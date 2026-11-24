package com.rost.cruma.core.world;

import com.rost.cruma.core.geometry.Box;
import glm_.vec3.Vec3;
import java.util.List;
import org.eclipse.collections.api.map.primitive.MutableLongObjectMap;
import org.eclipse.collections.impl.list.mutable.primitive.IntArrayList;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;
import org.jetbrains.annotations.Nullable;

public class LightGrid {
    public static final int MAX_LIGHTS = 4;

    static final boolean DIAGNOSTICS = Boolean.getBoolean("cruma.core.light_grid_diagnostics");

    private static final float CELL_SIZE = 2000f;
    private static final float SELECTION_CELL_SIZE = 1000f;
    private static final float WIDE_LIGHT_RADIUS = 8 * CELL_SIZE;
    private static final int[] NO_LIGHTS = new int[0];

    private final Light[] lights;
    private final MutableLongObjectMap<IntArrayList> cells = new LongObjectHashMap<>();
    private final MutableLongObjectMap<IntArrayList> selectionCells = new LongObjectHashMap<>();
    private final MutableLongObjectMap<int[]> selectionNeighborhoods = new LongObjectHashMap<>();
    private final float[] positionRadius;
    private final float[] radiusSquared;
    private final float[] inverseRadius;
    private final float[] colors;
    private final float[] colorSums;
    private final int[] wideLights;
    private float maxRadius;
    private float gridMaxRadius;
    private int lastSelectionX = Integer.MIN_VALUE;
    private int lastSelectionY = Integer.MIN_VALUE;
    private int lastSelectionZ = Integer.MIN_VALUE;
    private int[] lastSelectionNeighborhood;
    private long diagnosticSelects;
    private long diagnosticCandidates;
    private long diagnosticRadiusHits;
    private long diagnosticSquareRoots;
    private long diagnosticNeighborhoods;
    private long diagnosticNeighborhoodCandidates;
    private int diagnosticMaxNeighborhood;

    public LightGrid(List<Light> lights) {
        this.lights = lights.toArray(Light[]::new);
        positionRadius = new float[lights.size() * 4];
        radiusSquared = new float[lights.size()];
        inverseRadius = new float[lights.size()];
        colors = new float[lights.size() * 3];
        colorSums = new float[lights.size()];
        for (int i = 0; i < lights.size(); i++) {
            var light = this.lights[i];
            var p = light.position();
            var c = light.color();
            var positionOffset = i * 4;
            var colorOffset = i * 3;
            var radius = light.radius();
            positionRadius[positionOffset] = p.getX();
            positionRadius[positionOffset + 1] = p.getY();
            positionRadius[positionOffset + 2] = p.getZ();
            positionRadius[positionOffset + 3] = radius;
            radiusSquared[i] = radius * radius;
            inverseRadius[i] = radius > 0f ? 1f / radius : 0f;
            colors[colorOffset] = c.getX();
            colors[colorOffset + 1] = c.getY();
            colors[colorOffset + 2] = c.getZ();
            colorSums[i] = c.getX() + c.getY() + c.getZ();
            maxRadius = Math.max(maxRadius, radius);
        }
        var selectionOrder = new int[lights.size()];
        for (var i = 0; i < selectionOrder.length; i++) {
            selectionOrder[i] = i;
        }
        sortByColorSumDescending(selectionOrder, 0, selectionOrder.length - 1);
        var wide = new IntArrayList();
        for (var i : selectionOrder) {
            var offset = i * 4;
            var px = positionRadius[offset];
            var py = positionRadius[offset + 1];
            var pz = positionRadius[offset + 2];
            var radius = positionRadius[offset + 3];
            if (radius > WIDE_LIGHT_RADIUS) {
                wide.add(i);
                continue;
            }
            gridMaxRadius = Math.max(gridMaxRadius, radius);
            cells.getIfAbsentPut(cellKey(toCell(px), toCell(py), toCell(pz)), IntArrayList::new)
                    .add(i);
            indexSelectionCells(i, px, py, pz, radius);
        }
        wideLights = wide.toArray();
    }

