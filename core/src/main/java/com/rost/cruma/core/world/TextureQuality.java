package com.rost.cruma.core.world;

import com.rost.cruma.core.unreal.UMaterial.UTexture;

public record TextureQuality(int textureDetail, int effectDetail, boolean keepMinFrameRate, boolean editor) {
    public static final TextureQuality FULL = new TextureQuality(0, 0, false, false);

    public TextureQuality {
        if (effectDetail < 0 || effectDetail > 2) {
            throw new IllegalArgumentException("effect detail must be within 0..2");
        }
    }

    public int firstMip(UTexture texture) {
        var mips = texture.getMips();
        if (mips == null || mips.length == 0) {
            return 0;
        }
        var maximum = mips.length - 1;
        for (var index = 0; index < mips.length; index++) {
            var width = mips[index].getUSize();
            var height = mips[index].getVSize();
            if (width < 64 || height < 64 && width < 256 && height < 256) {
                maximum = index;
                break;
            }
        }
        var quality = detailFor(texture.getFullName());
        var minimum = texture.getMinLod();
        var level = quality < minimum ? minimum : Math.min(quality, maximum);
        while (level > 0 && (texture.getUSize() >> level) < 64 && (texture.getVSize() >> level) < 64) {
            level--;
        }
        return Math.max(minimum, level);
    }

    int detailFor(String fullName) {
        if (keepMinFrameRate && !editor) {
            return 2;
        }
        if (fullName != null
                && (fullName.regionMatches(true, 0, "LineageEffectsTextures.", 0, 23)
                        || fullName.regionMatches(true, 0, "LineageEffectsTextures2.", 0, 24))) {
            return effectDetail;
        }
        return editor ? 0 : textureDetail;
    }
}
