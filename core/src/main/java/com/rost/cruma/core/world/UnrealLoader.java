package com.rost.cruma.core.world;

import static java.util.Comparator.comparingDouble;
import static java.util.Comparator.comparingInt;

import com.rost.cruma.core.audio.SoundClip;
import com.rost.cruma.core.audio.SoundClipCache;
import com.rost.cruma.core.editing.NativeTerrainLayerPasses;
import com.rost.cruma.core.editing.TerrainEditorGeometry;
import com.rost.cruma.core.editing.TerrainEditorGeometry.LayerSource;
import com.rost.cruma.core.editing.TerrainLayerMapping;
import com.rost.cruma.core.editing.TerrainNativeCodec.Info;
import com.rost.cruma.core.env.EnvSetup;
import com.rost.cruma.core.env.EnvSetupParser;
import com.rost.cruma.core.env.MovableSun;
import com.rost.cruma.core.env.UnrealHsv;
import com.rost.cruma.core.geometry.Bounds3f;
import com.rost.cruma.core.geometry.Box;
import com.rost.cruma.core.npc.NpcSocials;
import com.rost.cruma.core.npc.script.ScriptedClass;
import com.rost.cruma.core.texture.DxtMipCodec;
import com.rost.cruma.core.texture.DxtMipCodec.Options;
import com.rost.cruma.core.texture.TexturePixels;
import com.rost.cruma.core.unreal.Bsp.BspNodeFlags;
import com.rost.cruma.core.unreal.Bsp.BspPolygonFlags;
import com.rost.cruma.core.unreal.Bsp.FBspSection;
import com.rost.cruma.core.unreal.Bsp.FLightmapIndex;
import com.rost.cruma.core.unreal.Bsp.FStaticLightmapTexture;
import com.rost.cruma.core.unreal.Bsp.UBspNode;
import com.rost.cruma.core.unreal.Bsp.UBspSurface;
import com.rost.cruma.core.unreal.Bsp.UModel;
import com.rost.cruma.core.unreal.DecryptedPackages;
import com.rost.cruma.core.unreal.EffectClasses;
import com.rost.cruma.core.unreal.EffectClasses.Effect;
import com.rost.cruma.core.unreal.EffectClasses.Projectile;
import com.rost.cruma.core.unreal.EffectClasses.Quake;
import com.rost.cruma.core.unreal.EffectPackages;
import com.rost.cruma.core.unreal.MeshDecorationData;
import com.rost.cruma.core.unreal.NpcClassScripts;
import com.rost.cruma.core.unreal.NpcClasses;
import com.rost.cruma.core.unreal.NpcClasses.Body;
import com.rost.cruma.core.unreal.SkeletalPose;
import com.rost.cruma.core.unreal.SkillEffects;
import com.rost.cruma.core.unreal.SkillEffects.Skill;
import com.rost.cruma.core.unreal.UActor;
import com.rost.cruma.core.unreal.UActor.AmbientSoundActor;
import com.rost.cruma.core.unreal.UActor.AmbientSoundWObjectActor;
import com.rost.cruma.core.unreal.UActor.AmbientVolumeActor;
import com.rost.cruma.core.unreal.UActor.BlockingVolumeActor;
import com.rost.cruma.core.unreal.UActor.EmitterActor;
import com.rost.cruma.core.unreal.UActor.L2FogInfoActor;
import com.rost.cruma.core.unreal.UActor.L2SkyLevelZoneInfoActor;
import com.rost.cruma.core.unreal.UActor.LightActor;
import com.rost.cruma.core.unreal.UActor.NCelestialActor;
import com.rost.cruma.core.unreal.UActor.PhysicsVolumeActor;
import com.rost.cruma.core.unreal.UActor.ProjectorActor;
import com.rost.cruma.core.unreal.UActor.SkyZoneInfoActor;
import com.rost.cruma.core.unreal.UActor.StaticMeshActor;
import com.rost.cruma.core.unreal.UActor.WaterVolumeActor;
import com.rost.cruma.core.unreal.UActor.ZoneInfoActor;
import com.rost.cruma.core.unreal.UArchive;
import com.rost.cruma.core.unreal.UArchiveLoader;
import com.rost.cruma.core.unreal.UArchiveLoader.USearchConfig;
import com.rost.cruma.core.unreal.UBitSet;
import com.rost.cruma.core.unreal.UDecryptor;
import com.rost.cruma.core.unreal.UEmitter.UBeamEmitter;
import com.rost.cruma.core.unreal.UEmitter.UMeshEmitter;
import com.rost.cruma.core.unreal.UEmitter.UParticleEmitter;
import com.rost.cruma.core.unreal.UEmitter.USpriteEmitter;
import com.rost.cruma.core.unreal.UEmitter.UTrailEmitter;
import com.rost.cruma.core.unreal.UEmitter.UVertMeshEmitter;
import com.rost.cruma.core.unreal.UL2MovableStaticMeshActor;
import com.rost.cruma.core.unreal.ULevel;
import com.rost.cruma.core.unreal.UMaterial;
import com.rost.cruma.core.unreal.UMaterial.EAlphaOperation;
import com.rost.cruma.core.unreal.UMaterial.EColorOperation;
import com.rost.cruma.core.unreal.UMaterial.FrameBufferBlending;
import com.rost.cruma.core.unreal.UMaterial.OutputBlending;
import com.rost.cruma.core.unreal.UMaterial.TextureClampMode;
import com.rost.cruma.core.unreal.UMaterial.UColorModifier;
import com.rost.cruma.core.unreal.UMaterial.UCombiner;
import com.rost.cruma.core.unreal.UMaterial.UConstantColor;
import com.rost.cruma.core.unreal.UMaterial.UCubemap;
import com.rost.cruma.core.unreal.UMaterial.UFadeColor;
import com.rost.cruma.core.unreal.UMaterial.UFinalBlend;
import com.rost.cruma.core.unreal.UMaterial.UMipmap;
import com.rost.cruma.core.unreal.UMaterial.UModifier;
import com.rost.cruma.core.unreal.UMaterial.URenderedMaterial;
import com.rost.cruma.core.unreal.UMaterial.UShader;
import com.rost.cruma.core.unreal.UMaterial.UTexCoordSource;
import com.rost.cruma.core.unreal.UMaterial.UTexEnvMap;
import com.rost.cruma.core.unreal.UMaterial.UTexOscillator;
import com.rost.cruma.core.unreal.UMaterial.UTexPanner;
import com.rost.cruma.core.unreal.UMaterial.UTexPannerTriggered;
import com.rost.cruma.core.unreal.UMaterial.UTexRotator;
import com.rost.cruma.core.unreal.UMaterial.UTexScaler;
import com.rost.cruma.core.unreal.UMaterial.UTexture;
import com.rost.cruma.core.unreal.UMaterial.UVertexColor;
import com.rost.cruma.core.unreal.UMeshAnimation;
import com.rost.cruma.core.unreal.UMeshAnimation.Motion;
import com.rost.cruma.core.unreal.UMissingObjectException;
import com.rost.cruma.core.unreal.UMovableStaticMeshActor;
import com.rost.cruma.core.unreal.UMover;
import com.rost.cruma.core.unreal.UObject;
import com.rost.cruma.core.unreal.UObjectRef;
import com.rost.cruma.core.unreal.UPackage;
import com.rost.cruma.core.unreal.UPackageLoader;
import com.rost.cruma.core.unreal.UProperty;
import com.rost.cruma.core.unreal.USkeletalMesh;
import com.rost.cruma.core.unreal.USound;
import com.rost.cruma.core.unreal.UStaticMesh;
import com.rost.cruma.core.unreal.UStaticMesh.Material;
import com.rost.cruma.core.unreal.UStaticMeshInstance;
import com.rost.cruma.core.unreal.UStaticMeshInstance.ColorStream;
import com.rost.cruma.core.unreal.UStaticMeshInstance.LightInfo;
import com.rost.cruma.core.unreal.UTerrain.UDecorationLayer;
import com.rost.cruma.core.unreal.UTerrain.UTerrainInfoActor;
import com.rost.cruma.core.unreal.UTerrain.UTerrainSector;
import com.rost.cruma.core.unreal.UTerrain.UTerrainSector.TerrainLightInfo;
import com.rost.cruma.core.unreal.UVertMesh;
import com.rost.cruma.core.unreal.primitives.UColor;
import com.rost.cruma.core.unreal.primitives.URotator;
import com.rost.cruma.core.unreal.primitives.USphere;
import com.rost.cruma.core.world.AmbientSound.Type;
import com.rost.cruma.core.world.AmbientSoundList.PlayMode;
import com.rost.cruma.core.world.AuthoredMaterialCompiler.Client;
import com.rost.cruma.core.world.CsgDepthResolver.Face;
import com.rost.cruma.core.world.CsgDepthResolver.Resolution;
import com.rost.cruma.core.world.Entity.ActorVertexLighting;
import com.rost.cruma.core.world.Entity.AnimatedTextureEntityMaterial;
import com.rost.cruma.core.world.Entity.BlendMode;
import com.rost.cruma.core.world.Entity.BspDrawSource;
import com.rost.cruma.core.world.Entity.ColorEntityMaterial;
import com.rost.cruma.core.world.Entity.ColorModifierEntityMaterial;
import com.rost.cruma.core.world.Entity.CombinerEntityMaterial;
import com.rost.cruma.core.world.Entity.ConstantOpacityEntityMaterial;
import com.rost.cruma.core.world.Entity.Cubemap;
import com.rost.cruma.core.world.Entity.DebugLabel;
import com.rost.cruma.core.world.Entity.DistanceFog;
import com.rost.cruma.core.world.Entity.EntityMaterial;
import com.rost.cruma.core.world.Entity.EntityMesh;
import com.rost.cruma.core.world.Entity.EnvCubeLayer;
import com.rost.cruma.core.world.Entity.EnvMapEntityMaterial;
import com.rost.cruma.core.world.Entity.EnvironmentLayer;
import com.rost.cruma.core.world.Entity.FadeColorSpec;
import com.rost.cruma.core.world.Entity.HardwareSunDirection;
import com.rost.cruma.core.world.Entity.HardwareSunEnvironment;
import com.rost.cruma.core.world.Entity.HardwareSunSource;
import com.rost.cruma.core.world.Entity.MeshVertex;
import com.rost.cruma.core.world.Entity.MeshVertexBuffer;
import com.rost.cruma.core.world.Entity.MultiTexture;
import com.rost.cruma.core.world.Entity.MultiTextureEntityMaterial;
import com.rost.cruma.core.world.Entity.MultiTextureEntry;
import com.rost.cruma.core.world.Entity.ReflectiveEntityMaterial;
import com.rost.cruma.core.world.Entity.SpecularEntityMaterial;
import com.rost.cruma.core.world.Entity.SpecularOverlay;
import com.rost.cruma.core.world.Entity.StaticMeshLod;
import com.rost.cruma.core.world.Entity.SurfaceType;
import com.rost.cruma.core.world.Entity.TerrainAlphaAtlas;
import com.rost.cruma.core.world.Entity.TerrainMesh;
import com.rost.cruma.core.world.Entity.TerrainVertexBuffer;
import com.rost.cruma.core.world.Entity.Texture;
import com.rost.cruma.core.world.Entity.TextureEntityMaterial;
import com.rost.cruma.core.world.Entity.TextureEntityMaterialWithOpacity;
import com.rost.cruma.core.world.Entity.TextureFormat;
import com.rost.cruma.core.world.Entity.UvAnimation;
import com.rost.cruma.core.world.Entity.WaterEntityMaterial;
import com.rost.cruma.core.world.Entity.WaterSheetSource;
import com.rost.cruma.core.world.L2FogInfo.ColorKey;
import com.rost.cruma.core.world.L2FogInfo.Range;
import com.rost.cruma.core.world.NativeStaticMeshLod.Mesh;
import com.rost.cruma.core.world.StaticMeshAmbient.Source;
import com.rost.cruma.core.world.SunVisibility.MeshOccluders;
import com.rost.cruma.core.world.SunVisibility.MeshOccluders.TriangleMesh;
import com.rost.cruma.core.world.SunVisibility.Occluders;
import com.rost.cruma.core.world.SunVisibility.TerrainField;
import com.rost.cruma.core.world.USky.Celestial;
import com.rost.cruma.core.world.USky.Flare;
import com.rost.cruma.core.world.USky.Layer;
import com.rost.cruma.core.world.WaterVolume.FogSettings;
import glm_.mat3x3.Mat3;
import glm_.mat4x4.Mat4;
import glm_.vec2.Vec2;
import glm_.vec3.Vec3;
import glm_.vec4.Vec4;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.collections.api.factory.primitive.ObjectIntMaps;
import org.eclipse.collections.api.map.primitive.ObjectIntMap;
import org.eclipse.collections.impl.list.mutable.primitive.FloatArrayList;
import org.eclipse.collections.impl.list.mutable.primitive.IntArrayList;
import org.jetbrains.annotations.Nullable;

public class UnrealLoader {
    private static final String CAMERA_MESH = "Camera.CAMERA_REC";
    private static final int BEAM_END_ACTOR = 3;
    private static final int TERRAIN_SECTOR_SIZE = 16;
    private static final int TERRAIN_RENDER_LAYERS = 32;
    private static final int TERRAIN_FIRST_DRAW_LAYERS = 12;
    private static final int TERRAIN_ALPHA_ATLAS_MAX_EXTENT = 1_024;
    private static final int SHADOW_VARIANT_COUNT = 8;
    private static final Vec4 YELLOW_COLOR = new Vec4(1.0f, 1.0f, 0.7f, 1f);
    private static final Vec4 TERRAIN_COLOR = new Vec4(0.85f, 0.85f, 0.85f, 1f);
    private static final Vec4 VERTEX_COLOR_BASE = new Vec4(1f, 1f, 1f, 1f);
    private static final float LIGHT_RADIUS_SCALE = 25f;
    private static final int LE_STATIC_SPOT = 8;
    private static final int LE_SPOTLIGHT = 12;
    private static final int LE_NON_INCIDENCE = 13;
    private static final int LE_CYLINDER = 17;
    private static final int LE_SUNLIGHT = LightActor.LE_SUNLIGHT;
    private static final int DRAW_TYPE_SUN = 13;
    private static final int LE_QUADRATIC_NON_INCIDENCE = 20;
    private static final float LEGACY_REJECTED_INSTANCE_SUN_SCALE = 2f * 0.8f * 0.875f;
    private static final float STATIC_MESH_SCENE_LIGHT_SCALE = 0.5f;
    private static final float BAKE_INTENSITY = 1.2f;
    private static final float INTERIOR_AMBIENT_LEVEL = 0.12f;
    private static final float PROP_FLOOR_MIN = 0.5f;
    private static final float PROP_FLOOR_SUN = 0.4f;
    private static final int EXACT_INTERIOR_MESH_MIN_VERTICES = 2_048;
    private static final float EXACT_INTERIOR_ORIGIN_SATURATION = 0.98f;
    private static final float SUN_OCCLUSION_SHADOW = 0.65f;
    private static final float PROP_SHADOW_FLOOR = 0.30f;
    private static final float SKY_VIS_LO = 0.15f;
    private static final float SKY_VIS_HI = 0.5f;
    private static final float AUTO_PAN_RATE = 0.15f;
    private static final float EMITTER_DEFAULT_SIZE = 64f;
    private static final float EMITTER_SCALE_MIN = 0.3f;
    private static final float EMITTER_SCALE_MAX = 3.0f;
    private static final float ALPHA_OPAQUE_LEVEL = 0.98f;
    private static final float EMITTER_MIN_OPACITY = 0.15f;
    private static final long EMITTER_RNG_SEED = 1337L;
    private static final Vec4 FIRE_COLOR = new Vec4(1.0f, 0.55f, 0.15f, 0.7f);
    private static final int EMITTER_CLUSTER_COUNT = 5;
    private static final float EMITTER_CLUSTER_SPREAD = 0.35f;
    private static final float EMITTER_CLUSTER_SIZE_VAR = 0.4f;
    private static final float EMITTER_SIZE_MAX = 300f;
    private static final int MAX_ANIM_FRAMES = 64;
    private static final float EMITTER_FX_BOOST = 1.8f;
    private static final MultiTextureEntry EMPTY_MULTI_TEXTURE_ENTRY = new MultiTextureEntry(null, null);
    private static final Vec3 VEC_3_1 = new Vec3(1.0f, 1.0f, 1.0f);
    private static final Vec3 VEC3_EMPTY = new Vec3();
    private static final Vec2 VEC2_EMPTY = new Vec2();
    private static final Vec2 LIGHTMAP_NONE = new Vec2(-1f, -1f);
    private static final ColorEntityMaterial BOUNDING_BOX_MATERIAL =
            new ColorEntityMaterial(new Vec4(1.0f, 1.0f, 1.0f, 1f));
    private static final ColorEntityMaterial DEFAULT_MESH_ACTOR_MATERIAL =
            new ColorEntityMaterial(new Vec4(1.0f, 0.6f, 0.6f, 1f));
    private static final ColorEntityMaterial DEFAULT_PASSABLE_MATERIAL =
            new ColorEntityMaterial(new Vec4(0.7f, 1.0f, 0.7f, 1f));
    private static final Set<String> REPORTED_MALFORMED_VERT_MESHES = ConcurrentHashMap.newKeySet();
    private static final Set<String> REPORTED_UNKNOWN_TEXTURE_FORMATS = ConcurrentHashMap.newKeySet();
    private static final Set<Path> CLIENTS_WITHOUT_SCRIPT_TEXT = ConcurrentHashMap.newKeySet();

    private static final float WORLD_EXTENT = 10_000_000f;

    private static final String OLD_PLAYER_PACKAGE = "LineageWarriors";

    private static final String HEAD_BONE = "Bip01_head";

    private static final double CARRY_REACH = 4;

    private static final List<String> PLAYER_BODY = List.of("u", "l", "g", "b");

    private static final Map<String, BareStandIn> BARE_STAND_INS = Map.of(
            "fdarkelf_b", new BareStandIn(null, "FElf.FElf_m000_t1000_b", new Vec4(.437f, .623f, .734f, 1f)),
            "mertheia_u", new BareStandIn("Ertheia.Mertheia_M001_u", "MErtheia.MErtheia_m001_t62_u", null),
            "mertheia_l", new BareStandIn("Ertheia.Mertheia_M001_l", "MErtheia.MErtheia_m001_t62_l", null),
            "mertheia_g", new BareStandIn("Ertheia.Mertheia_M001_g", "MErtheia.MErtheia_m001_t62_g", null),
            "mertheia_b", new BareStandIn("Ertheia.Mertheia_M001_b", "MErtheia.MErtheia_m001_t62_b", null),
            "mertheia_e", new BareStandIn(null, "LineageFaceTex.Mertheia_m000_t01_e_sp", null));

    private static final int NOT_DRAWN_POLYGON_FLAGS =
            BspPolygonFlags.PF_Invisible | BspPolygonFlags.PF_Portal | BspPolygonFlags.PF_FakeBackdrop;

    private static final float MESH_COINCIDENCE_NUDGE = 3f;
    private static final float MESH_PLANE_EPS = 2f;
    private static final int MESH_COINCIDENCE_MIN_VERTS = 4;

    private static final Logger log = LogManager.getLogger(UnrealLoader.class);
    private static final String BEAST_LIGHTMAP_MARKER = "_Lightmap_";
    private static final int SOLID_PLATE_MAX_TEXELS = 64 * 64;
    private static final int SOLID_PLATE_SPREAD = 8;

    private final UPackageLoader packageLoader;
    private @Nullable Boolean meshComponents;
    private @Nullable Boolean essenceZoneStates;
    private EmitterResetPolicy.@Nullable ClientRule emitterClientRule;
    private final Path clientRoot;
    private WorldVariant variant = WorldVariant.MAIN;
    private @Nullable NpcSocials classTable;
    private @Nullable ClassHeads classHeads;
    private @Nullable FigureBodies figureBodies;
    private @Nullable HairRows hairRows;
    private Map<String, String> mapPackages = Map.of();
    private volatile @Nullable Boolean sharedSky;
    private volatile @Nullable Set<String> skillEffectPackages;
    private final DxtChainCache dxtChains;
    private final SoundClipCache soundClips;

    private int telemetryAttempts;
    private int telemetryLoaded;
    private long telemetryTotalNanos;
    private long telemetryPackageNanos;
    private long telemetryDxtNanos;
    private long telemetryTerrainNanos;
    private long telemetryLightingNanos;
    private long telemetryStaticMeshNanos;
    private long telemetryStaticMeshOccluderNanos;
    private long telemetryStaticMeshIndexNanos;
    private long telemetryStaticMeshMergeNanos;
    private long telemetryStaticMeshActors;
    private long telemetryStaticMeshActorVertices;
    private long telemetryStaticMeshLightStreamActors;
    private long telemetryStaticMeshLightStreamVertices;
    private long telemetryStaticMeshSpatialActors;
    private long telemetryStaticMeshExactActors;
    private long telemetryStaticMeshLodActors;
    private long telemetryStaticMeshLod2Actors;
    private long telemetryStaticMeshLod0Vertices;
    private long telemetryStaticMeshLod1Vertices;
    private long telemetryStaticMeshLod2Vertices;
    private long telemetryBspNanos;
    private long telemetryEffectsNanos;
    private String telemetrySlowestMap = "";
    private long telemetrySlowestMapNanos;

    private final Map<String, EntityMesh> meshCache = new HashMap<>();
    private final AuthoredMaterialCompiler authoredMaterials =
            new AuthoredMaterialCompiler(this::loadTextureSequence, this::authoredMaterialIdentity);
    private final Map<String, EntityMesh> bbMeshCache = new HashMap<>();
    private final Map<String, List<EntityMesh>> vertMeshFrameCache = new HashMap<>();
    private final Map<String, TriangleMesh> occluderMeshCache = new HashMap<>();

    private @Nullable ObjectIntMap<String> oneOffUsage;
    private int oneOffMergeVertexBudget;
    private boolean oneOffMergeCommutative;
    private final boolean csgArrayConcat;
    private final TextureQuality textureQuality;
    private final MaterialClient materialClient;
    private @Nullable Boolean reflectionTextOfP152;
    private @Nullable Boolean reflectionReplacedWithoutLight;
    private boolean mapActorMaterials;
    private boolean npcPartMaterials;
    private boolean constantsFromParticleColor;
    private float impostorDistanceMultiplier = 1f;
    private boolean decorationsNearCamera;

    public UnrealLoader(Path root, Options dxtMipOptions, boolean csgArrayConcat) {
        this(root, new DxtChainCache(dxtMipOptions), csgArrayConcat);
    }

    public UnrealLoader(Path root, Options dxtMipOptions, boolean csgArrayConcat, TextureQuality textureQuality) {
        this(root, new DxtChainCache(dxtMipOptions), new SoundClipCache(), csgArrayConcat, textureQuality);
    }

    public UnrealLoader(
            Path root,
            Options dxtMipOptions,
            boolean csgArrayConcat,
            TextureQuality textureQuality,
            MaterialClient materialClient) {
        this(
                root,
                new DxtChainCache(dxtMipOptions),
                new SoundClipCache(),
                csgArrayConcat,
                textureQuality,
                materialClient);
    }

    public UnrealLoader(Path root, DxtChainCache dxtChains, boolean csgArrayConcat) {
        this(root, dxtChains, new SoundClipCache(), csgArrayConcat);
    }

    public UnrealLoader(Path root, DxtChainCache dxtChains, SoundClipCache soundClips, boolean csgArrayConcat) {
        this(root, dxtChains, soundClips, csgArrayConcat, TextureQuality.FULL);
    }

    public UnrealLoader(
            Path root,
            DxtChainCache dxtChains,
            SoundClipCache soundClips,
            boolean csgArrayConcat,
            TextureQuality textureQuality) {
        this(root, dxtChains, soundClips, csgArrayConcat, textureQuality, MaterialClient.detect(root));
    }

    public UnrealLoader(
            Path root,
            DxtChainCache dxtChains,
            SoundClipCache soundClips,
            boolean csgArrayConcat,
            TextureQuality textureQuality,
            MaterialClient materialClient) {
        this(root, dxtChains, soundClips, csgArrayConcat, textureQuality, materialClient, null);
    }

    public UnrealLoader(
            Path root,
            DxtChainCache dxtChains,
            SoundClipCache soundClips,
            boolean csgArrayConcat,
            TextureQuality textureQuality,
            DecryptedPackages sharedPackages) {
        this(root, dxtChains, soundClips, csgArrayConcat, textureQuality, MaterialClient.detect(root), sharedPackages);
    }

    private UnrealLoader(
            Path root,
            DxtChainCache dxtChains,
            SoundClipCache soundClips,
            boolean csgArrayConcat,
            TextureQuality textureQuality,
            MaterialClient materialClient,
            @Nullable DecryptedPackages sharedPackages) {
        this.materialClient = Objects.requireNonNull(materialClient);
        if (dxtChains == null) {
            throw new IllegalArgumentException("DXT chain cache cannot be null");
        }
        if (soundClips == null) {
            throw new IllegalArgumentException("sound clip cache cannot be null");
        }
        this.dxtChains = dxtChains;
        this.soundClips = soundClips;
        this.textureQuality = Objects.requireNonNull(textureQuality, "texture quality");
        this.csgArrayConcat = csgArrayConcat;
        this.clientRoot = root;
        dxtChains.trimThreadState();
        this.packageLoader = new UPackageLoader(
                root,
                List.of(
                        new USearchConfig("Maps", "unr"),
                        new USearchConfig("Animations", "ukx"),
                        new USearchConfig("StaticMeshes", "usx"),
                        new USearchConfig("Textures", "utx"),
                        new USearchConfig("SysTextures", "utx"),
                        new USearchConfig("System", "u"),
                        new USearchConfig("Sounds", "uax")
                        ),
                sharedPackages);
    }

    static List<Layer> orderedSkyLayers(UModel model, List<Entity> entities, Vec3 viewOrigin) {
        var bySection = new LinkedHashMap<Integer, Entity>();
        for (var entity : entities) {
            if (entity.getSourceBspSection() >= 0) {
                bySection.put(entity.getSourceBspSection(), entity);
            }
        }

        var result = new ArrayList<Layer>(entities.size());
        var emitted = new LinkedHashSet<Integer>();

        for (var entity : entities) {
            if (writesOpaqueSkyDepth(entity) && emitted.add(entity.getSourceBspSection())) {
                result.add(skyLayer(entity));
            }
        }
        var solidCount = result.size();

        var processOrder = new ArrayList<Integer>();
        collectSkyBspProcessOrder(model, 0, viewOrigin, processOrder, new boolean[model.getNodes().length]);
        for (var i = processOrder.size() - 1; i >= 0; i--) {
            var node = model.getNodes()[processOrder.get(i)];
            var section = node.getISection();
            var entity = bySection.get(section);
            if (entity != null && !writesOpaqueSkyDepth(entity) && emitted.add(section)) {
                result.add(skyLayer(entity));
            }
        }

        for (var entity : entities) {
            if (emitted.add(entity.getSourceBspSection())) {
                result.add(skyLayer(entity));
            }
        }
        result.subList(solidCount, result.size())
                .sort(comparingDouble((Layer layer) -> skyLayerRadiusSquared(layer, viewOrigin))
                        .reversed());
        return List.copyOf(result);
    }

    static void packTerrainAlphaChannel(
            byte[] packed,
            int atlasWidth,
            int atlasHeight,
            int channel,
            byte[] red,
            int sourceWidth,
            int sourceHeight) {
        if (atlasWidth == sourceWidth && atlasHeight == sourceHeight) {
            for (var pixel = 0; pixel < red.length; pixel++) {
                packed[pixel * 4 + channel] = red[pixel];
            }
            return;
        }

        var sourceXByAtlasX = new int[atlasWidth];
        for (var x = 0; x < atlasWidth; x++) {
            sourceXByAtlasX[x] = Math.min(sourceWidth - 1, x * sourceWidth / atlasWidth);
        }
        for (var y = 0; y < atlasHeight; y++) {
            var sourceY = Math.min(sourceHeight - 1, y * sourceHeight / atlasHeight);
            var sourceRow = sourceY * sourceWidth;
            var target = y * atlasWidth * 4 + channel;
            for (var x = 0; x < atlasWidth; x++, target += 4) {
                packed[target] = red[sourceRow + sourceXByAtlasX[x]];
            }
        }
    }

    static void packTerrainAlphaChannelSmoothly(
            byte[] packed,
            int atlasWidth,
            int atlasHeight,
            int channel,
            byte[] red,
            int sourceWidth,
            int sourceHeight) {
        var firstX = new int[atlasWidth];
        var secondX = new int[atlasWidth];
        var shareX = new float[atlasWidth];
        for (var x = 0; x < atlasWidth; x++) {
            var sourceX = Math.clamp((x + .5f) * sourceWidth / atlasWidth - .5f, 0f, sourceWidth - 1f);
            firstX[x] = (int) sourceX;
            secondX[x] = Math.min(sourceWidth - 1, firstX[x] + 1);
            shareX[x] = sourceX - firstX[x];
        }
        for (var y = 0; y < atlasHeight; y++) {
            var sourceY = Math.clamp((y + .5f) * sourceHeight / atlasHeight - .5f, 0f, sourceHeight - 1f);
            var firstRow = (int) sourceY * sourceWidth;
            var secondRow = Math.min(sourceHeight - 1, (int) sourceY + 1) * sourceWidth;
            var shareY = sourceY - (int) sourceY;
            var target = y * atlasWidth * 4 + channel;
            for (var x = 0; x < atlasWidth; x++, target += 4) {
                var top = (red[firstRow + firstX[x]] & 255) * (1 - shareX[x])
                        + (red[firstRow + secondX[x]] & 255) * shareX[x];
                var bottom = (red[secondRow + firstX[x]] & 255) * (1 - shareX[x])
                        + (red[secondRow + secondX[x]] & 255) * shareX[x];
                packed[target] = (byte) Math.round(top + (bottom - top) * shareY);
            }
        }
    }

    static @Nullable Vec3 rejectedInstanceHardwareSun(
            @Nullable UStaticMeshInstance rootInstance,
            float levelBrightness,
            MaterialClient client,
            float lightIntensity) {
        var source = rejectedInstanceSunSource(rootInstance);
        if (source == null) {
            return null;
        }
        if (client != MaterialClient.UNKNOWN && source.getLightType() == 1 && !source.isDeleteMe()) {
            return lightColor(source.getLightHue(), source.getLightSaturation(), source.getLightBrightness(), client)
                    .times(levelBrightness)
                    .times(lightIntensity)
                    .times(.875f)
                    .times(2f);
        }
        var light = lightFromActor(source, levelBrightness, client);
        var scale =
                client == MaterialClient.UNKNOWN ? LEGACY_REJECTED_INSTANCE_SUN_SCALE : 2f * 0.875f * lightIntensity;
        return light == null ? null : light.color().times(scale);
    }

    static @Nullable HardwareSunEnvironment rejectedInstanceHardwareSunEnvironment(
            @Nullable UStaticMeshInstance rootInstance,
            float levelBrightness,
            MaterialClient client,
            float lightIntensity) {
        if (client == MaterialClient.UNKNOWN) {
            return null;
        }
        var source = rejectedInstanceSunSource(rootInstance);
        if (source == null || source.isDeleteMe() || source.getLightType() != 1 || !source.isSunlightColor()) {
            return null;
        }
        return new HardwareSunEnvironment(levelBrightness, lightIntensity);
    }

    static @Nullable HardwareSunDirection rejectedInstanceHardwareSunDirection(
            @Nullable UStaticMeshInstance rootInstance, MaterialClient client) {
        if (client == MaterialClient.UNKNOWN) {
            return null;
        }
        var source = rejectedInstanceSunSource(rootInstance);
        if (source == null) {
            return null;
        }
        return source.isMovableSun()
                ? new HardwareSunDirection(true, null)
                : new HardwareSunDirection(false, MovableSun.incidentDirection(source.getRotation()));
    }

    static @Nullable HardwareSunSource rejectedInstanceHardwareSunSource(
            @Nullable UStaticMeshInstance rootInstance, MaterialClient client, float lightIntensity) {
        if (client == MaterialClient.UNKNOWN) {
            return null;
        }
        var source = rejectedInstanceSunSource(rootInstance);
        if (source == null || source.isDeleteMe() || source.getLightType() != 1) {
            return null;
        }
        return new HardwareSunSource(source.getFullName(), lightIntensity);
    }

    static StaticMeshAmbient staticMeshAmbient(
            Vec3 actorAmbient,
            UActor actor,
            boolean sunAffected,
            @Nullable UModel model,
            UStaticMesh mesh,
            @Nullable UStaticMeshInstance originalInstance,
            @Nullable UStaticMeshInstance acceptedInstance) {
        var beastWorld = model != null && model.getBeastLightingFlag() != null && model.getBeastLightingFlag() != 0;
        var source = Source.select(
                sunAffected,
                true,
                beastWorld,
                originalInstance != null,
                acceptedInstance != null && acceptedInstance.usesBeastLighting(true),
                mesh.isUseBillBoard());
        return new StaticMeshAmbient(
                source, actorAmbient, actor.getAmbientLightingActor().getAmbientGlow() == 255);
    }

    static boolean isStaticMeshSunAffected(
            boolean zoneSunAffected, boolean authoredSunAffected, boolean hasEnvironmentLighting) {
        return zoneSunAffected || authoredSunAffected && hasEnvironmentLighting;
    }

    static float clientColorChannel(float value) {
        return Math.clamp((float) Math.floor(value * 255f), 0f, 255f) / 255f;
    }

    static @Nullable USphere joinLitSpheres(List<NpcModel> parts, float bottom) {
        var box = new Box();
        for (var part : parts) {
            var sphere = part.litSphere();
            if (sphere == null) {
                continue;
            }
            var center = sphere.location();
            var z = center.getZ() - part.lowest() - bottom;
            var r = sphere.radius();
            box.add(new Vec3(center.getX() - r, center.getY() - r, z - r));
            box.add(new Vec3(center.getX() + r, center.getY() + r, z + r));
        }
        if (!box.isValid()) {
            return null;
        }
        var size = box.getMax().minus(box.getMin());
        return new USphere(
                box.getMin().plus(box.getMax()).times(.5f),
                Math.max(size.getX(), Math.max(size.getY(), size.getZ())) * .5f);
    }

    static long boxLeafZoneMask(UModel model, Box box, int[] nodeStack) {
        return BspLeafSelection.zoneMask(model, box, nodeStack, UArchive.NONE_NAME, 0);
    }

    static boolean hasStaticMeshInstanceLighting(@Nullable UStaticMeshInstance instance, int vertexCount) {
        return isStaticMeshInstanceAcceptedByClient(instance, vertexCount);
    }

    static boolean isStaticMeshInstanceAcceptedByClient(@Nullable UStaticMeshInstance instance, int vertexCount) {
        return isStaticMeshInstanceAcceptedByClient(instance, vertexCount, true);
    }

    static boolean isStaticMeshInstanceAcceptedByClient(
            @Nullable UStaticMeshInstance instance, int vertexCount, boolean lightmapsEnabled) {
        if (!hasValidInstanceColorStream(instance, vertexCount)) {
            return false;
        }
        if (instance.usesBeastLighting(lightmapsEnabled)) {
            return true;
        }
        var requiredBytes = (vertexCount + 7) >>> 3;
        for (var light : instance.getSceneLights()) {
            if (light.getVertexFlags().length != requiredBytes) {
                return false;
            }
        }
        return true;
    }

    static boolean usesCachedStaticMeshSceneLighting(@Nullable UStaticMeshInstance instance, int vertexCount) {
        return usesCachedStaticMeshSceneLighting(instance, vertexCount, MaterialClient.UNKNOWN);
    }

    static boolean hasStaticMeshVertexLightFlag(short[] flags, int vertexIndex) {
        var byteIndex = vertexIndex >>> 3;
        return byteIndex < flags.length && (((flags[byteIndex] & 0xff) & (1 << (vertexIndex & 7))) != 0);
    }

    static Vec3 staticMeshSceneLighting(
            List<StaticMeshSceneLight> lights, int vertexIndex, Vec3 worldPosition, Vec3 worldNormal, float scaleGlow) {
        var result = new float[3];
        computeStaticMeshSceneLighting(
                lights,
                vertexIndex,
                worldPosition.getX(),
                worldPosition.getY(),
                worldPosition.getZ(),
                worldNormal.getX(),
                worldNormal.getY(),
                worldNormal.getZ(),
                scaleGlow,
                result);
        return new Vec3(result[0], result[1], result[2]);
    }

    static boolean isStaticMeshEnvironmentStreamEnabled(
            @Nullable UStaticMeshInstance instance, @Nullable UModel model) {
        return (instance == null || !instance.usesBeastLighting(true))
                && (model == null || model.getBeastLightingFlag() == null || model.getBeastLightingFlag() == 0);
    }

    static @Nullable ActorVertexLighting applyStaticMeshInstanceLighting(
            EntityMesh mesh,
            UStaticMesh unrealMesh,
            UActor actor,
            @Nullable UStaticMeshInstance instance,
            @Nullable ActorVertexLighting baseLighting,
            boolean environmentStreamEnabled) {
        return applyStaticMeshInstanceLighting(
                mesh, unrealMesh, actor, instance, baseLighting, environmentStreamEnabled, MaterialClient.UNKNOWN);
    }

    static @Nullable ActorVertexLighting applyStaticMeshInstanceLighting(
            EntityMesh mesh,
            UStaticMesh unrealMesh,
            UActor actor,
            @Nullable UStaticMeshInstance instance,
            @Nullable ActorVertexLighting baseLighting,
            boolean environmentStreamEnabled,
            MaterialClient client) {
        if (instance == null) {
            return baseLighting;
        }
        var vertexCount = mesh.vertices().size();
        var colors = instance.getColorStream();
        var hasColors = hasValidInstanceColorStream(instance, vertexCount);
        var useCachedColors = usesCachedStaticMeshSceneLighting(instance, vertexCount, client);
        var environmentMasks = environmentStreamEnabled ? environmentVisibilityMasks(instance, vertexCount) : null;
        var hasEnvironment = environmentMasks != null;
        var hasSceneData = hasValidLightFlags(instance.getSceneLights(), vertexCount);
        if (!hasColors && !hasEnvironment && !hasSceneData) {
            return baseLighting;
        }

        var sceneLights = useCachedColors ? new StaticMeshSceneLight[0] : staticMeshSceneLights(instance, client);
        var sceneLightIndex = sceneLights.length == 0 ? null : indexStaticMeshSceneLights(sceneLights, vertexCount);
        float[] matrix = null;
        float[] normalMatrix = null;
        if (sceneLightIndex != null) {
            var placed = new Entity(mesh);
            placeActor(placed, actor);
            matrix = new float[16];
            placed.getModelMatrix().to(matrix);
            normalMatrix = OneOffMerger.inverseTranspose3x3(matrix);
        }
        var sceneLight = new float[3];
        var sourceVertices = mesh.vertexBuffer();
        var assetColors = unrealMesh.getColorStream() == null
                ? null
                : unrealMesh.getColorStream().getColors();
        var modulateRebuiltByAssetColor = !useCachedColors
                && unrealMesh.isUseVertexColor()
                && assetColors != null
                && assetColors.length == vertexCount;
        var replaceBakedLights =
                baseLighting != null && baseLighting.hasRgb() || useCachedColors || sceneLightIndex != null;
        var bakedLights = replaceBakedLights ? new byte[vertexCount * 3] : null;
        var sunVisibilityMasks = !environmentStreamEnabled
                ? null
                : hasEnvironment ? environmentMasks : baseLighting == null ? null : baseLighting.visibilityMasks();
        for (var vertexIndex = 0; vertexIndex < vertexCount; vertexIndex++) {
            var instanceR = useCachedColors ? colors.getRed(vertexIndex) : 0f;
            var instanceG = useCachedColors ? colors.getGreen(vertexIndex) : 0f;
            var instanceB = useCachedColors ? colors.getBlue(vertexIndex) : 0f;
            sceneLight[0] = 0f;
            sceneLight[1] = 0f;
            sceneLight[2] = 0f;
            if (matrix != null) {
                var px = sourceVertices.getPositionX(vertexIndex);
                var py = sourceVertices.getPositionY(vertexIndex);
                var pz = sourceVertices.getPositionZ(vertexIndex);
                var worldX = matrix[0] * px + matrix[4] * py + matrix[8] * pz + matrix[12];
                var worldY = matrix[1] * px + matrix[5] * py + matrix[9] * pz + matrix[13];
                var worldZ = matrix[2] * px + matrix[6] * py + matrix[10] * pz + matrix[14];
                var normalX = sourceVertices.getNormalX(vertexIndex);
                var normalY = sourceVertices.getNormalY(vertexIndex);
                var normalZ = sourceVertices.getNormalZ(vertexIndex);
                var nx = normalMatrix[0] * normalX + normalMatrix[3] * normalY + normalMatrix[6] * normalZ;
                var ny = normalMatrix[1] * normalX + normalMatrix[4] * normalY + normalMatrix[7] * normalZ;
                var nz = normalMatrix[2] * normalX + normalMatrix[5] * normalY + normalMatrix[8] * normalZ;
                var length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (length > 1e-8f) {
                    nx /= length;
                    ny /= length;
                    nz /= length;
                } else {
                    nx = 0f;
                    ny = 0f;
                    nz = 0f;
                }
                computeStaticMeshSceneLighting(
                        sceneLightIndex,
                        vertexIndex,
                        worldX,
                        worldY,
                        worldZ,
                        nx,
                        ny,
                        nz,
                        actor.getScaleGlow(),
                        sceneLight);
                if (modulateRebuiltByAssetColor) {
                    var assetColor = assetColors[vertexIndex];
                    sceneLight[0] = modulateClientColorByte(sceneLight[0], Byte.toUnsignedInt(assetColor.r()));
                    sceneLight[1] = modulateClientColorByte(sceneLight[1], Byte.toUnsignedInt(assetColor.g()));
                    sceneLight[2] = modulateClientColorByte(sceneLight[2], Byte.toUnsignedInt(assetColor.b()));
                }
            }
            if (bakedLights != null) {
                var targetOffset = vertexIndex * 3;
                var clientSceneLighting = useCachedColors || sceneLightIndex != null;
                var baseR = clientSceneLighting
                        ? 0f
                        : baseLighting != null && baseLighting.hasRgb()
                                ? baseLighting.red(vertexIndex)
                                : sourceVertices.getBakedLightX(vertexIndex);
                var baseG = clientSceneLighting
                        ? 0f
                        : baseLighting != null && baseLighting.hasRgb()
                                ? baseLighting.green(vertexIndex)
                                : sourceVertices.getBakedLightY(vertexIndex);
                var baseB = clientSceneLighting
                        ? 0f
                        : baseLighting != null && baseLighting.hasRgb()
                                ? baseLighting.blue(vertexIndex)
                                : sourceVertices.getBakedLightZ(vertexIndex);
                bakedLights[targetOffset] = lightingByte(baseR + instanceR + sceneLight[0]);
                bakedLights[targetOffset + 1] = lightingByte(baseG + instanceG + sceneLight[1]);
                bakedLights[targetOffset + 2] = lightingByte(baseB + instanceB + sceneLight[2]);
            }
        }
        if (bakedLights == null && sunVisibilityMasks == null) {
            return baseLighting;
        }
        var nativeSceneColor = hasEnvironment
                || useCachedColors
                || sceneLightIndex != null
                || baseLighting != null && baseLighting.nativeSceneColor();
        var resultRgb = bakedLights != null ? bakedLights : baseLighting == null ? null : baseLighting.rgb();
        return new ActorVertexLighting(
                resultRgb,
                sunVisibilityMasks,
                nativeSceneColor,
                actor.getAmbientLightingActor().getAmbientGlow() == 255,
                staticMeshLightingAlpha(
                        colors,
                        assetColors,
                        vertexCount,
                        useCachedColors
                                && (!hasEnvironment
                                        || !actor.isSunAffect()
                                        || assetColors == null
                                        || assetColors.length != vertexCount),
                        baseLighting == null ? null : baseLighting.colorAlpha()));
    }

    static byte @Nullable [] staticMeshLightingAlpha(
            @Nullable ColorStream cached,
            UColor @Nullable [] assetColors,
            int vertexCount,
            boolean useCachedColors,
            byte @Nullable [] fallback) {
        if (useCachedColors && cached != null && cached.size() == vertexCount) {
            var alpha = new byte[vertexCount];
            for (var i = 0; i < vertexCount; i++) {
                alpha[i] = cached.getAlphaByte(i);
            }
            return alpha;
        }
        if (assetColors != null && assetColors.length == vertexCount) {
            var alpha = new byte[vertexCount];
            for (var i = 0; i < vertexCount; i++) {
                alpha[i] = assetColors[i].a();
            }
            return alpha;
        }
        return fallback;
    }

    static float modulateClientColorByte(float rebuilt, int assetByte) {
        var rebuiltByte = Math.clamp(Math.round(rebuilt * 255f), 0, 255);
        return (rebuiltByte * Math.clamp(assetByte, 0, 255) / 255) / 255f;
    }

    static int environmentVisibilityMask(short[][] environmentFlags, int vertexIndex) {
        var result = 0;
        var byteIndex = vertexIndex >>> 3;
        var bit = 1 << (vertexIndex & 7);
        for (var slot = 0; slot < Math.min(environmentFlags.length, SHADOW_VARIANT_COUNT); slot++) {
            var flags = environmentFlags[slot];
            if (byteIndex < flags.length && ((flags[byteIndex] & 0xff) & bit) != 0) {
                result |= 1 << slot;
            }
        }
        return result;
    }

    static byte @Nullable [] environmentVisibilityMasks(@Nullable UStaticMeshInstance instance, int vertexCount) {
        if (instance == null || vertexCount <= 0) {
            return null;
        }
        var environment = instance.getEnvironmentLights();
        var flags = new short[Math.min(environment.length, SHADOW_VARIANT_COUNT)][];
        var requiredFlagBytes = (vertexCount + 7) >>> 3;
        var hasCompleteSlot = false;
        for (var slot = 0; slot < flags.length; slot++) {
            flags[slot] = environment[slot].getVertexFlags();
            hasCompleteSlot |= flags[slot].length >= requiredFlagBytes;
        }
        if (!hasCompleteSlot) {
            return null;
        }
        var result = new byte[vertexCount];
        for (var vertex = 0; vertex < vertexCount; vertex++) {
            result[vertex] = (byte) environmentVisibilityMask(flags, vertex);
        }
        return result;
    }

    static boolean isOneOffMergeEligible(int census, int vertexCount, int vertexBudget) {
        return census == 1 && vertexCount <= vertexBudget;
    }

    static boolean isMirrored(StaticMeshActor actor) {
        var scale = actor.getScale();
        return scale.getX() * scale.getY() * scale.getZ() < 0;
    }

    static boolean isTwoSided(@Nullable UMaterial material) {
        return switch (material) {
            case UFinalBlend blend -> blend.isTwoSided();
            case UShader shader -> shader.isTwoSided() && !shader.isTreatAsTwoSided();
            case UTexture texture -> texture.isTwoSided();
            case UModifier modifier -> isTwoSided(modifier.getMaterial().get());
            case null, default -> false;
        };
    }

    static String staticMeshActorCacheKey(UStaticMesh mesh, StaticMeshActor actor) {
        var name = meshAssetPath(mesh) + (isMirrored(actor) ? "#mirrored" : "");
        if (!doesActorBlockMovement(actor)) {
            for (var slot = 0; slot < mesh.getSurfaces().length; slot++) {
                if (mesh.getSurfaces()[slot].getTriangleMax() > 0 && mesh.getMaterials()[slot].isEnableCollision()) {
                    return name + "#passable";
                }
            }
        }
        return name;
    }

    static @Nullable UVertMesh resolveOptionalVertMesh(
            String mapName,
            String actorName,
            int childIndex,
            @Nullable String meshName,
            Supplier<? extends UObject> resolver) {
        try {
            var resolved = resolver.get();
            return resolved instanceof UVertMesh mesh ? mesh : null;
        } catch (RuntimeException failure) {
            var resolvedName = meshName == null ? "<unresolved>" : meshName;
            var failureKey = resolvedName + '\0' + failure.getClass().getName() + '\0' + failure.getMessage();
            if (REPORTED_MALFORMED_VERT_MESHES.add(failureKey)) {
                log.warn(
                        "Skipping malformed optional VertMesh emitter "
                                + "{}/{} child {} mesh {}: {} (identical failures will be DEBUG-only)",
                        mapName,
                        actorName,
                        childIndex,
                        resolvedName,
                        failure.toString());
            } else {
                log.debug(
                        "Repeated malformed optional VertMesh emitter " + "{}/{} child {} mesh {}: {}",
                        mapName,
                        actorName,
                        childIndex,
                        resolvedName,
                        failure.toString());
            }
            return null;
        }
    }

    static int playerSequence(UMeshAnimation animation, @Nullable String wait, @Nullable String stance) {
        var named = wait == null ? -1 : animation.findSequenceIndex(wait);
        if (named >= 0) {
            return named;
        }
        if (stance != null) {
            var prefix = stance.toLowerCase(Locale.ROOT) + "_";
            var sequences = animation.getSequences();
            for (var i = 0; i < sequences.length; i++) {
                if (sequences[i].name().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    return i;
                }
            }
        }
        return npcIdleSequence(animation);
    }

    static int playerSequence(UMeshAnimation animation, @Nullable String stance) {
        return playerSequence(animation, stance, stance);
    }

    static int npcIdleSequence(UMeshAnimation animation) {
        return npcIdleSequence(animation, null);
    }

    static int npcIdleSequence(UMeshAnimation animation, @Nullable String wait) {
        var named = wait == null ? -1 : animation.findSequenceIndex(wait);
        if (named >= 0) {
            return named;
        }
        var exact = animation.findSequenceIndex("wait");
        if (exact >= 0) {
            return exact;
        }
        var sequences = animation.getSequences();
        for (var prefix : List.of("wait", "idle", "atkwait")) {
            for (var i = 0; i < sequences.length; i++) {
                if (sequences[i].name().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    return i;
                }
            }
        }
        return 0;
    }

    public static boolean isPlayerFace(String meshReference) {
        return PlayerFigures.isFace(meshReference);
    }

    static long drawnPolygonFlags(long polygonFlags, @Nullable UMaterial material) {
        var framebuffer = AuthoredFramebuffer.resolve(material);
        return framebuffer != null && framebuffer.twoSided()
                ? polygonFlags | BspPolygonFlags.PF_TwoSided
                : polygonFlags;
    }

    static List<UObject> meshEmitterMaterialOverrides(UParticleEmitter emitter, List<UObject> skins) {
        var customMaterials = emitter.getCustomMaterials();
        if (customMaterials.isEmpty()) {
            return skins;
        }
        var slots = Math.max(customMaterials.size(), skins.size());
        var overrides = new ArrayList<UObject>(slots);
        for (var i = 0; i < slots; i++) {
            var custom = i < customMaterials.size() ? customMaterials.get(i) : null;
            overrides.add(custom != null ? custom : i < skins.size() ? skins.get(i) : null);
        }
        return overrides;
    }

    public static int beastLightmapSlotOf(String textureName) {
        return beastLightmapSlot(textureName);
    }

    static int beastLightmapSlot(String textureName) {
        var marker = textureName.lastIndexOf(BEAST_LIGHTMAP_MARKER);
        if (marker < 0) {
            return -1;
        }
        var rest = textureName.substring(marker + BEAST_LIGHTMAP_MARKER.length());
        var separator = rest.indexOf('_');
        if (separator <= 0) {
            return -1;
        }
        try {
            var slot = Integer.parseInt(rest.substring(0, separator));
            return slot == 0 || slot == 1 ? slot : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    static BlendMode effectiveFxBlend(BlendMode blendMode, EntityMaterial material) {
        return blendMode == BlendMode.TRANSLUCENT && isBlackBackgroundMaterial(material)
                ? BlendMode.ADDITIVE
                : blendMode;
    }

    static BlendMode flowingWaterBlend(BlendMode blend, @Nullable String meshFullName) {
        if (blend != BlendMode.OPAQUE || meshFullName == null) {
            return blend;
        }
        var lower = meshFullName.toLowerCase(Locale.ROOT);
        return lower.contains(".icewater_water") || lower.contains(".icewater_ice") ? BlendMode.UNLIT : blend;
    }

    static @Nullable WaterVolume waterVolume(WaterVolumeActor volume, @Nullable WorldTile ownerTile) {
        return waterVolume(volume, ownerTile, ZoneRender.EVERY_STATE);
    }

    static @Nullable WaterVolume waterVolume(
            WaterVolumeActor volume, @Nullable WorldTile ownerTile, ZoneRender zoneRender) {
        if (!volume.isCollideActors()) {
            return null;
        }
        var brush = volume.getBrush().get();
        if (brush == null || brush.getPoints().length == 0) {
            return null;
        }
        var authored = volume.isUseCellophane() ? volume.getCellophaneColor() : null;
        var cellophane = authored == null
                ? null
                : new Vec4(
                        (authored.r() & 0xff) / 255f,
                        (authored.g() & 0xff) / 255f,
                        (authored.b() & 0xff) / 255f,
                        (authored.a() & 0xff) / 255f);
        var fogColor = volume.getDistanceFogColor();
        if (fogColor == null) {
            throw new IllegalStateException(
                    "WaterVolume class defaults did not provide DistanceFogColor for " + volume.getFullName());
        }
        var fogSettings = new FogSettings(
                volume.isDistanceFog(),
                volume.isUseDistanceFogColor(),
                new DistanceFog(
                        new Vec3(
                                (fogColor.r() & 0xff) / 255f,
                                (fogColor.g() & 0xff) / 255f,
                                (fogColor.b() & 0xff) / 255f),
                        volume.getDistanceFogStart(),
                        volume.getDistanceFogEnd()),
                new Vec4(
                        (fogColor.r() & 0xff) / 255f,
                        (fogColor.g() & 0xff) / 255f,
                        (fogColor.b() & 0xff) / 255f,
                        (fogColor.a() & 0xff) / 255f));
        return new WaterVolume(
                brush,
                BrushTransforms.actorMatrix(
                        volume.getLocation(), volume.getPrePivot(), volume.getRotation(), volume.getScale()),
                cellophane,
                fogSettings,
                volume.isL2WaterVolume(),
                volume.getPriority(),
                ownerTile,
                zoneRender);
    }

    static float lightAttenuation(Light light, float dx, float dy, float dz, float radius, @Nullable Vec3 normal) {
        var distSq = dx * dx + dy * dy + dz * dz;
        return lightAttenuation(
                light,
                dx,
                dy,
                dz,
                radius,
                (float) Math.sqrt(distSq),
                distSq,
                normal == null ? 0f : normal.getX(),
                normal == null ? 0f : normal.getY(),
                normal == null ? 0f : normal.getZ(),
                normal != null);
    }

    static @Nullable Vec3[] buildPropZoneFloors(UModel model) {
        var zones = model.getZones();
        if (zones == null || zones.length == 0) {
            return null;
        }
        var floors = new Vec3[zones.length];
        var any = false;
        for (int i = 0; i < zones.length; i++) {
            ZoneInfoActor zone;
            try {
                zone = zones[i].getZoneActor().get();
            } catch (RuntimeException e) {
                zone = null;
            }
            if (zone == null
                    || zone.isSunAffect()
                    || zone.getAmbientBrightness() <= 0
                    || zone.getAmbientVector() == null) {
                continue;
            }
            var v = zone.getAmbientVector();
            floors[i] = new Vec3(clamp01(v.getX()), clamp01(v.getY()), clamp01(v.getZ()));
            any = true;
        }
        return any ? floors : null;
    }

    static WaterSheetSource waterSheetSource(
            UModel model, UBspNode node, WaterPlane reflectionPlane, @Nullable ZoneInfoActor levelInfo) {
        var zones = node.getZone();
        var backZone = zones != null && zones.length > 0 ? zones[0] : 0;
        var frontZone = zones != null && zones.length > 1 ? zones[1] : backZone;
        var nodePlane = node.getPlane();
        var canonicalFollowsNode = reflectionPlane.x() * nodePlane.getX()
                        + reflectionPlane.y() * nodePlane.getY()
                        + reflectionPlane.z() * nodePlane.getZ()
                >= 0f;
        return new WaterSheetSource(
                model.getFullName(),
                node.getSurfaceIndex(),
                canonicalFollowsNode ? backZone : frontZone,
                canonicalFollowsNode ? frontZone : backZone,
                nodePlane.getX(),
                nodePlane.getY(),
                nodePlane.getZ(),
                nodePlane.getW(),
                isWaterFrontZoneSunAffected(model, frontZone, levelInfo));
    }

    static int blendOfDrawStyle(int drawStyle, @Nullable Texture texture) {
        return switch (drawStyle) {
            case 1 -> Billboard.BLEND_ALPHA;
            case 2 -> Billboard.BLEND_MODULATE;
            case 5 -> Billboard.BLEND_DARKEN;
            case 3 -> Billboard.BLEND_ADDITIVE;
            case 6 -> Billboard.BLEND_SCREEN;
            case 0 -> hasAlphaChannel(texture) ? Billboard.BLEND_ALPHA : Billboard.BLEND_SCREEN;
            default -> Billboard.BLEND_SCREEN;
        };
    }

    static boolean hasVaryingAlpha(Texture texture) {
        if (texture.data().isEmpty()) {
            return false;
        }
        var base = texture.data().getFirst();
        return DxtMipCodec.averageAlpha(texture.format(), texture.width(), texture.height(), base) < ALPHA_OPAQUE_LEVEL;
    }

    static Vec3 lightColor(int hue, int saturation, float brightness, MaterialClient client) {
        return client == MaterialClient.UNKNOWN
                ? lightColor(hue, saturation, brightness)
                : UnrealHsv.toRgb(hue, saturation, 255).times(brightness / 255f);
    }

    static Vec3 lightColor(int hue, int saturation, float brightness) {
        hue &= 0xff;
        float hueR;
        float hueG;
        float hueB;
        if (hue < 0x56) {
            hueR = (0x55 - hue) * 0.01176471f;
            hueG = hue * 0.01176471f;
            hueB = 0f;
        } else if (hue < 0xab) {
            hueR = 0f;
            hueG = (0xaa - hue) * 0.01176471f;
            hueB = (hue - 0x55) * 0.01176471f;
        } else {
            hueR = (hue - 0xaa) * 0.01176471f;
            hueG = 0f;
            hueB = (0xff - hue) * 0.01190476f;
        }

        var whiten = (saturation & 0xff) / 255f;
        var value = 1.5f;
        value = value / ((float) Math.sqrt(value) + 0.01f) * 0.7f;
        value = Math.clamp(value, 0f, 1f) * brightness / 255f;
        return new Vec3(
                ((1f - hueR) * whiten + hueR) * value,
                ((1f - hueG) * whiten + hueG) * value,
                ((1f - hueB) * whiten + hueB) * value);
    }

    static void installLightmapVariants(Entity entity, FStaticLightmapTexture[] variants) {
        installLightmapVariants(entity, variants, Map.of());
    }

    static void installLightmapVariants(
            Entity entity, FStaticLightmapTexture[] variants, Map<FStaticLightmapTexture, Integer> sources) {
        var textures = new ArrayList<Texture>();
        var slots = new ArrayList<Integer>();
        var sparse = false;
        for (var variant : variants) {
            if (variant.isEmpty()) {
                slots.add(-1);
                sparse = true;
            } else {
                slots.add(textures.size());
                textures.add(lightmapTexture(entity, variant, sources));
            }
        }
        entity.setLightmapVariants(List.copyOf(textures));
        entity.setLightmapVariantSlots(sparse ? slots : List.of());
    }

    static float bspWaterVertexHeight(float height) {
        return height > -3870f && height < -3670f ? -3780f : height;
    }

    static boolean needsZoneBoundaryBackface(UBspNode node, long polygonFlags, BlendMode blendMode) {
        if ((polygonFlags & (BspPolygonFlags.PF_TwoSided | BspPolygonFlags.PF_Unlit)) != 0) {
            return false;
        }
        if (!isOpaqueCsgDepthBlend(blendMode)) {
            return false;
        }
        var zone = node.getZone();
        return zone != null && zone.length >= 2 && (zone[0] == 0) != (zone[1] == 0);
    }

    static boolean shouldCullCsgBackFaces(
            long polygonFlags, BlendMode blendMode, boolean skyMode, boolean fakeBackdrop) {
        if (skyMode || fakeBackdrop || !isOpaqueCsgDepthBlend(blendMode)) {
            return false;
        }
        return (polygonFlags & (BspPolygonFlags.PF_TwoSided | BspPolygonFlags.PF_Portal)) == 0;
    }

    static boolean isMeshCoincidenceNudgeAllowed(boolean unlit, boolean depthOrdered) {
        return unlit && !depthOrdered;
    }

    static boolean isCsgConcatEligible(EntityMaterial material, BlendMode blend, UvAnimation uvAnimation) {
        if (uvAnimation.mode() != 0 || !(material instanceof TextureEntityMaterial)) {
            return false;
        }
        return blend == BlendMode.OPAQUE
                || blend == BlendMode.MASKED
                || blend == BlendMode.MASKED_BLEND
                || blend == BlendMode.UNLIT;
    }

    static long bspRenderPolygonFlags(UBspNode node, UBspSurface surface, @Nullable FBspSection[] sections) {
        var section = node.getISection();
        return sections != null && section >= 0 && section < sections.length
                ? sections[section].polygonFlags()
                : surface.getPolygonFlags();
    }

    static boolean doesNativeSuppressBspColor(long surfaceFlags, boolean skyMode, boolean water, boolean fakeBackdrop) {
        if (skyMode || water || fakeBackdrop) {
            return false;
        }
        var exceptional = BspPolygonFlags.PF_AutoVPan | BspPolygonFlags.PF_L2_Water | BspPolygonFlags.PF_Mirrored;
        return (surfaceFlags & BspPolygonFlags.PF_Unused2) != 0 && (surfaceFlags & exceptional) == 0;
    }

    static boolean doesNativeSkipInvisible(long surfaceFlags, boolean skyMode, boolean fakeBackdrop) {
        if (skyMode || fakeBackdrop) {
            return false;
        }
        return (surfaceFlags & BspPolygonFlags.PF_Invisible) != 0 && (surfaceFlags & BspPolygonFlags.PF_L2_Water) == 0;
    }

    static boolean isSheetDrawn(long surfaceFlags) {
        return (surfaceFlags & (NOT_DRAWN_POLYGON_FLAGS | BspPolygonFlags.PF_AutoVPan)) == 0;
    }

    static long csgConcatGroupKey(int atlasWidth, int atlasHeight) {
        return ((long) atlasWidth << 32) | (atlasHeight & 0xFFFFFFFFL);
    }

    static boolean isEditorDummyCsgMaterial(@Nullable UMaterial material) {
        return material instanceof UTexture texture
                && texture.getName().toLowerCase(Locale.ROOT).endsWith("_dummy");
    }

    static boolean usesVertexColor(@Nullable UMaterial material) {
        return usesVertexColor(material, new IdentityHashMap<>());
    }

    static boolean modulatesStaticLighting2x(@Nullable UMaterial material) {
        return switch (material) {
            case UShader shader -> shader.isModulateStaticLighting2x();
            case UModifier modifier ->
                modulatesStaticLighting2x(modifier.getMaterial().get());
            case null, default -> false;
        };
    }

    static @Nullable EntityMaterial envMapMaterialOf(@Nullable EntityMaterial mapMaterial, int envMapType) {
        if (mapMaterial == null) {
            return null;
        }
        var mapTexture = mapMaterial.getTexture();
        return mapTexture == null ? mapMaterial : new EnvMapEntityMaterial(mapTexture, envMapType);
    }

    static boolean hasOpacity(UShader shader) {
        try {
            return shader.getOpacity().get() != null;
        } catch (RuntimeException unresolved) {
            return false;
        }
    }

    static boolean isSelfAlphaDecal(UShader shader) {
        var opacity = getOpacity(shader);
        return opacity != null
                && isSameTexture(baseTextureOf(shader.getDiffuse().get()), opacity);
    }

    static int materialUvChannel(@Nullable UMaterial node) {
        try {
            return switch (node) {
                case UTexCoordSource source -> source.getSourceChannel() == 1 ? 1 : 0;
                case UModifier modifier ->
                    materialUvChannel(modifier.getMaterial().get());
                case null, default -> 0;
            };
        } catch (RuntimeException e) {
            return 0;
        }
    }

    static Texture solidColorTexture(Vec4 color) {
        var data = new byte[] {toByte(color.getZ()), toByte(color.getY()), toByte(color.getX()), (byte) 0xFF};
        return new Texture(TextureFormat.TEXTURE_RGBA, 1, 1, List.of(data), false);
    }

    static @Nullable FadeColorSpec fadeSpecOf(@Nullable UMaterial node) {
        return node instanceof UFadeColor fade && fade.isAnimated() ? fadeSpec(fade) : null;
    }

    static @Nullable FadeColorSpec colorSpecOf(@Nullable UMaterial node) {
        return switch (node) {
            case UFadeColor fade -> fadeSpec(fade);
            case UConstantColor constant -> {
                var color = toVec4(constant.getColor());
                yield new FadeColorSpec(color, color, 0f, 0f, 2);
            }
            case null, default -> null;
        };
    }

    static boolean isWhiteTint(Vec4 tint) {
        return tint.getX() >= 1f && tint.getY() >= 1f && tint.getZ() >= 1f;
    }

    static @Nullable Vec4 meshVertexColor(UColor @Nullable [] colors, int index, int vertexCount) {
        if (colors == null || colors.length != vertexCount || index < 0 || index >= colors.length) {
            return null;
        }
        return toVec4(colors[index]);
    }

    static @Nullable Vec4 meshVertexColor(
            UColor @Nullable [] colors, UColor @Nullable [] alphas, int index, int vertexCount) {
        var color = meshVertexColor(colors, index, vertexCount);
        return color == null ? meshVertexColor(alphas, index, vertexCount) : color;
    }

    public static BlendMode blendModeOf(@Nullable UMaterial node) {
        return switch (node) {
            case UShader shader ->
                switch (shader.getOutputBlending()) {
                    case OB_Masked -> BlendMode.MASKED;
                    case OB_Translucent -> BlendMode.ADDITIVE;
                    case OB_Darken -> BlendMode.DARKEN;
                    case OB_Modulate -> BlendMode.MODULATE;
                    case OB_Brighten -> BlendMode.SCREEN;
                    case OB_Invisible -> BlendMode.INVISIBLE;
                    default -> {
                        if (hasNoDiffuse(shader)) {
                            yield BlendMode.EMISSIVE;
                        }
                        if (slotMaterialNode(shader.getOpacity()) == null) {
                            yield BlendMode.OPAQUE;
                        }
                        yield shader.isZWrite() && shader.isAlphaTest()
                                ? BlendMode.MASKED_BLEND
                                : BlendMode.TRANSLUCENT;
                    }
                };
            case UFinalBlend fb ->
                switch (fb.getFbBlending()) {
                    case FB_Overwrite, FB_OverwriteAlpha -> fb.isAlphaTest() ? BlendMode.MASKED : BlendMode.OPAQUE;
                    case FB_Modulate -> BlendMode.MODULATE;
                    case FB_AlphaBlend -> fb.isAlphaTest() ? BlendMode.MASKED_BLEND : BlendMode.TRANSLUCENT;
                    case FB_AlphaModulate_MightNotFogCorrectly -> BlendMode.TRANSLUCENT;
                    case FB_Translucent -> BlendMode.SCREEN;
                    case FB_Darken, FB_DarkenInv -> BlendMode.DARKEN;
                    case FB_Brighten -> BlendMode.BRIGHTEN;
                    case FB_Invisible -> BlendMode.INVISIBLE;
                    case FB_Add -> BlendMode.ADDITIVE;
                    case FB_InWaterBlend -> BlendMode.TRANSLUCENT;
                    case FB_Capture -> BlendMode.INVISIBLE;
                };
            case UColorModifier colorMod -> {
                try {
                    var baseBlend = blendModeOf(colorMod.getMaterial().get());
                    yield colorMod.isAlphaBlend()
                                    && (colorMod.getColor().a() & 0xFF) < 255
                                    && (baseBlend == BlendMode.OPAQUE || baseBlend == BlendMode.MASKED)
                            ? BlendMode.TRANSLUCENT
                            : baseBlend;
                } catch (RuntimeException e) {
                    yield BlendMode.OPAQUE;
                }
            }
            case UModifier modifier -> {
                try {
                    yield blendModeOf(modifier.getMaterial().get());
                } catch (RuntimeException e) {
                    yield BlendMode.OPAQUE;
                }
            }
            case UTexture tex ->
                tex.isMasked() ? BlendMode.MASKED : tex.isAlphaTexture() ? BlendMode.TRANSLUCENT : BlendMode.OPAQUE;
            case null, default -> BlendMode.OPAQUE;
        };
    }

    static int alphaRefOf(@Nullable UMaterial node, BlendMode blend) {
        try {
            return switch (node) {
                case UShader shader ->
                    switch (shader.getOutputBlending()) {
                        case OB_Masked -> 127;
                        case OB_Normal, OB_MAX ->
                            slotMaterialNode(shader.getOpacity()) == null
                                    ? -1
                                    : !shader.isZWrite() ? 0 : shader.isAlphaTest() ? shader.getAlphaRef() : -1;
                        default -> -1;
                    };
                case UFinalBlend fb -> fb.isAlphaTest() ? fb.getAlphaRef() : -1;
                case UModifier modifier -> alphaRefOf(modifier.getMaterial().get(), blend);
                case UTexture texture ->
                    texture.isMasked() ? 127 : texture.isAlphaTexture() && blend == BlendMode.TRANSLUCENT ? 0 : -1;
                case null, default -> -1;
            };
        } catch (RuntimeException e) {
            return -1;
        }
    }

    static boolean writesDepth(@Nullable UMaterial node, BlendMode blend) {
        try {
            return switch (node) {
                case UShader shader ->
                    switch (shader.getOutputBlending()) {
                        case OB_Masked -> true;
                        case OB_Modulate, OB_Translucent, OB_Invisible, OB_Brighten, OB_Darken -> false;
                        case OB_Normal, OB_MAX -> slotMaterialNode(shader.getOpacity()) == null || shader.isZWrite();
                    };
                case UFinalBlend finalBlend -> finalBlend.isZWrite();
                case UModifier modifier -> writesDepth(modifier.getMaterial().get(), blend);
                case UTexture texture -> texture.isAlphaTexture() || Entity.Surface.writesDepthByDefault(blend);
                case null, default -> Entity.Surface.writesDepthByDefault(blend);
            };
        } catch (RuntimeException e) {
            return Entity.Surface.writesDepthByDefault(blend);
        }
    }

    static UvAnimation uvAnimationOf(@Nullable UMaterial node) {
        try {
            return switch (node) {
                case UTexPannerTriggered panner ->
                    uvAnimationOf(panner.getMaterial().get());
                case UTexPanner panner -> {
                    var angle = panner.getPanDirection().yaw() * (float) Math.PI / 32768f;
                    var rate = panner.getPanRate();
                    yield UvAnimation.pan((float) Math.cos(angle) * rate, (float) Math.sin(angle) * rate);
                }
                case UTexRotator rotator -> rotatorAnimation(rotator);
                case UTexEnvMap envMap ->
                    aboutMiddle(uvAnimationOf(envMap.getMaterial().get()));
                case UTexOscillator osc ->
                    UvAnimation.oscillate(
                            osc.getUOscillationRate(), osc.getUOscillationAmplitude(),
                            osc.getVOscillationRate(), osc.getVOscillationAmplitude());
                case UShader shader -> {
                    var diffuseNode = shader.getDiffuse().get();
                    var a = uvAnimationOf(diffuseNode);
                    if (a.mode() != 0) {
                        yield a;
                    }
                    var diffuseTex = baseTextureOf(diffuseNode);
                    if (diffuseNode == null) {
                        var g = uvAnimationOfSlot(shader.getSelfIllumination());
                        yield g.mode() != 0 ? g : uvAnimationOfSlot(shader.getSpecular());
                    }
                    if (diffuseTex == null) {
                        yield UvAnimation.NONE;
                    }
                    var s = matchedSlotAnimation(shader.getSpecular(), diffuseTex);
                    yield s.mode() != 0 ? s : matchedSlotAnimation(shader.getSelfIllumination(), diffuseTex);
                }
                case UTexScaler scaler -> {
                    var width = AuthoredMaterialCompiler.materialSize(scaler, true);
                    var height = AuthoredMaterialCompiler.materialSize(scaler, false);
                    var uOffset = width > 0 ? scaler.getUOffset() / width : 0f;
                    var vOffset = height > 0 ? scaler.getVOffset() / height : 0f;
                    var identity =
                            scaler.getUScale() == 1f && scaler.getVScale() == 1f && uOffset == 0f && vOffset == 0f;
                    if (!identity && scaler.getUScale() != 0f && scaler.getVScale() != 0f) {
                        yield UvAnimation.scale(scaler.getUScale(), scaler.getVScale(), uOffset, vOffset);
                    }
                    yield uvAnimationOf(scaler.getMaterial().get());
                }
                case UModifier modifier -> uvAnimationOf(modifier.getMaterial().get());
                case UCombiner combiner -> {
                    var a = uvAnimationOf(combiner.getMaterial1().get());
                    if (a.mode() == 0) {
                        a = uvAnimationOf(combiner.getMaterial2().get());
                    }
                    if (a.mode() == 0) {
                        a = uvAnimationOf(combiner.getMask().get());
                    }
                    yield a;
                }
                case null, default -> UvAnimation.NONE;
            };
        } catch (RuntimeException e) {
            return UvAnimation.NONE;
        }
    }

    static UvAnimation layerAnimationOf(@Nullable UMaterial slot) {
        var animation = uvAnimationOf(slot);
        return animation.mode() == 5 && turnsEnvironmentMap(slot, 0) ? UvAnimation.NONE : animation;
    }

    static @Nullable EnvironmentLayer environmentLayerOf(@Nullable UMaterial slot) {
        try {
            var chain = environmentChainOf(slot);
            if (chain == null) {
                return null;
            }
            var source = switch (chain.environment().getEnvMapTypeId()) {
                case 1 -> TextureCoordinates.Source.CAMERA_REFLECTION;
                case 2 -> TextureCoordinates.Source.SPHERE_MAP;
                default -> null;
            };
            var animation = environmentAnimationOf(chain.modifiers());
            return source == null || animation == null ? null : new EnvironmentLayer(source, animation);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static @Nullable EnvironmentLayer fixedFunctionLayerOf(@Nullable UMaterial slot) {
        try {
            var chain = environmentChainOf(slot);
            if (chain == null) {
                return null;
            }
            var modifiers = chain.modifiers();
            var source = switch (chain.environment().getEnvMapTypeId()) {
                case 0 -> modifiers.isEmpty() ? TextureCoordinates.Source.WORLD_REFLECTION : null;
                case 1 -> TextureCoordinates.Source.CAMERA_REFLECTION;
                case 2, 3 -> TextureCoordinates.Source.SPHERE_MAP;
                default -> null;
            };
            if (source == TextureCoordinates.Source.SPHERE_MAP && movesByThirdRow(modifiers)) {
                return null;
            }
            var animation = environmentAnimationOf(modifiers);
            return source == null || animation == null ? null : EnvironmentLayer.withoutShaders(source, animation);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static boolean takesSpecularPoint(UShader shader, boolean replacedWithoutLight) {
        try {
            return replacedWithoutLight
                    && shader.getSpecularMap().get() == null
                    && !holdsCubemap(shader.getDiffuse().get(), 0)
                    && !holdsCubemap(shader.getOpacity().get(), 0)
                    && !holdsCubemap(shader.getSpecular().get(), 0)
                    && !holdsCubemap(shader.getSelfIllumination().get(), 0)
                    && !holdsCubemap(shader.getDetail().get(), 0);
        } catch (RuntimeException unresolved) {
            return false;
        }
    }

    static float animatedFrameRate(float maxFrameRate) {
        if (!(maxFrameRate > 0f)) {
            return 0f;
        }
        return Math.clamp(maxFrameRate, 0.1f, 100f);
    }

    static int zoneStart(UActor actor) {
        var zone = actor.getRegionZone();
        return zone == null || !zone.isUseZoneState() ? ZoneRender.NO_ZONE_STATES : zone.getCurZoneState();
    }

    static String zoneMap(UActor actor) {
        var zone = actor.getRegionZone();
        return ZoneStateChoice.key(ZoneStateChoice.mapOf((zone == null ? actor : zone).getFullName()));
    }

    static ZoneRender waterZoneRender(PhysicsVolumeActor volume) {
        var mask = volume.getZoneRenderMask();
        return mask == 0 || !volume.isWaterVolume() || volume.getRegionZone() == null
                ? ZoneRender.EVERY_STATE
                : new ZoneRender(mask, zoneStart(volume), true, zoneMap(volume));
    }

    static Vec3 propInstanceLight(
            UActor actor,
            UTerrainInfoActor terrain,
            List<UTerrainSector> sectors,
            @Nullable LightGrid lightGrid,
            @Nullable Vec3 interiorAmbient,
            float[] lightPosRadius,
            float[] lightColor,
            int[] lightIdx,
            @Nullable UModel propZoneModel,
            @Nullable Vec3[] propZoneFloors,
            @Nullable Occluders sunOccluders,
            @Nullable MeshOccluders meshOccluders,
            @Nullable TerrainField sunTerrain,
            int selfActorIndex,
            boolean foliage) {
        var pos = actor.getPosition();
        var zoneFloor = interiorZoneFloor(propZoneModel, propZoneFloors, pos);
        if (zoneFloor != null) {
            return bakeVertexLight(lightGrid, pos, null, lightPosRadius, lightColor, lightIdx, zoneFloor);
        }
        var outdoor = terrainPropFloor(terrain, sectors, pos);
        if (foliage) {
            return bakeVertexLight(lightGrid, pos, null, lightPosRadius, lightColor, lightIdx, outdoor);
        }
        var skyVis = SunVisibility.skyVisibility(sunOccluders, meshOccluders, sunTerrain, selfActorIndex, pos);
        var interiorLevel = occludedPropFloor(outdoor, interiorAmbient);
        var outdoorLevel =
                luminance(outdoor) > 1e-4f ? outdoor : new Vec3(PROP_FLOOR_MIN, PROP_FLOOR_MIN, PROP_FLOOR_MIN);
        var level = lerpVec(interiorLevel, outdoorLevel, smoothstep(SKY_VIS_LO, SKY_VIS_HI, skyVis));
        return bakeVertexLight(lightGrid, pos, null, lightPosRadius, lightColor, lightIdx, level);
    }

    static float smoothstep(float lo, float hi, float x) {
        if (hi <= lo) {
            return x < lo ? 0f : 1f;
        }
        var t = (x - lo) / (hi - lo);
        if (t < 0f) {
            t = 0f;
        } else if (t > 1f) {
            t = 1f;
        }
        return t * t * (3f - 2f * t);
    }

    static Vec3 lerpVec(Vec3 a, Vec3 b, float t) {
        return new Vec3(
                a.getX() + (b.getX() - a.getX()) * t,
                a.getY() + (b.getY() - a.getY()) * t,
                a.getZ() + (b.getZ() - a.getZ()) * t);
    }

    static Vec3 occludedPropFloor(Vec3 outdoor, @Nullable Vec3 interiorAmbient) {
        if (luminance(outdoor) > 1e-4f) {
            return clampFloor(scale(outdoor, SUN_OCCLUSION_SHADOW), PROP_SHADOW_FLOOR);
        }
        if (interiorAmbient != null && luminance(interiorAmbient) > 1e-4f) {
            return interiorAmbient;
        }
        return new Vec3(PROP_SHADOW_FLOOR, PROP_SHADOW_FLOOR, PROP_SHADOW_FLOOR);
    }

    static Vec3 scale(Vec3 v, float s) {
        return new Vec3(v.getX() * s, v.getY() * s, v.getZ() * s);
    }

    static Vec3 clampFloor(Vec3 v, float min) {
        return new Vec3(Math.max(v.getX(), min), Math.max(v.getY(), min), Math.max(v.getZ(), min));
    }

    static @Nullable Vec3 interiorZoneFloor(@Nullable UModel propZoneModel, @Nullable Vec3[] propZoneFloors, Vec3 pos) {
        if (propZoneModel == null || propZoneFloors == null) {
            return null;
        }
        var zone = findPositionZone(propZoneModel, pos);
        return zone >= 0 && zone < propZoneFloors.length ? propZoneFloors[zone] : null;
    }

    static int findPositionZone(UModel model, Vec3 pos) {
        var nodes = model.getNodes();
        if (nodes == null || nodes.length == 0) {
            return -1;
        }
        var idx = 0;
        var guard = 0;
        while (idx >= 0 && idx < nodes.length && guard++ < nodes.length) {
            var node = nodes[idx];
            var plane = node.getPlane();
            var side = plane.getX() * pos.getX() + plane.getY() * pos.getY() + plane.getZ() * pos.getZ() - plane.getW();
            var zone = node.getZone();
            if (side >= 0f) {
                if (node.getFrontIndex() < 0) {
                    return zone[1];
                }
                idx = node.getFrontIndex();
            } else {
                if (node.getBackIndex() < 0) {
                    return zone[0];
                }
                idx = node.getBackIndex();
            }
        }
        return -1;
    }

    static @Nullable UModel loadPrimaryModel(UPackage uPackage) {
        var levels = new ArrayList<ULevel>();
        uPackage.loadObjects("Level", levels);
        for (var level : levels) {
            if (level.getModel().isNull()) {
                continue;
            }
            var model = level.getModel().get();
            if (model != null && model.getNodes() != null && model.getNodes().length > 0) {
                return model;
            }
        }
        return null;
    }

    @Nullable
    static boolean isWaterSheetShader(@Nullable UMaterial node) {
        if (!(node instanceof UShader shader)) {
            return false;
        }
        try {
            return shader.getDiffuse().get() instanceof UCombiner top
                    && top.getCombineOperation() == EColorOperation.CO_Add_With_Mask_Modulation
                    && top.getMask().get() == null
                    && top.getMaterial2().get() != null
                    && getOpacity(shader) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    static UvAnimation waterLayerOscillation(@Nullable UMaterial node) {
        var osc = firstTexOscillator(node);
        return osc == null
                ? UvAnimation.NONE
                : gatedOscillation(
                        osc.getUOscillationType(),
                        osc.getUOscillationRate(),
                        osc.getUOscillationAmplitude(),
                        osc.getVOscillationType(),
                        osc.getVOscillationRate(),
                        osc.getVOscillationAmplitude());
    }

    static UvAnimation gatedOscillation(int uType, float uRate, float uAmp, int vType, float vRate, float vAmp) {
        var pan = UTexOscillator.OT_PAN;
        return UvAnimation.oscillate(
                uType == pan ? uRate : 0f, uType == pan ? uAmp : 0f,
                vType == pan ? vRate : 0f, vType == pan ? vAmp : 0f);
    }

    static @Nullable UTexture firstTextureOf(@Nullable UMaterial node) {
        try {
            return switch (node) {
                case UTexture texture -> texture;
                case UCombiner combiner ->
                    firstTextureOf(combiner.getMaterial1().get());
                case UShader shader -> firstTextureOf(shader.getDiffuse().get());
                case UModifier modifier -> firstTextureOf(modifier.getMaterial().get());
                case null, default -> null;
            };
        } catch (RuntimeException e) {
            return null;
        }
    }

    static List<Vec2> getTerrainTextureCoords(int x, int y, UTerrainInfoActor terrain) {
        return getTerrainTextureCoords(x, y, 0, terrain);
    }

    static List<Vec2> getTerrainTextureCoords(int x, int y, int z, UTerrainInfoActor terrain) {
        var packed = getPackedTerrainTextureCoords(x, y, z, terrainTextureProjectors(terrain));
        var result = new ArrayList<Vec2>(packed.length / 2);
        for (var offset = 0; offset < packed.length; offset += 2) {
            result.add(new Vec2(packed[offset], packed[offset + 1]));
        }
        return result;
    }

    static Vec2 getTerrainAlphaCoords(int x, int y, int width, int height) {
        return new Vec2(getTerrainAlphaCoord(x, width), getTerrainAlphaCoord(y, height));
    }

    static float getTerrainAlphaCoord(int coordinate, int size) {
        if (size <= 0 || coordinate < 0 || coordinate > size) {
            throw new IllegalArgumentException("Invalid terrain alpha coordinate " + coordinate + "/" + size);
        }
        var lastSectorStart = Math.max(0, size - TERRAIN_SECTOR_SIZE);
        var uv = (coordinate + 0.5f) / size;
        if (coordinate >= lastSectorStart) {
            uv -= (coordinate - lastSectorStart) / (size * (float) TERRAIN_SECTOR_SIZE);
        }
        return uv;
    }

    public int getMeshCacheSize() {
        return meshCache.size();
    }

    public int getBbCacheSize() {
        return bbMeshCache.size();
    }

    public int getPackageCacheSize() {
        return packageLoader.getPackageCount();
    }

    public synchronized StaticMeshStreamTelemetry getStaticMeshStreamTelemetry() {
        return new StaticMeshStreamTelemetry(
                telemetryStaticMeshActors,
                telemetryStaticMeshActorVertices,
                telemetryStaticMeshLightStreamActors,
                telemetryStaticMeshLightStreamVertices,
                telemetryStaticMeshSpatialActors,
                telemetryStaticMeshExactActors,
                telemetryStaticMeshLodActors,
                telemetryStaticMeshLod2Actors,
                telemetryStaticMeshLod0Vertices,
                telemetryStaticMeshLod1Vertices,
                telemetryStaticMeshLod2Vertices);
    }

    public record StaticMeshStreamTelemetry(
            long actors,
            long actorVertices,
            long actorSpecificLightStreams,
            long actorSpecificLightVertices,
            long spatialLightActors,
            long exactInstanceLightActors,
            long lodActors,
            long lod2Actors,
            long lod0Vertices,
            long lod1Vertices,
            long lod2Vertices) {}

    public synchronized LoadTelemetry getLoadTelemetry() {
        return new LoadTelemetry(
                telemetryAttempts,
                telemetryLoaded,
                telemetryTotalNanos,
                telemetryPackageNanos,
                telemetryDxtNanos,
                telemetryTerrainNanos,
                telemetryLightingNanos,
                telemetryStaticMeshNanos,
                telemetryStaticMeshOccluderNanos,
                telemetryStaticMeshIndexNanos,
                telemetryStaticMeshMergeNanos,
                telemetryBspNanos,
                telemetryEffectsNanos,
                telemetrySlowestMap,
                telemetrySlowestMapNanos);
    }

    public record LoadTelemetry(
            int attempts,
            int loaded,
            long totalNanos,
            long packageNanos,
            long dxtNanos,
            long terrainNanos,
            long lightingNanos,
            long staticMeshNanos,
            long staticMeshOccluderNanos,
            long staticMeshIndexNanos,
            long staticMeshMergeNanos,
            long bspNanos,
            long effectsNanos,
            String slowestMap,
            long slowestMapNanos) {
        public long accountedNanos() {
            return packageNanos + dxtNanos + terrainNanos + lightingNanos + staticMeshNanos + bspNanos + effectsNanos;
        }

        public long otherNanos() {
            return Math.max(0L, totalNanos - accountedNanos());
        }

        public long staticMeshOtherNanos() {
            return Math.max(
                    0L, staticMeshNanos - staticMeshOccluderNanos - staticMeshIndexNanos - staticMeshMergeNanos);
        }
    }

    public DxtChainCache getDxtChains() {
        return dxtChains;
    }

    public void trimCaches() {
        authoredMaterials.clear();
        meshCache.clear();
        bbMeshCache.clear();
        vertMeshFrameCache.clear();
        occluderMeshCache.clear();
        packageLoader.clearPackages();
        dxtChains.trimThreadState();
    }

    public Optional<Texture> loadHighWaterNormalTexture(Path root) {
        return loadNativeWaterBitmap(root, "cubemaps", "water_normal").map(this::loadTexture);
    }

    public Optional<TextureSequence> loadHighWaterDiffuseTexture(Path root) {
        return loadNativeWaterBitmap(root, "FX_E_T", "ocean011").map(this::loadTextureSequence);
    }

    TextureSequence loadTextureSequence(UTexture root) {
        var loadedAtSeconds = System.nanoTime() * 1e-9;
        var sources = new ArrayList<UTexture>();
        var indices = new IdentityHashMap<UTexture, Integer>();
        var frame = root;
        while (frame != null && !indices.containsKey(frame)) {
            if (sources.size() >= MAX_ANIM_FRAMES) {
                throw new IllegalArgumentException("texture sequence exceeds frame limit: " + root.getFullName());
            }
            indices.put(frame, sources.size());
            sources.add(frame);
            frame = frame.getAnimationNextFrame();
        }
        var frames = new ArrayList<Texture>(sources.size());
        var next = new ArrayList<Integer>(sources.size());
        for (var source : sources) {
            var texture = loadTexture(source);
            if (texture == null) {
                throw new IllegalArgumentException("texture sequence has no bitmap: " + source.getFullName());
            }
            frames.add(texture);
            var following = source.getAnimationNextFrame();
            next.add(following == null ? 0 : indices.get(following));
        }
        return new TextureSequence(
                frames,
                next,
                root.getMaxFrameRate(),
                root.getTotalFrameNum(),
                root.getPrimeCount(),
                root.isOneTimeAnimLoop(),
                sources.stream().allMatch(source -> source.getSourceIdentity() != null)
                        ? sources.stream()
                                .map(source ->
                                        clientRoot.toAbsolutePath().normalize() + "|" + source.getSourceIdentity())
                                .toList()
                        : List.of(),
                loadedAtSeconds);
    }

    public void prepareOneOffMerge(List<String> mapNames, int vertexBudget, boolean commutativeBlends) {
        this.oneOffMergeVertexBudget = vertexBudget;
        this.oneOffMergeCommutative = commutativeBlends;
        var start = System.nanoTime();
        var counts = ObjectIntMaps.mutable.<String>empty();
        var actors = new ArrayList<StaticMeshActor>();
        for (var tile : mapNames) {
            var name = getMapPackage(tile);
            var optionalPackage = packageLoader.loadPackage(name);
            if (optionalPackage.isEmpty()) {
                continue;
            }
            var uPackage = optionalPackage.get();
            actors.clear();
            uPackage.loadObjects("StaticMeshActor", actors);
            uPackage.loadObjects("MovableStaticMeshActor", actors);
            uPackage.loadObjects("L2MovableStaticMeshActor", actors);
            for (var actor : actors) {
                if (actor.isDeleteMe() || actor.isHidden()) {
                    continue;
                }
                var meshName = actor.getStaticMesh().peekFullName();
                if (meshName != null) {
                    counts.addToValue(meshName.toLowerCase(Locale.ROOT), 1);
                }
            }
            packageLoader.removePackage(name);
        }
        this.oneOffUsage = counts;
        log.debug(
                "Merge pre-scan: {} maps, {} distinct meshes in {} ms "
                        + "(world-unique only, vertexBudget={}, commutative={})",
                mapNames.size(),
                counts.size(),
                (System.nanoTime() - start) / 1_000_000,
                vertexBudget,
                commutativeBlends);
    }

    public void reuseOneOffMergePlan(UnrealLoader preparedLoader) {
        this.oneOffUsage = preparedLoader.oneOffUsage;
        this.oneOffMergeVertexBudget = preparedLoader.oneOffMergeVertexBudget;
        this.oneOffMergeCommutative = preparedLoader.oneOffMergeCommutative;
    }

    public UnrealLoader worldVariant(WorldVariant variant) {
        this.variant = variant;
        classTable = null;
        classHeads = null;
        figureBodies = null;
        hairRows = null;
        mapPackages = variant.getMapWorld() == WorldVariant.MAIN
                ? Map.of()
                : WorldMapCatalog.findPackages(clientRoot, variant);
        return this;
    }

    public String getMapPackage(String tile) {
        return mapPackages.getOrDefault(tile, tile);
    }

    @Nullable
    UPackage loadMapPackage(String name) {
        return packageLoader.loadPackage(name).orElse(null);
    }

    void releaseMapPackage(String name) {
        packageLoader.removePackage(name);
    }

    public @Nullable UMap loadMap(String tile) {
        var name = getMapPackage(tile);
        log.info("Loading map: {}", name);
        var loadStarted = System.nanoTime();
        var packageStarted = System.nanoTime();
        var optionalPackage = packageLoader.loadPackage(name);
        recordLoadPhase(LoadPhase.PACKAGE, System.nanoTime() - packageStarted);
        if (optionalPackage.isEmpty()) {
            recordLoad(name, System.nanoTime() - loadStarted, false);
            return null;
        }
        var loaded = false;
        try {
            var map = buildMap(tile, optionalPackage.get());
            loaded = true;
            return map;
        } catch (UMissingObjectException e) {
            log.warn("Skipping map {}: {}", name, e.toString());
            return null;
        } finally {
            packageLoader.removePackage(name);
            packageLoader.removePackage("T_" + name);
            packageLoader.removePackage("t_" + name);
            recordLoad(name, System.nanoTime() - loadStarted, loaded);
        }
    }

    public @Nullable MapCollision loadMapCollision(String tile) {
        var name = getMapPackage(tile);
        var optionalPackage = packageLoader.loadPackage(name);
        if (optionalPackage.isEmpty()) {
            return null;
        }
        try {
            var uPackage = optionalPackage.get();
            var terrains = new ArrayList<UTerrainInfoActor>();
            uPackage.loadObjects("TerrainInfo", terrains);
            var terrain = terrains.isEmpty() ? null : loadTerrain(uPackage);
            var terrainCollision = terrain == null || !terrain.hasSurface()
                    ? new TerrainCollision(new float[0], new float[0])
                    : collisionTerrain(terrain);
            var meshActors = new ArrayList<StaticMeshActor>();
            uPackage.loadObjects("StaticMeshActor", meshActors);
            uPackage.loadObjects("MovableStaticMeshActor", meshActors);
            uPackage.loadObjects("L2MovableStaticMeshActor", meshActors);
            var volumes = new ArrayList<BlockingVolumeActor>();
            uPackage.loadObjects("BlockingVolume", volumes);
            var starts = new ArrayList<UActor>();
            uPackage.loadObjects("PlayerStart", starts);
            return MapCollision.of(
                    loadPrimaryModel(uPackage),
                    meshActors,
                    volumes,
                    starts,
                    terrainCollision.triangles(),
                    terrainCollision.grid());
        } finally {
            packageLoader.removePackage(name);
        }
    }

    private record TerrainCollision(float[] triangles, float[] grid) {}

    public @Nullable USky loadSky(String name) {
        log.debug("Loading sky: {}", name);
        var optionalPackage = packageLoader.loadPackageIfPresent(name);
        if (optionalPackage.isEmpty()) {
            return null;
        }
        try {
            return buildSky(optionalPackage.get());
        } catch (RuntimeException e) {
            log.warn("Sky unavailable, could not build {}: {}", name, e.toString());
            return null;
        } finally {
            packageLoader.removePackage(name);
        }
    }

    public List<Effect> listEffects() {
        var effects = new ArrayList<Effect>();
        for (var name : effectPackageNames()) {
            var uPackage = packageLoader.loadPackageIfPresent(name);
            if (uPackage.isEmpty()) {
                continue;
            }
            try {
                effects.addAll(EffectClasses.list(uPackage.get().getArchive()));
            } catch (RuntimeException e) {
                log.warn("Effects of {} unavailable: {}", name, e.toString());
            }
        }
        return List.copyOf(effects);
    }

    public @Nullable Quake loadQuake(String className) {
        for (var name : effectPackageNames()) {
            var uPackage = packageLoader.loadPackageIfPresent(name);
            if (uPackage.isPresent()) {
                var quake = EffectClasses.quake(uPackage.get().getArchive(), className);
                if (quake != null) {
                    return quake;
                }
            }
        }
        return null;
    }

    public @Nullable EffectParts loadEffect(Effect effect) {
        var uPackage = packageLoader.loadPackageIfPresent(effect.packageName());
        if (uPackage.isEmpty()) {
            return null;
        }
        var actor = EffectClasses.actor(
                uPackage.get().getArchive(), effect.name(), spawnedParents(effect.packageName(), effect.name()));
        if (actor == null) {
            return null;
        }
        var actors = List.of(actor);
        var visibility = new HashMap<String, EmitterVisibility>();
        return new EffectParts(
                effect,
                spriteEmitterDescriptors(actors, visibility),
                meshEmitterDescriptors(effect.packageName(), actors, List.of(), List.of(), visibility),
                beamEmitterDescriptors(actors, visibility),
                actor.isSetSizeScale(),
                actor.getProjectileSpeed(),
                actor.getProjectileAcceleration(),
                forcedLifeTime(actor),
                EmitterLight.of(actor));
    }

    public @Nullable EffectParts loadProjectile(String effectPath) {
        var dot = effectPath.lastIndexOf('.');
        if (dot <= 0) {
            return null;
        }
        var wanted = effectPath.substring(0, dot);
        var className = effectPath.substring(dot + 1);
        for (var name : effectPackageNames()) {
            if (!name.equalsIgnoreCase(wanted)) {
                continue;
            }
            var uPackage = packageLoader.loadPackageIfPresent(name);
            if (uPackage.isEmpty()) {
                return null;
            }
            var archive = uPackage.get().getArchive();
            var projectile = EffectClasses.projectile(archive, className);
            var actor = projectile == null
                    ? null
                    : EffectClasses.actor(archive, className, spawnedParents(name, className));
            if (actor == null) {
                return null;
            }
            return new EffectParts(
                    projectile.effect(),
                    List.of(),
                    projectileMeshes(projectile, actor),
                    List.of(),
                    actor.isSetSizeScale(),
                    actor.getProjectileSpeed(),
                    actor.getProjectileAcceleration());
        }
        return null;
    }

    public @Nullable EffectParts loadEffect(Effect effect, byte[] previewPackage) {
        return loadEffect(effect, previewPackage, null);
    }

    public @Nullable EffectParts loadEffect(Effect effect, byte[] previewPackage, byte @Nullable [] previewTextures) {
        var archives = packageLoader.getArchiveLoader();
        var archive = archives.createMemoryArchive(
                effect.packageName(), UDecryptor.decrypt(previewPackage, Path.of(effect.packageName() + ".u")));
        UArchive shadowed = null;
        if (previewTextures != null) {
            var body = UDecryptor.decrypt(previewTextures, Path.of(EffectClasses.CUSTOM_TEXTURES + ".utx"));
            shadowed = archives.replaceArchive(
                    EffectClasses.CUSTOM_TEXTURES, archives.createMemoryArchive(EffectClasses.CUSTOM_TEXTURES, body));
        }
        try {
            var actor =
                    EffectClasses.actor(archive, effect.name(), spawnedParents(effect.packageName(), effect.name()));
            if (actor == null) {
                return null;
            }
            var actors = List.of(actor);
            var visibility = new HashMap<String, EmitterVisibility>();
            return new EffectParts(
                    effect,
                    spriteEmitterDescriptors(actors, visibility),
                    meshEmitterDescriptors(effect.packageName(), actors, List.of(), List.of(), visibility),
                    beamEmitterDescriptors(actors, visibility),
                    actor.isSetSizeScale(),
                    actor.getProjectileSpeed(),
                    actor.getProjectileAcceleration(),
                    forcedLifeTime(actor),
                    EmitterLight.of(actor));
        } finally {
            if (previewTextures != null) {
                archives.restoreArchive(EffectClasses.CUSTOM_TEXTURES, shadowed);
            }
            dxtChains.trimThreadState();
        }
    }

    public Body loadNpcBody(String npcClass) {
        try {
            return NpcClasses.body(
                    npcClass,
                    name -> packageLoader
                            .loadPackageIfPresent(name)
                            .map(UPackage::getArchive)
                            .orElse(null));
        } catch (RuntimeException e) {
            log.warn("NPC class {} unavailable: {}", npcClass, e.toString());
            return Body.UNKNOWN;
        }
    }

    public @Nullable ScriptedClass loadNpcClassScript(String npcClass) {
        Function<String, @Nullable UArchive> packages = name -> packageLoader
                .loadPackageIfPresent(name)
                .map(UPackage::getArchive)
                .orElse(null);
        try {
            var sources = NpcClassScripts.read(npcClass, packages);
            if (!sources.isEmpty()) {
                var script = ScriptedClass.of(sources);
                if (script != null && log.isDebugEnabled()) {
                    var skipped = script.getUnsupported();
                    if (!skipped.isEmpty()) {
                        log.debug("The script of NPC class {} skips {}", npcClass, skipped);
                    }
                }
                return script;
            }
            if (NpcClassScripts.hasEmptyText(npcClass, packages) && CLIENTS_WITHOUT_SCRIPT_TEXT.add(clientRoot)) {
                log.info(
                        "The classes of {} keep no UnrealScript source, so no class of an NPC spawns "
                                + "effects of its own in a cut scene. The first such class is {}",
                        clientRoot.getFileName(),
                        npcClass);
            } else {
                log.debug("NPC class {} has no UnrealScript source", npcClass);
            }
            return null;
        } catch (RuntimeException e) {
            log.warn("The script of NPC class {} unavailable: {}", npcClass, e.toString());
            return null;
        }
    }

    public List<Skill> listSkills() {
        try {
            var uPackage = packageLoader.loadPackageIfPresent("LineageSkillEffect");
            if (uPackage.isPresent()) {
                return SkillEffects.list(uPackage.get().getArchive());
            }
            var visuals = new UArchiveLoader(clientRoot, List.of(new USearchConfig("Animations", "usk")));
            var archive = visuals.loadArchiveIfPresent(SkillEffects.VISUAL_PACKAGE);
            if (archive == null) {
                return List.of();
            }
            try {
                return SkillEffects.listVisuals(archive);
            } finally {
                visuals.removeArchive(SkillEffects.VISUAL_PACKAGE);
            }
        } catch (RuntimeException e) {
            log.warn("Skill effects unavailable: {}", e.toString());
            return List.of();
        }
    }

    public @Nullable SoundClip loadSound(String fullName) {
        var dot = fullName.indexOf('.');
        if (dot <= 0 || dot == fullName.length() - 1) {
            return null;
        }
        var uPackage = packageLoader.loadPackageIfPresent(fullName.substring(0, dot));
        if (uPackage.isEmpty()) {
            return null;
        }
        var name = fullName.substring(fullName.lastIndexOf('.') + 1);
        return soundClips.get(
                fullName, () -> uPackage.get().findObject("Sound", name) instanceof USound sound ? sound : null);
    }

    public List<Entity> loadEditorActors(UPackage uPackage, Set<String> actorPaths) {
        if (actorPaths.isEmpty()) {
            return List.of();
        }
        var terrains = new ArrayList<UTerrainInfoActor>();
        uPackage.loadObjects("TerrainInfo", terrains);
        var terrain = terrains.isEmpty() ? new UTerrainInfoActor(null) : loadTerrain(uPackage);
        if (!terrain.hasBrokenScale()) {
            ensureSectorsLoaded(uPackage, terrain);
        }
        var brightness = loadLevelBrightness(uPackage);
        var lights = new ArrayList<Light>();
        for (var light : loadLights(uPackage, brightness, materialClient)) {
            if (!light.isAnimated()) {
                lights.add(light);
            }
        }
        var model = loadPrimaryModel(uPackage);
        var floors = model == null ? null : buildPropZoneFloors(model);
        var levelInfo = loadModelLevelInfo(uPackage, model);
        var zoneLighting = model == null ? null : buildActorZoneLighting(model, levelInfo);
        return loadMeshActorEntities(
                uPackage,
                terrain,
                new LightGrid(lights),
                loadInteriorAmbient(uPackage),
                model,
                floors,
                zoneLighting,
                levelInfo,
                SunVisibility.build(model),
                sunTerrainField(terrain),
                brightness,
                new MeshPlaneIndex(),
                new ArrayList<>(),
                actorPaths);
    }

    public List<Entity> loadEditorBsp(UPackage uPackage) {
        var groundless = !WorldTile.isMapName(uPackage.getName()) && !hasTerrain(uPackage);
        var terrain = groundless ? new UTerrainInfoActor(null) : loadTerrain(uPackage);
        return loadBspEntities(uPackage, mapBounds(terrain, groundless), null, csgArrayConcat, new ArrayList<>(), null);
    }

    public List<Entity> loadEditorBspSurfaces(UPackage uPackage, Set<Integer> surfaces) {
        var groundless = !WorldTile.isMapName(uPackage.getName()) && !hasTerrain(uPackage);
        var terrain = groundless ? new UTerrainInfoActor(null) : loadTerrain(uPackage);
        return loadBspEntities(
                uPackage, mapBounds(terrain, groundless), null, false, new ArrayList<>(), Set.copyOf(surfaces));
    }

    public int[] loadTerrainSteadyLight(UPackage uPackage, String owner) {
        var terrains = new ArrayList<UTerrainInfoActor>();
        uPackage.loadObjects("TerrainInfo", terrains);
        var terrain = terrains.stream()
                .filter(candidate -> owner.equals(candidate.getSourcePath()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Terrain owner is absent from the package: " + owner));
        ensureSectorsLoaded(uPackage, terrain);
        var map = terrain.getTerrainMap().get();
        var width = map.getUSize();
        var height = map.getVSize();
        var lighting = new TerrainSteadyLighting(terrain, loadLevelBrightness(uPackage), materialClient);
        var rgb = new int[width * height];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                rgb[y * width + x] = lighting.rgbAt(x, y);
            }
        }
        return rgb;
    }

    public TerrainEditorGeometry loadEditorTerrain(UPackage uPackage, String owner, Info info) {
        var terrains = new ArrayList<UTerrainInfoActor>();
        uPackage.loadObjects("TerrainInfo", terrains);
        var terrain = terrains.stream()
                .filter(candidate -> owner.equals(candidate.getSourcePath()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Terrain owner is absent from the package: " + owner));
        if (terrain.hasBrokenScale()) {
            throw new IllegalArgumentException("Terrain has no explicit valid scale");
        }
        ensureSectorsLoaded(uPackage, terrain);
        var entity =
                loadTerrainEntities(terrain, loadLevelBrightness(uPackage), WorldTile.isMapName(uPackage.getName()))
                        .stream()
                        .filter(candidate -> candidate.getMesh() instanceof TerrainMesh)
                        .findFirst()
                        .orElseThrow();
        var edges = new BitSet(info.width() * info.height());
        for (var i = 0; i < info.width() * info.height(); i++) {
            if (terrain.getEdgeTurnBitmap().get(i)) {
                edges.set(i);
            }
        }
        var projectors = terrainTextureProjectors(terrain);
        var packed = new float[projectors.length * 8];
        for (var i = 0; i < projectors.length; i++) {
            var p = projectors[i];
            var at = i * 8;
            packed[at] = p.uX();
            packed[at + 1] = p.uY();
            packed[at + 2] = p.uZ();
            packed[at + 3] = p.uOffset();
            packed[at + 4] = p.vX();
            packed[at + 5] = p.vY();
            packed[at + 6] = p.vZ();
            packed[at + 7] = p.vOffset();
        }
        var layers = new ArrayList<LayerSource>();
        var entries = entity.getMesh() instanceof TerrainMesh mesh
                        && mesh.surface().material() instanceof MultiTextureEntityMaterial material
                ? material.multiTexture().entries()
                : List.<MultiTextureEntry>of();
        for (var i = 0; i < terrain.getLayers().size(); i++) {
            var layer = terrain.getLayers().get(i);
            var texture = layer.getTexture().get();
            var alpha = layer.getAlphaMap().get();
            var drawn = i < TERRAIN_RENDER_LAYERS
                    && i < entries.size()
                    && entries.get(i).alphaMap() != null;
            layers.add(new LayerSource(
                    i,
                    texture == null || texture.getSourcePath() == null ? "" : texture.getSourcePath(),
                    alpha == null || alpha.getSourcePath() == null ? "" : alpha.getSourcePath(),
                    drawn));
        }
        return new TerrainEditorGeometry(
                entity,
                info,
                edges,
                terrain.isInverted(),
                packed,
                layers,
                terrain.getNativeToWorld(),
                terrain.getNativeToHeightMap());
    }

    public List<DecorationTemplate> loadEditorDecorations(UPackage uPackage, String owner) {
        var terrains = new ArrayList<UTerrainInfoActor>();
        uPackage.loadObjects("TerrainInfo", terrains);
        var terrain = terrains.stream()
                .filter(candidate -> owner.equals(candidate.getSourcePath()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Terrain owner is absent from the package: " + owner));
        if (!terrain.hasSurface()) {
            return List.of();
        }
        ensureSectorsLoaded(uPackage, terrain);
        return List.copyOf(decorationTemplates(terrain, loadLevelBrightness(uPackage)));
    }

    @Nullable
    DecorationDensity loadDecorationDensity(UTexture texture) {
        var width = texture.getUSize();
        var height = texture.getVSize();
        var mips = texture.getMips();
        var data = mips == null || mips.length == 0 ? null : mips[0].getData();
        if (data != null && width > 0 && height > 0) {
            var values = new byte[width * height];
            switch (texture.getFormat()) {
                case TEXF_DXT1 -> {
                    if (width % 4 == 0 && height % 4 == 0 && data.length >= width * height / 2) {
                        for (var y = 0; y < height; y++) {
                            for (var x = 0; x < width; x++) {
                                values[y * width + x] = (byte) NativeTerrainLayerPasses.dxt1(data, 0, width, x, y);
                            }
                        }
                        return new DecorationDensity(width, height, values);
                    }
                }
                case TEXF_P8 -> {
                    if (data.length >= values.length) {
                        var palette = texture.getPalette().get();
                        for (var i = 0; i < values.length; i++) {
                            values[i] = palette == null ? data[i] : (byte) palette.getColors()[data[i] & 255].r();
                        }
                        return new DecorationDensity(width, height, values);
                    }
                }
                case TEXF_RGBA8 -> {
                    if (data.length >= values.length * 4) {
                        for (var i = 0; i < values.length; i++) {
                            values[i] = data[i * 4 + 3];
                        }
                        return new DecorationDensity(width, height, values);
                    }
                }
                default -> {}
            }
        }
        var loaded = loadTexture(texture);
        if (loaded == null || loaded.data().isEmpty()) {
            return null;
        }
        var values = TexturePixels.decodeBaseRed(loaded);
        return values.length >= loaded.width() * loaded.height()
                ? new DecorationDensity(loaded.width(), loaded.height(), values)
                : null;
    }

    public void setDecorationsNearCamera(boolean nearCamera) {
        decorationsNearCamera = nearCamera;
    }

    public void setImpostorDistanceMultiplier(float multiplier) {
        if (!Float.isFinite(multiplier) || multiplier < 1f || multiplier > 20f) {
            throw new IllegalArgumentException("impostor distance multiplier must be within 1..20");
        }
        impostorDistanceMultiplier = multiplier;
    }

    EntityMesh getStaticMeshActorMesh(UStaticMesh mesh, StaticMeshActor actor) {
        return meshCache.computeIfAbsent(
                staticMeshActorCacheKey(mesh, actor),
                ignored -> buildForMapActor(() -> buildStaticEntityMesh(mesh, actor)));
    }

    EntityMesh buildStaticEntityMesh(UStaticMesh unrealMesh, @Nullable StaticMeshActor collisionActor) {
        var uvStream = unrealMesh.getUvStream();
        if (uvStream == null || uvStream.length == 0) {
            throw new IllegalStateException("Surface doesn't have texture coordinates: " + unrealMesh.getFullName());
        }
        var meshFullName = unrealMesh.getFullName();
        var uvs = uvStream[0].getUvs();
        var secondaryUvs =
                uvStream.length > 1 && uvStream[1].getUvs().length == uvs.length ? uvStream[1].getUvs() : null;
        var beastUvs = unrealMesh.getBeastLightmapUvs();
        if (beastUvs != null && beastUvs.length == uvs.length) {
            secondaryUvs = beastUvs;
        }
        var vertices = unrealMesh.getVertexStream().getVertices();
        var colorStream = unrealMesh.getColorStream();
        var vertexColors = colorStream != null ? colorStream.getColors() : null;
        var alphaStream = unrealMesh.getAlphaStream();
        var vertexAlphas = alphaStream != null ? alphaStream.getColors() : null;
        var ambientMultiplierColors = unrealMesh.isUseVertexColor() ? vertexColors : vertexAlphas;
        var meshVertices = MeshVertexBuffer.builder(vertices.length);
        var nativeTangents = unrealMesh.isUseNormalMap() ? NativeStaticMeshTangents.generate(unrealMesh) : null;
        var hasVertexAlpha = false;
        for (int i = 0; i < vertices.length; i++) {
            var vertex = vertices[i];
            var position = vertex.getLocation();
            var normal = vertex.getNormal();
            var uv = uvs[i];
            var vertexIndex = meshVertices.add(
                    position.getX(),
                    position.getY(),
                    position.getZ(),
                    normal.getX(),
                    normal.getY(),
                    normal.getZ(),
                    uv.getX(),
                    uv.getY());
            if (nativeTangents != null) {
                meshVertices.setStaticMeshTangent(
                        vertexIndex,
                        nativeTangents[i * 4],
                        nativeTangents[i * 4 + 1],
                        nativeTangents[i * 4 + 2],
                        nativeTangents[i * 4 + 3]);
            }
            var color = meshVertexColor(ambientMultiplierColors, vertexAlphas, i, vertices.length);
            if (color != null) {
                meshVertices.setColor(vertexIndex, color.getX(), color.getY(), color.getZ(), color.getW());
                hasVertexAlpha |= color.getW() < 1f - 1e-6f;
            }
            if (secondaryUvs != null) {
                meshVertices.setSecondaryUv(vertexIndex, secondaryUvs[i].getX(), secondaryUvs[i].getY());
            }
        }
        var meshSurfaces = new ArrayList<Entity.Surface>();
        var meshIndices = new int[countSurfaceIndices(unrealMesh.getSurfaces())];
        var meshIndicesIndex = 0;
        for (int i = 0; i < unrealMesh.getSurfaces().length; i++) {
            var unrealSurface = unrealMesh.getSurfaces()[i];
            if (unrealSurface.getTriangleMax() == 0) {
                continue;
            }
            for (int j = 0; j < unrealSurface.getTriangleMax(); j++) {
                var startIndex = unrealSurface.getFirstIndex() + j * 3;
                var indices = unrealMesh.getIndexStream().getIndices();
                meshIndices[meshIndicesIndex++] = indices[startIndex + 2];
                meshIndices[meshIndicesIndex++] = indices[startIndex + 1];
                meshIndices[meshIndicesIndex++] = indices[startIndex];
            }
            var unrealMaterial = unrealMesh.getMaterials()[i];
            var materialNode = unrealMaterial.getMaterial().get();
            var surfaceType = SurfaceType.STATIC_MESH.getId();
            if (unrealMesh.isSurfaceMirrorDoubled(i)) {
                surfaceType |= SurfaceType.TWO_SIDED_DOUBLED.getId();
            } else if (isTwoSided(materialNode)) {
                surfaceType |= SurfaceType.MESH_TWO_SIDED.getId();
            }
            if (collisionActor != null && isMirrored(collisionActor)) {
                surfaceType |= SurfaceType.MESH_MIRRORED.getId();
            }
            if (hasVertexAlpha) {
                surfaceType |= SurfaceType.VERTEX_ALPHA.getId();
            }
            var maybeMaterial = loadMaterial(materialNode);
            var surfaceMaterial = maybeMaterial == null ? DEFAULT_MESH_ACTOR_MATERIAL : maybeMaterial;
            if (collisionActor == null || !hasCollision(collisionActor, unrealMaterial)) {
                surfaceType |= SurfaceType.PASSABLE.getId();
                surfaceMaterial = maybeMaterial == null ? DEFAULT_PASSABLE_MATERIAL : maybeMaterial;
            }
            var blend = flowingWaterBlend(effectiveFxBlend(blendModeOf(materialNode), surfaceMaterial), meshFullName);
            meshSurfaces.add(new Entity.Surface(
                    surfaceType,
                    unrealSurface.getFirstIndex(),
                    unrealSurface.getTriangleMax() * 3,
                    surfaceMaterial,
                    blend,
                    uvAnimationOf(materialNode),
                    false,
                    maybeMaterial != null && materialNode != null ? materialNode.getFullName() : null,
                    alphaRefOf(materialNode, blend),
                    usesVertexColor(materialNode),
                    writesDepth(materialNode, blend),
                    modulatesStaticLighting2x(materialNode)));
        }
        return new EntityMesh(
                meshVertices.build(),
                meshIndices,
                meshSurfaces,
                List.of(),
                unrealMesh.getBoundingBox().toBox(),
                unrealMesh.isSwayObject() ? unrealMesh.getFrequency() : 0f,
                unrealMesh.getMaxSwayAngle(),
                unrealMesh.isUseBillBoard());
    }

    int findVertMeshSurfaces(UVertMesh mesh, List<UObject> overrides, Predicate<@Nullable UMaterial> rule) {
        var found = 0;
        var surface = 0;
        for (var materialIndex = 0; materialIndex < mesh.getMaterials().length; materialIndex++) {
            var index = materialIndex;
            if (Arrays.stream(mesh.getFaces()).noneMatch(face -> face.materialIndex() == index)) {
                continue;
            }
            var override = vertMeshOverride(mesh, materialIndex, overrides);
            var node = override instanceof UMaterial material ? material : mesh.getTexture(materialIndex);
            if (surface < Integer.SIZE && rule.test(node)) {
                found |= 1 << surface;
            }
            surface++;
        }
        return found;
    }

    List<EntityMesh> buildVertMeshFrames(UVertMesh mesh, List<UObject> overrides, boolean useMeshBlendMode) {
        return buildVertMeshFrames(mesh, overrides, useMeshBlendMode, false);
    }

    List<EntityMesh> buildVertMeshFrames(
            UVertMesh mesh, List<UObject> overrides, boolean useMeshBlendMode, boolean colorModifier) {
        if (mesh == null || mesh.getFrameCount() < 1 || mesh.getWedges().length < 3 || mesh.getFaces().length < 1) {
            throw new IllegalArgumentException("vertex mesh has no renderable animation frames");
        }
        var indices = new int[Math.multiplyExact(mesh.getFaces().length, 3)];
        var surfaces = new ArrayList<Entity.Surface>();
        var indexCursor = 0;
        for (var materialIndex = 0; materialIndex < mesh.getMaterials().length; materialIndex++) {
            var firstIndex = indexCursor;
            for (var face : mesh.getFaces()) {
                if (face.materialIndex() != materialIndex) {
                    continue;
                }
                indices[indexCursor++] = face.wedge2();
                indices[indexCursor++] = face.wedge1();
                indices[indexCursor++] = face.wedge0();
            }
            if (indexCursor == firstIndex) {
                continue;
            }
            var override = vertMeshOverride(mesh, materialIndex, overrides);
            var materialNode = override instanceof UMaterial material ? material : mesh.getTexture(materialIndex);
            var maybeMaterial = loadMaterial(materialNode);
            var material = maybeMaterial == null ? DEFAULT_PASSABLE_MATERIAL : maybeMaterial;
            var ownBlend = effectiveFxBlend(blendModeOf(materialNode), material);
            var blend = colorModifier ? MeshParticleMaterials.blendUnderColorModifier(ownBlend) : ownBlend;
            var sides = isTwoSided(materialNode) ? SurfaceType.MESH_TWO_SIDED.getId() : 0L;
            surfaces.add(new Entity.Surface(
                    SurfaceType.STATIC_MESH.getId() | SurfaceType.PASSABLE.getId() | sides,
                    firstIndex,
                    indexCursor - firstIndex,
                    material,
                    blend,
                    uvAnimationOf(materialNode),
                    false,
                    maybeMaterial != null && materialNode != null ? materialNode.getFullName() : null,
                    alphaRefOf(materialNode, blend),
                    usesVertexColor(materialNode),
                    writesDepth(materialNode, ownBlend),
                    modulatesStaticLighting2x(materialNode)));
        }
        if (indexCursor != indices.length) {
            indices = Arrays.copyOf(indices, indexCursor);
        }

        var frames = new ArrayList<EntityMesh>(mesh.getFrameCount());
        for (var frame = 0; frame < mesh.getFrameCount(); frame++) {
            var normalX = new float[mesh.getVertexCount()];
            var normalY = new float[mesh.getVertexCount()];
            var normalZ = new float[mesh.getVertexCount()];
            for (var face : mesh.getFaces()) {
                var w0 = mesh.getWedges()[face.wedge0()];
                var w1 = mesh.getWedges()[face.wedge1()];
                var w2 = mesh.getWedges()[face.wedge2()];
                var p0 = mesh.getPosition(frame, w0.vertexIndex());
                var p1 = mesh.getPosition(frame, w1.vertexIndex());
                var p2 = mesh.getPosition(frame, w2.vertexIndex());
                var ax = p1.getX() - p0.getX();
                var ay = p1.getY() - p0.getY();
                var az = p1.getZ() - p0.getZ();
                var bx = p2.getX() - p0.getX();
                var by = p2.getY() - p0.getY();
                var bz = p2.getZ() - p0.getZ();
                var nx = ay * bz - az * by;
                var ny = az * bx - ax * bz;
                var nz = ax * by - ay * bx;
                accumulateNormal(normalX, normalY, normalZ, w0.vertexIndex(), nx, ny, nz);
                accumulateNormal(normalX, normalY, normalZ, w1.vertexIndex(), nx, ny, nz);
                accumulateNormal(normalX, normalY, normalZ, w2.vertexIndex(), nx, ny, nz);
            }
            var vertices = MeshVertexBuffer.builder(mesh.getWedges().length);
            var frameBounds = new Box();
            for (var wedge : mesh.getWedges()) {
                var vertex = wedge.vertexIndex();
                var length = (float) Math.sqrt(normalX[vertex] * normalX[vertex]
                        + normalY[vertex] * normalY[vertex]
                        + normalZ[vertex] * normalZ[vertex]);
                var normal = length > 1e-7f
                        ? new Vec3(normalX[vertex] / length, normalY[vertex] / length, normalZ[vertex] / length)
                        : new Vec3(0f, 0f, 1f);
                var position = mesh.getPosition(frame, vertex);
                frameBounds.add(position);
                vertices.add(
                        position.getX(),
                        position.getY(),
                        position.getZ(),
                        normal.getX(),
                        normal.getY(),
                        normal.getZ(),
                        wedge.uv().getX(),
                        wedge.uv().getY());
            }
            frames.add(new EntityMesh(vertices.build(), indices, surfaces, List.of(), frameBounds, 0f, 1f, false));
        }
        return List.copyOf(frames);
    }

    public @Nullable NpcModel loadNpcModel(String meshReference, List<String> textureReferences) {
        return loadNpcModel(meshReference, textureReferences, null, null, null, null, false, null, null);
    }

    public boolean hasSkeletalMesh(String reference) {
        return findNpcMesh(reference) != null;
    }

    public @Nullable NpcModel loadStaticModel(String reference) {
        var mesh = findStaticMesh(reference);
        return mesh == null ? null : new NpcModel(reference, buildStaticEntityMesh(mesh, null), "", 0);
    }

    public @Nullable UStaticMesh findStaticMesh(String reference) {
        var dot = reference.indexOf('.');
        if (dot <= 0) {
            return null;
        }
        var name = reference.substring(reference.lastIndexOf('.') + 1);
        var found = packageLoader
                .loadPackageIfPresent(reference.substring(0, dot))
                .map(uPackage -> uPackage.findObject("StaticMesh", name))
                .orElse(null);
        return found instanceof UStaticMesh mesh ? mesh : null;
    }

    record FoundNpcMesh(USkeletalMesh mesh, UPackage uPackage) {}

    public record NpcRig(
            NpcModel model,
            USkeletalMesh mesh,
            @Nullable UMeshAnimation animation,
            long[] materialFlags,
            List<PlayerRigPart> parts) {}

    public @Nullable NpcRig loadNpcRig(String meshReference, List<String> textureReferences) {
        return loadNpcRig(meshReference, textureReferences, List.of());
    }

    public @Nullable NpcRig loadNpcRig(String meshReference, List<String> textureReferences, List<HeldItem> held) {
        return loadNpcRig(meshReference, textureReferences, held, null);
    }

    public @Nullable NpcRig loadNpcRig(
            String meshReference, List<String> textureReferences, List<HeldItem> held, @Nullable String wait) {
        var parts = new ArrayList<PlayerRigPart>();
        var model = holding(meshReference, textureReferences, held, parts, wait);
        var found = model == null ? null : findNpcMesh(meshReference);
        if (found == null) {
            return null;
        }
        UMeshAnimation animation = null;
        try {
            if (found.mesh().getAnimationIndex() != 0
                    && found.uPackage().loadObject(found.mesh().getAnimationIndex()) instanceof UMeshAnimation loaded) {
                animation = loaded;
            }
        } catch (RuntimeException unreadable) {
            log.debug("{} has no readable animation: {}", meshReference, unreadable.toString());
        }
        return new NpcRig(
                model,
                found.mesh(),
                animation,
                npcMaterialFlags(found.mesh(), textureReferences),
                parts.size() > 1 ? List.copyOf(parts) : List.of());
    }

    public @Nullable NpcModel loadNpcModel(String meshReference, List<String> textureReferences, List<HeldItem> held) {
        return holding(meshReference, textureReferences, held, null, null);
    }

    public @Nullable NpcModel loadNpcModel(
            String meshReference, List<String> textureReferences, List<HeldItem> held, @Nullable String wait) {
        return holding(meshReference, textureReferences, held, null, wait);
    }

    @Nullable
    FoundNpcMesh findNpcMesh(String meshReference) {
        var found = findSkeletalMesh(meshReference);
        if (found == null) {
            return null;
        }
        var mesh = found.mesh();
        var meshName = meshReference.substring(meshReference.lastIndexOf('.') + 1);
        if (mesh.getFaces().length == 0
                && meshName.toLowerCase(Locale.ROOT).endsWith("_f")
                && found.uPackage().findObject("SkeletalMesh", meshName.substring(0, meshName.length() - 2))
                        instanceof USkeletalMesh whole) {
            mesh = whole;
        }
        return mesh.getFaces().length == 0 || mesh.getPoints().length == 0
                ? null
                : new FoundNpcMesh(mesh, found.uPackage());
    }

    public @Nullable SceneRig loadSceneRig(String meshReference, float scale) {
        var found = findSkeletalMesh(meshReference);
        if (found == null || found.mesh().getAnimationIndex() == 0) {
            return null;
        }
        try {
            return found.uPackage().loadObject(found.mesh().getAnimationIndex()) instanceof UMeshAnimation animation
                    ? SceneRig.of(found.mesh(), animation, scale)
                    : null;
        } catch (RuntimeException unreadable) {
            log.debug("{} has no readable animation: {}", meshReference, unreadable.toString());
            return null;
        }
    }

    public @Nullable SceneRig loadAnimationRig(String meshReference) {
        return loadAnimationRig(meshReference, PlayerFigures.figureOf(meshReference));
    }

    public @Nullable SceneRig loadAnimationRig(String meshReference, String figure) {
        var parsed = PlayerFigures.parseFace(meshReference);
        if (parsed == null) {
            return loadSceneRig(meshReference, 1);
        }
        var found = findSkeletalMesh(findPlayerFace(meshReference));
        if (found == null) {
            return null;
        }
        try {
            var animation = findRaceAnimation(found.uPackage(), parsed.race());
            return animation == null ? null : SceneRig.of(found.mesh(), narrowFor(animation, figure, null), 1);
        } catch (RuntimeException unreadable) {
            log.debug("{} has no readable animation: {}", meshReference, unreadable.toString());
            return null;
        }
    }

    public @Nullable SceneRig loadCameraRig() {
        return loadSceneRig(CAMERA_MESH, 1);
    }

    public @Nullable NpcModel loadPlayerLikeNpcModel(String faceReference, List<String> faceTextures) {
        return loadPlayerLikeNpcModel(faceReference, faceTextures, Outfit.BARE);
    }

    public record HeldItem(String mesh, List<String> textures, String bone, float scale) {
        public HeldItem(String mesh, List<String> textures, String bone) {
            this(mesh, textures, bone, 1);
        }
    }

    public record WornItem(String mesh, List<String> textures, boolean hairAccessory) {}

    public record HairCover(int style, boolean byArmor, Fallback fallback) {
        public static final HairCover NONE = new HairCover(0, false, Fallback.NONE);

        public enum Fallback {
            NONE,
            FRONT,
            ALL
        }

        public HairCover withoutAccessory() {
            return byArmor ? this : NONE;
        }
    }

    public record Outfit(
            List<WornItem> worn,
            Set<String> covered,
            int hairStyle,
            HairCover hairCover,
            List<HeldItem> held,
            @Nullable String stance,
            @Nullable String classWait,
            @Nullable String figure,
            @Nullable String rowClass) {
        public static final Outfit BARE =
                new Outfit(List.of(), Set.of(), 0, HairCover.NONE, List.of(), null, null, null, null);

        public Outfit(
                List<WornItem> worn,
                Set<String> covered,
                int hairStyle,
                HairCover hairCover,
                List<HeldItem> held,
                @Nullable String stance,
                @Nullable String classWait,
                @Nullable String figure) {
            this(worn, covered, hairStyle, hairCover, held, stance, classWait, figure, null);
        }

        public Outfit(
                List<WornItem> worn,
                Set<String> covered,
                int hairStyle,
                HairCover hairCover,
                List<HeldItem> held,
                @Nullable String stance,
                @Nullable String classWait) {
            this(worn, covered, hairStyle, hairCover, held, stance, classWait, null);
        }

        public Outfit withFigure(@Nullable String figure) {
            return new Outfit(worn, covered, hairStyle, hairCover, held, stance, classWait, figure, rowClass);
        }

        public Outfit withRowClass(@Nullable String rowClass) {
            return new Outfit(worn, covered, hairStyle, hairCover, held, stance, classWait, figure, rowClass);
        }
    }

    public @Nullable NpcModel loadPlayerLikeNpcModel(String faceReference, List<String> faceTextures, Outfit outfit) {
        return playerLike(findPlayerFace(faceReference), faceTextures, outfit, null);
    }

    public String findPlayerFace(String faceReference) {
        var parsed = PlayerFigures.parseFace(faceReference);
        if (parsed == null
                || parsed.packageName().equalsIgnoreCase(OLD_PLAYER_PACKAGE)
                || hasSkeletalMesh(faceReference)) {
            return faceReference;
        }
        var old = OLD_PLAYER_PACKAGE + faceReference.substring(faceReference.indexOf('.'));
        return hasSkeletalMesh(old) ? old : faceReference;
    }

    public boolean hasPlayerFace(String faceReference) {
        return hasSkeletalMesh(findPlayerFace(faceReference));
    }

    public record PlayerRigPart(
            USkeletalMesh mesh,
            @Nullable String bone,
            boolean accessory,
            long[] materialFlags,
            float scale,
            Vec3 @Nullable [] points) {
        public PlayerRigPart(
                USkeletalMesh mesh, @Nullable String bone, boolean accessory, long[] materialFlags, float scale) {
            this(mesh, bone, accessory, materialFlags, scale, null);
        }
    }

    public record PlayerRig(
            NpcModel model, USkeletalMesh frame, @Nullable UMeshAnimation animation, List<PlayerRigPart> parts) {}

    public @Nullable PlayerRig loadPlayerRig(String asked, List<String> faceTextures, Outfit outfit) {
        var faceReference = findPlayerFace(asked);
        var parts = new ArrayList<PlayerRigPart>();
        var model = playerLike(faceReference, faceTextures, outfit, parts);
        var parsed = PlayerFigures.parseFace(faceReference);
        if (model == null || parts.isEmpty() || parsed == null) {
            return null;
        }
        var uPackage = packageLoader.loadPackageIfPresent(parsed.packageName());
        var frame = uPackage.isPresent()
                        && uPackage.get()
                                        .findObject(
                                                "SkeletalMesh", faceReference.substring(faceReference.indexOf('.') + 1))
                                instanceof USkeletalMesh face
                ? face
                : parts.getFirst().mesh();
        var animation = uPackage.isEmpty() ? null : findRaceAnimation(uPackage.get(), parsed.race());
        var narrowed = animation == null
                ? null
                : narrowFor(animation, figureOf(faceReference, outfit), outfit.classWait(), outfit.rowClass());
        for (var part : parts) {
            if (narrowed != null && part.bone() == null && SkeletalPose.isMount(part.mesh(), frame)) {
                var own = part.mesh().loadOwnAnimation();
                if (own != null) {
                    narrowed = narrowed.join(
                            own, SkeletalPose.MOUNT_PREFIX, outfit.classWait() == null ? "" : outfit.classWait());
                }
            }
        }
        return new PlayerRig(model, frame, narrowed, List.copyOf(parts));
    }

    private record BareStandIn(
            @Nullable String mesh, String texture, @Nullable Vec4 tint) {}

    void setReflectionTextOfP152(boolean known) {
        reflectionTextOfP152 = known;
    }

    void setReflectionReplacedWithoutLight(boolean replaced) {
        reflectionReplacedWithoutLight = replaced;
    }

    @Nullable
    EntityMaterial loadMapActorMaterial(UMaterial unrealMaterial) {
        return buildForMapActor(() -> loadMaterial(unrealMaterial));
    }

    @Nullable
    NpcPartPasses loadNpcPartPasses(@Nullable UMaterial node) {
        var inner = node;
        try {
            for (var depth = 0;
                    depth < 8 && (inner instanceof UFinalBlend || inner instanceof UColorModifier);
                    depth++) {
                inner = ((UModifier) inner).getMaterial().get();
            }
        } catch (RuntimeException unresolved) {
            return null;
        }
        if (!(inner instanceof UShader shader)
                || !holdsReflectionInCombiner(shader.getDiffuse().get(), false, 0)) {
            return null;
        }
        var outer = npcPartMaterials;
        npcPartMaterials = true;
        EntityMaterial converted;
        try {
            converted = loadMaterial(node);
        } finally {
            npcPartMaterials = outer;
        }
        return converted instanceof WaterEntityMaterial water
                        && water.authoredPasses() != null
                        && water.authoredPasses().passes().hasReflectionCoordinates()
                ? new NpcPartPasses(water.authoredPasses(), water.framebuffer())
                : null;
    }

    @Nullable
    EntityMaterial loadMaterial(UMaterial unrealMaterial) {
        if (unrealMaterial == null) {
            return null;
        }
        return switch (unrealMaterial) {
            case UTexture uTexture -> {
                var animated = loadAnimatedTexture(uTexture);
                if (animated != null) {
                    yield animated;
                }
                var texture = loadTexture(uTexture);
                yield texture == null ? null : new TextureEntityMaterial(texture);
            }
            case UShader uShader -> {
                var diffuseMaterial = loadMaterial(uShader.getDiffuse().get());
                var base = loadShaderBase(uShader, diffuseMaterial);
                if (base != null && slotMaterialNode(uShader.getOpacity()) instanceof UConstantColor color) {
                    base = new ConstantOpacityEntityMaterial(
                            base, toVec4(color.getColor()).getW());
                }
                var waterSheet = waterSheetMaterial(uShader, base);
                if (waterSheet != null) {
                    yield waterSheet;
                }
                if (mapActorMaterials
                        && base != null
                        && base.getTexture() != null
                        && isSelfAlphaDecal(uShader)
                        && !uShader.isPerformLightingOnSpecularPass()
                        && uShader.getOutputBlending() == OutputBlending.OB_Normal
                        && slotMaterialNode(uShader.getSpecular()) != null) {
                    var passes = compileNativePasses(uShader);
                    if (passes != null
                            && passes.hasCompleteTextureBindings()
                            && passes.passes().drawCount() > 1) {
                        yield new WaterEntityMaterial(
                                base.getTexture(),
                                1f,
                                VEC3_EMPTY,
                                null,
                                UvAnimation.NONE,
                                0f,
                                null,
                                AuthoredFramebuffer.resolve(uShader),
                                passes,
                                true);
                    }
                }
                var reflective = reflectionPassMaterial(uShader, base);
                if (reflective != null) {
                    yield reflective;
                }
                var overlay = diffuseMaterial != null ? specularOverlayOf(uShader) : null;
                var result = overlay != null ? new SpecularEntityMaterial(base, overlay) : base;
                var selfIllumination = diffuseMaterial != null ? selfIlluminationOverlayOf(uShader) : null;
                yield selfIllumination != null ? new SpecularEntityMaterial(result, selfIllumination) : result;
            }
            case UFinalBlend uBlend -> {
                var base = loadMaterial(uBlend.getMaterial().get());
                if (base instanceof WaterEntityMaterial water) {
                    yield new WaterEntityMaterial(
                            water.texture(),
                            water.alpha(),
                            water.tint(),
                            water.material2(),
                            water.material2Anim(),
                            water.material2Rotation(),
                            authoredMaterials.compile(uBlend),
                            AuthoredFramebuffer.resolve(uBlend),
                            compileWrapperPasses(uBlend, water),
                            water.specularDecal());
                }
                if ((doesFinalBlendConsumeSourceAlpha(uBlend.getFbBlending()) || uBlend.isAlphaTest())
                        && base instanceof TextureEntityMaterial(Texture texture)) {
                    yield new TextureEntityMaterial(withAlphaTexture(texture));
                }
                yield base;
            }
            case UColorModifier uColorModifier -> {
                var base = loadMaterial(uColorModifier.getMaterial().get());
                if (base == null) {
                    yield null;
                }
                if (base instanceof WaterEntityMaterial water) {
                    var program = authoredMaterials.compile(uColorModifier);
                    var passes = compileWrapperPasses(uColorModifier, water);
                    if (program != null || passes != null) {
                        yield new WaterEntityMaterial(
                                water.texture(),
                                water.alpha(),
                                water.tint(),
                                water.material2(),
                                water.material2Anim(),
                                water.material2Rotation(),
                                program,
                                AuthoredFramebuffer.resolve(uColorModifier),
                                passes,
                                water.specularDecal());
                    }
                }
                var tint = toVec4(uColorModifier.getColor());
                if (tint.getX() == 1f && tint.getY() == 1f && tint.getZ() == 1f && tint.getW() == 1f) {
                    yield base;
                }
                yield new ColorModifierEntityMaterial(base, tint);
            }
            case UTexEnvMap uEnvMap ->
                envMapMaterialOf(
                        loadMaterial(uEnvMap.getEnvMapMaterial()),
                        uEnvMap.getEnvMapType().getId());
            case UModifier uModifier -> loadMaterial(uModifier.getMaterial().get());
            case UCombiner uCombiner -> loadCombiner(uCombiner);
            case UCubemap uCubemap -> {
                try {
                    var cube = loadCubemap(uCubemap);
                    if (cube != null) {
                        yield new ReflectiveEntityMaterial(null, cube, 1.0f);
                    }
                    var face = uCubemap.getFirstFace();
                    yield face == null ? null : loadMaterial(face);
                } catch (ClassCastException e) {
                    yield null;
                }
            }
            case UConstantColor uColor -> new ColorEntityMaterial(toVec4(uColor.getColor()));
            case UFadeColor uColor -> new ColorEntityMaterial(toVec4(uColor.getColor()));
            case UVertexColor _ -> new ColorEntityMaterial(VERTEX_COLOR_BASE);
            default -> null;
        };
    }

    @Nullable
    WaterSecondLayer getWaterSecondLayer(@Nullable UMaterial materialNode) {
        try {
            if (!(materialNode instanceof UShader shader)
                    || !(shader.getDiffuse().get() instanceof UCombiner top)) {
                return null;
            }
            if (top.getCombineOperation() != EColorOperation.CO_Add_With_Mask_Modulation
                    || top.getMask().get() != null) {
                return null;
            }
            var m2Node = top.getMaterial2().get();
            var m2Tex = firstTextureOf(m2Node);
            if (m2Tex == null) {
                return null;
            }
            var m2Image = loadTexture(m2Tex);
            return m2Image == null
                    ? null
                    : new WaterSecondLayer(
                            m2Image,
                            waterLayerOscillation(m2Node),
                            waterLayerRotation(m2Node),
                            waterLayerAdditive(m2Node));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private synchronized void recordLoad(String name, long nanos, boolean loaded) {
        telemetryAttempts++;
        if (loaded) {
            telemetryLoaded++;
        }
        telemetryTotalNanos = Math.addExact(telemetryTotalNanos, Math.max(0L, nanos));
        if (nanos > telemetrySlowestMapNanos) {
            telemetrySlowestMapNanos = nanos;
            telemetrySlowestMap = name;
        }
    }

    private synchronized void recordLoadPhase(LoadPhase phase, long nanos) {
        var elapsed = Math.max(0L, nanos);
        switch (phase) {
            case PACKAGE -> telemetryPackageNanos = Math.addExact(telemetryPackageNanos, elapsed);
            case DXT -> telemetryDxtNanos = Math.addExact(telemetryDxtNanos, elapsed);
            case TERRAIN -> telemetryTerrainNanos = Math.addExact(telemetryTerrainNanos, elapsed);
            case LIGHTING -> telemetryLightingNanos = Math.addExact(telemetryLightingNanos, elapsed);
            case STATIC_MESH -> telemetryStaticMeshNanos = Math.addExact(telemetryStaticMeshNanos, elapsed);
            case BSP -> telemetryBspNanos = Math.addExact(telemetryBspNanos, elapsed);
            case EFFECTS -> telemetryEffectsNanos = Math.addExact(telemetryEffectsNanos, elapsed);
        }
    }

    private synchronized void recordStaticMeshDetail(
            long occluderNanos,
            long indexNanos,
            long mergeNanos,
            long actors,
            long actorVertices,
            long lightStreamActors,
            long lightStreamVertices,
            long spatialActors,
            long exactActors,
            long lodActors,
            long lod2Actors,
            long lod0Vertices,
            long lod1Vertices,
            long lod2Vertices) {
        telemetryStaticMeshOccluderNanos = Math.addExact(telemetryStaticMeshOccluderNanos, Math.max(0L, occluderNanos));
        telemetryStaticMeshIndexNanos = Math.addExact(telemetryStaticMeshIndexNanos, Math.max(0L, indexNanos));
        telemetryStaticMeshMergeNanos = Math.addExact(telemetryStaticMeshMergeNanos, Math.max(0L, mergeNanos));
        telemetryStaticMeshActors = Math.addExact(telemetryStaticMeshActors, actors);
        telemetryStaticMeshActorVertices = Math.addExact(telemetryStaticMeshActorVertices, actorVertices);
        telemetryStaticMeshLightStreamActors = Math.addExact(telemetryStaticMeshLightStreamActors, lightStreamActors);
        telemetryStaticMeshLightStreamVertices =
                Math.addExact(telemetryStaticMeshLightStreamVertices, lightStreamVertices);
        telemetryStaticMeshSpatialActors = Math.addExact(telemetryStaticMeshSpatialActors, spatialActors);
        telemetryStaticMeshExactActors = Math.addExact(telemetryStaticMeshExactActors, exactActors);
        telemetryStaticMeshLodActors = Math.addExact(telemetryStaticMeshLodActors, lodActors);
        telemetryStaticMeshLod2Actors = Math.addExact(telemetryStaticMeshLod2Actors, lod2Actors);
        telemetryStaticMeshLod0Vertices = Math.addExact(telemetryStaticMeshLod0Vertices, lod0Vertices);
        telemetryStaticMeshLod1Vertices = Math.addExact(telemetryStaticMeshLod1Vertices, lod1Vertices);
        telemetryStaticMeshLod2Vertices = Math.addExact(telemetryStaticMeshLod2Vertices, lod2Vertices);
    }

    private enum LoadPhase {
        PACKAGE,
        DXT,
        TERRAIN,
        LIGHTING,
        STATIC_MESH,
        BSP,
        EFFECTS
    }

    private @Nullable String authoredMaterialIdentity(UMaterial material) {
        var identity = material.getSourceIdentity();
        return identity == null ? null : clientRoot.toAbsolutePath().normalize() + "|" + identity;
    }

    private Optional<UTexture> loadNativeWaterBitmap(Path root, String packageName, String name) {
        var loader = new UPackageLoader(
                root, List.of(new USearchConfig("Textures", "utx"), new USearchConfig("SysTextures", "utx")));
        var uPackage = loader.loadPackageIfPresent(packageName);
        if (uPackage.isEmpty()) {
            return Optional.empty();
        }
        var textures = new ArrayList<UTexture>();
        uPackage.get().loadObjects("Texture", textures);
        return textures.stream()
                .filter(texture -> name.equalsIgnoreCase(texture.getName()))
                .findFirst();
    }

    private List<String> effectPackageNames() {
        var names = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
        var present = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
        try (var roots = Files.list(clientRoot)) {
            for (var system : roots.filter(
                            path -> path.getFileName().toString().equalsIgnoreCase("System") && Files.isDirectory(path))
                    .toList()) {
                try (var files = Files.list(system)) {
                    for (var name :
                            files.map(path -> path.getFileName().toString()).toList()) {
                        if (!name.toLowerCase(Locale.ROOT).endsWith(".u")) {
                            continue;
                        }
                        var packageName = name.substring(0, name.length() - 2);
                        present.add(packageName);
                        if (EffectPackages.isStock(name)) {
                            names.add(packageName);
                        }
                    }
                }
            }
        } catch (IOException e) {
            log.warn("Effect packages unavailable: {}", e.toString());
        }
        for (var name : skillEffectPackageNames()) {
            if (present.contains(name)) {
                names.add(name);
            }
        }
        return List.copyOf(names);
    }

    private Set<String> skillEffectPackageNames() {
        var names = skillEffectPackages;
        if (names == null) {
            names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            try {
                for (var file : EffectPackages.find(clientRoot)) {
                    var fileName = file.getFileName().toString();
                    if (!EffectPackages.isStock(fileName)) {
                        names.add(fileName.substring(0, fileName.length() - 2));
                    }
                }
            } catch (IOException e) {
                log.warn("The packages of skill effects unavailable: {}", e.toString());
            }
            skillEffectPackages = names;
        }
        return names;
    }

    private USky buildSky(UPackage uPackage) {
        return buildSky(uPackage, -1);
    }

    private USky buildSky(UPackage uPackage, int skyZone) {
        var model = loadPrimaryModel(uPackage);
        if (model == null) {
            throw new IllegalStateException("No sky model (Level) in package: " + uPackage.getName());
        }
        var entities = loadModelEntity(model, new Box(), false, Optional.empty(), true, null, false, null, null);
        if (skyZone >= 0) {
            var sections = new HashSet<Integer>();
            for (var node : model.getNodes()) {
                if (node.getZone()[0] == skyZone || node.getZone()[1] == skyZone) {
                    sections.add(node.getISection());
                }
            }
            entities = entities.stream()
                    .filter(entity -> sections.contains(entity.getSourceBspSection()))
                    .toList();
        }
        var layers = new LinkedHashMap<String, List<Entity>>();
        for (var entity : entities) {
            var key = entity.getDebugName() != null ? entity.getDebugName() : "unknown";
            layers.computeIfAbsent(key, _ -> new ArrayList<>()).add(entity);
        }

        var nsuns = new ArrayList<NCelestialActor>();
        uPackage.loadObjects("NSun", nsuns);
        var sun = nsuns.isEmpty() ? null : celestialOf(nsuns.getFirst());

        var nmoons = new ArrayList<NCelestialActor>();
        uPackage.loadObjects("NMoon", nmoons);
        nmoons.sort((a, b) -> a.getName().compareTo(b.getName()));
        var moons = new ArrayList<Celestial>();
        for (var nmoon : nmoons) {
            var moon = celestialOf(nmoon);
            if (moon != null) {
                moons.add(moon);
            }
        }

        var zones = new ArrayList<SkyZoneInfoActor>();
        uPackage.loadObjects("SkyZoneInfo", zones);
        var panU = 0.2f;
        var panV = 0.2f;
        var origin = VEC3_EMPTY;
        var flares = new ArrayList<Flare>();
        if (!zones.isEmpty()) {
            var zone = zones.getFirst();
            panU = zone.getTexUPanSpeed();
            panV = zone.getTexVPanSpeed();
            origin = zone.getPosition();
            for (int i = 0; i < zone.getLensFlareCount(); i++) {
                if (zone.getLensFlare(i) instanceof UMaterial flareMat) {
                    var tex = skyTexture(flareMat);
                    if (tex != null) {
                        flares.add(new Flare(tex, zone.getLensFlareOffset()[i], zone.getLensFlareScale()[i]));
                    }
                }
            }
        }
        var drawLayers = new ArrayList<>(orderedSkyLayers(model, entities, origin));
        drawLayers.addAll(skyMeshLayers(uPackage));
        var envSetup = loadEnvSetup();
        var activeSkyMaterials = envSetup == null ? List.<String>of() : envSetup.activeSkyMaterials();
        var cloudMaterials = envSetup == null ? List.<String>of() : envSetup.clouds();
        log.debug(
                "Sky replay: {}; EnvSetup active: {}",
                drawLayers.stream()
                        .map(layer -> layer.bspSection() + ":" + layer.name())
                        .toList(),
                activeSkyMaterials);
        return new USky(
                layers,
                List.copyOf(drawLayers),
                activeSkyMaterials,
                cloudMaterials,
                origin,
                sun,
                moons,
                flares,
                panU,
                panV);
    }

    private @Nullable EnvSetup loadEnvSetup() {
        try {
            return EnvSetupParser.parse(clientRoot.resolve("system").resolve("env.int"));
        } catch (RuntimeException e) {
            log.warn("EnvSetup unavailable; retaining all SkyLevel layers: {}", e.toString());
            return null;
        }
    }

    private List<Layer> skyMeshLayers(UPackage uPackage) {
        var actors = new ArrayList<StaticMeshActor>();
        uPackage.loadObjects("SkyMeshActor", actors);
        var result = new ArrayList<Layer>();
        for (var actor : actors) {
            var unrealMesh = actor.getStaticMesh().get();
            if (unrealMesh == null) {
                continue;
            }
            var looks = zoneLooksOf(actor);
            if (looks.isEmpty()) {
                looks = List.of(new ActorLook(actor.getSkins(), skyZoneRender(actor), true));
            }
            for (var look : looks) {
                result.addAll(skyMeshLayers(uPackage, actor, unrealMesh, look));
            }
        }
        return result;
    }

    private ZoneRender skyZoneRender(StaticMeshActor actor) {
        var mask = actor.getZoneRenderMask();
        return mask == 0 || !hasEssenceZoneStates()
                ? ZoneRender.EVERY_STATE
                : new ZoneRender(mask, zoneStart(actor), true, zoneMap(actor));
    }

    private List<Layer> skyMeshLayers(
            UPackage uPackage, StaticMeshActor actor, UStaticMesh unrealMesh, ActorLook look) {
        var result = new ArrayList<Layer>();
        EntityMesh mesh;
        try {
            mesh = applyStaticMeshActorSkins(buildStaticEntityMesh(unrealMesh, null), unrealMesh, look.skins());
        } catch (RuntimeException e) {
            log.warn("Sky mesh {} of {} skipped: {}", unrealMesh.getFullName(), actor.getName(), e.toString());
            return result;
        }
        var placed = new Entity(mesh);
        placeActor(placed, actor);
        var baked = bakeModelMatrix(mesh, placed.getModelMatrix());
        for (var sourceSurface : baked.surfaces()) {
            if (sourceSurface.indexCount() < 3 || !look.own() && isSkySurfaceInvisible(sourceSurface)) {
                continue;
            }
            var surface = new Entity.Surface(
                    sourceSurface.type(),
                    sourceSurface.indexOffset(),
                    sourceSurface.indexCount(),
                    sourceSurface.material(),
                    sourceSurface.blendMode(),
                    sourceSurface.uvAnimation(),
                    sourceSurface.water(),
                    sourceSurface.materialName(),
                    sourceSurface.alphaRef(),
                    sourceSurface.usesVertexColor(),
                    false,
                    sourceSurface.modulateStaticLighting2x());
            var entity = new Entity(withSurfaces(baked, List.of(surface)));
            var name = shortName(surface.materialName());
            entity.setDebugName(name);
            entity.setMeshName(uPackage.getName() + "/" + actor.getName() + "/" + name);
            entity.setZoneRender(look.render());
            result.add(new Layer(name, -1, entity, !look.own()));
        }
        return result;
    }

    private @Nullable Celestial celestialOf(NCelestialActor actor) {
        for (var skin : actor.getSkins()) {
            if (skin instanceof UMaterial mat) {
                var tex = skyTexture(mat);
                if (tex != null) {
                    return new Celestial(tex, actor.getDrawScale(), actor.getRadius(), actor.getEnvType());
                }
            }
        }
        return null;
    }

    private @Nullable Texture skyTexture(UMaterial mat) {
        var material = loadMaterial(mat);
        return material == null ? null : material.getTexture();
    }

    private UMap buildMap(String name, UPackage uPackage) {
        var phaseStarted = System.nanoTime();
        dxtChains.prebuild(uPackage);
        recordLoadPhase(LoadPhase.DXT, System.nanoTime() - phaseStarted);
        phaseStarted = System.nanoTime();
        var groundless = !WorldTile.isMapName(uPackage.getName()) && !hasTerrain(uPackage);
        var terrain = groundless ? new UTerrainInfoActor(null) : loadTerrain(uPackage);
        var levelBrightness = loadLevelBrightness(uPackage);

        var position = groundless ? new Vec3(0f, 0f, 0f) : terrain.getPosition();
        var boundingBox = mapBounds(terrain, groundless);
        var mapEntities = new ArrayList<Entity>();
        var nearDecorations = new ArrayList<DecorationTemplate>();

        if (terrain.hasSurface()) {
            ensureSectorsLoaded(uPackage, terrain);
            loadTerrainEntities(terrain, levelBrightness, WorldTile.isMapName(uPackage.getName())).stream()
                    .filter(entity -> !(entity.getMesh() instanceof TerrainMesh mesh) || mesh.indices().length > 0)
                    .forEach(mapEntities::add);
            if (decorationsNearCamera) {
                for (var template : decorationTemplates(terrain, levelBrightness)) {
                    if (template.getFadeoutRadiusMax() > 0f) {
                        nearDecorations.add(template);
                    } else {
                        var entity = template.build(template.getSavedDensity(), template.getSavedGround());
                        if (entity != null) {
                            mapEntities.add(entity);
                        }
                    }
                }
            } else {
                mapEntities.addAll(loadDecorationEntities(terrain, levelBrightness));
            }
        }
        recordLoadPhase(LoadPhase.TERRAIN, System.nanoTime() - phaseStarted);

        phaseStarted = System.nanoTime();
        var allLights = loadLights(uPackage, levelBrightness, materialClient);
        var lights = new ArrayList<Light>(allLights.size());
        var animatedLights = new ArrayList<Light>();
        for (var light : allLights) {
            (light.isAnimated() ? animatedLights : lights).add(light);
        }
        var lightGrid = new LightGrid(lights);
        var interiorAmbient = loadInteriorAmbient(uPackage);
        var propZoneModel = loadPrimaryModel(uPackage);
        var propZoneFloors = propZoneModel != null ? buildPropZoneFloors(propZoneModel) : null;
        var actorLevelInfo = loadModelLevelInfo(uPackage, propZoneModel);
        var actorZoneLighting = propZoneModel != null ? buildActorZoneLighting(propZoneModel, actorLevelInfo) : null;
        var sunOccluders = SunVisibility.build(propZoneModel);
        var sunTerrain = sunTerrainField(terrain);
        recordLoadPhase(LoadPhase.LIGHTING, System.nanoTime() - phaseStarted);
        var meshIndex = new MeshPlaneIndex();
        var rootEnvironmentSources = new ArrayList<LightInfo>();
        phaseStarted = System.nanoTime();
        mapEntities.addAll(loadMeshActorEntities(
                uPackage,
                terrain,
                lightGrid,
                interiorAmbient,
                propZoneModel,
                propZoneFloors,
                actorZoneLighting,
                actorLevelInfo,
                sunOccluders,
                sunTerrain,
                levelBrightness,
                meshIndex,
                rootEnvironmentSources));
        var nativeLights =
                loadNativeLightScene(uPackage, propZoneModel, levelBrightness, materialClient, rootEnvironmentSources);
        meshIndex.freeze();
        recordLoadPhase(LoadPhase.STATIC_MESH, System.nanoTime() - phaseStarted);
        var reflectionBspModels = new ArrayList<ReflectionBspModel>();
        phaseStarted = System.nanoTime();
        var bspEntities = loadBspEntities(
                uPackage,
                boundingBox,
                meshIndex.isEmpty() ? null : meshIndex,
                csgArrayConcat,
                reflectionBspModels,
                null);
        mapEntities.addAll(bspEntities);
        recordLoadPhase(LoadPhase.BSP, System.nanoTime() - phaseStarted);
        phaseStarted = System.nanoTime();
        var waterVolumes = new ArrayList<WaterVolume>();
        loadWaterVolumes(uPackage, waterVolumes);
        var emitterVisibility = new HashMap<String, EmitterVisibility>();
        var sprites = loadSpriteEmitterDescriptors(uPackage, emitterVisibility);
        var localSky = loadLocalSky(uPackage, sprites);
        var map = new UMap(
                name,
                mapEntities,
                position,
                boundingBox,
                lights,
                animatedLights,
                sprites,
                loadMeshEmitterDescriptors(uPackage, emitterVisibility),
                loadBeamEmitterDescriptors(uPackage, emitterVisibility),
                reflectionBspModels,
                waterVolumes,
                loadL2FogInfos(uPackage),
                loadProjectorDescriptors(uPackage),
                MusicVolume.readAll(uPackage),
                loadAmbientSounds(uPackage, reflectionBspModels),
                nativeLights,
                localSky,
                loadMapPawns(uPackage),
                loadLightCoronas(uPackage),
                nearDecorations,
                loadSwings(uPackage),
                loadTurns(uPackage),
                loadAmbientVolumes(uPackage));
        recordLoadPhase(LoadPhase.EFFECTS, System.nanoTime() - phaseStarted);
        if (LightGrid.DIAGNOSTICS) {
            var diagnostic = lightGrid.getDiagnostics();
            log.info(
                    "Light grid diagnostic: map={} lights={} maxRadius={} selects={} "
                            + "candidates={} radiusHits={} squareRoots={} neighborhoods={} "
                            + "neighborhoodCandidates={} maxNeighborhood={}",
                    name,
                    diagnostic.lights(),
                    diagnostic.maxRadius(),
                    diagnostic.selects(),
                    diagnostic.candidates(),
                    diagnostic.radiusHits(),
                    diagnostic.squareRoots(),
                    diagnostic.neighborhoods(),
                    diagnostic.neighborhoodCandidates(),
                    diagnostic.maxNeighborhood());
        }
        return map;
    }

    private Optional<UPackage> loadMapPackage(int x, int y) {
        var packageName = getMapPackage(x + "_" + y);
        return packageLoader.loadPackageIfPresent(packageName);
    }

    private UTerrainInfoActor loadTerrain(UPackage uPackage) {
        var terrains = new ArrayList<UTerrainInfoActor>();
        uPackage.loadObjects("TerrainInfo", terrains);
        if (terrains.isEmpty()) {
            throw new IllegalStateException("No terrains in package: " + uPackage.getName());
        }
        terrains.sort(comparingInt((UTerrainInfoActor terrain) -> terrainPriority(terrain, uPackage.getName()))
                .reversed());
        var terrain = terrains.getFirst();
        if (terrains.size() > 1) {
            var selectedPriority = terrainPriority(terrain, uPackage.getName());
            var nextPriority = terrainPriority(terrains.get(1), uPackage.getName());
            if (selectedPriority == nextPriority) {
                log.warn(
                        "Multiple equally valid terrains in package {}: {}",
                        uPackage.getName(),
                        terrains.stream().map(candidate -> candidate.getName()).toList());
            } else {
                log.debug(
                        "Selected terrain {} from package {}; ignored {}",
                        terrain.getName(),
                        uPackage.getName(),
                        terrains.subList(1, terrains.size()).stream()
                                .map(candidate -> candidate.getName())
                                .toList());
            }
        }
        if (!terrain.hasHeightmap()) {
            log.warn(
                    "Terrain {} in package {} has no heightmap; the tile has no ground",
                    terrain.getName(),
                    uPackage.getName());
            return terrain;
        }
        UTexture heightmap;
        try {
            heightmap = terrain.getTerrainMap().get();
        } catch (UMissingObjectException missing) {
            var name = terrain.getTerrainMap().peekFullName();
            if (name == null) {
                throw new UMissingObjectException(
                        "Map " + uPackage.getName() + " needs a terrain heightmap that the client does not have");
            }
            throw new UMissingObjectException(
                    "Map %s needs the terrain heightmap %s, which the client does not have (package %s)"
                            .formatted(uPackage.getName(), name, name.substring(0, name.indexOf('.'))));
        }
        var mips = heightmap.getMips();
        if (mips == null || mips.length == 0) {
            throw new IllegalStateException("Can't load terrain heightmap in package: " + terrain.getName());
        }
        return terrain;
    }

    private @Nullable SideTerrain loadSideTerrain(int x, int y) {
        if (x < 0 || x > WorldDimensions.TILE_X_MAX || y < 0 || y > WorldDimensions.TILE_Y_MAX) {
            return null;
        }
        var uPackage = loadMapPackage(x, y);
        if (uPackage.isEmpty()) {
            return null;
        }

        try {
            var terrain = loadTerrain(uPackage.get());
            if (!terrain.hasSurface()) {
                return null;
            }
            try {
                ensureSectorsLoaded(uPackage.get(), terrain);
            } catch (RuntimeException e) {
                log.warn("Side terrain {}_{} sectors unusable, edge stitched from self: {}", x, y, e.toString());
                terrain.getSectors().clear();
            }
            return new SideTerrain(terrain, loadLevelBrightness(uPackage.get()));
        } catch (UMissingObjectException e) {
            log.warn("Skipping side terrain {}_{}: {}", x, y, e.toString());
            return null;
        }
    }

    private List<Entity> loadTerrainEntities(UTerrainInfoActor terrain, float levelBrightness, boolean stitch) {
        var entities = new ArrayList<Entity>();

        var terrainMap = terrain.getTerrainMap().get();
        var width = terrainMap.getUSize();
        var height = terrainMap.getVSize();

        var fullWidth = width + 1;
        var fullHeight = height + 1;

        var terrainVertexCapacity = fullWidth * fullHeight;
        var terrainTextureProjectors = terrainTextureProjectors(terrain);
        var steadyLighting = new TerrainSteadyLighting(terrain, levelBrightness, materialClient);
        var meshVertices = TerrainVertexBuffer.builder(
                terrainVertexCapacity, Math.multiplyExact(terrainTextureProjectors.length, 2));
        var meshIndices = new IntArrayList(width * height * 6);
        var shadowVariants = new FloatArrayList[SHADOW_VARIANT_COUNT];
        for (int v = 0; v < SHADOW_VARIANT_COUNT; v++) {
            shadowVariants[v] = new FloatArrayList(terrainVertexCapacity);
        }

        var sectors = terrain.getSectors();
        var heights = new int[fullWidth * fullHeight];
        {
            var position = terrain.getPosition();
            var scale = terrain.getScale();
            var positionX = position.getX();
            var positionY = position.getY();
            var positionZ = position.getZ();
            var scaleX = scale.getX();
            var scaleY = scale.getY();
            var scaleZ = scale.getZ();
            var heightmap = terrainMap.getMips()[0].getData();

            for (var y = 0; y < height; ++y) {
                for (var x = 0; x < width; ++x) {
                    var z = getHeight(heightmap, y * width + x);
                    addTerrainVertex(
                            meshVertices,
                            x * scaleX + positionX,
                            y * scaleY + positionY,
                            z * scaleZ + positionZ,
                            x,
                            y,
                            z,
                            width,
                            height,
                            steadyLighting.vertexNormal(x, y),
                            bakeShadowVariants(shadowVariants, x, y, sectors),
                            steadyLighting.rgbAt(x, y),
                            terrainTextureProjectors);
                    heights[y * fullWidth + x] = z;
                }
            }

            for (var y = 0; y < height - 1; ++y) {
                for (var x = 0; x < width - 1; ++x) {
                    var quadIndex = x + y * width;
                    if (!terrain.getQuadVisibilityBitmap().get(quadIndex)) {
                        continue;
                    }
                    if (!terrain.getEdgeTurnBitmap().get(quadIndex)) {
                        meshIndices.add((x + 0) + (y + 0) * width);
                        meshIndices.add((x + 1) + (y + 0) * width);
                        meshIndices.add((x + 1) + (y + 1) * width);

                        meshIndices.add((x + 0) + (y + 0) * width);
                        meshIndices.add((x + 1) + (y + 1) * width);
                        meshIndices.add((x + 0) + (y + 1) * width);
                    } else {
                        meshIndices.add((x + 0) + (y + 1) * width);
                        meshIndices.add((x + 0) + (y + 0) * width);
                        meshIndices.add((x + 1) + (y + 0) * width);

                        meshIndices.add((x + 0) + (y + 1) * width);
                        meshIndices.add((x + 1) + (y + 0) * width);
                        meshIndices.add((x + 1) + (y + 1) * width);
                    }
                }
            }
        }

        var southTerrain = !stitch ? null : loadSideTerrain(terrain.getMapX(), terrain.getMapY() + 1);
        if (southTerrain != null) {
            var south = southTerrain.terrain();
            var southSteady = new TerrainSteadyLighting(south, southTerrain.levelBrightness(), materialClient);
            var position = new Vec3(
                    terrain.getPosition().getX(),
                    terrain.getPosition().getY(),
                    south.getPosition().getZ());
            var scale = new Vec3(
                    terrain.getScale().getX(),
                    terrain.getScale().getY(),
                    south.getScale().getZ());
            var positionX = position.getX();
            var positionY = position.getY();
            var positionZ = position.getZ();
            var scaleX = scale.getX();
            var scaleY = scale.getY();
            var scaleZ = scale.getZ();
            var heightmap = south.getTerrainMap().get().getMips()[0].getData();
            var southSeam = new boolean[width - 1];
            for (var x = 0; x < width - 1; ++x) {
                southSeam[x] = isTerrainQuadVisible(terrain, x, height - 1)
                        && isTerrainQuadVisible(terrain, x, height - 2)
                        && isTerrainQuadVisible(south, x, 0);
            }
            for (var x = 0; x < width; ++x) {
                var z = getHeight(heightmap, x);
                addTerrainVertex(
                        meshVertices,
                        x * scaleX + positionX,
                        height * scaleY + positionY,
                        z * scaleZ + positionZ,
                        x,
                        height,
                        z,
                        width,
                        height,
                        southSteady.vertexNormal(x, 0),
                        bakeStitchVariants(shadowVariants, south, x, 0, sectors, x, height),
                        southSteady.rgbAt(x, 0),
                        terrainTextureProjectors);
                heights[height * fullWidth + x] = z;
                if (x != width - 1 && southSeam[x]) {
                    meshIndices.add((x + 0) + (height - 1) * width);
                    meshIndices.add((x + 1) + (height - 1) * width);
                    meshIndices.add(meshVertices.size() - 1);
                }
                if (x != 0 && southSeam[x - 1]) {
                    meshIndices.add((x + 0) + (height - 1) * width);
                    meshIndices.add(meshVertices.size() - 1);
                    meshIndices.add(meshVertices.size() - 2);
                }
            }
        }

        var eastTerrain = !stitch ? null : loadSideTerrain(terrain.getMapX() + 1, terrain.getMapY());
        if (eastTerrain != null) {
            var east = eastTerrain.terrain();
            var eastSteady = new TerrainSteadyLighting(east, eastTerrain.levelBrightness(), materialClient);
            var position = new Vec3(
                    terrain.getPosition().getX(),
                    terrain.getPosition().getY(),
                    east.getPosition().getZ());
            var scale = new Vec3(
                    terrain.getScale().getX(),
                    terrain.getScale().getY(),
                    east.getScale().getZ());
            var positionX = position.getX();
            var positionY = position.getY();
            var positionZ = position.getZ();
            var scaleX = scale.getX();
            var scaleY = scale.getY();
            var scaleZ = scale.getZ();
            var heightmap = east.getTerrainMap().get().getMips()[0].getData();
            var eastSeam = new boolean[height - 1];
            for (var y = 0; y < height - 1; ++y) {
                eastSeam[y] = isTerrainQuadVisible(terrain, width - 1, y)
                        && isTerrainQuadVisible(terrain, width - 2, y)
                        && isTerrainQuadVisible(east, 0, y);
            }

            for (var y = 0; y < height; ++y) {
                var z = getHeight(heightmap, y * width);
                addTerrainVertex(
                        meshVertices,
                        width * scaleX + positionX,
                        y * scaleY + positionY,
                        z * scaleZ + positionZ,
                        width,
                        y,
                        z,
                        width,
                        height,
                        eastSteady.vertexNormal(0, y),
                        bakeStitchVariants(shadowVariants, east, 0, y, sectors, width, y),
                        eastSteady.rgbAt(0, y),
                        terrainTextureProjectors);
                heights[y * fullWidth + width] = z;
                if (y != height - 1 && eastSeam[y]) {
                    meshIndices.add((width - 1) + (y + 0) * width);
                    meshIndices.add(meshVertices.size() - 1);
                    meshIndices.add((width - 1) + (y + 1) * width);
                }
                if (y != 0 && eastSeam[y - 1]) {
                    meshIndices.add((width - 1) + (y + 0) * width);
                    meshIndices.add(meshVertices.size() - 2);
                    meshIndices.add(meshVertices.size() - 1);
                }
            }
        }

        var southeastTerrain = !stitch ? null : loadSideTerrain(terrain.getMapX() + 1, terrain.getMapY() + 1);
        if (southeastTerrain != null) {
            var southeast = southeastTerrain.terrain();
            var southeastSteady =
                    new TerrainSteadyLighting(southeast, southeastTerrain.levelBrightness(), materialClient);
            var position = new Vec3(
                    terrain.getPosition().getX(),
                    terrain.getPosition().getY(),
                    southeast.getPosition().getZ());
            var scale = new Vec3(
                    terrain.getScale().getX(),
                    terrain.getScale().getY(),
                    southeast.getScale().getZ());
            var positionX = position.getX();
            var positionY = position.getY();
            var positionZ = position.getZ();
            var scaleX = scale.getX();
            var scaleY = scale.getY();
            var scaleZ = scale.getZ();
            var heightmap = southeast.getTerrainMap().get().getMips()[0].getData();
            var z = getHeight(heightmap, 0);
            addTerrainVertex(
                    meshVertices,
                    width * scaleX + positionX,
                    height * scaleY + positionY,
                    z * scaleZ + positionZ,
                    width,
                    height,
                    z,
                    width,
                    height,
                    southeastSteady.vertexNormal(0, 0),
                    bakeStitchVariants(shadowVariants, southeast, 0, 0, sectors, width, height),
                    southeastSteady.rgbAt(0, 0),
                    terrainTextureProjectors);
            heights[height * fullWidth + width] = z;
            var southTile = southTerrain == null ? null : southTerrain.terrain();
            var eastTile = eastTerrain == null ? null : eastTerrain.terrain();
            if (isTerrainQuadVisible(terrain, width - 1, height - 1)
                    && isTerrainQuadVisible(terrain, width - 2, height - 2)
                    && isTerrainQuadVisible(southTile, width - 2, 0)
                    && isTerrainQuadVisible(eastTile, 0, height - 2)
                    && isTerrainQuadVisible(southeast, 0, 0)) {
                meshIndices.add((width - 1) + (height - 1) * width);
                meshIndices.add(meshVertices.size() - 2);
                meshIndices.add(meshVertices.size() - 1);
                meshIndices.add((width - 1) + (height - 1) * width);
                meshIndices.add(meshVertices.size() - 1);
                meshIndices.add(meshVertices.size() - (width + 2));
            }
        }
        var multiTextureEntries = new ArrayList<MultiTextureEntry>();
        for (int i = 0; i < terrain.getLayers().size(); i++) {
            var layer = terrain.getLayers().get(i);
            var layerMaterial = layer.getTexture().get();
            var maybeTexture = loadRenderedMaterial(layerMaterial);
            var maybeAlphaMap = loadTexture(layer.getAlphaMap().get());
            if (maybeTexture != null && maybeAlphaMap != null) {
                var name = layerMaterial == null ? null : layerMaterial.getSourcePath();
                multiTextureEntries.add(new MultiTextureEntry(
                        maybeTexture,
                        maybeAlphaMap,
                        name == null ? "" : name,
                        terrainZoneRender(terrain, layer.getZoneRenderStates())));
            } else {
                multiTextureEntries.add(EMPTY_MULTI_TEXTURE_ENTRY);
            }
        }

        var multiTexture = new MultiTexture(multiTextureEntries, packTerrainAlphaGroups(multiTextureEntries));

        var surface = new Entity.Surface(
                SurfaceType.TERRAIN.getId(), 0, meshIndices.size(), new MultiTextureEntityMaterial(multiTexture));

        var position = terrain.getPosition();
        var scale = terrain.getScale();
        var meshBoundingBox = new Box(
                terrain.getBoundingBox().min().times(scale).plus(position),
                terrain.getBoundingBox().max().times(scale).plus(position));

        var lightVariants = new float[SHADOW_VARIANT_COUNT][];
        for (int v = 0; v < SHADOW_VARIANT_COUNT; v++) {
            lightVariants[v] = shadowVariants[v].toArray();
        }

        setTerrainLayerRows(meshVertices, terrainTextureProjectors, position, scale);

        var terrainEntity = new Entity(
                new TerrainMesh(meshVertices.build(), meshIndices.toArray(), surface, meshBoundingBox, lightVariants));
        terrainEntity.setDebugName("terrain");
        terrainEntity.setSourceActor(terrain.getSourcePath());
        terrainEntity.setSourceAsset(terrain.getTerrainMap().get().getSourcePath());
        entities.add(terrainEntity);

        var bbEntity = new Entity(boundingBoxMesh(SurfaceType.TERRAIN.getId(), meshBoundingBox));
        bbEntity.setWireframe(true);
        entities.add(bbEntity);

        return entities;
    }

    private List<Entity> loadDecorationEntities(UTerrainInfoActor terrain, float levelBrightness) {
        var entities = new ArrayList<Entity>();
        for (var template : decorationTemplates(terrain, levelBrightness)) {
            var entity = template.build(template.getSavedDensity(), template.getSavedGround());
            if (entity != null) {
                entities.add(entity);
                log.debug(
                        "Terrain decoration {} layer {}: {} instances of {}",
                        terrain.getName(),
                        template.getLayerIndex(),
                        ((EntityMesh) entity.getMesh()).instanceMatrices().size(),
                        template.getMeshName());
            }
        }
        return entities;
    }

    private List<DecorationTemplate> decorationTemplates(UTerrainInfoActor terrain, float levelBrightness) {
        var templates = new ArrayList<DecorationTemplate>();
        var ground = DecorationGround.saved(terrain);
        if (ground == null) {
            return templates;
        }
        DecorationLight light = null;
        for (int layerIndex = 0; layerIndex < terrain.getDecoLayers().size(); layerIndex++) {
            var layer = terrain.getDecoLayers().get(layerIndex);
            var unrealMesh = layer.getStaticMesh().get();
            var densityTexture = layer.getDensityMap().get();
            if (layer.getShowOnTerrain() == 0 || unrealMesh == null || densityTexture == null) {
                continue;
            }
            var density = loadDecorationDensity(densityTexture);
            if (density == null) {
                log.warn(
                        "Unsupported decoration density map {} ({})",
                        densityTexture.getFullName(),
                        densityTexture.getFormat());
                continue;
            }
            var meshName = meshAssetPath(unrealMesh);
            var sourceMesh =
                    meshCache.computeIfAbsent(meshName + "#terrain-deco", _ -> buildStaticEntityMesh(unrealMesh, null));
            var densityMap = densityTexture.getSourcePath();
            if (light == null) {
                light = new DecorationLight(terrain, levelBrightness, materialClient);
            }
            templates.add(new DecorationTemplate(
                    terrain,
                    layer,
                    layerIndex,
                    meshName,
                    densityMap == null ? densityTexture.getFullName() : densityMap,
                    sourceMesh,
                    density.toQuads(ground.width(), ground.height()),
                    ground,
                    light,
                    terrainZoneRender(terrain, layer.getZoneRenderStates())));
        }
        return templates;
    }

    private List<Entity> meshDecorationEntities(StaticMeshActor actor, Entity meshEntity) {
        var layers = actor.getDecoLayers();
        var data = actor.getDecoLayerData().isEmpty() ? actor.getDecoLayerDataForQuad() : actor.getDecoLayerData();
        if (layers.isEmpty() || data.isEmpty()) {
            return List.of();
        }
        var ambient = meshEntity.getStaticMeshAmbient();
        var essence = hasEssenceZoneStates();
        var result = new ArrayList<Entity>();
        for (var index = 0; index < Math.min(layers.size(), data.size()); index++) {
            var layer = layers.get(index);
            var instances = data.get(index);
            var unrealMesh = layer.getStaticMesh().get();
            if (unrealMesh == null || instances.size() == 0 || !(layer.getFadeoutRadiusMax() > 0f)) {
                continue;
            }
            var layerMask = essence ? UActor.zoneStateMask(layer.getZoneRenderStates()) : 0;
            var render = MeshDecoration.render(
                    actor.getZoneRenderMask(),
                    layerMask,
                    zoneStart(actor),
                    essence,
                    actor.getRegionZone() != null,
                    zoneMap(actor));
            if (render == null) {
                continue;
            }
            var meshName = meshAssetPath(unrealMesh);
            var sourceMesh =
                    meshCache.computeIfAbsent(meshName + "#terrain-deco", _ -> buildStaticEntityMesh(unrealMesh, null));
            var entity = meshDecorationEntity(actor, layer, instances, sourceMesh, ambient != null);
            if (entity == null) {
                continue;
            }
            entity.setDebugName(meshName + " [mesh decoration]");
            entity.setSourceAsset(meshName);
            entity.setZoneRender(render);
            entity.setMeshDecoration(new MeshDecoration(index, layerMask == 0 ? null : render));
            entity.setStaticMeshAmbient(ambient);
            entity.setStaticMeshAmbientIntensity(1f);
            result.add(entity);
        }
        return result;
    }

    public record DecorationDensity(int width, int height, byte[] values) {
        public DecorationDensity {
            if (width < 1 || height < 1 || values.length < width * height) {
                throw new IllegalArgumentException("Decoration density does not match its size");
            }
        }

        public DecorationDensity toQuads(int samplesWide, int samplesHigh) {
            if (width == samplesWide && height == samplesHigh) {
                return this;
            }
            var quads = new byte[samplesWide * samplesHigh];
            for (var y = 0; y < samplesHigh; y++) {
                for (var x = 0; x < samplesWide; x++) {
                    quads[y * samplesWide + x] = (byte) quadValue(x, y, samplesWide, samplesHigh);
                }
            }
            return new DecorationDensity(samplesWide, samplesHigh, quads);
        }

        public int quadValue(int x, int y, int samplesWide, int samplesHigh) {
            var ratioX = width / (float) samplesWide;
            var ratioY = height / (float) samplesHigh;
            var blockWidth = (int) (ratioX + .5f);
            var blockHeight = (int) (ratioY + .5f);
            var value = 0;
            var zero = false;
            var full = false;
            for (var j = 0; j < blockHeight; j++) {
                for (var i = 0; i < blockWidth; i++) {
                    var texelX = Math.min(width - 1, (int) (x * ratioX + i));
                    var texelY = Math.min(height - 1, (int) (y * ratioY + j));
                    value = values[texelY * width + texelX] & 255;
                    if (value == 0) {
                        if (full) {
                            return 100;
                        }
                        zero = true;
                    } else if (value == 255) {
                        if (zero) {
                            return 100;
                        }
                        full = true;
                    } else {
                        return value;
                    }
                }
            }
            return value;
        }
    }

    record DecorationScatter(List<Mat4> matrices, List<Vec4> lighting, List<Vec3> steadyLighting) {}

    public record DecorationGround(int width, int height, short[] samples, BitSet visible, BitSet turned) {
        public DecorationGround {
            if (width < 2 || height < 2 || samples.length < width * height) {
                throw new IllegalArgumentException("Decoration ground does not match its size");
            }
        }

        static @Nullable DecorationGround saved(UTerrainInfoActor terrain) {
            var terrainMap = terrain.getTerrainMap().get();
            var heightmap = terrainMap.getMips()[0].getData();
            var width = terrainMap.getUSize();
            var height = terrainMap.getVSize();
            if (width < 2 || height < 2 || heightmap.length < width * height * 2) {
                return null;
            }
            var samples = new short[width * height];
            for (var i = 0; i < samples.length; i++) {
                samples[i] = (short) getHeight(heightmap, i);
            }
            var visible = new BitSet(width * height);
            var turned = new BitSet(width * height);
            for (var i = 0; i < width * height; i++) {
                if (terrain.getQuadVisibilityBitmap().get(i)) {
                    visible.set(i);
                }
                if (terrain.getEdgeTurnBitmap().get(i)) {
                    turned.set(i);
                }
            }
            return new DecorationGround(width, height, samples, visible, turned);
        }
    }

    public static final class DecorationTemplate {
        private static final String MARK = "$deco$";

        private final String owner;
        private final int layerIndex;
        private final String meshName;
        private final String densityMap;
        private final EntityMesh sourceMesh;
        private final DecorationDensity savedDensity;
        private final DecorationGround savedGround;
        private final DecorationLight light;
        private final Vec3 position;
        private final Vec3 scale;
        private final float offset;
        private final List<DecorationSector> quadSectors;
        private final boolean inverted;
        private final int zone;
        private final int maxPerQuad;
        private final int seed;
        private final boolean showOnInvisibleTerrain;
        private final boolean alignToTerrain;
        private final boolean randomYaw;
        private final boolean disregardTerrainLighting;
        private final float densityMultiplierMin;
        private final float densityMultiplierMax;
        private final float fadeoutRadiusMin;
        private final float fadeoutRadiusMax;
        private final Vec3 scaleMin;
        private final Vec3 scaleMax;
        private final ZoneRender zoneRender;

        private DecorationTemplate(
                UTerrainInfoActor terrain,
                UDecorationLayer layer,
                int layerIndex,
                String meshName,
                String densityMap,
                EntityMesh sourceMesh,
                DecorationDensity savedDensity,
                DecorationGround savedGround,
                DecorationLight light,
                ZoneRender zoneRender) {
            owner = terrain.getSourcePath();
            this.layerIndex = layerIndex;
            this.meshName = meshName;
            this.densityMap = densityMap;
            this.sourceMesh = sourceMesh;
            this.savedDensity = savedDensity;
            this.savedGround = savedGround;
            this.light = light;
            position = terrain.getPosition();
            scale = terrain.getScale();
            offset = terrain.getDecoLayerOffset();
            quadSectors = decorationSectors(terrain.getSectors(), savedGround.width(), savedGround.height());
            inverted = terrain.isInverted();
            zone = terrain.getRegionZoneNumber();
            maxPerQuad = layer.getMaxPerQuad();
            seed = layer.getSeed();
            showOnInvisibleTerrain = layer.getShowOnInvisibleTerrain() != 0;
            alignToTerrain = layer.getAlignToTerrain() != 0;
            randomYaw = layer.getRandomYaw() != 0;
            disregardTerrainLighting = layer.getDisregardTerrainLighting() != 0;
            densityMultiplierMin = layer.getDensityMultiplierMin();
            densityMultiplierMax = layer.getDensityMultiplierMax();
            fadeoutRadiusMin = layer.getFadeoutRadiusMin();
            fadeoutRadiusMax = layer.getFadeoutRadiusMax();
            scaleMin = layer.getScaleMin();
            scaleMax = layer.getScaleMax();
            this.zoneRender = zoneRender;
        }

        private DecorationTemplate(DecorationTemplate source, EntityMesh sourceMesh) {
            owner = source.owner;
            layerIndex = source.layerIndex;
            meshName = source.meshName;
            densityMap = source.densityMap;
            this.sourceMesh = sourceMesh;
            savedDensity = source.savedDensity;
            savedGround = source.savedGround;
            light = source.light;
            position = source.position;
            scale = source.scale;
            offset = source.offset;
            quadSectors = source.quadSectors;
            inverted = source.inverted;
            zone = source.zone;
            maxPerQuad = source.maxPerQuad;
            seed = source.seed;
            showOnInvisibleTerrain = source.showOnInvisibleTerrain;
            alignToTerrain = source.alignToTerrain;
            randomYaw = source.randomYaw;
            disregardTerrainLighting = source.disregardTerrainLighting;
            densityMultiplierMin = source.densityMultiplierMin;
            densityMultiplierMax = source.densityMultiplierMax;
            fadeoutRadiusMin = source.fadeoutRadiusMin;
            fadeoutRadiusMax = source.fadeoutRadiusMax;
            scaleMin = source.scaleMin;
            scaleMax = source.scaleMax;
            zoneRender = source.zoneRender;
        }

        public static String actorOf(String owner, int layerIndex) {
            return owner + MARK + layerIndex;
        }

        public static boolean isDecorationActor(@Nullable String actor) {
            return actor != null && actor.contains(MARK);
        }

        public int getLayerIndex() {
            return layerIndex;
        }

        public String getOwner() {
            return owner;
        }

        public String getActor() {
            return actorOf(owner, layerIndex);
        }

        public String getMeshName() {
            return meshName;
        }

        public EntityMesh getSourceMesh() {
            return sourceMesh;
        }

        public DecorationTemplate withSourceMesh(EntityMesh mesh) {
            return mesh == sourceMesh ? this : new DecorationTemplate(this, mesh);
        }

        public String getDensityMap() {
            return densityMap;
        }

        public DecorationDensity getSavedDensity() {
            return savedDensity;
        }

        public DecorationGround getSavedGround() {
            return savedGround;
        }

        public int getMaxPerQuad() {
            return maxPerQuad;
        }

        public float getFadeoutRadiusMax() {
            return fadeoutRadiusMax;
        }

        public ZoneRender getZoneRender() {
            return zoneRender;
        }

        public float getMeshExtent() {
            var box = sourceMesh.boundingBox();
            var largest = 0f;
            for (var axis = 0; axis < 3; axis++) {
                largest =
                        Math.max(largest, Math.max(Math.abs(axisOf(scaleMin, axis)), Math.abs(axisOf(scaleMax, axis))));
            }
            var extent = box.getMax().minus(box.getMin()).length() / 2f * largest + Math.abs(offset);
            return Float.isFinite(extent) ? extent : 0f;
        }

        public int getSectorCount() {
            return quadSectors.size();
        }

        public int getSectorOffsetX(int sector) {
            return quadSectors.get(sector).offsetX();
        }

        public int getSectorOffsetY(int sector) {
            return quadSectors.get(sector).offsetY();
        }

        public int getZone() {
            return zone;
        }

        public boolean isTerrainLit() {
            return !disregardTerrainLighting;
        }

        public Bounds3f getSectorBounds(int sector, DecorationGround ground) {
            var quads = quadSectors.get(sector);
            var minZ = Integer.MAX_VALUE;
            var maxZ = Integer.MIN_VALUE;
            for (var j = 0; j <= quads.quadsY(); j++) {
                var y = Math.clamp(quads.offsetY() + j, 0, ground.height() - 1);
                for (var i = 0; i <= quads.quadsX(); i++) {
                    var x = Math.clamp(quads.offsetX() + i, 0, ground.width() - 1);
                    var z = Short.toUnsignedInt(ground.samples()[y * ground.width() + x]);
                    minZ = Math.min(minZ, z);
                    maxZ = Math.max(maxZ, z);
                }
            }
            var x0 = position.getX() + quads.offsetX() * scale.getX();
            var x1 = position.getX() + (quads.offsetX() + quads.quadsX()) * scale.getX();
            var y0 = position.getY() + quads.offsetY() * scale.getY();
            var y1 = position.getY() + (quads.offsetY() + quads.quadsY()) * scale.getY();
            var z0 = position.getZ() + minZ * scale.getZ();
            var z1 = position.getZ() + maxZ * scale.getZ();
            return new Bounds3f(
                    Math.min(x0, x1),
                    Math.min(y0, y1),
                    Math.min(z0, z1),
                    Math.max(x0, x1),
                    Math.max(y0, y1),
                    Math.max(z0, z1));
        }

        public long computeSectorHash(int sector, DecorationDensity density, DecorationGround ground) {
            var quads = quadSectors.get(sector);
            var width = ground.width();
            var height = ground.height();
            var hash = 0x9e3779b97f4a7c15L ^ sector;
            for (var j = 0; j <= quads.quadsY(); j++) {
                for (var i = 0; i <= quads.quadsX(); i++) {
                    var x = quads.offsetX() + i;
                    var y = quads.offsetY() + j;
                    if (x < 0 || y < 0 || x >= width || y >= height) {
                        continue;
                    }
                    var index = y * width + x;
                    var value = (long) Short.toUnsignedInt(ground.samples()[index]) << 16;
                    if (i < quads.quadsX() && j < quads.quadsY() && x < width - 1 && y < height - 1) {
                        value |= (long) density.quadValue(x, y, width, height) << 2;
                        value |= ground.visible().get(index) ? 2 : 0;
                        value |= ground.turned().get(index) ? 1 : 0;
                    }
                    hash = (hash ^ value) * 0x100000001b3L;
                    hash ^= hash >>> 29;
                }
            }
            return hash;
        }

        public @Nullable Entity build(DecorationDensity density, DecorationGround ground) {
            return build(density, ground, null);
        }

        public @Nullable Entity build(DecorationDensity density, DecorationGround ground, int @Nullable [] sectors) {
            var scatter = decorationScatter(this, density, ground, sectors);
            var matrices = scatter.matrices();
            if (matrices.isEmpty()) {
                return null;
            }
            var union = new Box();
            for (var matrix : matrices) {
                var placed = new Box(sourceMesh.boundingBox(), matrix);
                union.add(placed.getMin());
                union.add(placed.getMax());
            }
            var scatteredMesh = new EntityMesh(
                    sourceMesh.vertices(),
                    sourceMesh.indices(),
                    sourceMesh.surfaces(),
                    matrices,
                    union,
                    0f,
                    0f,
                    sourceMesh.billboard(),
                    sourceMesh.staticMeshInstanceLighting(),
                    fadeoutRadiusMin,
                    fadeoutRadiusMax,
                    scatter.lighting());
            var entity = new Entity(scatteredMesh);
            entity.setDebugName(meshName + " [terrain decoration]");
            entity.setSourceAsset(meshName);
            entity.setSourceActor(getActor());
            entity.setSunAffected(!disregardTerrainLighting);
            entity.setAmbientOnlyLighting(!disregardTerrainLighting);
            entity.setFullbrightDecoration(disregardTerrainLighting);
            entity.setTerrainDecorationSteadyLighting(scatter.steadyLighting());
            entity.setTerrainDecorationZone(zone);
            entity.setZoneRender(zoneRender);
            return entity;
        }

        public int[] countPerQuad(DecorationDensity density, DecorationGround ground) {
            var counts = new int[ground.width() * ground.height()];
            placeDecorations(
                    this,
                    density,
                    ground,
                    null,
                    (x, y, location, rotation, meshScale) -> counts[y * ground.width() + x]++);
            return counts;
        }

        private static float axisOf(Vec3 vector, int axis) {
            return axis == 0 ? vector.getX() : axis == 1 ? vector.getY() : vector.getZ();
        }
    }

    static final class DecorationLight {
        private final byte[][][] lightMaps;
        private final int[] sectorX;
        private final int[] sectorY;
        private final TerrainSteadyLighting steady;

        private DecorationLight(UTerrainInfoActor terrain, float levelBrightness, MaterialClient client) {
            var sectors = terrain.getSectors();
            var count = sectors.size() == 256 ? 256 : 0;
            lightMaps = new byte[count][][];
            sectorX = new int[count];
            sectorY = new int[count];
            for (var index = 0; index < count; index++) {
                var sector = sectors.get(index);
                var maps = new byte[sector.getLightMapCount()][];
                for (var variant = 0; variant < maps.length; variant++) {
                    maps[variant] = sector.getLightMap(variant);
                }
                lightMaps[index] = maps;
                sectorX[index] = sector.getSectorX();
                sectorY[index] = sector.getSectorY();
            }
            steady = new TerrainSteadyLighting(terrain, levelBrightness, client);
            steady.resolveAll();
        }

        private Vec4 packedLight(int x, int y, float marker) {
            var samples = new int[SHADOW_VARIANT_COUNT];
            for (int variant = 0; variant < SHADOW_VARIANT_COUNT; variant++) {
                samples[variant] = Math.round(255f * lightMapValue(x, y, variant));
            }
            return new Vec4(
                    packLightBytes(samples, 0, 3),
                    packLightBytes(samples, 3, 3),
                    packLightBytes(samples, 6, 2),
                    marker);
        }

        private boolean hasShadowMaps() {
            return lightMaps.length == 256;
        }

        private float lightMapValue(int x, int y, int variant) {
            var dx = x == 256 ? 15 : x / 16;
            var dy = y == 256 ? 15 : y / 16;
            var index = dy * 16 + dx;
            if (sectorX[index] != dx || sectorY[index] != dy) {
                throw new RuntimeException(
                        "Sector mismatch: " + dx + " " + dy + " " + sectorX[index] + " " + sectorY[index]);
            }
            var maps = lightMaps[index];
            var lightMap = variant >= 0 && variant < maps.length ? maps[variant] : maps[0];
            if (lightMap.length == 0) {
                return 1f;
            }
            return (lightMap[(y - dy * 16) * 17 + x - dx * 16] & 0xFF) / 255f;
        }
    }

    private List<Entity> loadMeshActorEntities(
            UPackage uPackage,
            UTerrainInfoActor terrain,
            @Nullable LightGrid lightGrid,
            @Nullable Vec3 interiorAmbient,
            @Nullable UModel propZoneModel,
            @Nullable Vec3[] propZoneFloors,
            @Nullable ActorZoneLighting[] actorZoneLighting,
            @Nullable ZoneInfoActor actorLevelInfo,
            @Nullable Occluders sunOccluders,
            @Nullable TerrainField sunTerrain,
            float levelBrightness,
            MeshPlaneIndex meshIndex,
            List<LightInfo> rootEnvironmentSources) {
        return loadMeshActorEntities(
                uPackage,
                terrain,
                lightGrid,
                interiorAmbient,
                propZoneModel,
                propZoneFloors,
                actorZoneLighting,
                actorLevelInfo,
                sunOccluders,
                sunTerrain,
                levelBrightness,
                meshIndex,
                rootEnvironmentSources,
                null);
    }

    private List<Entity> loadMeshActorEntities(
            UPackage uPackage,
            UTerrainInfoActor terrain,
            @Nullable LightGrid lightGrid,
            @Nullable Vec3 interiorAmbient,
            @Nullable UModel propZoneModel,
            @Nullable Vec3[] propZoneFloors,
            @Nullable ActorZoneLighting[] actorZoneLighting,
            @Nullable ZoneInfoActor actorLevelInfo,
            @Nullable Occluders sunOccluders,
            @Nullable TerrainField sunTerrain,
            float levelBrightness,
            MeshPlaneIndex meshIndex,
            List<LightInfo> rootEnvironmentSources,
            @Nullable Set<String> actorPaths) {
        loadSwings(uPackage);
        linkMovementCarriers(uPackage);
        var entities = new ArrayList<Entity>();
        var merger = new OneOffMerger();
        var sectors = terrain.getSectors();
        var lightPosRadius = new float[LightGrid.MAX_LIGHTS * 4];
        var lightColor = new float[LightGrid.MAX_LIGHTS * 4];
        var lightIdx = new int[LightGrid.MAX_LIGHTS];
        var spatialLightScratch = new SpatialLightScratch();
        var meshActors = new ArrayList<StaticMeshActor>();
        uPackage.loadObjects("StaticMeshActor", meshActors);
        uPackage.loadObjects("MovableStaticMeshActor", meshActors);
        uPackage.loadObjects("L2MovableStaticMeshActor", meshActors);
        uPackage.loadObjects("Mover", meshActors);
        var levels = new ArrayList<ULevel>();
        uPackage.loadObjects("Level", levels);
        if (!levels.isEmpty()) {
            var registered = new HashSet<String>();
            for (var level : levels) {
                for (var reference : level.getActors()) {
                    var path = reference.peekExportSourcePath();
                    if (path != null) {
                        registered.add(path);
                    }
                }
            }
            meshActors.removeIf(actor -> !registered.contains(actor.getSourcePath()));
        }
        for (var actor : meshActors) {
            linkTurn(actor);
        }
        var actorAmbientNodeStack = propZoneModel != null ? new int[propZoneModel.getNodes().length] : null;

        var occluderStarted = System.nanoTime();
        var meshOccluders = buildMeshSunOccluders(meshActors);
        var occluderNanos = Math.max(0L, System.nanoTime() - occluderStarted);
        var meshIndexNanos = 0L;
        var meshActorCount = 0L;
        var meshActorVertices = 0L;
        var lightStreamActors = 0L;
        var lightStreamVertices = 0L;
        var spatialLightActors = 0L;
        var exactLightActors = 0L;
        var lodActors = 0L;
        var lod2Actors = 0L;
        var lod0Vertices = 0L;
        var lod1Vertices = 0L;
        var lod2Vertices = 0L;
        var missingMeshActors = 0;
        String firstMissingMeshActor = null;

        for (int actorIdx = 0; actorIdx < meshActors.size(); actorIdx++) {
            var actor = meshActors.get(actorIdx);
            if (actorPaths != null && !actorPaths.contains(actor.getSourcePath())) {
                continue;
            }
            if (actor.isDeleteMe() || actor.isHidden()) {
                continue;
            }

            var maybeStaticMesh = actor.getStaticMesh();
            if (maybeStaticMesh.get() == null) {
                missingMeshActors++;
                if (firstMissingMeshActor == null) {
                    firstMissingMeshActor = actor.getFullName();
                }
                continue;
            }
            var unrealMesh = maybeStaticMesh.get();
            var authoredLods = staticMeshLodChain(unrealMesh);
            var nativeTangentLifecycle =
                    unrealMesh.isUseNormalMap() || authoredLods != null && authoredLods.nativeSelection();
            var boundingBox = unrealMesh.getBoundingBox().toBox();
            var meshName = meshAssetPath(unrealMesh);
            var cachedBbMesh = bbMeshCache.computeIfAbsent(
                    meshName, _ -> boundingBoxMesh(SurfaceType.STATIC_MESH.getId(), boundingBox));
            var actorCacheKey = staticMeshActorCacheKey(unrealMesh, actor);
            var cachedMesh = getStaticMeshActorMesh(unrealMesh, actor);
            var authoredStaticMeshInstance = actor.getStaticMeshInstance();
            if (authoredStaticMeshInstance != null && authoredStaticMeshInstance.getEnvironmentLights().length != 0) {
                rootEnvironmentSources.add(authoredStaticMeshInstance.getEnvironmentLights()[0]);
            }
            var staticMeshInstance = isStaticMeshInstanceAcceptedByClient(
                            authoredStaticMeshInstance, cachedMesh.vertices().size())
                    ? authoredStaticMeshInstance
                    : null;
            var looks = zoneLooksOf(actor);
            var actorMesh = buildForMapActor(() -> applyStaticMeshActorSkins(
                    cachedMesh,
                    unrealMesh,
                    looks.isEmpty() ? actor.getSkins() : looks.getFirst().skins()));
            var nativeLightingInputs = NativeStaticMeshLightingInputs.capture(
                    0, unrealMesh, staticMeshInstance, authoredStaticMeshInstance, true);
            var beastLightmaps =
                    nativeLightingInputs.hasBeastUv() ? beastLightmapTextures(staticMeshInstance) : List.<Texture>of();
            if (!beastLightmaps.isEmpty()) {
                actorMesh = withBeastLightmapSurfaces(actorMesh);
            }
            var actorMeshName = actorMeshName(
                    actorMesh, cachedMesh, actorCacheKey, meshName, actor, looks.isEmpty() ? null : looks.getFirst());
            var rejectedStaticMeshInstance = authoredStaticMeshInstance != null && staticMeshInstance == null;
            var environmentVisibilityMasks = environmentVisibilityMasks(
                    staticMeshInstance, cachedMesh.vertices().size());
            var vertexCount = cachedMesh.vertices().size();
            meshActorCount = Math.incrementExact(meshActorCount);
            meshActorVertices = Math.addExact(meshActorVertices, vertexCount);
            if (authoredLods != null) {
                lodActors = Math.incrementExact(lodActors);
                lod0Vertices = Math.addExact(lod0Vertices, vertexCount);
                if (authoredLods.lod2() != null) {
                    lod2Actors = Math.incrementExact(lod2Actors);
                }
            }
            var hasInstanceColor = hasValidInstanceColorStream(staticMeshInstance, vertexCount);
            var hasEnvironmentLighting = staticMeshInstance != null
                    && hasValidLightFlags(staticMeshInstance.getEnvironmentLights(), vertexCount);
            var hasInstanceLighting = hasInstanceColor
                    || staticMeshInstance != null
                            && (hasValidLightFlags(staticMeshInstance.getSceneLights(), vertexCount)
                                    || hasEnvironmentLighting);
            var foliage = cachedMesh.swayFrequency() > 0f;
            var actorZoneFloor = interiorZoneFloor(propZoneModel, propZoneFloors, actor.getPosition());
            var clientActorLighting = staticMeshActorLighting(
                    propZoneModel,
                    actorZoneLighting,
                    actor,
                    boundingBox,
                    cachedMesh,
                    actorAmbientNodeStack,
                    actorLevelInfo);
            var clientActorAmbient = clientActorLighting.ambient();
            var effectiveSunAffected = isStaticMeshSunAffected(
                    clientActorLighting.sunAffected(),
                    actor.isSunAffect() && !BspLeafSelection.hasActorFilter(actor),
                    hasEnvironmentLighting);
            var approximatePropLight = propInstanceLight(
                    actor,
                    terrain,
                    sectors,
                    lightGrid,
                    interiorAmbient,
                    lightPosRadius,
                    lightColor,
                    lightIdx,
                    propZoneModel,
                    propZoneFloors,
                    sunOccluders,
                    meshOccluders,
                    sunTerrain,
                    actorIdx,
                    foliage);

            var spatialLighting =
                    bakeSpatialPropLight(cachedMesh, actor, lightGrid, lightColor, lightIdx, spatialLightScratch);
            var hardwareSun = rejectedStaticMeshInstance
                    ? rejectedInstanceHardwareSun(
                            authoredStaticMeshInstance, levelBrightness, materialClient, actor.getLightIntensity())
                    : null;
            if (spatialLighting != null) {
                spatialLightActors = Math.incrementExact(spatialLightActors);
            }
            var worldUnique = oneOffUsage != null && oneOffUsage.get(meshName.toLowerCase(Locale.ROOT)) == 1;
            var instanceZoneFloor = hasInstanceLighting
                            && staticMeshInstance != null
                            && hasValidLightFlags(
                                    staticMeshInstance.getSceneLights(),
                                    cachedMesh.vertices().size())
                    ? actorZoneFloor
                    : null;
            var exactInterior = instanceZoneFloor != null
                    && (cachedMesh.vertices().size() >= EXACT_INTERIOR_MESH_MIN_VERTICES
                            || maxComponent(approximatePropLight) >= EXACT_INTERIOR_ORIGIN_SATURATION);
            var exactInstanceLighting = hasInstanceLighting;
            var actorLighting = exactInstanceLighting
                    ? applyStaticMeshInstanceLighting(
                            cachedMesh,
                            unrealMesh,
                            actor,
                            staticMeshInstance,
                            spatialLighting,
                            isStaticMeshEnvironmentStreamEnabled(staticMeshInstance, propZoneModel),
                            materialClient)
                    : spatialLighting;
            if (exactInstanceLighting || actorLighting != null && actorLighting.nativeSceneColor()) {
                exactLightActors = Math.incrementExact(exactLightActors);
            }
            if (actorLighting != null) {
                lightStreamActors = Math.incrementExact(lightStreamActors);
                lightStreamVertices = Math.addExact(lightStreamVertices, actorLighting.vertexCount());
            }
            Vec3 propLight;
            if (exactInstanceLighting || actorLighting != null && actorLighting.nativeSceneColor()) {
                propLight = clientActorAmbient;
            } else {
                propLight = approximatePropLight;
            }
            var lookEntities = new ArrayList<Entity>(Math.max(1, looks.size()));
            for (var look = 0; look < Math.max(1, looks.size()); look++) {
                var lookMesh = actorMesh;
                var lookMeshName = actorMeshName;
                if (look > 0) {
                    var lookSkins = looks.get(look).skins();
                    lookMesh = buildForMapActor(() -> applyStaticMeshActorSkins(cachedMesh, unrealMesh, lookSkins));
                    if (!beastLightmaps.isEmpty()) {
                        lookMesh = withBeastLightmapSurfaces(lookMesh);
                    }
                    lookMeshName = actorMeshName(lookMesh, cachedMesh, actorCacheKey, meshName, actor, looks.get(look));
                }
                var lookEntity =
                        makeMeshEntity(lookMesh, lookMeshName, meshName, actor, propLight, effectiveSunAffected);
                if (!looks.isEmpty()) {
                    lookEntity.setZoneRender(looks.get(look).render());
                    lookEntity.setZoneLook(new ZoneLook(zoneLookActor(actor), zoneLookStates(looks), look > 0));
                }
                lookEntity.setHardwareSunLight(hardwareSun);
                if (materialClient != MaterialClient.UNKNOWN) {
                    lookEntity.setNativeStaticMeshLightingInputs(nativeLightingInputs);
                }
                captureVertexLightmapInputs(
                        lookEntity,
                        unrealMesh,
                        staticMeshInstance,
                        actor,
                        look == 0 ? rootEnvironmentSources : new ArrayList<>());
                lookEntity.setHardwareSunSource(
                        hardwareSun == null
                                ? null
                                : rejectedInstanceHardwareSunSource(
                                        authoredStaticMeshInstance, materialClient, actor.getLightIntensity()));
                lookEntity.setHardwareSunDirection(
                        hardwareSun == null
                                ? null
                                : rejectedInstanceHardwareSunDirection(authoredStaticMeshInstance, materialClient));
                lookEntity.setHardwareSunEnvironment(
                        hardwareSun == null
                                ? null
                                : rejectedInstanceHardwareSunEnvironment(
                                        authoredStaticMeshInstance,
                                        levelBrightness,
                                        materialClient,
                                        actor.getLightIntensity()));
                lookEntity.setFlatSunAmbient(authoredStaticMeshInstance == null);
                lookEntity.setStaticMeshAmbient(staticMeshAmbient(
                        clientActorAmbient,
                        actor,
                        effectiveSunAffected,
                        propZoneModel,
                        unrealMesh,
                        authoredStaticMeshInstance,
                        staticMeshInstance));
                if (!beastLightmaps.isEmpty()) {
                    lookEntity.setLightmapVariants(beastLightmaps);
                    lookEntity.setBeastLightmap(true);
                }
                if (authoredLods != null) {
                    lookEntity.setStaticMeshLod(authoredLods.metadata(0));
                }
                if (nativeTangentLifecycle) {
                    lookEntity.setNativeStaticMeshDraw(NativeStaticMeshDraw.from(
                            materialClient.name(), unrealMesh, unrealMesh, actor, 0, meshLodRanges(unrealMesh)));
                }
                lookEntity.setActorVertexLighting(actorLighting);
                if (materialClient != MaterialClient.UNKNOWN) {
                    lookEntity.setNativeLightReceiver(NativeLightReceiver.capture(
                            actor,
                            unrealMesh,
                            lookEntity.getModelMatrix(),
                            propZoneModel,
                            actorLevelInfo,
                            actorAmbientNodeStack,
                            authoredStaticMeshInstance != null));
                }
                if (!exactInstanceLighting && beastLightmaps.isEmpty()) {
                    lookEntity.setEnvironmentVisibilityMasks(environmentVisibilityMasks);
                }
                lookEntities.add(lookEntity);
            }
            var entity = lookEntities.getFirst();
            if (authoredLods == null
                    && entity.getMovement() == null
                    && entity.getMotionLink() == null
                    && !entity.isDoor()
                    && entity.getNativeStaticMeshDraw() == null
                    && actorLighting == null
                    && entity.getStaticMeshAmbient() == null
                    && entity.getZoneRenderMask() == 0
                    && actorMesh == cachedMesh
                    && oneOffUsage != null
                    && isOneOffMergeEligible(
                            oneOffUsage.get(meshName.toLowerCase(Locale.ROOT)),
                            cachedMesh.vertices().size(),
                            oneOffMergeVertexBudget)
                    && OneOffMerger.canMerge(cachedMesh, oneOffMergeCommutative)) {
                merger.add(
                        cachedMesh,
                        meshName,
                        entity.getModelMatrix(),
                        entity.getInstanceLight(),
                        entity.isSunAffected(),
                        entity.getSourceActor());
            } else {
                entities.add(entity);
            }
            entities.addAll(lookEntities.subList(1, lookEntities.size()));
            if (authoredLods != null) {
                for (var level = 1; level <= 2; level++) {
                    var selected = level == 1 ? authoredLods.lod1() : authoredLods.lod2();
                    if (selected == null) {
                        continue;
                    }
                    var lodEntity = makeStaticMeshLodEntity(
                            selected,
                            level,
                            authoredLods,
                            actor,
                            authoredStaticMeshInstance,
                            approximatePropLight,
                            clientActorAmbient,
                            effectiveSunAffected,
                            foliage,
                            actorZoneFloor,
                            levelBrightness,
                            lightGrid,
                            lightColor,
                            lightIdx,
                            spatialLightScratch,
                            propZoneModel,
                            rootEnvironmentSources);
                    lodEntity.setNativeLightReceiver(entity.getNativeLightReceiver());
                    if (nativeTangentLifecycle) {
                        lodEntity.setNativeStaticMeshDraw(NativeStaticMeshDraw.from(
                                materialClient.name(), unrealMesh, selected, actor, level, meshLodRanges(unrealMesh)));
                    }
                    entities.add(lodEntity);
                    var vertices = ((EntityMesh) lodEntity.getMesh()).vertices().size();
                    if (level == 1) {
                        lod1Vertices = Math.addExact(lod1Vertices, vertices);
                    } else {
                        lod2Vertices = Math.addExact(lod2Vertices, vertices);
                    }
                }
            }
            var modelEntity = entity;

            var meshIndexStarted = System.nanoTime();
            var worldModel = modelEntity.getModelMatrix();
            meshIndex.add(unrealMesh, worldModel, new Box(boundingBox, worldModel));
            meshIndexNanos = Math.addExact(meshIndexNanos, Math.max(0L, System.nanoTime() - meshIndexStarted));

            var bbEntity = new Entity(cachedBbMesh);
            bbEntity.setWireframe(true);
            placeActor(bbEntity, actor);
            entities.add(bbEntity);
            entities.addAll(meshDecorationEntities(actor, entity));
        }
        if (missingMeshActors > 0) {
            log.warn(
                    "Skipped {} static mesh actors without a resolvable mesh " + "in {} (first: {})",
                    missingMeshActors,
                    uPackage.getName(),
                    firstMissingMeshActor);
        }
        var mergeStarted = System.nanoTime();
        var merged = merger.flush();
        if (!merged.isEmpty()) {
            var surfaceCount = 0;
            var vertexCount = 0;
            var indexCount = 0;
            for (var mergedEntity : merged) {
                entities.add(mergedEntity);
                if (mergedEntity.getMesh() instanceof EntityMesh m) {
                    surfaceCount += m.surfaces().size();
                    vertexCount += m.vertices().size();
                    indexCount += m.indices().length;
                }
            }
            log.debug(
                    "Merged {} one-off props into {} region meshes " + "({} surfaces, {} vertices, {} indices)",
                    merger.getPropCount(),
                    merged.size(),
                    surfaceCount,
                    vertexCount,
                    indexCount);
        }
        recordStaticMeshDetail(
                occluderNanos,
                meshIndexNanos,
                Math.max(0L, System.nanoTime() - mergeStarted),
                meshActorCount,
                meshActorVertices,
                lightStreamActors,
                lightStreamVertices,
                spatialLightActors,
                exactLightActors,
                lodActors,
                lod2Actors,
                lod0Vertices,
                lod1Vertices,
                lod2Vertices);
        if (hasEssenceZoneStates()) {
            for (var entity : entities) {
                if (entity.getZoneLook() == null) {
                    entity.setZoneRender(entity.getZoneRender().hidingWithoutState());
                }
            }
        }
        return entities;
    }

    private record StaticMeshLodChain(
            @Nullable UStaticMesh lod1,
            @Nullable UStaticMesh lod2,
            float distance1,
            float distance2,
            boolean authoredBlend,
            int impostorThresholdMask,
            boolean nativeSelection) {
        @Nullable
        StaticMeshLod metadata(int level) {
            return nativeSelection
                    ? null
                    : new StaticMeshLod(level, distance1, distance2, authoredBlend, impostorThresholdMask);
        }
    }

    private Mesh meshLodRanges(UStaticMesh mesh) {
        return new Mesh(
                        mesh.isHasStaticMeshLod(),
                        mesh.getLodRange1(),
                        mesh.getLodRange2(),
                        NativeStaticMeshDraw.impostorThresholdMask(mesh))
                .withImpostorDistanceRatio(impostorDistanceMultiplier);
    }

    private @Nullable StaticMeshLodChain staticMeshLodChain(UStaticMesh mesh) {
        if (!mesh.isHasStaticMeshLod()) {
            return null;
        }
        var lod1 = mesh.getStaticMeshLod1().get();
        var lod2 = mesh.getStaticMeshLod2().get();
        var ranges = meshLodRanges(mesh);
        var distance1 = ranges.range1() * UStaticMesh.LOD_RANGE_UNIT;
        var distance2 = ranges.range2() * UStaticMesh.LOD_RANGE_UNIT;
        var nativeSelection =
                mesh.isUseNormalMap() || lod1 != null && lod1.isUseNormalMap() || lod2 != null && lod2.isUseNormalMap();
        if (nativeSelection) {
            return new StaticMeshLodChain(
                    lod1,
                    lod2,
                    distance1,
                    distance2,
                    mesh.isStaticMeshLodBlend(),
                    ranges.impostorThresholdMask(),
                    true);
        }
        if (lod1 == null || !Float.isFinite(distance1) || distance1 <= 0f) {
            return null;
        }
        if (lod2 == null || !Float.isFinite(distance2) || distance2 <= distance1) {
            lod2 = null;
            distance2 = 0f;
        }
        return new StaticMeshLodChain(
                lod1, lod2, distance1, distance2, mesh.isStaticMeshLodBlend(), ranges.impostorThresholdMask(), false);
    }

    private void captureVertexLightmapInputs(
            Entity entity,
            UStaticMesh mesh,
            @Nullable UStaticMeshInstance instance,
            StaticMeshActor actor,
            List<LightInfo> sources) {
        if (materialClient == MaterialClient.UNKNOWN || !mesh.isUseNormalMap() || instance == null) {
            return;
        }
        entity.setNativeVertexLightmapInputs(NativeVertexLightmapInputs.capture(instance, actor));
        Collections.addAll(sources, instance.getSceneLights());
        Collections.addAll(sources, instance.getEnvironmentLights());
    }

    private Entity makeStaticMeshLodEntity(
            UStaticMesh unrealMesh,
            int level,
            StaticMeshLodChain chain,
            StaticMeshActor actor,
            @Nullable UStaticMeshInstance rootInstance,
            Vec3 approximatePropLight,
            Vec3 clientActorAmbient,
            boolean effectiveSunAffected,
            boolean rootFoliage,
            @Nullable Vec3 actorZoneFloor,
            float levelBrightness,
            @Nullable LightGrid lightGrid,
            float[] lightColor,
            int[] lightIdx,
            SpatialLightScratch spatialLightScratch,
            @Nullable UModel propZoneModel,
            List<LightInfo> rootEnvironmentSources) {
        var meshName = meshAssetPath(unrealMesh);
        var actorCacheKey = staticMeshActorCacheKey(unrealMesh, actor);
        var mesh = getStaticMeshActorMesh(unrealMesh, actor);
        var authoredInstance = rootInstance == null
                ? null
                : level == 1 ? rootInstance.resolveLodInstance1() : rootInstance.resolveLodInstance2();
        var vertexCount = mesh.vertices().size();
        var instance = isStaticMeshInstanceAcceptedByClient(authoredInstance, vertexCount) ? authoredInstance : null;
        var spatialLighting = bakeSpatialPropLight(mesh, actor, lightGrid, lightColor, lightIdx, spatialLightScratch);
        var hardwareSun = instance == null
                ? rejectedInstanceHardwareSun(rootInstance, levelBrightness, materialClient, actor.getLightIntensity())
                : null;
        var hasInstanceColor = hasValidInstanceColorStream(instance, vertexCount);
        var hasInstanceLighting = hasInstanceColor
                || instance != null
                        && (hasValidLightFlags(instance.getSceneLights(), vertexCount)
                                || hasValidLightFlags(instance.getEnvironmentLights(), vertexCount));
        var instanceZoneFloor =
                hasInstanceLighting && instance != null && hasValidLightFlags(instance.getSceneLights(), vertexCount)
                        ? actorZoneFloor
                        : null;
        var exactInterior = instanceZoneFloor != null
                && (vertexCount >= EXACT_INTERIOR_MESH_MIN_VERTICES
                        || maxComponent(approximatePropLight) >= EXACT_INTERIOR_ORIGIN_SATURATION);
        var exactInstanceLighting = hasInstanceLighting;
        var actorLighting = exactInstanceLighting
                ? applyStaticMeshInstanceLighting(
                        mesh,
                        unrealMesh,
                        actor,
                        instance,
                        spatialLighting,
                        isStaticMeshEnvironmentStreamEnabled(instance, propZoneModel),
                        materialClient)
                : spatialLighting;
        var propLight = exactInstanceLighting || actorLighting != null && actorLighting.nativeSceneColor()
                ? clientActorAmbient
                : approximatePropLight;
        var nativeLightingInputs =
                NativeStaticMeshLightingInputs.capture(level, unrealMesh, instance, rootInstance, true);
        var beastLightmaps = nativeLightingInputs.hasBeastUv() ? beastLightmapTextures(instance) : List.<Texture>of();
        var lodMesh = beastLightmaps.isEmpty() ? mesh : withBeastLightmapSurfaces(mesh);
        var entity = makeMeshEntity(
                lodMesh,
                beastLightmaps.isEmpty() ? actorCacheKey : meshName + "#actor/" + actor.getFullName(),
                meshName,
                actor,
                propLight,
                effectiveSunAffected);
        entity.setHardwareSunLight(hardwareSun);
        if (materialClient != MaterialClient.UNKNOWN) {
            entity.setNativeStaticMeshLightingInputs(nativeLightingInputs);
        }
        captureVertexLightmapInputs(entity, unrealMesh, instance, actor, rootEnvironmentSources);
        entity.setHardwareSunSource(
                hardwareSun == null
                        ? null
                        : rejectedInstanceHardwareSunSource(rootInstance, materialClient, actor.getLightIntensity()));
        entity.setHardwareSunDirection(
                hardwareSun == null ? null : rejectedInstanceHardwareSunDirection(rootInstance, materialClient));
        entity.setHardwareSunEnvironment(
                hardwareSun == null
                        ? null
                        : rejectedInstanceHardwareSunEnvironment(
                                rootInstance, levelBrightness, materialClient, actor.getLightIntensity()));
        entity.setFlatSunAmbient(rootInstance == null);
        entity.setStaticMeshAmbient(staticMeshAmbient(
                clientActorAmbient, actor, effectiveSunAffected, propZoneModel, unrealMesh, rootInstance, instance));
        entity.setStaticMeshLod(chain.metadata(level));
        entity.setFoliageLighting(rootFoliage);
        if (!beastLightmaps.isEmpty()) {
            entity.setLightmapVariants(beastLightmaps);
            entity.setBeastLightmap(true);
        }
        entity.setActorVertexLighting(actorLighting);
        if (!exactInstanceLighting && beastLightmaps.isEmpty()) {
            entity.setEnvironmentVisibilityMasks(environmentVisibilityMasks(instance, vertexCount));
        }
        return entity;
    }

    private @Nullable MeshOccluders buildMeshSunOccluders(List<StaticMeshActor> actors) {
        var start = System.nanoTime();
        var aabb = new FloatArrayList(actors.size() * 6);
        var invMatrix = new FloatArrayList(actors.size() * 16);
        var triMeshId = new IntArrayList(actors.size());
        var actorIndex = new IntArrayList(actors.size());
        var triMeshes = new ArrayList<TriangleMesh>();
        var triMeshByName = new HashMap<String, Integer>();
        var scratch = new float[16];
        var minExtent = SunVisibility.getMeshOccluderMinExtent();
        for (int i = 0; i < actors.size(); i++) {
            var actor = actors.get(i);
            if (actor.isDeleteMe() || actor.isHidden()) {
                continue;
            }
            var mesh = actor.getStaticMesh().get();
            if (mesh == null || mesh.isSwayObject()) {
                continue;
            }
            var meshName = meshAssetPath(mesh);
            var cachedMesh = getStaticMeshActorMesh(mesh, actor);
            var placed = new Entity(cachedMesh);
            placeActor(placed, actor);
            var model = placed.getModelMatrix();
            var world = new Box(mesh.getBoundingBox().toBox(), model);
            var mn = world.getMin();
            var mx = world.getMax();
            var extent = Math.max(mx.getX() - mn.getX(), Math.max(mx.getY() - mn.getY(), mx.getZ() - mn.getZ()));
            if (extent < minExtent) {
                continue;
            }
            var meshId = triMeshByName.computeIfAbsent(meshName, _ -> {
                triMeshes.add(
                        occluderMeshCache.computeIfAbsent(meshName, _ -> new TriangleMesh(localTriangles(cachedMesh))));
                return triMeshes.size() - 1;
            });
            aabb.add(mn.getX());
            aabb.add(mn.getY());
            aabb.add(mn.getZ());
            aabb.add(mx.getX());
            aabb.add(mx.getY());
            aabb.add(mx.getZ());
            model.inverse().to(scratch);
            for (var f : scratch) {
                invMatrix.add(f);
            }
            triMeshId.add(meshId);
            actorIndex.add(i);
        }
        var occluders = SunVisibility.buildMeshOccluders(
                aabb.toArray(),
                invMatrix.toArray(),
                triMeshId.toArray(),
                triMeshes.toArray(new TriangleMesh[0]),
                actorIndex.toArray(),
                actorIndex.size());
        log.debug(
                "Sun mesh occluders: {} of {} actors ({} meshes) in {} ms",
                actorIndex.size(),
                actors.size(),
                triMeshes.size(),
                (System.nanoTime() - start) / 1_000_000);
        return occluders;
    }

    private List<MeshEmitterDescriptor> loadMeshEmitterDescriptors(
            UPackage uPackage, Map<String, EmitterVisibility> emitterVisibility) {
        var staticActors = new ArrayList<StaticMeshActor>();
        uPackage.loadObjects("StaticMeshActor", staticActors);
        uPackage.loadObjects("MovableStaticMeshActor", staticActors);
        uPackage.loadObjects("L2MovableStaticMeshActor", staticActors);
        var staticNames = new ArrayList<String>();
        var staticPositions = new ArrayList<Vec3>();
        for (var a : staticActors) {
            if (a.isDeleteMe() || a.isHidden()) {
                continue;
            }
            var m = a.getStaticMesh().get();
            if (m != null) {
                staticNames.add(meshAssetPath(m));
                staticPositions.add(a.getPosition());
            }
        }

        var actors = new ArrayList<EmitterActor>();
        uPackage.loadEmitterActors(actors);
        return meshEmitterDescriptors(uPackage.getName(), actors, staticNames, staticPositions, emitterVisibility);
    }

    private List<MeshEmitterDescriptor> meshEmitterDescriptors(
            String packageName,
            List<EmitterActor> actors,
            List<String> staticNames,
            List<Vec3> staticPositions,
            Map<String, EmitterVisibility> emitterVisibility) {
        var descriptors = new ArrayList<MeshEmitterDescriptor>();
        for (var actorOrder = 0; actorOrder < actors.size(); actorOrder++) {
            var actor = actors.get(actorOrder);
            if (actor.isDeleteMe() || actor.isHidden()) {
                continue;
            }
            var childIndex = 0;
            for (var child : actor.getChildEmitters()) {
                childIndex++;
                if (!(child instanceof UParticleEmitter particle) || !actor.isChildPlaying(particle)) {
                    continue;
                }
                var seed = actor.getFullName().hashCode() * 0x9E3779B97F4A7C15L + childIndex;
                if (particle instanceof UMeshEmitter emitter) {
                    var uMesh = emitter.getMesh();
                    if (uMesh == null) {
                        continue;
                    }
                    if (isMotionlessImmortal(emitter)
                            && coincidesWithStatic(
                                    meshAssetPath(uMesh), actor.getPosition(), staticNames, staticPositions)) {
                        continue;
                    }
                    var cachedMesh = meshCache.computeIfAbsent(
                            meshAssetPath(uMesh) + "#fx", _ -> buildStaticEntityMesh(uMesh, null));
                    var overridden = applyMeshEmitterAlphaSemantics(
                            applyMeshEmitterMaterialOverrides(
                                    cachedMesh,
                                    uMesh,
                                    meshEmitterMaterialOverrides(emitter, actor.getSkins()),
                                    emitter),
                            emitter);
                    var rules = applyMeshEmitterSurfaceRules(
                            overridden, uMesh, meshEmitterMaterialOverrides(emitter, actor.getSkins()), emitter);
                    var cpuMesh = rules.mesh();
                    emitter.getTexture();
                    descriptors.add(new MeshEmitterDescriptor(
                                    emitter,
                                    actor.getLocation(),
                                    actor.getPrePivot(),
                                    actor.getRotation(),
                                    actor.getScale(),
                                    cpuMesh,
                                    seed,
                                    doesMeshHaveBlackBackground(cpuMesh),
                                    visibilityOf(actor, emitterVisibility),
                                    shortName(uMesh.getFullName()),
                                    actor.getTranslucentRenderPriority(),
                                    actor.getFullName(),
                                    actorOrder,
                                    childIndex - 1)
                            .withSurfaceRules(cpuMesh, rules.particleColor(), rules.unlit(), rules.severalPasses()));
                    continue;
                }
                if (particle instanceof UVertMeshEmitter emitter) {
                    var meshRef = emitter.getVertexMesh();
                    var uMesh = resolveOptionalVertMesh(
                            packageName, actor.getFullName(), childIndex, meshRef.peekFullName(), meshRef::get);
                    if (uMesh == null) {
                        continue;
                    }
                    var overrides = meshEmitterMaterialOverrides(emitter, actor.getSkins());
                    var colorModifier = emitter.isUseMeshBlendMode() && emitter.isUseParticleColor();
                    var cacheKey =
                            vertMeshCacheKey(uMesh, overrides, emitter.isUseMeshBlendMode()) + "#" + colorModifier;
                    var frames = vertMeshFrameCache.computeIfAbsent(
                            cacheKey,
                            _ -> buildVertMeshFrames(uMesh, overrides, emitter.isUseMeshBlendMode(), colorModifier));
                    var sequence = uMesh.findAnimationSequence(uMesh.getName());
                    descriptors.add(new MeshEmitterDescriptor(
                            emitter,
                            actor.getLocation(),
                            actor.getPrePivot(),
                            actor.getRotation(),
                            actor.getScale(),
                            frames.getFirst(),
                            seed,
                            doesMeshHaveBlackBackground(frames.getFirst()),
                            visibilityOf(actor, emitterVisibility),
                            shortName(uMesh.getFullName()),
                            frames,
                            sequence == null ? 0 : sequence.startFrame(),
                            sequence == null ? 0 : sequence.frameCount(),
                            sequence == null ? 0f : actor.getSpeedRate() * sequence.rate(),
                            emitter.isRenderTwoSided(),
                            actor.getTranslucentRenderPriority(),
                            actor.getFullName(),
                            actorOrder,
                            childIndex - 1,
                            0,
                            emitter.isUseMeshBlendMode()
                                    ? findVertMeshSurfaces(uMesh, overrides, MeshParticleMaterials::takesNoLight)
                                    : 0,
                            emitter.isUseMeshBlendMode()
                                    ? findVertMeshSurfaces(
                                            uMesh, overrides, MeshParticleMaterials::drawsInSeveralPasses)
                                    : 0));
                }
            }
        }
        return descriptors;
    }

    private List<MeshEmitterDescriptor> projectileMeshes(Projectile projectile, EmitterActor actor) {
        String meshReference = null;
        var skins = new ArrayList<String>();
        for (var load : projectile.loads()) {
            if (meshReference == null && hasSkeletalMesh(load)) {
                meshReference = load;
            } else {
                skins.add(load);
            }
        }
        if (meshReference == null) {
            return List.of();
        }
        skins.sort(String.CASE_INSENSITIVE_ORDER);
        var model = loadNpcModel(meshReference, skins.isEmpty() ? List.of() : List.of(skins.getFirst()));
        if (model == null) {
            return List.of();
        }
        var lifted = model.entityMesh();
        var vertices = MeshVertexBuffer.builder(lifted.vertices().size());
        var bounds = new Box();
        for (var vertex : lifted.vertices()) {
            var position = vertex.position();
            var origin = new Vec3(position.getX(), position.getY(), position.getZ() - model.lowest());
            bounds.add(origin);
            vertices.add(
                    origin.getX(),
                    origin.getY(),
                    origin.getZ(),
                    vertex.normal().getX(),
                    vertex.normal().getY(),
                    vertex.normal().getZ(),
                    vertex.uv().getX(),
                    vertex.uv().getY());
        }
        var mesh =
                new EntityMesh(vertices.build(), lifted.indices(), lifted.surfaces(), List.of(), bounds, 0f, 1f, false);
        return List.of(new MeshEmitterDescriptor(
                new ActorMeshEmitter(),
                actor.getLocation(),
                actor.getPrePivot(),
                actor.getRotation(),
                actor.getScale(),
                mesh,
                actor.getFullName().hashCode(),
                false,
                visibilityOf(actor, new HashMap<>()),
                Objects.requireNonNullElse(shortName(meshReference), meshReference),
                actor.getTranslucentRenderPriority()));
    }

    private @Nullable NpcModel loadNpcModel(
            String meshReference,
            List<String> textureReferences,
            @Nullable USkeletalMesh frame,
            @Nullable UMeshAnimation frameAnimation,
            @Nullable String stance,
            double @Nullable [] heldBy,
            boolean accessory,
            @Nullable String heldBone,
            @Nullable List<PlayerRigPart> rig) {
        return loadNpcModel(
                meshReference,
                textureReferences,
                frame,
                frameAnimation,
                stance,
                heldBy,
                accessory,
                heldBone,
                rig,
                null,
                1);
    }

    private @Nullable NpcModel loadNpcModel(
            String meshReference,
            List<String> textureReferences,
            @Nullable USkeletalMesh frame,
            @Nullable UMeshAnimation frameAnimation,
            @Nullable String stance,
            double @Nullable [] heldBy,
            boolean accessory,
            @Nullable String heldBone,
            @Nullable List<PlayerRigPart> rig,
            @Nullable Vec4 tint,
            float heldScale) {
        var found = findNpcMesh(meshReference);
        if (found == null) {
            return null;
        }
        var mesh = found.mesh();
        var uPackage = Optional.of(found.uPackage());
        var pose = "";
        Vec3[] points = mesh.getPoints();
        Vec3[] rigPoints = null;
        Map<String, Vec3> bones = Map.of();
        try {
            if (heldBy != null) {
                points = new Vec3[mesh.getPoints().length];
                for (var i = 0; i < points.length; i++) {
                    var local = accessory
                            ? mesh.convertAttachedToLocal(mesh.getPoints()[i])
                            : mesh.convertMeshToLocal(mesh.getPoints()[i]);
                    points[i] = SkeletalPose.place(heldBy, local.times(heldScale));
                }
            } else if (frame != null) {
                if (frameAnimation != null && frameAnimation.getSequences().length > 0) {
                    var sequence = playerSequence(frameAnimation, stance);
                    var motion = frameAnimation.decodeMotion(sequence);
                    var mount = SkeletalPose.isMount(mesh, frame) ? mesh.loadOwnAnimation() : null;
                    var carrier = mount != null || SkeletalPose.sharesBones(mesh, frame)
                            ? null
                            : SkeletalPose.findCarrierBone(mesh, frame, CARRY_REACH);
                    if (carrier != null) {
                        var name = frameAnimation.getSequences()[sequence].name();
                        Motion ownMotion = null;
                        UMeshAnimation ownAnimation = null;
                        if (mesh.getAnimationIndex() != 0
                                && uPackage.get().loadObject(mesh.getAnimationIndex()) instanceof UMeshAnimation own
                                && own != frameAnimation
                                && !Objects.equals(own.getName(), frameAnimation.getName())) {
                            var ownSequence = own.findSequenceIndex(name);
                            if (ownSequence >= 0) {
                                ownMotion = own.decodeMotion(ownSequence);
                                ownAnimation = own;
                            }
                        }
                        if (ownMotion != null
                                && SkeletalPose.followsCarrier(
                                        mesh, ownMotion, ownAnimation, frame, motion, frameAnimation, carrier)) {
                            points = SkeletalPose.pose(mesh, ownMotion, ownAnimation, 0, true);
                            rigPoints = SkeletalPose.rebaseOnCarrier(mesh, frame, carrier);
                        } else {
                            points = SkeletalPose.poseCarriedPart(
                                    mesh, ownMotion, ownAnimation, frame, motion, frameAnimation, carrier);
                        }
                    } else if (mount != null) {
                        var name = frameAnimation.getSequences()[sequence].name();
                        var same = mount.findSequenceIndex(name);
                        var held =
                                same >= 0 ? same : Math.max(0, mount.findSequenceIndex(stance == null ? "" : stance));
                        points = SkeletalPose.pose(mesh, mount.decodeMotion(held), mount, 0, true);
                    } else {
                        points = SkeletalPose.pose(mesh, frame, motion, frameAnimation, 0);
                    }
                    bones = SkeletalPose.posedBones(frame, motion, frameAnimation, 0);
                    pose = frameAnimation.getSequences()[sequence].name();
                }
            } else if (uPackage.get().loadObject(mesh.getAnimationIndex()) instanceof UMeshAnimation animation
                    && animation.getSequences().length > 0) {
                var sequence = npcIdleSequence(animation, stance);
                var motion = animation.decodeMotion(sequence);
                points = SkeletalPose.pose(mesh, motion, animation, 0, true);
                bones = SkeletalPose.posedBones(mesh, motion, animation, 0);
                pose = animation.getSequences()[sequence].name();
            } else {
                bones = SkeletalPose.posedBones(mesh, null, null, 0);
            }
        } catch (RuntimeException failure) {
            log.debug("{} keeps its reference pose: {}", meshReference, failure.toString());
        }
        var local = new Vec3[points.length];
        var bottom = Float.MAX_VALUE;
        var space = frame != null ? frame : mesh;
        for (var i = 0; i < points.length; i++) {
            local[i] = space.convertMeshToLocal(points[i]);
            bottom = Math.min(bottom, local[i].getZ());
        }
        for (var i = 0; i < local.length; i++) {
            local[i] = new Vec3(local[i].getX(), local[i].getY(), local[i].getZ() - bottom);
        }
        var placedBones = new HashMap<String, Vec3>();
        for (var bone : bones.entrySet()) {
            var at = space.convertMeshToLocal(bone.getValue());
            placedBones.put(bone.getKey(), new Vec3(at.getX(), at.getY(), at.getZ() - bottom));
        }
        var litSphere = heldBy == null ? litSphere(mesh, space, bones, bottom) : null;
        var boneNames = new ArrayList<String>();
        if (!placedBones.isEmpty()) {
            for (var bone : space.getBones()) {
                boneNames.add(bone.name());
            }
        }
        var faces = mesh.getFaces();
        var faceSections = mesh.getFaceSections();
        var wedges = mesh.getWedges();
        var indices = new int[Math.multiplyExact(faces.length, 3)];
        var surfaces = new ArrayList<Entity.Surface>();
        var indexCursor = 0;
        var sectionMaterials = npcMaterials(mesh, textureReferences);
        var surfacePasses = new HashMap<Integer, NpcPartPasses>();
        for (var section = 0; section < sectionMaterials.size(); section++) {
            var firstIndex = indexCursor;
            for (var faceIndex = 0; faceIndex < faces.length; faceIndex++) {
                if (faceSections[faceIndex] != section) {
                    continue;
                }
                var face = faces[faceIndex];
                indices[indexCursor++] = face.wedge2();
                indices[indexCursor++] = face.wedge1();
                indices[indexCursor++] = face.wedge0();
            }
            if (indexCursor == firstIndex) {
                continue;
            }
            var materialNode = sectionMaterials.get(section);
            var maybeMaterial = loadMaterial(materialNode);
            var material =
                    maybeMaterial == null ? DEFAULT_PASSABLE_MATERIAL : withShaderOpacity(materialNode, maybeMaterial);
            var blend = effectiveFxBlend(blendModeOf(materialNode), material);
            var passes = tint == null && maybeMaterial != null ? loadNpcPartPasses(materialNode) : null;
            if (passes != null) {
                surfacePasses.put(surfaces.size(), passes);
            }
            surfaces.add(new Entity.Surface(
                    SurfaceType.STATIC_MESH.getId() | SurfaceType.PASSABLE.getId(),
                    firstIndex,
                    indexCursor - firstIndex,
                    tint == null ? material : new ColorModifierEntityMaterial(material, tint),
                    blend,
                    uvAnimationOf(materialNode),
                    false,
                    maybeMaterial != null && materialNode != null ? materialNode.getFullName() : null,
                    alphaRefOf(materialNode, blend),
                    usesVertexColor(materialNode),
                    writesDepth(materialNode, blend),
                    modulatesStaticLighting2x(materialNode)));
        }
        if (indexCursor != indices.length) {
            indices = Arrays.copyOf(indices, indexCursor);
        }
        var normalX = new float[local.length];
        var normalY = new float[local.length];
        var normalZ = new float[local.length];
        for (var face : faces) {
            var v0 = wedges[face.wedge0()].vertexIndex();
            var v1 = wedges[face.wedge1()].vertexIndex();
            var v2 = wedges[face.wedge2()].vertexIndex();
            var p0 = local[v0];
            var p1 = local[v1];
            var p2 = local[v2];
            var ax = p1.getX() - p0.getX();
            var ay = p1.getY() - p0.getY();
            var az = p1.getZ() - p0.getZ();
            var bx = p2.getX() - p0.getX();
            var by = p2.getY() - p0.getY();
            var bz = p2.getZ() - p0.getZ();
            var nx = ay * bz - az * by;
            var ny = az * bx - ax * bz;
            var nz = ax * by - ay * bx;
            accumulateNormal(normalX, normalY, normalZ, v0, nx, ny, nz);
            accumulateNormal(normalX, normalY, normalZ, v1, nx, ny, nz);
            accumulateNormal(normalX, normalY, normalZ, v2, nx, ny, nz);
        }
        var vertices = MeshVertexBuffer.builder(wedges.length);
        var bounds = new Box();
        for (var wedge : wedges) {
            var vertex = wedge.vertexIndex();
            var length = (float) Math.sqrt(normalX[vertex] * normalX[vertex]
                    + normalY[vertex] * normalY[vertex]
                    + normalZ[vertex] * normalZ[vertex]);
            var position = local[vertex];
            bounds.add(position);
            vertices.add(
                    position.getX(),
                    position.getY(),
                    position.getZ(),
                    length > 1e-7f ? normalX[vertex] / length : 0f,
                    length > 1e-7f ? normalY[vertex] / length : 0f,
                    length > 1e-7f ? normalZ[vertex] / length : 1f,
                    wedge.uv().getX(),
                    wedge.uv().getY());
        }
        if (rig != null) {
            rig.add(new PlayerRigPart(
                    mesh,
                    heldBy != null ? heldBone : null,
                    accessory,
                    npcMaterialFlags(mesh, textureReferences),
                    heldScale,
                    rigPoints));
        }
        return new NpcModel(
                meshReference,
                new EntityMesh(vertices.build(), indices, surfaces, List.of(), bounds, 0f, 1f, false),
                pose,
                -bottom,
                placedBones,
                boneNames,
                litSphere,
                surfacePasses);
    }

    private @Nullable NpcModel holding(
            String meshReference,
            List<String> textureReferences,
            List<HeldItem> held,
            @Nullable List<PlayerRigPart> rig,
            @Nullable String wait) {
        var body = loadNpcModel(meshReference, textureReferences, null, null, wait, null, false, null, rig);
        var found = body == null || held.isEmpty() ? null : findNpcMesh(meshReference);
        if (found == null) {
            return body;
        }
        var mesh = found.mesh();
        UMeshAnimation animation = null;
        Motion motion = null;
        try {
            if (found.uPackage().loadObject(mesh.getAnimationIndex()) instanceof UMeshAnimation read
                    && read.getSequences().length > 0) {
                animation = read;
                motion = read.decodeMotion(npcIdleSequence(read, wait));
            }
        } catch (RuntimeException unreadable) {
            log.debug("{} holds things in its reference pose: {}", meshReference, unreadable.toString());
        }
        var ownScale = mesh.getMeshScale().getX() > 0 ? mesh.getMeshScale().getX() : 1;
        var parts = new ArrayList<NpcModel>();
        parts.add(body);
        for (var item : held) {
            var bone = SkeletalPose.findPosedBone(mesh, motion, animation, 0, item.bone());
            var part = bone == null
                    ? null
                    : loadNpcModel(
                            item.mesh(),
                            item.textures(),
                            mesh,
                            null,
                            null,
                            bone,
                            false,
                            item.bone(),
                            rig,
                            null,
                            item.scale() / ownScale);
            if (part != null) {
                parts.add(part);
            }
        }
        return parts.size() < 2 ? body : joinNpcParts(meshReference, parts);
    }

    private @Nullable FoundNpcMesh findSkeletalMesh(String meshReference) {
        var dot = meshReference.indexOf('.');
        if (dot <= 0) {
            return null;
        }
        var packageName = meshReference.substring(0, dot);
        var meshName = meshReference.substring(meshReference.lastIndexOf('.') + 1);
        var uPackage = packageLoader.loadPackageIfPresent(packageName);
        var meshObject = uPackage.map(found -> found.findObject("SkeletalMesh", meshName))
                .orElse(null);
        for (var number = 2; meshObject == null && number <= 9; number++) {
            var sibling = packageLoader.loadPackageIfPresent(packageName + number);
            if (sibling.isPresent()) {
                meshObject = sibling.get().findObject("SkeletalMesh", meshName);
                if (meshObject != null) {
                    uPackage = sibling;
                }
            }
        }
        return meshObject instanceof USkeletalMesh mesh && uPackage.isPresent()
                ? new FoundNpcMesh(mesh, uPackage.get())
                : null;
    }

    private boolean isCarriedByHead(String reference, USkeletalMesh frame, double @Nullable [] head) {
        var dot = reference.indexOf('.');
        if (dot <= 0 || head == null) {
            return false;
        }
        var found = packageLoader
                .loadPackageIfPresent(reference.substring(0, dot))
                .map(pkg -> pkg.findObject("SkeletalMesh", reference.substring(reference.lastIndexOf('.') + 1)))
                .orElse(null);
        if (!(found instanceof USkeletalMesh mesh)) {
            return false;
        }
        if (isRigidAccessory(mesh, frame)) {
            return true;
        }
        var headHeight = Math.sqrt(head[4] * head[4] + head[5] * head[5] + head[6] * head[6]);
        for (var point : mesh.getPoints()) {
            if (point.length() > headHeight / 2) {
                return false;
            }
        }
        return mesh.getPoints().length > 0;
    }

    private List<String> firstFaceStyle(String race) {
        for (var candidate : List.of(
                "LineageFaceTex." + race.toLowerCase(Locale.ROOT) + "." + race + "_m000_t00_f",
                race + "." + race + "_m000_t00_f")) {
            if (resolveNpcMaterial(candidate) != null) {
                return List.of(candidate);
            }
        }
        return List.of();
    }

    private @Nullable NpcModel playerLike(
            String faceReference, List<String> faceTextures, Outfit outfit, @Nullable List<PlayerRigPart> rig) {
        var parsed = PlayerFigures.parseFace(faceReference);
        if (parsed == null) {
            return null;
        }
        var packageName = parsed.packageName();
        var race = parsed.race();
        var figure = figureOf(faceReference, outfit);
        var wholeBody = getFigureBodies().findWholeBody(figure);
        var uPackage = packageLoader.loadPackageIfPresent(packageName);
        if (uPackage.isEmpty()
                || !(uPackage.get().findObject("SkeletalMesh", faceReference.substring(faceReference.indexOf('.') + 1))
                        instanceof USkeletalMesh upper)) {
            return null;
        }
        var raceAnimation = findRaceAnimation(uPackage.get(), race);
        var animation =
                raceAnimation == null ? null : narrowFor(raceAnimation, figure, outfit.classWait(), outfit.rowClass());
        var stance = animation == null || animation.getSequences().length == 0
                ? outfit.stance()
                : animation.getSequences()[playerSequence(animation, outfit.classWait(), outfit.stance())].name();
        var parts = new ArrayList<NpcModel>();
        var classHead = findClassHead(figure);
        var textures = faceTextures.isEmpty() && ownTexture(upper) == null ? firstFaceStyle(race) : faceTextures;
        var faceMesh = classHead != null && !classHead.face().isEmpty() && findNpcMesh(classHead.face()) != null
                ? classHead.face()
                : faceReference;
        if (faceTextures.isEmpty()
                && classHead != null
                && !classHead.faceTexture().isEmpty()
                && resolveNpcMaterial(classHead.faceTexture()) != null) {
            textures = List.of(classHead.faceTexture());
        }
        var hiddenFace = wholeBody != null && textures.isEmpty() && ownTexture(upper) == null;
        var face =
                hiddenFace ? null : loadNpcModel(faceMesh, textures, upper, animation, stance, null, false, null, rig);
        if (face == null && !hiddenFace) {
            return null;
        }
        if (face != null) {
            parts.add(face);
        }
        if (wholeBody != null && !outfit.covered().contains("u")) {
            var body = loadNpcModel(
                    wholeBody.mesh(), wholeBody.textures(), upper, animation, stance, null, false, null, rig);
            if (body != null) {
                parts.add(body);
            }
        }
        for (var slot : PLAYER_BODY) {
            if (outfit.covered().contains(slot)) {
                continue;
            }
            var texture = race + "." + race + "_m000_t1000_" + slot;
            if (resolveNpcMaterial(texture) == null) {
                texture = race + "." + race + "_m000_t00_" + slot;
            }
            if (resolveNpcMaterial(texture) == null
                    && resolveNpcMaterial(race + "." + race + "_m001_t02_" + slot) != null) {
                texture = race + "." + race + "_m001_t02_" + slot;
            }
            Vec4 tint = null;
            var mesh = packageName + "." + race + "_m000_" + slot;
            var standIn = BARE_STAND_INS.get(race.toLowerCase(Locale.ROOT) + "_" + slot);
            if (standIn != null
                    && resolveNpcMaterial(texture) == null
                    && resolveNpcMaterial(standIn.texture()) != null) {
                texture = standIn.texture();
                tint = standIn.tint();
                if (standIn.mesh() != null) {
                    mesh = standIn.mesh();
                }
            }
            var part = loadNpcModel(mesh, List.of(texture), upper, animation, stance, null, false, null, rig, tint, 1);
            if (part != null) {
                parts.add(part);
            }
        }
        var accessorySkipped = false;
        for (var item : outfit.worn()) {
            if (item.mesh().isBlank() || item.mesh().equalsIgnoreCase("None")) {
                continue;
            }
            var head = animation == null
                    ? null
                    : SkeletalPose.findPosedBone(
                            upper, animation.decodeMotion(playerSequence(animation, stance)), animation, 0, HEAD_BONE);
            if (item.hairAccessory() && isCarriedByHead(item.mesh(), upper, head)) {
                var accessory = head == null
                        ? null
                        : loadNpcModel(item.mesh(), item.textures(), upper, null, null, head, true, HEAD_BONE, rig);
                if (accessory != null) {
                    parts.add(accessory);
                } else {
                    accessorySkipped = true;
                }
                continue;
            }
            var part = loadNpcModel(item.mesh(), item.textures(), upper, animation, stance, null, false, null, rig);
            if (part != null) {
                parts.add(part);
            }
        }
        var cover = accessorySkipped ? outfit.hairCover().withoutAccessory() : outfit.hairCover();
        var hairRows = wholeBody == null && classHead == null
                ? getHairRows().find(PlayerFigures.getPawnClassName(figure))
                : null;
        if (cover.fallback() != HairCover.Fallback.ALL) {
            var ears = loadEars(packageName, race, upper, animation, stance, rig);
            if (ears != null) {
                parts.add(ears);
            }
        }
        if (hairRows != null) {
            var row = hairRows.pickRow(cover);
            if (row >= 0) {
                var style = outfit.hairStyle();
                var ownRig = row == 0 ? rig : null;
                var own = loadTableHair(hairRows.findHair(0, style), upper, animation, stance, ownRig);
                if (own.isEmpty() && style != 0) {
                    style = 0;
                    own = loadTableHair(hairRows.findHair(0, style), upper, animation, stance, ownRig);
                }
                parts.addAll(
                        row == 0 ? own : loadTableHair(hairRows.findHair(row, style), upper, animation, stance, rig));
            }
        } else if (cover.fallback() != HairCover.Fallback.ALL) {
            var ownHair = wholeBody == null ? null : findOwnHair(figure);
            var classHair = classHead == null ? null : classHead.findHair(outfit.hairStyle());
            if (classHair != null && findNpcMesh(classHair) != null) {
                var texture = classHead.findHairTexture(outfit.hairStyle());
                var hair = loadNpcModel(
                        classHair,
                        texture.isEmpty() ? List.of() : List.of(texture),
                        upper,
                        animation,
                        stance,
                        null,
                        false,
                        null,
                        rig);
                if (hair != null) {
                    parts.add(hair);
                }
            } else if (ownHair == null) {
                parts.addAll(loadHair(
                        packageName,
                        race,
                        outfit.hairStyle(),
                        cover.fallback() == HairCover.Fallback.NONE,
                        upper,
                        animation,
                        stance,
                        rig));
            } else if (!isHairWorn(outfit)) {
                var hair = loadOwnHair(ownHair, outfit.hairStyle(), upper, animation, stance, rig);
                if (hair != null) {
                    parts.add(hair);
                }
            }
        }
        if (parts.isEmpty() || parts.size() < 2 && (face != null || wholeBody == null)) {
            return null;
        }
        if (animation != null && !outfit.held().isEmpty()) {
            var sequence = playerSequence(animation, stance);
            var motion = animation.decodeMotion(sequence);
            for (var item : outfit.held()) {
                var bone = SkeletalPose.findPosedBone(upper, motion, animation, 0, item.bone());
                if (bone == null) {
                    continue;
                }
                var weapon =
                        loadNpcModel(item.mesh(), item.textures(), upper, null, null, bone, false, item.bone(), rig);
                if (weapon != null) {
                    parts.add(weapon);
                }
            }
        }
        return joinNpcParts(faceReference, parts);
    }

    private List<NpcModel> loadHair(
            String packageName,
            String race,
            int style,
            boolean front,
            USkeletalMesh frame,
            @Nullable UMeshAnimation animation,
            @Nullable String stance,
            @Nullable List<PlayerRigPart> rig) {
        var styles = style > 0 ? List.of(String.format(Locale.ROOT, "_m%03d", style), "_m000") : List.of("_m000");
        for (var number : styles) {
            var parts = new ArrayList<NpcModel>();
            for (var kind : front ? List.of("_m00_ah", "_m00_bh") : List.of("_m00_bh")) {
                var hair = hairPart(packageName, race, number, kind, frame, animation, stance, rig);
                if (hair != null) {
                    parts.add(hair);
                }
            }
            if (!parts.isEmpty()) {
                return parts;
            }
        }
        for (var number : style > 0 ? List.of(String.format(Locale.ROOT, "_h%02d", style), "_h00") : List.of("_h00")) {
            var parts = new ArrayList<NpcModel>();
            for (var kind : front ? List.of("_m005_ah", "_m005_bh") : List.of("_m005_bh")) {
                var texture = race + "." + race + number + kind + "_t00";
                if (resolveNpcMaterial(texture) == null) {
                    continue;
                }
                var hair = loadNpcModel(
                        packageName + "." + race + number + kind,
                        List.of(texture),
                        frame,
                        animation,
                        stance,
                        null,
                        false,
                        null,
                        rig);
                if (hair != null) {
                    parts.add(hair);
                }
            }
            if (!parts.isEmpty()) {
                return parts;
            }
        }
        return List.of();
    }

    private @Nullable NpcModel loadEars(
            String packageName,
            String race,
            USkeletalMesh frame,
            @Nullable UMeshAnimation animation,
            @Nullable String stance,
            @Nullable List<PlayerRigPart> rig) {
        var reference = packageName + "." + race + "_m000_m00_e";
        var found = findNpcMesh(reference);
        if (found == null) {
            return null;
        }
        if (ownTexture(found.mesh()) != null) {
            return loadNpcModel(reference, List.of(), frame, animation, stance, null, false, null, rig);
        }
        var standIn = BARE_STAND_INS.get(race.toLowerCase(Locale.ROOT) + "_e");
        return standIn == null || resolveNpcMaterial(standIn.texture()) == null
                ? null
                : loadNpcModel(reference, List.of(standIn.texture()), frame, animation, stance, null, false, null, rig);
    }

    private UMeshAnimation narrowFor(UMeshAnimation animation, String figure, @Nullable String classWait) {
        return narrowFor(animation, figure, classWait, null);
    }

    private UMeshAnimation narrowFor(
            UMeshAnimation animation, String figure, @Nullable String classWait, @Nullable String rowClass) {
        var sharedRaces = getFigureBodies().getSharedRaces();
        if (!PlayerFigures.sharesRaceAnimation(figure, sharedRaces)) {
            return animation;
        }
        if (classTable == null) {
            classTable = NpcSocials.read(clientRoot, variant);
        }
        var sharers = PlayerFigures.getSharers(figure);
        var others = new HashSet<String>();
        for (var sharer : sharers) {
            others.addAll(classTable.getSequences(PlayerFigures.getPawnClassName(sharer)));
        }
        if (!sharers.isEmpty() && others.isEmpty() && PlayerFigures.findClassFigure(figure) == null) {
            return animation;
        }
        var named = new HashSet<>(classTable.getSequences(PlayerFigures.getPawnClassName(figure)));
        if (rowClass != null) {
            named.addAll(classTable.getSequences(rowClass.substring(rowClass.indexOf('.') + 1)));
        }
        if (classWait != null) {
            named.add(classWait.toLowerCase(Locale.ROOT));
        }
        return PlayerFigures.narrow(animation, figure, named, others, sharedRaces);
    }

    private ClassHeads.@Nullable Head findClassHead(String figure) {
        if (PlayerFigures.findClassFigure(figure) == null) {
            return null;
        }
        return getClassHeads().find(PlayerFigures.getPawnClassName(figure));
    }

    private ClassHeads.@Nullable Head findOwnHair(String figure) {
        var pawnClass = PlayerFigures.getPawnClassName(figure);
        var own = getFigureBodies().find(pawnClass);
        if (own != null && own.head().findHair(0) != null) {
            return own.head();
        }
        var head = getClassHeads().find(pawnClass);
        return head != null && head.findHair(0) != null ? head : null;
    }

    private ClassHeads getClassHeads() {
        if (classHeads == null) {
            classHeads = ClassHeads.read(clientRoot, variant);
        }
        return classHeads;
    }

    private FigureBodies getFigureBodies() {
        if (figureBodies == null) {
            figureBodies = FigureBodies.read(clientRoot, variant);
        }
        return figureBodies;
    }

    private @Nullable NpcModel loadOwnHair(
            ClassHeads.Head head,
            int style,
            USkeletalMesh frame,
            @Nullable UMeshAnimation animation,
            @Nullable String stance,
            @Nullable List<PlayerRigPart> rig) {
        for (var number : style > 0 ? List.of(style, 0) : List.of(0)) {
            var mesh = head.findHair(number);
            if (mesh == null || findNpcMesh(mesh) == null) {
                continue;
            }
            var texture = head.findHairTexture(number);
            var textures =
                    texture.isEmpty() || resolveNpcMaterial(texture) == null ? List.<String>of() : List.of(texture);
            var hair = loadNpcModel(mesh, textures, frame, animation, stance, null, false, null, rig);
            if (hair != null) {
                return hair;
            }
        }
        return null;
    }

    private List<NpcModel> loadTableHair(
            List<HairRows.Hair> hair,
            USkeletalMesh frame,
            @Nullable UMeshAnimation animation,
            @Nullable String stance,
            @Nullable List<PlayerRigPart> rig) {
        var parts = new ArrayList<NpcModel>();
        for (var each : hair) {
            var found = findNpcMesh(each.mesh());
            var texture = each.texture();
            if (found == null
                    || (texture.isEmpty() || resolveNpcMaterial(texture) == null) && ownTexture(found.mesh()) == null) {
                continue;
            }
            var part = loadNpcModel(
                    each.mesh(),
                    texture.isEmpty() ? List.of() : List.of(texture),
                    frame,
                    animation,
                    stance,
                    null,
                    false,
                    null,
                    rig);
            if (part != null) {
                parts.add(part);
            }
        }
        return parts;
    }

    private HairRows getHairRows() {
        if (hairRows == null) {
            hairRows = HairRows.read(clientRoot, variant);
        }
        return hairRows;
    }

    private @Nullable NpcModel hairPart(
            String packageName,
            String race,
            String number,
            String kind,
            USkeletalMesh frame,
            @Nullable UMeshAnimation animation,
            @Nullable String stance,
            @Nullable List<PlayerRigPart> rig) {
        var texture = race + "." + race + number + "_t00" + kind;
        var mesh = packageName + "." + race + number + kind;
        if (resolveNpcMaterial(texture) == null) {
            var found = findNpcMesh(mesh);
            if (found == null || ownTexture(found.mesh()) == null) {
                return null;
            }
        }
        return loadNpcModel(mesh, List.of(texture), frame, animation, stance, null, false, null, rig);
    }

    private List<@Nullable UMaterial> npcMaterials(USkeletalMesh mesh, List<String> textureReferences) {
        var count = Math.min(
                textureReferences.size(), Math.max(mesh.getSectionMaterials().length, mesh.getMaterials().length));
        var listed = new ArrayList<@Nullable UMaterial>(count);
        for (var index = 0; index < count; index++) {
            var reference = textureReferences.get(index);
            listed.add(reference != null && !reference.isBlank() ? resolveNpcMaterial(reference) : null);
        }
        return drawsThroughMeshComponents()
                ? USkeletalMesh.pickSectionMaterials(mesh.getSectionMaterials(), listed, mesh::getTexture)
                : USkeletalMesh.pickClassicSectionMaterials(
                        mesh.getSectionMaterials(), mesh.getMaterials().length, listed, mesh::getMaterialTexture);
    }

    private @Nullable UMaterial ownTexture(USkeletalMesh mesh) {
        return drawsThroughMeshComponents() ? mesh.getSectionTexture(0) : mesh.getMaterialTexture(0);
    }

    private boolean drawsThroughMeshComponents() {
        if (meshComponents == null) {
            var found = false;
            try {
                var engine = packageLoader.loadPackageIfPresent("Engine");
                if (engine.isPresent()) {
                    for (var export : engine.get().getArchive().getExportMap()) {
                        if (export.getObjectName().equals("MeshComponent") && "Object".equals(export.getSuperName())) {
                            found = true;
                            break;
                        }
                    }
                }
            } catch (RuntimeException unreadable) {
                log.debug("No Engine.u to read the class MeshComponent from: {}", unreadable.toString());
            }
            meshComponents = found;
        }
        return meshComponents;
    }

    private long[] npcMaterialFlags(USkeletalMesh mesh, List<String> textureReferences) {
        var materials = npcMaterials(mesh, textureReferences);
        var flags = new long[materials.size()];
        for (var section = 0; section < flags.length; section++) {
            flags[section] = drawnPolygonFlags(mesh.getSectionPolygonFlags(section), materials.get(section));
        }
        return flags;
    }

    private @Nullable UMaterial resolveNpcMaterial(String reference) {
        var dot = reference.indexOf('.');
        if (dot <= 0) {
            return null;
        }
        var uPackage = packageLoader.loadPackageIfPresent(reference.substring(0, dot));
        if (uPackage.isEmpty()) {
            return null;
        }
        return uPackage.get().findObject(null, reference.substring(reference.lastIndexOf('.') + 1))
                        instanceof UMaterial material
                ? material
                : null;
    }

    private EmitterVisibility visibilityOf(EmitterActor actor, Map<String, EmitterVisibility> cache) {
        return cache.computeIfAbsent(
                actor.getFullName(),
                ignored -> new EmitterVisibility(
                        actor.getVisibleLimitMin(),
                        actor.getVisibleLimitMax(),
                        actor.getVisibilityInterpRange(),
                        new EmitterResetPolicy(
                                actor.isAutoReset(),
                                actor.isAutoDestroy(),
                                actor.isNoDelete(),
                                actor.isPlaced(),
                                actor.getTimeTillResetMin(),
                                actor.getTimeTillResetMax(),
                                emitterClientRule(),
                                isAlwaysUpdated(actor)),
                        actor.isIgnoredRange() ? actor.getRangeForIgnoredRange() : 0f,
                        actor.isDisableFogging(),
                        actor.getGameEvents(),
                        actor.isRotEmitter(),
                        actor.getRotation(),
                        actor.getRotPerSecond(),
                        actor.isSelfRotation(),
                        spawnSoundsOf(actor),
                        zoneRenderOf(actor)));
    }

    private ZoneRender zoneRenderOf(EmitterActor actor) {
        var mask = actor.getZoneRenderMask();
        return mask == 0 || !hasEssenceZoneStates()
                ? ZoneRender.EVERY_STATE
                : new ZoneRender(mask, zoneStart(actor), true, ZoneBlend.of(actor.getRegionZone()), zoneMap(actor));
    }

    private ZoneRender terrainZoneRender(UTerrainInfoActor terrain, int[] states) {
        var mask = UActor.zoneStateMask(states);
        if (mask == 0 || !hasEssenceZoneStates()) {
            return ZoneRender.EVERY_STATE;
        }
        var zone = terrain.getRegionZone();
        return ZoneRender.ofTerrainLayer(
                mask, zone != null && zone.isUseTerrainZoneState(), zoneStart(terrain), zoneMap(terrain));
    }

    private Consumer<EmitterActor> spawnedParents(String packageName, String className) {
        return actor -> {
            var archives = packageLoader.getArchiveLoader();
            actor.takeEngineLifetime(archives.findClassDefaults("Engine", "Emitter"));
            var chain = archives.findEmitterChain(packageName, className);
            for (var i = chain.size() - 1; i >= 0; i--) {
                var link = chain.get(i);
                var engineEmitter = link.packageName().equalsIgnoreCase("Engine")
                        && link.className().equalsIgnoreCase("Emitter");
                if (!engineEmitter && !link.packageName().equalsIgnoreCase(packageName)) {
                    actor.takeLifetime(archives.findClassDefaults(link.packageName(), link.className()));
                }
            }
        };
    }

    private EmitterResetPolicy.ClientRule emitterClientRule() {
        if (emitterClientRule == null) {
            UArchive engine = null;
            try {
                var found = packageLoader.loadPackageIfPresent("Engine");
                engine = found.isPresent() ? found.get().getArchive() : null;
            } catch (RuntimeException unreadable) {
                log.debug("No Engine.u to read the class Emitter from: {}", unreadable.toString());
            }
            emitterClientRule = emitterClientRule(engine);
        }
        return emitterClientRule;
    }

    private EmitterResetPolicy.ClientRule emitterClientRule(@Nullable UArchive engine) {
        if (engine == null) {
            return EmitterResetPolicy.ClientRule.POST_UPDATE;
        }
        var exports = engine.getExportMap();
        var emitter = 0;
        for (var index = 1; index <= exports.size(); index++) {
            var export = exports.get(index - 1);
            if (export.getObjectName().equals("Emitter") && "None".equals(export.getClassName())) {
                emitter = index;
            }
        }
        if (emitter == 0) {
            return EmitterResetPolicy.ClientRule.POST_UPDATE;
        }
        var rule = EmitterResetPolicy.ClientRule.TICK;
        for (var export : exports) {
            if (export.getPackageIndex() == emitter) {
                if (export.getObjectName().equalsIgnoreCase("AccDeltatime")) {
                    return EmitterResetPolicy.ClientRule.P152;
                }
                if (export.getObjectName().equalsIgnoreCase("bRendered")) {
                    rule = EmitterResetPolicy.ClientRule.HIGH_FIVE;
                }
            }
        }
        return rule;
    }

    private boolean isAlwaysUpdated(EmitterActor actor) {
        for (var child : actor.getChildEmitters()) {
            if (child instanceof UBeamEmitter
                    || child instanceof UMeshEmitter
                    || child instanceof UVertMeshEmitter
                    || child instanceof UTrailEmitter) {
                return true;
            }
        }
        return false;
    }

    private boolean hasEssenceZoneStates() {
        if (essenceZoneStates == null) {
            var found = false;
            try {
                var engine = packageLoader.loadPackageIfPresent("Engine");
                if (engine.isPresent()) {
                    for (var export : engine.get().getArchive().getExportMap()) {
                        if (export.getObjectName().equals("ZoneStateInfo") && "Struct".equals(export.getClassName())) {
                            found = true;
                            break;
                        }
                    }
                }
            } catch (RuntimeException unreadable) {
                log.debug("No Engine.u to read the struct ZoneStateInfo from: {}", unreadable.toString());
            }
            essenceZoneStates = found;
        }
        return essenceZoneStates;
    }

    private List<EmitterSound> spawnSoundsOf(EmitterActor actor) {
        var count = actor.getSpawnSoundCount();
        if (count == 0 || !(actor.getSoundRadius() > 0f) || !(actor.getSoundVolume() > 0f)) {
            return List.of();
        }
        var sounds = new ArrayList<EmitterSound>(count);
        for (var i = 0; i < count; i++) {
            var fullName = actor.peekSpawnSoundName(i);
            if (fullName == null) {
                continue;
            }
            var index = i;
            var clip = soundClips.get(fullName, () -> actor.loadSpawnSound(index));
            if (clip == null) {
                continue;
            }
            sounds.add(new EmitterSound(
                    clip,
                    actor.getSoundRadius(),
                    actor.getSoundVolume() / 255f,
                    actor.getSoundPitchMin(),
                    actor.getSoundPitchMax(),
                    actor.isSoundLooping(),
                    actor.isUiSound(),
                    actor.getSoundDelay(),
                    actor.getSoundFadeInDuration(),
                    actor.getSoundFadeOutStart(),
                    actor.getSoundFadeOutDuration()));
        }
        return sounds;
    }

    private record ActorLook(List<UObject> skins, ZoneRender render, boolean own) {}

    private List<ActorLook> zoneLooksOf(StaticMeshActor actor) {
        if (!hasEssenceZoneStates() || actor instanceof UMover) {
            return List.of();
        }
        var start = zoneStart(actor);
        if (start == ZoneRender.NO_ZONE_STATES) {
            return List.of();
        }
        var mask = actor.getZoneRenderMask();
        var blend = ZoneBlend.of(actor.getRegionZone());
        var groups = new LinkedHashMap<List<UObject>, Integer>();
        var looked = 0;
        for (var state = 1; state <= MapEventState.MAX_ZONE_STATE; state++) {
            var bit = 1 << (state - 1);
            var skins = mask == 0 || (mask & bit) != 0 ? actor.getZoneStateSkins(state) : null;
            if (skins != null) {
                groups.merge(skins, bit, (first, second) -> first | second);
                looked |= bit;
            }
        }
        if (looked == 0) {
            return List.of();
        }
        var looks = new ArrayList<ActorLook>(groups.size() + 1);
        var own = mask == 0
                ? new ZoneRender(~looked, start, false, blend, zoneMap(actor)).inEssence()
                : new ZoneRender(mask & ~looked, start, true, blend, zoneMap(actor));
        if (own.mask() != 0) {
            looks.add(new ActorLook(actor.getSkins(), own, true));
        }
        for (var group : groups.entrySet()) {
            looks.add(new ActorLook(
                    group.getKey(), new ZoneRender(group.getValue(), start, true, blend, zoneMap(actor)), false));
        }
        for (var look = 0; look < looks.size(); look++) {
            if (looks.get(look).render().shows(ZoneStateChoice.EMPTY)) {
                looks.addFirst(looks.remove(look));
                break;
            }
        }
        return looks;
    }

    private EntityMesh applyStaticMeshActorSkins(EntityMesh mesh, UStaticMesh sourceMesh, List<UObject> authoredSkins) {
        var skins = renderedStaticMeshOverrides(sourceMesh, authoredSkins);
        if (skins.isEmpty()) {
            return mesh;
        }
        var surfaces = new ArrayList<Entity.Surface>(mesh.surfaces().size());
        var changed = false;
        for (var i = 0; i < mesh.surfaces().size(); i++) {
            var source = mesh.surfaces().get(i);
            if (i >= skins.size() || !(skins.get(i) instanceof UMaterial materialNode)) {
                surfaces.add(source);
                continue;
            }
            var material = loadMaterial(materialNode);
            if (material == null) {
                surfaces.add(source);
                continue;
            }
            var blend = blendModeOf(materialNode);
            surfaces.add(new Entity.Surface(
                    source.type(),
                    source.indexOffset(),
                    source.indexCount(),
                    material,
                    blend,
                    uvAnimationOf(materialNode),
                    source.water(),
                    materialNode.getFullName(),
                    alphaRefOf(materialNode, blend),
                    usesVertexColor(materialNode),
                    writesDepth(materialNode, blend),
                    modulatesStaticLighting2x(materialNode)));
            changed = true;
        }
        return changed ? withSurfaces(mesh, surfaces) : mesh;
    }

    private List<Texture> beastLightmapTextures(@Nullable UStaticMeshInstance instance) {
        if (instance == null || instance.getBeastLightmapIndices().length == 0) {
            return List.of();
        }
        var slots = new Texture[2];
        for (var index : instance.getBeastLightmapIndices()) {
            if (index == 0) {
                continue;
            }
            try {
                var object = instance.getArchive().getObjectLoader().loadObject(index);
                if (!(object instanceof UTexture uTexture)) {
                    return List.of();
                }
                var slot = beastLightmapSlot(uTexture.getName());
                var texture = slot < 0 ? null : loadTexture(uTexture);
                if (texture == null) {
                    log.debug("Beast lightmap {} unusable", uTexture.getName());
                    return List.of();
                }
                slots[slot] = texture;
            } catch (RuntimeException e) {
                log.debug("Beast lightmap {} unavailable: {}", index, e.toString());
                return List.of();
            }
        }
        var template = slots[0] != null ? slots[0] : slots[1];
        if (template == null) {
            return List.of();
        }
        for (var slot = 0; slot < slots.length; slot++) {
            if (slots[slot] == null) {
                slots[slot] = blackTextureLike(template);
            } else if (slots[slot].width() != template.width()
                    || slots[slot].height() != template.height()
                    || slots[slot].format() != template.format()) {
                log.debug("Beast lightmap slots of {} differ in size", instance);
                return List.of();
            }
        }
        return List.of(slots[0], slots[1]);
    }

    record MeshEmitterSurfaces(EntityMesh mesh, int particleColor, int unlit, int severalPasses) {}

    private MeshEmitterSurfaces applyMeshEmitterSurfaceRules(
            EntityMesh mesh, UStaticMesh sourceMesh, List<UObject> authoredOverrides, UMeshEmitter emitter) {
        if (!emitter.isUseMeshBlendMode()) {
            return new MeshEmitterSurfaces(mesh, 0, 0, 0);
        }
        var overrides = renderedStaticMeshOverrides(sourceMesh, authoredOverrides);
        var surfaces = new ArrayList<Entity.Surface>(mesh.surfaces().size());
        var particleColor = 0;
        var unlit = 0;
        var severalPasses = 0;
        for (var i = 0; i < mesh.surfaces().size(); i++) {
            var source = mesh.surfaces().get(i);
            var node = meshEmitterMaterialNode(sourceMesh, overrides, i, source.materialName());
            if (i < Integer.SIZE && MeshParticleMaterials.takesNoLight(node)) {
                unlit |= 1 << i;
            }
            if (i < Integer.SIZE && MeshParticleMaterials.drawsInSeveralPasses(node)) {
                severalPasses |= 1 << i;
            }
            if (i >= Integer.SIZE || !MeshParticleMaterials.drawsConstantAsParticleColor(node)) {
                surfaces.add(source);
                continue;
            }
            EntityMaterial material;
            constantsFromParticleColor = true;
            try {
                material = loadMaterial(node);
            } finally {
                constantsFromParticleColor = false;
            }
            if (!(material instanceof CombinerEntityMaterial)) {
                surfaces.add(source);
                continue;
            }
            particleColor |= 1 << i;
            surfaces.add(new Entity.Surface(
                    source.type(),
                    source.indexOffset(),
                    source.indexCount(),
                    material,
                    source.blendMode(),
                    source.uvAnimation(),
                    source.water(),
                    source.materialName(),
                    source.alphaRef(),
                    source.usesVertexColor(),
                    source.depthWrite(),
                    source.modulateStaticLighting2x()));
        }
        if (particleColor == 0) {
            return new MeshEmitterSurfaces(mesh, 0, unlit, severalPasses);
        }
        return new MeshEmitterSurfaces(
                new EntityMesh(
                        mesh.vertices(),
                        mesh.indices(),
                        surfaces,
                        mesh.instanceMatrices(),
                        mesh.boundingBox(),
                        mesh.swayFrequency(),
                        mesh.swayMaxAngle(),
                        mesh.billboard(),
                        mesh.staticMeshInstanceLighting()),
                particleColor,
                unlit,
                severalPasses);
    }

    private EntityMesh applyMeshEmitterMaterialOverrides(
            EntityMesh mesh, UStaticMesh sourceMesh, List<UObject> authoredOverrides, UMeshEmitter emitter) {
        var overrides = renderedStaticMeshOverrides(sourceMesh, authoredOverrides);
        if (overrides.isEmpty()) {
            return mesh;
        }
        var surfaces = new ArrayList<Entity.Surface>(mesh.surfaces().size());
        var changed = false;
        for (var i = 0; i < mesh.surfaces().size(); i++) {
            var source = mesh.surfaces().get(i);
            if (i >= overrides.size() || !(overrides.get(i) instanceof UMaterial materialNode)) {
                surfaces.add(source);
                continue;
            }
            var material = loadMaterial(materialNode);
            if (material == null) {
                surfaces.add(source);
                continue;
            }
            var blend = emitter.isUseMeshBlendMode()
                    ? effectiveFxBlend(blendModeOf(materialNode), material)
                    : source.blendMode();
            var type = source.type();
            if ((type & SurfaceType.TWO_SIDED_DOUBLED.getId()) == 0) {
                type = isTwoSided(materialNode)
                        ? type | SurfaceType.MESH_TWO_SIDED.getId()
                        : type & ~SurfaceType.MESH_TWO_SIDED.getId();
            }
            surfaces.add(new Entity.Surface(
                    type,
                    source.indexOffset(),
                    source.indexCount(),
                    material,
                    blend,
                    uvAnimationOf(materialNode),
                    source.water(),
                    materialNode.getFullName(),
                    alphaRefOf(materialNode, blend),
                    usesVertexColor(materialNode),
                    writesDepth(materialNode, blend),
                    modulatesStaticLighting2x(materialNode)));
            changed = true;
        }
        return changed
                ? new EntityMesh(
                        mesh.vertices(),
                        mesh.indices(),
                        surfaces,
                        mesh.instanceMatrices(),
                        mesh.boundingBox(),
                        mesh.swayFrequency(),
                        mesh.swayMaxAngle(),
                        mesh.billboard(),
                        mesh.staticMeshInstanceLighting())
                : mesh;
    }

    private List<Entity> loadBspEntities(
            UPackage uPackage,
            Box mapBoundingBox,
            @Nullable MeshPlaneIndex meshIndex,
            boolean csgConcat,
            List<ReflectionBspModel> reflectionBspModels,
            @Nullable Set<Integer> surfaces) {
        var entities = new ArrayList<Entity>();
        var levels = new ArrayList<ULevel>();
        uPackage.loadObjects("Level", levels);
        if (levels.isEmpty()) {
            throw new IllegalStateException("No levels in package: " + uPackage.getName());
        }
        var levelInfos = new ArrayList<ZoneInfoActor>();
        uPackage.loadObjects("LevelInfo", levelInfos);
        var levelInfo = levelInfos.isEmpty() ? null : levelInfos.getFirst();
        for (var level : levels) {
            var model = level.getModel().get();
            reflectionBspModels.add(new ReflectionBspModel(model));
            entities.addAll(loadModelEntity(
                    model, mapBoundingBox, true, Optional.empty(), false, meshIndex, csgConcat, levelInfo, surfaces));
        }
        for (var ordinal = 0; ordinal < entities.size(); ordinal++) {
            entities.get(ordinal).setBspOrdinal(ordinal);
        }
        return entities;
    }

    record WaterSecondLayer(Texture texture, UvAnimation animation, float rotation, Vec3 additive) {}

    private void loadWaterVolumes(UPackage uPackage, List<WaterVolume> waterVolumes) {
        var volumes = new ArrayList<WaterVolumeActor>();
        uPackage.loadObjects("WaterVolume", volumes);
        if (volumes.isEmpty()) {
            return;
        }
        var ownerTile = WorldTile.isMapName(uPackage.getName()) ? WorldTile.fromMapName(uPackage.getName()) : null;
        var switched = switchesWaterVolumes(uPackage);
        for (var volume : volumes) {
            var water = waterVolume(volume, ownerTile, switched ? waterZoneRender(volume) : ZoneRender.EVERY_STATE);
            if (water != null) {
                waterVolumes.add(water);
            }
        }
    }

    private boolean switchesWaterVolumes(UPackage uPackage) {
        if (!hasEssenceZoneStates()) {
            return false;
        }
        var zones = new ArrayList<ZoneInfoActor>();
        uPackage.loadObjects("ZoneInfo", zones);
        for (var zone : zones) {
            if (zone.isUseWaterZoneState() && zone.isUseZoneState()) {
                return true;
            }
        }
        return false;
    }

    private List<MapPawn> loadMapPawns(UPackage uPackage) {
        try {
            return MapPawn.readAll(uPackage);
        } catch (RuntimeException unreadable) {
            log.warn("The pawns of map {} cannot be read: {}", uPackage.getName(), unreadable.toString());
            return List.of();
        }
    }

    private List<AmbientSound> loadAmbientSounds(UPackage uPackage, List<ReflectionBspModel> reflectionBspModels) {
        var actors = new ArrayList<AmbientSoundActor>();
        uPackage.loadObjects("AmbientSoundObject", actors);
        uPackage.loadObjects("AmbientSound", actors);
        var result = new ArrayList<AmbientSound>(actors.size());
        for (var actor : actors) {
            if (actor.isDeleteMe() || !(actor.getSoundRadius() > 0f)) {
                continue;
            }
            var fullName = actor.getAmbientSound().peekFullName();
            if (fullName == null) {
                continue;
            }
            var type = Type.of(actor.getAmbientSoundType());
            if (type == null) {
                continue;
            }
            var clip = soundClips.get(fullName, () -> actor.getAmbientSound().get());
            if (clip == null) {
                continue;
            }
            var position = actor.getPosition();
            result.add(new AmbientSound(
                    position,
                    actor.getSoundRadius(),
                    actor.getSoundVolume() / 255f,
                    actor.getSoundPitch() / 64f,
                    type,
                    AmbientSound.chanceOf(actor.getAmbientRandom()),
                    clip,
                    actor.getFullName(),
                    soundZoneRender(actor.getZonePlayMask(), position, reflectionBspModels),
                    null,
                    actor.getAmbientSoundStartTime(),
                    AmbientSoundHours.ofAuthored(actor.getAmbientSoundRangeTime())));
        }
        var listActors = new ArrayList<AmbientSoundWObjectActor>();
        uPackage.loadObjects("AmbientSoundWObject", listActors);
        for (var actor : listActors) {
            var type = Type.of(actor.getAmbientSoundType());
            if (actor.isDeleteMe() || !(actor.getSoundRadius() > 0f) || type == null) {
                continue;
            }
            var clips = new ArrayList<SoundClip>(actor.getSoundCount());
            for (var i = 0; i < actor.getSoundCount(); i++) {
                var fullName = actor.peekSoundName(i);
                if (fullName == null) {
                    continue;
                }
                var index = i;
                var clip = soundClips.get(fullName, () -> actor.loadSound(index));
                if (clip != null) {
                    clips.add(clip);
                }
            }
            if (clips.isEmpty()) {
                continue;
            }
            var position = actor.getPosition();
            result.add(new AmbientSound(
                    position,
                    actor.getSoundRadius(),
                    actor.getSoundVolume() / 255f,
                    actor.getSoundPitch() / 64f,
                    type,
                    AmbientSound.LOOPS,
                    clips.getFirst(),
                    actor.getFullName(),
                    soundZoneRender(actor.getZonePlayMask(), position, reflectionBspModels),
                    new AmbientSoundList(
                            clips, PlayMode.of(actor.getPlayMode()), actor.getInitialDelay(), actor.getDuration()),
                    0f,
                    AmbientSoundHours.ofAuthored(actor.getAmbientSoundRangeTime())));
        }
        return result;
    }

    private ZoneRender soundZoneRender(int mask, Vec3 position, List<ReflectionBspModel> reflectionBspModels) {
        var zoneStart = ZoneRender.NO_ZONE_STATES;
        var map = "";
        if (mask != 0) {
            for (var model : reflectionBspModels) {
                zoneStart = model.getZoneStartAt(position);
                if (zoneStart != ZoneRender.NO_ZONE_STATES) {
                    map = model.getMap();
                    break;
                }
            }
        }
        return ZoneRender.ofSound(mask, zoneStart, hasEssenceZoneStates(), map);
    }

    private List<AmbientVolume> loadAmbientVolumes(UPackage uPackage) {
        var actors = new ArrayList<AmbientVolumeActor>();
        uPackage.loadObjects("AmbientVolume", actors);
        if (actors.isEmpty()) {
            return List.of();
        }
        var essence = hasEssenceZoneStates();
        var ownerTile = WorldTile.isMapName(uPackage.getName()) ? WorldTile.fromMapName(uPackage.getName()) : null;
        var result = new ArrayList<AmbientVolume>(actors.size());
        for (var actor : actors) {
            if (actor.isDeleteMe() || !actor.isCollideActors()) {
                continue;
            }
            var brush = actor.getBrush().get();
            if (brush == null || brush.getPoints().length == 0) {
                continue;
            }
            var sounds = new ArrayList<AmbientVolume.Sound>(actor.getSoundCount());
            for (var i = 0; i < actor.getSoundCount(); i++) {
                var sound = actor.loadSound(i);
                if (sound == null) {
                    continue;
                }
                var fullName = sound.getSound().peekFullName();
                if (fullName == null) {
                    continue;
                }
                var clip = soundClips.get(fullName, () -> sound.getSound().get());
                if (clip != null) {
                    sounds.add(new AmbientVolume.Sound(
                            sound.getType(), sound.getVolume(), sound.getPitch(), sound.getRandom(), clip));
                }
            }
            if (sounds.isEmpty()) {
                continue;
            }
            var zoneRender = ZoneRender.EVERY_STATE;
            var events = AmbientVolume.Events.NONE;
            if (essence) {
                var mask = UActor.zoneStateMask(actor.getZonePlayStates());
                var zone = actor.getRegionZone();
                if (mask != 0 && zone != null) {
                    zoneRender = new ZoneRender(
                            mask,
                            zone.isUseZoneState() ? zone.getCurZoneState() : ZoneRender.NO_ZONE_STATES,
                            true,
                            zoneMap(actor));
                }
                var entries = actor.getGameEvents();
                if (!entries.isEmpty()) {
                    var playIds = new ArrayList<Integer>();
                    for (var entry : entries) {
                        if (entry[1] == MapEventState.PLAY) {
                            playIds.add(entry[0]);
                        }
                    }
                    events = new AmbientVolume.Events(true, actor.isEventPlaying(), playIds);
                }
            }
            result.add(new AmbientVolume(
                    actor.getFullName(),
                    new BrushVolume(
                            brush,
                            BrushTransforms.actorMatrix(
                                    actor.getLocation(), actor.getPrePivot(), actor.getRotation(), actor.getScale())),
                    ownerTile,
                    actor.getPriority(),
                    actor.getFadeInTime(),
                    actor.getFadeOutTime(),
                    sounds,
                    zoneRender,
                    events,
                    essence));
        }
        return result;
    }

    private LocalSky loadLocalSky(UPackage uPackage, List<SpriteEmitterDescriptor> sprites) {
        var skyZones = loadSkyZones(uPackage);
        var zones = new ArrayList<SkyZoneInfoActor>();
        uPackage.loadObjects("SkyZoneInfo", zones);
        if (zones.isEmpty()) {
            return skyZones.isEmpty() ? null : new LocalSky(null, List.of(), false, skyZones);
        }
        var ownSky = !zones.getFirst().isUseDefaultMap() || !hasSharedSky();
        if (!ownSky && skyZones.isEmpty()) {
            return null;
        }
        var zone = zones.getFirst();
        var actors = new ArrayList<EmitterActor>();
        uPackage.loadEmitterActors(actors);
        var skyActors = new HashSet<String>();
        for (var actor : actors) {
            if (actor.getRegionZoneNumber() == 0 || actor.getRegionZoneNumber() == zone.getRegionZoneNumber()) {
                skyActors.add(actor.getFullName());
            }
        }
        USky sky;
        if (ownSky) {
            sky = buildSky(uPackage, zone.getRegionZoneNumber());
        } else {
            try {
                sky = buildSky(uPackage, zone.getRegionZoneNumber());
            } catch (RuntimeException unbuilt) {
                log.warn("The own sky of {} could not be built: {}", uPackage.getName(), unbuilt.toString());
                sky = null;
            }
        }
        return new LocalSky(
                sky,
                sprites.stream()
                        .filter(sprite -> skyActors.contains(sprite.actorName()))
                        .toList(),
                ownSky,
                skyZones);
    }

    private List<SkyZone> loadSkyZones(UPackage uPackage) {
        if (!hasEssenceZoneStates()) {
            return List.of();
        }
        var actors = new ArrayList<L2SkyLevelZoneInfoActor>();
        uPackage.loadObjects("L2SkyLevelZoneInfo", actors);
        var result = new ArrayList<SkyZone>(actors.size());
        for (var actor : actors) {
            if (actor.isDeleteMe()) {
                continue;
            }
            var states = actor.getZoneRenderStates();
            var listed = states.length > 0;
            var render = listed
                    ? new ZoneRender(
                            SkyZone.maskOf(states),
                            zoneStart(actor),
                            true,
                            ZoneBlend.of(actor.getRegionZone()),
                            zoneMap(actor))
                    : ZoneRender.EVERY_STATE;
            result.add(new SkyZone(
                    actor.getFullName(),
                    actor.getLocation(),
                    actor.getAffectBoxMax(),
                    actor.getAffectBoxMin(),
                    actor.getTimeEnv(),
                    actor.getTime(),
                    actor.isUseDefaultMap(),
                    actor.isUseTimeZone(),
                    actor.isRealTime(),
                    actor.isShowSunActor(),
                    actor.getZoneStateChangeTime(),
                    listed,
                    render));
        }
        return List.copyOf(result);
    }

    private boolean hasSharedSky() {
        var known = sharedSky;
        if (known == null) {
            known = WorldMapCatalog.hasSharedSky(clientRoot);
            sharedSky = known;
        }
        return known;
    }

    private List<L2FogInfo> loadL2FogInfos(UPackage uPackage) {
        var actors = new ArrayList<L2FogInfoActor>();
        uPackage.loadObjects("L2FogInfo", actors);
        var result = new ArrayList<L2FogInfo>();
        for (var actor : actors) {
            if (actor.isDeleteMe()) {
                continue;
            }
            var byName = new TreeMap<String, UProperty>(String.CASE_INSENSITIVE_ORDER);
            actor.getFogProperties().forEach(property -> byName.put(property.getName(), property));
            var affect = l2Range(byName.get("AffectRange"));
            var fogRanges = new ArrayList<Range>(5);
            for (var preset = 1; preset <= 5; preset++) {
                var range = l2Range(byName.get("FogRange" + preset));
                if (range == null) {
                    fogRanges.clear();
                    break;
                }
                fogRanges.add(range);
            }
            var colors = l2Colors(byName.get("Colors"));
            if (affect == null || fogRanges.isEmpty() || colors.isEmpty()) {
                log.debug("Skipping incomplete L2FogInfo {}", actor.getName());
                continue;
            }
            var useBox = byName.get("bUseAffectBox");
            var inner = byName.get("affectBoxMin");
            var outer = byName.get("affectBoxMax");
            List<Integer> sharedCloudIndices = null;
            var colorRows = byName.get("Colors").getSubProperties();
            var cloudData = colorRows.getFirst().get("CloudColor").getDataValue();
            if (cloudData != null && cloudData.length == 4) {
                sharedCloudIndices = new ArrayList<>();
                var setup = loadEnvSetup();
                if (setup != null) {
                    for (var index = 0; index < setup.clouds().size(); index++) {
                        if (setup.clouds().get(index).contains("Cloud_Final")) {
                            sharedCloudIndices.add(index);
                        }
                    }
                }
            }
            var boxed = useBox != null && useBox.isTrue() && inner != null && outer != null;
            result.add(new L2FogInfo(
                    actor.getPosition(),
                    affect,
                    fogRanges,
                    colors,
                    boxed ? inner.getVectorValue() : null,
                    boxed ? outer.getVectorValue() : null,
                    sharedCloudIndices,
                    zoneRenderOf(actor)));
        }
        return List.copyOf(result);
    }

    private ZoneRender zoneRenderOf(L2FogInfoActor actor) {
        var mask = actor.getZoneRenderMask();
        return mask == 0 || !hasEssenceZoneStates()
                ? ZoneRender.EVERY_STATE
                : new ZoneRender(mask, zoneStart(actor), true, ZoneBlend.of(actor.getRegionZone()), zoneMap(actor));
    }

    private List<ProjectorDescriptor> loadProjectorDescriptors(UPackage uPackage) {
        var actors = new ArrayList<ProjectorActor>();
        uPackage.loadObjects("Projector", actors);
        if (actors.isEmpty()) {
            return List.of();
        }
        var result = new ArrayList<ProjectorDescriptor>(actors.size());
        for (var actor : actors) {
            if (actor.isDeleteMe()) {
                continue;
            }
            var unrealMaterial = actor.getProjTexture();
            var material = loadMaterial(unrealMaterial);
            var texture = material == null ? null : material.getTexture();
            if (material == null || texture == null) {
                continue;
            }
            var uSize = actor.getDrawScale() * texture.width();
            var vSize = actor.getDrawScale() * texture.height();
            if (!(uSize > 0f) || !(vSize > 0f)) {
                continue;
            }
            result.add(new ProjectorDescriptor(
                    actor.getFullName(),
                    actor.getLocation(),
                    ProjectorDescriptor.basis(actor.getRotation()),
                    material,
                    actor.getProjTextureName(),
                    actor.getMaterialBlendingOp(),
                    actor.getFrameBufferBlendingOp(),
                    uSize,
                    vSize,
                    actor.getFov(),
                    actor.getMaxTraceDistance(),
                    actor.isProjectBsp(),
                    actor.isProjectTerrain(),
                    actor.isProjectStaticMesh(),
                    actor.isProjectParticles(),
                    actor.isProjectActor(),
                    actor.isLevelStatic(),
                    actor.isClipBsp(),
                    actor.isClipStaticMesh(),
                    actor.isProjectOnUnlit(),
                    actor.isGradient(),
                    actor.isProjectOnBackfaces(),
                    actor.isProjectOnAlpha(),
                    actor.isProjectOnParallelBsp(),
                    actor.getProjectTag(),
                    actor.isDynamicAttach(),
                    actor.getProjectorRadius()));
        }
        return List.copyOf(result);
    }

    private List<L2Swing> loadSwings(UPackage uPackage) {
        var meshes = new ArrayList<UMovableStaticMeshActor>();
        uPackage.loadObjects("MovableStaticMeshActor", meshes);
        if (meshes.isEmpty()) {
            return List.of();
        }
        if (!meshes.getFirst().isSwingResolved()) {
            resolveSwings(uPackage, meshes);
        }
        var swings = new ArrayList<L2Swing>();
        for (var mesh : meshes) {
            if (mesh.getResolvedSwing() != null) {
                swings.add(mesh.getResolvedSwing());
            }
        }
        return List.copyOf(swings);
    }

    private void resolveSwings(UPackage uPackage, List<UMovableStaticMeshActor> loaded) {
        var registered = levelActorPaths(uPackage);
        var meshes = new ArrayList<UMovableStaticMeshActor>();
        var anyTag = false;
        for (var mesh : loaded) {
            mesh.setResolvedSwing(null);
            if (!mesh.isDeleteMe() && (registered == null || registered.contains(mesh.getSourcePath()))) {
                meshes.add(mesh);
                anyTag |= !L2Attach.getAttachTags(mesh.getMovementTags()).isEmpty();
            }
        }
        var swinging = new HashSet<UMovableStaticMeshActor>();
        for (var mesh : meshes) {
            if (mesh.getSwing(List.of(), null) != null) {
                swinging.add(mesh);
            }
        }
        var followers = new HashMap<UMovableStaticMeshActor, List<L2Swing.Follower>>();
        var carriers = new HashMap<UActor, UMovableStaticMeshActor>();
        if (anyTag) {
            var actors = new ArrayList<UActor>();
            uPackage.loadEmitterActors(actors);
            for (var className : new String[] {"Light", "Spotlight", "StaticMeshActor", "MovableStaticMeshActor"}) {
                uPackage.loadObjects(className, actors);
            }
            var byPath = new HashMap<String, UActor>();
            var candidates = new ArrayList<L2Attach.Candidate>();
            for (var actor : actors) {
                if (actor.isDeleteMe() || registered != null && !registered.contains(actor.getSourcePath())) {
                    continue;
                }
                byPath.putIfAbsent(actor.getFullName(), actor);
                candidates.add(new L2Attach.Candidate(
                        actor.getFullName(), actor.getL2MoveEvent(), actor.isStatic(), actor.isMovable()));
            }
            var moving = new ArrayList<L2Attach.Carrier>();
            var meshByPath = new HashMap<String, UMovableStaticMeshActor>();
            for (var mesh : meshes) {
                if (swinging.contains(mesh)) {
                    moving.add(new L2Attach.Carrier(mesh.getFullName(), mesh.getMovementTags()));
                    meshByPath.put(mesh.getFullName(), mesh);
                }
            }
            L2Attach.attach(moving, candidates).forEach((path, attached) -> {
                var mesh = meshByPath.get(path);
                for (var candidate : attached) {
                    var actor = byPath.get(candidate.path());
                    var kind = switch (actor) {
                        case EmitterActor ignored -> L2Swing.Kind.EMITTER;
                        case LightActor ignored -> L2Swing.Kind.LIGHT;
                        case UMovableStaticMeshActor other when swinging.contains(other) -> L2Swing.Kind.SWING;
                        default -> L2Swing.Kind.MESH;
                    };
                    carriers.put(actor, mesh);
                    followers
                            .computeIfAbsent(mesh, ignored -> new ArrayList<>())
                            .add(new L2Swing.Follower(
                                    actor.getFullName(), kind, actor.getLocation(), actor.getRotation()));
                }
            });
        }
        var swings = new HashMap<UMovableStaticMeshActor, L2Swing>();
        for (var mesh : meshes) {
            if (!swinging.contains(mesh)) {
                continue;
            }
            var carrier = carriers.get(mesh);
            var swing = mesh.getSwing(
                    followers.getOrDefault(mesh, List.of()), carrier == null ? null : carrier.getFullName());
            mesh.setResolvedSwing(swing);
            swings.put(mesh, swing);
            mesh.setMotionLink(MotionLink.ofSwing(swing, mesh.getScale(), mesh.getPrePivot()));
        }
        for (var entry : carriers.entrySet()) {
            var actor = entry.getKey();
            if (actor instanceof StaticMeshActor
                    && !(actor instanceof UMovableStaticMeshActor other && swinging.contains(other))) {
                var swing = swings.get(entry.getValue());
                for (var follower : swing.followers()) {
                    if (follower.actorPath().equals(actor.getFullName())) {
                        var turn = turnOf((StaticMeshActor) actor, swing.actorPath());
                        actor.setMotionLink(
                                MotionLink.ofCarried(swing, follower, turn, actor.getScale(), actor.getPrePivot()));
                    }
                }
            }
        }
    }

    private List<PhysicsRotation> loadTurns(UPackage uPackage) {
        var carriers = new HashMap<String, String>();
        for (var swing : loadSwings(uPackage)) {
            for (var follower : swing.followers()) {
                carriers.putIfAbsent(follower.actorPath(), swing.actorPath());
            }
        }
        var registered = levelActorPaths(uPackage);
        var turns = new ArrayList<PhysicsRotation>();
        var meshes = new ArrayList<StaticMeshActor>();
        uPackage.loadObjects("StaticMeshActor", meshes);
        uPackage.loadObjects("MovableStaticMeshActor", meshes);
        for (var mesh : meshes) {
            if (!mesh.isDeleteMe() && (registered == null || registered.contains(mesh.getSourcePath()))) {
                linkTurn(mesh);
                var link = mesh.getMotionLink();
                if (link != null && link.turn() != null) {
                    turns.add(link.turn());
                }
            }
        }
        var emitters = new ArrayList<EmitterActor>();
        uPackage.loadEmitterActors(emitters);
        for (var emitter : emitters) {
            if (registered == null || registered.contains(emitter.getSourcePath())) {
                var turn = emitter.createPhysicsRotation(carriers.get(emitter.getFullName()));
                if (turn != null) {
                    turns.add(turn);
                }
            }
        }
        return List.copyOf(turns);
    }

    private void linkTurn(StaticMeshActor actor) {
        if (actor.getMotionLink() == null) {
            var turn = turnOf(actor, null);
            if (turn != null) {
                actor.setMotionLink(MotionLink.ofTurn(turn, actor.getScale(), actor.getPrePivot()));
            }
        }
    }

    private @Nullable PhysicsRotation turnOf(StaticMeshActor actor, @Nullable String carrier) {
        if (actor instanceof UMover
                || actor instanceof UL2MovableStaticMeshActor
                || actor instanceof UMovableStaticMeshActor movable && !movable.isPhysicsSaved()) {
            return null;
        }
        return actor.createPhysicsRotation(carrier);
    }

    private void linkMovementCarriers(UPackage uPackage) {
        var carriers = new ArrayList<UL2MovableStaticMeshActor>();
        uPackage.loadObjects("L2MovableStaticMeshActor", carriers);
        if (carriers.isEmpty() || carriers.getFirst().isCarryResolved()) {
            return;
        }
        carriers.forEach(UL2MovableStaticMeshActor::markCarryResolved);
        var registered = levelActorPaths(uPackage);
        var moving = new ArrayList<L2Attach.Carrier>();
        var movements = new HashMap<String, MovementCarrier>();
        for (var carrier : carriers) {
            if (carrier.isDeleteMe() || registered != null && !registered.contains(carrier.getSourcePath())) {
                continue;
            }
            var movement = carrier.getMovement();
            if (movement == null || !movement.movesPlace()) {
                continue;
            }
            moving.add(new L2Attach.Carrier(carrier.getFullName(), carrier.getMovementTags()));
            movements.put(carrier.getFullName(), new MovementCarrier(carrier.getFullName(), movement));
        }
        if (moving.stream()
                .allMatch(carrier ->
                        L2Attach.getAttachTags(carrier.movementTags()).isEmpty())) {
            return;
        }
        var byPath = new HashMap<String, StaticMeshActor>();
        var candidates = new ArrayList<L2Attach.Candidate>();
        var meshes = new ArrayList<StaticMeshActor>();
        uPackage.loadObjects("StaticMeshActor", meshes);
        for (var mesh : meshes) {
            if (mesh.isDeleteMe()
                    || registered != null && !registered.contains(mesh.getSourcePath())
                    || mesh.getMotionLink() != null
                    || turnOf(mesh, null) != null) {
                continue;
            }
            byPath.putIfAbsent(mesh.getFullName(), mesh);
            candidates.add(new L2Attach.Candidate(
                    mesh.getFullName(), mesh.getL2MoveEvent(), mesh.isStatic(), mesh.isMovable()));
        }
        L2Attach.attach(moving, candidates).forEach((path, attached) -> {
            var carried = movements.get(path);
            for (var candidate : attached) {
                var mesh = byPath.get(candidate.path());
                var follower = new L2Swing.Follower(
                        mesh.getFullName(), L2Swing.Kind.MESH, mesh.getLocation(), mesh.getRotation());
                mesh.setMotionLink(
                        MotionLink.ofMovementCarried(carried, follower, mesh.getScale(), mesh.getPrePivot()));
            }
        });
    }

    private @Nullable Set<String> levelActorPaths(UPackage uPackage) {
        var levels = new ArrayList<ULevel>();
        uPackage.loadObjects("Level", levels);
        if (levels.isEmpty()) {
            return null;
        }
        var registered = new HashSet<String>();
        for (var level : levels) {
            for (var reference : level.getActors()) {
                var path = reference.peekExportSourcePath();
                if (path != null) {
                    registered.add(path);
                }
            }
        }
        return registered;
    }

    private List<LightCorona> loadLightCoronas(UPackage uPackage) {
        var actors = new ArrayList<LightActor>();
        uPackage.loadObjects("Light", actors);
        uPackage.loadObjects("Spotlight", actors);
        var coronas = new ArrayList<LightCorona>();
        var textures = new IdentityHashMap<UTexture, Optional<Texture>>();
        for (var actor : actors) {
            if (!actor.isCorona() || actor.isDeleteMe() || actor.getDrawType() == DRAW_TYPE_SUN) {
                continue;
            }
            UTexture skin;
            try {
                skin = actor.getSkin(0) instanceof UTexture texture ? texture : null;
            } catch (RuntimeException unreadable) {
                skin = null;
            }
            if (skin == null) {
                continue;
            }
            var decoded = textures.computeIfAbsent(skin, texture -> Optional.ofNullable(loadTexture(texture)));
            if (decoded.isEmpty()) {
                continue;
            }
            coronas.add(new LightCorona(
                    actor.getFullName(),
                    actor.getLocation(),
                    actor.getRotation(),
                    decoded.get(),
                    skin.getFullName(),
                    actor.getLightHue(),
                    actor.getLightSaturation(),
                    actor.getDrawScale(),
                    actor.getDrawScale3d(),
                    actor.getMinCoronaSize(),
                    actor.getMaxCoronaSize(),
                    actor.getCoronaRotation(),
                    actor.getCoronaRotationOffset(),
                    actor.getScaleGlow(),
                    actor.getLightRadius(),
                    actor.getCullDistance(),
                    actor.isUseOwnFinalBlend()));
        }
        return coronas;
    }

    private List<SpriteEmitterDescriptor> loadSpriteEmitterDescriptors(
            UPackage uPackage, Map<String, EmitterVisibility> emitterVisibility) {
        var actors = new ArrayList<EmitterActor>();
        uPackage.loadEmitterActors(actors);
        return spriteEmitterDescriptors(actors, emitterVisibility);
    }

    private List<SpriteEmitterDescriptor> spriteEmitterDescriptors(
            List<EmitterActor> actors, Map<String, EmitterVisibility> emitterVisibility) {
        var descriptors = new ArrayList<SpriteEmitterDescriptor>();
        var textureCache = new IdentityHashMap<UTexture, Texture>();
        for (var actorOrder = 0; actorOrder < actors.size(); actorOrder++) {
            var actor = actors.get(actorOrder);
            if (actor.isDeleteMe() || actor.isHidden()) {
                continue;
            }
            var childIndex = 0;
            for (var child : actor.getChildEmitters()) {
                if (child instanceof USpriteEmitter sprite && actor.isChildPlaying(sprite)) {
                    var uTexture = firstTextureOf(sprite.getTexture());
                    if (uTexture != null) {
                        var texture = textureCache.computeIfAbsent(uTexture, this::loadTexture);
                        if (texture != null) {
                            var seed = actor.getFullName().hashCode() * 0x9E3779B97F4A7C15L + childIndex + 991L;
                            descriptors.add(new SpriteEmitterDescriptor(
                                    sprite,
                                    actor.getLocation(),
                                    actor.getRotation(),
                                    actor.getScale(),
                                    texture,
                                    blendOfDrawStyle(sprite.getDrawStyle(), texture),
                                    seed,
                                    visibilityOf(actor, emitterVisibility),
                                    shortName(uTexture.getFullName()),
                                    uTexture.getFullName(),
                                    actor.getTranslucentRenderPriority(),
                                    actor.getFullName(),
                                    actorOrder,
                                    childIndex));
                        }
                    }
                }
                childIndex++;
            }
        }
        return descriptors;
    }

    private List<BeamEmitterDescriptor> loadBeamEmitterDescriptors(
            UPackage uPackage, Map<String, EmitterVisibility> emitterVisibility) {
        var actors = new ArrayList<EmitterActor>();
        uPackage.loadEmitterActors(actors);
        resolveBeamActors(uPackage, actors);
        return beamEmitterDescriptors(actors, emitterVisibility);
    }

    private void resolveBeamActors(UPackage uPackage, List<EmitterActor> actors) {
        var wanted = new HashSet<String>();
        for (var actor : actors) {
            for (var child : actor.getChildEmitters()) {
                if (child instanceof UBeamEmitter beam && beam.getDetermineEndPointBy() == BEAM_END_ACTOR) {
                    for (var end : beam.getBeamEndPoints()) {
                        if (!end.actorTag().equalsIgnoreCase("None")) {
                            wanted.add(end.actorTag().toLowerCase(Locale.ROOT));
                        }
                    }
                }
            }
        }
        if (wanted.isEmpty()) {
            return;
        }
        var places = new HashMap<String, Vec3>();
        var archive = uPackage.getArchive();
        var levels = new ArrayList<ULevel>();
        uPackage.loadObjects("Level", levels);
        for (var level : levels) {
            for (var reference : level.getActors()) {
                if (places.size() == wanted.size()) {
                    break;
                }
                var index = reference.getIndex();
                if (index <= 0) {
                    continue;
                }
                String tag = null;
                var location = new Vec3();
                try {
                    for (var property : archive.getObjectLoader()
                            .exportProperties(archive.getExportMap().get(index - 1))) {
                        if (property.getName().equals("Tag")) {
                            tag = archive.resolveName(property.getIndexValue());
                        } else if (property.getName().equals("Location") && property.getVectorValue() != null) {
                            location = property.getVectorValue();
                        }
                    }
                } catch (RuntimeException unreadable) {
                    log.debug("No Tag of actor {}: {}", index, unreadable.toString());
                }
                if (tag != null && wanted.contains(tag.toLowerCase(Locale.ROOT))) {
                    places.putIfAbsent(tag.toLowerCase(Locale.ROOT), location);
                }
            }
        }
        for (var actor : actors) {
            for (var child : actor.getChildEmitters()) {
                if (child instanceof UBeamEmitter beam && beam.getDetermineEndPointBy() == BEAM_END_ACTOR) {
                    beam.resolveActorEnds(places);
                }
            }
        }
    }

    private List<BeamEmitterDescriptor> beamEmitterDescriptors(
            List<EmitterActor> actors, Map<String, EmitterVisibility> emitterVisibility) {
        var descriptors = new ArrayList<BeamEmitterDescriptor>();
        var textureCache = new IdentityHashMap<UTexture, Texture>();
        for (var actorOrder = 0; actorOrder < actors.size(); actorOrder++) {
            var actor = actors.get(actorOrder);
            if (actor.isDeleteMe() || actor.isHidden()) {
                continue;
            }
            var childIndex = 0;
            for (var child : actor.getChildEmitters()) {
                if (child instanceof UBeamEmitter beam && actor.isChildPlaying(beam)) {
                    var uTexture = firstTextureOf(beam.getTexture());
                    if (uTexture != null) {
                        var texture = textureCache.computeIfAbsent(uTexture, this::loadTexture);
                        if (texture != null) {
                            var seed = actor.getFullName().hashCode() * 0x9E3779B97F4A7C15L + childIndex + 4013L;
                            descriptors.add(new BeamEmitterDescriptor(
                                    beam,
                                    actor.getLocation(),
                                    actor.getRotation(),
                                    actor.getScale(),
                                    texture,
                                    blendOfDrawStyle(beam.getDrawStyle()),
                                    seed,
                                    visibilityOf(actor, emitterVisibility),
                                    shortName(uTexture.getFullName()),
                                    uTexture.getFullName(),
                                    actor.getTranslucentRenderPriority(),
                                    actor.getFullName(),
                                    actorOrder,
                                    childIndex));
                        }
                    }
                }
                childIndex++;
            }
        }
        return descriptors;
    }

    @SuppressWarnings("unused")
    private List<Billboard> loadEmitters(UPackage uPackage) {
        var actors = new ArrayList<EmitterActor>();
        uPackage.loadEmitterActors(actors);
        var billboards = new ArrayList<Billboard>();
        var textureCache = new IdentityHashMap<UTexture, Texture>();
        var rng = new Random(EMITTER_RNG_SEED);
        for (var actor : actors) {
            if (actor.isDeleteMe() || actor.isHidden()) {
                continue;
            }
            var position = actor.getPosition();
            var scale = Math.max(EMITTER_SCALE_MIN, Math.min(EMITTER_SCALE_MAX, actor.getDrawScale()));
            var fallbackSize = EMITTER_DEFAULT_SIZE * scale;
            var added = false;
            for (var child : actor.getChildEmitters()) {
                if (!(child instanceof USpriteEmitter sprite)) {
                    continue;
                }
                var uTexture = firstTextureOf(sprite.getTexture());
                if (uTexture == null) {
                    continue;
                }
                var texture = textureCache.computeIfAbsent(uTexture, this::loadTexture);
                if (texture == null) {
                    continue;
                }
                var opacity = Math.max(EMITTER_MIN_OPACITY, Math.min(1f, sprite.getOpacity()));
                var startSize = sprite.getStartSize();
                var size = startSize > 0 ? Math.min(EMITTER_SIZE_MAX, startSize) : fallbackSize;
                addCluster(
                        billboards,
                        rng,
                        position,
                        size,
                        new Vec4(1f, 1f, 1f, opacity),
                        blendOfDrawStyle(sprite.getDrawStyle()),
                        texture,
                        animTypeOfTexture(uTexture.getName()));
                added = true;
            }
            if (!added) {
                addCluster(
                        billboards,
                        rng,
                        position,
                        fallbackSize,
                        FIRE_COLOR,
                        Billboard.BLEND_ADDITIVE,
                        null,
                        Billboard.ANIM_FLICKER);
            }
        }
        return billboards;
    }

    private List<Entity> loadModelEntity(
            UModel model,
            Box mapBoundingBox,
            boolean checkBounds,
            Optional<Vec4> maybeColor,
            boolean skyMode,
            @Nullable MeshPlaneIndex meshIndex,
            boolean csgConcat,
            @Nullable ZoneInfoActor levelInfo,
            @Nullable Set<Integer> surfaces) {
        var points = model.getPoints();
        if (points == null || points.length == 0) {
            return List.of();
        }
        var lightmaps = model.getLightmaps();
        var lightmapAtlases = model.getLightmapAtlases();
        var lightmapAtlasVariants = model.getLightmapAtlasVariants();
        var sparseLightmapAtlases = new boolean[lightmapAtlasVariants == null ? 0 : lightmapAtlasVariants.length];
        for (var i = 0; i < sparseLightmapAtlases.length; i++) {
            for (var variant : lightmapAtlasVariants[i]) {
                if (variant.isEmpty()) {
                    sparseLightmapAtlases[i] = true;
                }
            }
        }
        var lightmapSources = new IdentityHashMap<FStaticLightmapTexture, Integer>();
        for (var i = 0; i < sparseLightmapAtlases.length; i++) {
            for (var v = 0; v < lightmapAtlasVariants[i].length; v++) {
                lightmapSources.putIfAbsent(lightmapAtlasVariants[i][v], i * 16 + v);
            }
        }
        var bspSections = model.getBspSections();
        var bspWaterTangents =
                maybeColor.isEmpty() ? BspRenderGeometry.waterTangents(model) : Map.<UBspNode, float[]>of();
        var retainedLightmapAtlasIndices = new LinkedHashSet<Integer>();
        var groups = new LinkedHashMap<Integer, CsgGroup>();
        var concatGroups = new LinkedHashMap<Long, CsgConcatGroup>();
        var depthResolution =
                csgDepthResolution(model, points, checkBounds, mapBoundingBox, maybeColor.isPresent(), skyMode);
        var nudges = depthResolution.nudges();

        for (UBspNode node : model.getNodes()) {
            if (surfaces != null && !surfaces.contains(node.getSurfaceIndex())) {
                continue;
            }
            var unrealSurfaces = model.getSurfaces()[node.getSurfaceIndex()];
            var renderPolygonFlags = bspRenderPolygonFlags(node, unrealSurfaces, bspSections);
            var surfacePolygonFlags = unrealSurfaces.getPolygonFlags();
            var unlit = (renderPolygonFlags & BspPolygonFlags.PF_Unlit) != 0;
            var normal = model.getVectors()[unrealSurfaces.getNormalIndex()];
            var isWater = maybeColor.isEmpty()
                    && ((unrealSurfaces.getPolygonFlags() & BspPolygonFlags.PF_L2_Water) != 0
                            || ((node.getFlags() & BspNodeFlags.NF_NotCsg) != 0
                                    && (node.getFlags() & BspNodeFlags.NF_NotVisBlocking) == 0
                                    && normal.getZ() >= 0.5f
                                    && isWaterShader(safeMaterial(unrealSurfaces))));
            var isReflectiveSheet =
                    maybeColor.isEmpty() && (unrealSurfaces.getPolygonFlags() & BspPolygonFlags.PF_L2_Water) != 0;
            var fakeBackdrop = !skyMode && (surfacePolygonFlags & BspPolygonFlags.PF_FakeBackdrop) != 0;
            if (doesNativeSuppressBspColor(surfacePolygonFlags, skyMode, isWater, fakeBackdrop)
                    || doesNativeSkipInvisible(surfacePolygonFlags, skyMode, fakeBackdrop)) {
                continue;
            }
            var drawnSheet = isSheetDrawn(surfacePolygonFlags)
                    && !(checkBounds && checkBspNodeBounds(model, node, mapBoundingBox));
            var renderedPassableSheet = isWater
                    || (maybeColor.isEmpty()
                            && !fakeBackdrop
                            && (node.getFlags() & BspNodeFlags.NF_NotCsg) != 0
                            && (node.getFlags() & BspNodeFlags.NF_NotVisBlocking) == 0
                            && (normal.getZ() >= 0.5f || drawnSheet)
                            && safeMaterial(unrealSurfaces) != null);
            if (!skyMode
                    && !renderedPassableSheet
                    && !fakeBackdrop
                    && (node.getFlags() & BspNodeFlags.NF_Passable) != 0) {
                continue;
            }
            if (!skyMode
                    && !renderedPassableSheet
                    && !fakeBackdrop
                    && checkBounds
                    && checkBspNodeBounds(model, node, mapBoundingBox)) {
                continue;
            }
            if (!skyMode
                    && !renderedPassableSheet
                    && !fakeBackdrop
                    && (unrealSurfaces.getPolygonFlags() & BspPolygonFlags.PF_Passable) != 0) {
                continue;
            }

            var nudge = nudges.getOrDefault(node, 0f);
            if (maybeColor.isPresent() && normal.getZ() < 0.5f) {
                continue;
            }
            var lightmapIndex = node.getILightmapIndex();
            var li = (!unlit || isReflectiveSheet)
                            && lightmaps != null
                            && lightmapIndex >= 0
                            && lightmapIndex < lightmaps.length
                    ? lightmaps[lightmapIndex]
                    : null;
            var atlasIndex = li != null ? li.iLightmapTexture() : -1;
            var storedAtlas = atlasIndex >= 0 && lightmapAtlases != null && atlasIndex < lightmapAtlases.length
                    ? lightmapAtlases[atlasIndex]
                    : null;
            var atlas = firstUsableLightmap(
                    storedAtlas,
                    atlasIndex >= 0 && lightmapAtlasVariants != null && atlasIndex < lightmapAtlasVariants.length
                            ? lightmapAtlasVariants[atlasIndex]
                            : null);
            if (atlas == null) {
                atlasIndex = -1;
                li = null;
            }
            var atlasVariants =
                    atlasIndex >= 0 && lightmapAtlasVariants != null && atlasIndex < lightmapAtlasVariants.length
                            ? lightmapAtlasVariants[atlasIndex]
                            : null;
            if (atlasIndex >= 0) {
                retainedLightmapAtlasIndices.add(atlasIndex);
            }
            var depthOrdered = depthResolution.frontLayers().containsKey(node)
                    || depthResolution.backLayers().containsKey(node);
            if (meshIndex != null
                    && atlas == null
                    && nudge < MESH_COINCIDENCE_NUDGE
                    && isMeshCoincidenceNudgeAllowed(unlit, depthOrdered)
                    && isMeshCoincident(model, node, points, normal, meshIndex)) {
                nudge = MESH_COINCIDENCE_NUDGE;
            }
            var materialNode = unrealSurfaces.getMaterial().get();
            if (!skyMode && isEditorDummyCsgMaterial(materialNode)) {
                continue;
            }
            var maybeMaterial = loadMaterial(materialNode);
            var maybeTexture = maybeMaterial == null ? null : maybeMaterial.getTexture();
            EntityMaterial surfaceMaterial;
            if (isWater && maybeTexture != null) {
                surfaceMaterial = waterMaterial(materialNode, maybeTexture, isReflectiveSheet);
            } else if (maybeMaterial instanceof SpecularEntityMaterial spec
                    && spec.overlay().emissive()) {
                surfaceMaterial = maybeMaterial;
            } else if (maybeTexture != null) {
                surfaceMaterial = new TextureEntityMaterial(maybeTexture);
            } else if (maybeMaterial instanceof ColorEntityMaterial) {
                surfaceMaterial = maybeMaterial;
            } else {
                surfaceMaterial = new ColorEntityMaterial(maybeColor.orElse(YELLOW_COLOR));
            }
            var framebuffer = surfaceMaterial instanceof WaterEntityMaterial water ? water.framebuffer() : null;
            var blendMode = framebuffer != null
                    ? framebuffer.drawOrderBlend()
                    : isWater
                            ? blendModeOf(materialNode)
                            : maybeColor.isPresent()
                                    ? BlendMode.TRANSLUCENT
                                    : skyMode
                                            ? blendModeOf(materialNode)
                                            : unlit ? BlendMode.UNLIT : blendModeOf(materialNode);
            var cullBackFaces = shouldCullCsgBackFaces(renderPolygonFlags, blendMode, skyMode, fakeBackdrop);
            var zoneBoundaryBackface = needsZoneBoundaryBackface(node, renderPolygonFlags, blendMode);
            var nativePlaneSide = cullBackFaces && !zoneBoundaryBackface;
            var nodePlane = node.getPlane();
            var bspPlane = nativePlaneSide
                    ? new Vec4(nodePlane.getX(), nodePlane.getY(), nodePlane.getZ(), nodePlane.getW())
                    : null;
            var uvAnimation = uvAnimationOf(materialNode);
            if (uvAnimation.mode() == 0) {
                var pf = renderPolygonFlags;
                var panU = (pf & BspPolygonFlags.PF_AutoUPan) != 0 ? AUTO_PAN_RATE : 0f;
                var panV = (pf & BspPolygonFlags.PF_AutoVPan) != 0 ? AUTO_PAN_RATE : 0f;
                if (panU != 0f || panV != 0f) {
                    uvAnimation = UvAnimation.pan(panU, panV);
                }
            }

            var groupKey = atlasIndex;
            if (skyMode) {
                groupKey = node.getISection() >= 0 ? node.getISection() : Integer.MIN_VALUE + node.getSurfaceIndex();
            }
            CsgConcatGroup concatGroup = null;
            if (csgConcat
                    && !skyMode
                    && maybeColor.isEmpty()
                    && !isWater
                    && !fakeBackdrop
                    && isCsgConcatEligible(surfaceMaterial, blendMode, uvAnimation)
                    && (atlasIndex < 0
                            || atlasIndex >= sparseLightmapAtlases.length
                            || !sparseLightmapAtlases[atlasIndex])
                    && (atlas == null || isConcatUsableAtlas(atlas))) {
                concatGroup = concatGroups.computeIfAbsent(csgConcatGroupKey(atlas), _ -> new CsgConcatGroup());
            }
            Entity.MeshVertexBuffer.Builder meshVertices;
            Box meshBoundingBox;
            Map<Object, CsgBucket> buckets;
            List<DebugLabel> debugLabels;
            IntArrayList spans;
            if (concatGroup != null) {
                meshVertices = concatGroup.vertices;
                meshBoundingBox = concatGroup.boundingBox;
                buckets = concatGroup.buckets;
                debugLabels = concatGroup.debugLabels;
                spans = concatGroup.spans;
            } else {
                var group = groups.computeIfAbsent(groupKey, _ -> new CsgGroup(atlas, atlasVariants));
                meshVertices = group.vertices();
                meshBoundingBox = group.boundingBox();
                buckets = group.buckets();
                debugLabels = group.debugLabels();
                spans = group.spans();
            }
            var atlasWidth = atlas != null ? (float) atlas.width() : 1f;
            var atlasHeight = atlas != null ? (float) atlas.height() : 1f;
            var vertexOffset = meshVertices.size();
            spans.add(node.getSurfaceIndex());
            spans.add(vertexOffset);
            spans.add(node.getVertexCount());

            var uVector = model.getVectors()[unrealSurfaces.getUIndex()];
            var vVector = model.getVectors()[unrealSurfaces.getVIndex()];
            var base = points[unrealSurfaces.getBaseIndex()];

            var uSize = 64.0f;
            var vSize = 64.0f;
            if (maybeTexture != null) {
                uSize = maybeTexture.width();
                vSize = maybeTexture.height();
            }

            var waterVertices = isWater ? BspRenderGeometry.buildVertices(model, node, materialNode, li) : null;
            var waterTangents = isWater ? bspWaterTangents.get(node) : null;

            for (int i = 0; i < node.getVertexCount(); i++) {
                var index = model.getVertices()[node.getVertexPoolIndex() + i].getVertexIndex();
                var waterVertex = waterVertices != null ? waterVertices[i] : null;
                var sourcePosition = waterVertex != null ? waterVertex.position() : points[index];
                var vertexHeight =
                        isReflectiveSheet ? bspWaterVertexHeight(sourcePosition.getZ()) : sourcePosition.getZ();
                var position = nudgedPosition(
                        vertexHeight == sourcePosition.getZ()
                                ? sourcePosition
                                : new Vec3(sourcePosition.getX(), sourcePosition.getY(), vertexHeight),
                        normal,
                        nudge);
                var distance = position.minus(base);
                var texture = waterVertex != null
                        ? new Vec2(waterVertex.u(), waterVertex.v())
                        : new Vec2(distance.dot(uVector) / uSize, distance.dot(vVector) / vSize);
                meshBoundingBox.add(position);
                var lightmapUv = atlas != null && waterVertex != null
                        ? new Vec2(waterVertex.u2(), waterVertex.v2())
                        : li != null ? lightmapUv(li, position, atlasWidth, atlasHeight) : LIGHTMAP_NONE;
                var vertexNormal = waterVertex != null ? waterVertex.normal() : normal;
                var meshVertex = meshVertices.add(
                        position.getX(),
                        position.getY(),
                        position.getZ(),
                        vertexNormal.getX(),
                        vertexNormal.getY(),
                        vertexNormal.getZ(),
                        texture.getX(),
                        texture.getY());
                meshVertices.setLightmapUv(meshVertex, lightmapUv.getX(), lightmapUv.getY());
                if (waterTangents != null) {
                    meshVertices.setBspTangent(
                            meshVertex, waterTangents[i * 3], waterTangents[i * 3 + 1], waterTangents[i * 3 + 2]);
                }
                if (nativePlaneSide) {
                    meshVertices.setSunVisibilityMask(meshVertex, -2);
                }
                if (bspPlane != null) {
                    meshVertices.setBspPlane(
                            meshVertex, bspPlane.getX(), bspPlane.getY(), bspPlane.getZ(), bspPlane.getW());
                }
            }

            var polygonFlags = renderPolygonFlags;

            if (concatGroup != null) {
                var ordinal = atlas != null ? concatGroup.getOrdinalOf(atlas, atlasVariants) : 0;
                concatGroup.addVertexLayers(ordinal, meshVertices.size() - vertexOffset);
            }

            var animation = uvAnimation;
            var materialName = materialNode != null ? shortName(materialNode.getFullName()) : null;
            if (!fakeBackdrop && materialName != null) {
                var count = node.getVertexCount();
                var lx = 0f;
                var ly = 0f;
                var lz = 0f;
                var minX = Float.MAX_VALUE;
                var minY = Float.MAX_VALUE;
                var minZ = Float.MAX_VALUE;
                var maxX = -Float.MAX_VALUE;
                var maxY = -Float.MAX_VALUE;
                var maxZ = -Float.MAX_VALUE;
                var polygon = new Vec3[count];
                for (int i = 0; i < count; i++) {
                    var p = points[model.getVertices()[node.getVertexPoolIndex() + i].getVertexIndex()];
                    polygon[i] = p;
                    lx += p.getX();
                    ly += p.getY();
                    lz += p.getZ();
                    minX = Math.min(minX, p.getX());
                    minY = Math.min(minY, p.getY());
                    minZ = Math.min(minZ, p.getZ());
                    maxX = Math.max(maxX, p.getX());
                    maxY = Math.max(maxY, p.getY());
                    maxZ = Math.max(maxZ, p.getZ());
                }
                debugLabels.add(new DebugLabel(
                        materialName,
                        new Vec3(lx / count, ly / count, lz / count),
                        new Vec3(minX, minY, minZ),
                        new Vec3(maxX, maxY, maxZ),
                        polygon));
            }
            var dedupBase = surfaceMaterial instanceof SpecularEntityMaterial spec ? spec.base() : surfaceMaterial;
            var dedupName = materialNode != null
                            && (dedupBase instanceof TextureEntityMaterial || dedupBase instanceof WaterEntityMaterial)
                    ? materialNode.getFullName()
                    : null;
            var reflectionPlane =
                    isReflectiveSheet ? WaterPlane.from(normal, nudgedPosition(base, normal, nudge)) : null;
            var distanceFog = reflectionPlane != null ? pickDistanceFog(model, node) : null;
            var waterSheetSource = isReflectiveSheet ? waterSheetSource(model, node, reflectionPlane, levelInfo) : null;
            var bspDrawSource = bspDrawSource(model, node);
            var csgDepthWrite = !fakeBackdrop
                    && (framebuffer != null ? framebuffer.depthWrite() : writesDepth(materialNode, blendMode));
            var depthOrdering = csgDepthWrite
                    && !skyMode
                    && !isWater
                    && maybeColor.isEmpty()
                    && !fakeBackdrop
                    && isOpaqueCsgDepthBlend(blendMode);
            var depthLayer = depthOrdering ? depthResolution.frontLayers().getOrDefault(node, 0) : 0;
            var reverseDepthLayer = depthOrdering ? depthResolution.backLayers().getOrDefault(node, 0) : 0;
            var modulateStaticLighting2x = modulatesStaticLighting2x(materialNode);
            var alphaReference =
                    framebuffer != null ? framebuffer.alphaReference() : alphaRefOf(materialNode, blendMode);
            IntFunction<CsgBucket> bucketForLayer = layer -> buckets.computeIfAbsent(
                    new CsgBucketKey(
                            materialNode,
                            blendMode,
                            reflectionPlane,
                            distanceFog,
                            waterSheetSource,
                            bspDrawSource,
                            fakeBackdrop,
                            csgDepthWrite,
                            cullBackFaces,
                            nativePlaneSide,
                            layer),
                    _ -> new CsgBucket(
                            surfaceMaterial,
                            blendMode,
                            animation,
                            new IntArrayList(),
                            dedupName,
                            alphaReference,
                            modulateStaticLighting2x,
                            reflectionPlane,
                            distanceFog,
                            waterSheetSource,
                            bspDrawSource,
                            fakeBackdrop,
                            csgDepthWrite,
                            cullBackFaces,
                            nativePlaneSide,
                            layer));
            var bucket = bucketForLayer.apply(depthLayer);
            var tris = bucket.tris();
            var legacyThinLayer =
                    cullBackFaces && depthResolution.legacyBackfaces().contains(node);
            var reversed = isWindingReversed(model, node, points, normal) ^ legacyThinLayer;
            for (int i = 2; i < node.getVertexCount(); i++) {
                tris.add(vertexOffset);
                if (reversed) {
                    tris.add(vertexOffset + i);
                    tris.add(vertexOffset + i - 1);
                } else {
                    tris.add(vertexOffset + i - 1);
                    tris.add(vertexOffset + i);
                }
            }
            if (zoneBoundaryBackface) {
                var reverseTris = zoneBoundaryBackface && reverseDepthLayer != depthLayer
                        ? bucketForLayer.apply(reverseDepthLayer).tris()
                        : tris;
                for (int i = 2; i < node.getVertexCount(); i++) {
                    reverseTris.add(vertexOffset);
                    if (reversed) {
                        reverseTris.add(vertexOffset + i - 1);
                        reverseTris.add(vertexOffset + i);
                    } else {
                        reverseTris.add(vertexOffset + i);
                        reverseTris.add(vertexOffset + i - 1);
                    }
                }
            }
        }

        var entities = new ArrayList<Entity>();
        for (var groupEntry : groups.entrySet()) {
            var group = groupEntry.getValue();
            var groupVertices = group.vertices();
            if (groupVertices.isEmpty()) {
                continue;
            }
            var meshIndices = new IntArrayList();
            var meshSurfaces = new ArrayList<Entity.Surface>();
            for (var bucket : group.buckets().values()) {
                if (bucket.tris().isEmpty()) {
                    continue;
                }
                var indexOffset = meshIndices.size();
                meshIndices.addAll(bucket.tris());
                meshSurfaces.add(new Entity.Surface(
                        bucket.fakeBackdrop() ? SurfaceType.FAKE_BACKDROP.getId() : csgSurfaceType(bucket),
                        indexOffset,
                        bucket.tris().size(),
                        bucket.material(),
                        bucket.blend(),
                        bucket.uv(),
                        maybeColor.isPresent(),
                        bucket.materialName(),
                        bucket.alphaRef(),
                        false,
                        bucket.depthWrite(),
                        bucket.modulateStaticLighting2x(),
                        bucket.reflectionPlane(),
                        bucket.distanceFog(),
                        bucket.waterSheetSource(),
                        bucket.bspDrawSource()));
            }
            if (meshSurfaces.isEmpty()) {
                continue;
            }
            var entity = new Entity(new EntityMesh(
                    groupVertices.build(), meshIndices.toArray(), meshSurfaces, List.of(), group.boundingBox(), 0f));
            entity.setDebugLabels(group.debugLabels());
            entity.setBspSpans(group.spans().toArray());
            if (skyMode) {
                entity.setSourceBspSection(groupEntry.getKey());
                if (!group.debugLabels().isEmpty()) {
                    entity.setDebugName(group.debugLabels().getFirst().name());
                }
            }
            var atlas = group.atlas();
            if (atlas != null) {
                entity.setLightmap(lightmapTexture(entity, atlas, lightmapSources));
                if (group.atlasVariants() != null && group.atlasVariants().length > 1) {
                    installLightmapVariants(entity, group.atlasVariants(), lightmapSources);
                }
            }
            entities.add(entity);
        }
        for (var group : concatGroups.values()) {
            var groupVertices = group.vertices;
            if (groupVertices.isEmpty()) {
                continue;
            }
            var meshIndices = new IntArrayList();
            var meshSurfaces = new ArrayList<Entity.Surface>();
            for (var bucket : group.buckets.values()) {
                if (bucket.tris().isEmpty()) {
                    continue;
                }
                var indexOffset = meshIndices.size();
                meshIndices.addAll(bucket.tris());
                meshSurfaces.add(new Entity.Surface(
                        bucket.fakeBackdrop() ? SurfaceType.FAKE_BACKDROP.getId() : csgSurfaceType(bucket),
                        indexOffset,
                        bucket.tris().size(),
                        bucket.material(),
                        bucket.blend(),
                        bucket.uv(),
                        false,
                        bucket.materialName(),
                        bucket.alphaRef(),
                        false,
                        bucket.depthWrite(),
                        bucket.modulateStaticLighting2x(),
                        bucket.reflectionPlane(),
                        bucket.distanceFog(),
                        bucket.waterSheetSource(),
                        bucket.bspDrawSource()));
            }
            if (meshSurfaces.isEmpty()) {
                continue;
            }
            var entity = new Entity(new EntityMesh(
                    groupVertices.build(), meshIndices.toArray(), meshSurfaces, List.of(), group.boundingBox, 0f));
            entity.setDebugLabels(group.debugLabels);
            entity.setBspSpans(group.spans.toArray());
            entity.setCsgConcat(true);
            if (!group.atlases.isEmpty()) {
                var lightmapTextures = new ArrayList<Texture>(group.atlases.size());
                for (var atlas : group.atlases) {
                    lightmapTextures.add(lightmapTexture(entity, atlas, lightmapSources));
                }
                entity.setLightmaps(lightmapTextures);
                entity.setLightmapLayers(group.vertexLayers.toArray());
                var variantCount = group.getVariantCount();
                if (variantCount > 1) {
                    var variants = new ArrayList<Texture>(group.atlases.size() * variantCount);
                    for (var slot = 0; slot < variantCount; slot++) {
                        for (var ordinal = 0; ordinal < group.atlases.size(); ordinal++) {
                            variants.add(
                                    lightmapTexture(entity, group.getAtlasForSlot(ordinal, slot), lightmapSources));
                        }
                    }
                    entity.setCsgLightmapVariants(variants);
                    entity.setCsgLightmapVariantCount(variantCount);
                }
            }
            entities.add(entity);
        }
        if (!skyMode && maybeColor.isEmpty()) {
            model.retainLightmapAtlasVariants(retainedLightmapAtlasIndices);
        }
        return entities;
    }

    private @Nullable EntityMaterial loadShaderBase(UShader uShader, @Nullable EntityMaterial diffuseMaterial) {
        if (diffuseMaterial == null) {
            var alt = slotMaterial(uShader.getSelfIllumination());
            if (alt == null) {
                alt = slotMaterial(uShader.getSpecular());
            }
            if (alt instanceof TextureEntityMaterial(Texture altTexture)) {
                return new TextureEntityMaterialWithOpacity(altTexture, loadTexture(getOpacity(uShader)));
            }
            return alt;
        }
        if (diffuseMaterial instanceof TextureEntityMaterial(Texture diffuseTexture)) {
            var opacity = getOpacity(uShader);
            if (opacity != null) {
                if (!isSameTexture(baseTextureOf(uShader.getDiffuse().get()), opacity)) {
                    return new TextureEntityMaterialWithOpacity(diffuseTexture, loadTexture(opacity));
                }
                return new TextureEntityMaterial(withAlphaTexture(diffuseTexture));
            }
            return diffuseMaterial;
        }
        return diffuseMaterial;
    }

    private @Nullable SpecularOverlay specularOverlayOf(UShader shader) {
        var specNode = slotMaterialNode(shader.getSpecular());
        if (specNode == null) {
            return null;
        }
        if (specNode instanceof UFadeColor fade) {
            var explicitMask = slotMaterialNode(shader.getSpecularMask());
            var maskTexture = explicitMask == null ? null : baseTextureOf(explicitMask);
            if (maskTexture == null) {
                maskTexture = baseTextureOf(shader);
            }
            var maskImage = loadAlphaMask(maskTexture);
            if (maskImage == null) {
                return null;
            }
            return new SpecularOverlay(
                    null,
                    UvAnimation.NONE,
                    maskImage,
                    shader.isModulateSpecular2x(),
                    shader.isPerformLightingOnSpecularPass(),
                    fadeSpec(fade),
                    false,
                    hasOpacity(shader),
                    materialUvChannel(explicitMask));
        }
        var cube = specularCubeOf(shader);
        if (cube != null) {
            var cubeMaskNode = slotMaterialNode(shader.getSpecularMask());
            return new SpecularOverlay(
                            null,
                            UvAnimation.NONE,
                            loadAlphaMask(cubeMaskNode == null ? null : baseTextureOf(cubeMaskNode)),
                            shader.isModulateSpecular2x(),
                            shader.isPerformLightingOnSpecularPass(),
                            null,
                            false,
                            false,
                            materialUvChannel(cubeMaskNode),
                            false,
                            null,
                            cube)
                    .withSeparatePass(hasOpacity(shader));
        }
        var specTexture = baseTextureOf(specNode);
        if (specTexture == null) {
            return null;
        }
        var specImage = loadTexture(specTexture);
        if (specImage == null) {
            return null;
        }
        var maskNode = slotMaterialNode(shader.getSpecularMask());
        var maskTexture = maskNode == null ? null : baseTextureOf(maskNode);
        var maskImage = loadAlphaMask(maskTexture);
        var separateAlphaPass = hasOpacity(shader);
        var animation = layerAnimationOf(specNode);
        var animatedFireGlow = maskImage != null
                && !shader.isPerformLightingOnSpecularPass()
                && animation.mode() != 0
                && (containsIgnoreCase(specNode.getFullName(), "fire")
                        || containsIgnoreCase(specTexture.getFullName(), "fire"));
        return new SpecularOverlay(
                specImage,
                animation,
                maskImage,
                shader.isModulateSpecular2x(),
                shader.isPerformLightingOnSpecularPass(),
                null,
                animatedFireGlow,
                separateAlphaPass,
                materialUvChannel(maskNode),
                false,
                environmentOf(shader, specNode));
    }

    private @Nullable SpecularOverlay selfIlluminationOverlayOf(UShader shader) {
        var selfIllumNode = slotMaterialNode(shader.getSelfIllumination());
        if (selfIllumNode == null) {
            return null;
        }
        var selfIllumTexture = baseTextureOf(selfIllumNode);
        if (selfIllumTexture == null) {
            return null;
        }
        var selfIllumImage = loadTexture(selfIllumTexture);
        if (selfIllumImage == null) {
            return null;
        }
        var maskNode = slotMaterialNode(shader.getSelfIlluminationMask());
        var maskTexture = maskNode == null ? null : baseTextureOf(maskNode);
        var maskImage = loadAlphaMask(maskTexture);
        if (maskImage == null) {
            return null;
        }
        return new SpecularOverlay(
                selfIllumImage,
                layerAnimationOf(selfIllumNode),
                maskImage,
                false,
                false,
                null,
                true,
                false,
                materialUvChannel(maskNode),
                true,
                environmentOf(shader, selfIllumNode));
    }

    private @Nullable Texture loadAlphaMask(@Nullable UTexture texture) {
        var loaded = loadTexture(texture);
        return loaded == null ? null : withAlphaTexture(loaded);
    }

    private @Nullable EntityMaterial loadCombiner(UCombiner combiner) {
        var op = combiner.getCombineOperation();
        var m1 = resolveCombinerLayer(combiner.getMaterial1());
        var m2 = resolveCombinerLayer(combiner.getMaterial2());
        var m2Fade = constantsFromParticleColor ? null : fadeSpecOf(combiner.getMaterial2());
        var m2Cube = m2 == null && m2Fade == null ? envCubeLayerOf(combiner.getMaterial2()) : null;
        var maskNode = slotMaterialNode(combiner.getMask());
        var vertexColorMask = maskNode instanceof UVertexColor;
        var mask = vertexColorMask ? null : resolveCombinerLayer(combiner.getMask());
        return switch (op) {
            case CO_Use_Color_From_Material2 -> m2 != null ? new TextureEntityMaterial(m2) : toTextureMaterial(m1);
            case CO_Use_Color_From_Mask -> {
                if (vertexColorMask && m1 != null) {
                    yield new CombinerEntityMaterial(
                            m1,
                            m1,
                            null,
                            op.getId(),
                            combiner.getModulateFactor(),
                            null,
                            combiner.isInvertMask(),
                            combiner.getAlphaOperation().getId(),
                            true);
                }
                yield mask != null ? new TextureEntityMaterial(mask) : toTextureMaterial(m1);
            }
            case CO_Multiply, CO_Add, CO_Subtract, CO_AlphaBlend_With_Mask, CO_Add_With_Mask_Modulation -> {
                if (m1 == null) {
                    yield toTextureMaterial(m2);
                }
                if (m2 == null && m2Fade == null && m2Cube == null) {
                    yield new TextureEntityMaterial(m1);
                }
                var needsMask = op == EColorOperation.CO_AlphaBlend_With_Mask
                        || op == EColorOperation.CO_Add_With_Mask_Modulation;
                if (needsMask && mask == null && !vertexColorMask) {
                    yield op == EColorOperation.CO_Add_With_Mask_Modulation
                                    && slotMaterialNode(combiner.getMaterial2()) instanceof UTexture
                            ? addByAlphaOfMaterial2(combiner, m1, m2)
                            : new TextureEntityMaterial(m1);
                }
                yield new CombinerEntityMaterial(
                        m1,
                        m2Fade != null ? null : m2,
                        mask,
                        op.getId(),
                        combiner.getModulateFactor(),
                        m2Fade,
                        combiner.isInvertMask(),
                        combiner.getAlphaOperation().getId(),
                        vertexColorMask,
                        m2Cube);
            }
            default -> toTextureMaterial(m1);
        };
    }

    private CombinerEntityMaterial addByAlphaOfMaterial2(UCombiner combiner, Texture m1, Texture m2) {
        var m1Fade = fadeSpecOf(combiner.getMaterial1());
        var alphaOp = switch (combiner.getAlphaOperation()) {
            case AO_Use_Alpha_From_Material1 -> EAlphaOperation.AO_Use_Alpha_From_Material2;
            case AO_Use_Alpha_From_Material2 -> EAlphaOperation.AO_Use_Alpha_From_Material1;
            default -> combiner.getAlphaOperation();
        };
        return new CombinerEntityMaterial(
                m2,
                m1Fade != null ? null : m1,
                m2,
                combiner.getCombineOperation().getId(),
                combiner.getModulateFactor(),
                m1Fade,
                false,
                alphaOp.getId(),
                false);
    }

    private @Nullable EnvCubeLayer envCubeLayerOf(UObjectRef<UMaterial, UObjectRef.Optional> ref) {
        UMaterial node;
        try {
            node = ref.get();
        } catch (RuntimeException e) {
            return null;
        }
        var envMapType = -1;
        var pitch = 0;
        var yaw = 0;
        var roll = 0;
        for (var depth = 0; node != null && depth < 8; depth++) {
            switch (node) {
                case UTexRotator rotator -> {
                    if (rotator.getTexRotationType() != 1) {
                        pitch += rotator.getRotation().pitch();
                        yaw += rotator.getRotation().yaw();
                        roll += rotator.getRotation().roll();
                    }
                    node = rotator.getMaterial().get();
                }
                case UTexEnvMap envMap -> {
                    envMapType = envMap.getEnvMapType().getId();
                    node = envMap.getEnvMapMaterial();
                }
                case UCubemap cubemap -> {
                    if (envMapType < 0) {
                        return null;
                    }
                    var cube = loadCubemap(cubemap);
                    return cube == null
                            ? null
                            : new EnvCubeLayer(
                                    cube, envMapType, rotatorRadians(pitch), rotatorRadians(yaw), rotatorRadians(roll));
                }
                case UModifier modifier -> node = modifier.getMaterial().get();
                default -> {
                    return null;
                }
            }
        }
        return null;
    }

    private @Nullable EnvCubeLayer specularCubeOf(UShader shader) {
        try {
            var node = shader.getSpecular().get();
            var environmentType = -1;
            for (var depth = 0; node instanceof UModifier modifier && depth < 8; depth++) {
                if (modifier instanceof UTexEnvMap environment) {
                    environmentType = environment.getEnvMapTypeId();
                    node = environment.getEnvMapMaterial();
                } else {
                    node = modifier.getMaterial().get();
                }
            }
            return node instanceof UCubemap && (environmentType == 0 || environmentType == 1)
                    ? envCubeLayerOf(shader.getSpecular())
                    : null;
        } catch (RuntimeException unresolved) {
            return null;
        }
    }

    private @Nullable Texture resolveCombinerLayer(UObjectRef<UMaterial, UObjectRef.Optional> ref) {
        try {
            var node = ref.get();
            if (constantsFromParticleColor && MeshParticleMaterials.isConstant(node)) {
                return solidColorTexture(new Vec4(1f, 1f, 1f, 1f));
            }
            var material = loadMaterial(node);
            if (material == null) {
                return null;
            }
            var texture = material.getTexture();
            if (texture != null) {
                return texture;
            }
            if (material instanceof ColorEntityMaterial(Vec4 color)
                    && (node instanceof UFadeColor || node instanceof UConstantColor)) {
                return solidColorTexture(color);
            }
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private @Nullable Cubemap loadCubemap(UCubemap cm) {
        var faces = new ArrayList<@Nullable Texture>(6);
        Texture first = null;
        for (var ref : cm.getFaces()) {
            UTexture faceTex;
            try {
                faceTex = ref.get();
            } catch (RuntimeException e) {
                return null;
            }
            if (faceTex == null) {
                faces.add(null);
                continue;
            }
            var tex = loadTexture(faceTex);
            if (tex == null || tex.data().isEmpty() || tex.data().getFirst().length == 0) {
                return null;
            }
            if (first == null) {
                first = tex;
            } else if (tex.width() != first.width() || tex.height() != first.height()) {
                return null;
            }
            faces.add(tex);
        }
        if (first == null || faces.size() != 6) {
            return null;
        }
        var complete = new ArrayList<Texture>(6);
        for (var face : faces) {
            complete.add(face != null ? face : emptyFace(first));
        }
        return new Cubemap(complete);
    }

    private @Nullable AnimatedTextureEntityMaterial loadAnimatedTexture(UTexture first) {
        if (first.getAnimationNextFrame() == null) {
            return null;
        }
        var frames = new ArrayList<Texture>();
        var seen = new HashSet<UTexture>();
        var frame = (UTexture) first;
        while (frame != null && seen.add(frame) && frames.size() < MAX_ANIM_FRAMES) {
            var texture = loadTexture(frame);
            if (texture != null) {
                frames.add(texture);
            }
            frame = frame.getAnimationNextFrame();
        }
        if (frames.size() < 2) {
            return null;
        }
        return new AnimatedTextureEntityMaterial(frames, animatedFrameRate(first.getMaxFrameRate()));
    }

    private @Nullable Texture loadTexture(@Nullable UTexture uTexture) {
        if (uTexture == null) {
            return null;
        }
        var mips = uTexture.getMips();
        if (mips == null || mips.length == 0) {
            return null;
        }
        var clampU = uTexture.getUClampMode() == TextureClampMode.TC_Clamp;
        var clampV = uTexture.getVClampMode() == TextureClampMode.TC_Clamp;
        var filters = new TextureFilters(uTexture.getMinFilter(), uTexture.getMagFilter(), uTexture.getMipFilter());
        var firstMip = textureQuality.firstMip(uTexture);
        var format = (TextureFormat) null;
        var height = uTexture.getVSize();
        var width = uTexture.getUSize();
        var mipLevels = Math.min(mips.length, Integer.SIZE - Integer.numberOfLeadingZeros(Math.min(width, height)));
        switch (uTexture.getFormat()) {
            case TEXF_DXT1 -> format = TextureFormat.TEXTURE_DXT1;
            case TEXF_DXT3 -> format = TextureFormat.TEXTURE_DXT3;
            case TEXF_DXT5 -> format = TextureFormat.TEXTURE_DXT5;
            case TEXF_RGBA8 -> format = TextureFormat.TEXTURE_RGBA;
            case TEXF_P8 -> {
                var uPalette = uTexture.getPalette().get();
                var colors = uPalette == null ? new UColor[256] : uPalette.getColors();
                if (uPalette == null) {
                    for (var index = 0; index < colors.length; index++) {
                        var gray = (byte) index;
                        colors[index] = new UColor(gray, gray, gray, (byte) 0);
                    }
                }

                var data = mips[0].getData();
                if (data.length == 0) {
                    return new Texture(
                            TextureFormat.TEXTURE_P8,
                            width,
                            height,
                            List.of(),
                            uTexture.isAlphaTexture(),
                            clampU,
                            clampV,
                            filters,
                            firstMip);
                }
                var colorKeyed = uTexture.isMasked();
                var levels = new ArrayList<byte[]>(mipLevels);
                for (var mip = 0; mip < mipLevels; mip++) {
                    var mipWidth = Math.max(1, width >> mip);
                    var mipHeight = Math.max(1, height >> mip);
                    data = mips[mip].getData();
                    var texture = new byte[mipHeight * mipWidth * 4];
                    for (var index = 0; index < mipWidth * mipHeight; index++) {
                        var palIndex = data[index] & 0xff;
                        if (colorKeyed && palIndex == 0) {
                            continue;
                        }
                        var color = colors[palIndex];
                        texture[index * 4] = color.r();
                        texture[index * 4 + 1] = color.g();
                        texture[index * 4 + 2] = color.b();
                        texture[index * 4 + 3] = color.a();
                    }
                    levels.add(texture);
                }
                return new Texture(
                        TextureFormat.TEXTURE_P8,
                        width,
                        height,
                        levels,
                        uTexture.isAlphaTexture(),
                        clampU,
                        clampV,
                        filters,
                        firstMip);
            }
            case TEXF_G16 -> {
                var data = mips[0].getData();
                if (data.length == 0) {
                    return null;
                }
                var levels = new ArrayList<byte[]>(mipLevels);
                for (var mip = 0; mip < mipLevels; mip++) {
                    var pixels = Math.max(1, width >> mip) * Math.max(1, height >> mip);
                    data = mips[mip].getData();
                    var texture = new byte[pixels * 4];
                    for (var i = 0; i < pixels; i++) {
                        var gray = data[i * 2 + 1];
                        texture[i * 4] = gray;
                        texture[i * 4 + 1] = gray;
                        texture[i * 4 + 2] = gray;
                        texture[i * 4 + 3] = (byte) 255;
                    }
                    levels.add(texture);
                }
                return new Texture(
                        TextureFormat.TEXTURE_RGBA,
                        width,
                        height,
                        levels,
                        uTexture.isAlphaTexture(),
                        clampU,
                        clampV,
                        filters,
                        firstMip);
            }
            default -> {
                var unknownFormat = String.valueOf(uTexture.getFormat());
                if (REPORTED_UNKNOWN_TEXTURE_FORMATS.add(unknownFormat)) {
                    log.warn("Unsupported texture format {}; affected textures are untextured", unknownFormat);
                } else {
                    log.debug("Repeated unsupported texture format {} for {}", unknownFormat, uTexture.getFullName());
                }
                return null;
            }
        }

        var authoredMipmaps =
                Arrays.stream(mips, 0, mipLevels).map(UMipmap::getData).toList();
        var mipmapsData = authoredMipmaps;
        if (isCompressed(format)) {
            dxtChains.recordCensus(uTexture, format, width, height, authoredMipmaps);
            mipmapsData = dxtChains.complete(uTexture, format, width, height, authoredMipmaps);
        }
        return new Texture(
                format, width, height, mipmapsData, uTexture.isAlphaTexture(), clampU, clampV, filters, firstMip);
    }

    private @Nullable Texture loadRenderedMaterial(@Nullable URenderedMaterial renderedMaterial) {
        if (renderedMaterial == null) {
            return null;
        }
        return switch (renderedMaterial) {
            case UTexture uTexture -> loadTexture(uTexture);
            case UShader uShader -> {
                var diffuseMaterial = loadMaterial(uShader.getDiffuse().get());
                if (diffuseMaterial == null) {
                    yield null;
                }
                if (diffuseMaterial instanceof TextureEntityMaterial(Texture diffuseTexture)) {
                    yield diffuseTexture;
                }
                yield diffuseMaterial.getTexture();
            }
            default -> null;
        };
    }

    private @Nullable EntityMaterial waterSheetMaterial(UShader shader, @Nullable EntityMaterial base) {
        if (base == null || base.getTexture() == null || !isWaterSheetShader(shader)) {
            return null;
        }
        var water = waterMaterial(shader, base.getTexture());
        return water.material2() == null ? null : water;
    }

    private WaterEntityMaterial waterMaterial(@Nullable UMaterial node, Texture texture) {
        return waterMaterial(node, texture, false);
    }

    private WaterEntityMaterial waterMaterial(@Nullable UMaterial node, Texture texture, boolean bspWater) {
        var secondLayer = getWaterSecondLayer(node);
        return new WaterEntityMaterial(
                texture,
                waterOpacityAlpha(node),
                secondLayer == null ? VEC3_EMPTY : secondLayer.additive(),
                secondLayer == null ? null : secondLayer.texture(),
                secondLayer == null ? UvAnimation.NONE : secondLayer.animation(),
                secondLayer == null ? 0f : secondLayer.rotation(),
                bspWater ? authoredMaterials.compileBspWater(node) : authoredMaterials.compile(node),
                bspWater ? AuthoredFramebuffer.resolveBspWater(node) : AuthoredFramebuffer.resolve(node),
                bspWater ? null : compileNativePasses(node));
    }

    private @Nullable AuthoredMaterialPassProgram compileNativePasses(@Nullable UMaterial node) {
        return materialClient == MaterialClient.UNKNOWN
                ? null
                : authoredMaterials.compilePasses(
                        node, 8, materialClient == MaterialClient.P152 ? Client.P152 : Client.P162);
    }

    private <T> T buildForMapActor(Supplier<T> build) {
        var outer = mapActorMaterials;
        mapActorMaterials = true;
        try {
            return build.get();
        } finally {
            mapActorMaterials = outer;
        }
    }

    private @Nullable AuthoredMaterialPassProgram compileWrapperPasses(UMaterial wrapper, WaterEntityMaterial water) {
        return water.authoredPasses() != null && water.authoredPasses().passes().hasReflectionCoordinates()
                ? compileReflectionPasses(wrapper)
                : compileNativePasses(wrapper);
    }

    private @Nullable EntityMaterial reflectionPassMaterial(UShader shader, @Nullable EntityMaterial base) {
        var diffuse = shader.getDiffuse().get();
        var opacity = slotMaterialNode(shader.getOpacity());
        if (!(mapActorMaterials || npcPartMaterials)
                || base == null
                || base.getTexture() == null
                || shader.getOutputBlending() != OutputBlending.OB_Normal
                || shader.isPerformLightingOnSpecularPass()
                || slotMaterialNode(shader.getSpecular()) == null
                || opacity == null
                || opacity == diffuse
                || baseTextureOf(opacity) == null
                || !holdsReflectionInCombiner(diffuse, false, 0)) {
            return null;
        }
        var passes = compileReflectionPasses(shader);
        if (passes == null
                || !passes.hasCompleteTextureBindings()
                || passes.passes().drawCount() <= 1) {
            return null;
        }
        return new WaterEntityMaterial(
                base.getTexture(),
                1f,
                VEC3_EMPTY,
                null,
                UvAnimation.NONE,
                0f,
                null,
                AuthoredFramebuffer.resolve(shader),
                passes,
                true);
    }

    private @Nullable AuthoredMaterialPassProgram compileReflectionPasses(UMaterial node) {
        if (materialClient != MaterialClient.UNKNOWN) {
            return authoredMaterials.compilePasses(
                    node, 8, materialClient == MaterialClient.P152 ? Client.P152 : Client.P162, true);
        }
        if (!hasReflectionText()) {
            return null;
        }
        var p152 = authoredMaterials.compilePasses(node, 8, Client.P152, true);
        var p162 = authoredMaterials.compilePasses(node, 8, Client.P162, true);
        return p152 != null && p152.equals(p162) ? p152 : null;
    }

    private boolean hasReflectionText() {
        if (reflectionTextOfP152 == null) {
            reflectionTextOfP152 = ReflectionCoordinateText.matchesP152(clientRoot);
        }
        return reflectionTextOfP152;
    }

    private @Nullable EnvironmentLayer environmentOf(UShader shader, UMaterial slot) {
        if (materialClient == MaterialClient.UNKNOWN) {
            return fixedFunctionLayerOf(slot);
        }
        var layer = hasReflectionText() ? environmentLayerOf(slot) : null;
        if (layer == null || layer.source() != TextureCoordinates.Source.CAMERA_REFLECTION) {
            return layer;
        }
        if (reflectionReplacedWithoutLight == null) {
            reflectionReplacedWithoutLight = ReflectionCoordinateText.replacesReflectionWithoutLight(clientRoot);
        }
        return takesSpecularPoint(shader, reflectionReplacedWithoutLight) ? layer.toSpecularPoint() : layer;
    }

    private EntityMaterial withShaderOpacity(@Nullable UMaterial node, EntityMaterial material) {
        var shader = shaderOf(node);
        var opacity = shader == null ? null : getOpacity(shader);
        if (opacity == null) {
            return material;
        }
        if (!(slotMaterialNode(shader.getOpacity()) instanceof UTexture)) {
            return switch (material) {
                case SpecularEntityMaterial(
                        TextureEntityMaterialWithOpacity(Texture texture, Texture ignored),
                        SpecularOverlay overlay) ->
                    new SpecularEntityMaterial(new TextureEntityMaterial(texture), overlay);
                case TextureEntityMaterialWithOpacity(Texture texture, Texture ignored) ->
                    new TextureEntityMaterial(texture);
                default -> material;
            };
        }
        return switch (material) {
            case SpecularEntityMaterial(CombinerEntityMaterial combiner, SpecularOverlay overlay) ->
                new SpecularEntityMaterial(
                        new TextureEntityMaterialWithOpacity(combiner.material1(), loadTexture(opacity)), overlay);
            case CombinerEntityMaterial combiner ->
                new TextureEntityMaterialWithOpacity(combiner.material1(), loadTexture(opacity));
            default -> material;
        };
    }

    private float waterOpacityAlpha(@Nullable UMaterial materialNode) {
        if (!(materialNode instanceof UShader shader)) {
            return 1f;
        }
        var opacity = getOpacity(shader);
        if (opacity == null) {
            return 1f;
        }
        var texture = loadTexture(opacity);
        if (texture == null || texture.data().isEmpty()) {
            return 1f;
        }
        var base = texture.data().getFirst();
        return switch (texture.format()) {
            case TEXTURE_DXT1, TEXTURE_DXT3, TEXTURE_DXT5 ->
                DxtMipCodec.averageAlpha(texture.format(), texture.width(), texture.height(), base);
            case TEXTURE_RGBA, TEXTURE_P8 -> averageByteAlpha(base);
        };
    }

    private Vec3 waterLayerAdditive(@Nullable UMaterial node) {
        if (!(node instanceof UCombiner combiner) || combiner.getCombineOperation() != EColorOperation.CO_Add) {
            return VEC3_EMPTY;
        }
        try {
            if (!(combiner.getMaterial2().get() instanceof UTexture plate)) {
                return VEC3_EMPTY;
            }
            var texture = loadTexture(plate);
            var solid = texture == null ? null : solidRgb(texture);
            return solid == null ? VEC3_EMPTY : solid;
        } catch (RuntimeException e) {
            return VEC3_EMPTY;
        }
    }

    @Nullable
    private EntityMaterial slotMaterial(UObjectRef<?, ?> ref) {
        try {
            return ref.get() instanceof UMaterial material ? loadMaterial(material) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Texture emptyFace(Texture like) {
        var levels = new ArrayList<byte[]>(like.data().size());
        for (var level : like.data()) {
            levels.add(new byte[level.length]);
        }
        return new Texture(
                like.format(),
                like.width(),
                like.height(),
                levels,
                like.alphaTexture(),
                like.clampU(),
                like.clampV(),
                like.filters(),
                like.firstMip());
    }

    private static TerrainCollision collisionTerrain(UTerrainInfoActor terrain) {
        var terrainMap = terrain.getTerrainMap().get();
        var width = terrainMap.getUSize();
        var height = terrainMap.getVSize();
        var heightmap = terrainMap.getMips()[0].getData();
        var terrainScale = terrain.getTerrainScale();
        var location = terrain.getLocation();
        var originX = -(location.getX() / terrainScale.getX()) + (float) (width / 2);
        var originY = -(location.getY() / terrainScale.getY()) + (float) (height / 2);
        var originZ = location.getZ() * -256f / terrainScale.getZ() + 32767f;
        var heightScale = terrainScale.getZ() * 0.00390625f;
        var visibility = terrain.getQuadVisibilityBitmap();
        var turns = terrain.getEdgeTurnBitmap();
        var out = new FloatArrayList(width * height * 26);
        var corner = new float[12];
        for (var y = 0; y < height - 1; y++) {
            for (var x = 0; x < width - 1; x++) {
                var quad = x + y * width;
                if (!visibility.get(quad)) {
                    continue;
                }
                for (var c = 0; c < 4; c++) {
                    var cx = x + (c == 1 || c == 2 ? 1 : 0);
                    var cy = y + (c >= 2 ? 1 : 0);
                    corner[c * 3] = (cx - originX) * terrainScale.getX();
                    corner[c * 3 + 1] = (cy - originY) * terrainScale.getY();
                    corner[c * 3 + 2] = (getHeight(heightmap, cy * width + cx) - originZ) * heightScale;
                }
                if (!turns.get(quad)) {
                    addTerrainTriangle(out, corner, 0, 1, 2, 0, x, y);
                    addTerrainTriangle(out, corner, 0, 2, 3, 1, x, y);
                } else {
                    addTerrainTriangle(out, corner, 0, 1, 3, 0, x, y);
                    addTerrainTriangle(out, corner, 3, 1, 2, 2, x, y);
                }
            }
        }
        var grid = new float[] {
            (0 - originX) * terrainScale.getX(),
            (0 - originY) * terrainScale.getY(),
            terrainScale.getX(),
            terrainScale.getY(),
            width,
            height,
            heightScale
        };
        return new TerrainCollision(out.toArray(), grid);
    }

    private static void addTerrainTriangle(
            FloatArrayList out, float[] corner, int a, int b, int c, int plane, int column, int row) {
        for (var index : new int[] {0, a, b, c}) {
            out.add(corner[index * 3]);
            out.add(corner[index * 3 + 1]);
            out.add(corner[index * 3 + 2]);
        }
        out.add(plane);
        out.add(column);
        out.add(row);
    }

    private static EntityMesh bakeModelMatrix(EntityMesh mesh, Mat4 modelMatrix) {
        var normalMatrix = new Mat3(modelMatrix).inverse().transpose();
        var vertices = new ArrayList<MeshVertex>(mesh.vertices().size());
        for (var vertex : mesh.vertices()) {
            var position = new Vec3(modelMatrix.times(new Vec4(vertex.position(), 1f)));
            var normal = normalMatrix.times(vertex.normal());
            var length = normal.length();
            if (length > 0f) {
                normal = normal.div(length);
            }
            vertices.add(new MeshVertex(
                    position,
                    normal,
                    vertex.uv(),
                    vertex.bakedLight(),
                    vertex.lightmapUv(),
                    vertex.color(),
                    vertex.sunVisibilityMask(),
                    vertex.secondaryUv(),
                    vertex.bspPlane()));
        }
        return new EntityMesh(
                vertices,
                mesh.indices(),
                mesh.surfaces(),
                mesh.instanceMatrices(),
                new Box(mesh.boundingBox(), modelMatrix),
                mesh.swayFrequency(),
                mesh.swayMaxAngle(),
                mesh.billboard(),
                mesh.staticMeshInstanceLighting());
    }

    private static double skyLayerRadiusSquared(Layer layer, Vec3 origin) {
        if (!(layer.entity().getMesh() instanceof EntityMesh mesh)) {
            return 0;
        }
        double radiusSquared = 0;
        for (var vertex : mesh.vertices()) {
            var position = vertex.position();
            var x = (double) position.getX() - origin.getX();
            var y = (double) position.getY() - origin.getY();
            var z = (double) position.getZ() - origin.getZ();
            radiusSquared = Math.max(radiusSquared, x * x + y * y + z * z);
        }
        return radiusSquared;
    }

    private static Layer skyLayer(Entity entity) {
        return new Layer(
                entity.getDebugName() != null ? entity.getDebugName() : "unknown",
                entity.getSourceBspSection(),
                entity);
    }

    private static boolean writesOpaqueSkyDepth(Entity entity) {
        if (!(entity.getMesh() instanceof EntityMesh mesh) || mesh.surfaces().isEmpty()) {
            return false;
        }
        var surface = mesh.surfaces().getFirst();
        return surface.depthWrite() && isOpaqueCsgDepthBlend(surface.blendMode());
    }

    private static void collectSkyBspProcessOrder(
            UModel model, int nodeIndex, Vec3 viewOrigin, List<Integer> out, boolean[] visited) {
        if (nodeIndex < 0 || nodeIndex >= model.getNodes().length || visited[nodeIndex]) {
            return;
        }
        var node = model.getNodes()[nodeIndex];
        var plane = node.getPlane();
        var dot = plane.getX() * viewOrigin.getX()
                + plane.getY() * viewOrigin.getY()
                + plane.getZ() * viewOrigin.getZ()
                - plane.getW();
        var near = dot > 0f ? node.getFrontIndex() : node.getBackIndex();
        var far = dot > 0f ? node.getBackIndex() : node.getFrontIndex();
        collectSkyBspProcessOrder(model, near, viewOrigin, out, visited);
        var coplanar = nodeIndex;
        while (coplanar >= 0 && coplanar < model.getNodes().length && !visited[coplanar]) {
            visited[coplanar] = true;
            out.add(coplanar);
            coplanar = model.getNodes()[coplanar].getPlaneIndex();
        }
        collectSkyBspProcessOrder(model, far, viewOrigin, out, visited);
    }

    private static boolean hasTerrain(UPackage uPackage) {
        var terrains = new ArrayList<UTerrainInfoActor>();
        uPackage.loadObjects("TerrainInfo", terrains);
        return !terrains.isEmpty();
    }

    private static int terrainPriority(UTerrainInfoActor terrain, String packageName) {
        var priority = 0;
        if (!terrain.isDeleteMe() && !terrain.isHidden()) {
            priority += 1;
        }
        if (terrain.hasSurface()) {
            priority += 4;
        }
        var mapName = terrain.getTerrainMap().peekFullName();
        if (mapName != null && mapName.substring(mapName.lastIndexOf('.') + 1).equalsIgnoreCase(packageName)) {
            priority += 2;
        }
        return priority;
    }

    private static @Nullable TerrainField sunTerrainField(UTerrainInfoActor terrain) {
        if (!terrain.hasSurface()) {
            return null;
        }
        var map = terrain.getTerrainMap().get();
        if (map == null || map.getMips() == null || map.getMips().length == 0) {
            return null;
        }
        var data = map.getMips()[0].getData();
        var width = map.getUSize();
        if (data == null || width <= 0 || data.length < width * width * 2) {
            return null;
        }
        var p = terrain.getPosition();
        var s = terrain.getScale();
        return new TerrainField(data, width, p.getX(), p.getY(), p.getZ(), s.getX(), s.getY(), s.getZ());
    }

    private static void ensureSectorsLoaded(UPackage uPackage, UTerrainInfoActor terrain) {
        var sectors = terrain.getSectors();
        if (!sectors.isEmpty()) {
            return;
        }
        var nativeSectors = terrain.resolveNativeSectors();
        if (nativeSectors.size() == 256) {
            sectors.addAll(nativeSectors);
        } else {
            uPackage.loadObjects("TerrainSector", sectors);
        }
        var sectorsByCell = new HashMap<Integer, UTerrainSector>();
        for (var sector : sectors) {
            var x = sector.getSectorX();
            var y = sector.getSectorY();
            if (x < 0 || x >= 16 || y < 0 || y >= 16) {
                continue;
            }
            sectorsByCell.compute(y * 16 + x, (_, v) -> {
                if (v != null) {
                    return v.getSectorWidth() < sector.getSectorWidth() ? v : sector;
                }
                return sector;
            });
        }
        var sortedSectors = sectorsByCell.values().stream()
                .sorted(comparingInt(sector -> sector.getSectorY() * 16 + sector.getSectorX()))
                .toList();
        if (sortedSectors.size() != 256) {
            throw new IllegalStateException(
                    "Expected 256 terrain sectors, got " + sortedSectors.size() + " in package: " + uPackage.getName());
        }
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                var sector = sortedSectors.get(y * 16 + x);
                if (sector.getSectorX() != x || sector.getSectorY() != y) {
                    throw new IllegalStateException("Sectors are not sorted in package: " + uPackage.getName());
                }
            }
        }
        sectors.clear();
        sectors.addAll(sortedSectors);
    }

    private record SideTerrain(UTerrainInfoActor terrain, float levelBrightness) {}

    private static boolean isTerrainQuadVisible(@Nullable UTerrainInfoActor terrain, int x, int y) {
        if (terrain == null) {
            return true;
        }
        var bitmap = terrain.getQuadVisibilityBitmap();
        var index = x + y * terrain.getTerrainMap().get().getUSize();
        return bitmap == null || index >= bitmap.size() || bitmap.get(index);
    }

    private static List<TerrainAlphaAtlas> packTerrainAlphaGroups(List<MultiTextureEntry> entries) {
        var layerCount = Math.min(TERRAIN_RENDER_LAYERS, entries.size());
        if (layerCount <= 1) {
            return List.of();
        }
        var decoded = new IdentityHashMap<Texture, byte[]>();
        var groups = new ArrayList<TerrainAlphaAtlas>((layerCount + 3) / 4);
        for (var firstLayer = 0; firstLayer < layerCount; firstLayer += 4) {
            var atlasWidth = 1;
            var atlasHeight = 1;
            var lastLayer = Math.min(layerCount, firstLayer + 4);
            for (var layer = firstLayer; layer < lastLayer; layer++) {
                var alpha = entries.get(layer).alphaMap();
                if (alpha == null || alpha.width() < 1 || alpha.height() < 1) {
                    continue;
                }
                atlasWidth = Math.max(atlasWidth, Math.min(TERRAIN_ALPHA_ATLAS_MAX_EXTENT, alpha.width()));
                atlasHeight = Math.max(atlasHeight, Math.min(TERRAIN_ALPHA_ATLAS_MAX_EXTENT, alpha.height()));
            }
            var packed = new byte[Math.multiplyExact(Math.multiplyExact(atlasWidth, atlasHeight), 4)];
            for (var channel = 0; channel < 4; channel++) {
                var layer = firstLayer + channel;
                if (layer >= layerCount) {
                    break;
                }
                var alpha = entries.get(layer).alphaMap();
                if (alpha == null || alpha.width() < 1 || alpha.height() < 1) {
                    continue;
                }
                if (atlasWidth == alpha.width() && atlasHeight == alpha.height()) {
                    TexturePixels.writeBaseRedInterleaved(alpha, packed, channel, 4);
                    continue;
                }
                var red = decoded.computeIfAbsent(alpha, TexturePixels::decodeBaseRed);
                if (firstLayer < TERRAIN_FIRST_DRAW_LAYERS) {
                    packTerrainAlphaChannel(
                            packed, atlasWidth, atlasHeight, channel, red, alpha.width(), alpha.height());
                } else {
                    packTerrainAlphaChannelSmoothly(
                            packed, atlasWidth, atlasHeight, channel, red, alpha.width(), alpha.height());
                }
            }
            groups.add(new TerrainAlphaAtlas(atlasWidth, atlasHeight, packed));
        }
        return List.copyOf(groups);
    }

    private static DecorationScatter decorationScatter(
            DecorationTemplate template, DecorationDensity density, DecorationGround ground, int @Nullable [] sectors) {
        var matrices = new ArrayList<Mat4>();
        var lighting = new ArrayList<Vec4>();
        var steadyLighting = new ArrayList<Vec3>();
        var light = template.light;
        var disregardTerrainLighting = template.disregardTerrainLighting;
        var marker = disregardTerrainLighting ? 5f : 3f;
        placeDecorations(template, density, ground, sectors, (x, y, location, rotation, scale) -> {
            matrices.add(Entity.modelMatrix(location, rotation.vector(), scale));
            lighting.add(
                    light.hasShadowMaps() && !disregardTerrainLighting
                            ? light.packedLight(x, y, marker)
                            : new Vec4(0f, 0f, 0f, marker));
            var steadyRgb = !disregardTerrainLighting ? light.steady.rgbAt(x, y) : 0;
            steadyLighting.add(new Vec3(
                    ((steadyRgb >>> 16) & 0xff) / 255f, ((steadyRgb >>> 8) & 0xff) / 255f, (steadyRgb & 0xff) / 255f));
        });
        return new DecorationScatter(matrices, lighting, steadyLighting);
    }

    private static @Nullable Entity meshDecorationEntity(
            StaticMeshActor actor,
            UDecorationLayer layer,
            MeshDecorationData.Instances instances,
            EntityMesh sourceMesh,
            boolean liveAmbient) {
        var count = instances.size();
        var matrices = new ArrayList<Mat4>(count);
        var lighting = new ArrayList<Vec4>(count);
        var steady = new ArrayList<Vec3>(count);
        var ambient = liveAmbient ? actor.getDecoAmbientColor() : 0;
        var union = new Box();
        var locations = instances.locations();
        var rotations = instances.rotations();
        var scales = instances.scales();
        var marker = new Vec4(0f, 0f, 0f, MeshDecoration.MARKER);
        for (var index = 0; index < count; index++) {
            var at = index * 3;
            var matrix = Entity.modelMatrix(
                    new Vec3(locations[at], locations[at + 1], locations[at + 2]),
                    new URotator(rotations[at], rotations[at + 1], rotations[at + 2]).vector(),
                    new Vec3(scales[at], scales[at + 1], scales[at + 2]));
            matrices.add(matrix);
            var placed = new Box(sourceMesh.boundingBox(), matrix);
            union.add(placed.getMin());
            union.add(placed.getMax());
            lighting.add(marker);
            var color = instances.colors()[index];
            steady.add(new Vec3(
                    Math.max(0, ((color >>> 16) & 0xff) - ((ambient >>> 16) & 0xff)) / 255f,
                    Math.max(0, ((color >>> 8) & 0xff) - ((ambient >>> 8) & 0xff)) / 255f,
                    Math.max(0, (color & 0xff) - (ambient & 0xff)) / 255f));
        }
        if (matrices.isEmpty()) {
            return null;
        }
        var mesh = new EntityMesh(
                sourceMesh.vertices(),
                sourceMesh.indices(),
                sourceMesh.surfaces(),
                matrices,
                union,
                0f,
                0f,
                sourceMesh.billboard(),
                sourceMesh.staticMeshInstanceLighting(),
                layer.getFadeoutRadiusMin(),
                layer.getFadeoutRadiusMax(),
                lighting);
        var entity = new Entity(mesh);
        entity.setSourceActor(actor.getSourcePath());
        entity.setTerrainDecorationSteadyLighting(steady);
        return entity;
    }

    private static void placeDecorations(
            DecorationTemplate template,
            DecorationDensity density,
            DecorationGround ground,
            int @Nullable [] sectors,
            DecorationPlacement placement) {
        var maxPerQuad = template.maxPerQuad;
        if (maxPerQuad <= 0) {
            return;
        }
        var width = ground.width();
        var height = ground.height();
        var random = new GameRandom();
        var count = sectors == null ? template.quadSectors.size() : sectors.length;
        for (var step = 0; step < count; step++) {
            var sector = sectors == null ? step : sectors[step];
            var quads = template.quadSectors.get(sector);
            random.setSeed(template.seed + sector);
            var quadsX = quads.offsetX() == 240 ? quads.quadsX() - 1 : quads.quadsX();
            var quadsY = quads.offsetY() == 240 ? quads.quadsY() - 1 : quads.quadsY();
            for (var j = 0; j < quadsY; j++) {
                for (var i = 0; i < quadsX; i++) {
                    var x = quads.offsetX() + i;
                    var y = quads.offsetY() + j;
                    if (x < 0 || y < 0 || x >= width - 1 || y >= height - 1) {
                        continue;
                    }
                    if (!template.showOnInvisibleTerrain && !ground.visible().get(y * width + x)) {
                        continue;
                    }
                    var value = density.quadValue(x, y, width, height) / 255f;
                    if (value == 0f) {
                        continue;
                    }
                    for (var trial = 0; trial < maxPerQuad; trial++) {
                        var chance = random.next();
                        var multiplier =
                                random.pickInRange(template.densityMultiplierMin, template.densityMultiplierMax);
                        if (chance < multiplier * value) {
                            placeDecoration(template, ground, x, y, random, placement);
                        }
                    }
                }
            }
        }
    }

    private static void placeDecoration(
            DecorationTemplate template,
            DecorationGround ground,
            int x,
            int y,
            GameRandom random,
            DecorationPlacement placement) {
        var fx = random.next();
        var fy = random.next();
        Vec3 corner;
        Vec3 along;
        Vec3 across;
        Vec3 point;
        if (fx <= fy) {
            corner = decorationVertex(template, ground, x, y + 1);
            along = decorationVertex(template, ground, x, y).minus(corner);
            across = decorationVertex(template, ground, x + 1, y + 1).minus(corner);
            point = corner.plus(along.times(fx)).plus(across.times(1f - fy));
        } else {
            corner = decorationVertex(template, ground, x + 1, y);
            along = decorationVertex(template, ground, x, y).minus(corner);
            across = decorationVertex(template, ground, x + 1, y + 1).minus(corner);
            point = corner.plus(along.times(1f - fx)).plus(across.times(fy));
        }
        var normal = safeNormal(along.cross(across));
        if (normal.getZ() < 0f) {
            normal = normal.times(-1f);
        }
        if (template.inverted) {
            normal = normal.times(-1f);
        }
        var up = template.alignToTerrain ? normal : new Vec3(0f, 0f, template.inverted ? -1f : 1f);
        var pitch = (int) (Math.atan2(up.getZ(), Math.sqrt(up.getX() * up.getX() + up.getY() * up.getY()))
                        * 65535f
                        / (2f * (float) Math.PI))
                - 16384;
        var yaw = (int) (Math.atan2(up.getY(), up.getX()) * 65535f / (2f * (float) Math.PI));
        if (template.randomYaw) {
            yaw = (int) (random.next() * 65535.0);
        }
        var location = point.plus(up.times(template.offset));
        var scaleZ = random.pickInRange(template.scaleMin.getZ(), template.scaleMax.getZ());
        var scaleY = random.pickInRange(template.scaleMin.getY(), template.scaleMax.getY());
        var scaleX = random.pickInRange(template.scaleMin.getX(), template.scaleMax.getX());
        placement.place(x, y, location, new URotator(pitch, yaw, 0), new Vec3(scaleX, scaleY, scaleZ));
    }

    private static Vec3 decorationVertex(DecorationTemplate template, DecorationGround ground, int x, int y) {
        var z = Short.toUnsignedInt(ground.samples()[y * ground.width() + x]);
        return new Vec3(
                x * template.scale.getX() + template.position.getX(),
                y * template.scale.getY() + template.position.getY(),
                z * template.scale.getZ() + template.position.getZ());
    }

    private static Vec3 safeNormal(Vec3 vector) {
        var length = vector.length();
        return length < 1e-8f ? new Vec3(0f, 0f, 0f) : vector.div(length);
    }

    private static List<DecorationSector> decorationSectors(List<UTerrainSector> sectors, int width, int height) {
        var result = new ArrayList<DecorationSector>();
        if (!sectors.isEmpty() && sectors.stream().allMatch(sector -> sector.getSectorSizeX() > 0)) {
            for (var sector : sectors) {
                result.add(new DecorationSector(
                        sector.getTerrainOffsetX(),
                        sector.getTerrainOffsetY(),
                        sector.getSectorSizeX(),
                        sector.getSectorSizeY()));
            }
            return List.copyOf(result);
        }
        for (var y = 0; y < height - 1; y += 16) {
            for (var x = 0; x < width - 1; x += 16) {
                result.add(new DecorationSector(x, y, 16, 16));
            }
        }
        return List.copyOf(result);
    }

    record DecorationSector(int offsetX, int offsetY, int quadsX, int quadsY) {}

    @FunctionalInterface
    interface DecorationPlacement {
        void place(int x, int y, Vec3 location, URotator rotation, Vec3 scale);
    }

    static final class GameRandom {
        private int state;

        void setSeed(int seed) {
            state = seed;
        }

        float next() {
            state = state * 196_314_165 + 907_633_515;
            return (state & 0x7f_ffff) / 8_388_608f;
        }

        float pickInRange(float min, float max) {
            return min == max ? max : max + next() * (min - max);
        }
    }

    private static float packLightBytes(int[] values, int offset, int count) {
        var packed = 0;
        var scale = 1;
        for (int i = 0; i < count; i++) {
            packed += values[offset + i] * scale;
            scale *= 256;
        }
        return packed;
    }

    private static float terrainLightAt(
            List<UTerrainSector> sectors, int x, int y, float fx, float fy, boolean edgeTurn, int variant) {
        var l00 = getLightMapValue(x, y, sectors, variant);
        var l10 = getLightMapValue(x + 1, y, sectors, variant);
        var l01 = getLightMapValue(x, y + 1, sectors, variant);
        var l11 = getLightMapValue(x + 1, y + 1, sectors, variant);
        if (!edgeTurn) {
            return fx >= fy ? l00 + fx * (l10 - l00) + fy * (l11 - l10) : l00 + fy * (l01 - l00) + fx * (l11 - l01);
        }
        return fx + fy <= 1f
                ? l00 + fx * (l10 - l00) + fy * (l01 - l00)
                : l11 + (1f - fy) * (l10 - l11) + (1f - fx) * (l01 - l11);
    }

    private static @Nullable LightActor rejectedInstanceSunSource(@Nullable UStaticMeshInstance rootInstance) {
        if (rootInstance == null || rootInstance.getEnvironmentLights().length == 0) {
            return null;
        }
        LightActor source;
        try {
            source = rootInstance.getEnvironmentLights()[0].getLight();
        } catch (RuntimeException ignored) {
            return null;
        }
        if (source == null || source.getLightEffect() != LE_SUNLIGHT) {
            return null;
        }
        return source;
    }

    private static final class SpatialLightScratch {
        final float[] matrix = new float[16];
        final float[] normalMatrix = new float[9];
        final float[] originLight = new float[3];
        final float[] vertexLight = new float[3];
        final float[] lightDistance = new float[LightGrid.MAX_LIGHTS];
        float[] deltas = new float[0];

        float[] getDeltas(int required) {
            if (deltas.length < required) {
                deltas = new float[Math.max(required, Math.max(96, deltas.length << 1))];
            }
            return deltas;
        }
    }

    private static @Nullable ActorVertexLighting bakeSpatialPropLight(
            EntityMesh mesh,
            UActor actor,
            @Nullable LightGrid lightGrid,
            float[] lightColor,
            int[] lightIdx,
            SpatialLightScratch scratch) {
        if (lightGrid == null || lightGrid.isEmpty()) {
            return null;
        }
        var placed = new Entity(mesh);
        placeActor(placed, actor);
        var modelMatrix = placed.getModelMatrix();
        var worldBounds = new Box(mesh.boundingBox(), modelMatrix);
        var candidateLights = lightGrid.findOverlappingLightIndices(worldBounds);
        if (candidateLights.length == 0) {
            return null;
        }
        var cellCandidates = lightGrid.getSingleSelectionCellCandidates(worldBounds);
        var fixedCandidates = cellCandidates != null;
        if (cellCandidates != null && cellCandidates.length < candidateLights.length) {
            candidateLights = cellCandidates;
        }
        var origin = actor.getPosition();
        bakeVertexLightUnclampedInto(
                lightGrid,
                origin.getX(),
                origin.getY(),
                origin.getZ(),
                0f,
                0f,
                0f,
                false,
                lightColor,
                lightIdx,
                candidateLights,
                fixedCandidates,
                scratch.lightDistance,
                scratch.originLight);
        var matrix = scratch.matrix;
        modelMatrix.to(matrix);
        var normalMatrix = scratch.normalMatrix;
        OneOffMerger.inverseTranspose3x3(matrix, normalMatrix);
        var sourceVertices = mesh.vertexBuffer();
        var deltas = scratch.getDeltas(sourceVertices.size() * 3);
        var changed = false;
        var deltaOffset = 0;
        for (var vertexIndex = 0; vertexIndex < sourceVertices.size(); vertexIndex++) {
            var px = sourceVertices.getPositionX(vertexIndex);
            var py = sourceVertices.getPositionY(vertexIndex);
            var pz = sourceVertices.getPositionZ(vertexIndex);
            var worldX = matrix[0] * px + matrix[4] * py + matrix[8] * pz + matrix[12];
            var worldY = matrix[1] * px + matrix[5] * py + matrix[9] * pz + matrix[13];
            var worldZ = matrix[2] * px + matrix[6] * py + matrix[10] * pz + matrix[14];
            var normalX = sourceVertices.getNormalX(vertexIndex);
            var normalY = sourceVertices.getNormalY(vertexIndex);
            var normalZ = sourceVertices.getNormalZ(vertexIndex);
            var nx = normalMatrix[0] * normalX + normalMatrix[3] * normalY + normalMatrix[6] * normalZ;
            var ny = normalMatrix[1] * normalX + normalMatrix[4] * normalY + normalMatrix[7] * normalZ;
            var nz = normalMatrix[2] * normalX + normalMatrix[5] * normalY + normalMatrix[8] * normalZ;
            var normalLength = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            var hasNormal = normalLength > 1e-8f;
            if (hasNormal) {
                nx /= normalLength;
                ny /= normalLength;
                nz /= normalLength;
            }
            bakeVertexLightUnclampedInto(
                    lightGrid,
                    worldX,
                    worldY,
                    worldZ,
                    nx,
                    ny,
                    nz,
                    hasNormal,
                    lightColor,
                    lightIdx,
                    candidateLights,
                    fixedCandidates,
                    scratch.lightDistance,
                    scratch.vertexLight);
            var deltaR = Math.max(scratch.vertexLight[0] - scratch.originLight[0], 0f);
            var deltaG = Math.max(scratch.vertexLight[1] - scratch.originLight[1], 0f);
            var deltaB = Math.max(scratch.vertexLight[2] - scratch.originLight[2], 0f);
            deltas[deltaOffset++] = deltaR;
            deltas[deltaOffset++] = deltaG;
            deltas[deltaOffset++] = deltaB;
            changed |= deltaR > 1e-4f || deltaG > 1e-4f || deltaB > 1e-4f;
        }
        if (!changed) {
            return null;
        }
        var bakedLights = new byte[sourceVertices.size() * 3];
        deltaOffset = 0;
        for (var vertexIndex = 0; vertexIndex < sourceVertices.size(); vertexIndex++) {
            var targetOffset = vertexIndex * 3;
            bakedLights[targetOffset] =
                    lightingByte(sourceVertices.getBakedLightX(vertexIndex) + deltas[deltaOffset++]);
            bakedLights[targetOffset + 1] =
                    lightingByte(sourceVertices.getBakedLightY(vertexIndex) + deltas[deltaOffset++]);
            bakedLights[targetOffset + 2] =
                    lightingByte(sourceVertices.getBakedLightZ(vertexIndex) + deltas[deltaOffset++]);
        }
        return new ActorVertexLighting(bakedLights, null);
    }

    private record ActorZoneLighting(Vec3 ambient, boolean sunAffected) {}

    private record StaticMeshActorLighting(Vec3 ambient, boolean sunAffected) {}

    private static StaticMeshActorLighting staticMeshActorLighting(
            @Nullable UModel model,
            @Nullable ActorZoneLighting[] zoneLighting,
            UActor actor,
            Box localBounds,
            EntityMesh mesh,
            int @Nullable [] nodeStack,
            @Nullable ZoneInfoActor levelInfo) {
        var r = 0f;
        var g = 0f;
        var b = 0f;
        var found = false;
        var sunAffected = false;
        var forcedTag = actor.getForcedRegionTag();
        var forcedRegion = actor.getForcedRegion();
        var constrained = BspLeafSelection.hasActorFilter(actor);
        if (model != null && zoneLighting != null && nodeStack != null && nodeStack.length > 0) {
            var placed = new Entity(mesh);
            placeActor(placed, actor);
            var worldBounds = new Box(localBounds, placed.getModelMatrix());
            var touched = BspLeafSelection.zoneMask(
                    model,
                    worldBounds,
                    nodeStack,
                    forcedTag,
                    forcedRegion,
                    actor.getForcedVisibilityZoneTag(),
                    levelInfo);
            while (touched != 0L) {
                var zone = Long.numberOfTrailingZeros(touched);
                touched &= touched - 1L;
                if (zone >= zoneLighting.length || zoneLighting[zone] == null) {
                    continue;
                }
                var lighting = zoneLighting[zone];
                var ambient = lighting.ambient();
                r = Math.max(r, ambient.getX());
                g = Math.max(g, ambient.getY());
                b = Math.max(b, ambient.getZ());
                sunAffected |= lighting.sunAffected();
                found = true;
            }
        }
        if (!found && !constrained && zoneLighting != null) {
            var zone = actor.getRegionZoneNumber();
            if (zone < 0 && model != null) {
                zone = findPositionZone(model, actor.getPosition());
            }
            if (zone >= 0 && zone < zoneLighting.length && zoneLighting[zone] != null) {
                var lighting = zoneLighting[zone];
                var ambient = lighting.ambient();
                r = ambient.getX();
                g = ambient.getY();
                b = ambient.getZ();
                sunAffected = lighting.sunAffected();
                found = true;
            }
        }
        if (!found && !constrained) {
            sunAffected = actor.isSunAffect();
        }
        var lightingActor = actor.getAmbientLightingActor();
        var glow = lightingActor.getAmbientGlow() == 255 ? 0f : lightingActor.getAmbientGlow() / 255f;
        return new StaticMeshActorLighting(
                new Vec3(clientColorChannel(r + glow), clientColorChannel(g + glow), clientColorChannel(b + glow)),
                sunAffected);
    }

    private static boolean hasValidInstanceColorStream(@Nullable UStaticMeshInstance instance, int vertexCount) {
        if (instance == null) {
            return false;
        }
        var colors = instance.getColorStream();
        return colors != null && colors.size() == vertexCount;
    }

    private static float maxComponent(Vec3 value) {
        return Math.max(value.getX(), Math.max(value.getY(), value.getZ()));
    }

    private static boolean hasValidLightFlags(LightInfo[] lights, int vertexCount) {
        var requiredBytes = (vertexCount + 7) >>> 3;
        for (var light : lights) {
            if (light.getVertexFlags().length >= requiredBytes) {
                return true;
            }
        }
        return false;
    }

    record StaticMeshSceneLight(
            Light light,
            short[] vertexFlags,
            boolean applied,
            float x,
            float y,
            float z,
            float r,
            float g,
            float b,
            float radius,
            int effect,
            float directionX,
            float directionY,
            float directionZ,
            int cone) {
        StaticMeshSceneLight(Light light, short[] vertexFlags) {
            this(light, vertexFlags, false);
        }

        StaticMeshSceneLight(Light light, short[] vertexFlags, boolean applied) {
            this(
                    light,
                    vertexFlags,
                    applied,
                    light.position().getX(),
                    light.position().getY(),
                    light.position().getZ(),
                    light.color().getX(),
                    light.color().getY(),
                    light.color().getZ(),
                    light.radius(),
                    light.lightEffect(),
                    light.direction().getX(),
                    light.direction().getY(),
                    light.direction().getZ(),
                    light.cone());
        }
    }

    private static StaticMeshSceneLight[] staticMeshSceneLights(UStaticMeshInstance instance, MaterialClient client) {
        var source = instance.getSceneLights();
        var result = new StaticMeshSceneLight[source.length];
        var count = 0;
        for (var info : source) {
            LightActor actor;
            try {
                actor = info.getLight();
            } catch (RuntimeException e) {
                actor = null;
            }
            var light = actor != null ? lightFromActor(actor, 1f, client) : null;
            if (light != null && !light.isAnimated() && !actor.isDynamicLight() && info.getVertexFlags().length > 0) {
                result[count++] = new StaticMeshSceneLight(light, info.getVertexFlags(), info.isApplied());
            }
        }
        return count == result.length ? result : Arrays.copyOf(result, count);
    }

    private static boolean usesCachedStaticMeshSceneLighting(
            @Nullable UStaticMeshInstance instance, int vertexCount, MaterialClient client) {
        if (instance == null) {
            return false;
        }
        var colors = instance.getColorStream();
        if (colors == null || colors.size() != vertexCount) {
            return false;
        }
        for (var light : staticMeshSceneLights(instance, client)) {
            if (!light.applied()) {
                return false;
            }
        }
        return true;
    }

    private record StaticMeshSceneLightIndex(StaticMeshSceneLight[] lights, long[] vertexMasks, int wordsPerVertex) {}

    private static StaticMeshSceneLightIndex indexStaticMeshSceneLights(
            StaticMeshSceneLight[] lights, int vertexCount) {
        var wordsPerVertex = (lights.length + Long.SIZE - 1) / Long.SIZE;
        var masks = new long[vertexCount * wordsPerVertex];
        for (int lightIndex = 0; lightIndex < lights.length; lightIndex++) {
            var flags = lights[lightIndex].vertexFlags();
            for (int byteIndex = 0; byteIndex < flags.length; byteIndex++) {
                var bits = flags[byteIndex] & 0xff;
                while (bits != 0) {
                    var bit = Integer.numberOfTrailingZeros(bits);
                    var vertex = (byteIndex << 3) + bit;
                    if (vertex < vertexCount) {
                        masks[vertex * wordsPerVertex + (lightIndex >>> 6)] |= 1L << lightIndex;
                    }
                    bits &= bits - 1;
                }
            }
        }
        return new StaticMeshSceneLightIndex(lights, masks, wordsPerVertex);
    }

    private static void computeStaticMeshSceneLighting(
            List<StaticMeshSceneLight> lights,
            int vertexIndex,
            float worldX,
            float worldY,
            float worldZ,
            float normalX,
            float normalY,
            float normalZ,
            float scaleGlow,
            float[] result) {
        int r = 0;
        int g = 0;
        int b = 0;
        for (var scene : lights) {
            if (!hasStaticMeshVertexLightFlag(scene.vertexFlags(), vertexIndex)) {
                continue;
            }
            var dx = scene.x() - worldX;
            var dy = scene.y() - worldY;
            var dz = scene.z() - worldZ;
            var intensity = lightAttenuation(scene, dx, dy, dz, normalX, normalY, normalZ)
                    * scaleGlow
                    * STATIC_MESH_SCENE_LIGHT_SCALE;
            var sourceR = lightingChannel(scene.r() * intensity);
            var sourceG = lightingChannel(scene.g() * intensity);
            var sourceB = lightingChannel(scene.b() * intensity);
            r = Math.min(255, r + sourceR);
            g = Math.min(255, g + sourceG);
            b = Math.min(255, b + sourceB);
        }
        result[0] = r / 255f;
        result[1] = g / 255f;
        result[2] = b / 255f;
    }

    private static void computeStaticMeshSceneLighting(
            StaticMeshSceneLightIndex index,
            int vertexIndex,
            float worldX,
            float worldY,
            float worldZ,
            float normalX,
            float normalY,
            float normalZ,
            float scaleGlow,
            float[] result) {
        int r = 0;
        int g = 0;
        int b = 0;
        var lights = index.lights();
        var offset = vertexIndex * index.wordsPerVertex();
        for (int word = 0; word < index.wordsPerVertex(); word++) {
            var selected = index.vertexMasks()[offset + word];
            while (selected != 0L) {
                var lightIndex = (word << 6) + Long.numberOfTrailingZeros(selected);
                var scene = lights[lightIndex];
                var dx = scene.x() - worldX;
                var dy = scene.y() - worldY;
                var dz = scene.z() - worldZ;
                var intensity = lightAttenuation(scene, dx, dy, dz, normalX, normalY, normalZ)
                        * scaleGlow
                        * STATIC_MESH_SCENE_LIGHT_SCALE;
                var sourceR = lightingChannel(scene.r() * intensity);
                var sourceG = lightingChannel(scene.g() * intensity);
                var sourceB = lightingChannel(scene.b() * intensity);
                r = Math.min(255, r + sourceR);
                g = Math.min(255, g + sourceG);
                b = Math.min(255, b + sourceB);
                selected &= selected - 1L;
            }
        }
        result[0] = r / 255f;
        result[1] = g / 255f;
        result[2] = b / 255f;
    }

    private static int lightingChannel(float value) {
        return Math.clamp((int) Math.floor(value * 255f), 0, 255);
    }

    private static byte lightingByte(float value) {
        return (byte) Math.clamp(Math.round(value * 255f), 0, 255);
    }

    private static Entity makeMeshEntity(
            EntityMesh mesh, String glName, String debugName, UActor actor, Vec3 instanceLight, boolean sunAffected) {
        var entity = new Entity(mesh);
        entity.setMeshName(glName);
        entity.setDebugName(shortName(debugName));
        entity.setSourceAsset(debugName);
        placeActor(entity, actor);
        if (actor instanceof UL2MovableStaticMeshActor movable) {
            entity.setMovement(movable.getMovement());
        }
        entity.setMotionLink(actor.getMotionLink());
        entity.setDoor(actor instanceof UMover);
        entity.setInstanceLight(instanceLight);
        entity.setSunAffected(sunAffected);
        if (actor instanceof StaticMeshActor staticMeshActor) {
            entity.setStaticMeshAmbientIntensity(staticMeshActor.getAmbientIntensity());
        }
        entity.setFullbrightDecoration(actor.getAmbientLightingActor().isUnlit());
        return entity;
    }

    private static float[] localTriangles(EntityMesh mesh) {
        var verts = mesh.vertexBuffer();
        var idx = mesh.indices();
        var out = new float[idx.length * 3];
        var o = 0;
        for (int i = 0; i + 2 < idx.length; i += 3) {
            for (int k = 0; k < 3; k++) {
                var vertexIndex = idx[i + k];
                out[o++] = verts.getPositionX(vertexIndex);
                out[o++] = verts.getPositionY(vertexIndex);
                out[o++] = verts.getPositionZ(vertexIndex);
            }
        }
        return o == out.length ? out : Arrays.copyOf(out, o);
    }

    private static String meshAssetPath(UObject asset) {
        var path = asset.getSourcePath();
        return path == null || path.isBlank() ? asset.getFullName() : path;
    }

    private static @Nullable UObject vertMeshOverride(UVertMesh mesh, int materialIndex, List<UObject> overrides) {
        var textureIndex = mesh.getMaterials()[materialIndex].textureIndex();
        return textureIndex >= 0 && textureIndex < overrides.size() ? overrides.get(textureIndex) : null;
    }

    private static String vertMeshCacheKey(UVertMesh mesh, List<UObject> overrides, boolean useMeshBlendMode) {
        var key = new StringBuilder(meshAssetPath(mesh)).append("#vert-fx#").append(useMeshBlendMode);
        for (var override : overrides) {
            key.append('|').append(override == null ? "-" : meshAssetPath(override));
        }
        return key.toString();
    }

    private static boolean isRigidAccessory(USkeletalMesh mesh, USkeletalMesh frame) {
        if (mesh.getBones().length != 1) {
            return false;
        }
        var name = SkeletalPose.boneKey(mesh.getBones()[0].name());
        for (var bone : frame.getBones()) {
            if (SkeletalPose.boneKey(bone.name()).equals(name)) {
                return false;
            }
        }
        return true;
    }

    private static @Nullable USphere litSphere(
            USkeletalMesh mesh, USkeletalMesh space, Map<String, Vec3> bones, float bottom) {
        var sphere = mesh.getBoundingSphere();
        if (!(sphere.radius() > 0)) {
            return null;
        }
        var center = new Vec3(sphere.location());
        var root = space.getBones().length == 0 ? null : bones.get(SkeletalPose.boneKey(space.getBones()[0].name()));
        if (root != null) {
            center = center.plus(space.convertMeshToLocal(root));
        }
        return new USphere(new Vec3(center.getX(), center.getY(), center.getZ() - bottom), sphere.radius());
    }

    private static NpcModel joinNpcParts(String reference, List<NpcModel> parts) {
        var bottom = Float.MAX_VALUE;
        var vertexCount = 0;
        var indexCount = 0;
        for (var part : parts) {
            vertexCount += part.entityMesh().vertices().size();
            indexCount += part.entityMesh().indices().length;
            for (var vertex : part.entityMesh().vertices()) {
                bottom = Math.min(bottom, vertex.position().getZ() - part.lowest());
            }
        }
        var vertices = MeshVertexBuffer.builder(vertexCount);
        var indices = new int[indexCount];
        var surfaces = new ArrayList<Entity.Surface>();
        var surfacePasses = new HashMap<Integer, NpcPartPasses>();
        var bounds = new Box();
        var vertexBase = 0;
        var indexBase = 0;
        for (var part : parts) {
            var mesh = part.entityMesh();
            for (var passes : part.surfacePasses().entrySet()) {
                surfacePasses.put(surfaces.size() + passes.getKey(), passes.getValue());
            }
            for (var vertex : mesh.vertices()) {
                var position = vertex.position();
                var z = position.getZ() - part.lowest() - bottom;
                bounds.add(new Vec3(position.getX(), position.getY(), z));
                vertices.add(
                        position.getX(),
                        position.getY(),
                        z,
                        vertex.normal().getX(),
                        vertex.normal().getY(),
                        vertex.normal().getZ(),
                        vertex.uv().getX(),
                        vertex.uv().getY());
            }
            var source = mesh.indices();
            for (var i = 0; i < source.length; i++) {
                indices[indexBase + i] = source[i] + vertexBase;
            }
            for (var surface : mesh.surfaces()) {
                surfaces.add(new Entity.Surface(
                        surface.type(),
                        surface.indexOffset() + indexBase,
                        surface.indexCount(),
                        surface.material(),
                        surface.blendMode(),
                        surface.uvAnimation(),
                        surface.water(),
                        surface.materialName(),
                        surface.alphaRef(),
                        surface.usesVertexColor(),
                        surface.depthWrite(),
                        surface.modulateStaticLighting2x(),
                        surface.reflectionPlane(),
                        surface.distanceFog(),
                        surface.waterSheetSource(),
                        surface.bspDrawSource()));
            }
            vertexBase += mesh.vertices().size();
            indexBase += source.length;
        }
        var bones = new HashMap<String, Vec3>();
        List<String> boneNames = List.of();
        for (var part : parts) {
            if (!part.bones().isEmpty()) {
                for (var bone : part.bones().entrySet()) {
                    var at = bone.getValue();
                    bones.put(bone.getKey(), new Vec3(at.getX(), at.getY(), at.getZ() - part.lowest() - bottom));
                }
                boneNames = part.boneNames();
                break;
            }
        }
        return new NpcModel(
                reference,
                new EntityMesh(vertices.build(), indices, surfaces, List.of(), bounds, 0f, 1f, false),
                parts.getFirst().pose(),
                -bottom,
                bones,
                boneNames,
                joinLitSpheres(parts, bottom),
                surfacePasses);
    }

    private static void accumulateNormal(float[] x, float[] y, float[] z, int vertex, float nx, float ny, float nz) {
        x[vertex] += nx;
        y[vertex] += ny;
        z[vertex] += nz;
    }

    private static Texture blackTextureLike(Texture template) {
        var mips = new ArrayList<byte[]>(template.data().size());
        for (var mip : template.data()) {
            mips.add(new byte[mip.length]);
        }
        return new Texture(
                template.format(),
                template.width(),
                template.height(),
                mips,
                false,
                template.clampU(),
                template.clampV(),
                template.filters(),
                template.firstMip());
    }

    private static EntityMesh withBeastLightmapSurfaces(EntityMesh mesh) {
        var surfaces = new ArrayList<Entity.Surface>(mesh.surfaces().size());
        for (var source : mesh.surfaces()) {
            surfaces.add(new Entity.Surface(
                    source.type() | SurfaceType.BEAST_LIGHTMAP.getId(),
                    source.indexOffset(),
                    source.indexCount(),
                    source.material(),
                    source.blendMode(),
                    source.uvAnimation(),
                    source.water(),
                    source.materialName(),
                    source.alphaRef(),
                    source.usesVertexColor(),
                    source.depthWrite(),
                    source.modulateStaticLighting2x(),
                    source.reflectionPlane(),
                    source.distanceFog(),
                    source.waterSheetSource(),
                    source.bspDrawSource()));
        }
        return withSurfaces(mesh, surfaces);
    }

    private static EntityMesh withSurfaces(EntityMesh mesh, List<Entity.Surface> surfaces) {
        return new EntityMesh(
                mesh.vertices(),
                mesh.indices(),
                surfaces,
                mesh.instanceMatrices(),
                mesh.boundingBox(),
                mesh.swayFrequency(),
                mesh.swayMaxAngle(),
                mesh.billboard(),
                mesh.staticMeshInstanceLighting());
    }

    private static @Nullable UMaterial meshEmitterMaterialNode(
            UStaticMesh sourceMesh, List<UObject> overrides, int surface, @Nullable String materialName) {
        if (materialName == null) {
            return null;
        }
        if (surface < overrides.size()
                && overrides.get(surface) instanceof UMaterial override
                && materialName.equals(override.getFullName())) {
            return override;
        }
        for (var slot : sourceMesh.getMaterials()) {
            try {
                var node = slot.getMaterial().get();
                if (node != null && materialName.equals(node.getFullName())) {
                    return node;
                }
            } catch (RuntimeException unresolved) {
            }
        }
        return null;
    }

    private static List<UObject> renderedStaticMeshOverrides(UStaticMesh mesh, List<UObject> authored) {
        if (authored.isEmpty()) {
            return authored;
        }
        var sections = mesh.getSurfaces();
        var result = new ArrayList<UObject>(sections.length);
        for (var slot = 0; slot < sections.length; slot++) {
            if (sections[slot].getTriangleMax() != 0) {
                result.add(slot < authored.size() ? authored.get(slot) : null);
            }
        }
        return result;
    }

    private static EntityMesh applyMeshEmitterAlphaSemantics(EntityMesh mesh, UMeshEmitter emitter) {
        if (!emitter.isUseMeshBlendMode() || !emitter.isUseParticleColor()) {
            return mesh;
        }
        var surfaces = new ArrayList<Entity.Surface>(mesh.surfaces().size());
        var changed = false;
        for (var source : mesh.surfaces()) {
            var blend = MeshParticleMaterials.blendUnderColorModifier(source.blendMode());
            if (blend == source.blendMode()) {
                surfaces.add(source);
                continue;
            }
            surfaces.add(new Entity.Surface(
                    source.type(),
                    source.indexOffset(),
                    source.indexCount(),
                    source.material(),
                    blend,
                    source.uvAnimation(),
                    source.water(),
                    source.materialName(),
                    source.alphaRef(),
                    source.usesVertexColor(),
                    source.depthWrite(),
                    source.modulateStaticLighting2x()));
            changed = true;
        }
        return changed
                ? new EntityMesh(
                        mesh.vertices(),
                        mesh.indices(),
                        surfaces,
                        mesh.instanceMatrices(),
                        mesh.boundingBox(),
                        mesh.swayFrequency(),
                        mesh.swayMaxAngle(),
                        mesh.billboard(),
                        mesh.staticMeshInstanceLighting())
                : mesh;
    }

    private static boolean isMotionlessImmortal(UMeshEmitter e) {
        return isZeroVec(e.getStartVelocityMin())
                && isZeroVec(e.getStartVelocityMax())
                && isZeroVec(e.getAcceleration())
                && e.getLifetimeMax() <= 0f;
    }

    private static boolean isZeroVec(Vec3 v) {
        return v.getX() == 0f && v.getY() == 0f && v.getZ() == 0f;
    }

    private static boolean coincidesWithStatic(
            String meshName, Vec3 pos, List<String> staticNames, List<Vec3> staticPositions) {
        for (int i = 0, n = staticNames.size(); i < n; i++) {
            if (staticNames.get(i).equals(meshName)) {
                var s = staticPositions.get(i);
                var dx = s.getX() - pos.getX();
                var dy = s.getY() - pos.getY();
                var dz = s.getZ() - pos.getZ();
                if (dx * dx + dy * dy + dz * dz <= 256f) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean doesMeshHaveBlackBackground(EntityMesh mesh) {
        for (var surface : mesh.surfaces()) {
            var texture = surface.material().getTexture();
            if (texture != null) {
                return isBlackBackgroundTexture(texture);
            }
        }
        return false;
    }

    private static boolean isBlackBackgroundTexture(Texture texture) {
        if (texture.data().isEmpty()) {
            return false;
        }
        var data = texture.data().getFirst();
        return switch (texture.format()) {
            case TEXTURE_RGBA ->
                data.length >= 4
                        && (data[3] & 0xFF) > 200
                        && (data[0] & 0xFF) < 24
                        && (data[1] & 0xFF) < 24
                        && (data[2] & 0xFF) < 24;
            case TEXTURE_DXT1 -> data.length >= 2 && isDark565((data[0] & 0xFF) | ((data[1] & 0xFF) << 8));
            case TEXTURE_DXT3, TEXTURE_DXT5 ->
                data.length >= 10 && isDark565((data[8] & 0xFF) | ((data[9] & 0xFF) << 8));
            default -> false;
        };
    }

    private static boolean isDark565(int rgb565) {
        return ((rgb565 >> 11) & 0x1F) < 3 && ((rgb565 >> 5) & 0x3F) < 6 && (rgb565 & 0x1F) < 3;
    }

    private static boolean isBlackBackgroundMaterial(EntityMaterial material) {
        var texture = material.getTexture();
        return texture != null
                && !texture.alphaTexture()
                && !hasAuthoredOpacity(material)
                && isBlackBackgroundTexture(texture);
    }

    private static boolean hasAuthoredOpacity(EntityMaterial material) {
        return switch (material) {
            case ConstantOpacityEntityMaterial _ -> true;
            case TextureEntityMaterialWithOpacity(var _, Texture opacity) -> opacity != null;
            case SpecularEntityMaterial(EntityMaterial base, var _) -> hasAuthoredOpacity(base);
            case ColorModifierEntityMaterial(EntityMaterial base, var _) -> hasAuthoredOpacity(base);
            default -> false;
        };
    }

    private static @Nullable Range l2Range(@Nullable UProperty property) {
        if (property == null || property.getSubProperties().isEmpty()) {
            return null;
        }
        var values = property.getSubProperties().getFirst();
        var min = values.get("Min");
        var max = values.get("Max");
        if (min == null || max == null) {
            return null;
        }
        var start = min.getFloatValue();
        var end = max.getFloatValue();
        return Float.isFinite(start) && Float.isFinite(end) && end >= start ? new Range(start, end) : null;
    }

    private static List<ColorKey> l2Colors(@Nullable UProperty property) {
        if (property == null || property.getSubProperties().isEmpty()) {
            return List.of();
        }
        var result = new ArrayList<ColorKey>();
        for (var values : property.getSubProperties()) {
            var timeProperty = values.get("Time");
            var fog = l2Color(values.get("FogColor"));
            var sky = l2Color(values.get("SkyColor"));
            var clouds = values.get("CloudColor");
            var haze = l2ArrayColor(values.get("HazeRingColor"), 0);
            if (timeProperty == null || fog == null || sky == null || haze == null || clouds == null) {
                continue;
            }
            var cloud1 = l2ArrayColor(clouds, 0);
            var cloud2 = l2ArrayColor(clouds, 1);
            var cloud3 = l2ArrayColor(clouds, 2);
            if (cloud1 != null && (clouds.getDataValue() == null || clouds.getDataValue().length == 4)) {
                cloud2 = cloud1;
                cloud3 = cloud1;
            }
            if (cloud1 == null || cloud2 == null || cloud3 == null) {
                continue;
            }
            result.add(new ColorKey(l2Time(timeProperty), fog, sky, cloud1, cloud2, cloud3, haze));
        }
        return result;
    }

    private static float l2Time(UProperty property) {
        return switch (property.getType()) {
            case Byte -> property.getUInt8Value() & 0xff;
            case Int -> property.getInt32Value();
            case Float -> property.getFloatValue();
            default -> 0;
        };
    }

    private static @Nullable Vec3 l2Color(@Nullable UProperty property) {
        if (property == null || property.getColorValue() == null) {
            return null;
        }
        return toNormalizedRgb(property.getColorValue());
    }

    private static @Nullable Vec3 l2ArrayColor(@Nullable UProperty property, int index) {
        if (property == null) {
            return null;
        }
        if (property.getDataValue() == null) {
            return index == 0 ? l2Color(property) : null;
        }
        var data = property.getDataValue();
        var offset = index * 4;
        if (offset + 3 >= data.length) {
            return null;
        }
        return new Vec3(
                (data[offset + 2] & 0xff) / 255f, (data[offset + 1] & 0xff) / 255f, (data[offset] & 0xff) / 255f);
    }

    private static Vec3 toNormalizedRgb(UColor color) {
        return new Vec3(
                Byte.toUnsignedInt(color.r()) / 255f,
                Byte.toUnsignedInt(color.g()) / 255f,
                Byte.toUnsignedInt(color.b()) / 255f);
    }

    private static NativeLightScene loadNativeLightScene(
            UPackage uPackage,
            @Nullable UModel model,
            float levelBrightness,
            MaterialClient client,
            List<LightInfo> rootEnvironmentSources) {
        var levels = new ArrayList<ULevel>();
        uPackage.loadObjects("Level", levels);
        ULevel owner = null;
        for (var level : levels) {
            if (model == null || level.getModel().get() == model) {
                owner = level;
                break;
            }
        }
        var scene = NativeLightScene.capture(owner, model, levelBrightness, client, rootEnvironmentSources);
        if (!scene.getUnresolvedStaticSources().isEmpty()) {
            log.warn(
                    "{} native static light references could not be resolved in {} (first: {})",
                    scene.getUnresolvedStaticSources().size(),
                    uPackage.getName(),
                    scene.getUnresolvedStaticSources().getFirst());
        }
        return scene;
    }

    private static List<Light> loadLights(UPackage uPackage, float levelBrightness, MaterialClient client) {
        var actors = new ArrayList<LightActor>();
        uPackage.loadObjects("Light", actors);
        uPackage.loadObjects("Spotlight", actors);
        var lights = new ArrayList<Light>();
        for (var actor : actors) {
            var light = lightFromActor(actor, levelBrightness, client);
            if (light != null) {
                lights.add(light);
            }
        }
        return lights;
    }

    private static @Nullable Light lightFromActor(LightActor actor, float levelBrightness, MaterialClient client) {
        if (actor.isDeleteMe()
                || actor.getLightType() == 0
                || actor.getLightBrightness() == 0
                || actor.getLightRadius() == 0) {
            return null;
        }
        return new Light(
                actor.getPosition(),
                lightColor(actor.getLightHue(), actor.getLightSaturation(), actor.getLightBrightness(), client)
                        .times(levelBrightness),
                (actor.getLightRadius() + 1f) * LIGHT_RADIUS_SCALE,
                actor.getLightEffect(),
                forwardVector(actor.getRotation()),
                actor.getLightCone(),
                actor.getLightType(),
                actor.getLightPeriod(),
                actor.getLightPhase());
    }

    private static float loadLevelBrightness(UPackage uPackage) {
        var levels = new ArrayList<ZoneInfoActor>();
        uPackage.loadObjects("LevelInfo", levels);
        for (var level : levels) {
            if (!level.isDeleteMe() && Float.isFinite(level.getLevelBrightness())) {
                return Math.max(0f, level.getLevelBrightness());
            }
        }
        return 1f;
    }

    private static Vec3 forwardVector(URotator rotation) {
        var pitch = URotator.toRadians(rotation.pitch());
        var yaw = URotator.toRadians(rotation.yaw());
        var cosPitch = (float) Math.cos(pitch);
        return new Vec3(cosPitch * (float) Math.cos(yaw), cosPitch * (float) Math.sin(yaw), (float) Math.sin(pitch));
    }

    private static Vec3 bakeVertexLight(
            @Nullable LightGrid lightGrid,
            Vec3 position,
            @Nullable Vec3 normal,
            @Nullable float[] posRadius,
            @Nullable float[] color,
            @Nullable int[] idx,
            @Nullable Vec3 floor) {
        return bakeVertexLight(lightGrid, position, normal, posRadius, color, idx, floor, true);
    }

    private static Vec3 bakeVertexLightUnclamped(
            @Nullable LightGrid lightGrid,
            Vec3 position,
            @Nullable Vec3 normal,
            @Nullable float[] posRadius,
            @Nullable float[] color,
            @Nullable int[] idx,
            @Nullable Vec3 floor) {
        return bakeVertexLight(lightGrid, position, normal, posRadius, color, idx, floor, false);
    }

    private static Vec3 bakeVertexLightUnclamped(
            @Nullable LightGrid lightGrid,
            Vec3 position,
            @Nullable Vec3 normal,
            @Nullable float[] posRadius,
            @Nullable float[] color,
            @Nullable int[] idx,
            @Nullable Vec3 floor,
            int[] candidates) {
        return bakeVertexLight(lightGrid, position, normal, posRadius, color, idx, floor, false, candidates);
    }

    private static Vec3 bakeVertexLight(
            @Nullable LightGrid lightGrid,
            Vec3 position,
            @Nullable Vec3 normal,
            @Nullable float[] posRadius,
            @Nullable float[] color,
            @Nullable int[] idx,
            @Nullable Vec3 floor,
            boolean clamp) {
        return bakeVertexLight(lightGrid, position, normal, posRadius, color, idx, floor, clamp, null);
    }

    private static Vec3 bakeVertexLight(
            @Nullable LightGrid lightGrid,
            Vec3 position,
            @Nullable Vec3 normal,
            @Nullable float[] posRadius,
            @Nullable float[] color,
            @Nullable int[] idx,
            @Nullable Vec3 floor,
            boolean clamp,
            @Nullable int[] candidates) {
        float r = 0f, g = 0f, b = 0f;
        if (lightGrid != null && !lightGrid.isEmpty()) {
            var px = position.getX();
            var py = position.getY();
            var pz = position.getZ();
            var found = lightGrid.select(px, py, pz, LightGrid.MAX_LIGHTS, posRadius, color, idx, candidates);
            for (int k = 0; k < found; k++) {
                var dx = posRadius[k * 4] - px;
                var dy = posRadius[k * 4 + 1] - py;
                var dz = posRadius[k * 4 + 2] - pz;
                var radius = posRadius[k * 4 + 3];
                var att = lightAttenuation(lightGrid.getLight(idx[k]), dx, dy, dz, radius, normal);
                r += color[k * 4] * att;
                g += color[k * 4 + 1] * att;
                b += color[k * 4 + 2] * att;
            }
            r *= BAKE_INTENSITY;
            g *= BAKE_INTENSITY;
            b *= BAKE_INTENSITY;
        }
        if (floor != null) {
            r += floor.getX();
            g += floor.getY();
            b += floor.getZ();
        }
        if (r == 0f && g == 0f && b == 0f) {
            return VEC3_EMPTY;
        }
        return clamp ? new Vec3(Math.min(1f, r), Math.min(1f, g), Math.min(1f, b)) : new Vec3(r, g, b);
    }

    private static void bakeVertexLightUnclampedInto(
            LightGrid lightGrid,
            float px,
            float py,
            float pz,
            float normalX,
            float normalY,
            float normalZ,
            boolean hasNormal,
            float[] scoreScratch,
            int[] idx,
            int @Nullable [] candidates,
            boolean candidatesCoverCell,
            float[] distance,
            float[] result) {
        float r = 0f;
        float g = 0f;
        float b = 0f;
        var found = candidatesCoverCell
                ? lightGrid.selectIndicesFromCandidates(
                        px, py, pz, LightGrid.MAX_LIGHTS, idx, candidates, distance, scoreScratch)
                : lightGrid.selectIndices(px, py, pz, LightGrid.MAX_LIGHTS, idx, candidates, distance, scoreScratch);
        for (int k = 0; k < found; k++) {
            var lightIndex = idx[k];
            var dx = lightGrid.getPositionX(lightIndex) - px;
            var dy = lightGrid.getPositionY(lightIndex) - py;
            var dz = lightGrid.getPositionZ(lightIndex) - pz;
            var distSq = dx * dx + dy * dy + dz * dz;
            var att = lightAttenuation(
                    lightGrid.getLight(lightIndex),
                    dx,
                    dy,
                    dz,
                    lightGrid.getRadius(lightIndex),
                    distance[k],
                    distSq,
                    normalX,
                    normalY,
                    normalZ,
                    hasNormal);
            r += lightGrid.getRed(lightIndex) * att;
            g += lightGrid.getGreen(lightIndex) * att;
            b += lightGrid.getBlue(lightIndex) * att;
        }
        result[0] = r * BAKE_INTENSITY;
        result[1] = g * BAKE_INTENSITY;
        result[2] = b * BAKE_INTENSITY;
    }

    private static float lightAttenuation(
            Light light,
            float dx,
            float dy,
            float dz,
            float radius,
            float normalX,
            float normalY,
            float normalZ,
            boolean hasNormal) {
        var distSq = dx * dx + dy * dy + dz * dz;
        return lightAttenuation(
                light, dx, dy, dz, radius, (float) Math.sqrt(distSq), distSq, normalX, normalY, normalZ, hasNormal);
    }

    private static float lightAttenuation(
            Light light,
            float dx,
            float dy,
            float dz,
            float radius,
            float dist,
            float distSq,
            float normalX,
            float normalY,
            float normalZ,
            boolean hasNormal) {
        var effect = light.lightEffect();
        if (effect == LE_SUNLIGHT) {
            if (!hasNormal) {
                return 0f;
            }
            var direction = light.direction();
            var dot = direction.getX() * normalX + direction.getY() * normalY + direction.getZ() * normalZ;
            return dot < 0f ? -dot * 2f : 0f;
        }
        if (effect == LE_CYLINDER) {
            if (dist >= radius || radius <= 0f) {
                return 0f;
            }
            return Math.max(0f, 1f - (dx * dx + dy * dy) / (radius * radius)) * 2f;
        }
        var dot = dx * normalX + dy * normalY + dz * normalZ;
        if (effect == LE_NON_INCIDENCE) {
            return hasNormal && dot > 0f && dist < radius && radius > 0f
                    ? (float) Math.sqrt(1.02f - dist / radius) * 2f
                    : 0f;
        }
        if (effect == LE_QUADRATIC_NON_INCIDENCE) {
            var radiusSq = radius * radius;
            return hasNormal && dot > 0f && distSq < radiusSq && radiusSq > 0f ? (1.02f - distSq / radiusSq) * 2f : 0f;
        }
        var base = attenuationBase(dist, radius, dx, dy, dz, normalX, normalY, normalZ, hasNormal);
        if (base <= 0f) {
            return 0f;
        }
        if (effect != LE_SPOTLIGHT && effect != LE_STATIC_SPOT) {
            return base;
        }
        var sine = 1f - light.cone() / 256f;
        var rSine = 1f / (1f - sine);
        var sineRSine = sine * rSine;
        var sineSq = sine * sine;
        var dir = light.direction();
        var vDotV = -(dx * dir.getX() + dy * dir.getY() + dz * dir.getZ());
        if (vDotV > 0f && vDotV * vDotV > sineSq * distSq) {
            var cone = vDotV * rSine / dist - sineRSine;
            return cone * cone * base;
        }
        return 0f;
    }

    private static float lightAttenuation(
            StaticMeshSceneLight light, float dx, float dy, float dz, float normalX, float normalY, float normalZ) {
        var distSq = dx * dx + dy * dy + dz * dz;
        var dist = (float) Math.sqrt(distSq);
        if (light.effect() == LE_SUNLIGHT) {
            var dot = light.directionX() * normalX + light.directionY() * normalY + light.directionZ() * normalZ;
            return dot < 0f ? -dot * 2f : 0f;
        }
        if (light.effect() == LE_CYLINDER) {
            if (dist >= light.radius() || light.radius() <= 0f) {
                return 0f;
            }
            return Math.max(0f, 1f - (dx * dx + dy * dy) / (light.radius() * light.radius())) * 2f;
        }
        var dot = dx * normalX + dy * normalY + dz * normalZ;
        if (light.effect() == LE_NON_INCIDENCE) {
            return dot > 0f && dist < light.radius() && light.radius() > 0f
                    ? (float) Math.sqrt(1.02f - dist / light.radius()) * 2f
                    : 0f;
        }
        if (light.effect() == LE_QUADRATIC_NON_INCIDENCE) {
            var radiusSq = light.radius() * light.radius();
            return dot > 0f && distSq < radiusSq && radiusSq > 0f ? (1.02f - distSq / radiusSq) * 2f : 0f;
        }
        var base = attenuationBase(dist, light.radius(), dx, dy, dz, normalX, normalY, normalZ, true);
        if (base <= 0f) {
            return 0f;
        }
        if (light.effect() != LE_SPOTLIGHT && light.effect() != LE_STATIC_SPOT) {
            return base;
        }
        var sine = 1f - light.cone() / 256f;
        var rSine = 1f / (1f - sine);
        var sineRSine = sine * rSine;
        var sineSq = sine * sine;
        var vDotV = -(dx * light.directionX() + dy * light.directionY() + dz * light.directionZ());
        if (vDotV > 0f && vDotV * vDotV > sineSq * distSq) {
            var cone = vDotV * rSine / dist - sineRSine;
            return cone * cone * base;
        }
        return 0f;
    }

    private static float attenuationBase(
            float dist,
            float radius,
            float dx,
            float dy,
            float dz,
            float normalX,
            float normalY,
            float normalZ,
            boolean hasNormal) {
        if (dist > radius) {
            return 0f;
        }
        var a = dist / radius;
        var b = 2f * a * a * a - 3f * a * a + 1f;
        if (!hasNormal) {
            return b * 2f;
        }
        var dot = dx * normalX + dy * normalY + dz * normalZ;
        if (dot <= 0f) {
            return 0f;
        }
        var c = Math.abs(dot / radius);
        return b / a * c * 2f;
    }

    private static @Nullable Vec3 loadInteriorAmbient(UPackage uPackage) {
        var zones = new ArrayList<ZoneInfoActor>();
        uPackage.loadObjects("ZoneInfo", zones);
        uPackage.loadObjects("LevelInfo", zones);
        ZoneInfoActor best = null;
        for (var zone : zones) {
            if (zone.isSunAffect() || zone.getAmbientBrightness() <= 0) {
                continue;
            }
            if (best == null || zone.getAmbientBrightness() > best.getAmbientBrightness()) {
                best = zone;
            }
        }
        if (best == null) {
            return null;
        }
        var tint = normalizedTint(best.getAmbientVector());
        return new Vec3(
                tint.getX() * INTERIOR_AMBIENT_LEVEL,
                tint.getY() * INTERIOR_AMBIENT_LEVEL,
                tint.getZ() * INTERIOR_AMBIENT_LEVEL);
    }

    private static @Nullable ActorZoneLighting[] buildActorZoneLighting(
            UModel model, @Nullable ZoneInfoActor levelInfo) {
        var zones = model.getZones();
        if (zones == null || zones.length == 0) {
            return null;
        }
        var lighting = new ActorZoneLighting[zones.length];
        var any = false;
        for (int i = 0; i < zones.length; i++) {
            ZoneInfoActor zone;
            try {
                zone = zones[i].getZoneActor().get();
            } catch (RuntimeException e) {
                zone = null;
            }
            if (zone == null) {
                zone = levelInfo;
            }
            if (zone == null || zone.getAmbientVector() == null) {
                continue;
            }
            var ambient = zone.getAmbientVector();
            lighting[i] = new ActorZoneLighting(
                    new Vec3(clamp01(ambient.getX()), clamp01(ambient.getY()), clamp01(ambient.getZ())),
                    zone.isSunAffect());
            any = true;
        }
        return any ? lighting : null;
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    private static @Nullable DistanceFog pickDistanceFog(UModel model, UBspNode node) {
        var zones = model.getZones();
        if (zones == null || zones.length == 0) {
            return null;
        }
        var nodeZones = node.getZone();
        for (var slot : new int[] {nodeZones[1], nodeZones[0]}) {
            if (slot < 0 || slot >= zones.length) {
                continue;
            }
            ZoneInfoActor zone;
            try {
                zone = zones[slot].getZoneActor().get();
            } catch (RuntimeException e) {
                zone = null;
            }
            if (zone == null || !zone.isDistanceFog() || zone.getDistanceFogEnd() <= zone.getDistanceFogStart()) {
                continue;
            }
            var color = zone.getDistanceFogColor();
            return new DistanceFog(
                    new Vec3((color.r() & 0xFF) / 255f, (color.g() & 0xFF) / 255f, (color.b() & 0xFF) / 255f),
                    zone.getDistanceFogStart(),
                    zone.getDistanceFogEnd());
        }
        return null;
    }

    private static boolean isWaterFrontZoneSunAffected(UModel model, int frontZone, @Nullable ZoneInfoActor levelInfo) {
        var zones = model.getZones();
        var zone = levelInfo;
        if (zones != null && frontZone >= 0 && frontZone < zones.length) {
            var resolved = zones[frontZone].getZoneActor().get();
            if (resolved != null) {
                zone = resolved;
            }
        }
        return zone != null && zone.isSunAffect();
    }

    private static BspDrawSource bspDrawSource(UModel model, UBspNode node) {
        var zones = node.getZone();
        var mask = 0L;
        if (zones != null) {
            for (var zone : zones) {
                if (zone >= 0 && zone < Long.SIZE) {
                    mask |= 1L << zone;
                }
            }
        }
        return new BspDrawSource(model.getFullName(), mask == 0L ? -1L : mask);
    }

    private static Vec3 normalizedTint(@Nullable Vec3 v) {
        if (v == null) {
            return new Vec3(1.0f, 0.95f, 0.85f);
        }
        var max = Math.max(v.getX(), Math.max(v.getY(), v.getZ()));
        if (max <= 0f) {
            return new Vec3(1.0f, 0.95f, 0.85f);
        }
        return new Vec3(v.getX() / max, v.getY() / max, v.getZ() / max);
    }

    private static void addCluster(
            List<Billboard> out,
            Random rng,
            Vec3 position,
            float size,
            Vec4 baseColor,
            int blend,
            @Nullable Texture sprite,
            int animType) {
        var alphaScale = switch (blend) {
            case Billboard.BLEND_MODULATE -> 1f;
            case Billboard.BLEND_ADDITIVE, Billboard.BLEND_SCREEN ->
                Math.min(1f, EMITTER_FX_BOOST / (float) Math.sqrt(EMITTER_CLUSTER_COUNT));
            default -> 1f / (float) Math.sqrt(EMITTER_CLUSTER_COUNT);
        };
        var color = new Vec4(baseColor.getX(), baseColor.getY(), baseColor.getZ(), baseColor.getW() * alphaScale);
        for (int i = 0; i < EMITTER_CLUSTER_COUNT; i++) {
            var jitterX = (rng.nextFloat() * 2f - 1f) * size * EMITTER_CLUSTER_SPREAD;
            var jitterY = (rng.nextFloat() * 2f - 1f) * size * EMITTER_CLUSTER_SPREAD;
            var jitterZ = (rng.nextFloat() * 2f - 1f) * size * EMITTER_CLUSTER_SPREAD * 0.4f;
            var pos = new Vec3(position.getX() + jitterX, position.getY() + jitterY, position.getZ() + jitterZ);
            var quadSize = size * (1f - EMITTER_CLUSTER_SIZE_VAR + rng.nextFloat() * 2f * EMITTER_CLUSTER_SIZE_VAR);
            out.add(new Billboard(pos, quadSize, color, blend, sprite, rng.nextFloat(), animType));
        }
    }

    private static int blendOfDrawStyle(int drawStyle) {
        return blendOfDrawStyle(drawStyle, null);
    }

    private static boolean hasAlphaChannel(@Nullable Texture texture) {
        if (texture == null) {
            return false;
        }
        return switch (texture.format()) {
            case TEXTURE_DXT3, TEXTURE_DXT5 -> hasVaryingAlpha(texture);
            case TEXTURE_RGBA -> true;
            default -> false;
        };
    }

    private static int animTypeOfTexture(String textureName) {
        var name = textureName == null ? "" : textureName.toLowerCase();
        if (name.contains("smoke")
                || name.contains("fog")
                || name.contains("mist")
                || name.contains("cloud")
                || name.contains("dust")
                || name.contains("steam")) {
            return Billboard.ANIM_RISE;
        }
        return Billboard.ANIM_FLICKER;
    }

    private static Texture toLightmapTexture(FStaticLightmapTexture atlas) {
        return new Texture(
                TextureFormat.TEXTURE_DXT1,
                atlas.width(),
                atlas.height(),
                DxtMipCodec.buildChain(TextureFormat.TEXTURE_DXT1, atlas.width(), atlas.height(), atlas.data()),
                false);
    }

    private static Texture lightmapTexture(
            Entity entity, FStaticLightmapTexture atlas, Map<FStaticLightmapTexture, Integer> sources) {
        var texture = toLightmapTexture(atlas);
        var source = sources.get(atlas);
        if (source != null) {
            entity.setBspLightmapSource(texture, source);
        }
        return texture;
    }

    private static @Nullable FStaticLightmapTexture firstUsableLightmap(
            @Nullable FStaticLightmapTexture first, FStaticLightmapTexture @Nullable [] variants) {
        if (first != null && !first.isEmpty()) {
            return first;
        }
        if (variants != null) {
            for (var variant : variants) {
                if (!variant.isEmpty()) {
                    return variant;
                }
            }
        }
        return null;
    }

    private static Vec2 lightmapUv(FLightmapIndex li, Vec3 position, float atlasWidth, float atlasHeight) {
        var m = li.uvMatrix();
        var px = position.getX();
        var py = position.getY();
        var pz = position.getZ();
        var u = (li.offsetX() + m[0] * px + m[4] * py + m[8] * pz + m[12]) / atlasWidth;
        var v = (li.offsetY() + m[1] * px + m[5] * py + m[9] * pz + m[13]) / atlasHeight;
        return new Vec2(u, v);
    }

    private static boolean isWindingReversed(UModel model, UBspNode node, Vec3[] points, Vec3 normal) {
        var count = node.getVertexCount();
        if (count < 3) {
            return false;
        }
        var pool = node.getVertexPoolIndex();
        var verts = model.getVertices();
        var nx = 0f;
        var ny = 0f;
        var nz = 0f;
        var prev = points[verts[pool + count - 1].getVertexIndex()];
        for (int i = 0; i < count; i++) {
            var cur = points[verts[pool + i].getVertexIndex()];
            nx += (prev.getY() - cur.getY()) * (prev.getZ() + cur.getZ());
            ny += (prev.getZ() - cur.getZ()) * (prev.getX() + cur.getX());
            nz += (prev.getX() - cur.getX()) * (prev.getY() + cur.getY());
            prev = cur;
        }
        return nx * normal.getX() + ny * normal.getY() + nz * normal.getZ() < 0f;
    }

    private static Box mapBounds(UTerrainInfoActor terrain, boolean groundless) {
        if (groundless) {
            return new Box(
                    new Vec3(-WORLD_EXTENT, -WORLD_EXTENT, -WORLD_EXTENT),
                    new Vec3(WORLD_EXTENT, WORLD_EXTENT, WORLD_EXTENT));
        }
        var position = terrain.getPosition();
        var scale = terrain.getScale();
        return new Box(
                terrain.getBoundingBox().min().times(scale).plus(position),
                terrain.getBoundingBox().max().times(scale).plus(position));
    }

    private static long csgSurfaceType(CsgBucket bucket) {
        var type = Entity.csgSurfaceType(bucket.depthLayer());
        if (bucket.nativePlaneSide()) {
            type |= SurfaceType.CSG_NATIVE_PLANE_SIDE.getId();
        }
        return bucket.cullBackFaces() ? type : type | SurfaceType.CSG_CULL_DISABLED.getId();
    }

    private static boolean isOpaqueCsgDepthBlend(BlendMode blendMode) {
        return switch (blendMode) {
            case OPAQUE, MASKED, MASKED_BLEND, UNLIT -> true;
            default -> false;
        };
    }

    private static Resolution csgDepthResolution(
            UModel model,
            Vec3[] points,
            boolean checkBounds,
            Box mapBoundingBox,
            boolean waterVolume,
            boolean skyMode) {
        var geos = new ArrayList<Face>();
        var bspSections = model.getBspSections();
        for (var node : model.getNodes()) {
            if (node.getVertexCount() < 3) {
                continue;
            }
            if (checkBounds && checkBspNodeBounds(model, node, mapBoundingBox)) {
                continue;
            }
            var surf = model.getSurfaces()[node.getSurfaceIndex()];
            var rawFlags = surf.getPolygonFlags();
            var nativeWater = (rawFlags & BspPolygonFlags.PF_L2_Water) != 0;
            var fakeBackdrop = !skyMode && (rawFlags & BspPolygonFlags.PF_FakeBackdrop) != 0;
            if (doesNativeSuppressBspColor(rawFlags, skyMode, nativeWater, fakeBackdrop)) {
                continue;
            }
            var material = surf.getMaterial().get();
            var polygonFlags = bspRenderPolygonFlags(node, surf, bspSections);
            if ((polygonFlags & NOT_DRAWN_POLYGON_FLAGS) != 0) {
                continue;
            }
            var sourceNormal = model.getVectors()[surf.getNormalIndex()];
            if (waterVolume && sourceNormal.getZ() < 0.5f) {
                continue;
            }
            var normalLength = (float) Math.sqrt(sourceNormal.getX() * sourceNormal.getX()
                    + sourceNormal.getY() * sourceNormal.getY()
                    + sourceNormal.getZ() * sourceNormal.getZ());
            if (normalLength < 1e-8f) {
                continue;
            }
            var normal = new Vec3(
                    sourceNormal.getX() / normalLength,
                    sourceNormal.getY() / normalLength,
                    sourceNormal.getZ() / normalLength);
            var blend = (polygonFlags & BspPolygonFlags.PF_Unlit) != 0 ? BlendMode.UNLIT : blendModeOf(material);
            geos.add(new Face(
                    node,
                    normal,
                    projectPolygon(model, node, points, normal),
                    material,
                    blend,
                    surf.getPlane().getW() / normalLength,
                    isCsgDepthLayerEligible(polygonFlags, material),
                    needsZoneBoundaryBackface(node, polygonFlags, blend)));
        }
        return CsgDepthResolver.resolve(geos);
    }

    private static boolean isCsgDepthLayerEligible(long polygonFlags, @Nullable UMaterial material) {
        if ((polygonFlags
                        & (NOT_DRAWN_POLYGON_FLAGS
                                | BspPolygonFlags.PF_TwoSided
                                | BspPolygonFlags.PF_Translucent
                                | BspPolygonFlags.PF_Modulated
                                | BspPolygonFlags.PF_L2_Water))
                != 0) {
            return false;
        }
        var blend = (polygonFlags & BspPolygonFlags.PF_Unlit) != 0 ? BlendMode.UNLIT : blendModeOf(material);
        return isOpaqueCsgDepthBlend(blend) && writesDepth(material, blend);
    }

    private static float dot(Vec3 a, Vec3 b) {
        return a.getX() * b.getX() + a.getY() * b.getY() + a.getZ() * b.getZ();
    }

    private static float[][] projectPolygon(UModel model, UBspNode node, Vec3[] points, Vec3 normal) {
        var count = node.getVertexCount();
        var pool = node.getVertexPoolIndex();
        var verts = model.getVertices();
        var ax = Math.abs(normal.getX());
        var ay = Math.abs(normal.getY());
        var az = Math.abs(normal.getZ());
        var poly = new float[count][2];
        for (int i = 0; i < count; i++) {
            var p = points[verts[pool + i].getVertexIndex()];
            if (az >= ax && az >= ay) {
                poly[i][0] = p.getX();
                poly[i][1] = p.getY();
            } else if (ax >= ay) {
                poly[i][0] = p.getY();
                poly[i][1] = p.getZ();
            } else {
                poly[i][0] = p.getX();
                poly[i][1] = p.getZ();
            }
        }
        return poly;
    }

    private static Vec3 nudgedPosition(Vec3 position, Vec3 normal, float nudge) {
        return nudge == 0f
                ? position
                : new Vec3(
                        position.getX() - normal.getX() * nudge,
                        position.getY() - normal.getY() * nudge,
                        position.getZ() - normal.getZ() * nudge);
    }

    private static boolean isMeshCoincident(
            UModel model, UBspNode node, Vec3[] points, Vec3 normal, MeshPlaneIndex meshIndex) {
        var count = node.getVertexCount();
        var pool = node.getVertexPoolIndex();
        var verts = model.getVertices();
        float cx = 0, cy = 0, cz = 0;
        var minX = Float.MAX_VALUE;
        var minY = Float.MAX_VALUE;
        var minZ = Float.MAX_VALUE;
        var maxX = -Float.MAX_VALUE;
        var maxY = -Float.MAX_VALUE;
        var maxZ = -Float.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            var p = points[verts[pool + i].getVertexIndex()];
            cx += p.getX();
            cy += p.getY();
            cz += p.getZ();
            minX = Math.min(minX, p.getX());
            minY = Math.min(minY, p.getY());
            minZ = Math.min(minZ, p.getZ());
            maxX = Math.max(maxX, p.getX());
            maxY = Math.max(maxY, p.getY());
            maxZ = Math.max(maxZ, p.getZ());
        }
        return meshIndex.isCoincident(
                normal.getX(),
                normal.getY(),
                normal.getZ(),
                cx / count,
                cy / count,
                cz / count,
                minX,
                minY,
                minZ,
                maxX,
                maxY,
                maxZ);
    }

    static final class MeshPlaneIndex {
        private final List<IndexedActor> actors = new ArrayList<>();
        private IndexedActor[] byMinX;

        void add(UStaticMesh mesh, Mat4 model, Box worldBounds) {
            if (byMinX != null) {
                throw new IllegalStateException("MeshPlaneIndex is frozen");
            }
            var matrix = new float[16];
            model.to(matrix);
            var min = worldBounds.getMin();
            var max = worldBounds.getMax();
            actors.add(new IndexedActor(
                    mesh,
                    matrix,
                    min.getX() - MESH_PLANE_EPS,
                    min.getY() - MESH_PLANE_EPS,
                    min.getZ() - MESH_PLANE_EPS,
                    max.getX() + MESH_PLANE_EPS,
                    max.getY() + MESH_PLANE_EPS,
                    max.getZ() + MESH_PLANE_EPS));
        }

        void add(float x, float y, float z) {
            if (byMinX != null) {
                throw new IllegalStateException("MeshPlaneIndex is frozen");
            }
            actors.add(IndexedActor.atPoint(x, y, z));
        }

        boolean isEmpty() {
            return actors.isEmpty();
        }

        void freeze() {
            if (byMinX != null) {
                return;
            }
            byMinX = actors.toArray(new IndexedActor[0]);
            Arrays.sort(byMinX, (left, right) -> Float.compare(left.minX, right.minX));
        }

        boolean isCoincident(
                float nx,
                float ny,
                float nz,
                float cx,
                float cy,
                float cz,
                float minX,
                float minY,
                float minZ,
                float maxX,
                float maxY,
                float maxZ) {
            var ex0 = minX - MESH_PLANE_EPS;
            var ex1 = maxX + MESH_PLANE_EPS;
            var ey0 = minY - MESH_PLANE_EPS;
            var ey1 = maxY + MESH_PLANE_EPS;
            var ez0 = minZ - MESH_PLANE_EPS;
            var ez1 = maxZ + MESH_PLANE_EPS;
            freeze();
            var found = 0;
            var end = upperBound(byMinX, ex1);
            for (var index = 0; index < end; index++) {
                var actor = byMinX[index];
                if (actor.maxX < ex0 || actor.maxY < ey0 || actor.minY > ey1 || actor.maxZ < ez0 || actor.minZ > ez1) {
                    continue;
                }
                found = actor.countCoincident(nx, ny, nz, cx, cy, cz, ex0, ex1, ey0, ey1, ez0, ez1, found);
                if (found >= MESH_COINCIDENCE_MIN_VERTS) {
                    return true;
                }
            }
            return false;
        }

        private static int upperBound(IndexedActor[] actors, float value) {
            var low = 0;
            var high = actors.length;
            while (low < high) {
                var mid = (low + high) >>> 1;
                if (actors[mid].minX <= value) {
                    low = mid + 1;
                } else {
                    high = mid;
                }
            }
            return low;
        }

        private static final class IndexedActor {
            private final @Nullable UStaticMesh mesh;
            private final float[] matrix;
            private final float minX;
            private final float minY;
            private final float minZ;
            private final float maxX;
            private final float maxY;
            private final float maxZ;
            private float[] world;

            private IndexedActor(
                    UStaticMesh mesh,
                    float[] matrix,
                    float minX,
                    float minY,
                    float minZ,
                    float maxX,
                    float maxY,
                    float maxZ) {
                this.mesh = mesh;
                this.matrix = matrix;
                this.minX = minX;
                this.minY = minY;
                this.minZ = minZ;
                this.maxX = maxX;
                this.maxY = maxY;
                this.maxZ = maxZ;
            }

            private int countCoincident(
                    float nx,
                    float ny,
                    float nz,
                    float cx,
                    float cy,
                    float cz,
                    float ex0,
                    float ex1,
                    float ey0,
                    float ey1,
                    float ez0,
                    float ez1,
                    int found) {
                if (world == null) {
                    world = transform();
                }
                for (int k = 0; k < world.length; k += 3) {
                    var x = world[k];
                    var y = world[k + 1];
                    var z = world[k + 2];
                    if (x < ex0 || x > ex1 || y < ey0 || y > ey1 || z < ez0 || z > ez1) {
                        continue;
                    }
                    if (Math.abs((x - cx) * nx + (y - cy) * ny + (z - cz) * nz) <= MESH_PLANE_EPS
                            && ++found >= MESH_COINCIDENCE_MIN_VERTS) {
                        return found;
                    }
                }
                return found;
            }

            private float[] transform() {
                var vertices = mesh.getVertexStream().getVertices();
                var out = new float[vertices.length * 3];
                var cursor = 0;
                for (var vertex : vertices) {
                    var l = vertex.getLocation();
                    var lx = l.getX();
                    var ly = l.getY();
                    var lz = l.getZ();
                    out[cursor++] = matrix[0] * lx + matrix[4] * ly + matrix[8] * lz + matrix[12];
                    out[cursor++] = matrix[1] * lx + matrix[5] * ly + matrix[9] * lz + matrix[13];
                    out[cursor++] = matrix[2] * lx + matrix[6] * ly + matrix[10] * lz + matrix[14];
                }
                return out;
            }

            private static IndexedActor atPoint(float x, float y, float z) {
                var actor = new IndexedActor(
                        null,
                        new float[16],
                        x - MESH_PLANE_EPS,
                        y - MESH_PLANE_EPS,
                        z - MESH_PLANE_EPS,
                        x + MESH_PLANE_EPS,
                        y + MESH_PLANE_EPS,
                        z + MESH_PLANE_EPS);
                actor.world = new float[] {x, y, z};
                return actor;
            }
        }
    }

    private record CsgBucket(
            EntityMaterial material,
            BlendMode blend,
            UvAnimation uv,
            IntArrayList tris,
            @Nullable String materialName,
            int alphaRef,
            boolean modulateStaticLighting2x,
            @Nullable WaterPlane reflectionPlane,
            @Nullable DistanceFog distanceFog,
            @Nullable WaterSheetSource waterSheetSource,
            BspDrawSource bspDrawSource,
            boolean fakeBackdrop,
            boolean depthWrite,
            boolean cullBackFaces,
            boolean nativePlaneSide,
            int depthLayer) {}

    private record CsgBucketKey(
            @Nullable Object material,
            BlendMode blend,
            @Nullable WaterPlane reflectionPlane,
            @Nullable DistanceFog distanceFog,
            @Nullable WaterSheetSource waterSheetSource,
            BspDrawSource bspDrawSource,
            boolean fakeBackdrop,
            boolean depthWrite,
            boolean cullBackFaces,
            boolean nativePlaneSide,
            int depthLayer) {}

    private record CsgGroup(
            @Nullable FStaticLightmapTexture atlas,
            FStaticLightmapTexture @Nullable [] atlasVariants,
            Entity.MeshVertexBuffer.Builder vertices,
            Map<Object, CsgBucket> buckets,
            Box boundingBox,
            List<DebugLabel> debugLabels,
            IntArrayList spans) {
        CsgGroup(@Nullable FStaticLightmapTexture atlas, FStaticLightmapTexture @Nullable [] atlasVariants) {
            this(
                    atlas,
                    atlasVariants,
                    MeshVertexBuffer.builder(512),
                    new LinkedHashMap<>(),
                    new Box(),
                    new ArrayList<>(),
                    new IntArrayList());
        }
    }

    private static final class CsgConcatGroup {
        final Entity.MeshVertexBuffer.Builder vertices = MeshVertexBuffer.builder(2_048);
        final Map<Object, CsgBucket> buckets = new LinkedHashMap<>();
        final Box boundingBox = new Box();
        final List<DebugLabel> debugLabels = new ArrayList<>();
        final List<FStaticLightmapTexture> atlases = new ArrayList<>();
        final List<FStaticLightmapTexture[]> atlasVariants = new ArrayList<>();
        final IntArrayList vertexLayers = new IntArrayList();
        final IntArrayList spans = new IntArrayList();
        private final Map<FStaticLightmapTexture, Integer> ordinalByAtlas = new IdentityHashMap<>();

        int getOrdinalOf(FStaticLightmapTexture atlas, FStaticLightmapTexture @Nullable [] variants) {
            var existing = ordinalByAtlas.get(atlas);
            if (existing != null) {
                return existing;
            }
            atlases.add(atlas);
            atlasVariants.add(
                    variants == null || variants.length == 0 ? new FStaticLightmapTexture[] {atlas} : variants);
            ordinalByAtlas.put(atlas, atlases.size() - 1);
            return atlases.size() - 1;
        }

        int getVariantCount() {
            return atlasVariants.stream()
                    .mapToInt(variants -> variants.length)
                    .max()
                    .orElse(1);
        }

        FStaticLightmapTexture getAtlasForSlot(int ordinal, int slot) {
            var variants = atlasVariants.get(ordinal);
            return variants[Math.min(slot, variants.length - 1)];
        }

        void addVertexLayers(int ordinal, int count) {
            for (int i = 0; i < count; i++) {
                vertexLayers.add(ordinal);
            }
        }
    }

    private static long csgConcatGroupKey(@Nullable FStaticLightmapTexture atlas) {
        return atlas == null ? -1L : csgConcatGroupKey(atlas.width(), atlas.height());
    }

    private static boolean isConcatUsableAtlas(FStaticLightmapTexture atlas) {
        return atlas.data().length >= ((atlas.width() + 3) / 4) * ((atlas.height() + 3) / 4) * 8;
    }

    private static boolean hasCollision(StaticMeshActor meshActor, Material material) {
        return doesActorBlockMovement(meshActor) && material.isEnableCollision();
    }

    private static boolean doesActorBlockMovement(StaticMeshActor actor) {
        return actor.isCollideActors() && actor.isBlockActors() && actor.isBlockPlayers();
    }

    private static @Nullable String shortName(@Nullable String fullName) {
        if (fullName == null || fullName.isEmpty()) {
            return null;
        }
        var dot = fullName.lastIndexOf('.');
        return dot >= 0 && dot < fullName.length() - 1 ? fullName.substring(dot + 1) : fullName;
    }

    private static @Nullable UMaterial safeMaterial(UBspSurface surface) {
        try {
            return surface.getMaterial().get();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean isWaterShader(@Nullable UMaterial material) {
        if (!(material instanceof UShader shader)) {
            return false;
        }
        var name = shortName(shader.getFullName());
        return name != null && name.startsWith("WaterShader");
    }

    private static EntityMesh boundingBoxMesh(long type, Box box) {
        var min = box.getMin();
        var max = box.getMax();

        var vertices = List.of(
                new MeshVertex(new Vec3(min.getX(), min.getY(), max.getZ()), VEC_3_1, VEC2_EMPTY),
                new MeshVertex(new Vec3(max.getX(), min.getY(), max.getZ()), VEC_3_1, VEC2_EMPTY),
                new MeshVertex(new Vec3(max.getX(), max.getY(), max.getZ()), VEC_3_1, VEC2_EMPTY),
                new MeshVertex(new Vec3(min.getX(), max.getY(), max.getZ()), VEC_3_1, VEC2_EMPTY),
                new MeshVertex(new Vec3(min.getX(), min.getY(), min.getZ()), VEC_3_1, VEC2_EMPTY),
                new MeshVertex(new Vec3(max.getX(), min.getY(), min.getZ()), VEC_3_1, VEC2_EMPTY),
                new MeshVertex(new Vec3(max.getX(), max.getY(), min.getZ()), VEC_3_1, VEC2_EMPTY),
                new MeshVertex(new Vec3(min.getX(), max.getY(), min.getZ()), VEC_3_1, VEC2_EMPTY));

        var indices = new int[] {
            0, 1, 2, 2, 3, 0,
            1, 5, 6, 6, 2, 1,
            7, 6, 5, 5, 4, 7,
            4, 0, 3, 3, 7, 4,
            4, 5, 1, 1, 0, 4,
            3, 2, 6, 6, 7, 3
        };

        var surface =
                new Entity.Surface(type | SurfaceType.BOUNDING_BOX.getId(), 0, indices.length, BOUNDING_BOX_MATERIAL);
        return new EntityMesh(vertices, indices, List.of(surface), List.of(), box, 0f);
    }

    private static boolean usesVertexColor(@Nullable UMaterial material, IdentityHashMap<UMaterial, Boolean> visited) {
        if (material == null || visited.put(material, Boolean.TRUE) != null) {
            return false;
        }
        return switch (material) {
            case UVertexColor _ -> true;
            case UCombiner combiner ->
                usesVertexColor(combiner.getMaterial1(), visited)
                        || usesVertexColor(combiner.getMaterial2(), visited)
                        || usesVertexColor(combiner.getMask(), visited);
            case UShader shader ->
                usesVertexColor(shader.getDiffuse(), visited)
                        || usesVertexColor(shader.getOpacity(), visited)
                        || usesVertexColor(shader.getSpecular(), visited)
                        || usesVertexColor(shader.getSpecularMask(), visited)
                        || usesVertexColor(shader.getSelfIllumination(), visited)
                        || usesVertexColor(shader.getSelfIlluminationMask(), visited)
                        || usesVertexColor(shader.getDetail(), visited);
            case UModifier modifier -> usesVertexColor(modifier.getMaterial(), visited);
            default -> false;
        };
    }

    private static boolean usesVertexColor(UObjectRef<?, ?> materialRef, IdentityHashMap<UMaterial, Boolean> visited) {
        return usesVertexColor(slotMaterialNode(materialRef), visited);
    }

    private static boolean containsIgnoreCase(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    private static @Nullable UMaterial slotMaterialNode(UObjectRef<?, ?> ref) {
        try {
            return ref.get() instanceof UMaterial material ? material : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static float rotatorRadians(int units) {
        return units * (float) Math.PI / 32768f;
    }

    private static @Nullable EntityMaterial toTextureMaterial(@Nullable Texture texture) {
        return texture == null ? null : new TextureEntityMaterial(texture);
    }

    private static byte toByte(float channel) {
        return (byte) Math.round(Math.max(0f, Math.min(1f, channel)) * 255f);
    }

    private static @Nullable FadeColorSpec fadeSpecOf(UObjectRef<UMaterial, UObjectRef.Optional> ref) {
        try {
            return fadeSpecOf(ref.get());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static FadeColorSpec fadeSpec(UFadeColor fade) {
        return new FadeColorSpec(
                toVec4(fade.getColor1()),
                toVec4(fade.getColor2()),
                fade.getFadePeriod(),
                fade.getFadePhase(),
                fade.getColorFadeType());
    }

    private static Vec4 toVec4(UColor color) {
        return new Vec4(
                (color.r() & 0xFF) / 255f,
                (color.g() & 0xFF) / 255f,
                (color.b() & 0xFF) / 255f,
                (color.a() & 0xFF) / 255f);
    }

    private static boolean doesFinalBlendConsumeSourceAlpha(FrameBufferBlending blending) {
        return switch (blending) {
            case FB_AlphaBlend, FB_AlphaModulate_MightNotFogCorrectly, FB_Brighten, FB_InWaterBlend -> true;
            default -> false;
        };
    }

    private static UvAnimation uvAnimationOfSlot(UObjectRef<?, ?> ref) {
        try {
            return ref.get() instanceof UMaterial material ? uvAnimationOf(material) : UvAnimation.NONE;
        } catch (RuntimeException e) {
            return UvAnimation.NONE;
        }
    }

    private static @Nullable EnvironmentChain environmentChainOf(@Nullable UMaterial slot) {
        var chain = new ArrayList<UModifier>();
        UTexEnvMap environment = null;
        var node = slot;
        for (var depth = 0; depth < 16 && node instanceof UModifier modifier; depth++) {
            if (modifier instanceof UTexEnvMap inner) {
                if (environment == null) {
                    environment = inner;
                }
                node = inner.getEnvMapMaterial();
                continue;
            }
            if (modifier.getTexCoordSource() != 15
                    || modifier.getTexCoordCount() != 0
                    || modifier.isTexCoordProjected()) {
                return null;
            }
            if (!(modifier instanceof UColorModifier)) {
                chain.add(modifier);
            }
            node = modifier.getMaterial().get();
        }
        if (environment == null || environment.isTexCoordProjected() || !(node instanceof UTexture)) {
            return null;
        }
        return new EnvironmentChain(environment, chain);
    }

    private record EnvironmentChain(UTexEnvMap environment, List<UModifier> modifiers) {}

    private static boolean movesByThirdRow(List<UModifier> chain) {
        for (var modifier : chain) {
            var still = modifier instanceof UTexRotator rotator
                            && rotator.getUOffset() == 0f
                            && rotator.getVOffset() == 0f
                    || modifier instanceof UTexScaler scaler && scaler.getUOffset() == 0f && scaler.getVOffset() == 0f;
            if (!still) {
                return true;
            }
        }
        return false;
    }

    private static @Nullable UvAnimation environmentAnimationOf(List<UModifier> chain) {
        if (chain.isEmpty()) {
            return UvAnimation.NONE;
        }
        var unitsToRadians = (float) Math.PI / 32768f;
        if (chain.size() == 2
                && chain.get(0) instanceof UTexRotator rotator
                && chain.get(1) instanceof UTexScaler scaler) {
            var fixed = rotator.getTexRotationType() == 0;
            if (!(fixed || rotator.getTexRotationType() == 1)
                    || rotator.getUOffset() != 0f
                    || rotator.getVOffset() != 0f
                    || scaler.getUOffset() != 0f
                    || scaler.getVOffset() != 0f
                    || scaler.getUScale() == 0f
                    || scaler.getVScale() == 0f) {
                return null;
            }
            var rotation = rotator.getRotation();
            return UvAnimation.turnThenScale(
                    fixed,
                    rotation.pitch() * unitsToRadians,
                    rotation.yaw() * unitsToRadians,
                    rotation.roll() * unitsToRadians,
                    scaler.getUScale(),
                    scaler.getVScale());
        }
        if (chain.size() != 1) {
            return null;
        }
        return switch (chain.getFirst()) {
            case UTexRotator rotator -> {
                if (rotator.getTexRotationType() == 0) {
                    var rotation = rotator.getRotation();
                    yield rotator.getUOffset() != 0f || rotator.getVOffset() != 0f
                            ? null
                            : UvAnimation.turnThenScale(
                                    true,
                                    rotation.pitch() * unitsToRadians,
                                    rotation.yaw() * unitsToRadians,
                                    rotation.roll() * unitsToRadians,
                                    1f,
                                    1f);
                }
                var amplitude = rotator.getOscillationAmplitude();
                if (rotator.getTexRotationType() == 2 && (amplitude.pitch() != 0 || amplitude.roll() != 0)) {
                    yield null;
                }
                var hasPivot = rotator.getUOffset() != 0f || rotator.getVOffset() != 0f;
                if (hasPivot
                        && (AuthoredMaterialCompiler.materialSize(rotator, true) <= 0
                                || AuthoredMaterialCompiler.materialSize(rotator, false) <= 0)) {
                    yield null;
                }
                yield rotatorAnimation(rotator, false);
            }
            case UTexScaler scaler -> {
                var width = AuthoredMaterialCompiler.materialSize(scaler, true);
                var height = AuthoredMaterialCompiler.materialSize(scaler, false);
                var offsets = scaler.getUOffset() != 0f || scaler.getVOffset() != 0f;
                yield scaler.getUScale() == 0f || scaler.getVScale() == 0f || offsets && (width <= 0 || height <= 0)
                        ? null
                        : UvAnimation.scale(
                                scaler.getUScale(),
                                scaler.getVScale(),
                                offsets ? scaler.getUOffset() / width : 0f,
                                offsets ? scaler.getVOffset() / height : 0f);
            }
            case UTexPannerTriggered _ -> null;
            case UTexPanner panner -> uvAnimationOf(panner);
            case UTexOscillator oscillator ->
                oscillator.getUOscillationType() == 0 && oscillator.getVOscillationType() == 0
                        ? uvAnimationOf(oscillator)
                        : null;
            default -> null;
        };
    }

    private static UvAnimation rotatorAnimation(UTexRotator rotator) {
        return rotatorAnimation(rotator, turnsEnvironmentMap(rotator, 0));
    }

    private static UvAnimation rotatorAnimation(UTexRotator rotator, boolean keepMiddle) {
        var unitsToRadians = (float) Math.PI / 32768f;
        var pivotU = 0f;
        var pivotV = 0f;
        if (rotator.getUOffset() != 0f || rotator.getVOffset() != 0f) {
            var width = AuthoredMaterialCompiler.materialSize(rotator, true);
            var height = AuthoredMaterialCompiler.materialSize(rotator, false);
            pivotU = width > 0 && height > 0 ? rotator.getUOffset() / width : 0.5f;
            pivotV = width > 0 && height > 0 ? rotator.getVOffset() / height : 0.5f;
        }
        UvAnimation animation;
        if (rotator.getTexRotationType() == 2) {
            animation = UvAnimation.oscillateRotate(
                    rotator.getOscillationRate().yaw() / 65535f,
                    rotator.getOscillationAmplitude().yaw() * unitsToRadians,
                    pivotU,
                    pivotV);
        } else if (rotator.getTexRotationType() == 1) {
            var rotation = rotator.getRotation();
            animation = UvAnimation.rotate(
                    pivotU,
                    pivotV,
                    rotation.pitch() * unitsToRadians,
                    rotation.yaw() * unitsToRadians,
                    rotation.roll() * unitsToRadians);
        } else {
            animation =
                    UvAnimation.rotate(pivotU, pivotV, rotator.getRotationRate().yaw() * unitsToRadians);
        }
        return keepMiddle ? aboutMiddle(animation) : animation;
    }

    private static UvAnimation aboutMiddle(UvAnimation animation) {
        return switch (animation.mode()) {
            case 2 -> UvAnimation.rotate(0.5f, 0.5f, animation.paramZ() + animation.paramW() + animation.extra());
            case 5 -> UvAnimation.oscillateRotate(animation.paramX(), animation.paramZ(), 0.5f, 0.5f);
            default -> animation;
        };
    }

    private static boolean turnsEnvironmentMap(@Nullable UMaterial node, int depth) {
        if (node == null || depth > 8) {
            return false;
        }
        try {
            return switch (node) {
                case UTexEnvMap _ -> true;
                case UCubemap _ -> true;
                case UModifier modifier ->
                    turnsEnvironmentMap(modifier.getMaterial().get(), depth + 1);
                case UCombiner combiner ->
                    turnsEnvironmentMap(combiner.getMaterial1().get(), depth + 1)
                            || turnsEnvironmentMap(combiner.getMaterial2().get(), depth + 1);
                default -> false;
            };
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static UvAnimation matchedSlotAnimation(UObjectRef<?, ?> ref, UTexture diffuseTexture) {
        try {
            if (ref.get() instanceof UMaterial slot) {
                var anim = uvAnimationOf(slot);
                if (anim.mode() != 0 && isSameTexture(baseTextureOf(slot), diffuseTexture)) {
                    return anim;
                }
            }
            return UvAnimation.NONE;
        } catch (RuntimeException e) {
            return UvAnimation.NONE;
        }
    }

    private static boolean holdsCubemap(@Nullable UMaterial node, int depth) {
        if (node == null || depth > 64) {
            return false;
        }
        return switch (node) {
            case UCubemap ignored -> true;
            case UTexEnvMap environment -> holdsCubemap(environment.getEnvMapMaterial(), depth + 1);
            case UCombiner combiner ->
                holdsCubemap(combiner.getMaterial1().get(), depth + 1)
                        || holdsCubemap(combiner.getMaterial2().get(), depth + 1)
                        || holdsCubemap(combiner.getMask().get(), depth + 1);
            case UShader inner -> holdsCubemap(inner.getDiffuse().get(), depth + 1);
            case UModifier modifier -> holdsCubemap(modifier.getMaterial().get(), depth + 1);
            default -> false;
        };
    }

    private static boolean holdsReflectionInCombiner(@Nullable UMaterial node, boolean inCombiner, int depth) {
        if (node == null || depth > 64) {
            return false;
        }
        try {
            return switch (node) {
                case UTexEnvMap environment ->
                    inCombiner && baseTextureOf(environment.getMaterial().get()) != null;
                case UCombiner combiner ->
                    holdsReflectionInCombiner(combiner.getMaterial1().get(), true, depth + 1)
                            || holdsReflectionInCombiner(combiner.getMaterial2().get(), true, depth + 1)
                            || holdsReflectionInCombiner(combiner.getMask().get(), true, depth + 1);
                case UModifier modifier ->
                    holdsReflectionInCombiner(modifier.getMaterial().get(), inCombiner, depth + 1);
                default -> false;
            };
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static @Nullable UTexture baseTextureOf(@Nullable UMaterial node) {
        try {
            return switch (node) {
                case UTexture texture -> texture;
                case UShader shader -> baseTextureOf(shader.getDiffuse().get());
                case UModifier modifier -> baseTextureOf(modifier.getMaterial().get());
                case null, default -> null;
            };
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean isSameTexture(@Nullable UTexture a, @Nullable UTexture b) {
        return a != null && b != null && (a == b || a.getFullName().equals(b.getFullName()));
    }

    private static Texture withAlphaTexture(Texture texture) {
        return texture.alphaTexture()
                ? texture
                : new Texture(
                        texture.format(),
                        texture.width(),
                        texture.height(),
                        texture.data(),
                        true,
                        texture.clampU(),
                        texture.clampV(),
                        texture.filters(),
                        texture.firstMip());
    }

    private static boolean isCompressed(TextureFormat format) {
        return format == TextureFormat.TEXTURE_DXT1
                || format == TextureFormat.TEXTURE_DXT3
                || format == TextureFormat.TEXTURE_DXT5;
    }

    private static boolean checkBspNodeBounds(UModel model, UBspNode node, Box mapBoundingBox) {
        for (int i = 0; i < node.getVertexCount(); i++) {
            var index = node.getVertexPoolIndex() + i;
            var position = model.getPoints()[model.getVertices()[index].getVertexIndex()];
            if (!mapBoundingBox.contains(position)) {
                return true;
            }
        }
        return false;
    }

    private static void placeActor(Entity entity, UActor actor) {
        entity.setSourceActor(actor.getSourcePath());
        var rotation = actor.getRotation().vector();
        var scale = actor.getScale();
        entity.setPosition(Entity.actorModelOrigin(actor.getLocation(), actor.getPrePivot(), rotation, scale));
        entity.setRotation(rotation);
        entity.setScale(scale);
        entity.setAcceptsProjectors(actor.isAcceptsProjectors());
        if (actor instanceof StaticMeshActor) {
            var mask = actor.getZoneRenderMask();
            if (mask != 0) {
                entity.setZoneRender(new ZoneRender(
                        mask, zoneStart(actor), false, ZoneBlend.of(actor.getRegionZone()), zoneMap(actor)));
            }
        }
    }

    private static String actorMeshName(
            EntityMesh mesh,
            EntityMesh cachedMesh,
            String cacheKey,
            String meshName,
            UActor actor,
            @Nullable ActorLook look) {
        if (mesh == cachedMesh) {
            return cacheKey;
        }
        var name = meshName + "#actor/" + actor.getFullName();
        return look == null || look.own()
                ? name
                : name + "#zone/" + Integer.toHexString(look.render().mask());
    }

    private static ZoneRender zoneLookActor(UActor actor) {
        var mask = actor.getZoneRenderMask();
        return new ZoneRender(mask, zoneStart(actor), mask != 0, ZoneBlend.of(actor.getRegionZone()), zoneMap(actor))
                .inEssence();
    }

    private static boolean isSkySurfaceInvisible(Entity.Surface surface) {
        return surface.material() instanceof ConstantOpacityEntityMaterial constant
                && constant.alpha() <= 0f
                && (surface.blendMode() == BlendMode.MASKED || SkyRules.usesAlpha(surface.blendMode()));
    }

    private static int zoneLookStates(List<ActorLook> looks) {
        var states = 0;
        for (var look : looks) {
            if (!look.own()) {
                states |= look.render().mask();
            }
        }
        return states;
    }

    private static @Nullable ZoneInfoActor loadModelLevelInfo(UPackage uPackage, @Nullable UModel model) {
        if (model == null) {
            return null;
        }
        var levels = new ArrayList<ULevel>();
        uPackage.loadObjects("Level", levels);
        for (var level : levels) {
            if (level.getModel().isNull() || level.getModel().get() != model) {
                continue;
            }
            return level.getLevelInfo();
        }
        return null;
    }

    private static Vec3 terrainPropFloor(UTerrainInfoActor terrain, List<UTerrainSector> sectors, Vec3 worldPos) {
        if (!terrain.hasSurface() || sectors.isEmpty()) {
            return VEC3_EMPTY;
        }
        var position = terrain.getPosition();
        var scale = terrain.getScale();
        var gx = Math.round((worldPos.getX() - position.getX()) / scale.getX());
        var gy = Math.round((worldPos.getY() - position.getY()) / scale.getY());
        if (gx < 0 || gx > 255 || gy < 0 || gy > 255) {
            return VEC3_EMPTY;
        }
        try {
            var floor = PROP_FLOOR_MIN + getLightMapValue(gx, gy, sectors) * PROP_FLOOR_SUN;
            return new Vec3(floor, floor, floor);
        } catch (RuntimeException e) {
            return VEC3_EMPTY;
        }
    }

    private static float luminance(Vec3 c) {
        return 0.299f * c.getX() + 0.587f * c.getY() + 0.114f * c.getZ();
    }

    private static @Nullable UShader shaderOf(@Nullable UMaterial node) {
        for (var depth = 0; depth < 8 && node != null; depth++) {
            if (node instanceof UShader shader) {
                return shader;
            }
            if (!(node instanceof UModifier modifier)) {
                return null;
            }
            try {
                node = modifier.getMaterial().get();
            } catch (RuntimeException unresolved) {
                return null;
            }
        }
        return null;
    }

    private static UTexture getOpacity(UShader uShader) {
        return firstTextureOf(slotMaterialNode(uShader.getOpacity()));
    }

    private static float averageByteAlpha(byte[] rgba) {
        var texels = rgba.length / 4;
        if (texels == 0) {
            return 1f;
        }
        long sum = 0;
        for (int i = 0; i < texels; i++) {
            sum += rgba[i * 4 + 3] & 0xFF;
        }
        return sum / (255f * texels);
    }

    private static @Nullable Vec3 solidRgb(Texture texture) {
        if (texture.data().isEmpty() || texture.width() * texture.height() > SOLID_PLATE_MAX_TEXELS) {
            return null;
        }
        var rgba = DxtMipCodec.decodeToRgba(
                texture.format(),
                texture.width(),
                texture.height(),
                texture.data().getFirst());
        var min = new int[] {255, 255, 255};
        var max = new int[3];
        for (var at = 0; at + 3 < rgba.length; at += 4) {
            for (var channel = 0; channel < 3; channel++) {
                var value = rgba[at + channel] & 0xff;
                min[channel] = Math.min(min[channel], value);
                max[channel] = Math.max(max[channel], value);
            }
        }
        for (var channel = 0; channel < 3; channel++) {
            if (max[channel] - min[channel] > SOLID_PLATE_SPREAD) {
                return null;
            }
        }
        return new Vec3((min[0] + max[0]) / 510f, (min[1] + max[1]) / 510f, (min[2] + max[2]) / 510f);
    }

    private static float waterLayerRotation(@Nullable UMaterial node) {
        var rotator = firstTexRotator(node);
        return rotator == null ? 0f : rotator.getRotation().yaw() * (float) Math.PI / 32768f;
    }

    private static @Nullable UTexOscillator firstTexOscillator(@Nullable UMaterial node) {
        try {
            return switch (node) {
                case UTexOscillator osc -> osc;
                case UCombiner combiner -> {
                    var found = firstTexOscillator(combiner.getMaterial1().get());
                    if (found == null) {
                        found = firstTexOscillator(combiner.getMaterial2().get());
                    }
                    if (found == null) {
                        found = firstTexOscillator(combiner.getMask().get());
                    }
                    yield found;
                }
                case UShader shader -> firstTexOscillator(shader.getDiffuse().get());
                case UModifier modifier ->
                    firstTexOscillator(modifier.getMaterial().get());
                case null, default -> null;
            };
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static @Nullable UTexRotator firstTexRotator(@Nullable UMaterial node) {
        try {
            return switch (node) {
                case UTexRotator rotator -> rotator;
                case UCombiner combiner -> {
                    var found = firstTexRotator(combiner.getMaterial1().get());
                    if (found == null) {
                        found = firstTexRotator(combiner.getMaterial2().get());
                    }
                    if (found == null) {
                        found = firstTexRotator(combiner.getMask().get());
                    }
                    yield found;
                }
                case UShader shader -> firstTexRotator(shader.getDiffuse().get());
                case UModifier modifier ->
                    firstTexRotator(modifier.getMaterial().get());
                case null, default -> null;
            };
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean hasNoDiffuse(UShader shader) {
        try {
            return shader.getDiffuse().get() == null;
        } catch (RuntimeException e) {
            return true;
        }
    }

    private static float[] getPackedTerrainTextureCoords(int x, int y, int z, TerrainLayerProjector[] projectors) {
        var result = new float[Math.multiplyExact(projectors.length, 2)];
        for (var index = 0; index < projectors.length; index++) {
            projectors[index].projectInto(x, y, z, result, index * 2);
        }
        return result;
    }

    private static final class TerrainSteadyLighting {
        private final float levelBrightness;
        private final MaterialClient client;
        private final int width;
        private final int height;
        private final byte[] heightmap;
        private final boolean inverted;
        private final UBitSet edgeTurns;
        private final SteadyLight[][] sectorLights;
        private final float positionX;
        private final float positionY;
        private final float positionZ;
        private final float scaleX;
        private final float scaleY;
        private final float scaleZ;

        private TerrainSteadyLighting(UTerrainInfoActor terrain, float levelBrightness, MaterialClient client) {
            this.levelBrightness = levelBrightness;
            this.client = client;
            var map = terrain.getTerrainMap().get();
            width = map.getUSize();
            height = map.getVSize();
            heightmap = map.getMips()[0].getData();
            inverted = terrain.isInverted();
            edgeTurns = terrain.getEdgeTurnBitmap();
            var sectors = terrain.getSectors();
            sectorLights = new SteadyLight[sectors.size()][];
            for (var index = 0; index < sectors.size(); index++) {
                var infos = sectors.get(index).getStaticLights();
                sectorLights[index] = new SteadyLight[infos.length];
                for (var light = 0; light < infos.length; light++) {
                    sectorLights[index][light] = new SteadyLight(infos[light]);
                }
            }
            var position = terrain.getPosition();
            var scale = terrain.getScale();
            positionX = position.getX();
            positionY = position.getY();
            positionZ = position.getZ();
            scaleX = scale.getX();
            scaleY = scale.getY();
            scaleZ = scale.getZ();
        }

        private int rgbAt(int terrainX, int terrainY) {
            if (sectorLights.length != 256 || width <= 0 || height <= 0) {
                return 0;
            }
            var x = Math.min(Math.max(terrainX, 0), width - 1);
            var y = Math.min(Math.max(terrainY, 0), height - 1);
            var sectorX = Math.min(x / TERRAIN_SECTOR_SIZE, 15);
            var sectorY = Math.min(y / TERRAIN_SECTOR_SIZE, 15);
            var staticLights = sectorLights[sectorY * 16 + sectorX];
            if (staticLights.length == 0) {
                return 0;
            }
            var localX = x - sectorX * TERRAIN_SECTOR_SIZE;
            var localY = y - sectorY * TERRAIN_SECTOR_SIZE;
            var localVertex = localY * 17 + localX;
            var hasVisibleLight = false;
            for (var info : staticLights) {
                if (info.isVertexVisible(localVertex)) {
                    hasVisibleLight = true;
                    break;
                }
            }
            if (!hasVisibleLight) {
                return 0;
            }

            var normal = vertexNormal(x, y);
            var worldX = positionX + x * scaleX;
            var worldY = positionY + y * scaleY;
            var worldZ = positionZ + getHeight(heightmap, y * width + x) * scaleZ;
            var red = 0;
            var green = 0;
            var blue = 0;
            for (var info : staticLights) {
                if (!info.isVertexVisible(localVertex)) {
                    continue;
                }
                var light = info.resolve(levelBrightness, client);
                if (light == null) {
                    continue;
                }
                var dx = light.position().getX() - worldX;
                var dy = light.position().getY() - worldY;
                var dz = light.position().getZ() - worldZ;
                var intensity =
                        lightAttenuation(light, dx, dy, dz, light.radius(), normal[0], normal[1], normal[2], true)
                                * STATIC_MESH_SCENE_LIGHT_SCALE;
                red = Math.min(255, red + lightingChannel(light.color().getX() * intensity));
                green = Math.min(255, green + lightingChannel(light.color().getY() * intensity));
                blue = Math.min(255, blue + lightingChannel(light.color().getZ() * intensity));
            }
            return red << 16 | green << 8 | blue;
        }

        private void resolveAll() {
            for (var lights : sectorLights) {
                for (var light : lights) {
                    light.resolve(levelBrightness, client);
                }
            }
        }

        private float[] vertexNormal(int x, int y) {
            var sum = new float[3];
            addQuadNormals(sum, x, y);
            if (x > 0) {
                addQuadNormals(sum, x - 1, y);
            }
            if (y > 0) {
                addQuadNormals(sum, x, y - 1);
            }
            if (x > 0 && y > 0) {
                addQuadNormals(sum, x - 1, y - 1);
            }
            var length = (float) Math.sqrt(sum[0] * sum[0] + sum[1] * sum[1] + sum[2] * sum[2]);
            if (length > 1e-8f && Float.isFinite(length)) {
                sum[0] /= length;
                sum[1] /= length;
                sum[2] /= length;
            } else {
                sum[0] = 0f;
                sum[1] = 0f;
                sum[2] = 1f;
            }
            if (inverted) {
                sum[0] = -sum[0];
                sum[1] = -sum[1];
                sum[2] = -sum[2];
            }
            return sum;
        }

        private void addQuadNormals(float[] sum, int x, int y) {
            if (x < 0 || y < 0 || x >= width - 1 || y >= height - 1) {
                return;
            }
            var x0 = positionX + x * scaleX;
            var x1 = x0 + scaleX;
            var y0 = positionY + y * scaleY;
            var y1 = y0 + scaleY;
            var z00 = positionZ + getHeight(heightmap, y * width + x) * scaleZ;
            var z10 = positionZ + getHeight(heightmap, y * width + x + 1) * scaleZ;
            var z01 = positionZ + getHeight(heightmap, (y + 1) * width + x) * scaleZ;
            var z11 = positionZ + getHeight(heightmap, (y + 1) * width + x + 1) * scaleZ;
            var turned = edgeTurns.get(x + y * width);
            if (!turned) {
                addTriangleNormal(sum, x0, y0, z00, x1, y0, z10, x1, y1, z11);
                addTriangleNormal(sum, x0, y0, z00, x1, y1, z11, x0, y1, z01);
            } else {
                addTriangleNormal(sum, x0, y1, z01, x0, y0, z00, x1, y0, z10);
                addTriangleNormal(sum, x0, y1, z01, x1, y0, z10, x1, y1, z11);
            }
        }

        private static void addTriangleNormal(
                float[] sum, float ax, float ay, float az, float bx, float by, float bz, float cx, float cy, float cz) {
            var abx = bx - ax;
            var aby = by - ay;
            var abz = bz - az;
            var acx = cx - ax;
            var acy = cy - ay;
            var acz = cz - az;
            var nx = aby * acz - abz * acy;
            var ny = abz * acx - abx * acz;
            var nz = abx * acy - aby * acx;
            var length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (length <= 1e-8f || !Float.isFinite(length)) {
                return;
            }
            sum[0] += nx / length;
            sum[1] += ny / length;
            sum[2] += nz / length;
        }
    }

    private static final class SteadyLight {
        private final byte[] vertexVisibility;
        private @Nullable TerrainLightInfo info;
        private @Nullable Light light;

        private SteadyLight(TerrainLightInfo info) {
            this.info = info;
            vertexVisibility = info.getVertexVisibility();
        }

        private boolean isVertexVisible(int vertex) {
            if (vertex < 0) {
                return false;
            }
            var byteIndex = vertex >>> 3;
            return byteIndex < vertexVisibility.length
                    && (Byte.toUnsignedInt(vertexVisibility[byteIndex]) & (1 << (vertex & 7))) != 0;
        }

        private @Nullable Light resolve(float levelBrightness, MaterialClient client) {
            var source = info;
            if (source == null) {
                return light;
            }
            LightActor actor;
            try {
                actor = source.getLight();
            } catch (RuntimeException e) {
                actor = null;
            }
            light = actor == null ? null : lightFromActor(actor, levelBrightness, client);
            info = null;
            return light;
        }
    }

    private static void addTerrainVertex(
            Entity.TerrainVertexBuffer.Builder target,
            float worldX,
            float worldY,
            float worldZ,
            int terrainX,
            int terrainY,
            int heightValue,
            int alphaWidth,
            int alphaHeight,
            float[] normal,
            float lightValue,
            int steadyLightRgb,
            TerrainLayerProjector[] projectors) {
        var index = target.add(
                worldX,
                worldY,
                worldZ,
                getTerrainAlphaCoord(terrainX, alphaWidth),
                getTerrainAlphaCoord(terrainY, alphaHeight),
                lightValue);
        target.setNormal(index, normal[0], normal[1], normal[2]);
        target.setSteadyLightRgb(index, steadyLightRgb);
        for (var layer = 0; layer < projectors.length; layer++) {
            var projector = projectors[layer];
            target.setTerrainTextureCoordinate(index, layer * 2, projector.u(terrainX, terrainY, heightValue));
            target.setTerrainTextureCoordinate(index, layer * 2 + 1, projector.v(terrainX, terrainY, heightValue));
        }
    }

    private static TerrainLayerProjector[] terrainTextureProjectors(UTerrainInfoActor terrain) {
        var layers = terrain.getLayers();
        var toWorld = terrain.getNativeToWorld();
        var toHeightMap = terrain.getNativeToHeightMap();
        var result = new TerrainLayerProjector[layers.size()];
        for (int i = 0; i < layers.size(); i++) {
            var p = TerrainLayerMapping.of(layers.get(i)).projector(toWorld, toHeightMap);
            result[i] = new TerrainLayerProjector(p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7]);
        }
        return result;
    }

    private static void setTerrainLayerRows(
            Entity.TerrainVertexBuffer.Builder target, TerrainLayerProjector[] projectors, Vec3 position, Vec3 scale) {
        float scaleX = scale.getX();
        float scaleY = scale.getY();
        float scaleZ = scale.getZ();
        if (scaleX == 0f || scaleY == 0f || scaleZ == 0f) {
            return;
        }
        var rows = new float[projectors.length * 8];
        for (var layer = 0; layer < projectors.length; layer++) {
            var projector = projectors[layer];
            var at = layer * 8;
            rows[at] = projector.uX() / scaleX;
            rows[at + 1] = projector.uY() / scaleY;
            rows[at + 2] = projector.uZ() / scaleZ;
            rows[at + 3] = projector.uOffset();
            rows[at + 4] = projector.vX() / scaleX;
            rows[at + 5] = projector.vY() / scaleY;
            rows[at + 6] = projector.vZ() / scaleZ;
            rows[at + 7] = projector.vOffset();
            for (var term = at; term < at + 8; term++) {
                if (!Float.isFinite(rows[term])) {
                    Arrays.fill(rows, at, at + 8, 0f);
                    break;
                }
            }
        }
        target.setLayerRows(new float[] {position.getX(), position.getY(), position.getZ()}, rows);
    }

    private record TerrainLayerProjector(
            float uX, float uY, float uZ, float uOffset, float vX, float vY, float vZ, float vOffset) {
        float u(int x, int y, int z) {
            return uX * x + uY * y + uZ * z + uOffset;
        }

        float v(int x, int y, int z) {
            return vX * x + vY * y + vZ * z + vOffset;
        }

        void projectInto(int x, int y, int z, float[] target, int offset) {
            target[offset] = u(x, y, z);
            target[offset + 1] = v(x, y, z);
        }
    }

    private static int getHeight(byte[] heightmap, int index) {
        return Short.toUnsignedInt((short) ((heightmap[index * 2] & 0xFF) | ((heightmap[index * 2 + 1] & 0xFF) << 8)));
    }

    private static float stitchLight(
            @Nullable UTerrainInfoActor neighbor,
            int nx,
            int ny,
            List<UTerrainSector> selfSectors,
            int selfX,
            int selfY,
            int variant) {
        if (neighbor != null
                && neighbor.getSectors().size() == 256
                && getSector(nx, ny, neighbor.getSectors()).getLightMap0().length > 0) {
            return getLightMapValue(nx, ny, neighbor.getSectors(), variant);
        }
        return getLightMapValue(selfX, selfY, selfSectors, variant);
    }

    private static float getLightMapValue(int x, int y, List<UTerrainSector> sectors) {
        return getLightMapValue(x, y, sectors, 0);
    }

    private static float getLightMapValue(int x, int y, List<UTerrainSector> sectors, int variant) {
        var sector = getSector(x, y, sectors);
        var lightMap = sector.getLightMap(variant);
        if (lightMap.length == 0) {
            return 1f;
        }
        var dx = x - sector.getSectorX() * 16;
        var dy = y - sector.getSectorY() * 16;
        var index = dy * 17 + dx;
        return (lightMap[index] & 0xFF) / 255f;
    }

    private static float bakeShadowVariants(FloatArrayList[] variants, int x, int y, List<UTerrainSector> sectors) {
        var light0 = getLightMapValue(x, y, sectors, 0);
        variants[0].add(light0);
        for (int v = 1; v < SHADOW_VARIANT_COUNT; v++) {
            variants[v].add(getLightMapValue(x, y, sectors, v));
        }
        return light0;
    }

    private static float bakeStitchVariants(
            FloatArrayList[] variants,
            @Nullable UTerrainInfoActor neighbor,
            int nx,
            int ny,
            List<UTerrainSector> selfSectors,
            int selfX,
            int selfY) {
        var light0 = stitchLight(neighbor, nx, ny, selfSectors, selfX, selfY, 0);
        variants[0].add(light0);
        for (int v = 1; v < SHADOW_VARIANT_COUNT; v++) {
            variants[v].add(stitchLight(neighbor, nx, ny, selfSectors, selfX, selfY, v));
        }
        return light0;
    }

    private static UTerrainSector getSector(int x, int y, List<UTerrainSector> sectors) {
        var dx = x == 256 ? 15 : x / 16;
        var dy = y == 256 ? 15 : y / 16;
        var sector = sectors.get(dy * 16 + dx);
        if (sector.getSectorX() != dx || sector.getSectorY() != dy) {
            throw new RuntimeException(
                    "Sector mismatch: " + dx + " " + dy + " " + sector.getSectorX() + " " + sector.getSectorY());
        }
        return sector;
    }

    private static int countSurfaceIndices(UStaticMesh.Surface[] surfaces) {
        int indicesCount = 0;
        for (int i = 0, size = surfaces.length; i < size; i++) {
            indicesCount += surfaces[i].getTriangleMax() * 3;
        }
        return indicesCount;
    }

    private static float forcedLifeTime(EmitterActor actor) {
        var emitters = new ArrayList<UParticleEmitter>();
        for (var child : actor.getChildEmitters()) {
            if (child instanceof UParticleEmitter emitter) {
                emitters.add(emitter);
            }
        }
        return EmitterLife.longestForced(emitters);
    }

    private static String figureOf(String faceReference, Outfit outfit) {
        var figure = outfit.figure();
        return figure == null || figure.isBlank() ? PlayerFigures.figureOf(faceReference) : figure;
    }

    private static @Nullable UMeshAnimation findRaceAnimation(UPackage uPackage, String race) {
        return uPackage.findObject("MeshAnimation", race + "_anim") instanceof UMeshAnimation found ? found : null;
    }

    private static boolean isHairWorn(Outfit outfit) {
        for (var item : outfit.worn()) {
            if (item.hairAccessory()) {
                for (var texture : item.textures()) {
                    if (texture.toLowerCase(Locale.ROOT).contains("_bh_t")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
