package com.rost.cruma.viewer.render.bgfx;

import static org.lwjgl.bgfx.BGFX.BGFX_UNIFORM_TYPE_SAMPLER;
import static org.lwjgl.bgfx.BGFX.BGFX_UNIFORM_TYPE_VEC4;

import com.rost.cruma.viewer.render.bgfx.BgfxNativeVertexLightmaps.Stream;
import glm_.vec3.Vec3;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.lwjgl.bgfx.BGFX;
import org.lwjgl.system.Platform;

final class BgfxSceneUniforms implements AutoCloseable {
    static final int MATERIAL_VECTORS = 18;
    static final int TERRAIN_VECTORS = 8;
    static final int FRAME_VECTORS = 18;
    static final int FRAME_HEIGHT_CUT = 16 * 4;
    static final int FRAME_UNLIT = 10 * 4 + 3;
    static final int VERTEX_MATERIAL_VECTORS = 3;
    static final int VERTEX_FRAME_VECTORS = 7;
    static final int TERRAIN_MODEL_VECTORS = 4;
    private static final float[] DISABLED_SHADER_MAPS = new float[4];
    private static final boolean NATIVE_UNIFORM_VALUE_CACHE = Boolean.parseBoolean(System.getProperty(
            "cruma.viewer.bgfx.native_uniform_value_cache", Boolean.toString(Platform.get() == Platform.WINDOWS)));
    private static final boolean DIRECT_UNIFORM_UPLOADS = Boolean.parseBoolean(System.getProperty(
            "cruma.viewer.bgfx.direct_uniform_uploads", Boolean.toString(Platform.get() == Platform.WINDOWS)));
    private static final int DIRECT_UNIFORM_CYCLE_FRAMES =
            Math.max(0, Integer.getInteger("cruma.viewer.bgfx.direct_uniform_cycle_frames", 0));
    private static final boolean COMPACT_AUTHORED_UNIFORMS =
            Boolean.getBoolean("cruma.viewer.bgfx.compact_authored_uniforms");
    private static final int COMPACT_AUTHORED_CYCLE_FRAMES =
            Math.max(0, Integer.getInteger("cruma.viewer.bgfx.compact_authored_cycle_frames", 0));

    private final List<BgfxUniform> owned = new ArrayList<>();
    private final BgfxUniform[] sceneSamplers = new BgfxUniform[14];
    private final BgfxUniform[] authoredSamplers = new BgfxUniform[8];
    private final BgfxUniform authoredMaterial;
    private final BgfxUniform authoredPasses;
    private final BgfxUniform nativeWaterLights;
    private final BgfxUniform nativeMeshDraw;
    private final BgfxUniform nativeVertexLightmap;
    private final BgfxUniform nativeShaderMaps;
    private final BgfxUniform[] nativeShaderMapSamplers = new BgfxUniform[2];
    private final float[] nativeVertexLightmapData = new float[4];
    private final float[] nativeMeshDrawData = new float[4];
    private final BgfxUniform[] terrainSamplers = new BgfxUniform[15];
    private final BgfxUniform material;
    private final BgfxUniform terrainMaterial;
    private final BgfxUniform sceneFrame;
    private final BgfxUniform vertexMaterial;
    private final BgfxUniform vertexFrame;
    private final BgfxUniform terrainModel;
    private final BgfxUniform terrainLayerUv;
    private final BgfxUniform terrainBaseUv;
    private final float[] vertexMaterialData = new float[VERTEX_MATERIAL_VECTORS * 4];
    private final float[] vertexFrameData = new float[VERTEX_FRAME_VECTORS * 4];
    private final float[] fragmentFrameData = new float[FRAME_VECTORS * 4];
    private float materialAlphaScale = 1f;
    private final float[] scaledMaterialData = new float[MATERIAL_VECTORS * 4];
    private final boolean deduplicate =
            Boolean.parseBoolean(System.getProperty("cruma.viewer.bgfx.uniform_value_cache", "true"));
    private final BgfxUniformValueCache materialValues = new BgfxUniformValueCache(MATERIAL_VECTORS * 4);
    private final BgfxUniformValueCache frameValues = new BgfxUniformValueCache(FRAME_VECTORS * 4);
    private final BgfxUniformValueCache vertexMaterialValues = new BgfxUniformValueCache(VERTEX_MATERIAL_VECTORS * 4);
    private final BgfxUniformValueCache vertexFrameValues = new BgfxUniformValueCache(VERTEX_FRAME_VECTORS * 4);
    private final boolean deduplicateNative = NATIVE_UNIFORM_VALUE_CACHE;
    private final BgfxUniformValueCache authoredMaterialValues =
            new BgfxUniformValueCache(BgfxAuthoredMaterial.VECTORS * 4);
    private final BgfxUniformValueCache authoredPassValues = new BgfxUniformValueCache(BgfxAuthoredPasses.VECTORS * 4);
    private final BgfxUniformValueCache nativeWaterValues =
            new BgfxUniformValueCache(BgfxNativeWaterLighting.VECTORS * 4);
    private final BgfxUniformValueCache nativeMeshValues = new BgfxUniformValueCache(4);
    private final BgfxUniformValueCache nativeVertexLightmapValues = new BgfxUniformValueCache(4);
    private final BgfxUniformValueCache nativeShaderMapValues = new BgfxUniformValueCache(4);
    private boolean sequentialView;
    private final BgfxTextureBindingCache textureBindings = new BgfxTextureBindingCache();
    private boolean textureBatch;
    private boolean closed;
    private final MemorySegment uploadPayload =
            MemorySegment.ofBuffer(ByteBuffer.allocateDirect(BgfxAuthoredPasses.VECTORS * 16));
    private boolean directUniformUploads = isDirectUniformModeRequested(0);
    private boolean compactAuthoredUniforms = isCompactAuthoredModeRequested(0);

