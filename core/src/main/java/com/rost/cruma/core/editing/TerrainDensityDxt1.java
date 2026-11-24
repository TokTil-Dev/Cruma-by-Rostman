package com.rost.cruma.core.editing;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

final class TerrainDensityDxt1 {
    private static final int[] GRAYS = grays();
    private static final int[][] LEVELS = levels();

    private final Map<String, byte[]> blocks = new HashMap<>();

    static int gray(int color) {
        return ((color >> 8 & 0xf8) + (color >> 3 & 0xfc) + (color & 0x1f) * 8) / 3;
    }

    void encode(int[] wanted, byte[] out, int offset) {
        var key = Arrays.toString(wanted);
        var block = blocks.get(key);
        if (block == null) {
            block = encode(wanted);
            blocks.put(key, block);
        }
        System.arraycopy(block, 0, out, offset, 8);
    }

    private static byte[] encode(int[] wanted) {
        var best = 0;
        var bestError = Long.MAX_VALUE;
        for (var pair = 0; pair < LEVELS.length && bestError > 0; pair++) {
            var levels = LEVELS[pair];
            long error = 0;
            for (var i = 0; i < 16 && error < bestError; i++) {
                error += nearestError(levels, wanted[i]);
            }
            if (error < bestError) {
                bestError = error;
                best = pair;
            }
        }
        var levels = LEVELS[best];
        var bits = 0;
        for (var i = 0; i < 16; i++) {
            var index = 0;
            var error = Integer.MAX_VALUE;
            for (var k = 0; k < 4; k++) {
                var difference = levels[k] - wanted[i];
                if (difference * difference < error) {
                    error = difference * difference;
                    index = k;
                }
            }
            bits |= index << 2 * i;
        }
        var first = GRAYS[best / GRAYS.length];
        var second = GRAYS[best % GRAYS.length];
        return new byte[] {
            (byte) first,
            (byte) (first >> 8),
            (byte) second,
            (byte) (second >> 8),
            (byte) bits,
            (byte) (bits >> 8),
            (byte) (bits >> 16),
            (byte) (bits >> 24)
        };
    }

    private static int nearestError(int[] levels, int value) {
        var error = Integer.MAX_VALUE;
        for (var level : levels) {
            var difference = level - value;
            error = Math.min(error, difference * difference);
        }
        return error;
    }

    private static int[] grays() {
        var result = new int[64];
        for (var red = 0; red < 32; red++) {
            result[red * 2] = red << 11 | red * 2 << 5 | red;
            result[red * 2 + 1] = red << 11 | (red * 2 + 1) << 5 | red;
        }
        return result;
    }

    private static int[][] levels() {
        var result = new int[GRAYS.length * GRAYS.length][];
        for (var a = 0; a < GRAYS.length; a++) {
            for (var b = 0; b < GRAYS.length; b++) {
                var c0 = GRAYS[a];
                var c1 = GRAYS[b];
                result[a * GRAYS.length + b] = c0 > c1
                        ? new int[] {gray(c0), gray(c1), gray((2 * c0 + 1 + c1) / 3), gray((2 * c1 + 1 + c0) / 3)}
                        : new int[] {gray(c0), gray(c1), gray((c0 + c1) / 2), 0};
            }
        }
        return result;
    }
}
