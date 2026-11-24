package com.rost.cruma.viewer.render.bgfx;

import static org.lwjgl.bgfx.BGFX.bgfx_set_texture;
import static org.lwjgl.bgfx.BGFX.bgfx_set_uniform;

import com.rost.cruma.core.unreal.primitives.UColorFade;
import com.rost.cruma.core.world.AuthoredFramebuffer;
import com.rost.cruma.core.world.Entity.AnimatedTextureEntityMaterial;
import com.rost.cruma.core.world.Entity.BlendMode;
import com.rost.cruma.core.world.Entity.ColorEntityMaterial;
import com.rost.cruma.core.world.Entity.ColorModifierEntityMaterial;
import com.rost.cruma.core.world.Entity.CombinerEntityMaterial;
import com.rost.cruma.core.world.Entity.ConstantOpacityEntityMaterial;
import com.rost.cruma.core.world.Entity.Cubemap;
import com.rost.cruma.core.world.Entity.EntityMaterial;
import com.rost.cruma.core.world.Entity.EnvCubeLayer;
import com.rost.cruma.core.world.Entity.EnvMapEntityMaterial;
import com.rost.cruma.core.world.Entity.EnvironmentLayer;
import com.rost.cruma.core.world.Entity.FadeColorSpec;
import com.rost.cruma.core.world.Entity.MultiTextureEntityMaterial;
import com.rost.cruma.core.world.Entity.ReflectiveEntityMaterial;
import com.rost.cruma.core.world.Entity.SpecularEntityMaterial;
import com.rost.cruma.core.world.Entity.SpecularOverlay;
import com.rost.cruma.core.world.Entity.Surface;
import com.rost.cruma.core.world.Entity.SurfaceType;
import com.rost.cruma.core.world.Entity.Texture;
import com.rost.cruma.core.world.Entity.TextureEntityMaterial;
import com.rost.cruma.core.world.Entity.TextureEntityMaterialWithOpacity;
import com.rost.cruma.core.world.Entity.UvAnimation;
import com.rost.cruma.core.world.Entity.WaterEntityMaterial;
import com.rost.cruma.core.world.NativeStaticMeshLightingFeatures;
import com.rost.cruma.viewer.render.WaterRenderRoute;
import com.rost.cruma.viewer.render.api.DebugMaterialSlot;
import com.rost.cruma.viewer.render.bgfx.BgfxLegacyWaterClock.BumpMatrix;
import com.rost.cruma.viewer.render.bgfx.BgfxTextureCache.TextureRef;
import com.rost.cruma.viewer.render.bgfx.BgfxWaterTexture.Binding;
import glm_.vec3.Vec3;
import glm_.vec4.Vec4;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

final class BgfxSceneMaterial implements AutoCloseable {
    private static final float ENCODED_PAGE_LAYER_STRIDE = 2048f;
    private static final Logger log = LogManager.getLogger(BgfxSceneMaterial.class);
    private static final AtomicLong DIAGNOSTIC_FALLBACKS = new AtomicLong();
    private static final int KIND_TEXTURE = 0;
    private static final int KIND_COLOR = 1;
    private static final int KIND_WATER = 2;
    private static final int KIND_COMBINER = 3;
    private static final int KIND_ENVMAP = 4;
    private static final int KIND_REFLECTIVE = 5;

    private final long stableId;
    private final String materialName;
    private final DebugMaterialSlot diagnosticSource;

    private final ProgramFamily programFamily;
    private final long featureBits;
    private final long surfaceType;
    private final BlendMode blendMode;
    private final boolean depthWrite;
    private final AuthoredFramebuffer framebuffer;
    private final BgfxTextureCache cache;
    private final int ordinaryDiffusePageGroupSize;
    private final TextureRef diffuse;
    private final TextureRef opacity;
    private final TextureRef second;
    private final TextureRef mask;
    private final TextureRef specular;
    private final TextureRef specularMask;
    private final TextureRef selfIllumination;
    private final TextureRef selfIlluminationMask;
    private final TextureRef cube;
    private final List<TextureRef> frames;
    private final List<TextureRef> ownedTextures;
    private final float framesPerSecond;
    private final FadeSpec secondFade;
    private final FadeSpec specularFade;
    private final boolean layeredWater;
    private final BgfxAuthoredMaterial authoredWater;
    private final BgfxAuthoredPasses authoredPasses;
    private final boolean diagnostic;
    private final boolean premultipliedSpecular;
    private final float[] uniforms;
    private final float[] bindingTemplate;
    private final int bindingHash;
    private boolean closed;

