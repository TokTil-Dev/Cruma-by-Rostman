package com.rost.cruma.viewer.config;

import java.util.List;
import java.util.Locale;

public record InterfaceScale(int percent) {
    public static final InterfaceScale AUTOMATIC = new InterfaceScale(0);
    public static final int MIN_PERCENT = 100;
    public static final int MAX_PERCENT = 300;

    public static final List<Integer> PRESETS = List.of(100, 125, 150, 175, 200, 250, 300);

    public InterfaceScale {
        if (percent != 0 && (percent < MIN_PERCENT || percent > MAX_PERCENT)) {
            throw new IllegalArgumentException(problem(Integer.toString(percent)));
        }
    }

    public static InterfaceScale parse(String value) {
        var text = value.strip().toLowerCase(Locale.ROOT);
        if (text.equals("auto")) {
            return AUTOMATIC;
        }
        try {
            var number = Double.parseDouble(text);
            if (number == Math.rint(number) && number >= MIN_PERCENT && number <= MAX_PERCENT) {
                return new InterfaceScale((int) number);
            }
        } catch (NumberFormatException ignored) {
        }
        throw new IllegalArgumentException(problem(value));
    }

    public boolean isAutomatic() {
        return percent == 0;
    }

    public String key() {
        return isAutomatic() ? "auto" : Integer.toString(percent);
    }

    public float factor(float contentScale, float framebufferRatio) {
        if (!isAutomatic()) {
            return percent / 100f;
        }
        if (!(contentScale > 0) || !(framebufferRatio > 0) || !Float.isFinite(contentScale / framebufferRatio)) {
            return 1f;
        }
        return Math.clamp(contentScale / framebufferRatio, MIN_PERCENT / 100f, MAX_PERCENT / 100f);
    }

    private static String problem(String value) {
        return "interface.scale must be auto or a whole percentage from " + MIN_PERCENT + " to " + MAX_PERCENT
                + ", got " + value;
    }
}
