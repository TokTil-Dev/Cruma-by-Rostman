package com.rost.cruma.viewer.geodata;

import com.rost.cruma.core.geodata.GeoCellRef;
import com.rost.cruma.core.geodata.GeoDataAccess;
import com.rost.cruma.core.geodata.GeoDataAccess.Availability;
import com.rost.cruma.core.geodata.GeoPoint;
import com.rost.cruma.core.geodata.GeoQueryEngine;
import com.rost.cruma.core.geodata.GeoQueryEngine.Completeness;
import com.rost.cruma.core.geodata.GeoQueryEngine.Failure;
import com.rost.cruma.core.geodata.GeoQueryEngine.Selection;
import com.rost.cruma.core.geodata.GeoReachability;
import com.rost.cruma.core.geodata.GeoReachability.Node;
import com.rost.cruma.core.geodata.PathNodeGraph;
import com.rost.cruma.core.geodata.ServerPathFinder;
import com.rost.cruma.core.geodata.ServerPathFinder.Options;
import com.rost.cruma.core.geodata.ServerPathFinder.ReplayStatus;
import com.rost.cruma.core.geodata.ServerPathFinder.Status;
import com.rost.cruma.viewer.geodata.GeoScenario.Endpoint;
import com.rost.cruma.viewer.geodata.GeoScenario.Expected;
import com.rost.cruma.viewer.geodata.GeoScenario.Fingerprints;
import com.rost.cruma.viewer.geodata.GeoScenario.Kind;
import com.rost.cruma.viewer.geodata.GeoScenario.Layer;
import com.rost.cruma.viewer.geodata.GeoScenario.Profile;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.function.BooleanSupplier;

public final class GeoScenarioRunner {
    private final SourceResolver sources;

    public GeoScenarioRunner(SourceResolver sources) {
        this.sources = Objects.requireNonNull(sources);
    }

    public enum Verdict {
        PASS,
        FAIL,
        UNAVAILABLE,
        UNSUPPORTED,
        SOURCE_CHANGED,
        ALGORITHM_CHANGED,
        INCOMPLETE,
        CANCELLED,
        NONDETERMINISTIC
    }

    @FunctionalInterface
    public interface SourceResolver {
        Context resolve(String alias);

        default Context resolve(GeoScenario scenario) {
            return resolve(scenario.sourceAlias());
        }
    }

    public record Context(GeoDataAccess data, PathNodeGraph graph, Fingerprints fingerprints, boolean graphStale) {
        public Context {
            Objects.requireNonNull(data);
            Objects.requireNonNull(fingerprints);
        }
    }

    public record Observation(
            boolean success,
            boolean complete,
            String status,
            GeoPoint finalPosition,
            double traveledDistance,
            String digest,
            Verdict interruption,
            Layer resolvedA,
            Layer resolvedB) {
        public Observation(
                boolean success,
                boolean complete,
                String status,
                GeoPoint finalPosition,
                double traveledDistance,
                String digest,
                Verdict interruption) {
            this(success, complete, status, finalPosition, traveledDistance, digest, interruption, null, null);
        }

        public Expected toExpectation(double tolerance) {
            return new Expected(
                    success,
                    complete,
                    status,
                    finalPosition,
                    traveledDistance,
                    digest,
                    tolerance,
                    resolvedA,
                    resolvedB);
        }
    }

    public record Result(
            String scenarioId, Verdict verdict, List<Observation> runs, String detail, boolean dataChanged) {
        public Result {
            runs = List.copyOf(runs);
        }
    }

    public static Observation observe(
            Context context, GeoPoint a, GeoPoint b, Profile profile, BooleanSupplier cancelled) {
        return observe(context, new Endpoint(a, null), b == null ? null : new Endpoint(b, null), profile, cancelled);
    }

