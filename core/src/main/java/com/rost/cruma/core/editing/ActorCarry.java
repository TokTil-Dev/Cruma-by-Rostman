package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.ActorTransform.Rotator;
import com.rost.cruma.core.editing.ActorTransform.Vector3;

public final class ActorCarry {
    private static final double RADIANS = Math.PI / 32768;

    private ActorCarry() {}

    public static ActorTransform carry(ActorTransform from, ActorTransform to, ActorTransform follower) {
        return new ActorTransform(
                carryLocation(from, to, follower.location()),
                carryRotation(from.rotation(), to.rotation(), follower.rotation()),
                follower.drawScale(),
                follower.drawScale3d(),
                follower.prePivot());
    }

    public static Vector3 carryLocation(ActorTransform from, ActorTransform to, Vector3 point) {
        var l0 = from.location();
        var l1 = to.location();
        var s0 = scale(from);
        var s1 = scale(to);
        if (from.rotation().equals(to.rotation()) && isSame(s0, s1)) {
            return new Vector3(
                    (float) ((double) point.x() + ((double) l1.x() - l0.x())),
                    (float) ((double) point.y() + ((double) l1.y() - l0.y())),
                    (float) ((double) point.z() + ((double) l1.z() - l0.z())));
        }
        var offset =
                new double[] {(double) point.x() - l0.x(), (double) point.y() - l0.y(), (double) point.z() - l0.z()};
        var local = timesTransposed(matrix(from.rotation()), offset);
        for (var axis = 0; axis < 3; axis++) {
            local[axis] *= Math.abs(s0[axis]) < 1e-12 ? 1 : s1[axis] / s0[axis];
        }
        var world = times(matrix(to.rotation()), local);
        return new Vector3((float) (l1.x() + world[0]), (float) (l1.y() + world[1]), (float) (l1.z() + world[2]));
    }

    public static Rotator carryRotation(Rotator from, Rotator to, Rotator follower) {
        if (from.equals(to)) {
            return follower;
        }
        if (from.pitch() == to.pitch() && from.roll() == to.roll()) {
            return new Rotator(follower.pitch(), follower.yaw() + wrap(to.yaw() - from.yaw()), follower.roll());
        }
        return toRotator(multiply(multiply(matrix(to), transpose(matrix(from))), matrix(follower)), follower);
    }

    static Rotator toRotator(double[][] turned, Rotator near) {
        var ry = Math.asin(Math.clamp(-turned[2][0], -1.0, 1.0));
        double rx;
        double rz;
        if (Math.abs(Math.cos(ry)) > 1e-9) {
            rx = Math.atan2(turned[2][1], turned[2][2]);
            rz = Math.atan2(turned[1][0], turned[0][0]);
        } else {
            rx = 0;
            rz = Math.atan2(-turned[0][1], turned[1][1]);
        }
        var first = new double[] {-ry / RADIANS, rz / RADIANS, -rx / RADIANS};
        var second = new double[] {32768 - first[0], first[1] + 32768, first[2] + 32768};
        var old = new double[] {near.pitch(), near.yaw(), near.roll()};
        var chosen = distance(first, old) <= distance(second, old) ? first : second;
        return new Rotator(
                nearest(chosen[0], near.pitch()), nearest(chosen[1], near.yaw()), nearest(chosen[2], near.roll()));
    }

    static double[][] matrix(Rotator rotation) {
        var x = -(short) rotation.roll() * RADIANS;
        var y = -(short) rotation.pitch() * RADIANS;
        var z = (short) rotation.yaw() * RADIANS;
        var cx = Math.cos(x);
        var sx = Math.sin(x);
        var cy = Math.cos(y);
        var sy = Math.sin(y);
        var cz = Math.cos(z);
        var sz = Math.sin(z);
        return new double[][] {
            {cz * cy, cz * sy * sx - sz * cx, cz * sy * cx + sz * sx},
            {sz * cy, sz * sy * sx + cz * cx, sz * sy * cx - cz * sx},
            {-sy, cy * sx, cy * cx}
        };
    }

    private static double[] scale(ActorTransform transform) {
        var axes = transform.drawScale3d();
        double uniform = transform.drawScale();
        return new double[] {uniform * axes.x(), uniform * axes.y(), uniform * axes.z()};
    }

    private static boolean isSame(double[] a, double[] b) {
        return a[0] == b[0] && a[1] == b[1] && a[2] == b[2];
    }

    private static double[][] multiply(double[][] a, double[][] b) {
        var result = new double[3][3];
        for (var row = 0; row < 3; row++) {
            for (var column = 0; column < 3; column++) {
                for (var k = 0; k < 3; k++) {
                    result[row][column] += a[row][k] * b[k][column];
                }
            }
        }
        return result;
    }

    private static double[][] transpose(double[][] m) {
        var result = new double[3][3];
        for (var row = 0; row < 3; row++) {
            for (var column = 0; column < 3; column++) {
                result[row][column] = m[column][row];
            }
        }
        return result;
    }

    private static double[] times(double[][] m, double[] v) {
        return new double[] {
            m[0][0] * v[0] + m[0][1] * v[1] + m[0][2] * v[2],
            m[1][0] * v[0] + m[1][1] * v[1] + m[1][2] * v[2],
            m[2][0] * v[0] + m[2][1] * v[1] + m[2][2] * v[2]
        };
    }

    private static double[] timesTransposed(double[][] m, double[] v) {
        return new double[] {
            m[0][0] * v[0] + m[1][0] * v[1] + m[2][0] * v[2],
            m[0][1] * v[0] + m[1][1] * v[1] + m[2][1] * v[2],
            m[0][2] * v[0] + m[1][2] * v[1] + m[2][2] * v[2]
        };
    }

    private static double distance(double[] angles, double[] old) {
        var sum = 0.0;
        for (var index = 0; index < 3; index++) {
            sum += Math.abs(wrap(angles[index] - old[index]));
        }
        return sum;
    }

    private static int nearest(double angle, int old) {
        return old + (int) Math.round(wrap(angle - old));
    }

    private static double wrap(double units) {
        return units - 65536 * Math.floor((units + 32768) / 65536);
    }

    private static int wrap(int units) {
        return (short) units;
    }
}
