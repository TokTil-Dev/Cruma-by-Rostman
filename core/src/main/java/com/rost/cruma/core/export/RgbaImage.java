package com.rost.cruma.core.export;

record RgbaImage(int width, int height, byte[] rgba) {
    RgbaImage {
        if (width < 1 || height < 1 || rgba.length != Math.multiplyExact(Math.multiplyExact(width, height), 4)) {
            throw new IllegalArgumentException("pixels do not match a " + width + "x" + height + " image");
        }
    }

    static byte toByte(float value) {
        return (byte) Math.round(Math.clamp(value, 0f, 1f) * 255f);
    }

    void sample(float u, float v, boolean repeat, float[] out) {
        var x = u * width - .5f;
        var y = v * height - .5f;
        var x0 = (int) Math.floor(x);
        var y0 = (int) Math.floor(y);
        var fx = x - x0;
        var fy = y - y0;
        var left = resolveColumn(x0, repeat);
        var right = resolveColumn(x0 + 1, repeat);
        var top = resolveRow(y0, repeat);
        var bottom = resolveRow(y0 + 1, repeat);
        for (var channel = 0; channel < 4; channel++) {
            var a = texel(left, top, channel);
            var b = texel(right, top, channel);
            var c = texel(left, bottom, channel);
            var d = texel(right, bottom, channel);
            var upper = a + (b - a) * fx;
            var lower = c + (d - c) * fx;
            out[channel] = (upper + (lower - upper) * fy) / 255f;
        }
    }

    RgbaImage resized(int targetWidth, int targetHeight, boolean repeat) {
        if (targetWidth == width && targetHeight == height) {
            return this;
        }
        var result = new byte[Math.multiplyExact(Math.multiplyExact(targetWidth, targetHeight), 4)];
        var texel = new float[4];
        for (var y = 0; y < targetHeight; y++) {
            for (var x = 0; x < targetWidth; x++) {
                sample((x + .5f) / targetWidth, (y + .5f) / targetHeight, repeat, texel);
                var offset = (y * targetWidth + x) * 4;
                for (var channel = 0; channel < 4; channel++) {
                    result[offset + channel] = toByte(texel[channel]);
                }
            }
        }
        return new RgbaImage(targetWidth, targetHeight, result);
    }

    private int texel(int x, int y, int channel) {
        return Byte.toUnsignedInt(rgba[(y * width + x) * 4 + channel]);
    }

    private int resolveColumn(int x, boolean repeat) {
        return repeat ? Math.floorMod(x, width) : Math.clamp(x, 0, width - 1);
    }

    private int resolveRow(int y, boolean repeat) {
        return repeat ? Math.floorMod(y, height) : Math.clamp(y, 0, height - 1);
    }
}
