package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.ActorTransform.Rotator;
import com.rost.cruma.core.editing.ActorTransform.Vector3;
import com.rost.cruma.core.editing.NativeClassDefaults.Definition;
import com.rost.cruma.core.editing.NativeClassDefaults.Inheritance;
import com.rost.cruma.core.editing.NativeClassDefaults.Property;
import com.rost.cruma.core.editing.PackageBytes.Cursor;
import com.rost.cruma.core.editing.PackageBytes.ExportEntry;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;

public final class P152EffectiveInputs {
    private static final Map<String, String> EXPECTED = Map.of(
            "Core.dll", "fd90cdfe58460231abd7b2f3b529c21782e330af918e88c7c38555b4e0fdd8c7",
            "Engine.dll", "c46ab699b51a9b23e8db2c39d439deda4f2f98dc64eb56ed150651f4f2b44f64",
            "Core.u", "0ee12c2889e5a1e576739cc9f8d8cb6f9546be79f1fdf77b5f282886069fb6f9",
            "Engine.u", "912bc7e93d541309f3275549dff212bce91f3ea8587603525809157c5c0ba854");

    private static final String GAMEPLAY_SHA256 = "c9b989aa739df140ea76bb47fd77bde096823968b10cffc3458d69918eda4a13";
    private static final int MAX_INPUT_BYTES = 128 * 1024 * 1024;
    private static final int MAX_MAP_BYTES = 512 * 1024 * 1024;
    private final Path system;
    private final List<Source> sources;
    private final List<Input> receipt;
    private final Inheritance terrainDefaults;
    private final Inheritance lightDefaults;
    private final Inheritance staticMeshDefaults;
    private final Inheritance levelInfoDefaults;
    private final Inheritance materialDefaults;
    private final Map<String, Inheritance> actorDefaults;

    private P152EffectiveInputs(
            Path system,
            List<Source> sources,
            Inheritance terrain,
            Inheritance light,
            Inheritance staticMesh,
            Inheritance levelInfo,
            Inheritance material,
            Map<String, Inheritance> actorDefaults) {
        this.system = system;
        this.sources = List.copyOf(sources);
        receipt = sources.stream()
                .map(s -> new Input(s.logicalPath().getFileName().toString(), s.sha256()))
                .toList();
        terrainDefaults = terrain;
        lightDefaults = light;
        staticMeshDefaults = staticMesh;
        levelInfoDefaults = levelInfo;
        materialDefaults = material;
        this.actorDefaults = Map.copyOf(actorDefaults);
    }

    public enum Origin {
        INSTANCE,
        SERIALIZED_CLASS_DEFAULT,
        NATIVE_ZERO_INITIALIZATION
    }

    public record Provenance(Origin origin, String declaringClass, String property, String sourceSha256) {}

    public record Fact<T>(T value, Provenance provenance) {
        public Fact {
            Objects.requireNonNull(value);
            Objects.requireNonNull(provenance);
        }
    }

    public record Input(String name, String sha256) {}

    public record TerrainInputs(
            Fact<Boolean> inverted,
            Fact<Boolean> collideActors,
            Fact<Boolean> shadowCast,
            Fact<Boolean> blockZeroExtentTraces,
            Fact<Boolean> specialLit,
            Fact<Boolean> staticLighting) {}

    public record LevelInfoInputs(Fact<Boolean> collideActors, Fact<Boolean> shadowCast) {}

    public record ActorQueryInputs(
            Fact<Boolean> collideActors, Fact<Boolean> shadowCast, Fact<Boolean> blockZeroExtentTraces) {}

    public record PlacementInputs(
            Fact<Vector3> location,
            Fact<Rotator> rotation,
            Fact<Float> drawScale,
            Fact<Vector3> drawScale3d,
            Fact<Vector3> prePivot) {
        public ActorTransform transform() {
            return new ActorTransform(
                    location.value(), rotation.value(), drawScale.value(), drawScale3d.value(), prePivot.value());
        }
    }

    public record CollisionInputs(
            Fact<Boolean> collideActors,
            Fact<Boolean> shadowCast,
            Fact<Boolean> blockZeroExtentTraces,
            Fact<Boolean> useCylinderCollision,
            Fact<Boolean> deleteMe,
            Fact<Boolean> pendingDelete,
            Fact<Float> radius,
            Fact<Float> height) {}

    public record ObjectReference(int index, String objectPath, String declaredClass) {
        public boolean isNull() {
            return index == 0;
        }
    }

    public record StaticMeshInputs(
            PlacementInputs placement,
            CollisionInputs collision,
            Fact<ObjectReference> staticMesh,
            Fact<ObjectReference> mesh,
            Fact<ObjectReference> meshContainer) {}

    public record SunInputs(Fact<Integer> type, Fact<Integer> effect, Fact<Boolean> specialLit) {}

    record BspLightInputs(
            String classPath, boolean movableSun, Fact<Integer> effect, Fact<Vector3> location, Fact<Float> radius) {}

    public record LightInputs(
            Fact<Integer> type,
            Fact<Integer> effect,
            Fact<Integer> hue,
            Fact<Integer> saturation,
            Fact<Integer> cone,
            Fact<Float> brightness,
            Fact<Float> radius,
            Fact<Boolean> isStatic,
            Fact<Boolean> noDelete,
            Fact<Boolean> specialLit) {
        public int admissionFlags() {
            return (isStatic.value() ? 0x80000 : 0)
                    | (noDelete.value() ? 0x200000 : 0)
                    | (specialLit.value() ? 0x20 : 0);
        }
    }

    public enum LightKind {
        NONE,
        LIGHT,
        MOVABLE_SUN
    }

    record Source(Path logicalPath, Path realPath, String sha256) {
        void verifyUnchanged() throws IOException {
            verifyUnchanged(() -> false);
        }

        void verifyUnchanged(BooleanSupplier cancelled) throws IOException {
            P152FreshHashQuery.checkCancelled(cancelled);
            verifyTarget();
            verify(logicalPath, sha256, cancelled);
            verifyTarget();
            P152FreshHashQuery.checkCancelled(cancelled);
        }

        private void verifyTarget() throws IOException {
            if (!logicalPath.toRealPath().equals(realPath)) {
                throw new IOException("Effective-input source identity changed: " + logicalPath.getFileName());
            }
        }
    }

    private record Captured(Source identity, byte[] bytes) {}

    public static P152EffectiveInputs open(Path clientRoot) throws IOException {
        return open(clientRoot, () -> false);
    }

