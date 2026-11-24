package com.rost.cruma.viewer.editing;

import com.rost.cruma.core.editing.LightColor;
import com.rost.cruma.core.env.UnrealHsv;
import java.util.List;
import java.util.Locale;
import org.jetbrains.annotations.Nullable;

public final class LightColors {
    public static final List<Preset> PRESETS = List.of(
            new Preset("Candle", 20, 80, 96),
            new Preset("Lamp", 26, 150, 128),
            new Preset("Daylight", 0, 255, 160),
            new Preset("Moonlight", 150, 170, 64),
            new Preset("Fire", 12, 30, 160),
            new Preset("Magic", 195, 60, 128),
            new Preset("Poison", 70, 60, 96));

    public static final float WARMEST = 1_000;

    public static final float COOLEST = 12_000;

    static final int WHITE_SATURATION = 235;

    static final float NEAR_COLOR = .2f;

    private LightColors() {}

    public record Preset(String name, int hue, int saturation, float brightness) {
        public LightColor color() {
            return new LightColor(brightness, hue, saturation);
        }
    }

    public static @Nullable Preset preset(String name) {
        for (var preset : PRESETS) {
            if (preset.name().equalsIgnoreCase(name.strip())) {
                return preset;
            }
        }
        return null;
    }

    public static float saturationPercent(int saturation) {
        return (255 - saturation) * 100f / 255;
    }

    public static int saturationOf(float percent) {
        return Math.clamp(Math.round(255 - percent * 255 / 100), 0, 255);
    }

    public static float[] kelvinRgb(float kelvin) {
        var t = kelvin / 100.0;
        double red;
        double green;
        double blue;
        if (t <= 66) {
            red = 255;
            green = 99.4708025861 * Math.log(t) - 161.1195681661;
            blue = t <= 19 ? 0 : 138.5177312231 * Math.log(t - 10) - 305.0447927307;
        } else {
            red = 329.698727446 * Math.pow(t - 60, -0.1332047592);
            green = 288.1221695283 * Math.pow(t - 60, -0.0755148492);
            blue = 255;
        }
        var r = (float) Math.clamp(red / 255, 0, 1);
        var g = (float) Math.clamp(green / 255, 0, 1);
        var b = (float) Math.clamp(blue / 255, 0, 1);
        var strongest = Math.max(r, Math.max(g, b));
        return new float[] {r / strongest, g / strongest, b / strongest};
    }

    public static LightColor temperatureColor(float kelvin, float brightness) {
        var rgb = kelvinRgb(kelvin);
        var unreal = UnrealHsv.fromRgb(rgb[0], rgb[1], rgb[2]);
        return new LightColor(brightness, unreal[0], unreal[1]);
    }

    public static float temperature(int hue, int saturation) {
        var rgb = rgb(hue, saturation);
        var best = Float.NaN;
        var bestError = .04f;
        for (var kelvin = WARMEST; kelvin <= COOLEST; kelvin += 50) {
            var other = kelvinRgb(kelvin);
            var error = Math.max(
                    Math.abs(rgb[0] - other[0]), Math.max(Math.abs(rgb[1] - other[1]), Math.abs(rgb[2] - other[2])));
            if (error < bestError) {
                best = kelvin;
                bestError = error;
            }
        }
        return best;
    }

    public static float[] rgb(int hue, int saturation) {
        var color = UnrealHsv.toRgb(hue, saturation, 255);
        var max = Math.max(color.getX(), Math.max(color.getY(), color.getZ()));
        if (!(max > 0)) {
            return new float[] {1, 1, 1};
        }
        return new float[] {color.getX() / max, color.getY() / max, color.getZ() / max};
    }

    public static String colorName(LightColor color) {
        for (var preset : PRESETS) {
            if (preset.hue() == color.hue()
                    && preset.saturation() == color.saturation()
                    && preset.brightness() == color.brightness()) {
                return preset.name().toLowerCase(Locale.ROOT);
            }
        }
        if (color.saturation() >= WHITE_SATURATION) {
            return "white";
        }
        var rgb = rgb(color.hue(), color.saturation());
        Preset nearest = null;
        var best = Float.MAX_VALUE;
        for (var preset : PRESETS) {
            var other = rgb(preset.hue(), preset.saturation());
            var dr = rgb[0] - other[0];
            var dg = rgb[1] - other[1];
            var db = rgb[2] - other[2];
            var distance = dr * dr + dg * dg + db * db;
            if (distance < best) {
                best = distance;
                nearest = preset;
            }
        }
        return nearest != null && best <= NEAR_COLOR * NEAR_COLOR
                ? nearest.name().toLowerCase(Locale.ROOT)
                : "custom";
    }
}
