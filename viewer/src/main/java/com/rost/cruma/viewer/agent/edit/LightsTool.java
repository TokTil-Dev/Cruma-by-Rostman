package com.rost.cruma.viewer.agent.edit;

import static com.rost.cruma.viewer.agent.edit.EditSteps.AI;
import static com.rost.cruma.viewer.agent.edit.EditSteps.DEFAULT_LIMIT;
import static com.rost.cruma.viewer.agent.edit.EditSteps.DEFAULT_RADIUS;
import static com.rost.cruma.viewer.agent.edit.EditSteps.limit;
import static com.rost.cruma.viewer.agent.edit.EditSteps.locate;
import static com.rost.cruma.viewer.agent.edit.EditSteps.mapOf;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.core.editing.ActorTransform.Vector3;
import com.rost.cruma.core.editing.LightColor;
import com.rost.cruma.core.editing.LightPlacement;
import com.rost.cruma.core.editing.LightRecord;
import com.rost.cruma.core.editing.LightShape;
import com.rost.cruma.core.world.WorldTile;
import com.rost.cruma.viewer.agent.Step;
import com.rost.cruma.viewer.agent.ToolArgs;
import com.rost.cruma.viewer.agent.ToolProblem;
import com.rost.cruma.viewer.agent.ToolResult;
import com.rost.cruma.viewer.agent.ViewerContext;
import com.rost.cruma.viewer.editing.EditingSession;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

final class LightsTool {
    private LightsTool() {}

    static Step lights(ToolArgs args, ViewerContext viewer) throws ToolProblem {
        var session = EditSteps.session(viewer);
        var action = args.getText("action", "list");
        var maps = lightMaps(args, viewer, session);
        return EditSteps.editing(
                viewer,
                session,
                () -> EditSteps.opening(
                        session,
                        maps,
                        () -> Step.done(
                                switch (action) {
                                    case "list" -> listLights(args, viewer, session);
                                    case "add" -> addLight(args, viewer, session);
                                    case "copy" -> copyLights(args, viewer, session);
                                    case "change" -> changeLights(args, viewer, session);
                                    case "delete" ->
                                        eachLight(
                                                args,
                                                session,
                                                "Delete",
                                                session::getLightDeleteRestriction,
                                                (path, label) -> session.deleteLight(path, label));
                                    case "revert", "reset" ->
                                        eachLight(
                                                args,
                                                session,
                                                "Revert",
                                                session::getLightMoveRestriction,
                                                session::resetLight);
                                    default ->
                                        throw new ToolProblem(
                                                "action must be list, add, copy, change, delete or revert.");
                                })));
    }

    private static Set<String> lightMaps(ToolArgs args, ViewerContext viewer, EditingSession session)
            throws ToolProblem {
        var loaded = EditSteps.loadedMaps(viewer, session);
        var maps = new LinkedHashSet<String>();
        for (var path : args.list("lights")) {
            if (loaded.contains(mapOf(path))) {
                maps.add(mapOf(path));
            }
        }
        var middle = locate(viewer, session, args.getText("target", "camera"));
        maps.addAll(new ActorFilter(null, null, null, middle, (float) args.getNumber("radius", DEFAULT_RADIUS))
                .maps(loaded));
        return maps;
    }

    private static ToolResult listLights(ToolArgs args, ViewerContext viewer, EditingSession session)
            throws ToolProblem {
        var middle = locate(viewer, session, args.getText("target", "camera"));
        var radius = (float) args.getNumber("radius", DEFAULT_RADIUS);
        var filter = new ActorFilter(null, null, null, middle, radius);
        var limit = limit(args);
        var found = new ArrayList<LightRecord>();
        for (var map : filter.maps(EditSteps.loadedMaps(viewer, session))) {
            for (var light : session.getLights(map)) {
                if (Math.hypot(
                                light.location().x() - middle.x(),
                                light.location().y() - middle.y())
                        <= radius) {
                    found.add(light);
                }
            }
        }
        found.sort((a, b) -> Double.compare(
                Math.hypot(a.location().x() - middle.x(), a.location().y() - middle.y()),
                Math.hypot(b.location().x() - middle.x(), b.location().y() - middle.y())));
        var rows = new JSONArray();
        for (var light : found.subList(0, Math.min(limit, found.size()))) {
            rows.add(row(light));
        }
        var data = new JSONObject();
        data.put("count", found.size());
        data.put("lights", rows);
        return ToolResult.data(data);
    }

    private static JSONObject row(LightRecord light) {
        var row = new JSONObject();
        row.put("light", light.sourcePath());
        row.put("class", light.className());
        row.put("at", EditSteps.point(light.location()));
        row.put("radius", EditSteps.round(light.radius()));
        LightPaint.write(light.color(), row);
        LightAim.write(light.shape(), row);
        if (!light.restriction().isEmpty()) {
            row.put("restriction", light.restriction());
        }
        if (light.isChanged()) {
            row.put("changed", true);
        }
        return row;
    }

