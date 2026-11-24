package com.rost.cruma.viewer.render.bgfx;

final class TerrainWeightBits {
    private TerrainWeightBits() {}

    static boolean fits(byte[] rgba) {
        if (rgba == null || (rgba.length & 3) != 0) {
            throw new IllegalArgumentException("a weight atlas has four bytes for a texel");
        }
        for (var value : rgba) {
            var high = value >> 4 & 0xf;
            var low = value & 0xf;
            if (high != low) {
                return false;
            }
        }
        return true;
    }

    static byte[] pack(byte[] rgba) {
        if (rgba == null || (rgba.length & 3) != 0) {
            throw new IllegalArgumentException("a weight atlas has four bytes for a texel");
        }
        var packed = new byte[rgba.length / 2];
        for (int source = 0, target = 0; source < rgba.length; source += 4, target += 2) {
            packed[target] = (byte) (rgba[source + 2] & 0xf | (rgba[source + 1] & 0xf) << 4);
            packed[target + 1] = (byte) (rgba[source] & 0xf | (rgba[source + 3] & 0xf) << 4);
        }
        return packed;
    }
}
