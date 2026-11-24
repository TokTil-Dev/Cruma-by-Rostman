package com.rost.cruma.viewer.render.bgfx;

import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_MAG_POINT;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_MIN_POINT;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_U_CLAMP;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_V_CLAMP;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_FORMAT_RGBA32F;
import static org.lwjgl.bgfx.BGFX.BGFX_UNIFORM_TYPE_SAMPLER;
import static org.lwjgl.bgfx.BGFX.BGFX_UNIFORM_TYPE_VEC4;
import static org.lwjgl.bgfx.BGFX.bgfx_set_texture;
import static org.lwjgl.bgfx.BGFX.bgfx_set_uniform;

import com.rost.cruma.viewer.render.Camera;
import com.rost.cruma.viewer.render.api.RenderState;
import glm_.vec3.Vec3;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Arrays;
import org.apache.logging.log4j.LogManager;

final class BgfxGlobalIllumination implements AutoCloseable {
    static final int X = 12, Y = 12, Z = 6, PROBES = X * Y * Z;
    static final int WIDTH = X * Y, HEIGHT = Z * 12, FLOATS = WIDTH * HEIGHT * 4;
    static final int LEVELS = 4, PARAM_FLOATS = 24 + BgfxLocalLightSet.PARAM_FLOATS;
    static final float SPACING = 512f;
    static final int BATCH = 54, BOUNCES = 3;
    static final double BLEND_SECONDS = 0.3;
    private final BgfxResourceRegistry resources;
    private final BgfxMetalRayQueries rays;
    private Arena arena;
    private MemorySegment milliseconds;
    private BgfxTexture texture, previousTexture;
    private BgfxUniform sampler, previousSampler, uniform;
    private final float[] grid = new float[LEVELS * 16];
    private final Field[] fields = new Field[LEVELS];
    private int active = -1, pending = -1, nextLevel, batchSize = 16;
    private boolean failed;

    BgfxGlobalIllumination(BgfxResourceRegistry resources, BgfxMetalRayQueries rays) {
        this.resources = resources;
        this.rays = rays;
        for (int i = 0; i < LEVELS; i++) {
            fields[i] = new Field();
        }
        sampler = BgfxUniform.create(resources, "s_customGi", BGFX_UNIFORM_TYPE_SAMPLER, 1);
        try {
            previousSampler = BgfxUniform.create(resources, "s_customGiPrevious", BGFX_UNIFORM_TYPE_SAMPLER, 1);
            uniform = BgfxUniform.create(resources, "u_customGi", BGFX_UNIFORM_TYPE_VEC4, LEVELS * 4);
        } catch (RuntimeException | Error e) {
            if (previousSampler != null) {
                previousSampler.close();
            }
            sampler.close();
            throw e;
        }
    }

    private static final class Field {
        final float[] desired = new float[PARAM_FLOATS], submitted = new float[PARAM_FLOATS];
        final float[] published = new float[PARAM_FLOATS], previous = new float[PARAM_FLOATS];
        MemorySegment output, packed;
        boolean ready, previousReady, complete, sceneReady;
        int batch;
        long revision = -1, capturedRevision = -1, blendStarted, updated;
        double gpuMillis;
    }

    static int nextBatchSize(int previous, float milliseconds) {
        if (!Float.isFinite(milliseconds) || milliseconds <= 0) {
            return 8;
        }
        int target = (int) Math.floor(previous * 2f / milliseconds);
        return Math.clamp(Math.min(previous + 4, target), 8, BATCH);
    }

    static float spacing(int level) {
        return SPACING * (1 << (level * 2));
    }

    static float blend(long now, long started) {
        if (started == 0) {
            return 1;
        }
        float t = Math.clamp((float) ((now - started) / (BLEND_SECONDS * 1e9)), 0, 1);
        return t * t * (3 - 2 * t);
    }

    static float gridOrigin(float camera, int count) {
        return gridOrigin(camera, count, SPACING);
    }

    static float gridOrigin(float camera, int count, float spacing) {
        return (float) Math.floor(camera / spacing) * spacing - (count / 2 - 1) * spacing;
    }

    static boolean isSameField(float[] a, float[] b) {
        for (int i = 0; i < a.length; i++) {
            if (i >= 16 && i < 20) {
                continue;
            }
            if (Math.abs(a[i] - b[i]) > .0001f) {
                return false;
            }
        }
        return true;
    }