    private BgfxSceneMaterial(long stableId, Surface surface, Builder builder) {
        this.stableId = stableId;
        this.materialName = surface.materialName();
        this.diagnosticSource = DebugMaterialSlot.fromSurface(0, surface);
        this.programFamily = selectProgramFamily(surface, builder);
        this.featureBits = featureBits(surface, builder);
        this.surfaceType = surface.type();
        this.framebuffer = builder.framebuffer;
        this.blendMode = framebuffer == null ? surface.blendMode() : framebuffer.drawOrderBlend();
        this.depthWrite = framebuffer == null ? surface.depthWrite() : framebuffer.depthWrite();
        this.cache = builder.cache;
        this.ordinaryDiffusePageGroupSize = builder.cache.getOrdinaryDiffusePageGroupSize();
        this.diffuse = builder.diffuse;
        this.opacity = builder.opacity;
        this.second = builder.second;
        this.mask = builder.mask;
        this.specular = builder.specular;
        this.specularMask = builder.specularMask;
        this.selfIllumination = builder.selfIllumination;
        this.selfIlluminationMask = builder.selfIlluminationMask;
        this.cube = builder.cube;
        this.frames = List.copyOf(builder.frames);
        this.ownedTextures = List.copyOf(builder.ownedTextures);
        this.framesPerSecond = builder.framesPerSecond;
        this.secondFade = builder.secondFade;
        this.specularFade = builder.specularFade;
        this.layeredWater = builder.layeredWater;
        this.authoredWater = builder.authoredWater;
        this.authoredPasses = builder.authoredPasses;
        this.diagnostic = builder.diagnostic;
        this.premultipliedSpecular = isSpecularPremultiplied(programFamily, blendMode, builder);
        this.uniforms = builder.uniforms;

        put(0, builder.baseColor);
        put(1, builder.tint);
        put(
                2,
                builder.kind,
                builder.constantOpacity >= 0f
                        ? -1f - builder.constantOpacity
                        : packedLayerFlag(builder.opacity, builder.hasOpacity),
                packedLayerFlag(builder.second, builder.hasSecond),
                packedLayerFlag(builder.mask, builder.hasMask));
        var activeReference = framebuffer == null ? surface.alphaRef() : framebuffer.alphaReference();
        var alphaRef = activeReference >= 0
                ? activeReference / 255f
                : builder.kind == KIND_WATER || surface.water() ? -1f : 0.25f;
        put(
                3,
                alphaRef,
                shaderBlendCode(blendMode),
                vertexStreamFlags(surface.type(), surface.usesVertexColor()) + (builder.hasColorModifier ? 4f : 0f),
                builder.diffuse.getBaseLayer());
        var uv = surface.uvAnimation();
        put(4, uv.mode(), 0f, uv.paramX(), uv.paramY());
        put(5, uv.paramZ(), uv.paramW(), builder.combineOp, builder.combineModulate);
        put(
                6,
                builder.combineInvert ? 1f : 0f,
                builder.combineAlphaOp,
                builder.combineVertexMask ? 1f : 0f,
                builder.secondCube == null ? 0f : builder.secondCube.envMapType() + 1f);
        put(7, builder.waterAlpha, builder.waterTint.getX(), builder.waterTint.getY(), builder.waterTint.getZ());
        put(
                8,
                packedLayerFlag(builder.specular, builder.hasSpecular),
                packedLayerFlag(builder.specularMask, builder.hasSpecularMask),
                builder.specularModulate2x ? 1f : 0f,
                builder.specularLit ? 1f : 0f);
        if (builder.secondCube != null || builder.specularCube != null) {
            var quaternion =
                    envCubeRotationQuaternion(builder.secondCube != null ? builder.secondCube : builder.specularCube);
            put(9, quaternion[0], quaternion[1], quaternion[2], quaternion[3]);
        } else {
            put(9, 1f, 1f, 1f, 1f);
        }
        var lightingFlags = (surface.modulateStaticLighting2x() ? 1 : 0)
                | (programFamily == ProgramFamily.WATER && (surface.type() & SurfaceType.STATIC_MESH.getId()) != 0
                        ? 2
                        : 0);
        put(10, builder.reflectivity, builder.reflective ? 1f : 0f, isUnlit(surface) ? 1f : 0f, lightingFlags);
        put(11, 0f, 0f, builder.specularEmissive ? 1f : 0f, premultipliedSpecular ? 1f : 0f);
        put(12, 1f, 1f, 1f, 1f);
        if (builder.layeredWater) {
            var waterUv = builder.waterSecondUv;
            put(13, waterUv.paramX(), waterUv.paramY(), waterUv.paramZ(), waterUv.paramW());
            put(14, 0f, 0f, 0f, 0f);
            put(15, 1f, builder.waterSecondRotation, waterUv.mode(), 0f);
        } else {
            var specUv = builder.specularUv;
            put(13, specUv.mode(), 0f, specUv.paramX(), specUv.paramY());
            put(
                    14,
                    specUv.paramZ(),
                    specUv.paramW(),
                    builder.specularMaskUvChannel + 2f * builder.specularSource,
                    specUv.extra());
            put(15, 0f, 0f, 0f, builder.diagnostic ? 1f : 0f);
        }
        var selfUv = builder.selfIlluminationUv;
        put(
                16,
                packedLayerFlag(builder.selfIllumination, builder.hasSelfIllumination),
                packedLayerFlag(builder.selfIlluminationMask, builder.hasSelfIllumination),
                selfUv.mode()
                        + (builder.selfIlluminationMaskUvChannel > 0 ? 8f : 0f)
                        + 16f * builder.selfIlluminationSource,
                selfUv.extra());
        put(17, selfUv.paramX(), selfUv.paramY(), selfUv.paramZ(), selfUv.paramW());
        if (programFamily == ProgramFamily.WATER) {
            var color = framebuffer == null ? -1 : framebuffer.fogColor();
            put(17, color >= 0 ? 1f : 0f, (color >> 16 & 255) / 255f, (color >> 8 & 255) / 255f, (color & 255) / 255f);
        }
        this.bindingTemplate = uniforms.clone();
        this.bindingHash = 31
                        * Objects.hash(
                                programFamily,
                                featureBits,
                                surfaceType,
                                blendMode,
                                depthWrite,
                                diffuse,
                                opacity,
                                second,
                                mask,
                                specular,
                                specularMask,
                                cube,
                                selfIllumination,
                                selfIlluminationMask,
                                frames,
                                framesPerSecond,
                                secondFade,
                                specularFade,
                                diagnostic,
                                authoredWater,
                                authoredPasses,
                                framebuffer)
                + Arrays.hashCode(bindingTemplate);
    }

    static BgfxSceneMaterial create(long stableId, Surface surface, BgfxTextureCache cache) {
        var builder = new Builder(cache, surface.materialName());
        try {
            builder.apply(surface.material(), "diffuse");
            return new BgfxSceneMaterial(stableId, surface, builder);
        } catch (UnsupportedMaterialException failure) {
            builder.closeAfterFailure(failure);
            log.error(
                    "Material fallback: stableId={} materialName={} surfaceType={} "
                            + "materialClass={} errorType={} message={}",
                    Long.toUnsignedString(stableId),
                    surface.materialName(),
                    Long.toUnsignedString(surface.type()),
                    surface.material().getClass().getName(),
                    failure.getClass().getName(),
                    failure.getMessage(),
                    failure);
            var diagnosticBuilder = new Builder(cache, surface.materialName());
            diagnosticBuilder.useDiagnostic();
            try {
                var result = new BgfxSceneMaterial(stableId, surface, diagnosticBuilder);
                DIAGNOSTIC_FALLBACKS.incrementAndGet();
                return result;
            } catch (RuntimeException | Error diagnosticFailure) {
                diagnosticBuilder.closeAfterFailure(diagnosticFailure);
                failure.addSuppressed(diagnosticFailure);
                throw failure;
            }
        } catch (Error failure) {
            builder.closeAfterFailure(failure);
            throw failure;
        }
    }

    static long diagnosticFallbackCount() {
        return DIAGNOSTIC_FALLBACKS.get();
    }

    static int gpuTextureSlotMask(ProgramFamily family) {
        return switch (family) {
            case ORDINARY -> 1 << 0;
            case CSG -> 1 << 0 | 1 << 6;
            case COMPLEX -> 0xff | 1 << 12 | 1 << 13;
            case WATER -> 1 << 0 | 1 << 3 | 1 << 6;
        };
    }

    static long gpuBatchSurfaceType(long surfaceType) {
        return surfaceType
                & (SurfaceType.CSG.getId()
                        | SurfaceType.STATIC_MESH.getId()
                        | SurfaceType.TWO_SIDED_DOUBLED.getId()
                        | SurfaceType.MESH_TWO_SIDED.getId()
                        | SurfaceType.MESH_MIRRORED.getId()
                        | SurfaceType.CSG_CULL_DISABLED.getId()
                        | SurfaceType.CSG_NATIVE_PLANE_SIDE.getId());
    }

    static float vertexStreamFlags(long surfaceType, boolean usesVertexColor) {
        var flags = usesVertexColor ? 1 : 0;
        if ((surfaceType & SurfaceType.VERTEX_ALPHA.getId()) != 0) {
            flags |= 2;
        }
        return flags;
    }

    static boolean usesMaskedFoliageMipBias(long surfaceType) {
        return (surfaceType & SurfaceType.TWO_SIDED_DOUBLED.getId()) != 0;
    }

    static float[] envCubeRotationQuaternion(EnvCubeLayer layer) {
        var m = layer.rotationMatrix();
        var m00 = m[0];
        var m01 = m[3];
        var m02 = m[6];
        var m10 = m[1];
        var m11 = m[4];
        var m12 = m[7];
        var m20 = m[2];
        var m21 = m[5];
        var m22 = m[8];
        var trace = m00 + m11 + m22;
        float x;
        float y;
        float z;
        float w;
        if (trace > 0f) {
            var scale = (float) Math.sqrt(trace + 1f) * 2f;
            w = 0.25f * scale;
            x = (m21 - m12) / scale;
            y = (m02 - m20) / scale;
            z = (m10 - m01) / scale;
        } else if (m00 > m11 && m00 > m22) {
            var scale = (float) Math.sqrt(1f + m00 - m11 - m22) * 2f;
            w = (m21 - m12) / scale;
            x = 0.25f * scale;
            y = (m01 + m10) / scale;
            z = (m02 + m20) / scale;
        } else if (m11 > m22) {
            var scale = (float) Math.sqrt(1f + m11 - m00 - m22) * 2f;
            w = (m02 - m20) / scale;
            x = (m01 + m10) / scale;
            y = 0.25f * scale;
            z = (m12 + m21) / scale;
        } else {
            var scale = (float) Math.sqrt(1f + m22 - m00 - m11) * 2f;
            w = (m10 - m01) / scale;
            x = (m02 + m20) / scale;
            y = (m12 + m21) / scale;
            z = 0.25f * scale;
        }
        return new float[] {x, y, z, w};
    }

