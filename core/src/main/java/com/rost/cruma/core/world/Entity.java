package com.rost.cruma.core.world;

import com.rost.cruma.core.geometry.Bounds3f;
import com.rost.cruma.core.geometry.Box;
import glm_.mat4x4.Mat4;
import glm_.quat.Quat;
import glm_.vec2.Vec2;
import glm_.vec3.Vec3;
import glm_.vec4.Vec4;
import java.util.AbstractList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.RandomAccess;
import org.jetbrains.annotations.Nullable;

public class Entity {
    public static final int CSG_DEPTH_LAYER_SHIFT = 16;
    public static final int CSG_DEPTH_LAYER_MAX = 0xFFFF;
    public static final long CSG_DEPTH_LAYER_MASK = (long) CSG_DEPTH_LAYER_MAX << CSG_DEPTH_LAYER_SHIFT;

    private static final Mat4 MAT_4_1 = new Mat4(1.0f);
    private static final List<Mat4> EMPTY_INSTANCE_MATRICES = List.of(MAT_4_1);
    private static final Vec3 VEC_3_1 = new Vec3(1.0f);
    private static final Vec3 VEC3_EMPTY = new Vec3();
    private static final Vec2 VEC2_EMPTY = new Vec2();

    private final Entity.Mesh mesh;
    private L2Movement movement;
    private @Nullable MotionLink motionLink;
    private boolean door;
    private Vec3 position;
    private Vec3 rotation;
    private Vec3 scale;
    private boolean acceptsProjectors = true;
    boolean wireframe;
    private String meshName;

    @Nullable
    private String debugName;

    private @Nullable String sourceActor;
    private @Nullable String sourceAsset;
    private List<SourcePart> sourceParts = List.of();

    private ZoneRender zoneRender = ZoneRender.EVERY_STATE;
    private @Nullable ZoneLook zoneLook;

    private int sourceBspSection = -1;
    private int bspOrdinal = -1;
    private int @Nullable [] bspSpans;

    @Nullable
    private List<DebugLabel> debugLabels;

    @Nullable
    private Texture lightmap;

    @Nullable
    private List<Texture> lightmapVariants;

    private List<Integer> lightmapVariantSlots = List.of();
    private boolean beastLightmap;
    private boolean csgConcat;

    @Nullable
    private List<Texture> lightmaps;

    @Nullable
    private List<Texture> csgLightmapVariants;

    private int csgLightmapVariantCount = 1;
    private int @Nullable [] lightmapLayers;
    private @Nullable IdentityHashMap<Texture, Integer> bspLightmapSources;
    private Vec3 instanceLight = VEC3_EMPTY;
    private boolean sunAffected;
    private boolean ambientOnlyLighting;
    private boolean modulateAmbientLighting2x;
    private boolean fullbrightDecoration;
    private List<Vec3> terrainDecorationSteadyLighting = List.of();
    private int terrainDecorationZone = -1;
    private @Nullable MeshDecoration meshDecoration;

    @Nullable
    private ActorVertexLighting actorVertexLighting;

    @Nullable
    private NativeLightReceiver nativeLightReceiver;

    private NativeStaticMeshLightingInputs nativeStaticMeshLightingInputs;
    private NativeVertexLightmapInputs nativeVertexLightmapInputs;
    private NativeStaticMeshDraw nativeStaticMeshDraw;
    private byte @Nullable [] environmentVisibilityMasks;

    @Nullable
    private Vec3 hardwareSunLight;

    @Nullable
    private HardwareSunDirection hardwareSunDirection;

    @Nullable
    private HardwareSunEnvironment hardwareSunEnvironment;

    private @Nullable HardwareSunSource hardwareSunSource;
    private boolean flatSunAmbient;
    private float staticMeshAmbientIntensity = 1f;

    @Nullable
    private StaticMeshAmbient staticMeshAmbient;

    private boolean mergedRegion;

    @Nullable
    private StaticMeshLod staticMeshLod;

    private boolean foliageLighting;
    private float @Nullable [] swayVertexParams;

    public Entity(Entity.Mesh mesh) {
        this.mesh = mesh;
        this.position = VEC3_EMPTY;
        this.rotation = VEC3_EMPTY;
        this.scale = VEC_3_1;
        this.wireframe = false;
    }

    public record HardwareSunEnvironment(float levelBrightness, float lightIntensity) {}

    public record HardwareSunSource(String actorId, float lightIntensity) {}

    public record HardwareSunDirection(
            boolean movable, @Nullable Vec3 fixedDirection) {
        public HardwareSunDirection {
            if (movable == (fixedDirection != null)) {
                throw new IllegalArgumentException("hardware sun requires either movable mode or a fixed direction");
            }
        }
    }

    public enum TextureFormat {
        TEXTURE_RGBA,
        TEXTURE_DXT1,
        TEXTURE_DXT3,
        TEXTURE_DXT5,
        TEXTURE_P8,
    }

    public record StaticMeshLod(
            int level, float distance1, float distance2, boolean authoredBlend, int impostorThresholdMask) {
        public StaticMeshLod(int level, float distance1, float distance2, boolean authoredBlend) {
            this(level, distance1, distance2, authoredBlend, 0);
        }

        public StaticMeshLod {
            if (level < 0
                    || level > 2
                    || !Float.isFinite(distance1)
                    || distance1 <= 0f
                    || !Float.isFinite(distance2)
                    || distance2 < 0f
                    || distance2 > 0f && distance2 <= distance1
                    || level == 2 && distance2 <= 0f) {
                throw new IllegalArgumentException("invalid static-mesh LOD metadata");
            }
        }
    }

    public enum SurfaceType {
        PASSABLE(0x1),
        TERRAIN(0x2),
        STATIC_MESH(0x4),
        CSG(0x8),
        BOUNDING_BOX(0x10),
        IMPORTED_GEODATA(0x20),
        GENERATED_GEODATA(0x40),
        FAKE_BACKDROP(0x80),
        TWO_SIDED_DOUBLED(0x100),
        VERTEX_ALPHA(0x200),
        CSG_CULL_DISABLED(0x400),
        CSG_NATIVE_PLANE_SIDE(0x800),
        BEAST_LIGHTMAP(0x1000),
        MESH_TWO_SIDED(0x2000),
        MESH_MIRRORED(0x4000);

        private final int id;

        SurfaceType(int id) {
            this.id = id;
        }

        public int getId() {
            return this.id;
        }
    }

    public static long csgSurfaceType(int depthLayer) {
        if (depthLayer < 0 || depthLayer > CSG_DEPTH_LAYER_MAX) {
            throw new IllegalArgumentException("CSG depth layer out of range: " + depthLayer);
        }
        return SurfaceType.CSG.getId() | ((long) depthLayer << CSG_DEPTH_LAYER_SHIFT);
    }

    public static int csgDepthLayer(long surfaceType) {
        return (int) ((surfaceType & CSG_DEPTH_LAYER_MASK) >>> CSG_DEPTH_LAYER_SHIFT);
    }

    public enum BlendMode {
        OPAQUE,
        MASKED,
        TRANSLUCENT,
        ADDITIVE,
        BRIGHTEN,
        MODULATE,
        SCREEN,
        DARKEN,
        EMISSIVE,
        UNLIT,
        INVISIBLE,
        MASKED_BLEND
    }

    public record UvAnimation(int mode, float paramX, float paramY, float paramZ, float paramW, float extra) {
        public static final UvAnimation NONE = new UvAnimation(0, 0f, 0f, 0f, 0f);

        public UvAnimation(int mode, float paramX, float paramY, float paramZ, float paramW) {
            this(mode, paramX, paramY, paramZ, paramW, 0f);
        }

        public static UvAnimation pan(float u, float v) {
            return new UvAnimation(1, u, v, 0f, 0f);
        }

        public static UvAnimation rotate(float pivotU, float pivotV, float yawRate) {
            return new UvAnimation(2, pivotU, pivotV, yawRate, 0f);
        }

        public static UvAnimation rotate(float pivotU, float pivotV, float pitchRate, float yawRate, float rollRate) {
            return new UvAnimation(2, pivotU, pivotV, yawRate, pitchRate, rollRate);
        }

        public static UvAnimation oscillate(float uRate, float uAmp, float vRate, float vAmp) {
            return new UvAnimation(3, uRate, uAmp, vRate, vAmp);
        }

        public static UvAnimation scale(float uScale, float vScale, float uOffset, float vOffset) {
            return new UvAnimation(4, uScale, vScale, uOffset, vOffset);
        }

        public static UvAnimation oscillateRotate(float rateHz, float amplitude, float pivotU, float pivotV) {
            return new UvAnimation(5, rateHz, pivotU, amplitude, pivotV);
        }

        public static UvAnimation turnThenScale(
                boolean fixed, float pitch, float yaw, float roll, float uScale, float vScale) {
            return new UvAnimation(fixed ? 7 : 6, uScale, vScale, yaw, pitch, roll);
        }
    }

    public interface EntityTexture {}

    public record Texture(
            TextureFormat format,
            int width,
            int height,
            List<byte[]> data,
            boolean alphaTexture,
            boolean clampU,
            boolean clampV,
            TextureFilters filters,
            int firstMip)
            implements EntityTexture {
        public Texture(
                TextureFormat format,
                int width,
                int height,
                List<byte[]> data,
                boolean alphaTexture,
                boolean clampU,
                boolean clampV,
                TextureFilters filters) {
            this(format, width, height, data, alphaTexture, clampU, clampV, filters, 0);
        }

        public Texture(
                TextureFormat format,
                int width,
                int height,
                List<byte[]> data,
                boolean alphaTexture,
                boolean clampU,
                boolean clampV) {
            this(format, width, height, data, alphaTexture, clampU, clampV, TextureFilters.INHERITED);
        }

        public Texture(TextureFormat format, int width, int height, List<byte[]> data, boolean alphaTexture) {
            this(format, width, height, data, alphaTexture, false, false);
        }

        public Texture uploadView() {
            return uploadView(firstMip);
        }

        public Texture uploadView(int selectedMip) {
            if (selectedMip == 0 && firstMip == 0) {
                return this;
            }
            if (selectedMip < 0
                    || selectedMip >= data.size()
                    || selectedMip >= 31
                    || (width >> selectedMip) == 0
                    || (height >> selectedMip) == 0) {
                throw new IllegalArgumentException("texture first mip has no supplied image: " + selectedMip);
            }
            return new Texture(
                    format,
                    width >> selectedMip,
                    height >> selectedMip,
                    data.subList(selectedMip, data.size()),
                    alphaTexture,
                    clampU,
                    clampV,
                    filters,
                    0);
        }
    }

    public record MultiTexture(List<MultiTextureEntry> entries, List<TerrainAlphaAtlas> packedAlphaGroups)
            implements EntityTexture {
        public MultiTexture(List<MultiTextureEntry> entries) {
            this(entries, List.of());
        }

        public MultiTexture {
            entries = List.copyOf(entries);
            packedAlphaGroups = List.copyOf(packedAlphaGroups);
        }

        public TerrainZoneLayers getZoneLayers() {
            return TerrainZoneLayers.of(entries);
        }
    }

    public record TerrainAlphaAtlas(int width, int height, byte[] rgba) {
        public TerrainAlphaAtlas {
            if (width < 1 || height < 1 || rgba.length != Math.multiplyExact(Math.multiplyExact(width, height), 4)) {
                throw new IllegalArgumentException("invalid packed terrain alpha atlas");
            }
        }
    }

