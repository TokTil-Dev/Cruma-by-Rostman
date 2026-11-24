package com.rost.cruma.viewer.render.bgfx;

import static org.lwjgl.bgfx.BGFX.BGFX_CLEAR_COLOR;
import static org.lwjgl.bgfx.BGFX.BGFX_CLEAR_DEPTH;
import static org.lwjgl.bgfx.BGFX.BGFX_DISCARD_ALL;
import static org.lwjgl.bgfx.BGFX.BGFX_INVALID_HANDLE;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_MAG_POINT;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_MIN_POINT;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_MIP_POINT;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_U_CLAMP;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_V_CLAMP;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_BLEND_DST_COLOR;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_BLEND_FACTOR;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_BLEND_FUNC;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_BLEND_INV_FACTOR;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_BLEND_INV_SRC_ALPHA;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_BLEND_INV_SRC_COLOR;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_BLEND_ONE;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_BLEND_SRC_ALPHA;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_BLEND_SRC_COLOR;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_BLEND_ZERO;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_DEPTH_TEST_LESS;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_MSAA;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_WRITE_A;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_WRITE_RGB;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_WRITE_Z;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_FORMAT_RGBA8;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_RT;
import static org.lwjgl.bgfx.BGFX.BGFX_UNIFORM_TYPE_SAMPLER;
import static org.lwjgl.bgfx.BGFX.BGFX_UNIFORM_TYPE_VEC4;
import static org.lwjgl.bgfx.BGFX.BGFX_VIEW_MODE_SEQUENTIAL;
import static org.lwjgl.bgfx.BGFX.bgfx_set_index_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_set_state;
import static org.lwjgl.bgfx.BGFX.bgfx_set_texture;
import static org.lwjgl.bgfx.BGFX.bgfx_set_uniform;
import static org.lwjgl.bgfx.BGFX.bgfx_set_vertex_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_clear;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_frame_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_mode;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_name;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_rect;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_transform;
import static org.lwjgl.bgfx.BGFX.bgfx_submit;
import static org.lwjgl.bgfx.BGFX.bgfx_touch;

import com.rost.cruma.core.env.LobbyTimeEnv;
import com.rost.cruma.core.env.ResolvedEnv;
import com.rost.cruma.core.env.TimeEnvParser;
import com.rost.cruma.core.env.TimeEnvPreset;
import com.rost.cruma.core.env.WorldTimeEnv;
import com.rost.cruma.core.texture.DxtMipCodec.Options;
import com.rost.cruma.core.world.Entity.BlendMode;
import com.rost.cruma.core.world.Entity.EntityMaterial;
import com.rost.cruma.core.world.Entity.EntityMesh;
import com.rost.cruma.core.world.Entity.Texture;
import com.rost.cruma.core.world.Entity.UvAnimation;
import com.rost.cruma.core.world.LocalSky;
import com.rost.cruma.core.world.SkyRules;
import com.rost.cruma.core.world.SkyZones;
import com.rost.cruma.core.world.UMap;
import com.rost.cruma.core.world.USky;
import com.rost.cruma.core.world.UnrealLoader;
import com.rost.cruma.core.world.WaterPlane;
import com.rost.cruma.core.world.WorldMapCatalog;
import com.rost.cruma.core.world.WorldTile;
import com.rost.cruma.core.world.ZoneRender;
import com.rost.cruma.core.world.ZoneStateChoice;
import com.rost.cruma.viewer.config.Config.Viewer.World;
import com.rost.cruma.viewer.render.Camera;
import com.rost.cruma.viewer.render.ParticleEmitter;
import com.rost.cruma.viewer.render.ParticleEmitter.Kind;
import com.rost.cruma.viewer.render.RenderingContext;
import com.rost.cruma.viewer.render.api.RenderExtents.Extent;
import com.rost.cruma.viewer.render.api.RenderFrameSnapshot.RenderFamily;
import com.rost.cruma.viewer.render.api.RenderState;
import com.rost.cruma.viewer.render.bgfx.BgfxFrameEnvironment.Snapshot;
import com.rost.cruma.viewer.render.bgfx.BgfxTextureCache.TextureRef;
import com.rost.cruma.viewer.render.bgfx.BgfxViewAllocator.Domain;
import glm_.quat.Quat;
import glm_.vec3.Vec3;
import glm_.vec4.Vec4;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.BufferUtils;
import org.lwjgl.bgfx.BGFX;

final class BgfxSkyRenderer implements AutoCloseable {
    private static final Vec3 DEFAULT_ZENITH = new Vec3(0.30f, 0.45f, 0.75f);
    private static final Vec3 DEFAULT_HORIZON = new Vec3(0.65f, 0.72f, 0.82f);
    private static final Vec3 DEFAULT_SUN = new Vec3(1.0f, 0.95f, 0.85f);
    private static final float FALLBACK_RADIUS = 300f;

    private static final Logger log = LogManager.getLogger(BgfxSkyRenderer.class);
    private final BgfxDevice device;
    private final RenderingContext renderingContext;
    private final RenderState state;
    private final BgfxTextureCache textures;
    private final BgfxPerformanceTracker performance;
    private final FloatBuffer projectionBuffer = BufferUtils.createFloatBuffer(16);
    private final BgfxFullscreenGeometry gradientGeometry;
    private final BgfxProgram gradientProgram;
    private final BgfxProgram meshProgram;
    private final BgfxProgram spriteProgram;
    private final BgfxUniform sampler;
    private final BgfxUniform skyParamsUniform;
    private final BgfxBuffer spriteQuad;
    private final List<TimeEnvPreset> presets = new ArrayList<>();
    private final float[] skyParams = new float[24];
    private final float[] flareDirection = new float[3];
    private float moonSize = 300f;
    private float flareBaseSize = 420f;
    private float flareIntensity = 0.5f;
    private USky defaultSky;
    private LocalSkyScene selectedLocalSky;
    private final Map<WorldTile, LocalSkyScene> localSkies = new HashMap<>();
    private final SkySet shared = new SkySet();
    private final SkySet local = new SkySet();
    private BgfxTexture blendColor;
    private BgfxTexture blendDepth;
    private BgfxFramebuffer blendTarget;
    private BgfxFullscreenGeometry blendGeometry;
    private BgfxProgram blendProgram;
    private BgfxUniform blendSampler;
    private BgfxUniform blendScale;
    private final float[] blendScaleValue = {1f, 1f, 0f, 0f};
    private BgfxProgram particleProgram;
    private long residencyRevision;
    private boolean closed;

