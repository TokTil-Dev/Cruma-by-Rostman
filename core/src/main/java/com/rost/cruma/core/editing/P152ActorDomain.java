package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.NativeStaticMeshTrace.Hit;
import com.rost.cruma.core.editing.NativeStaticMeshTrace.MatrixPair;
import com.rost.cruma.core.editing.P152ActorDomainMesh.Mesh;
import com.rost.cruma.core.editing.P152EffectiveInputs.ActorQueryInputs;
import com.rost.cruma.core.editing.P152EffectiveInputs.Fact;
import com.rost.cruma.core.editing.P152EffectiveInputs.Input;
import com.rost.cruma.core.editing.P152EffectiveInputs.LevelInfoInputs;
import com.rost.cruma.core.editing.P152EffectiveInputs.MapInputs;
import com.rost.cruma.core.editing.P152EffectiveInputs.StaticMeshInputs;
import com.rost.cruma.core.editing.P152EffectiveInputs.TerrainInputs;
import com.rost.cruma.core.editing.PackageBytes.ExportEntry;
import com.rost.cruma.core.editing.ShadowTriangleQuery.Triangle;
import com.rost.cruma.core.editing.TerrainNativeCodec.Box;
import com.rost.cruma.core.editing.TerrainNativeCodec.Vector;
import com.rost.cruma.core.world.NativeActorTransformPair;
import com.rost.cruma.core.world.NativeActorTransformPair.Parameters;
import com.rost.cruma.core.world.NativeActorTransformPair.Rotation;
import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;

public final class P152ActorDomain {
    private static final long MAX_PACKAGE_BYTES = 512L * 1024 * 1024;
    private static final long MAX_CAPTURE_BYTES = 2L * 1024 * 1024 * 1024;
    private static final int MAX_ACTORS = 65536;
    private static final int FLAGS = 0x597;
    private static final String INDEX_GAP =
            "Native collision-index insertion and per-ray hash/octree admission are not certified";
    private static final String NEIGHBOR_GAP = "Neighbor actor-index membership is not serialized in this map capture";

    private final Report report;
    private final List<Placement> placements;
    private final List<Captured> captured;
    private final Inputs inputs;
    private final List<String> nonIndexGaps;
    private final boolean currentLevelInfoExcluded;

    private P152ActorDomain(
            Report report,
            List<Placement> placements,
            List<Captured> captured,
            Inputs inputs,
            List<String> nonIndexGaps,
            boolean currentLevelInfoExcluded) {
        this.report = report;
        this.placements = List.copyOf(placements);
        this.captured = List.copyOf(captured);
        this.inputs = inputs;
        this.nonIndexGaps = List.copyOf(nonIndexGaps);
        this.currentLevelInfoExcluded = currentLevelInfoExcluded;
    }

    public enum Disposition {
        CAPTURED_MESH,
        EXCLUDED,
        UNSUPPORTED
    }

    public record Identity(String packageSha256, int reference, String objectPath) {}

    public record Source(String filename, String sha256, int fileVersion, int licenseVersion) {}

    public record Actor(Identity identity, String classPath, Disposition disposition, String detail) {}

    public record Asset(
            Identity identity,
            int internalVersion,
            int collisionFaces,
            int collisionNodes,
            String simpleCollisionModel) {}

    public record Report(
            Identity level,
            Identity currentLevelInfo,
            int registrySlots,
            int nullSlots,
            List<Actor> actors,
            List<Asset> assets,
            List<Source> sources,
            List<Input> runtimeInputs,
            List<String> unresolved) {
        public Report {
            actors = List.copyOf(actors);
            assets = List.copyOf(assets);
            sources = List.copyOf(sources);
            runtimeInputs = List.copyOf(runtimeInputs);
            unresolved = List.copyOf(unresolved);
        }
    }

    public record BoundsEvidence(
            Box transformedSavedBox,
            Box expandedSavedBox,
            Vector surfacePoint,
            float minimumSurfaceMargin,
            boolean savedBoxInsideNativeWorld,
            boolean simpleModelUnionCaptured) {}

    public record HitEvidence(Identity actor, Identity asset, Hit hit, BoundsEvidence bounds) {}

    public record Query(List<HitEvidence> geometricHits, int testedMeshes, int meshMisses, List<String> unresolved) {
        public Query {
            geometricHits = List.copyOf(geometricHits);
            unresolved = List.copyOf(unresolved);
        }
    }

    interface Inputs {
        StaticMeshInputs getMesh(String path) throws IOException;

