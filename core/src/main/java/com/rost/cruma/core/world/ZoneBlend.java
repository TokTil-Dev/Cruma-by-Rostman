package com.rost.cruma.core.world;

import com.rost.cruma.core.unreal.UActor.StateBlendInfo;
import com.rost.cruma.core.unreal.UActor.ZoneInfoActor;
import java.util.List;
import org.jetbrains.annotations.Nullable;

public record ZoneBlend(String zone, List<StateBlendInfo> entries, int previousZoneState, int blendingState) {
    public ZoneBlend {
        entries = List.copyOf(entries);
    }

    public static @Nullable ZoneBlend of(@Nullable ZoneInfoActor zone) {
        if (zone == null || !zone.isUseZoneState() || !zone.isUseBlendZoneState()) {
            return null;
        }
        return new ZoneBlend(
                zone.getFullName(), zone.getStateBlendInfos(), zone.getPreviousZoneState(), zone.getBlendingState());
    }
}
