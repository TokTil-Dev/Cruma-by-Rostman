package com.rost.cruma.viewer.render.bgfx;

import com.rost.cruma.core.world.Entity.TerrainVertexBuffer;
import java.util.Arrays;

final class TerrainLayerProjection {
    static final int LAYER_FLOATS = 8;

    private static final int MAX_FIT_VERTICES = 8192;

    private final float[] origin;
    private final float[] rows;
    private final int layers;

    private TerrainLayerProjection(float[] origin, float[] rows, int layers) {
        this.origin = origin;
        this.rows = rows;
        this.layers = layers;
    }

    static TerrainLayerProjection of(TerrainVertexBuffer vertices, int firstLayer, int count) {
        if (vertices == null || firstLayer < 0 || count < 0) {
            throw new IllegalArgumentException("terrain layer projection requires vertices and a layer range");
        }
        var known = vertices.getLayerRows();
        if (known == null) {
            return fit(vertices, firstLayer, count);
        }
        var rows = new float[count * LAYER_FLOATS];
        var carried = Math.min(count, known.length / LAYER_FLOATS - firstLayer);
        if (carried > 0) {
            System.arraycopy(known, firstLayer * LAYER_FLOATS, rows, 0, carried * LAYER_FLOATS);
        }
        return new TerrainLayerProjection(vertices.getLayerOrigin(), rows, count);
    }

    static TerrainLayerProjection fit(TerrainVertexBuffer vertices, int firstLayer, int count) {
        if (vertices == null || firstLayer < 0 || count < 0) {
            throw new IllegalArgumentException("terrain layer projection requires vertices and a layer range");
        }
        var origin = new double[3];
        var size = vertices.size();
        if (size > 0) {
            origin[0] = vertices.getPositionX(0);
            origin[1] = vertices.getPositionY(0);
            origin[2] = vertices.getPositionZ(0);
        }
        var step = Math.max(1, size / MAX_FIT_VERTICES);
        var rows = new float[count * LAYER_FLOATS];
        for (var coordinate = 0; coordinate < 2 * count; coordinate++) {
            var source = 2 * firstLayer + coordinate;
            if (source >= vertices.getTextureCoordinateCount()) {
                continue;
            }
            var system = new double[4][5];
            var used = 0;
            for (var vertex = 0; vertex < size; vertex += step) {
                double value = vertices.getTerrainTextureCoordinate(vertex, source);
                if (!Double.isFinite(value)) {
                    continue;
                }
                double[] point = {
                    vertices.getPositionX(vertex) - origin[0],
                    vertices.getPositionY(vertex) - origin[1],
                    vertices.getPositionZ(vertex) - origin[2],
                    1
                };
                for (var row = 0; row < 4; row++) {
                    for (var column = 0; column < 4; column++) {
                        system[row][column] += point[row] * point[column];
                    }
                    system[row][4] += point[row] * value;
                }
                used++;
            }
            if (used == 0) {
                continue;
            }
            var solution = solve(system);
            for (var term = 0; term < 4; term++) {
                rows[coordinate * 4 + term] = (float) solution[term];
            }
        }
        return new TerrainLayerProjection(
                new float[] {(float) origin[0], (float) origin[1], (float) origin[2]}, rows, count);
    }

    int getLayerCount() {
        return layers;
    }

    float project(int layer, int component, float x, float y, float z) {
        var at = (layer * 2 + component) * 4;
        return rows[at] * (x - origin[0])
                + rows[at + 1] * (y - origin[1])
                + rows[at + 2] * (z - origin[2])
                + rows[at + 3];
    }

    void writeWorld(float[] model, int first, int count, int slots, float[] target) {
        double r00 = 1, r01 = 0, r02 = 0, r10 = 0, r11 = 1, r12 = 0, r20 = 0, r21 = 0, r22 = 1;
        double tx = 0, ty = 0, tz = 0;
        if (model != null && model.length == 16) {
            r00 = model[0];
            r10 = model[1];
            r20 = model[2];
            r01 = model[4];
            r11 = model[5];
            r21 = model[6];
            r02 = model[8];
            r12 = model[9];
            r22 = model[10];
            tx = model[12];
            ty = model[13];
            tz = model[14];
        }
        var identity = r00 == 1 && r11 == 1 && r22 == 1 && r01 == 0 && r02 == 0 && r10 == 0 && r12 == 0 && r20 == 0
                && r21 == 0;
        var c00 = r11 * r22 - r12 * r21;
        var c01 = r12 * r20 - r10 * r22;
        var c02 = r10 * r21 - r11 * r20;
        var c10 = r02 * r21 - r01 * r22;
        var c11 = r00 * r22 - r02 * r20;
        var c12 = r01 * r20 - r00 * r21;
        var c20 = r01 * r12 - r02 * r11;
        var c21 = r02 * r10 - r00 * r12;
        var c22 = r00 * r11 - r01 * r10;
        var determinant = r00 * c00 + r01 * c01 + r02 * c02;
        if (!identity && Math.abs(determinant) < 1e-12) {
            identity = true;
        }
        Arrays.fill(target, 0, (2 * slots + 1) * 4, 0f);
        for (var slot = 0; slot < Math.min(count, slots); slot++) {
            var layer = first + slot;
            if (layer < 0 || layer >= layers) {
                continue;
            }
            for (var component = 0; component < 2; component++) {
                var at = (layer * 2 + component) * 4;
                double ax = rows[at];
                double ay = rows[at + 1];
                double az = rows[at + 2];
                var out = (slot * 2 + component) * 4;
                if (identity) {
                    target[out] = (float) ax;
                    target[out + 1] = (float) ay;
                    target[out + 2] = (float) az;
                } else {
                    target[out] = (float) ((c00 * ax + c01 * ay + c02 * az) / determinant);
                    target[out + 1] = (float) ((c10 * ax + c11 * ay + c12 * az) / determinant);
                    target[out + 2] = (float) ((c20 * ax + c21 * ay + c22 * az) / determinant);
                }
                target[out + 3] = rows[at + 3];
            }
        }
        var out = 2 * slots * 4;
        target[out] = (float) (r00 * origin[0] + r01 * origin[1] + r02 * origin[2] + tx);
        target[out + 1] = (float) (r10 * origin[0] + r11 * origin[1] + r12 * origin[2] + ty);
        target[out + 2] = (float) (r20 * origin[0] + r21 * origin[1] + r22 * origin[2] + tz);
    }

    private static double[] solve(double[][] system) {
        for (var row = 0; row < 4; row++) {
            system[row][row] += Math.max(Math.abs(system[row][row]), 1) * 1e-12;
        }
        for (var column = 0; column < 4; column++) {
            var pivot = column;
            for (var row = column + 1; row < 4; row++) {
                if (Math.abs(system[row][column]) > Math.abs(system[pivot][column])) {
                    pivot = row;
                }
            }
            var swap = system[column];
            system[column] = system[pivot];
            system[pivot] = swap;
            for (var row = column + 1; row < 4; row++) {
                var factor = system[row][column] / system[column][column];
                for (var term = column; term < 5; term++) {
                    system[row][term] -= factor * system[column][term];
                }
            }
        }
        var solution = new double[4];
        for (var row = 3; row >= 0; row--) {
            var sum = system[row][4];
            for (var term = row + 1; term < 4; term++) {
                sum -= system[row][term] * solution[term];
            }
            solution[row] = sum / system[row][row];
        }
        return solution;
    }
}