        default StaticMeshInputs getMovableMesh(String path) throws IOException {
            throw new IOException("No L2MovableStaticMeshActor profile was supplied");
        }

        default StaticMeshInputs getMover(String path) throws IOException {
            throw new IOException("No Mover profile was supplied");
        }

        default Fact<Integer> getMoverDamageGrade(String path) throws IOException {
            throw new IOException("No Mover DamageGrade profile was supplied");
        }

        default MapInputs getMapInputs() {
            return null;
        }

        default void seal() {}

        default ActorQueryInputs getWaterVolume(String path) throws IOException {
            throw new IOException("No Gameplay.WaterVolume profile was supplied");
        }

        default ActorQueryInputs getWaterVolume(String path, BooleanSupplier cancelled) throws IOException {
            P152FreshHashQuery.checkCancelled(cancelled);
            var result = getWaterVolume(path);
            P152FreshHashQuery.checkCancelled(cancelled);
            return result;
        }

        default ActorQueryInputs getActorQueryFlags(String path, String className) throws IOException {
            throw new IOException("No actor-query flag profile was supplied for " + className);
        }

        TerrainInputs getTerrain(String path) throws IOException;

        LevelInfoInputs getLevelInfo(String path) throws IOException;

        void verify() throws IOException;

        default void verify(BooleanSupplier cancelled) throws IOException {
            P152FreshHashQuery.checkCancelled(cancelled);
            verify();
            P152FreshHashQuery.checkCancelled(cancelled);
        }

        default List<Input> getReceipt() {
            return List.of();
        }
    }

    record Captured(Path logical, P152EffectiveInputs.Source identity, PackageBytes bytes, long length) {
        Source receipt() {
            return new Source(
                    logical.getFileName().toString(), identity.sha256(), bytes.fileVersion, bytes.licenseVersion);
        }
    }

    private record Geometry(Asset asset, Mesh mesh) {}

    private record Placement(
            Identity actor,
            Geometry geometry,
            NativeStaticMeshTrace trace,
            Box worldBounds,
            Box expandedBounds,
            float[] localToWorld) {}

    public static P152ActorDomain capture(Path clientRoot, Path map) throws IOException {
        return capture(clientRoot, map, () -> false);
    }

    public static P152ActorDomain capture(Path clientRoot, Path map, BooleanSupplier cancelled) throws IOException {
        Objects.requireNonNull(cancelled);
        P152FreshHashQuery.checkCancelled(cancelled);
        var profile = P152EffectiveInputs.open(clientRoot, cancelled);
        var mapInputs = profile.captureMap(map, cancelled);
        return capture(clientRoot, map, mapInputs.getSourceSha256(), toInputs(mapInputs), cancelled);
    }

    static Inputs toInputs(MapInputs mapInputs) {
        return new Inputs() {
            @Override
            public StaticMeshInputs getMesh(String path) throws IOException {
                return mapInputs.getStaticMeshActor(path);
            }

            @Override
            public StaticMeshInputs getMovableMesh(String path) throws IOException {
                return mapInputs.getMovableStaticMeshActor(path);
            }

            @Override
            public StaticMeshInputs getMover(String path) throws IOException {
                return mapInputs.getMover(path);
            }

            @Override
            public Fact<Integer> getMoverDamageGrade(String path) throws IOException {
                return mapInputs.getMoverDamageGrade(path);
            }

            @Override
            public ActorQueryInputs getActorQueryFlags(String path, String className) throws IOException {
                return mapInputs.getActorQueryFlags(path, className);
            }

            @Override
            public TerrainInputs getTerrain(String path) throws IOException {
                return mapInputs.getTerrain(path);
            }

            @Override
            public LevelInfoInputs getLevelInfo(String path) throws IOException {
                return mapInputs.getLevelInfo(path);
            }

            @Override
            public void verify() throws IOException {
                mapInputs.verifySourcesUnchanged();
            }

            @Override
            public void verify(BooleanSupplier token) throws IOException {
                mapInputs.verifySourcesUnchanged(token);
            }

            @Override
            public ActorQueryInputs getWaterVolume(String path) throws IOException {
                return mapInputs.getWaterVolume(path);
            }

            @Override
            public ActorQueryInputs getWaterVolume(String path, BooleanSupplier token) throws IOException {
                return mapInputs.getWaterVolume(path, token);
            }

            @Override
            public MapInputs getMapInputs() {
                return mapInputs;
            }

            @Override
            public void seal() {
                mapInputs.seal();
            }

            @Override
            public List<Input> getReceipt() {
                return mapInputs.getReceipt();
            }
        };
    }

