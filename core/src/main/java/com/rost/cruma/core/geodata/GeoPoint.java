package com.rost.cruma.core.geodata;

public record GeoPoint(double x, double y, double z) {
    public GeoPoint {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("Geodata position must be finite");
        }
    }

    public double distance(GeoPoint other) {
        return Math.sqrt(distanceSquared(other));
    }

    public double distanceSquared(GeoPoint other) {
        var dx = x - other.x;
        var dy = y - other.y;
        var dz = z - other.z;
        return dx * dx + dy * dy + dz * dz;
    }
}
