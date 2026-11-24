package com.rost.cruma.viewer.render.bgfx;

import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_COLOR0;
import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_NORMAL;
import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_POSITION;
import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_TEXCOORD0;
import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_TEXCOORD1;
import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_TEXCOORD2;
import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_TEXCOORD3;
import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_TEXCOORD4;
import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_TEXCOORD5;
import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_TEXCOORD6;
import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_TYPE_FLOAT;
import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_TYPE_UINT8;
import static org.lwjgl.bgfx.BGFX.BGFX_CAPS_FORMAT_TEXTURE_MSAA;
import static org.lwjgl.bgfx.BGFX.BGFX_CLEAR_COLOR;
import static org.lwjgl.bgfx.BGFX.BGFX_CLEAR_DEPTH;
import static org.lwjgl.bgfx.BGFX.BGFX_DISCARD_ALL;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_MAG_ANISOTROPIC;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_MIN_ANISOTROPIC;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_U_CLAMP;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_V_CLAMP;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_BLEND_ALPHA_TO_COVERAGE;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_BLEND_FUNC;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_BLEND_INV_SRC_ALPHA;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_BLEND_ONE;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_DEPTH_TEST_EQUAL;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_DEPTH_TEST_LEQUAL;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_DEPTH_TEST_LESS;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_MSAA;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_WRITE_A;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_WRITE_RGB;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_WRITE_Z;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_FORMAT_RGBA8;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_RT;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_RT_MSAA_X4;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_RT_WRITE_ONLY;
import static org.lwjgl.bgfx.BGFX.BGFX_UNIFORM_TYPE_SAMPLER;
import static org.lwjgl.bgfx.BGFX.BGFX_UNIFORM_TYPE_VEC4;
import static org.lwjgl.bgfx.BGFX.bgfx_get_caps;
import static org.lwjgl.bgfx.BGFX.bgfx_set_index_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_set_state;
import static org.lwjgl.bgfx.BGFX.bgfx_set_texture;
import static org.lwjgl.bgfx.BGFX.bgfx_set_uniform;
import static org.lwjgl.bgfx.BGFX.bgfx_set_vertex_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_clear;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_frame_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_name;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_rect;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_transform;
import static org.lwjgl.bgfx.BGFX.bgfx_submit;
import static org.lwjgl.bgfx.BGFX.bgfx_touch;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.jetbrains.annotations.Nullable;

public final class BgfxTileFlight implements AutoCloseable {
    private static final BgfxVertexLayoutSpec LAYOUT = BgfxVertexLayoutSpec.builder()
            .add(BGFX_ATTRIB_POSITION, 3, BGFX_ATTRIB_TYPE_FLOAT)
            .add(BGFX_ATTRIB_NORMAL, 3, BGFX_ATTRIB_TYPE_FLOAT)
            .add(BGFX_ATTRIB_TEXCOORD0, 2, BGFX_ATTRIB_TYPE_FLOAT)
            .add(BGFX_ATTRIB_COLOR0, 4, BGFX_ATTRIB_TYPE_UINT8, true, false)
            .add(BGFX_ATTRIB_TEXCOORD1, 2, BGFX_ATTRIB_TYPE_FLOAT)
            .build();
    private static final BgfxVertexLayoutSpec GROUND_LAYOUT = BgfxVertexLayoutSpec.builder()
            .add(BGFX_ATTRIB_POSITION, 3, BGFX_ATTRIB_TYPE_FLOAT)
            .add(BGFX_ATTRIB_NORMAL, 3, BGFX_ATTRIB_TYPE_FLOAT)
            .add(BGFX_ATTRIB_COLOR0, 4, BGFX_ATTRIB_TYPE_UINT8, true, false)
            .add(BGFX_ATTRIB_TEXCOORD0, 2, BGFX_ATTRIB_TYPE_FLOAT)
            .add(BGFX_ATTRIB_TEXCOORD1, 4, BGFX_ATTRIB_TYPE_FLOAT)
            .add(BGFX_ATTRIB_TEXCOORD2, 4, BGFX_ATTRIB_TYPE_FLOAT)
            .add(BGFX_ATTRIB_TEXCOORD3, 4, BGFX_ATTRIB_TYPE_FLOAT)
            .add(BGFX_ATTRIB_TEXCOORD4, 4, BGFX_ATTRIB_TYPE_FLOAT)
            .add(BGFX_ATTRIB_TEXCOORD5, 4, BGFX_ATTRIB_TYPE_FLOAT)
            .add(BGFX_ATTRIB_TEXCOORD6, 4, BGFX_ATTRIB_TYPE_FLOAT)
            .build();
    private static final BgfxVertexLayoutSpec SKY_LAYOUT = BgfxVertexLayoutSpec.builder()
            .add(BGFX_ATTRIB_POSITION, 3, BGFX_ATTRIB_TYPE_FLOAT)
            .build();
    private static final long STATE = BGFX_STATE_WRITE_RGB
            | BGFX_STATE_WRITE_A
            | BGFX_STATE_WRITE_Z
            | BGFX_STATE_DEPTH_TEST_LESS
            | BGFX_STATE_MSAA;
    private static final long OVERLAY_STATE = BGFX_STATE_WRITE_RGB
            | BGFX_STATE_DEPTH_TEST_EQUAL
            | BGFX_STATE_MSAA
            | BGFX_STATE_BLEND_FUNC(BGFX_STATE_BLEND_ONE, BGFX_STATE_BLEND_INV_SRC_ALPHA);
    private static final long CUT_STATE = STATE & ~BGFX_STATE_WRITE_A | BGFX_STATE_BLEND_ALPHA_TO_COVERAGE;
    private static final long SKY_STATE =
            BGFX_STATE_WRITE_RGB | BGFX_STATE_WRITE_A | BGFX_STATE_DEPTH_TEST_LEQUAL | BGFX_STATE_MSAA;
    private static final int SAMPLER = BGFX_SAMPLER_MIN_ANISOTROPIC | BGFX_SAMPLER_MAG_ANISOTROPIC;
    private static final int ALPHA_SAMPLER = BGFX_SAMPLER_U_CLAMP | BGFX_SAMPLER_V_CLAMP;

