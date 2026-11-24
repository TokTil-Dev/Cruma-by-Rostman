package com.rost.cruma.viewer.agent.edit;

import static com.rost.cruma.viewer.agent.edit.EditSteps.AI;
import static com.rost.cruma.viewer.agent.edit.EditSteps.bounds;
import static com.rost.cruma.viewer.agent.edit.EditSteps.filter;
import static com.rost.cruma.viewer.agent.edit.EditSteps.limit;
import static com.rost.cruma.viewer.agent.edit.EditSteps.locate;
import static com.rost.cruma.viewer.agent.edit.EditSteps.mapOf;
import static com.rost.cruma.viewer.agent.edit.EditSteps.moveInOneStep;
import static com.rost.cruma.viewer.agent.edit.EditSteps.pick;
import static com.rost.cruma.viewer.agent.edit.EditSteps.subject;

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
import com.rost.cruma.viewer.agent.edit.EditGround.Answer;
import com.rost.cruma.viewer.agent.edit.EditGround.Ask;
import com.rost.cruma.viewer.agent.edit.EditSteps.Next;
import com.rost.cruma.viewer.agent.edit.EditSteps.Picked;
import com.rost.cruma.viewer.editing.EditingSession;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

final class ActorChangeTool {
    private final EditTools tools;

    ActorChangeTool(EditTools tools) {
        this.tools = tools;
    }

    Step change(ToolArgs args, ViewerContext viewer) throws ToolProblem {
        var action = ActorActions.of(args);
        if (!action.equals("change")) {
            return ActorActions.start(tools, action, args, viewer);
        }
        var session = EditSteps.session(viewer);
        var moveTo = moveTo(args, viewer, session);
        var faceText = args.getText("face");
        var face = faceText == null ? null : locate(viewer, session, faceText);
        var change = ActorChange.read(args, moveTo, face == null ? null : new Vector3(face.x(), face.y(), 0));
        var filter = filter(args, viewer, session);
        var limit = limit(args);
        var preview = args.readFlag("preview", false);
        var picked = new Picked();
        return EditSteps.editing(
                viewer,
                session,
                () -> pick(args, viewer, session, filter, picked, () -> {
                    var offset = change.offset(picked.actors.values());
                    var planned = new LinkedHashMap<String, ActorTransform>();
                    for (var actor : picked.actors.values()) {
                        planned.put(actor.sourcePath(), change.applied(actor.transform(), offset));
                    }
                    if (!change.toGround()) {
                        return Step.done(commit(viewer, session, change, picked, planned, preview, limit));
                    }
                    return grounded(
                            viewer,
                            session,
                            change,
                            picked,
                            planned,
                            () -> Step.done(commit(viewer, session, change, picked, planned, preview, limit)));
                }));
    }

    private static @Nullable Vector3 moveTo(ToolArgs args, ViewerContext viewer, EditingSession session)
            throws ToolProblem {
        var text = args.getText("move_to");
        if (text == null) {
            return null;
        }
        var target = locate(viewer, session, text);
        if (target.z() == null) {
            throw new ToolProblem("move_to needs a height. Give a point x,y,z, or use to_ground with move_by.");
        }
        return new Vector3(target.x(), target.y(), target.z());
    }