    public static P152EffectiveInputs open(Path clientRoot, BooleanSupplier cancelled) throws IOException {
        Objects.requireNonNull(cancelled);
        P152FreshHashQuery.checkCancelled(cancelled);
        var system = clientRoot.toAbsolutePath().normalize().resolve("system");
        var sources = new ArrayList<Source>();
        var packages = new ArrayList<NativeClassDefaults>();
        for (var name : List.of("Core.dll", "Engine.dll", "Core.u", "Engine.u")) {
            var captured = capture(system.resolve(name), MAX_INPUT_BYTES, cancelled);
            var bytes = captured.bytes();
            var hash = captured.identity().sha256();
            if (!EXPECTED.get(name).equals(hash)) {
                throw new IOException("Unaudited P152 effective-input fingerprint: " + name);
            }
            sources.add(captured.identity());
            if (name.endsWith(".u")) {
                packages.add(NativeClassDefaults.read(captured.identity().realPath(), bytes));
                P152FreshHashQuery.checkCancelled(cancelled);
            }
        }
        var actors = new LinkedHashMap<String, Inheritance>();
        for (var cls : List.of(
                "L2MovableStaticMeshActor",
                "Mover",
                "Emitter",
                "Brush",
                "BlockingVolume",
                "ServerBlockingVolume",
                "Camera",
                "Light",
                "PlayerStart",
                "ZoneInfo",
                "SkyZoneInfo",
                "AmbientVolume",
                "MusicVolume",
                "PhysicsVolume",
                "NMoon",
                "NSun",
                "L2FogInfo",
                "NMovableSunLight")) {
            actors.put(cls, resolve(packages, "Engine." + cls, cancelled));
        }
        var result = new P152EffectiveInputs(
                system,
                sources,
                resolve(packages, "Engine.TerrainInfo", cancelled),
                resolve(packages, "Engine.Light", cancelled),
                resolve(packages, "Engine.StaticMeshActor", cancelled),
                resolve(packages, "Engine.LevelInfo", cancelled),
                resolve(packages, "Engine.Material", cancelled),
                actors);
        P152FreshHashQuery.checkCancelled(cancelled);
        return result;
    }

    static DormantRenderer captureDormantRenderer(Path path) throws IOException {
        var captured = capture(path, MAX_INPUT_BYTES);
        if (!captured.identity().sha256().equals("69432750a96ec3648c99e425a2f0a4ad4daeb13f94c8a22ec1549218bd9f0601")) {
            throw new IOException("Unaudited P152 dormant-mask renderer fingerprint: D3DDrv.dll");
        }
        var renderer = new DormantRenderer(captured.identity());
        renderer.verifySourcesUnchanged();
        return renderer;
    }

    public final class MapInputs {
        private final Source identity;
        private final String sha256;
        private final PackageBytes source;
        private final BspSources bspSources;
        private GameplayProfile gameplay;
        private Inheritance waterDefaults;
        private boolean sealed;

        private MapInputs(Source identity, PackageBytes source) {
            this(identity, source, null);
        }

        private MapInputs(Source identity, PackageBytes source, BspSources bspSources) {
            this.identity = identity;
            sha256 = identity.sha256();
            this.source = source;
            this.bspSources = bspSources;
        }

        public String getSourceSha256() {
            return sha256;
        }

        public List<Input> getReceipt() {
            if (bspSources != null) {
                return bspSources.getReceipt();
            }
            if (gameplay == null) {
                return P152EffectiveInputs.this.getReceipt();
            }
            var result = new ArrayList<>(P152EffectiveInputs.this.getReceipt());
            result.add(new Input("Gameplay.u", gameplay.identity().sha256()));
            return List.copyOf(result);
        }

        void seal() {
            sealed = true;
        }

        Source getMapIdentity() {
            return identity;
        }

        PackageBytes getPackageBytes() throws IOException {
            return new PackageBytes(identity.logicalPath(), source.body);
        }

        void verifyMapUnchanged() throws IOException {
            identity.verifyUnchanged();
        }

        BspLightInputs getBspLight(int reference) throws IOException {
            var entry = localBspActor(source, reference);
            var path = source.getObjectPath(entry.classIndex());
            var defaults = switch (path) {
                case "Engine.Light" -> lightDefaults;
                case "Engine.NMovableSunLight" -> actorDefaults.get("NMovableSunLight");
                case "Gameplay.Spotlight" -> {
                    if (bspSources == null) {
                        throw new IOException("Spotlight requires the immutable BSP script capture");
                    }
                    yield bspSources.spotlight;
                }
                default -> throw new IOException("Unaudited native BSP light class: " + path);
            };
            var split = path.indexOf('.');
            var authored = actorProperties(source, entry, path.substring(0, split), path.substring(split + 1));
            return resolveBspLight(defaults, authored, sha256);
        }

        Fact<Rotator> getBspSunRotation(int reference) throws IOException {
            return resolveSunRotation(
                    actorDefaults.get("NMovableSunLight"),
                    actorProperties(source, localBspActor(source, reference), "Engine", "NMovableSunLight"),
                    sha256);
        }

        Fact<Vector3> getBspSunLocation(int reference) throws IOException {
            return resolveSunLocation(
                    actorDefaults.get("NMovableSunLight"),
                    actorProperties(source, localBspActor(source, reference), "Engine", "NMovableSunLight"),
                    sha256);
        }

        Fact<Boolean> getBspZoneSunAffect(int reference) throws IOException {
            var entry = localBspActor(source, reference);
            var name = source.getClassName(entry);
            var defaults = switch (name) {
                case "ZoneInfo", "SkyZoneInfo" -> actorDefaults.get(name);
                case "LevelInfo" -> levelInfoDefaults;
                default -> throw new IOException("Unaudited BSP zone class " + name);
            };
            return resolveBspZoneSunAffect(defaults, actorProperties(source, entry, "Engine", name), sha256);
        }

        public ActorQueryInputs getWaterVolume(String actorPath) throws IOException {
            return getWaterVolume(actorPath, () -> false);
        }

        ActorQueryInputs getWaterVolume(String actorPath, BooleanSupplier cancelled) throws IOException {
            P152FreshHashQuery.checkCancelled(cancelled);
            var authored = actorProperties(source, actorPath, "Gameplay", "WaterVolume");
            if (gameplay == null) {
                if (sealed) {
                    throw new IOException("Gameplay was not captured before the input snapshot was sealed");
                }
                var loaded = captureGameplay(system.resolve("Gameplay.u"), GAMEPLAY_SHA256, cancelled);
                var chain = new ArrayList<Definition>();
                chain.add(loaded.definition());
                chain.addAll(actorDefaults.get("PhysicsVolume").chain());
                var inheritance = new Inheritance(chain);
                var result = resolveWaterVolume(inheritance, authored, sha256);
                loaded.identity().verifyUnchanged(cancelled);
                gameplay = loaded;
                waterDefaults = inheritance;
                return result;
            }
            var result = resolveWaterVolume(waterDefaults, authored, sha256);
            P152FreshHashQuery.checkCancelled(cancelled);
            return result;
        }

        public LightKind getLightKind(String actorPath) throws IOException {
            var entry = findActor(source, actorPath);
            var classPath = source.getObjectPath(entry.classIndex());
            if (classPath.equals("Gameplay.WaterVolume")) {
                getWaterVolume(actorPath);
                return LightKind.NONE;
            }
            if (!classPath.startsWith("Engine.")) {
                throw new IOException("Unaudited native GetLight class: " + classPath);
            }
            var name = classPath.substring("Engine.".length());
            actorProperties(source, actorPath, name);
            var defaults = switch (name) {
                case "TerrainInfo" -> terrainDefaults;
                case "LevelInfo" -> levelInfoDefaults;
                case "StaticMeshActor" -> staticMeshDefaults;
                default -> actorDefaults.get(name);
            };
            if (defaults == null) {
                throw new IOException("Unaudited native GetLight class: " + classPath);
            }
            return nativeLightKind(defaults);
        }

