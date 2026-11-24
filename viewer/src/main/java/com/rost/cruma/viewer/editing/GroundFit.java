package com.rost.cruma.viewer.editing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.jetbrains.annotations.Nullable;

final class GroundFit {
    static final float LEVEL = 16;

    static final float STEEPEST_UP = .5f;

    private GroundFit() {}

    static @Nullable Ground fit(List<float[]> points, float x, float y) {
        if (points.isEmpty()) {
            return null;
        }
        var kept = points;
        var plane = plane(kept, x, y);
        if (plane != null && rms(kept, plane, x, y) > LEVEL) {
            var heights = new float[kept.size()];
            for (var i = 0; i < heights.length; i++) {
                heights[i] = kept.get(i)[2];
            }
            var median = median(heights);
            var upper = new ArrayList<float[]>();
            for (var point : kept) {
                if (point[2] >= median) {
                    upper.add(point);
                }
            }
            kept = upper;
            plane = plane(kept, x, y);
        }
        if (plane == null) {
            var heights = new float[kept.size()];
            for (var i = 0; i < heights.length; i++) {
                heights[i] = kept.get(i)[2];
            }
            return new Ground(median(heights), null);
        }
        var length = (float) Math.sqrt(plane[0] * plane[0] + plane[1] * plane[1] + 1);
        var normal = new float[] {-plane[0] / length, -plane[1] / length, 1 / length};
        return new Ground(plane[2], normal[2] >= STEEPEST_UP ? normal : null);
    }

    static float median(float[] values) {
        var sorted = values.clone();
        Arrays.sort(sorted);
        var middle = sorted.length / 2;
        return sorted.length % 2 == 1 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2;
    }

    private static float @Nullable [] plane(List<float[]> points, float x, float y) {
        if (points.size() < 3) {
            return null;
        }
        double sxx = 0, sxy = 0, syy = 0, sx = 0, sy = 0, sxz = 0, syz = 0, sz = 0;
        var n = points.size();
        for (var point : points) {
            double px = point[0] - x, py = point[1] - y, pz = point[2];
            sxx += px * px;
            sxy += px * py;
            syy += py * py;
            sx += px;
            sy += py;
            sxz += px * pz;
            syz += py * pz;
            sz += pz;
        }
        var det = sxx * (syy * n - sy * sy) - sxy * (sxy * n - sy * sx) + sx * (sxy * sy - syy * sx);
        var scale = (sxx + syy) * n;
        if (!(Math.abs(det) > 1e-9 * Math.max(1, scale * scale))) {
            return null;
        }
        var a = (sxz * (syy * n - sy * sy) - sxy * (syz * n - sy * sz) + sx * (syz * sy - syy * sz)) / det;
        var b = (sxx * (syz * n - sz * sy) - sxz * (sxy * n - sy * sx) + sx * (sxy * sz - syz * sx)) / det;
        var c = (sxx * (syy * sz - sy * syz) - sxy * (sxy * sz - syz * sx) + sxz * (sxy * sy - syy * sx)) / det;
        return new float[] {(float) a, (float) b, (float) c};
    }

    private static float rms(List<float[]> points, float[] plane, float x, float y) {
        var sum = 0.0;
        for (var point : points) {
            var residual = point[2] - (plane[0] * (point[0] - x) + plane[1] * (point[1] - y) + plane[2]);
            sum += residual * residual;
        }
        return (float) Math.sqrt(sum / points.size());
    }

    record Ground(float z, float @Nullable [] normal) {}
}
