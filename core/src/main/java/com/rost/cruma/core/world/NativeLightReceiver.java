package com.rost.cruma.core.world;

import com.rost.cruma.core.geometry.Box;
import com.rost.cruma.core.unreal.Bsp.UModel;
import com.rost.cruma.core.unreal.UActor.StaticMeshActor;
import com.rost.cruma.core.unreal.UActor.ZoneInfoActor;
import com.rost.cruma.core.unreal.UStaticMesh;
import com.rost.cruma.core.unreal.primitives.USphere;
import glm_.mat4x4.Mat4;
import glm_.vec3.Vec3;
import org.jetbrains.annotations.Nullable;

public final class NativeLightReceiver {
    private final String actorId;
    private final USphere localSphere;
    private final USphere worldSphere;
    private final int[] leaves;
    private final Flags flags;
    private final NativeLightmapScale lightmapScale;

    private NativeLightReceiver(
            String actorId,
            USphere localSphere,
            USphere worldSphere,
            int[] leaves,
            Flags flags,
            NativeLightmapScale lightmapScale) {
        this.actorId = actorId;
        this.localSphere = localSphere;
        this.worldSphere = worldSphere;
        this.leaves = leaves.clone();
        this.flags = flags;
        this.lightmapScale = lightmapScale;
    }

    public record Flags(
            int drawType,
            boolean unlit,
            boolean specialLit,
            boolean useDynamicLights,
            boolean staticActor,
            boolean lightChanged,
            boolean hasStaticMeshInstance,
            boolean sunAffect,
            boolean unlitCheck,
            boolean culledSunlight,
            float lightIntensity,
            int maxLights) {}

    public static NativeLightReceiver capture(
            StaticMeshActor actor,
            UStaticMesh mesh,
            Mat4 matrix,
            @Nullable UModel model,
            @Nullable ZoneInfoActor levelInfo,
            int @Nullable [] nodeStack,
            boolean hasAuthoredStaticMeshInstance) {
        var sphere = mesh.getBoundingSphere();
        var localSphere = new USphere(new Vec3(sphere.location()), sphere.radius());
        var leaves = new int[0];
        if (model != null && nodeStack != null && nodeStack.length > 0) {
            var box = mesh.getBoundingBox();
            var transformed = NativeActorBounds.transformBox(box, matrix);
            var worldBox = new Box(transformed.min(), transformed.max());
            leaves = BspLeafSelection.collect(
                    model, worldBox, nodeStack, actor.getForcedRegionTag(), actor.getForcedRegion());
            leaves = BspLeafSelection.filterVisibility(model, leaves, actor.getForcedVisibilityZoneTag(), levelInfo);
        }
        var flags = new Flags(
                actor.getDrawType(),
                actor.getAmbientLightingActor().isUnlit(),
                actor.isSpecialLit(),
                actor.isUseDynamicLights(),
                actor.isStatic(),
                actor.isLightChanged(),
                hasAuthoredStaticMeshInstance,
                actor.isSunAffect(),
                actor.isUnlitCheck(),
                actor.isCulledSunlight(),
                actor.getLightIntensity(),
                actor.getMaxLights());
        return new NativeLightReceiver(
                actor.getFullName(),
                localSphere,
                transformSphere(localSphere, matrix),
                leaves,
                flags,
                NativeLightmapScale.capture(actor, model, levelInfo, leaves));
    }

    public static USphere transformSphere(USphere sphere, Mat4 transform) {
        var m = transform.to(new float[16]);
        var p = sphere.location();
        var center = new Vec3(
                ((m[0] * p.getX() + m[4] * p.getY()) + m[8] * p.getZ()) + m[12],
                ((m[5] * p.getY() + m[1] * p.getX()) + m[9] * p.getZ()) + m[13],
                ((m[6] * p.getY() + m[2] * p.getX()) + m[10] * p.getZ()) + m[14]);
        var x = (m[1] * m[1] + m[0] * m[0]) + m[2] * m[2];
        var y = (m[5] * m[5] + m[4] * m[4]) + m[6] * m[6];
        var z = (m[9] * m[9] + m[8] * m[8]) + m[10] * m[10];
        var largest = Integer.compareUnsigned(Float.floatToRawIntBits(z), Float.floatToRawIntBits(y)) < 0 ? y : z;
        largest =
                Integer.compareUnsigned(Float.floatToRawIntBits(x), Float.floatToRawIntBits(largest)) < 0 ? largest : x;
        return new USphere(center, (float) (Math.sqrt((double) largest) * (double) sphere.radius()));
    }

    public String getActorId() {
        return actorId;
    }

    public USphere getLocalSphere() {
        return localSphere;
    }

    public USphere getWorldSphere() {
        return worldSphere;
    }

    public Flags getFlags() {
        return flags;
    }

    public NativeLightmapScale getLightmapScale() {
        return lightmapScale;
    }

    public int getLeafCount() {
        return leaves.length;
    }

    public int getLeaf(int index) {
        return leaves[index];
    }
}
