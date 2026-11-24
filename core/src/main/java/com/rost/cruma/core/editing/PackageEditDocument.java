package com.rost.cruma.core.editing;

import static com.rost.cruma.core.editing.PackageBytes.littleEndian;
import static com.rost.cruma.core.editing.PackageBytes.malformed;
import static com.rost.cruma.core.editing.PackageBytes.writeIndex;
import static com.rost.cruma.core.editing.PackageBytes.writeInt;

import com.rost.cruma.core.editing.ActorLightingChanges.Actor;
import com.rost.cruma.core.editing.ActorLightingChanges.BeastTexture;
import com.rost.cruma.core.editing.ActorLightingChanges.Instance;
import com.rost.cruma.core.editing.ActorLightingChanges.Light;
import com.rost.cruma.core.editing.ActorTransform.Rotator;
import com.rost.cruma.core.editing.ActorTransform.Vector3;
import com.rost.cruma.core.editing.BeastTextureCodec.Saved;
import com.rost.cruma.core.editing.BeastTextureCodec.Template;
import com.rost.cruma.core.editing.BspLightmapTail.LeafLights;
import com.rost.cruma.core.editing.IsolatedHeightPackage.Prepared;
import com.rost.cruma.core.editing.PackageBytes.Cursor;
import com.rost.cruma.core.editing.PackageBytes.ExportEntry;
import com.rost.cruma.core.editing.PackageBytes.ImportEntry;
import com.rost.cruma.core.editing.PackageRecoveryCodec.Bundle;
import com.rost.cruma.core.editing.PackageRecoveryCodec.Definition;
import com.rost.cruma.core.editing.PackageRecoveryCodec.Frame;
import com.rost.cruma.core.editing.PackageRecoveryCodec.LightAim;
import com.rost.cruma.core.editing.PackageRecoveryCodec.LightValue;
import com.rost.cruma.core.editing.PackageRecoveryCodec.Scalar;
import com.rost.cruma.core.editing.PackageRecoveryCodec.SurfaceFlagValue;
import com.rost.cruma.core.editing.PackageRecoveryCodec.SurfaceMaterialValue;
import com.rost.cruma.core.editing.PackageRecoveryCodec.SurfaceValue;
import com.rost.cruma.core.editing.PackageRecoveryCodec.Tables;
import com.rost.cruma.core.editing.PackageRecoveryCodec.Value;
import com.rost.cruma.core.editing.StaticMeshInstanceCodec.Decoded;
import com.rost.cruma.core.unreal.UArchiveLoader;
import com.rost.cruma.core.unreal.UStaticMeshInstance;
import com.rost.cruma.core.unreal.primitives.URotator;
import com.rost.cruma.core.world.L2Attach;
import com.rost.cruma.core.world.L2Movement;
import com.rost.cruma.core.world.L2Swing;
import com.rost.cruma.core.world.UnrealLoader;
import glm_.vec3.Vec3;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.regex.Pattern;
import org.eclipse.collections.impl.map.mutable.primitive.IntIntHashMap;
import org.jetbrains.annotations.Nullable;

public final class PackageEditDocument {
    public static final String PAWN_LOOK = "The mesh and the skins of a pawn cannot be changed.";

    public static final String PAWN_MAP = "A pawn cannot move to another map.";

    public static final String PAWN = "LineagePawn";

    public static final String MOVING_KEEPS = "A mesh that can move keeps its mesh and its map.";

    public static final String EFFECT_SCALE = "The game does not scale an effect by its actor.";

    public static final String EFFECT_KEEPS = "An effect keeps its emitters and its map.";

    public static final String DECORATIONS_SCALE = "A mesh with decorations on it cannot take a scale of zero.";

    private static final String PAWN_CLASS = "LineageWarrior.LineagePawn";
    private static final String EMITTER_CLASS = "Engine.Emitter";
    private static final int MOST_PAWN_PARENTS = 8;
    private static final List<String> DECORATION_DATA = List.of("DecoLayerData", "DecoLayerDataForQuad");
    private static final Set<String> MATERIAL_CLASSES = Set.of(
            "Material",
            "Texture",
            "WetTexture",
            "TexModifier",
            "TexCoordSource",
            "TexScaler",
            "TexEnvMap",
            "ColorModifier",
            "OpacityModifier",
            "TexPanner",
            "TexPannerTriggered",
            "TexRotator",
            "TexOscillator",
            "TexOscillatorTriggered",
            "Combiner",
            "FinalBlend",
            "Shader",
            "Cubemap",
            "ConstantColor",
            "FadeColor",
            "VertexColor");
    private static final int MAX_SKIN_SLOTS = 256;
    private static final int MODEL_BASES = 2;
    private static final String DELETED_BRUSH_SURFACE =
            "This side belongs to a deleted brush. Bring the brush back first";
    private static final Map<String, String> MOVING_CLASSES =
            Map.of("MovableStaticMeshActor", "bL2InitMove", "L2MovableStaticMeshActor", "bInitialized");
    private static final Set<String> EFFECT_COPY_DROPS = Set.of("touching");
    private static final Set<String> SWING_COPY_DROPS = Set.of(
            "l2movementtag", "bl2initmove", "l2orgrotator", "l2cycledeltatime", "l2currentcycletime", "l2currentmax");
    private static final Set<String> MOVEMENT_COPY_DROPS = Set.of("binitialized", "l2movementtag");
    private static final Set<String> DECORATION_COPY_DROPS = Set.of("decolayerdata", "decolayerdataforquad");
    private static final int BEAST_TAIL_VERSION = 132;
    private static final List<String> BEAST_PROPERTIES = List.of("BeastMapScaleSunLight", "BeastMapScaleLocalLight");
    private static final Set<String> COPY_SCALARS = Set.of(
            "beastmapscalesunlight",
            "beastmapscalelocallight",
            "culldistance",
            "scaleglow",
            "ambientglow",
            "maxlights",
            "style",
            "drawtype",
            "translucentrenderpriority",
            "bunlit",
            "bshadowcast",
            "bspeciallit",
            "bunlitcheck",
            "bculledsunlight",
            "bacceptsprojectors",
            "bsunaffect",
            "bstatic",
            "bhidden",
            "busel2actorviewtype",
            "bignoredrange",
            "rangeforignoredrange",
            "l2actorviewduration",
            "l2actorviewtype",
            "bdynamiclight",
            "busedynamiclights",
            "bcollideactors",
            "bblockactors",
            "bblockplayers",
            "bblocknonzeroextenttraces",
            "bblockzeroextenttraces",
            "busecylindercollision",
            "bworldgeometry",
            "collisionradius",
            "collisionheight",
            "bstaticlighting",
            "ambientintensity",
            "lightintensity");
    private final Path source;
    private final @Nullable Path clientRoot;
    private UArchiveLoader.@Nullable EmitterClassTest emitterClasses;
    private final String originalSha256;
    private final PackageBytes original;
    private final List<String> names;
    private final List<ImportEntry> imports;
    private final Map<String, ActorState> actorStates = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final Map<String, LightState> lightStates = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private @Nullable CarryGraph carryGraph;
    private long carryGraphBuilds;
    private int brightnessType;
    private int radiusType;
    private BspLeafLightEdit leafLightEdit;
    private String leafLightFailure;
    private final Map<Set<Integer>, CachedLists> leafLightsCache = new HashMap<>();
    private BspSurfaceEdit surfaceEdit;
    private String surfaceFailure;
    private final Map<Integer, BspSurfaceMapping> surfaceMappings = new TreeMap<>();
    private final Set<Integer> hiddenSurfaces = new TreeSet<>();
    private final Map<Integer, BspSurfaceEdit.Material> surfaceMaterials = new TreeMap<>();
    private final Map<Integer, Integer> surfaceFlags = new TreeMap<>();
    private final Set<Integer> deletedBrushes = new TreeSet<>();
    private BspBrushDelete brushDelete;
    private String brushFailure;
    private final Map<Integer, BrushFacts> brushFacts = new HashMap<>();
    private @Nullable Map<Integer, BspBrushShapes.Shape> brushShapes;
    private int @Nullable [] surfaceItems;
    private final Map<Set<Integer>, ModelBase> modelBases = new LinkedHashMap<>();
    private volatile Set<Integer> deletedSurfaces = Set.of();
    private final Map<String, MaterialReference> materialReferences = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final Set<String> dirtyActors = new HashSet<>();
    private int savedImportCount;
    private boolean importsChanged;
    private NativeLevelEditor level;
    private String structuralRestriction;
    private Snapshot savedSnapshot;
    private @Nullable ActorRegions actorRegions;

    private PackageEditDocument(Path source, byte[] file) throws IOException {
        this(source, file, clientRootOf(source));
    }

    private PackageEditDocument(Path source, byte[] file, @Nullable Path clientRoot) throws IOException {
        this.source = source;
        this.clientRoot = clientRoot;
        originalSha256 = sha256(file);
        original = new PackageBytes(source, file);
        names = new ArrayList<>(original.names);
        imports = new ArrayList<>(original.imports);
        try {
            level = NativeLevelEditor.read(original);
        } catch (IOException failure) {
            structuralRestriction = failure.getMessage();
        }
        for (var entry : original.exports) {
            var className = original.getClassName(entry);
            if (className.equals("StaticMeshActor") || MOVING_CLASSES.containsKey(className)) {
                var path = original.getObjectPath(entry.index());
                var state = readActor(path, className, entry);
                state.moving = MOVING_CLASSES.containsKey(className);
                state.nonStatic = state.moving || state.editable && savesFalse(state, "bStatic");
                if (!original.getObjectPath(entry.classIndex()).equalsIgnoreCase("Engine." + className)) {
                    state.restriction = "Only Engine." + className + " has a verified editable layout";
                    state.editable = false;
                }
                if (actorStates.put(path, state) != null) {
                    throw malformed("Duplicate actor path " + path);
                }
            }
            if (isEmitterClassName(entry)) {
                var path = original.getObjectPath(entry.index());
                var state = readActor(path, className, entry);
                if (actorStates.put(path, state) != null) {
                    throw malformed("Duplicate actor path " + path);
                }
            }
            if (className.equals(PAWN) && entry.classIndex() < 0 && isPawnClass(entry.classIndex())
                    || entry.classIndex() > 0 && isPawnClass(entry.classIndex())) {
                var path = original.getObjectPath(entry.index());
                var state = readActor(path, className, entry);
                state.pawn = true;
                if (actorStates.put(path, state) != null) {
                    throw malformed("Duplicate actor path " + path);
                }
            }
            var lightClass = EditableLight.CLASSES.get(className);
            if (lightClass != null && original.getObjectPath(entry.classIndex()).equalsIgnoreCase(lightClass)) {
                var path = original.getObjectPath(entry.index());
                try {
                    var light = EditableLight.read(original, entry, path, className);
                    lightStates.put(path, new LightState(light));
                    if (brightnessType == 0) {
                        brightnessType = light.brightnessType;
                    }
                    if (radiusType == 0 && light.radiusType > 0) {
                        radiusType = light.radiusType;
                    }
                } catch (IOException | RuntimeException unreadable) {
                }
            }
            if (MATERIAL_CLASSES.contains(className)) {
                var path = original.getObjectPath(entry.index());
                materialReferences.putIfAbsent(path, new MaterialReference(path, className, entry.index()));
            }
        }
        for (var i = 0; i < imports.size(); i++) {
            var entry = imports.get(i);
            var className = original.getName(entry.className());
            if (MATERIAL_CLASSES.contains(className)) {
                var path = original.getObjectPath(-i - 1);
                materialReferences.putIfAbsent(path, new MaterialReference(path, className, -i - 1));
            }
        }
        savedImportCount = imports.size();
        if (emitterClasses != null) {
            emitterClasses.close();
        }
        if (level != null) {
            var active = new HashSet<>(level.actors);
            for (var state : actorStates.values()) {
                state.deleted = !active.contains(state.entry.index());
                state.baselineDeleted = state.deleted;
            }
            lightStates.values().removeIf(state -> !active.contains(state.light.entry.index()));
        }
        savedSnapshot = snapshot();
    }

