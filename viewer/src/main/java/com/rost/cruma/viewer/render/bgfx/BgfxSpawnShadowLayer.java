package com.rost.cruma.viewer.render.bgfx;

import com.rost.cruma.viewer.render.SpawnOverlayState;
import com.rost.cruma.viewer.render.SpawnOverlayState.ModelInstance;
import com.rost.cruma.viewer.render.api.RenderState;
import com.rost.cruma.viewer.render.bgfx.BgfxViewAllocator.Domain;
import com.rost.cruma.viewer.spawn.SpawnModelData;
import com.rost.cruma.viewer.spawn.SpawnModelData.Kind;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.bgfx.BGFX;
import org.lwjgl.system.MemoryStack;

final class BgfxSpawnShadowLayer implements AutoCloseable {
    static final int CASCADES = 2;

    static final int RESOLUTION = 2_048;

    static final int WIDTH = RESOLUTION * CASCADES;

    static final int HEIGHT = RESOLUTION + BgfxSpawnSelfShadows.REGION_HEIGHT;

    static final int MIN_REBUILD_FRAMES = 6;

    private static final float SECOND_CASCADE_REACH = BgfxSpawnSelfShadows.GROUND_REACH;

    private static final float MASKED_CUTOFF = .35f;

    private static final float AREA_PADDING = 2f / RESOLUTION;

    private static final int MAX_CACHED_BOUNDS = 1_024;

    private final BgfxDevice device;
    private final BgfxSpawnModelRenderer models;
    private final float[] identity = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    private final float[] projection = new float[16];
    private final float[] transform = new float[16];
    private BgfxTexture depth;
    private BgfxFramebuffer framebuffer;
    private BgfxProgram program;
    private BgfxUniform textureSampler;
    private BgfxUniform paramsUniform;
    private BgfxTexture white;

    @Nullable
    private BgfxShadowRenderer.Snapshot drawnSun;

    private long drawnModels;
    private long drawnExcluded;
    private int drawnCount = -1;
    private Snapshot snapshot = Snapshot.DISABLED;
    private long rebuilds;
    private long lastDraws;
    private long frame;
    private long rebuiltFrame;
    private int rebuildFrames = MIN_REBUILD_FRAMES;
    private final float[] areas = new float[CASCADES * 4];
    private final Map<String, float[]> bounds = new HashMap<>();

    BgfxSpawnShadowLayer(BgfxDevice device, BgfxSpawnModelRenderer models) {
        this.device = device;
        this.models = models;
    }

    static boolean isSupported(BgfxCapabilities capabilities) {
        return BgfxShadowRenderer.isSupported(capabilities) && capabilities.maxTextureSize() >= Math.max(WIDTH, HEIGHT);
    }

    static long computeSignature(List<ModelInstance> instances) {
        var sum = 0L;
        for (var instance : instances) {
            var hash = instance.model().key().hashCode() * 0x9E3779B97F4A7C15L;
            hash = mix(hash ^ Float.floatToIntBits(instance.x()));
            hash = mix(hash ^ Float.floatToIntBits(instance.y()));
            hash = mix(hash ^ Float.floatToIntBits(instance.z()));
            hash = mix(hash ^ Float.floatToIntBits(instance.yaw()));
            hash = mix(hash ^ Float.floatToIntBits(instance.scale()));
            if (instance.matrix() != null) {
                hash = mix(hash ^ Arrays.hashCode(instance.matrix()));
            }
            if (instance.pose() != null) {
                hash = mix(hash ^ System.identityHashCode(instance.pose().getVertices()));
            }
            sum += hash;
        }
        return sum;
    }

    boolean isUsable(SpawnOverlayState state, BgfxShadowRenderer.@Nullable Snapshot sun, RenderState rendering) {
        return !state.getShadowCasters().isEmpty()
                && sun != null
                && sun.available()
                && rendering.isCustomLightingEffective()
                && rendering.settings.isCustomSunShadowsEnabled()
                && sun.lightViewProjections().length >= CASCADES * 16
                && sun.splits().length >= CASCADES
                && isSupported(device.getCapabilities());
    }