    public record MultiTextureEntry(
            @Nullable Texture texture, @Nullable Texture alphaMap, String name, ZoneRender zoneRender) {
        public MultiTextureEntry(@Nullable Texture texture, @Nullable Texture alphaMap) {
            this(texture, alphaMap, "");
        }

        public MultiTextureEntry(@Nullable Texture texture, @Nullable Texture alphaMap, String name) {
            this(texture, alphaMap, name, ZoneRender.EVERY_STATE);
        }

        public MultiTextureEntry {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(zoneRender, "zoneRender");
        }
    }

    public sealed interface EntityMaterial
            permits ColorEntityMaterial,
                    TextureEntityMaterial,
                    TextureEntityMaterialWithOpacity,
                    MultiTextureEntityMaterial,
                    ReflectiveEntityMaterial,
                    CombinerEntityMaterial,
                    AnimatedTextureEntityMaterial,
                    WaterEntityMaterial,
                    SpecularEntityMaterial,
                    ColorModifierEntityMaterial,
                    ConstantOpacityEntityMaterial,
                    EnvMapEntityMaterial {
        @Nullable
        Texture getTexture();
    }

    public record ColorModifierEntityMaterial(EntityMaterial base, Vec4 tint) implements EntityMaterial {
        @Override
        public @Nullable Texture getTexture() {
            return base.getTexture();
        }
    }

    public record ConstantOpacityEntityMaterial(EntityMaterial base, float alpha) implements EntityMaterial {
        public ConstantOpacityEntityMaterial {
            if (!Float.isFinite(alpha) || alpha < 0f || alpha > 1f) {
                throw new IllegalArgumentException("opacity must be in [0,1]");
            }
        }

        @Override
        public @Nullable Texture getTexture() {
            return base.getTexture();
        }
    }

    public record FadeColorSpec(Vec4 color1, Vec4 color2, float period, float phase, int fadeType) {}

    public record SpecularOverlay(
            @Nullable Texture texture,
            UvAnimation uvAnimation,
            @Nullable Texture mask,
            boolean modulate2x,
            boolean lit,
            @Nullable FadeColorSpec fade,
            boolean emissive,
            boolean separateAlphaPass,
            int maskUvChannel,
            boolean selfIllumination,
            @Nullable EnvironmentLayer environment,
            @Nullable EnvCubeLayer cube) {
        public SpecularOverlay(
                @Nullable Texture texture,
                UvAnimation uvAnimation,
                @Nullable Texture mask,
                boolean modulate2x,
                boolean lit,
                @Nullable FadeColorSpec fade,
                boolean emissive,
                boolean separateAlphaPass,
                int maskUvChannel,
                boolean selfIllumination,
                @Nullable EnvironmentLayer environment) {
            this(
                    texture,
                    uvAnimation,
                    mask,
                    modulate2x,
                    lit,
                    fade,
                    emissive,
                    separateAlphaPass,
                    maskUvChannel,
                    selfIllumination,
                    environment,
                    null);
        }

        public SpecularOverlay(
                @Nullable Texture texture,
                UvAnimation uvAnimation,
                @Nullable Texture mask,
                boolean modulate2x,
                boolean lit,
                @Nullable FadeColorSpec fade,
                boolean emissive,
                boolean separateAlphaPass,
                int maskUvChannel,
                boolean selfIllumination) {
            this(
                    texture,
                    uvAnimation,
                    mask,
                    modulate2x,
                    lit,
                    fade,
                    emissive,
                    separateAlphaPass,
                    maskUvChannel,
                    selfIllumination,
                    null);
        }

        public SpecularOverlay(
                @Nullable Texture texture,
                UvAnimation uvAnimation,
                @Nullable Texture mask,
                boolean modulate2x,
                boolean lit,
                @Nullable FadeColorSpec fade,
                boolean emissive,
                boolean separateAlphaPass,
                int maskUvChannel) {
            this(texture, uvAnimation, mask, modulate2x, lit, fade, emissive, separateAlphaPass, maskUvChannel, false);
        }

        public SpecularOverlay(
                @Nullable Texture texture,
                UvAnimation uvAnimation,
                @Nullable Texture mask,
                boolean modulate2x,
                boolean lit,
                @Nullable FadeColorSpec fade,
                boolean emissive,
                boolean separateAlphaPass) {
            this(texture, uvAnimation, mask, modulate2x, lit, fade, emissive, separateAlphaPass, 0);
        }

        public SpecularOverlay(
                Texture texture, UvAnimation uvAnimation, @Nullable Texture mask, boolean modulate2x, boolean lit) {
            this(texture, uvAnimation, mask, modulate2x, lit, null, false, false, 0);
        }

        public SpecularOverlay(
                @Nullable Texture texture,
                UvAnimation uvAnimation,
                @Nullable Texture mask,
                boolean modulate2x,
                boolean lit,
                @Nullable FadeColorSpec fade) {
            this(texture, uvAnimation, mask, modulate2x, lit, fade, false, false, 0);
        }

        public SpecularOverlay(
                @Nullable Texture texture,
                UvAnimation uvAnimation,
                @Nullable Texture mask,
                boolean modulate2x,
                boolean lit,
                @Nullable FadeColorSpec fade,
                boolean emissive) {
            this(texture, uvAnimation, mask, modulate2x, lit, fade, emissive, false, 0);
        }

        public SpecularOverlay withSeparatePass(boolean separate) {
            return new SpecularOverlay(
                    texture,
                    uvAnimation,
                    mask,
                    modulate2x,
                    lit,
                    fade,
                    emissive,
                    separate,
                    maskUvChannel,
                    selfIllumination,
                    environment,
                    cube);
        }
    }

    public record EnvironmentLayer(
            TextureCoordinates.Source source, UvAnimation animation, boolean specularPoint, boolean fixedFunction) {
        public EnvironmentLayer {
            if (source != TextureCoordinates.Source.CAMERA_REFLECTION
                    && source != TextureCoordinates.Source.SPHERE_MAP
                    && !(fixedFunction && source == TextureCoordinates.Source.WORLD_REFLECTION)) {
                throw new IllegalArgumentException("an environment layer reads the reflection or the sphere map");
            }
            if (specularPoint && (fixedFunction || source != TextureCoordinates.Source.CAMERA_REFLECTION)) {
                throw new IllegalArgumentException(
                        "only the shaders' camera space reflection takes the specular point");
            }
        }

        public EnvironmentLayer(TextureCoordinates.Source source, UvAnimation animation) {
            this(source, animation, false, false);
        }

        public static EnvironmentLayer withoutShaders(TextureCoordinates.Source source, UvAnimation animation) {
            return new EnvironmentLayer(source, animation, false, true);
        }

        public EnvironmentLayer toSpecularPoint() {
            return new EnvironmentLayer(source, animation, true, fixedFunction);
        }
    }

    public record SpecularEntityMaterial(EntityMaterial base, SpecularOverlay overlay) implements EntityMaterial {
        @Override
        public @Nullable Texture getTexture() {
            return base.getTexture();
        }
    }

    public record AnimatedTextureEntityMaterial(List<Texture> frames, float framesPerSecond) implements EntityMaterial {
        @Override
        public Texture getTexture() {
            return frames.getFirst();
        }
    }

    public record EnvCubeLayer(Cubemap cubemap, int envMapType, float pitch, float yaw, float roll) {
        public EnvCubeLayer {
            if (cubemap == null) {
                throw new IllegalArgumentException("an environment cube layer needs its cubemap");
            }
        }

        public float[] rotationMatrix() {
            var sr = (float) Math.sin(roll);
            var cr = (float) Math.cos(roll);
            var sp = (float) Math.sin(pitch);
            var cp = (float) Math.cos(pitch);
            var sy = (float) Math.sin(yaw);
            var cy = (float) Math.cos(yaw);
            return new float[] {
                cp * cy,
                cp * sy,
                sp,
                sr * sp * cy - cr * sy,
                sr * sp * sy + cr * cy,
                -sr * cp,
                -(cr * sp * cy + sr * sy),
                cy * sr - cr * sp * sy,
                cr * cp
            };
        }
    }

    public record CombinerEntityMaterial(
            Texture material1,
            @Nullable Texture material2,
            @Nullable Texture mask,
            int combineOp,
            float modulate,
            @Nullable FadeColorSpec material2Fade,
            boolean invertMask,
            int alphaOp,
            boolean vertexColorMask,
            @Nullable EnvCubeLayer material2Cube)
            implements EntityMaterial {
        public CombinerEntityMaterial(
                Texture material1,
                @Nullable Texture material2,
                @Nullable Texture mask,
                int combineOp,
                float modulate,
                @Nullable FadeColorSpec material2Fade,
                boolean invertMask,
                int alphaOp,
                boolean vertexColorMask) {
            this(
                    material1,
                    material2,
                    mask,
                    combineOp,
                    modulate,
                    material2Fade,
                    invertMask,
                    alphaOp,
                    vertexColorMask,
                    null);
        }

        public CombinerEntityMaterial(
                Texture material1, @Nullable Texture material2, @Nullable Texture mask, int combineOp, float modulate) {
            this(material1, material2, mask, combineOp, modulate, null, false, 0, false);
        }

        public CombinerEntityMaterial(
                Texture material1,
                @Nullable Texture material2,
                @Nullable Texture mask,
                int combineOp,
                float modulate,
                @Nullable FadeColorSpec material2Fade) {
            this(material1, material2, mask, combineOp, modulate, material2Fade, false, 0, false);
        }

        public CombinerEntityMaterial(
                Texture material1,
                @Nullable Texture material2,
                @Nullable Texture mask,
                int combineOp,
                float modulate,
                @Nullable FadeColorSpec material2Fade,
                boolean invertMask,
                int alphaOp) {
            this(material1, material2, mask, combineOp, modulate, material2Fade, invertMask, alphaOp, false);
        }

        @Override
        public Texture getTexture() {
            return material1;
        }
    }

    public record Cubemap(List<Texture> faces) {}

    public record ReflectiveEntityMaterial(@Nullable Texture base, Cubemap cubemap, float reflectivity)
            implements EntityMaterial {
        @Override
        public @Nullable Texture getTexture() {
            return base;
        }
    }

    public record EnvMapEntityMaterial(Texture map, int envMapType) implements EntityMaterial {
        @Override
        public Texture getTexture() {
            return map;
        }
    }

    public record ColorEntityMaterial(Vec4 color) implements EntityMaterial {
        @Override
        public @Nullable Texture getTexture() {
            return null;
        }
    }

    public record TextureEntityMaterial(Texture texture) implements EntityMaterial {
        @Override
        public Texture getTexture() {
            return texture;
        }
    }

    public record TextureEntityMaterialWithOpacity(
            Texture texture, @Nullable Texture opacityTexture) implements EntityMaterial {
        @Override
        public Texture getTexture() {
            return texture;
        }
    }

