package com.rost.cruma.viewer.render.api;

public record RenderExtents(Extent display, Extent render, Extent reflection, Extent bloomQuarter, Extent capture) {
    public static final int CAPTURE_WIDTH = 1024;
    public static final int CAPTURE_HEIGHT = 1024;

    public RenderExtents {
        if (display == null || render == null || reflection == null || bloomQuarter == null || capture == null) {
            throw new IllegalArgumentException("render extents cannot contain null values");
        }
    }

    public enum Domain {
        DISPLAY,
        RENDER,
        REFLECTION,
        BLOOM_QUARTER,
        CAPTURE
    }

    public static RenderExtents resolve(
            int displayWidth, int displayHeight, double renderScale, double reflectionScale) {
        return resolve(
                displayWidth, displayHeight, renderScale, reflectionScale, new Extent(CAPTURE_WIDTH, CAPTURE_HEIGHT));
    }

    public static RenderExtents resolve(
            int displayWidth, int displayHeight, double renderScale, double reflectionScale, Extent capture) {
        var display = new Extent(displayWidth, displayHeight);
        var render = display.scaled(renderScale);
        var reflection = render.scaled(reflectionScale);
        var bloom = render.divCeil(4);
        return new RenderExtents(display, render, reflection, bloom, capture);
    }

    public Extent activeWorld(boolean captureFrame) {
        return captureFrame ? capture : render;
    }

    public record Extent(int width, int height) {
        public Extent {
            if (width < 0 || height < 0) {
                throw new IllegalArgumentException("extent cannot be negative: " + width + "x" + height);
            }
            if ((width == 0) != (height == 0)) {
                throw new IllegalArgumentException("only 0x0 is a valid zero extent");
            }
        }

        public boolean isZero() {
            return width == 0;
        }

        public long pixels() {
            return (long) width * height;
        }

        public Extent scaled(double scale) {
            if (isZero()) {
                return this;
            }
            if (!Double.isFinite(scale) || scale <= 0.0 || scale > 1.0) {
                throw new IllegalArgumentException("scale must be finite and within (0, 1]");
            }
            return new Extent(
                    Math.max(1, (int) Math.round(width * scale)), Math.max(1, (int) Math.round(height * scale)));
        }

        public Extent divCeil(int divisor) {
            if (divisor < 1) {
                throw new IllegalArgumentException("divisor must be positive");
            }
            if (isZero()) {
                return this;
            }
            return new Extent((width + divisor - 1) / divisor, (height + divisor - 1) / divisor);
        }
    }
}
