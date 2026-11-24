package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.TerrainNativeCodec.Info;
import com.rost.cruma.core.editing.TerrainNativeCodec.SavedGeometry;
import com.rost.cruma.core.editing.TerrainNativeCodec.Vector;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Optional;

public final class NativeTerrainSavedGeometry {
    private NativeTerrainSavedGeometry() {}

    public record Agreement(long vertices, long exactVertices, long faceNormals, long exactFaceNormals) {
        public static final Agreement EMPTY = new Agreement(0, 0, 0, 0);

        public Agreement plus(Agreement other) {
            return new Agreement(
                    vertices + other.vertices,
                    exactVertices + other.exactVertices,
                    faceNormals + other.faceNormals,
                    exactFaceNormals + other.exactFaceNormals);
        }

        public double vertexShare() {
            return vertices == 0 ? 1 : (double) exactVertices / vertices;
        }

        public double faceNormalShare() {
            return faceNormals == 0 ? 1 : (double) exactFaceNormals / faceNormals;
        }
    }

    public static String findRestriction(Info info) {
        if (info.savedGeometry().isEmpty()) {
            return "";
        }
        var geometry = info.savedGeometry().orElseThrow();
        var samples = info.width() * info.height();
        if (geometry.getVertexCount() != 0 && geometry.getVertexCount() != samples
                || geometry.getFaceNormalCount() != 0 && geometry.getFaceNormalCount() != samples) {
            return "This Terrain saves vertex positions or face normals of an unknown count";
        }
        return "";
    }

    public static Optional<SavedGeometry> rebuild(
            Optional<SavedGeometry> saved, TerrainHeightField original, TerrainHeightField edited, BitSet edgeTurns) {
        return rebuild(saved, original, edited, edgeTurns, edgeTurns);
    }

    public static Optional<SavedGeometry> rebuild(
            Optional<SavedGeometry> saved,
            TerrainHeightField original,
            TerrainHeightField edited,
            BitSet oldTurns,
            BitSet edgeTurns) {
        if (saved.isEmpty()) {
            return saved;
        }
        var turned = (BitSet) oldTurns.clone();
        turned.xor(edgeTurns);
        var geometry = saved.orElseThrow();
        var width = edited.getWidth();
        var height = edited.getHeight();
        var samples = width * height;
        var vertices = new HashMap<Integer, Vector>();
        var normals = new HashMap<Integer, float[]>();
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                if (original.getHeightAt(x, y) == edited.getHeightAt(x, y)) {
                    continue;
                }
                if (geometry.getVertexCount() == samples) {
                    vertices.put(y * width + x, edited.getWorldAt(x, y));
                }
                if (geometry.getFaceNormalCount() != samples) {
                    continue;
                }
                for (var qy = Math.max(0, y - 1); qy <= Math.min(height - 2, y); qy++) {
                    for (var qx = Math.max(0, x - 1); qx <= Math.min(width - 2, x); qx++) {
                        normals.computeIfAbsent(
                                qy * width + qx, quad -> edited.getFaceNormals(quad % width, quad / width, edgeTurns));
                    }
                }
            }
        }
        if (geometry.getFaceNormalCount() == samples) {
            for (var quad = turned.nextSetBit(0); quad >= 0 && quad < samples; quad = turned.nextSetBit(quad + 1)) {
                if (quad % width < width - 1 && quad / width < height - 1) {
                    normals.computeIfAbsent(quad, each -> edited.getFaceNormals(each % width, each / width, edgeTurns));
                }
            }
        }
        return Optional.of(geometry.with(vertices, normals));
    }

    public static Agreement measure(
            Optional<SavedGeometry> saved, TerrainHeightField field, BitSet edgeTurns, int margin) {
        if (saved.isEmpty()) {
            return Agreement.EMPTY;
        }
        var geometry = saved.orElseThrow();
        var width = field.getWidth();
        var height = field.getHeight();
        long vertices = 0, exactVertices = 0, normals = 0, exactNormals = 0;
        if (geometry.getVertexCount() == width * height) {
            for (var y = margin; y < height - margin; y++) {
                for (var x = margin; x < width - margin; x++) {
                    vertices++;
                    if (geometry.getVertex(y * width + x).equals(field.getWorldAt(x, y))) {
                        exactVertices++;
                    }
                }
            }
        }
        if (geometry.getFaceNormalCount() == width * height) {
            for (var y = margin; y < height - 1 - margin; y++) {
                for (var x = margin; x < width - 1 - margin; x++) {
                    normals++;
                    if (Arrays.equals(geometry.getFaceNormal(y * width + x), field.getFaceNormals(x, y, edgeTurns))) {
                        exactNormals++;
                    }
                }
            }
        }
        return new Agreement(vertices, exactVertices, normals, exactNormals);
    }
}