    private static Step grounded(
            ViewerContext viewer,
            EditingSession session,
            ActorChange change,
            Picked picked,
            Map<String, ActorTransform> planned,
            Next next) {
        var known = bounds(viewer, new HashSet<>(planned.keySet()));
        var asks = new ArrayList<Ask>();
        var paths = new ArrayList<String>();
        for (var entry : planned.entrySet()) {
            var path = entry.getKey();
            var box = known.get(path);
            if (box == null) {
                continue;
            }
            paths.add(path);
            var moved = entry.getValue().location();
            var now = session.getActor(path).transform().location();
            var x = (box.minX() + box.maxX()) * .5f + moved.x() - now.x();
            var y = (box.minY() + box.maxY()) * .5f + moved.y() - now.y();
            var top = (box.minZ() + box.maxZ()) * .5f + moved.z() - now.z();
            var source = session.getSourceTransform(path);
            if (source == null || session.getActor(path).created()) {
                asks.add(Ask.forNew(mapOf(path), x, y, top));
            } else {
                var was = source.location();
                asks.add(new Ask(
                        mapOf(path),
                        x,
                        y,
                        top,
                        (box.minX() + box.maxX()) * .5f - now.x() + was.x(),
                        (box.minY() + box.maxY()) * .5f - now.y() + was.y(),
                        box.minZ() - now.z() + was.z()));
            }
        }
        var answers = new ArrayList<@Nullable Answer>();
        return EditGround.read(viewer, session, asks, answers, () -> {
            for (var i = 0; i < paths.size(); i++) {
                var path = paths.get(i);
                var answer = answers.get(i);
                if (answer == null) {
                    picked.refused.add("No Terrain or BSP floor lies under it.", path);
                    planned.remove(path);
                    continue;
                }
                var box = known.get(path);
                var from = session.getActor(path).transform().location();
                var hangs = (box.minZ() - from.z()) * change.scale();
                planned.put(path, ActorChange.atHeight(planned.get(path), answer.bottom() - hangs));
            }
            for (var path : List.copyOf(planned.keySet())) {
                if (!known.containsKey(path)) {
                    picked.refused.add("It is not drawn yet, so its bottom is not known.", path);
                    planned.remove(path);
                }
            }
            return next.start();
        });
    }

    private static ToolResult commit(
            ViewerContext viewer,
            EditingSession session,
            ActorChange change,
            Picked picked,
            Map<String, ActorTransform> planned,
            boolean preview,
            int limit) {
        var values = new LinkedHashMap<String, ActorTransform>();
        for (var entry : planned.entrySet()) {
            var path = entry.getKey();
            var reason = change.copy()
                    ? session.copyRestriction(path)
                    : session.transformRestriction(path, entry.getValue());
            if (reason.isEmpty()) {
                values.put(path, entry.getValue());
            } else {
                picked.refused.add(reason, path);
            }
        }
        var data = new JSONObject();
        var rows = rows(picked, values, limit, preview ? bounds(viewer, shown(values, limit)) : null);
        data.put(preview ? "would_change" : "changed", values.size());
        data.put("could_not", picked.refused.getCount());
        if (picked.refused.getCount() > 0) {
            data.put("reasons", picked.refused.write());
        }
        data.put("actors", rows);
        if (values.size() > rows.size()) {
            data.put("more", values.size() - rows.size());
        }
        if (preview || values.isEmpty()) {
            return ToolResult.data(data);
        }
        var label = AI + change.verb() + " " + subject(values.keySet());
        if (change.copy()) {
            var copies = session.copyActors(values, label);
            data.put("copies", copies.subList(0, Math.min(limit, copies.size())));
            var note = session.takeStepNote();
            if (note != null) {
                data.put("note", note);
            }
        } else {
            moveInOneStep(viewer, session, values, label, data);
        }
        data.put("step", label);
        return ToolResult.data(data);
    }

    private static Set<String> shown(Map<String, ActorTransform> values, int limit) {
        var shown = new LinkedHashSet<String>();
        for (var path : values.keySet()) {
            if (shown.size() == limit) {
                break;
            }
            shown.add(path);
        }
        return shown;
    }

    private static JSONArray rows(
            Picked picked, Map<String, ActorTransform> values, int limit, @Nullable Map<String, Bounds3f> known) {
        var rows = new JSONArray();
        for (var entry : values.entrySet()) {
            if (rows.size() == limit) {
                break;
            }
            var path = entry.getKey();
            var row = new JSONObject();
            row.put("actor", path);
            row.put(
                    "before",
                    EditSteps.point(picked.actors.get(path).transform().location()));
            row.put("after", EditSteps.point(entry.getValue().location()));
            var box = known == null ? null : known.get(path);
            if (box != null) {
                row.put("mesh", picked.actors.get(path).meshSourcePath());
                row.put("bottom", EditSteps.round(box.minZ()));
            }
            rows.add(row);
        }
        return rows;
    }
}