    public boolean overlaps(Box bounds) {
        return findOverlappingLightIndices(bounds).length != 0;
    }

    public int[] findOverlappingLightIndices(Box bounds) {
        if (lights.length == 0 || maxRadius <= 0f) {
            return new int[0];
        }
        var min = bounds.getMin();
        var max = bounds.getMax();
        var minX = toCell(min.getX() - gridMaxRadius);
        var minY = toCell(min.getY() - gridMaxRadius);
        var minZ = toCell(min.getZ() - gridMaxRadius);
        var maxX = toCell(max.getX() + gridMaxRadius);
        var maxY = toCell(max.getY() + gridMaxRadius);
        var maxZ = toCell(max.getZ() + gridMaxRadius);
        var result = new IntArrayList();
        for (var idx : wideLights) {
            if (reaches(idx, min.getX(), min.getY(), min.getZ(), max.getX(), max.getY(), max.getZ())) {
                result.add(idx);
            }
        }
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    var bucket = cells.get(cellKey(x, y, z));
                    if (bucket == null) {
                        continue;
                    }
                    for (int i = 0, n = bucket.size(); i < n; i++) {
                        var idx = bucket.get(i);
                        if (reaches(idx, min.getX(), min.getY(), min.getZ(), max.getX(), max.getY(), max.getZ())) {
                            result.add(idx);
                        }
                    }
                }
            }
        }
        var indices = result.toArray();
        sortByColorSumDescending(indices, 0, indices.length - 1);
        return indices;
    }

    public int select(Vec3 p, int max, float[] outPosRadius, float[] outColor) {
        return select(p, max, outPosRadius, outColor, null);
    }

    public int select(Vec3 p, int max, float[] outPosRadius, float[] outColor, @Nullable int[] outIdx) {
        return select(p.getX(), p.getY(), p.getZ(), max, outPosRadius, outColor, outIdx);
    }

    public int select(
            float px, float py, float pz, int max, float[] outPosRadius, float[] outColor, @Nullable int[] outIdx) {
        return select(px, py, pz, max, outPosRadius, outColor, outIdx, null);
    }

    public int select(
            float px,
            float py,
            float pz,
            int max,
            float[] outPosRadius,
            float[] outColor,
            @Nullable int[] outIdx,
            @Nullable int[] candidates) {
        return select(px, py, pz, max, outPosRadius, outColor, outIdx, candidates, null);
    }

    public int select(
            float px,
            float py,
            float pz,
            int max,
            float[] outPosRadius,
            float[] outColor,
            @Nullable int[] outIdx,
            @Nullable int[] candidates,
            float @Nullable [] outDistance) {
        return select(
                px,
                py,
                pz,
                max,
                outPosRadius,
                outColor,
                outIdx,
                candidates,
                outDistance,
                outColor,
                true,
                true,
                candidates == null);
    }

    public int selectIndices(
            float px,
            float py,
            float pz,
            int max,
            int[] outIdx,
            @Nullable int[] candidates,
            float[] outDistance,
            float[] scoreScratch) {
        return select(px, py, pz, max, null, null, outIdx, candidates, outDistance, scoreScratch, false, true, true);
    }

    public int selectIndicesFromCandidates(
            float px,
            float py,
            float pz,
            int max,
            int[] outIdx,
            int[] candidates,
            float[] outDistance,
            float[] scoreScratch) {
        return select(px, py, pz, max, null, null, outIdx, candidates, outDistance, scoreScratch, false, false, true);
    }

    public float getPositionX(int index) {
        return positionRadius[index * 4];
    }

    public float getPositionY(int index) {
        return positionRadius[index * 4 + 1];
    }

    public float getPositionZ(int index) {
        return positionRadius[index * 4 + 2];
    }

    public float getRadius(int index) {
        return positionRadius[index * 4 + 3];
    }

    public float getRed(int index) {
        return colors[index * 3];
    }

    public float getGreen(int index) {
        return colors[index * 3 + 1];
    }

    public float getBlue(int index) {
        return colors[index * 3 + 2];
    }

    public Light getLight(int index) {
        return lights[index];
    }

    public int @Nullable [] getSingleSelectionCellCandidates(Box bounds) {
        var min = bounds.getMin();
        var max = bounds.getMax();
        var x = selectionCell(min.getX());
        var y = selectionCell(min.getY());
        var z = selectionCell(min.getZ());
        if (x != selectionCell(max.getX()) || y != selectionCell(max.getY()) || z != selectionCell(max.getZ())) {
            return null;
        }
        return selectionNeighborhood(x, y, z);
    }

    public boolean isEmpty() {
        return lights.length == 0;
    }

    int getIndexedSelectionCells() {
        return selectionCells.size();
    }

    public Diagnostics getDiagnostics() {
        return new Diagnostics(
                lights.length,
                maxRadius,
                diagnosticSelects,
                diagnosticCandidates,
                diagnosticRadiusHits,
                diagnosticSquareRoots,
                diagnosticNeighborhoods,
                diagnosticNeighborhoodCandidates,
                diagnosticMaxNeighborhood);
    }

    public record Diagnostics(
            int lights,
            float maxRadius,
            long selects,
            long candidates,
            long radiusHits,
            long squareRoots,
            long neighborhoods,
            long neighborhoodCandidates,
            int maxNeighborhood) {}

    private int select(
            float px,
            float py,
            float pz,
            int max,
            float @Nullable [] outPosRadius,
            float @Nullable [] outColor,
            @Nullable int[] outIdx,
            @Nullable int[] candidates,
            float @Nullable [] outDistance,
            float[] scoreScratch,
            boolean copyPayload,
            boolean querySelectionCell,
            boolean candidatesOrdered) {
        var bestIdx = outIdx != null ? outIdx : new int[max];
        var bestScore = scoreScratch;
        var found = 0;
        var weakest = 0;

        var local = querySelectionCell
                ? selectionNeighborhood(selectionCell(px), selectionCell(py), selectionCell(pz))
                : null;
        var bucket = local == null || candidates != null && candidates.length <= local.length ? candidates : local;
        var bucketOrdered = bucket == local || candidatesOrdered;
        if (DIAGNOSTICS) {
            diagnosticSelects++;
        }
        for (int i = 0; i < bucket.length; i++) {
            var idx = bucket[i];
            if (DIAGNOSTICS) {
                diagnosticCandidates++;
            }
            var colorSum = colorSums[idx];
            var offset = idx * 4;
            var lightX = positionRadius[offset];
            var lightY = positionRadius[offset + 1];
            var lightZ = positionRadius[offset + 2];
            var distSq = sq(lightX - px) + sq(lightY - py) + sq(lightZ - pz);
            if (distSq >= radiusSquared[idx]) {
                continue;
            }
            if (DIAGNOSTICS) {
                diagnosticRadiusHits++;
            }
            if (found == max) {
                var weakestScore = bestScore[weakest];
                if (colorSum <= weakestScore) {
                    if (bucketOrdered) {
                        break;
                    }
                    continue;
                }
                var winningDistance = Math.nextUp(positionRadius[offset + 3] * (1f - weakestScore / colorSum));
                if (distSq > winningDistance * winningDistance) {
                    continue;
                }
            }
            var dist = (float) Math.sqrt(distSq);
            if (DIAGNOSTICS) {
                diagnosticSquareRoots++;
            }
            var att = 1f - dist * inverseRadius[idx];
            var score = colorSums[idx] * att;
            if (found < max) {
                bestIdx[found] = idx;
                bestScore[found] = score;
                if (outDistance != null) {
                    outDistance[found] = dist;
                }
                found++;
                if (found == max) {
                    weakest = findWeakest(bestScore, max);
                }
            } else {
                if (score > bestScore[weakest]) {
                    bestIdx[weakest] = idx;
                    bestScore[weakest] = score;
                    if (outDistance != null) {
                        outDistance[weakest] = dist;
                    }
                    weakest = findWeakest(bestScore, max);
                }
            }
        }

        if (copyPayload) {
            for (int k = 0; k < found; k++) {
                var idx = bestIdx[k];
                var positionOffset = idx * 4;
                var colorOffset = idx * 3;
                outPosRadius[k * 4] = positionRadius[positionOffset];
                outPosRadius[k * 4 + 1] = positionRadius[positionOffset + 1];
                outPosRadius[k * 4 + 2] = positionRadius[positionOffset + 2];
                outPosRadius[k * 4 + 3] = positionRadius[positionOffset + 3];
                outColor[k * 4] = colors[colorOffset];
                outColor[k * 4 + 1] = colors[colorOffset + 1];
                outColor[k * 4 + 2] = colors[colorOffset + 2];
                outColor[k * 4 + 3] = 0f;
            }
        }
        return found;
    }

    private int[] selectionNeighborhood(int cx, int cy, int cz) {
        if (lastSelectionNeighborhood != null && cx == lastSelectionX && cy == lastSelectionY && cz == lastSelectionZ) {
            return lastSelectionNeighborhood;
        }
        var neighborhoodKey = cellKey(cx, cy, cz);
        var cached = selectionNeighborhoods.get(neighborhoodKey);
        if (cached != null) {
            rememberSelectionNeighborhood(cx, cy, cz, cached);
            return cached;
        }
        var neighborhood = selectionCells.get(neighborhoodKey);
        var immutable = withWideLights(neighborhood == null ? NO_LIGHTS : neighborhood.toArray(), cx, cy, cz);
        if (DIAGNOSTICS) {
            diagnosticNeighborhoods++;
            diagnosticNeighborhoodCandidates += immutable.length;
            diagnosticMaxNeighborhood = Math.max(diagnosticMaxNeighborhood, immutable.length);
        }
        selectionNeighborhoods.put(neighborhoodKey, immutable);
        rememberSelectionNeighborhood(cx, cy, cz, immutable);
        return immutable;
    }

    private int[] withWideLights(int[] indexed, int cx, int cy, int cz) {
        var minX = cx * SELECTION_CELL_SIZE;
        var minY = cy * SELECTION_CELL_SIZE;
        var minZ = cz * SELECTION_CELL_SIZE;
        IntArrayList reaching = null;
        for (var idx : wideLights) {
            var offset = idx * 4;
            var radius = positionRadius[offset + 3];
            if (cx < selectionCell(positionRadius[offset] - radius)
                    || cx > selectionCell(positionRadius[offset] + radius)
                    || cy < selectionCell(positionRadius[offset + 1] - radius)
                    || cy > selectionCell(positionRadius[offset + 1] + radius)
                    || cz < selectionCell(positionRadius[offset + 2] - radius)
                    || cz > selectionCell(positionRadius[offset + 2] + radius)) {
                continue;
            }
            if (reaches(
                    idx,
                    minX,
                    minY,
                    minZ,
                    minX + SELECTION_CELL_SIZE,
                    minY + SELECTION_CELL_SIZE,
                    minZ + SELECTION_CELL_SIZE)) {
                if (reaching == null) {
                    reaching = new IntArrayList();
                }
                reaching.add(idx);
            }
        }
        if (reaching == null) {
            return indexed;
        }
        var merged = new int[indexed.length + reaching.size()];
        var left = 0;
        var right = 0;
        for (var i = 0; i < merged.length; i++) {
            merged[i] = right == reaching.size()
                            || left < indexed.length && compareSelectionOrder(indexed[left], reaching.get(right)) < 0
                    ? indexed[left++]
                    : reaching.get(right++);
        }
        return merged;
    }

    private boolean reaches(int lightIndex, float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        var offset = lightIndex * 4;
        var dx = axisDistance(positionRadius[offset], minX, maxX);
        var dy = axisDistance(positionRadius[offset + 1], minY, maxY);
        var dz = axisDistance(positionRadius[offset + 2], minZ, maxZ);
        var radius = positionRadius[offset + 3];
        return dx * dx + dy * dy + dz * dz <= radius * radius;
    }

    private void indexSelectionCells(int lightIndex, float px, float py, float pz, float radius) {
        var minX = selectionCell(px - radius);
        var minY = selectionCell(py - radius);
        var minZ = selectionCell(pz - radius);
        var maxX = selectionCell(px + radius);
        var maxY = selectionCell(py + radius);
        var maxZ = selectionCell(pz + radius);
        var radiusSq = radius * radius;
        for (int x = minX; x <= maxX; x++) {
            var cellMinX = x * SELECTION_CELL_SIZE;
            var dx = axisDistance(px, cellMinX, cellMinX + SELECTION_CELL_SIZE);
            for (int y = minY; y <= maxY; y++) {
                var cellMinY = y * SELECTION_CELL_SIZE;
                var dy = axisDistance(py, cellMinY, cellMinY + SELECTION_CELL_SIZE);
                for (int z = minZ; z <= maxZ; z++) {
                    var cellMinZ = z * SELECTION_CELL_SIZE;
                    var dz = axisDistance(pz, cellMinZ, cellMinZ + SELECTION_CELL_SIZE);
                    if (dx * dx + dy * dy + dz * dz <= radiusSq) {
                        selectionCells
                                .getIfAbsentPut(cellKey(x, y, z), IntArrayList::new)
                                .add(lightIndex);
                    }
                }
            }
        }
    }

    private void rememberSelectionNeighborhood(int cx, int cy, int cz, int[] neighborhood) {
        lastSelectionX = cx;
        lastSelectionY = cy;
        lastSelectionZ = cz;
        lastSelectionNeighborhood = neighborhood;
    }

    private void sortByColorSumDescending(int[] indices, int low, int high) {
        while (low < high) {
            var left = low;
            var right = high;
            var pivot = indices[(low + high) >>> 1];
            while (left <= right) {
                while (compareSelectionOrder(indices[left], pivot) < 0) {
                    left++;
                }
                while (compareSelectionOrder(indices[right], pivot) > 0) {
                    right--;
                }
                if (left <= right) {
                    var value = indices[left];
                    indices[left++] = indices[right];
                    indices[right--] = value;
                }
            }
            if (right - low < high - left) {
                if (low < right) {
                    sortByColorSumDescending(indices, low, right);
                }
                low = left;
            } else {
                if (left < high) {
                    sortByColorSumDescending(indices, left, high);
                }
                high = right;
            }
        }
    }

    private int compareSelectionOrder(int left, int right) {
        var byEnergy = Float.compare(colorSums[right], colorSums[left]);
        return byEnergy != 0 ? byEnergy : Integer.compare(left, right);
    }

    private static int findWeakest(float[] scores, int count) {
        if (count == MAX_LIGHTS) {
            var weakest = scores[1] < scores[0] ? 1 : 0;
            weakest = scores[2] < scores[weakest] ? 2 : weakest;
            return scores[3] < scores[weakest] ? 3 : weakest;
        }
        var weakest = 0;
        for (int i = 1; i < count; i++) {
            if (scores[i] < scores[weakest]) {
                weakest = i;
            }
        }
        return weakest;
    }

    private static int toCell(float v) {
        return (int) Math.floor(v / CELL_SIZE);
    }

    private static int selectionCell(float v) {
        return (int) Math.floor(v / SELECTION_CELL_SIZE);
    }

    private static long cellKey(int x, int y, int z) {
        var key = (x & 0xFFFFFL) | ((y & 0xFFFFFL) << 20) | ((z & 0xFFFFFL) << 40);
        key ^= key >>> 33;
        key *= 0xff51afd7ed558ccdL;
        key ^= key >>> 33;
        key *= 0xc4ceb9fe1a85ec53L;
        return key ^ key >>> 33;
    }

    private static float sq(float v) {
        return v * v;
    }

    private static float axisDistance(float value, float min, float max) {
        return value < min ? min - value : Math.max(value - max, 0f);
    }
}
