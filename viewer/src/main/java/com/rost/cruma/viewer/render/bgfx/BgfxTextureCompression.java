package com.rost.cruma.viewer.render.bgfx;

import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_FORMAT_BC1;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_FORMAT_BC2;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_FORMAT_BC3;

import com.rost.cruma.core.world.Entity.Texture;
import com.rost.cruma.core.world.Entity.TextureFormat;

final class BgfxTextureCompression {
    private BgfxTextureCompression() {}

    static int nativeFormat(BgfxCapabilities capabilities, Texture texture) {
        if (texture == null) {
            return -1;
        }
        if (isMetal(capabilities) && texture.format() == TextureFormat.TEXTURE_DXT3 && texture.alphaTexture()) {
            return -1;
        }
        return nativeFormat(capabilities, texture.format());
    }

    static int nativeFormat(BgfxCapabilities capabilities, TextureFormat format) {
        if (capabilities == null || format == null) {
            return -1;
        }
        return switch (format) {
            case TEXTURE_DXT1 -> capabilities.bc1() ? BGFX_TEXTURE_FORMAT_BC1 : -1;
            case TEXTURE_DXT3 -> capabilities.bc2() ? BGFX_TEXTURE_FORMAT_BC2 : -1;
            case TEXTURE_DXT5 -> capabilities.bc3() ? BGFX_TEXTURE_FORMAT_BC3 : -1;
            case TEXTURE_RGBA, TEXTURE_P8 -> -1;
        };
    }

    static boolean canPackTerrainWeights(BgfxCapabilities capabilities) {
        if (capabilities == null || !capabilities.bgra4() || capabilities.renderer() == null) {
            return false;
        }
        return switch (capabilities.renderer()) {
            case "Direct3D 11", "Direct3D 12", "Vulkan" -> true;
            default -> false;
        };
    }

    static boolean usesNativeCompression(BgfxCapabilities capabilities, TextureFormat format) {
        return nativeFormat(capabilities, format) >= 0;
    }

    static boolean usesNativeCompression(BgfxCapabilities capabilities, Texture texture) {
        return nativeFormat(capabilities, texture) >= 0;
    }

    private static boolean isMetal(BgfxCapabilities capabilities) {
        return capabilities.renderer() != null && capabilities.renderer().equalsIgnoreCase("Metal");
    }
}
