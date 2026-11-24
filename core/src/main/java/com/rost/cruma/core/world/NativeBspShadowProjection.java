package com.rost.cruma.core.world;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class NativeBspShadowProjection {
    private final float[] worldToView, projection, worldToScreen, screenToWorld;
    private final List<Plane> planes;

    private NativeBspShadowProjection(Placement p, Vector input, boolean sun, Rect rectangle) {
        Objects.requireNonNull(p);
        Objects.requireNonNull(input);
        Objects.requireNonNull(rectangle).requireWithin(p);
        var width = rectangle.maxX - rectangle.minX + 1;
        var height = rectangle.maxY - rectangle.minY + 1;
        var inv = inverse(p.worldToLightmap);
        var cx = (width + 1f) * .5f + rectangle.minX;
        var cy = (height + 1f) * .5f + rectangle.minY;
        var center = sun ? transformXy(inv, cx, cy) : transform(inv, cx, cy, 0, 1);
        var u = transformXyDirection(inv, width * .5f, 0);
        var v = transform(inv, 0, height * .5f, 0, 0);
        var normal = safeNormal(cross(u, v));
        var plane = p.plane;
        var reciprocal = 1f / ((plane.x * normal.x + plane.y * normal.y) + plane.z * normal.z);
        requireFinite(reciprocal);
        var centerDistance = ((center.x * plane.x + center.y * plane.y) + center.z * plane.z) - plane.w;
        center = offsetAlongNormal(center, normal, centerDistance, reciprocal);
        u = offsetAlongNormal(u, normal, (u.y * plane.y + u.x * plane.x) + u.z * plane.z, reciprocal);
        v = offsetAlongNormal(v, normal, (v.y * plane.y + v.x * plane.x) + v.z * plane.z, reciprocal);
        var sideValue = (input.y * plane.y + input.x * plane.x) + input.z * plane.z;
        if (!sun) {
            sideValue -= plane.w;
        }
        var side = sideValue <= 0 ? -1f : 1f;
        var back = new Vector(plane.x * -side, plane.y * -side, plane.z * -side);
        Vector camera;
        if (sun) {
            var length = size(input);
            if (Math.abs(length - 1f) > 0.000001f) {
                throw new IllegalArgumentException("sun input must be the resolved native unit direction");
            }
            var distance = 65536f / ((-back.x * input.x + -back.y * input.y) + -back.z * input.z);
            requireFinite(distance);
            camera = new Vector(
                    input.x * distance + center.x, input.y * distance + center.y, input.z * distance + center.z);
        } else {
            camera = input;
        }
        var ru = reciprocalVector(u);
        var rv = reciprocalVector(v);
        var rn = reciprocalVector(back);
        var basis = new float[] {ru.x, rv.x, rn.x, 0, ru.y, rv.y, rn.y, 0, ru.z, rv.z, rn.z, 0, 0, 0, 0, 1};
        var translation = identity();
        translation[12] = -camera.x;
        translation[13] = -camera.y;
        translation[14] = -camera.z;
        worldToView = multiply(multiply(identity(), translation), basis);
        var c = transform(worldToView, center.x, center.y, center.z, 1);
        var tu = transform(worldToView, u.x, u.y, u.z, 0);
        var tv = transform(worldToView, v.x, v.y, v.z, 0);
        if (c.z == 0 || c.z == 1) {
            throw new IllegalArgumentException("degenerate projection depth");
        }
        var shear = identity();
        shear[8] = -(c.x / c.z);
        shear[9] = -(c.y / c.z);
        var scale = identity();
        scale[0] = sun ? 1f / size(tu) : size(tu) * c.z;
        scale[5] = sun ? 1f / size(tv) : size(tv) * c.z;
        var depth = identity();
        depth[5] = -1;
        var k = sun ? 1f / (c.z - 1f) : c.z / (c.z - 1f);
        depth[10] = k;
        depth[14] = -k;
        if (!sun) {
            depth[11] = 1;
            depth[15] = 0;
        }
        projection = multiply(multiply(multiply(identity(), shear), scale), depth);
        worldToScreen = multiply(worldToView, projection);
        screenToWorld = multiply(inverse(projection), inverse(worldToView));
        planes = List.copyOf(buildFrustum(sun, center, camera));
    }

    public record Vector(float x, float y, float z) {
        public Vector {
            requireFinite(x, y, z);
        }
    }

    public record Plane(float x, float y, float z, float w) {
        public Plane {
            requireFinite(x, y, z, w);
            if (x == 0 && y == 0 && z == 0 && w != 0) {
                throw new IllegalArgumentException("zero plane normal with nonzero distance");
            }
        }
    }

    public record Bounds(Vector min, Vector max) {
        public Bounds {
            Objects.requireNonNull(min);
            Objects.requireNonNull(max);
            if (min.x > max.x || min.y > max.y || min.z > max.z) {
                throw new IllegalArgumentException("inverted bounds");
            }
        }
    }

    public record Sphere(Vector center, float radius) {
        public Sphere {
            Objects.requireNonNull(center);
            requireFinite(radius);
            if (radius < 0) {
                throw new IllegalArgumentException("negative sphere radius");
            }
        }
    }

    public static final class Placement {
        private final int width, height;
        private final float[] worldToLightmap;
        private final Plane plane;

        public Placement(int width, int height, float[] worldToLightmap, Plane plane) {
            if (width < 1 || height < 1 || width > 65536 || height > 65536) {
                throw new IllegalArgumentException("unsupported lightmap dimensions");
            }
            this.width = width;
            this.height = height;
            this.worldToLightmap = copyMatrix(worldToLightmap);
            this.plane = Objects.requireNonNull(plane);
            if (plane.x == 0 && plane.y == 0 && plane.z == 0) {
                throw new IllegalArgumentException("zero receiver surface normal");
            }
            if (this.worldToLightmap[3] != 0
                    || this.worldToLightmap[7] != 0
                    || this.worldToLightmap[11] != 0
                    || this.worldToLightmap[15] != 1) {
                throw new IllegalArgumentException("placement must be affine");
            }
            inverse(this.worldToLightmap);
        }

        public int getWidth() {
            return width;
        }

        public int getHeight() {
            return height;
        }

        public float[] getWorldToLightmap() {
            return worldToLightmap.clone();
        }

        public Plane getPlane() {
            return plane;
        }
    }

    public record Rect(int minX, int minY, int maxX, int maxY) {
        public Rect {
            if (minX < 0 || minY < 0 || maxX < minX || maxY < minY || maxX >= 65536 || maxY >= 65536) {
                throw new IllegalArgumentException("invalid inclusive lightmap rectangle");
            }
        }

        private void requireWithin(Placement placement) {
            if (maxX >= placement.width || maxY >= placement.height) {
                throw new IllegalArgumentException("rectangle is outside its lightmap");
            }
        }
    }

    public static final class Volume {
        private final List<Plane> planes;

        public Volume(List<Plane> planes) {
            this.planes = List.copyOf(planes);
            if (planes.isEmpty() || planes.size() > 32) {
                throw new IllegalArgumentException("native volume requires 1..32 planes");
            }
        }

        public List<Plane> getPlanes() {
            return planes;
        }

        public int checkBox(Bounds bounds) {
            var c = center(bounds);
            var e = sub(bounds.max, c);
            int flags = 1;
            for (var p : planes) {
                var d = distance(p, c);
                var r = projectedRadius(p, e);
                requireFinite(d, r);
                if (-r < d) {
                    flags |= 2;
                    if (r < d) {
                        return 2;
                    }
                }
            }
            return flags;
        }

        public int checkSphere(Sphere sphere) {
            Objects.requireNonNull(sphere);
            int flags = 1;
            for (var plane : planes) {
                var d = distance(plane, sphere.center);
                requireFinite(d);
                if (-sphere.radius < d) {
                    flags |= 2;
                    if (sphere.radius < d) {
                        return 2;
                    }
                }
            }
            return flags;
        }

        public Optional<Separation> findSeparatingPlane(Bounds bounds) {
            var c = center(bounds);
            var e = sub(bounds.max, c);
            for (int i = 0; i < planes.size(); i++) {
                var p = planes.get(i);
                var d = distance(p, c);
                var r = projectedRadius(p, e);
                requireFinite(d, r);
                if (!(d > r)) {
                    continue;
                }
                double dx = (double) p.x * c.x, dy = (double) p.y * c.y, dz = (double) p.z * c.z;
                double rx = Math.abs((double) p.x) * e.x,
                        ry = Math.abs((double) p.y) * e.y,
                        rz = Math.abs((double) p.z) * e.z;
                var dLower = nextDown(nextDown(dy + dx) + dz);
                dLower = nextDown(dLower - p.w);
                var rUpper = nextUp(nextUp(ry + rx) + rz);
                var dAbs = nextUp(nextUp(nextUp(Math.abs(dx) + Math.abs(dy)) + Math.abs(dz)) + Math.abs((double) p.w));
                var rAbs = nextUp(nextUp(rx + ry) + rz);
                var error = nextUp(nextUp(gamma(6) * dAbs) + nextUp(gamma(5) * rAbs));
                error = nextUp(error + 11d * Float.MIN_VALUE);
                var nativeMargin = nextDown(nextDown(dLower - rUpper) - error);
                double x = p.x >= 0 ? bounds.min.x : bounds.max.x;
                double y = p.y >= 0 ? bounds.min.y : bounds.max.y;
                double z = p.z >= 0 ? bounds.min.z : bounds.max.z;
                var geometricMargin = nextDown(nextDown(nextDown(p.y * y + p.x * x) + p.z * z) - p.w);
                var margin = Math.min(nativeMargin, geometricMargin);
                if (Double.isFinite(margin) && margin > 0) {
                    return Optional.of(new Separation(i, margin, error));
                }
            }
            return Optional.empty();
        }

        private static double gamma(int n) {
            var nu = n * 0x1.0p-24;
            return nextUp(nu / (1d - nu));
        }

        private static double nextUp(double value) {
            return Math.nextUp(value);
        }

        private static double nextDown(double value) {
            return Math.nextDown(value);
        }

        private static Vector center(Bounds b) {
            Objects.requireNonNull(b);
            return new Vector((b.min.x + b.max.x) * .5f, (b.min.y + b.max.y) * .5f, (b.min.z + b.max.z) * .5f);
        }

        private static float distance(Plane p, Vector c) {
            return (p.y * c.y + p.x * c.x) + p.z * c.z - p.w;
        }

        private static float projectedRadius(Plane p, Vector e) {
            return (Math.abs(p.y) * e.y + Math.abs(p.x) * e.x) + Math.abs(p.z) * e.z;
        }
    }

    public record Separation(int planeIndex, double minimumMargin, double floatErrorBound) {}

    public static NativeBspShadowProjection point(Placement placement, Vector lightPosition) {
        return point(placement, lightPosition, fullRect(placement));
    }

    public static NativeBspShadowProjection point(Placement placement, Vector lightPosition, Rect rectangle) {
        return new NativeBspShadowProjection(placement, lightPosition, false, rectangle);
    }

    public static Rect pointCrop(
            Placement placement,
            Vector savedAxisU,
            Vector savedAxisV,
            Vector lightPosition,
            float authoredLightRadius) {
        Objects.requireNonNull(placement);
        Objects.requireNonNull(savedAxisU);
        Objects.requireNonNull(savedAxisV);
        Objects.requireNonNull(lightPosition);
        requireFinite(authoredLightRadius);
        if (authoredLightRadius < 0) {
            throw new IllegalArgumentException("negative light radius");
        }
        var p = placement.plane;
        var distance = (p.y * lightPosition.y + p.x * lightPosition.x) + p.z * lightPosition.z - p.w;
        var worldRadius = (authoredLightRadius + 1f) * 25f;
        var squaredRadius = (worldRadius * worldRadius) * 1.05f - distance * distance;
        requireFinite(distance, worldRadius, squaredRadius);
        var projectedRadius = (float) Math.sqrt(Math.max(0f, squaredRadius));
        var point = new Vector(
                lightPosition.x - p.x * distance, lightPosition.y - p.y * distance, lightPosition.z - p.z * distance);
        var matrix = placement.worldToLightmap;
        var x = ((point.y * matrix[4] + point.x * matrix[0]) + point.z * matrix[8]) + matrix[12];
        var y = ((point.y * matrix[5] + point.x * matrix[1]) + point.z * matrix[9]) + matrix[13];
        var u = projectedRadius / size(savedAxisU);
        var v = projectedRadius / size(savedAxisV);
        requireFinite(x, y, u, v);
        return new Rect(
                cropLimit(x - u, placement.width),
                cropLimit(y - v, placement.height),
                cropLimit(x + u, placement.width),
                cropLimit(y + v, placement.height));
    }

    public static NativeBspShadowProjection sun(Placement placement, Vector resolvedTowardLightDirection) {
        return new NativeBspShadowProjection(placement, resolvedTowardLightDirection, true, fullRect(placement));
    }

    public float[] getWorldToView() {
        return worldToView.clone();
    }

    public float[] getProjection() {
        return projection.clone();
    }

    public float[] getWorldToScreen() {
        return worldToScreen.clone();
    }

    public float[] getScreenToWorld() {
        return screenToWorld.clone();
    }

    public List<Plane> getPlanes() {
        return planes;
    }

    public Volume getVolume() {
        return new Volume(planes);
    }

    private List<Plane> buildFrustum(boolean sun, Vector center, Vector origin) {
        var positive = determinant(worldToScreen) >= 0f;
        var corners = new Vector[sun ? 8 : 4];
        int index = 0;
        if (sun) {
            for (float x : new float[] {-1, 1}) {
                for (float y : new float[] {-1, 1}) {
                    for (float z : new float[] {0, 1}) {
                        corners[index++] = transformFrustum(screenToWorld, x, y, z, 1);
                    }
                }
            }
        } else {
            var p = transform4(worldToScreen, center);
            var projectedZ = p[2] * (1f / p[3]);
            for (float x : new float[] {-1, 1}) {
                for (float y : new float[] {-1, 1}) {
                    corners[index++] = transformFrustum(screenToWorld, x * p[3], y * p[3], projectedZ * p[3], p[3]);
                }
            }
        }
        var result = new ArrayList<Plane>();
        if (sun) {
            int[][] triples = {
                {0, 5, 1},
                {4, 7, 5},
                {6, 3, 7},
                {2, 1, 3},
                {0, 6, 4},
                {1, 5, 7}
            };
            for (var t : triples) {
                result.add(planeThrough(corners[t[0]], corners[t[positive ? 1 : 2]], corners[t[positive ? 2 : 1]]));
            }
        } else {
            int[][] pairs = {{0, 1}, {2, 0}, {3, 2}, {1, 3}};
            for (var pair : pairs) {
                result.add(planeThrough(origin, corners[pair[positive ? 0 : 1]], corners[pair[positive ? 1 : 0]]));
            }
            result.add(planeThrough(corners[0], corners[positive ? 2 : 1], corners[positive ? 1 : 2]));
        }
        return result;
    }

    private static Rect fullRect(Placement p) {
        Objects.requireNonNull(p);
        return new Rect(0, 0, p.width - 1, p.height - 1);
    }

    private static int cropLimit(float value, int size) {
        requireFinite(value);
        var truncated = value >= 0x1p31f || value < -0x1p31f ? Integer.MIN_VALUE : (int) value;
        return Math.clamp(truncated, 0, size - 1);
    }

    private static Plane planeThrough(Vector base, Vector a, Vector b) {
        var cross = cross(sub(a, base), sub(b, base));
        var square = (cross.x * cross.x + cross.y * cross.y) + cross.z * cross.z;
        requireFinite(square);
        var n = square < 1.0e-8 ? new Vector(0, 0, 0) : safeNormal(cross);
        return new Plane(n.x, n.y, n.z, (n.y * base.y + base.x * n.x) + n.z * base.z);
    }

    private static Vector transformXy(float[] m, float x, float y) {
        return new Vector(
                (m[0] * x + m[4] * y) + m[8] * 0f + m[12],
                (m[1] * x + m[5] * y) + m[9] * 0f + m[13],
                (m[2] * x + m[6] * y) + m[10] * 0f + m[14]);
    }

    private static Vector transformXyDirection(float[] m, float x, float y) {
        return new Vector(
                (m[0] * x + m[4] * y) + m[8] * 0f + m[12] * 0f,
                (m[1] * x + m[5] * y) + m[9] * 0f + m[13] * 0f,
                (m[2] * x + m[6] * y) + m[10] * 0f + m[14] * 0f);
    }

    private static Vector transform(float[] m, float x, float y, float z, float w) {
        return new Vector(
                (m[4] * y + m[0] * x) + m[8] * z + m[12] * w,
                (m[5] * y + m[1] * x) + m[9] * z + m[13] * w,
                (m[6] * y + m[2] * x) + m[10] * z + m[14] * w);
    }

    private static Vector transformFrustum(float[] m, float x, float y, float z, float w) {
        return new Vector(
                m[8] * z + (m[4] * y + m[0] * x) + m[12] * w,
                m[9] * z + (m[5] * y + m[1] * x) + m[13] * w,
                m[10] * z + (m[6] * y + m[2] * x) + m[14] * w);
    }

    private static float[] transform4(float[] m, Vector p) {
        var out = new float[4];
        for (int k = 0; k < 4; k++) {
            out[k] = (m[k + 4] * p.y + m[k] * p.x) + m[k + 8] * p.z + m[k + 12];
        }
        requireFinite(out);
        return out;
    }

    private static Vector offsetAlongNormal(Vector p, Vector n, float d, float reciprocal) {
        return new Vector(p.x - n.x * d * reciprocal, p.y - n.y * d * reciprocal, p.z - n.z * d * reciprocal);
    }

    private static Vector reciprocalVector(Vector v) {
        var square = (v.x * v.x + v.y * v.y) + v.z * v.z;
        var reciprocal = 1f / square;
        return new Vector(v.x * reciprocal, v.y * reciprocal, v.z * reciprocal);
    }

    private static Vector sub(Vector a, Vector b) {
        return new Vector(a.x - b.x, a.y - b.y, a.z - b.z);
    }

    private static Vector cross(Vector a, Vector b) {
        return new Vector(a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x);
    }

    private static Vector safeNormal(Vector v) {
        var square = (v.x * v.x + v.y * v.y) + v.z * v.z;
        requireFinite(square);
        if (square < 1.0e-8) {
            throw new IllegalArgumentException("degenerate shadow basis");
        }
        var reciprocal = 1f / (float) Math.sqrt(square);
        return new Vector(v.x * reciprocal, v.y * reciprocal, v.z * reciprocal);
    }

    private static float size(Vector v) {
        var square = (v.x * v.x + v.y * v.y) + v.z * v.z;
        requireFinite(square);
        return (float) Math.sqrt(square);
    }

    private static float[] identity() {
        return new float[] {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    }

    private static float[] multiply(float[] a, float[] b) {
        var out = new float[16];
        NativeMatrixMath.multiply(a, b, out);
        requireFinite(out);
        return out;
    }

    private static float determinant(float[] m) {
        var d = BrushTransforms.determinant(m);
        requireFinite(d);
        return d;
    }

    private static float[] inverse(float[] m) {
        if (determinant(m) == 0f) {
            throw new IllegalArgumentException("singular shadow matrix");
        }
        var out = NativeMatrixMath.inverse(m);
        requireFinite(out);
        return out;
    }

    private static float[] copyMatrix(float[] m) {
        Objects.requireNonNull(m);
        if (m.length != 16) {
            throw new IllegalArgumentException("matrix needs 16 coefficients");
        }
        requireFinite(m);
        return m.clone();
    }

    private static void requireFinite(float... values) {
        for (var v : values) {
            if (!Float.isFinite(v)) {
                throw new IllegalArgumentException("non-finite shadow arithmetic");
            }
        }
    }
}