    static P152ActorDomain capture(Path clientRoot, Path map, String expectedMapHash, Inputs inputs)
            throws IOException {
        return capture(clientRoot, map, expectedMapHash, inputs, () -> false);
    }

    static P152ActorDomain capture(
            Path clientRoot, Path map, String expectedMapHash, Inputs inputs, BooleanSupplier cancelled)
            throws IOException {
        Objects.requireNonNull(cancelled);
        P152FreshHashQuery.checkCancelled(cancelled);
        var root = clientRoot.toAbsolutePath().normalize().toRealPath();
        var mapFile = read(root, map, cancelled);
        if (!mapFile.identity().sha256().equals(expectedMapHash)) {
            throw new IOException("Actor capture map changed after effective-input capture");
        }
        var source = mapFile.bytes();
        if (source.fileVersion != 128 || source.licenseVersion != 39) {
            throw new IOException("Actor-domain map requires P152 128/39");
        }
        var level = NativeLevelEditor.read(source);
        P152FreshHashQuery.checkCancelled(cancelled);
        requireEngineClass(source, level.entry, "Level");
        if (level.actors.isEmpty() || level.actors.size() > MAX_ACTORS || level.actors.getFirst() <= 0) {
            throw new IOException("Native Level has no bounded local current LevelInfo slot");
        }
        var current = source.exports.get(level.actors.getFirst() - 1);
        requireEngineClass(source, current, "LevelInfo");
        var currentPath = source.getObjectPath(current.index());
        var levelFacts = inputs.getLevelInfo(currentPath);
        var nonIndexGaps = new ArrayList<String>();
        var currentLevelInfoExcluded =
                !levelFacts.collideActors().value() || !levelFacts.shadowCast().value();
        if (!currentLevelInfoExcluded) {
            nonIndexGaps.add("Current LevelInfo cannot be excluded from the native actor query");
        }
        var actors = new ArrayList<Actor>();
        var placements = new ArrayList<Placement>();
        var packages = new LinkedHashMap<String, Captured>();
        packages.put(source.packageName.toLowerCase(Locale.ROOT), mapFile);
        var geometry = new LinkedHashMap<Identity, Geometry>();
        var assetFiles = assetFiles(root, cancelled);
        var seen = new HashSet<Integer>();
        var nulls = 0;
        for (var ref : level.actors) {
            P152FreshHashQuery.checkCancelled(cancelled);
            if (ref == 0) {
                nulls++;
                continue;
            }
            if (!seen.add(ref)) {
                throw new IOException("Duplicate actor reference in native Level registry: " + ref);
            }
            var path = source.getObjectPath(ref);
            var identity = new Identity(mapFile.identity().sha256(), ref, path);
            if (ref < 0) {
                actors.add(new Actor(
                        identity,
                        "Unresolved imported actor",
                        Disposition.UNSUPPORTED,
                        "Active imported actor is outside the local registry capture"));
                continue;
            }
            var entry = source.exports.get(ref - 1);
            var cls = source.getObjectPath(entry.classIndex());
            try {
                if (ref == current.index()) {
                    var excluded = !levelFacts.collideActors().value()
                            || !levelFacts.shadowCast().value();
                    actors.add(new Actor(
                            identity,
                            cls,
                            excluded ? Disposition.EXCLUDED : Disposition.UNSUPPORTED,
                            excluded
                                    ? "Current LevelInfo effective collision/shadow flag excludes it"
                                    : "Current LevelInfo index identity is unresolved"));
                } else if (cls.equals("Engine.TerrainInfo")) {
                    requireEngineClass(source, entry, "TerrainInfo");
                    var terrain = inputs.getTerrain(path);
                    var excluded = !terrain.collideActors().value()
                            || !terrain.shadowCast().value()
                            || !terrain.blockZeroExtentTraces().value();
                    actors.add(new Actor(
                            identity,
                            cls,
                            excluded ? Disposition.EXCLUDED : Disposition.UNSUPPORTED,
                            excluded
                                    ? "Effective Terrain actor-index admission flag is false"
                                    : "Terrain actor primitive/index bounds need qualification"));
                } else if (cls.equals("Gameplay.WaterVolume")) {
                    var flags = inputs.getWaterVolume(path, cancelled);
                    var excluded = !flags.collideActors().value()
                            || !flags.shadowCast().value()
                            || !flags.blockZeroExtentTraces().value();
                    actors.add(new Actor(
                            identity,
                            cls,
                            excluded ? Disposition.EXCLUDED : Disposition.UNSUPPORTED,
                            excluded
                                    ? "Fresh WaterVolume native admission flag excludes it"
                                    : "Authored WaterVolume flags require primitive and index qualification"));
                } else if (cls.startsWith("Engine.")
                        && P152EffectiveInputs.hasActorQueryProfile(cls.substring("Engine.".length()))) {
                    var className = cls.substring("Engine.".length());
                    requireEngineClass(source, entry, className);
                    var flags = inputs.getActorQueryFlags(path, className);
                    var excluded = !flags.collideActors().value()
                            || !flags.shadowCast().value()
                            || !flags.blockZeroExtentTraces().value();
                    actors.add(new Actor(
                            identity,
                            cls,
                            excluded ? Disposition.EXCLUDED : Disposition.UNSUPPORTED,
                            excluded
                                    ? "Exact post-load class admission flag excludes it"
                                    : "Authored flags require primitive and index qualification"));
                } else if (cls.equals("Engine.StaticMeshActor")
                        || cls.equals("Engine.L2MovableStaticMeshActor")
                        || cls.equals("Engine.Mover")) {
                    var className = cls.substring("Engine.".length());
                    requireEngineClass(source, entry, className);
                    var mover = cls.equals("Engine.Mover");
                    var facts = mover
                            ? inputs.getMover(path)
                            : cls.equals("Engine.L2MovableStaticMeshActor")
                                    ? inputs.getMovableMesh(path)
                                    : inputs.getMesh(path);
                    var collision = facts.collision();
                    if (mover && inputs.getMoverDamageGrade(path).value() == 6) {
                        actors.add(new Actor(
                                identity,
                                cls,
                                Disposition.EXCLUDED,
                                "Mover DamageGrade=6 disables its native ShouldTrace"));
                        continue;
                    }
                    if (!collision.collideActors().value()
                            || (!mover && !collision.shadowCast().value())
                            || !collision.blockZeroExtentTraces().value()
                            || collision.deleteMe().value()
                            || collision.pendingDelete().value()) {
                        actors.add(new Actor(
                                identity,
                                cls,
                                Disposition.EXCLUDED,
                                "Effective " + className + " index/admission flag excludes it"));
                        continue;
                    }
                    if (collision.useCylinderCollision().value()
                            || !facts.mesh().value().isNull()
                            || !facts.meshContainer().value().isNull()) {
                        throw new IOException(
                                className + " selects a cylinder or higher-priority Mesh/MeshContainer primitive");
                    }
                    var reference = facts.staticMesh().value();
                    if (reference.isNull() || !reference.declaredClass().equals("Engine.StaticMesh")) {
                        throw new IOException("Authored StaticMesh reference has no exact Engine.StaticMesh identity");
                    }
                    P152FreshHashQuery.checkCancelled(cancelled);
                    var assetSource = resolvePackage(root, source, reference.index(), assetFiles, packages, cancelled);
                    var meshExport = assetExport(assetSource.bytes(), reference.objectPath(), cancelled);
                    var assetIdentity =
                            new Identity(assetSource.identity().sha256(), meshExport.index(), reference.objectPath());
                    var capturedGeometry = geometry.get(assetIdentity);
                    if (capturedGeometry == null) {
                        nonIndexGaps.addAll(editorAssetLoadGaps(assetSource.bytes(), meshExport));
                        P152FreshHashQuery.checkCancelled(cancelled);
                        var mesh = P152ActorDomainMesh.read(assetSource.bytes(), meshExport, cancelled);
                        P152FreshHashQuery.checkCancelled(cancelled);
                        var asset = new Asset(
                                assetIdentity,
                                mesh.internalVersion(),
                                mesh.collision().faces().size(),
                                mesh.collision().nodes().size(),
                                assetSource.bytes().getObjectPath(mesh.simpleCollisionModel()));
                        capturedGeometry = new Geometry(asset, mesh);
                        geometry.put(assetIdentity, capturedGeometry);
                    }
                    var matrices = toTransformPair(facts);
                    var bounds = transform(capturedGeometry.mesh().primitiveBounds(), matrices.getLocalToWorld());
                    if (capturedGeometry.mesh().simpleCollisionBounds() != null) {
                        bounds = union(
                                bounds,
                                transform(capturedGeometry.mesh().simpleCollisionBounds(), matrices.getLocalToWorld()));
                    }
                    var expanded = expand(bounds, 4.2f);
                    var trace = new NativeStaticMeshTrace(
                            capturedGeometry.mesh().collision(),
                            new MatrixPair(matrices.getWorldToLocal(), matrices.getLocalToWorld()),
                            false);
                    P152FreshHashQuery.checkCancelled(cancelled);
                    placements.add(new Placement(
                            identity, capturedGeometry, trace, bounds, expanded, matrices.getLocalToWorld()));
                    actors.add(new Actor(
                            identity,
                            cls,
                            Disposition.CAPTURED_MESH,
                            "Saved shadow collision tree and complete primitive box captured; native index"
                                    + " admission unresolved"));
                } else {
                    actors.add(new Actor(
                            identity,
                            cls,
                            Disposition.UNSUPPORTED,
                            "Class lifecycle, admission and selected primitive are not qualified"));
                }
            } catch (IOException | IllegalArgumentException failure) {
                actors.add(new Actor(
                        identity,
                        cls,
                        Disposition.UNSUPPORTED,
                        failure instanceof FileSystemException
                                ? "Referenced package I/O failed: "
                                        + failure.getClass().getSimpleName()
                                : failure.getMessage()));
            }
        }
        for (var actor : actors) {
            P152FreshHashQuery.checkCancelled(cancelled);
            if (actor.disposition() == Disposition.UNSUPPORTED) {
                nonIndexGaps.add(actor.identity().objectPath() + ": " + actor.detail());
            }
        }
        inputs.seal();
        var unresolved = new ArrayList<String>();
        unresolved.add(INDEX_GAP);
        unresolved.add(NEIGHBOR_GAP);
        unresolved.addAll(nonIndexGaps);
        var sources = new ArrayList<>(packages.values());
        var report = new Report(
                new Identity(
                        mapFile.identity().sha256(), level.entry.index(), source.getObjectPath(level.entry.index())),
                new Identity(mapFile.identity().sha256(), current.index(), currentPath),
                level.actors.size(),
                nulls,
                actors,
                geometry.values().stream().map(Geometry::asset).toList(),
                sources.stream().map(Captured::receipt).toList(),
                inputs.getReceipt(),
                unresolved);
        var result = new P152ActorDomain(report, placements, sources, inputs, nonIndexGaps, currentLevelInfoExcluded);
        result.verifySourcesUnchanged(cancelled);
        P152FreshHashQuery.checkCancelled(cancelled);
        return result;
    }