    public record WaterEntityMaterial(
            Texture texture,
            float alpha,
            Vec3 tint,
            @Nullable Texture material2,
            UvAnimation material2Anim,
            float material2Rotation,
            @Nullable AuthoredMaterialProgram authoredProgram,
            @Nullable AuthoredFramebuffer framebuffer,
            @Nullable AuthoredMaterialPassProgram authoredPasses,
            boolean specularDecal)
            implements EntityMaterial {
        public WaterEntityMaterial(
                Texture texture,
                float alpha,
                Vec3 tint,
                @Nullable Texture material2,
                UvAnimation material2Anim,
                float material2Rotation,
                @Nullable AuthoredMaterialProgram authoredProgram,
                @Nullable AuthoredFramebuffer framebuffer,
                @Nullable AuthoredMaterialPassProgram authoredPasses) {
            this(
                    texture,
                    alpha,
                    tint,
                    material2,
                    material2Anim,
                    material2Rotation,
                    authoredProgram,
                    framebuffer,
                    authoredPasses,
                    false);
        }

        public WaterEntityMaterial(
                Texture texture,
                float alpha,
                Vec3 tint,
                @Nullable Texture material2,
                UvAnimation material2Anim,
                float material2Rotation,
                @Nullable AuthoredMaterialProgram authoredProgram,
                @Nullable AuthoredFramebuffer framebuffer) {
            this(texture, alpha, tint, material2, material2Anim, material2Rotation, authoredProgram, framebuffer, null);
        }

        public WaterEntityMaterial(
                Texture texture,
                float alpha,
                Vec3 tint,
                @Nullable Texture material2,
                UvAnimation material2Anim,
                float material2Rotation,
                @Nullable AuthoredMaterialProgram authoredProgram) {
            this(texture, alpha, tint, material2, material2Anim, material2Rotation, authoredProgram, null);
        }

        public WaterEntityMaterial(
                Texture texture,
                float alpha,
                Vec3 tint,
                @Nullable Texture material2,
                UvAnimation material2Anim,
                float material2Rotation) {
            this(texture, alpha, tint, material2, material2Anim, material2Rotation, null);
        }

        public WaterEntityMaterial(Texture texture, float alpha, Vec3 tint) {
            this(texture, alpha, tint, null, UvAnimation.NONE, 0f);
        }

        public WaterEntityMaterial(
                Texture texture, float alpha, Vec3 tint, @Nullable Texture material2, UvAnimation material2Anim) {
            this(texture, alpha, tint, material2, material2Anim, 0f);
        }

        @Override
        public Texture getTexture() {
            return texture;
        }
    }

    public record MultiTextureEntityMaterial(MultiTexture multiTexture) implements EntityMaterial {
        @Override
        public @Nullable Texture getTexture() {
            return multiTexture.entries().get(0).texture();
        }
    }

    public record DistanceFog(Vec3 color, float start, float end) {}

    public record WaterSheetSource(
            String modelId,
            int surfaceIndex,
            int negativeZone,
            int positiveZone,
            float authoredPlaneX,
            float authoredPlaneY,
            float authoredPlaneZ,
            float authoredPlaneW,
            boolean frontZoneSunAffected) {
        public boolean isInFrontOf(Vec3 position) {
            return (position.getY() * authoredPlaneY
                                    + position.getX() * authoredPlaneX
                                    + position.getZ() * authoredPlaneZ)
                            - authoredPlaneW
                    > 0f;
        }

        public int frontZone(WaterPlane canonicalPlane) {
            return canonicalPlane.x() * authoredPlaneX
                                    + canonicalPlane.y() * authoredPlaneY
                                    + canonicalPlane.z() * authoredPlaneZ
                            >= 0f
                    ? positiveZone
                    : negativeZone;
        }
    }

    public record BspDrawSource(String modelId, long zoneMask) {}

    public record Surface(
            long type,
            int indexOffset,
            int indexCount,
            EntityMaterial material,
            BlendMode blendMode,
            UvAnimation uvAnimation,
            boolean water,
            @Nullable String materialName,
            int alphaRef,
            boolean usesVertexColor,
            boolean depthWrite,
            boolean modulateStaticLighting2x,
            @Nullable WaterPlane reflectionPlane,
            @Nullable DistanceFog distanceFog,
            @Nullable WaterSheetSource waterSheetSource,
            @Nullable BspDrawSource bspDrawSource) {
        public Surface(
                long type,
                int indexOffset,
                int indexCount,
                EntityMaterial material,
                BlendMode blendMode,
                UvAnimation uvAnimation,
                boolean water,
                @Nullable String materialName,
                int alphaRef,
                boolean usesVertexColor,
                boolean depthWrite,
                boolean modulateStaticLighting2x,
                @Nullable WaterPlane reflectionPlane,
                @Nullable DistanceFog distanceFog,
                @Nullable WaterSheetSource waterSheetSource) {
            this(
                    type,
                    indexOffset,
                    indexCount,
                    material,
                    blendMode,
                    uvAnimation,
                    water,
                    materialName,
                    alphaRef,
                    usesVertexColor,
                    depthWrite,
                    modulateStaticLighting2x,
                    reflectionPlane,
                    distanceFog,
                    waterSheetSource,
                    null);
        }

        public Surface(
                long type,
                int indexOffset,
                int indexCount,
                EntityMaterial material,
                BlendMode blendMode,
                UvAnimation uvAnimation,
                boolean water,
                @Nullable String materialName,
                int alphaRef,
                boolean usesVertexColor,
                boolean depthWrite,
                boolean modulateStaticLighting2x,
                @Nullable WaterPlane reflectionPlane,
                @Nullable DistanceFog distanceFog) {
            this(
                    type,
                    indexOffset,
                    indexCount,
                    material,
                    blendMode,
                    uvAnimation,
                    water,
                    materialName,
                    alphaRef,
                    usesVertexColor,
                    depthWrite,
                    modulateStaticLighting2x,
                    reflectionPlane,
                    distanceFog,
                    null);
        }

        public Surface(
                long type,
                int indexOffset,
                int indexCount,
                EntityMaterial material,
                BlendMode blendMode,
                UvAnimation uvAnimation,
                boolean water,
                @Nullable String materialName,
                int alphaRef,
                boolean usesVertexColor,
                boolean depthWrite,
                boolean modulateStaticLighting2x,
                @Nullable WaterPlane reflectionPlane) {
            this(
                    type,
                    indexOffset,
                    indexCount,
                    material,
                    blendMode,
                    uvAnimation,
                    water,
                    materialName,
                    alphaRef,
                    usesVertexColor,
                    depthWrite,
                    modulateStaticLighting2x,
                    reflectionPlane,
                    null,
                    null);
        }

        public Surface(
                long type,
                int indexOffset,
                int indexCount,
                EntityMaterial material,
                BlendMode blendMode,
                UvAnimation uvAnimation,
                boolean water,
                @Nullable String materialName,
                int alphaRef,
                boolean usesVertexColor,
                boolean depthWrite,
                boolean modulateStaticLighting2x) {
            this(
                    type,
                    indexOffset,
                    indexCount,
                    material,
                    blendMode,
                    uvAnimation,
                    water,
                    materialName,
                    alphaRef,
                    usesVertexColor,
                    depthWrite,
                    modulateStaticLighting2x,
                    null,
                    null,
                    null);
        }

        public Surface(
                long type,
                int indexOffset,
                int indexCount,
                EntityMaterial material,
                BlendMode blendMode,
                UvAnimation uvAnimation,
                boolean water,
                @Nullable String materialName,
                int alphaRef,
                boolean usesVertexColor,
                boolean depthWrite) {
            this(
                    type,
                    indexOffset,
                    indexCount,
                    material,
                    blendMode,
                    uvAnimation,
                    water,
                    materialName,
                    alphaRef,
                    usesVertexColor,
                    depthWrite,
                    false,
                    null,
                    null,
                    null);
        }

        public Surface(
                long type,
                int indexOffset,
                int indexCount,
                EntityMaterial material,
                BlendMode blendMode,
                UvAnimation uvAnimation,
                boolean water,
                @Nullable String materialName,
                int alphaRef,
                boolean usesVertexColor) {
            this(
                    type,
                    indexOffset,
                    indexCount,
                    material,
                    blendMode,
                    uvAnimation,
                    water,
                    materialName,
                    alphaRef,
                    usesVertexColor,
                    writesDepthByDefault(blendMode),
                    false);
        }

        public Surface(
                long type,
                int indexOffset,
                int indexCount,
                EntityMaterial material,
                BlendMode blendMode,
                UvAnimation uvAnimation,
                boolean water,
                @Nullable String materialName,
                int alphaRef) {
            this(type, indexOffset, indexCount, material, blendMode, uvAnimation, water, materialName, alphaRef, false);
        }

        public Surface(long type, int indexOffset, int indexCount, EntityMaterial material) {
            this(type, indexOffset, indexCount, material, BlendMode.OPAQUE, UvAnimation.NONE, false, null, -1);
        }

        public Surface(long type, int indexOffset, int indexCount, EntityMaterial material, BlendMode blendMode) {
            this(type, indexOffset, indexCount, material, blendMode, UvAnimation.NONE, false, null, -1);
        }

        public Surface(
                long type,
                int indexOffset,
                int indexCount,
                EntityMaterial material,
                BlendMode blendMode,
                UvAnimation uvAnimation) {
            this(type, indexOffset, indexCount, material, blendMode, uvAnimation, false, null, -1);
        }

        public Surface(
                long type,
                int indexOffset,
                int indexCount,
                EntityMaterial material,
                BlendMode blendMode,
                UvAnimation uvAnimation,
                boolean water) {
            this(type, indexOffset, indexCount, material, blendMode, uvAnimation, water, null, -1);
        }

        public Surface(
                long type,
                int indexOffset,
                int indexCount,
                EntityMaterial material,
                BlendMode blendMode,
                UvAnimation uvAnimation,
                boolean water,
                @Nullable String materialName) {
            this(type, indexOffset, indexCount, material, blendMode, uvAnimation, water, materialName, -1);
        }

        public static boolean writesDepthByDefault(BlendMode blendMode) {
            return switch (blendMode) {
                case OPAQUE, MASKED, MASKED_BLEND, UNLIT -> true;
                case TRANSLUCENT, ADDITIVE, BRIGHTEN, MODULATE, SCREEN, DARKEN, EMISSIVE, INVISIBLE -> false;
            };
        }
    }

    public record DebugLabel(String name, Vec3 center, Vec3 min, Vec3 max, Vec3[] polygon) {}

    public record SourcePart(
            String actor, String asset, int materialSlot, int indexOffset, int indexCount, Bounds3f bounds) {
        public SourcePart {
            if (actor == null
                    || actor.isBlank()
                    || asset == null
                    || asset.isBlank()
                    || materialSlot < 0
                    || indexOffset < 0
                    || indexCount <= 0
                    || bounds == null) {
                throw new IllegalArgumentException("invalid merged source part");
            }
        }
    }