    Snapshot render(
            SpawnOverlayState state,
            BgfxShadowRenderer.@Nullable Snapshot sun,
            RenderState rendering,
            BgfxViewAllocator views,
            Set<ModelInstance> excluded,
            long excludedSignature) {
        frame++;
        var instances = state.getShadowCasters();
        if (!isUsable(state, sun, rendering)) {
            forget();
            return snapshot;
        }
        var signature = computeSignature(instances);
        if (sun == drawnSun
                && excludedSignature == drawnExcluded
                && (signature == drawnModels && instances.size() == drawnCount
                        || frame - rebuiltFrame < rebuildFrames)) {
            return snapshot;
        }
        ensureResources();
        var draws = 0;
        for (var cascade = 0; cascade < CASCADES; cascade++) {
            var view = views.allocate("spawn-sun-shadow-" + cascade, Domain.RENDER, true);
            BGFX.bgfx_set_view_name(view, "spawn-sun-shadow-" + cascade);
            BGFX.bgfx_set_view_frame_buffer(view, framebuffer.getHandle());
            BGFX.bgfx_set_view_rect(view, cascade * RESOLUTION, 0, RESOLUTION, RESOLUTION);
            BGFX.bgfx_set_view_clear(view, BGFX.BGFX_CLEAR_DEPTH, 0, 1f, 0);
            BGFX.bgfx_set_view_mode(view, BGFX.BGFX_VIEW_MODE_SEQUENTIAL);
            System.arraycopy(sun.lightViewProjections(), cascade * 16, projection, 0, 16);
            BGFX.bgfx_set_view_transform(view, identity, projection);
            BGFX.bgfx_touch(view);
            var center = sun.cameraPosition();
            var nearSplit = sun.splits()[0];
            var area = cascade * 4;
            areas[area] = areas[area + 1] = Float.MAX_VALUE;
            areas[area + 2] = areas[area + 3] = -Float.MAX_VALUE;
            for (var instance : instances) {
                if (excluded.contains(instance)) {
                    continue;
                }
                if (cascade > 0
                        && Math.hypot(instance.x() - center[0], instance.y() - center[1])
                                < nearSplit - SECOND_CASCADE_REACH) {
                    continue;
                }
                var submitted = submit(view, instance);
                if (submitted > 0) {
                    cover(instance, area, sun.originBottomLeft());
                }
                draws += submitted;
            }
            areas[area] -= AREA_PADDING;
            areas[area + 1] -= AREA_PADDING;
            areas[area + 2] += AREA_PADDING;
            areas[area + 3] += AREA_PADDING;
        }
        drawnSun = sun;
        drawnModels = signature;
        drawnExcluded = excludedSignature;
        drawnCount = instances.size();
        rebuiltFrame = frame;
        rebuilds++;
        lastDraws = draws;
        snapshot = new Snapshot(true, depth, CASCADES, areas.clone(), BgfxSpawnSelfShadows.Snapshot.DISABLED);
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

    int submitDepth(int view, ModelInstance instance, float @Nullable [] clipFromWorld) {
        ensureResources();
        var model = instance.model();
        if (model.indices().length == 0 || model.vertices().length == 0) {
            return 0;
        }
        var resident = models.getResident(model);
        var draws = 0;
        for (var i = 0; i < resident.parts.size(); i++) {
            var part = resident.parts.get(i);
            if (part.indexCount() == 0 || part.kind() == Kind.BLENDED) {
                continue;
            }
            var texture = resident.textures.get(i);
            try (var stack = MemoryStack.stackPush()) {
                BgfxSpawnModelRenderer.writeModelTransform(instance, transform);
                if (clipFromWorld != null) {
                    BgfxShadowRenderer.multiply(clipFromWorld, transform.clone(), transform, 0);
                }
                var matrix = stack.malloc(64);
                matrix.asFloatBuffer().put(transform);
                BGFX.bgfx_set_transform(matrix);
                var masked = part.kind() == Kind.MASKED && texture != null;
                BGFX.bgfx_set_uniform(
                        paramsUniform.getHandle(), stack.floats(masked ? MASKED_CUTOFF : 0f, 0f, 0f, 0f), 1);
                BGFX.bgfx_set_texture(
                        0,
                        textureSampler.getHandle(),
                        texture == null ? white.getHandle() : texture.getHandle(),
                        0xffffffff);
                models.bindVertices(instance, resident);
                BGFX.bgfx_set_index_buffer(resident.indices.getHandle(), part.indexOffset(), part.indexCount());
                BGFX.bgfx_set_state(BGFX.BGFX_STATE_WRITE_Z | BGFX.BGFX_STATE_DEPTH_TEST_LESS, 0);
                BGFX.bgfx_submit(view, program.getHandle(), 0, BGFX.BGFX_DISCARD_ALL);
            }
            draws++;
        }
        return draws;
    }

    float[] getBounds(ModelInstance instance) {
        var model = instance.model();
        var box = bounds.get(model.key());
        if (box != null) {
            return box;
        }
        if (bounds.size() >= MAX_CACHED_BOUNDS) {
            bounds.clear();
        }
        box = new float[] {
            Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE
        };
        var vertices = model.vertices();
        for (var i = 0; i + 2 < vertices.length; i += SpawnModelData.FLOATS) {
            for (var axis = 0; axis < 3; axis++) {
                box[axis] = Math.min(box[axis], vertices[i + axis]);
                box[axis + 3] = Math.max(box[axis + 3], vertices[i + axis]);
            }
        }
        bounds.put(model.key(), box);
        return box;
    }

    BgfxFramebuffer getFramebuffer() {
        ensureResources();
        return framebuffer;
    }

    BgfxTexture getDepth() {
        ensureResources();
        return depth;
    }

    @Override
    public void close() {
        forget();
        for (var resource : new AutoCloseable[] {program, textureSampler, paramsUniform, white, framebuffer, depth}) {
            if (resource != null) {
                try {
                    resource.close();
                } catch (Exception failure) {
                    throw new IllegalStateException("failed to close the NPC shadow layer", failure);
                }
            }
        }
        program = null;
        textureSampler = null;
        paramsUniform = null;
        white = null;
        framebuffer = null;
        depth = null;
    }

    record Snapshot(
            boolean available,
            @Nullable BgfxTexture depth,
            int cascades,
            float[] areas,
            BgfxSpawnSelfShadows.Snapshot tiles) {
        static final Snapshot DISABLED =
                new Snapshot(false, null, 0, new float[0], BgfxSpawnSelfShadows.Snapshot.DISABLED);

        Snapshot withTiles(BgfxSpawnSelfShadows.Snapshot tiles) {
            return new Snapshot(available, depth, cascades, areas, tiles);
        }
    }

    private int submit(int view, ModelInstance instance) {
        return submitDepth(view, instance, null);
    }

    private void cover(ModelInstance instance, int area, boolean originBottomLeft) {
        var box = getBounds(instance);
        BgfxSpawnModelRenderer.writeModelTransform(instance, transform);
        for (var corner = 0; corner < 8; corner++) {
            var x = box[(corner & 1) == 0 ? 0 : 3];
            var y = box[(corner & 2) == 0 ? 1 : 4];
            var z = box[(corner & 4) == 0 ? 2 : 5];
            var worldX = transform[0] * x + transform[4] * y + transform[8] * z + transform[12];
            var worldY = transform[1] * x + transform[5] * y + transform[9] * z + transform[13];
            var worldZ = transform[2] * x + transform[6] * y + transform[10] * z + transform[14];
            var clipX = projection[0] * worldX + projection[4] * worldY + projection[8] * worldZ + projection[12];
            var clipY = projection[1] * worldX + projection[5] * worldY + projection[9] * worldZ + projection[13];
            var u = clipX * .5f + .5f;
            var v = originBottomLeft ? clipY * .5f + .5f : .5f - clipY * .5f;
            areas[area] = Math.min(areas[area], u);
            areas[area + 1] = Math.min(areas[area + 1], v);
            areas[area + 2] = Math.max(areas[area + 2], u);
            areas[area + 3] = Math.max(areas[area + 3], v);
        }
    }

    private void forget() {
        drawnSun = null;
        drawnCount = -1;
        snapshot = Snapshot.DISABLED;
    }

    private void ensureResources() {
        if (program != null) {
            return;
        }
        var resources = device.getResources();
        depth = BgfxTexture.create2D(
                resources,
                "spawn-sun-shadow/depth",
                WIDTH,
                HEIGHT,
                false,
                1,
                device.getCapabilities().depthStencilFormat(),
                BGFX.BGFX_TEXTURE_RT,
                null);
        framebuffer = BgfxFramebuffer.create(resources, "spawn-sun-shadow/framebuffer", List.of(depth));
        program = BgfxProgram.graphics(
                resources,
                "spawn-sun-shadow",
                BgfxShaderResources.load(device.getCapabilities(), "vs_spawn_model"),
                BgfxShaderResources.load(device.getCapabilities(), "fs_spawn_model_shadow"));
        textureSampler = BgfxUniform.create(resources, "s_spawnShadowTexture", BGFX.BGFX_UNIFORM_TYPE_SAMPLER, 1);
        paramsUniform = BgfxUniform.create(resources, "u_spawnShadowParams", BGFX.BGFX_UNIFORM_TYPE_VEC4, 1);
        white = BgfxSpawnModelRenderer.whiteTexture(resources, "spawn-sun-shadow/white");
    }

    private static long mix(long value) {
        value = (value ^ value >>> 33) * 0xFF51AFD7ED558CCDL;
        value = (value ^ value >>> 33) * 0xC4CEB9FE1A85EC53L;
        return value ^ value >>> 33;
    }
}