    static List<String> editorAssetLoadGaps(PackageBytes source, ExportEntry entry) throws IOException {
        requirePackageOuters(source, entry.outer());
        var result = new ArrayList<String>();
        for (var current = entry;
                current != null;
                current = current.outer() == 0 ? null : source.exports.get(current.outer() - 1)) {
            if ((current.flags() & 0x40000) == 0 || (current.flags() & 0x400000) != 0) {
                result.add(source.getObjectPath(current.index())
                        + ": Export is not admitted by the fresh editor load flags");
            }
        }
        return List.copyOf(result);
    }

    static Vector transformPoint(Vector v, float[] m) {
        return new Vector(
                m[4] * v.y() + m[0] * v.x() + m[8] * v.z() + m[12],
                m[1] * v.x() + m[5] * v.y() + m[9] * v.z() + m[13],
                m[2] * v.x() + m[6] * v.y() + m[10] * v.z() + m[14]);
    }

    static void requireEngineClass(PackageBytes source, ExportEntry entry, String name) throws IOException {
        if (entry.classIndex() >= 0) {
            throw new IOException("Native capture requires an imported Engine." + name + " class");
        }
        var cls = source.imports.get(-entry.classIndex() - 1);
        if (!source.getName(cls.classPackage()).equals("Core")
                || !source.getName(cls.className()).equals("Class")
                || !source.getName(cls.objectName()).equals(name)
                || cls.outer() >= 0) {
            throw new IOException("Native capture requires exact Engine." + name);
        }
        var root = source.imports.get(-cls.outer() - 1);
        if (!source.getName(root.classPackage()).equals("Core")
                || !source.getName(root.className()).equals("Package")
                || !source.getName(root.objectName()).equals("Engine")
                || root.outer() != 0) {
            throw new IOException("Native capture class package identity is not Engine");
        }
    }

