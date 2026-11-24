package com.rost.cruma.core.world;

import com.rost.cruma.core.unreal.Bsp.UModel;
import com.rost.cruma.core.unreal.UActor;
import com.rost.cruma.core.unreal.UActor.ZoneInfoActor;
import org.jetbrains.annotations.Nullable;

public record NativeLightmapScale(boolean zoneOverride, float value) {
    public static final NativeLightmapScale ENVIRONMENT = new NativeLightmapScale(false, 0f);

    public static NativeLightmapScale fromZone(@Nullable ZoneInfoActor zone) {
        if (zone == null || zone.isSunAffect()) {
            return ENVIRONMENT;
        }
        return new NativeLightmapScale(true, zone.isUseZoneLightMapIntensity() ? zone.getZoneLightMapIntensity() : .6f);
    }

    static NativeLightmapScale capture(
            UActor actor, @Nullable UModel model, @Nullable ZoneInfoActor levelInfo, int[] actorLeaves) {
        if (!BspLeafSelection.hasTag(actor.getForcedRegionTag())) {
            return fromZone(actor.getRegionZone());
        }
        var selected = (ZoneInfoActor) null;
        if (model != null && model.getLeaves() != null) {
            var leaves = model.getLeaves();
            var zones = model.getZones();
            for (var index : actorLeaves) {
                if (index < 0 || index >= leaves.length || leaves[index].permeating() == -1) {
                    continue;
                }
                var zone = leaves[index].zone();
                selected = zones == null || zone < 0 || zone >= zones.length
                        ? null
                        : zones[zone].getZoneActor().get();
                if (selected == null) {
                    selected = levelInfo;
                }
            }
        }
        return fromZone(selected);
    }

    public float resolve(float environmentValue) {
        return zoneOverride ? value : environmentValue;
    }
}