    private final BgfxDevice device;
    private final BgfxViewerUiRenderer ui;
    private final int view;
    private final BgfxProgram program;
    private final BgfxProgram groundProgram;
    private final BgfxProgram overlayProgram;
    private final BgfxProgram skyProgram;
    private final BgfxBuffer skyTriangle;
    private final BgfxUniform skyData;
    private final float[] sky = new float[20];
    private final BgfxUniform sampler;
    private final BgfxUniform lightmapSampler;
    private final BgfxUniform[] groundSamplers =
            new BgfxUniform[TileFlightGeometry.LAYERS + TileFlightGeometry.ALPHA_ATLASES];
    private final BgfxUniform sun;
    private final BgfxUniform sunColor;
    private final BgfxUniform ambient;
    private final BgfxUniform fog;
    private final BgfxUniform eye;
    private final BgfxUniform material;
    private final BgfxUniform water;
    private final BgfxUniform terrain;
    private final BgfxUniform layerCoordinates;
    private final BgfxTexture plain;
    private final BgfxTexture black;
    private final float[] viewMatrix = new float[16];
    private final float[] projection = new float[16];
    private final float[] materialData = new float[4];
    private final float[] terrainData = new float[4];
    private final List<UploadedGround> grounds = new ArrayList<>();
    private final List<Uploaded> uploaded = new ArrayList<>();
    private final List<@Nullable BgfxTexture> textures = new ArrayList<>();
    private @Nullable TileFlightGeometry geometry;
    private int nextGround;
    private int nextChunk;
    private int nextTexture;
    private @Nullable BgfxTexture color;
    private @Nullable BgfxTexture depth;
    private @Nullable BgfxFramebuffer target;
    private long imageId;
    private long submitted;
    private boolean multisampled = true;
    private int imageWidth;
    private int imageHeight;
    private boolean closed;

    public record View(
            float eyeX,
            float eyeY,
            float eyeZ,
            float atX,
            float atY,
            float atZ,
            float fieldOfView,
            TileFlightLight light) {}

    private record Uploaded(BgfxBuffer vertices, BgfxBuffer indices, List<TileFlightGeometry.Draw> draws) {}

    private record UploadedGround(BgfxBuffer vertices, BgfxBuffer indices, TileFlightGeometry.Ground ground) {}

