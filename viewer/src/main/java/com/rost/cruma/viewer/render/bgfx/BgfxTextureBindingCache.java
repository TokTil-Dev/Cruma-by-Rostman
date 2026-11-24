package com.rost.cruma.viewer.render.bgfx;

final class BgfxTextureBindingCache {
    private final short[] samplers = new short[16];
    private final short[] textures = new short[16];
    private final int[] flags = new int[16];
    private int valid;

    void invalidate() {
        valid = 0;
    }

    boolean needsWrite(int stage, short sampler, short texture, int samplerFlags) {
        if (stage < 0 || stage >= textures.length) {
            throw new IllegalArgumentException("texture stage must be in [0, 15]");
        }
        var bit = 1 << stage;
        if ((valid & bit) != 0
                && samplers[stage] == sampler
                && textures[stage] == texture
                && flags[stage] == samplerFlags) {
            return false;
        }
        samplers[stage] = sampler;
        textures[stage] = texture;
        flags[stage] = samplerFlags;
        valid |= bit;
        return true;
    }
}
