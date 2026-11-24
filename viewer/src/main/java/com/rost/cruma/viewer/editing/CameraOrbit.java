package com.rost.cruma.viewer.editing;

final class CameraOrbit {
    static final double DEGREES_PER_PIXEL = .3;

    static final double MAX_PITCH = 85;

    private CameraOrbit() {}

    record Pose(double x, double y, double z, double yaw, double pitch) {}

    static Pose orbit(Pose start, double centerX, double centerY, double centerZ, double turn, double tilt) {
        var pitch = Math.clamp(start.pitch() + tilt, -MAX_PITCH, MAX_PITCH);
        var yaw = start.yaw() + turn;
        var before = basis(start.yaw(), start.pitch());
        var dx = start.x() - centerX;
        var dy = start.y() - centerY;
        var dz = start.z() - centerZ;
        var along = dot(before[0], dx, dy, dz);
        var across = dot(before[1], dx, dy, dz);
        var up = dot(before[2], dx, dy, dz);
        var after = basis(yaw, pitch);
        return new Pose(
                centerX + along * after[0][0] + across * after[1][0] + up * after[2][0],
                centerY + along * after[0][1] + across * after[1][1] + up * after[2][1],
                centerZ + along * after[0][2] + across * after[1][2] + up * after[2][2],
                yaw,
                pitch);
    }

    static double[] drag(double dx, double dy) {
        return new double[] {dx * DEGREES_PER_PIXEL, -dy * DEGREES_PER_PIXEL};
    }

    private static double[][] basis(double yawDegrees, double pitchDegrees) {
        var yaw = Math.toRadians(yawDegrees);
        var pitch = Math.toRadians(pitchDegrees);
        var look = new double[] {Math.cos(pitch) * Math.cos(yaw), Math.cos(pitch) * Math.sin(yaw), Math.sin(pitch)};
        var right = new double[] {-Math.sin(yaw), Math.cos(yaw), 0};
        var up = new double[] {
            look[1] * right[2] - look[2] * right[1],
            look[2] * right[0] - look[0] * right[2],
            look[0] * right[1] - look[1] * right[0]
        };
        return new double[][] {look, right, up};
    }

    private static double dot(double[] axis, double x, double y, double z) {
        return axis[0] * x + axis[1] * y + axis[2] * z;
    }
}
