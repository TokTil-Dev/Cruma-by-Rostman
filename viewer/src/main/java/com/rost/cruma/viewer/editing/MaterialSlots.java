package com.rost.cruma.viewer.editing;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;
import org.jetbrains.annotations.Nullable;

public final class MaterialSlots {
    private MaterialSlots() {}

    public record Slot(
            int index,
            String name,
            String meshMaterial,
            @Nullable String override) {
        public String assignment() {
            return override == null ? "Mesh default" : EditingSession.shortName(override);
        }
    }

    public static List<Slot> of(List<String> meshMaterials, List<@Nullable String> overrides) {
        var count = meshMaterials.isEmpty() ? Math.max(1, overrides.size()) : meshMaterials.size();
        var result = new ArrayList<Slot>(count);
        for (var i = 0; i < count; i++) {
            var material = i < meshMaterials.size() ? meshMaterials.get(i) : "";
            var override = i < overrides.size() ? overrides.get(i) : null;
            result.add(new Slot(i, name(i, material), material == null ? "" : material, override));
        }
        return result;
    }

    public static @Nullable String taken(
            EditingSession session,
            String actor,
            int slot,
            List<String> meshMaterials,
            IntFunction<@Nullable String> scene) {
        var skins = session.getActor(actor).skinReferences();
        String material;
        if (slot < skins.size() && skins.get(slot) != 0) {
            material = session.getMaterialName(actor, skins.get(slot));
        } else if (slot < meshMaterials.size()) {
            material = meshMaterials.get(slot);
        } else {
            material = scene.apply(slot);
        }
        if (material == null || material.isBlank() || material.equals("Unavailable") || material.indexOf('.') <= 0) {
            return null;
        }
        return material;
    }

    static String name(int index, @Nullable String material) {
        if (material == null || material.isBlank() || material.equals("Unavailable") || material.equals("None")) {
            return "Slot " + index;
        }
        return EditingSession.shortName(material);
    }
}
