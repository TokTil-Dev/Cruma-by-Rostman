package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.ActorTransform.Rotator;
import com.rost.cruma.core.unreal.MeshDecorationData;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.IntFunction;

public final class MeshDecorationCarry {
    private MeshDecorationCarry() {}

    public static byte[] carry(
            byte[] value, IntFunction<String> names, boolean quads, ActorTransform from, ActorTransform to) {
        var places = MeshDecorationData.findPlaces(value, names, quads);
        var result = value.clone();
        if (from.equals(to)) {
            return result;
        }
        var buffer = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN);
        var change = new Change(from, to);
        for (var at : places.quadLocations()) {
            change.moveLocation(buffer, at);
        }
        for (var instance = 0; instance < places.size(); instance++) {
            change.moveLocation(buffer, places.locations()[instance]);
            change.turn(buffer, places.rotations()[instance], places.scales()[instance]);
        }
        return result;
    }

    public static double[][] toSimilarPart(double[][] linear) {
        var det = determinant(linear);
        if (!Double.isFinite(det) || Math.abs(det) < 1e-18) {
            throw new IllegalArgumentException("A change of zero scale cannot carry decorations");
        }
        var k = Math.cbrt(Math.abs(det));
        var q = new double[3][3];
        for (var row = 0; row < 3; row++) {
            for (var column = 0; column < 3; column++) {
                q[row][column] = linear[row][column] / k;
            }
        }
        for (var step = 0; step < 40; step++) {
            var inverse = invert(q);
            var largest = 0.0;
            for (var row = 0; row < 3; row++) {
                for (var column = 0; column < 3; column++) {
                    var next = (q[row][column] + inverse[column][row]) / 2;
                    largest = Math.max(largest, Math.abs(next - q[row][column]));
                    q[row][column] = next;
                }
            }
            if (largest < 1e-15) {
                break;
            }
        }
        for (var row = 0; row < 3; row++) {
            for (var column = 0; column < 3; column++) {
                q[row][column] *= k;
            }
        }
        return q;
    }

    private static final class Change {
        private final double[][] linear;
        private final double[] translation;
        private final boolean moveOnly;
        private final double[] offset;
        private final Rotator fromRotation;
        private final Rotator toRotation;
        private final boolean similar;
        private final double factor;
        private final double[][] similarTurn;

        private Change(ActorTransform from, ActorTransform to) {
            var a0 = model(from);
            var a1 = model(to);
            var s0 = scale(from);
            var s1 = scale(to);
            for (var axis = 0; axis < 3; axis++) {
                if (s0[axis] == 0 || s1[axis] == 0) {
                    throw new IllegalArgumentException("An actor of zero scale cannot carry decorations");
                }
            }
            var inverse = invert(a0.linear);
            linear = multiply(a1.linear, inverse);
            var back = times(inverse, a0.translation);
            var moved = times(a1.linear, back);
            translation = new double[] {
                a1.translation[0] - moved[0], a1.translation[1] - moved[1], a1.translation[2] - moved[2]
            };
            fromRotation = from.rotation();
            toRotation = to.rotation();
            moveOnly = fromRotation.equals(toRotation)
                    && s0[0] == s1[0]
                    && s0[1] == s1[1]
                    && s0[2] == s1[2]
                    && from.prePivot().equals(to.prePivot());
            offset = new double[] {
                (double) to.location().x() - from.location().x(),
                (double) to.location().y() - from.location().y(),
                (double) to.location().z() - from.location().z()
            };
            var ratio = new double[] {s1[0] / s0[0], s1[1] / s0[1], s1[2] / s0[2]};
            similar = ratio[0] > 0 && isClose(ratio[0], ratio[1]) && isClose(ratio[0], ratio[2]);
            factor = similar ? ratio[0] : Math.cbrt(Math.abs(ratio[0] * ratio[1] * ratio[2]));
            var part = toSimilarPart(linear);
            similarTurn = new double[3][3];
            for (var row = 0; row < 3; row++) {
                for (var column = 0; column < 3; column++) {
                    similarTurn[row][column] = part[row][column] / factor;
                }
            }
        }

        private void moveLocation(ByteBuffer buffer, int at) {
            if (at < 0) {
                throw new IllegalStateException("A placed decoration leaves out its Location");
            }
            var point = new double[] {buffer.getFloat(at), buffer.getFloat(at + 4), buffer.getFloat(at + 8)};
            double[] world;
            if (moveOnly) {
                world = new double[] {point[0] + offset[0], point[1] + offset[1], point[2] + offset[2]};
            } else {
                world = times(linear, point);
                for (var axis = 0; axis < 3; axis++) {
                    world[axis] += translation[axis];
                }
            }
            for (var axis = 0; axis < 3; axis++) {
                buffer.putFloat(at + axis * 4, (float) world[axis]);
            }
        }

        private void turn(ByteBuffer buffer, int rotationAt, int scaleAt) {
            if (moveOnly) {
                return;
            }
            if (rotationAt < 0 || scaleAt < 0) {
                throw new IllegalStateException("A placed decoration leaves out its Rotation or Scale");
            }
            var rotation = new Rotator(
                    buffer.getInt(rotationAt), buffer.getInt(rotationAt + 4), buffer.getInt(rotationAt + 8));
            var scale =
                    new double[] {buffer.getFloat(scaleAt), buffer.getFloat(scaleAt + 4), buffer.getFloat(scaleAt + 8)};
            Rotator turned;
            if (similar) {
                turned = ActorCarry.carryRotation(fromRotation, toRotation, rotation);
            } else {
                var matrix = multiply(similarTurn, ActorCarry.matrix(rotation));
                if (determinant(matrix) < 0) {
                    for (var row = 0; row < 3; row++) {
                        matrix[row][0] = -matrix[row][0];
                    }
                    scale[0] = -scale[0];
                }
                turned = ActorCarry.toRotator(matrix, rotation);
            }
            if (!turned.equals(rotation)) {
                buffer.putInt(rotationAt, turned.pitch());
                buffer.putInt(rotationAt + 4, turned.yaw());
                buffer.putInt(rotationAt + 8, turned.roll());
            }
            if (factor != 1 || !similar) {
                for (var axis = 0; axis < 3; axis++) {
                    buffer.putFloat(scaleAt + axis * 4, (float) (scale[axis] * factor));
                }
            }
        }
    }

    private record Model(double[][] linear, double[] translation) {}

    private static Model model(ActorTransform transform) {
        var rotation = ActorCarry.matrix(transform.rotation());
        var scale = scale(transform);
        var linear = new double[3][3];
        for (var row = 0; row < 3; row++) {
            for (var column = 0; column < 3; column++) {
                linear[row][column] = rotation[row][column] * scale[column];
            }
        }
        var pivot = transform.prePivot();
        var shifted = times(linear, new double[] {pivot.x(), pivot.y(), pivot.z()});
        var location = transform.location();
        return new Model(
                linear, new double[] {location.x() - shifted[0], location.y() - shifted[1], location.z() - shifted[2]});
    }

    private static double[] scale(ActorTransform transform) {
        var axes = transform.drawScale3d();
        double uniform = transform.drawScale();
        return new double[] {uniform * axes.x(), uniform * axes.y(), uniform * axes.z()};
    }

    private static boolean isClose(double a, double b) {
        return Math.abs(a - b) <= 1e-9 * Math.max(Math.abs(a), Math.abs(b));
    }

    private static double determinant(double[][] m) {
        return m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1])
                - m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0])
                + m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0]);
    }

    private static double[][] invert(double[][] m) {
        var det = determinant(m);
        var r = new double[3][3];
        r[0][0] = (m[1][1] * m[2][2] - m[1][2] * m[2][1]) / det;
        r[0][1] = (m[0][2] * m[2][1] - m[0][1] * m[2][2]) / det;
        r[0][2] = (m[0][1] * m[1][2] - m[0][2] * m[1][1]) / det;
        r[1][0] = (m[1][2] * m[2][0] - m[1][0] * m[2][2]) / det;
        r[1][1] = (m[0][0] * m[2][2] - m[0][2] * m[2][0]) / det;
        r[1][2] = (m[0][2] * m[1][0] - m[0][0] * m[1][2]) / det;
        r[2][0] = (m[1][0] * m[2][1] - m[1][1] * m[2][0]) / det;
        r[2][1] = (m[0][1] * m[2][0] - m[0][0] * m[2][1]) / det;
        r[2][2] = (m[0][0] * m[1][1] - m[0][1] * m[1][0]) / det;
        return r;
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

    private static double[] times(double[][] m, double[] v) {
        return new double[] {
            m[0][0] * v[0] + m[0][1] * v[1] + m[0][2] * v[2],
            m[1][0] * v[0] + m[1][1] * v[1] + m[1][2] * v[2],
            m[2][0] * v[0] + m[2][1] * v[1] + m[2][2] * v[2]
        };
    }
}
