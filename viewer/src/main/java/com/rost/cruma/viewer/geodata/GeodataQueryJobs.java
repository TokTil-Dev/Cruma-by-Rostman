package com.rost.cruma.viewer.geodata;

import com.rost.cruma.core.geodata.GeoBodyCheck;
import com.rost.cruma.core.geodata.GeoBodyCheck.Narrow;
import com.rost.cruma.core.geodata.GeoCellRef;
import com.rost.cruma.core.geodata.GeoDataAccess;
import com.rost.cruma.core.geodata.GeoEditDocument.CellKey;
import com.rost.cruma.core.geodata.GeoEditedGraphValidator;
import com.rost.cruma.core.geodata.GeoPoint;
import com.rost.cruma.core.geodata.GeoQueryEngine;
import com.rost.cruma.core.geodata.GeoQueryEngine.MoveResult;
import com.rost.cruma.core.geodata.GeoReachability;
import com.rost.cruma.core.geodata.PathNodeGraph;
import com.rost.cruma.core.geodata.PathNodeGraph.Audit;
import com.rost.cruma.core.geodata.PathNodeGraph.Metadata;
import com.rost.cruma.core.geodata.ServerPathFinder;
import com.rost.cruma.core.geodata.ServerPathFinder.Options;
import com.rost.cruma.core.geodata.ServerPathFinder.Replay;
import com.rost.cruma.core.geodata.ServerPathFinder.ReplayStatus;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.jetbrains.annotations.Nullable;