    void update(
            Camera camera,
            RenderState rendering,
            BgfxFrameEnvironment.Snapshot environment,
            BgfxLocalLightSet.Snapshot localLights) {
        Arrays.fill(grid, 0);
        rendering.stats.customGiActive = false;
        if (failed || !rendering.stats.customRayTracingAvailable || rays == null || rays.getGiSession() == null) {
            rendering.stats.customGiStatus = "GI unavailable. Using Realistic Relighting";
            return;
        }
        try {
            allocate();
            long now = System.nanoTime();
            var session = rays.getGiSession();
            if (pending >= 0) {
                var field = fields[pending];
                if (session.readGi(field.output, FLOATS, milliseconds)) {
                    pending = -1;
                    field.batch += (int) field.submitted[19];
                    rendering.stats.customGiGpuMillis = (float) milliseconds.get(JAVA_DOUBLE, 0);
                    field.gpuMillis += rendering.stats.customGiGpuMillis;
                    batchSize = nextBatchSize((int) field.submitted[19], rendering.stats.customGiGpuMillis);
                    if (field.batch == PROBES) {
                        field.complete = true;
                        active = -1;
                    }
                }
            }
            for (int level = 0; level < LEVELS; level++) {
                var field = fields[level];
                if (field.complete && blend(now, field.blendStarted) >= 1) {
                    publish(level, now, rendering);
                }
                request(field.desired, level, camera, environment, localLights);
            }
            if (active < 0 && pending < 0) {
                for (int offset = 0; offset < LEVELS; offset++) {
                    int level = (nextLevel + offset) % LEVELS;
                    var field = fields[level];
                    boolean dynamicRefresh = rays.hasPendingGiMovement() && now - field.updated > 1_000_000_000L;
                    if (!field.complete
                            && (!field.ready
                                    || !isSameField(field.desired, field.published)
                                    || field.revision != rays.getGiRevision()
                                    || dynamicRefresh)) {
                        active = level;
                        nextLevel = (level + 1) % LEVELS;
                        System.arraycopy(field.desired, 0, field.submitted, 0, PARAM_FLOATS);
                        field.batch = 0;
                        field.gpuMillis = 0;
                        field.sceneReady = false;
                        rays.captureGiMovement();
                        break;
                    }
                }
            }
            if (active >= 0 && pending < 0) {
                var field = fields[active];
                if (!field.sceneReady) {
                    field.sceneReady = rays.prepareGi(field.submitted[16], field.submitted[17]);
                    if (field.sceneReady) {
                        field.capturedRevision = rays.getGiRevision();
                    }
                }
                if (field.sceneReady) {
                    field.submitted[18] = field.batch;
                    field.submitted[19] = Math.min(batchSize, PROBES - field.batch);
                    try (var parameters = Arena.ofConfined()) {
                        if (session.dispatchGi(parameters.allocateFrom(JAVA_FLOAT, field.submitted))) {
                            pending = active;
                            rendering.stats.customGiBatches++;
                        } else {
                            field.batch = 0;
                            field.gpuMillis = 0;
                            field.sceneReady = false;
                        }
                    }
                }
            }
            int readyCount = 0;
            float minimumBlend = 1;
            for (int level = 0; level < LEVELS; level++) {
                var field = fields[level];
                if (!field.ready) {
                    continue;
                }
                int base = level * 16;
                System.arraycopy(field.published, 0, grid, base, 4);
                grid[base + 5] = 1;
                grid[base + 6] = blend(now, field.blendStarted);
                grid[base + 7] = field.previousReady ? 1 : 0;
                System.arraycopy(field.previous, 0, grid, base + 8, 4);
                minimumBlend = Math.min(minimumBlend, grid[base + 6]);
                readyCount++;
            }
            grid[60] = readyCount > 0 ? 1 : 0;
            grid[61] = rendering.settings.getCustomGiStrength();
            rendering.stats.customGiActive = readyCount > 0;
            rendering.stats.customGiBlend = readyCount == 0 ? 0 : minimumBlend;
            rendering.stats.customGiStatus = readyCount == 0
                    ? "Preparing GI materials / scene…"
                    : "GI active · "
                            + readyCount
                            + "/4 volumes · 3 diffuse bounces"
                            + (active >= 0 || readyCount < LEVELS || minimumBlend < 1 ? " · updating…" : "");
            rendering.stats.customGiMaterialBytes = rays.getGiMaterialBytes();
            rendering.stats.customGiPreparedGeometries = rays.getGiPreparedGeometries();
        } catch (RuntimeException failure) {
            failed = true;
            rendering.stats.customGiStatus = "GI unavailable. Using Realistic Relighting";
            LogManager.getLogger(BgfxGlobalIllumination.class)
                    .warn("Global illumination is off after a failure", failure);
        }
    }

    void bind(BgfxTexture fallback, boolean enabled) {
        if (!enabled) {
            Arrays.fill(grid, 0);
        }
        bgfx_set_texture(
                13,
                sampler.getHandle(),
                texture == null ? fallback.getHandle() : texture.getHandle(),
                BGFX_SAMPLER_U_CLAMP | BGFX_SAMPLER_V_CLAMP | BGFX_SAMPLER_MIN_POINT | BGFX_SAMPLER_MAG_POINT);
        bgfx_set_texture(
                14,
                previousSampler.getHandle(),
                previousTexture == null ? fallback.getHandle() : previousTexture.getHandle(),
                BGFX_SAMPLER_U_CLAMP | BGFX_SAMPLER_V_CLAMP | BGFX_SAMPLER_MIN_POINT | BGFX_SAMPLER_MAG_POINT);
        bgfx_set_uniform(uniform.getHandle(), grid, LEVELS * 4);
    }

