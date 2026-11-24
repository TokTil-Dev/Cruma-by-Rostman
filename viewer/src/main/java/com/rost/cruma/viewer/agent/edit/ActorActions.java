package com.rost.cruma.viewer.agent.edit;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.core.editing.ActorTransform;
import com.rost.cruma.core.editing.ActorTransform.Vector3;
import com.rost.cruma.core.geometry.Bounds3f;
import com.rost.cruma.viewer.agent.Step;
import com.rost.cruma.viewer.agent.ToolArgs;
import com.rost.cruma.viewer.agent.ToolProblem;
import com.rost.cruma.viewer.agent.ToolResult;
import com.rost.cruma.viewer.agent.ViewerContext;
import com.rost.cruma.viewer.agent.edit.EditSteps.Picked;
import com.rost.cruma.viewer.editing.ActorClipboard;
import com.rost.cruma.viewer.editing.BoundsAlign;
import com.rost.cruma.viewer.editing.BoundsAlign.Edge;
import com.rost.cruma.viewer.editing.EditingSession;
import com.rost.cruma.viewer.editing.EditingSession.Change;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

final class ActorActions {
    static final List<String> ACTIONS =
            List.of("revert", "replace_mesh", "hide", "show", "lock", "unlock", "group", "ungroup", "save_prefab");

    static final List<String> ALIGN = List.of(
            "left", "centre_x", "right", "front", "centre_y", "back", "same_height", "distribute_x", "distribute_y");

    private static final float LOW = .9f;
    private static final float HIGH = 1.1f;

    private ActorActions() {}

    static String of(ToolArgs args) throws ToolProblem {
        var action = args.getText("action", "change").toLowerCase(Locale.ROOT);
        if (!action.equals("change") && !ACTIONS.contains(action)) {
            throw new ToolProblem("action must be change or one of " + String.join(", ", ACTIONS) + ".");
        }
        var named = new ArrayList<String>();
        for (var key : List.of("mirror", "randomize", "align")) {
            if (args.has(key)) {
                named.add(key);
            }
        }
        if (named.size() > 1 || !named.isEmpty() && !action.equals("change")) {
            throw new ToolProblem("Give one of action, mirror, randomize and align in a call.");
        }
        return named.isEmpty() ? action : named.getFirst();
    }

    static Step start(EditTools tools, String action, ToolArgs args, ViewerContext viewer) throws ToolProblem {
        var session = EditSteps.session(viewer);
        var preview = args.readFlag("preview", false);
        var limit = EditSteps.limit(args);
        if (action.equals("save_prefab")) {
            EditExport.requireWrite(viewer.agent().getAccess(), "actors_change save_prefab");
        }
        if (action.equals("revert")) {
            return revert(args, viewer, session, preview, limit);
        }
        var group = args.getText("group", "");
        if (!args.has("actors") && !args.has("where")) {
            var all = switch (action) {
                case "show" -> new ArrayList<>(session.getHiddenActors());
                case "unlock" -> new ArrayList<>(session.getLockedActors());
                case "ungroup" -> new ArrayList<>(session.getGroupMembers(group));
                default -> null;
            };
            if (all != null) {
                return EditSteps.editing(
                        viewer, session, () -> Step.done(state(action, all, group, session, new Refusals(), limit)));
            }
        }
        var axis = action.equals("mirror") ? axis(args) : -1;
        var align = action.equals("align") ? align(args) : null;
        var file = action.equals("save_prefab") ? prefabFile(args) : null;
        var mesh = action.equals("replace_mesh") ? args.getRequired("mesh") : null;
        var filter = EditSteps.filter(args, viewer, session);
        var picked = new Picked();
        return EditSteps.editing(
                viewer,
                session,
                () -> EditSteps.pick(args, viewer, session, filter, picked, () -> switch (action) {
                    case "mirror" -> Step.done(mirror(session, picked, axis, preview, limit));
                    case "randomize" -> Step.done(randomize(args, session, picked, preview, limit));
                    case "align" -> Step.done(align(viewer, session, picked, align, preview, limit));
                    case "replace_mesh" -> replaceMesh(tools, viewer, session, picked, mesh, preview, limit);
                    case "save_prefab" -> Step.done(savePrefab(viewer, session, picked, file));
                    default ->
                        Step.done(state(
                                action, List.copyOf(picked.actors.keySet()), group, session, picked.refused, limit));
                }));
    }

