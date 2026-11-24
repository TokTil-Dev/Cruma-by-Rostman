package com.rost.cruma.viewer.render;

public final class WaterRenderRoute {
    public static final int AUTHORED = 0;
    public static final int PS19 = 1;
    public static final int LEGACY_REFLECTION = 2;

    private WaterRenderRoute() {}

    public static int select(boolean l2Shader, boolean gl2ShaderWater, int l2WaterEffectType) {
        if (l2Shader) {
            return gl2ShaderWater ? PS19 : AUTHORED;
        }
        return l2WaterEffectType != 0 ? LEGACY_REFLECTION : AUTHORED;
    }
}