    BgfxSkyRenderer(
            BgfxDevice device,
            RenderingContext renderingContext,
            RenderState state,
            BgfxTextureCache textures,
            BgfxPerformanceTracker performance) {
        this.device = device;
        this.renderingContext = renderingContext;
        this.state = state;
        this.textures = textures;
        this.performance = performance;
        BgfxFullscreenGeometry createdGradientGeometry = null;
        BgfxProgram createdGradient = null;
        BgfxProgram createdMesh = null;
        BgfxProgram createdSprite = null;
        BgfxUniform createdSampler = null;
        BgfxUniform createdParams = null;
        BgfxBuffer createdQuad = null;
        try {
            createdGradientGeometry = new BgfxFullscreenGeometry(
                    device.getResources(), device.getCapabilities().originBottomLeft());
            createdGradient = BgfxProgram.graphics(
                    device.getResources(),
                    "sky/gradient",
                    BgfxShaderResources.load(device.getCapabilities(), "vs_sky_gradient"),
                    BgfxShaderResources.load(device.getCapabilities(), "fs_sky_gradient"));
            createdMesh = BgfxProgram.graphics(
                    device.getResources(),
                    "sky/mesh",
                    BgfxShaderResources.load(device.getCapabilities(), "vs_sky_mesh"),
                    BgfxShaderResources.load(device.getCapabilities(), "fs_sky_mesh"));
            createdSprite = BgfxProgram.graphics(
                    device.getResources(),
                    "sky/sprite",
                    BgfxShaderResources.load(device.getCapabilities(), "vs_sky_sprite"),
                    BgfxShaderResources.load(device.getCapabilities(), "fs_sky_sprite"));
            createdSampler = BgfxUniform.create(device.getResources(), "s_skyTexture", BGFX_UNIFORM_TYPE_SAMPLER, 1);
            createdParams = BgfxUniform.create(device.getResources(), "u_skyParams", BGFX_UNIFORM_TYPE_VEC4, 6);
            createdQuad = BgfxBuffer.staticVertex(
                    device.getResources(), "sky/sprite-quad", spriteQuad(), BgfxSceneVertexLayouts.FX_QUAD, 0);
        } catch (RuntimeException | Error e) {
            closeAfterFailure(createdQuad, e);
            closeAfterFailure(createdParams, e);
            closeAfterFailure(createdSampler, e);
            closeAfterFailure(createdSprite, e);
            closeAfterFailure(createdMesh, e);
            closeAfterFailure(createdGradient, e);
            closeAfterFailure(createdGradientGeometry, e);
            throw e;
        }
        gradientGeometry = createdGradientGeometry;
        gradientProgram = createdGradient;
        meshProgram = createdMesh;
        spriteProgram = createdSprite;
        sampler = createdSampler;
        skyParamsUniform = createdParams;
        spriteQuad = createdQuad;
    }

    static long skyStateFor(BlendMode blend, boolean depthWrite, boolean underwaterModifier) {
        var state = BGFX_STATE_WRITE_RGB | BGFX_STATE_WRITE_A | BGFX_STATE_DEPTH_TEST_LESS | BGFX_STATE_MSAA;
        if (depthWrite) {
            state |= BGFX_STATE_WRITE_Z;
        }
        return state
                | switch (blend) {
                    case OPAQUE, MASKED, MASKED_BLEND, UNLIT, INVISIBLE ->
                        underwaterModifier
                                ? BGFX_STATE_BLEND_FUNC(BGFX_STATE_BLEND_SRC_ALPHA, BGFX_STATE_BLEND_INV_SRC_ALPHA)
                                : 0L;
                    case ADDITIVE -> BGFX_STATE_BLEND_FUNC(BGFX_STATE_BLEND_ONE, BGFX_STATE_BLEND_ONE);
                    case BRIGHTEN -> BGFX_STATE_BLEND_FUNC(BGFX_STATE_BLEND_SRC_ALPHA, BGFX_STATE_BLEND_ONE);
                    case MODULATE -> BGFX_STATE_BLEND_FUNC(BGFX_STATE_BLEND_DST_COLOR, BGFX_STATE_BLEND_SRC_COLOR);
                    case SCREEN -> BGFX_STATE_BLEND_FUNC(BGFX_STATE_BLEND_ONE, BGFX_STATE_BLEND_INV_SRC_COLOR);
                    case DARKEN -> BGFX_STATE_BLEND_FUNC(BGFX_STATE_BLEND_ZERO, BGFX_STATE_BLEND_INV_SRC_COLOR);
                    case TRANSLUCENT, EMISSIVE ->
                        BGFX_STATE_BLEND_FUNC(BGFX_STATE_BLEND_SRC_ALPHA, BGFX_STATE_BLEND_INV_SRC_ALPHA);
                };
    }

    static float skyAlphaCutoff(BlendMode blend, int alphaRef) {
        return SkyRules.alphaCutoff(blend, alphaRef);
    }

    void load(World worldConfig) {
        requireOpen();
        presets.clear();
        var lobby = LobbyTimeEnv.files(worldConfig.clientPath(), renderingContext.getStandaloneMap());
        var files = lobby.isEmpty() ? WorldTimeEnv.files(worldConfig.clientPath()) : lobby;
        for (var file : files) {
            try {
                presets.add(TimeEnvParser.parse(file));
            } catch (RuntimeException e) {
                log.debug("Sky {} unavailable: {}", file.getFileName(), e.toString());
            }
        }
        var rendering = worldConfig.rendering();
        moonSize = (float) rendering.realSkyMoonSize();
        flareBaseSize = (float) rendering.realSkyFlareBaseSize();
        flareIntensity = (float) rendering.realSkyFlareIntensity();
        try {
            var loader = new UnrealLoader(
                    worldConfig.clientPath(),
                    new Options(rendering.dxtMipCodecEnabled(), rendering.dxtMipMinSize()),
                    false,
                    rendering.textureQuality());
            var sky = loader.loadSky("SkyLevel");
            if (sky != null) {
                defaultSky = sky;
                install(shared, sky);
                log.info(
                        "Loaded real sky: {} layers, {} moons, {} flares",
                        shared.layers.size(),
                        shared.moons.size(),
                        shared.flares.size());
            } else if (!WorldMapCatalog.hasSharedSky(worldConfig.clientPath())) {
                log.info("The client has no SkyLevel map: each map draws its own sky");
            }
        } catch (RuntimeException e) {
            log.warn("Real sky unavailable; using gradient: {}", e.toString());
        }
    }

