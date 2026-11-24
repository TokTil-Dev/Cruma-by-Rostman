package com.rost.cruma.viewer.agent.geo;

import static com.rost.cruma.viewer.agent.geo.GeoSteps.CELL;
import static com.rost.cruma.viewer.agent.geo.GeoSteps.MOST_GRAPH_RADIUS;
import static com.rost.cruma.viewer.agent.geo.GeoSteps.REGION;
import static com.rost.cruma.viewer.agent.geo.GeoSteps.around;
import static com.rost.cruma.viewer.agent.geo.GeoSteps.loaded;

import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.core.geodata.GeoDataAccess;
import com.rost.cruma.core.geodata.GeoDataSnapshot;
import com.rost.cruma.core.geodata.GeoTile;
import com.rost.cruma.viewer.agent.Step;
import com.rost.cruma.viewer.agent.ToolArgs;
import com.rost.cruma.viewer.agent.ToolProblem;
import com.rost.cruma.viewer.agent.ToolResult;
import com.rost.cruma.viewer.agent.ViewerContext;
import com.rost.cruma.viewer.geodata.GeoEditingSession;
import com.rost.cruma.viewer.geodata.GeoLivePathGraph;
import com.rost.cruma.viewer.geodata.GeoLivePathGraph.State;
import com.rost.cruma.viewer.render.GeodataState;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.jetbrains.annotations.Nullable;

final class GeoCheckTool {
    private static final long GRAPH_NANOS = TimeUnit.MINUTES.toNanos(5);
    private static final int MOST_STOPS = 6;
    private static final int MOST_BODY = 256;
    private static final int REACH_RADIUS = 1024;
    private static final int GRAPH_RADIUS = 512;

    private static final class EditedPath implements Step {
        private final GeoEditingSession session;
        private final Path folder;
        private final GeoDataAccess data;
        private final GeoDataSnapshot files;
        private final List<GeoSpot> spots;
        private final PathRun run;
        private final long revision;
        private final long deadline = System.nanoTime() + GRAPH_NANOS;
        private final GeoLivePathGraph live = new GeoLivePathGraph(0);
        private @Nullable Step running;

        EditedPath(
                GeoEditingSession session,
                Path folder,
                GeoDataAccess data,
                GeoDataSnapshot files,
                List<GeoSpot> spots,
                PathRun run) {
            this.session = session;
            this.folder = folder;
            this.data = data;
            this.files = files;
            this.spots = spots;
            this.run = run;
            revision = session.getDocument().revision();
        }

        @Override
        public @Nullable ToolResult step() throws ToolProblem {
            if (running != null) {
                return running.step();
            }
            if (session.getDocument().revision() != revision) {
                live.close();
                throw new ToolProblem("The geodata changed during the check. Check again.");
            }
            live.update(session);
            var status = live.getStatus();
            if (status.state() != State.READY && status.state() != State.FAILED) {
                if (System.nanoTime() > deadline) {
                    live.close();
                    throw new ToolProblem("The pathnode graph of the edits did not get ready in time.");
                }
                return null;
            }
            var graph = live.getGraphDirectory(revision);
            var covered = graph != null;
            for (var index = 1; covered && index < spots.size(); index++) {
                covered = status.covers(
                        spots.get(index - 1).point(data), spots.get(index).point(data));
            }
            if (covered) {
                var note = "This path follows a graph built from the edits of " + status.names() + ".";
                running = Step.onWorker(() -> {
                    try {
                        return ToolResult.data(run.run(data, graph, note));
                    } finally {
                        live.close();
                    }
                });
            } else {
                var why = status.state() == State.FAILED
                        ? "the graph of the edits failed: " + status.detail()
                        : "the graph of the edits does not hold every point";
                var note = "This path ignores the edits, because " + why
                        + ". It runs on the cells before them, with the folder's graph.";
                running = Step.onWorker(() -> {
                    try {
                        return ToolResult.data(run.run(files, folder, note));
                    } finally {
                        live.close();
                    }
                });
            }
            return running.step();
        }

        @Override
        public void cancel() {
            if (running != null) {
                running.cancel();
            }
            live.close();
        }
    }

    private final GeoSource source;

    GeoCheckTool(GeoSource source) {
        this.source = source;
    }

