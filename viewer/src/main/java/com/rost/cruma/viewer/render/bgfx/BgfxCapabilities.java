package com.rost.cruma.viewer.render.bgfx;

import static org.lwjgl.bgfx.BGFX.BGFX_CAPS_COMPUTE;
import static org.lwjgl.bgfx.BGFX.BGFX_CAPS_DRAW_INDIRECT;
import static org.lwjgl.bgfx.BGFX.BGFX_CAPS_FORMAT_TEXTURE_2D;
import static org.lwjgl.bgfx.BGFX.BGFX_CAPS_FORMAT_TEXTURE_FRAMEBUFFER;
import static org.lwjgl.bgfx.BGFX.BGFX_CAPS_FORMAT_TEXTURE_IMAGE_READ;
import static org.lwjgl.bgfx.BGFX.BGFX_CAPS_FORMAT_TEXTURE_IMAGE_WRITE;
import static org.lwjgl.bgfx.BGFX.BGFX_CAPS_FORMAT_TEXTURE_VERTEX;
import static org.lwjgl.bgfx.BGFX.BGFX_CAPS_IMAGE_RW;
import static org.lwjgl.bgfx.BGFX.BGFX_CAPS_INDEX32;
import static org.lwjgl.bgfx.BGFX.BGFX_CAPS_INSTANCING;
import static org.lwjgl.bgfx.BGFX.BGFX_CAPS_TEXTURE_2D_ARRAY;
import static org.lwjgl.bgfx.BGFX.BGFX_CAPS_TEXTURE_BLIT;
import static org.lwjgl.bgfx.BGFX.BGFX_CAPS_TEXTURE_EXTERNAL;
import static org.lwjgl.bgfx.BGFX.BGFX_CAPS_TEXTURE_READ_BACK;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_FORMAT_BC1;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_FORMAT_BC2;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_FORMAT_BC3;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_FORMAT_BC5;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_FORMAT_BGRA4;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_FORMAT_D24S8;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_FORMAT_D32FS8;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_FORMAT_R32F;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_FORMAT_R8U;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_FORMAT_RGBA32F;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_FORMAT_RGBA8;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_FORMAT_UNKNOWN_DEPTH;

import java.util.ArrayList;
import java.util.List;
import org.lwjgl.bgfx.BGFXCaps;

