package com.rost.cruma.viewer.render.bgfx;

import java.nio.ByteBuffer;
import java.util.ArrayList;

final class BgfxUiMipmaps {
    private BgfxUiMipmaps() {}

    static ByteBuffer createRgbaMipChain(ByteBuffer source, int width, int height) {
        var levels = new ArrayList<byte[]>();
        var current = new byte[Math.multiplyExact(Math.multiplyExact(width, height), 4)];
        source.duplicate().get(current);
        levels.add(current);
        int bytes = current.length;
        while (width > 1 || height > 1) {
            int nextWidth = Math.max(1, width / 2), nextHeight = Math.max(1, height / 2);
            var next = new byte[Math.multiplyExact(Math.multiplyExact(nextWidth, nextHeight), 4)];
            for (int y = 0; y < nextHeight; y++) {
                int y0 = y * height / nextHeight, y1 = (y + 1) * height / nextHeight;
                for (int x = 0; x < nextWidth; x++) {
                    int x0 = x * width / nextWidth, x1 = (x + 1) * width / nextWidth;
                    long alpha = 0, red = 0, green = 0, blue = 0;
                    for (int sy = y0; sy < y1; sy++) {
                        for (int sx = x0; sx < x1; sx++) {
                            int offset = (sy * width + sx) * 4, a = current[offset + 3] & 255;
                            alpha += a;
                            red += (current[offset] & 255) * a;
                            green += (current[offset + 1] & 255) * a;
                            blue += (current[offset + 2] & 255) * a;
                        }
                    }
                    int offset = (y * nextWidth + x) * 4, count = (x1 - x0) * (y1 - y0);
                    if (alpha > 0) {
                        next[offset] = (byte) ((red + alpha / 2) / alpha);
                        next[offset + 1] = (byte) ((green + alpha / 2) / alpha);
                        next[offset + 2] = (byte) ((blue + alpha / 2) / alpha);
                    }
                    next[offset + 3] = (byte) ((alpha + count / 2) / count);
                }
            }
            levels.add(next);
            bytes = Math.addExact(bytes, next.length);
            current = next;
            width = nextWidth;
            height = nextHeight;
        }
        var result = ByteBuffer.allocateDirect(bytes);
        for (var level : levels) {
            result.put(level);
        }
        return result.flip();
    }
}
