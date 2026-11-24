package com.rost.cruma.viewer.agent.edit;

import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.core.editing.ActorRecord;
import com.rost.cruma.core.editing.ActorTransform;
import com.rost.cruma.core.editing.ActorTransform.Vector3;
import com.rost.cruma.core.editing.PackageEditDocument;
import com.rost.cruma.core.geometry.Bounds3f;
import com.rost.cruma.viewer.agent.Step;
import com.rost.cruma.viewer.agent.Target;
import com.rost.cruma.viewer.agent.Target.Kind;
import com.rost.cruma.viewer.agent.ToolArgs;
import com.rost.cruma.viewer.agent.ToolProblem;
import com.rost.cruma.viewer.agent.ToolResult;
import com.rost.cruma.viewer.agent.ViewerContext;
import com.rost.cruma.viewer.editing.EditingSession;
import com.rost.cruma.viewer.editing.EditingSession.Change;
import com.rost.cruma.viewer.editing.EditingSession.ChangeKind;
import com.rost.cruma.viewer.editing.EditorUi;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.jetbrains.annotations.Nullable;

final class EditSteps {
    static final String AI = "AI: ";

    static final long LIBRARY_MILLIS = TimeUnit.MINUTES.toMillis(10);
    static final int MOST_MAPS = 25;
    static final int DEFAULT_LIMIT = 20;
    static final float DEFAULT_RADIUS = 2000;

    private static final long WAIT_NANOS = TimeUnit.MINUTES.toNanos(2);
    private static final long READ_NANOS_PER_FRAME = TimeUnit.MILLISECONDS.toNanos(4);
    private static final int MOST_LIMIT = 200;

    private EditSteps() {}

    @FunctionalInterface
    interface Next {
        Step start() throws ToolProblem;
    }

    static EditingSession session(ViewerContext viewer) throws ToolProblem {
        if (!viewer.ui().editingPrepared) {
            throw new ToolProblem("Edit is off. Call edit_mode first.");
        }
        var scene = viewer.scene();
        var session = scene.getEditing();
        var recovery = scene.getEditingRecovery();
        if (recovery != null && recovery.getAvailable() != null) {
            throw new ToolProblem(
                    "A recovery draft of an earlier session waits in Cruma. Ask the person to restore or discard it"
                            + " first.");
        }
        var terrain = scene.getTerrainEditing();
        session.getTimeline().bindTerrain(terrain);
        session.getExportCoordinator().setTerrain(terrain);
        return session;
    }

    static @Nullable String busy(ViewerContext viewer, EditingSession session) {
        if (!viewer.ready()) {
            return "The tiles are still loading. Call again when they are ready.";
        }
        if (session.isExportLocked()) {
            return "An export is running, and the world cannot change until it ends. Call again when it has ended, or"
                    + " ask the person to stop it.";
        }
        if (session.isGestureActive() || session.isLightDragActive()) {
            return "The person is still dragging something. Call again when they let go.";
        }
        return viewer.scene().isEditingOperationActive()
                ? "The Terrain is still at work. Call again in a moment."
                : null;
    }

    static Step editing(ViewerContext viewer, EditingSession session, Next next) {
        return waiting(
                () -> busy(viewer, session),
                WAIT_NANOS,
                () -> {
                    session.getTimeline().sync();
                    viewer.agent().refreshStatus(viewer);
                },
                next);
    }

    static Step waiting(Supplier<@Nullable String> busy, long timeoutNanos, Runnable answered, Next next) {
        return new Step() {
            private final long deadline = System.nanoTime() + timeoutNanos;
            private @Nullable Step work;

            @Override
            public @Nullable ToolResult step() throws ToolProblem {
                try {
                    if (work == null) {
                        var reason = busy.get();
                        if (reason != null) {
                            if (System.nanoTime() > deadline) {
                                throw new ToolProblem(reason);
                            }
                            return null;
                        }
                        work = next.start();
                    }
                    var result = work.step();
                    if (result != null) {
                        answered.run();
                    }
                    return result;
                } catch (ToolProblem problem) {
                    ended();
                    throw problem;
                } catch (IllegalArgumentException | IllegalStateException refused) {
                    ended();
                    throw new ToolProblem(refused.getMessage());
                }
            }

            @Override
            public void cancel() {
                if (work != null) {
                    work.cancel();
                }
            }

            private void ended() {
                if (work != null) {
                    answered.run();
                }
            }
        };
    }