    Step check(ToolArgs args, ViewerContext viewer) throws ToolProblem {
        var state = viewer.scene().geodata;
        var folder =
                GeoSource.folder(args.getText("folder"), state, viewer.agent().isOwned());
        var kind = args.getText("kind", "move");
        var fromText = args.getText("target", "camera");
        var from = GeoSpot.of(viewer.locate(fromText), fromText);
        switch (kind) {
            case "files" -> {
                return Step.onWorker(() -> ToolResult.data(GeoRoutes.files(folder, GeoSteps::isInterrupted)));
            }
            case "reach", "graph" -> {
                return nearby(args, viewer, folder, from, kind);
            }
            case "move", "sight", "path" -> {}
            default -> throw new ToolProblem("kind must be move, sight, path, reach, graph or files.");
        }
        var toText = args.getText("to");
        if (toText == null) {
            throw new ToolProblem("kind " + kind + " needs to, where it ends.");
        }
        var to = GeoSpot.of(viewer.locate(toText), toText);
        var visits = Math.clamp(args.getInteger("visits", GeoChecks.SERVER_VISITS), 1, 100_000);
        var stops = args.list("stops");
        var body = args.getInteger("body", 0);
        if (stops.size() > MOST_STOPS) {
            throw new ToolProblem("A route takes at most " + MOST_STOPS + " stops, C to H.");
        }
        if (body < 0 || body > MOST_BODY) {
            throw new ToolProblem("body must be from 0 to " + MOST_BODY + " units.");
        }
        var spots = new ArrayList<GeoSpot>(List.of(from, to));
        for (var stop : stops) {
            spots.add(GeoSpot.of(viewer.locate(stop), stop));
        }
        var route = !stops.isEmpty() || body > 0;
        if (route && kind.equals("sight")) {
            throw new ToolProblem("stops and body work with move and path, not with sight.");
        }
        var needs = around(spots, kind.equals("path") ? pathMargin(spots) : CELL);
        return loaded(source.load(GeoSource.wish(folder, needs, state)), files -> {
            GeoSource.requireRegion(files);
            var data = GeoSource.view(state, files);
            if (route) {
                return kind.equals("path")
                        ? path(
                                state,
                                folder,
                                data,
                                files,
                                spots,
                                (cells, graph, note) -> GeoRoutes.route(
                                        cells, graph, spots, visits, body, note, GeoSteps::isInterrupted))
                        : Step.onWorker(() -> ToolResult.data(
                                GeoRoutes.route(data, null, spots, visits, body, null, GeoSteps::isInterrupted)));
            }
            return switch (kind) {
                case "move" ->
                    Step.onWorker(() -> ToolResult.data(GeoChecks.move(data, from, to, GeoSteps::isInterrupted)));
                case "sight" ->
                    Step.onWorker(() -> ToolResult.data(GeoChecks.sight(data, from, to, GeoSteps::isInterrupted)));
                default ->
                    path(
                            state,
                            folder,
                            data,
                            files,
                            spots,
                            (cells, graph, note) ->
                                    GeoChecks.path(cells, graph, from, to, visits, note, GeoSteps::isInterrupted));
            };
        });
    }

    private Step nearby(ToolArgs args, ViewerContext viewer, Path folder, GeoSpot from, String kind)
            throws ToolProblem {
        var state = viewer.scene().geodata;
        var reach = kind.equals("reach");
        var radius = args.getInteger("radius", reach ? REACH_RADIUS : GRAPH_RADIUS);
        var most = reach ? REGION : MOST_GRAPH_RADIUS;
        if (radius < CELL || radius > most) {
            throw new ToolProblem("radius must be from " + CELL + " to " + most + " units for " + kind + ".");
        }
        var connect = args.getText("connect", "both");
        if (!GeoRoutes.getConnectWords().contains(connect)) {
            throw new ToolProblem("connect must be both, from, to or either.");
        }
        return loaded(source.load(GeoSource.wish(folder, around(List.of(from), radius + CELL), state)), files -> {
            GeoSource.requireRegion(files);
            var data = GeoSource.view(state, files);
            if (reach) {
                return Step.onWorker(
                        () -> ToolResult.data(GeoRoutes.reach(data, from, radius, connect, GeoSteps::isInterrupted)));
            }
            var edited = state.editing != null && state.editing.isModified();
            return Step.onWorker(() -> {
                var answer = GeoRoutes.graph(data, folder, from, radius, GeoSteps::isInterrupted);
                if (edited) {
                    answer.put(
                            "edits",
                            "This is the stored graph. It does not hold the edits, which kind path follows on a graph"
                                    + " of its own.");
                }
                return ToolResult.data(answer);
            });
        });
    }

    private static int pathMargin(List<GeoSpot> spots) {
        var low = GeoWords.tile(spots.getFirst().x(), spots.getFirst().y());
        var high = low;
        for (var spot : spots) {
            var tile = GeoWords.tile(spot.x(), spot.y());
            low = new GeoTile(Math.min(low.x(), tile.x()), Math.min(low.y(), tile.y()));
            high = new GeoTile(Math.max(high.x(), tile.x()), Math.max(high.y(), tile.y()));
        }
        var columns = (long) high.x() - low.x() + 3;
        var rows = (long) high.y() - low.y() + 3;
        return columns * rows <= GeoSource.MOST_REGIONS ? REGION : CELL;
    }

    @FunctionalInterface
    private interface PathRun {
        JSONObject run(GeoDataAccess data, Path graph, @Nullable String note) throws ToolProblem;
    }

    private static Step path(
            GeodataState state,
            Path folder,
            GeoDataAccess data,
            GeoDataSnapshot files,
            List<GeoSpot> spots,
            PathRun run) {
        var session = state.editing;
        if (session == null || !session.isModified()) {
            return Step.onWorker(() -> ToolResult.data(run.run(data, folder, null)));
        }
        return new EditedPath(session, folder, data, files, spots, run);
    }
}