    public record MeshVertex(
            Vec3 position,
            Vec3 normal,
            Vec2 uv,
            Vec3 bakedLight,
            Vec2 lightmapUv,
            @Nullable Vec4 color,
            int sunVisibilityMask,
            Vec2 secondaryUv,
            @Nullable Vec4 bspPlane,
            @Nullable Vec3 bspTangent,
            @Nullable Vec4 staticMeshTangent) {
        public MeshVertex(
                Vec3 position,
                Vec3 normal,
                Vec2 uv,
                Vec3 bakedLight,
                Vec2 lightmapUv,
                @Nullable Vec4 color,
                int sunVisibilityMask,
                Vec2 secondaryUv,
                @Nullable Vec4 bspPlane,
                @Nullable Vec3 bspTangent) {
            this(
                    position,
                    normal,
                    uv,
                    bakedLight,
                    lightmapUv,
                    color,
                    sunVisibilityMask,
                    secondaryUv,
                    bspPlane,
                    bspTangent,
                    null);
        }

        public MeshVertex(
                Vec3 position,
                Vec3 normal,
                Vec2 uv,
                Vec3 bakedLight,
                Vec2 lightmapUv,
                @Nullable Vec4 color,
                int sunVisibilityMask,
                Vec2 secondaryUv,
                @Nullable Vec4 bspPlane) {
            this(position, normal, uv, bakedLight, lightmapUv, color, sunVisibilityMask, secondaryUv, bspPlane, null);
        }

        public MeshVertex(
                Vec3 position,
                Vec3 normal,
                Vec2 uv,
                Vec3 bakedLight,
                Vec2 lightmapUv,
                @Nullable Vec4 color,
                int sunVisibilityMask,
                Vec2 secondaryUv) {
            this(position, normal, uv, bakedLight, lightmapUv, color, sunVisibilityMask, secondaryUv, null);
        }

        public MeshVertex(
                Vec3 position,
                Vec3 normal,
                Vec2 uv,
                Vec3 bakedLight,
                Vec2 lightmapUv,
                @Nullable Vec4 color,
                int sunVisibilityMask) {
            this(position, normal, uv, bakedLight, lightmapUv, color, sunVisibilityMask, VEC2_EMPTY, null);
        }

        public MeshVertex(Vec3 position, Vec3 normal, Vec2 uv, Vec3 bakedLight, Vec2 lightmapUv, @Nullable Vec4 color) {
            this(position, normal, uv, bakedLight, lightmapUv, color, -1, VEC2_EMPTY, null);
        }

        public MeshVertex(Vec3 position, Vec3 normal, Vec2 uv) {
            this(position, normal, uv, VEC3_EMPTY, VEC2_EMPTY, null, -1, VEC2_EMPTY, null);
        }

        public MeshVertex(Vec3 position, Vec3 normal, Vec2 uv, Vec3 bakedLight) {
            this(position, normal, uv, bakedLight, VEC2_EMPTY, null, -1, VEC2_EMPTY, null);
        }

        public MeshVertex(Vec3 position, Vec3 normal, Vec2 uv, Vec3 bakedLight, Vec2 lightmapUv) {
            this(position, normal, uv, bakedLight, lightmapUv, null, -1, VEC2_EMPTY, null);
        }

        public MeshVertex(Vec3 position, Vec3 normal, Vec2 uv, @Nullable Vec4 color) {
            this(position, normal, uv, VEC3_EMPTY, VEC2_EMPTY, color, -1, VEC2_EMPTY, null);
        }

        public MeshVertex(Vec3 position, Vec3 normal, Vec2 uv, @Nullable Vec4 color, Vec2 secondaryUv) {
            this(position, normal, uv, VEC3_EMPTY, VEC2_EMPTY, color, -1, secondaryUv, null);
        }
    }

    public static final class MeshVertexBuffer extends AbstractList<MeshVertex> implements RandomAccess {
        private static final int COLOR_PRESENT = 1;
        private static final int BSP_PLANE_PRESENT = 2;
        private static final int BSP_TANGENT_PRESENT = 4;
        private static final int STATIC_MESH_TANGENT_PRESENT = 8;

        private final int size;
        private final float[] positions;
        private final float[] normals;
        private final float[] uvs;
        private final float @Nullable [] bakedLights;
        private final float @Nullable [] lightmapUvs;
        private final float @Nullable [] colors;
        private final int @Nullable [] sunVisibilityMasks;
        private final float @Nullable [] secondaryUvs;
        private final float @Nullable [] bspPlanes;
        private final float @Nullable [] bspTangents;
        private final float @Nullable [] staticMeshTangents;
        private final byte @Nullable [] presence;

        private MeshVertexBuffer(Builder builder) {
            size = builder.size;
            positions = takeOrTrimRequired(builder.positions, size * 3);
            normals = takeOrTrimRequired(builder.normals, size * 3);
            uvs = takeOrTrimRequired(builder.uvs, size * 2);
            bakedLights = takeOrTrim(builder.bakedLights, size * 3);
            lightmapUvs = takeOrTrim(builder.lightmapUvs, size * 2);
            colors = takeOrTrim(builder.colors, size * 4);
            sunVisibilityMasks = takeOrTrim(builder.sunVisibilityMasks, size);
            secondaryUvs = takeOrTrim(builder.secondaryUvs, size * 2);
            bspPlanes = takeOrTrim(builder.bspPlanes, size * 4);
            bspTangents = takeOrTrim(builder.bspTangents, size * 3);
            staticMeshTangents = takeOrTrim(builder.staticMeshTangents, size * 4);
            presence = takeOrTrim(builder.presence, size);
        }

        private MeshVertexBuffer(
                MeshVertexBuffer source,
                float @Nullable [] replacementBakedLights,
                int @Nullable [] replacementSunVisibilityMasks) {
            size = source.size;
            positions = source.positions;
            normals = source.normals;
            uvs = source.uvs;
            bakedLights = replacementBakedLights != null ? replacementBakedLights : source.bakedLights;
            lightmapUvs = source.lightmapUvs;
            colors = source.colors;
            sunVisibilityMasks =
                    replacementSunVisibilityMasks != null ? replacementSunVisibilityMasks : source.sunVisibilityMasks;
            secondaryUvs = source.secondaryUvs;
            bspPlanes = source.bspPlanes;
            bspTangents = source.bspTangents;
            staticMeshTangents = source.staticMeshTangents;
            presence = source.presence;
        }

        public static MeshVertexBuffer copyOf(List<MeshVertex> vertices) {
            Objects.requireNonNull(vertices, "vertices");
            if (vertices instanceof MeshVertexBuffer packed) {
                return packed;
            }
            var builder = builder(vertices.size());
            for (var vertex : vertices) {
                builder.add(vertex);
            }
            return builder.build();
        }

        public static Builder builder(int expectedSize) {
            return new Builder(expectedSize);
        }

        MeshVertexBuffer copyWithLightingOverrides(
                float @Nullable [] replacementBakedLights, int @Nullable [] replacementSunVisibilityMasks) {
            if (replacementBakedLights != null && replacementBakedLights.length != size * 3) {
                throw new IllegalArgumentException(
                        "baked-light override length " + replacementBakedLights.length + " != " + size * 3);
            }
            if (replacementSunVisibilityMasks != null && replacementSunVisibilityMasks.length != size) {
                throw new IllegalArgumentException(
                        "sun-mask override length " + replacementSunVisibilityMasks.length + " != " + size);
            }
            if (replacementBakedLights == null && replacementSunVisibilityMasks == null) {
                return this;
            }
            return new MeshVertexBuffer(this, replacementBakedLights, replacementSunVisibilityMasks);
        }

        @Override
        public MeshVertex get(int index) {
            checkIndex(index);
            var p3 = index * 3;
            var p2 = index * 2;
            var p4 = index * 4;
            return new MeshVertex(
                    new Vec3(positions[p3], positions[p3 + 1], positions[p3 + 2]),
                    new Vec3(normals[p3], normals[p3 + 1], normals[p3 + 2]),
                    new Vec2(uvs[p2], uvs[p2 + 1]),
                    bakedLights == null
                            ? new Vec3()
                            : new Vec3(bakedLights[p3], bakedLights[p3 + 1], bakedLights[p3 + 2]),
                    lightmapUvs == null ? new Vec2() : new Vec2(lightmapUvs[p2], lightmapUvs[p2 + 1]),
                    hasColor(index) ? new Vec4(colors[p4], colors[p4 + 1], colors[p4 + 2], colors[p4 + 3]) : null,
                    getSunVisibilityMask(index),
                    secondaryUvs == null ? new Vec2() : new Vec2(secondaryUvs[p2], secondaryUvs[p2 + 1]),
                    hasBspPlane(index)
                            ? new Vec4(bspPlanes[p4], bspPlanes[p4 + 1], bspPlanes[p4 + 2], bspPlanes[p4 + 3])
                            : null,
                    hasBspTangent(index) ? new Vec3(bspTangents[p3], bspTangents[p3 + 1], bspTangents[p3 + 2]) : null,
                    hasStaticMeshTangent(index)
                            ? new Vec4(
                                    staticMeshTangents[p4],
                                    staticMeshTangents[p4 + 1],
                                    staticMeshTangents[p4 + 2],
                                    staticMeshTangents[p4 + 3])
                            : null);
        }

        @Override
        public int size() {
            return size;
        }

        public float getPositionX(int index) {
            return positions[checked3(index)];
        }

        public float getPositionY(int index) {
            return positions[checked3(index) + 1];
        }

        public float getPositionZ(int index) {
            return positions[checked3(index) + 2];
        }

        public float getNormalX(int index) {
            return normals[checked3(index)];
        }

        public float getNormalY(int index) {
            return normals[checked3(index) + 1];
        }

        public float getNormalZ(int index) {
            return normals[checked3(index) + 2];
        }

        public float getUvX(int index) {
            return uvs[checked2(index)];
        }

        public float getUvY(int index) {
            return uvs[checked2(index) + 1];
        }

        public float getBakedLightX(int index) {
            var offset = checked3(index);
            return bakedLights == null ? 0f : bakedLights[offset];
        }

        public float getBakedLightY(int index) {
            var offset = checked3(index);
            return bakedLights == null ? 0f : bakedLights[offset + 1];
        }

        public float getBakedLightZ(int index) {
            var offset = checked3(index);
            return bakedLights == null ? 0f : bakedLights[offset + 2];
        }

        public float getLightmapUvX(int index) {
            var offset = checked2(index);
            return lightmapUvs == null ? 0f : lightmapUvs[offset];
        }

        public float getLightmapUvY(int index) {
            var offset = checked2(index);
            return lightmapUvs == null ? 0f : lightmapUvs[offset + 1];
        }

        public boolean hasColor(int index) {
            checkIndex(index);
            return presence != null && (presence[index] & COLOR_PRESENT) != 0;
        }

        public float getColorR(int index) {
            return colors[checked4Present(index, COLOR_PRESENT)];
        }

        public float getColorG(int index) {
            return colors[checked4Present(index, COLOR_PRESENT) + 1];
        }

        public float getColorB(int index) {
            return colors[checked4Present(index, COLOR_PRESENT) + 2];
        }

        public float getColorA(int index) {
            return colors[checked4Present(index, COLOR_PRESENT) + 3];
        }

        public int getSunVisibilityMask(int index) {
            checkIndex(index);
            return sunVisibilityMasks == null ? -1 : sunVisibilityMasks[index];
        }

        public float getSecondaryUvX(int index) {
            var offset = checked2(index);
            return secondaryUvs == null ? 0f : secondaryUvs[offset];
        }

        public float getSecondaryUvY(int index) {
            var offset = checked2(index);
            return secondaryUvs == null ? 0f : secondaryUvs[offset + 1];
        }

        public boolean hasBspPlane(int index) {
            checkIndex(index);
            return presence != null && (presence[index] & BSP_PLANE_PRESENT) != 0;
        }

        public float getBspPlaneX(int index) {
            return bspPlanes[checked4Present(index, BSP_PLANE_PRESENT)];
        }

        public float getBspPlaneY(int index) {
            return bspPlanes[checked4Present(index, BSP_PLANE_PRESENT) + 1];
        }

        public float getBspPlaneZ(int index) {
            return bspPlanes[checked4Present(index, BSP_PLANE_PRESENT) + 2];
        }

        public float getBspPlaneW(int index) {
            return bspPlanes[checked4Present(index, BSP_PLANE_PRESENT) + 3];
        }

        public boolean hasBspTangent(int index) {
            checkIndex(index);
            return presence != null && (presence[index] & BSP_TANGENT_PRESENT) != 0;
        }

