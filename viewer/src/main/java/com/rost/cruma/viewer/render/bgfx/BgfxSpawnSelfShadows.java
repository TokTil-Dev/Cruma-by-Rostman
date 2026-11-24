package com.rost.cruma.viewer.render.bgfx;

import com.rost.cruma.viewer.render.SpawnOverlayState.ModelInstance;
import com.rost.cruma.viewer.render.bgfx.BgfxViewAllocator.Domain;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.bgfx.BGFX;

final class BgfxSpawnSelfShadows implements AutoCloseable {
    static final int NEAR_TILE = 256;

    static final int NEAR_NPCS = 16;

    static final int FAR_TILE = 128;

    static final int FAR_NPCS = 64;

    static final int MAX_NPCS = NEAR_NPCS + FAR_NPCS;

    static final int GROUP = 8;

    static final int MAX_GROUPS = MAX_NPCS / GROUP;

    static final int REGION_WIDTH = 2_048;

    static final int REGION_HEIGHT = 1_024;

    static final float DISTANCE = 6_000f;

    static final float GROUND_REACH = 1_000f;

    private static final int NEAR_PER_SIDE = 4;

    private static final int FAR_PER_SIDE = 8;

    private static final float PADDING = 4f;

    private final BgfxDevice device;
    private final BgfxSpawnShadowLayer layer;
    private final float[] identity = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    private final float[] lightView = new float[16];
    private final float[] lightProjection = new float[16];
    private final float[] lightViewProjection = new float[16];
    private final float[] tileFromLight = new float[16];
    private final float[] transform = new float[16];
    private final float[] drawnSun = new float[3];
    private long frame;
    private long rebuiltFrame;
    private long drawnSignature;
    private int drawnCount = -1;
    private Snapshot snapshot = Snapshot.DISABLED;
    private long rebuilds;
    private long lastDraws;
    private int rebuildFrames = BgfxSpawnShadowLayer.MIN_REBUILD_FRAMES;

    BgfxSpawnSelfShadows(BgfxDevice device, BgfxSpawnShadowLayer layer) {
        this.device = device;
        this.layer = layer;
    }

    static float[] sunTowards(BgfxShadowRenderer.Snapshot shadows) {
        var matrix = shadows.lightViewProjections();
        return normalize(-matrix[2], -matrix[6], -matrix[10]);
    }

    static List<ModelInstance> findNearest(List<ModelInstance> instances, float eyeX, float eyeY, float eyeZ) {
        var near = new ArrayList<ModelInstance>();
        var limit = (double) DISTANCE * DISTANCE;
        for (var instance : instances) {
            if (instance.model().indices().length > 0 && distanceSquared(instance, eyeX, eyeY, eyeZ) <= limit) {
                near.add(instance);
            }
        }
        near.sort(Comparator.comparingDouble(instance -> distanceSquared(instance, eyeX, eyeY, eyeZ)));
        return near.size() > MAX_NPCS ? List.copyOf(near.subList(0, MAX_NPCS)) : near;
    }

    static List<ModelInstance> sortIntoGroups(List<ModelInstance> instances, float[][] axes) {
        var right = Comparator.<ModelInstance>comparingDouble(instance -> projectOnAxis(instance, axes[0]));
        var up = Comparator.<ModelInstance>comparingDouble(instance -> projectOnAxis(instance, axes[1]));
        var byRight = new ArrayList<>(instances);
        byRight.sort(right);
        var groups = (instances.size() + GROUP - 1) / GROUP;
        var columns = Math.max(1, (int) Math.round(Math.sqrt(groups)));
        var perColumn = ((groups + columns - 1) / columns) * GROUP;
        var result = new ArrayList<ModelInstance>(instances.size());
        for (var start = 0; start < byRight.size(); start += perColumn) {
            var column = new ArrayList<>(byRight.subList(start, Math.min(byRight.size(), start + perColumn)));
            column.sort(up);
            result.addAll(column);
        }
        return result;
    }

    static float[][] computeAxes(float[] sun) {
        var forward = normalize(-sun[0], -sun[1], -sun[2]);
        var referenceUp = Math.abs(forward[2]) > .94f ? new float[] {0f, 1f, 0f} : new float[] {0f, 0f, 1f};
        var right = normalize(cross(referenceUp, forward));
        var up = normalize(cross(forward, right));
        return new float[][] {right, up, forward};
    }

