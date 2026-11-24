package com.rost.cruma.core.world;

public final class FixedFunctionCoordinates {
    private FixedFunctionCoordinates() {}

    public static double[] reflection(double[] viewPosition, double[] viewNormal) {
        var view = normalize(viewPosition);
        var normal = normalize(viewNormal);
        var along = 2 * dot(view, normal);
        return new double[] {view[0] - along * normal[0], view[1] - along * normal[1], view[2] - along * normal[2]};
    }

    public static double[] sphereMap(double[] reflection) {
        var dz = reflection[2] - 1;
        var m = 2 * Math.sqrt(reflection[0] * reflection[0] + reflection[1] * reflection[1] + dz * dz);
        return new double[] {reflection[0] / m + 0.5, reflection[1] / m + 0.5};
    }

    public static double[] toWorld(double[] viewVector, double[][] viewRotation) {
        var world = new double[3];
        for (var column = 0; column < 3; column++) {
            for (var row = 0; row < 3; row++) {
                world[column] += viewVector[row] * viewRotation[row][column];
            }
        }
        return world;
    }

    private static double[] normalize(double[] vector) {
        var length = Math.sqrt(dot(vector, vector));
        return new double[] {vector[0] / length, vector[1] / length, vector[2] / length};
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }
}