    BgfxSceneUniforms(BgfxResourceRegistry resources) {
        nativeShaderMaps = own(BgfxUniform.create(resources, "u_nativeShaderMaps", BGFX_UNIFORM_TYPE_VEC4, 1));
        nativeShaderMapSamplers[0] =
                own(BgfxUniform.create(resources, "s_nativeNormalMap", BGFX_UNIFORM_TYPE_SAMPLER, 1));
        nativeShaderMapSamplers[1] =
                own(BgfxUniform.create(resources, "s_nativeSpecularMap", BGFX_UNIFORM_TYPE_SAMPLER, 1));
        nativeMeshDraw = own(BgfxUniform.create(resources, "u_nativeMeshDraw", BGFX_UNIFORM_TYPE_VEC4, 1));
        nativeVertexLightmap = own(BgfxUniform.create(resources, "u_nativeVertexLightmap", BGFX_UNIFORM_TYPE_VEC4, 1));
        nativeWaterLights = own(BgfxUniform.create(
                resources, "u_nativeWaterLights", BGFX_UNIFORM_TYPE_VEC4, BgfxNativeWaterLighting.VECTORS));
        authoredPasses = own(
                BgfxUniform.create(resources, "u_authoredPasses", BGFX_UNIFORM_TYPE_VEC4, BgfxAuthoredPasses.VECTORS));
        authoredMaterial = own(BgfxUniform.create(
                resources, "u_authoredMaterial", BGFX_UNIFORM_TYPE_VEC4, BgfxAuthoredMaterial.VECTORS));
        for (var index = 0; index < authoredSamplers.length; index++) {
            authoredSamplers[index] =
                    own(BgfxUniform.create(resources, "s_authored" + index, BGFX_UNIFORM_TYPE_SAMPLER, 1));
        }
        var sceneNames = new String[] {
            "s_sceneDiffuse",
            "s_sceneOpacity",
            "s_sceneSecond",
            "s_sceneMask",
            "s_sceneSpecular",
            "s_sceneSpecMask",
            "s_sceneLightmap",
            "s_sceneCube",
            "s_sceneDepth",
            "s_sceneReflection",
            "s_sceneBack",
            "s_sceneNormal",
            "s_sceneSelfIllumination",
            "s_sceneSelfIlluminationMask"
        };
        for (var index = 0; index < sceneNames.length; index++) {
            sceneSamplers[index] = own(BgfxUniform.create(resources, sceneNames[index], BGFX_UNIFORM_TYPE_SAMPLER, 1));
        }
        for (var index = 0; index < 12; index++) {
            terrainSamplers[index] =
                    own(BgfxUniform.create(resources, "s_terrain" + index, BGFX_UNIFORM_TYPE_SAMPLER, 1));
        }
        for (var index = 0; index < 3; index++) {
            terrainSamplers[12 + index] =
                    own(BgfxUniform.create(resources, "s_terrainAlpha" + index, BGFX_UNIFORM_TYPE_SAMPLER, 1));
        }
        material = own(BgfxUniform.create(resources, "u_sceneMaterial", BGFX_UNIFORM_TYPE_VEC4, MATERIAL_VECTORS));
        terrainMaterial =
                own(BgfxUniform.create(resources, "u_terrainMaterial", BGFX_UNIFORM_TYPE_VEC4, TERRAIN_VECTORS));
        sceneFrame = own(BgfxUniform.create(resources, "u_sceneFrame", BGFX_UNIFORM_TYPE_VEC4, FRAME_VECTORS));
        vertexMaterial = own(BgfxUniform.create(
                resources, "u_sceneVertexMaterial", BGFX_UNIFORM_TYPE_VEC4, VERTEX_MATERIAL_VECTORS));
        vertexFrame =
                own(BgfxUniform.create(resources, "u_sceneVertexFrame", BGFX_UNIFORM_TYPE_VEC4, VERTEX_FRAME_VECTORS));
        terrainModel =
                own(BgfxUniform.create(resources, "u_terrainModel", BGFX_UNIFORM_TYPE_VEC4, TERRAIN_MODEL_VECTORS));
        terrainLayerUv = own(BgfxUniform.create(
                resources, "u_terrainLayerUv", BGFX_UNIFORM_TYPE_VEC4, BgfxTerrainMaterial.LAYER_UV_VECTORS));
        terrainBaseUv = own(BgfxUniform.create(
                resources, "u_terrainBaseUv", BGFX_UNIFORM_TYPE_VEC4, BgfxTerrainMaterial.LAYER_UV_VECTORS));
    }

