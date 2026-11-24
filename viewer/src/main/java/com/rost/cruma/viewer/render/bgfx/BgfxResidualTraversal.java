package com.rost.cruma.viewer.render.bgfx;

import com.rost.cruma.core.geometry.Bounds3f;
import com.rost.cruma.core.geometry.Frustum;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

final class BgfxResidualTraversal {
    private List<BgfxSceneObject> objects = List.of();
    private int[] eligible = new int[0];
    private int[] staticObjects = new int[0];
    private int[] movingObjects = new int[0];
    private int[] visible = new int[0];
    private int[] staticVisible = new int[0];
    private int[] stack = new int[0];
    private BgfxCommandRangeHierarchy hierarchy;
    private int totalPrimitives;
    private int visibleCount;
    private int culledPrimitives;

    void rebuild(List<BgfxSceneObject> source, Predicate<BgfxSceneObject> skip) {
        objects = List.copyOf(source);
        var all = new ArrayList<Integer>();
        var stationary = new ArrayList<Integer>();
        var moving = new ArrayList<Integer>();
        var bounds = new ArrayList<Bounds3f>();
        totalPrimitives = 0;
        for (var index = 0; index < source.size(); index++) {
            var object = source.get(index);
            if (skip.test(object)) {
                continue;
            }
            all.add(index);
            totalPrimitives += object.getGeometry().getPrimitives().size();
            if (object.isMoving()) {
                moving.add(index);
            } else {
                stationary.add(index);
                bounds.add(object.getWorldBounds());
            }
        }
        eligible = all.stream().mapToInt(Integer::intValue).toArray();
        staticObjects = stationary.stream().mapToInt(Integer::intValue).toArray();
        movingObjects = moving.stream().mapToInt(Integer::intValue).toArray();
        visible = new int[eligible.length];
        staticVisible = new int[staticObjects.length];
        hierarchy = bounds.isEmpty() ? null : BgfxCommandRangeHierarchy.fromBounds(8, bounds, bounds.size());
        stack = hierarchy == null ? new int[0] : new int[hierarchy.getMaximumDepth() + 1];
    }

    void collect(Frustum frustum) {
        visibleCount = 0;
        if (frustum == null) {
            System.arraycopy(eligible, 0, visible, 0, eligible.length);
            visibleCount = eligible.length;
            culledPrimitives = 0;
            return;
        }
        if (hierarchy != null) {
            var count = hierarchy.collectVisibleCommands(0, frustum, staticVisible, stack);
            if (count == BgfxCommandRangeHierarchy.ALL_VISIBLE) {
                System.arraycopy(staticObjects, 0, visible, 0, staticObjects.length);
                visibleCount = staticObjects.length;
            } else {
                for (var index = 0; index < count; index++) {
                    visible[visibleCount++] = staticObjects[staticVisible[index]];
                }
            }
        }
        for (var index : movingObjects) {
            var b = objects.get(index).getWorldBounds();
            if (frustum.intersects(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ())) {
                visible[visibleCount++] = index;
            }
        }
        Arrays.sort(visible, 0, visibleCount);
        var visiblePrimitives = 0;
        for (var index = 0; index < visibleCount; index++) {
            visiblePrimitives +=
                    objects.get(visible[index]).getGeometry().getPrimitives().size();
        }
        culledPrimitives = totalPrimitives - visiblePrimitives;
    }

    int getCount() {
        return visibleCount;
    }

    int getObjectIndex(int index) {
        return visible[index];
    }

    int getCulledPrimitives() {
        return culledPrimitives;
    }

    void audit(Frustum frustum) {
        var nextVisible = 0;
        var rejected = 0;
        for (var index : eligible) {
            var object = objects.get(index);
            var b = object.getWorldBounds();
            var expected =
                    frustum == null || frustum.intersects(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ());
            if (expected) {
                if (nextVisible >= visibleCount || visible[nextVisible++] != index) {
                    throw new IllegalStateException("residual hierarchy changed object visibility/order at " + index);
                }
            } else {
                rejected += object.getGeometry().getPrimitives().size();
            }
        }
        if (nextVisible != visibleCount || rejected != culledPrimitives) {
            throw new IllegalStateException("residual hierarchy changed the scene traversal census");
        }
    }

    void clear() {
        objects = List.of();
        eligible = staticObjects = movingObjects = visible = staticVisible = stack = new int[0];
        hierarchy = null;
        totalPrimitives = visibleCount = culledPrimitives = 0;
    }
}