    static int flipbookFrame(float time, long renderFrame, float framesPerSecond, int count) {
        if (count <= 0) {
            return 0;
        }
        var raw = framesPerSecond > 0f
                ? (long) Math.floor(Math.max(0f, time) * framesPerSecond)
                : Math.max(0L, renderFrame);
        return (int) (raw % count);
    }

    static boolean isSpecularPremultiplied(ProgramFamily family, BlendMode blend, Builder builder) {
        return family == ProgramFamily.COMPLEX
                && builder.hasSpecular
                && builder.specularSeparateAlpha
                && (blend == BlendMode.TRANSLUCENT || blend == BlendMode.MASKED_BLEND);
    }

    static int cubeSpecularSource(EnvCubeLayer layer) {
        return layer.envMapType() == 0 ? 8 : 7;
    }

    static int environmentSourceOf(@Nullable EnvironmentLayer layer) {
        if (layer == null) {
            return 0;
        }
        if (layer.fixedFunction()) {
            return switch (layer.source()) {
                case CAMERA_REFLECTION -> 4;
                case WORLD_REFLECTION -> 5;
                case SPHERE_MAP -> 6;
                default -> throw new UnsupportedMaterialException("an environment layer reads a reflection");
            };
        }
        return switch (layer.source()) {
            case CAMERA_REFLECTION -> layer.specularPoint() ? 1 : 2;
            case SPHERE_MAP -> 3;
            default -> throw new UnsupportedMaterialException("an environment layer reads a view space vector");
        };
    }

    static boolean isUnlit(Surface surface) {
        return switch (surface.blendMode()) {
            case ADDITIVE, SCREEN, DARKEN, MODULATE -> (surface.type() & SurfaceType.STATIC_MESH.getId()) == 0;
            case EMISSIVE, UNLIT -> true;
            default -> false;
        };
    }

    DebugMaterialSlot getDiagnosticSlot(int slot) {
        return new DebugMaterialSlot(
                slot,
                diagnosticSource.name(),
                diagnosticSource.blend(),
                diagnosticSource.materialType(),
                diagnosticSource.uvAnimation(),
                diagnosticSource.animated());
    }

    long getStableId() {
        return stableId;
    }

    String getMaterialName() {
        return materialName;
    }

    ProgramFamily getProgramFamily() {
        return programFamily;
    }

    void collectTextures(Set<TextureRef> target) {
        target.addAll(ownedTextures);
    }

    long getFeatureBits() {
        return featureBits;
    }

    long getSurfaceType() {
        return surfaceType;
    }

    boolean hasPremultipliedSpecular() {
        return premultipliedSpecular;
    }

    BlendMode getBlendMode() {
        return blendMode;
    }

    boolean hasDepthWrite() {
        return depthWrite;
    }

    boolean isDiagnostic() {
        return diagnostic;
    }

    boolean isTranslucent() {
        return switch (blendMode) {
            case TRANSLUCENT, ADDITIVE, BRIGHTEN, MODULATE, SCREEN, DARKEN, EMISSIVE -> true;
            case OPAQUE, MASKED, UNLIT, INVISIBLE, MASKED_BLEND -> false;
        };
    }

    AuthoredFramebuffer getFramebuffer() {
        return framebuffer;
    }

    int getBindingHash() {
        return bindingHash;
    }

    boolean isSameBinding(BgfxSceneMaterial other) {
        return other != null
                && bindingHash == other.bindingHash
                && programFamily == other.programFamily
                && featureBits == other.featureBits
                && surfaceType == other.surfaceType
                && blendMode == other.blendMode
                && depthWrite == other.depthWrite
                && Objects.equals(framebuffer, other.framebuffer)
                && Objects.equals(diffuse, other.diffuse)
                && Objects.equals(opacity, other.opacity)
                && Objects.equals(second, other.second)
                && Objects.equals(mask, other.mask)
                && Objects.equals(specular, other.specular)
                && Objects.equals(specularMask, other.specularMask)
                && Objects.equals(selfIllumination, other.selfIllumination)
                && Objects.equals(selfIlluminationMask, other.selfIlluminationMask)
                && Objects.equals(cube, other.cube)
                && frames.equals(other.frames)
                && Float.floatToIntBits(framesPerSecond) == Float.floatToIntBits(other.framesPerSecond)
                && Objects.equals(secondFade, other.secondFade)
                && Objects.equals(specularFade, other.specularFade)
                && authoredWater == other.authoredWater
                && authoredPasses == other.authoredPasses
                && Arrays.equals(bindingTemplate, other.bindingTemplate);
    }

    boolean isSameGpuBatch(BgfxSceneMaterial other, TextureRef lightmap, TextureRef otherLightmap) {
        if (other == null
                || premultipliedSpecular != other.premultipliedSpecular
                || programFamily != other.programFamily
                || gpuBatchSurfaceType(surfaceType) != gpuBatchSurfaceType(other.surfaceType)
                || blendMode != other.blendMode
                || depthWrite != other.depthWrite
                || !Objects.equals(framebuffer, other.framebuffer)) {
            return false;
        }
        if ((!frames.isEmpty() || !other.frames.isEmpty()) && this != other) {
            return false;
        }
        var slots = gpuTextureSlotMask(programFamily);
        if (programFamily == ProgramFamily.ORDINARY && ordinaryDiffusePageGroupSize > 1) {
            var left = gpuTexture(0, diffuse, lightmap);
            var right = other.gpuTexture(0, other.diffuse, otherLightmap);
            var samePageGroup =
                    ordinaryDiffusePageGroupSize >= 4 ? left.isSameGpuPageQuad(right) : left.isSameGpuPagePair(right);
            if (!samePageGroup) {
                return false;
            }
            slots &= ~(1 << 0);
        }
        for (var slot = 0; slot < GpuTextureBindingCache.SLOTS; slot++) {
            if ((slots & 1 << slot) != 0
                    && !isSameGpuResource(
                            gpuTexture(slot, diffuse, lightmap),
                            other.gpuTexture(slot, other.diffuse, otherLightmap))) {
                return false;
            }
        }
        return true;
    }

