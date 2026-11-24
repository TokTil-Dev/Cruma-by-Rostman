package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.ShadowColorBlend.State;
import com.rost.cruma.core.editing.ShadowTriangleQuery.Triangle;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;

final class ShadowMaskRasterizer {
    private static final int MAX_TRIANGLES = 1_000_000;
    private static final int DEPTH_MAX = 0xffffff;
    private static final int CLEAR_DEPTH = toDepth(Float.intBitsToFloat(0x3f7fff00));
    private static final long MAX_PIXEL_TESTS = 100_000_000;
    private final float[] vertices;
    private final int[] owners;

    ShadowMaskRasterizer(List<Triangle> triangles, BooleanSupplier cancelled) {
        P152FreshHashQuery.checkCancelled(cancelled);
        if (triangles.size() > MAX_TRIANGLES) {
            throw new IllegalArgumentException("Raster triangle count exceeds editor bounds");
        }
        var frozen = List.copyOf(triangles);
        vertices = new float[frozen.size() * 9];
        owners = new int[frozen.size()];
        int cursor = 0;
        for (var triangle : frozen) {
            P152FreshHashQuery.checkCancelled(cancelled);
            owners[cursor / 9] = triangle.owner();
            for (var vertex : List.of(triangle.a(), triangle.b(), triangle.c())) {
                vertices[cursor++] = vertex.x();
                vertices[cursor++] = vertex.y();
                vertices[cursor++] = vertex.z();
            }
        }
        P152FreshHashQuery.checkCancelled(cancelled);
    }

    enum Cull {
        NONE,
        CLOCKWISE,
        COUNTERCLOCKWISE
    }

    enum DepthFunction {
        LESS_EQUAL,
        ALWAYS;

        static DepthFunction fromNative(int value) {
            return switch (value) {
                case 4 -> LESS_EQUAL;
                case 8 -> ALWAYS;
                default -> throw new IllegalArgumentException("Unsupported shadow depth comparison: " + value);
            };
        }
    }

    record ClipVertex(float x, float y, float z, float w) {
        ClipVertex {
            requireFinite(x, y, z, w);
        }
    }

    record ClipTriangle(ClipVertex a, ClipVertex b, ClipVertex c) {
        ClipTriangle {
            Objects.requireNonNull(a);
            Objects.requireNonNull(b);
            Objects.requireNonNull(c);
        }
    }

    record ScreenVertex(float x, float y, float z) {
        ScreenVertex {
            requireFinite(x, y, z);
            if (Math.abs(x) > 65536 || Math.abs(y) > 65536 || z < 0 || z > 1) {
                throw new IllegalArgumentException("Screen vertex exceeds raster bounds");
            }
        }
    }

    record OpaqueState(
            Cull cull,
            int argb,
            boolean depthEnabled,
            boolean depthWrite,
            int alphaReference,
            DepthFunction depthFunction) {
        OpaqueState(Cull cull, int argb, boolean depthEnabled, boolean depthWrite, int alphaReference) {
            this(cull, argb, depthEnabled, depthWrite, alphaReference, DepthFunction.LESS_EQUAL);
        }

        OpaqueState {
            Objects.requireNonNull(cull);
            Objects.requireNonNull(depthFunction);
            if (alphaReference < -1 || alphaReference > 255) {
                throw new IllegalArgumentException("Invalid alpha reference");
            }
        }
    }

    record ScreenDraw(ScreenVertex a, ScreenVertex b, ScreenVertex c, OpaqueState state, State blend) {
        ScreenDraw(ScreenVertex a, ScreenVertex b, ScreenVertex c, OpaqueState state) {
            this(a, b, c, state, ShadowColorBlend.OPAQUE);
        }

        ScreenDraw {
            Objects.requireNonNull(a);
            Objects.requireNonNull(b);
            Objects.requireNonNull(c);
            Objects.requireNonNull(state);
            Objects.requireNonNull(blend);
        }
    }

    record ClipDraw(ClipTriangle triangle, OpaqueState state, State blend) {
        ClipDraw(ClipTriangle triangle, OpaqueState state) {
            this(triangle, state, ShadowColorBlend.OPAQUE);
        }