    public BgfxTileFlight(BgfxDevice device, BgfxViewerUiRenderer ui) {
        this.device = device;
        this.ui = ui;
        var resources = device.getResources();
        var capabilities = device.getCapabilities();
        view = device.getUiViewId() - 1;
        program = BgfxProgram.graphics(
                resources,
                "start-flight",
                BgfxShaderResources.load(capabilities, "vs_start_flight"),
                BgfxShaderResources.load(capabilities, "fs_start_flight"));
        groundProgram = BgfxProgram.graphics(
                resources,
                "start-flight-terrain",
                BgfxShaderResources.load(capabilities, "vs_start_flight_terrain"),
                BgfxShaderResources.load(capabilities, "fs_start_flight_terrain"));
        overlayProgram = BgfxProgram.graphics(
                resources,
                "start-flight-terrain-overlay",
                BgfxShaderResources.load(capabilities, "vs_start_flight_terrain"),
                BgfxShaderResources.load(capabilities, "fs_start_flight_terrain_overlay"));
        skyProgram = BgfxProgram.graphics(
                resources,
                "start-flight-sky",
                BgfxShaderResources.load(capabilities, "vs_start_flight_sky"),
                BgfxShaderResources.load(capabilities, "fs_start_flight_sky"));
        var corners = ByteBuffer.allocateDirect(36).order(ByteOrder.nativeOrder());
        corners.putFloat(-1).putFloat(-1).putFloat(1);
        corners.putFloat(3).putFloat(-1).putFloat(1);
        corners.putFloat(-1).putFloat(3).putFloat(1);
        skyTriangle = BgfxBuffer.staticVertex(resources, "start-flight/sky", corners.flip(), SKY_LAYOUT, 0);
        skyData = BgfxUniform.create(resources, "u_flightSky", BGFX_UNIFORM_TYPE_VEC4, 5);
        sampler = BgfxUniform.create(resources, "s_flightTexture", BGFX_UNIFORM_TYPE_SAMPLER, 1);
        lightmapSampler = BgfxUniform.create(resources, "s_flightLightmap", BGFX_UNIFORM_TYPE_SAMPLER, 1);
        for (var layer = 0; layer < TileFlightGeometry.LAYERS; layer++) {
            groundSamplers[layer] =
                    BgfxUniform.create(resources, "s_flightLayer" + layer, BGFX_UNIFORM_TYPE_SAMPLER, 1);
        }
        for (var atlas = 0; atlas < TileFlightGeometry.ALPHA_ATLASES; atlas++) {
            groundSamplers[TileFlightGeometry.LAYERS + atlas] =
                    BgfxUniform.create(resources, "s_flightAlpha" + atlas, BGFX_UNIFORM_TYPE_SAMPLER, 1);
        }
        sun = BgfxUniform.create(resources, "u_flightSun", BGFX_UNIFORM_TYPE_VEC4, 1);
        sunColor = BgfxUniform.create(resources, "u_flightSunColor", BGFX_UNIFORM_TYPE_VEC4, 1);
        ambient = BgfxUniform.create(resources, "u_flightAmbient", BGFX_UNIFORM_TYPE_VEC4, 1);
        fog = BgfxUniform.create(resources, "u_flightFog", BGFX_UNIFORM_TYPE_VEC4, 1);
        eye = BgfxUniform.create(resources, "u_flightEye", BGFX_UNIFORM_TYPE_VEC4, 1);
        material = BgfxUniform.create(resources, "u_flightMaterial", BGFX_UNIFORM_TYPE_VEC4, 1);
        water = BgfxUniform.create(resources, "u_flightWater", BGFX_UNIFORM_TYPE_VEC4, 1);
        terrain = BgfxUniform.create(resources, "u_flightTerrain", BGFX_UNIFORM_TYPE_VEC4, 1);
        layerCoordinates = BgfxUniform.create(
                resources, "u_flightLayerUv", BGFX_UNIFORM_TYPE_VEC4, BgfxTerrainMaterial.LAYER_UV_VECTORS);
        plain = pixel(resources, "start-flight/plain", 150, 150, 150, 255);
        black = pixel(resources, "start-flight/black", 0, 0, 0, 0);
    }

