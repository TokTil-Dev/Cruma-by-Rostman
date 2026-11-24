package com.rost.cruma.viewer.render.bgfx;

import com.rost.cruma.viewer.render.Camera;
import com.rost.cruma.viewer.render.LightGlow;
import com.rost.cruma.viewer.render.api.RenderExtents.Extent;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.bgfx.BGFX;
import org.lwjgl.system.MemoryStack;

final class BgfxLightGlowPass implements AutoCloseable {
    private static final int CLAMP_POINT = BGFX.BGFX_SAMPLER_U_CLAMP
            | BGFX.BGFX_SAMPLER_V_CLAMP
            | BGFX.BGFX_SAMPLER_MIN_POINT
            | BGFX.BGFX_SAMPLER_MAG_POINT;

    private static final float ALL_AROUND = 2;

    private final BgfxDevice device;
    private final float[] projection = new float[16];
    private final float[] inverse = new float[16];
    private final float[] view = new float[16];
    private @Nullable BgfxProgram program;
    private @Nullable BgfxFullscreenGeometry fullscreen;
    private @Nullable BgfxUniform inverseUniform;
    private @Nullable BgfxUniform lightUniform;
    private @Nullable BgfxUniform axisUniform;
    private @Nullable BgfxUniform colorUniform;
    private @Nullable BgfxUniform frameUniform;
    private @Nullable BgfxUniform depthSampler;
    private @Nullable BgfxUniform modelDepthSampler;

    BgfxLightGlowPass(BgfxDevice device) {
        this.device = device;
    }

