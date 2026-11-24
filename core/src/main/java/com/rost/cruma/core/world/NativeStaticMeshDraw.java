package com.rost.cruma.core.world;

import com.rost.cruma.core.unreal.UActor.StaticMeshActor;
import com.rost.cruma.core.unreal.UStaticMesh;
import com.rost.cruma.core.world.NativeStaticMeshLod.Mesh;
import java.util.Locale;
import java.util.Objects;

public record NativeStaticMeshDraw(
        Asset root,
        Asset selected,
        Mesh lod,
        int level,
        float actorX,
        float actorY,
        float pivotX,
        float pivotY,
        float pivotZ,
        boolean ignoredRange,
        float rangeForIgnoredRange,
        boolean unlit) {
    public NativeStaticMeshDraw {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(selected, "selected");
        Objects.requireNonNull(lod, "lod");
        if (level < 0 || level > 2) {
            throw new IllegalArgumentException("native mesh draw level must be in [0,2]");
        }
    }

    public record Asset(String id, boolean useNormalMap, boolean useVertexColor) {
        public Asset(String id, boolean useNormalMap) {
            this(id, useNormalMap, false);
        }

        public Asset {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("native mesh asset identity is required");
            }
            id = id.toLowerCase(Locale.ROOT);
        }
    }

    public static NativeStaticMeshDraw from(
            String scope, UStaticMesh root, UStaticMesh selected, StaticMeshActor actor, int level) {
        return from(
                scope,
                root,
                selected,
                actor,
                level,
                new Mesh(
                        root.isHasStaticMeshLod(),
                        root.getLodRange1(),
                        root.getLodRange2(),
                        impostorThresholdMask(root)));
    }

    static int impostorThresholdMask(UStaticMesh root) {
        return root.isHasStaticMeshLod() ? 3 : 0;
    }

    public static NativeStaticMeshDraw from(
            String scope, UStaticMesh root, UStaticMesh selected, StaticMeshActor actor, int level, Mesh lod) {
        var pivot = actor.getPrePivot();
        return new NativeStaticMeshDraw(
                new Asset(scope + ":" + root.getFullName(), root.isUseNormalMap(), root.isUseVertexColor()),
                new Asset(scope + ":" + selected.getFullName(), selected.isUseNormalMap(), selected.isUseVertexColor()),
                lod,
                level,
                actor.getLocation().getX(),
                actor.getLocation().getY(),
                pivot.getX(),
                pivot.getY(),
                pivot.getZ(),
                actor.isIgnoredRange(),
                actor.getRangeForIgnoredRange(),
                actor.getAmbientLightingActor().isUnlit());
    }
}