    static void lookAt(View camera, float[] m) {
        float fx = camera.atX() - camera.eyeX(), fy = camera.atY() - camera.eyeY(), fz = camera.atZ() - camera.eyeZ();
        var length = (float) Math.sqrt(fx * fx + fy * fy + fz * fz);
        fx /= length;
        fy /= length;
        fz /= length;
        float rx = -fy, ry = fx, rz = 0;
        var right = (float) Math.sqrt(rx * rx + ry * ry);
        rx /= right;
        ry /= right;
        float ux = fy * rz - fz * ry, uy = fz * rx - fx * rz, uz = fx * ry - fy * rx;
        m[0] = rx;
        m[1] = ux;
        m[2] = fx;
        m[3] = 0;
        m[4] = ry;
        m[5] = uy;
        m[6] = fy;
        m[7] = 0;
        m[8] = rz;
        m[9] = uz;
        m[10] = fz;
        m[11] = 0;
        m[12] = -(rx * camera.eyeX() + ry * camera.eyeY() + rz * camera.eyeZ());
        m[13] = -(ux * camera.eyeX() + uy * camera.eyeY() + uz * camera.eyeZ());
        m[14] = -(fx * camera.eyeX() + fy * camera.eyeY() + fz * camera.eyeZ());
        m[15] = 1;
    }

    public void show(@Nullable TileFlightGeometry next) {
        freeTile();
        geometry = next;
        nextGround = 0;
        nextChunk = 0;
        nextTexture = 0;
        if (next != null) {
            textures.addAll(Collections.nCopies(next.getTextures().size(), null));
        }
    }

    public boolean upload(long budgetNanos) {
        var tile = geometry;
        if (tile == null || closed) {
            return true;
        }
        var deadline = System.nanoTime() + budgetNanos;
        var parts = tile.getGrounds();
        var chunks = tile.getChunks();
        var prepared = tile.getTextures();
        while (nextGround < parts.size()) {
            uploadGround(parts.get(nextGround));
            nextGround++;
        }
        while (System.nanoTime() < deadline && (nextTexture < prepared.size() || nextChunk < chunks.size())) {
            if (nextTexture < prepared.size()) {
                uploadTexture(prepared.get(nextTexture), nextTexture);
                nextTexture++;
            } else {
                uploadChunk(chunks.get(nextChunk));
                nextChunk++;
            }
        }
        var done = nextTexture >= prepared.size() && nextChunk >= chunks.size();
        if (done) {
            geometry = null;
        }
        return done;
    }

    public boolean hasPicture() {
        for (var ground : grounds) {
            if (isReady(ground.ground())) {
                return true;
            }
        }
        return grounds.isEmpty() && !uploaded.isEmpty();
    }

    public boolean isFlipped() {
        return device.getCapabilities().originBottomLeft();
    }

    public int getDrawCount() {
        var count = 0;
        for (var ground : grounds) {
            count += isReady(ground.ground()) ? 1 : 0;
        }
        for (var part : uploaded) {
            count += part.draws().size();
        }
        return count;
    }

    public long getSubmittedDraws() {
        return submitted;
    }

    public boolean hasImage(int width, int height) {
        return color != null && imageWidth == Math.max(1, width) && imageHeight == Math.max(1, height);
    }

    public long getImageId() {
        return imageId;
    }

    public boolean isMultisampled() {
        return multisampled;
    }

