package com.rost.cruma.core.world;

import com.rost.cruma.core.world.NativeStaticMeshDraw.Asset;
import java.util.HashSet;
import java.util.Set;

public final class NativeStaticMeshTangentState {
    private final Set<String> loaded = new HashSet<>();

    public record Decision(boolean visible, boolean loadSelected, boolean useTangent) {}

    public Decision enter(
            NativeStaticMeshDraw draw, int selectedLevel, boolean shaderEnabled, boolean viewLit, int editor) {
        if (selectedLevel != draw.level() || selectedLevel < 0) {
            return new Decision(false, false, false);
        }
        var load = shaderEnabled && draw.selected().useNormalMap() && !isLoaded(draw.selected());
        if (load) {
            loaded.add(draw.selected().id());
        }
        var use = isLoaded(draw.root()) && !draw.unlit() && viewLit && (selectedLevel != 2 || editor != 0);
        return new Decision(true, load, use);
    }

    public boolean isLoaded(Asset asset) {
        return loaded.contains(asset.id());
    }

    public void restore(Asset asset, boolean wasLoaded) {
        if (wasLoaded) {
            loaded.add(asset.id());
        } else {
            loaded.remove(asset.id());
        }
    }

    public void destroy(Asset asset) {
        loaded.remove(asset.id());
    }
}