    public static Observation observe(
            Context context, Endpoint a, Endpoint b, Profile profile, BooleanSupplier cancelled) {
        if (!"GROUND".equals(profile.movementMode())) {
            return new Observation(false, false, "UNSUPPORTED", null, 0, "", Verdict.UNSUPPORTED);
        }
        var seed = profile.kind() == Kind.REACHABILITY ? a.selectedLayer() : null;
        if (seed != null && !matchesSelection(context.data(), a)) {
            return new Observation(false, false, "SELECTED_LAYER_CHANGED", null, 0, "", Verdict.SOURCE_CHANGED);
        }
        var observation = observeQuery(context, a.point(), b == null ? null : b.point(), seed, profile, cancelled);
        var engine = new GeoQueryEngine(context.data());
        return new Observation(
                observation.success(),
                observation.complete(),
                observation.status(),
                observation.finalPosition(),
                observation.traveledDistance(),
                observation.digest(),
                observation.interruption(),
                seed == null ? toLayer(engine.findBaseCell(a.point())) : seed,
                b == null ? null : toLayer(engine.findBaseCell(b.point())));
    }

    public Result run(GeoScenario scenario, int repetitions, BooleanSupplier cancelled) {
        return run(scenario, repetitions, false, cancelled);
    }

    public Result run(GeoScenario scenario, int repetitions, boolean allowChangedSource, BooleanSupplier cancelled) {
        if (repetitions < 1 || repetitions > 100) {
            throw new IllegalArgumentException("Repetitions must be between 1 and 100");
        }
        if (!"GROUND".equals(scenario.profile().movementMode())) {
            return newResult(scenario, Verdict.UNSUPPORTED, List.of(), "Movement profile is not supported");
        }
        if (cancelled.getAsBoolean()) {
            return newResult(scenario, Verdict.CANCELLED, List.of(), "Scenario cancelled");
        }
        var context = sources.resolve(scenario);
        if (context == null) {
            return newResult(scenario, Verdict.UNAVAILABLE, List.of(), "Source alias is not configured locally");
        }
        if (!scenario.fingerprints().algorithm().equals(context.fingerprints().algorithm())
                || !GeoScenario.ALGORITHM.equals(context.fingerprints().algorithm())) {
            return newResult(scenario, Verdict.ALGORITHM_CHANGED, List.of(), "Algorithm fingerprint changed");
        }
        var dataChanged = false;
        for (var region : scenario.fingerprints().regions().entrySet()) {
            if (!context.fingerprints().regions().containsKey(region.getKey())) {
                return newResult(scenario, Verdict.UNAVAILABLE, List.of(), "A required region is not loaded");
            }
            if (!region.getValue().equals(context.fingerprints().regions().get(region.getKey()))) {
                dataChanged = true;
            }
        }
        if (scenario.fingerprints().regions().isEmpty()
                        && !scenario.fingerprints()
                                .data()
                                .equals(context.fingerprints().data())
                || scenario.profile().kind() == Kind.PATH
                        && (!scenario.fingerprints()
                                        .graph()
                                        .equals(context.fingerprints().graph())
                                || context.graphStale())) {
            dataChanged = true;
        }
        if (!matchesSelection(context.data(), scenario.a()) || !matchesSelection(context.data(), scenario.b())) {
            dataChanged = true;
        }
        if (dataChanged && !allowChangedSource) {
            return newResult(scenario, Verdict.SOURCE_CHANGED, List.of(), "Data, path graph or selected layer changed");
        }
        var runs = new ArrayList<Observation>();
        var revision = context.data().revision();
        var sourceId = context.data().getSourceId();
        for (var i = 0; i < repetitions; i++) {
            var observation = observe(context, scenario.a(), scenario.b(), scenario.profile(), cancelled);
            runs.add(observation);
            if (revision != context.data().revision()
                    || !sourceId.equals(context.data().getSourceId())) {
                return newResult(scenario, Verdict.SOURCE_CHANGED, runs, "Data changed during repeated queries");
            }
            if (observation.interruption() != null) {
                return newResult(
                        scenario,
                        observation.interruption(),
                        runs,
                        "Scenario did not produce a complete result",
                        dataChanged);
            }
            if (!observation.complete()) {
                return newResult(
                        scenario,
                        Verdict.INCOMPLETE,
                        runs,
                        "Limits or missing data prevent a complete result",
                        dataChanged);
            }
            if (i > 0 && !runs.getFirst().equals(observation)) {
                return newResult(
                        scenario,
                        Verdict.NONDETERMINISTIC,
                        runs,
                        "Repeated queries returned different results",
                        dataChanged);
            }
        }
        var matches = matches(scenario.expected(), runs.getFirst());
        return newResult(
                scenario,
                matches ? Verdict.PASS : Verdict.FAIL,
                runs,
                matches ? "Expected result reproduced" : "Result differs from the saved expectation",
                dataChanged);
    }