    static Captured resolvePackage(
            Path root,
            PackageBytes map,
            int ref,
            Map<String, List<Path>> assetFiles,
            Map<String, Captured> packages,
            BooleanSupplier cancelled)
            throws IOException {
        P152FreshHashQuery.checkCancelled(cancelled);
        if (ref > 0) {
            requirePackageOuters(map, map.exports.get(ref - 1).outer());
            return packages.get(map.packageName.toLowerCase(Locale.ROOT));
        }
        map.checkReference(ref);
        var asset = map.imports.get(-ref - 1);
        if (!map.getName(asset.classPackage()).equals("Engine")
                || !map.getName(asset.className()).equals("StaticMesh")) {
            throw new IOException("Mesh import has a non-StaticMesh type");
        }
        var seen = new HashSet<Integer>();
        var outer = asset.outer();
        String name = null;
        while (outer != 0) {
            P152FreshHashQuery.checkCancelled(cancelled);
            map.checkReference(outer);
            if (outer > 0 || !seen.add(outer)) {
                throw new IOException("Unaudited mesh import outer chain");
            }
            var group = map.imports.get(-outer - 1);
            if (!map.getName(group.classPackage()).equals("Core")
                    || !map.getName(group.className()).equals("Package")) {
                throw new IOException("Mesh import outer is not a Core.Package");
            }
            name = map.getName(group.objectName());
            outer = group.outer();
        }
        if (name == null || name.contains(".") || name.contains("/") || name.contains("\\")) {
            throw new IOException("Mesh import has no simple package identity");
        }
        var key = name.toLowerCase(Locale.ROOT);
        var existing = packages.get(key);
        if (existing != null) {
            return existing;
        }
        var matches = assetFiles.getOrDefault(key, List.of());
        if (matches.size() != 1) {
            throw new IOException("Mesh package is missing or ambiguous: " + name);
        }
        var file = read(root, matches.getFirst(), cancelled);
        var total = packages.values().stream().mapToLong(Captured::length).sum() + file.length();
        if (total > MAX_CAPTURE_BYTES) {
            throw new IOException("Actor-domain package capture exceeds memory bounds");
        }
        packages.put(key, file);
        return file;
    }