        public TerrainInputs getTerrain(String actorPath) throws IOException {
            return resolveTerrain(terrainDefaults, actorProperties(source, actorPath, "TerrainInfo"), sha256);
        }

        Fact<Boolean> getTerrainDeleted(String actorPath) throws IOException {
            return resolveTerrainDeleted(terrainDefaults, actorProperties(source, actorPath, "TerrainInfo"), sha256);
        }

        public LightInputs getLight(String actorPath) throws IOException {
            return resolveLight(lightDefaults, actorProperties(source, actorPath, "Light"), sha256);
        }

        public Fact<Boolean> getZoneSunAffect(String actorPath) throws IOException {
            return resolveZoneSunAffect(
                    actorDefaults.get("ZoneInfo"), actorProperties(source, actorPath, "ZoneInfo"), sha256);
        }

        public Fact<Rotator> getSunRotation(String actorPath) throws IOException {
            return resolveSunRotation(
                    actorDefaults.get("NMovableSunLight"),
                    actorProperties(source, actorPath, "NMovableSunLight"),
                    sha256);
        }

        public SunInputs getSun(String actorPath) throws IOException {
            return resolveSun(
                    actorDefaults.get("NMovableSunLight"),
                    actorProperties(source, actorPath, "NMovableSunLight"),
                    sha256);
        }

        public StaticMeshInputs getStaticMeshActor(String actorPath) throws IOException {
            return resolveStaticMesh(
                    staticMeshDefaults, actorProperties(source, actorPath, "StaticMeshActor"), sha256, source);
        }

        public StaticMeshInputs getMovableStaticMeshActor(String actorPath) throws IOException {
            return resolveMovableStaticMesh(
                    actorDefaults.get("L2MovableStaticMeshActor"),
                    actorProperties(source, actorPath, "L2MovableStaticMeshActor"),
                    sha256,
                    source);
        }

        public StaticMeshInputs getMover(String actorPath) throws IOException {
            return resolveMover(
                    actorDefaults.get("Mover"), actorProperties(source, actorPath, "Mover"), sha256, source);
        }

        Fact<List<ObjectReference>> getShadowMeshSkins(String actorPath) throws IOException {
            var entry = findActor(source, actorPath);
            var name = source.getClassName(entry);
            var defaults = switch (source.getObjectPath(entry.classIndex())) {
                case "Engine.StaticMeshActor" -> staticMeshDefaults;
                case "Engine.L2MovableStaticMeshActor", "Engine.Mover" -> actorDefaults.get(name);
                default -> throw new IOException("Unaudited shadow material actor class");
            };
            return resolveShadowMeshSkins(defaults, actorProperties(source, actorPath, name), sha256, source);
        }

        Fact<String> getShadowDefaultMaterial() throws IOException {
            return resolveShadowDefaultMaterial(materialDefaults);
        }

        public Fact<Integer> getMoverDamageGrade(String actorPath) throws IOException {
            return resolveMoverDamageGrade(
                    actorDefaults.get("Mover"), actorProperties(source, actorPath, "Mover"), sha256);
        }

        public ActorQueryInputs getActorQueryFlags(String actorPath, String className) throws IOException {
            var defaults = actorDefaults.get(className);
            if (defaults == null) {
                throw new IOException("Unaudited actor-query flag class: " + className);
            }
            return resolveActorQueryFlags(defaults, actorProperties(source, actorPath, className), sha256);
        }

        public LevelInfoInputs getLevelInfo(String actorPath) throws IOException {
            return resolveLevelInfo(levelInfoDefaults, actorProperties(source, actorPath, "LevelInfo"), sha256);
        }

        public void verifySourcesUnchanged() throws IOException {
            verifySourcesUnchanged(() -> false);
        }

        public void verifySourcesUnchanged(BooleanSupplier cancelled) throws IOException {
            if (bspSources == null) {
                P152EffectiveInputs.this.verifySourcesUnchanged(cancelled);
            } else {
                bspSources.verifySourcesUnchanged(cancelled);
            }
            identity.verifyUnchanged(cancelled);
            if (gameplay != null) {
                gameplay.identity().verifyUnchanged(cancelled);
            }
        }
    }

    record GameplayProfile(Source identity, Definition definition) {}

    static GameplayProfile captureSpotlight(Path path, String expectedSha256) throws IOException {
        var captured = capture(path, MAX_INPUT_BYTES);
        if (!captured.identity().sha256().equals(expectedSha256)) {
            throw new IOException("Unaudited P152 effective-input fingerprint: Gameplay.u");
        }
        var source = new PackageBytes(captured.identity().logicalPath(), captured.bytes());
        ExportEntry found = null;
        for (var entry : source.exports) {
            if (source.getObjectPath(entry.index()).equals("Gameplay.Spotlight")) {
                if (found != null) {
                    throw new IOException("Ambiguous Gameplay.Spotlight export");
                }
                found = entry;
            }
        }
        if (found == null
                || found.classIndex() != 0
                || found.outer() != 0
                || (found.flags() & 0x40000) == 0
                || (found.flags() & 0x04400000) != 0) {
            throw new IOException("Spotlight requires a fresh non-native editor-loaded Class export");
        }
        var definition = NativeClassDefaults.read(captured.identity().logicalPath(), captured.bytes())
                .readDefinition("Gameplay.Spotlight");
        if (!definition.superClass().equals("Engine.Light") || definition.classFlags() != 0x212) {
            throw new IOException("Unaudited Spotlight class flags or parent");
        }
        captured.identity().verifyUnchanged();
        return new GameplayProfile(captured.identity(), definition);
    }

    static GameplayProfile captureGameplay(Path path, String expectedSha256) throws IOException {
        return captureGameplay(path, expectedSha256, () -> false);
    }

    static GameplayProfile captureGameplay(Path path, String expectedSha256, BooleanSupplier cancelled)
            throws IOException {
        var captured = capture(path, MAX_INPUT_BYTES, cancelled);
        if (!captured.identity().sha256().equals(expectedSha256)) {
            throw new IOException("Unaudited P152 effective-input fingerprint: Gameplay.u");
        }
        var bytes = new PackageBytes(captured.identity().logicalPath(), captured.bytes());
        P152FreshHashQuery.checkCancelled(cancelled);
        if (!bytes.packageName.equals("Gameplay")) {
            throw new IOException("Optional class package must have Gameplay identity");
        }
        ExportEntry found = null;
        for (var entry : bytes.exports) {
            P152FreshHashQuery.checkCancelled(cancelled);
            if (bytes.getObjectPath(entry.index()).equals("Gameplay.WaterVolume")) {
                if (found != null) {
                    throw new IOException("Ambiguous Gameplay.WaterVolume export");
                }
                found = entry;
            }
        }
        if (found == null
                || found.classIndex() != 0
                || found.outer() != 0
                || (found.flags() & 0x40000) == 0
                || (found.flags() & 0x04400000) != 0) {
            throw new IOException("WaterVolume requires a fresh non-native editor-loaded Class export");
        }
        var definition = NativeClassDefaults.read(captured.identity().logicalPath(), captured.bytes())
                .readDefinition("Gameplay.WaterVolume");
        P152FreshHashQuery.checkCancelled(cancelled);
        if (!definition.superClass().equals("Engine.PhysicsVolume") || definition.classFlags() != 0x32) {
            throw new IOException("Unaudited WaterVolume class flags or parent");
        }
        captured.identity().verifyUnchanged(cancelled);
        return new GameplayProfile(captured.identity(), definition);
    }

