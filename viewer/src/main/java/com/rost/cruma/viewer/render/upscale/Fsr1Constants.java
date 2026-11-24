package com.rost.cruma.viewer.render.upscale;

public final class Fsr1Constants {
    public static final String VERSION = "1.0.2";
    public static final String UPSTREAM_COMMIT = "a21ffb8f6c13233ba336352bdff293894c706575";
    private static final double RCAS_MAX_STOPS = 2.0;

    private Fsr1Constants() {}

    public static Easu easu(
            int inputViewportWidth,
            int inputViewportHeight,
            int inputResourceWidth,
            int inputResourceHeight,
            int outputWidth,
            int outputHeight) {
        requirePositive(inputViewportWidth, "input viewport width");
        requirePositive(inputViewportHeight, "input viewport height");
        requirePositive(inputResourceWidth, "input resource width");
        requirePositive(inputResourceHeight, "input resource height");
        requirePositive(outputWidth, "output width");
        requirePositive(outputHeight, "output height");
        if (inputViewportWidth > inputResourceWidth || inputViewportHeight > inputResourceHeight) {
            throw new IllegalArgumentException("FSR1 input viewport cannot exceed its resource");
        }

        var viewportX = (float) inputViewportWidth;
        var viewportY = (float) inputViewportHeight;
        var inputX = (float) inputResourceWidth;
        var inputY = (float) inputResourceHeight;
        var outputX = (float) outputWidth;
        var outputY = (float) outputHeight;
        return new Easu(
                new Vec4(
                        viewportX / outputX,
                        viewportY / outputY,
                        0.5f * viewportX / outputX - 0.5f,
                        0.5f * viewportY / outputY - 0.5f),
                new Vec4(1f / inputX, 1f / inputY, 1f / inputX, -1f / inputY),
                new Vec4(-1f / inputX, 2f / inputY, 1f / inputX, 2f / inputY),
                new Vec4(0f, 4f / inputY, 0f, 0f));
    }

    public static void easuInto(
            int inputViewportWidth,
            int inputViewportHeight,
            int inputResourceWidth,
            int inputResourceHeight,
            int outputWidth,
            int outputHeight,
            float[] con0,
            float[] con1) {
        requirePositive(inputViewportWidth, "input viewport width");
        requirePositive(inputViewportHeight, "input viewport height");
        requirePositive(inputResourceWidth, "input resource width");
        requirePositive(inputResourceHeight, "input resource height");
        requirePositive(outputWidth, "output width");
        requirePositive(outputHeight, "output height");
        requireVectorCapacity(con0, "EASU con0");
        requireVectorCapacity(con1, "EASU con1");
        if (inputViewportWidth > inputResourceWidth || inputViewportHeight > inputResourceHeight) {
            throw new IllegalArgumentException("FSR1 input viewport cannot exceed its resource");
        }

        var viewportX = (float) inputViewportWidth;
        var viewportY = (float) inputViewportHeight;
        var inputX = (float) inputResourceWidth;
        var inputY = (float) inputResourceHeight;
        var outputX = (float) outputWidth;
        var outputY = (float) outputHeight;
        con0[0] = viewportX / outputX;
        con0[1] = viewportY / outputY;
        con0[2] = 0.5f * viewportX / outputX - 0.5f;
        con0[3] = 0.5f * viewportY / outputY - 0.5f;
        con1[0] = 1f / inputX;
        con1[1] = 1f / inputY;
        con1[2] = 1f / inputX;
        con1[3] = -1f / inputY;
    }

    public static Rcas rcas(double normalizedSharpness) {
        var stops = rcasSharpnessStops(normalizedSharpness);
        return new Rcas((float) Math.pow(2.0, -stops), (float) stops);
    }

    public static float rcasAttenuation(double normalizedSharpness) {
        return (float) Math.pow(2.0, -rcasSharpnessStops(normalizedSharpness));
    }

    public static float rcasSharpnessStops(double normalizedSharpness) {
        if (!Double.isFinite(normalizedSharpness) || normalizedSharpness < 0.0 || normalizedSharpness > 1.0) {
            throw new IllegalArgumentException("normalized RCAS sharpness must be within 0..1");
        }
        return (float) (RCAS_MAX_STOPS * (1.0 - normalizedSharpness));
    }

    private static void requirePositive(int value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static void requireVectorCapacity(float[] value, String name) {
        if (value == null || value.length < 4) {
            throw new IllegalArgumentException(name + " destination is too small");
        }
    }

    public record Easu(Vec4 con0, Vec4 con1, Vec4 con2, Vec4 con3) {}

    public record Rcas(float attenuation, float sharpnessStops) {}

    public record Vec4(float x, float y, float z, float w) {
        public void put(float[] target, int offset) {
            if (target == null || offset < 0 || target.length - offset < 4) {
                throw new IllegalArgumentException("FSR constant destination is too small");
            }
            target[offset] = x;
            target[offset + 1] = y;
            target[offset + 2] = z;
            target[offset + 3] = w;
        }
    }
}