    static ExportEntry assetExport(PackageBytes source, String path, BooleanSupplier cancelled) throws IOException {
        ExportEntry found = null;
        for (var entry : source.exports) {
            P152FreshHashQuery.checkCancelled(cancelled);
            if (source.getObjectPath(entry.index()).equalsIgnoreCase(path)) {
                if (found != null) {
                    throw new IOException("Ambiguous StaticMesh object identity: " + path);
                }
                found = entry;
            }
        }
        if (found == null) {
            throw new IOException("Referenced StaticMesh export is missing: " + path);
        }
        requireEngineClass(source, found, "StaticMesh");
        requirePackageOuters(source, found.outer());
        return found;
    }

    static Map<String, List<Path>> assetFiles(Path root, BooleanSupplier cancelled) throws IOException {
        P152FreshHashQuery.checkCancelled(cancelled);
        var result = new HashMap<String, List<Path>>();
        var folder = root.resolve("StaticMeshes");
        if (!Files.isDirectory(folder)) {
            return result;
        }
        try (var files = Files.list(folder)) {
            var iterator = files.iterator();
            while (iterator.hasNext()) {
                P152FreshHashQuery.checkCancelled(cancelled);
                var path = iterator.next();
                var name = path.getFileName().toString();
                if (Files.isRegularFile(path) && name.toLowerCase(Locale.ROOT).endsWith(".usx")) {
                    var key = name.substring(0, name.length() - 4).toLowerCase(Locale.ROOT);
                    result.computeIfAbsent(key, _ -> new ArrayList<>()).add(path);
                }
            }
        }
        return result;
    }

    static Captured read(Path root, Path path, BooleanSupplier cancelled) throws IOException {
        P152FreshHashQuery.checkCancelled(cancelled);
        var logical = path.toAbsolutePath().normalize();
        var real = logical.toRealPath();
        if (!real.startsWith(root)
                || !logical.getFileName()
                        .toString()
                        .equalsIgnoreCase(real.getFileName().toString())) {
            throw new IOException("Actor capture source has an unaudited path/package identity");
        }
        var length = Files.size(logical);
        if (length <= 0 || length > MAX_PACKAGE_BYTES) {
            throw new IOException("Actor capture package exceeds size bounds");
        }
        var bytes = P152CaptureIo.read(logical, (int) MAX_PACKAGE_BYTES, cancelled);
        if (bytes.length != length || !logical.toRealPath().equals(real)) {
            throw new IOException("Actor capture source changed while reading");
        }
        var identity = new P152EffectiveInputs.Source(logical, real, P152CaptureIo.hash(bytes, cancelled));
        var source = new PackageBytes(logical, bytes);
        P152FreshHashQuery.checkCancelled(cancelled);
        return new Captured(logical, identity, source, length);
    }