    private static ToolResult addLight(ToolArgs args, ViewerContext viewer, EditingSession session) throws ToolProblem {
        var target = locate(viewer, session, args.getRequired("target"));
        if (target.z() == null) {
            throw new ToolProblem("A light needs a height. Give a point x,y,z.");
        }
        var loaded = EditSteps.loadedMaps(viewer, session);
        var map = WorldTile.mapAt(target.x(), target.y(), loaded);
        if (!loaded.contains(map)) {
            throw new ToolProblem("The map " + map + " is not loaded in this viewer. Open it with world_open.");
        }
        var spot = args.readFlag("spot", false);
        var aim = aimOf(args, viewer, session);
        if (aim.any() && !spot) {
            throw new ToolProblem("yaw, pitch, face and cone are for a spotlight. Add it with spot.");
        }
        var template = session.getLightTemplate(map, spot);
        if (!template.restriction().isEmpty()) {
            throw new ToolProblem(template.restriction());
        }
        var model = session.getLight(template.path()).orElseThrow();
        var location = new Vector3(target.x(), target.y(), target.z());
        var placement = new LightPlacement(location, radiusOf(args, model.radius()));
        var color = colorOf(args, model.color());
        var restriction = session.getLightPlacementRestriction(template.path(), placement);
        if (!restriction.isEmpty()) {
            throw new ToolProblem(restriction);
        }
        var down = new Vector3(0, 0, -1);
        LightShape aimed = null;
        if (aim.any()) {
            aimed = aim.apply(model.shape().withRotation(model.shape().facing(down)), location);
            var reason = session.getLightShapeRestriction(template.path(), aimed);
            if (!reason.isEmpty()) {
                throw new ToolProblem(reason + ".");
            }
        }
        var label = AI + (spot ? "Add spotlight" : "Add light");
        session.beginStep();
        String path;
        try {
            path = session.addLight(template.path(), placement, color, spot ? down : null, label);
            if (aimed != null) {
                session.setLightShape(path, aimed, label);
            }
        } finally {
            session.endStep(label);
        }
        var data = row(session.getLight(path).orElseThrow());
        data.put("step", label);
        return ToolResult.data(data);
    }

    private static LightAim aimOf(ToolArgs args, ViewerContext viewer, EditingSession session) throws ToolProblem {
        var faceText = args.getText("face");
        Vector3 face = null;
        if (faceText != null) {
            var point = locate(viewer, session, faceText);
            if (point.z() == null) {
                throw new ToolProblem("face needs a height. Give a point x,y,z or an actor.");
            }
            face = new Vector3(point.x(), point.y(), point.z());
        }
        return LightAim.of(args, face);
    }

    private static float radiusOf(ToolArgs args, float fallback) throws ToolProblem {
        var radius = args.getNumber("radius", fallback);
        if (radius < 0 || radius > LightPlacement.MAX_RADIUS) {
            throw new ToolProblem("radius must be between 0 and " + (int) LightPlacement.MAX_RADIUS + ".");
        }
        return (float) radius;
    }

    private static LightColor colorOf(ToolArgs args, LightColor fallback) throws ToolProblem {
        return LightPaint.of(args, fallback);
    }

    private static ToolResult copyLights(ToolArgs args, ViewerContext viewer, EditingSession session)
            throws ToolProblem {
        var targetText = args.getText("target");
        var target = targetText == null ? null : locate(viewer, session, targetText);
        if (target != null && target.z() == null) {
            throw new ToolProblem("A light needs a height. Give a point x,y,z.");
        }
        var refused = new Refusals();
        var paths = lightPaths(args, session, refused);
        if (target != null && paths.size() > 1) {
            throw new ToolProblem(
                    "target places one copy. Name one light, or leave target out to copy each beside its light.");
        }
        var placements = new LinkedHashMap<String, LightPlacement>();
        for (var path : paths) {
            var reason = session.getLightCopyRestriction(path);
            var light = session.getLight(path).orElseThrow();
            var place = target == null
                    ? session.placeBeside(path)
                    : new LightPlacement(
                            new Vector3(target.x(), target.y(), target.z()), radiusOf(args, light.radius()));
            if (reason.isEmpty() && place == null) {
                reason = "No place beside it is free of solid BSP";
            }
            if (reason.isEmpty()) {
                reason = session.getLightPlacementRestriction(path, place);
            }
            if (reason.isEmpty()) {
                placements.put(path, place);
            } else {
                refused.add(reason, path);
            }
        }
        var data = new JSONObject();
        data.put("copied", placements.size());
        data.put("could_not", refused.getCount());
        if (refused.getCount() > 0) {
            data.put("reasons", refused.write());
        }
        if (placements.isEmpty()) {
            return ToolResult.data(data);
        }
        var label = AI + (placements.size() == 1 ? "Copy light" : "Copy " + placements.size() + " lights");
        var rows = new JSONArray();
        for (var copy : session.duplicateLights(placements, label)) {
            session.getLight(copy).ifPresent(light -> rows.add(row(light)));
        }
        data.put("step", label);
        data.put("lights", rows.subList(0, Math.min(DEFAULT_LIMIT, rows.size())));
        return ToolResult.data(data);
    }