    public long render(int width, int height, View camera) {
        ensureImage(Math.max(1, width), Math.max(1, height));
        lookAt(camera, viewMatrix);
        perspective(camera.fieldOfView(), width / (float) Math.max(1, height), projection);
        var light = camera.light();
        var sky = light.sky();
        var clear = ((int) (Math.clamp(sky[0], 0f, 1f) * 255) << 24)
                | ((int) (Math.clamp(sky[1], 0f, 1f) * 255) << 16)
                | ((int) (Math.clamp(sky[2], 0f, 1f) * 255) << 8)
                | 0xff;
        bgfx_set_view_name(view, "start-flight");
        bgfx_set_view_rect(view, 0, 0, width, height);
        bgfx_set_view_frame_buffer(view, target.getHandle());
        bgfx_set_view_clear(view, BGFX_CLEAR_COLOR | BGFX_CLEAR_DEPTH, clear, 1f, 0);
        bgfx_set_view_transform(view, viewMatrix, projection);
        bgfx_touch(view);
        float[] eyeData = {camera.eyeX(), camera.eyeY(), camera.eyeZ(), light.fogStart()};
        float[] fogData = {light.fog()[0], light.fog()[1], light.fog()[2], light.fogEnd()};
        drawSky(camera, width / (float) Math.max(1, height));
        for (var part : grounds) {
            var ground = part.ground();
            if (!isReady(ground)) {
                continue;
            }
            for (var layer = 0; layer < TileFlightGeometry.LAYERS; layer++) {
                var slot = ground.layers()[layer];
                bgfx_set_texture(
                        layer,
                        groundSamplers[layer].getHandle(),
                        (slot < 0 ? plain : textures.get(slot)).getHandle(),
                        SAMPLER);
            }
            for (var atlas = 0; atlas < TileFlightGeometry.ALPHA_ATLASES; atlas++) {
                var slot = ground.alphas()[atlas];
                bgfx_set_texture(
                        TileFlightGeometry.LAYERS + atlas,
                        groundSamplers[TileFlightGeometry.LAYERS + atlas].getHandle(),
                        (slot < 0 ? black : textures.get(slot)).getHandle(),
                        ALPHA_SAMPLER);
            }
            terrainData[0] = ground.layerCount();
            terrainData[1] = ground.diffuseAlpha()[0];
            terrainData[2] = ground.diffuseAlpha()[1];
            terrainData[3] = ground.diffuseAlpha()[2];
            bgfx_set_uniform(terrain.getHandle(), terrainData, 1);
            bgfx_set_uniform(sunColor.getHandle(), light.groundSun(), 1);
            bgfx_set_uniform(ambient.getHandle(), light.groundAmbient(), 1);
            bgfx_set_uniform(fog.getHandle(), fogData, 1);
            bgfx_set_uniform(eye.getHandle(), eyeData, 1);
            bgfx_set_vertex_buffer(0, part.vertices().getHandle(), 0, -1);
            bgfx_set_index_buffer(part.indices().getHandle(), 0, -1);
            bgfx_set_state(STATE, 0);
            bgfx_submit(view, groundProgram.getHandle(), 0, BGFX_DISCARD_ALL);
            submitted++;
            for (var overlay : ground.overlays()) {
                drawOverlay(part, overlay);
            }
        }
        for (var part : uploaded) {
            for (var draw : part.draws()) {
                var texture = draw.texture() < 0 ? plain : textures.get(draw.texture());
                var bake = draw.lightmap() < 0 ? plain : textures.get(draw.lightmap());
                if (texture == null || bake == null) {
                    continue;
                }
                bgfx_set_vertex_buffer(0, part.vertices().getHandle(), 0, -1);
                bgfx_set_index_buffer(part.indices().getHandle(), draw.firstIndex(), draw.indexCount());
                bgfx_set_texture(0, sampler.getHandle(), texture.getHandle(), SAMPLER);
                bgfx_set_texture(1, lightmapSampler.getHandle(), bake.getHandle(), ALPHA_SAMPLER);
                materialData[0] = draw.cut();
                materialData[1] = draw.water() ? 1 : 0;
                materialData[2] = draw.bsp() ? 1 : 0;
                bgfx_set_uniform(sun.getHandle(), light.sunDirection(), 1);
                bgfx_set_uniform(sunColor.getHandle(), light.meshSun(), 1);
                bgfx_set_uniform(ambient.getHandle(), light.meshAmbient(), 1);
                bgfx_set_uniform(fog.getHandle(), fogData, 1);
                bgfx_set_uniform(eye.getHandle(), eyeData, 1);
                bgfx_set_uniform(material.getHandle(), materialData, 1);
                bgfx_set_uniform(water.getHandle(), light.water(), 1);
                bgfx_set_state(draw.cut() > 0 ? CUT_STATE : STATE, 0);
                bgfx_submit(view, program.getHandle(), 0, BGFX_DISCARD_ALL);
                submitted++;
            }
        }
        return imageId;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        freeTile();
        closeImage();
        black.close();
        plain.close();
        layerCoordinates.close();
        terrain.close();
        water.close();
        material.close();
        eye.close();
        fog.close();
        ambient.close();
        sunColor.close();
        sun.close();
        for (var uniform : groundSamplers) {
            uniform.close();
        }
        lightmapSampler.close();
        sampler.close();
        skyData.close();
        skyTriangle.close();
        skyProgram.close();
        overlayProgram.close();
        groundProgram.close();
        program.close();
    }