    static boolean isNativeUniformValueCacheEnabled() {
        return NATIVE_UNIFORM_VALUE_CACHE;
    }

    static boolean isCompactAuthoredModeRequested(long frameNumber) {
        if (COMPACT_AUTHORED_CYCLE_FRAMES == 0) {
            return COMPACT_AUTHORED_UNIFORMS;
        }
        var phase = frameNumber / COMPACT_AUTHORED_CYCLE_FRAMES % 4;
        return phase == 0 || phase == 3;
    }

    static boolean isDirectUniformModeRequested(long frameNumber) {
        if (DIRECT_UNIFORM_CYCLE_FRAMES == 0) {
            return DIRECT_UNIFORM_UPLOADS;
        }
        var phase = frameNumber / DIRECT_UNIFORM_CYCLE_FRAMES % 4;
        return phase == 0 || phase == 3;
    }

    static void fillVertexFrame(float[] fullFrame, boolean terrain, Vec3 camera, float[] target) {
        fillVertexFrame(
                fullFrame, terrain, camera, target, fullFrame == null || fullFrame.length <= 35 ? 0f : fullFrame[35]);
    }

    static void fillVertexFrame(
            float[] fullFrame, boolean terrain, Vec3 camera, float[] target, float ambientBeastScale) {
        if (fullFrame == null
                || fullFrame.length < FRAME_VECTORS * 4
                || target == null
                || target.length < VERTEX_FRAME_VECTORS * 4
                || !terrain && camera == null) {
            throw new IllegalArgumentException("invalid vertex-frame payload");
        }
        Arrays.fill(target, 0f);
        if (terrain) {
            copyVector(fullFrame, 3, target, 0);
            target[2] = fullFrame[23];
        } else {
            target[0] = camera.getX();
            target[1] = camera.getY();
            target[2] = camera.getZ();
            target[3] = fullFrame[4 * 4 + 3];
            copyVector(fullFrame, 5, target, 1);
            copyVector(fullFrame, 6, target, 2);
            target[4] = fullFrame[8 * 4];
            target[5] = fullFrame[8 * 4 + 1];
            target[6] = fullFrame[8 * 4 + 2];
            target[8] = fullFrame[7 * 4];
            target[9] = fullFrame[7 * 4 + 1];
            target[10] = fullFrame[7 * 4 + 2];
            copyVector(fullFrame, 9, target, 3);
            target[15] = ambientBeastScale;
            copyVector(fullFrame, 13, target, 4);
            copyVector(fullFrame, 14, target, 5);
            copyVector(fullFrame, 15, target, 6);
        }
    }

