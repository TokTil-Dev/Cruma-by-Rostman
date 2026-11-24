package com.rost.cruma.viewer.editing;

import com.rost.cruma.core.geometry.Bounds3f;
import com.rost.cruma.viewer.geodata.GeoOverlayLines;
import com.rost.cruma.viewer.render.Camera;
import com.rost.cruma.viewer.ui.HudWidgets;
import com.rost.cruma.viewer.ui.MarkerDepth;
import com.rost.cruma.viewer.ui.MarkerDepth.Placed;
import com.rost.cruma.viewer.ui.MarkerDepth.Projector;
import imgui.ImDrawList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

public final class EffectMarks {
    public static final float REACH = 12_000;
    private static final int MOST = 400;

    private EffectMarks() {}

    public record Effect(String path, Bounds3f bounds) {
        float x() {
            return (bounds.minX() + bounds.maxX()) * .5f;
        }

        float y() {
            return (bounds.minY() + bounds.maxY()) * .5f;
        }

        float z() {
            return (bounds.minZ() + bounds.maxZ()) * .5f;
        }
    }

    private record Shown(Effect effect, Placed placed) {}

    public static final class Grid {
        private static final int CELL = 2048;
        private final Map<Long, List<Effect>> cells = new HashMap<>();

        public Grid(Collection<Effect> effects) {
            for (var effect : effects) {
                cells.computeIfAbsent(
                                key(
                                        Math.floorDiv((int) Math.floor(effect.x()), CELL),
                                        Math.floorDiv((int) Math.floor(effect.y()), CELL)),
                                ignored -> new ArrayList<>())
                        .add(effect);
            }
        }

        public List<Effect> getNear(float x, float y, float reach) {
            var result = new ArrayList<Effect>();
            for (var cellX = Math.floorDiv((int) Math.floor(x - reach), CELL);
                    cellX <= Math.floorDiv((int) Math.floor(x + reach), CELL);
                    cellX++) {
                for (var cellY = Math.floorDiv((int) Math.floor(y - reach), CELL);
                        cellY <= Math.floorDiv((int) Math.floor(y + reach), CELL);
                        cellY++) {
                    var effects = cells.get(key(cellX, cellY));
                    if (effects == null) {
                        continue;
                    }
                    for (var effect : effects) {
                        var dx = effect.x() - x;
                        var dy = effect.y() - y;
                        if (dx * dx + dy * dy <= reach * reach) {
                            result.add(effect);
                        }
                    }
                }
            }
            return result;
        }

        private static long key(int cellX, int cellY) {
            return (long) cellX << 32 | cellY & 0xffffffffL;
        }
    }

    public static @Nullable Effect markAt(
            Camera camera, float x, float y, int width, int height, List<Effect> effects) {
        var shown = place(camera, width, height, effects);
        Effect nearest = null;
        var best = Float.MAX_VALUE;
        for (var mark : shown) {
            var placed = mark.placed();
            var dx = placed.x() - x;
            var dy = placed.y() - y;
            var reach = MarkerDepth.pickRadius(placed.dotDiameter());
            if (dx * dx + dy * dy <= reach * reach && placed.distance() < best) {
                best = placed.distance();
                nearest = mark.effect();
            }
        }
        return nearest;
    }

    public static void draw(
            @Nullable GeoOverlayLines lines,
            ImDrawList draw,
            Camera camera,
            int width,
            int height,
            List<Effect> effects,
            Set<String> selection,
            @Nullable String hovered) {
        var shown = place(camera, width, height, effects);
        shown.sort(Comparator.comparingDouble((Shown mark) -> mark.placed().distance())
                .reversed());
        var projector = new Projector(camera, width, height);
        for (var mark : shown) {
            var path = mark.effect().path();
            var big = selection.contains(path) || path.equals(hovered);
            var placed = mark.placed();
            var alpha = big ? 1f : projector.getAlpha(placed.distance(), REACH);
            var color = MarkerDepth.faded(HudWidgets.MUTED, alpha);
            var effect = mark.effect();
            if (lines != null) {
                if (big) {
                    MarkerDepth.ring(
                            lines,
                            effect.x(),
                            effect.y(),
                            effect.z(),
                            placed.pixelsPerUnit(),
                            HudWidgets.ACCENT,
                            true,
                            1);
                }
                MarkerDepth.mark(lines, effect.x(), effect.y(), effect.z(), placed.pixelsPerUnit(), color, false, big);
                continue;
            }
            var radius = big ? 6f : 4.5f;
            draw.addCircleFilled(placed.x(), placed.y(), radius + 1.5f, HudWidgets.BACKGROUND, 16);
            draw.addCircle(placed.x(), placed.y(), radius, color, 16, 2f);
            if (big) {
                draw.addCircle(placed.x(), placed.y(), radius + 4, HudWidgets.ACCENT, 20, 2f);
            }
        }
    }

    private static List<Shown> place(Camera camera, int width, int height, List<Effect> effects) {
        var projector = new Projector(camera, width, height);
        var shown = new ArrayList<Shown>();
        for (var effect : effects) {
            var placed = projector.place(effect.x(), effect.y(), effect.z());
            if (placed != null
                    && placed.x() >= 0
                    && placed.y() >= 0
                    && placed.x() <= width
                    && placed.y() <= height
                    && (projector.isOrtho() || placed.distance() <= REACH)) {
                shown.add(new Shown(effect, placed));
            }
        }
        if (shown.size() > MOST) {
            shown.sort(Comparator.comparingDouble(mark -> mark.placed().distance()));
            return new ArrayList<>(shown.subList(0, MOST));
        }
        return shown;
    }
}
