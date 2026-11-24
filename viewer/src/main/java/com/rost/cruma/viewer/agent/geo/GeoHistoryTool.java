package com.rost.cruma.viewer.agent.geo;

import static com.rost.cruma.viewer.agent.geo.GeoSteps.STEPS_SHOWN;
import static com.rost.cruma.viewer.agent.geo.GeoSteps.afterHold;
import static com.rost.cruma.viewer.agent.geo.GeoSteps.afterWorker;
import static com.rost.cruma.viewer.agent.geo.GeoSteps.loaded;
import static com.rost.cruma.viewer.agent.geo.GeoSteps.regionBounds;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.core.geodata.GeoDataDiff.Bounds;
import com.rost.cruma.core.geodata.GeoDataSnapshot;
import com.rost.cruma.core.geodata.GeoDataSnapshot.RegionState;
import com.rost.cruma.core.geodata.GeoTile;
import com.rost.cruma.viewer.agent.AgentSteps;
import com.rost.cruma.viewer.agent.Step;
import com.rost.cruma.viewer.agent.ToolArgs;
import com.rost.cruma.viewer.agent.ToolProblem;
import com.rost.cruma.viewer.agent.ToolResult;
import com.rost.cruma.viewer.agent.ViewerContext;
import com.rost.cruma.viewer.geodata.GeoEditPatchStore;
import com.rost.cruma.viewer.geodata.GeoEditingSession;
import com.rost.cruma.viewer.geodata.GeoEditingSession.Patch;
import com.rost.cruma.viewer.render.GeodataState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.jetbrains.annotations.Nullable;

final class GeoHistoryTool {
    private final GeoSource source;

    GeoHistoryTool(GeoSource source) {
        this.source = source;
    }

    static JSONObject restore(GeodataState state, GeoDataSnapshot files, Patch patch, boolean owned, boolean hidden)
            throws ToolProblem {
        requireNoEdits(state, owned, hidden);
        var held = new HashMap<GeoTile, RegionState>();
        for (var tile : patch.sourceFingerprints().keySet()) {
            held.put(tile, files.getState(tile));
        }
        GeoSource.holdRegions(state, files.withTiles(held));
        var session = state.editing;
        var selected = state.inspection.selected;
        try {
            session.restorePatch(patch);
        } catch (IllegalArgumentException | IllegalStateException refused) {
            throw new ToolProblem("The patch does not fit these region files: " + refused.getMessage()
                    + ". It opens only on the files it was made from.");
        }
        state.followEdit(selected);
        var answer = describeHistory(session, 0);
        answer.put(
                "note",
                "The patch is the new start of the session, so Undo does not take it back. The region files hold its"
                        + " edits only after geo_save.");
        return answer;
    }

    Step runHistory(ToolArgs args, ViewerContext viewer) throws ToolProblem {
        var state = viewer.scene().geodata;
        var action = args.getText("action", "list");
        var steps = Math.clamp(args.getInteger("steps", 1), 1, AgentSteps.MOST_STEPS);
        var session = state.editing;
        if (action.equals("load")) {
            return loadPatch(args, viewer);
        }
        if (session == null) {
            if (action.equals("list")) {
                return Step.done(ToolResult.data(describeHistory(null, 0)));
            }
            throw new ToolProblem("There are no geodata edits yet.");
        }
        return switch (action) {
            case "list" -> Step.done(ToolResult.data(describeHistory(session, 0)));
            case "undo", "redo" ->
                afterHold(viewer, () -> {
                    var selected = state.inspection.selected;
                    var undo = action.equals("undo");
                    var done = 0;
                    String held = null;
                    while (done < steps) {
                        var next = undo
                                ? session.getDocument().getUndoLabels()
                                : session.getDocument().getRedoLabels();
                        if (!next.isEmpty()
                                && !AgentSteps.isOwn(
                                        next.getFirst(), viewer.agent().isHidden())) {
                            held = next.getFirst();
                            break;
                        }
                        if (!(undo ? session.undo() : session.redo())) {
                            break;
                        }
                        done++;
                    }
                    state.followEdit(selected);
                    var answer = describeHistory(session, done);
                    if (held != null) {
                        answer.put("held", AgentSteps.held(held));
                    }
                    return Step.done(ToolResult.data(answer));
                });
            default -> throw new ToolProblem("action must be list, undo, redo or load.");
        };
    }