    private void drawSky(View camera, float aspect) {
        var light = camera.light();
        var tangent = (float) Math.tan(Math.toRadians(camera.fieldOfView()) * .5);
        put(sky, 0, viewMatrix[2], viewMatrix[6], viewMatrix[10], tangent * aspect);
        put(sky, 1, viewMatrix[1], viewMatrix[5], viewMatrix[9], tangent);
        put(sky, 2, viewMatrix[0], viewMatrix[4], viewMatrix[8], 0);
        put(sky, 3, light.sky()[0], light.sky()[1], light.sky()[2], 0);
        put(sky, 4, light.haze()[0], light.haze()[1], light.haze()[2], 0);
        bgfx_set_uniform(skyData.getHandle(), sky, 5);
        bgfx_set_vertex_buffer(0, skyTriangle.getHandle(), 0, 3);
        bgfx_set_state(SKY_STATE, 0);
        bgfx_submit(view, skyProgram.getHandle(), 0, BGFX_DISCARD_ALL);
        submitted++;
    }

    private boolean isReady(TileFlightGeometry.Ground ground) {
        for (var slot : ground.layers()) {
            if (slot >= 0 && textures.get(slot) == null) {
                return false;
            }
        }
        for (var slot : ground.alphas()) {
            if (slot >= 0 && textures.get(slot) == null) {
                return false;
            }
        }
        for (var overlay : ground.overlays()) {
            for (var slot : overlay.layers()) {
                if (slot >= 0 && textures.get(slot) == null) {
                    return false;
                }
            }
            for (var slot : overlay.alphas()) {
                if (slot >= 0 && textures.get(slot) == null) {
                    return false;
                }
            }
        }
        return true;
    }

    private void drawOverlay(UploadedGround part, TileFlightGeometry.Overlay overlay) {
        for (var layer = 0; layer < TileFlightGeometry.LAYERS; layer++) {
            var slot = overlay.layers()[layer];
            bgfx_set_texture(
                    layer,
                    groundSamplers[layer].getHandle(),
                    (slot < 0 ? plain : textures.get(slot)).getHandle(),
                    SAMPLER);
        }
        for (var atlas = 0; atlas < TileFlightGeometry.ALPHA_ATLASES; atlas++) {
            var slot = overlay.alphas()[atlas];
            bgfx_set_texture(
                    TileFlightGeometry.LAYERS + atlas,
                    groundSamplers[TileFlightGeometry.LAYERS + atlas].getHandle(),
                    (slot < 0 ? black : textures.get(slot)).getHandle(),
                    ALPHA_SAMPLER);
        }
        terrainData[0] = overlay.layerCount();
        terrainData[1] = overlay.diffuseAlpha()[0];
        terrainData[2] = overlay.diffuseAlpha()[1];
        terrainData[3] = overlay.diffuseAlpha()[2];
        bgfx_set_uniform(terrain.getHandle(), terrainData, 1);
        bgfx_set_uniform(layerCoordinates.getHandle(), overlay.coordinates(), BgfxTerrainMaterial.LAYER_UV_VECTORS);
        bgfx_set_vertex_buffer(0, part.vertices().getHandle(), 0, -1);
        bgfx_set_index_buffer(part.indices().getHandle(), 0, -1);
        bgfx_set_state(OVERLAY_STATE, 0);
        bgfx_submit(view, overlayProgram.getHandle(), 0, BGFX_DISCARD_ALL);
        submitted++;
    }

    private void perspective(float fieldOfView, float aspect, float[] m) {
        var near = 40f;
        var far = 160_000f;
        var height = (float) (1 / Math.tan(Math.toRadians(fieldOfView) * .5));
        var width = height / aspect;
        var homogeneous = device.getCapabilities().homogeneousDepth();
        var diff = far - near;
        var aa = homogeneous ? (far + near) / diff : far / diff;
        var bb = homogeneous ? 2 * far * near / diff : near * aa;
        Arrays.fill(m, 0);
        m[0] = width;
        m[5] = height;
        m[10] = aa;
        m[11] = 1;
        m[14] = -bb;
    }