        ClipDraw {
            Objects.requireNonNull(triangle);
            Objects.requireNonNull(state);
            Objects.requireNonNull(blend);
        }
    }

    record WorldDraw(Triangle triangle, OpaqueState state, State blend) {
        WorldDraw(Triangle triangle, OpaqueState state) {
            this(triangle, state, ShadowColorBlend.OPAQUE);
        }

        WorldDraw {
            Objects.requireNonNull(triangle);
            Objects.requireNonNull(state);
            Objects.requireNonNull(blend);
        }
    }

    record ColorResult(int width, int height, int[] argb, byte[] visibleBytes) {
        ColorResult {
            argb = argb.clone();
            visibleBytes = visibleBytes.clone();
        }

        @Override
        public int[] argb() {
            return argb.clone();
        }

        @Override
        public byte[] visibleBytes() {
            return visibleBytes.clone();
        }
    }

    static byte[] renderClip(
            int width, int height, List<ClipTriangle> triangles, Cull cull, BooleanSupplier cancelled) {
        P152FreshHashQuery.checkCancelled(cancelled);
        if (triangles.size() > MAX_TRIANGLES) {
            throw new IllegalArgumentException("Raster triangle count exceeds editor bounds");
        }
        var frozen = List.copyOf(triangles);
        var target = new Target(width, height, cull);
        var input = new float[12];
        var scratch = new float[2][48];
        for (var triangle : frozen) {
            P152FreshHashQuery.checkCancelled(cancelled);
            int cursor = 0;
            for (var v : List.of(triangle.a, triangle.b, triangle.c)) {
                input[cursor++] = v.x;
                input[cursor++] = v.y;
                input[cursor++] = v.z;
                input[cursor++] = v.w;
            }
            target.clip(input, scratch, cancelled);
        }
        P152FreshHashQuery.checkCancelled(cancelled);
        return target.visible;
    }

    static byte[] renderScreen(
            int width,
            int height,
            ScreenVertex a,
            ScreenVertex b,
            ScreenVertex c,
            Cull cull,
            BooleanSupplier cancelled) {
        P152FreshHashQuery.checkCancelled(cancelled);
        var target = new Target(width, height, cull);
        target.triangle(a, b, c, cancelled);
        P152FreshHashQuery.checkCancelled(cancelled);
        return target.visible;
    }

    static ColorResult renderScreenDraws(int width, int height, List<ScreenDraw> draws, BooleanSupplier cancelled) {
        P152FreshHashQuery.checkCancelled(cancelled);
        if (draws.size() > MAX_TRIANGLES) {
            throw new IllegalArgumentException("Raster draw count exceeds editor bounds");
        }
        var frozen = List.copyOf(draws);
        var target = new Target(width, height, Cull.NONE, true);
        for (var draw : frozen) {
            P152FreshHashQuery.checkCancelled(cancelled);
            target.state = draw.state;
            target.blend = draw.blend;
            target.triangle(draw.a, draw.b, draw.c, cancelled);
        }
        var visible = ShadowMaskReadback.readPointMask(
                width, height, 0, 0, width, target.colors, new byte[target.visible.length], cancelled);
        return new ColorResult(width, height, target.colors, visible);
    }

    static ColorResult renderClipDraws(int width, int height, List<ClipDraw> draws, BooleanSupplier cancelled) {
        if (draws.size() > MAX_TRIANGLES) {
            throw new IllegalArgumentException("Raster draw count exceeds editor bounds");
        }
        var frozen = List.copyOf(draws);
        P152FreshHashQuery.checkCancelled(cancelled);
        var target = new Target(width, height, Cull.NONE, true);
        var input = new float[12];
        var scratch = new float[2][48];
        for (var draw : frozen) {
            P152FreshHashQuery.checkCancelled(cancelled);
            int cursor = 0;
            for (var vertex : List.of(draw.triangle.a, draw.triangle.b, draw.triangle.c)) {
                input[cursor++] = vertex.x;
                input[cursor++] = vertex.y;
                input[cursor++] = vertex.z;
                input[cursor++] = vertex.w;
            }
            target.state = draw.state;
            target.blend = draw.blend;
            target.clip(input, scratch, cancelled);
        }
        return collectColors(target, cancelled);
    }