    static <K> Map<K, Float> shifts(String align, Map<K, Bounds3f> bounds, int[] axis) {
        var index = ALIGN.indexOf(align);
        if (index < 6) {
            axis[0] = index < 3 ? 0 : 1;
            return BoundsAlign.align(bounds, axis[0], Edge.values()[index % 3]);
        }
        if (index == 6) {
            axis[0] = 2;
            return BoundsAlign.sameHeight(bounds);
        }
        axis[0] = index == 7 ? 0 : 1;
        return BoundsAlign.distribute(bounds, axis[0]);
    }

    static void inOneStep(EditingSession session, String label, Runnable change) {
        session.beginStep();
        try {
            change.run();
        } finally {
            session.endStep(label);
        }
    }

    static JSONArray transforms(EditingSession session, Collection<String> actors, int limit) {
        var rows = new JSONArray();
        for (var path : actors) {
            if (rows.size() == limit) {
                break;
            }
            rows.add(transform(path, session.getActor(path).transform()));
        }
        return rows;
    }

    static JSONObject transform(String path, ActorTransform t) {
        var row = new JSONObject();
        row.put("actor", path);
        row.put("location", EditSteps.point(t.location()));
        var r = t.rotation();
        row.put("rotation", JSONArray.of(degrees(r.yaw()), degrees(r.pitch()), degrees(r.roll())));
        row.put("scale", EditSteps.round(t.drawScale()));
        var s = t.drawScale3d();
        row.put("scale3d", JSONArray.of(EditSteps.round(s.x()), EditSteps.round(s.y()), EditSteps.round(s.z())));
        return row;
    }

    static ToolResult replaced(
            EditingSession session,
            Picked picked,
            String mesh,
            int keptSlots,
            @Nullable Path packageFile,
            boolean preview,
            int limit) {
        var chosen = movable(session, picked);
        var data = answer(chosen, picked, preview, limit);
        data.put("mesh", mesh);
        if (preview || chosen.isEmpty()) {
            return ToolResult.data(data);
        }
        var label = EditSteps.AI + "Replace mesh of " + EditSteps.subject(chosen);
        var created = new ArrayList<String>();
        inOneStep(session, label, () -> created.addAll(session.replaceMesh(chosen, mesh, packageFile, keptSlots)));
        var rows = new JSONArray();
        for (var i = 0; i < created.size() && i < limit; i++) {
            var row = new JSONObject();
            row.put("was", chosen.get(i));
            row.put("actor", created.get(i));
            rows.add(row);
        }
        data.put("actors", rows);
        data.put("step", label);
        data.put(
                "note",
                "Each actor is a new actor with the new mesh, in the place of the old one, so use the new paths.");
        return ToolResult.data(data);
    }

    private static int axis(ToolArgs args) throws ToolProblem {
        var text = args.getRequired("mirror").toLowerCase(Locale.ROOT);
        var axis = List.of("x", "y", "z").indexOf(text);
        if (axis < 0) {
            throw new ToolProblem("mirror must be x, y or z.");
        }
        return axis;
    }

    private static List<String> movable(EditingSession session, Picked picked) {
        var locked = session.getLockedActors();
        var result = new ArrayList<String>();
        for (var entry : picked.actors.entrySet()) {
            var path = entry.getKey();
            var reason = locked.contains(path)
                    ? "It is locked. Unlock it first."
                    : session.transformRestriction(path, entry.getValue().transform());
            if (reason.isEmpty()) {
                result.add(path);
            } else {
                picked.refused.add(reason, path);
            }
        }
        return result;
    }

    private static ToolResult mirror(EditingSession session, Picked picked, int axis, boolean preview, int limit) {
        var chosen = movable(session, picked);
        var label = EditSteps.AI + "Mirror " + EditSteps.subject(chosen) + " on " + "XYZ".charAt(axis);
        var data = answer(chosen, picked, preview, limit);
        if (!preview && !chosen.isEmpty()) {
            inOneStep(session, label, () -> session.mirror(chosen, axis));
            data.put("step", label);
            data.put("actors", transforms(session, chosen, limit));
        }
        return ToolResult.data(data);
    }

