package com.conveyal.r5.analyst;

import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClient;
import com.amazonaws.services.sqs.model.MessageAttributeValue;
import com.amazonaws.services.sqs.model.SendMessageRequest;
import com.conveyal.r5.analyst.cluster.GridRequest;
import com.conveyal.r5.analyst.cluster.Origin;
import com.conveyal.r5.analyst.cluster.TaskStatistics;
import com.conveyal.r5.api.util.LegMode;
import com.conveyal.r5.profile.FastRaptorWorker;
import com.conveyal.r5.profile.PerTargetPropagater;
import com.conveyal.r5.profile.Propagater;
import com.conveyal.r5.profile.RaptorWorker;
import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.streets.LinkedPointSet;
import com.conveyal.r5.streets.StreetRouter;
import com.conveyal.r5.transit.TransportNetwork;
import com.google.common.io.LittleEndianDataOutputStream;
import gnu.trove.iterator.TIntIntIterator;
import gnu.trove.map.TIntIntMap;
import org.apache.commons.math3.random.MersenneTwister;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Base64;
import java.util.BitSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.DoubleStream;
import java.util.stream.Stream;

/**
 * Computes an accessibility indicator at a single cell in a Web Mercator grid, using destination densities from
 * a Web Mercator density grid. Both grids must be at the same zoom level. The accessibility indicator is calculated
 * separately for each iteration of the range RAPTOR algorithm (each departure minute and randomization of the schedules
 * for the frequency-based routes) and all of these different values of the indicator are retained to allow
 * probabilistic scenario comparison. This does freeze the travel time cutoff and destination grid in the interest of
 * keeping the results down to an acceptable size. The results are placed on an Amazon SQS queue for collation by
 * a GridResultConsumer and a GridResultAssembler.
 *
 * These requests are enqueued by the frontend one for each origin in a regional analysis.
 */
public class GridComputer  {
    private static final Logger LOG = LoggerFactory.getLogger(GridComputer.class);

    /** The number of iterations used to bootstrap the sampling distribution of the percentiles */
    public static final int BOOTSTRAP_ITERATIONS = 1000;

    /** SQS client. TODO: async? */
    private static final AmazonSQS sqs = new AmazonSQSClient();

    private static final Base64.Encoder base64 = Base64.getEncoder();

    private final GridCache gridCache;

    public final GridRequest request;

    private static WebMercatorGridPointSetCache pointSetCache = new WebMercatorGridPointSetCache();

    public final TransportNetwork network;

    public GridComputer(GridRequest request, GridCache gridCache, TransportNetwork network) {
        this.request = request;
        this.gridCache = gridCache;
        this.network = network;
    }