    static boolean invert(float[] m, float[] out) {
        var a = new double[16];
        for (var i = 0; i < 16; i++) {
            a[i] = m[i];
        }
        var inv = new double[16];
        inv[0] = a[5] * a[10] * a[15]
                - a[5] * a[11] * a[14]
                - a[9] * a[6] * a[15]
                + a[9] * a[7] * a[14]
                + a[13] * a[6] * a[11]
                - a[13] * a[7] * a[10];
        inv[4] = -a[4] * a[10] * a[15]
                + a[4] * a[11] * a[14]
                + a[8] * a[6] * a[15]
                - a[8] * a[7] * a[14]
                - a[12] * a[6] * a[11]
                + a[12] * a[7] * a[10];
        inv[8] = a[4] * a[9] * a[15]
                - a[4] * a[11] * a[13]
                - a[8] * a[5] * a[15]
                + a[8] * a[7] * a[13]
                + a[12] * a[5] * a[11]
                - a[12] * a[7] * a[9];
        inv[12] = -a[4] * a[9] * a[14]
                + a[4] * a[10] * a[13]
                + a[8] * a[5] * a[14]
                - a[8] * a[6] * a[13]
                - a[12] * a[5] * a[10]
                + a[12] * a[6] * a[9];
        inv[1] = -a[1] * a[10] * a[15]
                + a[1] * a[11] * a[14]
                + a[9] * a[2] * a[15]
                - a[9] * a[3] * a[14]
                - a[13] * a[2] * a[11]
                + a[13] * a[3] * a[10];
        inv[5] = a[0] * a[10] * a[15]
                - a[0] * a[11] * a[14]
                - a[8] * a[2] * a[15]
                + a[8] * a[3] * a[14]
                + a[12] * a[2] * a[11]
                - a[12] * a[3] * a[10];
        inv[9] = -a[0] * a[9] * a[15]
                + a[0] * a[11] * a[13]
                + a[8] * a[1] * a[15]
                - a[8] * a[3] * a[13]
                - a[12] * a[1] * a[11]
                + a[12] * a[3] * a[9];
        inv[13] = a[0] * a[9] * a[14]
                - a[0] * a[10] * a[13]
                - a[8] * a[1] * a[14]
                + a[8] * a[2] * a[13]
                + a[12] * a[1] * a[10]
                - a[12] * a[2] * a[9];
        inv[2] = a[1] * a[6] * a[15]
                - a[1] * a[7] * a[14]
                - a[5] * a[2] * a[15]
                + a[5] * a[3] * a[14]
                + a[13] * a[2] * a[7]
                - a[13] * a[3] * a[6];
        inv[6] = -a[0] * a[6] * a[15]
                + a[0] * a[7] * a[14]
                + a[4] * a[2] * a[15]
                - a[4] * a[3] * a[14]
                - a[12] * a[2] * a[7]
                + a[12] * a[3] * a[6];
        inv[10] = a[0] * a[5] * a[15]
                - a[0] * a[7] * a[13]
                - a[4] * a[1] * a[15]
                + a[4] * a[3] * a[13]
                + a[12] * a[1] * a[7]
                - a[12] * a[3] * a[5];
        inv[14] = -a[0] * a[5] * a[14]
                + a[0] * a[6] * a[13]
                + a[4] * a[1] * a[14]
                - a[4] * a[2] * a[13]
                - a[12] * a[1] * a[6]
                + a[12] * a[2] * a[5];
        inv[3] = -a[1] * a[6] * a[11]
                + a[1] * a[7] * a[10]
                + a[5] * a[2] * a[11]
                - a[5] * a[3] * a[10]
                - a[9] * a[2] * a[7]
                + a[9] * a[3] * a[6];
        inv[7] = a[0] * a[6] * a[11]
                - a[0] * a[7] * a[10]
                - a[4] * a[2] * a[11]
                + a[4] * a[3] * a[10]
                + a[8] * a[2] * a[7]
                - a[8] * a[3] * a[6];
        inv[11] = -a[0] * a[5] * a[11]
                + a[0] * a[7] * a[9]
                + a[4] * a[1] * a[11]
                - a[4] * a[3] * a[9]
                - a[8] * a[1] * a[7]
                + a[8] * a[3] * a[5];
        inv[15] = a[0] * a[5] * a[10]
                - a[0] * a[6] * a[9]
                - a[4] * a[1] * a[10]
                + a[4] * a[2] * a[9]
                + a[8] * a[1] * a[6]
                - a[8] * a[2] * a[5];
        var det = a[0] * inv[0] + a[1] * inv[4] + a[2] * inv[8] + a[3] * inv[12];
        if (Math.abs(det) < 1e-30 || !Double.isFinite(det)) {
            return false;
        }
        for (var i = 0; i < 16; i++) {
            out[i] = (float) (inv[i] / det);
        }
        return true;
    }