    private static ToolResult randomize(
            ToolArgs args, EditingSession session, Picked picked, boolean preview, int limit) throws ToolProblem {
        var options = args.getJson().get("randomize");
        if (!(options instanceof JSONObject json)) {
            throw new ToolProblem("randomize must be an object with yaw, scale and seed.");
        }
        var parts = new ToolArgs(json);
        var yaw = parts.readFlag("yaw", true);
        var scale = json.get("scale");
        var sized = !Boolean.FALSE.equals(scale);
        var low = LOW;
        var high = HIGH;
        if (sized && scale != null) {
            var range = ActorChange.numbers(scale, "randomize.scale");
            if (range.length != 2 || !(range[0] > 0) || !(range[1] >= range[0])) {
                throw new ToolProblem(
                        "randomize.scale must be two factors, the first above 0 and not above the second, such as"
                                + " [0.9, 1.1], or false.");
            }
            low = range[0];
            high = range[1];
        }
        if (!yaw && !sized) {
            throw new ToolProblem("randomize changes nothing with yaw false and scale false.");
        }
        var seedValue = parts.getNumber("seed");
        var seed = seedValue == null ? System.nanoTime() : seedValue.longValue();
        var chosen = movable(session, picked);
        var data = answer(chosen, picked, preview, limit);
        data.put("seed", seed);
        if (!preview && !chosen.isEmpty()) {
            var label = EditSteps.AI + "Randomize " + EditSteps.subject(chosen);
            var from = low;
            var to = high;
            inOneStep(session, label, () -> session.randomize(chosen, seed, yaw, sized, from, to));
            data.put("step", label);
            data.put("actors", transforms(session, chosen, limit));
        }
        return ToolResult.data(data);
    }

    private static String align(ToolArgs args) throws ToolProblem {
        var text = args.getRequired("align").toLowerCase(Locale.ROOT);
        if (!ALIGN.contains(text)) {
            throw new ToolProblem("align must be one of " + String.join(", ", ALIGN) + ".");
        }
        return text;
    }

    private static ToolResult align(
            ViewerContext viewer, EditingSession session, Picked picked, String align, boolean preview, int limit)
            throws ToolProblem {
        var chosen = movable(session, picked);
        var known = EditSteps.bounds(viewer, new LinkedHashSet<>(chosen));
        var bounds = new LinkedHashMap<String, Bounds3f>();
        for (var path : chosen) {
            var box = known.get(path);
            if (box == null) {
                picked.refused.add("It is not drawn yet, so its bounds are not known.", path);
            } else {
                bounds.put(path, box);
            }
        }
        var distribute = align.startsWith("distribute");
        if (bounds.size() < (distribute ? 3 : 2)) {
            throw new ToolProblem((distribute ? "Distribute needs three" : "Align needs two")
                    + " or more actors that are drawn and not locked, and the call has "
                    + bounds.size()
                    + ".");
        }
        var axis = new int[1];
        var values = BoundsAlign.moved(
                shifts(align, bounds, axis),
                axis[0],
                path -> session.getActor(path).transform());
        var data = new JSONObject();
        data.put(preview ? "would_change" : "changed", values.size());
        data.put("lined_up_already", bounds.size() - values.size());
        data.put("could_not", picked.refused.getCount());
        if (picked.refused.getCount() > 0) {
            data.put("reasons", picked.refused.write());
        }
        var rows = new JSONArray();
        for (var entry : values.entrySet()) {
            if (rows.size() == limit) {
                break;
            }
            var row = new JSONObject();
            row.put("actor", entry.getKey());
            row.put(
                    "before",
                    EditSteps.point(session.getActor(entry.getKey()).transform().location()));
            row.put("after", EditSteps.point(entry.getValue().location()));
            rows.add(row);
        }
        data.put("actors", rows);
        if (!preview && !values.isEmpty()) {
            var label = EditSteps.AI + (distribute ? "Distribute " : "Align ") + bounds.size() + " actors";
            EditSteps.moveInOneStep(viewer, session, values, label, data);
            data.put("step", label);
        }
        return ToolResult.data(data);
    }

