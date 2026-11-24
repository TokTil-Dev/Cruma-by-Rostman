package com.rost.cruma.viewer.agent.geo;

import static com.rost.cruma.viewer.agent.geo.GeoSteps.afterHold;
import static com.rost.cruma.viewer.agent.geo.GeoSteps.afterWorker;
import static com.rost.cruma.viewer.agent.geo.GeoSteps.grown;
import static com.rost.cruma.viewer.agent.geo.GeoSteps.loaded;
import static com.rost.cruma.viewer.agent.geo.GeoSteps.otherFolder;
import static com.rost.cruma.viewer.agent.geo.GeoSteps.regionBounds;
import static com.rost.cruma.viewer.agent.geo.GeoSteps.scanArea;
import static com.rost.cruma.viewer.agent.geo.GeoSteps.startPoints;

import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.core.geodata.GeoDataAccess.Layer;
import com.rost.cruma.core.geodata.GeoDataDiff.Bounds;
import com.rost.cruma.core.geodata.GeoDataSnapshot;
import com.rost.cruma.core.geodata.GeoDataSnapshot.RegionState;
import com.rost.cruma.core.geodata.GeoEditDocument.CellKey;
import com.rost.cruma.core.geodata.GeoQueryEngine;
import com.rost.cruma.core.geodata.GeoTile;
import com.rost.cruma.viewer.agent.Step;
import com.rost.cruma.viewer.agent.Target;
import com.rost.cruma.viewer.agent.Target.Kind;
import com.rost.cruma.viewer.agent.ToolArgs;
import com.rost.cruma.viewer.agent.ToolProblem;
import com.rost.cruma.viewer.agent.ToolResult;
import com.rost.cruma.viewer.agent.ViewerContext;
import com.rost.cruma.viewer.agent.geo.GeoEdits.Operation;
import com.rost.cruma.viewer.agent.geo.GeoEdits.Order;
import com.rost.cruma.viewer.agent.geo.GeoScans.Chosen;
import com.rost.cruma.viewer.agent.geo.GeoSource.Wish;
import com.rost.cruma.viewer.agent.geo.GeoSteps.Next;
import com.rost.cruma.viewer.geodata.GeoClientRebuild;
import com.rost.cruma.viewer.geodata.GeoEditingSession.Clip;
import com.rost.cruma.viewer.render.GeodataState;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

final class GeoEditTool {
    private @Nullable Clip clip;
    private @Nullable GeoClientRebuild rebuilder;
    private final GeoSource source;
    private final GeoSource folderB;

    GeoEditTool(GeoSource source, GeoSource folderB) {
        this.source = source;
        this.folderB = folderB;
    }

    Step edit(ToolArgs args, ViewerContext viewer) throws ToolProblem {
        var state = viewer.scene().geodata;
        var folder =
                GeoSource.folder(args.getText("folder"), state, viewer.agent().isOwned());
        var text = args.getText("target", "camera");
        var target = viewer.locate(text);
        var toText = args.getText("to");
        var to = toText == null ? null : GeoSpot.of(viewer.locate(toText), toText);
        var order = GeoEdits.order(args, GeoSpot.of(target, text), to, target.kind() == Kind.TILE);
        if (order.operation() == Operation.FIX) {
            return fix(args, viewer, folder, target, order);
        }
        if (order.operation() == Operation.TAKE) {
            return take(args, viewer, folder, order);
        }
        return loaded(source.load(GeoSource.wish(folder, order.needs(), state)), files -> {
            if (order.operation() == Operation.COPY) {
                return copy(state, files, order);
            }
            if (order.operation() == Operation.REBUILD) {
                return afterBuild(viewer, order.tile(), built -> change(viewer, files, order, built));
            }
            return change(viewer, files, order, null);
        });
    }

    private record Planned(Chosen chosen, Map<CellKey, List<Layer>> cells) {}

