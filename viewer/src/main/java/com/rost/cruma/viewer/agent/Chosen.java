package com.rost.cruma.viewer.agent;

import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.core.geometry.Bounds3f;
import com.rost.cruma.viewer.agent.Target.Kind;
import com.rost.cruma.viewer.agent.view.ScenePlacement;
import com.rost.cruma.viewer.render.api.DebugSceneObject;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

public record Chosen(
        String kind,
        float x,
        float y,
        float z,
        @Nullable Bounds3f bounds,
        @Nullable DebugSceneObject object,
        JSONObject details) {
    public static @Nullable Chosen current(ViewerContext viewer) {
        var chosen = editing(viewer);
        if (chosen == null) {
            chosen = object(viewer);
        }
        if (chosen == null) {
            chosen = spawn(viewer);
        }
        return chosen != null ? chosen : cell(viewer);
    }

    Located located(String text) {
        return new Located(new Target(Kind.POINT, text, x, y, z), bounds, object);
    }

    private static @Nullable Chosen editing(ViewerContext viewer) {
        var session = viewer.editing();
        if (session == null || session.getSelection().isEmpty()) {
            return null;
        }
        var primary = session.getPrimary();
        var rows = viewer.backend().getEditorActorObjects(Set.of(primary));
        var details = new JSONObject();
        details.put("actor", primary);
        details.put("selected", session.getSelection().size());
        if (rows.isEmpty()) {
            var record = session.getActor(primary);
            var place = record.transform().location();
            return new Chosen("actor", place.x(), place.y(), place.z(), null, null, details);
        }
        var placement = ScenePlacement.of(rows.getFirst());
        for (var row : rows.subList(1, rows.size())) {
            placement = placement.with(row);
        }
        var box = placement.bounds();
        details.put("mesh", placement.mesh());
        return new Chosen("actor", box.centerX(), box.centerY(), box.centerZ(), box, rows.getFirst(), details);
    }

    private static @Nullable Chosen object(ViewerContext viewer) {
        var selected = viewer.scene().inspection.selected;
        if (selected == null) {
            return null;
        }
        var box = selected.bounds();
        if (selected.sceneObject() != null) {
            var rows = viewer.backend().getEditorActorObjects(Set.of(selected.actor()));
            if (!rows.isEmpty()) {
                var whole = ScenePlacement.of(rows.getFirst());
                for (var row : rows.subList(1, rows.size())) {
                    whole = whole.with(row);
                }
                box = whole.bounds();
            }
        }
        var details = new JSONObject();
        details.put("name", selected.shortName());
        details.put("mesh", selected.assetName());
        details.put("type", selected.typeName());
        if (selected.sceneObject() != null) {
            var placement = ScenePlacement.of(selected.sceneObject());
            details.put("actor", placement.actor());
            details.put("class", placement.className());
        } else {
            details.put("actor", selected.actor());
        }
        return new Chosen("object", box.centerX(), box.centerY(), box.centerZ(), box, selected.sceneObject(), details);
    }

    private static @Nullable Chosen spawn(ViewerContext viewer) {
        var spawn = viewer.system().getSpawns().getSelectedSpawn();
        if (spawn == null) {
            return null;
        }
        var details = new JSONObject();
        details.put("npc", spawn.npc());
        details.put("maker", spawn.maker());
        details.put("fixed", spawn.fixed());
        details.put("radius", spawn.radius());
        return new Chosen("spawn", (float) spawn.x(), (float) spawn.y(), (float) spawn.z(), null, null, details);
    }

    private static @Nullable Chosen cell(ViewerContext viewer) {
        var cell = viewer.scene().geodata.inspection.selected;
        if (cell == null) {
            return null;
        }
        var center = cell.center();
        var details = new JSONObject();
        details.put("height", cell.height());
        details.put("nswe", cell.nswe());
        details.put("layer", cell.ref().layer());
        return new Chosen(
                "geodata cell", (float) center.x(), (float) center.y(), (float) center.z(), null, null, details);
    }
}