    public void run() throws IOException {
        final Grid grid = gridCache.get(request.grid);

        // ensure they both have the same zoom level
        if (request.zoom != grid.zoom) throw new IllegalArgumentException("grid zooms do not match!");

        // use the middle of the grid cell
        request.request.fromLat = Grid.pixelToLat(request.north + request.y + 0.5, request.zoom);
        request.request.fromLon = Grid.pixelToLon(request.west + request.x + 0.5, request.zoom);

        // Run the raptor algorithm to get times at each destination for each iteration

        // first, find the access stops
        StreetMode mode;
        if (request.request.accessModes.contains(LegMode.CAR)) mode = StreetMode.CAR;
        else if (request.request.accessModes.contains(LegMode.BICYCLE)) mode = StreetMode.BICYCLE;
        else mode = StreetMode.WALK;

        LOG.info("Maximum number of rides: {}", request.request.maxRides);
        LOG.info("Maximum trip duration: {}", request.request.maxTripDurationMinutes);

        // Use the extent of the grid as the targets; this avoids having to convert between coordinate systems,
        // and avoids including thousands of extra points in the weeds where there happens to be a transit stop.
        WebMercatorGridPointSet targets = pointSetCache.get(grid);
        // TODO recast using egress mode
        final LinkedPointSet linkedTargets = targets.link(network.streetLayer, mode);

        StreetRouter sr = new StreetRouter(network.streetLayer);
        sr.distanceLimitMeters = 2000;
        sr.setOrigin(request.request.fromLat, request.request.fromLon);
        sr.dominanceVariable = StreetRouter.State.RoutingVariable.DISTANCE_MILLIMETERS;
        sr.route();

        TIntIntMap reachedStops = sr.getReachedStops();

        // convert millimeters to seconds
        int millimetersPerSecond = (int) (request.request.walkSpeed * 1000);
        for (TIntIntIterator it = reachedStops.iterator(); it.hasNext();) {
            it.advance();
            it.setValue(it.value() / millimetersPerSecond);
        }

        FastRaptorWorker router = new FastRaptorWorker(network.transitLayer, request.request, reachedStops);

        // Run the raptor algorithm
        int[][] timesAtStopsEachIteration = router.route();

        // compute bootstrap weights
        // the Mersenne Twister is a high-quality RNG well-suited to Monte Carlo situations
        MersenneTwister twister = new MersenneTwister();
        int[][] bootstrapWeights = new int[BOOTSTRAP_ITERATIONS + 1][router.nMinutes * router.monteCarloDrawsPerMinute];

        Arrays.fill(bootstrapWeights[0], 1); // equal weight to all observations (sample mean) for first sample

        for (int bootstrap = 1; bootstrap < bootstrapWeights.length; bootstrap++) {
            for (int draw = 0; draw < timesAtStopsEachIteration.length; draw++) {
                bootstrapWeights[bootstrap][twister.nextInt(timesAtStopsEachIteration.length)]++;
            }
        }

        // Do propagation
        int[] nonTransferTravelTimesToStops = linkedTargets.eval(sr::getTravelTimeToVertex).travelTimes;
        PerTargetPropagater propagater =
                new PerTargetPropagater(timesAtStopsEachIteration, nonTransferTravelTimesToStops, linkedTargets, request.request, request.cutoffMinutes * 60);

        // compute the percentiles
        double[] samples = new double[BOOTSTRAP_ITERATIONS + 1];

        propagater.propagate((target, times) -> {
            boolean foundAbove = false;
            boolean foundBelow = false;

            for (int time : times) {
                if (time > request.cutoffMinutes * 60) foundAbove = true;
                if (time < request.cutoffMinutes * 60) foundBelow = true;
            }

            int gridx = target % grid.width;
            int gridy = target / grid.width;

            if (foundAbove && foundBelow) {
                // This origin is sometimes reachable within the time window, do bootstrapping to determine
                // the distribution of how often
                BOOTSTRAP:
                for (int bootstrap = 0; bootstrap < BOOTSTRAP_ITERATIONS + 1; bootstrap++) {
                    int count = 0;
                    // include all departure minutes, but create a sample of the same size as the original
                    for (int iteration = 0; iteration < times.length; iteration++) {
                        if (times[iteration] < request.cutoffMinutes * 60)
                            count += bootstrapWeights[bootstrap][iteration];
                        if (count > timesAtStopsEachIteration.length / 2) {
                            samples[bootstrap] += grid.grid[gridx][gridy];
                            continue BOOTSTRAP;
                        }
                    }
                }
            } else if (foundBelow && !foundAbove) {
                // this destination is always reachable and will be included in all bootstrap samples, no need to do the
                // bootstrapping
                for (int i = 0; i < samples.length; i++) samples[i] += grid.grid[gridx][gridy];
            } // otherwise, this destination is never reachable, no need to do bootstrapping or increment samples
        });

        int[] intSamples = DoubleStream.of(samples).mapToInt(d -> (int) Math.round(d)).toArray();

        // now construct the output
        // these things are tiny, no problem storing in memory
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        new Origin(request, intSamples).write(baos);

        // send this origin to an SQS queue as a binary payload; it will be consumed by GridResultConsumer
        // and GridResultAssembler
        SendMessageRequest smr = new SendMessageRequest(request.outputQueue, base64.encodeToString(baos.toByteArray()));
        smr = smr.addMessageAttributesEntry("jobId", new MessageAttributeValue().withDataType("String").withStringValue(request.jobId));
        sqs.sendMessage(smr);
    }
}