    private Step fix(ToolArgs args, ViewerContext viewer, Path folder, Target target, Order order) throws ToolProblem {
        var state = viewer.scene().geodata;
        var word = args.getText("fix");
        var named = word == null ? null : GeoScans.fix(word);
        var kinds = args.list("kinds");
        GeoScans.choose(List.of(), kinds, named, null);
        var oneCell = order.radius() == 0 && order.to() == null;
        var area = oneCell && target.kind() == Kind.TILE ? regionBounds(order.tile()) : scanArea(args, viewer, target);
        GeoScans.requireScanSize(area);
        var cell = oneCell && target.kind() != Kind.TILE
                ? new CellKey(
                        GeoQueryEngine.cellMinimum(order.at().x()),
                        GeoQueryEngine.cellMinimum(order.at().y()))
                : null;
        return loaded(source.load(GeoSource.wish(folder, grown(area), state)), files -> {
            GeoSource.requireRegion(files);
            var before = state.editing;
            var revision = before == null ? -1 : before.getDocument().revision();
            var data = GeoSource.view(state, files);
            return afterWorker(
                    () -> {
                        var chosen = GeoScans.choose(
                                GeoScans.scan(data, area, GeoSteps::isInterrupted)
                                        .findings(),
                                kinds,
                                named,
                                cell);
                        return new Planned(chosen, GeoScans.edits(data, chosen));
                    },
                    planned -> afterHold(viewer, () -> {
                        if (state.editing != before
                                || before != null && before.getDocument().revision() != revision) {
                            throw new ToolProblem("The geodata changed during the fix. Call geo_edit fix again.");
                        }
                        return fixed(viewer, files, order, area, kinds, cell, planned);
                    }));
        });
    }

    private Step fixed(
            ViewerContext viewer,
            GeoDataSnapshot files,
            Order order,
            Bounds area,
            List<String> kinds,
            @Nullable CellKey cell,
            Planned planned)
            throws ToolProblem {
        var state = viewer.scene().geodata;
        var chosen = planned.chosen();
        var answer = new JSONObject();
        answer.put("action", "fix");
        answer.put("findings", chosen.findings());
        var fixes = new JSONObject();
        var count = 0;
        for (var entry : chosen.byFix().entrySet()) {
            fixes.put(GeoScans.fixWord(entry.getKey()), entry.getValue().size());
            count += entry.getValue().size();
        }
        answer.put("fixes", fixes);
        if (chosen.withoutFix() > 0) {
            answer.put("without_fix", chosen.withoutFix());
            answer.put(
                    "without_fix_why",
                    "These findings offer no fix, or not the one named. geo_scan lists the fixes of each kind.");
        }
        if (planned.cells().isEmpty()) {
            answer.put("changed_cells", 0);
            answer.put("step", "none");
            answer.put(
                    "note",
                    chosen.findings() == 0
                            ? "The scan finds nothing of these kinds here."
                            : "Nothing to change: the cells already look fixed.");
            return Step.done(ToolResult.data(answer));
        }
        var held = new HashMap<GeoTile, RegionState>();
        for (var key : planned.cells().keySet()) {
            var tile = GeoTile.fromWorld(key.x(), key.y());
            held.put(tile, files.getState(tile));
        }
        GeoSource.holdRegions(state, files.withTiles(held));
        var session = state.editing;
        var selected = state.inspection.selected;
        var label = order.args().getText("label") != null
                ? order.label()
                : GeoEdits.PREFIX
                        + (chosen.byFix().size() == 1
                                ? chosen.byFix().keySet().iterator().next().label
                                : "Fix")
                        + " · "
                        + count
                        + (count == 1 ? " finding" : " findings");
        int changed;
        try {
            changed = session.applyCells(label, planned.cells());
        } catch (IllegalArgumentException | IndexOutOfBoundsException refused) {
            throw new ToolProblem("The fix was refused, and nothing changed: " + refused.getMessage());
        }
        state.followEdit(selected);
        answer.put("changed_cells", changed);
        answer.put("step", changed > 0 ? label : "none");
        answer.put("undo_steps", session.getDocument().getUndoLabels().size());
        var after = GeoSource.view(state, files);
        return Step.onWorker(() -> {
            var left = GeoScans.choose(
                    GeoScans.scan(after, area, GeoSteps::isInterrupted).findings(), kinds, null, cell);
            answer.put("left", left.findings());
            answer.put(
                    "note",
                    "left counts the findings of these kinds that a new scan still finds. geo_history undo takes the"
                            + " fix back.");
            return ToolResult.data(answer);
        });
    }

