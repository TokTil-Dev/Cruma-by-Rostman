package com.rost.cruma.core.world;

import com.rost.cruma.core.world.Entity.Texture;
import glm_.vec3.Vec3;
import glm_.vec4.Vec4;
import org.jetbrains.annotations.Nullable;

public record Billboard(
        Vec3 position,
        float size,
        float height,
        Vec4 color,
        int blend,
        @Nullable Texture sprite,
        float phase,
        int animType,
        Vec4 uvRect,
        Vec3 velDir,
        int orientMode) {
    public static final Vec4 FULL_UV = new Vec4(0f, 0f, 1f, 1f);

    public static final int BLEND_ALPHA = 0;
    public static final int BLEND_ADDITIVE = 1;
    public static final int BLEND_MODULATE = 2;
    public static final int BLEND_SCREEN = 3;
    public static final int BLEND_DARKEN = 4;

    public static final int ANIM_FLICKER = 0;
    public static final int ANIM_RISE = 1;

    private static final Vec3 NO_VEL_DIR = new Vec3(0f, 0f, 0f);

    public Billboard(
            Vec3 position, float size, Vec4 color, int blend, @Nullable Texture sprite, float phase, int animType) {
        this(position, size, size, color, blend, sprite, phase, animType, FULL_UV, NO_VEL_DIR, 0);
    }
}
