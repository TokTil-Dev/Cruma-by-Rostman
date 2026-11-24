package com.rost.cruma.viewer.render.api;

import com.rost.cruma.core.geometry.Bounds3f;
import java.util.List;

public record DebugSceneObject(
        String shortName,
        String assetName,
        String materialName,
        String owner,
        int surfaceOrdinal,
        int instanceOrdinal,
        Bounds3f bounds,
        Category category,
        String lighting,
        long renderObjectId,
        String actorName,
        List<DebugMaterialSlot> materials,
        String animation,
        int sourcePartOrdinal,
        boolean actorAnimated) {
    public DebugSceneObject(
            String shortName,
            String assetName,
            String materialName,
            String owner,
            int surfaceOrdinal,
            int instanceOrdinal,
            Bounds3f bounds,
            Category category,
            String lighting,
            long renderObjectId,
            String actorName,
            List<DebugMaterialSlot> materials,
            String animation,
            int sourcePartOrdinal) {
        this(
                shortName,
                assetName,
                materialName,
                owner,
                surfaceOrdinal,
                instanceOrdinal,
                bounds,
                category,
                lighting,
                renderObjectId,
                actorName,
                materials,
                animation,
                sourcePartOrdinal,
                false);
    }

    public DebugSceneObject(
            String shortName,
            String assetName,
            String materialName,
            String owner,
            int surfaceOrdinal,
            int instanceOrdinal,
            Bounds3f bounds,
            Category category,
            String lighting,
            long renderObjectId,
            String actorName,
            List<DebugMaterialSlot> materials,
            String animation) {
        this(
                shortName,
                assetName,
                materialName,
                owner,
                surfaceOrdinal,
                instanceOrdinal,
                bounds,
                category,
                lighting,
                renderObjectId,
                actorName,
                materials,
                animation,
                -1);
    }

    public DebugSceneObject(
            String shortName,
            String assetName,
            String materialName,
            String owner,
            int surfaceOrdinal,
            int instanceOrdinal,
            Bounds3f bounds,
            Category category,
            String lighting) {
        this(
                shortName,
                assetName,
                materialName,
                owner,
                surfaceOrdinal,
                instanceOrdinal,
                bounds,
                category,
                lighting,
                0L,
                "Unavailable",
                List.of(),
                "Unavailable",
                -1);
    }

    public DebugSceneObject {
        materials = List.copyOf(materials);
        if (shortName == null
                || shortName.isBlank()
                || assetName == null
                || assetName.isBlank()
                || materialName == null
                || materialName.isBlank()
                || owner == null
                || owner.isBlank()
                || surfaceOrdinal < 0
                || instanceOrdinal < -1
                || sourcePartOrdinal < -1
                || bounds == null
                || category == null
                || lighting == null
                || lighting.isBlank()) {
            throw new IllegalArgumentException("invalid debug scene object");
        }
    }

    public String fullName() {
        var instance = instanceOrdinal < 0 ? "" : " [instance " + instanceOrdinal + ']';
        var actor = "Unavailable".equals(actorName) ? "" : actorName + " / ";
        return owner
                + " / "
                + actor
                + assetName
                + " / "
                + materialName
                + " [surface "
                + surfaceOrdinal
                + ']'
                + instance;
    }

    public String key() {
        return owner
                + "/"
                + Long.toUnsignedString(renderObjectId, 16)
                + "/"
                + instanceOrdinal
                + "/"
                + surfaceOrdinal
                + (sourcePartOrdinal < 0 ? "" : "/part/" + sourcePartOrdinal);
    }

    public enum Category {
        CSG,
        STATIC_MESH,
        TERRAIN
    }
}