    static ColorResult renderWorldDraws(
            int width,
            int height,
            List<WorldDraw> draws,
            float[] worldToClip,
            Set<Integer> excludedOwners,
            BooleanSupplier cancelled) {
        if (draws.size() > MAX_TRIANGLES) {
            throw new IllegalArgumentException("Raster draw count exceeds editor bounds");
        }
        var frozen = List.copyOf(draws);
        var matrix = worldToClip.clone();
        var excluded = Set.copyOf(excludedOwners);
        if (matrix.length != 16) {
            throw new IllegalArgumentException("Raster transform requires sixteen words");
        }
        requireFinite(matrix);
        P152FreshHashQuery.checkCancelled(cancelled);
        var target = new Target(width, height, Cull.NONE, true);
        var input = new float[12];
        var scratch = new float[2][48];
        for (var draw : frozen) {
            P152FreshHashQuery.checkCancelled(cancelled);
            var triangle = draw.triangle;
            if (excluded.contains(triangle.owner())) {
                continue;
            }
            int cursor = 0;
            for (var vertex : List.of(triangle.a(), triangle.b(), triangle.c())) {
                for (int axis = 0; axis < 4; axis++) {
                    input[cursor++] = ((vertex.x() * matrix[axis] + vertex.y() * matrix[axis + 4])
                                    + vertex.z() * matrix[axis + 8])
                            + matrix[axis + 12];
                }
            }
            requireFinite(input);
            target.state = draw.state;
            target.blend = draw.blend;
            target.clip(input, scratch, cancelled);
        }
        return collectColors(target, cancelled);
    }

    byte[] render(int width, int height, float[] worldToClip, Cull cull, BooleanSupplier cancelled) {
        return render(width, height, worldToClip, cull, Set.of(), cancelled);
    }

    byte[] render(
            int width,
            int height,
            float[] worldToClip,
            Cull cull,
            Set<Integer> excludedOwners,
            BooleanSupplier cancelled) {
        var target = new Target(width, height, cull);
        var matrix = worldToClip.clone();
        var excluded = Set.copyOf(excludedOwners);
        if (matrix.length != 16) {
            throw new IllegalArgumentException("Raster transform requires sixteen words");
        }
        requireFinite(matrix);
        var input = new float[12];
        var scratch = new float[2][48];
        int previousOwner = 0;
        boolean skip = false;
        for (int i = 0; i < vertices.length; i += 9) {
            P152FreshHashQuery.checkCancelled(cancelled);
            if (!excluded.isEmpty()) {
                int owner = owners[i / 9];
                if (i == 0 || owner != previousOwner) {
                    previousOwner = owner;
                    skip = excluded.contains(owner);
                }
                if (skip) {
                    continue;
                }
            }
            for (int v = 0; v < 3; v++) {
                float x = vertices[i + v * 3], y = vertices[i + v * 3 + 1], z = vertices[i + v * 3 + 2];
                for (int axis = 0; axis < 4; axis++) {
                    input[v * 4 + axis] =
                            ((x * matrix[axis] + y * matrix[axis + 4]) + z * matrix[axis + 8]) + matrix[axis + 12];
                }
            }
            requireFinite(input);
            target.clip(input, scratch, cancelled);
        }
        P152FreshHashQuery.checkCancelled(cancelled);
        return target.visible;
    }

    private static ColorResult collectColors(Target target, BooleanSupplier cancelled) {
        var visible = ShadowMaskReadback.readPointMask(
                target.width,
                target.height,
                0,
                0,
                target.width,
                target.colors,
                new byte[target.visible.length],
                cancelled);
        P152FreshHashQuery.checkCancelled(cancelled);
        return new ColorResult(target.width, target.height, target.colors, visible);
    }

    private static final class Target {
        private final int width, height, stride;
        private final Cull cull;
        private final byte[] visible;
        private final int[] colors, depths;
        private OpaqueState state;
        private State blend;
        private long testedPixels;

        Target(int width, int height, Cull cull) {
            this(width, height, cull, false);
        }

