package com.rost.cruma.core.world;

public record NativeStaticMeshLightingFeatures(
        boolean modulate2x, boolean vertexColor, boolean dynamicLight, boolean lightmap) {
    public static NativeStaticMeshLightingFeatures select(
            boolean modulate2x, boolean acceptedInstance, boolean useTangent, boolean beastUv, boolean setLightCalled) {
        return new NativeStaticMeshLightingFeatures(
                modulate2x, acceptedInstance, !useTangent || setLightCalled || beastUv, beastUv);
    }

    public int materialFactor() {
        return modulate2x && (vertexColor || dynamicLight) ? 2 : 1;
    }

    public int colorModifierFactor() {
        return modulate2x ? 2 : 1;
    }
}