    private static Step revert(ToolArgs args, ViewerContext viewer, EditingSession session, boolean preview, int limit)
            throws ToolProblem {
        var named = args.list("actors");
        var filter = EditSteps.filter(args, viewer, session);
        if (named.isEmpty() && filter == null) {
            throw new ToolProblem("Name what to revert: actors, paths from edit_changes, or where.");
        }
        var maps = new LinkedHashSet<String>();
        var loaded = EditSteps.loadedMaps(viewer, session);
        for (var path : named) {
            if (loaded.contains(EditSteps.mapOf(path))) {
                maps.add(EditSteps.mapOf(path));
            }
        }
        var picked = new Picked();
        var rest = args.without("actors");
        return EditSteps.editing(
                viewer,
                session,
                () -> EditSteps.opening(session, maps, () -> {
                    var wanted = new LinkedHashSet<String>();
                    for (var path : named) {
                        if (path.equalsIgnoreCase("selection")) {
                            wanted.addAll(session.getSelection());
                        } else {
                            wanted.add(path);
                        }
                    }
                    if (filter == null) {
                        return Step.done(reverted(session, wanted, picked.refused, preview, limit));
                    }
                    return EditSteps.pick(rest, viewer, session, filter, picked, () -> {
                        wanted.addAll(picked.actors.keySet());
                        return Step.done(reverted(session, wanted, picked.refused, preview, limit));
                    });
                }));
    }

    private static ToolResult reverted(
            EditingSession session, Collection<String> wanted, Refusals refused, boolean preview, int limit) {
        var changes = new LinkedHashMap<String, Change>();
        for (var change : session.getChanges()) {
            changes.put(change.path(), change);
        }
        var chosen = new ArrayList<Change>();
        for (var path : wanted) {
            var change = changes.get(path);
            if (change != null) {
                chosen.add(change);
            } else if (session.checkEditable(path) != null) {
                refused.add("It is as in the client.", path);
            } else {
                refused.add("It is not in the list of changes. edit_changes lists them.", path);
            }
        }
        var data = new JSONObject();
        data.put(preview ? "would_revert" : "reverted", chosen.size());
        data.put("could_not", refused.getCount());
        if (refused.getCount() > 0) {
            data.put("reasons", refused.write());
        }
        var rows = new JSONArray();
        for (var change : chosen.subList(0, Math.min(limit, chosen.size()))) {
            var row = new JSONObject();
            row.put("actor", change.path());
            row.put("what", change.detail());
            rows.add(row);
        }
        data.put("actors", rows);
        if (!preview && !chosen.isEmpty()) {
            var paths = new ArrayList<String>();
            chosen.forEach(change -> paths.add(change.path()));
            var label = EditSteps.AI + "Revert " + EditSteps.subject(paths);
            inOneStep(session, label, () -> chosen.forEach(session::revert));
            data.put("step", label);
        }
        return ToolResult.data(data);
    }

    private static ToolResult state(
            String action, List<String> actors, String group, EditingSession session, Refusals refused, int limit)
            throws ToolProblem {
        var data = new JSONObject();
        switch (action) {
            case "hide" -> session.hide(actors);
            case "show" -> session.show(actors);
            case "lock", "unlock" -> session.setLocked(actors, action.equals("lock"));
            case "group" -> {
                if (actors.size() < 2) {
                    throw new ToolProblem(
                            "A group needs two or more actors, and the call names " + actors.size() + ".");
                }
                data.put("group", session.group(group, actors));
            }
            default -> {
                var names = new LinkedHashSet<String>();
                for (var actor : actors) {
                    var named = session.findGroupOf(actor);
                    if (named != null) {
                        names.add(named);
                    }
                }
                if (names.isEmpty()) {
                    throw new ToolProblem(
                            group.isEmpty() ? "The actors are in no group." : "There is no group " + group + ".");
                }
                names.forEach(session::ungroup);
                data.put("ungrouped", names);
            }
        }
        data.put("actors", actors.size());
        data.put("examples", actors.subList(0, Math.min(limit, actors.size())));
        data.put("could_not", refused.getCount());
        if (refused.getCount() > 0) {
            data.put("reasons", refused.write());
        }
        data.put("hidden", session.getHiddenActors().size());
        data.put("locked", session.getLockedActors().size());
        data.put(
                "note",
                "Hide, Lock and groups are no Undo step and are not saved: they last as long as Edit in this window.");
        return ToolResult.data(data);
    }