        public float getBspTangentX(int index) {
            return bspTangents[checkedBspTangent(index)];
        }

        public float getBspTangentY(int index) {
            return bspTangents[checkedBspTangent(index) + 1];
        }

        public float getBspTangentZ(int index) {
            return bspTangents[checkedBspTangent(index) + 2];
        }

        public boolean hasStaticMeshTangent(int index) {
            checkIndex(index);
            return presence != null && (presence[index] & STATIC_MESH_TANGENT_PRESENT) != 0;
        }

        public float getStaticMeshTangentX(int index) {
            return staticMeshTangents[checked4Present(index, STATIC_MESH_TANGENT_PRESENT)];
        }

        public float getStaticMeshTangentY(int index) {
            return staticMeshTangents[checked4Present(index, STATIC_MESH_TANGENT_PRESENT) + 1];
        }

        public float getStaticMeshTangentZ(int index) {
            return staticMeshTangents[checked4Present(index, STATIC_MESH_TANGENT_PRESENT) + 2];
        }

        public float getStaticMeshTangentW(int index) {
            return staticMeshTangents[checked4Present(index, STATIC_MESH_TANGENT_PRESENT) + 3];
        }

        private int checkedBspTangent(int index) {
            if (!hasBspTangent(index)) {
                throw new IllegalStateException("BSP tangent is absent at " + index);
            }
            return index * 3;
        }

        private int checked2(int index) {
            checkIndex(index);
            return index * 2;
        }

        private int checked3(int index) {
            checkIndex(index);
            return index * 3;
        }

        private int checked4Present(int index, int flag) {
            checkIndex(index);
            if (presence == null || (presence[index] & flag) == 0) {
                throw new IllegalStateException("optional vertex channel is absent at " + index);
            }
            return index * 4;
        }

        private void checkIndex(int index) {
            Objects.checkIndex(index, size);
        }

        private static float[] takeOrTrimRequired(float[] values, int length) {
            return values.length == length ? values : Arrays.copyOf(values, length);
        }

        private static float @Nullable [] takeOrTrim(float @Nullable [] values, int length) {
            return values == null || values.length == length ? values : Arrays.copyOf(values, length);
        }

        private static int @Nullable [] takeOrTrim(int @Nullable [] values, int length) {
            return values == null || values.length == length ? values : Arrays.copyOf(values, length);
        }

        private static byte @Nullable [] takeOrTrim(byte @Nullable [] values, int length) {
            return values == null || values.length == length ? values : Arrays.copyOf(values, length);
        }

        public static final class Builder {
            private int size;
            private boolean built;
            private float[] positions;
            private float[] normals;
            private float[] uvs;
            private float @Nullable [] bakedLights;
            private float @Nullable [] lightmapUvs;
            private float @Nullable [] colors;
            private int @Nullable [] sunVisibilityMasks;
            private float @Nullable [] secondaryUvs;
            private float @Nullable [] bspPlanes;
            private float @Nullable [] bspTangents;
            private float @Nullable [] staticMeshTangents;
            private byte @Nullable [] presence;

            private Builder(int expectedSize) {
                if (expectedSize < 0) {
                    throw new IllegalArgumentException("negative vertex capacity: " + expectedSize);
                }
                var capacity = Math.max(1, expectedSize);
                positions = new float[Math.multiplyExact(capacity, 3)];
                normals = new float[Math.multiplyExact(capacity, 3)];
                uvs = new float[Math.multiplyExact(capacity, 2)];
            }

            public int size() {
                return size;
            }

            public boolean isEmpty() {
                return size == 0;
            }

            public int add(MeshVertex vertex) {
                Objects.requireNonNull(vertex, "vertex");
                var index = add(
                        vertex.position().getX(),
                        vertex.position().getY(),
                        vertex.position().getZ(),
                        vertex.normal().getX(),
                        vertex.normal().getY(),
                        vertex.normal().getZ(),
                        vertex.uv().getX(),
                        vertex.uv().getY());
                var baked = vertex.bakedLight();
                setBakedLight(index, baked.getX(), baked.getY(), baked.getZ());
                var lightmap = vertex.lightmapUv();
                setLightmapUv(index, lightmap.getX(), lightmap.getY());
                var color = vertex.color();
                if (color != null) {
                    setColor(index, color.getX(), color.getY(), color.getZ(), color.getW());
                }
                if (vertex.sunVisibilityMask() != -1) {
                    setSunVisibilityMask(index, vertex.sunVisibilityMask());
                }
                var secondary = vertex.secondaryUv();
                setSecondaryUv(index, secondary.getX(), secondary.getY());
                var plane = vertex.bspPlane();
                if (plane != null) {
                    setBspPlane(index, plane.getX(), plane.getY(), plane.getZ(), plane.getW());
                }
                var tangent = vertex.bspTangent();
                if (tangent != null) {
                    setBspTangent(index, tangent.getX(), tangent.getY(), tangent.getZ());
                }
                var staticTangent = vertex.staticMeshTangent();
                if (staticTangent != null) {
                    setStaticMeshTangent(
                            index,
                            staticTangent.getX(),
                            staticTangent.getY(),
                            staticTangent.getZ(),
                            staticTangent.getW());
                }
                return index;
            }

            public int add(MeshVertexBuffer source, int sourceIndex) {
                var index = add(
                        source.getPositionX(sourceIndex),
                        source.getPositionY(sourceIndex),
                        source.getPositionZ(sourceIndex),
                        source.getNormalX(sourceIndex),
                        source.getNormalY(sourceIndex),
                        source.getNormalZ(sourceIndex),
                        source.getUvX(sourceIndex),
                        source.getUvY(sourceIndex));
                setBakedLight(
                        index,
                        source.getBakedLightX(sourceIndex),
                        source.getBakedLightY(sourceIndex),
                        source.getBakedLightZ(sourceIndex));
                setLightmapUv(index, source.getLightmapUvX(sourceIndex), source.getLightmapUvY(sourceIndex));
                if (source.hasColor(sourceIndex)) {
                    setColor(
                            index,
                            source.getColorR(sourceIndex),
                            source.getColorG(sourceIndex),
                            source.getColorB(sourceIndex),
                            source.getColorA(sourceIndex));
                }
                var sunVisibilityMask = source.getSunVisibilityMask(sourceIndex);
                if (sunVisibilityMask != -1) {
                    setSunVisibilityMask(index, sunVisibilityMask);
                }
                setSecondaryUv(index, source.getSecondaryUvX(sourceIndex), source.getSecondaryUvY(sourceIndex));
                if (source.hasBspPlane(sourceIndex)) {
                    setBspPlane(
                            index,
                            source.getBspPlaneX(sourceIndex),
                            source.getBspPlaneY(sourceIndex),
                            source.getBspPlaneZ(sourceIndex),
                            source.getBspPlaneW(sourceIndex));
                }
                if (source.hasBspTangent(sourceIndex)) {
                    setBspTangent(
                            index,
                            source.getBspTangentX(sourceIndex),
                            source.getBspTangentY(sourceIndex),
                            source.getBspTangentZ(sourceIndex));
                }
                if (source.hasStaticMeshTangent(sourceIndex)) {
                    setStaticMeshTangent(
                            index,
                            source.getStaticMeshTangentX(sourceIndex),
                            source.getStaticMeshTangentY(sourceIndex),
                            source.getStaticMeshTangentZ(sourceIndex),
                            source.getStaticMeshTangentW(sourceIndex));
                }
                return index;
            }

            public int add(float px, float py, float pz, float nx, float ny, float nz, float u, float v) {
                ensureCapacity(size + 1);
                var p3 = size * 3;
                positions[p3] = px;
                positions[p3 + 1] = py;
                positions[p3 + 2] = pz;
                normals[p3] = nx;
                normals[p3 + 1] = ny;
                normals[p3 + 2] = nz;
                var p2 = size * 2;
                uvs[p2] = u;
                uvs[p2 + 1] = v;
                return size++;
            }

            public void setBakedLight(int index, float x, float y, float z) {
                checkAddedIndex(index);
                if (bakedLights == null && x == 0f && y == 0f && z == 0f) {
                    return;
                }
                bakedLights = ensureOptional(bakedLights, positions.length);
                var offset = index * 3;
                bakedLights[offset] = x;
                bakedLights[offset + 1] = y;
                bakedLights[offset + 2] = z;
            }

            public void setLightmapUv(int index, float u, float v) {
                checkAddedIndex(index);
                if (lightmapUvs == null && u == 0f && v == 0f) {
                    return;
                }
                lightmapUvs = ensureOptional(lightmapUvs, uvs.length);
                var offset = index * 2;
                lightmapUvs[offset] = u;
                lightmapUvs[offset + 1] = v;
            }

            public void setColor(int index, float r, float g, float b, float a) {
                checkAddedIndex(index);
                colors = ensureOptional(colors, capacity() * 4);
                presence = ensureOptional(presence, capacity());
                var offset = index * 4;
                colors[offset] = r;
                colors[offset + 1] = g;
                colors[offset + 2] = b;
                colors[offset + 3] = a;
                presence[index] |= COLOR_PRESENT;
            }

            public void setSunVisibilityMask(int index, int mask) {
                checkAddedIndex(index);
                if (sunVisibilityMasks == null && mask == -1) {
                    return;
                }
                if (sunVisibilityMasks == null) {
                    sunVisibilityMasks = new int[capacity()];
                    Arrays.fill(sunVisibilityMasks, -1);
                }
                sunVisibilityMasks[index] = mask;
            }

            public void setSecondaryUv(int index, float u, float v) {
                checkAddedIndex(index);
                if (secondaryUvs == null && u == 0f && v == 0f) {
                    return;
                }
                secondaryUvs = ensureOptional(secondaryUvs, uvs.length);
                var offset = index * 2;
                secondaryUvs[offset] = u;
                secondaryUvs[offset + 1] = v;
            }

            public void setBspPlane(int index, float x, float y, float z, float w) {
                checkAddedIndex(index);
                bspPlanes = ensureOptional(bspPlanes, capacity() * 4);
                presence = ensureOptional(presence, capacity());
                var offset = index * 4;
                bspPlanes[offset] = x;
                bspPlanes[offset + 1] = y;
                bspPlanes[offset + 2] = z;
                bspPlanes[offset + 3] = w;
                presence[index] |= BSP_PLANE_PRESENT;
            }

            public void setBspTangent(int index, float x, float y, float z) {
                checkAddedIndex(index);
                bspTangents = ensureOptional(bspTangents, capacity() * 3);
                presence = ensureOptional(presence, capacity());
                var offset = index * 3;
                bspTangents[offset] = x;
                bspTangents[offset + 1] = y;
                bspTangents[offset + 2] = z;
                presence[index] |= BSP_TANGENT_PRESENT;
            }

            public void setStaticMeshTangent(int index, float x, float y, float z, float w) {
                checkAddedIndex(index);
                staticMeshTangents = ensureOptional(staticMeshTangents, capacity() * 4);
                presence = ensureOptional(presence, capacity());
                var offset = index * 4;
                staticMeshTangents[offset] = x;
                staticMeshTangents[offset + 1] = y;
                staticMeshTangents[offset + 2] = z;
                staticMeshTangents[offset + 3] = w;
                presence[index] |= STATIC_MESH_TANGENT_PRESENT;
            }

            public MeshVertexBuffer build() {
                checkMutable();
                built = true;
                return new MeshVertexBuffer(this);
            }

