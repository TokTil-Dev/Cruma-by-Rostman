package com.rost.cruma.core.world;

import com.rost.cruma.core.geometry.Box;
import com.rost.cruma.core.unreal.Bsp.UModel;
import com.rost.cruma.core.world.Entity.DistanceFog;
import glm_.mat4x4.Mat4;
import glm_.vec3.Vec3;
import glm_.vec4.Vec4;

public final class WaterVolume {
    private static final DistanceFog HF_CLASS_DEFAULT_FOG =
            new DistanceFog(new Vec3(32f / 255f, 64f / 255f, 128f / 255f), 8f, 2_000f);
    private static final Vec4 HF_CLASS_DEFAULT_FOG_COLOR = new Vec4(32f / 255f, 64f / 255f, 128f / 255f, 64f / 255f);

    private final BrushVolume shape;
    private final Vec4 cellophaneColor;
    private final FogSettings fogSettings;
    private final boolean l2WaterVolume;
    private final int priority;
    private final WorldTile ownerTile;
    private final ZoneRender zoneRender;

    public WaterVolume(UModel brush, Mat4 brushToWorld) {
        this(brush, brushToWorld, null, defaultFogSettings(), true);
    }

    public WaterVolume(UModel brush, Mat4 brushToWorld, Vec4 cellophaneColor) {
        this(brush, brushToWorld, cellophaneColor, defaultFogSettings(), true);
    }

    public WaterVolume(UModel brush, Mat4 brushToWorld, Vec4 cellophaneColor, FogSettings fogSettings) {
        this(brush, brushToWorld, cellophaneColor, fogSettings, true);
    }

    public WaterVolume(
            UModel brush, Mat4 brushToWorld, Vec4 cellophaneColor, FogSettings fogSettings, boolean l2WaterVolume) {
        this(brush, brushToWorld, cellophaneColor, fogSettings, l2WaterVolume, 0, null);
    }

    public WaterVolume(
            UModel brush,
            Mat4 brushToWorld,
            Vec4 cellophaneColor,
            FogSettings fogSettings,
            boolean l2WaterVolume,
            int priority,
            WorldTile ownerTile) {
        this(brush, brushToWorld, cellophaneColor, fogSettings, l2WaterVolume, priority, ownerTile, null);
    }

    public WaterVolume(
            UModel brush,
            Mat4 brushToWorld,
            Vec4 cellophaneColor,
            FogSettings fogSettings,
            boolean l2WaterVolume,
            int priority,
            WorldTile ownerTile,
            ZoneRender zoneRender) {
        this.shape = new BrushVolume(brush, brushToWorld);
        this.cellophaneColor = cellophaneColor == null ? null : new Vec4(cellophaneColor);
        this.fogSettings = copyFogSettings(fogSettings);
        this.l2WaterVolume = l2WaterVolume;
        this.priority = priority;
        this.ownerTile = ownerTile;
        this.zoneRender = zoneRender == null ? ZoneRender.EVERY_STATE : zoneRender;
    }

    public WaterVolume(Box worldBounds, Mat4 worldToBrush, float[] planes, int[] front, int[] back) {
        this(worldBounds, worldToBrush, planes, front, back, null, defaultFogSettings(), true);
    }

    public WaterVolume(
            Box worldBounds, Mat4 worldToBrush, float[] planes, int[] front, int[] back, Vec4 cellophaneColor) {
        this(worldBounds, worldToBrush, planes, front, back, cellophaneColor, defaultFogSettings(), true);
    }

    public WaterVolume(
            Box worldBounds,
            Mat4 worldToBrush,
            float[] planes,
            int[] front,
            int[] back,
            Vec4 cellophaneColor,
            FogSettings fogSettings) {
        this(worldBounds, worldToBrush, planes, front, back, cellophaneColor, fogSettings, true);
    }

    public WaterVolume(
            Box worldBounds,
            Mat4 worldToBrush,
            float[] planes,
            int[] front,
            int[] back,
            Vec4 cellophaneColor,
            FogSettings fogSettings,
            boolean l2WaterVolume) {
        this(worldBounds, worldToBrush, planes, front, back, cellophaneColor, fogSettings, l2WaterVolume, 0, null);
    }