    private static Path prefabFile(ToolArgs args) throws ToolProblem {
        var text = args.getRequired("file");
        try {
            var path = Path.of(text);
            var file = ActorClipboard.prefabFile(
                    path.isAbsolute() ? path : ActorClipboard.prefabFolder().resolve(path));
            if (Files.exists(file)) {
                throw new ToolProblem("The prefab " + file + " is there already. Choose another name.");
            }
            return file;
        } catch (InvalidPathException wrong) {
            throw new ToolProblem("file is not a path: " + wrong.getMessage());
        }
    }

    private static ToolResult savePrefab(ViewerContext viewer, EditingSession session, Picked picked, Path file)
            throws ToolProblem {
        var actors = List.copyOf(picked.actors.keySet());
        if (actors.isEmpty()) {
            throw new ToolProblem("No actor of the call is in Edit.");
        }
        double x = 0, y = 0, z = 0;
        for (var path : actors) {
            var at = session.getActor(path).transform().location();
            x += at.x();
            y += at.y();
            z += at.z();
        }
        var pivot = new Vector3((float) (x / actors.size()), (float) (y / actors.size()), (float) (z / actors.size()));
        Float bottom = null;
        for (var box : EditSteps.bounds(viewer, new LinkedHashSet<>(actors)).values()) {
            bottom = bottom == null ? box.minZ() : Math.min(bottom, box.minZ());
        }
        var clip = ActorClipboard.of(session, actors, pivot, bottom, session::getPackageFile);
        if (clip == null) {
            throw new ToolProblem("The actors have no mesh to save.");
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, ActorClipboard.encode(clip));
        } catch (IOException failed) {
            throw new ToolProblem("The prefab could not be saved: " + failed.getMessage());
        }
        var data = new JSONObject();
        data.put("saved", file.toString());
        data.put("actors", clip.items().size());
        data.put("could_not", picked.refused.getCount());
        data.put("use", "actors_place with prefab and a target places it.");
        return ToolResult.data(data)
                .withFolder(file.getParent())
                .saying("Saved the prefab "
                        + file.getFileName()
                        + " of "
                        + clip.items().size()
                        + " actors.");
    }

    private static JSONObject answer(List<String> chosen, Picked picked, boolean preview, int limit) {
        var data = new JSONObject();
        data.put(preview ? "would_change" : "changed", chosen.size());
        data.put("could_not", picked.refused.getCount());
        if (picked.refused.getCount() > 0) {
            data.put("reasons", picked.refused.write());
        }
        if (preview) {
            data.put("actors", chosen.subList(0, Math.min(limit, chosen.size())));
        }
        return data;
    }

    private static double degrees(int units) {
        return EditSteps.round((short) units * 360.0 / 65536);
    }

    private static Step replaceMesh(
            EditTools tools,
            ViewerContext viewer,
            EditingSession session,
            Picked picked,
            String mesh,
            boolean preview,
            int limit)
            throws ToolProblem {
        if (picked.actors.isEmpty()) {
            return Step.done(replaced(session, picked, mesh, Integer.MAX_VALUE, null, preview, limit));
        }
        var map = EditSteps.mapOf(picked.actors.keySet().iterator().next());
        var path = EditSteps.meshOfMap(session, map, mesh);
        Path file = null;
        if (path == null) {
            file = session.getPackageFile(mesh);
            path = file != null && EditSteps.doesPackageHold(file, mesh) ? mesh : null;
        }
        if (path != null) {
            var ready = tools.getReadyLibrary();
            var entry = ready == null ? null : ready.getMesh(path);
            var kept = entry == null || entry.slots().isEmpty()
                    ? Integer.MAX_VALUE
                    : entry.slots().size();
            return Step.done(replaced(session, picked, path, kept, file, preview, limit));
        }
        var index = tools.getLibrary(viewer, session);
        return Step.until(
                index::areMeshesReady,
                EditSteps.LIBRARY_MILLIS,
                "The index of the client's meshes is not ready. Call again in a moment.",
                () -> {
                    var entry = MeshMatch.find(index.getMeshes(), mesh);
                    var kept = entry.slots().isEmpty()
                            ? Integer.MAX_VALUE
                            : entry.slots().size();
                    return replaced(session, picked, entry.path(), kept, entry.packageFile(), preview, limit);
                });
    }
}
