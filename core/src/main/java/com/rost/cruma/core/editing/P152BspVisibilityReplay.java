package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.NativeLevelShadowQuery.State;
import com.rost.cruma.core.editing.P152BspReceiverModel.Receiver;
import com.rost.cruma.core.editing.P152BspReceiverModel.SavedMask;
import com.rost.cruma.core.editing.P152FreshHashQuery.Ray;
import com.rost.cruma.core.editing.ShadowMaskRasterizer.Cull;
import com.rost.cruma.core.editing.TerrainNativeCodec.Vector;
import com.rost.cruma.core.world.NativeBspShadowProjection;
import com.rost.cruma.core.world.NativeBspShadowProjection.Rect;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;

final class P152BspVisibilityReplay {
    private static final int MAX_PIXELS = 65536;

    private P152BspVisibilityReplay() {}

    record Sampling(float offsetU, float offsetV, float endpointRetreat) {
        Sampling {
            if (!Float.isFinite(offsetU)
                    || !Float.isFinite(offsetV)
                    || Math.abs(offsetU) > 1
                    || Math.abs(offsetV) > 1
                    || !Float.isFinite(endpointRetreat)
                    || endpointRetreat < 0
                    || endpointRetreat > 16) {
                throw new IllegalArgumentException("Invalid bounded BSP sampling hypothesis");
            }
        }
    }

    record Grid(int minX, int minY, int width, int height, Vector origin, Vector axisU, Vector axisV) {
        Grid {
            if (minX < 0
                    || minY < 0
                    || width < 1
                    || height < 1
                    || (long) minX + width > 65536
                    || (long) minY + height > 65536
                    || (long) width * height > MAX_PIXELS) {
                throw new IllegalArgumentException("BSP replay requires a bounded nonempty sample grid");
            }
            requireFinite(origin);
            requireFinite(axisU);
            requireFinite(axisV);
            if (isZero(axisU) || isZero(axisV)) {
                throw new IllegalArgumentException("BSP replay requires nonzero sample axes");
            }
        }

        static Grid capture(Receiver receiver, SavedMask mask) throws IOException {
            var restriction = mask.metadataRestriction(
                    receiver.projection().getWidth(), receiver.projection().getHeight());
            if (restriction.isPresent()) {
                throw new IOException(restriction.get());
            }
            return new Grid(
                    mask.minX(),
                    mask.minY(),
                    mask.width(),
                    mask.height(),
                    toVector(receiver.savedOrigin()),
                    toVector(receiver.savedAxisU()),
                    toVector(receiver.savedAxisV()));
        }

        int stride() {
            return (width + 7) / 8;
        }
    }

    @FunctionalInterface
    interface Query {
        List<State> evaluate(List<Ray> rays) throws IOException;
    }

    static final class Candidate {
        private final Grid grid;
        private final byte[] visible, known;

        private Candidate(Grid grid, byte[] visible, byte[] known) {
            this.grid = grid;
            this.visible = visible;
            this.known = known;
        }

        byte[] getVisibleBytes() {
            return visible.clone();
        }

        byte[] getKnownBytes() {
            return known.clone();
        }

        Comparison compare(byte[] expected, BooleanSupplier cancelled) {
            Objects.requireNonNull(expected);
            if (expected.length != visible.length) {
                throw new IllegalArgumentException("Saved BSP mask byte count differs from the candidate");
            }
            expected = expected.clone();
            int compared = 0, differing = 0, differingBytes = 0, paddingDifferences = 0;
            int expectedVisible = 0, candidateVisible = 0;
            var firstDifferences = new ArrayList<PixelDifference>();
            var stride = grid.stride();
            for (int i = 0; i < visible.length; i++) {
                P152FreshHashQuery.checkCancelled(cancelled);
                var remainder = grid.width & 7;
                var valid = i % stride == stride - 1 && remainder != 0 ? (1 << remainder) - 1 : 255;
                var observed = known[i] & valid;
                var difference = (expected[i] ^ visible[i]) & 255;
                var changed = difference & observed;
                compared += Integer.bitCount(observed);
                differing += Integer.bitCount(changed);
                expectedVisible += Integer.bitCount(expected[i] & valid);
                candidateVisible += Integer.bitCount(visible[i] & observed);
                paddingDifferences += Integer.bitCount(difference & ~valid & 255);
                if ((difference & (observed | (~valid & 255))) != 0) {
                    differingBytes++;
                }
                for (int bit = 0; bit < 8 && firstDifferences.size() < 16; bit++) {
                    if ((changed & (1 << bit)) != 0) {
                        firstDifferences.add(new PixelDifference(
                                grid.minX + i % stride * 8 + bit,
                                grid.minY + i / stride,
                                (expected[i] & (1 << bit)) != 0,
                                (visible[i] & (1 << bit)) != 0));
                    }
                }
            }
            P152FreshHashQuery.checkCancelled(cancelled);
            return new Comparison(
                    grid.width * grid.height,
                    compared,
                    differing,
                    differingBytes,
                    paddingDifferences,
                    expectedVisible,
                    candidateVisible,
                    firstDifferences);
        }
    }

