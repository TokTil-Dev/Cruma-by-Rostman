package com.rost.cruma.viewer.agent.edit;

import com.rost.cruma.core.editing.ActorTransform.Vector3;
import com.rost.cruma.viewer.agent.Step;
import com.rost.cruma.viewer.agent.ToolResult;
import com.rost.cruma.viewer.agent.ViewerContext;
import com.rost.cruma.viewer.agent.edit.EditSteps.Next;
import com.rost.cruma.viewer.editing.EditingCheck;
import com.rost.cruma.viewer.editing.EditingSession;
import com.rost.cruma.viewer.editing.EditingSession.Floors;
import com.rost.cruma.viewer.editing.GroundProbe.Snapshot;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.jetbrains.annotations.Nullable;

final class EditGround {
    private static final float MOST_DROP = 8192;
    private static final float MOST_CLIMB = 2048;
    private static final float CLIMB_STEP = 32;

    private EditGround() {}

    record Ask(String map, float x, float y, float top, float sourceX, float sourceY, float sourceBottom) {
        static Ask forNew(String map, float x, float y, float top) {
            return new Ask(map, x, y, top, Float.NaN, Float.NaN, Float.NaN);
        }
    }

    record Answer(float z, String from, float clearance) {
        float bottom() {
            return z + clearance;
        }
    }

    static @Nullable Answer pick(@Nullable Float terrain, float floor) {
        var hasFloor = !Float.isNaN(floor);
        if (terrain == null && !hasFloor) {
            return null;
        }
        if (!hasFloor) {
            return new Answer(terrain, "Terrain", 0);
        }
        return terrain == null || floor > terrain ? new Answer(floor, "level", 0) : new Answer(terrain, "Terrain", 0);
    }

    static float floorUnder(Floors floors, float x, float y, float top) {
        for (var climbed = 0f; climbed <= MOST_CLIMB; climbed += CLIMB_STEP) {
            var from = top + climbed;
            var depth = floors.getSolidBelow(new Vector3(x, y, from), MOST_DROP);
            if (depth != 0) {
                return from - depth;
            }
        }
        return Float.NaN;
    }

    static float clearance(@Nullable Float terrainThere, float sourceBottom) {
        if (terrainThere == null || Float.isNaN(sourceBottom)) {
            return 0;
        }
        var clearance = sourceBottom - terrainThere;
        return clearance <= EditingCheck.TOLERANCE ? clearance : 0;
    }

    static Step read(
            ViewerContext viewer, EditingSession session, List<Ask> asks, List<@Nullable Answer> answers, Next next) {
        var probe = session.getGroundProbe();
        viewer.scene().getTerrainEditing().updateProbe(probe);
        var ground = probe.snapshot();
        var floors = new HashMap<String, Floors>();
        for (var ask : asks) {
            floors.computeIfAbsent(ask.map(), session::getFloors);
        }
        return EditSteps.after(
                Step.onWorker(() -> {
                    var found = new ArrayList<@Nullable Answer>();
                    for (var ask : asks) {
                        found.add(answer(ask, ground, floors.get(ask.map())));
                    }
                    answers.addAll(found);
                    return ToolResult.text("");
                }),
                next);
    }

    private static @Nullable Answer answer(Ask ask, Snapshot ground, Floors floors) {
        var found = pick(ground.getCurrent(ask.x(), ask.y()), floorUnder(floors, ask.x(), ask.y(), ask.top()));
        if (found == null || Float.isNaN(ask.sourceBottom())) {
            return found;
        }
        return new Answer(
                found.z(), found.from(), clearance(ground.getSaved(ask.sourceX(), ask.sourceY()), ask.sourceBottom()));
    }
}