    Snapshot render(
            List<ModelInstance> instances, float eyeX, float eyeY, float eyeZ, float[] sun, BgfxViewAllocator views) {
        frame++;
        var nearest = findNearest(instances, eyeX, eyeY, eyeZ);
        if (nearest.isEmpty()) {
            forget();
            return snapshot;
        }
        var signature = BgfxSpawnShadowLayer.computeSignature(nearest);
        var same = snapshot.available()
                && signature == drawnSignature
                && nearest.size() == drawnCount
                && Arrays.equals(sun, drawnSun);
        if (same || snapshot.available() && frame - rebuiltFrame < rebuildFrames) {
            return snapshot;
        }
        var axes = computeAxes(sun);
        var nearCount = Math.min(NEAR_NPCS, nearest.size());
        var near = sortIntoGroups(nearest.subList(0, nearCount), axes);
        var far = sortIntoGroups(nearest.subList(nearCount, nearest.size()), axes);
        var ordered = new ArrayList<ModelInstance>(near);
        ordered.addAll(far);
        var view = views.allocate("spawn-self-shadows", Domain.RENDER, true);
        BGFX.bgfx_set_view_name(view, "spawn-self-shadows");
        BGFX.bgfx_set_view_frame_buffer(view, layer.getFramebuffer().getHandle());
        BGFX.bgfx_set_view_rect(view, 0, BgfxSpawnShadowLayer.RESOLUTION, REGION_WIDTH, REGION_HEIGHT);
        BGFX.bgfx_set_view_clear(view, BGFX.BGFX_CLEAR_DEPTH, 0, 1f, 0);
        BGFX.bgfx_set_view_mode(view, BGFX.BGFX_VIEW_MODE_SEQUENTIAL);
        BGFX.bgfx_set_view_transform(view, identity, identity);
        BGFX.bgfx_touch(view);
        var tiles = new HashMap<ModelInstance, Integer>();
        var matrices = new float[ordered.size() * 16];
        var params = new float[ordered.size() * 4];
        var areas = new float[ordered.size() * 4];
        var draws = 0;
        for (var tile = 0; tile < ordered.size(); tile++) {
            var instance = ordered.get(tile);
            var isNear = tile < nearCount;
            var slot = isNear ? tile : tile - nearCount;
            fit(instance, axes, isNear ? NEAR_TILE : FAR_TILE, tile, params, areas);
            System.arraycopy(lightViewProjection, 0, matrices, tile * 16, 16);
            updateTileMatrix(isNear, slot);
            params[tile * 4] = tileFromLight[12];
            params[tile * 4 + 1] = tileFromLight[13];
            BgfxShadowRenderer.multiply(tileFromLight, lightViewProjection, transform, 0);
            draws += layer.submitDepth(view, instance, transform);
            tiles.put(instance, tile);
        }
        System.arraycopy(sun, 0, drawnSun, 0, 3);
        drawnSignature = signature;
        drawnCount = nearest.size();
        rebuiltFrame = frame;
        rebuilds++;
        lastDraws = draws;
        snapshot = new Snapshot(
                true,
                layer.getDepth(),
                Map.copyOf(tiles),
                signature,
                nearCount,
                packBasis(axes),
                matrices,
                params,
                areas,
                computeGroups(areas, nearCount));
        return snapshot;
    }

    Snapshot getSnapshot() {
        return snapshot;
    }

    long getRebuilds() {
        return rebuilds;
    }

    long getLastDraws() {
        return lastDraws;
    }

    void setRebuildFrames(int frames) {
        rebuildFrames = frames;
    }

    void forget() {
        drawnCount = -1;
        snapshot = Snapshot.DISABLED;
    }

    @Override
    public void close() {
        forget();
    }

    record Snapshot(
            boolean available,
            @Nullable BgfxTexture depth,
            Map<ModelInstance, Integer> tiles,
            long signature,
            int nearCount,
            float[] basis,
            float[] matrices,
            float[] params,
            float[] areas,
            float[] groups) {
        static final Snapshot DISABLED = new Snapshot(
                false, null, Map.of(), 0, 0, new float[0], new float[0], new float[0], new float[0], new float[0]);

        float[] scale(int tile) {
            var size = tile < nearCount ? NEAR_TILE : FAR_TILE;
            return new float[] {(float) size / REGION_WIDTH, (float) size / REGION_HEIGHT};
        }
    }