    private static Layer toLayer(Selection selection) {
        return selection == null
                ? null
                : new Layer(
                        selection.cell().x(),
                        selection.cell().y(),
                        selection.layer().index(),
                        selection.layer().height());
    }

    private static Observation observeQuery(
            Context context, GeoPoint a, GeoPoint b, Layer seed, Profile profile, BooleanSupplier cancelled) {
        var engine = new GeoQueryEngine(context.data());
        var limits = new GeoQueryEngine.Limits(profile.maxSteps(), profile.maxTrace(), cancelled);
        switch (profile.kind()) {
            case MOVEMENT -> {
                var move = engine.moveStraight(a, b, profile.distance(), limits);
                var status = move.failure() == Failure.NONE
                        ? move.reachedDestination() ? "REACHED" : "DISTANCE_EXHAUSTED"
                        : move.failure().name();
                var signature = new StringBuilder(status)
                        .append('|')
                        .append(move.finalPosition())
                        .append('|')
                        .append(move.traveledDistance());
                for (var edge : move.trace()) {
                    signature
                            .append('|')
                            .append(edge.direction())
                            .append(':')
                            .append(edge.target())
                            .append(':')
                            .append(edge.accepted());
                }
                return newObservation(
                        move.success(),
                        move.completeness(),
                        status,
                        move.finalPosition(),
                        move.traveledDistance(),
                        signature.toString());
            }
            case SIGHT -> {
                var sight = engine.checkSight(a, b, limits);
                var status = sight.visible()
                        ? "VISIBLE_GEODATA_ONLY"
                        : sight.failure().name();
                var signature = new StringBuilder(status);
                for (var edge : sight.trace()) {
                    signature
                            .append('|')
                            .append(edge.direction())
                            .append(':')
                            .append(edge.target())
                            .append(':')
                            .append(edge.accepted());
                }
                return newObservation(sight.visible(), sight.completeness(), status, null, 0, signature.toString());
            }
            case REACHABILITY -> {
                var reachability = new GeoReachability(context.data());
                var reachLimits = new GeoReachability.Limits(
                        profile.radius(), profile.maxNodes(), profile.maxMillis(), cancelled);
                var reach = seed == null
                        ? reachability.explore(a, reachLimits)
                        : reachability.explore(
                                new GeoCellRef(
                                        context.data().getSourceId(),
                                        context.data().revision(),
                                        seed.x(),
                                        seed.y(),
                                        seed.index()),
                                reachLimits);
                var nodes = new ArrayList<>(reach.nodes().entrySet());
                nodes.sort(Entry.comparingByKey(Comparator.comparingInt(Node::x)
                        .thenComparingInt(Node::y)
                        .thenComparingInt(Node::layer)
                        .thenComparingInt(Node::height)));
                var signature = new StringBuilder();
                for (var entry : nodes) {
                    signature
                            .append(entry.getKey())
                            .append(':')
                            .append(entry.getValue())
                            .append('\n');
                }
                return newObservation(
                        !nodes.isEmpty(), reach.completeness(), "REACHABILITY", null, 0, signature.toString());
            }
            case PATH -> {
                var path = new ServerPathFinder(context.graph(), engine, context.data())
                        .replay(
                                a,
                                b,
                                new Options(
                                        profile.maxPathVisits(), profile.maxTrace(), context.graphStale(), cancelled),
                                profile.maxWaypoints());
                var last = path.calls().isEmpty() ? null : path.calls().getLast();
                var state = last == null ? Status.SOURCE_CHANGED : last.status();
                var complete = path.status() == ReplayStatus.REACHED_DESTINATION
                        || path.status() == ReplayStatus.STOPPED
                                && switch (state) {
                                    case NO_START_ANCHOR, NO_DESTINATION_ANCHOR, NO_PATH -> true;
                                    default -> false;
                                };
                var interruption = switch (state) {
                    case CANCELLED -> Verdict.CANCELLED;
                    case SOURCE_CHANGED, STALE_GRAPH -> Verdict.SOURCE_CHANGED;
                    case GRAPH_MISSING, INVALID_GRAPH, DATA_UNAVAILABLE -> Verdict.UNAVAILABLE;
                    default -> complete ? null : Verdict.INCOMPLETE;
                };
                var signature = new StringBuilder(path.status().name());
                var distance = 0.0;
                GeoPoint previous = null;
                for (var point : path.waypoints()) {
                    signature.append('|').append(point);
                    if (previous != null) {
                        distance += previous.distance(point);
                    }
                    previous = point;
                }
                var status = path.status() == ReplayStatus.STOPPED
                        ? state.name()
                        : path.status().name();
                return new Observation(
                        path.status() == ReplayStatus.REACHED_DESTINATION,
                        complete,
                        status,
                        previous,
                        distance,
                        GeoScenarioFingerprints.hash(signature.toString()),
                        interruption);
            }
            default -> throw new IllegalStateException("Unknown scenario query");
        }
    }