    private Step take(ToolArgs args, ViewerContext viewer, Path folder, Order order) throws ToolProblem {
        var state = viewer.scene().geodata;
        var from = args.getText("from");
        if (from == null) {
            throw new ToolProblem("take needs from, folder B: the folder whose file of this region the session takes.");
        }
        var other = otherFolder(from, folder);
        var tile = order.tile();
        var named = args.getText("label") != null
                ? order
                : new Order(
                        order.operation(),
                        order.at(),
                        order.to(),
                        order.radius(),
                        true,
                        false,
                        order.tolerance(),
                        GeoEdits.PREFIX + "Take " + GeoWords.region(tile) + " from B",
                        args);
        var region = regionBounds(tile);
        return loaded(
                source.load(GeoSource.wish(folder, region, state)),
                files -> loaded(
                        folderB.load(new Wish(other, region, 0, 0)),
                        filesB -> change(viewer, files.withTiles(Map.of(tile, files.getState(tile))), named, filesB)));
    }

    private Step copy(GeodataState state, GeoDataSnapshot files, Order order) throws ToolProblem {
        var copied = GeoEdits.copy(GeoSource.view(state, files), order);
        clip = copied;
        var answer = new JSONObject();
        answer.put("action", "copy");
        answer.put("cells", copied.count());
        answer.put("size", copied.columns() + " x " + copied.rows());
        return Step.done(ToolResult.data(answer));
    }

    private Step change(ViewerContext viewer, GeoDataSnapshot files, Order order, @Nullable GeoDataSnapshot built) {
        var state = viewer.scene().geodata;
        return afterHold(viewer, () -> {
            GeoSource.holdRegions(state, files);
            var selected = state.inspection.selected;
            var answer = GeoEdits.apply(state.editing, GeoSource.view(state, files), order, clip, built);
            state.followEdit(selected);
            answer.put("target", GeoReads.cell(GeoSource.view(state, files), order.at()));
            return Step.done(ToolResult.data(answer));
        });
    }

    private Step afterBuild(ViewerContext viewer, GeoTile tile, Next<GeoDataSnapshot> next) throws ToolProblem {
        var builder = rebuilder(viewer);
        return new Step() {
            private @Nullable Step following;

            @Override
            public @Nullable ToolResult step() throws ToolProblem {
                if (following != null) {
                    return following.step();
                }
                var built = builder.getBuiltRegion(tile);
                if (built == null) {
                    var status = builder.getStatus();
                    if (!status.running() && !status.problem().isBlank()) {
                        throw new ToolProblem(status.problem());
                    }
                    return null;
                }
                following = next.start(built);
                return following.step();
            }

            @Override
            public void cancel() {
                if (following != null) {
                    following.cancel();
                } else {
                    builder.cancel();
                }
            }
        };
    }

    private GeoClientRebuild rebuilder(ViewerContext viewer) throws ToolProblem {
        var world = viewer.world();
        if (world.clientPath() == null) {
            throw new ToolProblem("rebuild needs a client, and this viewer has none.");
        }
        if (rebuilder == null) {
            rebuilder = new GeoClientRebuild(world.clientPath(), world.variant());
            rebuilder.startSources(startPoints(world));
        }
        return rebuilder;
    }
}
