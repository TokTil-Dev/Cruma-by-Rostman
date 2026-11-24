package com.rost.cruma.viewer.render.bgfx;

import com.rost.cruma.core.world.Entity.Texture;
import com.rost.cruma.core.world.Entity.TextureFormat;
import java.util.zip.CRC32;
import java.util.zip.CRC32C;

record BgfxTextureContent(
        int width, int height, int levels, TextureFormat format, boolean alphaTexture, int samplerFlags, long bits) {
    static BgfxTextureContent of(Texture upload, int samplerFlags) {
        var first = new CRC32();
        var second = new CRC32C();
        for (var level : upload.data()) {
            first.update(level);
            second.update(level);
        }
        return new BgfxTextureContent(
                upload.width(),
                upload.height(),
                upload.data().size(),
                upload.format(),
                upload.alphaTexture(),
                samplerFlags,
                first.getValue() << 32 | second.getValue());
    }
}