    int computeGpuBatchHash(TextureRef lightmap) {
        var result = 1;
        result = 31 * result + programFamily.hashCode();
        result = 31 * result + Long.hashCode(gpuBatchSurfaceType(surfaceType));
        result = 31 * result + blendMode.hashCode();
        result = 31 * result + Boolean.hashCode(depthWrite);
        if (!frames.isEmpty()) {
            result = 31 * result + System.identityHashCode(this);
        }
        var slots = gpuTextureSlotMask(programFamily);
        if (programFamily == ProgramFamily.ORDINARY && ordinaryDiffusePageGroupSize > 1) {
            var ordinaryDiffuse = gpuTexture(0, diffuse, lightmap);
            result = 31 * result
                    + (ordinaryDiffusePageGroupSize >= 4
                            ? ordinaryDiffuse.getGpuPageQuadHash()
                            : ordinaryDiffuse.getGpuPagePairHash());
            slots &= ~(1 << 0);
        }
        for (var slot = 0; slot < GpuTextureBindingCache.SLOTS; slot++) {
            if ((slots & 1 << slot) != 0) {
                result = 31 * result + gpuResourceHash(gpuTexture(slot, diffuse, lightmap));
            }
        }
        return result;
    }

    void bind(
            BgfxSceneUniforms sceneUniforms,
            float time,
            boolean texturesEnabled,
            TextureRef lightmap,
            float lightmapLayer,
            float semanticDepthBias) {
        bind(
                sceneUniforms,
                time,
                texturesEnabled,
                lightmap,
                lightmapLayer,
                semanticDepthBias,
                WaterParameters.AUTHORED);
    }

    void bindShadow(
            BgfxUniform shadowMaterial,
            BgfxUniform shadowDiffuse,
            BgfxUniform shadowOpacity,
            float[] target,
            float time,
            boolean texturesEnabled,
            boolean foliage) {
        var activeDiffuse = writeShadowRecord(target, time, texturesEnabled, foliage);
        bgfx_set_texture(
                0, shadowDiffuse.getHandle(), activeDiffuse.getTexture().getHandle(), activeDiffuse.getSamplerFlags());
        bgfx_set_texture(1, shadowOpacity.getHandle(), opacity.getTexture().getHandle(), opacity.getSamplerFlags());
        bgfx_set_uniform(shadowMaterial.getHandle(), target, 4);
    }

    TextureRef writeShadowRecord(float[] target, float time, boolean texturesEnabled, boolean foliage) {
        if (target == null || target.length < 16) {
            throw new IllegalArgumentException("shadow material target requires four vectors");
        }
        var activeDiffuse = prepareUniforms(time, texturesEnabled, null, 0f, 0f, WaterParameters.AUTHORED);
        Arrays.fill(target, 0f);
        target[0] = uniforms[3 * 4];
        target[1] = uniforms[3 * 4 + 1];
        target[2] = uniforms[3 * 4 + 3];
        target[3] = repackLayer(bindingTemplate[2 * 4 + 1], opacity);
        System.arraycopy(uniforms, 4 * 4, target, 4, 4);
        System.arraycopy(uniforms, 5 * 4, target, 8, 2);
        target[12] = uniforms[1 * 4 + 3];
        target[13] = foliage || usesMaskedFoliageMipBias(surfaceType) ? 1f : 0f;
        return activeDiffuse;
    }

    void bind(
            BgfxSceneUniforms sceneUniforms,
            float time,
            boolean texturesEnabled,
            TextureRef lightmap,
            float lightmapLayer,
            float semanticDepthBias,
            WaterParameters water) {
        bind(sceneUniforms, time, texturesEnabled, lightmap, lightmapLayer, semanticDepthBias, water, false, 0);
    }

    int getAuthoredDrawCount(WaterParameters water, boolean nativeStaticMesh) {
        return usesAuthoredPasses(water, nativeStaticMesh) ? authoredPasses.getDrawCount() : 1;
    }

    boolean usesAuthoredPasses(WaterParameters water, boolean nativeStaticMesh) {
        return authoredPasses != null && nativeStaticMesh && (water == null || water.route() == 0);
    }

    long getAuthoredPassState(long state, WaterParameters water, boolean nativeStaticMesh, int draw) {
        return getAuthoredPassState(state, water, nativeStaticMesh, draw, false, false);
    }

    long getAuthoredPassState(
            long state, WaterParameters water, boolean nativeStaticMesh, int draw, boolean ortho, boolean mirrored) {
        if (usesAuthoredPasses(water, nativeStaticMesh) && authoredPasses.getFramebuffer(draw) != null) {
            return BgfxSceneState.authoredFramebuffer(
                    authoredPasses.getFramebuffer(draw), surfaceType, ortho, mirrored);
        }
        return usesAuthoredPasses(water, nativeStaticMesh) ? authoredPasses.getState(state, draw) : state;
    }

    void bind(
            BgfxSceneUniforms sceneUniforms,
            float time,
            boolean texturesEnabled,
            TextureRef lightmap,
            float lightmapLayer,
            float semanticDepthBias,
            WaterParameters water,
            boolean nativeStaticMesh,
            int draw) {
        bind(
                sceneUniforms,
                time,
                texturesEnabled,
                lightmap,
                lightmapLayer,
                semanticDepthBias,
                water,
                nativeStaticMesh,
                draw,
                null);
    }

    void bind(
            BgfxSceneUniforms sceneUniforms,
            float time,
            boolean texturesEnabled,
            TextureRef lightmap,
            float lightmapLayer,
            float semanticDepthBias,
            WaterParameters water,
            boolean nativeStaticMesh,
            int draw,
            NativeStaticMeshLightingFeatures lighting) {
        bind(
                sceneUniforms,
                time,
                texturesEnabled,
                lightmap,
                lightmapLayer,
                semanticDepthBias,
                water,
                nativeStaticMesh,
                draw,
                lighting,
                false);
    }

    void bind(
            BgfxSceneUniforms sceneUniforms,
            float time,
            boolean texturesEnabled,
            TextureRef lightmap,
            float lightmapLayer,
            float semanticDepthBias,
            WaterParameters water,
            boolean nativeStaticMesh,
            int draw,
            NativeStaticMeshLightingFeatures lighting,
            boolean useTangent) {
        Objects.checkIndex(draw, getAuthoredDrawCount(water, nativeStaticMesh));
        var activeDiffuse = prepareUniforms(time, texturesEnabled, lightmap, lightmapLayer, semanticDepthBias, water);
        var diffuseFlags = programFamily == ProgramFamily.WATER
                        && water != null
                        && water.route() == WaterRenderRoute.PS19
                        && water.texture() != null
                        && texturesEnabled
                ? water.texture().samplerFlags()
                : activeDiffuse.getSamplerFlags();
        bindTextures(sceneUniforms, activeDiffuse, lightmap, diffuseFlags);
        if (programFamily == ProgramFamily.WATER) {
            sceneUniforms.bindNativeShaderMaps(null);
        }
        if (usesAuthoredPasses(water, nativeStaticMesh)) {
            if (draw == 0) {
                authoredPasses.prepare(time, time, texturesEnabled, cache.getWhite());
            }
            authoredPasses.bindTextures(sceneUniforms, texturesEnabled);
            sceneUniforms.bindNativeShaderMaps(authoredPasses.getShaderMaps(lighting != null, useTangent));
            sceneUniforms.bindAuthoredPasses(authoredPasses.draw(draw, lighting));
            var passFramebuffer = authoredPasses.getFramebuffer(draw);
            if (passFramebuffer != null) {
                uniforms[3 * 4] = passFramebuffer.alphaReference() < 0 ? -1f : passFramebuffer.alphaReference() / 255f;
                var fog = passFramebuffer.fogColor();
                put(17, fog >= 0 ? 1f : 0f, (fog >> 16 & 255) / 255f, (fog >> 8 & 255) / 255f, (fog & 255) / 255f);
            }
            uniforms[4 * 4] = 0;
            uniforms[15 * 4] = 3;
        } else if (authoredWater != null && (water == null || water.route() == 0)) {
            authoredWater.bind(sceneUniforms, time, texturesEnabled, cache);
        }
        sceneUniforms.bindVertexMaterial(uniforms);
        sceneUniforms.bindMaterial(uniforms);
    }