    BgfxUniform getSceneSampler(int slot) {
        return sceneSamplers[slot];
    }

    void beginTextureBatch() {
        if (textureBatch) {
            throw new IllegalStateException("texture batch already active");
        }
        BGFX.bgfx_discard(BGFX.BGFX_DISCARD_BINDINGS);
        textureBindings.invalidate();
        textureBatch = true;
    }

    void endTextureBatch() {
        BGFX.bgfx_discard(BGFX.BGFX_DISCARD_ALL);
        textureBatch = false;
        textureBindings.invalidate();
    }

    boolean isTextureBatchActive() {
        return textureBatch;
    }

    void bindTexture(int stage, short sampler, short texture, int flags) {
        if (!textureBatch || textureBindings.needsWrite(stage, sampler, texture, flags)) {
            BGFX.bgfx_set_texture(stage, sampler, texture, flags);
        }
    }

    BgfxUniform getAuthoredSampler(int index) {
        return authoredSamplers[index];
    }

    BgfxUniform getNativeShaderMapSampler(int index) {
        return nativeShaderMapSamplers[index];
    }

    void bindNativeShaderMaps(float[] values) {
        bindNative(nativeShaderMaps, values == null ? DISABLED_SHADER_MAPS : values, 1, nativeShaderMapValues);
    }

    void bindAuthoredPasses(float[] values) {
        bindNative(
                authoredPasses,
                values,
                compactAuthoredUniforms ? BgfxAuthoredPasses.uploadVectors(values) : BgfxAuthoredPasses.VECTORS,
                authoredPassValues);
    }

    void bindNativeWaterLights(BgfxNativeWaterLighting.Draw draw) {
        bindNative(
                nativeWaterLights,
                draw == null ? BgfxNativeWaterLighting.DISABLED : draw.uniforms(),
                BgfxNativeWaterLighting.VECTORS,
                nativeWaterValues);
    }

    void bindNativeVertexLightmap(Stream stream) {
        nativeVertexLightmapData[0] = stream == null ? 0 : stream.offset() & 0xffff;
        nativeVertexLightmapData[1] = stream == null ? 0 : stream.offset() >>> 16;
        nativeVertexLightmapData[2] = stream == null ? 0 : stream.vertexCount();
        bindNative(nativeVertexLightmap, nativeVertexLightmapData, 1, nativeVertexLightmapValues);
    }

    void bindNativeMeshDraw(BgfxNativeMeshDraws.Draw draw) {
        nativeMeshDrawData[0] = draw == null ? 0 : 1;
        nativeMeshDrawData[1] = draw != null && draw.useTangent() ? 1 : 0;
        nativeMeshDrawData[2] = draw != null && draw.useVertexColor() ? 1 : 0;
        bindNative(nativeMeshDraw, nativeMeshDrawData, 1, nativeMeshValues);
    }

    void bindAuthoredMaterial(float[] values) {
        bindNative(authoredMaterial, values, BgfxAuthoredMaterial.VECTORS, authoredMaterialValues);
    }

    void beginView(boolean sequential) {
        sequentialView = sequential;
        materialValues.invalidate();
        frameValues.invalidate();
        vertexMaterialValues.invalidate();
        vertexFrameValues.invalidate();
        authoredMaterialValues.invalidate();
        authoredPassValues.invalidate();
        nativeWaterValues.invalidate();
        nativeMeshValues.invalidate();
        nativeVertexLightmapValues.invalidate();
        nativeShaderMapValues.invalidate();
    }

    void bindMaterial(float[] values) {
        if (materialAlphaScale != 1f) {
            System.arraycopy(values, 0, scaledMaterialData, 0, scaledMaterialData.length);
            scaledMaterialData[7] *= materialAlphaScale;
            bind(material, scaledMaterialData, MATERIAL_VECTORS, materialValues);
            return;
        }
        bind(material, values, MATERIAL_VECTORS, materialValues);
    }

