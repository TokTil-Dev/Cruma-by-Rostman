package com.rost.cruma.core.editing;

import java.util.BitSet;

public final class TerrainSoftSelection {
    private static final double FAR = 1e20;

    private final int width;
    private final int height;
    private final BitSet selected = new BitSet();
    private long revision;

    public TerrainSoftSelection(int width, int height) {
        if (width < 8 || height < 8 || width > 4096 || height > 4096) {
            throw new IllegalArgumentException("Invalid Terrain selection grid");
        }
        this.width = width;
        this.height = height;
    }

    public static float falloff(double distance, double inner, double outer) {
        if (distance <= inner) {
            return 1;
        }
        if (distance >= outer) {
            return 0;
        }
        return (float) (.5 + .5 * Math.cos(Math.PI * (distance - inner) / (outer - inner)));
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public int size() {
        return selected.cardinality();
    }

    public boolean isEmpty() {
        return selected.isEmpty();
    }

    public long getRevision() {
        return revision;
    }

    public boolean isSelected(int x, int y) {
        return x >= 0 && y >= 0 && x < width && y < height && selected.get(y * width + x);
    }

    public boolean add(float x, float y, float radius) {
        return change(x, y, radius, true);
    }

    public boolean remove(float x, float y, float radius) {
        return change(x, y, radius, false);
    }

    public void clear() {
        if (!selected.isEmpty()) {
            selected.clear();
            revision++;
        }
    }

    public float[] computeWeights(float inner, float outer) {
        if (!Float.isFinite(inner) || !Float.isFinite(outer) || inner < 0 || outer < 0) {
            throw new IllegalArgumentException("Invalid Terrain soft selection radii");
        }
        var weights = new float[width * height];
        if (selected.isEmpty()) {
            return weights;
        }
        var squared = distances();
        for (var index = 0; index < weights.length; index++) {
            weights[index] = falloff(Math.sqrt(squared[index]), inner, outer);
        }
        return weights;
    }

    private boolean change(float x, float y, float radius, boolean on) {
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(radius) || radius < 0) {
            throw new IllegalArgumentException("Invalid Terrain selection brush");
        }
        var minX = Math.max(0, (int) Math.floor(x - radius));
        var maxX = Math.min(width - 1, (int) Math.ceil(x + radius));
        var minY = Math.max(0, (int) Math.floor(y - radius));
        var maxY = Math.min(height - 1, (int) Math.ceil(y + radius));
        var changed = false;
        for (var sy = minY; sy <= maxY; sy++) {
            for (var sx = minX; sx <= maxX; sx++) {
                var dx = sx - x;
                var dy = sy - y;
                if (dx * dx + dy * dy > radius * radius) {
                    continue;
                }
                var index = sy * width + sx;
                if (selected.get(index) != on) {
                    selected.set(index, on);
                    changed = true;
                }
            }
        }
        if (changed) {
            revision++;
        }
        return changed;
    }

    private double[] distances() {
        var grid = new double[width * height];
        for (var i = 0; i < grid.length; i++) {
            grid[i] = selected.get(i) ? 0 : FAR;
        }
        var line = new double[Math.max(width, height)];
        var out = new double[line.length];
        var hulls = new int[line.length];
        var bounds = new double[line.length + 1];
        for (var x = 0; x < width; x++) {
            for (var y = 0; y < height; y++) {
                line[y] = grid[y * width + x];
            }
            transform(line, height, out, hulls, bounds);
            for (var y = 0; y < height; y++) {
                grid[y * width + x] = out[y];
            }
        }
        for (var y = 0; y < height; y++) {
            System.arraycopy(grid, y * width, line, 0, width);
            transform(line, width, out, hulls, bounds);
            System.arraycopy(out, 0, grid, y * width, width);
        }
        return grid;
    }

    private static void transform(double[] f, int n, double[] out, int[] hulls, double[] bounds) {
        var k = 0;
        hulls[0] = 0;
        bounds[0] = Double.NEGATIVE_INFINITY;
        bounds[1] = Double.POSITIVE_INFINITY;
        for (var q = 1; q < n; q++) {
            var s = intersection(f, q, hulls[k]);
            while (s <= bounds[k]) {
                k--;
                s = intersection(f, q, hulls[k]);
            }
            k++;
            hulls[k] = q;
            bounds[k] = s;
            bounds[k + 1] = Double.POSITIVE_INFINITY;
        }
        k = 0;
        for (var q = 0; q < n; q++) {
            while (bounds[k + 1] < q) {
                k++;
            }
            var d = q - hulls[k];
            out[q] = d * (double) d + f[hulls[k]];
        }
    }

    private static double intersection(double[] f, int q, int p) {
        return (f[q] + (double) q * q - (f[p] + (double) p * p)) / (2.0 * q - 2.0 * p);
    }
}
