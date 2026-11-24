package com.rost.cruma.core.export;

import com.rost.cruma.core.world.WorldDimensions;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Locale;

public record ExportScale(float centimetersPerUnit) {
    public static final ExportScale REAL_SIZE = new ExportScale(4f);

    public static final ExportScale UNREAL_UNITS = new ExportScale(1f);

    public static final ExportScale METERS = new ExportScale(100f);

    public static final float MIN = .1f;

    public static final float MAX = 1000f;

    private static final int HUMAN_UNITS = 46;

    public ExportScale {
        if (!(centimetersPerUnit >= MIN && centimetersPerUnit <= MAX)) {
            throw new IllegalArgumentException("a unit must be " + MIN + " to " + MAX + " cm");
        }
    }

    public static ExportScale clamped(float centimeters) {
        return new ExportScale(
                Float.isNaN(centimeters) ? REAL_SIZE.centimetersPerUnit : Math.clamp(centimeters, MIN, MAX));
    }

    static String length(double meters) {
        if (meters < 1) {
            return toPlainString(meters * 100) + " cm";
        }
        return toPlainString(meters) + " m";
    }

    public float metersPerUnit() {
        return centimetersPerUnit / 100f;
    }

    public String unitText() {
        return "1 unit = " + length(metersPerUnit());
    }

    public String describe() {
        return "1 client unit = "
                + length(metersPerUnit())
                + ". A client human, about "
                + HUMAN_UNITS
                + " units, is "
                + humanText()
                + " tall";
    }

    public String tileText() {
        var meters = WorldDimensions.TILE_SIZE * (double) centimetersPerUnit / 100.0;
        return new BigDecimal(String.format(Locale.ROOT, "%.2f", meters))
                        .stripTrailingZeros()
                        .toPlainString()
                + " m";
    }

    public String humanText() {
        return length(HUMAN_UNITS * metersPerUnit());
    }

    private static String toPlainString(double value) {
        var rounded = new BigDecimal(value, new MathContext(3));
        return rounded.stripTrailingZeros().toPlainString().toLowerCase(Locale.ROOT);
    }
}