    @FunctionalInterface
    private interface LightRestriction {
        String of(String path);
    }

    @FunctionalInterface
    private interface LightAction {
        void run(String path, String label);
    }

    private static List<String> lightPaths(ToolArgs args, EditingSession session, Refusals refused) throws ToolProblem {
        var named = args.list("lights");
        if (named.isEmpty()) {
            throw new ToolProblem("Name the lights: give lights, paths from the action list.");
        }
        var known = new ArrayList<String>();
        for (var path : named) {
            if (session.getLights(mapOf(path)).stream()
                    .anyMatch(light -> light.sourcePath().equals(path))) {
                known.add(path);
            } else {
                refused.add("There is no such light.", path);
            }
        }
        return known;
    }

    private static ToolResult eachLight(
            ToolArgs args, EditingSession session, String verb, LightRestriction restriction, LightAction action)
            throws ToolProblem {
        var refused = new Refusals();
        var chosen = new ArrayList<String>();
        for (var path : lightPaths(args, session, refused)) {
            var reason = restriction.of(path);
            if (reason.isEmpty()) {
                chosen.add(path);
            } else {
                refused.add(reason, path);
            }
        }
        var label = AI + verb + " " + (chosen.size() == 1 ? "light" : chosen.size() + " lights");
        return lightsAnswer(session, chosen, refused, label, path -> action.run(path, label));
    }

    private static ToolResult changeLights(ToolArgs args, ViewerContext viewer, EditingSession session)
            throws ToolProblem {
        var refused = new Refusals();
        var chosen = new ArrayList<String>();
        var targetText = args.getText("target");
        var target = targetText == null ? null : locate(viewer, session, targetText);
        if (target != null && target.z() == null) {
            throw new ToolProblem("A light needs a height. Give a point x,y,z.");
        }
        var colors = LightPaint.isAsked(args);
        var places = target != null || args.has("radius");
        var aim = aimOf(args, viewer, session);
        if (!colors && !places && !aim.any()) {
            throw new ToolProblem(
                    "Say what to change: brightness, preset, temperature, saturation_pct, hue, saturation, radius,"
                            + " target, yaw, pitch, face or cone.");
        }
        var newColors = new HashMap<String, LightColor>();
        var newPlaces = new HashMap<String, LightPlacement>();
        var newShapes = new HashMap<String, LightShape>();
        for (var path : lightPaths(args, session, refused)) {
            var light = session.getLight(path).orElseThrow();
            var color = colorOf(args, light.color());
            var location = target == null ? light.location() : new Vector3(target.x(), target.y(), target.z());
            var placement = new LightPlacement(location, radiusOf(args, light.radius()));
            var reason = colors ? session.getLightColorRestriction(path, color) : "";
            if (reason.isEmpty() && places) {
                reason = session.getLightPlacementRestriction(path, placement);
            }
            LightShape shape = null;
            if (reason.isEmpty() && aim.any()) {
                try {
                    shape = aim.apply(light.shape(), location);
                    reason = session.getLightShapeRestriction(path, shape);
                } catch (ToolProblem wrong) {
                    reason = wrong.getMessage();
                }
            }
            if (reason.isEmpty()) {
                chosen.add(path);
                newColors.put(path, color);
                newPlaces.put(path, placement);
                if (shape != null) {
                    newShapes.put(path, shape);
                }
            } else {
                refused.add(reason, path);
            }
        }
        var label = AI + "Change " + (chosen.size() == 1 ? "light" : chosen.size() + " lights");
        return lightsAnswer(session, chosen, refused, label, path -> {
            if (colors) {
                session.setLightColor(path, newColors.get(path), label);
            }
            if (places) {
                session.setLightPlacement(path, newPlaces.get(path), label);
            }
            if (newShapes.containsKey(path)) {
                session.setLightShape(path, newShapes.get(path), label);
            }
        });
    }

    private static ToolResult lightsAnswer(
            EditingSession session, List<String> chosen, Refusals refused, String label, Consumer<String> action) {
        var data = new JSONObject();
        data.put("changed", chosen.size());
        data.put("could_not", refused.getCount());
        if (refused.getCount() > 0) {
            data.put("reasons", refused.write());
        }
        if (chosen.isEmpty()) {
            return ToolResult.data(data);
        }
        session.beginStep();
        try {
            chosen.forEach(action);
        } finally {
            session.endStep(label);
        }
        var rows = new JSONArray();
        for (var path : chosen.subList(0, Math.min(DEFAULT_LIMIT, chosen.size()))) {
            session.getLight(path).ifPresent(light -> rows.add(row(light)));
        }
        data.put("step", label);
        data.put("lights", rows);
        return ToolResult.data(data);
    }
}
