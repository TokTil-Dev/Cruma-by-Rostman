package com.rost.cruma.viewer.ui;

import java.util.Locale;

public final class OrthoGridSteps {
    public static final float MIN_SPACING = 64f;

    public static final int STRONG_EVERY = 5;

    public static final String[] DENSITY_LABELS = {"Sparse", "Normal", "Dense", "Finest"};

    public static final int DEFAULT_DENSITY = 1;

    public static final float MIN_FIXED_SPACING = 6f;

    public static final int MAX_STEP = 32_768;

    private static final int[] DENSITY_SHIFT = {1, 0, -1, -2};

    private OrthoGridSteps() {}

    public static long step(double unitsPerPoint) {
        return step(unitsPerPoint, DEFAULT_DENSITY, 0);
    }

    public static long step(double unitsPerPoint, int density, int fixed) {
        if (fixed > 0) {
            return multiple(fixed, Math.abs(unitsPerPoint) * MIN_FIXED_SPACING);
        }
        var usual = multiple(1, Math.max(1, Math.abs(unitsPerPoint) * MIN_SPACING));
        return shifted(usual, DENSITY_SHIFT[Math.clamp(density, 0, DENSITY_SHIFT.length - 1)]);
    }

    public static long labelStep(long step, double unitsPerPoint) {
        var wanted = Math.abs(unitsPerPoint) * MIN_SPACING;
        if (!Double.isFinite(wanted) || step >= wanted) {
            return step;
        }
        var label = step * STRONG_EVERY;
        while (label < wanted) {
            label *= label / step % 10 == 0 ? STRONG_EVERY : 2;
        }
        return label;
    }

    public static int parseStep(String typed) {
        try {
            return (int) Math.clamp(Long.parseLong(typed.trim()), 0, MAX_STEP);
        } catch (NumberFormatException noNumber) {
            return 0;
        }
    }

    public static long first(double from, long step) {
        return Math.ceilDiv((long) Math.ceil(from), step) * step;
    }

    public static boolean isStrong(long line, long step) {
        return Math.floorMod(line, STRONG_EVERY * step) == 0;
    }

    public static String label(long line) {
        return String.format(Locale.ROOT, "%,d", line);
    }

    private static long shifted(long step, int places) {
        var result = step;
        for (var place = 0; place < Math.abs(places); place++) {
            var lead = result;
            while (lead % 10 == 0) {
                lead /= 10;
            }
            if (places > 0) {
                result = lead == 2 ? result / 2 * 5 : result * 2;
            } else {
                result = Math.max(1, lead == 5 ? result / 5 * 2 : result / 2);
            }
        }
        return result;
    }

    private static long multiple(long base, double wanted) {
        if (!Double.isFinite(wanted)) {
            return base;
        }
        var power = 1L;
        while (true) {
            for (var factor : new int[] {1, 2, 5}) {
                var step = base * factor * power;
                if (step >= wanted) {
                    return step;
                }
            }
            power *= 10;
        }
    }
}