    private static Observation newObservation(
            boolean success,
            Completeness completeness,
            String status,
            GeoPoint point,
            double distance,
            String signature) {
        var interruption = switch (completeness) {
            case COMPLETE -> null;
            case LIMIT -> Verdict.INCOMPLETE;
            case DATA_UNAVAILABLE -> Verdict.UNAVAILABLE;
            case CANCELLED -> Verdict.CANCELLED;
            case SOURCE_CHANGED -> Verdict.SOURCE_CHANGED;
        };
        return new Observation(
                success,
                completeness == Completeness.COMPLETE,
                status,
                point,
                distance,
                GeoScenarioFingerprints.hash(signature),
                interruption);
    }

    private static boolean matchesSelection(GeoDataAccess data, Endpoint endpoint) {
        if (endpoint == null || endpoint.selectedLayer() == null) {
            return true;
        }
        var selected = endpoint.selectedLayer();
        var cell = data.cellAt(selected.x(), selected.y());
        if (cell.availability() != Availability.AVAILABLE) {
            return false;
        }
        for (var layer : cell.layers()) {
            if (layer.index() == selected.index() && layer.height() == selected.height()) {
                return true;
            }
        }
        return false;
    }

    private static boolean matches(Expected expected, Observation actual) {
        if (expected.resolvedA() != null && !expected.resolvedA().equals(actual.resolvedA())
                || expected.resolvedB() != null && !expected.resolvedB().equals(actual.resolvedB())) {
            return false;
        }
        if (expected.success() != actual.success()
                || expected.complete() != actual.complete()
                || !expected.status().equals(actual.status())
                || Math.abs(expected.traveledDistance() - actual.traveledDistance()) > expected.tolerance()
                || !expected.digest().isEmpty() && !expected.digest().equals(actual.digest())) {
            return false;
        }
        if (expected.finalPosition() == null || actual.finalPosition() == null) {
            return expected.finalPosition() == actual.finalPosition();
        }
        return expected.finalPosition().distance(actual.finalPosition()) <= expected.tolerance();
    }

    private static Result newResult(GeoScenario scenario, Verdict verdict, List<Observation> runs, String detail) {
        return newResult(scenario, verdict, runs, detail, verdict == Verdict.SOURCE_CHANGED);
    }

    private static Result newResult(
            GeoScenario scenario, Verdict verdict, List<Observation> runs, String detail, boolean dataChanged) {
        return new Result(scenario.id(), verdict, runs, detail, dataChanged);
    }
}