    static ActorQueryInputs resolveWaterVolume(Inheritance defaults, List<Property> authored, String mapHash)
            throws IOException {
        requireChain(
                defaults,
                List.of(
                        "Gameplay.WaterVolume",
                        "Engine.PhysicsVolume",
                        "Engine.Volume",
                        "Engine.Brush",
                        "Engine.Actor",
                        "Core.Object"));
        return new ActorQueryInputs(
                resolveBool(defaults, authored, mapHash, "bCollideActors", false),
                resolveBool(defaults, authored, mapHash, "bShadowCast", true),
                resolveBool(defaults, authored, mapHash, "bBlockZeroExtentTraces", false));
    }

    static LightKind nativeLightKind(Inheritance defaults) throws IOException {
        if (defaults.chain().isEmpty()) {
            throw new IOException("Missing native GetLight ancestry");
        }
        var path = defaults.chain().getFirst().path();
        switch (path) {
            case "Engine.Light" -> {
                requireChain(defaults, List.of(path, "Engine.Actor", "Core.Object"));
                return LightKind.LIGHT;
            }
            case "Engine.NMovableSunLight" -> {
                requireChain(defaults, List.of(path, "Engine.Light", "Engine.Actor", "Core.Object"));
                return LightKind.MOVABLE_SUN;
            }
            case "Engine.TerrainInfo" ->
                requireChain(defaults, List.of(path, "Engine.Info", "Engine.Actor", "Core.Object"));
            case "Engine.LevelInfo" ->
                requireChain(defaults, List.of(path, "Engine.ZoneInfo", "Engine.Info", "Engine.Actor", "Core.Object"));
            case "Engine.StaticMeshActor", "Engine.Mover" ->
                requireChain(defaults, List.of(path, "Engine.Actor", "Core.Object"));
            case "Engine.L2MovableStaticMeshActor" ->
                requireChain(defaults, List.of(path, "Engine.StaticMeshActor", "Engine.Actor", "Core.Object"));
            case "Gameplay.WaterVolume" ->
                requireChain(
                        defaults,
                        List.of(
                                path,
                                "Engine.PhysicsVolume",
                                "Engine.Volume",
                                "Engine.Brush",
                                "Engine.Actor",
                                "Core.Object"));
            default -> hasZeroCollisionDefault(defaults);
        }
        return LightKind.NONE;
    }

    static Fact<Rotator> resolveSunRotation(Inheritance defaults, List<Property> authored, String mapHash)
            throws IOException {
        requireChain(defaults, List.of("Engine.NMovableSunLight", "Engine.Light", "Engine.Actor", "Core.Object"));
        var resolved = findValue(defaults, authored, mapHash, "Rotation", false);
        var bytes = structPayload(resolved.property(), "Rotation", "Rotator");
        return new Fact<>(new Rotator(bytes.getInt(), bytes.getInt(), bytes.getInt()), resolved.provenance());
    }

    static Fact<Vector3> resolveSunLocation(Inheritance defaults, List<Property> authored, String mapHash)
            throws IOException {
        requireChain(defaults, List.of("Engine.NMovableSunLight", "Engine.Light", "Engine.Actor", "Core.Object"));
        return resolveVector(defaults, authored, mapHash, "Location", false);
    }

    static Fact<Boolean> resolveZoneSunAffect(Inheritance defaults, List<Property> authored, String mapHash)
            throws IOException {
        requireChain(defaults, List.of("Engine.ZoneInfo", "Engine.Info", "Engine.Actor", "Core.Object"));
        return resolveBool(defaults, authored, mapHash, "bSunAffect", false);
    }

    static Fact<Boolean> resolveBspZoneSunAffect(Inheritance defaults, List<Property> authored, String mapHash)
            throws IOException {
        if (defaults.chain().isEmpty()) {
            throw new IOException("Missing BSP zone ancestry");
        }
        var type = defaults.chain().getFirst().path();
        switch (type) {
            case "Engine.ZoneInfo" ->
                requireChain(defaults, List.of(type, "Engine.Info", "Engine.Actor", "Core.Object"));
            case "Engine.LevelInfo", "Engine.SkyZoneInfo" ->
                requireChain(defaults, List.of(type, "Engine.ZoneInfo", "Engine.Info", "Engine.Actor", "Core.Object"));
            default -> throw new IOException("Unaudited BSP zone ancestry " + type);
        }
        return resolveBool(defaults, authored, mapHash, "bSunAffect", false);
    }

    static TerrainInputs resolveTerrain(Inheritance defaults, List<Property> authored, String mapHash)
            throws IOException {
        requireChain(defaults, List.of("Engine.TerrainInfo", "Engine.Info", "Engine.Actor", "Core.Object"));
        return new TerrainInputs(
                resolveBool(defaults, authored, mapHash, "Inverted", true),
                resolveBool(defaults, authored, mapHash, "bCollideActors", true),
                resolveBool(defaults, authored, mapHash, "bShadowCast", true),
                resolveBool(defaults, authored, mapHash, "bBlockZeroExtentTraces", false),
                resolveBool(defaults, authored, mapHash, "bSpecialLit", true),
                resolveBool(defaults, authored, mapHash, "bStaticLighting", false));
    }

    static Fact<Boolean> resolveTerrainDeleted(Inheritance defaults, List<Property> authored, String mapHash)
            throws IOException {
        requireChain(defaults, List.of("Engine.TerrainInfo", "Engine.Info", "Engine.Actor", "Core.Object"));
        return resolveBool(defaults, authored, mapHash, "bDeleteMe", true);
    }

    static LevelInfoInputs resolveLevelInfo(Inheritance defaults, List<Property> authored, String mapHash)
            throws IOException {
        requireChain(
                defaults, List.of("Engine.LevelInfo", "Engine.ZoneInfo", "Engine.Info", "Engine.Actor", "Core.Object"));
        return new LevelInfoInputs(
                resolveBool(defaults, authored, mapHash, "bCollideActors", true),
                resolveBool(defaults, authored, mapHash, "bShadowCast", true));
    }

    static SunInputs resolveSun(Inheritance defaults, List<Property> authored, String mapHash) throws IOException {
        requireChain(defaults, List.of("Engine.NMovableSunLight", "Engine.Light", "Engine.Actor", "Core.Object"));
        return new SunInputs(
                byteValue(defaults, authored, mapHash, "LightType", false),
                byteValue(defaults, authored, mapHash, "LightEffect", false),
                resolveBool(defaults, authored, mapHash, "bSpecialLit", true));
    }

    static LightInputs resolveLight(Inheritance defaults, List<Property> authored, String mapHash) throws IOException {
        requireChain(defaults, List.of("Engine.Light", "Engine.Actor", "Core.Object"));
        return new LightInputs(
                byteValue(defaults, authored, mapHash, "LightType", false),
                byteValue(defaults, authored, mapHash, "LightEffect", true),
                byteValue(defaults, authored, mapHash, "LightHue", true),
                byteValue(defaults, authored, mapHash, "LightSaturation", false),
                byteValue(defaults, authored, mapHash, "LightCone", false),
                floatValue(defaults, authored, mapHash, "LightBrightness"),
                floatValue(defaults, authored, mapHash, "LightRadius"),
                resolveBool(defaults, authored, mapHash, "bStatic", false),
                resolveBool(defaults, authored, mapHash, "bNoDelete", false),
                resolveBool(defaults, authored, mapHash, "bSpecialLit", true));
    }