    void bindGpuTextures(BgfxSceneUniforms sceneUniforms, float time, boolean texturesEnabled, TextureRef lightmap) {
        var activeDiffuse = activeDiffuse(time, texturesEnabled);
        var slots = gpuTextureSlotMask(programFamily);
        if (programFamily == ProgramFamily.ORDINARY) {
            bindGpuOrdinaryDiffusePages(sceneUniforms, activeDiffuse);
            slots &= ~(1 << 0);
        }
        for (var slot = 0; slot < GpuTextureBindingCache.SLOTS; slot++) {
            if ((slots & 1 << slot) != 0) {
                bind(slot, sceneUniforms, gpuTexture(slot, activeDiffuse, lightmap));
            }
        }
    }

    void bindGpuTextures(
            BgfxSceneUniforms sceneUniforms,
            float time,
            boolean texturesEnabled,
            TextureRef lightmap,
            GpuTextureBindingCache bindings) {
        var activeDiffuse = activeDiffuse(time, texturesEnabled);
        var slots = gpuTextureSlotMask(programFamily);
        if (programFamily == ProgramFamily.ORDINARY) {
            bindings.bindOrdinaryDiffusePages(sceneUniforms, activeDiffuse, ordinaryDiffusePageGroupSize);
            slots &= ~(1 << 0);
        }
        for (var slot = 0; slot < GpuTextureBindingCache.SLOTS; slot++) {
            if ((slots & 1 << slot) != 0) {
                bindings.bind(slot, sceneUniforms, gpuTexture(slot, activeDiffuse, lightmap));
            }
        }
    }

    void writeGpuRecord(
            FloatBuffer target,
            float time,
            boolean texturesEnabled,
            TextureRef lightmap,
            float lightmapLayer,
            float semanticDepthBias,
            boolean foliage) {
        if (target == null || target.remaining() < BgfxSceneUniforms.MATERIAL_VECTORS * 4) {
            throw new IllegalArgumentException("GPU material record destination is too small");
        }
        var activeDiffuse = prepareUniforms(
                time, texturesEnabled, lightmap, lightmapLayer, semanticDepthBias, WaterParameters.AUTHORED);
        if (programFamily == ProgramFamily.ORDINARY && ordinaryDiffusePageGroupSize > 1) {
            var pageOrdinal = activeDiffuse.getGpuPageGroupOrdinal(ordinaryDiffusePageGroupSize);
            if (pageOrdinal > 0) {
                uniforms[3 * 4 + 3] = -(pageOrdinal * ENCODED_PAGE_LAYER_STRIDE + activeDiffuse.getBaseLayer() + 1f);
            }
        }
        uniforms[4 * 4 + 1] = 0f;
        if (layeredWater) {
            uniforms[15 * 4 + 3] = 0f;
        } else {
            uniforms[13 * 4 + 1] = 0f;
        }
        uniforms[2 * 4] = bindingTemplate[2 * 4] + (foliage ? 16f : 0f);
        target.put(uniforms, 0, BgfxSceneUniforms.MATERIAL_VECTORS * 4);
    }