    private void uploadGround(TileFlightGeometry.Ground ground) {
        var resources = device.getResources();
        var vertices =
                BgfxBuffer.staticVertex(resources, "start-flight/ground-vertices", ground.vertices(), GROUND_LAYOUT, 0);
        var indices = BgfxBuffer.staticIndex(resources, "start-flight/ground-indices", ground.indices(), true, 0);
        grounds.add(new UploadedGround(vertices, indices, ground));
    }

    private void uploadChunk(TileFlightGeometry.Chunk chunk) {
        var resources = device.getResources();
        var vertices = BgfxBuffer.staticVertex(resources, "start-flight/vertices", chunk.vertices(), LAYOUT, 0);
        var indices = BgfxBuffer.staticIndex(resources, "start-flight/indices", chunk.indices(), true, 0);
        uploaded.add(new Uploaded(vertices, indices, chunk.draws()));
    }

    private void uploadTexture(TileFlightGeometry.Prepared prepared, int slot) {
        BgfxTexture texture;
        try {
            texture = BgfxTexture.create2D(
                    device.getResources(),
                    "start-flight/texture",
                    prepared.width(),
                    prepared.height(),
                    prepared.levels(),
                    1,
                    prepared.format(),
                    0,
                    prepared.data());
        } catch (RuntimeException refused) {
            texture = null;
        }
        textures.set(slot, texture == null ? plain : texture);
    }

    private void freeTile() {
        for (var part : grounds) {
            part.vertices().close();
            part.indices().close();
        }
        grounds.clear();
        for (var part : uploaded) {
            part.vertices().close();
            part.indices().close();
        }
        uploaded.clear();
        for (var texture : textures) {
            if (texture != null && texture != plain) {
                texture.close();
            }
        }
        textures.clear();
        geometry = null;
    }

    private void ensureImage(int width, int height) {
        if (color != null && imageWidth == width && imageHeight == height) {
            return;
        }
        closeImage();
        var depthFormat = device.getCapabilities().depthStencilFormat();
        if (multisampled && !(takesSamples(BGFX_TEXTURE_FORMAT_RGBA8) && takesSamples(depthFormat))) {
            multisampled = false;
        }
        if (multisampled) {
            try {
                createImage(width, height, depthFormat, BGFX_TEXTURE_RT_MSAA_X4);
            } catch (RuntimeException refused) {
                freeImage();
                multisampled = false;
            }
        }
        if (!multisampled) {
            createImage(width, height, depthFormat, 0);
        }
        imageId = ui.registerTexture(color);
        imageWidth = width;
        imageHeight = height;
    }

    private void createImage(int width, int height, int depthFormat, long samples) {
        var resources = device.getResources();
        color = BgfxTexture.create2D(
                resources,
                "start-flight/image",
                width,
                height,
                false,
                1,
                BGFX_TEXTURE_FORMAT_RGBA8,
                BGFX_TEXTURE_RT | samples,
                null);
        depth = BgfxTexture.create2D(
                resources,
                "start-flight/depth",
                width,
                height,
                false,
                1,
                depthFormat,
                BGFX_TEXTURE_RT_WRITE_ONLY | samples,
                null);
        target = BgfxFramebuffer.create(resources, "start-flight/target", List.of(color, depth));
    }

    private void closeImage() {
        if (color == null) {
            return;
        }
        ui.unregisterTexture(imageId);
        freeImage();
    }

    private void freeImage() {
        if (target != null) {
            target.close();
        }
        if (depth != null) {
            depth.close();
        }
        if (color != null) {
            color.close();
        }
        target = null;
        depth = null;
        color = null;
    }

    private static boolean takesSamples(int format) {
        var caps = bgfx_get_caps();
        return caps != null && (caps.formats(format) & BGFX_CAPS_FORMAT_TEXTURE_MSAA) != 0;
    }

    private static BgfxTexture pixel(BgfxResourceRegistry resources, String name, int r, int g, int b, int a) {
        var data = ByteBuffer.allocateDirect(4).put(new byte[] {(byte) r, (byte) g, (byte) b, (byte) a});
        return BgfxTexture.create2D(resources, name, 1, 1, false, 1, BGFX_TEXTURE_FORMAT_RGBA8, 0, data.flip());
    }

    private static void put(float[] target, int vector, float x, float y, float z, float w) {
        target[vector * 4] = x;
        target[vector * 4 + 1] = y;
        target[vector * 4 + 2] = z;
        target[vector * 4 + 3] = w;
    }
}