    static BspLightInputs resolveBspLight(Inheritance defaults, List<Property> authored, String mapHash)
            throws IOException {
        if (defaults.chain().isEmpty()) {
            throw new IOException("Missing BSP light ancestry");
        }
        var path = defaults.chain().getFirst().path();
        var sun = path.equals("Engine.NMovableSunLight");
        switch (path) {
            case "Engine.Light" -> requireChain(defaults, List.of("Engine.Light", "Engine.Actor", "Core.Object"));
            case "Engine.NMovableSunLight" ->
                requireChain(
                        defaults, List.of("Engine.NMovableSunLight", "Engine.Light", "Engine.Actor", "Core.Object"));
            case "Gameplay.Spotlight" ->
                requireChain(defaults, List.of("Gameplay.Spotlight", "Engine.Light", "Engine.Actor", "Core.Object"));
            default -> throw new IOException("Unaudited BSP projection class: " + path);
        }
        var effect = byteValue(defaults, authored, mapHash, "LightEffect", path.equals("Engine.Light"));
        if (sun) {
            return new BspLightInputs(path, true, effect, null, null);
        }
        if (authored.stream().noneMatch(property -> property.name().equalsIgnoreCase("Location"))) {
            throw new IOException("BSP point light requires authored Location");
        }
        return new BspLightInputs(
                path,
                false,
                effect,
                resolveVector(defaults, authored, mapHash, "Location", false),
                floatValue(defaults, authored, mapHash, "LightRadius"));
    }

    static StaticMeshInputs resolveStaticMesh(
            Inheritance defaults, List<Property> authored, String mapHash, PackageBytes source) throws IOException {
        requireChain(defaults, List.of("Engine.StaticMeshActor", "Engine.Actor", "Core.Object"));
        return meshInputs(defaults, authored, mapHash, source);
    }

    static StaticMeshInputs resolveMovableStaticMesh(
            Inheritance defaults, List<Property> authored, String mapHash, PackageBytes source) throws IOException {
        requireChain(
                defaults,
                List.of("Engine.L2MovableStaticMeshActor", "Engine.StaticMeshActor", "Engine.Actor", "Core.Object"));
        return meshInputs(defaults, authored, mapHash, source);
    }

    static StaticMeshInputs resolveMover(
            Inheritance defaults, List<Property> authored, String mapHash, PackageBytes source) throws IOException {
        requireChain(defaults, List.of("Engine.Mover", "Engine.Actor", "Core.Object"));
        return meshInputs(defaults, authored, mapHash, source);
    }

    static Fact<Integer> resolveMoverDamageGrade(Inheritance defaults, List<Property> authored, String mapHash)
            throws IOException {
        requireChain(defaults, List.of("Engine.Mover", "Engine.Actor", "Core.Object"));
        var value = findValue(defaults, authored, mapHash, "DamageGrade", true);
        if (value.property() == null) {
            return new Fact<>(0, value.provenance());
        }
        var bytes = value.property().payload();
        if (value.property().type() != 2 || bytes.length != 4) {
            throw new IOException("Effective input is not an Int: DamageGrade");
        }
        return new Fact<>(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(), value.provenance());
    }

    static boolean hasActorQueryProfile(String exactClass) {
        return switch (exactClass) {
            case "Emitter",
                    "Brush",
                    "BlockingVolume",
                    "ServerBlockingVolume",
                    "Camera",
                    "Light",
                    "NMovableSunLight",
                    "PlayerStart",
                    "ZoneInfo",
                    "SkyZoneInfo",
                    "AmbientVolume",
                    "MusicVolume",
                    "PhysicsVolume",
                    "NMoon",
                    "NSun",
                    "L2FogInfo" -> true;
            default -> false;
        };
    }

    static ActorQueryInputs resolveActorQueryFlags(Inheritance defaults, List<Property> authored, String mapHash)
            throws IOException {
        var zeroCollision = hasZeroCollisionDefault(defaults);
        return new ActorQueryInputs(
                resolveBool(defaults, authored, mapHash, "bCollideActors", zeroCollision),
                resolveBool(defaults, authored, mapHash, "bShadowCast", true),
                resolveBool(defaults, authored, mapHash, "bBlockZeroExtentTraces", false));
    }

    static Fact<List<ObjectReference>> resolveShadowMeshSkins(
            Inheritance defaults, List<Property> authored, String mapHash, PackageBytes source) throws IOException {
        var chain = switch (defaults.chain().getFirst().path()) {
            case "Engine.StaticMeshActor" -> List.of("Engine.StaticMeshActor", "Engine.Actor", "Core.Object");
            case "Engine.L2MovableStaticMeshActor" ->
                List.of("Engine.L2MovableStaticMeshActor", "Engine.StaticMeshActor", "Engine.Actor", "Core.Object");
            case "Engine.Mover" -> List.of("Engine.Mover", "Engine.Actor", "Core.Object");
            default -> throw new IOException("Unaudited shadow material actor class");
        };
        requireChain(defaults, chain);
        var value = findValue(defaults, authored, mapHash, "Skins", true);
        if (value.property() == null) {
            return new Fact<>(List.of(), value.provenance());
        }
        if (value.property().type() != 9) {
            throw new IOException("Effective Skins input is not an object array");
        }
        var bytes = value.property().payload();
        var c = new Cursor(bytes, 0, bytes.length);
        int count = c.readIndex();
        if (count < 0 || count > 65536) {
            throw new IOException("Effective Skins array exceeds capture bounds");
        }
        var result = new ArrayList<ObjectReference>(count);
        for (int i = 0; i < count; i++) {
            result.add(referenceValue(c.readIndex(), value.provenance(), "Skins", source));
        }
        if (c.position != c.end) {
            throw new IOException("Trailing effective Skins array bytes");
        }
        return new Fact<>(List.copyOf(result), value.provenance());
    }

    static Fact<String> resolveShadowDefaultMaterial(Inheritance defaults) throws IOException {
        requireChain(defaults, List.of("Engine.Material", "Core.Object"));
        var value = findValue(defaults, List.of(), "", "DefaultMaterial", false);
        var property = value.property();
        if (property.type() != 5) {
            throw new IOException("Material class default is not an object reference");
        }
        var bytes = property.payload();
        var c = new Cursor(bytes, 0, bytes.length);
        int index = c.readIndex();
        if (c.position != c.end
                || index != 0
                        && (property.referencedObject() == null
                                || property.referencedObject().isBlank()
                                || property.referencedObject().equals("None"))) {
            throw new IOException("Invalid class-scoped default material reference");
        }
        return new Fact<>(index == 0 ? "" : property.referencedObject(), value.provenance());
    }

    private record Value(Property property, Provenance provenance) {}

    static List<Property> actorProperties(PackageBytes source, String actorPath, String exactClass) throws IOException {
        return actorProperties(source, actorPath, "Engine", exactClass);
    }