    void setMaterialAlphaScale(float scale) {
        materialAlphaScale = scale;
    }

    void bindFrame(float[] values) {
        bind(sceneFrame, values, FRAME_VECTORS, frameValues);
    }

    void bindFrame(
            float[] values,
            float backHalfWidth,
            float backHalfHeight,
            float viewportHalfWidth,
            float viewportHalfHeight) {
        System.arraycopy(values, 0, fragmentFrameData, 0, fragmentFrameData.length);
        fragmentFrameData[56] = backHalfWidth;
        fragmentFrameData[57] = backHalfHeight;
        fragmentFrameData[58] = viewportHalfWidth;
        fragmentFrameData[59] = viewportHalfHeight;
        bindFrame(fragmentFrameData);
    }

    void beginFrame(long frameNumber) {
        directUniformUploads = isDirectUniformModeRequested(frameNumber);
        compactAuthoredUniforms = isCompactAuthoredModeRequested(frameNumber);
    }

    BgfxUniform getTerrainSampler(int slot) {
        return terrainSamplers[slot];
    }

    BgfxUniform getTerrainMaterial() {
        return terrainMaterial;
    }

    BgfxUniform getTerrainLayerUv() {
        return terrainLayerUv;
    }

    BgfxUniform getTerrainBaseUv() {
        return terrainBaseUv;
    }

    void bindVertexMaterial(float[] fullMaterial) {
        bindNativeMeshDraw(null);
        copyVector(fullMaterial, 4, vertexMaterialData, 0);
        copyVector(fullMaterial, 5, vertexMaterialData, 1);
        copyVector(fullMaterial, 11, vertexMaterialData, 2);
        vertexMaterialData[11] =
                fullMaterial[2 * 4] == 2 && fullMaterial[12 * 4] < .5f && fullMaterial[15 * 4] > 2.5f ? 1 : 0;
        bind(vertexMaterial, vertexMaterialData, VERTEX_MATERIAL_VECTORS, vertexMaterialValues);
    }

    void bindVertexFrame(float[] fullFrame, boolean terrain, Vec3 camera) {
        fillVertexFrame(fullFrame, terrain, camera, vertexFrameData);
        bind(vertexFrame, vertexFrameData, VERTEX_FRAME_VECTORS, vertexFrameValues);
    }

    void bindVertexFrame(float[] fullFrame, boolean terrain, Vec3 camera, float ambientBeastScale) {
        fillVertexFrame(fullFrame, terrain, camera, vertexFrameData, ambientBeastScale);
        bind(vertexFrame, vertexFrameData, VERTEX_FRAME_VECTORS, vertexFrameValues);
    }

    void bindTerrainModel(float[] model) {
        upload(terrainModel, model, TERRAIN_MODEL_VECTORS);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        RuntimeException failure = null;
        for (var index = owned.size() - 1; index >= 0; index--) {
            try {
                owned.get(index).close();
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        owned.clear();
        if (failure != null) {
            throw failure;
        }
    }

    private void bind(BgfxUniform uniform, float[] values, int vectors, BgfxUniformValueCache cached) {
        if (!deduplicate || !sequentialView || cached.needsWrite(values, vectors * 4)) {
            upload(uniform, values, vectors);
        }
    }

    private void bindNative(BgfxUniform uniform, float[] values, int vectors, BgfxUniformValueCache cached) {
        if (deduplicateNative) {
            bind(uniform, values, vectors, cached);
        } else {
            upload(uniform, values, vectors);
        }
    }

    private void upload(BgfxUniform uniform, float[] values, int vectors) {
        if (!directUniformUploads) {
            BGFX.bgfx_set_uniform(uniform.getHandle(), values, vectors);
            return;
        }
        MemorySegment.copy(MemorySegment.ofArray(values), 0, uploadPayload, 0, (long) vectors * 16);
        BGFX.nbgfx_set_uniform(uniform.getHandle(), uploadPayload.address(), (short) vectors);
    }

    private BgfxUniform own(BgfxUniform uniform) {
        owned.add(uniform);
        return uniform;
    }

    private static void copyVector(float[] source, int sourceVector, float[] target, int targetVector) {
        System.arraycopy(source, sourceVector * 4, target, targetVector * 4, 4);
    }
}