        Target(int width, int height, Cull cull, boolean colored) {
            if (width < 1 || height < 1 || width > 65536 || height > 65536 || (long) width * height > 65536) {
                throw new IllegalArgumentException("Raster requires one bounded nonempty image");
            }
            this.width = width;
            this.height = height;
            this.cull = Objects.requireNonNull(cull);
            colors = colored ? new int[width * height] : null;
            depths = colored ? new int[width * height] : null;
            if (colored) {
                Arrays.fill(colors, -1);
                Arrays.fill(depths, CLEAR_DEPTH);
            }
            stride = (width + 7) / 8;
            visible = new byte[stride * height];
            Arrays.fill(visible, (byte) 255);
            if ((width & 7) != 0) {
                for (int y = 0; y < height; y++) {
                    visible[y * stride + stride - 1] = (byte) ((1 << (width & 7)) - 1);
                }
            }
        }

        void clip(float[] input, float[][] scratch, BooleanSupplier cancelled) {
            int allOutside = 63, anyOutside = 0;
            boolean lateralClip = false;
            for (int v = 0; v < 3; v++) {
                float w = input[v * 4 + 3];
                lateralClip |= w <= 0
                        || Math.abs((input[v * 4] / w + 1f) * (width * .5f)) > 65536
                        || Math.abs((1f - input[v * 4 + 1] / w) * (height * .5f)) > 65536;
                int code = 0;
                for (int plane = 0; plane < 6; plane++) {
                    if (clipDistance(input, v * 4, plane) < 0) {
                        code |= 1 << plane;
                    }
                }
                allOutside &= code;
                anyOutside |= code;
            }
            if (allOutside != 0) {
                return;
            }
            System.arraycopy(input, 0, scratch[0], 0, 12);
            int count = 3, current = 0;
            if (anyOutside != 0) {
                for (int plane = 0; plane < 6 && count != 0; plane++) {
                    if (plane < 4 && !lateralClip) {
                        continue;
                    }
                    var src = scratch[current];
                    var dst = scratch[1 - current];
                    int output = 0;
                    int previous = (count - 1) * 4;
                    float previousDistance = clipDistance(src, previous, plane);
                    for (int v = 0; v < count; v++) {
                        int offset = v * 4;
                        float nextDistance = clipDistance(src, offset, plane);
                        if ((previousDistance < 0) != (nextDistance < 0)) {
                            float t = previousDistance / (previousDistance - nextDistance);
                            for (int axis = 0; axis < 4; axis++) {
                                dst[output * 4 + axis] =
                                        src[previous + axis] + (src[offset + axis] - src[previous + axis]) * t;
                            }
                            int axis = plane / 2;
                            dst[output * 4 + axis] =
                                    plane == 4 ? 0 : (plane & 1) == 0 ? -dst[output * 4 + 3] : dst[output * 4 + 3];
                            output++;
                        }
                        if (nextDistance >= 0) {
                            System.arraycopy(src, offset, dst, output++ * 4, 4);
                        }
                        previous = offset;
                        previousDistance = nextDistance;
                    }
                    count = output;
                    current = 1 - current;
                }
            }
            if (count < 3) {
                return;
            }
            var points = new ScreenVertex[count];
            int valid = 0;
            for (int i = 0; i < count; i++) {
                var src = scratch[current];
                float w = src[i * 4 + 3];
                if (w == 0) {
                    continue;
                }
                float x = (src[i * 4] / w + 1f) * (width * .5f);
                float y = (1f - src[i * 4 + 1] / w) * (height * .5f);
                float z = src[i * 4 + 2] / w;
                points[valid++] = new ScreenVertex(x, y, z);
            }
            for (int i = 2; i < valid; i++) {
                triangle(points[0], points[i - 1], points[i], cancelled);
            }
        }