    static List<Property> actorProperties(PackageBytes source, String actorPath, String exactPackage, String exactClass)
            throws IOException {
        var found = findActor(source, actorPath);
        return actorProperties(source, found, exactPackage, exactClass);
    }

    static ExportEntry localBspActor(PackageBytes source, int reference) throws IOException {
        if (reference <= 0 || reference > source.exports.size()) {
            throw new IOException("BSP input requires a local actor export: " + reference);
        }
        var entry = source.exports.get(reference - 1);
        if (entry.classIndex() >= 0 || entry.outer() != 0) {
            throw new IOException("BSP input requires a root actor with an authenticated class import");
        }
        if ((entry.flags() & 0x00040000) == 0 || (entry.flags() & 0x00400000) != 0) {
            throw new IOException("BSP input actor is excluded by native editor load flags: " + reference);
        }
        return entry;
    }

    static List<Property> bspActorProperties(PackageBytes source, int reference, String exactPackage, String exactClass)
            throws IOException {
        return actorProperties(source, localBspActor(source, reference), exactPackage, exactClass);
    }

    static List<Property> taggedProperties(PackageBytes source, Cursor c) throws IOException {
        var result = new ArrayList<Property>();
        var seen = new HashSet<String>();
        while (result.size() < 16384) {
            var name = source.getName(c.readIndex());
            if (name.equalsIgnoreCase("None")) {
                return List.copyOf(result);
            }
            var tag = c.readU8();
            var type = tag & 15;
            if (type < 1 || type > 15) {
                throw new IOException("Invalid effective-input property type");
            }
            var struct = type == 10 ? source.getName(c.readIndex()) : null;
            var size = switch ((tag >> 4) & 7) {
                case 0 -> 1;
                case 1 -> 2;
                case 2 -> 4;
                case 3 -> 12;
                case 4 -> 16;
                case 5 -> c.readU8();
                case 6 -> c.readU16();
                default -> c.readI32();
            };
            var index = type != 3 && (tag & 128) != 0 ? c.readArrayIndex() : 0;
            if (type == 3 && size != 0 && size != 1) {
                throw new IOException("Invalid effective-input Boolean size");
            }
            if (!seen.add(name.toLowerCase(Locale.ROOT) + "/" + index)) {
                throw new IOException("Duplicate effective-input property " + name);
            }
            var count = type == 3 ? 0 : size;
            if (count < 0 || count > 32 * 1024 * 1024) {
                throw new IOException("Invalid effective-input property length");
            }
            var start = c.position;
            c.skip(count);
            result.add(new Property(
                    name,
                    type,
                    index,
                    struct,
                    type == 3 && (tag & 128) != 0,
                    Arrays.copyOfRange(c.bytes, start, c.position),
                    null));
        }
        throw new IOException("Excessive effective-input property count");
    }

    public List<Input> getReceipt() {
        return receipt;
    }

    public MapInputs captureMap(Path map) throws IOException {
        return captureMap(map, () -> false);
    }

    public MapInputs captureMap(Path map, BooleanSupplier cancelled) throws IOException {
        var captured = capture(map, MAX_MAP_BYTES, cancelled);
        var source = new PackageBytes(captured.identity().realPath(), captured.bytes());
        P152FreshHashQuery.checkCancelled(cancelled);
        if (source.fileVersion != 128 || source.licenseVersion != 39) {
            throw new IOException(
                    "Unaudited P152 effective-input map revision " + source.fileVersion + "/" + source.licenseVersion);
        }
        return new MapInputs(captured.identity(), source);
    }

    public void verifySourcesUnchanged() throws IOException {
        verifySourcesUnchanged(() -> false);
    }

    public void verifySourcesUnchanged(BooleanSupplier cancelled) throws IOException {
        P152FreshHashQuery.checkCancelled(cancelled);
        for (var source : sources) {
            source.verifyUnchanged(cancelled);
        }
        P152FreshHashQuery.checkCancelled(cancelled);
    }

    BspSources captureBspSources() throws IOException {
        var spotlight = captureSpotlight(system.resolve("Gameplay.u"), GAMEPLAY_SHA256);
        var chain = new ArrayList<Definition>();
        chain.add(spotlight.definition());
        chain.addAll(lightDefaults.chain());
        return new BspSources(spotlight.identity(), new Inheritance(chain));
    }

    final class BspSources {
        private final Source gameplay;
        private final Inheritance spotlight;
        private final List<Input> inputs;

        private BspSources(Source gameplay, Inheritance spotlight) {
            this.gameplay = gameplay;
            this.spotlight = spotlight;
            var all = new ArrayList<>(P152EffectiveInputs.this.getReceipt());
            all.add(new Input("Gameplay.u", gameplay.sha256()));
            inputs = List.copyOf(all);
        }

        MapInputs captureMap(Path logicalPath, Path capturedRealPath, String rawSha256, byte[] frozenRawBytes)
                throws IOException {
            if (frozenRawBytes.length > MAX_MAP_BYTES) {
                throw new IOException("Frozen BSP map bytes exceed the capture bound");
            }
            var frozen = frozenRawBytes.clone();
            if (!sha256(frozen).equals(rawSha256)) {
                throw new IOException("Frozen BSP map bytes differ from the inventory receipt");
            }
            var identity = new Source(logicalPath.toAbsolutePath().normalize(), capturedRealPath, rawSha256);
            identity.verifyTarget();
            var source = new PackageBytes(identity.logicalPath(), frozen);
            if (source.fileVersion < 123
                    || source.fileVersion > 132
                    || source.licenseVersion < 16
                    || source.licenseVersion > 40) {
                throw new IOException(
                        "Unaudited BSP tagged-input revision " + source.fileVersion + "/" + source.licenseVersion);
            }
            return new MapInputs(identity, source, this);
        }

        List<Input> getReceipt() {
            return inputs;
        }

        void requireMap(MapInputs map) throws IOException {
            if (map == null || map.bspSources != this) {
                throw new IOException("BSP map belongs to a different immutable runtime capture");
            }
        }

        DormantRenderer captureDormantRenderer() throws IOException {
            return P152EffectiveInputs.captureDormantRenderer(system.resolve("D3DDrv.dll"));
        }

        void verifySourcesUnchanged() throws IOException {
            verifySourcesUnchanged(() -> false);
        }

        void verifySourcesUnchanged(BooleanSupplier cancelled) throws IOException {
            P152EffectiveInputs.this.verifySourcesUnchanged(cancelled);
            gameplay.verifyUnchanged(cancelled);
        }
    }

    static final class DormantRenderer {
        private final Source identity;

        private DormantRenderer(Source identity) {
            this.identity = identity;
        }

        Input getInput() {
            return new Input("D3DDrv.dll", identity.sha256());
        }

        void verifySourcesUnchanged() throws IOException {
            identity.verifyUnchanged();
        }
    }