    record PixelDifference(int x, int y, boolean expectedVisible, boolean candidateVisible) {}

    record Comparison(
            int pixels,
            int comparedPixels,
            int differingPixels,
            int differingBytes,
            int paddingBitDifferences,
            int expectedVisible,
            int candidateVisible,
            List<PixelDifference> firstDifferences) {
        Comparison {
            firstDifferences = List.copyOf(firstDifferences);
        }

        int unknownPixels() {
            return pixels - comparedPixels;
        }

        boolean exact() {
            return unknownPixels() == 0 && differingBytes == 0;
        }
    }

    static Candidate generate(Grid grid, Vector light, Sampling sampling, Query query, BooleanSupplier cancelled)
            throws IOException {
        Objects.requireNonNull(grid);
        Objects.requireNonNull(sampling);
        Objects.requireNonNull(query);
        Objects.requireNonNull(cancelled);
        requireFinite(light);
        P152FreshHashQuery.checkCancelled(cancelled);
        var rays = new ArrayList<Ray>(grid.width * grid.height);
        for (int y = 0; y < grid.height; y++) {
            for (int x = 0; x < grid.width; x++) {
                P152FreshHashQuery.checkCancelled(cancelled);
                var u = grid.minX + x + sampling.offsetU;
                var v = grid.minY + y + sampling.offsetV;
                var target = new Vector(
                        (grid.origin.x() + grid.axisU.x() * u) + grid.axisV.x() * v,
                        (grid.origin.y() + grid.axisU.y() * u) + grid.axisV.y() * v,
                        (grid.origin.z() + grid.axisU.z() * u) + grid.axisV.z() * v);
                var dx = light.x() - target.x();
                var dy = light.y() - target.y();
                var dz = light.z() - target.z();
                var length = Math.sqrt((double) dx * dx + (double) dy * dy + (double) dz * dz);
                if (!Double.isFinite(length) || length == 0) {
                    throw new IllegalArgumentException("BSP sample and light need a finite nonzero segment");
                }
                var fraction = (float) Math.min(0.5, sampling.endpointRetreat / length);
                var end =
                        new Vector(target.x() + dx * fraction, target.y() + dy * fraction, target.z() + dz * fraction);
                var ray = new Ray(light, end);
                P152FreshHashQuery.validate(ray);
                rays.add(ray);
            }
        }
        return evaluate(grid, rays, query, cancelled);
    }

    static Candidate generateProjected(
            Receiver receiver, SavedMask mask, Vector light, float pixelOffset, Query query, BooleanSupplier cancelled)
            throws IOException {
        if (!Float.isFinite(pixelOffset) || pixelOffset < 0 || pixelOffset > 1) {
            throw new IllegalArgumentException("Invalid BSP projected pixel offset");
        }
        Objects.requireNonNull(query);
        Objects.requireNonNull(cancelled);
        requireFinite(light);
        P152FreshHashQuery.checkCancelled(cancelled);
        var grid = Grid.capture(receiver, mask);
        var projection = NativeBspShadowProjection.point(
                receiver.projection(),
                new NativeBspShadowProjection.Vector(light.x(), light.y(), light.z()),
                new Rect(mask.minX(), mask.minY(), mask.maxX(), mask.maxY()));
        var inverse = projection.getScreenToWorld();
        var rays = new ArrayList<Ray>(grid.width * grid.height);
        var clearDepth = Float.intBitsToFloat(0x3f7fff00);
        for (int y = 0; y < grid.height; y++) {
            for (int x = 0; x < grid.width; x++) {
                P152FreshHashQuery.checkCancelled(cancelled);
                var clipX = 2f * (x + pixelOffset) / grid.width - 1f;
                var clipY = 1f - 2f * (y + pixelOffset) / grid.height;
                var ray = new Ray(unproject(inverse, clipX, clipY, 0), unproject(inverse, clipX, clipY, clearDepth));
                P152FreshHashQuery.validate(ray);
                rays.add(ray);
            }
        }
        return evaluate(grid, rays, query, cancelled);
    }