            public void ensureCapacity(int required) {
                checkMutable();
                if (required <= capacity()) {
                    return;
                }
                var previous = capacity();
                var next = Math.max(required, Math.max(8, previous << 1));
                positions = Arrays.copyOf(positions, next * 3);
                normals = Arrays.copyOf(normals, next * 3);
                uvs = Arrays.copyOf(uvs, next * 2);
                bakedLights = grow(bakedLights, next * 3);
                lightmapUvs = grow(lightmapUvs, next * 2);
                colors = grow(colors, next * 4);
                secondaryUvs = grow(secondaryUvs, next * 2);
                bspPlanes = grow(bspPlanes, next * 4);
                bspTangents = grow(bspTangents, next * 3);
                staticMeshTangents = grow(staticMeshTangents, next * 4);
                presence = grow(presence, next);
                if (sunVisibilityMasks != null) {
                    sunVisibilityMasks = Arrays.copyOf(sunVisibilityMasks, next);
                    Arrays.fill(sunVisibilityMasks, previous, next, -1);
                }
            }

            private int capacity() {
                return positions.length / 3;
            }

            private void checkAddedIndex(int index) {
                checkMutable();
                Objects.checkIndex(index, size);
            }

            private void checkMutable() {
                if (built) {
                    throw new IllegalStateException("mesh vertex builder was already built");
                }
            }

            private static float[] ensureOptional(float @Nullable [] values, int length) {
                return values == null ? new float[length] : values;
            }

            private static byte[] ensureOptional(byte @Nullable [] values, int length) {
                return values == null ? new byte[length] : values;
            }

            private static float @Nullable [] grow(float @Nullable [] values, int length) {
                return values == null ? null : Arrays.copyOf(values, length);
            }

            private static byte @Nullable [] grow(byte @Nullable [] values, int length) {
                return values == null ? null : Arrays.copyOf(values, length);
            }
        }
    }

    public record TerrainMeshVertex(
            Vec3 position,
            Vec2 alphaMapCoord,
            float lightValue,
            float[] terrainTextureCoords,
            int steadyLightRgb,
            Vec3 normal) {
        public TerrainMeshVertex {
            if (terrainTextureCoords == null) {
                terrainTextureCoords = new float[0];
            }
            if (normal == null) {
                normal = new Vec3(0f, 0f, 1f);
            }
        }

        public TerrainMeshVertex(Vec3 position, Vec2 alphaMapCoord, float lightValue, List<Vec2> terrainTextureCoords) {
            this(
                    position,
                    alphaMapCoord,
                    lightValue,
                    packTerrainTextureCoords(terrainTextureCoords),
                    0,
                    new Vec3(0f, 0f, 1f));
        }

        public TerrainMeshVertex(Vec3 position, Vec2 alphaMapCoord, float lightValue, float[] terrainTextureCoords) {
            this(position, alphaMapCoord, lightValue, terrainTextureCoords, 0, new Vec3(0f, 0f, 1f));
        }

        public TerrainMeshVertex(
                Vec3 position,
                Vec2 alphaMapCoord,
                float lightValue,
                List<Vec2> terrainTextureCoords,
                int steadyLightRgb) {
            this(
                    position,
                    alphaMapCoord,
                    lightValue,
                    packTerrainTextureCoords(terrainTextureCoords),
                    steadyLightRgb,
                    new Vec3(0f, 0f, 1f));
        }

        public TerrainMeshVertex(
                Vec3 position, Vec2 alphaMapCoord, float lightValue, float[] terrainTextureCoords, int steadyLightRgb) {
            this(position, alphaMapCoord, lightValue, terrainTextureCoords, steadyLightRgb, new Vec3(0f, 0f, 1f));
        }

        private static float[] packTerrainTextureCoords(List<Vec2> coordinates) {
            if (coordinates == null || coordinates.isEmpty()) {
                return new float[0];
            }
            var packed = new float[Math.multiplyExact(coordinates.size(), 2)];
            for (var index = 0; index < coordinates.size(); index++) {
                var coordinate = coordinates.get(index);
                packed[index * 2] = coordinate.getX();
                packed[index * 2 + 1] = coordinate.getY();
            }
            return packed;
        }
    }

    public static final class TerrainVertexBuffer extends AbstractList<TerrainMeshVertex> implements RandomAccess {
        private final int size;
        private final int textureCoordinateCount;
        private final float[] positions;
        private final float[] normals;
        private final float[] alphaMapCoords;
        private final float[] lightValues;
        private final byte[] steadyLightRgb;
        private final float[] terrainTextureCoords;
        private final float @Nullable [] layerRows;
        private final float[] layerOrigin;

        private TerrainVertexBuffer(Builder source) {
            size = source.size;
            textureCoordinateCount = source.textureCoordinateCount;
            positions = source.positions;
            normals = source.normals;
            alphaMapCoords = source.alphaMapCoords;
            lightValues = source.lightValues;
            steadyLightRgb = source.steadyLightRgb;
            terrainTextureCoords = source.terrainTextureCoords;
            layerRows = source.layerRows;
            layerOrigin = source.layerOrigin;
        }

        public static Builder builder(int expectedSize, int textureCoordinateCount) {
            return new Builder(expectedSize, textureCoordinateCount);
        }

        public static TerrainVertexBuffer copyOf(List<TerrainMeshVertex> source) {
            Objects.requireNonNull(source, "source");
            if (source instanceof TerrainVertexBuffer primitive) {
                return primitive;
            }
            var textureCoordinateCount = 0;
            for (var vertex : source) {
                textureCoordinateCount = Math.max(
                        textureCoordinateCount,
                        vertex.terrainTextureCoords() == null ? 0 : vertex.terrainTextureCoords().length);
            }
            var builder = builder(source.size(), textureCoordinateCount);
            for (var vertex : source) {
                builder.add(vertex);
            }
            return builder.build();
        }

        @Override
        public TerrainMeshVertex get(int index) {
            var p3 = checked3(index);
            var p2 = index * 2;
            var textureOffset = index * textureCoordinateCount;
            return new TerrainMeshVertex(
                    new Vec3(positions[p3], positions[p3 + 1], positions[p3 + 2]),
                    new Vec2(alphaMapCoords[p2], alphaMapCoords[p2 + 1]),
                    lightValues[index],
                    Arrays.copyOfRange(terrainTextureCoords, textureOffset, textureOffset + textureCoordinateCount),
                    (getSteadyLightRed(index) << 16) | (getSteadyLightGreen(index) << 8) | getSteadyLightBlue(index),
                    new Vec3(normals[p3], normals[p3 + 1], normals[p3 + 2]));
        }

        @Override
        public int size() {
            return size;
        }

        public int getTextureCoordinateCount() {
            return textureCoordinateCount;
        }

        public float @Nullable [] getLayerRows() {
            return layerRows == null ? null : layerRows.clone();
        }

        public float[] getLayerOrigin() {
            return layerOrigin.clone();
        }

        public float getPositionX(int index) {
            return positions[checked3(index)];
        }

        public float getPositionY(int index) {
            return positions[checked3(index) + 1];
        }

        public float getPositionZ(int index) {
            return positions[checked3(index) + 2];
        }

        public float getNormalX(int index) {
            return normals[checked3(index)];
        }

        public float getNormalY(int index) {
            return normals[checked3(index) + 1];
        }

        public float getNormalZ(int index) {
            return normals[checked3(index) + 2];
        }

        public float getAlphaMapU(int index) {
            return alphaMapCoords[checked2(index)];
        }

        public float getAlphaMapV(int index) {
            return alphaMapCoords[checked2(index) + 1];
        }

        public float getLightValue(int index) {
            checkIndex(index);
            return lightValues[index];
        }

        public int getSteadyLightRed(int index) {
            return Byte.toUnsignedInt(steadyLightRgb[checked3(index)]);
        }

        public int getSteadyLightGreen(int index) {
            return Byte.toUnsignedInt(steadyLightRgb[checked3(index) + 1]);
        }

        public int getSteadyLightBlue(int index) {
            return Byte.toUnsignedInt(steadyLightRgb[checked3(index) + 2]);
        }

        public float getTerrainTextureCoordinate(int index, int coordinate) {
            checkIndex(index);
            Objects.checkIndex(coordinate, textureCoordinateCount);
            return terrainTextureCoords[index * textureCoordinateCount + coordinate];
        }

        private int checked2(int index) {
            checkIndex(index);
            return index * 2;
        }

        private int checked3(int index) {
            checkIndex(index);
            return index * 3;
        }

        private void checkIndex(int index) {
            Objects.checkIndex(index, size);
        }

        public static final class Builder {
            private final int textureCoordinateCount;
            private int size;
            private boolean built;
            private float[] positions;
            private float[] normals;
            private float[] alphaMapCoords;
            private float[] lightValues;
            private byte[] steadyLightRgb;
            private float[] terrainTextureCoords;
            private float @Nullable [] layerRows;
            private float[] layerOrigin = new float[3];

            private Builder(int expectedSize, int textureCoordinateCount) {
                if (expectedSize < 0 || textureCoordinateCount < 0) {
                    throw new IllegalArgumentException(
                            "negative terrain buffer dimensions: " + expectedSize + "/" + textureCoordinateCount);
                }
                this.textureCoordinateCount = textureCoordinateCount;
                var capacity = Math.max(1, expectedSize);
                positions = new float[Math.multiplyExact(capacity, 3)];
                normals = new float[Math.multiplyExact(capacity, 3)];
                alphaMapCoords = new float[Math.multiplyExact(capacity, 2)];
                lightValues = new float[capacity];
                steadyLightRgb = new byte[Math.multiplyExact(capacity, 3)];
                terrainTextureCoords = new float[Math.multiplyExact(capacity, textureCoordinateCount)];
            }

            public int size() {
                return size;
            }

            public boolean isEmpty() {
                return size == 0;
            }

            public int add(TerrainMeshVertex vertex) {
                Objects.requireNonNull(vertex, "vertex");
                var index = add(
                        vertex.position().getX(),
                        vertex.position().getY(),
                        vertex.position().getZ(),
                        vertex.alphaMapCoord().getX(),
                        vertex.alphaMapCoord().getY(),
                        vertex.lightValue());
                var coordinates = vertex.terrainTextureCoords();
                if (coordinates != null) {
                    System.arraycopy(
                            coordinates,
                            0,
                            terrainTextureCoords,
                            index * textureCoordinateCount,
                            Math.min(coordinates.length, textureCoordinateCount));
                }
                setSteadyLightRgb(index, vertex.steadyLightRgb());
                setNormal(
                        index,
                        vertex.normal().getX(),
                        vertex.normal().getY(),
                        vertex.normal().getZ());
                return index;
            }

            public int add(float x, float y, float z, float alphaU, float alphaV, float lightValue) {
                ensureCapacity(size + 1);
                var p3 = size * 3;
                positions[p3] = x;
                positions[p3 + 1] = y;
                positions[p3 + 2] = z;
                normals[p3 + 2] = 1f;
                var p2 = size * 2;
                alphaMapCoords[p2] = alphaU;
                alphaMapCoords[p2 + 1] = alphaV;
                lightValues[size] = lightValue;
                return size++;
            }

            public void setTerrainTextureCoordinate(int vertex, int coordinate, float value) {
                checkMutable();
                Objects.checkIndex(vertex, size);
                Objects.checkIndex(coordinate, textureCoordinateCount);
                terrainTextureCoords[vertex * textureCoordinateCount + coordinate] = value;
            }