    private static boolean hasZeroCollisionDefault(Inheritance defaults) throws IOException {
        if (defaults.chain().isEmpty()) {
            throw new IOException("Missing exact actor-query class ancestry");
        }
        var name = defaults.chain().getFirst().path();
        return switch (name) {
            case "Engine.Emitter", "Engine.Brush", "Engine.Light", "Engine.NMoon", "Engine.NSun" -> {
                requireChain(defaults, List.of(name, "Engine.Actor", "Core.Object"));
                yield true;
            }
            case "Engine.NMovableSunLight" -> {
                requireChain(defaults, List.of(name, "Engine.Light", "Engine.Actor", "Core.Object"));
                yield true;
            }
            case "Engine.ZoneInfo", "Engine.L2FogInfo" -> {
                requireChain(defaults, List.of(name, "Engine.Info", "Engine.Actor", "Core.Object"));
                yield true;
            }
            case "Engine.SkyZoneInfo" -> {
                requireChain(defaults, List.of(name, "Engine.ZoneInfo", "Engine.Info", "Engine.Actor", "Core.Object"));
                yield true;
            }
            case "Engine.PlayerStart" -> {
                requireChain(
                        defaults,
                        List.of(
                                name,
                                "Engine.SmallNavigationPoint",
                                "Engine.NavigationPoint",
                                "Engine.Actor",
                                "Core.Object"));
                yield true;
            }
            case "Engine.Camera" -> {
                requireChain(
                        defaults,
                        List.of(
                                "Engine.Camera",
                                "Engine.PlayerController",
                                "Engine.Controller",
                                "Engine.Actor",
                                "Core.Object"));
                yield true;
            }
            case "Engine.BlockingVolume",
                    "Engine.ServerBlockingVolume",
                    "Engine.AmbientVolume",
                    "Engine.MusicVolume",
                    "Engine.PhysicsVolume" -> {
                requireChain(defaults, List.of(name, "Engine.Volume", "Engine.Brush", "Engine.Actor", "Core.Object"));
                yield false;
            }
            default -> throw new IOException("Unaudited actor-query flag class: " + name);
        };
    }

    private static StaticMeshInputs meshInputs(
            Inheritance defaults, List<Property> authored, String mapHash, PackageBytes source) throws IOException {
        requireAuthored(authored, "Location");
        requireAuthored(authored, "StaticMesh");
        var placement = new PlacementInputs(
                resolveVector(defaults, authored, mapHash, "Location", false),
                resolveRotator(defaults, authored, mapHash, "Rotation"),
                floatValue(defaults, authored, mapHash, "DrawScale"),
                resolveVector(defaults, authored, mapHash, "DrawScale3D", false),
                resolveVector(defaults, authored, mapHash, "PrePivot", true));
        var collision = new CollisionInputs(
                resolveBool(defaults, authored, mapHash, "bCollideActors", false),
                resolveBool(defaults, authored, mapHash, "bShadowCast", false),
                resolveBool(defaults, authored, mapHash, "bBlockZeroExtentTraces", false),
                resolveBool(defaults, authored, mapHash, "bUseCylinderCollision", true),
                resolveBool(defaults, authored, mapHash, "bDeleteMe", true),
                resolveBool(defaults, authored, mapHash, "bPendingDelete", true),
                floatValue(defaults, authored, mapHash, "CollisionRadius"),
                floatValue(defaults, authored, mapHash, "CollisionHeight"));
        return new StaticMeshInputs(
                placement,
                collision,
                resolveReference(defaults, authored, mapHash, "StaticMesh", false, source),
                resolveReference(defaults, authored, mapHash, "Mesh", true, source),
                resolveReference(defaults, authored, mapHash, "MeshContainer", true, source));
    }

    private static void requireAuthored(List<Property> authored, String name) throws IOException {
        if (authored.stream().noneMatch(property -> property.name().equalsIgnoreCase(name))) {
            throw new IOException("Missing authored effective input: " + name);
        }
    }

    private static Value findValue(
            Inheritance defaults, List<Property> authored, String mapHash, String name, boolean nativeZero)
            throws IOException {
        Property found = null;
        for (var property : authored) {
            if (property.name().equalsIgnoreCase(name)) {
                if (found != null || property.arrayIndex() != 0) {
                    throw new IOException("Ambiguous or indexed effective-input property " + name);
                }
                found = property;
            }
        }
        var classPath = defaults.chain().getFirst().path();
        if (found != null) {
            return new Value(found, new Provenance(Origin.INSTANCE, classPath, name, mapHash));
        }
        for (var definition : defaults.chain()) {
            Property inherited = null;
            for (var property : definition.properties()) {
                if (property.name().equalsIgnoreCase(name)) {
                    if (inherited != null || property.arrayIndex() != 0) {
                        throw new IOException("Ambiguous or indexed class input " + name);
                    }
                    inherited = property;
                }
            }
            if (inherited != null) {
                return new Value(
                        inherited,
                        new Provenance(
                                Origin.SERIALIZED_CLASS_DEFAULT, definition.path(), name, definition.sourceSha256()));
            }
        }
        if (!nativeZero) {
            throw new IOException("Missing audited explicit class input " + name);
        }
        return new Value(
                null,
                new Provenance(
                        Origin.NATIVE_ZERO_INITIALIZATION,
                        classPath,
                        name,
                        defaults.chain().getFirst().sourceSha256()));
    }

    private static Fact<Boolean> resolveBool(
            Inheritance defaults, List<Property> authored, String hash, String name, boolean zero) throws IOException {
        var value = findValue(defaults, authored, hash, name, zero);
        if (value.property() == null) {
            return new Fact<>(false, value.provenance());
        }
        if (value.property().type() != 3 || value.property().payload().length != 0) {
            throw new IOException("Effective input is not a Boolean: " + name);
        }
        return new Fact<>(value.property().booleanValue(), value.provenance());
    }

    private static Fact<Integer> byteValue(
            Inheritance defaults, List<Property> authored, String hash, String name, boolean zero) throws IOException {
        var value = findValue(defaults, authored, hash, name, zero);
        if (value.property() == null) {
            return new Fact<>(0, value.provenance());
        }
        var bytes = value.property().payload();
        if (value.property().type() != 1 || bytes.length != 1) {
            throw new IOException("Effective input is not a Byte: " + name);
        }
        return new Fact<>(bytes[0] & 255, value.provenance());
    }

    private static Fact<Float> floatValue(Inheritance defaults, List<Property> authored, String hash, String name)
            throws IOException {
        var value = findValue(defaults, authored, hash, name, false);
        var bytes = value.property().payload();
        if (value.property().type() != 4 || bytes.length != 4) {
            throw new IOException("Effective input is not a Float: " + name);
        }
        var number = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getFloat();
        if (!Float.isFinite(number)) {
            throw new IOException("Non-finite effective input: " + name);
        }
        return new Fact<>(number, value.provenance());
    }

    private static Fact<Vector3> resolveVector(
            Inheritance defaults, List<Property> authored, String hash, String name, boolean zero) throws IOException {
        var value = findValue(defaults, authored, hash, name, zero);
        if (value.property() == null) {
            return new Fact<>(Vector3.ZERO, value.provenance());
        }
        var bytes = structPayload(value.property(), name, "Vector");
        try {
            return new Fact<>(new Vector3(bytes.getFloat(), bytes.getFloat(), bytes.getFloat()), value.provenance());
        } catch (IllegalArgumentException nonfinite) {
            throw new IOException("Non-finite effective input: " + name, nonfinite);
        }
    }