    void registerLocalSky(UMap map) {
        if (map.localSky() != null && map.localSky().sky() != null) {
            localSkies.put(
                    WorldTile.ofMap(
                            map.name(), map.position().getX(), map.position().getY()),
                    LocalSkyScene.of(map.localSky()));
        }
    }

    void evictMaps(Set<String> owners) {
        localSkies.keySet().removeIf(tile -> owners.contains(tile.x() + "_" + tile.y()));
    }

    long getResidencyRevision() {
        return residencyRevision;
    }

    int renderMain(
            BgfxFramebuffer target,
            Extent extent,
            BgfxViewAllocator views,
            float elapsedSeconds,
            boolean enabled,
            Snapshot environment) {
        if (!enabled || extent.isZero() || renderingContext.camera.isOrtho() || renderingContext.skyHidden) {
            return 0;
        }
        selectSky();
        var colors = resolveColors(environment);
        var zone = state.settings.getSkyZone();
        var overlay = getSharedOverlay(zone);
        var draws = renderSet(
                baseSet(zone), target, extent, views, elapsedSeconds, null, colors, zone.showSun(), environment);
        if (overlay > 0f) {
            draws += renderSharedOverlay(
                    overlay, extent, views, elapsedSeconds, colors, zone.showSun(), target, environment);
        }
        clearSkyDepth(target, extent, views);
        return draws;
    }

    int renderReflection(
            BgfxFramebuffer target,
            Extent extent,
            BgfxViewAllocator views,
            float elapsedSeconds,
            WaterPlane plane,
            Snapshot environment) {
        selectSky();
        var colors = resolveColors(environment);
        var zone = state.settings.getSkyZone();
        var set = getSharedOverlay(zone) >= 0.5f ? shared : baseSet(zone);
        var draws = renderSet(set, target, extent, views, elapsedSeconds, plane, colors, zone.showSun(), environment);
        clearSkyDepth(target, extent, views);
        return draws;
    }