    private void fit(ModelInstance instance, float[][] axes, int size, int tile, float[] params, float[] areas) {
        var right = axes[0];
        var up = axes[1];
        var forward = axes[2];
        var box = layer.getBounds(instance);
        BgfxSpawnModelRenderer.writeModelTransform(instance, transform);
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (var corner = 0; corner < 8; corner++) {
            var x = box[(corner & 1) == 0 ? 0 : 3];
            var y = box[(corner & 2) == 0 ? 1 : 4];
            var z = box[(corner & 4) == 0 ? 2 : 5];
            var world = new float[] {
                transform[0] * x + transform[4] * y + transform[8] * z + transform[12],
                transform[1] * x + transform[5] * y + transform[9] * z + transform[13],
                transform[2] * x + transform[6] * y + transform[10] * z + transform[14]
            };
            var lightX = dot(world, right);
            var lightY = dot(world, up);
            var lightZ = dot(world, forward);
            minX = Math.min(minX, lightX);
            maxX = Math.max(maxX, lightX);
            minY = Math.min(minY, lightY);
            maxY = Math.max(maxY, lightY);
            minZ = Math.min(minZ, lightZ);
            maxZ = Math.max(maxZ, lightZ);
        }
        var half = Math.max(maxX - minX, maxY - minY) * .5f + PADDING;
        var range = maxZ - minZ + PADDING * 2 + GROUND_REACH;
        BgfxShadowRenderer.setView(
                lightView, right, up, forward, -(minX + maxX) * .5f, -(minY + maxY) * .5f, -(minZ - PADDING));
        BgfxShadowRenderer.setOrthoZeroToOne(lightProjection, -half, half, -half, half, 0f, range);
        BgfxMatrices.adaptProjection(lightProjection, device.getCapabilities().homogeneousDepth());
        BgfxShadowRenderer.multiply(lightProjection, lightView, lightViewProjection, 0);
        params[tile * 4 + 2] = half * 2f / size;
        params[tile * 4 + 3] = 1f / range;
        areas[tile * 4] = (minX + maxX) * .5f;
        areas[tile * 4 + 1] = (minY + maxY) * .5f;
        areas[tile * 4 + 2] = 1f / half;
        areas[tile * 4 + 3] = minZ - PADDING;
    }

    private void updateTileMatrix(boolean isNear, int slot) {
        var size = isNear ? NEAR_TILE : FAR_TILE;
        var perSide = isNear ? NEAR_PER_SIDE : FAR_PER_SIDE;
        var left = (isNear ? 0 : NEAR_TILE * NEAR_PER_SIDE) + slot % perSide * size;
        var top = slot / perSide * size;
        Arrays.fill(tileFromLight, 0f);
        tileFromLight[0] = (float) size / REGION_WIDTH;
        tileFromLight[5] = (float) size / REGION_HEIGHT;
        tileFromLight[10] = 1f;
        tileFromLight[15] = 1f;
        tileFromLight[12] = -1f + (2f * left + size) / REGION_WIDTH;
        tileFromLight[13] = 1f - (2f * top + size) / REGION_HEIGHT;
    }

    private static double distanceSquared(ModelInstance instance, float x, float y, float z) {
        double dx = instance.x() - x;
        double dy = instance.y() - y;
        double dz = instance.z() + instance.model().height() * instance.scale() * .5 - z;
        return dx * dx + dy * dy + dz * dz;
    }

    private static double projectOnAxis(ModelInstance instance, float[] axis) {
        return instance.x() * axis[0] + instance.y() * axis[1] + instance.z() * axis[2];
    }

    private static float[] computeGroups(float[] areas, int nearCount) {
        var count = areas.length / 4;
        var result = new ArrayList<float[]>();
        for (var start = 0; start < count; ) {
            var end = Math.min(start < nearCount ? nearCount : count, start + GROUP);
            var square = new float[] {Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
            for (var tile = start; tile < end; tile++) {
                var half = 1f / areas[tile * 4 + 2];
                square[0] = Math.min(square[0], areas[tile * 4] - half);
                square[1] = Math.min(square[1], areas[tile * 4 + 1] - half);
                square[2] = Math.max(square[2], areas[tile * 4] + half);
                square[3] = Math.max(square[3], areas[tile * 4 + 1] + half);
            }
            result.add(new float[] {square[0], square[1], square[2], square[3], start, end, 0, 0});
            start = end;
        }
        var flat = new float[result.size() * 8];
        for (var group = 0; group < result.size(); group++) {
            System.arraycopy(result.get(group), 0, flat, group * 8, 8);
        }
        return flat;
    }

    private static float[] packBasis(float[][] axes) {
        var basis = new float[12];
        for (var axis = 0; axis < 3; axis++) {
            System.arraycopy(axes[axis], 0, basis, axis * 4, 3);
        }
        return basis;
    }

    private static float[] cross(float[] left, float[] right) {
        return new float[] {
            left[1] * right[2] - left[2] * right[1],
            left[2] * right[0] - left[0] * right[2],
            left[0] * right[1] - left[1] * right[0]
        };
    }

    private static float dot(float[] left, float[] right) {
        return left[0] * right[0] + left[1] * right[1] + left[2] * right[2];
    }

    private static float[] normalize(float[] value) {
        return normalize(value[0], value[1], value[2]);
    }

    private static float[] normalize(float x, float y, float z) {
        var length = (float) Math.sqrt(x * x + y * y + z * z);
        return length < 1e-7f ? new float[] {0f, 0f, 1f} : new float[] {x / length, y / length, z / length};
    }
}