    public WaterVolume(
            Box worldBounds,
            Mat4 worldToBrush,
            float[] planes,
            int[] front,
            int[] back,
            Vec4 cellophaneColor,
            FogSettings fogSettings,
            boolean l2WaterVolume,
            int priority,
            WorldTile ownerTile) {
        this(
                worldBounds,
                worldToBrush,
                planes,
                front,
                back,
                cellophaneColor,
                fogSettings,
                l2WaterVolume,
                priority,
                ownerTile,
                null);
    }

    public WaterVolume(
            Box worldBounds,
            Mat4 worldToBrush,
            float[] planes,
            int[] front,
            int[] back,
            Vec4 cellophaneColor,
            FogSettings fogSettings,
            boolean l2WaterVolume,
            int priority,
            WorldTile ownerTile,
            ZoneRender zoneRender) {
        this.shape = new BrushVolume(worldBounds, worldToBrush, planes, front, back);
        this.cellophaneColor = cellophaneColor == null ? null : new Vec4(cellophaneColor);
        this.fogSettings = copyFogSettings(fogSettings);
        this.l2WaterVolume = l2WaterVolume;
        this.priority = priority;
        this.ownerTile = ownerTile;
        this.zoneRender = zoneRender == null ? ZoneRender.EVERY_STATE : zoneRender;
    }

    public record FogSettings(boolean enabled, boolean useOwnValues, DistanceFog ownValues, Vec4 ownColor) {
        public FogSettings {
            if (ownValues == null) {
                throw new IllegalArgumentException("a PhysicsVolume always carries its own fog values");
            }
            if (ownColor == null) {
                throw new IllegalArgumentException("a PhysicsVolume always carries its full fog RGBA");
            }
            ownValues = copyFog(ownValues);
            ownColor = new Vec4(ownColor);
        }

        public FogSettings(boolean enabled, boolean useOwnValues, DistanceFog ownValues) {
            this(enabled, useOwnValues, ownValues, new Vec4(ownValues.color(), 1f));
        }

        @Override
        public DistanceFog ownValues() {
            return copyFog(ownValues);
        }

        @Override
        public Vec4 ownColor() {
            return new Vec4(ownColor);
        }
    }

    public int getPriority() {
        return priority;
    }

    public WorldTile getOwnerTile() {
        return ownerTile;
    }

    public Box getWorldBounds() {
        return shape.getWorldBounds();
    }

    public Vec4 getCellophaneColor() {
        return cellophaneColor == null ? null : new Vec4(cellophaneColor);
    }

    public FogSettings getFogSettings() {
        return copyFogSettings(fogSettings);
    }

    public boolean isL2WaterVolume() {
        return l2WaterVolume;
    }

    public ZoneRender getZoneRender() {
        return zoneRender;
    }

    public boolean isWaterInZoneState(ZoneStateChoice choice) {
        var chosen = zoneRender.choiceIn(choice);
        return chosen == MapEventState.AS_THE_MAP || zoneRender.shows(chosen);
    }

    Mat4 getWorldToBrush() {
        return shape.getWorldToBrush();
    }

    float[] getPlanes() {
        return shape.getPlanes();
    }

    int[] getFront() {
        return shape.getFront();
    }

    int[] getBack() {
        return shape.getBack();
    }

    public boolean contains(Vec3 point) {
        return shape.contains(point);
    }

    private static FogSettings defaultFogSettings() {
        return new FogSettings(true, false, HF_CLASS_DEFAULT_FOG, HF_CLASS_DEFAULT_FOG_COLOR);
    }

    private static FogSettings copyFogSettings(FogSettings settings) {
        if (settings == null) {
            throw new IllegalArgumentException("fogSettings must not be null");
        }
        return new FogSettings(settings.enabled(), settings.useOwnValues(), settings.ownValues(), settings.ownColor());
    }

    private static DistanceFog copyFog(DistanceFog fog) {
        return new DistanceFog(new Vec3(fog.color()), fog.start(), fog.end());
    }
}