    boolean isGpuRecordTimeDynamic() {
        return !frames.isEmpty() || secondFade != null || specularFade != null;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        RuntimeException failure = null;
        for (var index = ownedTextures.size() - 1; index >= 0; index--) {
            try {
                ownedTextures.get(index).close();
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private TextureRef prepareUniforms(
            float time,
            boolean texturesEnabled,
            TextureRef lightmap,
            float lightmapLayer,
            float semanticDepthBias,
            WaterParameters water) {
        var activeDiffuse = activeDiffuse(time, texturesEnabled);
        uniforms[3 * 4] = bindingTemplate[3 * 4];
        if (programFamily == ProgramFamily.WATER) {
            System.arraycopy(bindingTemplate, 17 * 4, uniforms, 17 * 4, 4);
        }
        uniforms[2 * 4] = bindingTemplate[2 * 4];
        uniforms[3 * 4 + 3] = activeDiffuse.getBaseLayer();
        uniforms[2 * 4 + 1] = repackLayer(bindingTemplate[2 * 4 + 1], opacity);
        uniforms[2 * 4 + 2] = repackLayer(bindingTemplate[2 * 4 + 2], second);
        uniforms[2 * 4 + 3] = repackLayer(bindingTemplate[2 * 4 + 3], mask);
        uniforms[8 * 4] = repackLayer(bindingTemplate[8 * 4], specular);
        uniforms[8 * 4 + 1] = repackLayer(bindingTemplate[8 * 4 + 1], specularMask);
        uniforms[16 * 4] = repackLayer(bindingTemplate[16 * 4], selfIllumination);
        uniforms[16 * 4 + 1] = repackLayer(bindingTemplate[16 * 4 + 1], selfIlluminationMask);
        var diffuseDescriptor = activeDiffuse.getTexture().getDescriptor();
        var maskedFoliage = usesMaskedFoliageMipBias(surfaceType);
        if (programFamily == ProgramFamily.ORDINARY) {
            uniforms[2 * 4 + 1] = maskedFoliage ? diffuseDescriptor.width() : -diffuseDescriptor.width();
            uniforms[2 * 4 + 2] = diffuseDescriptor.height();
        } else if (programFamily != ProgramFamily.WATER) {
            uniforms[15 * 4] = diffuseDescriptor.width();
            uniforms[15 * 4 + 1] = diffuseDescriptor.height();
            uniforms[15 * 4 + 2] = maskedFoliage ? 1f : 0f;
        }

        uniforms[4 * 4 + 1] = time;
        if (layeredWater) {
            uniforms[15 * 4 + 3] = time;
        } else {
            uniforms[13 * 4 + 1] = time;
        }
        uniforms[11 * 4] = semanticDepthBias;
        uniforms[11 * 4 + 1] = lightmap == null ? 0f : 1f;
        uniforms[11 * 4 + 2] = lightmapLayer;
        putFade(9, specularFade, time);
        putFade(12, secondFade, time);
        if (programFamily == ProgramFamily.WATER) {
            var activeWater = water == null ? WaterParameters.AUTHORED : water;
            uniforms[3 * 4] = activeWater.route() == 0 ? bindingTemplate[3 * 4] : -1f;
            uniforms[4 * 4] = activeWater.route() == 0 && authoredWater == null ? bindingTemplate[4 * 4] : 0f;
            uniforms[15 * 4] = authoredWater != null && activeWater.route() == 0 ? 2f : bindingTemplate[15 * 4];
            uniforms[13 * 4] = bindingTemplate[13 * 4];
            uniforms[13 * 4 + 1] = layeredWater ? bindingTemplate[13 * 4 + 1] : time;
            if (activeWater.route() == WaterRenderRoute.LEGACY_REFLECTION) {
                var bump = activeWater.legacyBump();
                uniforms[13 * 4] = bump == null ? 0f : bump.cosine();
                uniforms[13 * 4 + 1] = bump == null ? 0f : bump.sine();
            }
            if (activeWater.route() == WaterRenderRoute.PS19 && activeWater.texture() != null) {
                var binding = activeWater.texture();
                activeDiffuse = texturesEnabled ? binding.texture() : cache.getPagedBlack();
                uniforms[3 * 4 + 3] = texturesEnabled ? binding.layer() : activeDiffuse.getBaseLayer();
                uniforms[11 * 4 + 1] = binding.useLightmap() ? 1f : 0f;
            }
            put(
                    12,
                    activeWater.route(),
                    activeWater.reflectionReference(),
                    activeWater.bumpAmplitude(),
                    activeWater.hasNormal() ? 1f : 0f);
            put(14, activeWater.colorReference());
        }
        return activeDiffuse;
    }

    private TextureRef activeDiffuse(float time, boolean texturesEnabled) {
        var result = activeFrame(time);
        if (!texturesEnabled) {
            result = uniforms[2 * 4] == KIND_COLOR ? cache.getPagedWhite() : cache.getPagedBlack();
        }
        return result;
    }

    private void bindTextures(
            BgfxSceneUniforms sceneUniforms, TextureRef activeDiffuse, TextureRef lightmap, int diffuseFlags) {
        for (var slots = gpuTextureSlotMask(programFamily); slots != 0; slots &= slots - 1) {
            var slot = Integer.numberOfTrailingZeros(slots);
            var texture = gpuTexture(slot, activeDiffuse, lightmap);
            sceneUniforms.bindTexture(
                    slot,
                    sceneUniforms.getSceneSampler(slot).getHandle(),
                    texture.getTexture().getHandle(),
                    slot == 0 ? diffuseFlags : texture.getSamplerFlags());
        }
    }

    private TextureRef gpuTexture(int slot, TextureRef activeDiffuse, TextureRef lightmap) {
        return switch (slot) {
            case 0 -> activeDiffuse;
            case 1 -> opacity;
            case 2 -> second;
            case 3 -> mask;
            case 4 -> specular;
            case 5 -> specularMask;
            case 6 -> lightmap == null ? cache.getPagedWhite() : lightmap;
            case 7 -> cube;
            case 12 -> selfIllumination;
            case 13 -> selfIlluminationMask;
            default -> throw new IllegalArgumentException("unknown scene texture slot " + slot);
        };
    }

    record WaterParameters(
            int route,
            Vec4 colorReference,
            float reflectionReference,
            float bumpAmplitude,
            boolean hasNormal,
            Binding texture,
            BumpMatrix legacyBump) {
        private static final WaterParameters AUTHORED = new WaterParameters(0, new Vec4(0f, 0f, 0f, 0f), 0f, 0f, false);

        WaterParameters(
                int route, Vec4 colorReference, float reflectionReference, float bumpAmplitude, boolean hasNormal) {
            this(route, colorReference, reflectionReference, bumpAmplitude, hasNormal, null);
        }

        WaterParameters(
                int route,
                Vec4 colorReference,
                float reflectionReference,
                float bumpAmplitude,
                boolean hasNormal,
                Binding texture) {
            this(route, colorReference, reflectionReference, bumpAmplitude, hasNormal, texture, null);
        }
    }

    private TextureRef activeFrame(float time) {
        if (frames.isEmpty()) {
            return diffuse;
        }
        return frames.get(flipbookFrame(time, cache.getRenderFrame(), framesPerSecond, frames.size()));
    }

    private void bindGpuOrdinaryDiffusePages(BgfxSceneUniforms uniforms, TextureRef texture) {
        var flags = ordinaryDiffusePageGroupSize <= 1
                ? texture.getSamplerFlags()
                : texture.getGpuPageGroupSamplerFlags(ordinaryDiffusePageGroupSize);
        for (var slot = 0; slot < 4; slot++) {
            var page = texture.getGpuPageGroupTexture(ordinaryDiffusePageGroupSize, slot);
            bgfx_set_texture(slot, uniforms.getSceneSampler(slot).getHandle(), page.getHandle(), flags);
        }
    }

    static final class GpuTextureBindingCache {
        private static final int SLOTS = 14;
        private final int[] handles = new int[SLOTS];
        private final int[] samplerFlags = new int[SLOTS];

        GpuTextureBindingCache() {
            reset();
        }

        void reset() {
            Arrays.fill(handles, -1);
        }

        private void bind(int slot, BgfxSceneUniforms uniforms, TextureRef texture) {
            var handle = Short.toUnsignedInt(texture.getTexture().getHandle());
            var flags = texture.getSamplerFlags();
            if (handles[slot] == handle && samplerFlags[slot] == flags) {
                return;
            }
            bgfx_set_texture(
                    slot,
                    uniforms.getSceneSampler(slot).getHandle(),
                    texture.getTexture().getHandle(),
                    flags);
            handles[slot] = handle;
            samplerFlags[slot] = flags;
        }

        private void bindOrdinaryDiffusePages(
                BgfxSceneUniforms uniforms, TextureRef texture, int ordinaryDiffusePageGroupSize) {
            var flags = ordinaryDiffusePageGroupSize <= 1
                    ? texture.getSamplerFlags()
                    : texture.getGpuPageGroupSamplerFlags(ordinaryDiffusePageGroupSize);
            for (var slot = 0; slot < 4; slot++) {
                bind(
                        slot,
                        uniforms,
                        texture.getGpuPageGroupTexture(ordinaryDiffusePageGroupSize, slot)
                                .getHandle(),
                        flags);
            }
        }

        private void bind(int slot, BgfxSceneUniforms uniforms, short textureHandle, int flags) {
            var handle = Short.toUnsignedInt(textureHandle);
            if (handles[slot] == handle && samplerFlags[slot] == flags) {
                return;
            }
            bgfx_set_texture(slot, uniforms.getSceneSampler(slot).getHandle(), textureHandle, flags);
            handles[slot] = handle;
            samplerFlags[slot] = flags;
        }
    }

    private void putFade(int vector, FadeSpec fade, float time) {
        if (fade == null) {
            return;
        }
        var color = fade.curve.argb(time);
        put(vector, (color >>> 16 & 255) / 255f, (color >>> 8 & 255) / 255f, (color & 255) / 255f, 1f);
    }

    private void put(int vector, Vec4 value) {
        put(vector, value.getX(), value.getY(), value.getZ(), value.getW());
    }

    private void put(int vector, float[] value) {
        put(vector, value[0], value[1], value[2], value[3]);
    }

    private void put(int vector, float x, float y, float z, float w) {
        var offset = vector * 4;
        uniforms[offset] = x;
        uniforms[offset + 1] = y;
        uniforms[offset + 2] = z;
        uniforms[offset + 3] = w;
    }

    private static void bind(int slot, BgfxSceneUniforms uniforms, TextureRef texture) {
        bgfx_set_texture(
                slot,
                uniforms.getSceneSampler(slot).getHandle(),
                texture.getTexture().getHandle(),
                texture.getSamplerFlags());
    }

    private static boolean isSameGpuResource(TextureRef left, TextureRef right) {
        return left == null ? right == null : left.isSameGpuResource(right);
    }

    private static int gpuResourceHash(TextureRef texture) {
        return texture == null ? 0 : texture.getGpuResourceHash();
    }

    private static int shaderBlendCode(BlendMode mode) {
        return switch (mode) {
            case OPAQUE -> 0;
            case TRANSLUCENT -> 1;
            case ADDITIVE, SCREEN -> 2;
            case MODULATE, DARKEN -> 3;
            case EMISSIVE -> 4;
            case UNLIT -> 5;
            case MASKED -> 7;
            case BRIGHTEN -> 8;
            case MASKED_BLEND -> 9;
            case INVISIBLE -> -1;
        };
    }

    private static float packedLayerFlag(TextureRef texture, boolean enabled) {
        return texture.getBaseLayer() * 2f + (enabled ? 1f : 0f);
    }

    private static float repackLayer(float templateValue, TextureRef texture) {
        if (templateValue < 0f) {
            return templateValue;
        }
        return texture.getBaseLayer() * 2f + (((int) templateValue) & 1);
    }

    private static boolean usesTextureAlpha(Surface surface) {
        return (surface.type() & SurfaceType.PASSABLE.getId()) != 0
                || surface.blendMode() == BlendMode.MASKED
                || surface.blendMode() == BlendMode.MASKED_BLEND;
    }

    private static ProgramFamily selectProgramFamily(Surface surface, Builder builder) {
        if (builder.diagnostic) {
            return ProgramFamily.ORDINARY;
        }
        if (surface.water() || builder.kind == KIND_WATER) {
            return ProgramFamily.WATER;
        }
        if ((surface.type() & SurfaceType.CSG.getId()) != 0) {
            return ProgramFamily.CSG;
        }
        if ((surface.type() & SurfaceType.BEAST_LIGHTMAP.getId()) != 0) {
            return ProgramFamily.COMPLEX;
        }
        if (builder.kind == KIND_COMBINER
                || builder.kind == KIND_ENVMAP
                || builder.kind == KIND_REFLECTIVE
                || builder.hasOpacity
                || builder.constantOpacity >= 0f
                || builder.hasSecond
                || builder.hasMask
                || builder.hasSpecular
                || builder.hasSelfIllumination
                || builder.reflective) {
            return ProgramFamily.COMPLEX;
        }
        return ProgramFamily.ORDINARY;
    }

    private static long featureBits(Surface surface, Builder builder) {
        var result = 1L << selectProgramFamily(surface, builder).ordinal();
        if (builder.hasOpacity || builder.constantOpacity >= 0f) {
            result |= 1L << 8;
        }
        if (builder.hasSecond) {
            result |= 1L << 9;
        }
        if (builder.hasMask) {
            result |= 1L << 10;
        }
        if (builder.hasSpecular) {
            result |= 1L << 11;
        }
        if (builder.hasSpecularMask) {
            result |= 1L << 12;
        }
        if (builder.reflective) {
            result |= 1L << 13;
        }
        if (builder.hasSelfIllumination) {
            result |= 1L << 20;
        }
        if (surface.usesVertexColor()) {
            result |= 1L << 14;
        }
        if (surface.depthWrite()) {
            result |= 1L << 15;
        }
        if ((surface.type() & SurfaceType.VERTEX_ALPHA.getId()) != 0) {
            result |= 1L << 30;
        }
        if (builder.diagnostic) {
            result |= 1L << 31;
        }
        result |= (long) (surface.blendMode().ordinal() & 0xf) << 16;
        return result;
    }

    enum ProgramFamily {
        ORDINARY,
        COMPLEX,
        CSG,
        WATER
    }

    private record FadeSpec(UColorFade curve) {
        static FadeSpec of(FadeColorSpec source) {
            if (source == null) {
                return null;
            }
            var curve = new UColorFade(
                    packColor(source.color1()),
                    packColor(source.color2()),
                    source.period(),
                    source.phase(),
                    source.fadeType());
            return curve.isSupported() ? new FadeSpec(curve) : null;
        }

        private static int packColor(Vec4 color) {
            return Math.round(color.getW() * 255f) << 24
                    | Math.round(color.getX() * 255f) << 16
                    | Math.round(color.getY() * 255f) << 8
                    | Math.round(color.getZ() * 255f);
        }
    }

    private static final class Builder {
        private final BgfxTextureCache cache;
        private final String stableName;
        private final float[] uniforms = new float[BgfxSceneUniforms.MATERIAL_VECTORS * 4];
        private final List<TextureRef> frames = new ArrayList<>();
        private final List<TextureRef> ownedTextures = new ArrayList<>();
        private final float[] baseColor = new float[] {1f, 1f, 1f, 1f};
        private final float[] tint = new float[] {1f, 1f, 1f, 1f};
        private boolean hasColorModifier;

        private TextureRef diffuse;
        private TextureRef opacity;
        private TextureRef second;
        private TextureRef mask;
        private TextureRef specular;
        private TextureRef specularMask;
        private TextureRef selfIllumination;
        private TextureRef selfIlluminationMask;
        private boolean hasSelfIllumination;
        private UvAnimation selfIlluminationUv = UvAnimation.NONE;
        private int selfIlluminationMaskUvChannel;
        private int selfIlluminationSource;
        private TextureRef cube;
        private float framesPerSecond;
        private FadeSpec secondFade;
        private FadeSpec specularFade;
        private int kind = KIND_TEXTURE;
        private boolean hasOpacity;
        private float constantOpacity = -1f;
        private boolean hasSecond;
        private boolean hasMask;
        private int combineOp = -1;
        private float combineModulate = 1f;
        private boolean combineInvert;
        private int combineAlphaOp;
        private boolean combineVertexMask;
        private EnvCubeLayer secondCube;
        private EnvCubeLayer specularCube;
        private float waterAlpha = 1f;
        private Vec3 waterTint = new Vec3();
        private boolean hasSpecular;
        private boolean hasSpecularMask;
        private boolean specularModulate2x;
        private boolean specularLit;
        private boolean specularEmissive;
        private boolean specularSeparateAlpha;
        private int specularMaskUvChannel;
        private UvAnimation specularUv = UvAnimation.NONE;
        private int specularSource;
        private float reflectivity;
        private boolean reflective;
        private boolean layeredWater;
        private BgfxAuthoredMaterial authoredWater;
        private BgfxAuthoredPasses authoredPasses;
        private AuthoredFramebuffer framebuffer;
        private boolean diagnostic;
        private UvAnimation waterSecondUv = UvAnimation.NONE;
        private float waterSecondRotation;

        private Builder(BgfxTextureCache cache, String stableName) {
            this.cache = cache;
            this.stableName = stableName;
            diffuse = cache.getPagedWhite();
            opacity = cache.getPagedWhite();
            second = cache.getPagedBlack();
            mask = cache.getPagedWhite();
            specular = cache.getPagedBlack();
            specularMask = cache.getPagedWhite();
            selfIllumination = cache.getPagedBlack();
            selfIlluminationMask = cache.getPagedWhite();
            cube = cache.getWhiteCube();
        }

        private void apply(EntityMaterial material, String role) {
            switch (material) {
                case ColorEntityMaterial color -> {
                    kind = KIND_COLOR;
                    copy(color.color(), baseColor);
                }
                case TextureEntityMaterial texture -> diffuse = load(texture.texture(), name(role));
                case WaterEntityMaterial water -> {
                    kind = KIND_WATER;
                    framebuffer = water.framebuffer();
                    diffuse = load(water.texture(), name(role));
                    waterAlpha = water.alpha();
                    waterTint = new Vec3(water.tint());
                    if (water.authoredPasses() != null && BgfxAuthoredPasses.supports(water.authoredPasses())) {
                        authoredPasses = new BgfxAuthoredPasses(
                                water.authoredPasses(),
                                (texture, suffix) -> load(texture, name(suffix)),
                                cache.getBitmapClocks(),
                                cache.getAuthoredMaterials());
                    }
                    if (water.authoredProgram() != null) {
                        hasColorModifier = water.authoredProgram().colorModifier();
                        authoredWater = new BgfxAuthoredMaterial(
                                water.authoredProgram(),
                                (texture, suffix) -> load(texture, name(suffix)),
                                cache.getBitmapClocks(),
                                cache.getAuthoredMaterials());
                    }
                    if (water.material2() != null) {
                        hasSecond = true;
                        layeredWater = true;
                        waterSecondUv = water.material2Anim();
                        waterSecondRotation = water.material2Rotation();
                    }
                }
                case AnimatedTextureEntityMaterial animated -> {
                    for (var index = 0; index < animated.frames().size(); index++) {
                        frames.add(load(animated.frames().get(index), name("frame-" + index)));
                    }
                    if (!frames.isEmpty()) {
                        diffuse = frames.getFirst();
                    }
                    framesPerSecond = animated.framesPerSecond();
                }
                case TextureEntityMaterialWithOpacity withOpacity -> {
                    diffuse = load(withOpacity.texture(), name(role));
                    if (withOpacity.opacityTexture() != null) {
                        opacity = load(withOpacity.opacityTexture(), name("opacity"));
                        hasOpacity = true;
                    }
                }
                case MultiTextureEntityMaterial _ ->
                    throw new UnsupportedMaterialException(
                            "terrain material cannot be installed in an entity material binding");
                case ReflectiveEntityMaterial reflectiveMaterial -> {
                    kind = KIND_REFLECTIVE;
                    if (reflectiveMaterial.base() != null) {
                        diffuse = load(reflectiveMaterial.base(), name(role));
                    }
                    cube = loadCube(reflectiveMaterial.cubemap(), name("cube"));
                    reflectivity = reflectiveMaterial.reflectivity();
                    reflective = true;
                }
                case EnvMapEntityMaterial envMap -> {
                    kind = KIND_ENVMAP;
                    diffuse = load(envMap.map(), name(role));
                }
                case CombinerEntityMaterial combiner -> {
                    kind = KIND_COMBINER;
                    diffuse = load(combiner.material1(), name(role));
                    if (combiner.material2() != null) {
                        second = load(combiner.material2(), name("second"));
                        hasSecond = true;
                    } else if (combiner.material2Cube() != null) {
                        secondCube = combiner.material2Cube();
                        cube = loadCube(secondCube.cubemap(), name("cube"));
                        hasSecond = true;
                    } else if (combiner.material2Fade() != null) {
                        second = cache.getPagedWhite();
                        secondFade = FadeSpec.of(combiner.material2Fade());
                        hasSecond = true;
                    }
                    if (combiner.mask() != null) {
                        mask = load(combiner.mask(), name("mask"));
                        hasMask = true;
                    }
                    combineOp = combiner.combineOp();
                    combineModulate = combiner.modulate();
                    combineInvert = combiner.invertMask();
                    combineAlphaOp = combiner.alphaOp();
                    combineVertexMask = combiner.vertexColorMask();
                }
                case ColorModifierEntityMaterial modifier -> {
                    apply(modifier.base(), role);
                    tint[0] *= modifier.tint().getX();
                    tint[1] *= modifier.tint().getY();
                    tint[2] *= modifier.tint().getZ();
                    tint[3] *= modifier.tint().getW();
                }
                case ConstantOpacityEntityMaterial opacityMaterial -> {
                    apply(opacityMaterial.base(), role);
                    constantOpacity = opacityMaterial.alpha();
                }
                case SpecularEntityMaterial specularMaterial -> {
                    apply(specularMaterial.base(), role);
                    var overlay = specularMaterial.overlay();
                    if (overlay.selfIllumination()) {
                        if (overlay.texture() == null || overlay.mask() == null) {
                            throw new UnsupportedMaterialException("self illumination requires a bitmap and mask");
                        }
                        hasSelfIllumination = true;
                        selfIllumination = load(overlay.texture(), name("self-illumination"));
                        selfIlluminationMask = load(overlay.mask(), name("self-illumination-mask"));
                        selfIlluminationUv = environmentAnimation(overlay);
                        selfIlluminationMaskUvChannel = overlay.maskUvChannel();
                        selfIlluminationSource = environmentSourceOf(overlay.environment());
                        break;
                    }
                    if (overlay.cube() != null) {
                        if (cube != cache.getWhiteCube() || secondCube != null) {
                            break;
                        }
                        var loaded = loadCube(overlay.cube().cubemap(), name("specular-cube"));
                        if (loaded == cache.getWhite() || loaded == cache.getWhiteCube()) {
                            break;
                        }
                        cube = loaded;
                        specularCube = overlay.cube();
                        specular = cache.getPagedWhite();
                    }
                    hasSpecular = true;
                    if (overlay.fade() != null) {
                        specular = cache.getPagedWhite();
                        specularFade = FadeSpec.of(overlay.fade());
                    } else if (overlay.texture() != null) {
                        specular = load(overlay.texture(), name("specular"));
                    }
                    if (overlay.mask() != null) {
                        specularMask = load(overlay.mask(), name("specular-mask"));
                        hasSpecularMask = true;
                    }
                    specularModulate2x = overlay.modulate2x();
                    specularLit = overlay.lit();
                    specularEmissive = overlay.emissive();
                    specularSeparateAlpha = overlay.separateAlphaPass();
                    specularMaskUvChannel = overlay.maskUvChannel();
                    specularUv = environmentAnimation(overlay);
                    specularSource = specularCube != null
                            ? cubeSpecularSource(specularCube)
                            : environmentSourceOf(overlay.environment());
                }
            }
        }

        private void useDiagnostic() {
            diagnostic = true;
            kind = KIND_COLOR;
            baseColor[0] = 1f;
            baseColor[1] = 0f;
            baseColor[2] = 1f;
            baseColor[3] = 1f;
        }

        private String name(String role) {
            return stableName == null ? null : stableName + '/' + role;
        }

        private TextureRef load(Texture source, String name) {
            var ref = cache.loadPaged(source, name);
            if (!ref.isBaseline()) {
                ownedTextures.add(ref);
            }
            return ref;
        }

        private TextureRef loadCube(Cubemap source, String name) {
            var ref = cache.loadCube(source, name);
            if (ref != cache.getWhiteCube() && ref != cache.getWhite()) {
                ownedTextures.add(ref);
            }
            return ref;
        }

        private void closeAfterFailure(Throwable failure) {
            for (var index = ownedTextures.size() - 1; index >= 0; index--) {
                try {
                    ownedTextures.get(index).close();
                } catch (RuntimeException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            ownedTextures.clear();
        }

        private static void copy(Vec4 source, float[] target) {
            target[0] = source.getX();
            target[1] = source.getY();
            target[2] = source.getZ();
            target[3] = source.getW();
        }

        private static void multiply(float[] target, Vec4 value) {
            target[0] *= value.getX();
            target[1] *= value.getY();
            target[2] *= value.getZ();
            target[3] *= value.getW();
        }

        private static UvAnimation environmentAnimation(SpecularOverlay overlay) {
            return overlay.environment() == null
                    ? overlay.uvAnimation()
                    : overlay.environment().animation();
        }
    }

    private static final class UnsupportedMaterialException extends IllegalArgumentException {
        private UnsupportedMaterialException(String message) {
            super(message);
        }
    }
}