    static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static NativeActorTransformPair toTransformPair(StaticMeshInputs facts) {
        var p = facts.placement();
        var location = p.location().value();
        var pivot = p.prePivot().value();
        var rotation = p.rotation().value();
        var scale = p.drawScale3d().value();
        return new NativeActorTransformPair(new Parameters(
                new NativeActorTransformPair.Vector(location.x(), location.y(), location.z()),
                new NativeActorTransformPair.Vector(pivot.x(), pivot.y(), pivot.z()),
                new Rotation(rotation.pitch(), rotation.yaw(), rotation.roll()),
                p.drawScale().value(),
                new NativeActorTransformPair.Vector(scale.x(), scale.y(), scale.z())));
    }

    static Box transform(Box box, float[] m) {
        var min = new float[] {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY};
        var max = new float[] {Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
        for (var i = 0; i < 8; i++) {
            var x = (i & 1) == 0 ? box.min().x() : box.max().x();
            var y = (i & 2) == 0 ? box.min().y() : box.max().y();
            var z = (i & 4) == 0 ? box.min().z() : box.max().z();
            for (var axis = 0; axis < 3; axis++) {
                var value = m[4 + axis] * y + m[axis] * x + m[8 + axis] * z + m[12 + axis];
                if (!Float.isFinite(value)) {
                    throw new IllegalArgumentException("Transformed native box exceeds float range");
                }
                min[axis] = Math.min(min[axis], value);
                max[axis] = Math.max(max[axis], value);
            }
        }
        return new Box(new Vector(min[0], min[1], min[2]), new Vector(max[0], max[1], max[2]), 1);
    }

    static Box union(Box first, Box second) {
        return new Box(
                new Vector(
                        min(first.min().x(), second.min().x()),
                        min(first.min().y(), second.min().y()),
                        min(first.min().z(), second.min().z())),
                new Vector(
                        max(first.max().x(), second.max().x()),
                        max(first.max().y(), second.max().y()),
                        max(first.max().z(), second.max().z())),
                1);
    }

    public Report getReport() {
        return report;
    }

    MapInputs getMapInputs() {
        var result = inputs.getMapInputs();
        if (result == null) {
            throw new IllegalStateException("Invented actor inputs have no authenticated map profile");
        }
        return result;
    }

    List<String> getNonIndexGaps() {
        return nonIndexGaps;
    }

    boolean isCurrentLevelInfoExcluded() {
        return currentLevelInfoExcluded;
    }

    public void verifySourcesUnchanged() throws IOException {
        verifySourcesUnchanged(() -> false);
    }

    public void verifySourcesUnchanged(BooleanSupplier cancelled) throws IOException {
        Objects.requireNonNull(cancelled);
        P152FreshHashQuery.checkCancelled(cancelled);
        inputs.verify(cancelled);
        for (var source : captured) {
            source.identity().verifyUnchanged(cancelled);
        }
        P152FreshHashQuery.checkCancelled(cancelled);
    }

    public Query query(Vector start, Vector end) {
        requireFinite(start);
        requireFinite(end);
        var delta = new Vector(end.x() - start.x(), end.y() - start.y(), end.z() - start.z());
        requireFinite(delta);
        var lengthSquared = delta.x() * delta.x() + delta.y() * delta.y() + delta.z() * delta.z();
        if (!Float.isFinite(lengthSquared) || lengthSquared == 0) {
            throw new IllegalArgumentException("A finite nonzero actor query is required");
        }
        var hits = new ArrayList<HitEvidence>();
        List<String> failures = null;
        var misses = 0;
        for (var placement : placements) {
            try {
                var hit = placement.trace().findFirstHit(start, end, FLAGS);
                if (hit.isEmpty()) {
                    misses++;
                    continue;
                }
                var value = hit.orElseThrow();
                var surface = new Vector(
                        start.x() + delta.x() * value.surfaceFraction(),
                        start.y() + delta.y() * value.surfaceFraction(),
                        start.z() + delta.z() * value.surfaceFraction());
                requireFinite(surface);
                var box = placement.worldBounds();
                var margin = Math.min(
                        Math.min(
                                Math.min(surface.x() - box.min().x(), box.max().x() - surface.x()),
                                Math.min(surface.y() - box.min().y(), box.max().y() - surface.y())),
                        Math.min(surface.z() - box.min().z(), box.max().z() - surface.z()));
                if (!Float.isFinite(margin)) {
                    throw new IllegalArgumentException("Surface-box margin exceeds float range");
                }
                hits.add(new HitEvidence(
                        placement.actor(),
                        placement.geometry().asset().identity(),
                        value,
                        new BoundsEvidence(
                                box,
                                placement.expandedBounds(),
                                surface,
                                margin,
                                isInsideWorld(placement.expandedBounds()),
                                true)));
            } catch (IllegalArgumentException failure) {
                if (failures == null) {
                    failures = new ArrayList<>(report.unresolved());
                }
                failures.add(placement.actor().objectPath() + ": " + failure.getMessage());
            }
        }
        return new Query(hits, placements.size(), misses, failures == null ? report.unresolved() : failures);
    }

    List<Triangle> collectCollisionAdmittedRenderTriangles(BooleanSupplier cancelled) throws IOException {
        verifySourcesUnchanged(cancelled);
        var local = new HashMap<Identity, List<Triangle>>();
        var result = new ArrayList<Triangle>();
        for (var placement : placements) {
            P152FreshHashQuery.checkCancelled(cancelled);
            var identity = placement.geometry.asset.identity;
            var triangles = local.get(identity);
            if (triangles == null) {
                var source = captured.stream()
                        .filter(value -> value.identity.sha256().equals(identity.packageSha256))
                        .findFirst()
                        .orElseThrow(() -> new IOException("Missing captured render mesh package"));
                triangles = P152ActorDomainMesh.readRenderTriangles(
                        source.bytes, source.bytes.exports.get(identity.reference - 1), cancelled);
                local.put(identity, triangles);
            }
            if ((long) result.size() + triangles.size() > 1_000_000) {
                throw new IOException("Placed shadow triangle count exceeds editor bounds");
            }
            for (var triangle : triangles) {
                P152FreshHashQuery.checkCancelled(cancelled);
                result.add(new Triangle(
                        placement.actor.reference,
                        triangle.part(),
                        triangle.face(),
                        transformPoint(triangle.a(), placement.localToWorld),
                        transformPoint(triangle.b(), placement.localToWorld),
                        transformPoint(triangle.c(), placement.localToWorld)));
            }
        }
        verifySourcesUnchanged(cancelled);
        return List.copyOf(result);
    }

    private static void requirePackageOuters(PackageBytes source, int outer) throws IOException {
        var seen = new HashSet<Integer>();
        while (outer != 0) {
            source.checkReference(outer);
            if (outer < 0 || !seen.add(outer)) {
                throw new IOException("Unaudited local mesh outer chain");
            }
            var entry = source.exports.get(outer - 1);
            if (entry.classIndex() >= 0) {
                throw new IOException("Mesh group is not an imported Core.Package class");
            }
            var cls = source.imports.get(-entry.classIndex() - 1);
            if (!source.getName(cls.classPackage()).equals("Core")
                    || !source.getName(cls.className()).equals("Class")
                    || !source.getName(cls.objectName()).equals("Package")
                    || cls.outer() >= 0) {
                throw new IOException("Mesh group class is not Core.Package");
            }
            var root = source.imports.get(-cls.outer() - 1);
            if (!source.getName(root.classPackage()).equals("Core")
                    || !source.getName(root.className()).equals("Package")
                    || !source.getName(root.objectName()).equals("Core")
                    || root.outer() != 0) {
                throw new IOException("Mesh group class has an unaudited Core identity");
            }
            outer = entry.outer();
        }
    }

    private static float min(float first, float second) {
        return second < first ? second : first;
    }

    private static float max(float first, float second) {
        return second > first ? second : first;
    }

    private static Box expand(Box box, float amount) {
        var min = new Vector(
                box.min().x() - amount, box.min().y() - amount, box.min().z() - amount);
        var max = new Vector(
                box.max().x() + amount, box.max().y() + amount, box.max().z() + amount);
        requireFinite(min);
        requireFinite(max);
        return new Box(min, max, 1);
    }

    private static boolean isInsideWorld(Box box) {
        return box.min().x() > -360448f
                && box.min().y() > -360448f
                && box.min().z() > -360448f
                && box.max().x() < 360448f
                && box.max().y() < 360448f
                && box.max().z() < 360448f;
    }

    private static void requireFinite(Vector value) {
        if (value == null || !Float.isFinite(value.x()) || !Float.isFinite(value.y()) || !Float.isFinite(value.z())) {
            throw new IllegalArgumentException("Non-finite actor query point");
        }
    }
}