            public void setLayerRows(float[] origin, float[] rows) {
                checkMutable();
                if (origin.length != 3 || rows.length != textureCoordinateCount * 4) {
                    throw new IllegalArgumentException("terrain layer rows need an origin of 3 floats and 8 floats for"
                            + " each layer: " + origin.length + "/" + rows.length + " for " + textureCoordinateCount
                            + " coordinates");
                }
                layerOrigin = origin.clone();
                layerRows = rows.clone();
            }

            public void setSteadyLightRgb(int vertex, int rgb) {
                checkMutable();
                Objects.checkIndex(vertex, size);
                var offset = vertex * 3;
                steadyLightRgb[offset] = (byte) (rgb >>> 16);
                steadyLightRgb[offset + 1] = (byte) (rgb >>> 8);
                steadyLightRgb[offset + 2] = (byte) rgb;
            }

            public void setNormal(int vertex, float x, float y, float z) {
                checkMutable();
                Objects.checkIndex(vertex, size);
                var offset = vertex * 3;
                normals[offset] = x;
                normals[offset + 1] = y;
                normals[offset + 2] = z;
            }

            public TerrainVertexBuffer build() {
                checkMutable();
                built = true;
                return new TerrainVertexBuffer(this);
            }

            private int capacity() {
                return lightValues.length;
            }

            private void ensureCapacity(int required) {
                checkMutable();
                if (required <= capacity()) {
                    return;
                }
                var next = Math.max(required, Math.max(8, capacity() << 1));
                positions = Arrays.copyOf(positions, next * 3);
                normals = Arrays.copyOf(normals, next * 3);
                alphaMapCoords = Arrays.copyOf(alphaMapCoords, next * 2);
                lightValues = Arrays.copyOf(lightValues, next);
                steadyLightRgb = Arrays.copyOf(steadyLightRgb, next * 3);
                terrainTextureCoords =
                        Arrays.copyOf(terrainTextureCoords, Math.multiplyExact(next, textureCoordinateCount));
            }

            private void checkMutable() {
                if (built) {
                    throw new IllegalStateException("terrain vertex builder was already built");
                }
            }
        }
    }

    public sealed interface Mesh permits EntityMesh, TerrainMesh {}

    public record ActorVertexLighting(
            byte @Nullable [] rgb,
            byte @Nullable [] visibilityMasks,
            boolean nativeSceneColor,
            boolean animatedAmbientGlow,
            byte @Nullable [] colorAlpha) {
        public ActorVertexLighting(
                byte @Nullable [] rgb,
                byte @Nullable [] visibilityMasks,
                boolean nativeSceneColor,
                boolean animatedAmbientGlow) {
            this(rgb, visibilityMasks, nativeSceneColor, animatedAmbientGlow, null);
        }

        public ActorVertexLighting(byte @Nullable [] rgb, byte @Nullable [] visibilityMasks) {
            this(rgb, visibilityMasks, false, false);
        }

        public ActorVertexLighting(byte @Nullable [] rgb, byte @Nullable [] visibilityMasks, boolean nativeSceneColor) {
            this(rgb, visibilityMasks, nativeSceneColor, false);
        }

        public ActorVertexLighting {
            if (rgb == null && visibilityMasks == null) {
                throw new IllegalArgumentException("actor vertex lighting requires at least one channel");
            }
            if (rgb != null && rgb.length % 3 != 0) {
                throw new IllegalArgumentException("actor RGB lighting length must be a multiple of three");
            }
            var vertexCount = rgb != null ? rgb.length / 3 : visibilityMasks.length;
            if (vertexCount < 1
                    || visibilityMasks != null && visibilityMasks.length != vertexCount
                    || colorAlpha != null && colorAlpha.length != vertexCount) {
                throw new IllegalArgumentException("actor lighting channels have different vertex counts");
            }
        }

        public int vertexCount() {
            return rgb != null ? rgb.length / 3 : visibilityMasks.length;
        }

        public boolean hasRgb() {
            return rgb != null;
        }

        public boolean hasVisibilityMasks() {
            return visibilityMasks != null;
        }

        public float red(int vertex) {
            return component(vertex, 0);
        }

        public float green(int vertex) {
            return component(vertex, 1);
        }

        public float blue(int vertex) {
            return component(vertex, 2);
        }

        public float alpha(int vertex) {
            Objects.checkIndex(vertex, vertexCount());
            return colorAlpha == null ? 0 : Byte.toUnsignedInt(colorAlpha[vertex]) / 255f;
        }

        private float component(int vertex, int component) {
            if (rgb == null) {
                throw new IllegalStateException("actor lighting has no RGB channel");
            }
            if (vertex < 0 || vertex >= vertexCount()) {
                throw new IndexOutOfBoundsException(vertex);
            }
            return Byte.toUnsignedInt(rgb[vertex * 3 + component]) / 255f;
        }
    }

    public record EntityMesh(
            List<MeshVertex> vertices,
            int[] indices,
            List<Surface> surfaces,
            List<Mat4> instanceMatrices,
            Box boundingBox,
            float swayFrequency,
            float swayMaxAngle,
            boolean billboard,
            boolean staticMeshInstanceLighting,
            float decorationFadeMin,
            float decorationFadeMax,
            List<Vec4> instanceLighting)
            implements Mesh {
        public EntityMesh(
                List<MeshVertex> vertices,
                int[] indices,
                List<Surface> surfaces,
                List<Mat4> instanceMatrices,
                Box boundingBox,
                float swayFrequency,
                float swayMaxAngle,
                boolean billboard,
                boolean staticMeshInstanceLighting,
                float decorationFadeMin,
                float decorationFadeMax) {
            this(
                    vertices,
                    indices,
                    surfaces,
                    instanceMatrices,
                    boundingBox,
                    swayFrequency,
                    swayMaxAngle,
                    billboard,
                    staticMeshInstanceLighting,
                    decorationFadeMin,
                    decorationFadeMax,
                    List.of());
        }

        public EntityMesh(
                List<MeshVertex> vertices,
                int[] indices,
                List<Surface> surfaces,
                List<Mat4> instanceMatrices,
                Box boundingBox,
                float swayFrequency,
                float swayMaxAngle,
                boolean billboard,
                boolean staticMeshInstanceLighting) {
            this(
                    vertices,
                    indices,
                    surfaces,
                    instanceMatrices,
                    boundingBox,
                    swayFrequency,
                    swayMaxAngle,
                    billboard,
                    staticMeshInstanceLighting,
                    0f,
                    0f,
                    List.of());
        }

        public EntityMesh(
                List<MeshVertex> vertices,
                int[] indices,
                List<Surface> surfaces,
                List<Mat4> instanceMatrices,
                Box boundingBox,
                float swayFrequency,
                boolean billboard,
                boolean staticMeshInstanceLighting) {
            this(
                    vertices,
                    indices,
                    surfaces,
                    instanceMatrices,
                    boundingBox,
                    swayFrequency,
                    1f,
                    billboard,
                    staticMeshInstanceLighting,
                    0f,
                    0f,
                    List.of());
        }

        public EntityMesh(
                List<MeshVertex> vertices,
                int[] indices,
                List<Surface> surfaces,
                List<Mat4> instanceMatrices,
                Box boundingBox,
                float swayFrequency,
                float swayMaxAngle,
                boolean billboard) {
            this(
                    vertices,
                    indices,
                    surfaces,
                    instanceMatrices,
                    boundingBox,
                    swayFrequency,
                    swayMaxAngle,
                    billboard,
                    false,
                    0f,
                    0f,
                    List.of());
        }

        public EntityMesh(
                List<MeshVertex> vertices,
                int[] indices,
                List<Surface> surfaces,
                List<Mat4> instanceMatrices,
                Box boundingBox,
                float swayFrequency,
                boolean billboard) {
            this(
                    vertices,
                    indices,
                    surfaces,
                    instanceMatrices,
                    boundingBox,
                    swayFrequency,
                    1f,
                    billboard,
                    false,
                    0f,
                    0f,
                    List.of());
        }

        public EntityMesh(
                List<MeshVertex> vertices,
                int[] indices,
                List<Surface> surfaces,
                List<Mat4> instanceMatrices,
                Box boundingBox,
                float swayFrequency) {
            this(
                    vertices,
                    indices,
                    surfaces,
                    instanceMatrices,
                    boundingBox,
                    swayFrequency,
                    1f,
                    false,
                    false,
                    0f,
                    0f,
                    List.of());
        }

        public EntityMesh {
            vertices = MeshVertexBuffer.copyOf(vertices);
            if (!instanceLighting.isEmpty() && instanceLighting.size() != instanceMatrices.size()) {
                throw new IllegalArgumentException("Instance lighting size mismatch: " + instanceLighting.size()
                        + " != " + instanceMatrices.size());
            }
        }

        public MeshVertexBuffer vertexBuffer() {
            return (MeshVertexBuffer) vertices;
        }
    }

    public record TerrainMesh(
            List<TerrainMeshVertex> vertices,
            int[] indices,
            Surface surface,
            Box boundingBox,
            float[][] lightVariants)
            implements Mesh {
        public TerrainMesh {
            vertices = TerrainVertexBuffer.copyOf(vertices);
        }

        public TerrainVertexBuffer vertexBuffer() {
            return (TerrainVertexBuffer) vertices;
        }
    }

    public static Mat4 modelMatrix(Vec3 position, Vec3 rotation, Vec3 scale) {
        return transformationMatrices(position, rotation, scale);
    }

    public static Mat4 actorModelMatrix(Vec3 location, Vec3 prePivot, Vec3 rotation, Vec3 scale) {
        return transformationMatrices(location, rotation, scale).translate(prePivot.times(-1f));
    }

    public static Vec3 actorModelOrigin(Vec3 location, Vec3 prePivot, Vec3 rotation, Vec3 scale) {
        return actorModelMatrix(location, prePivot, rotation, scale)
                .times(new Vec4(0f, 0f, 0f, 1f))
                .toVec3();
    }

    public @Nullable String getSourceActor() {
        return sourceActor;
    }

    public void setSourceActor(String name) {
        sourceActor = name;
    }

    public @Nullable String getSourceAsset() {
        return sourceAsset;
    }

    public void setSourceAsset(String name) {
        sourceAsset = name;
    }

    public List<SourcePart> getSourceParts() {
        return sourceParts;
    }

    public int getZoneRenderMask() {
        return zoneRender.mask();
    }

    public ZoneRender getZoneRender() {
        return zoneRender;
    }

    public void setZoneRender(ZoneRender zoneRender) {
        this.zoneRender = zoneRender;
    }

    public @Nullable ZoneLook getZoneLook() {
        return zoneLook;
    }

    public void setZoneLook(@Nullable ZoneLook zoneLook) {
        this.zoneLook = zoneLook;
    }

    public boolean isZoneLookCopy() {
        return zoneLook != null && zoneLook.copy();
    }

    public ZoneRender getActorZoneRender() {
        return zoneLook == null ? zoneRender : zoneLook.actor();
    }

    public void setSourceParts(List<SourcePart> parts) {
        sourceParts = List.copyOf(parts);
    }

    public boolean isDoor() {
        return door;
    }

    public void setDoor(boolean door) {
        this.door = door;
    }

    public L2Movement getMovement() {
        return movement;
    }

    public void setMovement(L2Movement movement) {
        this.movement = movement;
    }

    public @Nullable MotionLink getMotionLink() {
        return motionLink;
    }

    public void setMotionLink(@Nullable MotionLink motionLink) {
        this.motionLink = motionLink;
    }