    @Override
    public void close() {
        if (texture != null) {
            texture.close();
        }
        if (previousTexture != null) {
            previousTexture.close();
        }
        previousSampler.close();
        uniform.close();
        sampler.close();
        if (arena != null) {
            arena.close();
        }
    }

    private void allocate() {
        if (arena != null) {
            return;
        }
        arena = Arena.ofConfined();
        milliseconds = arena.allocate(JAVA_DOUBLE);
        for (var field : fields) {
            field.output = arena.allocate(JAVA_FLOAT, FLOATS);
            field.packed = arena.allocate(JAVA_FLOAT, FLOATS);
        }
        texture = BgfxTexture.create2D(
                resources,
                "gi/irradiance-moments",
                WIDTH,
                HEIGHT * LEVELS,
                false,
                1,
                BGFX_TEXTURE_FORMAT_RGBA32F,
                0,
                null);
        previousTexture = BgfxTexture.create2D(
                resources,
                "gi/previous-irradiance-moments",
                WIDTH,
                HEIGHT * LEVELS,
                false,
                1,
                BGFX_TEXTURE_FORMAT_RGBA32F,
                0,
                null);
    }

    private void publish(int level, long now, RenderState rendering) {
        var field = fields[level];
        field.previousReady = field.ready;
        System.arraycopy(field.published, 0, field.previous, 0, PARAM_FLOATS);
        if (field.ready) {
            previousTexture.update2D(0, 0, 0, level * HEIGHT, WIDTH, HEIGHT, field.packed.asByteBuffer(), 0xffff);
        }
        for (int i = 0; i < FLOATS; i++) {
            float value = field.output.getAtIndex(JAVA_FLOAT, i);
            if (!Float.isFinite(value)) {
                throw new IllegalStateException("Non-finite GI output");
            }
            int probe = i / 48, face = (i % 48) / 4;
            int texel = (probe / WIDTH * 12 + face) * WIDTH + probe % WIDTH;
            field.packed.setAtIndex(JAVA_FLOAT, texel * 4L + i % 4, value);
        }
        texture.update2D(0, 0, 0, level * HEIGHT, WIDTH, HEIGHT, field.packed.asByteBuffer(), 0xffff);
        System.arraycopy(field.submitted, 0, field.published, 0, PARAM_FLOATS);
        rendering.stats.customGiUpdateGpuMillis = (float) field.gpuMillis;
        field.blendStarted = now;
        field.updated = now;
        field.ready = true;
        field.complete = false;
        field.revision = field.capturedRevision;
    }

    private static void request(
            float[] request,
            int level,
            Camera camera,
            BgfxFrameEnvironment.Snapshot environment,
            BgfxLocalLightSet.Snapshot lights) {
        var p = camera.getPosition();
        float spacing = spacing(level);
        request[0] = gridOrigin(p.getX(), X, spacing);
        request[1] = gridOrigin(p.getY(), Y, spacing);
        request[2] = gridOrigin(p.getZ(), Z, spacing);
        request[3] = spacing;
        var sun = environment.sunDirection();
        request[4] = sun.getX();
        request[5] = sun.getY();
        request[6] = sun.getZ();
        request[7] = 160_000f;
        putLinearEnergy(environment.staticMeshSun(), request, 8);
        putLinearEnergy(environment.staticMeshAmbient(), request, 12);
        float white = Math.max(
                1f, Math.max(request[8] + request[12], Math.max(request[9] + request[13], request[10] + request[14])));
        for (int c = 0; c < 3; c++) {
            request[8 + c] /= white;
            request[12 + c] /= white;
        }
        request[16] = p.getX();
        request[17] = p.getY();
        request[20] = BOUNCES;
        Arrays.fill(request, 24, request.length, 0);
        request[21] = lights.lightCount();
        if (lights.available()) {
            System.arraycopy(lights.params(), 0, request, 24, lights.params().length);
        }
    }

    private static void putLinearEnergy(Vec3 color, float[] target, int offset) {
        float peak = Math.max(0, Math.max(color.getX(), Math.max(color.getY(), color.getZ())));
        target[offset] = peak == 0 ? 0 : BgfxGiMaterial.srgbToLinear(color.getX() / peak) * peak;
        target[offset + 1] = peak == 0 ? 0 : BgfxGiMaterial.srgbToLinear(color.getY() / peak) * peak;
        target[offset + 2] = peak == 0 ? 0 : BgfxGiMaterial.srgbToLinear(color.getZ() / peak) * peak;
    }
}