public final class GeodataQueryJobs implements AutoCloseable {
    private final ThreadPoolExecutor worker =
            new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1), task -> {
                var thread = new Thread(task, "geodata-query");
                thread.setDaemon(true);
                return thread;
            });
    private volatile long generation;
    private volatile boolean busy;
    private Future<?> pending;
    private Result result;
    private Request active;

    public enum Kind {
        MOVEMENT,
        SIGHT,
        REACHABILITY,
        PATH,
        REPLAY,
        GRAPH,
        EDITED_GRAPH,
        GRAPH_AUDIT,
        LEGS
    }

    public record Leg(
            GeoPoint from,
            GeoPoint to,
            Object result,
            List<GeoPoint> line,
            @Nullable Narrow narrow) {
        public Leg {
            line = List.copyOf(line);
        }

        public boolean reaches() {
            var reached = result instanceof MoveResult move
                    ? move.reachedDestination()
                    : result instanceof Replay replay && replay.status() == ReplayStatus.REACHED_DESTINATION;
            return reached && narrow == null;
        }

        public double length() {
            var total = 0.0;
            for (var index = 1; index < line.size(); index++) {
                total += Math.hypot(
                        line.get(index).x() - line.get(index - 1).x(),
                        line.get(index).y() - line.get(index - 1).y());
            }
            return total;
        }
    }

    public record Legs(List<Leg> legs, int radius) {
        public Legs {
            legs = List.copyOf(legs);
        }
    }

    public record Result(
            Kind kind,
            String sourceId,
            long revision,
            GeoPoint a,
            GeoPoint b,
            Object forward,
            Object reverse,
            String error,
            long elapsedNanos) {}

    public record GraphAudit(Metadata metadata, Audit audit) {}

    public synchronized void submit(Kind kind, GeoDataAccess data, GeoPoint a, GeoPoint b, int radius) {
        submit(kind, data, a, b, radius, null, 400, false);
    }

    public synchronized void submit(
            Kind kind,
            GeoDataAccess data,
            GeoPoint a,
            GeoPoint b,
            int radius,
            Path graphDirectory,
            int maximumVisits,
            boolean graphStale) {
        submit(kind, data, a, b, radius, graphDirectory, maximumVisits, graphStale, 100_000, 5000);
    }

    public synchronized void submit(
            Kind kind,
            GeoDataAccess data,
            GeoPoint a,
            GeoPoint b,
            int radius,
            Path graphDirectory,
            int maximumVisits,
            boolean graphStale,
            int reachabilityNodes,
            long reachabilityMillis) {
        submit(
                kind,
                data,
                a,
                b,
                radius,
                graphDirectory,
                maximumVisits,
                graphStale,
                reachabilityNodes,
                reachabilityMillis,
                null,
                null);
    }

    public synchronized void submitReachability(
            GeoDataAccess data,
            GeoCellRef seed,
            GeoPoint a,
            GeoPoint b,
            int radius,
            int maximumNodes,
            long maximumMillis) {
        Objects.requireNonNull(seed, "A pinned layer is required");
        submit(Kind.REACHABILITY, data, a, b, radius, null, 400, false, maximumNodes, maximumMillis, null, seed);
    }

    public synchronized void submitEditedGraph(
            GeoDataAccess original,
            GeoDataAccess preview,
            Set<CellKey> changes,
            GeoPoint center,
            GeoPoint b,
            Path graphDirectory,
            int radius,
            int maximumEdges,
            long maximumMillis) {
        submit(
                Kind.EDITED_GRAPH,
                preview,
                center,
                b,
                radius,
                graphDirectory,
                400,
                true,
                100_000,
                5000,
                new EditValidation(original, Set.copyOf(changes), maximumEdges, maximumMillis),
                null);
    }

    public synchronized void submitLegs(
            Kind kind,
            GeoDataAccess data,
            List<GeoPoint> points,
            @Nullable Path graphDirectory,
            int maximumVisits,
            boolean graphStale,
            int radius) {
        if (points.size() < 2 || kind != Kind.MOVEMENT && kind != Kind.REPLAY) {
            throw new IllegalArgumentException("A route needs two points and Straight or Path");
        }
        cancel();
        var request = generation;
        var frozen = List.copyOf(points);
        active = new Request(data.getSourceId(), data.revision(), frozen.get(0), frozen.get(1), null);
        busy = true;
        pending = worker.submit(() -> {
            var start = System.nanoTime();
            BooleanSupplier cancelled = () -> Thread.currentThread().isInterrupted() || generation != request;
            Result next;
            try {
                var engine = new GeoQueryEngine(data);
                var limits = new GeoQueryEngine.Limits(65_536, 8192, cancelled);
                var legs = new ArrayList<Leg>();
                PathNodeGraph graph = null;
                try {
                    if (kind == Kind.REPLAY) {
                        if (graphDirectory == null) {
                            throw new IOException("Choose a source with pathnode.idx and pathnode.bin.");
                        }
                        graph = PathNodeGraph.open(graphDirectory, cancelled);
                    }
                    for (var index = 1; index < frozen.size() && !cancelled.getAsBoolean(); index++) {
                        var from = frozen.get(index - 1);
                        var to = frozen.get(index);
                        Object leg;
                        var line = new ArrayList<GeoPoint>();
                        if (graph == null) {
                            var move = engine.moveStraight(from, to, 8192, limits);
                            move.samples().forEach(sample -> line.add(sample.position()));
                            leg = move;
                        } else {
                            var replay = new ServerPathFinder(graph, engine, data)
                                    .replay(from, to, new Options(maximumVisits, 8192, graphStale, cancelled), 128);
                            line.addAll(replay.waypoints());
                            leg = replay;
                        }
                        legs.add(new Leg(from, to, leg, line, GeoBodyCheck.check(data, line, radius, cancelled)));
                    }
                } finally {
                    if (graph != null) {
                        graph.close();
                    }
                }
                next = new Result(
                        Kind.LEGS,
                        data.getSourceId(),
                        data.revision(),
                        frozen.get(0),
                        frozen.get(1),
                        new Legs(legs, radius),
                        null,
                        "",
                        System.nanoTime() - start);
            } catch (IOException | RuntimeException failure) {
                next = new Result(
                        Kind.LEGS,
                        data.getSourceId(),
                        data.revision(),
                        frozen.get(0),
                        frozen.get(1),
                        null,
                        null,
                        failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage(),
                        System.nanoTime() - start);
            }
            synchronized (this) {
                if (!cancelled.getAsBoolean()) {
                    result = next;
                    busy = false;
                }
            }
        });
    }

    public synchronized Result poll(GeoDataAccess data, GeoPoint a, GeoPoint b) {
        return poll(data, a, b, active == null ? null : active.seed());
    }

    public synchronized Result poll(GeoDataAccess data, GeoPoint a, GeoPoint b, GeoCellRef seed) {
        if (active != null
                && (data == null
                        || active.revision() != data.revision()
                        || !active.sourceId().equals(data.getSourceId())
                        || !Objects.equals(active.a(), a)
                        || !Objects.equals(active.b(), b)
                        || active.seed() != null && !active.seed().equals(seed))) {
            cancel();
        }
        return result;
    }

    public boolean isBusy() {
        return busy;
    }

    public synchronized void cancel() {
        generation++;
        if (pending != null) {
            pending.cancel(true);
            pending = null;
        }
        worker.getQueue().clear();
        result = null;
        active = null;
        busy = false;
    }

    @Override
    public synchronized void close() {
        cancel();
        worker.shutdownNow();
    }

    private record Request(String sourceId, long revision, GeoPoint a, GeoPoint b, GeoCellRef seed) {}

    private record EditValidation(GeoDataAccess original, Set<CellKey> changes, int maximumEdges, long maximumMillis) {}

    private synchronized void submit(
            Kind kind,
            GeoDataAccess data,
            GeoPoint a,
            GeoPoint b,
            int radius,
            Path graphDirectory,
            int maximumVisits,
            boolean graphStale,
            int reachabilityNodes,
            long reachabilityMillis,
            EditValidation validation,
            GeoCellRef reachabilitySeed) {
        if (reachabilityNodes < 1
                || reachabilityNodes > 1_000_000
                || reachabilityMillis < 1
                || reachabilityMillis > 600_000) {
            throw new IllegalArgumentException("Invalid reachability budget");
        }
        cancel();
        var request = generation;
        active = new Request(data.getSourceId(), data.revision(), a, b, reachabilitySeed);
        busy = true;
        pending = worker.submit(() -> {
            var start = System.nanoTime();
            BooleanSupplier cancelled = () -> Thread.currentThread().isInterrupted() || generation != request;
            Result next;
            try {
                var engine = new GeoQueryEngine(data);
                var limits = new GeoQueryEngine.Limits(65_536, 8192, cancelled);
                Object forward;
                Object reverse = null;
                switch (kind) {
                    case MOVEMENT -> {
                        forward = engine.moveStraight(a, b, 8192, limits);
                        reverse = engine.moveStraight(b, a, 8192, limits);
                    }
                    case SIGHT -> {
                        forward = engine.checkSight(a, b, limits);
                        reverse = engine.checkSight(b, a, limits);
                    }
                    case REACHABILITY -> {
                        var reachability = new GeoReachability(data);
                        var budget =
                                new GeoReachability.Limits(radius, reachabilityNodes, reachabilityMillis, cancelled);
                        forward = reachabilitySeed == null
                                ? reachability.explore(a, budget)
                                : reachability.explore(reachabilitySeed, budget);
                    }
                    case PATH, REPLAY, GRAPH, EDITED_GRAPH, GRAPH_AUDIT -> {
                        if (graphDirectory == null) {
                            throw new IOException("Choose a source with pathnode.idx and pathnode.bin.");
                        }
                        try (var graph = PathNodeGraph.open(graphDirectory, cancelled)) {
                            var finder = new ServerPathFinder(graph, engine, data);
                            var options = new Options(maximumVisits, 8192, graphStale, cancelled);
                            if (kind == Kind.GRAPH_AUDIT) {
                                forward = new GraphAudit(graph.getMetadata(), graph.audit(cancelled));
                            } else if (kind == Kind.EDITED_GRAPH) {
                                if (validation == null) {
                                    throw new IOException(
                                            "Edited graph checks require the original source and changed cells.");
                                }
                                forward = GeoEditedGraphValidator.validate(
                                        graph,
                                        validation.original(),
                                        data,
                                        validation.changes(),
                                        a,
                                        new GeoEditedGraphValidator.Limits(
                                                radius,
                                                8192,
                                                validation.maximumEdges(),
                                                validation.maximumMillis(),
                                                cancelled));
                            } else if (kind == Kind.GRAPH) {
                                forward = GeoGraphView.read(graph, a, radius, 2048, cancelled);
                            } else if (kind == Kind.PATH) {
                                forward = finder.findNext(a, b, options);
                                reverse = finder.findNext(b, a, options);
                            } else {
                                forward = finder.replay(a, b, options, 128);
                                reverse = finder.replay(b, a, options, 128);
                            }
                        }
                    }
                    default -> throw new IllegalStateException("Unknown geodata query");
                }
                next = new Result(
                        kind,
                        data.getSourceId(),
                        data.revision(),
                        a,
                        b,
                        forward,
                        reverse,
                        "",
                        System.nanoTime() - start);
            } catch (IOException | RuntimeException failure) {
                next = new Result(
                        kind,
                        data.getSourceId(),
                        data.revision(),
                        a,
                        b,
                        null,
                        null,
                        failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage(),
                        System.nanoTime() - start);
            }
            synchronized (this) {
                if (!cancelled.getAsBoolean()) {
                    result = next;
                    busy = false;
                }
            }
        });
    }
}