public record BgfxCapabilities(
        String renderer,
        int vendorId,
        int deviceId,
        boolean homogeneousDepth,
        boolean originBottomLeft,
        boolean instancing,
        boolean index32,
        boolean textureArrays,
        boolean blit,
        boolean readback,
        boolean externalTextures,
        boolean compute,
        boolean imageReadWrite,
        boolean drawIndirect,
        boolean bc1,
        boolean bc2,
        boolean bc3,
        boolean bc5,
        boolean rgba8,
        boolean bgra4,
        boolean rgba8ImageWrite,
        boolean rgba32fVertex,
        boolean r8u,
        boolean r32fImageReadWrite,
        int depthStencilFormat,
        boolean depthStencilSampled,
        int maxDrawCalls,
        int maxTextureSize,
        int maxTextureLayers,
        int maxViews,
        int maxTextureSamplers,
        int maxComputeBindings,
        int maxVertexStreams,
        int maxEncoders) {
    public static final int NO_DEPTH_STENCIL = BGFX_TEXTURE_FORMAT_UNKNOWN_DEPTH;

    private static final boolean SKIP_D24S8 = Boolean.getBoolean("cruma.viewer.bgfx.skip_d24s8");

    public static BgfxCapabilities copyOf(BGFXCaps caps, String renderer) {
        var supported = caps.supported();
        var limits = caps.limits();
        var d24s8 = SKIP_D24S8 ? 0 : caps.formats(BGFX_TEXTURE_FORMAT_D24S8);
        var d32fs8 = caps.formats(BGFX_TEXTURE_FORMAT_D32FS8);
        var depthStencilFormat = chooseDepthStencilFormat(d24s8, d32fs8);
        var depthStencilFlags = depthStencilFormat == BGFX_TEXTURE_FORMAT_D24S8 ? d24s8 : d32fs8;
        return new BgfxCapabilities(
                renderer,
                Short.toUnsignedInt(caps.vendorId()),
                Short.toUnsignedInt(caps.deviceId()),
                caps.homogeneousDepth(),
                caps.originBottomLeft(),
                has(supported, BGFX_CAPS_INSTANCING),
                has(supported, BGFX_CAPS_INDEX32),
                has(supported, BGFX_CAPS_TEXTURE_2D_ARRAY),
                has(supported, BGFX_CAPS_TEXTURE_BLIT),
                has(supported, BGFX_CAPS_TEXTURE_READ_BACK),
                has(supported, BGFX_CAPS_TEXTURE_EXTERNAL),
                has(supported, BGFX_CAPS_COMPUTE),
                has(supported, BGFX_CAPS_IMAGE_RW),
                has(supported, BGFX_CAPS_DRAW_INDIRECT),
                supports2D(caps, BGFX_TEXTURE_FORMAT_BC1),
                supports2D(caps, BGFX_TEXTURE_FORMAT_BC2),
                supports2D(caps, BGFX_TEXTURE_FORMAT_BC3),
                supports2D(caps, BGFX_TEXTURE_FORMAT_BC5),
                supports2D(caps, BGFX_TEXTURE_FORMAT_RGBA8),
                supports2D(caps, BGFX_TEXTURE_FORMAT_BGRA4),
                supportsImageWrite(caps, BGFX_TEXTURE_FORMAT_RGBA8),
                supportsVertexTexture(caps, BGFX_TEXTURE_FORMAT_RGBA32F),
                supports2D(caps, BGFX_TEXTURE_FORMAT_R8U),
                supportsImageReadWrite(caps, BGFX_TEXTURE_FORMAT_R32F),
                depthStencilFormat,
                depthStencilFormat != NO_DEPTH_STENCIL && (depthStencilFlags & BGFX_CAPS_FORMAT_TEXTURE_2D) != 0,
                limits.maxDrawCalls(),
                limits.maxTextureSize(),
                limits.maxTextureLayers(),
                limits.maxViews(),
                limits.maxTextureSamplers(),
                limits.maxComputeBindings(),
                limits.maxVertexStreams(),
                limits.maxEncoders());
    }

    static int chooseDepthStencilFormat(int d24s8Flags, int d32fs8Flags) {
        if (isSampledTarget(d24s8Flags)) {
            return BGFX_TEXTURE_FORMAT_D24S8;
        }
        if (isSampledTarget(d32fs8Flags)) {
            return BGFX_TEXTURE_FORMAT_D32FS8;
        }
        if (isTarget(d24s8Flags)) {
            return BGFX_TEXTURE_FORMAT_D24S8;
        }
        if (isTarget(d32fs8Flags)) {
            return BGFX_TEXTURE_FORMAT_D32FS8;
        }
        return NO_DEPTH_STENCIL;
    }

    public List<String> requiredFailures() {
        var failures = new ArrayList<String>();
        if (!instancing) {
            failures.add("instancing");
        }
        if (!index32) {
            failures.add("32-bit indices");
        }
        if (!textureArrays) {
            failures.add("2D texture arrays");
        }
        if (!rgba8) {
            failures.add("RGBA8 textures");
        }
        if (depthStencilFormat == NO_DEPTH_STENCIL) {
            failures.add("D24S8 or D32FS8 framebuffer");
        }
        if (maxTextureSamplers < 16) {
            failures.add("16 texture samplers (reported " + maxTextureSamplers + ")");
        }
        if (maxVertexStreams < 3) {
            failures.add("3 vertex streams (reported " + maxVertexStreams + ")");
        }
        if (maxViews < 240) {
            failures.add("240 views (reported " + maxViews + ")");
        }
        return List.copyOf(failures);
    }

    public String getDepthStencilName() {
        return switch (depthStencilFormat) {
            case BGFX_TEXTURE_FORMAT_D24S8 -> "D24S8";
            case BGFX_TEXTURE_FORMAT_D32FS8 -> "D32FS8";
            default -> "none";
        };
    }

    private static boolean isTarget(int flags) {
        return (flags & BGFX_CAPS_FORMAT_TEXTURE_FRAMEBUFFER) != 0;
    }

    private static boolean isSampledTarget(int flags) {
        return isTarget(flags) && (flags & BGFX_CAPS_FORMAT_TEXTURE_2D) != 0;
    }

    private static boolean has(long supported, long flag) {
        return (supported & flag) != 0;
    }

    private static boolean supports2D(BGFXCaps caps, int format) {
        return (caps.formats(format) & BGFX_CAPS_FORMAT_TEXTURE_2D) != 0;
    }

    private static boolean supportsVertexTexture(BGFXCaps caps, int format) {
        var flags = caps.formats(format);
        return (flags & BGFX_CAPS_FORMAT_TEXTURE_2D) != 0 && (flags & BGFX_CAPS_FORMAT_TEXTURE_VERTEX) != 0;
    }

    private static boolean supportsImageReadWrite(BGFXCaps caps, int format) {
        var flags = caps.formats(format);
        return (flags & BGFX_CAPS_FORMAT_TEXTURE_2D) != 0
                && (flags & BGFX_CAPS_FORMAT_TEXTURE_IMAGE_READ) != 0
                && (flags & BGFX_CAPS_FORMAT_TEXTURE_IMAGE_WRITE) != 0;
    }

    private static boolean supportsImageWrite(BGFXCaps caps, int format) {
        var flags = caps.formats(format);
        return (flags & BGFX_CAPS_FORMAT_TEXTURE_2D) != 0 && (flags & BGFX_CAPS_FORMAT_TEXTURE_IMAGE_WRITE) != 0;
    }
}
