package com.rost.cruma.viewer.agent.edit;

import static com.rost.cruma.viewer.agent.edit.EditSteps.AI;
import static com.rost.cruma.viewer.agent.edit.EditSteps.LIBRARY_MILLIS;
import static com.rost.cruma.viewer.agent.edit.EditSteps.bounds;
import static com.rost.cruma.viewer.agent.edit.EditSteps.doesPackageHold;
import static com.rost.cruma.viewer.agent.edit.EditSteps.limit;
import static com.rost.cruma.viewer.agent.edit.EditSteps.locate;
import static com.rost.cruma.viewer.agent.edit.EditSteps.meshOfMap;
import static com.rost.cruma.viewer.agent.edit.EditSteps.shortName;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.core.editing.ActorTransform;
import com.rost.cruma.core.editing.ActorTransform.Rotator;
import com.rost.cruma.core.editing.ActorTransform.Vector3;
import com.rost.cruma.core.world.WorldTile;
import com.rost.cruma.viewer.agent.Step;
import com.rost.cruma.viewer.agent.Target;
import com.rost.cruma.viewer.agent.ToolArgs;
import com.rost.cruma.viewer.agent.ToolProblem;
import com.rost.cruma.viewer.agent.ToolResult;
import com.rost.cruma.viewer.agent.ViewerContext;
import com.rost.cruma.viewer.agent.edit.EditGround.Answer;
import com.rost.cruma.viewer.agent.edit.EditGround.Ask;
import com.rost.cruma.viewer.agent.edit.EditSteps.Next;
import com.rost.cruma.viewer.editing.EditingSession;
import com.rost.cruma.viewer.editing.EditingSession.Placement;
import com.rost.cruma.viewer.editing.GroundProbe;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

final class ActorPlaceTool {
    private static final float ABOVE_TARGET = 64;
    private static final float ABOVE_TERRAIN = 1024;
    private static final float ABOVE_ALL = 16000;

    private final EditTools tools;

    ActorPlaceTool(EditTools tools) {
        this.tools = tools;
    }

    Step place(ToolArgs args, ViewerContext viewer) throws ToolProblem {
        if (args.has("list") || args.has("prefab")) {
            return PlaceExtras.start(args, viewer);
        }
        var session = EditSteps.session(viewer);
        var wanted = newActors(args, viewer, session);
        var onGround = args.readFlag("on_ground", false);
        var limit = limit(args);
        var loaded = EditSteps.loadedMaps(viewer, session);
        for (var actor : wanted) {
            if (!loaded.contains(actor.map)) {
                throw new ToolProblem(
                        "The map " + actor.map + " is not loaded in this viewer. Open it with world_open.");
            }
            if (!onGround && actor.target.z() == null) {
                throw new ToolProblem(actor.target.text() + " has no height. Give a point x,y,z, or use on_ground.");
            }
        }
        var maps = new LinkedHashSet<String>();
        wanted.forEach(actor -> maps.add(actor.map));
        return EditSteps.editing(
                viewer,
                session,
                () -> EditSteps.opening(
                        session,
                        maps,
                        () -> resolveMeshes(
                                viewer,
                                session,
                                wanted,
                                () -> onGround
                                        ? onGround(viewer, session, wanted, limit)
                                        : Step.done(placed(viewer, session, wanted, null, limit)))));
    }

    private Step resolveMeshes(ViewerContext viewer, EditingSession session, List<NewActor> wanted, Next next)
            throws ToolProblem {
        var waiting = new ArrayList<NewActor>();
        for (var actor : wanted) {
            actor.meshPath = meshOfMap(session, actor.map, actor.mesh);
            if (actor.meshPath == null) {
                var file = session.getPackageFile(actor.mesh);
                if (file != null && doesPackageHold(file, actor.mesh)) {
                    actor.meshPath = actor.mesh;
                    actor.packageFile = file;
                } else {
                    waiting.add(actor);
                }
            }
        }
        if (waiting.isEmpty()) {
            return next.start();
        }
        var index = tools.getLibrary(viewer, session);
        return EditSteps.after(
                Step.until(
                        index::areMeshesReady,
                        LIBRARY_MILLIS,
                        "The index of the client's meshes is not ready. Call again in a moment.",
                        () -> {
                            for (var actor : waiting) {
                                var entry = MeshMatch.find(index.getMeshes(), actor.mesh);
                                actor.meshPath = entry.path();
                                actor.packageFile = entry.packageFile();
                            }
                            return ToolResult.text("");
                        }),
                next);
    }

    private static final class NewActor {
        final String mesh;
        final Target target;
        final Rotator rotation;
        final float scale;
        final String map;

        @Nullable
        String meshPath;

        @Nullable
        Path packageFile;

        NewActor(String mesh, Target target, Rotator rotation, float scale, Set<String> loaded) {
            this.mesh = mesh;
            this.target = target;
            this.rotation = rotation;
            this.scale = scale;
            map = WorldTile.mapAt(target.x(), target.y(), loaded);
        }
    }