    public static PackageEditDocument open(Path source) throws IOException {
        var normalized = source.toAbsolutePath().normalize();
        if (!normalized.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".unr")) {
            throw new IOException("The scene editor opens .unr map packages");
        }
        return new PackageEditDocument(normalized, Files.readAllBytes(normalized));
    }

    public static void validateRecoveryEnvelope(byte[] payload) throws IOException {
        PackageRecoveryCodec.validateEnvelope(payload);
    }

    public static RestoredRecovery restoreRecovery(Path source, byte[] payload) throws IOException {
        try {
            var bundle = PackageRecoveryCodec.decode(payload);
            var document = open(source);
            if (!document.originalSha256.equals(bundle.sourceHash())) {
                throw malformed("Recovery source fingerprint differs");
            }
            if (!document.original.packageName.equals(bundle.packageName())) {
                throw malformed("Recovery source package identity differs");
            }
            return document.restoreRecoveryBundle(bundle);
        } catch (IllegalArgumentException | IndexOutOfBoundsException failure) {
            throw new IOException("Invalid recovery metadata", failure);
        }
    }

    public static List<MaterialReference> inspectMaterials(Path source) throws IOException {
        var asset = new PackageBytes(source, Files.readAllBytes(source));
        var result = new ArrayList<MaterialReference>();
        for (var entry : asset.exports) {
            var type = asset.getClassName(entry);
            if (MATERIAL_CLASSES.contains(type)
                    && asset.getObjectPath(entry.classIndex()).equalsIgnoreCase("Engine." + type)) {
                result.add(new MaterialReference(asset.getObjectPath(entry.index()), type, entry.index()));
            }
        }
        return List.copyOf(result);
    }

    public static List<String> inspectMeshes(Path source) throws IOException {
        var asset = new PackageBytes(source, Files.readAllBytes(source));
        var result = new ArrayList<String>();
        for (var entry : asset.exports) {
            if (asset.getClassName(entry).equals("StaticMesh")
                    && asset.getObjectPath(entry.classIndex()).equalsIgnoreCase("Engine.StaticMesh")) {
                result.add(asset.getObjectPath(entry.index()));
            }
        }
        return List.copyOf(result);
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private record Tag(String name, int type, String struct, int arrayIndex, int start, int payload, int end) {}

    private record ScalarProperty(String name, int type, boolean truth, byte[] payload) {}

    private record MeshReference(int reference, String path) {}

    private record CreatedChild(ExportEntry entry, int source) {}

    private record LightCheckpoint(
            LightState state, LightColor color, LightPlacement placement, LightShape shape, boolean deleted) {}

    private static final class LightState {
        final EditableLight light;
        final boolean created;
        final String template;
        @Nullable
        String moveEventOverride;

        LightColor current;
        LightPlacement placement;
        LightShape shape;
        boolean deleted;

        LightState(EditableLight light) {
            this(light, false, "");
        }

        LightState(EditableLight light, boolean created, String template) {
            this.light = light;
            this.created = created;
            this.template = template;
            current = light.color;
            placement = light.getPlacement();
            shape = light.shape;
        }

        boolean isMoved() {
            return !created && !deleted && !light.isCarryOnly() && !placement.equals(light.getPlacement());
        }

        boolean isCarried() {
            return !created
                    && !deleted
                    && light.isCarryOnly()
                    && (!placement.equals(light.getPlacement()) || !shape.equals(light.shape));
        }

        boolean isTurned() {
            return !created && !deleted && !light.isCarryOnly() && !shape.equals(light.shape);
        }

        boolean isPlaced() {
            return isMoved() || created != deleted && !(created && light.isCarryOnly());
        }

        boolean isRelit() {
            return isPlaced() || isTurned();
        }

        LightCheckpoint getCheckpoint() {
            return new LightCheckpoint(this, current, placement, shape, deleted);
        }

        LightRecord snapshot() {
            return new LightRecord(
                    light.path,
                    light.entry.index(),
                    light.className,
                    placement.location(),
                    placement.radius(),
                    current,
                    light.color,
                    light.location,
                    light.radius,
                    light.isEditable(),
                    light.restriction,
                    created,
                    template,
                    shape,
                    light.shape);
        }
    }

    private static final class ActorState {
        final String path;
        final String className;
        final ExportEntry entry;
        final List<Tag> tags = new ArrayList<>();
        final List<Integer> originalSkins = new ArrayList<>();
        @Nullable
        List<String> movementTagsOverride;

        @Nullable
        String moveEventOverride;

        int terminator;
        byte[] tail = new byte[0];
        boolean tailMatchesTags = true;
        String restriction = "";
        boolean editable = true;
        boolean relocatable = true;
        boolean created;
        boolean deleted;
        boolean baselineDeleted;
        int meshReference;
        String meshPath = "";
        String template = "";
        byte[] newTail = new byte[0];
        int lightingInstance;
        List<ScalarProperty> scalarProperties = List.of();
        ActorTransform transform = ActorTransform.IDENTITY;
        ActorTransform current = ActorTransform.IDENTITY;
        ActorTransform savedTransform = ActorTransform.IDENTITY;
        List<Integer> savedSkins = List.of();
        List<Integer> skins = new ArrayList<>();
        boolean pawn;
        boolean moving;
        boolean emitter;
        List<Integer> emitterReferences = List.of();
        List<CreatedChild> children = List.of();
        boolean nonStatic;
        Set<String> droppedTags = Set.of();
        @Nullable
        ActorState copyOf;
        int frameEnd;
        @Nullable
        LightRegion region;

        int[] regionValues = new int[0];

        ActorState(String path, String className, ExportEntry entry) {
            this.path = path;
            this.className = className;
            this.entry = entry;
        }

        ActorRecord snapshot() {
            return new ActorRecord(
                    path,
                    entry.index(),
                    className,
                    current,
                    skins,
                    editable,
                    restriction,
                    meshPath,
                    template,
                    created,
                    pawn,
                    emitter);
        }
    }

    public Path getSource() {
        return source;
    }

    public String getOriginalSha256() {
        return originalSha256;
    }

    public int getFileVersion() {
        return original.fileVersion;
    }

    public int getLicenseVersion() {
        return original.licenseVersion;
    }

    public List<ActorRecord> getActors() {
        return actorStates.values().stream()
                .filter(state -> !state.deleted)
                .map(ActorState::snapshot)
                .toList();
    }

    public Optional<ActorRecord> findActor(String sourcePath) {
        var actor = actorStates.get(sourcePath);
        return actor == null || actor.deleted ? Optional.empty() : Optional.of(actor.snapshot());
    }

    public boolean keepsMap(String sourcePath) {
        var actor = actorStates.get(sourcePath);
        return actor != null && (actor.pawn || actor.nonStatic || actor.emitter);
    }

    public Map<String, L2Swing.Kind> getCarried(String carrierPath) {
        var carried = carryGraph().carried().get(carrierPath);
        return carried == null ? new LinkedHashMap<>() : new LinkedHashMap<>(carried);
    }

    public @Nullable String getCarrierOf(String actorPath) {
        return carryGraph().carriers().get(actorPath);
    }

    public long getCarryGraphBuilds() {
        return carryGraphBuilds;
    }

    public List<String> getMovementTags(String actorPath) {
        var state = actorStates.get(actorPath);
        try {
            return state == null ? List.of() : List.copyOf(carryFacts(state).movementTags());
        } catch (IOException unreadable) {
            return List.of();
        }
    }

    public String getMoveEvent(String actorPath) {
        try {
            var state = actorStates.get(actorPath);
            if (state != null) {
                return carryFacts(state).moveEvent();
            }
            var light = lightStates.get(actorPath);
            if (light != null) {
                return light.moveEventOverride != null ? light.moveEventOverride : light.light.getMoveEvent(original);
            }
        } catch (IOException unreadable) {
            return "None";
        }
        return "None";
    }

    public String createCarryName(Collection<String> taken) {
        var map = new StringBuilder();
        for (var c : original.packageName.toCharArray()) {
            if (c < 128 && Character.isLetterOrDigit(c)) {
                map.append(c);
            }
        }
        var used = new HashSet<String>();
        names.forEach(name -> used.add(name.toLowerCase(Locale.ROOT)));
        taken.forEach(name -> used.add(name.toLowerCase(Locale.ROOT)));
        for (var number = 1; ; number++) {
            var name = "CrumaCarry" + map + "n" + number;
            if (!used.contains(name.toLowerCase(Locale.ROOT))) {
                return name;
            }
        }
    }

    public void setCopyMovementTags(String copyPath, List<String> tags) {
        carryGraph = null;
        var state = actorStates.get(copyPath);
        if (state == null || !state.created || state.copyOf == null || !MOVING_CLASSES.containsKey(state.className)) {
            throw new IllegalArgumentException("Not a copy of a carrier: " + copyPath);
        }
        tags.forEach(this::intern);
        state.movementTagsOverride = List.copyOf(tags);
        refreshDirty(state);
    }

    public void setCopyMoveEvent(String copyPath, String event) {
        carryGraph = null;
        intern(event);
        var state = actorStates.get(copyPath);
        if (state != null && state.created && state.copyOf != null) {
            state.moveEventOverride = event;
            refreshDirty(state);
            return;
        }
        var light = lightStates.get(copyPath);
        if (light != null && light.created) {
            light.moveEventOverride = event;
            refreshDirty(light);
            return;
        }
        throw new IllegalArgumentException("Not a copy: " + copyPath);
    }

    public String getCarriedLightCopyRestriction(String sourcePath) {
        var state = lightStates.get(sourcePath);
        if (state == null || state.deleted) {
            return "Unknown light " + sourcePath;
        }
        if (level == null) {
            return structuralRestriction == null ? "The Level of this map cannot be read" : structuralRestriction;
        }
        if (!state.light.isCarryOnly()) {
            return EditableLight.CARRIED_LIT;
        }
        return "";
    }

    public LightRecord duplicateCarriedLight(String sourcePath, Vector3 location, Rotator rotation) throws IOException {
        carryGraph = null;
        var reason = getCarriedLightCopyRestriction(sourcePath);
        if (!reason.isEmpty()) {
            throw new IllegalArgumentException(reason);
        }
        var source = lightStates.get(sourcePath);
        var template = source.created ? lightStates.get(source.template) : source;
        var placement =
                new LightPlacement(location, template.light.getPlacement().radius());
        reason = template.light.getCarryRestriction(placement, newRegion(template, placement), rotation);
        if (!reason.isEmpty()) {
            throw new IllegalArgumentException(reason);
        }
        var state = newLight(template, nextCreatedIndex(), null);
        state.placement = placement;
        state.shape = template.shape.withRotation(rotation);
        state.moveEventOverride = source.moveEventOverride;
        lightStates.put(state.light.path, state);
        refreshDirty(state);
        return state.snapshot();
    }

    public boolean isEmitter(String sourcePath) {
        var actor = actorStates.get(sourcePath);
        return actor != null && actor.emitter;
    }

    public int getEmitterCount(String sourcePath) {
        var actor = actorStates.get(sourcePath);
        if (actor == null) {
            return 0;
        }
        return (actor.copyOf != null ? actor.copyOf : actor).emitterReferences.size();
    }

    public boolean isNonStatic(String sourcePath) {
        var actor = actorStates.get(sourcePath);
        return actor != null && actor.nonStatic;
    }

    public List<MaterialReference> getMaterials() {
        return List.copyOf(materialReferences.values());
    }

    public List<LightRecord> getLights() {
        return lightStates.values().stream()
                .filter(state -> !state.deleted)
                .map(LightState::snapshot)
                .toList();
    }

    public List<LightRecord> getChangedSavedLights() {
        return lightStates.values().stream()
                .filter(state -> !state.created
                        && !state.deleted
                        && (state.isMoved() || state.isCarried() || !state.current.equals(state.light.color)))
                .map(LightState::snapshot)
                .toList();
    }

    public Optional<LightRecord> getLight(String sourcePath) {
        var state = lightStates.get(sourcePath);
        return state == null || state.deleted ? Optional.empty() : Optional.of(state.snapshot());
    }

    public List<LightRecord> getDeletedLights() {
        return lightStates.values().stream()
                .filter(state -> state.deleted && !state.created)
                .map(LightState::snapshot)
                .toList();
    }

    public Optional<LightRecord> findDeletedLight(String sourcePath) {
        var state = lightStates.get(sourcePath);
        return state == null || !state.deleted || state.created ? Optional.empty() : Optional.of(state.snapshot());
    }

    public String getLightColorRestriction(String sourcePath, LightColor color) {
        var state = lightStates.get(sourcePath);
        if (state == null || state.deleted) {
            return "Unknown light " + sourcePath;
        }
        return state.light.getRestriction(color, state.placement, brightnessType, radiusType);
    }

    public String getLightPlacementRestriction(String sourcePath, LightPlacement placement) {
        var state = lightStates.get(sourcePath);
        if (state == null || state.deleted) {
            return "Unknown light " + sourcePath;
        }
        return placementRestriction(state, state.current, placement);
    }

    public String getLightCopyRestriction(String sourcePath) {
        var state = lightStates.get(sourcePath);
        if (state == null || state.deleted) {
            return "Unknown light " + sourcePath;
        }
        if (level == null) {
            return structuralRestriction == null ? "The Level of this map cannot be read" : structuralRestriction;
        }
        if (!state.light.relocatable) {
            return "This light keeps data after its properties, so it cannot be copied";
        }
        return getLightMoveRestriction(sourcePath);
    }

    public LightRecord duplicateLight(String sourcePath, LightPlacement placement) throws IOException {
        carryGraph = null;
        Objects.requireNonNull(placement);
        var reason = getLightCopyRestriction(sourcePath);
        if (!reason.isEmpty()) {
            throw new IllegalArgumentException(reason);
        }
        var source = lightStates.get(sourcePath);
        var template = source.created ? lightStates.get(source.template) : source;
        reason = placementRestriction(template, source.current, placement);
        if (!reason.isEmpty()) {
            throw new IllegalArgumentException(reason);
        }
        var state = newLight(template, nextCreatedIndex(), null);
        state.current = source.current;
        state.placement = placement;
        state.shape = source.shape;
        lightStates.put(state.light.path, state);
        refreshDirty(state);
        return state.snapshot();
    }

    public LightTemplate getLightTemplate(boolean spot) {
        LightState best = null;
        var bestRank = Integer.MAX_VALUE;
        for (var state : lightStates.values()) {
            if (state.created || state.deleted) {
                continue;
            }
            var rank = state.light.getTemplateRank(spot);
            if (rank >= 0
                    && (rank < bestRank || rank == bestRank && state.light.entry.index() < best.light.entry.index())) {
                best = state;
                bestRank = rank;
            }
        }
        if (best == null) {
            return new LightTemplate(
                    "",
                    spot
                            ? "This map has no spotlight that a new one can copy"
                            : "This map has no light that a new one can copy");
        }
        return new LightTemplate(best.light.path, getLightCopyRestriction(best.light.path));
    }

    public record LightTemplate(String path, String restriction) {}

    public String getLightDeleteRestriction(String sourcePath) {
        var state = lightStates.get(sourcePath);
        if (state == null || state.deleted) {
            return "Unknown light " + sourcePath;
        }
        if (level == null) {
            return structuralRestriction == null ? "The Level of this map cannot be read" : structuralRestriction;
        }
        if (!state.light.restriction.isEmpty()) {
            return state.light.restriction;
        }
        try {
            leafLightEdit();
            return "";
        } catch (IOException unavailable) {
            return unavailable.getMessage().replace("cannot move", "cannot be deleted");
        }
    }

    public void deleteLight(String sourcePath) {
        carryGraph = null;
        var reason = getLightDeleteRestriction(sourcePath);
        if (!reason.isEmpty()) {
            throw new IllegalArgumentException(reason);
        }
        var state = lightStates.get(sourcePath);
        state.deleted = true;
        refreshDirty(state);
    }

    public void undeleteLight(String sourcePath) {
        carryGraph = null;
        var state = lightStates.get(sourcePath);
        if (state == null || !state.deleted || state.created) {
            throw new IllegalArgumentException("Only a deleted light of the source map can come back: " + sourcePath);
        }
        state.deleted = false;
        refreshDirty(state);
    }

    public String getLightMoveRestriction(String sourcePath) {
        var state = lightStates.get(sourcePath);
        if (state == null || state.deleted) {
            return "Unknown light " + sourcePath;
        }
        if (!state.light.restriction.isEmpty()) {
            return state.light.restriction;
        }
        if (!state.light.placed) {
            return "This light has no saved place, so it cannot move";
        }
        try {
            leafLightEdit();
            return "";
        } catch (IOException unavailable) {
            return unavailable.getMessage();
        }
    }

    public float getSolidBelow(Vector3 point, float depth) {
        try {
            return leafLightEdit().getSolidBelow(point, depth);
        } catch (IOException unavailable) {
            return Float.NaN;
        }
    }

    public String getLightCarryRestriction(String sourcePath, Vector3 location, Rotator rotation) {
        Objects.requireNonNull(location);
        Objects.requireNonNull(rotation);
        var state = lightStates.get(sourcePath);
        if (state == null || state.deleted) {
            return "Unknown light " + sourcePath;
        }
        var placement = new LightPlacement(location, state.light.radius);
        try {
            return state.light.getCarryRestriction(placement, newRegion(state, placement), rotation);
        } catch (IOException unavailable) {
            return unavailable.getMessage();
        }
    }

    public void carryLight(String sourcePath, Vector3 location, Rotator rotation) {
        var restriction = getLightCarryRestriction(sourcePath, location, rotation);
        if (!restriction.isEmpty()) {
            throw new IllegalArgumentException(restriction);
        }
        var state = lightStates.get(sourcePath);
        state.placement = new LightPlacement(location, state.light.radius);
        state.shape = new LightShape(state.shape.type(), state.shape.effect(), state.shape.cone(), rotation);
        refreshDirty(state);
    }

    public void updateLightPlacement(String sourcePath, LightPlacement placement) {
        Objects.requireNonNull(placement);
        var restriction = getLightPlacementRestriction(sourcePath, placement);
        if (!restriction.isEmpty()) {
            throw new IllegalArgumentException(restriction);
        }
        var state = lightStates.get(sourcePath);
        state.placement = placement;
        refreshDirty(state);
    }

    public String getLightShapeRestriction(String sourcePath, LightShape shape) {
        Objects.requireNonNull(shape);
        var state = lightStates.get(sourcePath);
        if (state == null || state.deleted) {
            return "Unknown light " + sourcePath;
        }
        return state.light.getRestriction(state.current, state.placement, null, shape, brightnessType, radiusType);
    }

    public void updateLightShape(String sourcePath, LightShape shape) {
        var restriction = getLightShapeRestriction(sourcePath, shape);
        if (!restriction.isEmpty()) {
            throw new IllegalArgumentException(restriction);
        }
        var state = lightStates.get(sourcePath);
        state.shape = shape;
        refreshDirty(state);
    }

    public record LightPlacementEdit(
            String path,
            int exportIndex,
            @Nullable LightPlacement original,
            @Nullable LightPlacement current,
            int zone,
            int template,
            LightColor color,
            LightShape shape,
            boolean turned) {
        public List<LightPlacement> placements() {
            var result = new ArrayList<LightPlacement>(2);
            if (original != null) {
                result.add(original);
            }
            if (current != null) {
                result.add(current);
            }
            return result;
        }
    }

    public List<LightPlacementEdit> getLightPlacementEdits() {
        var result = new ArrayList<LightPlacementEdit>();
        for (var state : lightStates.values()) {
            if (!state.isRelit()) {
                continue;
            }
            var template =
                    state.created ? lightStates.get(state.template).light.entry.index() : state.light.entry.index();
            result.add(new LightPlacementEdit(
                    state.light.path,
                    state.light.entry.index(),
                    state.created ? null : state.light.getPlacement(),
                    state.deleted ? null : state.placement,
                    zoneAt(state, state.placement),
                    template,
                    state.current,
                    state.deleted ? state.light.shape : state.shape,
                    !state.deleted && !state.shape.equals(state.light.shape)));
        }
        return List.copyOf(result);
    }

    public void updateLightColor(String sourcePath, LightColor color) {
        Objects.requireNonNull(color);
        var restriction = getLightColorRestriction(sourcePath, color);
        if (!restriction.isEmpty()) {
            throw new IllegalArgumentException(restriction);
        }
        var state = lightStates.get(sourcePath);
        state.current = color;
        refreshDirty(state);
    }

    public record LightColorEdit(String path, int exportIndex, LightColor original, LightColor current) {}

    public List<LightColorEdit> getLightColorEdits() {
        return lightStates.values().stream()
                .filter(state -> !state.created && !state.deleted && !state.current.equals(state.light.color))
                .map(state -> new LightColorEdit(
                        state.light.path, state.light.entry.index(), state.light.color, state.current))
                .toList();
    }

    public String getSurfaceEditRestriction() {
        try {
            surfaceEdit();
            return "";
        } catch (IOException unavailable) {
            return unavailable.getMessage();
        }
    }

    public int pickSurface(Vector3 point, float tolerance) {
        try {
            var deleted = deletedSurfaces;
            return surfaceEdit()
                    .pick(point, tolerance, surface -> hiddenSurfaces.contains(surface) || deleted.contains(surface));
        } catch (IOException unavailable) {
            return -1;
        }
    }

    public String getSurfaceRestriction(int surface) {
        if (isSurfaceInDeletedBrush(surface)) {
            return DELETED_BRUSH_SURFACE;
        }
        try {
            return surfaceEdit().getRestriction(surface);
        } catch (IOException unavailable) {
            return unavailable.getMessage();
        }
    }

    public String getSurfaceMappingRestriction(int surface, BspSurfaceMapping mapping) {
        var reason = getSurfaceRestriction(surface);
        return reason.isEmpty() ? mapping.restriction() : reason;
    }

    public BspSurfaceMapping getSurfaceMapping(int surface) throws IOException {
        var edited = surfaceMappings.get(surface);
        return edited != null ? edited : getSourceSurfaceMapping(surface);
    }

    public BspSurfaceMapping getSourceSurfaceMapping(int surface) throws IOException {
        var edit = surfaceEdit();
        if (surface < 0 || surface >= edit.getSurfaceCount()) {
            throw new IllegalArgumentException("Unknown BSP surface " + surface);
        }
        return edit.getMapping(surface);
    }

    public Vector3 getSurfaceNormal(int surface) throws IOException {
        return surfaceEdit().getNormal(surface);
    }

    public List<float[]> getSurfacePolygons(int surface) throws IOException {
        return surfaceEdit().getPolygons(surface);
    }

    public int getSurfaceMaterial(int surface) throws IOException {
        return surfaceEdit().getMaterial(surface);
    }

    public float @Nullable [] getSurfaceTile(int surface) throws IOException {
        var material = surfaceMaterials.get(surface);
        if (material != null) {
            return new float[] {material.uSize(), material.vSize()};
        }
        return surfaceEdit().getMappingSizes(surface);
    }

    public void updateSurfaceMapping(int surface, BspSurfaceMapping mapping) throws IOException {
        Objects.requireNonNull(mapping);
        var restriction = getSurfaceMappingRestriction(surface, mapping);
        if (!restriction.isEmpty()) {
            throw new IllegalArgumentException(restriction);
        }
        if (mapping.equals(getSourceSurfaceMapping(surface))) {
            surfaceMappings.remove(surface);
        } else {
            surfaceMappings.put(surface, mapping);
        }
        refreshSurfaceDirty(surface);
    }

    public record SurfaceMappingEdit(int surface, BspSurfaceMapping original, BspSurfaceMapping current) {}

    public List<SurfaceMappingEdit> getSurfaceMappingEdits() {
        var result = new ArrayList<SurfaceMappingEdit>();
        surfaceMappings.forEach((surface, mapping) ->
                result.add(new SurfaceMappingEdit(surface, surfaceEdit.getMapping(surface), mapping)));
        return result;
    }

    public String getSurfaceHideRestriction(int surface) {
        if (isSurfaceInDeletedBrush(surface)) {
            return DELETED_BRUSH_SURFACE;
        }
        try {
            return surfaceEdit().getHideRestriction(surface);
        } catch (IOException unavailable) {
            return unavailable.getMessage();
        }
    }

    public boolean isSurfaceHidden(int surface) {
        return hiddenSurfaces.contains(surface);
    }

    public void updateSurfaceHidden(int surface, boolean hidden) {
        if (hidden) {
            var restriction = getSurfaceHideRestriction(surface);
            if (!restriction.isEmpty()) {
                throw new IllegalArgumentException(restriction);
            }
            hiddenSurfaces.add(surface);
        } else {
            if (isSurfaceInDeletedBrush(surface)) {
                throw new IllegalArgumentException(DELETED_BRUSH_SURFACE);
            }
            hiddenSurfaces.remove(surface);
        }
        refreshSurfaceDirty(surface);
    }

    public Set<Integer> getHiddenSurfaces() {
        return Collections.unmodifiableSet(new TreeSet<>(hiddenSurfaces));
    }

    public String getSurfaceMaterialRestriction(int surface) {
        return getSurfaceRestriction(surface);
    }

    public void updateSurfaceMaterial(int surface, @Nullable String materialPath, int mappingUSize, int mappingVSize)
            throws IOException {
        var restriction = getSurfaceMaterialRestriction(surface);
        if (!restriction.isEmpty()) {
            throw new IllegalArgumentException(restriction);
        }
        var source = surfaceEdit().getMaterial(surface);
        if (materialPath == null) {
            surfaceMaterials.remove(surface);
            refreshSurfaceDirty(surface);
            return;
        }
        var reference = materialReferences.get(materialPath);
        if (reference == null) {
            throw new IllegalArgumentException("Material is not referenced by the map: " + materialPath);
        }
        if (reference.objectIndex() == source) {
            surfaceMaterials.remove(surface);
            refreshSurfaceDirty(surface);
            return;
        }
        var material = new BspSurfaceEdit.Material(reference.objectIndex(), mappingUSize, mappingVSize);
        restriction = BspSurfaceEdit.getMaterialRestriction(material);
        if (!restriction.isEmpty()) {
            throw new IllegalArgumentException(restriction);
        }
        surfaceMaterials.put(surface, material);
        refreshSurfaceDirty(surface);
    }

    public String getSurfaceMaterialPath(int surface) throws IOException {
        var material = surfaceMaterials.get(surface);
        return materialPath(
                material != null ? material.reference() : surfaceEdit().getMaterial(surface));
    }

    public String getSourceSurfaceMaterialPath(int surface) throws IOException {
        return materialPath(surfaceEdit().getMaterial(surface));
    }

    public boolean isSurfaceSheet(int surface) {
        try {
            return surfaceEdit().isSheet(surface);
        } catch (IOException unavailable) {
            return false;
        }
    }

    public String getSurfaceFlagRestriction(int surface, BspSurfaceFlag flag, boolean on) {
        if (isSurfaceInDeletedBrush(surface)) {
            return DELETED_BRUSH_SURFACE;
        }
        try {
            return surfaceEdit().getFlagRestriction(surface, flag.getBit(), on);
        } catch (IOException unavailable) {
            return unavailable.getMessage();
        }
    }

    public boolean isSurfaceFlagOn(int surface, BspSurfaceFlag flag) throws IOException {
        return (currentSurfaceFlags(surface) & flag.getBit()) != 0;
    }

    public boolean isSourceSurfaceFlagOn(int surface, BspSurfaceFlag flag) throws IOException {
        return (sourceSurfaceFlags(surface) & flag.getBit()) != 0;
    }

    public void updateSurfaceFlag(int surface, BspSurfaceFlag flag, boolean on) throws IOException {
        var restriction = getSurfaceFlagRestriction(surface, flag, on);
        if (!restriction.isEmpty()) {
            throw new IllegalArgumentException(restriction);
        }
        var bits = currentSurfaceFlags(surface);
        bits = on ? bits | flag.getBit() : bits & ~flag.getBit();
        if (bits == sourceSurfaceFlags(surface)) {
            surfaceFlags.remove(surface);
        } else {
            surfaceFlags.put(surface, bits);
        }
        refreshSurfaceDirty(surface);
    }

    public String getSurfaceDeleteRestriction(int surface) {
        if (isSurfaceInDeletedBrush(surface)) {
            return DELETED_BRUSH_SURFACE;
        }
        try {
            return surfaceEdit().getDeleteRestriction(surface);
        } catch (IOException unavailable) {
            return unavailable.getMessage();
        }
    }

    public int getSurfaceBrush(int surface) {
        try {
            var edit = surfaceEdit();
            if (surface < 0 || surface >= edit.getSurfaceCount()) {
                return 0;
            }
            var actor = edit.getActor(surface);
            return isBrushActor(actor) ? actor : 0;
        } catch (IOException unavailable) {
            return 0;
        }
    }

    public String getBrushName(int brush) {
        try {
            var path = original.getObjectPath(brush);
            return path.substring(path.lastIndexOf('.') + 1);
        } catch (IOException | RuntimeException unavailable) {
            return "Brush " + brush;
        }
    }

    public List<Integer> getBrushSurfaces(int brush) {
        try {
            var sides = surfaceEdit().getSelect().getSides(brush);
            var result = new ArrayList<Integer>(sides.length);
            for (var side : sides) {
                result.add(side);
            }
            return Collections.unmodifiableList(result);
        } catch (IOException unavailable) {
            return List.of();
        }
    }

    public @Nullable BrushFacts describeBrush(int brush) {
        if (!isBrushActor(brush)) {
            return null;
        }
        var known = brushFacts.get(brush);
        if (known == null) {
            var tags = new int[2];
            try {
                readBrushTags(original.exports.get(brush - 1), tags);
            } catch (IOException | RuntimeException unreadable) {
            }
            known = new BrushFacts(
                    getBrushName(brush),
                    tags[0],
                    tags[1],
                    getBrushSurfaces(brush).size());
            brushFacts.put(brush, known);
        }
        return known;
    }

    public record BrushFacts(String name, int csgOper, int polyFlags, int sides) {
        public static final int ADD = 1;

        public static final int SUBTRACT = 2;

        public boolean isSemisolid() {
            return (polyFlags & 0x20) != 0;
        }

        public boolean isNotSolid() {
            return (polyFlags & 0x08) != 0;
        }
    }

    public int[] selectSurfaces(BspSurfaceSelect.Command command, int[] chosen, Set<String> materials)
            throws IOException {
        var edit = surfaceEdit();
        var select = edit.getSelect();
        var count = select.getSurfaceCount();
        var choice = new BitSet(count);
        for (var surface : chosen) {
            if (surface >= 0 && surface < count) {
                choice.set(surface);
            }
        }
        var paths = new HashMap<String, Integer>();
        var keys = switch (command) {
            case TEXTURE -> materialKeys(edit, paths);
            case ITEMS -> itemKeys(edit);
            default -> new int[count];
        };
        var wanted = new IntIntHashMap();
        for (var material : materials) {
            var key = material.isEmpty() ? Integer.valueOf(0) : paths.get(material.toLowerCase(Locale.ROOT));
            if (key != null) {
                wanted.put(key, 1);
            }
        }
        var brushes = new IntIntHashMap();
        var result = select.select(
                command,
                choice,
                actor -> {
                    if (!brushes.containsKey(actor)) {
                        brushes.put(actor, isBrushActor(actor) ? 1 : 0);
                    }
                    return brushes.get(actor) == 1;
                },
                keys,
                wanted::containsKey);
        var answer = new int[result.cardinality()];
        var at = 0;
        for (var surface = result.nextSetBit(0); surface >= 0; surface = result.nextSetBit(surface + 1)) {
            answer[at++] = surface;
        }
        return answer;
    }

    public synchronized boolean isBrushShapesRead() {
        return brushShapes != null;
    }

    public void readBrushShapes() {
        if (isBrushShapesRead()) {
            return;
        }
        Map<Integer, BspBrushShapes.Shape> read;
        try {
            var edit = surfaceEdit();
            var brushes = new TreeSet<Integer>();
            for (var surface = 0; surface < edit.getSurfaceCount(); surface++) {
                var actor = edit.getActor(surface);
                if (!brushes.contains(actor) && isBrushActor(actor)) {
                    brushes.add(actor);
                }
            }
            read = BspBrushShapes.read(source, original, brushes);
        } catch (IOException unreadable) {
            read = Map.of();
        }
        synchronized (this) {
            if (brushShapes == null) {
                brushShapes = read;
            }
        }
    }

    public String getSurfaceItemName(int surface) {
        readBrushShapes();
        try {
            var edit = surfaceEdit();
            if (surface < 0 || surface >= edit.getSurfaceCount()) {
                return "";
            }
            BspBrushShapes.Shape shape;
            synchronized (this) {
                shape = brushShapes.get(edit.getActor(surface));
            }
            var polygon = edit.getBrushPolygon(surface);
            return shape == null || polygon < 0 || polygon >= shape.itemNames().size()
                    ? ""
                    : shape.itemNames().get(polygon);
        } catch (IOException unavailable) {
            return "";
        }
    }

    public BrushWireframe getBrushWireframe(int brush) {
        readBrushShapes();
        BspBrushShapes.Shape shape;
        synchronized (this) {
            shape = brushShapes.get(brush);
        }
        if (shape == null || shape.polygons().isEmpty()) {
            return BrushWireframe.NONE;
        }
        var places = new HashMap<List<Float>, Integer>();
        var points = new ArrayList<Float>();
        var seen = new HashSet<Long>();
        var edges = new ArrayList<Integer>();
        for (var polygon : shape.polygons()) {
            var corners = new int[polygon.length];
            for (var k = 0; k < polygon.length; k++) {
                var corner = List.of((float) polygon[k][0], (float) polygon[k][1], (float) polygon[k][2]);
                var at = places.get(corner);
                if (at == null) {
                    at = places.size();
                    places.put(corner, at);
                    points.addAll(corner);
                }
                corners[k] = at;
            }
            for (var k = 0; k < corners.length; k++) {
                var a = corners[k];
                var b = corners[(k + 1) % corners.length];
                if (a != b && seen.add((long) Math.min(a, b) << 32 | Math.max(a, b))) {
                    edges.add(a);
                    edges.add(b);
                }
            }
        }
        var flat = new float[points.size()];
        for (var i = 0; i < flat.length; i++) {
            flat[i] = points.get(i);
        }
        return new BrushWireframe(
                flat, edges.stream().mapToInt(Integer::intValue).toArray());
    }

    public record BrushWireframe(float[] points, int[] edges) {
        public static final BrushWireframe NONE = new BrushWireframe(new float[0], new int[0]);
    }

    public int[] getSurfaceOutline(int surface) throws IOException {
        return surfaceEdit().getOutline(surface);
    }

    public void getBspPoint(int index, float[] out, int at) throws IOException {
        var points = surfaceEdit().getPoints();
        out[at] = points[index * 3];
        out[at + 1] = points[index * 3 + 1];
        out[at + 2] = points[index * 3 + 2];
    }

    public String getBrushDeleteRestriction(int brush) {
        var reason = getSurfaceEditRestriction();
        if (!reason.isEmpty()) {
            return reason;
        }
        BspBrushDelete delete;
        try {
            delete = brushDelete();
            reason = delete.getRestriction(brush);
        } catch (IOException unavailable) {
            return unavailable.getMessage();
        }
        if (!reason.isEmpty()) {
            return reason;
        }
        for (var surface : getBrushSurfaces(brush)) {
            if (hiddenSurfaces.contains(surface)
                    || surfaceMappings.containsKey(surface)
                    || surfaceMaterials.containsKey(surface)
                    || surfaceFlags.containsKey(surface)) {
                return "A side of it is hidden or has a new texture, mapping or flag. Give that back first";
            }
        }
        for (var state : actorStates.values()) {
            var location = state.current.location();
            if (!state.deleted && delete.contains(brush, location.x(), location.y(), location.z())) {
                return shortName(state.path) + " stands inside it, and its zone would change";
            }
        }
        for (var state : lightStates.values()) {
            var location = state.placement.location();
            if (!state.deleted && delete.contains(brush, location.x(), location.y(), location.z())) {
                return shortName(state.light.path) + " stands inside it, and its zone would change";
            }
        }
        return "";
    }

    public synchronized boolean isBrushDeleteRead() {
        return brushDelete != null || brushFailure != null;
    }

    public void readBrushDelete() {
        if (isBrushDeleteRead()) {
            return;
        }
        BspBrushDelete opened = null;
        String failure = null;
        if (level == null) {
            failure = structuralRestriction == null ? "its Level cannot be read" : structuralRestriction;
        } else {
            try {
                opened = BspBrushDelete.open(source, original, level.actors);
            } catch (IOException unavailable) {
                failure = unavailable.getMessage();
            }
        }
        synchronized (this) {
            if (brushDelete == null && brushFailure == null) {
                brushDelete = opened;
                brushFailure = failure;
            }
        }
    }

    public boolean isBrushDeleted(int brush) {
        return deletedBrushes.contains(brush);
    }

    public Set<Integer> getDeletedBrushes() {
        return Collections.unmodifiableSet(new TreeSet<>(deletedBrushes));
    }

    public boolean isSurfaceInDeletedBrush(int surface) {
        return deletedSurfaces.contains(surface);
    }

    public Set<Integer> getDeletedBrushSurfaces() {
        return deletedSurfaces;
    }

    public void updateBrushDeleted(int brush, boolean deleted) {
        if (deleted) {
            if (deletedBrushes.contains(brush)) {
                return;
            }
            var restriction = getBrushDeleteRestriction(brush);
            if (!restriction.isEmpty()) {
                throw new IllegalArgumentException(restriction);
            }
            var wanted = new TreeSet<>(deletedBrushes);
            wanted.add(brush);
            try {
                modelBase(wanted);
            } catch (IOException failed) {
                throw new IllegalArgumentException(
                        "This brush cannot be deleted, as the map's BSP cannot be written without it: "
                                + failed.getMessage(),
                        failed);
            }
            deletedBrushes.add(brush);
        } else {
            deletedBrushes.remove(brush);
        }
        refreshDeletedSurfaces();
        refreshBrushDirty(brush);
    }

    public Set<Integer> getEditedSurfaces() {
        return surfaceEdits().surfaces();
    }

    public void revertSurface(int surface) {
        surfaceMappings.remove(surface);
        hiddenSurfaces.remove(surface);
        surfaceMaterials.remove(surface);
        surfaceFlags.remove(surface);
        refreshSurfaceDirty(surface);
    }

    public byte[] previewSurfaceBody(boolean materials) throws IOException {
        var edits = new BspSurfaceEdit.Edits(
                surfaceMappings,
                Set.of(),
                materials ? surfaceMaterials : Map.of(),
                materials ? surfaceFlags : Map.of());
        return buildBody(null, null, edits, Set.of());
    }

    public byte[] previewSourceSurfaceBody() throws IOException {
        return buildBody(null, null, BspSurfaceEdit.Edits.NONE, Set.of());
    }

    public boolean isDirty() {
        return importsChanged || savedImportCount != imports.size() || !dirtyActors.isEmpty();
    }

    public static final class Snapshot {
        private final PackageEditDocument owner;
        private final List<String> names;
        private final List<ImportEntry> imports;
        private final Map<String, MaterialReference> materials;
        private final Map<String, ActorCheckpoint> actors;
        private final List<ActorCheckpoint> changes;
        private final Map<String, LightCheckpoint> lights;
        private final Map<Integer, BspSurfaceMapping> surfaces;
        private final Set<Integer> hiddenSurfaces;
        private final Map<Integer, BspSurfaceEdit.Material> surfaceMaterials;
        private final Map<Integer, Integer> surfaceFlags;
        private final Set<Integer> deletedBrushes;

        private Snapshot(PackageEditDocument owner) {
            this.owner = owner;
            names = List.copyOf(owner.names);
            imports = List.copyOf(owner.imports);
            materials = Map.copyOf(owner.materialReferences);
            var values = new LinkedHashMap<String, ActorCheckpoint>();
            var changed = new ArrayList<ActorCheckpoint>();
            owner.actorStates.forEach((path, state) -> {
                var checkpoint = new ActorCheckpoint(state, state.current, List.copyOf(state.skins), state.deleted);
                values.put(path, checkpoint);
                if (state.created
                        || state.deleted != state.baselineDeleted
                        || !state.current.equals(state.transform)
                        || !state.skins.equals(state.originalSkins)) {
                    changed.add(checkpoint);
                }
            });
            actors = Map.copyOf(values);
            changes = List.copyOf(changed);
            var checkpoints = new LinkedHashMap<String, LightCheckpoint>();
            owner.lightStates.forEach((path, state) -> checkpoints.put(path, state.getCheckpoint()));
            lights = Collections.unmodifiableMap(checkpoints);
            surfaces = Map.copyOf(owner.surfaceMappings);
            hiddenSurfaces = Set.copyOf(owner.hiddenSurfaces);
            surfaceMaterials = Map.copyOf(owner.surfaceMaterials);
            surfaceFlags = Map.copyOf(owner.surfaceFlags);
            deletedBrushes = Set.copyOf(owner.deletedBrushes);
        }
    }

    public static final class RecoveryBundle {
        private final Bundle bundle;

        private RecoveryBundle(Bundle bundle) {
            this.bundle = bundle;
        }

        public byte[] encode() throws IOException {
            return PackageRecoveryCodec.encode(bundle);
        }
    }

    public record RestoredRecovery(PackageEditDocument document, List<Snapshot> historySnapshots) {
        public RestoredRecovery {
            historySnapshots = List.copyOf(historySnapshots);
        }
    }

    public static final class UnsupportedRecoveryFormat extends IOException {
        public UnsupportedRecoveryFormat(String message) {
            super(message);
        }
    }

    public RecoveryBundle captureRecovery(List<Snapshot> historySnapshots) {
        var history = List.copyOf(historySnapshots);
        for (var snapshot : history) {
            if (snapshot.owner != this) {
                throw new IllegalArgumentException("Snapshot belongs to another map");
            }
        }
        var definitions = new ArrayList<Definition>();
        var identities = new IdentityHashMap<ActorState, Integer>();
        var frames = new IdentityHashMap<Snapshot, Frame>();
        Function<Snapshot, Frame> capture = snapshot -> frames.computeIfAbsent(snapshot, frame -> {
            var values = new ArrayList<Value>();
            for (var value : frame.changes.stream()
                    .sorted(Comparator.comparingInt(checkpoint -> checkpoint.state.entry.index()))
                    .toList()) {
                var state = value.state;
                var identity = identities.get(state);
                if (identity == null) {
                    identity = definitions.size();
                    identities.put(state, identity);
                    var scalars = state.scalarProperties.stream()
                            .map(scalar -> new Scalar(scalar.name, scalar.type, scalar.truth, scalar.payload))
                            .toList();
                    var children = state.children.stream()
                            .map(child -> new PackageRecoveryCodec.Child(
                                    child.source(), child.entry().objectName()))
                            .toList();
                    definitions.add(new Definition(
                            state.entry.index(),
                            state.created,
                            state.path,
                            state.entry.classIndex(),
                            state.entry.objectName(),
                            state.meshReference,
                            state.meshPath,
                            state.template,
                            state.newTail,
                            scalars,
                            children,
                            state.movementTagsOverride,
                            state.moveEventOverride));
                }
                values.add(new Value(identity, value.transform, value.skins, value.deleted));
            }
            var table = new Tables(
                    frame.names.subList(original.names.size(), frame.names.size()),
                    frame.imports.subList(original.imports.size(), frame.imports.size()),
                    frame.materials);
            var lights = new ArrayList<LightValue>();
            frame.lights.forEach((path, checkpoint) -> {
                var state = checkpoint.state();
                if (state.created) {
                    lights.add(new LightValue(
                            path,
                            checkpoint.color(),
                            checkpoint.placement(),
                            checkpoint.deleted(),
                            state.template,
                            state.light.entry.index(),
                            LightAim.of(checkpoint.shape()),
                            state.moveEventOverride));
                } else if (checkpoint.deleted()
                        || !checkpoint.color().equals(state.light.color)
                        || !checkpoint.placement().equals(state.light.getPlacement())
                        || !checkpoint.shape().equals(state.light.shape)) {
                    lights.add(new LightValue(
                            path,
                            checkpoint.color(),
                            checkpoint.placement(),
                            checkpoint.deleted(),
                            null,
                            0,
                            LightAim.of(checkpoint.shape())));
                }
            });
            var surfaces = frame.surfaces.entrySet().stream()
                    .sorted(Entry.comparingByKey())
                    .map(entry -> new SurfaceValue(entry.getKey(), entry.getValue()))
                    .toList();
            var hidden = frame.hiddenSurfaces.stream().sorted().toList();
            var materials = frame.surfaceMaterials.entrySet().stream()
                    .sorted(Entry.comparingByKey())
                    .map(entry -> new SurfaceMaterialValue(
                            entry.getKey(),
                            entry.getValue().reference(),
                            entry.getValue().uSize(),
                            entry.getValue().vSize()))
                    .toList();
            var brushes = frame.deletedBrushes.stream().sorted().toList();
            var flags = frame.surfaceFlags.entrySet().stream()
                    .sorted(Entry.comparingByKey())
                    .map(entry -> new SurfaceFlagValue(entry.getKey(), entry.getValue()))
                    .toList();
            return new Frame(table, values, lights, surfaces, hidden, materials, brushes, flags);
        });
        var current = capture.apply(snapshot());
        var saved = capture.apply(savedSnapshot);
        var capturedHistory = history.stream().map(capture).toList();
        return new RecoveryBundle(
                new Bundle(originalSha256, original.packageName, definitions, current, saved, capturedHistory));
    }

    public Snapshot snapshot() {
        return new Snapshot(this);
    }

    public void restore(Snapshot snapshot) {
        carryGraph = null;
        if (snapshot.owner != this) {
            throw new IllegalArgumentException("Snapshot belongs to another map");
        }
        names.clear();
        names.addAll(snapshot.names);
        imports.clear();
        imports.addAll(snapshot.imports);
        importsChanged = !imports.equals(savedSnapshot.imports);
        materialReferences.clear();
        materialReferences.putAll(snapshot.materials);
        actorStates.clear();
        snapshot.actors.forEach((path, checkpoint) -> {
            var state = checkpoint.state;
            state.current = checkpoint.transform;
            state.skins = new ArrayList<>(checkpoint.skins);
            state.deleted = checkpoint.deleted;
            actorStates.put(path, state);
        });
        lightStates.clear();
        snapshot.lights.forEach((path, checkpoint) -> {
            var state = checkpoint.state();
            state.current = checkpoint.color();
            state.placement = checkpoint.placement();
            state.shape = checkpoint.shape();
            state.deleted = checkpoint.deleted();
            lightStates.put(path, state);
        });
        surfaceMappings.clear();
        surfaceMappings.putAll(snapshot.surfaces);
        hiddenSurfaces.clear();
        hiddenSurfaces.addAll(snapshot.hiddenSurfaces);
        surfaceMaterials.clear();
        surfaceMaterials.putAll(snapshot.surfaceMaterials);
        surfaceFlags.clear();
        surfaceFlags.putAll(snapshot.surfaceFlags);
        deletedBrushes.clear();
        deletedBrushes.addAll(snapshot.deletedBrushes);
        refreshDeletedSurfaces();
        dirtyActors.clear();
        actorStates.values().forEach(this::refreshDirty);
        lightStates.values().forEach(this::refreshDirty);
        var surfaces = new HashSet<>(surfaceEdits().surfaces());
        surfaces.addAll(savedSnapshot.surfaces.keySet());
        surfaces.addAll(savedSnapshot.hiddenSurfaces);
        surfaces.addAll(savedSnapshot.surfaceMaterials.keySet());
        surfaces.addAll(savedSnapshot.surfaceFlags.keySet());
        surfaces.forEach(this::refreshSurfaceDirty);
        var brushes = new HashSet<>(deletedBrushes);
        brushes.addAll(savedSnapshot.deletedBrushes);
        brushes.forEach(this::refreshBrushDirty);
        for (var path : savedSnapshot.actors.keySet()) {
            if (!actorStates.containsKey(path)) {
                dirtyActors.add(path);
            }
        }
        for (var path : savedSnapshot.lights.keySet()) {
            if (!lightStates.containsKey(path)) {
                dirtyActors.add(path);
            }
        }
    }

    public boolean hasChangesFromSource() {
        return imports.size() != original.imports.size()
                || !getLightColorEdits().isEmpty()
                || !getLightPlacementEdits().isEmpty()
                || lightStates.values().stream().anyMatch(state -> state.created)
                || !surfaceEdits().isEmpty()
                || !deletedBrushes.isEmpty()
                || actorStates.values().stream()
                        .anyMatch(state -> state.created
                                || state.deleted != state.baselineDeleted
                                || !state.current.equals(state.transform)
                                || !state.skins.equals(state.originalSkins));
    }

    public byte[] previewBody() throws IOException {
        return buildBody();
    }

    public byte[] previewBody(ActorLightingChanges lighting) throws IOException {
        return buildBody(null, lighting);
    }

    public byte[] previewBody(NativePackageChanges nativeChanges) throws IOException {
        nativeChanges.validateFor(source, originalSha256, original);
        return buildBody(nativeChanges, null);
    }

    public record LightingEdit(
            String path,
            boolean created,
            boolean deleted,
            ActorTransform original,
            ActorTransform current,
            boolean editable) {}

    public List<LightingEdit> getLightingEdits() {
        var result = new ArrayList<LightingEdit>();
        for (var state : actorStates.values()) {
            if (state.created && state.deleted || state.pawn || state.nonStatic || state.emitter) {
                continue;
            }
            result.add(new LightingEdit(
                    state.path,
                    state.created,
                    state.deleted && !state.baselineDeleted,
                    state.created ? null : state.transform,
                    state.current,
                    state.editable));
        }
        return List.copyOf(result);
    }

    public boolean canWriteLighting(String sourcePath) {
        var state = actorStates.get(sourcePath);
        if (state == null || state.deleted || state.pawn || state.nonStatic || state.emitter) {
            return false;
        }
        if (state.created || state.editable && state.relocatable) {
            return true;
        }
        try {
            var reference = instanceReference(state);
            if (reference == 0 || !isInstanceExport(reference)) {
                return false;
            }
            for (var other : actorStates.values()) {
                if (other != state && !other.created && instanceReference(other) == reference) {
                    return false;
                }
            }
            return true;
        } catch (IOException malformed) {
            return false;
        }
    }

    record Unapplied(Map<String, Actor> actors, int beastKept) {}

    Unapplied getUnappliedLighting(Set<Integer> lights) throws IOException {
        var owners = new HashMap<Integer, Integer>();
        for (var state : actorStates.values()) {
            if (!state.created) {
                var reference = instanceReference(state);
                if (reference != 0) {
                    owners.merge(reference, 1, Integer::sum);
                }
            }
        }
        var result = new TreeMap<String, Actor>(String.CASE_INSENSITIVE_ORDER);
        var beastKept = 0;
        for (var state : actorStates.values()) {
            if (state.created || state.deleted) {
                continue;
            }
            var reference = instanceReference(state);
            if (reference == 0 || owners.get(reference) != 1 || !isInstanceExport(reference)) {
                continue;
            }
            var decoded = decodeInstance(reference);
            if (!decoded.beastLightmaps().isEmpty()) {
                if (names(decoded.instance(), lights)) {
                    beastKept++;
                }
                continue;
            }
            Decoded lod1 = null;
            if (decoded.lod1() != 0) {
                if (!isInstanceExport(decoded.lod1())) {
                    continue;
                }
                lod1 = decodeInstance(decoded.lod1());
            }
            var shares = decoded.lod2() != 0 && decoded.lod2() == decoded.lod1();
            Decoded lod2 = null;
            if (decoded.lod2() != 0 && !shares) {
                if (!isInstanceExport(decoded.lod2())) {
                    continue;
                }
                lod2 = decodeInstance(decoded.lod2());
            }
            if (!names(decoded.instance(), lights)
                    && (lod1 == null || !names(lod1.instance(), lights))
                    && (lod2 == null || !names(lod2.instance(), lights))) {
                continue;
            }
            result.put(
                    state.path,
                    new Actor(
                            unapplied(decoded.instance(), lights),
                            lod1 == null ? null : unapplied(lod1.instance(), lights),
                            lod2 == null ? null : unapplied(lod2.instance(), lights),
                            shares));
        }
        return new Unapplied(result, beastKept);
    }

    public boolean hasBeastLighting(String sourcePath) {
        var state = actorStates.get(sourcePath);
        if (state == null || state.created) {
            return false;
        }
        try {
            var reference = instanceReference(state);
            return reference != 0
                    && isInstanceExport(reference)
                    && !decodeInstance(reference).beastLightmaps().isEmpty();
        } catch (IOException malformed) {
            return false;
        }
    }

    record SavedBeast(int lod, int instance, int width, int height, int slot0, int slot1) {}

    List<SavedBeast> getSavedBeast(String sourcePath) {
        var state = actorStates.get(sourcePath);
        if (state == null || state.created) {
            return List.of();
        }
        var result = new ArrayList<SavedBeast>();
        try {
            var reference = instanceReference(state);
            if (reference == 0 || !isInstanceExport(reference)) {
                return List.of();
            }
            var decoded = decodeInstance(reference);
            addSavedBeast(result, 0, reference, decoded);
            if (decoded.lod1() > 0 && isInstanceExport(decoded.lod1())) {
                addSavedBeast(result, 1, decoded.lod1(), decodeInstance(decoded.lod1()));
            }
            if (decoded.lod2() > 0 && decoded.lod2() != decoded.lod1() && isInstanceExport(decoded.lod2())) {
                addSavedBeast(result, 2, decoded.lod2(), decodeInstance(decoded.lod2()));
            }
        } catch (IOException malformed) {
            return List.of();
        }
        return List.copyOf(result);
    }

    Saved getSavedBeastTexture(int exportIndex) throws IOException {
        if (exportIndex <= 0
                || exportIndex > original.exports.size()
                || !isBeastTexture(original.exports.get(exportIndex - 1))) {
            throw malformed("Not a Beast lightmap texture export: " + exportIndex);
        }
        return BeastTextureCodec.read(original, original.exports.get(exportIndex - 1));
    }

    PackageBytes getOriginalPackage() {
        return original;
    }

    List<Integer> getTerrainBeastTextures(String owner) throws IOException {
        int slot0 = 0, slot1 = 0;
        for (var entry : original.exports) {
            if (!original.getClassName(entry).equals("Texture")) {
                continue;
            }
            var name = original.getName(entry.objectName());
            if (name.equalsIgnoreCase(owner + "_Lightmap_0")) {
                slot0 = entry.index();
            } else if (name.equalsIgnoreCase(owner + "_Lightmap_1")) {
                slot1 = entry.index();
            }
        }
        return slot0 > 0 && slot1 > 0 ? List.of(slot0, slot1) : List.of();
    }

    byte[] getOriginalBody() {
        return original.body.clone();
    }

    public boolean hasStaticMeshInstance(String sourcePath) {
        var state = actorStates.get(sourcePath);
        if (state == null || state.created) {
            return false;
        }
        try {
            return instanceReference(state) != 0;
        } catch (IOException malformed) {
            return false;
        }
    }

    public Set<String> getDeletedActorPaths() {
        var paths = new HashSet<String>();
        actorStates.values().stream()
                .filter(state -> state.deleted && !state.baselineDeleted)
                .forEach(state -> paths.add(state.path));
        return Set.copyOf(paths);
    }

    public String importMesh(Path meshPackage, String meshSourcePath) throws IOException {
        var asset = new PackageBytes(meshPackage, Files.readAllBytes(meshPackage));
        for (var entry : asset.exports) {
            if (asset.getObjectPath(entry.index()).equalsIgnoreCase(meshSourcePath)) {
                if (!asset.getObjectPath(entry.classIndex()).equalsIgnoreCase("Engine.StaticMesh")) {
                    throw malformed("Selected asset is not an Engine.StaticMesh");
                }
                validateAssetOuter(asset, entry.outer());
                var canonical = asset.getObjectPath(entry.index());
                importPath(canonical, "Engine", "StaticMesh");
                return canonical;
            }
        }
        throw malformed("Mesh export not found: " + meshSourcePath);
    }

    public List<String> getMeshReferences() throws IOException {
        var result = new ArrayList<String>();
        for (var entry : original.exports) {
            if (original.getClassName(entry).equals("StaticMesh")) {
                result.add(original.getObjectPath(entry.index()));
            }
        }
        for (var i = 0; i < imports.size(); i++) {
            if (names.get(imports.get(i).className()).equals("StaticMesh")) {
                result.add(objectPath(-i - 1));
            }
        }
        return result.stream().sorted(String.CASE_INSENSITIVE_ORDER).distinct().toList();
    }

    public String getStructuralRestriction() {
        return structuralRestriction == null ? "" : structuralRestriction;
    }

    public ActorRecord duplicateActor(String sourcePath, ActorTransform placement) throws IOException {
        carryGraph = null;
        var state = requireEditable(sourcePath);
        requireStructural();
        if (state.emitter) {
            return keepMoveEvent(state, createEffect(state.copyOf != null ? state.copyOf : state, placement));
        }
        if (state.pawn || state.nonStatic) {
            return keepMoveEvent(
                    state, createPawn(state.copyOf != null ? state.copyOf : state, placement, state.skins));
        }
        if (state.meshReference == 0) {
            throw malformed("Actor has no StaticMesh reference");
        }
        if (!referenceClass(state.meshReference).equals("StaticMesh")) {
            throw malformed("Actor does not reference a StaticMesh");
        }
        return createActor(
                state.meshReference, state.meshPath, placement, state.skins, state.path, copiedScalars(state));
    }

    public ActorRecord createStaticMeshActor(String meshSourcePath, ActorTransform placement) throws IOException {
        carryGraph = null;
        requireStructural();
        var mesh = meshReference(meshSourcePath);
        return createActor(mesh.reference(), mesh.path(), placement, List.of(), "", List.of());
    }

    public ActorRecord replaceActorMesh(String sourcePath, String meshSourcePath, int keptSlots) throws IOException {
        carryGraph = null;
        var state = requireEditable(sourcePath);
        if (state.pawn) {
            throw new IllegalArgumentException(PAWN_LOOK);
        }
        if (state.nonStatic) {
            throw new IllegalArgumentException(MOVING_KEEPS);
        }
        if (state.emitter) {
            throw new IllegalArgumentException(EFFECT_KEEPS);
        }
        requireStructural();
        var mesh = meshReference(meshSourcePath);
        var skins = new ArrayList<>(state.skins.subList(0, Math.clamp(keptSlots, 0, state.skins.size())));
        while (!skins.isEmpty() && skins.getLast() == 0) {
            skins.removeLast();
        }
        var before = snapshot();
        try {
            var created =
                    createActor(mesh.reference(), mesh.path(), state.current, skins, state.path, copiedScalars(state));
            deleteActor(sourcePath);
            return created;
        } catch (IOException | RuntimeException failure) {
            restore(before);
            throw failure;
        }
    }

    public void deleteActor(String sourcePath) throws IOException {
        carryGraph = null;
        var state = requireEditable(sourcePath);
        requireStructural();
        if (level.actors.stream().limit(2).anyMatch(index -> index == state.entry.index())) {
            throw malformed("The two reserved Level actor slots cannot be deleted");
        }
        state.deleted = true;
        refreshDirty(state);
    }

    public void undeleteActor(String sourcePath) throws IOException {
        carryGraph = null;
        var state = actorStates.get(sourcePath);
        if (state == null || !state.deleted || state.baselineDeleted || state.created) {
            throw new IllegalArgumentException("Only a deleted actor of the source map can come back: " + sourcePath);
        }
        requireStructural();
        state.deleted = false;
        refreshDirty(state);
    }

    public Set<String> getDeletedSourceActors() {
        var paths = new HashSet<String>();
        for (var state : actorStates.values()) {
            if (state.deleted && !state.baselineDeleted && !state.created) {
                paths.add(state.path);
            }
        }
        return Set.copyOf(paths);
    }

    public @Nullable ActorRecord getDeletedActor(String sourcePath) {
        var state = actorStates.get(sourcePath);
        return state == null || !state.deleted ? null : state.snapshot();
    }

    public ActorRecord transferActorTo(String sourcePath, PackageEditDocument target, ActorTransform placement)
            throws IOException {
        carryGraph = null;
        target.carryGraph = null;
        Objects.requireNonNull(target);
        if (target == this) {
            throw malformed("Transfer requires a different map");
        }
        var before = snapshot();
        var targetBefore = target.snapshot();
        try {
            requireStructural();
            target.requireStructural();
            if (getFileVersion() != target.getFileVersion() || getLicenseVersion() != target.getLicenseVersion()) {
                throw malformed("Actor transfer requires matching map package versions");
            }
            var state = requireEditable(sourcePath);
            if (state.pawn) {
                throw new IllegalArgumentException(PAWN_MAP);
            }
            if (state.nonStatic) {
                throw new IllegalArgumentException(MOVING_KEEPS);
            }
            if (state.emitter) {
                throw new IllegalArgumentException(EFFECT_KEEPS);
            }
            var mesh = target.importReference(this, state.meshReference, "StaticMesh");
            var skins = new ArrayList<Integer>();
            for (var reference : state.skins) {
                var mapped = reference == 0 ? 0 : target.importReference(this, reference, null);
                skins.add(mapped);
                if (mapped != 0) {
                    var path = objectPath(reference);
                    target.materialReferences.put(path, new MaterialReference(path, referenceClass(reference), mapped));
                }
            }
            var result = target.createActor(mesh, state.meshPath, placement, skins, state.path, copiedScalars(state));
            deleteActor(sourcePath);
            return result;
        } catch (IOException | RuntimeException failure) {
            restore(before);
            target.restore(targetBefore);
            throw failure;
        }
    }

    public String transformRestriction(String sourcePath, ActorTransform transform) {
        try {
            var state = requireEditable(sourcePath);
            validateFixedEdits(state, transform, state.skins);
            return "";
        } catch (IllegalArgumentException refused) {
            return refused.getMessage();
        }
    }

    public String deleteRestriction(String sourcePath) {
        try {
            var state = requireEditable(sourcePath);
            if (level == null) {
                return getStructuralRestriction();
            }
            return level.actors.stream().limit(2).anyMatch(index -> index == state.entry.index())
                    ? "The two reserved Level actor slots cannot be deleted"
                    : "";
        } catch (IllegalArgumentException refused) {
            return refused.getMessage();
        }
    }

    public String copyRestriction(String sourcePath) {
        try {
            var state = requireEditable(sourcePath);
            if (level == null) {
                return getStructuralRestriction();
            }
            if (state.emitter) {
                return "";
            }
            return state.meshReference == 0 && !state.pawn ? "This actor has no StaticMesh" : "";
        } catch (IllegalArgumentException refused) {
            return refused.getMessage();
        }
    }

    public void updateTransform(String sourcePath, ActorTransform transform) {
        var state = requireEditable(sourcePath);
        Objects.requireNonNull(transform);
        validateFixedEdits(state, transform, state.skins);
        state.current = transform;
        refreshDirty(state);
    }

    public void updateSkin(String sourcePath, int slot, String materialSourcePath) {
        if (slot < 0 || slot >= MAX_SKIN_SLOTS) {
            throw new IllegalArgumentException("Invalid material slot " + slot);
        }
        var state = requireEditable(sourcePath);
        var reference = materialSourcePath == null ? null : materialReferences.get(materialSourcePath);
        if (materialSourcePath != null && reference == null) {
            throw new IllegalArgumentException("Material is not referenced by the map: " + materialSourcePath);
        }
        if (slot >= state.skins.size() && reference == null) {
            return;
        }
        var skins = new ArrayList<>(state.skins);
        while (skins.size() <= slot) {
            skins.add(0);
        }
        skins.set(slot, reference == null ? 0 : reference.objectIndex());
        while (skins.size() > state.originalSkins.size() && skins.getLast() == 0) {
            skins.removeLast();
        }
        validateFixedEdits(state, state.current, skins);
        state.skins = skins;
        refreshDirty(state);
    }

    public void updateSkins(String sourcePath, List<Integer> references) {
        var state = requireEditable(sourcePath);
        if (references.size() > MAX_SKIN_SLOTS) {
            throw new IllegalArgumentException("Too many material slots");
        }
        for (var reference : references) {
            if (reference != 0
                    && materialReferences.values().stream().noneMatch(m -> m.objectIndex() == reference)
                    && !state.originalSkins.contains(reference)) {
                throw new IllegalArgumentException("Unknown material reference " + reference);
            }
        }
        validateFixedEdits(state, state.current, references);
        state.skins = new ArrayList<>(references);
        refreshDirty(state);
    }

    public MaterialReference importMaterial(Path materialPackage, String materialSourcePath) throws IOException {
        var existing = materialReferences.get(materialSourcePath);
        if (existing != null) {
            return existing;
        }
        var asset = new PackageBytes(materialPackage, Files.readAllBytes(materialPackage));
        return importMaterial(asset, materialSourcePath);
    }

    public MaterialReference importHeightTexture(Prepared clone) throws IOException {
        verifySourceUnchanged();
        IsolatedHeightPackage.verifyPrepared(clone);
        var asset = new PackageBytes(Path.of(clone.packageName() + ".utx"), clone.bytes());
        return importMaterial(asset, clone.objectPath());
    }

    public Path saveAs(Path outputFile) throws IOException {
        var target = outputFile.toAbsolutePath().normalize();
        var bytes = prepareSave(target);
        var parent = target.getParent();
        if (!Files.isDirectory(parent)) {
            throw new IOException("Output directory does not exist: " + parent);
        }
        var temporary = Files.createTempFile(parent, ".cruma-map-", ".tmp");
        try {
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                var buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            verifySourceUnchanged();
            if (Files.exists(target) && Files.isSameFile(source, target)) {
                throw new IOException("Output resolves to the source map");
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
        markSaved();
        return target;
    }

    public byte[] prepareSave(Path outputFile) throws IOException {
        return prepareSave(outputFile, null);
    }

    public byte[] prepareSave(Path outputFile, NativePackageChanges nativeChanges) throws IOException {
        return prepareSave(outputFile, nativeChanges, null);
    }

    public byte[] prepareSave(Path outputFile, NativePackageChanges nativeChanges, ActorLightingChanges lighting)
            throws IOException {
        var target = outputFile.toAbsolutePath().normalize();
        if (target.equals(source) || Files.exists(target) && Files.isSameFile(source, target)) {
            throw new IOException("Save As must use a separate output map; the source client is read-only");
        }
        if (!target.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".unr")) {
            throw new IOException("The output map must have the .unr extension");
        }
        verifySourceUnchanged();
        if (nativeChanges != null) {
            nativeChanges.validateFor(source, originalSha256, original);
        }
        var body = buildBody(nativeChanges, lighting);
        var bytes = original.encode(body, target);
        var verified = new PackageEditDocument(target, bytes, clientRoot);
        verifySavedActors(verified);
        if (lighting != null) {
            verifyLighting(verified, lighting);
        }
        if (nativeChanges != null) {
            nativeChanges.verify(target, bytes);
        }
        return bytes;
    }

    public void markSaved() {
        for (var state : actorStates.values()) {
            state.savedTransform = state.current;
            state.savedSkins = List.copyOf(state.skins);
        }
        dirtyActors.clear();
        savedImportCount = imports.size();
        importsChanged = false;
        savedSnapshot = snapshot();
    }

    public void verifySourceUnchanged() throws IOException {
        if (!sha256(Files.readAllBytes(source)).equals(originalSha256)) {
            throw new IOException("Source map changed since editing began; reopen it before saving");
        }
    }

    public boolean canWriteBeastLightmaps() {
        if (original.licenseVersion < UStaticMeshInstance.BEAST_LIGHTMAP_LICENSE) {
            return false;
        }
        try {
            for (var entry : original.exports) {
                if (isBeastTexture(entry)) {
                    BeastTextureCodec.readTemplate(original, entry);
                    return true;
                }
            }
        } catch (IOException unreadable) {
            return false;
        }
        return false;
    }

    private LightState newLight(LightState template, int index, @Nullable String name) throws IOException {
        var prefix = "Cruma" + template.light.className;
        var objectName = name;
        if (objectName == null) {
            var suffix = index;
            objectName = prefix + suffix;
            while (names.contains(objectName) || lightStates.containsKey(original.packageName + "." + objectName)) {
                objectName = prefix + ++suffix;
            }
        }
        var saved = template.light.entry;
        var entry = new ExportEntry(
                index,
                saved.classIndex(),
                0,
                0,
                intern(objectName),
                saved.flags(),
                saved.serialSize(),
                saved.serialOffset(),
                0,
                0,
                0);
        return new LightState(
                template.light.copy(entry, original.packageName + "." + objectName), true, template.light.path);
    }

    private int nextCreatedIndex() {
        return original.exports.size() + createdExportCount() + 1;
    }

    private int createdExportCount() {
        return (int) (actorStates.values().stream()
                        .filter(state -> state.created)
                        .mapToLong(state -> 1 + state.children.size())
                        .sum()
                + lightStates.values().stream().filter(state -> state.created).count());
    }

    private String placementRestriction(LightState state, LightColor color, LightPlacement placement) {
        var reason = state.light.getRestriction(color, placement, brightnessType, radiusType);
        if (!reason.isEmpty() || placement.equals(state.light.getPlacement())) {
            return reason;
        }
        try {
            var edit = leafLightEdit();
            if (placement.location().equals(state.light.location)) {
                return "";
            }
            reason = edit.getRestriction(state.light.location, placement.location());
            if (!reason.isEmpty()) {
                return reason;
            }
            var before = edit.getRegion(state.light.location);
            var after = edit.getRegion(placement.location());
            if (before == null || after == null) {
                return "";
            }
            if (before.zoneNumber() != after.zoneNumber()) {
                if (state.light.region == null) {
                    return "Its zone is saved in an unusual way, so it can only move inside its own zone";
                }
                if (isSkyZone(before.zone()) != isSkyZone(after.zone())) {
                    return "A light cannot move into or out of a sky zone";
                }
            }
            return state.light.getRestriction(
                    color, placement, newRegion(state, placement), state.shape, brightnessType, radiusType);
        } catch (IOException unavailable) {
            return unavailable.getMessage();
        }
    }

    private @Nullable LightRegion newRegion(LightState state, LightPlacement placement) throws IOException {
        if (placement.location().equals(state.light.location) || state.light.region == null) {
            return null;
        }
        return state.light.isCarryOnly()
                ? readActorRegions().getRegion(placement.location())
                : leafLightEdit().getRegion(placement.location());
    }

    private boolean isSkyZone(int reference) throws IOException {
        return reference > 0
                && reference <= original.exports.size()
                && original.getClassName(original.exports.get(reference - 1)).equals("SkyZoneInfo");
    }

    private int zoneAt(LightState state, LightPlacement placement) {
        try {
            var region = newRegion(state, placement);
            if (region != null) {
                return region.zoneNumber();
            }
        } catch (IOException unavailable) {
        }
        return state.light.region == null ? -1 : state.light.region.zoneNumber();
    }

    private @Nullable LightRegion getMovedRegion(ActorState state) {
        var base = state.copyOf != null ? state.copyOf : state;
        if (base.region == null || base.transform.location().equals(state.current.location())) {
            return null;
        }
        var region = readActorRegions().getRegion(state.current.location());
        return region == null || region.equals(base.region) ? null : region;
    }

    private synchronized ActorRegions readActorRegions() {
        if (actorRegions == null) {
            try {
                actorRegions = level == null ? ActorRegions.NONE : ActorRegions.read(source, original, level.actors);
            } catch (IOException unreadable) {
                actorRegions = ActorRegions.NONE;
            }
        }
        return actorRegions;
    }

    private synchronized BspLeafLightEdit leafLightEdit() throws IOException {
        if (leafLightEdit == null && leafLightFailure == null) {
            if (level == null) {
                leafLightFailure = structuralRestriction == null ? "its Level cannot be read" : structuralRestriction;
            } else {
                try {
                    leafLightEdit = BspLeafLightEdit.open(source, original, level.actors);
                } catch (IOException unavailable) {
                    leafLightFailure = unavailable.getMessage();
                }
            }
        }
        if (leafLightEdit == null) {
            throw new IOException(
                    "The light lists of this map cannot be rebuilt, so its lights cannot move: " + leafLightFailure);
        }
        return leafLightEdit;
    }

    private @Nullable LeafLights leafLightLists(Set<Integer> brushes) throws IOException {
        if (!brushes.isEmpty()) {
            modelBase(brushes);
        }
        return lockedLeafLightLists(brushes);
    }

    private synchronized @Nullable LeafLights lockedLeafLightLists(Set<Integer> brushes) throws IOException {
        var moved = new LinkedHashMap<Integer, NativeBspLeafLights.Light>();
        var removed = new TreeSet<Integer>();
        for (var state : lightStates.values()) {
            if (!state.isPlaced()) {
                continue;
            }
            var index = state.light.entry.index();
            if (state.deleted) {
                removed.add(index);
                continue;
            }
            var location = state.placement.location();
            moved.put(
                    index,
                    new NativeBspLeafLights.Light(
                            index, location.x(), location.y(), location.z(), state.placement.reach(), false));
        }
        if (moved.isEmpty() && removed.isEmpty()) {
            return null;
        }
        var key = new LeafListsKey(Map.copyOf(moved), Set.copyOf(removed), appendedActors(), Set.copyOf(brushes));
        var cached = leafLightsCache.get(key.brushes());
        if (cached == null || !cached.key().equals(key)) {
            var edit = brushes.isEmpty() ? leafLightEdit() : modelBase(brushes).lights();
            var lists = edit.buildLists(moved, removed, key.appended());
            cached = new CachedLists(key, lists == null || lists.equals(edit.getSaved()) ? null : lists);
            leafLightsCache.put(key.brushes(), cached);
        }
        return cached.lists();
    }

    private record CachedLists(LeafListsKey key, @Nullable LeafLights lists) {}

    private record LeafListsKey(
            Map<Integer, NativeBspLeafLights.Light> moved,
            Set<Integer> removed,
            List<Integer> appended,
            Set<Integer> brushes) {}

    private record ModelBase(PackageBytes source, BspSurfaceEdit surfaces, BspLeafLightEdit lights) {}

    private List<Integer> appendedActors() {
        var result = new TreeSet<Integer>();
        for (var state : actorStates.values()) {
            if (state.created && !state.deleted) {
                result.add(state.entry.index());
            }
        }
        for (var state : lightStates.values()) {
            if (state.created && !state.deleted) {
                result.add(state.light.entry.index());
            }
        }
        return List.copyOf(result);
    }

    private boolean isBrushActor(int actor) {
        try {
            return actor > 0
                    && actor <= original.exports.size()
                    && original.getClassName(original.exports.get(actor - 1)).equals("Brush");
        } catch (IOException unreadable) {
            return false;
        }
    }

    private void readBrushTags(ExportEntry entry, int[] tags) throws IOException {
        var cursor = new Cursor(original.body, entry.serialOffset(), entry.serialOffset() + entry.serialSize());
        if ((entry.flags() & 0x02000000) != 0) {
            var node = cursor.readIndex();
            cursor.readIndex();
            cursor.skip(12);
            if (node != 0) {
                cursor.readIndex();
            }
        }
        while (true) {
            var name = original.getName(cursor.readIndex());
            if (name.equalsIgnoreCase("None")) {
                return;
            }
            var info = cursor.readU8();
            var type = info & 15;
            if (type == 0) {
                throw malformed("Invalid property type");
            }
            if (type == 10) {
                original.getName(cursor.readIndex());
            }
            var size = switch (info >> 4 & 7) {
                case 0 -> 1;
                case 1 -> 2;
                case 2 -> 4;
                case 3 -> 12;
                case 4 -> 16;
                case 5 -> cursor.readU8();
                case 6 -> cursor.readU16();
                default -> cursor.readI32();
            };
            var index = type != 3 && (info & 128) != 0 ? cursor.readArrayIndex() : 0;
            var payload = cursor.position;
            if (index == 0 && type == 1 && size == 1 && name.equalsIgnoreCase("CsgOper")) {
                tags[0] = cursor.readU8();
            } else if (index == 0 && type == 2 && size == 4 && name.equalsIgnoreCase("PolyFlags")) {
                tags[1] = cursor.readI32();
            }
            cursor.position = payload;
            original.skipValue(cursor, name, type, size);
        }
    }

    private int[] materialKeys(BspSurfaceEdit edit, Map<String, Integer> byPath) throws IOException {
        var keys = new int[edit.getSurfaceCount()];
        var byReference = new IntIntHashMap();
        for (var surface = 0; surface < keys.length; surface++) {
            var material = surfaceMaterials.get(surface);
            var reference = material != null ? material.reference() : edit.getMaterial(surface);
            if (reference == 0) {
                continue;
            }
            if (!byReference.containsKey(reference)) {
                var path = materialPath(reference).toLowerCase(Locale.ROOT);
                var key = byPath.get(path);
                if (key == null) {
                    key = byPath.size() + 1;
                    byPath.put(path, key);
                }
                byReference.put(reference, key);
            }
            keys[surface] = byReference.get(reference);
        }
        return keys;
    }

    private int[] itemKeys(BspSurfaceEdit edit) {
        readBrushShapes();
        synchronized (this) {
            if (surfaceItems == null) {
                var names = new HashMap<String, Integer>();
                names.put("none", 0);
                var keys = new int[edit.getSurfaceCount()];
                for (var surface = 0; surface < keys.length; surface++) {
                    var actor = edit.getActor(surface);
                    if (actor == 0) {
                        continue;
                    }
                    var shape = brushShapes.get(actor);
                    var polygon = edit.getBrushPolygon(surface);
                    if (shape == null
                            || polygon < 0
                            || polygon >= shape.itemNames().size()) {
                        keys[surface] = -1;
                        continue;
                    }
                    var name = shape.itemNames().get(polygon).toLowerCase(Locale.ROOT);
                    var key = names.get(name);
                    if (key == null) {
                        key = names.size();
                        names.put(name, key);
                    }
                    keys[surface] = key;
                }
                surfaceItems = keys;
            }
            return surfaceItems;
        }
    }

    private synchronized BspBrushDelete brushDelete() throws IOException {
        if (brushDelete == null && brushFailure == null) {
            if (level == null) {
                brushFailure = structuralRestriction == null ? "its Level cannot be read" : structuralRestriction;
            } else {
                try {
                    brushDelete = BspBrushDelete.open(source, original, level.actors);
                } catch (IOException unavailable) {
                    brushFailure = unavailable.getMessage();
                }
            }
        }
        if (brushDelete == null) {
            throw new IOException("The BSP brushes of this map cannot be deleted: " + brushFailure);
        }
        return brushDelete;
    }

    private ModelBase modelBase(Set<Integer> brushes) throws IOException {
        var surfaces = surfaceEdit();
        var delete = brushDelete();
        var actors = level.actors;
        synchronized (modelBases) {
            var key = Set.copyOf(brushes);
            var base = modelBases.remove(key);
            if (base == null) {
                try {
                    var rebased = delete.createRebased(key, surfaces::getMappingSizes);
                    base = new ModelBase(
                            rebased,
                            BspSurfaceEdit.open(rebased, delete.modelExport),
                            BspLeafLightEdit.open(source, rebased, actors));
                } catch (RuntimeException failed) {
                    throw new IOException(
                            "The level model without these brushes cannot be built: "
                                    + (failed.getMessage() == null ? failed.toString() : failed.getMessage()),
                            failed);
                }
                while (modelBases.size() >= MODEL_BASES) {
                    modelBases.remove(modelBases.keySet().iterator().next());
                }
            }
            modelBases.put(key, base);
            return base;
        }
    }

    private void refreshDeletedSurfaces() {
        var result = new TreeSet<Integer>();
        for (var brush : deletedBrushes) {
            result.addAll(getBrushSurfaces(brush));
        }
        deletedSurfaces = Collections.unmodifiableSet(result);
    }

    private void refreshBrushDirty(int brush) {
        var saved = savedSnapshot != null && savedSnapshot.deletedBrushes.contains(brush);
        if (saved == deletedBrushes.contains(brush)) {
            dirtyActors.remove(brushKey(brush));
        } else {
            dirtyActors.add(brushKey(brush));
        }
    }

    private synchronized BspSurfaceEdit surfaceEdit() throws IOException {
        if (surfaceEdit == null && surfaceFailure == null) {
            try {
                surfaceEdit = BspSurfaceEdit.open(source, original);
            } catch (IOException unavailable) {
                surfaceFailure = unavailable.getMessage();
            }
        }
        if (surfaceEdit == null) {
            throw new IOException("The BSP surfaces of this map cannot be written: " + surfaceFailure);
        }
        return surfaceEdit;
    }

    private void refreshSurfaceDirty(int surface) {
        var saved = savedSnapshot == null ? null : savedSnapshot.surfaces.get(surface);
        var savedHidden = savedSnapshot != null && savedSnapshot.hiddenSurfaces.contains(surface);
        var savedMaterial = savedSnapshot == null ? null : savedSnapshot.surfaceMaterials.get(surface);
        var savedFlags = savedSnapshot == null ? null : savedSnapshot.surfaceFlags.get(surface);
        if (Objects.equals(saved, surfaceMappings.get(surface))
                && savedHidden == hiddenSurfaces.contains(surface)
                && Objects.equals(savedMaterial, surfaceMaterials.get(surface))
                && Objects.equals(savedFlags, surfaceFlags.get(surface))) {
            dirtyActors.remove(surfaceKey(surface));
        } else {
            dirtyActors.add(surfaceKey(surface));
        }
    }

    private int currentSurfaceFlags(int surface) throws IOException {
        var edited = surfaceFlags.get(surface);
        return edited != null ? edited : sourceSurfaceFlags(surface);
    }

    private int sourceSurfaceFlags(int surface) throws IOException {
        var edit = surfaceEdit();
        if (surface < 0 || surface >= edit.getSurfaceCount()) {
            throw new IllegalArgumentException("Unknown BSP surface " + surface);
        }
        return edit.getPolyFlags(surface) & BspSurfaceEdit.EDITABLE_FLAGS;
    }

    private String getSurfaceFlagsRestriction(int surface, int bits) {
        for (var flag : BspSurfaceFlag.values()) {
            var restriction = getSurfaceFlagRestriction(surface, flag, (bits & flag.getBit()) != 0);
            if (!restriction.isEmpty()) {
                return restriction;
            }
        }
        return "";
    }

    private BspSurfaceEdit.Edits surfaceEdits() {
        return new BspSurfaceEdit.Edits(surfaceMappings, hiddenSurfaces, surfaceMaterials, surfaceFlags);
    }

    private String materialPath(int reference) throws IOException {
        return reference == 0 ? "" : objectPath(reference);
    }

    private RestoredRecovery restoreRecoveryBundle(Bundle bundle) throws IOException {
        var baseline = savedSnapshot;
        var identities = new ArrayList<ActorState>();
        var originalDefinitions = new HashSet<Integer>();
        for (var definition : bundle.definitions()) {
            if (!definition.created()) {
                var state = actorStates.get(definition.path());
                if (state == null
                        || state.entry.index() != definition.exportIndex()
                        || !originalDefinitions.add(definition.exportIndex())) {
                    throw malformed("Invalid recovery source actor identity");
                }
                identities.add(state);
            } else {
                requireStructural();
                if (definition.exportIndex() <= original.exports.size()
                        || definition.exportIndex() > PackageBytes.MAX_ENTRIES) {
                    throw malformed("Invalid recovery created actor layout");
                }
                var classReference = definition.classReference();
                if (classReference != 0 && -classReference <= original.imports.size() && isPawnClass(classReference)
                        || isMovingCopy(definition)) {
                    identities.add(recoveryCreatedPawn(definition));
                    continue;
                }
                var scalars = new ArrayList<ScalarProperty>();
                var seen = new HashSet<String>();
                for (var scalar : definition.scalars()) {
                    var expectedSize = switch (scalar.type()) {
                        case 1 -> 1;
                        case 2, 4 -> 4;
                        case 3 -> 0;
                        default -> -1;
                    };
                    if (!COPY_SCALARS.contains(scalar.name().toLowerCase(Locale.ROOT))
                            || !seen.add(scalar.name().toLowerCase(Locale.ROOT))
                            || scalar.payload().length != expectedSize) {
                        throw malformed("Invalid recovery copied actor property");
                    }
                    scalars.add(new ScalarProperty(
                            scalar.name(),
                            scalar.type(),
                            scalar.truth(),
                            scalar.payload().clone()));
                }
                if (!Arrays.equals(definition.tail(), creationTail(scalars))) {
                    throw malformed("Invalid recovery created actor layout");
                }
                var entry = new ExportEntry(
                        definition.exportIndex(),
                        definition.classReference(),
                        0,
                        0,
                        definition.nameIndex(),
                        0x02070001,
                        0,
                        0,
                        0,
                        0,
                        0);
                var state = new ActorState(definition.path(), "StaticMeshActor", entry);
                state.created = true;
                state.meshReference = definition.meshReference();
                state.meshPath = definition.meshPath();
                state.template = definition.template();
                state.newTail = definition.tail().clone();
                state.scalarProperties = List.copyOf(scalars);
                identities.add(state);
            }
        }
        var decoded = new IdentityHashMap<Frame, Snapshot>();
        var createdLights = new HashMap<String, LightState>();
        var requested = new ArrayList<Frame>();
        requested.add(bundle.current());
        requested.add(bundle.saved());
        requested.addAll(bundle.history());
        for (var frame : requested) {
            if (decoded.containsKey(frame)) {
                continue;
            }
            restore(baseline);
            restoreRecoveryTables(frame.tables(), baseline);
            var used = new HashSet<Integer>();
            var createdIndices = new HashSet<Integer>();
            for (var value : frame.values()) {
                if (!used.add(value.definition())) {
                    throw malformed("Duplicate recovery actor state");
                }
                var state = identities.get(value.definition());
                if (state.created) {
                    validateRecoveryCreatedActor(state);
                    requireKnownNames(state.movementTagsOverride, state.moveEventOverride);
                    if (!createdIndices.add(state.entry.index())
                            || actorStates.putIfAbsent(state.path, state) != null) {
                        throw malformed("Duplicate recovery created actor slot");
                    }
                    for (var child : state.children) {
                        var name = recoveryName(child.entry().objectName());
                        var source = original.getClassName(original.exports.get(child.source() - 1));
                        if (!createdIndices.add(child.entry().index())
                                || !name.matches(Pattern.quote(source) + "[0-9]+")
                                || original.names.stream().anyMatch(name::equalsIgnoreCase)) {
                            throw malformed("Invalid recovery effect copy sub-object");
                        }
                    }
                } else if (!state.editable || state.baselineDeleted) {
                    throw malformed("Recovery modifies a protected source actor");
                }
                if (!state.created && value.deleted() != state.baselineDeleted) {
                    requireStructural();
                    if (level.actors.stream().limit(2).anyMatch(index -> index == state.entry.index())) {
                        throw malformed("Recovery deletes a reserved Level actor");
                    }
                }
                for (var reference : value.skins()) {
                    checkRecoveryReference(reference);
                    if (reference != 0
                            && !state.originalSkins.contains(reference)
                            && !MATERIAL_CLASSES.contains(referenceClass(reference))) {
                        throw malformed("Recovery skin is not a material");
                    }
                }
                validateFixedEdits(state, value.transform(), value.skins());
                state.current = value.transform();
                state.skins = new ArrayList<>(value.skins());
                state.deleted = value.deleted();
            }
            var lights = new HashSet<String>();
            for (var value : frame.lights()) {
                if (!lights.add(value.path())) {
                    throw malformed("Duplicate recovery light");
                }
                LightState state;
                if (value.template() != null) {
                    state = recoveryCreatedLight(value, createdLights);
                    state.moveEventOverride = value.moveEvent();
                    requireKnownNames(null, value.moveEvent());
                    if (!createdIndices.add(state.light.entry.index())
                            || lightStates.putIfAbsent(value.path(), state) != null) {
                        throw malformed("Duplicate recovery created light slot");
                    }
                } else {
                    state = lightStates.get(value.path());
                    if (state == null || state.created) {
                        throw malformed("Recovery changes a light that cannot take this colour");
                    }
                }
                var placement = value.placement() == null ? state.light.getPlacement() : value.placement();
                var shape =
                        value.aim() == null ? state.light.shape : value.aim().applyTo(state.light.shape);
                if (state.light.isCarryOnly()) {
                    var kept = new LightShape(
                            state.light.shape.type(),
                            state.light.shape.effect(),
                            state.light.shape.cone(),
                            shape.rotation());
                    if (!value.color().equals(state.light.color)
                            || !kept.equals(shape)
                            || value.deleted() && !state.created
                            || !state.light
                                    .getCarryRestriction(placement, newRegion(state, placement), shape.rotation())
                                    .isEmpty()) {
                        throw malformed("Recovery changes a carried light in a way that a carrier cannot");
                    }
                } else {
                    if (!placementRestriction(state, value.color(), placement).isEmpty()) {
                        throw malformed("Recovery changes a light that cannot take this colour or place");
                    }
                    if (!state.light
                            .getRestriction(value.color(), placement, null, shape, brightnessType, radiusType)
                            .isEmpty()) {
                        throw malformed("Recovery turns a light that cannot take this direction or cone");
                    }
                }
                if (value.deleted() && (level == null || !state.light.restriction.isEmpty())) {
                    throw malformed("Recovery deletes a light that cannot be deleted");
                }
                state.current = value.color();
                state.placement = placement;
                state.shape = shape;
                state.deleted = value.deleted();
            }
            for (var index = original.exports.size() + 1;
                    index <= original.exports.size() + createdIndices.size();
                    index++) {
                if (!createdIndices.contains(index)) {
                    throw malformed("Recovery created exports are not contiguous");
                }
            }
            for (var value : frame.surfaces()) {
                if (surfaceMappings.containsKey(value.surface())
                        || !getSurfaceMappingRestriction(value.surface(), value.mapping())
                                .isEmpty()
                        || value.mapping().equals(getSourceSurfaceMapping(value.surface()))) {
                    throw malformed("Recovery changes a BSP surface that cannot take this mapping");
                }
                surfaceMappings.put(value.surface(), value.mapping());
            }
            for (var surface : frame.hiddenSurfaces()) {
                if (hiddenSurfaces.contains(surface)
                        || !getSurfaceHideRestriction(surface).isEmpty()) {
                    throw malformed("Recovery hides a BSP surface that cannot be hidden");
                }
                hiddenSurfaces.add(surface);
            }
            for (var value : frame.surfaceMaterials()) {
                var material = new BspSurfaceEdit.Material(value.reference(), value.uSize(), value.vSize());
                checkRecoveryReference(value.reference());
                if (surfaceMaterials.containsKey(value.surface())
                        || !getSurfaceMaterialRestriction(value.surface()).isEmpty()
                        || !BspSurfaceEdit.getMaterialRestriction(material).isEmpty()
                        || !MATERIAL_CLASSES.contains(referenceClass(value.reference()))
                        || value.reference() == surfaceEdit().getMaterial(value.surface())) {
                    throw malformed("Recovery gives a BSP surface a material that it cannot take");
                }
                surfaceMaterials.put(value.surface(), material);
            }
            for (var value : frame.surfaceFlags()) {
                var bits = value.flags();
                if (surfaceFlags.containsKey(value.surface())
                        || (bits & ~BspSurfaceEdit.EDITABLE_FLAGS) != 0
                        || bits == sourceSurfaceFlags(value.surface())
                        || !getSurfaceFlagsRestriction(value.surface(), bits).isEmpty()) {
                    throw malformed("Recovery gives a BSP surface flags that it cannot take");
                }
                surfaceFlags.put(value.surface(), bits);
            }
            for (var brush : frame.deletedBrushes()) {
                if (deletedBrushes.contains(brush)
                        || !getBrushDeleteRestriction(brush).isEmpty()) {
                    throw malformed("Recovery deletes a BSP brush that cannot be deleted");
                }
                deletedBrushes.add(brush);
            }
            refreshDeletedSurfaces();
            decoded.put(frame, snapshot());
        }
        savedSnapshot = decoded.get(bundle.saved());
        savedImportCount = savedSnapshot.imports.size();
        restore(decoded.get(bundle.current()));
        var candidate = new PackageEditDocument(source, original.encode(buildBody(), source), clientRoot);
        verifySavedActors(candidate);
        return new RestoredRecovery(
                this, bundle.history().stream().map(decoded::get).toList());
    }

    private void restoreRecoveryTables(Tables table, Snapshot baseline) throws IOException {
        if ((long) names.size() + table.names().size() > PackageBytes.MAX_ENTRIES
                || (long) imports.size() + table.imports().size() > PackageBytes.MAX_ENTRIES) {
            throw malformed("Recovery package tables exceed limits");
        }
        var uniqueNames = new HashSet<>(names);
        for (var name : table.names()) {
            if (name.isEmpty() || name.indexOf('\0') >= 0 || !uniqueNames.add(name)) {
                throw malformed("Invalid recovery package name");
            }
            names.add(name);
        }
        for (var entry : table.imports()) {
            var packageName = recoveryName(entry.classPackage());
            var className = recoveryName(entry.className());
            recoveryName(entry.objectName());
            if (entry.outer() > 0 || entry.outer() < -imports.size()) {
                throw malformed("Invalid recovery import outer");
            }
            if (className.equals("Class") || className.equals("Package")) {
                if (!packageName.equals("Core")) {
                    throw malformed("Invalid recovery class package");
                }
            } else if (!packageName.equals("Engine")
                    || !className.equals("StaticMesh") && !MATERIAL_CLASSES.contains(className)) {
                throw malformed("Unsupported recovery import class");
            }
            var outer = entry.outer();
            var depth = 0;
            while (outer < 0) {
                if (++depth > 64) {
                    throw malformed("Recovery import nesting exceeds limit");
                }
                outer = imports.get(-outer - 1).outer();
            }
            imports.add(entry);
        }
        materialReferences.clear();
        for (var material : table.materials().values()) {
            checkRecoveryReference(material.objectIndex());
            if (!MATERIAL_CLASSES.contains(material.className())
                    || !referenceClass(material.objectIndex()).equals(material.className())
                    || !objectPath(material.objectIndex()).equalsIgnoreCase(material.sourcePath())) {
                throw malformed("Invalid recovery material identity");
            }
            if (materialReferences.put(material.sourcePath(), material) != null) {
                throw malformed("Duplicate recovery material identity");
            }
        }
        for (var original : baseline.materials.keySet()) {
            if (!materialReferences.containsKey(original)) {
                throw malformed("Recovery removed a source material registration");
            }
        }
    }

    private LightState recoveryCreatedLight(LightValue value, Map<String, LightState> known) throws IOException {
        requireStructural();
        var key = value.path() + '\n' + value.template() + '\n' + value.exportIndex();
        var state = known.get(key);
        if (state != null) {
            return state;
        }
        var template = lightStates.get(value.template());
        var prefix = original.packageName + ".";
        var name = value.path().startsWith(prefix) ? value.path().substring(prefix.length()) : "";
        if (template == null
                || template.created
                || !template.light.isCarryOnly()
                        && (!template.light.relocatable || !template.light.restriction.isEmpty())
                || !name.matches("Cruma" + template.light.className + "[0-9]+")
                || original.names.contains(name)
                || !names.contains(name)) {
            throw malformed("Invalid recovery created light");
        }
        if (value.exportIndex() <= original.exports.size() || value.exportIndex() > PackageBytes.MAX_ENTRIES) {
            throw malformed("Invalid recovery created light layout");
        }
        state = newLight(template, value.exportIndex(), name);
        known.put(key, state);
        return state;
    }

    private boolean isMovingCopy(Definition definition) {
        var template = actorStates.get(definition.template());
        return template != null && (template.nonStatic || template.emitter) && !template.created;
    }

    private ActorState recoveryCreatedPawn(Definition definition) throws IOException {
        var template = actorStates.get(definition.template());
        if (template == null
                || !template.pawn && !template.nonStatic && !template.emitter
                || template.created
                || !template.editable
                || template.entry.classIndex() != definition.classReference()
                || definition.meshReference() != (template.pawn ? 0 : template.meshReference)
                || !definition.meshPath().equals(template.meshPath)
                || !Arrays.equals(definition.tail(), template.tail)
                || !definition.scalars().isEmpty()) {
            throw malformed("Invalid recovery created pawn");
        }
        var entry = new ExportEntry(
                definition.exportIndex(),
                definition.classReference(),
                0,
                0,
                definition.nameIndex(),
                template.entry.flags(),
                0,
                0,
                0,
                0,
                0);
        var state = newCopy(template, entry, definition.path());
        if (definition.movementTags() != null && !MOVING_CLASSES.containsKey(template.className)
                || definition.moveEvent() != null && template.pawn) {
            throw malformed("Invalid recovery copy of a carrier");
        }
        state.movementTagsOverride = definition.movementTags();
        state.moveEventOverride = definition.moveEvent();
        if (template.emitter) {
            restoreEffectChildren(state, template, definition);
        } else if (!definition.children().isEmpty()) {
            throw malformed("Invalid recovery created pawn");
        }
        return state;
    }

    private void restoreEffectChildren(ActorState state, ActorState template, Definition definition)
            throws IOException {
        var expected = new ArrayList<Integer>();
        for (var reference : template.emitterReferences) {
            if (reference > 0 && !expected.contains(reference)) {
                expected.add(reference);
            }
        }
        if (definition.children().size() != expected.size()) {
            throw malformed("Invalid recovery effect copy");
        }
        var copies = new HashMap<Integer, Integer>();
        var children = new ArrayList<CreatedChild>();
        for (var i = 0; i < expected.size(); i++) {
            var child = definition.children().get(i);
            var source = original.exports.get(expected.get(i) - 1);
            if (child.source() != expected.get(i)) {
                throw malformed("Invalid recovery effect copy");
            }
            var entry = new ExportEntry(
                    definition.exportIndex() + 1 + i,
                    source.classIndex(),
                    0,
                    0,
                    child.nameIndex(),
                    source.flags(),
                    0,
                    0,
                    0,
                    0,
                    0);
            copies.put(child.source(), entry.index());
            children.add(new CreatedChild(entry, child.source()));
        }
        var references = new ArrayList<Integer>(template.emitterReferences.size());
        for (var reference : template.emitterReferences) {
            references.add(copies.getOrDefault(reference, reference));
        }
        state.children = List.copyOf(children);
        state.emitterReferences = List.copyOf(references);
    }

    private void validateRecoveryCreatedActor(ActorState state) throws IOException {
        if (state.copyOf != null) {
            var name = recoveryName(state.entry.objectName());
            if (!name.matches(Pattern.quote(original.getClassName(state.entry)) + "[0-9]+")
                    || original.names.stream().anyMatch(name::equalsIgnoreCase)
                    || !state.path.equals(original.packageName + "." + name)) {
                throw malformed("Invalid recovery created pawn name");
            }
            return;
        }
        var name = recoveryName(state.entry.objectName());
        if (!name.matches("CrumaStaticMeshActor[0-9]+")
                || original.names.contains(name)
                || !state.path.equals(original.packageName + "." + name)) {
            throw malformed("Invalid recovery created actor name");
        }
        checkRecoveryReference(state.entry.classIndex());
        checkRecoveryReference(state.meshReference);
        if (!objectPath(state.entry.classIndex()).equalsIgnoreCase("Engine.StaticMeshActor")
                || !referenceClass(state.entry.classIndex()).equals("Class")
                || !referenceClass(state.meshReference).equals("StaticMesh")
                || !objectPath(state.meshReference).equalsIgnoreCase(state.meshPath)) {
            throw malformed("Invalid recovery created actor asset identity");
        }
    }

    private String recoveryName(int index) throws IOException {
        if (index < 0 || index >= names.size()) {
            throw malformed("Invalid recovery name reference");
        }
        return names.get(index);
    }

    private void checkRecoveryReference(int reference) throws IOException {
        if (reference > original.exports.size() || (long) reference < -(long) imports.size()) {
            throw malformed("Invalid recovery object reference");
        }
    }

    private record ActorCheckpoint(ActorState state, ActorTransform transform, List<Integer> skins, boolean deleted) {}

    private void addSavedBeast(List<SavedBeast> result, int lod, int instance, Decoded decoded) throws IOException {
        var references = decoded.beastLightmaps();
        if (references.size() != 2
                || !BeastTextureCodec.isValidSize(decoded.beastWidth(), decoded.beastHeight())
                || !isSameSizeBeastTexture(references.get(0), 0, decoded.beastWidth(), decoded.beastHeight())
                || !isSameSizeBeastTexture(references.get(1), 1, decoded.beastWidth(), decoded.beastHeight())) {
            return;
        }
        result.add(new SavedBeast(
                lod, instance, decoded.beastWidth(), decoded.beastHeight(), references.get(0), references.get(1)));
    }

    private MeshReference meshReference(String meshSourcePath) throws IOException {
        for (var entry : original.exports) {
            if (original.getObjectPath(entry.classIndex()).equalsIgnoreCase("Engine.StaticMesh")
                    && original.getObjectPath(entry.index()).equalsIgnoreCase(meshSourcePath)) {
                return new MeshReference(entry.index(), original.getObjectPath(entry.index()));
            }
        }
        for (var i = 0; i < imports.size(); i++) {
            if (names.get(imports.get(i).className()).equals("StaticMesh")
                    && names.get(imports.get(i).classPackage()).equalsIgnoreCase("Engine")
                    && objectPath(-i - 1).equalsIgnoreCase(meshSourcePath)) {
                return new MeshReference(-i - 1, objectPath(-i - 1));
            }
        }
        throw malformed("Mesh is not referenced by this map: " + meshSourcePath);
    }

    private void requireStructural() throws IOException {
        if (level == null) {
            throw malformed(structuralRestriction);
        }
    }

    private ActorRecord createActor(
            int mesh,
            String meshPath,
            ActorTransform placement,
            List<Integer> skins,
            String template,
            List<ScalarProperty> scalarProperties)
            throws IOException {
        Objects.requireNonNull(placement);
        if (mesh == 0) {
            throw malformed("A new actor requires a StaticMesh");
        }
        var reusable = actorStates.values().stream()
                .filter(state -> state.created && state.deleted && state.copyOf == null)
                .min(Comparator.comparingInt(state -> state.entry.index()))
                .orElse(null);
        var next = reusable == null ? nextCreatedIndex() : reusable.entry.index();
        var suffix = next;
        var objectName = reusable == null ? "CrumaStaticMeshActor" + suffix : names.get(reusable.entry.objectName());
        while (reusable == null && names.contains(objectName)) {
            objectName = "CrumaStaticMeshActor" + ++suffix;
        }
        var classReference = importPath("Engine.StaticMeshActor", "Core", "Class");
        var entry = new ExportEntry(next, classReference, 0, 0, intern(objectName), 0x02070001, 0, 0, 0, 0, 0);
        var state = new ActorState(original.packageName + "." + objectName, "StaticMeshActor", entry);
        state.created = true;
        state.meshReference = mesh;
        state.meshPath = meshPath;
        state.template = template;
        state.scalarProperties = scalarProperties;
        state.current = placement;
        state.skins = new ArrayList<>(skins);
        state.newTail = creationTail(scalarProperties);
        actorStates.put(state.path, state);
        refreshDirty(state);
        return state.snapshot();
    }

    private ActorRecord createEffect(ActorState template, ActorTransform placement) throws IOException {
        Objects.requireNonNull(placement);
        validateFixedEdits(template, placement, template.originalSkins);
        var taken = new HashSet<String>();
        for (var name : names) {
            taken.add(name.toLowerCase(Locale.ROOT));
        }
        var className = original.getClassName(template.entry);
        var objectName = freeName(className, taken);
        var index = nextCreatedIndex();
        var entry = new ExportEntry(
                index, template.entry.classIndex(), 0, 0, intern(objectName), template.entry.flags(), 0, 0, 0, 0, 0);
        var state = newCopy(template, entry, original.packageName + "." + objectName);
        var copies = new LinkedHashMap<Integer, Integer>();
        var children = new ArrayList<CreatedChild>();
        for (var reference : template.emitterReferences) {
            if (reference <= 0 || copies.containsKey(reference)) {
                continue;
            }
            var source = original.exports.get(reference - 1);
            var childName = freeName(original.getClassName(source), taken);
            var child = new ExportEntry(
                    index + 1 + children.size(),
                    source.classIndex(),
                    0,
                    0,
                    intern(childName),
                    source.flags(),
                    0,
                    0,
                    0,
                    0,
                    0);
            copies.put(reference, child.index());
            children.add(new CreatedChild(child, reference));
        }
        var references = new ArrayList<Integer>(template.emitterReferences.size());
        for (var reference : template.emitterReferences) {
            references.add(copies.getOrDefault(reference, reference));
        }
        state.children = List.copyOf(children);
        state.emitterReferences = List.copyOf(references);
        state.current = placement;
        actorStates.put(state.path, state);
        refreshDirty(state);
        return state.snapshot();
    }

    private ActorRecord createPawn(ActorState template, ActorTransform placement, List<Integer> skins)
            throws IOException {
        Objects.requireNonNull(placement);
        var className = original.getClassName(template.entry);
        var taken = new HashSet<String>();
        for (var name : names) {
            taken.add(name.toLowerCase(Locale.ROOT));
        }
        var suffix = 0;
        var objectName = className + suffix;
        while (taken.contains(objectName.toLowerCase(Locale.ROOT))
                || actorStates.containsKey(original.packageName + "." + objectName)) {
            objectName = className + ++suffix;
        }
        var entry = new ExportEntry(
                nextCreatedIndex(),
                template.entry.classIndex(),
                0,
                0,
                intern(objectName),
                template.entry.flags(),
                0,
                0,
                0,
                0,
                0);
        var state = newCopy(template, entry, original.packageName + "." + objectName);
        state.current = placement;
        state.skins = new ArrayList<>(skins);
        actorStates.put(state.path, state);
        refreshDirty(state);
        return state.snapshot();
    }

    private byte[] creationTail(List<ScalarProperty> scalars) {
        if (original.fileVersion < BEAST_TAIL_VERSION) {
            return new byte[0];
        }
        var tail = new byte[2];
        for (var scalar : scalars) {
            for (var i = 0; i < BEAST_PROPERTIES.size(); i++) {
                if (scalar.name.equalsIgnoreCase(BEAST_PROPERTIES.get(i))) {
                    tail[i] = scalar.payload[0];
                }
            }
        }
        return tail;
    }

    private List<ScalarProperty> copiedScalars(ActorState state) throws IOException {
        if (state.created) {
            return state.scalarProperties;
        }
        if (!state.tailMatchesTags) {
            throw malformed("Cannot copy this actor: its native BeastMapScale bytes differ from its tagged values");
        }
        var properties = new ArrayList<ScalarProperty>();
        var used = new HashSet<String>();
        for (var tag : state.tags) {
            if (!COPY_SCALARS.contains(tag.name().toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (!used.add(tag.name().toLowerCase(Locale.ROOT))
                    || tag.arrayIndex() != 0
                    || tag.type() < 1
                    || tag.type() > 4
                    || tag.type() == 1 && tag.end() - tag.payload() != 1
                    || (tag.type() == 2 || tag.type() == 4) && tag.end() - tag.payload() != 4) {
                throw malformed("Unsupported copied scalar property " + tag.name());
            }
            var header = new Cursor(original.body, tag.start(), tag.end());
            header.readIndex();
            var truth = (header.readU8() & 128) != 0;
            properties.add(new ScalarProperty(
                    tag.name(), tag.type(), truth, Arrays.copyOfRange(original.body, tag.payload(), tag.end())));
        }
        return List.copyOf(properties);
    }

    private String objectPath(int reference) throws IOException {
        if (reference >= 0) {
            return original.getObjectPath(reference);
        }
        var entry = imports.get(-reference - 1);
        return entry.outer() == 0
                ? names.get(entry.objectName())
                : objectPath(entry.outer()) + "." + names.get(entry.objectName());
    }

    private String referenceClass(int reference) throws IOException {
        if (reference == 0) {
            throw malformed("Null asset reference");
        }
        return reference > 0
                ? original.getClassName(original.exports.get(reference - 1))
                : names.get(imports.get(-reference - 1).className());
    }

    private int importReference(PackageEditDocument source, int reference, String expectedClass) throws IOException {
        var type = source.referenceClass(reference);
        if (expectedClass == null ? !MATERIAL_CLASSES.contains(type) : !type.equals(expectedClass)) {
            throw malformed("Unsupported transferred asset class " + type);
        }
        var path = source.objectPath(reference);
        if (reference > 0
                && !source.original
                        .getObjectPath(
                                source.original.exports.get(reference - 1).classIndex())
                        .equalsIgnoreCase("Engine." + type)) {
            throw malformed("Transferred asset has a custom class");
        }
        if (reference > 0) {
            validateAssetOuter(
                    source.original, source.original.exports.get(reference - 1).outer());
        }
        if (reference < 0
                && !source.names
                        .get(source.imports.get(-reference - 1).classPackage())
                        .equalsIgnoreCase("Engine")) {
            throw malformed("Transferred asset has a custom class package");
        }
        return importPath(path, "Engine", type);
    }

    private int importPath(String path, String classPackage, String className) throws IOException {
        for (var entry : original.exports) {
            if (original.getObjectPath(entry.index()).equalsIgnoreCase(path)
                    && original.getClassName(entry).equals(className)) {
                return entry.index();
            }
        }
        var parts = path.split("\\.");
        if (parts.length < 2) {
            throw malformed("Unsupported asset path " + path);
        }
        var outer = 0;
        for (var i = 0; i < parts.length; i++) {
            var last = i == parts.length - 1;
            var entry = new ImportEntry(
                    intern(last ? classPackage : "Core"),
                    intern(last ? className : "Package"),
                    outer,
                    intern(parts[i]));
            var found = imports.indexOf(entry);
            if (found < 0) {
                imports.add(entry);
                importsChanged = true;
                found = imports.size() - 1;
            }
            outer = -found - 1;
        }
        return outer;
    }

    private MaterialReference importMaterial(PackageBytes asset, String materialSourcePath) throws IOException {
        var existing = materialReferences.get(materialSourcePath);
        if (existing != null) {
            return existing;
        }
        ExportEntry selected = null;
        for (var entry : asset.exports) {
            if (asset.getObjectPath(entry.index()).equalsIgnoreCase(materialSourcePath)) {
                if (selected != null) {
                    throw malformed("Ambiguous material path " + materialSourcePath);
                }
                selected = entry;
            }
        }
        if (selected == null) {
            throw malformed("Material export not found: " + materialSourcePath);
        }
        var className = asset.getClassName(selected);
        if (!MATERIAL_CLASSES.contains(className)) {
            throw malformed("Object is not a supported material: " + className);
        }
        if (!asset.getObjectPath(selected.classIndex()).equalsIgnoreCase("Engine." + className)) {
            throw malformed("Material is not an Engine material class");
        }
        validateAssetOuter(asset, selected.outer());
        var canonical = asset.getObjectPath(selected.index());
        var segments = canonical.split("\\.");
        if (segments.length < 2 || !segments[0].equalsIgnoreCase(asset.packageName)) {
            throw malformed("Material has an unsupported outer chain");
        }
        var outer = 0;
        for (var i = 0; i < segments.length; i++) {
            var last = i == segments.length - 1;
            var next = new ImportEntry(
                    intern(last ? "Engine" : "Core"), intern(last ? className : "Package"), outer, intern(segments[i]));
            var found = imports.indexOf(next);
            if (found < 0) {
                imports.add(next);
                importsChanged = true;
                found = imports.size() - 1;
            }
            outer = -found - 1;
        }
        var result = new MaterialReference(canonical, className, outer);
        materialReferences.put(canonical, result);
        return result;
    }

    private void refreshDirty(ActorState state) {
        var saved = savedSnapshot == null ? null : savedSnapshot.actors.get(state.path);
        if (saved != null
                && saved.state == state
                && state.current.equals(saved.transform)
                && state.skins.equals(saved.skins)
                && state.deleted == saved.deleted) {
            dirtyActors.remove(state.path);
        } else {
            dirtyActors.add(state.path);
        }
    }

    private void refreshDirty(LightState state) {
        var saved = savedSnapshot == null ? null : savedSnapshot.lights.get(state.light.path);
        if (state.getCheckpoint().equals(saved)) {
            dirtyActors.remove(state.light.path);
        } else {
            dirtyActors.add(state.light.path);
        }
    }

    private void verifySavedActors(PackageEditDocument verified) throws IOException {
        for (var state : lightStates.values()) {
            var written = verified.lightStates.get(verified.original.getObjectPath(state.light.entry.index()));
            if (state.deleted) {
                if (written != null) {
                    throw malformed("Deleted light is still in the Level: " + state.light.path);
                }
                continue;
            }
            if (state.created && (written == null || !written.light.className.equals(state.light.className))) {
                throw malformed("Written new light did not round-trip: " + state.light.path);
            }
            var region = newRegion(state, state.placement);
            if (written == null
                    || !written.light.color.equals(state.current)
                    || !written.light.getPlacement().equals(state.placement)
                    || !written.light.shape.equals(state.shape)
                    || !Objects.equals(written.light.region, region == null ? state.light.region : region)) {
                throw malformed("Written light did not round-trip: " + state.light.path);
            }
        }
        var lists = leafLightLists(deletedBrushes);
        if (lists == null && !deletedBrushes.isEmpty()) {
            lists = modelBase(deletedBrushes).lights().getSaved();
        }
        if (lists != null) {
            var listEdit = deletedBrushes.isEmpty()
                    ? leafLightEdit
                    : modelBase(deletedBrushes).lights();
            var entry = verified.original.exports.get(listEdit.modelExport - 1);
            var payload = Arrays.copyOfRange(
                    verified.original.body, entry.serialOffset(), entry.serialOffset() + entry.serialSize());
            if (!BspLeafLightEdit.read(verified.original, payload, entry.serialOffset(), entry.flags())
                    .equals(lists)) {
                throw malformed("Written BSP leaf light lists did not round-trip");
            }
        }
        var surfaces = surfaceEdits();
        if (!surfaces.isEmpty() || !deletedBrushes.isEmpty()) {
            var expectedModel = deletedBrushes.isEmpty()
                    ? surfaceEdit
                    : modelBase(deletedBrushes).surfaces();
            var written = BspSurfaceEdit.open(verified.original, expectedModel.modelExport);
            if (written.getSurfaceCount() != expectedModel.getSurfaceCount()) {
                throw malformed("Written BSP surfaces did not round-trip");
            }
            for (var surface = 0; surface < written.getSurfaceCount(); surface++) {
                var expected = surfaceMappings.getOrDefault(surface, expectedModel.getMapping(surface));
                if (!written.getMapping(surface).equals(expected)) {
                    throw malformed("Written BSP surface mapping did not round-trip: " + surface);
                }
                var material = surfaceMaterials.get(surface);
                if (written.getMaterial(surface)
                        != (material == null ? expectedModel.getMaterial(surface) : material.reference())) {
                    throw malformed("Written BSP surface material did not round-trip: " + surface);
                }
                var polyFlags = expectedModel.getPolyFlags(surface);
                var flags = surfaceFlags.get(surface);
                if (flags != null) {
                    polyFlags = polyFlags & ~BspSurfaceEdit.EDITABLE_FLAGS | flags;
                }
                if (hiddenSurfaces.contains(surface)) {
                    polyFlags |= BspSurfaceEdit.INVISIBLE;
                }
                if (written.getPolyFlags(surface) != polyFlags) {
                    throw malformed("Written BSP surface flags did not round-trip: " + surface);
                }
            }
            var problem = written.getSectionProblem(surfaces.moved());
            if (!problem.isEmpty()) {
                throw malformed("Written BSP render sections did not round-trip: " + problem);
            }
        }
        if (!deletedBrushes.isEmpty()) {
            var problem = brushDelete().getWrittenProblem(verified.source, verified.original, deletedBrushes);
            if (!problem.isEmpty()) {
                throw malformed("Written deleted BSP brushes did not round-trip: " + problem);
            }
        }
        for (var state : actorStates.values()) {
            var exported = verified.original.exports.get(state.entry.index() - 1);
            var path = verified.original.getObjectPath(exported.index());
            var candidate = verified.actorStates.get(path);
            if (candidate == null
                    || !candidate.current.equals(state.current)
                    || !candidate.skins.equals(state.skins)
                    || candidate.deleted != state.deleted
                    || !candidate.meshPath.equalsIgnoreCase(state.meshPath)
                    || !Arrays.equals(candidate.tail, state.created ? state.newTail : state.tail)) {
                throw malformed("Written actor did not round-trip: " + state.path);
            }
            if (!state.created && state.copyOf == null && !state.transform.equals(state.current)) {
                verifyDecorations(state, verified, candidate);
            }
            if (state.pawn) {
                verifyPawn(state, verified, candidate);
            } else if (state.emitter) {
                verifyEmitter(state, verified, candidate);
            } else if (state.nonStatic) {
                verifyMoving(state, verified, candidate);
            } else if (state.created) {
                var copied = verified.copiedScalars(candidate);
                if (copied.size() != state.scalarProperties.size()) {
                    throw malformed("Copied actor properties did not round-trip");
                }
                for (var i = 0; i < copied.size(); i++) {
                    var expected = state.scalarProperties.get(i);
                    var actual = copied.get(i);
                    if (!expected.name.equals(actual.name)
                            || expected.type != actual.type
                            || expected.truth != actual.truth
                            || !Arrays.equals(expected.payload, actual.payload)) {
                        throw malformed("Copied actor property did not round-trip: " + expected.name);
                    }
                }
            }
        }
    }

    private void verifyDecorations(ActorState state, PackageEditDocument verified, ActorState candidate)
            throws IOException {
        for (var name : DECORATION_DATA) {
            var saved = tag(state, name);
            var written = verified.tag(candidate, name);
            if ((saved == null) != (written == null)) {
                throw malformed("Written decorations did not round-trip: " + state.path);
            }
            if (saved != null
                    && !Arrays.equals(
                            carryDecorations(state, saved, state.current),
                            Arrays.copyOfRange(verified.original.body, written.payload(), written.end()))) {
                throw malformed("Written decorations did not round-trip: " + state.path);
            }
        }
    }

    private void verifyEmitter(ActorState state, PackageEditDocument verified, ActorState candidate)
            throws IOException {
        var base = state.copyOf != null ? state.copyOf : state;
        var region = getMovedRegion(state);
        var frame = Arrays.copyOfRange(original.body, base.entry.serialOffset(), base.frameEnd);
        var written = Arrays.copyOfRange(verified.original.body, candidate.entry.serialOffset(), candidate.frameEnd);
        if (!candidate.emitter
                || candidate.emitterReferences.size() != state.emitterReferences.size()
                || !Arrays.equals(frame, written)
                || !Objects.equals(candidate.region, region != null ? region : base.region)) {
            throw malformed("Written effect did not round-trip: " + state.path);
        }
    }

    private void verifyPawn(ActorState state, PackageEditDocument verified, ActorState candidate) throws IOException {
        var base = state.copyOf != null ? state.copyOf : state;
        var region = getMovedRegion(state);
        var frame = Arrays.copyOfRange(original.body, base.entry.serialOffset(), base.frameEnd);
        var written = Arrays.copyOfRange(verified.original.body, candidate.entry.serialOffset(), candidate.frameEnd);
        var moved = !base.transform.location().equals(state.current.location());
        var place = tag(base, "ColLocation") == null
                ? null
                : moved ? state.current.location() : readVector(base, "ColLocation", Vector3.ZERO);
        if (!candidate.pawn
                || !Arrays.equals(frame, written)
                || !Objects.equals(candidate.region, region != null ? region : base.region)
                || place != null
                        && !verified.readVector(candidate, "ColLocation", Vector3.ZERO)
                                .equals(place)) {
            throw malformed("Written pawn did not round-trip: " + state.path);
        }
    }

    private void verifyMoving(ActorState state, PackageEditDocument verified, ActorState candidate) throws IOException {
        var base = state.copyOf != null ? state.copyOf : state;
        var frame = Arrays.copyOfRange(original.body, base.entry.serialOffset(), base.frameEnd);
        var written = Arrays.copyOfRange(verified.original.body, candidate.entry.serialOffset(), candidate.frameEnd);
        var dropped = candidate.tags.stream()
                .map(tag -> tag.name().toLowerCase(Locale.ROOT))
                .anyMatch(name -> state.droppedTags.contains(name)
                        && !(name.equals("l2movementtag") && state.movementTagsOverride != null));
        if (state.movementTagsOverride != null
                && !verified.carryFacts(candidate).movementTags().equals(state.movementTagsOverride)) {
            dropped = true;
        }
        var restart = getRestartTag(state);
        var cleared = restart == null || !verified.isTrue(candidate, restart.name());
        if (!candidate.nonStatic || !Arrays.equals(frame, written) || dropped || !cleared) {
            throw malformed("Written moving mesh did not round-trip: " + state.path);
        }
    }

    private boolean isTrue(ActorState state, String name) throws IOException {
        var tag = tag(state, name);
        return tag != null && tag.type() == 3 && (original.body[infoPosition(tag)] & 0x80) != 0;
    }

    private boolean savesFalse(ActorState state, String name) {
        try {
            var tag = tag(state, name);
            return tag != null && tag.type() == 3 && (original.body[infoPosition(tag)] & 0x80) == 0;
        } catch (IOException unreadable) {
            return false;
        }
    }

    private ActorState requireEditable(String path) {
        var state = actorStates.get(path);
        if (state == null || state.deleted) {
            throw new IllegalArgumentException("Unknown actor " + path);
        }
        if (!state.editable) {
            throw new IllegalArgumentException(state.restriction);
        }
        return state;
    }

    private void validateFixedEdits(ActorState state, ActorTransform value, List<Integer> skins) {
        if (state.pawn && !skins.equals((state.copyOf != null ? state.copyOf : state).originalSkins)) {
            throw new IllegalArgumentException(PAWN_LOOK);
        }
        if (state.emitter) {
            var base = state.copyOf != null ? state.copyOf : state;
            if (Float.compare(base.transform.drawScale(), value.drawScale()) != 0
                    || !base.transform.drawScale3d().equals(value.drawScale3d())) {
                throw new IllegalArgumentException(EFFECT_SCALE);
            }
            if (!base.transform.prePivot().equals(value.prePivot()) || !skins.equals(base.originalSkins)) {
                throw new IllegalArgumentException(EFFECT_KEEPS);
            }
        }
        if (!state.created
                && state.copyOf == null
                && !state.transform.equals(value)
                && state.tags.stream().anyMatch(tag -> DECORATION_DATA.stream().anyMatch(tag.name()::equalsIgnoreCase))
                && (hasZeroScale(state.transform) || hasZeroScale(value))) {
            throw new IllegalArgumentException(DECORATIONS_SCALE);
        }
        if (state.relocatable || state.created) {
            return;
        }
        var required = new ArrayList<String>();
        if (!state.transform.location().equals(value.location())) {
            required.add("Location");
        }
        if (!state.transform.rotation().equals(value.rotation())) {
            required.add("Rotation");
        }
        if (Float.compare(state.transform.drawScale(), value.drawScale()) != 0) {
            required.add("DrawScale");
        }
        if (!state.transform.drawScale3d().equals(value.drawScale3d())) {
            required.add("DrawScale3D");
        }
        if (!state.transform.prePivot().equals(value.prePivot())) {
            required.add("PrePivot");
        }
        for (var name : required) {
            if (state.tags.stream().noneMatch(tag -> tag.name().equalsIgnoreCase(name))) {
                throw new IllegalArgumentException("Cannot add " + name + ": this actor has an unaudited native tail");
            }
        }
        if (!skins.equals(state.originalSkins)) {
            var tag = state.tags.stream()
                    .filter(valueTag -> valueTag.name().equalsIgnoreCase("Skins"))
                    .findFirst();
            var payload = new ByteArrayOutputStream();
            writeIndex(payload, skins.size());
            for (var reference : skins) {
                writeIndex(payload, reference);
            }
            if (tag.isEmpty() || payload.size() != tag.get().end() - tag.get().payload()) {
                throw new IllegalArgumentException("Cannot resize Skins: this actor has an unaudited native tail");
            }
        }
    }

    private byte[] buildBody() throws IOException {
        return buildBody(null, null);
    }

    private byte[] buildBody(NativePackageChanges nativeChanges) throws IOException {
        return buildBody(nativeChanges, null);
    }

    private byte[] buildBody(NativePackageChanges nativeChanges, ActorLightingChanges lighting) throws IOException {
        return buildBody(nativeChanges, lighting, surfaceEdits(), deletedBrushes);
    }

    private byte[] buildBody(
            NativePackageChanges nativeChanges,
            ActorLightingChanges lighting,
            BspSurfaceEdit.Edits surfaces,
            Set<Integer> brushes)
            throws IOException {
        var created = new ArrayList<ExportEntry>();
        var changed = new LinkedHashMap<Integer, byte[]>();
        for (var state : actorStates.values()) {
            if (state.created) {
                created.add(state.entry);
                for (var child : state.children) {
                    created.add(child.entry());
                    var source = original.exports.get(child.source() - 1);
                    changed.put(
                            child.entry().index(),
                            Arrays.copyOfRange(
                                    original.body, source.serialOffset(), source.serialOffset() + source.serialSize()));
                }
            }
        }
        for (var state : lightStates.values()) {
            if (state.created) {
                created.add(state.light.entry);
            }
        }
        created.sort(Comparator.comparingInt(ExportEntry::index));
        var plan = lighting == null || lighting.isEmpty() ? LightingPlan.EMPTY : planLighting(lighting, created.size());
        try {
            for (var state : actorStates.values()) {
                state.lightingInstance = plan.actorReferences().getOrDefault(state.path, 0);
                if (state.created
                        || !state.current.equals(state.transform)
                        || !state.skins.equals(state.originalSkins)
                        || state.lightingInstance != 0) {
                    changed.put(state.entry.index(), buildActor(state));
                }
            }
        } finally {
            for (var state : actorStates.values()) {
                state.lightingInstance = 0;
            }
        }
        for (var state : lightStates.values()) {
            if (state.created
                    || !state.deleted
                            && (!state.current.equals(state.light.color)
                                    || state.isMoved()
                                    || state.isTurned()
                                    || state.isCarried())) {
                changed.put(
                        state.light.entry.index(),
                        state.light.write(
                                original,
                                state.current,
                                state.placement,
                                newRegion(state, state.placement),
                                state.shape,
                                brightnessType,
                                radiusType,
                                this::intern,
                                state.moveEventOverride == null ? -1 : intern(state.moveEventOverride)));
            }
        }
        changed.putAll(plan.bodies());
        var removed = new HashSet<Integer>();
        for (var state : actorStates.values()) {
            if (!state.created && state.deleted != state.baselineDeleted) {
                removed.add(state.entry.index());
            }
        }
        for (var state : lightStates.values()) {
            if (!state.created && state.deleted) {
                removed.add(state.light.entry.index());
            }
        }
        var added = appendedActors();
        if (!removed.isEmpty() || !added.isEmpty()) {
            requireStructural();
            changed.put(level.entry.index(), level.rewrite(removed, added));
        }
        if (nativeChanges != null) {
            nativeChanges.contribute(changed);
        }
        var base = brushes.isEmpty() ? null : modelBase(brushes);
        var listEdit = base == null ? leafLightEdit : base.lights();
        var surfaceWriter = base == null ? surfaceEdit : base.surfaces();
        var lists = leafLightLists(brushes);
        if (lists == null && base != null) {
            lists = base.lights().getSaved();
        }
        if (lists != null) {
            var model = original.exports.get(listEdit.modelExport - 1);
            var written = changed.get(model.index());
            if (written == null) {
                changed.put(model.index(), listEdit.getPayload(lists, model.serialOffset()));
            } else if (!BspLeafLightEdit.read(original, written, model.serialOffset(), model.flags())
                    .equals(lists)) {
                throw malformed("A native level Model change does not carry the lists of the edited lights");
            } else if (base != null
                    && surfaces.isEmpty()
                    && !surfaceWriter.carries(written, model.serialOffset(), surfaces)) {
                throw malformed("A native level Model change does not carry the deleted BSP brushes");
            }
        }
        var surfaceModel = surfaces.isEmpty() ? null : original.exports.get(surfaceWriter.modelExport - 1);
        byte[] surfaceBase = null;
        if (surfaceModel != null) {
            var written = changed.get(surfaceModel.index());
            if (nativeChanges != null && nativeChanges.contains(surfaceModel.index())) {
                if (!surfaceWriter.carries(written, surfaceModel.serialOffset(), surfaces)) {
                    throw malformed("A native level Model change does not carry the edited BSP surfaces");
                }
            } else {
                surfaceBase = written != null
                        ? written
                        : Arrays.copyOfRange(
                                original.body,
                                surfaceModel.serialOffset(),
                                surfaceModel.serialOffset() + surfaceModel.serialSize());
                changed.put(
                        surfaceModel.index(),
                        surfaceWriter.apply(
                                surfaceBase, surfaceModel.serialOffset(), surfaceModel.serialOffset(), surfaces));
            }
        }
        if (changed.isEmpty() && imports.size() == original.imports.size()) {
            return original.body.clone();
        }
        var out = new ByteArrayOutputStream();
        out.writeBytes(original.body);
        var offsets = new LinkedHashMap<Integer, Integer>();
        var patched = original.body.clone();
        for (var change : changed.entrySet()) {
            if (change.getKey() > original.exports.size()) {
                offsets.put(change.getKey(), out.size());
                out.writeBytes(plan.placed(change.getKey(), change.getValue(), out.size()));
                continue;
            }
            var entry = original.exports.get(change.getKey() - 1);
            if (change.getValue().length == entry.serialSize()) {
                System.arraycopy(
                        plan.placed(entry.index(), change.getValue(), entry.serialOffset()),
                        0,
                        patched,
                        entry.serialOffset(),
                        entry.serialSize());
            } else {
                if (nativeChanges != null && nativeChanges.contains(entry.index())) {
                    if (!nativeChanges.mayRelocate(entry.index())) {
                        throw malformed("Native export has no audited relocation policy");
                    }
                    var placed = nativeChanges.getPayloadAt(entry.index(), out.size());
                    if (placed.length != change.getValue().length) {
                        throw malformed("A relocated native export changed its size");
                    }
                    change.setValue(placed);
                } else if (plan.bodies().containsKey(entry.index())) {
                } else if (surfaceBase != null && entry.index() == surfaceModel.index()) {
                    change.setValue(surfaceWriter.apply(surfaceBase, entry.serialOffset(), out.size(), surfaces));
                } else if (lists != null && entry.index() == listEdit.modelExport) {
                    change.setValue(listEdit.getPayload(lists, out.size()));
                } else if (level == null || entry.index() != level.entry.index()) {
                    var light = lightStates.values().stream()
                            .filter(candidate -> candidate.light.entry.index() == entry.index())
                            .findFirst();
                    if (light.isPresent()) {
                        if (!light.orElseThrow().light.relocatable) {
                            throw malformed("Cannot relocate a light with a native tail");
                        }
                    } else {
                        var state = actorStates.values().stream()
                                .filter(actor -> actor.entry.index() == entry.index())
                                .findFirst()
                                .orElseThrow();
                        if (!state.relocatable) {
                            throw malformed("Cannot relocate an actor with an unaudited native tail");
                        }
                    }
                }
                offsets.put(entry.index(), out.size());
                out.writeBytes(plan.placed(entry.index(), change.getValue(), out.size()));
            }
        }
        var newNameOffset = -1;
        if (names.size() != original.names.size()) {
            newNameOffset = out.size();
            out.write(original.body, original.nameOffset, original.nameEnd - original.nameOffset);
            for (var i = original.names.size(); i < names.size(); i++) {
                PackageBytes.writeName(out, names.get(i));
            }
        }
        var newImportOffset = -1;
        if (imports.size() != original.imports.size()) {
            newImportOffset = out.size();
            out.write(original.body, original.importOffset, original.importEnd - original.importOffset);
            for (var i = original.imports.size(); i < imports.size(); i++) {
                var entry = imports.get(i);
                writeIndex(out, entry.classPackage());
                writeIndex(out, entry.className());
                writeInt(out, entry.outer());
                writeIndex(out, entry.objectName());
            }
        }
        var newExportOffset = -1;
        if (!offsets.isEmpty()) {
            newExportOffset = out.size();
            for (var entry : original.exports) {
                if (offsets.containsKey(entry.index())) {
                    out.write(original.body, entry.rowStart(), entry.sizeOffset() - entry.rowStart());
                    writeIndex(out, changed.get(entry.index()).length);
                    writeIndex(out, offsets.get(entry.index()));
                } else {
                    out.write(original.body, entry.rowStart(), entry.rowEnd() - entry.rowStart());
                }
            }
            for (var entry : created) {
                writeIndex(out, entry.classIndex());
                writeIndex(out, 0);
                writeInt(out, 0);
                writeIndex(out, entry.objectName());
                writeInt(out, entry.flags());
                writeIndex(out, changed.get(entry.index()).length);
                writeIndex(out, offsets.get(entry.index()));
            }
            for (var entry : plan.appended()) {
                writeIndex(out, entry.classIndex());
                writeIndex(out, 0);
                writeInt(out, 0);
                writeIndex(out, entry.objectName());
                writeInt(out, entry.flags());
                writeIndex(out, changed.get(entry.index()).length);
                writeIndex(out, offsets.get(entry.index()));
            }
        }
        var result = out.toByteArray();
        System.arraycopy(patched, 0, result, 0, patched.length);
        var header = littleEndian(result);
        if (newNameOffset >= 0) {
            header.putInt(12, names.size());
            header.putInt(16, newNameOffset);
            if (original.lastGenerationNameOffset >= 0) {
                header.putInt(original.lastGenerationNameOffset, names.size());
            }
        }
        if (newImportOffset >= 0) {
            header.putInt(28, imports.size());
            header.putInt(32, newImportOffset);
        }
        if (newExportOffset >= 0) {
            var exportCount =
                    original.exports.size() + created.size() + plan.appended().size();
            header.putInt(24, newExportOffset);
            header.putInt(20, exportCount);
            if (original.lastGenerationNameOffset >= 0) {
                header.putInt(original.lastGenerationNameOffset - 4, exportCount);
            }
        }
        return result;
    }

    private record LightingPlan(
            Map<Integer, byte[]> bodies,
            List<ExportEntry> appended,
            Map<String, Integer> actorReferences,
            Map<Integer, IntFunction<byte[]>> positioned) {
        static final LightingPlan EMPTY = new LightingPlan(Map.of(), List.of(), Map.of(), Map.of());

        byte[] placed(int index, byte[] body, int offset) {
            var builder = positioned.get(index);
            if (builder == null) {
                return body;
            }
            var result = builder.apply(offset);
            if (result.length != body.length) {
                throw new IllegalStateException("A placed Beast texture changed its size");
            }
            return result;
        }
    }

    @FunctionalInterface
    private interface TextureReplacement {
        void accept(int index, BeastTexture texture) throws IOException;
    }

    @FunctionalInterface
    private interface TextureAppender {
        int apply(String name, BeastTexture texture) throws IOException;
    }

    private LightingPlan planLighting(ActorLightingChanges lighting, int createdCount) throws IOException {
        var owners = new HashMap<Integer, Integer>();
        for (var state : actorStates.values()) {
            if (!state.created) {
                var reference = instanceReference(state);
                if (reference != 0) {
                    owners.merge(reference, 1, Integer::sum);
                }
            }
        }
        var bodies = new LinkedHashMap<Integer, byte[]>();
        var appended = new ArrayList<ExportEntry>();
        var references = new LinkedHashMap<String, Integer>();
        var positioned = new LinkedHashMap<Integer, IntFunction<byte[]>>();
        var used = new HashSet<Integer>();
        var next = new int[] {original.exports.size() + createdCount + 1};
        var flags = instanceFlags();
        var none = intern("None");
        var classReference = new int[1];
        var pendingNames = new HashSet<String>();
        Function<Integer, Integer> allocate = ignored -> {
            if (classReference[0] == 0) {
                try {
                    classReference[0] = importPath("Engine.StaticMeshInstance", "Core", "Class");
                } catch (IOException failure) {
                    throw new IllegalStateException(failure.getMessage(), failure);
                }
            }
            var index = next[0]++;
            var suffix = index;
            var name = "StaticMeshInstance" + suffix;
            while (names.contains(name) || pendingNames.contains(name)) {
                name = "StaticMeshInstance" + ++suffix;
            }
            pendingNames.add(name);
            appended.add(new ExportEntry(index, classReference[0], 0, 0, intern(name), flags, 0, 0, 0, 0, 0));
            return index;
        };
        ExportEntry[] templateEntry = new ExportEntry[1];
        Template[] template = new Template[1];
        TextureReplacement replaceTexture = (index, texture) -> {
            if (index <= 0 || index > original.exports.size() || !isBeastTexture(original.exports.get(index - 1))) {
                throw malformed("Beast texture replacement does not name a Beast lightmap texture export");
            }
            var entry = original.exports.get(index - 1);
            var own = BeastTextureCodec.readTemplate(original, entry);
            var saved = BeastTextureCodec.read(original, entry);
            if (saved.width() != texture.width() || saved.height() != texture.height()) {
                throw malformed("A replaced Beast texture must keep its size");
            }
            if (bodies.containsKey(index)) {
                throw malformed("A Beast texture is replaced twice");
            }
            positioned.put(
                    index,
                    offset -> BeastTextureCodec.writeBody(
                            own, texture.width(), texture.height(), texture.mips(), offset));
            bodies.put(
                    index,
                    BeastTextureCodec.writeBody(
                            own, texture.width(), texture.height(), texture.mips(), entry.serialOffset()));
        };
        TextureAppender appendTexture = (name, texture) -> {
            if (template[0] == null) {
                for (var candidate : original.exports) {
                    if (isBeastTexture(candidate)) {
                        templateEntry[0] = candidate;
                        template[0] = BeastTextureCodec.readTemplate(original, candidate);
                        break;
                    }
                }
                if (template[0] == null) {
                    throw malformed("The map has no Beast lightmap texture to copy the texture layout from");
                }
            }
            var layout = template[0];
            var index = next[0]++;
            var unique = name;
            var suffix = 1;
            while (names.contains(unique) || pendingNames.contains(unique)) {
                unique = name + "_" + suffix++;
            }
            pendingNames.add(unique);
            appended.add(new ExportEntry(
                    index,
                    templateEntry[0].classIndex(),
                    0,
                    0,
                    intern(unique),
                    templateEntry[0].flags() & ~0x02000000,
                    0,
                    0,
                    0,
                    0,
                    0));
            positioned.put(
                    index,
                    offset -> BeastTextureCodec.writeBody(
                            layout, texture.width(), texture.height(), texture.mips(), offset));
            bodies.put(
                    index, BeastTextureCodec.writeBody(layout, texture.width(), texture.height(), texture.mips(), 0));
            return index;
        };
        for (var change : lighting.getActors().entrySet()) {
            var state = actorStates.get(change.getKey());
            if (state == null || state.deleted) {
                throw malformed("Rebuilt lighting names an actor that is not in the map: " + change.getKey());
            }
            if (state.nonStatic) {
                throw malformed("A mesh that is not static keeps no baked light: " + change.getKey());
            }
            var actor = change.getValue();
            var existing = state.created ? 0 : instanceReference(state);
            Decoded decoded = null;
            if (existing > 0
                    && owners.getOrDefault(existing, 0) == 1
                    && isInstanceExport(existing)
                    && used.add(existing)) {
                decoded = StaticMeshInstanceCodec.read(original, original.exports.get(existing - 1));
            }
            int lod0;
            if (decoded != null) {
                lod0 = existing;
            } else {
                if (!state.created && !state.editable) {
                    throw malformed("Cannot add lighting to " + state.path + ": " + state.restriction);
                }
                lod0 = allocate.apply(0);
                references.put(state.path, lod0);
            }
            var lod1 = 0;
            if (actor.lod1() != null) {
                lod1 = decoded != null && claimReusable(decoded.lod1(), owners, used)
                        ? decoded.lod1()
                        : allocate.apply(1);
            }
            var lod2 = 0;
            if (actor.lod2SharesLod1()) {
                lod2 = lod1;
            } else if (actor.lod2() != null) {
                lod2 = decoded != null && claimReusable(decoded.lod2(), owners, used)
                        ? decoded.lod2()
                        : allocate.apply(2);
            }
            var beast0 = beastReferences(lod0, 0, actor.lod0(), appended, replaceTexture, appendTexture);
            bodies.put(lod0, instanceBody(lod0, none, actor.lod0(), lod1, lod2, beast0));
            if (actor.lod1() != null) {
                var beast1 = beastReferences(lod1, 1, actor.lod1(), appended, replaceTexture, appendTexture);
                bodies.put(lod1, instanceBody(lod1, none, actor.lod1(), 0, 0, beast1));
            }
            if (actor.lod2() != null) {
                var beast2 = beastReferences(lod2, 2, actor.lod2(), appended, replaceTexture, appendTexture);
                bodies.put(lod2, instanceBody(lod2, none, actor.lod2(), 0, 0, beast2));
            }
        }
        for (var texture : lighting.getTextures().entrySet()) {
            replaceTexture.accept(texture.getKey(), texture.getValue());
        }
        return new LightingPlan(
                Collections.unmodifiableMap(bodies),
                List.copyOf(appended),
                Map.copyOf(references),
                Map.copyOf(positioned));
    }

    private int[] beastReferences(
            int instanceIndex,
            int lod,
            Instance instance,
            List<ExportEntry> appended,
            TextureReplacement replaceTexture,
            TextureAppender appendTexture)
            throws IOException {
        var bake = instance.beast();
        if (bake == null) {
            return new int[0];
        }
        if (original.licenseVersion < UStaticMeshInstance.BEAST_LIGHTMAP_LICENSE) {
            throw malformed("This package version stores no Beast lightmaps");
        }
        if (instanceIndex <= original.exports.size()) {
            var decoded = StaticMeshInstanceCodec.read(original, original.exports.get(instanceIndex - 1));
            var saved = decoded.beastLightmaps();
            if (saved.size() == 2
                    && decoded.beastWidth() == bake.width()
                    && decoded.beastHeight() == bake.height()
                    && isSameSizeBeastTexture(saved.get(0), 0, bake.width(), bake.height())
                    && isSameSizeBeastTexture(saved.get(1), 1, bake.width(), bake.height())) {
                replaceTexture.accept(saved.get(0), bake.slot0());
                replaceTexture.accept(saved.get(1), bake.slot1());
                return new int[] {saved.get(0), saved.get(1)};
            }
        }
        var instanceName = instanceIndex <= original.exports.size()
                ? original.getName(original.exports.get(instanceIndex - 1).objectName())
                : appendedName(appended, instanceIndex);
        var slot0 = appendTexture.apply(instanceName + "_Lightmap_0_" + lod, bake.slot0());
        var slot1 = appendTexture.apply(instanceName + "_Lightmap_1_" + lod, bake.slot1());
        return new int[] {slot0, slot1};
    }

    private boolean isSameSizeBeastTexture(int reference, int slot, int width, int height) throws IOException {
        if (reference <= 0 || reference > original.exports.size()) {
            return false;
        }
        var entry = original.exports.get(reference - 1);
        if (!isBeastTexture(entry) || UnrealLoader.beastLightmapSlotOf(original.getName(entry.objectName())) != slot) {
            return false;
        }
        var saved = BeastTextureCodec.read(original, entry);
        return saved.width() == width && saved.height() == height;
    }

    private String appendedName(List<ExportEntry> appended, int index) {
        for (var entry : appended) {
            if (entry.index() == index) {
                return names.get(entry.objectName());
            }
        }
        throw new IllegalStateException("Unknown appended export " + index);
    }

    private boolean isBeastTexture(ExportEntry entry) throws IOException {
        return original.getClassName(entry).equals("Texture")
                && original.getName(entry.objectName()).contains("_Lightmap_");
    }

    private boolean claimReusable(int reference, Map<Integer, Integer> owners, Set<Integer> used) throws IOException {
        return reference > 0 && !owners.containsKey(reference) && isInstanceExport(reference) && used.add(reference);
    }

    private boolean isInstanceExport(int reference) throws IOException {
        return reference > 0
                && reference <= original.exports.size()
                && original.getClassName(original.exports.get(reference - 1)).equals("StaticMeshInstance")
                && original.getObjectPath(original.exports.get(reference - 1).classIndex())
                        .equalsIgnoreCase("Engine.StaticMeshInstance");
    }

    private byte[] instanceBody(int index, int none, Instance instance, int lod1, int lod2, int[] beast)
            throws IOException {
        var width = instance.beast() == null ? 0 : instance.beast().width();
        var height = instance.beast() == null ? 0 : instance.beast().height();
        if (index > original.exports.size()) {
            return StaticMeshInstanceCodec.write(
                    original.fileVersion, original.licenseVersion, none, instance, lod1, lod2, beast, width, height);
        }
        var entry = original.exports.get(index - 1);
        var decoded = StaticMeshInstanceCodec.read(original, entry);
        var out = new ByteArrayOutputStream();
        out.write(original.body, entry.serialOffset(), decoded.prefixEnd() - entry.serialOffset());
        out.writeBytes(StaticMeshInstanceCodec.writeNative(
                original.fileVersion, original.licenseVersion, instance, lod1, lod2, beast, width, height));
        return out.toByteArray();
    }

    private int instanceFlags() throws IOException {
        for (var entry : original.exports) {
            if (original.getClassName(entry).equals("StaticMeshInstance")) {
                return entry.flags() & ~0x02000000;
            }
        }
        return original.licenseVersion >= 39 ? 0x00060000 : 0x00070000;
    }

    private int instanceReference(ActorState state) throws IOException {
        var tag = tag(state, "StaticMeshInstance");
        if (tag == null) {
            return 0;
        }
        if (tag.type() != 5 || tag.arrayIndex() != 0) {
            throw malformed("Unsupported StaticMeshInstance property layout");
        }
        var cursor = new Cursor(original.body, tag.payload(), tag.end());
        var reference = cursor.readIndex();
        original.checkReference(reference);
        return reference;
    }

    private void verifyLighting(PackageEditDocument verified, ActorLightingChanges lighting) throws IOException {
        for (var change : lighting.getActors().entrySet()) {
            var candidate = verified.actorStates.get(change.getKey());
            if (candidate == null) {
                throw malformed("Lit actor did not round-trip: " + change.getKey());
            }
            var lod0 = verified.instanceReference(candidate);
            var decoded = verified.decodeInstance(lod0);
            var actor = change.getValue();
            verifySameInstance(change.getKey(), actor.lod0(), decoded.instance());
            verified.verifySameBeast(change.getKey(), actor.lod0(), decoded);
            if ((actor.lod1() == null) != (decoded.lod1() == 0)) {
                throw malformed("LOD1 lighting did not round-trip: " + change.getKey());
            }
            if (actor.lod1() != null) {
                var lod1 = verified.decodeInstance(decoded.lod1());
                verifySameInstance(change.getKey(), actor.lod1(), lod1.instance());
                verified.verifySameBeast(change.getKey(), actor.lod1(), lod1);
            }
            if (actor.lod2SharesLod1()
                    ? decoded.lod2() != decoded.lod1()
                    : (actor.lod2() == null) != (decoded.lod2() == 0)) {
                throw malformed("LOD2 lighting did not round-trip: " + change.getKey());
            }
            if (actor.lod2() != null) {
                var lod2 = verified.decodeInstance(decoded.lod2());
                verifySameInstance(change.getKey(), actor.lod2(), lod2.instance());
                verified.verifySameBeast(change.getKey(), actor.lod2(), lod2);
            }
        }
        for (var texture : lighting.getTextures().entrySet()) {
            var saved = BeastTextureCodec.read(verified.original, verified.original.exports.get(texture.getKey() - 1));
            if (!doMipsMatch(texture.getValue().mips(), saved.mips())) {
                throw malformed("Replaced Beast texture did not round-trip: " + texture.getKey());
            }
        }
    }

    private void verifySameBeast(String path, Instance expected, Decoded actual) throws IOException {
        var bake = expected.beast();
        var references = actual.beastLightmaps();
        if (bake == null) {
            if (!references.isEmpty()) {
                throw malformed("Beast lightmaps did not round-trip: " + path);
            }
            return;
        }
        if (references.size() != 2 || actual.beastWidth() != bake.width() || actual.beastHeight() != bake.height()) {
            throw malformed("Beast lightmaps did not round-trip: " + path);
        }
        for (var slot = 0; slot < 2; slot++) {
            var reference = references.get(slot);
            if (reference <= 0 || reference > original.exports.size()) {
                throw malformed("Beast lightmap reference did not round-trip: " + path);
            }
            var entry = original.exports.get(reference - 1);
            if (UnrealLoader.beastLightmapSlotOf(original.getName(entry.objectName())) != slot) {
                throw malformed("Beast lightmap name did not round-trip: " + path);
            }
            var saved = BeastTextureCodec.read(original, entry);
            var texture = slot == 0 ? bake.slot0() : bake.slot1();
            if (saved.width() != texture.width()
                    || saved.height() != texture.height()
                    || !doMipsMatch(texture.mips(), saved.mips())) {
                throw malformed("Beast lightmap texture did not round-trip: " + path);
            }
        }
    }

    private Decoded decodeInstance(int reference) throws IOException {
        if (!isInstanceExport(reference)) {
            throw malformed("StaticMeshInstance reference does not name an instance export");
        }
        return StaticMeshInstanceCodec.read(original, original.exports.get(reference - 1));
    }

    private byte[] buildActor(ActorState state) throws IOException {
        if (state.created && state.copyOf == null) {
            return buildCreatedActor(state);
        }
        var base = state.copyOf != null ? state.copyOf : state;
        var saved = base.transform;
        var replacements = new LinkedHashMap<String, byte[]>();
        if (!saved.location().equals(state.current.location())) {
            replacements.put("Location", encodeVector(state.current.location()));
        }
        if (!saved.rotation().equals(state.current.rotation())) {
            replacements.put("Rotation", encodeRotation(state.current.rotation()));
        }
        if (Float.compare(saved.drawScale(), state.current.drawScale()) != 0) {
            replacements.put(
                    "DrawScale",
                    littleEndian(new byte[4])
                            .putFloat(state.current.drawScale())
                            .array());
        }
        if (!saved.drawScale3d().equals(state.current.drawScale3d())) {
            replacements.put("DrawScale3D", encodeVector(state.current.drawScale3d()));
        }
        if (!saved.prePivot().equals(state.current.prePivot())) {
            replacements.put("PrePivot", encodeVector(state.current.prePivot()));
        }
        if (!state.created && state.copyOf == null && !saved.equals(state.current)) {
            for (var name : DECORATION_DATA) {
                var tag = tag(base, name);
                if (tag != null) {
                    replacements.put(name, carryDecorations(base, tag, state.current));
                }
            }
        }
        if (state.emitter && !state.emitterReferences.equals(base.emitterReferences)) {
            var emitters = new ByteArrayOutputStream();
            writeIndex(emitters, state.emitterReferences.size());
            for (var reference : state.emitterReferences) {
                writeIndex(emitters, reference);
            }
            replacements.put("Emitters", emitters.toByteArray());
        }
        if (!state.skins.equals(base.originalSkins)) {
            var skins = new ByteArrayOutputStream();
            writeIndex(skins, state.skins.size());
            for (var reference : state.skins) {
                writeIndex(skins, reference);
            }
            replacements.put("Skins", skins.toByteArray());
        }
        Tag regionTag = null;
        LightRegion region = null;
        if (state.pawn && !saved.location().equals(state.current.location())) {
            if (tag(base, "ColLocation") != null) {
                replacements.put("ColLocation", encodeVector(state.current.location()));
            }
            region = getMovedRegion(state);
            regionTag = region == null ? null : tag(base, "Region");
        }
        if (state.emitter) {
            if (!saved.location().equals(state.current.location())) {
                region = getMovedRegion(state);
                regionTag = region == null ? null : tag(base, "Region");
                if (tag(base, "ColLocation") != null
                        && readVector(base, "ColLocation", Vector3.ZERO).equals(saved.location())) {
                    replacements.put("ColLocation", encodeVector(state.current.location()));
                }
            }
            var sway = tag(base, "SwayRotationOrig");
            if (sway != null
                    && !saved.rotation().equals(state.current.rotation())
                    && readRotator(sway).equals(saved.rotation())) {
                replacements.put("SwayRotationOrig", encodeRotation(state.current.rotation()));
            }
        }
        if (state.moving
                && !saved.location().equals(state.current.location())
                && tag(base, "ColLocation") != null
                && readVector(base, "ColLocation", Vector3.ZERO).equals(saved.location())) {
            replacements.put("ColLocation", encodeVector(state.current.location()));
        }
        var restartTag = getRestartTag(state);
        var instanceTag = state.lightingInstance == 0 ? null : tag(state, "StaticMeshInstance");
        var out = new ByteArrayOutputStream();
        var position = base.entry.serialOffset();
        var arrayTags = false;
        var eventWritten = false;
        for (var tag : base.tags) {
            var lower = tag.name().toLowerCase(Locale.ROOT);
            if (state.movementTagsOverride != null && lower.equals("l2movementtag")) {
                arrayTags |= tag.type() == 9;
                out.write(original.body, position, tag.start() - position);
                position = tag.end();
                continue;
            }
            if (state.moveEventOverride != null && lower.equals("l2moveevent")) {
                out.write(original.body, position, tag.start() - position);
                writeNameTag(out, "L2MoveEvent", 0, intern(state.moveEventOverride));
                eventWritten = true;
                position = tag.end();
                continue;
            }
            if (state.droppedTags.contains(lower)) {
                out.write(original.body, position, tag.start() - position);
                position = tag.end();
                continue;
            }
            if (tag == restartTag) {
                var info = infoPosition(tag);
                out.write(original.body, position, info - position);
                out.write(original.body[info] & 0x7f);
                position = info + 1;
                continue;
            }
            if (tag == instanceTag) {
                out.write(original.body, position, tag.start() - position);
                var value = new ByteArrayOutputStream();
                writeIndex(value, state.lightingInstance);
                if (value.size() == tag.end() - tag.payload()) {
                    out.write(original.body, tag.start(), tag.payload() - tag.start());
                    out.writeBytes(value.toByteArray());
                } else {
                    writeObjectProperty(out, "StaticMeshInstance", state.lightingInstance);
                }
                position = tag.end();
                continue;
            }
            if (tag == regionTag) {
                out.write(original.body, position, tag.start() - position);
                var header = new Cursor(original.body, tag.start(), tag.end());
                header.readIndex();
                var info = header.position;
                header.readU8();
                header.readIndex();
                out.writeBytes(PointRegionTag.write(
                        original,
                        tag.start(),
                        info,
                        header.position,
                        tag.end(),
                        base.regionValues,
                        base.region,
                        region,
                        this::intern));
                position = tag.end();
                continue;
            }
            var key = replacements.keySet().stream()
                    .filter(name -> name.equalsIgnoreCase(tag.name()))
                    .findFirst()
                    .orElse(null);
            var replacement = key == null ? null : replacements.remove(key);
            if (replacement == null) {
                continue;
            }
            out.write(original.body, position, tag.start() - position);
            if (replacement.length == tag.end() - tag.payload()) {
                out.write(original.body, tag.start(), tag.payload() - tag.start());
                out.writeBytes(replacement);
            } else {
                writeProperty(out, key, replacement);
            }
            position = tag.end();
        }
        out.write(original.body, position, base.terminator - position);
        for (var change : replacements.entrySet()) {
            writeProperty(out, change.getKey(), change.getValue());
        }
        if (state.moveEventOverride != null && !eventWritten) {
            writeNameTag(out, "L2MoveEvent", 0, intern(state.moveEventOverride));
        }
        if (state.movementTagsOverride != null) {
            if (arrayTags || savesTagArray(base)) {
                var value = new ByteArrayOutputStream();
                writeIndex(value, state.movementTagsOverride.size());
                for (var name : state.movementTagsOverride) {
                    writeIndex(value, intern(name));
                }
                writeIndex(out, intern("L2MovementTag"));
                out.write(9 | 7 << 4);
                writeInt(out, value.size());
                out.writeBytes(value.toByteArray());
            } else {
                for (var index = 0; index < state.movementTagsOverride.size(); index++) {
                    writeNameTag(out, "L2MovementTag", index, intern(state.movementTagsOverride.get(index)));
                }
            }
        }
        if (state.lightingInstance != 0 && instanceTag == null) {
            writeObjectProperty(out, "StaticMeshInstance", state.lightingInstance);
        }
        var end = base.entry.serialOffset() + base.entry.serialSize();
        out.write(original.body, base.terminator, end - base.terminator);
        return out.toByteArray();
    }

    private @Nullable Tag getRestartTag(ActorState state) throws IOException {
        if (!state.moving || state.copyOf != null) {
            return null;
        }
        var tag = tag(state, MOVING_CLASSES.get(state.className));
        if (tag == null || tag.type() != 3 || (original.body[infoPosition(tag)] & 0x80) == 0) {
            return null;
        }
        var restarts = state.className.equals("MovableStaticMeshActor")
                ? !state.transform.rotation().equals(state.current.rotation())
                : !state.transform.equals(state.current);
        return restarts ? tag : null;
    }

    private byte[] carryDecorations(ActorState state, Tag tag, ActorTransform to) throws IOException {
        if (tag.type() != 9 || tag.arrayIndex() != 0) {
            throw malformed("Unsupported " + tag.name() + " property layout: " + state.path);
        }
        var value = Arrays.copyOfRange(original.body, tag.payload(), tag.end());
        try {
            return MeshDecorationCarry.carry(
                    value,
                    index -> index >= 0 && index < original.names.size() ? original.names.get(index) : null,
                    tag.name().equalsIgnoreCase("DecoLayerDataForQuad"),
                    state.transform,
                    to);
        } catch (IllegalStateException | IllegalArgumentException failure) {
            throw malformed("Cannot carry the decorations of " + state.path + ": " + failure.getMessage());
        }
    }

    private int infoPosition(Tag tag) throws IOException {
        var cursor = new Cursor(original.body, tag.start(), tag.end());
        cursor.readIndex();
        return cursor.position;
    }

    private byte[] buildCreatedActor(ActorState state) throws IOException {
        var out = new ByteArrayOutputStream();
        out.writeBytes(new byte[14]);
        writeProperty(out, "Location", encodeVector(state.current.location()));
        writeProperty(out, "Rotation", encodeRotation(state.current.rotation()));
        writeProperty(
                out,
                "DrawScale",
                littleEndian(new byte[4]).putFloat(state.current.drawScale()).array());
        writeProperty(out, "DrawScale3D", encodeVector(state.current.drawScale3d()));
        writeProperty(out, "PrePivot", encodeVector(state.current.prePivot()));
        var skins = new ByteArrayOutputStream();
        writeIndex(skins, state.skins.size());
        state.skins.forEach(reference -> writeIndex(skins, reference));
        writeProperty(out, "Skins", skins.toByteArray());
        writeObjectProperty(out, "StaticMesh", state.meshReference);
        writeObjectProperty(out, "Level", level.getLevelInfo());
        if (state.lightingInstance != 0) {
            writeObjectProperty(out, "StaticMeshInstance", state.lightingInstance);
        }
        for (var property : state.scalarProperties) {
            writeIndex(out, intern(property.name));
            if (property.type == 3) {
                out.write(3 | (property.truth ? 128 : 0));
            } else {
                out.write(property.type | (property.payload.length == 4 ? 0x20 : 0));
                out.writeBytes(property.payload);
            }
        }
        writeIndex(out, intern("None"));
        out.writeBytes(state.newTail);
        return out.toByteArray();
    }

    private void writeNameTag(ByteArrayOutputStream out, String name, int arrayIndex, int value) {
        var payload = new ByteArrayOutputStream();
        writeIndex(payload, value);
        writeIndex(out, intern(name));
        var sizeType = switch (payload.size()) {
            case 1 -> 0;
            case 2 -> 1;
            case 4 -> 2;
            default -> 5;
        };
        out.write(6 | sizeType << 4 | (arrayIndex > 0 ? 0x80 : 0));
        if (sizeType == 5) {
            out.write(payload.size());
        }
        if (arrayIndex > 0) {
            out.write(arrayIndex);
        }
        out.writeBytes(payload.toByteArray());
    }

    private boolean savesTagArray(ActorState state) {
        return state.tags.stream().anyMatch(tag -> tag.name().equalsIgnoreCase("L2MovementTag") && tag.type() == 9);
    }

    private void writeObjectProperty(ByteArrayOutputStream out, String name, int reference) {
        var value = new ByteArrayOutputStream();
        writeIndex(value, reference);
        writeIndex(out, intern(name));
        out.write(5 | 0x50);
        out.write(value.size());
        out.writeBytes(value.toByteArray());
    }

    private void writeProperty(ByteArrayOutputStream out, String name, byte[] payload) {
        writeIndex(out, intern(name));
        var type = name.equals("Skins") || name.equals("Emitters") ? 9 : name.equals("DrawScale") ? 4 : 10;
        var sizeType = switch (payload.length) {
            case 1 -> 0;
            case 2 -> 1;
            case 4 -> 2;
            case 12 -> 3;
            case 16 -> 4;
            default -> 7;
        };
        out.write(type | sizeType << 4);
        if (type == 10) {
            writeIndex(out, intern(name.equals("Rotation") ? "Rotator" : "Vector"));
        }
        if (sizeType == 7) {
            writeInt(out, payload.length);
        }
        out.writeBytes(payload);
    }

    private int intern(String name) {
        for (var i = 0; i < names.size(); i++) {
            if (names.get(i).equalsIgnoreCase(name)) {
                return i;
            }
        }
        names.add(name);
        return names.size() - 1;
    }

    private boolean isPawnClass(int classIndex) throws IOException {
        var at = classIndex;
        for (var depth = 0; depth < MOST_PAWN_PARENTS && at > 0; depth++) {
            if (at > original.exports.size() || original.exports.get(at - 1).classIndex() != 0) {
                return false;
            }
            at = original.exports.get(at - 1).superIndex();
        }
        return at < 0
                && -at <= original.imports.size()
                && original.getObjectPath(at).equalsIgnoreCase(PAWN_CLASS);
    }

    private ActorState readActor(String path, String className, ExportEntry entry) {
        var state = new ActorState(path, className, entry);
        state.pawn = className.equals(PAWN) || entry.classIndex() > 0;
        state.emitter = !state.pawn && isEmitterClassName(entry);
        try {
            var cursor = new Cursor(original.body, entry.serialOffset(), entry.serialOffset() + entry.serialSize());
            if ((entry.flags() & 0x02000000) != 0) {
                var node = cursor.readIndex();
                cursor.readIndex();
                cursor.skip(12);
                if (node != 0) {
                    cursor.readIndex();
                }
            }
            state.frameEnd = cursor.position;
            while (true) {
                var start = cursor.position;
                var name = original.getName(cursor.readIndex());
                if (name.equalsIgnoreCase("None")) {
                    state.terminator = start;
                    break;
                }
                var info = cursor.readU8();
                var type = info & 15;
                if (type == 0) {
                    throw malformed("Invalid property type");
                }
                var struct = type == 10 ? original.getName(cursor.readIndex()) : "";
                var size = switch (info >> 4 & 7) {
                    case 0 -> 1;
                    case 1 -> 2;
                    case 2 -> 4;
                    case 3 -> 12;
                    case 4 -> 16;
                    case 5 -> cursor.readU8();
                    case 6 -> cursor.readU16();
                    default -> cursor.readI32();
                };
                var index = type != 3 && (info & 128) != 0 ? cursor.readArrayIndex() : 0;
                var payload = cursor.position;
                original.skipValue(cursor, name, type, size);
                if (type == 10
                        && struct.equalsIgnoreCase("PointRegion")
                        && endsBeforeItsNone(payload, cursor.position)) {
                    cursor.readIndex();
                }
                state.tags.add(new Tag(name, type, struct, index, start, payload, cursor.position));
            }
            state.tail = Arrays.copyOfRange(original.body, cursor.position, cursor.end);
            if (state.tail.length
                    != (state.pawn || state.emitter || original.fileVersion < BEAST_TAIL_VERSION ? 0 : 2)) {
                state.relocatable = false;
                state.restriction = "Existing fixed-size properties only; the unaudited native tail is preserved";
            } else if (state.tail.length == 2) {
                state.tailMatchesTags = Arrays.equals(state.tail, taggedBeastScales(state));
            }
            state.transform = new ActorTransform(
                    readVector(state, "Location", Vector3.ZERO),
                    readRotation(state),
                    readScale(state),
                    readVector(state, "DrawScale3D", Vector3.ONE),
                    readVector(state, "PrePivot", Vector3.ZERO));
            var meshTag = tag(state, "StaticMesh");
            if (meshTag != null) {
                if (meshTag.type() != 5 || meshTag.arrayIndex() != 0) {
                    throw malformed("Unsupported StaticMesh property layout");
                }
                var meshCursor = new Cursor(original.body, meshTag.payload(), meshTag.end());
                state.meshReference = meshCursor.readIndex();
                original.checkReference(state.meshReference);
                if (meshCursor.position != meshCursor.end) {
                    throw malformed("Unsupported StaticMesh property padding");
                }
                state.meshPath = original.getObjectPath(state.meshReference);
            }
            if (state.pawn) {
                readPawn(state);
            } else if (state.emitter) {
                readVector(state, "ColLocation", Vector3.ZERO);
                var sway = tag(state, "SwayRotationOrig");
                if (sway != null) {
                    validateStruct(sway, "Rotator");
                }
                readRegion(state);
                readEmitters(state);
            }
            var skinTag = tag(state, "Skins");
            if (skinTag != null) {
                if (skinTag.type() != 9 || skinTag.arrayIndex() != 0) {
                    throw malformed("Unsupported Skins property layout");
                }
                var skinCursor = new Cursor(original.body, skinTag.payload(), skinTag.end());
                var count = PackageBytes.count(skinCursor.readIndex());
                if (count > MAX_SKIN_SLOTS) {
                    throw malformed("Too many authored material slots");
                }
                for (var i = 0; i < count; i++) {
                    var reference = skinCursor.readIndex();
                    original.checkReference(reference);
                    state.originalSkins.add(reference);
                }
                if (skinCursor.position != skinCursor.end) {
                    throw malformed("Unsupported Skins property padding");
                }
            }
        } catch (IOException | IllegalArgumentException failure) {
            state.restriction = failure.getMessage();
            state.editable = false;
        }
        state.current = state.transform;
        state.skins = new ArrayList<>(state.originalSkins);
        state.savedTransform = state.current;
        state.savedSkins = List.copyOf(state.skins);
        return state;
    }

    private void readPawn(ActorState state) throws IOException {
        var mesh = tag(state, "SavedMeshName");
        if (mesh != null && mesh.type() == 6 && mesh.arrayIndex() == 0) {
            var name = original.getName(new Cursor(original.body, mesh.payload(), mesh.end()).readIndex());
            state.meshPath = name.equalsIgnoreCase("None") ? "" : name;
        }
        var object = tag(state, "Mesh");
        if (state.meshPath.isEmpty() && object != null && object.type() == 5 && object.arrayIndex() == 0) {
            var reference = new Cursor(original.body, object.payload(), object.end()).readIndex();
            if (reference != 0) {
                original.checkReference(reference);
                state.meshPath = original.getObjectPath(reference);
            }
        }
        readVector(state, "ColLocation", Vector3.ZERO);
        readRegion(state);
    }

    private void readEmitters(ActorState state) throws IOException {
        var tag = tag(state, "Emitters");
        if (tag == null) {
            return;
        }
        if (tag.type() != 9 || tag.arrayIndex() != 0) {
            throw malformed("Unsupported Emitters property layout");
        }
        var cursor = new Cursor(original.body, tag.payload(), tag.end());
        var count = PackageBytes.count(cursor.readIndex());
        var references = new ArrayList<Integer>(count);
        for (var i = 0; i < count; i++) {
            var reference = cursor.readIndex();
            original.checkReference(reference);
            references.add(reference);
        }
        if (cursor.position != cursor.end) {
            throw malformed("Unsupported Emitters property padding");
        }
        state.emitterReferences = List.copyOf(references);
    }

    private void readRegion(ActorState state) throws IOException {
        var region = tag(state, "Region");
        if (region != null
                && region.type() == 10
                && region.struct().equalsIgnoreCase("PointRegion")
                && region.arrayIndex() == 0) {
            var values = PointRegionTag.read(original, region.payload(), region.end());
            if (values != null) {
                state.regionValues = values;
                state.region = PointRegionTag.getRegion(original, values);
            }
        }
    }

    private void requireKnownNames(@Nullable List<String> tags, @Nullable String event) throws IOException {
        var known = new HashSet<String>();
        names.forEach(name -> known.add(name.toLowerCase(Locale.ROOT)));
        var asked = new ArrayList<String>();
        if (tags != null) {
            if (tags.size() > L2Swing.MOST_TAGS) {
                throw malformed("Invalid recovery carrier tags");
            }
            asked.addAll(tags);
        }
        if (event != null) {
            asked.add(event);
        }
        for (var name : asked) {
            if (!known.contains(name.toLowerCase(Locale.ROOT))) {
                throw malformed("Invalid recovery carrier name");
            }
        }
    }

    private ActorRecord keepMoveEvent(ActorState source, ActorRecord copy) {
        var state = actorStates.get(copy.sourcePath());
        if (source.moveEventOverride != null && state != null) {
            state.moveEventOverride = source.moveEventOverride;
        }
        return copy;
    }

    private boolean endsBeforeItsNone(int start, int end) {
        try {
            var cursor = new Cursor(original.body, start, end);
            while (cursor.position < end) {
                if (original.getName(cursor.readIndex()).equalsIgnoreCase("None")) {
                    return false;
                }
                var info = cursor.readU8();
                var type = info & 15;
                if (type == 10) {
                    cursor.readIndex();
                }
                var size = switch (info >> 4 & 7) {
                    case 0 -> 1;
                    case 1 -> 2;
                    case 2 -> 4;
                    case 3 -> 12;
                    case 4 -> 16;
                    case 5 -> cursor.readU8();
                    case 6 -> cursor.readU16();
                    default -> cursor.readI32();
                };
                if (type != 3 && (info & 128) != 0) {
                    cursor.readArrayIndex();
                }
                cursor.skip(type == 3 ? 0 : size);
            }
            var after = new Cursor(original.body, end, original.body.length);
            return cursor.position == end && original.getName(after.readIndex()).equalsIgnoreCase("None");
        } catch (IOException | RuntimeException notTags) {
            return false;
        }
    }

    private boolean isEmitterClassName(ExportEntry entry) {
        if (entry.classIndex() >= 0) {
            return false;
        }
        try {
            var path = original.getObjectPath(entry.classIndex());
            if (path.equalsIgnoreCase(EMITTER_CLASS)) {
                return true;
            }
            var dot = path.indexOf('.');
            if (dot <= 0 || path.indexOf('.', dot + 1) >= 0 || clientRoot == null) {
                return false;
            }
            if (emitterClasses == null) {
                emitterClasses = UArchiveLoader.emitterClassTest(clientRoot);
            }
            return emitterClasses.isEmitterClass(path.substring(0, dot), path.substring(dot + 1));
        } catch (IOException unreadable) {
            return false;
        }
    }

    private byte[] taggedBeastScales(ActorState state) {
        var values = new byte[BEAST_PROPERTIES.size()];
        for (var tag : state.tags) {
            for (var i = 0; i < BEAST_PROPERTIES.size(); i++) {
                if (!tag.name().equalsIgnoreCase(BEAST_PROPERTIES.get(i))) {
                    continue;
                }
                if (tag.type() != 1 || tag.arrayIndex() != 0 || tag.end() - tag.payload() != 1) {
                    return null;
                }
                values[i] = original.body[tag.payload()];
            }
        }
        return values;
    }

    private record CarryGraph(Map<String, Map<String, L2Swing.Kind>> carried, Map<String, String> carriers) {}

    private CarryGraph carryGraph() {
        var graph = carryGraph;
        if (graph == null) {
            graph = buildCarryGraph();
            carryGraph = graph;
        }
        return graph;
    }

    private CarryGraph buildCarryGraph() {
        carryGraphBuilds++;
        var carried = new HashMap<String, Map<String, L2Swing.Kind>>();
        var carriers = new HashMap<String, String>();
        try {
            var actors = actorStates.values().stream()
                    .filter(state -> !state.deleted)
                    .sorted(Comparator.comparingInt(state -> state.entry.index()))
                    .toList();
            var facts = new HashMap<String, CarryFacts>();
            var swinging = new HashSet<String>();
            var swingCarriers = new ArrayList<L2Attach.Carrier>();
            var movementCarriers = new ArrayList<L2Attach.Carrier>();
            for (var state : actors) {
                var fact = carryFacts(state);
                facts.put(state.path, fact);
                if (fact.swings()) {
                    swinging.add(state.path);
                    swingCarriers.add(new L2Attach.Carrier(state.path, fact.movementTags()));
                } else if (fact.movesPlace()) {
                    movementCarriers.add(new L2Attach.Carrier(state.path, fact.movementTags()));
                }
            }
            if (swingCarriers.isEmpty() && movementCarriers.isEmpty()) {
                return new CarryGraph(carried, carriers);
            }
            var candidates = new ArrayList<L2Attach.Candidate>();
            var kinds = new HashMap<String, L2Swing.Kind>();
            for (var state : actors) {
                if (state.emitter) {
                    candidates.add(candidate(state, facts.get(state.path)));
                    kinds.put(state.path, L2Swing.Kind.EMITTER);
                }
            }
            for (var lightClass : List.of("Light", "Spotlight")) {
                for (var light : lightStates.values().stream()
                        .filter(light -> !light.deleted && light.light.className.equals(lightClass))
                        .sorted(Comparator.comparingInt(light -> light.light.entry.index()))
                        .toList()) {
                    var isStatic = light.light.getSavedBool("bStatic");
                    var movable = light.light.getSavedBool("bMovable");
                    candidates.add(new L2Attach.Candidate(
                            light.light.path,
                            light.moveEventOverride != null
                                    ? light.moveEventOverride
                                    : light.light.getMoveEvent(original),
                            isStatic == null || isStatic,
                            movable != null && movable));
                    kinds.put(light.light.path, L2Swing.Kind.LIGHT);
                }
            }
            for (var meshClass : List.of("StaticMeshActor", "MovableStaticMeshActor")) {
                for (var state : actors) {
                    if (!state.emitter && state.className.equals(meshClass)) {
                        candidates.add(candidate(state, facts.get(state.path)));
                        kinds.put(state.path, swinging.contains(state.path) ? L2Swing.Kind.SWING : L2Swing.Kind.MESH);
                    }
                }
            }
            var bySwings = new HashSet<String>();
            L2Attach.attach(swingCarriers, candidates).forEach((carrier, attached) -> {
                var map = new LinkedHashMap<String, L2Swing.Kind>();
                for (var each : attached) {
                    map.put(each.path(), kinds.get(each.path()));
                    carriers.putIfAbsent(each.path(), carrier);
                    bySwings.add(each.path());
                }
                carried.put(carrier, map);
            });
            var heads = new ArrayList<L2Attach.Candidate>();
            for (var state : actors) {
                if (state.className.equals("StaticMeshActor")
                        && !bySwings.contains(state.path)
                        && !facts.get(state.path).turns()) {
                    heads.add(candidate(state, facts.get(state.path)));
                }
            }
            L2Attach.attach(movementCarriers, heads).forEach((carrier, attached) -> {
                var map = new LinkedHashMap<String, L2Swing.Kind>();
                for (var each : attached) {
                    map.put(each.path(), L2Swing.Kind.MESH);
                    carriers.putIfAbsent(each.path(), carrier);
                }
                carried.put(carrier, map);
            });
        } catch (IOException unreadable) {
            return new CarryGraph(Map.of(), Map.of());
        }
        return new CarryGraph(carried, carriers);
    }

    private record CarryFacts(
            List<String> movementTags,
            String moveEvent,
            boolean isStatic,
            boolean movable,
            boolean swings,
            boolean movesPlace,
            boolean turns) {}

    private L2Attach.Candidate candidate(ActorState state, CarryFacts facts) {
        return new L2Attach.Candidate(state.path, facts.moveEvent(), facts.isStatic(), facts.movable());
    }

    private CarryFacts carryFacts(ActorState state) throws IOException {
        var source = state.copyOf != null ? state.copyOf : state;
        var dropped = state.droppedTags == null ? Set.<String>of() : state.droppedTags;
        var tags = new ArrayList<String>();
        var event = "None";
        Boolean isStatic = null;
        Boolean movable = null;
        for (var tag : source.tags) {
            var name = tag.name().toLowerCase(Locale.ROOT);
            if (dropped.contains(name)) {
                continue;
            }
            switch (name) {
                case "l2movementtag" -> {
                    if (tag.type() == 6) {
                        while (tags.size() <= tag.arrayIndex()) {
                            tags.add("None");
                        }
                        tags.set(tag.arrayIndex(), readName(tag));
                    } else if (tag.type() == 9) {
                        tags.clear();
                        var cursor = new Cursor(original.body, tag.payload(), tag.end());
                        var count = PackageBytes.count(cursor.readIndex());
                        for (var i = 0; i < count; i++) {
                            tags.add(original.getName(cursor.readIndex()));
                        }
                    }
                }
                case "l2moveevent" -> {
                    if (tag.type() == 6) {
                        event = readName(tag);
                    }
                }
                case "bstatic" -> isStatic = tag.type() == 3 && (original.body[infoPosition(tag)] & 0x80) != 0;
                case "bmovable" -> movable = tag.type() == 3 && (original.body[infoPosition(tag)] & 0x80) != 0;
                default -> {}
            }
        }
        if (state.movementTagsOverride != null) {
            tags = new ArrayList<>(state.movementTagsOverride);
        }
        if (state.moveEventOverride != null) {
            event = state.moveEventOverride;
        }
        var staticByClass = source.className.equals("StaticMeshActor");
        var deleted = isTrue(source, "bDeleteMe");
        var physics = tag(source, "Physics") != null;
        var swings = !deleted
                && !physics
                && source.className.equals("MovableStaticMeshActor")
                && isSwingMoving(source, dropped);
        var moves =
                !deleted && !physics && source.className.equals("L2MovableStaticMeshActor") && isMovementMoving(source);
        return new CarryFacts(
                tags,
                event,
                isStatic != null ? isStatic : staticByClass,
                movable == null || movable,
                swings,
                moves,
                turnsByItself(source));
    }

    private boolean isSwingMoving(ActorState state, Set<String> dropped) throws IOException {
        var max = rotatorOf(state, "L2RotatorMax");
        var rate = rotatorOf(state, "L2RotatorRate");
        var accel = rotatorTime(state, "L2AccelRatio");
        var saved = !dropped.contains("bl2initmove") && isTrue(state, "bL2InitMove")
                ? new L2Swing.Saved(new URotator(0, 0, 0), rotatorTime(state, "L2CurrentMax"), new Vec3(), new Vec3())
                : null;
        var swing = new L2Swing(
                state.path,
                new Vec3(),
                new URotator(0, 0, 0),
                new L2Swing.Axis(max.pitch(), rate.pitch(), accel.getX()),
                new L2Swing.Axis(max.roll(), rate.roll(), accel.getY()),
                new L2Swing.Axis(max.yaw(), rate.yaw(), accel.getZ()),
                false,
                false,
                saved,
                null,
                List.of(),
                0);
        return swing.isMoving();
    }

    private boolean isMovementMoving(ActorState state) throws IOException {
        var zero = new Vec3();
        var turnMax = rotatorOf(state, "RotationMax");
        var turnRate = rotatorOf(state, "RotationRate");
        var moveMax = readVector(state, "TranslationMax", Vector3.ZERO);
        var moveRate = readVector(state, "TranslationRate", Vector3.ZERO);
        return L2Movement.movesPlace(
                new L2Movement.Channel(
                        new Vec3(moveMax.x(), moveMax.y(), moveMax.z()),
                        new Vec3(moveRate.x(), moveRate.y(), moveRate.z()),
                        zero,
                        zero),
                new L2Movement.Channel(
                        new Vec3(turnMax.pitch(), turnMax.yaw(), turnMax.roll()),
                        new Vec3(turnRate.pitch(), turnRate.yaw(), turnRate.roll()),
                        zero,
                        zero));
    }

    private boolean turnsByItself(ActorState state) throws IOException {
        var physics = tag(state, "Physics");
        if (physics == null || physics.type() != 1 || original.body[physics.payload()] != 5) {
            return false;
        }
        var rate = rotatorOf(state, "RotationRate");
        return rate.pitch() != 0 || rate.yaw() != 0 || rate.roll() != 0;
    }

    private URotator rotatorOf(ActorState state, String name) throws IOException {
        var tag = tag(state, name);
        if (tag == null) {
            return new URotator(0, 0, 0);
        }
        var rotator = readRotator(tag);
        return new URotator(rotator.pitch(), rotator.yaw(), rotator.roll());
    }

    private Vec3 rotatorTime(ActorState state, String name) throws IOException {
        var tag = tag(state, name);
        var result = new float[3];
        if (tag == null || tag.type() != 10) {
            return new Vec3();
        }
        var cursor = new Cursor(original.body, tag.payload(), tag.end());
        while (cursor.position < tag.end()) {
            var field = original.getName(cursor.readIndex());
            if (field.equalsIgnoreCase("None")) {
                break;
            }
            var info = cursor.readU8();
            var type = info & 15;
            if (type == 10) {
                cursor.readIndex();
            }
            var size = switch (info >> 4 & 7) {
                case 0 -> 1;
                case 1 -> 2;
                case 2 -> 4;
                case 3 -> 12;
                case 4 -> 16;
                case 5 -> cursor.readU8();
                case 6 -> cursor.readU16();
                default -> cursor.readI32();
            };
            if (type != 3 && (info & 128) != 0) {
                cursor.readArrayIndex();
            }
            var at = switch (field) {
                case "PitchTime" -> 0;
                case "RollTime" -> 1;
                case "YawTime" -> 2;
                default -> -1;
            };
            if (at >= 0 && type == 4 && size == 4) {
                result[at] = littleEndian(original.body).getFloat(cursor.position);
            }
            cursor.skip(type == 3 ? 0 : size);
        }
        return new Vec3(result[0], result[1], result[2]);
    }

    private String readName(Tag tag) throws IOException {
        return original.getName(new Cursor(original.body, tag.payload(), tag.end()).readIndex());
    }

    private Tag tag(ActorState state, String name) throws IOException {
        Tag found = null;
        for (var tag : state.tags) {
            if (tag.name().equalsIgnoreCase(name)) {
                if (found != null) {
                    throw malformed("Duplicate authored property " + name);
                }
                found = tag;
            }
        }
        return found;
    }

    private Vector3 readVector(ActorState state, String name, Vector3 fallback) throws IOException {
        var tag = tag(state, name);
        if (tag == null) {
            return fallback;
        }
        validateStruct(tag, "Vector");
        var input = littleEndian(original.body);
        return new Vector3(
                input.getFloat(tag.payload()), input.getFloat(tag.payload() + 4), input.getFloat(tag.payload() + 8));
    }

    private Rotator readRotator(Tag tag) throws IOException {
        validateStruct(tag, "Rotator");
        var input = littleEndian(original.body);
        return new Rotator(
                input.getInt(tag.payload()), input.getInt(tag.payload() + 4), input.getInt(tag.payload() + 8));
    }

    private Rotator readRotation(ActorState state) throws IOException {
        var tag = tag(state, "Rotation");
        if (tag == null) {
            return new Rotator(0, 0, 0);
        }
        validateStruct(tag, "Rotator");
        var input = littleEndian(original.body);
        return new Rotator(
                input.getInt(tag.payload()), input.getInt(tag.payload() + 4), input.getInt(tag.payload() + 8));
    }

    private void validateStruct(Tag tag, String struct) throws IOException {
        if (tag.type() != 10
                || !tag.struct().equalsIgnoreCase(struct)
                || tag.arrayIndex() != 0
                || tag.end() - tag.payload() != 12) {
            throw malformed("Unsupported " + tag.name() + " property layout");
        }
    }

    private float readScale(ActorState state) throws IOException {
        var tag = tag(state, "DrawScale");
        if (tag == null) {
            return 1f;
        }
        if (tag.type() != 4 || tag.arrayIndex() != 0 || tag.end() - tag.payload() != 4) {
            throw malformed("Unsupported DrawScale property layout");
        }
        return littleEndian(original.body).getFloat(tag.payload());
    }

    private static ActorState newCopy(ActorState template, ExportEntry entry, String path) {
        var state = new ActorState(path, template.className, entry);
        state.created = true;
        state.pawn = template.pawn;
        state.moving = template.moving;
        state.nonStatic = template.nonStatic;
        state.emitter = template.emitter;
        state.emitterReferences = template.emitterReferences;
        if (!template.pawn) {
            state.droppedTags = template.emitter
                    ? EFFECT_COPY_DROPS
                    : switch (template.className) {
                        case "MovableStaticMeshActor" -> SWING_COPY_DROPS;
                        case "L2MovableStaticMeshActor" -> MOVEMENT_COPY_DROPS;
                        default -> DECORATION_COPY_DROPS;
                    };
            state.meshReference = template.meshReference;
        }
        state.copyOf = template;
        state.template = template.path;
        state.meshPath = template.meshPath;
        state.newTail = template.tail.clone();
        state.current = template.transform;
        state.skins = new ArrayList<>(template.originalSkins);
        state.savedTransform = state.current;
        state.savedSkins = List.copyOf(state.skins);
        return state;
    }

    private static String freeName(String className, Set<String> taken) {
        var suffix = 0;
        while (taken.contains((className + suffix).toLowerCase(Locale.ROOT))) {
            suffix++;
        }
        var name = className + suffix;
        taken.add(name.toLowerCase(Locale.ROOT));
        return name;
    }

    private static String surfaceKey(int surface) {
        return "#bsp-surface-" + surface;
    }

    private static String shortName(String path) {
        return path.substring(path.lastIndexOf('.') + 1);
    }

    private static String brushKey(int brush) {
        return "#bsp-brush-" + brush;
    }

    private static boolean names(Instance instance, Set<Integer> lights) {
        return instance.sceneLights().stream().anyMatch(light -> lights.contains(light.reference()));
    }

    private static Instance unapplied(Instance instance, Set<Integer> lights) {
        var scene = instance.sceneLights().stream()
                .map(light ->
                        lights.contains(light.reference()) ? new Light(light.reference(), light.rawBits(), 0) : light)
                .toList();
        return new Instance(
                instance.vertexCount(), instance.rawColors(), instance.revision(), scene, instance.environmentLights());
    }

    private static void validateAssetOuter(PackageBytes packageBytes, int outer) throws IOException {
        while (outer != 0) {
            if (outer > 0) {
                var entry = packageBytes.exports.get(outer - 1);
                if (!packageBytes.getClassName(entry).equals("Package")) {
                    throw malformed("Asset is nested inside a non-package object");
                }
                outer = entry.outer();
            } else {
                var entry = packageBytes.imports.get(-outer - 1);
                if (!packageBytes.getName(entry.className()).equals("Package")) {
                    throw malformed("Asset import has a non-package outer");
                }
                outer = entry.outer();
            }
        }
    }

    private static boolean doMipsMatch(List<byte[]> expected, List<byte[]> actual) {
        if (expected.size() != actual.size()) {
            return false;
        }
        for (var i = 0; i < expected.size(); i++) {
            if (!Arrays.equals(expected.get(i), actual.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static void verifySameInstance(String path, Instance expected, Instance actual) throws IOException {
        if (expected.vertexCount() != actual.vertexCount()
                || expected.revision() != actual.revision()
                || !Arrays.equals(expected.rawColors(), actual.rawColors())
                || !lightsMatch(expected.sceneLights(), actual.sceneLights())
                || !lightsMatch(expected.environmentLights(), actual.environmentLights())) {
            throw malformed("Rebuilt lighting did not round-trip: " + path);
        }
    }

    private static boolean lightsMatch(List<Light> expected, List<Light> actual) {
        if (expected.size() != actual.size()) {
            return false;
        }
        for (var i = 0; i < expected.size(); i++) {
            var a = expected.get(i);
            var b = actual.get(i);
            if (a.reference() != b.reference()
                    || a.applied() != b.applied()
                    || !Arrays.equals(a.rawBits(), b.rawBits())) {
                return false;
            }
        }
        return true;
    }

    private static byte[] encodeVector(Vector3 value) {
        return littleEndian(new byte[12])
                .putFloat(value.x())
                .putFloat(value.y())
                .putFloat(value.z())
                .array();
    }

    private static byte[] encodeRotation(Rotator value) {
        return littleEndian(new byte[12])
                .putInt(value.pitch())
                .putInt(value.yaw())
                .putInt(value.roll())
                .array();
    }

    private static @Nullable Path clientRootOf(Path source) {
        var maps = source.toAbsolutePath().getParent();
        return maps == null ? null : maps.getParent();
    }

    private static boolean hasZeroScale(ActorTransform transform) {
        var axes = transform.drawScale3d();
        return transform.drawScale() == 0 || axes.x() == 0 || axes.y() == 0 || axes.z() == 0;
    }
}