    void collectTextures(Set<TextureRef> target) {
        for (var set : List.of(shared, local)) {
            if (set.sun != null) {
                target.add(set.sun);
            }
            target.addAll(set.moons);
            for (var particle : set.skyParticles) {
                target.add(particle.texture());
            }
            for (var flare : set.flares) {
                target.add(flare.texture());
            }
            for (var layer : set.layers) {
                if (layer.texture != null) {
                    target.add(layer.texture);
                }
            }
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        RuntimeException failure = null;
        try {
            clearSky(local);
            clearSky(shared);
        } catch (RuntimeException e) {
            failure = e;
        }
        failure = closeAndMerge(blendTarget, failure);
        failure = closeAndMerge(blendColor, failure);
        failure = closeAndMerge(blendDepth, failure);
        failure = closeAndMerge(blendProgram, failure);
        failure = closeAndMerge(blendScale, failure);
        failure = closeAndMerge(blendSampler, failure);
        failure = closeAndMerge(blendGeometry, failure);
        failure = closeAndMerge(spriteQuad, failure);
        failure = closeAndMerge(skyParamsUniform, failure);
        failure = closeAndMerge(sampler, failure);
        failure = closeAndMerge(particleProgram, failure);
        failure = closeAndMerge(spriteProgram, failure);
        failure = closeAndMerge(meshProgram, failure);
        failure = closeAndMerge(gradientProgram, failure);
        failure = closeAndMerge(gradientGeometry, failure);
        if (failure != null) {
            throw failure;
        }
    }

    private void selectSky() {
        var camera = renderingContext.camera.getPosition();
        var local = localSkies.get(WorldTile.at(camera.getX(), camera.getY()));
        if (local == selectedLocalSky) {
            return;
        }
        selectedLocalSky = local;
        if (local == null) {
            clearSky(this.local);
            return;
        }
        install(this.local, local.sky());
        for (var sprite : local.sprites()) {
            sprite.emitter().resetSimulation();
            this.local.skyParticles.add(new SkyParticle(
                    sprite.emitter(), sprite.blend(), textures.load(sprite.sprite(), "sky/particle/" + sprite.name())));
        }
        log.info(
                "Local sky selected: layers={} spriteEmitters={} ownSky={}",
                this.local.layers.size(),
                this.local.skyParticles.size(),
                local.ownSky());
    }

    private SkySet baseSet(SkyZones.View zone) {
        if (selectedLocalSky == null) {
            return shared;
        }
        if (!zone.found()) {
            return selectedLocalSky.ownSky() ? local : shared;
        }
        return zone.sharedWeight() >= 1f ? shared : local;
    }

    private float getSharedOverlay(SkyZones.View zone) {
        if (selectedLocalSky == null || !zone.found() || zone.sharedWeight() >= 1f) {
            return 0f;
        }
        return Math.max(0, Math.min(255, (int) (zone.sharedWeight() * 255f))) / 255f;
    }

    private int renderSet(
            SkySet set,
            BgfxFramebuffer target,
            Extent extent,
            BgfxViewAllocator views,
            float elapsedSeconds,
            WaterPlane reflectionPlane,
            SkyColors colors,
            boolean showSun,
            Snapshot environment) {
        var draws = set.layers.isEmpty()
                ? renderGradient(target, extent, views, colors.skyBox, colors.haze, reflectionPlane)
                : renderLayers(set, target, extent, views, elapsedSeconds, reflectionPlane, colors);
        draws += renderSkyParticles(set, target, extent, views, elapsedSeconds, reflectionPlane);
        if (showSun) {
            draws += renderCelestials(set, target, extent, views, colors.env, reflectionPlane, environment);
        }
        return draws;
    }

    private int renderSharedOverlay(
            float overlay,
            Extent extent,
            BgfxViewAllocator views,
            float elapsedSeconds,
            SkyColors colors,
            boolean showSun,
            BgfxFramebuffer target,
            Snapshot environment) {
        ensureBlendTarget(extent);
        var clear = allocateView(views, "sky-zone-clear", blendTarget, extent);
        bgfx_set_view_clear(clear, BGFX_CLEAR_COLOR | BGFX_CLEAR_DEPTH, 0x000000ff, 1f, 0);
        bgfx_touch(clear);
        var draws = renderSet(shared, blendTarget, extent, views, elapsedSeconds, null, colors, showSun, environment);
        var view = allocateView(views, "sky-zone-blend", target, extent);
        blendGeometry.bind();
        bgfx_set_texture(
                0,
                blendSampler.getHandle(),
                blendColor.getHandle(),
                BGFX_SAMPLER_U_CLAMP
                        | BGFX_SAMPLER_V_CLAMP
                        | BGFX_SAMPLER_MIN_POINT
                        | BGFX_SAMPLER_MAG_POINT
                        | BGFX_SAMPLER_MIP_POINT);
        bgfx_set_uniform(blendScale.getHandle(), blendScaleValue, 1);
        var alpha = Math.round(overlay * 255f) & 0xFF;
        bgfx_set_state(
                BGFX_STATE_WRITE_RGB | BGFX_STATE_BLEND_FUNC(BGFX_STATE_BLEND_FACTOR, BGFX_STATE_BLEND_INV_FACTOR),
                alpha << 24 | alpha << 16 | alpha << 8 | alpha);
        bgfx_submit(view, blendProgram.getHandle(), 0, BGFX_DISCARD_ALL);
        performance.addDraw(RenderFamily.SKY, false, false, 1, 2);
        return draws + 1;
    }

    private void ensureBlendTarget(Extent extent) {
        if (blendProgram == null) {
            blendGeometry = new BgfxFullscreenGeometry(
                    device.getResources(), device.getCapabilities().originBottomLeft());
            blendSampler = BgfxUniform.create(device.getResources(), "s_source", BGFX_UNIFORM_TYPE_SAMPLER, 1);
            blendScale = BgfxUniform.create(device.getResources(), "u_easuCon0", BGFX_UNIFORM_TYPE_VEC4, 1);
            blendProgram = BgfxProgram.graphics(
                    device.getResources(),
                    "sky/zone-blend",
                    BgfxShaderResources.load(device.getCapabilities(), "vs_fullscreen"),
                    BgfxShaderResources.load(device.getCapabilities(), "fs_copy"));
        }
        if (blendColor != null
                && blendColor.getDescriptor().width() == extent.width()
                && blendColor.getDescriptor().height() == extent.height()) {
            return;
        }
        RuntimeException failure = closeAndMerge(blendTarget, null);
        failure = closeAndMerge(blendColor, failure);
        failure = closeAndMerge(blendDepth, failure);
        blendTarget = null;
        blendColor = null;
        blendDepth = null;
        if (failure != null) {
            throw failure;
        }
        blendColor = BgfxTexture.create2D(
                device.getResources(),
                "sky/zone-blend-color",
                extent.width(),
                extent.height(),
                false,
                1,
                BGFX_TEXTURE_FORMAT_RGBA8,
                BGFX_TEXTURE_RT,
                null);
        blendDepth = BgfxTexture.create2D(
                device.getResources(),
                "sky/zone-blend-depth",
                extent.width(),
                extent.height(),
                false,
                1,
                device.getCapabilities().depthStencilFormat(),
                BGFX_TEXTURE_RT,
                null);
        blendTarget = BgfxFramebuffer.create(device.getResources(), "sky/zone-blend", List.of(blendColor, blendDepth));
    }

    private int renderGradient(
            BgfxFramebuffer target,
            Extent extent,
            BgfxViewAllocator views,
            Vec3 zenith,
            Vec3 horizon,
            WaterPlane reflectionPlane) {
        var camera = renderingContext.camera;
        var forward = camera.getForwardDir();
        var right = camera.getRightDir();
        var up = cameraUp(right, forward);
        Arrays.fill(skyParams, 0f);
        put3(0, forward);
        skyParams[3] = reflectionPlane == null ? 0f : 1f;
        put3(1, right);
        skyParams[7] = (float) Math.tan(Math.toRadians(camera.getFov()) * 0.5);
        put3(2, up);
        skyParams[11] = extent.height() == 0 ? 1f : (float) extent.width() / extent.height();
        if (camera.getLens().stock()) {
            var projection = camera.getSkyProjectionMatrix();
            skyParams[7] = 1f / projection.get(5);
            skyParams[11] = projection.get(5) / projection.get(0);
        }
        put3(3, zenith);
        put3(4, horizon);
        put3(
                5,
                reflectionPlane == null
                        ? new Vec3(0f, 0f, 1f)
                        : new Vec3(reflectionPlane.x(), reflectionPlane.y(), reflectionPlane.z()));
        var view = allocateView(views, "sky-gradient", target, extent);
        gradientGeometry.bind();
        bgfx_set_uniform(skyParamsUniform.getHandle(), skyParams, 6);
        bgfx_set_state(BGFX_STATE_WRITE_RGB | BGFX_STATE_WRITE_A, 0);
        bgfx_submit(view, gradientProgram.getHandle(), 0, BGFX_DISCARD_ALL);
        performance.addDraw(RenderFamily.SKY, reflectionPlane != null, false, 1, 2);
        return 1;
    }

    private int renderLayers(
            SkySet set,
            BgfxFramebuffer target,
            Extent extent,
            BgfxViewAllocator views,
            float elapsedSeconds,
            WaterPlane reflectionPlane,
            SkyColors colors) {
        var view = allocateView(views, reflectionPlane == null ? "sky-real" : "sky-reflection", target, extent);
        var camera = renderingContext.camera;
        bgfx_set_view_transform(
                view,
                firstSixteenFloats(camera.getViewMatrix()),
                BgfxMatrices.toBackendProjection(
                        reflectionPlane == null
                                ? camera.getSkyProjectionMatrix()
                                : camera.getReflectionProjectionMatrix(),
                        device.getCapabilities().homogeneousDepth(),
                        projectionBuffer));
        var draws = 0;
        var events = state.settings.getGameEventStates();
        var zoneState = events == null ? ZoneStateChoice.EMPTY : events.zoneStates();
        var origin = set.origin;
        for (var layer : set.layers) {
            if (!layer.zoneRender.shows(zoneState)) {
                continue;
            }
            var center = reflectionPlane == null ? camera.getPosition() : reflectionPlane.reflect(camera.getPosition());
            Arrays.fill(skyParams, 0f);
            skyParams[0] = center.getX() - origin.getX();
            skyParams[1] = center.getY() - origin.getY();
            skyParams[2] = center.getZ() - origin.getZ();
            skyParams[3] = reflectionPlane == null ? 0f : 1f;
            if (reflectionPlane != null) {
                var plane = reflectionPlane.orientedToward(camera.getPosition());
                skyParams[4] = plane.getX();
                skyParams[5] = plane.getY();
                skyParams[6] = plane.getZ();
                skyParams[7] = plane.getW();
            }
            var tint = tintFor(layer.name, layer.cloudIndex, colors, layer.authoredTint);
            var underwater = colors.underwaterModifier;
            skyParams[8] = tint.getX() * (underwater == null ? 1f : underwater.getX());
            skyParams[9] = tint.getY() * (underwater == null ? 1f : underwater.getY());
            skyParams[10] = tint.getZ() * (underwater == null ? 1f : underwater.getZ());
            skyParams[11] = 1f;
            var animation = layer.uvAnimation;
            skyParams[12] = animation.mode();
            skyParams[13] = elapsedSeconds;
            skyParams[14] = animation.paramX();
            skyParams[15] = animation.paramY();
            skyParams[16] = animation.paramZ();
            skyParams[17] = animation.paramW();
            skyParams[18] = layer.texture == null ? 0f : 1f;
            skyParams[19] = usesAlpha(layer.blend) || layer.alphaCutoff >= 0f ? 1f : 0f;
            skyParams[20] = underwater == null ? 1f : underwater.getW();
            skyParams[21] = layer.alphaCutoff;
            bgfx_set_vertex_buffer(0, layer.vertices.getHandle(), 0, layer.vertexCount);
            bgfx_set_index_buffer(layer.indices.getHandle(), layer.indexOffset, layer.indexCount);
            var texture = layer.texture == null ? textures.getWhite() : layer.texture;
            bgfx_set_texture(0, sampler.getHandle(), texture.getTexture().getHandle(), texture.getSamplerFlags());
            bgfx_set_uniform(skyParamsUniform.getHandle(), skyParams, 6);
            bgfx_set_state(skyState(layer, underwater != null), 0);
            bgfx_submit(view, meshProgram.getHandle(), 0, BGFX_DISCARD_ALL);
            performance.addDraw(RenderFamily.SKY, reflectionPlane != null, false, 1, layer.indexCount / 3L);
            draws++;
        }
        return draws;
    }

    private int renderCelestials(
            SkySet set,
            BgfxFramebuffer target,
            Extent extent,
            BgfxViewAllocator views,
            ResolvedEnv env,
            WaterPlane reflectionPlane,
            Snapshot environment) {
        var sun = set.sun;
        var moons = set.moons;
        if (sun == null && moons.isEmpty()) {
            return 0;
        }
        var hour = state.settings.getTimeOfDay();
        var camera = renderingContext.camera;
        var sprites = new ArrayList<CelestialSprite>();
        var sunOffset = celestialOffset(hour, true, set.sunRadius);
        if (sun != null && sunOffset != null) {
            var color = env == null ? DEFAULT_SUN : env.sunColor();
            addSprite(
                    sprites,
                    sun,
                    camera.getPosition().plus(sunOffset),
                    state.settings.getRealSkySunSize() * (env == null ? 1f : env.sunScale()) * set.sunDrawScale,
                    color,
                    1f);
            if (sunOffset.getZ() > 0f) {
                addFlares(set, sprites, camera, sunOffset, color, set.sunRadius);
            }
        }
        var moonOffset = celestialOffset(hour, false, set.moonRadius);
        if (moonOffset != null && !moons.isEmpty()) {
            var moonIndex = selectMoonIndex(set, environment);
            addSprite(
                    sprites,
                    moons.get(moonIndex),
                    camera.getPosition().plus(moonOffset),
                    moonSize * (env == null ? 1f : env.moonScale()) * set.moonDrawScale,
                    new Vec3(1f),
                    1f);
        }
        if (sprites.isEmpty()) {
            return 0;
        }
        var reflected = reflectionPlane != null;
        var view = allocateView(views, reflected ? "sky-celestial-reflection" : "sky-celestial", target, extent);
        bgfx_set_view_transform(
                view,
                firstSixteenFloats(camera.getViewMatrix()),
                BgfxMatrices.toBackendProjection(
                        reflected ? camera.getReflectionProjectionMatrix() : camera.getSkyProjectionMatrix(),
                        device.getCapabilities().homogeneousDepth(),
                        projectionBuffer));
        var right = camera.getRightDir();
        var up = cameraUp(right, camera.getForwardDir());
        if (reflected) {
            right = reflectionPlane.reflectDirection(right);
            up = reflectionPlane.reflectDirection(up);
        }
        for (var sprite : sprites) {
            var position = reflected ? reflectionPlane.reflect(sprite.position) : sprite.position;
            Arrays.fill(skyParams, 0f);
            put3(0, right);
            put3(1, up);
            skyParams[8] = position.getX();
            skyParams[9] = position.getY();
            skyParams[10] = position.getZ();
            skyParams[11] = sprite.size;
            skyParams[12] = sprite.color.getX();
            skyParams[13] = sprite.color.getY();
            skyParams[14] = sprite.color.getZ();
            skyParams[15] = sprite.alpha;
            bgfx_set_uniform(skyParamsUniform.getHandle(), skyParams, 6);
            bgfx_set_vertex_buffer(0, spriteQuad.getHandle(), 0, spriteQuad.getCapacityElements());
            bgfx_set_texture(
                    0, sampler.getHandle(), sprite.texture.getTexture().getHandle(), sprite.texture.getSamplerFlags());
            bgfx_set_state(
                    BGFX_STATE_WRITE_RGB
                            | BGFX_STATE_WRITE_A
                            | BGFX_STATE_BLEND_FUNC(BGFX_STATE_BLEND_ONE, BGFX_STATE_BLEND_ONE),
                    0);
            bgfx_submit(view, spriteProgram.getHandle(), 0, BGFX_DISCARD_ALL);
            performance.addDraw(RenderFamily.SKY, reflected, false, 1, 2);
        }
        return sprites.size();
    }

    private void install(SkySet set, USky sky) {
        clearSky(set);
        set.origin = sky.origin();
        for (var layer : sky.drawLayers()) {
            if (sky.isLayerActive(layer)) {
                var uploaded = uploadLayer(set, layer, sky.cloudIndex(layer.name()));
                if (uploaded != null) {
                    set.layers.add(uploaded);
                }
            }
        }
        if (sky.sun() != null) {
            set.sun = textures.load(sky.sun().sprite(), "sky/sun");
            set.sunDrawScale = sky.sun().drawScale();
            set.sunRadius = sky.sun().radius() > 0f ? sky.sun().radius() : FALLBACK_RADIUS;
        }
        set.moonEnvTypes = new int[sky.moons().size()];
        for (var index = 0; index < sky.moons().size(); index++) {
            var moon = sky.moons().get(index);
            set.moons.add(textures.load(moon.sprite(), "sky/moon-" + index));
            set.moonEnvTypes[index] = moon.envType();
            if (index == 0) {
                set.moonDrawScale = moon.drawScale();
                set.moonRadius = moon.radius() > 0f ? moon.radius() : FALLBACK_RADIUS;
            }
        }
        for (var index = 0; index < sky.flares().size(); index++) {
            var flare = sky.flares().get(index);
            set.flares.add(new Flare(
                    textures.load(flare.sprite(), "sky/flare-" + index),
                    flare.offset(),
                    flare.scale() > 0f ? flare.scale() : 1f));
        }
    }

    private Layer uploadLayer(SkySet set, USky.Layer layer, int cloudIndex) {
        if (!(layer.entity().getMesh() instanceof EntityMesh mesh)
                || mesh.vertices().size() < 3
                || mesh.indices().length < 3
                || mesh.surfaces().isEmpty()) {
            return null;
        }
        var surface = mesh.surfaces().getFirst();
        var vertices = ByteBuffer.allocateDirect(
                        Math.multiplyExact(mesh.vertices().size(), BgfxSceneVertexLayouts.SKY.getStride()))
                .order(ByteOrder.nativeOrder());
        var sourceVertices = mesh.vertexBuffer();
        for (var index = 0; index < sourceVertices.size(); index++) {
            vertices.putFloat(sourceVertices.getPositionX(index))
                    .putFloat(sourceVertices.getPositionY(index))
                    .putFloat(sourceVertices.getPositionZ(index))
                    .putFloat(sourceVertices.getUvX(index))
                    .putFloat(sourceVertices.getUvY(index));
        }
        vertices.flip();
        var indices =
                ByteBuffer.allocateDirect(mesh.indices().length * Integer.BYTES).order(ByteOrder.nativeOrder());
        indices.asIntBuffer().put(mesh.indices());
        BgfxBuffer vertexBuffer = null;
        BgfxBuffer indexBuffer = null;
        try {
            var label = "sky/layer-" + set.layers.size() + '-' + sanitize(layer.name());
            vertexBuffer = BgfxBuffer.staticVertex(
                    device.getResources(), label + "/vertices", vertices, BgfxSceneVertexLayouts.SKY, 0);
            indexBuffer = BgfxBuffer.staticIndex(device.getResources(), label + "/indices", indices, true, 0);
            var sourceTexture =
                    surface.material() == null ? null : surface.material().getTexture();
            var texture = sourceTexture == null ? null : textures.load(sourceTexture, label + "/texture");
            return new Layer(
                    layer.name(),
                    vertexBuffer,
                    indexBuffer,
                    mesh.vertices().size(),
                    surface.indexOffset(),
                    surface.indexCount(),
                    texture,
                    surface.blendMode(),
                    surface.uvAnimation(),
                    surface.depthWrite(),
                    skyAlphaCutoff(surface.blendMode(), surface.alphaRef()),
                    authoredTint(surface.material()),
                    cloudIndex,
                    layer.entity().getZoneRender());
        } catch (RuntimeException | Error e) {
            closeAfterFailure(indexBuffer, e);
            closeAfterFailure(vertexBuffer, e);
            throw e;
        }
    }

    private SkyColors resolveColors(Snapshot environment) {
        var env = environment == null ? null : environment.resolved();
        var underwater = environment == null ? null : environment.underwaterSkyModifier();
        if (env == null) {
            return new SkyColors(DEFAULT_ZENITH, DEFAULT_HORIZON, null, underwater);
        }
        return new SkyColors(env.skyBox(), env.haze(), env, underwater);
    }

    private void clearSkyDepth(BgfxFramebuffer target, Extent extent, BgfxViewAllocator views) {
        var view = allocateView(views, "sky-depth-reset", target, extent);
        bgfx_set_view_clear(view, BGFX_CLEAR_DEPTH, 0, 1f, 0);
        bgfx_touch(view);
    }

    private void addFlares(
            SkySet set, List<CelestialSprite> sprites, Camera camera, Vec3 sunOffset, Vec3 sunColor, float radius) {
        var sunNdc = camera.projectToNdc(camera.getPosition().plus(sunOffset));
        if (sunNdc == null) {
            return;
        }
        var tint = sunColor.times(flareIntensity);
        var cameraPosition = camera.getPosition();
        for (var flare : set.flares) {
            camera.writeCursorRayDirection(
                    -sunNdc.getX() * flare.offset, -sunNdc.getY() * flare.offset, flareDirection);
            addSprite(
                    sprites,
                    flare.texture,
                    new Vec3(
                            cameraPosition.getX() + flareDirection[0] * radius,
                            cameraPosition.getY() + flareDirection[1] * radius,
                            cameraPosition.getZ() + flareDirection[2] * radius),
                    flareBaseSize * flare.scale,
                    tint,
                    1f);
        }
    }

    private int renderSkyParticles(
            SkySet set,
            BgfxFramebuffer target,
            Extent extent,
            BgfxViewAllocator views,
            float elapsedSeconds,
            WaterPlane reflectionPlane) {
        var skyParticles = set.skyParticles;
        var origin = set.origin;
        if (skyParticles.isEmpty()) {
            return 0;
        }
        if (particleProgram == null) {
            particleProgram = BgfxProgram.graphics(
                    device.getResources(),
                    "sky/particles",
                    BgfxShaderResources.load(device.getCapabilities(), "vs_sky_particle"),
                    BgfxShaderResources.load(device.getCapabilities(), "fs_sky_particle"));
        }
        var dt = Float.isNaN(set.particleTime) ? 1f / 60f : Math.max(0f, elapsedSeconds - set.particleTime);
        set.particleTime = elapsedSeconds;
        var camera = renderingContext.camera;
        var view = allocateView(
                views, reflectionPlane == null ? "sky-particles" : "sky-particles-reflection", target, extent);
        bgfx_set_view_transform(
                view,
                firstSixteenFloats(camera.getViewMatrix()),
                BgfxMatrices.toBackendProjection(
                        reflectionPlane == null
                                ? camera.getSkyProjectionMatrix()
                                : camera.getReflectionProjectionMatrix(),
                        device.getCapabilities().homogeneousDepth(),
                        projectionBuffer));
        var spatial = new float[6];
        var count = 0;
        for (var source : skyParticles) {
            var emitter = source.emitter();
            if (dt > 0f) {
                emitter.update(dt);
            }
            for (var i = 0; i < emitter.getActiveParticleLimit(); i++) {
                if (!emitter.isActive(i)) {
                    continue;
                }
                emitter.writeSpriteSpatial(i, spatial, 0, 3);
                var local =
                        new Vec3(spatial[0] - origin.getX(), spatial[1] - origin.getY(), spatial[2] - origin.getZ());
                var center = camera.getPosition().plus(local);
                var right = camera.getRightDir();
                var up = cameraUp(right, camera.getForwardDir());
                var direction = new Vec3(spatial[3], spatial[4], spatial[5]);
                var width = emitter.getSpriteRenderScaleX(i);
                var height = emitter.getSpriteRenderScaleY(i);
                if (emitter.getDirectionUsage() == 4 && direction.length() > 0.0001f) {
                    var plane = emitter.getCardPlane();
                    up = plane.up();
                    right = plane.right();
                    width = ParticleEmitter.getCardWidth(width, height);
                    height *= plane.rise();
                } else if (emitter.getDirectionUsage() == 1 && direction.length() > 0.0001f) {
                    up = direction.normalize();
                    var candidate = up.cross(local.normalize());
                    if (candidate.length() > 0.0001f) {
                        right = candidate.normalize();
                    }
                }
                var angle = emitter.getPitch(i);
                var cosine = (float) Math.cos(angle);
                var sine = (float) Math.sin(angle);
                var x = right.times(cosine).plus(up.times(sine)).times(2f * width);
                var y = up.times(cosine).minus(right.times(sine)).times(2f * height);
                if (reflectionPlane != null) {
                    center = reflectionPlane.reflect(center);
                    x = reflectionPlane.reflectDirection(x);
                    y = reflectionPlane.reflectDirection(y);
                }
                Arrays.fill(skyParams, 0f);
                put3(0, x);
                put3(1, y);
                put3(2, center);
                skyParams[11] = 1f;
                skyParams[12] = emitter.getR(i);
                skyParams[13] = emitter.getG(i);
                skyParams[14] = emitter.getB(i);
                skyParams[15] = emitter.getA(i);
                skyParams[16] = emitter.getSubU0(i);
                skyParams[17] = emitter.getSubV0(i);
                skyParams[18] = emitter.getSubUScale();
                skyParams[19] = emitter.getSubVScale();
                skyParams[20] = emitter.isAlphaTest() ? emitter.getAlphaRef() / 255f : -1f;
                bgfx_set_uniform(skyParamsUniform.getHandle(), skyParams, 6);
                bgfx_set_vertex_buffer(0, spriteQuad.getHandle(), 0, spriteQuad.getCapacityElements());
                bgfx_set_texture(
                        0,
                        sampler.getHandle(),
                        source.texture().getTexture().getHandle(),
                        source.texture().getSamplerFlags());
                var state = BgfxSceneState.particleFx(source.blend());
                if (!emitter.isZTest()) {
                    state &= ~BGFX.BGFX_STATE_DEPTH_TEST_MASK;
                }
                if (emitter.isZWrite()) {
                    state |= BGFX.BGFX_STATE_WRITE_Z;
                }
                bgfx_set_state(state, 0);
                bgfx_submit(view, particleProgram.getHandle(), 0, BGFX_DISCARD_ALL);
                count++;
            }
        }
        performance.addDraw(RenderFamily.SKY, false, false, count, count * 2);
        return count;
    }

    private record SkyParticle(ParticleEmitter emitter, int blend, TextureRef texture) {}

    private record LocalSkyScene(USky sky, List<SkySprite> sprites, boolean ownSky) {
        static LocalSkyScene of(LocalSky local) {
            var sprites = new ArrayList<SkySprite>(local.sprites().size());
            for (var descriptor : local.sprites()) {
                var emitter = new ParticleEmitter(
                        descriptor.emitter(),
                        descriptor.actorPos(),
                        Kind.SPRITE,
                        descriptor.blend(),
                        descriptor.seed());
                emitter.setActorTransform(new Quat(descriptor.rotation().vector()), descriptor.scale());
                sprites.add(new SkySprite(emitter, descriptor.blend(), descriptor.sprite(), descriptor.name()));
            }
            return new LocalSkyScene(local.sky(), List.copyOf(sprites), local.ownSky());
        }
    }

    private record SkySprite(ParticleEmitter emitter, int blend, Texture sprite, String name) {}

    private int selectMoonIndex(SkySet set, Snapshot environment) {
        var requested = state.settings.getMoonVariant();
        if (requested > 0) {
            return Math.min(requested - 1, set.moons.size() - 1);
        }
        var moonEnvTypes = set.moonEnvTypes;
        var desiredEnv = environment != null && environment.zoneEnvType() >= 0
                ? environment.zoneEnvType()
                : presets.isEmpty()
                        ? 0
                        : presets.get(Math.max(0, Math.min(state.settings.getSkyPreset(), presets.size() - 1)))
                                .envType();
        var fallback = 0;
        for (var index = 0; index < moonEnvTypes.length; index++) {
            if (moonEnvTypes[index] == desiredEnv) {
                return index;
            }
            if (moonEnvTypes[index] == 0) {
                fallback = index;
            }
        }
        return fallback;
    }

    private void put3(int vector, Vec3 value) {
        var offset = vector * 4;
        skyParams[offset] = value.getX();
        skyParams[offset + 1] = value.getY();
        skyParams[offset + 2] = value.getZ();
    }

    private void clearSky(SkySet set) {
        residencyRevision++;
        RuntimeException failure = null;
        for (var particle : set.skyParticles) {
            failure = closeAndMerge(particle.texture(), failure);
        }
        set.skyParticles.clear();
        set.particleTime = Float.NaN;
        for (var index = set.layers.size() - 1; index >= 0; index--) {
            failure = closeAndMerge(set.layers.get(index), failure);
        }
        for (var index = set.moons.size() - 1; index >= 0; index--) {
            failure = closeAndMerge(set.moons.get(index), failure);
        }
        for (var index = set.flares.size() - 1; index >= 0; index--) {
            failure = closeAndMerge(set.flares.get(index).texture(), failure);
        }
        failure = closeAndMerge(set.sun, failure);
        set.layers.clear();
        set.moons.clear();
        set.flares.clear();
        set.sun = null;
        set.moonEnvTypes = new int[0];
        if (failure != null) {
            throw failure;
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("BGFX sky renderer is closed");
        }
    }

    private static int allocateView(BgfxViewAllocator views, String name, BgfxFramebuffer target, Extent extent) {
        var view = views.allocate(name, Domain.RENDER, true);
        bgfx_set_view_name(view, name);
        bgfx_set_view_rect(view, 0, 0, extent.width(), extent.height());
        bgfx_set_view_frame_buffer(view, target == null ? BGFX_INVALID_HANDLE : target.getHandle());
        bgfx_set_view_mode(view, BGFX_VIEW_MODE_SEQUENTIAL);
        return view;
    }

    private static long skyState(Layer layer, boolean underwaterModifier) {
        return skyStateFor(layer.blend, layer.depthWrite, underwaterModifier);
    }

    private static void addSprite(
            List<CelestialSprite> output, TextureRef texture, Vec3 position, float size, Vec3 color, float alpha) {
        if (texture != null && size > 0f) {
            output.add(new CelestialSprite(texture, position, size, color, alpha));
        }
    }

    private static Vec3 celestialOffset(float hour, boolean sun, float radius) {
        return SkyRules.celestialOffset(hour, sun, radius);
    }

    private static ByteBuffer spriteQuad() {
        var values = new float[] {
            -0.5f, -0.5f, 0f, 0f, 1f, 0.5f, -0.5f, 0f, 1f, 1f, 0.5f, 0.5f, 0f, 1f, 0f, -0.5f, -0.5f, 0f, 0f, 1f, 0.5f,
            0.5f, 0f, 1f, 0f, -0.5f, 0.5f, 0f, 0f, 0f
        };
        var result = ByteBuffer.allocateDirect(values.length * Float.BYTES).order(ByteOrder.nativeOrder());
        result.asFloatBuffer().put(values);
        return result;
    }

    private static Vec3 cameraUp(Vec3 right, Vec3 forward) {
        var x = right.getY() * forward.getZ() - right.getZ() * forward.getY();
        var y = right.getZ() * forward.getX() - right.getX() * forward.getZ();
        var z = right.getX() * forward.getY() - right.getY() * forward.getX();
        var length = (float) Math.sqrt(x * x + y * y + z * z);
        return length == 0f ? new Vec3(0f, 0f, 1f) : new Vec3(x / length, y / length, z / length);
    }

    private static FloatBuffer firstSixteenFloats(FloatBuffer source) {
        var copy = source.duplicate();
        copy.position(0);
        copy.limit(16);
        return copy;
    }

    private static Vec3 authoredTint(EntityMaterial material) {
        return SkyRules.authoredTint(material);
    }

    private static Vec3 tintFor(String name, int cloudIndex, SkyColors colors, Vec3 authored) {
        return SkyRules.tint(name, cloudIndex, colors.skyBox, colors.haze, colors.env, authored);
    }

    private static boolean usesAlpha(BlendMode blend) {
        return SkyRules.usesAlpha(blend);
    }

    private static String sanitize(String value) {
        return value == null ? "unnamed" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
    }

    private static RuntimeException closeAndMerge(AutoCloseable resource, RuntimeException failure) {
        if (resource == null) {
            return failure;
        }
        try {
            resource.close();
        } catch (Exception e) {
            var runtime = e instanceof RuntimeException r ? r : new IllegalStateException(e);
            if (failure == null) {
                return runtime;
            }
            failure.addSuppressed(runtime);
        }
        return failure;
    }

    private static void closeAfterFailure(AutoCloseable resource, Throwable failure) {
        if (resource == null) {
            return;
        }
        try {
            resource.close();
        } catch (Exception e) {
            failure.addSuppressed(e);
        }
    }

    private record SkyColors(Vec3 skyBox, Vec3 haze, ResolvedEnv env, Vec4 underwaterModifier) {}

    private record Flare(TextureRef texture, float offset, float scale) {}

    private record CelestialSprite(TextureRef texture, Vec3 position, float size, Vec3 color, float alpha) {}

    private static final class SkySet {
        private final List<Layer> layers = new ArrayList<>();
        private final List<TextureRef> moons = new ArrayList<>();
        private final List<Flare> flares = new ArrayList<>();
        private final List<SkyParticle> skyParticles = new ArrayList<>();
        private Vec3 origin = new Vec3();
        private TextureRef sun;
        private float sunDrawScale = 1f;
        private float moonDrawScale = 1f;
        private float sunRadius = FALLBACK_RADIUS;
        private float moonRadius = FALLBACK_RADIUS;
        private int[] moonEnvTypes = new int[0];
        private float particleTime = Float.NaN;
    }

    private static final class Layer implements AutoCloseable {
        private final String name;
        private final BgfxBuffer vertices;
        private final BgfxBuffer indices;
        private final int vertexCount;
        private final int indexOffset;
        private final int indexCount;
        private final TextureRef texture;
        private final BlendMode blend;
        private final UvAnimation uvAnimation;
        private final boolean depthWrite;
        private final float alphaCutoff;
        private final Vec3 authoredTint;
        private final int cloudIndex;
        private final ZoneRender zoneRender;

        private Layer(
                String name,
                BgfxBuffer vertices,
                BgfxBuffer indices,
                int vertexCount,
                int indexOffset,
                int indexCount,
                TextureRef texture,
                BlendMode blend,
                UvAnimation uvAnimation,
                boolean depthWrite,
                float alphaCutoff,
                Vec3 authoredTint,
                int cloudIndex,
                ZoneRender zoneRender) {
            this.name = name;
            this.vertices = vertices;
            this.indices = indices;
            this.vertexCount = vertexCount;
            this.indexOffset = indexOffset;
            this.indexCount = indexCount;
            this.texture = texture;
            this.blend = blend;
            this.uvAnimation = uvAnimation;
            this.depthWrite = depthWrite;
            this.alphaCutoff = alphaCutoff;
            this.authoredTint = authoredTint;
            this.cloudIndex = cloudIndex;
            this.zoneRender = zoneRender;
        }

        @Override
        public void close() {
            RuntimeException failure = null;
            try {
                indices.close();
            } catch (RuntimeException e) {
                failure = e;
            }
            try {
                vertices.close();
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
            try {
                if (texture != null) {
                    texture.close();
                }
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }
}