    static Candidate generateRasterized(
            Receiver receiver,
            SavedMask mask,
            Vector light,
            ShadowMaskRasterizer rasterizer,
            Cull cull,
            BooleanSupplier cancelled)
            throws IOException {
        return rasterizeCandidate(receiver, mask, light, rasterizer, cull, null, cancelled);
    }

    static Candidate generateRasterizedWithMeshBounds(
            Receiver receiver,
            SavedMask mask,
            Vector light,
            ShadowMaskRasterizer rasterizer,
            Cull cull,
            P152ShadowMeshGeometry meshes,
            BooleanSupplier cancelled)
            throws IOException {
        return rasterizeCandidate(receiver, mask, light, rasterizer, cull, Objects.requireNonNull(meshes), cancelled);
    }

    private static Candidate rasterizeCandidate(
            Receiver receiver,
            SavedMask mask,
            Vector light,
            ShadowMaskRasterizer rasterizer,
            Cull cull,
            P152ShadowMeshGeometry meshes,
            BooleanSupplier cancelled)
            throws IOException {
        P152FreshHashQuery.checkCancelled(cancelled);
        var grid = Grid.capture(receiver, mask);
        var projection = NativeBspShadowProjection.point(
                receiver.projection(),
                new NativeBspShadowProjection.Vector(light.x(), light.y(), light.z()),
                new Rect(mask.minX(), mask.minY(), mask.maxX(), mask.maxY()));
        var excluded = meshes == null ? Set.<Integer>of() : meshes.collectExcludedBy(projection.getVolume(), cancelled);
        var visible =
                rasterizer.render(grid.width, grid.height, projection.getWorldToScreen(), cull, excluded, cancelled);
        var known = new byte[visible.length];
        var stride = grid.stride();
        for (int i = 0; i < known.length; i++) {
            P152FreshHashQuery.checkCancelled(cancelled);
            known[i] = (byte) (i % stride == stride - 1 && (grid.width & 7) != 0 ? (1 << (grid.width & 7)) - 1 : 255);
        }
        return new Candidate(grid, visible, known);
    }

    private static Vector unproject(float[] matrix, float x, float y, float z) {
        var w = ((x * matrix[3] + y * matrix[7]) + z * matrix[11]) + matrix[15];
        var point = new Vector(
                (((x * matrix[0] + y * matrix[4]) + z * matrix[8]) + matrix[12]) / w,
                (((x * matrix[1] + y * matrix[5]) + z * matrix[9]) + matrix[13]) / w,
                (((x * matrix[2] + y * matrix[6]) + z * matrix[10]) + matrix[14]) / w);
        requireFinite(point);
        return point;
    }

    private static Candidate evaluate(Grid grid, List<Ray> rays, Query query, BooleanSupplier cancelled)
            throws IOException {
        P152FreshHashQuery.checkCancelled(cancelled);
        var states = List.copyOf(query.evaluate(List.copyOf(rays)));
        if (states.size() != rays.size()) {
            throw new IOException("BSP query returned an incomplete sample batch");
        }
        var stride = grid.stride();
        var visible = new byte[stride * grid.height];
        var known = new byte[visible.length];
        for (int i = 0; i < states.size(); i++) {
            P152FreshHashQuery.checkCancelled(cancelled);
            var state = states.get(i);
            if (state != State.UNQUALIFIED) {
                var index = i / grid.width * stride + (i % grid.width) / 8;
                var bit = 1 << ((i % grid.width) & 7);
                known[index] |= (byte) bit;
                if (state == State.CLEAR) {
                    visible[index] |= (byte) bit;
                }
            }
        }
        P152FreshHashQuery.checkCancelled(cancelled);
        return new Candidate(grid, visible, known);
    }

    private static Vector toVector(com.rost.cruma.core.world.NativeBspShadowProjection.Vector value) {
        return new Vector(value.x(), value.y(), value.z());
    }

    private static boolean isZero(Vector vector) {
        return vector.x() == 0 && vector.y() == 0 && vector.z() == 0;
    }

    private static void requireFinite(Vector vector) {
        if (vector == null
                || !Float.isFinite(vector.x())
                || !Float.isFinite(vector.y())
                || !Float.isFinite(vector.z())) {
            throw new IllegalArgumentException("Non-finite BSP replay position");
        }
    }
}
