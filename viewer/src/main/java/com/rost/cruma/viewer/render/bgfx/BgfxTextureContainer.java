package com.rost.cruma.viewer.render.bgfx;

import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_FORMAT_BC1;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_FORMAT_BC2;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_FORMAT_BC3;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_FORMAT_RGBA8;

import com.rost.cruma.core.world.NativeMemory;
import com.rost.cruma.viewer.render.bgfx.BgfxTexture.Descriptor;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class BgfxTextureContainer {
    private static final int HEADER_BYTES = 148;

    private BgfxTextureContainer() {}

    static ByteBuffer encode(Descriptor descriptor, ByteBuffer pixels) {
        var format = switch (descriptor.format()) {
            case BGFX_TEXTURE_FORMAT_RGBA8 -> 28;
            case BGFX_TEXTURE_FORMAT_BC1 -> 71;
            case BGFX_TEXTURE_FORMAT_BC2 -> 74;
            case BGFX_TEXTURE_FORMAT_BC3 -> 77;
            default -> throw new IllegalArgumentException("unsupported partial mip format: " + descriptor.format());
        };
        var compressed = descriptor.format() != BGFX_TEXTURE_FORMAT_RGBA8;
        var blockBytes = descriptor.format() == BGFX_TEXTURE_FORMAT_BC1 ? 8 : 16;
        var pitch = compressed
                ? Math.multiplyExact(
                        Math.multiplyExact((descriptor.width() + 3) / 4, (descriptor.height() + 3) / 4), blockBytes)
                : Math.multiplyExact(descriptor.width(), 4);
        var bytes = pixels == null ? 0 : pixels.remaining();
        var expected = 0L;
        for (var mip = 0; mip < descriptor.mipLevels(); mip++) {
            var width = Math.max(1, descriptor.width() >> mip);
            var height = Math.max(1, descriptor.height() >> mip);
            expected +=
                    compressed ? (long) ((width + 3) / 4) * ((height + 3) / 4) * blockBytes : (long) width * height * 4;
        }
        expected = Math.multiplyExact(expected, (long) descriptor.layers() * (descriptor.cube() ? 6 : 1));
        if (pixels != null && bytes != expected) {
            throw new IllegalArgumentException("partial mip payload does not match its texture allocation");
        }
        var result =
                NativeMemory.directBuffer(Math.addExact(HEADER_BYTES, bytes)).order(ByteOrder.LITTLE_ENDIAN);
        result.putInt(0x20534444);
        result.putInt(124);
        result.putInt(0x1007 | (compressed ? 0x80000 : 0x8) | (descriptor.isMipmapped() ? 0x20000 : 0));
        result.putInt(descriptor.height())
                .putInt(descriptor.width())
                .putInt(pitch)
                .putInt(0);
        result.putInt(descriptor.mipLevels());
        for (var index = 0; index < 11; index++) {
            result.putInt(0);
        }
        result.putInt(32).putInt(0x4).putInt(0x30315844);
        for (var index = 0; index < 5; index++) {
            result.putInt(0);
        }
        result.putInt(0x1000
                | (descriptor.isMipmapped() ? 0x400008 : 0)
                | (descriptor.cube() || descriptor.layers() > 1 ? 0x8 : 0));
        result.putInt(descriptor.cube() ? 0xfe00 : 0);
        result.putInt(0).putInt(0).putInt(0);
        result.putInt(format)
                .putInt(3)
                .putInt(descriptor.cube() ? 0x4 : 0)
                .putInt(descriptor.layers())
                .putInt(0);
        if (pixels != null) {
            result.put(pixels.duplicate());
        }
        return result.flip();
    }
}
