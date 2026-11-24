package com.rost.cruma.viewer.spawn;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class SpawnShapes {
    private static final int SEGMENTS = 14;
    private static final int CAP_RINGS = 4;

    private static final double[] LIGHT = normalize(-.35, -.45, .82);

    private SpawnShapes() {}

    public record Body(double x, double y, double z, double radius, double height, int argb, float alpha) {}

    public static void addCurtain(
            SpawnTriangles out, float[] points, float lift, float height, int argb, float bottomAlpha) {
        var low = SpawnStyle.getImguiColor(argb, bottomAlpha, 1f);
        var high = SpawnStyle.getImguiColor(argb, 0f, 1f);
        for (var i = 0; i + 5 < points.length; i += 3) {
            var ax = points[i];
            var ay = points[i + 1];
            var az = points[i + 2] + lift;
            var bx = points[i + 3];
            var by = points[i + 4];
            var bz = points[i + 5] + lift;
            out.addQuad(ax, ay, az, low, bx, by, bz, low, bx, by, bz + height, high, ax, ay, az + height, high);
        }
    }

    public static int[] thinOutline(int samples, int[] corners, int step) {
        if (samples <= 0) {
            return new int[0];
        }
        var kept = new boolean[samples];
        for (var i = 0; i < samples; i += Math.max(1, step)) {
            kept[i] = true;
        }
        for (var corner : corners) {
            if (corner >= 0 && corner < samples) {
                kept[corner] = true;
            }
        }
        kept[samples - 1] = true;
        var count = 0;
        for (var keep : kept) {
            if (keep) {
                count++;
            }
        }
        var result = new int[count];
        var next = 0;
        for (var i = 0; i < samples; i++) {
            if (kept[i]) {
                result[next++] = i;
            }
        }
        return result;
    }

    public static void addBodies(SpawnTriangles out, List<Body> bodies, double eyeX, double eyeY, double eyeZ) {
        var ordered = new ArrayList<>(bodies);
        ordered.sort(Comparator.comparingDouble((Body body) -> {
                    var dx = body.x() - eyeX;
                    var dy = body.y() - eyeY;
                    var dz = body.z() + body.height() * .5 - eyeZ;
                    return dx * dx + dy * dy + dz * dz;
                })
                .reversed());
        var triangles = new ArrayList<double[]>();
        for (var body : ordered) {
            if (out.wouldOverflow(SEGMENTS * (CAP_RINGS * 2 + 1) * 6)) {
                return;
            }
            triangles.clear();
            buildCapsule(body, triangles);
            for (var pass = 0; pass < 2; pass++) {
                for (var triangle : triangles) {
                    var facing = facing(triangle, eyeX, eyeY, eyeZ);
                    if (pass == 0 ? facing < 0 : facing >= 0) {
                        emit(out, triangle, body);
                    }
                }
            }
        }
    }

    private static void buildCapsule(Body body, List<double[]> triangles) {
        var radius = body.radius();
        var height = Math.max(body.height(), radius * 2);
        var rings = new ArrayList<double[]>();
        rings.add(new double[] {0, 0, -1});
        for (var i = 1; i <= CAP_RINGS; i++) {
            var angle = -Math.PI / 2 + Math.PI / 2 * i / CAP_RINGS;
            rings.add(new double[] {radius + Math.sin(angle) * radius, Math.cos(angle) * radius, Math.sin(angle)});
        }
        for (var i = 0; i < CAP_RINGS; i++) {
            var angle = Math.PI / 2 * i / CAP_RINGS;
            rings.add(
                    new double[] {height - radius + Math.sin(angle) * radius, Math.cos(angle) * radius, Math.sin(angle)
                    });
        }
        rings.add(new double[] {height, 0, 1});
        for (var ring = 0; ring + 1 < rings.size(); ring++) {
            var lower = rings.get(ring);
            var upper = rings.get(ring + 1);
            for (var segment = 0; segment < SEGMENTS; segment++) {
                var a0 = 2 * Math.PI * segment / SEGMENTS;
                var a1 = 2 * Math.PI * (segment + 1) / SEGMENTS;
                var p00 = ringPoint(body, lower, a0);
                var p01 = ringPoint(body, lower, a1);
                var p10 = ringPoint(body, upper, a0);
                var p11 = ringPoint(body, upper, a1);
                if (lower[1] > 1e-6) {
                    triangles.add(join(p00, p01, p11));
                }
                if (upper[1] > 1e-6) {
                    triangles.add(join(p00, p11, p10));
                }
            }
        }
    }

    private static double[] ringPoint(Body body, double[] ring, double angle) {
        var cos = Math.cos(angle);
        var sin = Math.sin(angle);
        var horizontal = Math.sqrt(Math.max(0, 1 - ring[2] * ring[2]));
        return new double[] {
            body.x() + cos * ring[1],
            body.y() + sin * ring[1],
            body.z() + ring[0],
            cos * horizontal,
            sin * horizontal,
            ring[2]
        };
    }

    private static double[] join(double[] a, double[] b, double[] c) {
        var triangle = new double[18];
        System.arraycopy(a, 0, triangle, 0, 6);
        System.arraycopy(b, 0, triangle, 6, 6);
        System.arraycopy(c, 0, triangle, 12, 6);
        return triangle;
    }

    private static double facing(double[] triangle, double eyeX, double eyeY, double eyeZ) {
        var nx = triangle[3] + triangle[9] + triangle[15];
        var ny = triangle[4] + triangle[10] + triangle[16];
        var nz = triangle[5] + triangle[11] + triangle[17];
        var cx = (triangle[0] + triangle[6] + triangle[12]) / 3;
        var cy = (triangle[1] + triangle[7] + triangle[13]) / 3;
        var cz = (triangle[2] + triangle[8] + triangle[14]) / 3;
        return nx * (eyeX - cx) + ny * (eyeY - cy) + nz * (eyeZ - cz);
    }

    private static void emit(SpawnTriangles out, double[] triangle, Body body) {
        out.addTriangle(
                (float) triangle[0],
                (float) triangle[1],
                (float) triangle[2],
                shade(body, triangle, 3),
                (float) triangle[6],
                (float) triangle[7],
                (float) triangle[8],
                shade(body, triangle, 9),
                (float) triangle[12],
                (float) triangle[13],
                (float) triangle[14],
                shade(body, triangle, 15));
    }

    private static int shade(Body body, double[] triangle, int normal) {
        var lambert = Math.max(
                0, triangle[normal] * LIGHT[0] + triangle[normal + 1] * LIGHT[1] + triangle[normal + 2] * LIGHT[2]);
        var sky = .5 + .5 * triangle[normal + 2];
        var brightness = (float) (.38 + .5 * lambert + .22 * sky);
        return SpawnStyle.getImguiColor(body.argb(), body.alpha(), brightness);
    }

    private static double[] normalize(double x, double y, double z) {
        var length = Math.sqrt(x * x + y * y + z * z);
        return new double[] {x / length, y / length, z / length};
    }
}