        void triangle(ScreenVertex a, ScreenVertex b, ScreenVertex c, BooleanSupplier cancelled) {
            long ax = snap(a.x), ay = snap(a.y), bx = snap(b.x), by = snap(b.y), cx = snap(c.x), cy = snap(c.y);
            long area = edgeFunction(ax, ay, bx, by, cx, cy);
            var activeCull = colors == null ? cull : state.cull;
            if (area == 0
                    || activeCull == Cull.CLOCKWISE && area > 0
                    || activeCull == Cull.COUNTERCLOCKWISE && area < 0) {
                return;
            }
            if (area < 0) {
                long tmp = bx;
                bx = cx;
                cx = tmp;
                tmp = by;
                by = cy;
                cy = tmp;
                var vertex = b;
                b = c;
                c = vertex;
                area = -area;
            }
            int minX = (int) Math.max(0, Math.ceil(Math.min(ax, Math.min(bx, cx)) / 256d));
            int minY = (int) Math.max(0, Math.ceil(Math.min(ay, Math.min(by, cy)) / 256d));
            int maxX = (int) Math.min(width - 1, Math.floor(Math.max(ax, Math.max(bx, cx)) / 256d));
            int maxY = (int) Math.min(height - 1, Math.floor(Math.max(ay, Math.max(by, cy)) / 256d));
            if (minX > maxX || minY > maxY) {
                return;
            }
            testedPixels += (long) (maxX - minX + 1) * (maxY - minY + 1);
            if (testedPixels > MAX_PIXEL_TESTS) {
                throw new IllegalArgumentException("Raster sample budget exhausted");
            }
            boolean ab = isTopLeft(ax, ay, bx, by), bc = isTopLeft(bx, by, cx, cy), ca = isTopLeft(cx, cy, ax, ay);
            for (int y = minY; y <= maxY; y++) {
                P152FreshHashQuery.checkCancelled(cancelled);
                for (int x = minX; x <= maxX; x++) {
                    int index = y * stride + x / 8, bit = 1 << (x & 7);
                    if (colors == null && (visible[index] & bit) == 0) {
                        continue;
                    }
                    long u = edgeFunction(bx, by, cx, cy, x * 256L, y * 256L);
                    long v = edgeFunction(cx, cy, ax, ay, x * 256L, y * 256L);
                    long w = edgeFunction(ax, ay, bx, by, x * 256L, y * 256L);
                    if ((u > 0 || u == 0 && bc) && (v > 0 || v == 0 && ca) && (w > 0 || w == 0 && ab)) {
                        float z = (float) (((double) u * a.z + (double) v * b.z + (double) w * c.z) / area);
                        if (colors != null) {
                            writeColorSample(y * width + x, toDepth(z));
                        } else if (toDepth(z) <= CLEAR_DEPTH) {
                            visible[index] &= (byte) ~bit;
                        }
                    }
                }
            }
        }

        private void writeColorSample(int pixel, int z) {
            if ((state.argb >>> 24) <= state.alphaReference
                    || state.depthEnabled && state.depthFunction == DepthFunction.LESS_EQUAL && z > depths[pixel]) {
                return;
            }
            colors[pixel] = blend == ShadowColorBlend.OPAQUE
                    ? state.argb
                    : ShadowColorBlend.apply(state.argb, colors[pixel], blend);
            if (state.depthEnabled && state.depthWrite) {
                depths[pixel] = z;
            }
        }
    }

    private static float clipDistance(float[] p, int offset, int plane) {
        return switch (plane) {
            case 0 -> p[offset + 3] + p[offset];
            case 1 -> p[offset + 3] - p[offset];
            case 2 -> p[offset + 3] + p[offset + 1];
            case 3 -> p[offset + 3] - p[offset + 1];
            case 4 -> p[offset + 2];
            case 5 -> p[offset + 3] - p[offset + 2];
            default -> throw new IllegalArgumentException("Unknown raster clip plane");
        };
    }

    private static long snap(float coordinate) {
        return (long) Math.rint((double) coordinate * 256);
    }

    private static long edgeFunction(long ax, long ay, long bx, long by, long x, long y) {
        return (bx - ax) * (y - ay) - (by - ay) * (x - ax);
    }

    private static boolean isTopLeft(long ax, long ay, long bx, long by) {
        return by < ay || by == ay && bx > ax;
    }

    private static int toDepth(float z) {
        return (int) Math.round((double) z * DEPTH_MAX);
    }

    private static void requireFinite(float... values) {
        for (float value : values) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("Non-finite raster input");
            }
        }
    }
}
