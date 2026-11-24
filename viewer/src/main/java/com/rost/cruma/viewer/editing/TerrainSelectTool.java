package com.rost.cruma.viewer.editing;

import com.rost.cruma.core.editing.TerrainHeightField;
import com.rost.cruma.core.editing.TerrainNativeCodec.Info;
import com.rost.cruma.core.editing.TerrainSoftSelection;
import com.rost.cruma.viewer.ui.HudWidgets;
import imgui.ImGui;
import imgui.type.ImFloat;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

final class TerrainSelectTool {
    private static final int MAX_DOTS = 8000;
    private static final float MIN_DOT_WEIGHT = .02f;
    final ImFloat inner = new ImFloat(0);
    final ImFloat outer = new ImFloat(512);
    final ImFloat moveBy = new ImFloat(64);
    private final Map<String, TerrainSoftSelection> selections = new HashMap<>();
    private float @Nullable [] weights;
    private @Nullable TerrainSoftSelection weightsSelection;
    private long weightsRevision = -1;
    private float weightsInner = -1;
    private float weightsOuter = -1;

    static String describe(TerrainSoftSelection current) {
        var size = current.size();
        return size == 0 ? "nothing" : String.format(Locale.ROOT, "%,d %s", size, size == 1 ? "sample" : "samples");
    }

    TerrainSoftSelection getSelection(String openOwner, Info info) {
        var selection = selections.get(openOwner);
        if (selection == null || selection.getWidth() != info.width() || selection.getHeight() != info.height()) {
            selection = new TerrainSoftSelection(info.width(), info.height());
            selections.put(openOwner, selection);
        }
        return selection;
    }

    void reset() {
        selections.clear();
        weights = null;
        weightsSelection = null;
    }

    void reset(String owner) {
        if (selections.remove(owner) == weightsSelection) {
            weights = null;
            weightsSelection = null;
        }
    }

    float[] computeWeights(TerrainSoftSelection current, float spacing) {
        var in = Math.max(0, inner.get()) / spacing;
        var out = Math.max(0, outer.get()) / spacing;
        if (weights == null
                || weightsSelection != current
                || weightsRevision != current.getRevision()
                || weightsInner != in
                || weightsOuter != out) {
            weights = current.computeWeights(in, out);
            weightsSelection = current;
            weightsRevision = current.getRevision();
            weightsInner = in;
            weightsOuter = out;
        }
        return weights;
    }

    void drawDots(ScreenProjection projection, Info info, TerrainHeightField ground, float[] values) {
        var width = info.width();
        var height = info.height();
        var count = 0;
        for (var value : values) {
            if (value >= MIN_DOT_WEIGHT) {
                count++;
            }
        }
        if (count == 0) {
            return;
        }
        var step = Math.max(1, (int) Math.ceil(Math.sqrt(count / (double) MAX_DOTS)));
        var draw = ImGui.getBackgroundDrawList();
        var point = new float[2];
        for (var y = 0; y < height; y += step) {
            for (var x = 0; x < width; x += step) {
                var weight = values[y * width + x];
                if (weight < MIN_DOT_WEIGHT) {
                    continue;
                }
                var world = info.toWorld().transform(x, y, ground.getHeightAt(x, y));
                if (projection.project(world.x(), world.y(), world.z(), point, 0)) {
                    draw.addCircleFilled(
                            point[0], point[1], 2.5f, EditorUi.fill(HudWidgets.ACCENT, .25f + .75f * weight));
                }
            }
        }
    }
}