    private Step loadPatch(ToolArgs args, ViewerContext viewer) throws ToolProblem {
        var state = viewer.scene().geodata;
        var file = args.getText("patch");
        if (file == null) {
            throw new ToolProblem("load needs patch, the .json file that geo_save wrote.");
        }
        var path = Path.of(file).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            throw new ToolProblem("There is no file " + path + ".");
        }
        var owned = viewer.agent().isOwned();
        var hidden = viewer.agent().isHidden();
        var folder = GeoSource.folder(null, state, owned);
        requireNoEdits(state, owned, hidden);
        return afterWorker(
                () -> {
                    try {
                        return GeoEditPatchStore.read(path);
                    } catch (IOException | RuntimeException unreadable) {
                        throw new ToolProblem("The patch cannot be read: " + unreadable.getMessage());
                    }
                },
                patch -> {
                    var tiles = patch.sourceFingerprints().keySet();
                    if (tiles.isEmpty()) {
                        throw new ToolProblem("The patch holds no edits.");
                    }
                    if (tiles.size() > GeoSource.MOST_REGIONS) {
                        throw new ToolProblem("The patch has edits in " + tiles.size() + " regions, more than "
                                + GeoSource.MOST_REGIONS + " that one call holds. Load it in the panel.");
                    }
                    var area = regionBounds(tiles.iterator().next());
                    for (var tile : tiles) {
                        var bounds = regionBounds(tile);
                        area = new Bounds(
                                Math.min(area.minX(), bounds.minX()),
                                Math.min(area.minY(), bounds.minY()),
                                Math.max(area.maxX(), bounds.maxX()),
                                Math.max(area.maxY(), bounds.maxY()));
                    }
                    return loaded(
                            source.load(GeoSource.wish(folder, area, state)),
                            files -> afterHold(viewer, () -> {
                                var answer = restore(state, files, patch, owned, hidden);
                                answer.put("loaded", path.toString());
                                return Step.done(ToolResult.data(answer));
                            }));
                });
    }

    private static void requireNoEdits(GeodataState state, boolean owned, boolean hidden) throws ToolProblem {
        var session = state.editing;
        if (session == null) {
            return;
        }
        if (session.isModified()) {
            throw new ToolProblem(
                    "A patch opens only on a session with no edits. Save the edits with geo_save, or take them back"
                            + " with geo_history undo.");
        }
        if (owned) {
            return;
        }
        var steps = new ArrayList<>(session.getDocument().getUndoLabels());
        steps.addAll(session.getDocument().getRedoLabels());
        for (var step : steps) {
            if (!AgentSteps.isOwn(step, hidden)) {
                throw new ToolProblem("The Undo list holds a step of the person, " + step
                        + ", which a patch would drop. Ask them to clear it, or open a viewer of your own with"
                        + " world_open.");
            }
        }
    }

    private static JSONObject describeHistory(@Nullable GeoEditingSession session, int done) {
        var answer = new JSONObject();
        answer.put("undone", done);
        answer.put(
                "undo",
                session == null ? new JSONArray() : shown(session.getDocument().getUndoLabels()));
        answer.put(
                "redo",
                session == null ? new JSONArray() : shown(session.getDocument().getRedoLabels()));
        answer.put("changed_cells", session == null ? 0 : session.getDocument().getChangedCellCount());
        answer.put("unsaved", session != null && session.hasUnsavedChanges());
        return answer;
    }

    private static JSONArray shown(List<String> labels) {
        var shown = new JSONArray();
        shown.addAll(labels.subList(0, Math.min(STEPS_SHOWN, labels.size())));
        if (labels.size() > STEPS_SHOWN) {
            shown.add("and " + (labels.size() - STEPS_SHOWN) + " more");
        }
        return shown;
    }
}
