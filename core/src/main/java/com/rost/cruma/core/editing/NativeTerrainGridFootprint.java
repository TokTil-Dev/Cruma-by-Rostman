package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.TerrainNativeCodec.Box;
import com.rost.cruma.core.editing.TerrainNativeCodec.Vector;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

final class NativeTerrainGridFootprint {
    private NativeTerrainGridFootprint() {}

    record Triangle(int quadIndex, int triangleIndex, int a, int b, int c) {}

    record Footprint(
            int width,
            int height,
            List<Integer> changedVertices,
            List<Triangle> triangles,
            List<Integer> supportVertices,
            Optional<Box> bounds) {
        Footprint {
            changedVertices = List.copyOf(changedVertices);
            triangles = List.copyOf(triangles);
            supportVertices = List.copyOf(supportVertices);
            Objects.requireNonNull(bounds);
        }
    }

    static Footprint capture(
            TerrainHeightField before,
            TerrainHeightField after,
            BitSet changedVertices,
            BitSet edgeTurns,
            BitSet visibleQuads,
            BooleanSupplier cancelled) {
        Objects.requireNonNull(before);
        Objects.requireNonNull(after);
        Objects.requireNonNull(changedVertices);
        Objects.requireNonNull(edgeTurns);
        Objects.requireNonNull(visibleQuads);
        Objects.requireNonNull(cancelled);
        P152FreshHashQuery.checkCancelled(cancelled);
        var width = before.getWidth();
        var height = before.getHeight();
        var count = width * height;
        if (width > 256
                || height > 256
                || after.getWidth() != width
                || after.getHeight() != height
                || changedVertices.length() > count
                || edgeTurns.length() > count
                || visibleQuads.length() > count) {
            throw new IllegalArgumentException("Unqualified Terrain footprint grid or mask");
        }
        var changed = (BitSet) changedVertices.clone();
        var turns = (BitSet) edgeTurns.clone();
        var visible = (BitSet) visibleQuads.clone();
        var support = new BitSet(count);
        var triangles = new ArrayList<Triangle>();
        for (var y = 0; y < height - 1; y++) {
            P152FreshHashQuery.checkCancelled(cancelled);
            for (var x = 0; x < width - 1; x++) {
                var a = y * width + x;
                if (!visible.get(a)) {
                    continue;
                }
                var b = a + 1;
                var c = a + width;
                var d = c + 1;
                if (turns.get(a)) {
                    include(triangles, support, changed, a, 0, a, c, b);
                    include(triangles, support, changed, a, 1, c, d, b);
                } else {
                    include(triangles, support, changed, a, 0, a, c, d);
                    include(triangles, support, changed, a, 1, a, d, b);
                }
            }
        }
        var bounds = Optional.<Box>empty();
        float minX = Float.POSITIVE_INFINITY, minY = minX, minZ = minX;
        float maxX = Float.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
        for (var index = support.nextSetBit(0); index >= 0; index = support.nextSetBit(index + 1)) {
            P152FreshHashQuery.checkCancelled(cancelled);
            var oldPosition = before.getWorldAt(index % width, index / width);
            var newPosition = after.getWorldAt(index % width, index / width);
            requireFinite(oldPosition);
            requireFinite(newPosition);
            minX = Math.min(minX, Math.min(oldPosition.x(), newPosition.x()));
            minY = Math.min(minY, Math.min(oldPosition.y(), newPosition.y()));
            minZ = Math.min(minZ, Math.min(oldPosition.z(), newPosition.z()));
            maxX = Math.max(maxX, Math.max(oldPosition.x(), newPosition.x()));
            maxY = Math.max(maxY, Math.max(oldPosition.y(), newPosition.y()));
            maxZ = Math.max(maxZ, Math.max(oldPosition.z(), newPosition.z()));
        }
        if (!support.isEmpty()) {
            bounds = Optional.of(new Box(new Vector(minX, minY, minZ), new Vector(maxX, maxY, maxZ), 1));
        }
        P152FreshHashQuery.checkCancelled(cancelled);
        return new Footprint(width, height, indices(changed), triangles, indices(support), bounds);
    }

    private static void include(
            List<Triangle> triangles, BitSet support, BitSet changed, int quad, int ordinal, int a, int b, int c) {
        if (changed.get(a) || changed.get(b) || changed.get(c)) {
            triangles.add(new Triangle(quad, ordinal, a, b, c));
            support.set(a);
            support.set(b);
            support.set(c);
        }
    }

    private static List<Integer> indices(BitSet bits) {
        var result = new ArrayList<Integer>(bits.cardinality());
        for (var index = bits.nextSetBit(0); index >= 0; index = bits.nextSetBit(index + 1)) {
            result.add(index);
        }
        return result;
    }

    private static void requireFinite(Vector value) {
        if (!Float.isFinite(value.x()) || !Float.isFinite(value.y()) || !Float.isFinite(value.z())) {
            throw new IllegalArgumentException("Non-finite Terrain footprint position");
        }
    }
}
