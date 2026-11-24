package com.rost.cruma.core.export;

import com.rost.cruma.core.export.LocationModel.LayerFile;
import com.rost.cruma.core.export.LocationModel.TerrainLayer;
import com.rost.cruma.core.texture.TexturePixels;
import com.rost.cruma.core.world.Entity.MultiTexture;
import com.rost.cruma.core.world.Entity.TerrainMesh;
import com.rost.cruma.core.world.Entity.TerrainVertexBuffer;
import com.rost.cruma.core.world.Entity.Texture;
import com.rost.cruma.core.world.ZoneStateChoice;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

final class TerrainLayers {
    static final String FOLDER = "terrain-layers";

    private static final double NOISE = 1e-5;

    private final List<LayerFile> files = new ArrayList<>();
    private final Map<Object, String> paths = new IdentityHashMap<>();
    private final Set<String> taken = new HashSet<>();

    static float[] fitRow(TerrainVertexBuffer vertices, float[] positions, int coordinate) {
        var count = Math.min(vertices.size(), positions.length / 3);
        if (count == 0) {
            return new float[4];
        }
        var mean = new double[4];
        for (var vertex = 0; vertex < count; vertex++) {
            for (var axis = 0; axis < 3; axis++) {
                mean[axis] += positions[vertex * 3 + axis];
            }
            mean[3] += vertices.getTerrainTextureCoordinate(vertex, coordinate);
        }
        for (var axis = 0; axis < 4; axis++) {
            mean[axis] /= count;
        }
        var covariance = new double[3][3];
        var target = new double[3];
        var point = new double[3];
        for (var vertex = 0; vertex < count; vertex++) {
            for (var axis = 0; axis < 3; axis++) {
                point[axis] = positions[vertex * 3 + axis] - mean[axis];
            }
            var value = vertices.getTerrainTextureCoordinate(vertex, coordinate) - mean[3];
            for (var row = 0; row < 3; row++) {
                target[row] += point[row] * value;
                for (var column = 0; column < 3; column++) {
                    covariance[row][column] += point[row] * point[column];
                }
            }
        }
        var ridge = 1e-10 * (covariance[0][0] + covariance[1][1] + covariance[2][2]) + 1e-30;
        for (var axis = 0; axis < 3; axis++) {
            covariance[axis][axis] += ridge;
        }
        var slope = solve(covariance, target);
        for (var axis = 0; axis < 3; axis++) {
            if (Math.abs(slope[axis]) * Math.sqrt(covariance[axis][axis] / count) * 4 < NOISE) {
                slope[axis] = 0;
            }
        }
        var offset = mean[3] - slope[0] * mean[0] - slope[1] * mean[1] - slope[2] * mean[2];
        return new float[] {(float) slope[0], (float) slope[1], (float) slope[2], (float) offset};
    }

    List<LayerFile> getFiles() {
        return files;
    }

    List<TerrainLayer> getLayers(
            String tile, TerrainMesh mesh, MultiTexture material, float[] positions, ZoneStateChoice zoneState) {
        var vertices = mesh.vertexBuffer();
        var count = Math.min(
                Math.min(TerrainBake.MAX_LAYERS, material.entries().size()), vertices.getTextureCoordinateCount() / 2);
        var zones = material.getZoneLayers();
        var hidden = zones.findHiddenLayers(zoneState);
        var first = zones.findBaseLayer(hidden);
        var base = first < count ? first : 0;
        var result = new ArrayList<TerrainLayer>(count);
        for (var index = base; index < count; index++) {
            var entry = material.entries().get(index);
            var texture = entry.texture();
            if (!isReadable(texture) || index != base && (hidden >>> index & 1) != 0) {
                continue;
            }
            if (index > base && !isReadable(entry.alphaMap())) {
                continue;
            }
            var name = entry.name().isBlank() ? tile + " layer " + index : entry.name();
            var textureFile = textureFile(name, texture);
            var alphaMap = index > base ? alphaFile(tile, index, entry.alphaMap()) : "";
            result.add(new TerrainLayer(
                    index,
                    name,
                    textureFile,
                    alphaMap,
                    texture.alphaTexture(),
                    fitRow(vertices, positions, index * 2),
                    fitRow(vertices, positions, index * 2 + 1)));
        }
        return List.copyOf(result);
    }

    private String textureFile(String name, Texture texture) {
        var key = texture.data().getFirst();
        var known = paths.get(key);
        if (known != null) {
            return known;
        }
        var width = texture.width();
        var height = texture.height();
        var path = FOLDER + "/" + makeUniqueFileName(LocationExport.toSafeName(name));
        files.add(new LayerFile(
                path,
                width,
                height,
                false,
                () -> LocationMaterials.decode(texture, width, height).rgba()));
        paths.put(key, path);
        return path;
    }

    private String alphaFile(String tile, int index, Texture alphaMap) {
        var key = alphaMap.data().getFirst();
        var known = paths.get(key);
        if (known != null) {
            return known;
        }
        var width = alphaMap.width();
        var height = alphaMap.height();
        var path = FOLDER + "/" + makeUniqueFileName(LocationExport.toSafeName(tile + " layer " + index + " alpha"));
        files.add(new LayerFile(path, width, height, true, () -> decodeAlphaMap(alphaMap)));
        paths.put(key, path);
        return path;
    }

    private String makeUniqueFileName(String base) {
        var candidate = base + ".png";
        for (var suffix = 2; !taken.add(candidate.toLowerCase(Locale.ROOT)); suffix++) {
            candidate = base + "_" + suffix + ".png";
        }
        return candidate;
    }

    private static double[] solve(double[][] m, double[] b) {
        var det = determinant(m[0][0], m[0][1], m[0][2], m[1][0], m[1][1], m[1][2], m[2][0], m[2][1], m[2][2]);
        if (det == 0 || !Double.isFinite(det)) {
            return new double[3];
        }
        var x = determinant(b[0], m[0][1], m[0][2], b[1], m[1][1], m[1][2], b[2], m[2][1], m[2][2]);
        var y = determinant(m[0][0], b[0], m[0][2], m[1][0], b[1], m[1][2], m[2][0], b[2], m[2][2]);
        var z = determinant(m[0][0], m[0][1], b[0], m[1][0], m[1][1], b[1], m[2][0], m[2][1], b[2]);
        return new double[] {x / det, y / det, z / det};
    }

    private static double determinant(
            double a, double b, double c, double d, double e, double f, double g, double h, double i) {
        return a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g);
    }

    private static boolean isReadable(@Nullable Texture texture) {
        return texture != null && !texture.data().isEmpty() && texture.width() > 0 && texture.height() > 0;
    }

    private static byte[] decodeAlphaMap(Texture alphaMap) {
        try {
            var red = TexturePixels.decodeBaseRed(alphaMap);
            if (red.length == alphaMap.width() * alphaMap.height()) {
                return red;
            }
        } catch (RuntimeException unreadable) {
        }
        return new byte[alphaMap.width() * alphaMap.height()];
    }
}
