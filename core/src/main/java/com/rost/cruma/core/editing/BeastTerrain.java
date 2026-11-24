package com.rost.cruma.core.editing;

import java.util.BitSet;
import java.util.Objects;

public final class BeastTerrain {
    public static final Mapping SAVED_MAPPING = Mapping.SAMPLES;

    private final TerrainHeightField field;
    private final BitSet visibleQuads;
    private final int width;
    private final int height;
    private final Mapping mapping;
    private final float[] positions;
    private final float[] normals;

    public BeastTerrain(
            TerrainHeightField field,
            BitSet edgeTurns,
            BitSet visibleQuads,
            boolean inverted,
            int width,
            int height,
            Mapping mapping) {
        this.field = Objects.requireNonNull(field);
        this.visibleQuads = visibleQuads;
        this.width = width;
        this.height = height;
        this.mapping = Objects.requireNonNull(mapping);
        var samples = field.getWidth() * field.getHeight();
        positions = new float[samples * 3];
        normals = new float[samples * 3];
        for (var y = 0; y < field.getHeight(); y++) {
            for (var x = 0; x < field.getWidth(); x++) {
                var i = (y * field.getWidth() + x) * 3;
                var p = field.getWorldAt(x, y);
                positions[i] = p.x();
                positions[i + 1] = p.y();
                positions[i + 2] = p.z();
                var n = field.getNormalAt(x, y, edgeTurns, inverted);
                normals[i] = n.x();
                normals[i + 1] = n.y();
                normals[i + 2] = n.z();
            }
        }
    }

    public enum Mapping {
        SAMPLES,
        QUADS,
        CORNERS
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public float toHeightmapCoordinate(int texel, int texels, int samples) {
        return switch (mapping) {
            case SAMPLES -> (texel + .5f) * samples / texels - .5f;
            case QUADS -> (texel + .5f) * (samples - 1) / texels;
            case CORNERS -> (float) texel * samples / texels;
        };
    }

    public boolean computeWorldPoint(int x, int y, float[] position, float[] normal) {
        var u = Math.clamp(toHeightmapCoordinate(x, width, field.getWidth()), 0f, field.getWidth() - 1f);
        var v = Math.clamp(toHeightmapCoordinate(y, height, field.getHeight()), 0f, field.getHeight() - 1f);
        var x0 = Math.min((int) Math.floor(u), field.getWidth() - 2);
        var y0 = Math.min((int) Math.floor(v), field.getHeight() - 2);
        if (x0 < 0 || y0 < 0) {
            return false;
        }
        if (visibleQuads != null && !visibleQuads.get(y0 * field.getWidth() + x0)) {
            return false;
        }
        var fx = u - x0;
        var fy = v - y0;
        var w00 = (1 - fx) * (1 - fy);
        var w10 = fx * (1 - fy);
        var w01 = (1 - fx) * fy;
        var w11 = fx * fy;
        var i00 = (y0 * field.getWidth() + x0) * 3;
        var i10 = i00 + 3;
        var i01 = i00 + field.getWidth() * 3;
        var i11 = i01 + 3;
        for (var axis = 0; axis < 3; axis++) {
            position[axis] = positions[i00 + axis] * w00
                    + positions[i10 + axis] * w10
                    + positions[i01 + axis] * w01
                    + positions[i11 + axis] * w11;
            normal[axis] = normals[i00 + axis] * w00
                    + normals[i10 + axis] * w10
                    + normals[i01 + axis] * w01
                    + normals[i11 + axis] * w11;
        }
        var length = (float) Math.sqrt(normal[0] * normal[0] + normal[1] * normal[1] + normal[2] * normal[2]);
        if (!(length > 1e-6f)) {
            return false;
        }
        normal[0] /= length;
        normal[1] /= length;
        normal[2] /= length;
        return true;
    }

    public boolean[] computeTexelsOf(boolean[] changedSamples) {
        var grown = new boolean[changedSamples.length];
        for (var y = 0; y < field.getHeight(); y++) {
            for (var x = 0; x < field.getWidth(); x++) {
                if (!changedSamples[y * field.getWidth() + x]) {
                    continue;
                }
                for (var dy = -1; dy <= 1; dy++) {
                    for (var dx = -1; dx <= 1; dx++) {
                        var nx = x + dx;
                        var ny = y + dy;
                        if (nx >= 0 && ny >= 0 && nx < field.getWidth() && ny < field.getHeight()) {
                            grown[ny * field.getWidth() + nx] = true;
                        }
                    }
                }
            }
        }
        var result = new boolean[width * height];
        for (var y = 0; y < height; y++) {
            var v = Math.clamp(toHeightmapCoordinate(y, height, field.getHeight()), 0f, field.getHeight() - 1f);
            var y0 = Math.min((int) Math.floor(v), field.getHeight() - 2);
            for (var x = 0; x < width; x++) {
                var u = Math.clamp(toHeightmapCoordinate(x, width, field.getWidth()), 0f, field.getWidth() - 1f);
                var x0 = Math.min((int) Math.floor(u), field.getWidth() - 2);
                var i = y0 * field.getWidth() + x0;
                result[y * width + x] =
                        grown[i] || grown[i + 1] || grown[i + field.getWidth()] || grown[i + field.getWidth() + 1];
            }
        }
        return result;
    }
}
