package com.rost.cruma.viewer.render;

public record ScreenLens(boolean stock, float leastShape) {
    public static final ScreenLens WORLD = new ScreenLens(false, 0f);

    public static ScreenLens lobby(float leastShape) {
        return new ScreenLens(true, leastShape);
    }

    public float computeXScale(float fov, float width, float height) {
        var tangent = Math.tan((float) (fov * Math.PI / 360.0));
        if (stock) {
            return (float) (1.0 / tangent);
        }
        var xAspect = Math.max(0.2f, Math.min(1.0f, (height / width) * 4.0f / 3.0f));
        return (float) (xAspect / tangent);
    }

    public float computeYScale(float fov, float width, float height) {
        var tangent = Math.tan((float) (fov * Math.PI / 360.0));
        var aspect = width / height;
        var yAspect = stock ? Math.max(leastShape, aspect) : Math.max(0.26666668f, Math.min(1.33333337f, aspect));
        return (float) (yAspect / tangent);
    }

    public double computeHalfWidth(double fov, double shape) {
        var tangent = Math.tan(Math.toRadians(fov) / 2);
        return stock ? tangent : tangent / Math.clamp(4.0 / 3 / shape, .2, 1);
    }

    public double computeHalfHeight(double fov, double shape) {
        var tangent = Math.tan(Math.toRadians(fov) / 2);
        return tangent / (stock ? Math.max(leastShape, shape) : Math.clamp(shape, 4.0 / 15, 4.0 / 3));
    }
}
