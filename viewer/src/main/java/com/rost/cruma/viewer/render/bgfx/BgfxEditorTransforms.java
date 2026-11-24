package com.rost.cruma.viewer.render.bgfx;

import com.rost.cruma.core.editing.MeshDecorationCarry;
import com.rost.cruma.core.world.MeshDecoration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class BgfxEditorTransforms {
    private BgfxEditorTransforms() {}

    static void validate(Map<String, float[]> deltas) {
        for (var entry : deltas.entrySet()) {
            var matrix = entry.getValue();
            if (entry.getKey() == null || entry.getKey().isBlank() || matrix == null || matrix.length != 16) {
                throw new IllegalArgumentException("An actor preview requires a source identity and 4x4 matrix");
            }
            for (var value : matrix) {
                if (!Float.isFinite(value)) {
                    throw new IllegalArgumentException("Actor preview matrices must be finite");
                }
            }
            if (Math.abs(matrix[3]) > 0.0001f
                    || Math.abs(matrix[7]) > 0.0001f
                    || Math.abs(matrix[11]) > 0.0001f
                    || Math.abs(matrix[15] - 1f) > 0.0001f) {
                throw new IllegalArgumentException("Actor preview matrices must be affine");
            }
        }
    }

    static void apply(float[] original, float[] target, int stride, List<String> actors, Map<String, float[]> deltas) {
        if (original == target
                || original.length != target.length
                || stride < 16
                || original.length != actors.size() * stride) {
            throw new IllegalArgumentException("Invalid original and target instance records");
        }
        System.arraycopy(original, 0, target, 0, original.length);
        Map<String, float[]> decorationParts = null;
        for (var instance = 0; instance < actors.size(); instance++) {
            var actor = actors.get(instance);
            var delta = deltas.get(actor);
            if (delta == null) {
                continue;
            }
            var offset = instance * stride;
            var turn = delta;
            if (stride > 19 && original[offset + 19] == MeshDecoration.MARKER) {
                if (decorationParts == null) {
                    decorationParts = new HashMap<>();
                }
                turn = decorationParts.computeIfAbsent(actor, _ -> toDecorationPart(delta));
            }
            for (var column = 0; column < 4; column++) {
                var source = offset + column * 4;
                var linear = column == 3 ? delta : turn;
                for (var row = 0; row < 3; row++) {
                    target[source + row] = linear[row] * original[source]
                            + linear[4 + row] * original[source + 1]
                            + linear[8 + row] * original[source + 2]
                            + (column == 3 ? delta[12 + row] : 0f);
                }
            }
            if (BgfxBillboardTilt.carriesTilt(target, offset)) {
                BgfxBillboardTilt.writeFromMatrix(target, offset);
            }
        }
    }

    private static float[] toDecorationPart(float[] delta) {
        var linear = new double[3][3];
        for (var row = 0; row < 3; row++) {
            for (var column = 0; column < 3; column++) {
                linear[row][column] = delta[column * 4 + row];
            }
        }
        double[][] part;
        try {
            part = MeshDecorationCarry.toSimilarPart(linear);
        } catch (IllegalArgumentException flat) {
            return delta;
        }
        var result = delta.clone();
        for (var row = 0; row < 3; row++) {
            for (var column = 0; column < 3; column++) {
                result[column * 4 + row] = (float) part[row][column];
            }
        }
        return result;
    }
}