    private static Fact<Rotator> resolveRotator(Inheritance defaults, List<Property> authored, String hash, String name)
            throws IOException {
        var value = findValue(defaults, authored, hash, name, true);
        if (value.property() == null) {
            return new Fact<>(new Rotator(0, 0, 0), value.provenance());
        }
        var bytes = structPayload(value.property(), name, "Rotator");
        return new Fact<>(new Rotator(bytes.getInt(), bytes.getInt(), bytes.getInt()), value.provenance());
    }

    private static ByteBuffer structPayload(Property property, String name, String type) throws IOException {
        var payload = property.payload();
        if (property.type() != 10 || !type.equalsIgnoreCase(property.structType()) || payload.length != 12) {
            throw new IOException("Effective input is not a " + type + ": " + name);
        }
        return ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static Fact<ObjectReference> resolveReference(
            Inheritance defaults, List<Property> authored, String hash, String name, boolean zero, PackageBytes source)
            throws IOException {
        var value = findValue(defaults, authored, hash, name, zero);
        if (value.property() == null) {
            return new Fact<>(new ObjectReference(0, "None", "None"), value.provenance());
        }
        var payload = value.property().payload();
        if (value.property().type() != 5) {
            throw new IOException("Effective input is not an Object reference: " + name);
        }
        var cursor = new Cursor(payload, 0, payload.length);
        var index = cursor.readIndex();
        if (cursor.position != payload.length) {
            throw new IOException("Trailing effective-input reference bytes: " + name);
        }
        return new Fact<>(referenceValue(index, value.provenance(), name, source), value.provenance());
    }

    private static ObjectReference referenceValue(int index, Provenance provenance, String name, PackageBytes source)
            throws IOException {
        if (index == 0) {
            return new ObjectReference(0, "None", "None");
        }
        if (provenance.origin() != Origin.INSTANCE) {
            throw new IOException("Unaudited non-null class reference: " + name);
        }
        source.checkReference(index);
        String cls;
        if (index < 0) {
            var entry = source.imports.get(-index - 1);
            cls = source.getName(entry.classPackage()) + "." + source.getName(entry.className());
        } else {
            var entry = source.exports.get(index - 1);
            var classIndex = entry.classIndex();
            if (classIndex < 0) {
                var importedClass = source.imports.get(-classIndex - 1);
                if (!source.getName(importedClass.classPackage()).equals("Core")
                        || !source.getName(importedClass.className()).equals("Class")) {
                    throw new IOException("Effective-input reference has a non-class export type: " + name);
                }
            } else if (classIndex > 0 && source.exports.get(classIndex - 1).classIndex() != 0) {
                throw new IOException("Effective-input reference has a non-class export type: " + name);
            }
            cls = classIndex == 0 ? "Core.Class" : source.getObjectPath(classIndex);
        }
        return new ObjectReference(index, source.getObjectPath(index), cls);
    }

    private static void requireChain(Inheritance defaults, List<String> expected) throws IOException {
        if (defaults.chain().size() != expected.size()) {
            throw new IOException("Unaudited effective-input inheritance");
        }
        for (var i = 0; i < expected.size(); i++) {
            if (!defaults.chain().get(i).path().equalsIgnoreCase(expected.get(i))) {
                throw new IOException("Unaudited effective-input class "
                        + defaults.chain().get(i).path());
            }
        }
    }

    private static ExportEntry findActor(PackageBytes source, String actorPath) throws IOException {
        ExportEntry found = null;
        for (var entry : source.exports) {
            if (source.getObjectPath(entry.index()).equalsIgnoreCase(actorPath)) {
                if (found != null) {
                    throw new IOException("Ambiguous effective-input actor " + actorPath);
                }
                found = entry;
            }
        }
        if (found == null || found.classIndex() >= 0) {
            throw new IOException("Missing or unaudited effective-input actor class");
        }
        return found;
    }

    private static List<Property> actorProperties(
            PackageBytes source, ExportEntry found, String exactPackage, String exactClass) throws IOException {
        if (found.classIndex() >= 0) {
            throw new IOException("Effective input requires a native class import");
        }
        var cls = source.imports.get(-found.classIndex() - 1);
        if (!source.getName(cls.classPackage()).equals("Core")
                || !source.getName(cls.className()).equals("Class")
                || !source.getName(cls.objectName()).equals(exactClass)
                || cls.outer() >= 0) {
            throw new IOException("Effective input requires exact " + exactPackage + "." + exactClass);
        }
        var outer = source.imports.get(-cls.outer() - 1);
        if (!source.getName(outer.classPackage()).equals("Core")
                || !source.getName(outer.className()).equals("Package")
                || !source.getName(outer.objectName()).equals(exactPackage)
                || outer.outer() != 0) {
            throw new IOException("Effective input class has unaudited package identity");
        }
        var c = new Cursor(source.body, found.serialOffset(), found.serialOffset() + found.serialSize());
        if ((found.flags() & 0x02000000) != 0) {
            var node = c.readIndex();
            source.checkReference(node);
            source.checkReference(c.readIndex());
            c.skip(12);
            if (node != 0) {
                c.readIndex();
            }
        }
        return taggedProperties(source, c);
    }

    private static Captured capture(Path path, int maximum) throws IOException {
        return capture(path, maximum, () -> false);
    }

    private static Captured capture(Path path, int maximum, BooleanSupplier cancelled) throws IOException {
        P152FreshHashQuery.checkCancelled(cancelled);
        var logical = path.toAbsolutePath().normalize();
        var real = logical.toRealPath();
        var bytes = readBounded(real, maximum, cancelled);
        var identity = new Source(logical, real, P152CaptureIo.hash(bytes, cancelled));
        identity.verifyTarget();
        P152FreshHashQuery.checkCancelled(cancelled);
        return new Captured(identity, bytes);
    }

    private static byte[] readBounded(Path path, int maximum, BooleanSupplier cancelled) throws IOException {
        P152FreshHashQuery.checkCancelled(cancelled);
        if (Files.size(path) > maximum) {
            throw new IOException("Effective-input source exceeds size bound");
        }
        return P152CaptureIo.read(path, maximum, cancelled);
    }

    private static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(digest().digest(bytes));
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static void verify(Path path, String expected, BooleanSupplier cancelled) throws IOException {
        P152FreshHashQuery.checkCancelled(cancelled);
        var digest = digest();
        try (var in = Files.newInputStream(path)) {
            var buffer = new byte[64 * 1024];
            while (true) {
                P152FreshHashQuery.checkCancelled(cancelled);
                var count = in.read(buffer);
                P152FreshHashQuery.checkCancelled(cancelled);
                if (count < 0) {
                    break;
                }
                digest.update(buffer, 0, count);
            }
        }
        if (!expected.equals(HexFormat.of().formatHex(digest.digest()))) {
            throw new IOException("Effective-input source changed: " + path.getFileName());
        }
        P152FreshHashQuery.checkCancelled(cancelled);
    }

    private static Inheritance resolve(List<NativeClassDefaults> packages, String path, BooleanSupplier cancelled)
            throws IOException {
        P152FreshHashQuery.checkCancelled(cancelled);
        var result = NativeClassDefaults.resolve(packages, path);
        P152FreshHashQuery.checkCancelled(cancelled);
        return result;
    }
}
