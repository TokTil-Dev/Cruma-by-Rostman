package com.rost.cruma.viewer.render.bgfx;

import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_MAG_ANISOTROPIC;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_MAG_MASK;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_MAG_POINT;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_MIN_ANISOTROPIC;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_MIN_MASK;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_MIN_POINT;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_MIP_MASK;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_MIP_POINT;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_U_CLAMP;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_V_CLAMP;

import com.rost.cruma.core.world.Entity.Texture;
import com.rost.cruma.core.world.TextureFilters;

final class BgfxBitmapSampler {
    private static final int FILTER_MASK = BGFX_SAMPLER_MIN_MASK | BGFX_SAMPLER_MAG_MASK | BGFX_SAMPLER_MIP_MASK;

    private BgfxBitmapSampler() {}

    static int apply(int inherited, TextureFilters filters) {
        var flags = replace(
                inherited,
                filters.min(),
                BGFX_SAMPLER_MIN_MASK,
                BGFX_SAMPLER_MIN_POINT,
                BGFX_SAMPLER_MIN_ANISOTROPIC,
                "MinFilter");
        flags = replace(
                flags,
                filters.mag(),
                BGFX_SAMPLER_MAG_MASK,
                BGFX_SAMPLER_MAG_POINT,
                BGFX_SAMPLER_MAG_ANISOTROPIC,
                "MagFilter");
        return replace(flags, filters.mip(), BGFX_SAMPLER_MIP_MASK, BGFX_SAMPLER_MIP_POINT, 0, "MipFilter");
    }

    static int water(int bitmapFlags, TextureFilters filters, boolean linear) {
        return apply((bitmapFlags & ~FILTER_MASK) | BgfxSceneRenderer.waterFilterFlags(linear), filters);
    }

    static int water(Texture root, boolean linear) {
        var address = (root.clampU() ? BGFX_SAMPLER_U_CLAMP : 0) | (root.clampV() ? BGFX_SAMPLER_V_CLAMP : 0);
        return water(address, root.filters(), linear);
    }

    private static int replace(int inherited, int value, int mask, int point, int anisotropic, String name) {
        if (value == 0) {
            return inherited;
        }
        var filter = switch (value) {
            case 1 -> point;
            case 2 -> 0;
            case 3 -> {
                if (anisotropic == 0) {
                    throw new IllegalArgumentException("Unsupported bitmap " + name + "=" + value);
                }
                yield anisotropic;
            }
            default -> throw new IllegalArgumentException("Unsupported bitmap " + name + "=" + value);
        };
        return (inherited & ~mask) | filter;
    }
}