    private static Step onGround(ViewerContext viewer, EditingSession session, List<NewActor> wanted, int limit) {
        var probe = session.getGroundProbe();
        return EditSteps.after(
                Step.until(
                        () -> isReady(probe, wanted),
                        LIBRARY_MILLIS,
                        "The Terrain could not be read in time.",
                        () -> ToolResult.text("")),
                () -> {
                    var asks = new ArrayList<Ask>();
                    for (var actor : wanted) {
                        var z = actor.target.z();
                        var terrain = probe.getCurrent(actor.target.x(), actor.target.y());
                        var top = z != null ? z + ABOVE_TARGET : terrain != null ? terrain + ABOVE_TERRAIN : ABOVE_ALL;
                        asks.add(Ask.forNew(actor.map, actor.target.x(), actor.target.y(), top));
                    }
                    var answers = new ArrayList<@Nullable Answer>();
                    return EditGround.read(
                            viewer,
                            session,
                            asks,
                            answers,
                            () -> Step.done(placed(viewer, session, wanted, answers, limit)));
                });
    }

    private static boolean isReady(GroundProbe probe, List<NewActor> wanted) {
        for (var actor : wanted) {
            if (!probe.isReady(actor.target.x(), actor.target.y())) {
                return false;
            }
        }
        return true;
    }

    private static List<NewActor> newActors(ToolArgs args, ViewerContext viewer, EditingSession session)
            throws ToolProblem {
        var rotation = ActorChange.rotator(args, "rotation");
        var scale = (float) args.getNumber("scale", 1);
        var mesh = args.getText("mesh");
        var result = new ArrayList<NewActor>();
        var loaded = EditSteps.loadedMaps(viewer, session);
        var target = args.getText("target");
        if (target != null) {
            result.add(
                    new NewActor(required(mesh), locate(viewer, session, target), rotation, positive(scale), loaded));
        }
        for (var point : args.list("points")) {
            result.add(new NewActor(required(mesh), locate(viewer, session, point), rotation, positive(scale), loaded));
        }
        var items = args.getJson().getJSONArray("items");
        if (items != null) {
            for (var i = 0; i < items.size(); i++) {
                var item = new ToolArgs(items.getJSONObject(i));
                var turn = item.has("rotation") ? ActorChange.rotator(item, "rotation") : rotation;
                result.add(new NewActor(
                        item.getText("mesh", required(mesh)),
                        locate(viewer, session, item.getRequired("target")),
                        turn,
                        positive((float) item.getNumber("scale", scale)),
                        loaded));
            }
        }
        if (result.isEmpty()) {
            throw new ToolProblem("Say where: give target, points or items.");
        }
        return result;
    }

    private static String required(@Nullable String mesh) throws ToolProblem {
        if (mesh == null) {
            throw new ToolProblem("mesh is missing. assets_find lists the meshes of the client.");
        }
        return mesh;
    }

    private static float positive(float scale) throws ToolProblem {
        if (!(scale > 0) || !Float.isFinite(scale)) {
            throw new ToolProblem("scale must be above 0.");
        }
        return scale;
    }

    private static ToolResult placed(
            ViewerContext viewer,
            EditingSession session,
            List<NewActor> wanted,
            @Nullable List<@Nullable Answer> ground,
            int limit)
            throws ToolProblem {
        var placements = new ArrayList<Placement>();
        var refused = new Refusals();
        var kept = new ArrayList<NewActor>();
        var heights = new ArrayList<@Nullable Answer>();
        for (var i = 0; i < wanted.size(); i++) {
            var actor = wanted.get(i);
            var answer = ground == null ? null : ground.get(i);
            if (ground != null && answer == null) {
                refused.add("No Terrain or BSP floor lies under it.", actor.target.text());
                continue;
            }
            var z = answer != null ? answer.z() : actor.target.z();
            var location = new Vector3(actor.target.x(), actor.target.y(), z);
            var transform = new ActorTransform(location, actor.rotation, actor.scale, Vector3.ONE, Vector3.ZERO);
            placements.add(
                    new Placement(actor.map, actor.meshPath, actor.packageFile, transform, List.of(), ground != null));
            kept.add(actor);
            heights.add(answer);
        }
        var data = new JSONObject();
        data.put("placed", placements.size());
        data.put("could_not", refused.getCount());
        if (refused.getCount() > 0) {
            data.put("reasons", refused.write());
        }
        if (placements.isEmpty()) {
            return ToolResult.data(data);
        }
        var label = AI
                + "Place "
                + (placements.size() == 1
                        ? shortName(kept.getFirst().meshPath)
                        : placements.size() + " " + shortName(kept.getFirst().meshPath));
        var paths = session.placeMany(placements, label, ground == null ? null : path -> bottom(viewer, path));
        var rows = new JSONArray();
        for (var i = 0; i < paths.size() && i < limit; i++) {
            var row = new JSONObject();
            var path = paths.get(i);
            row.put("actor", path);
            row.put("mesh", kept.get(i).meshPath);
            row.put("at", EditSteps.point(session.getActor(path).transform().location()));
            var answer = heights.get(i);
            if (answer != null) {
                row.put("ground", EditSteps.round(answer.z()));
                row.put("ground_is", answer.from());
            }
            rows.add(row);
        }
        data.put("step", label);
        data.put("actors", rows);
        if (paths.size() > rows.size()) {
            data.put("more", paths.size() - rows.size());
        }
        return ToolResult.data(data);
    }

    private static @Nullable Float bottom(ViewerContext viewer, String actor) {
        var box = bounds(viewer, Set.of(actor)).get(actor);
        return box == null ? null : box.minZ();
    }
}
