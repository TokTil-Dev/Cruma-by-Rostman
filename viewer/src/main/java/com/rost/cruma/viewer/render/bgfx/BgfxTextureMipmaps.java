package com.rost.cruma.viewer.render.bgfx;

import com.rost.cruma.core.texture.DxtMipCodec;
import com.rost.cruma.core.texture.TexturePixels;
import com.rost.cruma.core.world.Entity.Texture;
import com.rost.cruma.core.world.Entity.TextureFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class BgfxTextureMipmaps {
    private BgfxTextureMipmaps() {}

    static List<byte[]> decodeRgbaLevels(Texture source, byte[] decodedBase) {
        requireLevels(source);
        var levels = new ArrayList<byte[]>(source.data().size());
        levels.add(decodedBase);
        for (var mip = 1; mip < source.data().size(); mip++) {
            levels.add(TexturePixels.decodeMipRgba(source, mip));
        }
        return levels;
    }

    static List<byte[]> splitCompressedLevels(Texture source) {
        requireLevels(source);
        var inspection =
                DxtMipCodec.inspectAuthoredChain(source.format(), source.width(), source.height(), source.data());
        if (inspection.validLevels() != source.data().size()) {
            throw new IllegalArgumentException("compressed bitmap has an incomplete mip payload");
        }
        var levels = new ArrayList<byte[]>(source.data().size());
        var blockBytes = source.format() == TextureFormat.TEXTURE_DXT1 ? 8 : 16;
        for (var mip = 0; mip < source.data().size(); mip++) {
            var width = Math.max(1, source.width() >> mip);
            var height = Math.max(1, source.height() >> mip);
            var size = Math.multiplyExact(Math.multiplyExact((width + 3) / 4, (height + 3) / 4), blockBytes);
            var bytes = source.data().get(mip);
            levels.add(bytes.length == size ? bytes : Arrays.copyOf(bytes, size));
        }
        return levels;
    }

    private static void requireLevels(Texture source) {
        if (source.data().isEmpty() || source.data().size() > DxtMipCodec.mipCount(source.width(), source.height())) {
            throw new IllegalArgumentException("bitmap mip count does not match its dimensions");
        }
    }
}
