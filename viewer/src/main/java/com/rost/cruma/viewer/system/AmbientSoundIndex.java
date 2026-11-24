package com.rost.cruma.viewer.system;

import com.rost.cruma.core.world.AmbientSound;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class AmbientSoundIndex {
    private static final int LEAF_SIZE = 8;
    private final AmbientSound[] sounds;
    private final float[] x;
    private final float[] y;
    private final float[] z;
    private final float[] reachSquared;
    private final int[] order;
    private final float[] minX, minY, minZ, maxX, maxY, maxZ, maximumReachSquared;
    private final int[] left, right, first, counts;
    private final int[] stack = new int[Integer.SIZE];
    private final int[] selected;
    private final List<AmbientSound> candidates = new ArrayList<>();
    private int nodes;

    AmbientSoundIndex(List<AmbientSound> placements, float radiusMultiplier) {
        sounds = placements.toArray(AmbientSound[]::new);
        x = new float[sounds.length];
        y = new float[sounds.length];
        z = new float[sounds.length];
        reachSquared = new float[sounds.length];
        order = new int[sounds.length];
        selected = new int[sounds.length];
        var capacity = Math.multiplyExact(sounds.length, 2);
        minX = new float[capacity];
        minY = new float[capacity];
        minZ = new float[capacity];
        maxX = new float[capacity];
        maxY = new float[capacity];
        maxZ = new float[capacity];
        maximumReachSquared = new float[capacity];
        left = new int[capacity];
        right = new int[capacity];
        first = new int[capacity];
        counts = new int[capacity];
        for (var index = 0; index < sounds.length; index++) {
            var sound = sounds[index];
            x[index] = sound.position().getX();
            y[index] = sound.position().getY();
            z[index] = sound.position().getZ();
            var reach = sound.radius() * radiusMultiplier;
            reachSquared[index] = Float.isFinite(x[index]) && Float.isFinite(y[index]) && Float.isFinite(z[index])
                    ? reach * reach
                    : Float.NaN;
            order[index] = index;
        }
        if (sounds.length > 0) {
            build(0, sounds.length);
        }
    }

    List<AmbientSound> findCandidates(float cameraX, float cameraY, float cameraZ) {
        candidates.clear();
        if (sounds.length == 0) {
            return candidates;
        }
        if (Float.isNaN(cameraX) || Float.isNaN(cameraY) || Float.isNaN(cameraZ)) {
            for (var sound : sounds) {
                candidates.add(sound);
            }
            return candidates;
        }
        var pending = 0;
        var found = 0;
        stack[pending++] = 0;
        while (pending > 0) {
            var node = stack[--pending];
            var dx = cameraX < minX[node] ? minX[node] - cameraX : cameraX > maxX[node] ? maxX[node] - cameraX : 0f;
            var dy = cameraY < minY[node] ? minY[node] - cameraY : cameraY > maxY[node] ? maxY[node] - cameraY : 0f;
            var dz = cameraZ < minZ[node] ? minZ[node] - cameraZ : cameraZ > maxZ[node] ? maxZ[node] - cameraZ : 0f;
            if (dx * dx + dy * dy + dz * dz > maximumReachSquared[node]) {
                continue;
            }
            if (left[node] < 0) {
                var end = first[node] + counts[node];
                for (var index = first[node]; index < end; index++) {
                    selected[found++] = order[index];
                }
            } else {
                stack[pending++] = right[node];
                stack[pending++] = left[node];
            }
        }
        Arrays.sort(selected, 0, found);
        for (var index = 0; index < found; index++) {
            candidates.add(sounds[selected[index]]);
        }
        return candidates;
    }

    private int build(int begin, int count) {
        var node = nodes++;
        first[node] = begin;
        counts[node] = count;
        minX[node] = minY[node] = minZ[node] = Float.POSITIVE_INFINITY;
        maxX[node] = maxY[node] = maxZ[node] = Float.NEGATIVE_INFINITY;
        var end = begin + count;
        for (var offset = begin; offset < end; offset++) {
            var index = order[offset];
            minX[node] = Math.min(minX[node], x[index]);
            maxX[node] = Math.max(maxX[node], x[index]);
            minY[node] = Math.min(minY[node], y[index]);
            maxY[node] = Math.max(maxY[node], y[index]);
            minZ[node] = Math.min(minZ[node], z[index]);
            maxZ[node] = Math.max(maxZ[node], z[index]);
            maximumReachSquared[node] = Math.max(maximumReachSquared[node], reachSquared[index]);
        }
        if (count <= LEAF_SIZE) {
            left[node] = right[node] = -1;
            return node;
        }
        var width = maxX[node] - minX[node];
        var height = maxY[node] - minY[node];
        var depth = maxZ[node] - minZ[node];
        var axis = height > width ? y : x;
        if (depth > Math.max(width, height)) {
            axis = z;
        }
        var middle = begin + count / 2;
        selectMedian(begin, end, middle, axis);
        left[node] = build(begin, middle - begin);
        right[node] = build(middle, end - middle);
        return node;
    }

    private void selectMedian(int begin, int end, int middle, float[] axis) {
        var low = begin;
        var high = end - 1;
        while (low < high) {
            var pivot = axis[order[(low + high) >>> 1]];
            var i = low;
            var j = high;
            while (i <= j) {
                while (i <= j && axis[order[i]] < pivot) {
                    i++;
                }
                while (i <= j && axis[order[j]] > pivot) {
                    j--;
                }
                if (i <= j) {
                    var swap = order[i];
                    order[i++] = order[j];
                    order[j--] = swap;
                }
            }
            if (middle <= j) {
                high = j;
            } else if (middle >= i) {
                low = i;
            } else {
                return;
            }
        }
    }
}
