package com.rost.cruma.core.texture;

import com.rost.cruma.core.world.Entity.Texture;
import com.rost.cruma.core.world.Entity.TextureFormat;
import java.util.Arrays;

public final class TexturePixels {
    private TexturePixels() {}

    public static byte[] decodeBaseRgba(Texture source) {
        if (source == null || source.data().isEmpty()) {
            return new byte[0];
        }
        return decodeMipRgba(source, 0);
    }

    public static byte[] decodeMipRgba(Texture source, int mip) {
        if (source == null || mip < 0 || mip >= source.data().size()) {
            throw new IllegalArgumentException("texture mip is out of range");
        }
        var base = source.data().get(mip);
        var width = Math.max(1, source.width() >> mip);
        var height = Math.max(1, source.height() >> mip);
        return switch (source.format()) {
            case TEXTURE_DXT1, TEXTURE_DXT3, TEXTURE_DXT5 ->
                DxtMipCodec.decodeToRgba(source.format(), width, height, base);
            case TEXTURE_RGBA -> bgraToRgba(base, width, height);
            case TEXTURE_P8 -> checkedRgba(base, width, height);
        };
    }

    public static byte[] decodeBaseRed(Texture source) {
        if (source == null || source.data().isEmpty()) {
            return new byte[0];
        }
        var base = source.data().getFirst();
        return switch (source.format()) {
            case TEXTURE_DXT1, TEXTURE_DXT3, TEXTURE_DXT5 ->
                DxtMipCodec.decodeRed(source.format(), source.width(), source.height(), base);
            case TEXTURE_RGBA -> extractChannel(base, source.width(), source.height(), 2);
            case TEXTURE_P8 -> extractChannel(base, source.width(), source.height(), 0);
        };
    }

    public static void writeBaseRedInterleaved(
            Texture source, byte[] target, int targetChannel, int targetPixelStride) {
        if (source == null || source.data().isEmpty()) {
            return;
        }
        var pixels = Math.multiplyExact(source.width(), source.height());
        if (targetChannel < 0
                || targetPixelStride <= targetChannel
                || pixels > 0 && (long) (pixels - 1) * targetPixelStride + targetChannel >= target.length) {
            throw new IllegalArgumentException("invalid interleaved red destination");
        }
        var base = source.data().getFirst();
        if (source.format() == TextureFormat.TEXTURE_DXT1
                || source.format() == TextureFormat.TEXTURE_DXT3
                || source.format() == TextureFormat.TEXTURE_DXT5) {
            DxtMipCodec.decodeRedInto(
                    source.format(), source.width(), source.height(), base, target, targetChannel, targetPixelStride);
            return;
        }
        var sourceChannel = source.format() == TextureFormat.TEXTURE_RGBA ? 2 : 0;
        var expected = Math.multiplyExact(pixels, 4);
        if (base.length < expected) {
            throw new IllegalArgumentException("short RGBA texture: " + base.length + " < " + expected);
        }
        for (var pixel = 0; pixel < pixels; pixel++) {
            target[pixel * targetPixelStride + targetChannel] = base[pixel * 4 + sourceChannel];
        }
    }

    private static byte[] extractChannel(byte[] source, int width, int height, int channel) {
        var pixels = Math.multiplyExact(width, height);
        var expected = Math.multiplyExact(pixels, 4);
        if (source.length < expected) {
            throw new IllegalArgumentException("short RGBA texture: " + source.length + " < " + expected);
        }
        var result = new byte[pixels];
        for (var pixel = 0; pixel < pixels; pixel++) {
            result[pixel] = source[pixel * 4 + channel];
        }
        return result;
    }

    private static byte[] checkedRgba(byte[] source, int width, int height) {
        var expected = Math.multiplyExact(Math.multiplyExact(width, height), 4);
        if (source.length < expected) {
            throw new IllegalArgumentException("short RGBA texture: " + source.length + " < " + expected);
        }
        return source.length == expected ? source.clone() : Arrays.copyOf(source, expected);
    }

    private static byte[] bgraToRgba(byte[] source, int width, int height) {
        var result = checkedRgba(source, width, height);
        for (var offset = 0; offset < result.length; offset += 4) {
            var red = result[offset];
            result[offset] = result[offset + 2];
            result[offset + 2] = red;
        }
        return result;
    }
}