    void submit(
            int view,
            Camera camera,
            Extent extent,
            BgfxTexture sceneDepth,
            @Nullable BgfxTexture modelDepth,
            LightGlow glow,
            float pixelScale) {
        create();
        var capabilities = device.getCapabilities();
        BgfxMatrices.toBackendProjection(camera.getProjectionMatrix(), capabilities.homogeneousDepth(), projection);
        if (!invert(projection, inverse)) {
            return;
        }
        var source = camera.getViewMatrix().duplicate();
        source.position(0).limit(16);
        source.get(this.view, 0, 16);
        var m = this.view;
        var lx = m[0] * glow.x() + m[4] * glow.y() + m[8] * glow.z() + m[12];
        var ly = m[1] * glow.x() + m[5] * glow.y() + m[9] * glow.z() + m[13];
        var lz = m[2] * glow.x() + m[6] * glow.y() + m[10] * glow.z() + m[14];
        var ax = m[0] * glow.axisX() + m[4] * glow.axisY() + m[8] * glow.axisZ();
        var ay = m[1] * glow.axisX() + m[5] * glow.axisY() + m[9] * glow.axisZ();
        var az = m[2] * glow.axisX() + m[6] * glow.axisY() + m[10] * glow.axisZ();
        var length = Math.sqrt(ax * ax + ay * ay + az * az);
        if (length > 1e-9) {
            ax /= length;
            ay /= length;
            az /= length;
        }
        try (var stack = MemoryStack.stackPush()) {
            BGFX.bgfx_set_uniform(inverseUniform.getHandle(), stack.floats(inverse), 1);
            BGFX.bgfx_set_uniform(
                    lightUniform.getHandle(), stack.floats((float) lx, (float) ly, (float) lz, glow.reach()), 1);
            BGFX.bgfx_set_uniform(
                    axisUniform.getHandle(),
                    stack.floats((float) ax, (float) ay, (float) az, glow.isSpot() ? glow.threshold() : ALL_AROUND),
                    1);
            BGFX.bgfx_set_uniform(
                    colorUniform.getHandle(), stack.floats(glow.red(), glow.green(), glow.blue(), glow.tint()), 1);
            BGFX.bgfx_set_uniform(
                    frameUniform.getHandle(),
                    stack.floats(
                            capabilities.originBottomLeft() ? 1 : 0,
                            capabilities.homogeneousDepth() ? 1 : 0,
                            modelDepth != null ? 1 : 0,
                            pixelScale),
                    1);
        }
        BGFX.bgfx_set_texture(0, depthSampler.getHandle(), sceneDepth.getHandle(), CLAMP_POINT);
        if (modelDepth != null) {
            BGFX.bgfx_set_texture(1, modelDepthSampler.getHandle(), modelDepth.getHandle(), CLAMP_POINT);
        }
        fullscreen.bind();
        BGFX.bgfx_set_state(
                BGFX.BGFX_STATE_WRITE_RGB
                        | BGFX.BGFX_STATE_BLEND_FUNC(BGFX.BGFX_STATE_BLEND_ONE, BGFX.BGFX_STATE_BLEND_INV_SRC_ALPHA),
                0);
        BGFX.bgfx_submit(view, program.getHandle(), 0, BGFX.BGFX_DISCARD_ALL);
    }

    @Override
    public void close() {
        for (var resource : new AutoCloseable[] {
            program,
            fullscreen,
            inverseUniform,
            lightUniform,
            axisUniform,
            colorUniform,
            frameUniform,
            depthSampler,
            modelDepthSampler
        }) {
            if (resource != null) {
                try {
                    resource.close();
                } catch (Exception ignored) {
                }
            }
        }
        program = null;
        fullscreen = null;
    }

    private void create() {
        if (program != null) {
            return;
        }
        var resources = device.getResources();
        var capabilities = device.getCapabilities();
        fullscreen = new BgfxFullscreenGeometry(resources, capabilities.originBottomLeft());
        program = BgfxProgram.graphics(
                resources,
                "editing/light-glow",
                BgfxShaderResources.load(capabilities, "vs_fullscreen"),
                BgfxShaderResources.load(capabilities, "fs_light_glow"));
        inverseUniform = BgfxUniform.create(resources, "u_glowInverse", BGFX.BGFX_UNIFORM_TYPE_MAT4, 1);
        lightUniform = BgfxUniform.create(resources, "u_glowLight", BGFX.BGFX_UNIFORM_TYPE_VEC4, 1);
        axisUniform = BgfxUniform.create(resources, "u_glowAxis", BGFX.BGFX_UNIFORM_TYPE_VEC4, 1);
        colorUniform = BgfxUniform.create(resources, "u_glowColor", BGFX.BGFX_UNIFORM_TYPE_VEC4, 1);
        frameUniform = BgfxUniform.create(resources, "u_glowFrame", BGFX.BGFX_UNIFORM_TYPE_VEC4, 1);
        depthSampler = BgfxUniform.create(resources, "s_glowDepth", BGFX.BGFX_UNIFORM_TYPE_SAMPLER, 1);
        modelDepthSampler = BgfxUniform.create(resources, "s_glowModelDepth", BGFX.BGFX_UNIFORM_TYPE_SAMPLER, 1);
    }
}