    static Step opening(EditingSession session, Collection<String> maps, Next next) throws ToolProblem {
        var closed = new ArrayList<>(maps);
        closed.removeAll(session.getOpenedMaps());
        if (closed.isEmpty()) {
            return next.start();
        }
        return after(
                Step.onWorker(() -> {
                    for (var map : closed) {
                        try {
                            session.readMap(map);
                        } catch (IOException | IllegalArgumentException unreadable) {
                            throw new ToolProblem("The map " + map + " cannot be read: " + unreadable.getMessage());
                        }
                    }
                    return ToolResult.text("");
                }),
                next);
    }

    static Step after(Step first, Next next) {
        return new Step() {
            private @Nullable Step rest;

            @Override
            public @Nullable ToolResult step() throws ToolProblem {
                if (rest == null) {
                    var result = first.step();
                    if (result == null) {
                        return null;
                    }
                    if (result.hasFailed()) {
                        return result;
                    }
                    rest = next.start();
                }
                return rest.step();
            }

            @Override
            public void cancel() {
                (rest == null ? first : rest).cancel();
            }
        };
    }

    static String point(Vector3 value) {
        return round(value.x()) + "," + round(value.y()) + "," + round(value.z());
    }

    static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }

    static int limit(ToolArgs args) throws ToolProblem {
        return Math.clamp(args.getInteger("limit", DEFAULT_LIMIT), 1, MOST_LIMIT);
    }

    static Target locate(ViewerContext viewer, EditingSession session, String text) throws ToolProblem {
        var target = Target.parse(text);
        if (target.kind() == Kind.SELECTION) {
            var primary = session.getPrimary();
            if (primary == null) {
                throw new ToolProblem("Nothing is selected in Edit.");
            }
            return actorPoint(session, primary, text);
        }
        if (target.kind() == Kind.NAME && text.indexOf('.') > 0) {
            try {
                return actorPoint(session, text.strip(), text);
            } catch (IllegalArgumentException notAnActor) {
            }
        }
        return viewer.locate(text);
    }

    static @Nullable ActorFilter filter(ToolArgs args, ViewerContext viewer, EditingSession session)
            throws ToolProblem {
        var where = args.getJson().get("where");
        if (where != null && !(where instanceof JSONObject)) {
            throw new ToolProblem("where must be an object with mesh, name, class, near or radius.");
        }
        return ActorFilter.read((JSONObject) where, text -> locate(viewer, session, text), DEFAULT_RADIUS);
    }

    static Step pick(
            ToolArgs args,
            ViewerContext viewer,
            EditingSession session,
            @Nullable ActorFilter filter,
            Picked picked,
            Next next)
            throws ToolProblem {
        var named = args.list("actors");
        if (named.isEmpty() && filter == null) {
            throw new ToolProblem("Name the actors: give actors, a list of paths, or where, a filter.");
        }
        var maps = new LinkedHashSet<String>();
        for (var path : named) {
            if (!path.equalsIgnoreCase("selection")
                    && loadedMaps(viewer, session).contains(mapOf(path))) {
                maps.add(mapOf(path));
            }
        }
        var walked = filter == null ? List.<String>of() : filter.maps(loadedMaps(viewer, session));
        if (walked.size() > MOST_MAPS) {
            throw new ToolProblem("This filter would read " + walked.size()
                    + " maps. Give where.near with a radius, or name the actors.");
        }
        maps.addAll(walked);
        return EditSteps.opening(session, maps, () -> {
            named(named, session, picked);
            return filter == null ? next.start() : walk(session, walked, filter, picked, next);
        });
    }

    static final class Picked {
        final Map<String, ActorRecord> actors = new LinkedHashMap<>();
        final Refusals refused = new Refusals();
    }

    static String notEditable(String path) {
        var reason = EditorUi.uneditable(path);
        return reason == null ? "There is no such actor." : reason;
    }

    static Map<String, Bounds3f> checkBounds(ViewerContext viewer, EditingSession session, List<Change> changes) {
        var paths = new LinkedHashSet<String>();
        var maps = new LinkedHashSet<String>();
        for (var change : changes) {
            paths.add(change.path());
            if (change.kinds().contains(ChangeKind.BRUSH)) {
                maps.add(change.map());
            }
        }
        for (var map : maps) {
            paths.addAll(mapActors(session, map));
        }
        return bounds(viewer, paths);
    }

    static Set<String> mapActors(EditingSession session, String map) {
        var result = new LinkedHashSet<String>();
        for (var actor : session.getActorsOf(map)) {
            result.add(actor.sourcePath());
        }
        return result;
    }

    static Map<String, Bounds3f> bounds(ViewerContext viewer, Set<String> actors) {
        var result = new HashMap<String, Bounds3f>();
        var moving = new HashSet<String>();
        for (var object : viewer.backend().getEditorActorObjects(actors)) {
            result.merge(object.actorName(), object.bounds(), EditSteps::union);
            if (object.actorAnimated()) {
                moving.add(object.actorName());
            }
        }
        for (var path : moving) {
            var saved = viewer.backend().getSavedActorBounds(path);
            if (saved != null) {
                result.put(path, saved);
            }
        }
        return result;
    }

    static String subject(Collection<String> paths) {
        return paths.size() == 1 ? shortName(paths.iterator().next()) : paths.size() + " actors";
    }

    static Set<String> loadedMaps(ViewerContext viewer, EditingSession session) {
        return session.getMapFiles().getSources(viewer.loading().getLoadedMaps());
    }

    static String mapOf(String path) {
        var dot = path.indexOf('.');
        return dot <= 0 ? path : path.substring(0, dot);
    }

    static void moveInOneStep(
            ViewerContext viewer,
            EditingSession session,
            Map<String, ActorTransform> values,
            String label,
            JSONObject data) {
        var settings = viewer.scene().getEditingSettings();
        var follow = settings == null || settings.moveActorsToTheirTile();
        var loaded = loadedMaps(viewer, session);
        session.beginStep();
        try {
            session.transformActors(values, label);
            var note = session.takeStepNote();
            if (note != null) {
                data.put("note", note);
            }
            var tiles = new LinkedHashMap<String, String>();
            var outside = 0;
            for (var path : values.keySet()) {
                var tile = session.getTileOutsideMap(path);
                if (tile != null && follow && loaded.contains(tile)) {
                    tiles.put(path, tile);
                } else if (tile != null) {
                    outside++;
                }
            }
            try {
                session.moveToTiles(tiles, label);
                if (!tiles.isEmpty()) {
                    data.put("moved_to_their_tiles", tiles.size());
                }
            } catch (IllegalArgumentException refused) {
                outside += tiles.size();
                data.put("stays_in_its_map", refused.getMessage());
            }
            if (outside > 0) {
                data.put("outside_their_tile", outside);
            }
        } finally {
            session.endStep(label);
        }
    }

    static @Nullable String meshOfMap(EditingSession session, String map, String mesh) throws ToolProblem {
        try {
            for (var known : session.getMeshesOf(map)) {
                if (known.equalsIgnoreCase(mesh) || shortName(known).equalsIgnoreCase(mesh)) {
                    return known;
                }
            }
            return null;
        } catch (IOException failed) {
            throw new ToolProblem("The map " + map + " cannot be read: " + failed.getMessage());
        }
    }

    static boolean doesPackageHold(Path file, String mesh) throws ToolProblem {
        try {
            for (var known : PackageEditDocument.inspectMeshes(file)) {
                if (known.equalsIgnoreCase(mesh)) {
                    return true;
                }
            }
            return false;
        } catch (IOException failed) {
            throw new ToolProblem("The package " + file.getFileName() + " cannot be read: " + failed.getMessage());
        }
    }

    static String shortName(String path) {
        return path.substring(path.lastIndexOf('.') + 1);
    }

    private static Target actorPoint(EditingSession session, String path, String text) {
        var location = session.getActor(path).transform().location();
        return new Target(Kind.POINT, text, location.x(), location.y(), location.z());
    }

    private static void named(List<String> named, EditingSession session, Picked picked) {
        for (var path : named) {
            if (path.equalsIgnoreCase("selection")) {
                for (var selected : session.getSelection()) {
                    picked.actors.put(selected, session.getActor(selected));
                }
                continue;
            }
            try {
                picked.actors.put(path, session.getActor(path));
            } catch (IllegalArgumentException unknown) {
                picked.refused.add(notEditable(path), path);
            }
        }
    }

    private static Step walk(EditingSession session, List<String> maps, ActorFilter filter, Picked picked, Next next) {
        var left = new ArrayDeque<>(maps);
        Step walk = () -> {
            var deadline = System.nanoTime() + READ_NANOS_PER_FRAME;
            while (!left.isEmpty()) {
                for (var actor : session.getActorsOf(left.poll())) {
                    if (filter.matches(actor)) {
                        picked.actors.putIfAbsent(actor.sourcePath(), actor);
                    }
                }
                if (System.nanoTime() > deadline) {
                    return null;
                }
            }
            return ToolResult.text("");
        };
        return EditSteps.after(walk, next);
    }

    private static Bounds3f union(Bounds3f a, Bounds3f b) {
        return new Bounds3f(
                Math.min(a.minX(), b.minX()),
                Math.min(a.minY(), b.minY()),
                Math.min(a.minZ(), b.minZ()),
                Math.max(a.maxX(), b.maxX()),
                Math.max(a.maxY(), b.maxY()),
                Math.max(a.maxZ(), b.maxZ()));
    }
}