    public Mat4 getModelMatrix() {
        return transformationMatrices(position, rotation, scale);
    }

    public float getSunLightMarker() {
        if (meshDecoration != null) {
            return MeshDecoration.MARKER;
        }
        if (fullbrightDecoration) {
            return 5f;
        }
        if (ambientOnlyLighting && modulateAmbientLighting2x) {
            return 4f;
        }
        if (ambientOnlyLighting) {
            return 3f;
        }
        return sunAffected ? 1f : 0f;
    }

    public List<Vec3> getTerrainDecorationSteadyLighting() {
        return terrainDecorationSteadyLighting;
    }

    public void setTerrainDecorationSteadyLighting(List<Vec3> lighting) {
        var instanceCount = getInstanceMatrices().size();
        if (!lighting.isEmpty() && lighting.size() != instanceCount) {
            throw new IllegalArgumentException(
                    "Terrain decoration steady-light size mismatch: " + lighting.size() + " != " + instanceCount);
        }
        terrainDecorationSteadyLighting = List.copyOf(lighting);
    }

    public List<Mat4> getInstanceMatrices() {
        return switch (mesh) {
            case EntityMesh entityMesh ->
                entityMesh.instanceMatrices.isEmpty() ? EMPTY_INSTANCE_MATRICES : entityMesh.instanceMatrices;
            case TerrainMesh _ -> EMPTY_INSTANCE_MATRICES;
        };
    }

    public Entity.Mesh getMesh() {
        return this.mesh;
    }

    public Vec3 getPosition() {
        return this.position;
    }

    public void setPosition(Vec3 position) {
        this.position = position;
    }

    public Vec3 getRotation() {
        return this.rotation;
    }

    public void setRotation(Vec3 rotation) {
        this.rotation = rotation;
    }

    public Vec3 getScale() {
        return this.scale;
    }

    public void setScale(Vec3 scale) {
        this.scale = scale;
    }

    public boolean isAcceptsProjectors() {
        return this.acceptsProjectors;
    }

    public void setAcceptsProjectors(boolean acceptsProjectors) {
        this.acceptsProjectors = acceptsProjectors;
    }

    public boolean isWireframe() {
        return this.wireframe;
    }

    public void setWireframe(boolean wireframe) {
        this.wireframe = wireframe;
    }

    public String getMeshName() {
        return this.meshName;
    }

    public void setMeshName(String meshName) {
        this.meshName = meshName;
    }

    public String getDebugName() {
        return this.debugName;
    }

    public void setDebugName(String debugName) {
        this.debugName = debugName;
    }

    public int getSourceBspSection() {
        return this.sourceBspSection;
    }

    public void setSourceBspSection(int sourceBspSection) {
        this.sourceBspSection = sourceBspSection;
    }

    public int getBspOrdinal() {
        return this.bspOrdinal;
    }

    public void setBspOrdinal(int bspOrdinal) {
        this.bspOrdinal = bspOrdinal;
    }

    public int @Nullable [] getBspSpans() {
        return this.bspSpans;
    }

    public void setBspSpans(int @Nullable [] bspSpans) {
        this.bspSpans = bspSpans;
    }

    public List<DebugLabel> getDebugLabels() {
        return this.debugLabels;
    }

    public void setDebugLabels(List<DebugLabel> debugLabels) {
        this.debugLabels = debugLabels;
    }

    public Texture getLightmap() {
        return this.lightmap;
    }

    public void setLightmap(Texture lightmap) {
        this.lightmap = lightmap;
    }

    public List<Texture> getLightmapVariants() {
        return this.lightmapVariants;
    }

    public void setLightmapVariants(List<Texture> lightmapVariants) {
        this.lightmapVariants = lightmapVariants;
    }

    public List<Integer> getLightmapVariantSlots() {
        return lightmapVariantSlots;
    }

    public void setLightmapVariantSlots(List<Integer> slots) {
        lightmapVariantSlots = List.copyOf(slots);
    }

    public boolean isBeastLightmap() {
        return beastLightmap;
    }

    public void setBeastLightmap(boolean beastLightmap) {
        this.beastLightmap = beastLightmap;
    }

    public boolean isCsgConcat() {
        return this.csgConcat;
    }

    public void setCsgConcat(boolean csgConcat) {
        this.csgConcat = csgConcat;
    }

    public List<Texture> getLightmaps() {
        return this.lightmaps;
    }

    public void setLightmaps(List<Texture> lightmaps) {
        this.lightmaps = lightmaps;
    }

    public List<Texture> getCsgLightmapVariants() {
        return this.csgLightmapVariants;
    }

    public void setCsgLightmapVariants(List<Texture> csgLightmapVariants) {
        this.csgLightmapVariants = csgLightmapVariants;
    }

    public int getCsgLightmapVariantCount() {
        return this.csgLightmapVariantCount;
    }

    public void setCsgLightmapVariantCount(int csgLightmapVariantCount) {
        this.csgLightmapVariantCount = csgLightmapVariantCount;
    }

    public int @Nullable [] getLightmapLayers() {
        return this.lightmapLayers;
    }

    public void setLightmapLayers(int @Nullable [] lightmapLayers) {
        this.lightmapLayers = lightmapLayers;
    }

    public int getBspLightmapSource(Texture texture) {
        var source = bspLightmapSources == null ? null : bspLightmapSources.get(texture);
        return source == null ? -1 : source;
    }

    public void setBspLightmapSource(Texture texture, int source) {
        if (bspLightmapSources == null) {
            bspLightmapSources = new IdentityHashMap<>();
        }
        bspLightmapSources.put(texture, source);
    }

    public Vec3 getInstanceLight() {
        return this.instanceLight;
    }

    public void setInstanceLight(Vec3 instanceLight) {
        this.instanceLight = instanceLight;
    }

    public boolean isSunAffected() {
        return this.sunAffected;
    }

    public void setSunAffected(boolean sunAffected) {
        this.sunAffected = sunAffected;
    }

    public boolean isAmbientOnlyLighting() {
        return this.ambientOnlyLighting;
    }

    public void setAmbientOnlyLighting(boolean ambientOnlyLighting) {
        this.ambientOnlyLighting = ambientOnlyLighting;
    }

    public boolean isModulateAmbientLighting2x() {
        return this.modulateAmbientLighting2x;
    }

    public void setModulateAmbientLighting2x(boolean modulateAmbientLighting2x) {
        this.modulateAmbientLighting2x = modulateAmbientLighting2x;
    }

    public boolean isFullbrightDecoration() {
        return this.fullbrightDecoration;
    }

    public void setFullbrightDecoration(boolean fullbrightDecoration) {
        this.fullbrightDecoration = fullbrightDecoration;
    }

    public int getTerrainDecorationZone() {
        return this.terrainDecorationZone;
    }

    public void setTerrainDecorationZone(int terrainDecorationZone) {
        this.terrainDecorationZone = terrainDecorationZone;
    }

    public @Nullable MeshDecoration getMeshDecoration() {
        return meshDecoration;
    }

    public void setMeshDecoration(@Nullable MeshDecoration meshDecoration) {
        this.meshDecoration = meshDecoration;
    }

    public ActorVertexLighting getActorVertexLighting() {
        return this.actorVertexLighting;
    }

    public @Nullable NativeLightReceiver getNativeLightReceiver() {
        return nativeLightReceiver;
    }

    public void setNativeLightReceiver(@Nullable NativeLightReceiver receiver) {
        nativeLightReceiver = receiver;
    }

    public @Nullable NativeStaticMeshLightingInputs getNativeStaticMeshLightingInputs() {
        return nativeStaticMeshLightingInputs;
    }

    public void setNativeStaticMeshLightingInputs(@Nullable NativeStaticMeshLightingInputs inputs) {
        nativeStaticMeshLightingInputs = inputs;
    }

    public void setActorVertexLighting(ActorVertexLighting actorVertexLighting) {
        this.actorVertexLighting = actorVertexLighting;
    }

    public byte @Nullable [] getEnvironmentVisibilityMasks() {
        return this.environmentVisibilityMasks;
    }

    public void setEnvironmentVisibilityMasks(byte @Nullable [] environmentVisibilityMasks) {
        this.environmentVisibilityMasks = environmentVisibilityMasks;
    }

    public @Nullable Vec3 getHardwareSunLight() {
        return this.hardwareSunLight;
    }

    public void setHardwareSunLight(@Nullable Vec3 hardwareSunLight) {
        this.hardwareSunLight = hardwareSunLight;
    }

    public @Nullable HardwareSunDirection getHardwareSunDirection() {
        return hardwareSunDirection;
    }

    public void setHardwareSunDirection(@Nullable HardwareSunDirection direction) {
        hardwareSunDirection = direction;
    }

    public @Nullable HardwareSunEnvironment getHardwareSunEnvironment() {
        return hardwareSunEnvironment;
    }

    public void setHardwareSunEnvironment(@Nullable HardwareSunEnvironment environment) {
        hardwareSunEnvironment = environment;
    }

    public @Nullable HardwareSunSource getHardwareSunSource() {
        return hardwareSunSource;
    }

    public void setHardwareSunSource(@Nullable HardwareSunSource source) {
        hardwareSunSource = source;
    }

    public boolean isFlatSunAmbient() {
        return this.flatSunAmbient;
    }

    public void setFlatSunAmbient(boolean flatSunAmbient) {
        this.flatSunAmbient = flatSunAmbient;
    }

    public float getStaticMeshAmbientIntensity() {
        return staticMeshAmbientIntensity;
    }

    public void setStaticMeshAmbientIntensity(float intensity) {
        staticMeshAmbientIntensity = intensity;
    }

    public @Nullable StaticMeshAmbient getStaticMeshAmbient() {
        return staticMeshAmbient;
    }

    public void setStaticMeshAmbient(@Nullable StaticMeshAmbient ambient) {
        staticMeshAmbient = ambient;
    }

    public boolean isMergedRegion() {
        return this.mergedRegion;
    }

    public void setMergedRegion(boolean mergedRegion) {
        this.mergedRegion = mergedRegion;
    }

    public StaticMeshLod getStaticMeshLod() {
        return this.staticMeshLod;
    }

    public @Nullable NativeStaticMeshDraw getNativeStaticMeshDraw() {
        return nativeStaticMeshDraw;
    }

    public @Nullable NativeVertexLightmapInputs getNativeVertexLightmapInputs() {
        return nativeVertexLightmapInputs;
    }

    public void setNativeVertexLightmapInputs(@Nullable NativeVertexLightmapInputs inputs) {
        nativeVertexLightmapInputs = inputs;
    }

    public void setNativeStaticMeshDraw(@Nullable NativeStaticMeshDraw draw) {
        nativeStaticMeshDraw = draw;
    }

    public void setStaticMeshLod(StaticMeshLod staticMeshLod) {
        this.staticMeshLod = staticMeshLod;
    }

    public boolean isFoliageLighting() {
        return this.foliageLighting;
    }

    public void setFoliageLighting(boolean foliageLighting) {
        this.foliageLighting = foliageLighting;
    }

    public float @Nullable [] getSwayVertexParams() {
        return this.swayVertexParams;
    }

    public void setSwayVertexParams(float @Nullable [] swayVertexParams) {
        this.swayVertexParams = swayVertexParams;
    }

    private static Mat4 transformationMatrices(Vec3 position, Vec3 rotation, Vec3 scale) {
        var matrix = Entity.MAT_4_1.translate(position);
        return matrix.times(new Quat(rotation).toMat4()).scale(scale);
    }
}
