package com.rost.cruma.viewer.editing;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter.Feature;
import com.rost.cruma.core.editing.P152TerrainExport.Evidence;
import com.rost.cruma.core.editing.PackageInventory;
import com.rost.cruma.core.editing.PackageInventory.Definition;
import com.rost.cruma.core.editing.TerrainHeightPatch;
import com.rost.cruma.core.unreal.ClientSearchPaths;
import com.rost.cruma.core.unreal.ClientSearchPaths.Entry;
import com.rost.cruma.core.unreal.EffectClasses;
import com.rost.cruma.core.unreal.UActor.StaticMeshActor;
import com.rost.cruma.core.unreal.UArchive;
import com.rost.cruma.core.unreal.UArchiveLoader;
import com.rost.cruma.core.unreal.UArchiveLoader.USearchConfig;
import com.rost.cruma.core.unreal.UDecryptor;
import com.rost.cruma.core.unreal.ULevel;
import com.rost.cruma.core.world.GameScenes;
import com.rost.cruma.core.world.MapScenes;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;

public final class PackageExportService {
    private static final Map<String, String> DESTINATIONS = Map.of(
            "unr",
            "Maps",
            "utx",
            "Textures",
            "usx",
            "StaticMeshes",
            "ukx",
            "Animations",
            "usk",
            "Animations",
            "u",
            "System",
            "uax",
            "Sounds");
    private static final Set<String> NATIVE_CLASSES = Set.of(
            "core.package",
            "core.class",
            "core.textbuffer",
            "core.function",
            "core.state",
            "core.struct",
            "core.const",
            "core.enum",
            "core.boolproperty",
            "core.byteproperty",
            "core.intproperty",
            "core.floatproperty",
            "core.strproperty",
            "core.nameproperty",
            "core.objectproperty",
            "core.classproperty",
            "core.arrayproperty",
            "core.structproperty",
            "engine.level",
            "engine.model",
            "engine.polys",
            "engine.terrainsector",
            "engine.staticmeshinstance",
            "engine.mesh",
            "engine.skeletalmesh",
            "engine.vertmesh",
            "engine.sound");
    private static final List<USearchConfig> SEARCH = List.of(
            new USearchConfig("Maps", "unr"),
            new USearchConfig("StaticMeshes", "usx"),
            new USearchConfig("Textures", "utx"),
            new USearchConfig("SysTextures", "utx"),
            new USearchConfig("Animations", "ukx"),
            new USearchConfig("Animations", "usk"),
            new USearchConfig("System", "u"),
            new USearchConfig("Sounds", "uax"));

    private static final String SCENE_PACKAGE = "LineageSceneInfo";

    private final Path clientRoot;
    private final PublishObserver observer;

    public PackageExportService(Path clientRoot) {
        this(clientRoot, new PublishObserver() {});
    }

    PackageExportService(Path clientRoot, PublishObserver observer) {
        this.clientRoot = clientRoot.toAbsolutePath().normalize();
        this.observer = observer;
    }

    public record MapInput(Path source, String sourceHash, byte[] bytes) {}

    public record AssetInput(Path source, String sourceHash) {}

    public record DerivedAssetInput(
            Path source, String sourceHash, String filename, byte[] bytes, List<AssetInput> origins) {
        public DerivedAssetInput(Path source, String sourceHash, String filename, byte[] bytes) {
            this(source, sourceHash, filename, bytes, List.of());
        }

        public DerivedAssetInput {
            if (source == null
                    || sourceHash == null
                    || !sourceHash.matches("[0-9a-f]{64}")
                    || filename == null
                    || !filename.matches("[A-Za-z0-9_]{1,64}\\.(utx|usx|ukx|uax|u)")
                    || filename.matches("(?i)(con|prn|aux|nul|com[1-9]|lpt[1-9])\\..*")
                    || bytes == null
                    || bytes.length == 0) {
                throw new IllegalArgumentException(
                        "A derived asset needs a source fingerprint, package filename and bytes");
            }
            bytes = bytes.clone();
            origins = List.copyOf(origins);
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    public record ReplacedAssetInput(Path source, String sourceHash, byte[] bytes) {
        public ReplacedAssetInput {
            if (source == null
                    || sourceHash == null
                    || !sourceHash.matches("[0-9a-f]{64}")
                    || bytes == null
                    || bytes.length == 0) {
                throw new IllegalArgumentException(
                        "A replaced package needs its source, a source fingerprint and bytes");
            }
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    public record PackageSummary(
            String name,
            String path,
            String sha256,
            int fileVersion,
            int licenseVersion,
            int container,
            String origin) {}

    public record Dependency(
            String from, String object, String expectedClass, String resolvedClass, String resolution) {}

    record NativePreparation(
            String owner,
            TerrainHeightPatch operation,
            Map<String, String> sources,
            Map<String, String> outputs,
            Evidence numeric) {
        NativePreparation {
            if (owner == null || numeric == null || operation == null || operation.size() == 0) {
                throw new IllegalArgumentException("Native evidence needs a nonempty operation");
            }
            sources = Map.copyOf(sources);
            outputs = Map.copyOf(outputs);
            if (sources.isEmpty()
                    || outputs.isEmpty()
                    || sources.values().stream().anyMatch(value -> !value.matches("[0-9a-f]{64}"))
                    || outputs.values().stream().anyMatch(value -> !value.matches("[0-9a-f]{64}"))) {
                throw new IllegalArgumentException("Native evidence needs source and output SHA-256 bindings");
            }
        }

        static NativePreparation capture(
                Path root,
                String owner,
                TerrainHeightPatch operation,
                MapInput map,
                List<DerivedAssetInput> derived,
                Evidence numeric)
                throws IOException {
            root = root.toAbsolutePath().normalize();
            var sources = new LinkedHashMap<String, String>();
            var outputs = new LinkedHashMap<String, String>();
            sources.put(relativeSource(root, map.source()), map.sourceHash());
            outputs.put(toPortable(relativePath(map.source())), hash(map.bytes()));
            for (var asset : derived) {
                var source = relativeSource(root, asset.source());
                var previous = sources.putIfAbsent(source, asset.sourceHash());
                if (previous != null && !previous.equals(asset.sourceHash())) {
                    throw new IOException("Conflicting native evidence source revisions");
                }
                var target = toPortable(relativePath(Path.of(asset.filename())));
                if (outputs.putIfAbsent(target, hash(asset.bytes())) != null) {
                    throw new IOException("Duplicate native evidence output");
                }
            }
            return new NativePreparation(owner, operation, sources, outputs, numeric);
        }

        private Map<String, Object> manifest() {
            var samples = new ArrayList<Map<String, Integer>>();
            for (var i = 0; i < operation.size(); i++) {
                samples.add(Map.of(
                        "x",
                        operation.getIndex(i) % operation.getWidth(),
                        "y",
                        operation.getIndex(i) / operation.getWidth(),
                        "before",
                        operation.getBefore(i),
                        "after",
                        operation.getAfter(i)));
            }
            return Map.of(
                    "role",
                    "informational-only",
                    "operation",
                    Map.of(
                            "owner",
                            owner,
                            "width",
                            operation.getWidth(),
                            "height",
                            operation.getHeight(),
                            "samples",
                            List.copyOf(samples)),
                    "sources",
                    sources,
                    "outputs",
                    outputs,
                    "numeric",
                    numeric,
                    "rayScope",
                    "original-and-edited",
                    "bspProjectionScope",
                    "selected-map-only");
        }

        private static String relativeSource(Path root, Path source) throws IOException {
            source = source.toAbsolutePath().normalize();
            if (!source.startsWith(root)) {
                throw new IOException("Native evidence source is outside the client directory");
            }
            return toPortable(root.relativize(source));
        }

        private static String toPortable(Path relative) {
            return relative.toString().replace('\\', '/');
        }
    }

    public static final class Plan {
        private final Path clientRoot;
        private final Map<Path, byte[]> files;
        private final Map<Path, String> fingerprints;
        private final List<PackageSummary> packages;
        private final List<PackageSummary> requirements;
        private final List<Dependency> dependencies;
        private final List<String> errors;
        private final List<String> notes;
        private final NativePreparation nativePreparation;
        private final List<String> lighting;
        private final List<String> lightingTips;
        private final List<Map<String, Object>> terrainReports;

        private Plan(
                Path clientRoot,
                Map<Path, byte[]> files,
                Map<Path, String> fingerprints,
                List<PackageSummary> packages,
                List<PackageSummary> requirements,
                List<Dependency> dependencies,
                List<String> errors,
                List<String> notes) {
            this(
                    clientRoot,
                    files,
                    fingerprints,
                    packages,
                    requirements,
                    dependencies,
                    errors,
                    notes,
                    null,
                    List.of(),
                    List.of(),
                    List.of());
        }

        private Plan(
                Path clientRoot,
                Map<Path, byte[]> files,
                Map<Path, String> fingerprints,
                List<PackageSummary> packages,
                List<PackageSummary> requirements,
                List<Dependency> dependencies,
                List<String> errors,
                List<String> notes,
                NativePreparation nativePreparation,
                List<String> lighting,
                List<String> lightingTips,
                List<Map<String, Object>> terrainReports) {
            this.lightingTips = List.copyOf(lightingTips);
            this.clientRoot = clientRoot;
            this.terrainReports = List.copyOf(terrainReports);
            this.files = Map.copyOf(files);
            this.fingerprints = Map.copyOf(fingerprints);
            this.packages = List.copyOf(packages);
            this.requirements = List.copyOf(requirements);
            this.dependencies = List.copyOf(dependencies);
            this.errors = List.copyOf(errors);
            this.notes = List.copyOf(notes);
            this.nativePreparation = nativePreparation;
            this.lighting = List.copyOf(lighting);
        }

        Plan withLighting(List<String> lines, List<String> tips) {
            return new Plan(
                    clientRoot,
                    files,
                    fingerprints,
                    packages,
                    requirements,
                    dependencies,
                    errors,
                    notes,
                    nativePreparation,
                    lines,
                    tips,
                    terrainReports);
        }

        public List<String> getLightingTips() {
            return lightingTips;
        }

        public List<String> getLighting() {
            return lighting;
        }

        Plan withNativePreparation(NativePreparation evidence) throws IOException {
            for (var source : evidence.sources().entrySet()) {
                var path = evidencePath(source.getKey());
                if (!source.getValue().equals(fingerprints.get(clientRoot.resolve(path)))) {
                    throw new IOException("Native evidence does not match the prepared source: " + source.getKey());
                }
            }
            for (var output : evidence.outputs().entrySet()) {
                evidencePath(output.getKey());
                if (packages.stream()
                        .noneMatch(item -> item.path().equals(output.getKey())
                                && item.sha256().equals(output.getValue())
                                && (item.origin().equals("map") || item.origin().equals("derived")))) {
                    throw new IOException("Native evidence does not match the prepared output: " + output.getKey());
                }
            }
            return new Plan(
                    clientRoot,
                    files,
                    fingerprints,
                    packages,
                    requirements,
                    dependencies,
                    errors,
                    notes,
                    evidence,
                    lighting,
                    lightingTips,
                    terrainReports);
        }

        Plan withTerrainReports(List<Map<String, Object>> reports) {
            return new Plan(
                    clientRoot,
                    files,
                    fingerprints,
                    packages,
                    requirements,
                    dependencies,
                    errors,
                    notes,
                    nativePreparation,
                    lighting,
                    lightingTips,
                    reports);
        }

        public List<Map<String, Object>> getTerrainReports() {
            return terrainReports;
        }

        public List<PackageSummary> getPackages() {
            return packages;
        }

        public List<PackageSummary> getRequirements() {
            return requirements;
        }

        public List<Dependency> getDependencies() {
            return dependencies;
        }

        public List<String> getErrors() {
            return errors;
        }

        public List<String> getNotes() {
            return notes;
        }

        public long getSize(PackageSummary item) {
            var bytes = files.get(Path.of(item.path()));
            return bytes == null ? 0 : bytes.length;
        }

        public boolean isValid() {
            return errors.isEmpty();
        }

        private static Path evidencePath(String value) throws IOException {
            var path = Path.of(value);
            if (path.isAbsolute()
                    || value.indexOf(':') >= 0
                    || value.indexOf('\\') >= 0
                    || path.getNameCount() < 2
                    || !path.normalize().equals(path)
                    || path.startsWith("..")) {
                throw new IOException("Native evidence paths must be relative package paths");
            }
            return path;
        }
    }

    @FunctionalInterface
    public interface SourceVerifier {
        void verify() throws IOException;
    }

    interface PublishObserver {
        default void beforeWrite(Path relative) throws IOException {}

        default void beforePublish(Path staging) throws IOException {}
    }

    public static String fileFingerprint(Path file) throws IOException {
        var digest = digest();
        try (var input = new DigestInputStream(Files.newInputStream(file), digest)) {
            input.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public Plan prepare(List<MapInput> maps, List<AssetInput> assets) throws IOException {
        return prepare(maps, assets, List.of());
    }

    public Plan prepare(List<MapInput> maps, List<AssetInput> assets, List<DerivedAssetInput> derivedAssets)
            throws IOException {
        return prepare(maps, assets, derivedAssets, List.of());
    }

    public Plan prepare(
            List<MapInput> maps,
            List<AssetInput> assets,
            List<DerivedAssetInput> derivedAssets,
            List<ReplacedAssetInput> replaced)
            throws IOException {
        return prepare(maps, assets, derivedAssets, replaced, List.of());
    }

    public Plan prepare(
            List<MapInput> maps,
            List<AssetInput> assets,
            List<DerivedAssetInput> derivedAssets,
            List<ReplacedAssetInput> replaced,
            List<AssetInput> clientPackages)
            throws IOException {
        var graph = new Graph(clientRoot);
        for (var input : replaced) {
            graph.add(input.source(), input.sourceHash(), input.bytes(), "replaced");
        }
        for (var input : maps) {
            graph.add(input.source(), input.sourceHash(), input.bytes(), "map");
        }
        for (var input : assets) {
            graph.add(input.source(), input.sourceHash(), null, "import");
        }
        for (var input : derivedAssets) {
            graph.addDerived(input);
        }
        for (var input : clientPackages) {
            graph.require(input.source(), input.sourceHash());
        }
        return graph.finish();
    }

    public List<Path> publish(Plan plan, Path directory) throws IOException {
        return publish(plan, directory, () -> false);
    }

    public List<Path> publish(Plan plan, Path directory, BooleanSupplier cancelled) throws IOException {
        return publish(plan, directory, cancelled, () -> {});
    }

    public List<Path> publish(Plan plan, Path directory, BooleanSupplier cancelled, SourceVerifier verifier)
            throws IOException {
        if (!clientRoot.equals(plan.clientRoot)) {
            throw new IOException("Export plan belongs to another client.");
        }
        if (!plan.isValid()) {
            throw new IOException(String.join("\n", plan.errors));
        }
        var output = directory.toAbsolutePath().normalize();
        validateOutput(output);
        checkCancelled(cancelled);
        if (plan.files.isEmpty()) {
            return List.of();
        }
        Files.createDirectories(output.getParent());
        validateOutput(output);
        var staging = Files.createTempDirectory(output.getParent(), ".cruma-export-");
        try {
            for (var entry : plan.files.entrySet()) {
                checkCancelled(cancelled);
                observer.beforeWrite(entry.getKey());
                var target = staging.resolve(entry.getKey());
                Files.createDirectories(target.getParent());
                writeDurably(target, entry.getValue());
            }
            checkCancelled(cancelled);
            validateStaged(staging, plan);
            var manifest = new LinkedHashMap<String, Object>();
            manifest.put("schema", "cruma.editor.export/v1");
            manifest.put("verification", "static imports of exported packages; referenced base-client objects");
            manifest.put("runtimeValidation", "Lineage II execution unverified; dynamic script lookups unverified");
            manifest.put("packages", plan.packages);
            manifest.put("clientRequirements", plan.requirements);
            manifest.put("dependencies", plan.dependencies);
            manifest.put("clientSources", clientSources(plan));
            if (!plan.notes.isEmpty()) {
                manifest.put("notes", plan.notes);
            }
            manifest.put(
                    "editSummary",
                    Map.of(
                            "changedMaps",
                                    plan.packages.stream()
                                            .filter(item -> item.origin().equals("map"))
                                            .map(PackageSummary::name)
                                            .toList(),
                            "importedPackages",
                                    plan.packages.stream()
                                            .filter(item -> item.origin().equals("import"))
                                            .map(PackageSummary::name)
                                            .toList(),
                            "derivedPackages",
                                    plan.packages.stream()
                                            .filter(item -> item.origin().equals("derived"))
                                            .map(PackageSummary::name)
                                            .toList(),
                            "replacedPackages",
                                    plan.packages.stream()
                                            .filter(item -> item.origin().equals("replaced"))
                                            .map(PackageSummary::name)
                                            .toList()));
            if (!plan.terrainReports.isEmpty()) {
                manifest.put("terrainEdits", plan.terrainReports);
            }
            if (plan.nativePreparation != null) {
                manifest.put("nativePreparation", plan.nativePreparation.manifest());
            }
            writeDurably(staging.resolve("manifest.json"), JSON.toJSONBytes(manifest, Feature.MapSortField));
            observer.beforePublish(staging);
            verifier.verify();
            for (var entry : plan.fingerprints.entrySet()) {
                checkCancelled(cancelled);
                if (!entry.getValue().equals(fileFingerprint(entry.getKey()))) {
                    throw new IOException("Package changed after export preparation: "
                            + entry.getKey().getFileName());
                }
            }
            verifyDerivedIdentities(plan);
            checkCancelled(cancelled);
            validateOutput(output);
            Files.move(staging, output);
            return plan.files.keySet().stream().sorted().map(output::resolve).toList();
        } finally {
            if (Files.exists(staging)) {
                try (var paths = Files.walk(staging)) {
                    for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                        Files.deleteIfExists(path);
                    }
                }
            }
        }
    }

    private void verifyDerivedIdentities(Plan plan) throws IOException {
        var derived = plan.packages.stream()
                .filter(item -> item.origin().equals("derived"))
                .toList();
        if (derived.isEmpty()) {
            return;
        }
        var installed = new Graph(clientRoot).installed;
        for (var item : derived) {
            if (installed.containsKey(key(item.name()))) {
                throw new IOException("Derived package identity appeared in the source client: " + item.name());
            }
        }
    }

    private void validateOutput(Path output) throws IOException {
        var ancestor = output;
        while (ancestor != null && !Files.exists(ancestor)) {
            ancestor = ancestor.getParent();
        }
        if (ancestor == null) {
            throw new IOException("Choose an existing output drive.");
        }
        var actual = ancestor.toRealPath().resolve(ancestor.relativize(output)).normalize();
        if (actual.startsWith(clientRoot.toRealPath())) {
            throw new IOException("Choose an output folder outside the source client.");
        }
        if (Files.exists(output)) {
            throw new IOException("The output directory already exists. Choose a new folder for this export.");
        }
    }

    private void validateStaged(Path staging, Plan plan) throws IOException {
        var staged = new HashMap<String, Path>();
        for (var summary : plan.packages) {
            staged.put(key(summary.name()), staging.resolve(summary.path()));
        }
        var archives = new UArchiveLoader(clientRoot, SEARCH) {
            private final Map<String, UArchive> overlays = new HashMap<>();

            @Override
            public UArchive loadArchive(String name) {
                var path = staged.get(key(name));
                if (path == null) {
                    return super.loadArchive(name);
                }
                return overlays.computeIfAbsent(
                        key(name), ignored -> new UArchive(name, UDecryptor.decrypt(path), this));
            }

            @Override
            public UArchive loadArchiveIfPresent(String name) {
                return staged.containsKey(key(name)) ? loadArchive(name) : super.loadArchiveIfPresent(name);
            }

            @Override
            public void clearArchives() {
                overlays.values().forEach(UArchive::close);
                overlays.clear();
                super.clearArchives();
            }
        };
        try {
            for (var summary : plan.packages) {
                var archive = archives.loadArchive(summary.name());
                if (archive == null
                        || archive.getHeader().getFileVersion() != summary.fileVersion()
                        || archive.getHeader().getLicenseVersion() != summary.licenseVersion()) {
                    throw new IOException("Prepared package did not reopen: " + summary.name());
                }
                if (summary.origin().equals("replaced") && key(summary.path()).endsWith(".unr")) {
                    MapScenes.read(archive);
                }
                if (summary.origin().equals("map")) {
                    verifyBspLightmaps(summary.name(), archive);
                    var actors = new ArrayList<Object>();
                    archive.loadObjects("StaticMeshActor", actors);
                    for (var actor : actors) {
                        if (actor instanceof StaticMeshActor meshActor && meshActor.getStaticMeshInstance() != null) {
                            var instance = meshActor.getStaticMeshInstance();
                            if (instance.getUnreadNativeBytes() != 0) {
                                throw new IOException(
                                        "StaticMeshInstance of " + meshActor.getSourcePath() + " has unread bytes");
                            }
                            instance.resolveLodInstance1();
                            instance.resolveLodInstance2();
                        }
                    }
                } else if (summary.path().endsWith(".u")) {
                    EffectClasses.list(archive);
                    if (summary.name().equalsIgnoreCase(SCENE_PACKAGE)) {
                        GameScenes.read(archive);
                    }
                }
            }
        } catch (RuntimeException invalid) {
            throw new IOException("Production reader rejected prepared packages: " + invalid.getMessage(), invalid);
        } finally {
            archives.clearArchives();
        }
    }

    private void verifyBspLightmaps(String name, UArchive staged) throws IOException {
        var error = lightmapError(staged);
        if (error == null) {
            return;
        }
        var installed = new UArchiveLoader(clientRoot, SEARCH);
        try {
            var original = installed.loadArchiveIfPresent(name);
            if (original != null && lightmapError(original) == null) {
                throw new IOException("BSP lightmaps of " + name + " do not parse after export: " + error);
            }
        } finally {
            installed.clearArchives();
        }
    }

    private static Map<String, String> clientSources(Plan plan) {
        var sources = new TreeMap<String, String>();
        var exported = new HashMap<String, String>();
        for (var path : plan.files.keySet()) {
            var portable = path.toString().replace('\\', '/');
            exported.put(key(portable), portable);
        }
        for (var entry : plan.fingerprints.entrySet()) {
            if (entry.getKey().startsWith(plan.clientRoot)) {
                var relative =
                        plan.clientRoot.relativize(entry.getKey()).toString().replace('\\', '/');
                var path = exported.get(key(relative));
                if (path != null) {
                    sources.put(path, entry.getValue());
                }
            }
        }
        return sources;
    }

    private static void checkCancelled(BooleanSupplier cancelled) throws InterruptedIOException {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Export cancelled. No packages were published.");
        }
    }

    private static void writeDurably(Path file, byte[] bytes) throws IOException {
        try (var channel = FileChannel.open(file, StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW)) {
            var buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
    }

    private static String lightmapError(UArchive archive) {
        var levels = new ArrayList<ULevel>();
        archive.loadObjects("Level", levels);
        if (levels.isEmpty() || levels.getFirst().getModel().isNull()) {
            return null;
        }
        var model = levels.getFirst().getModel().get();
        if (model == null || model.getZones() == null) {
            return null;
        }
        return model.getLightmapTailError();
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static String hash(byte[] bytes) {
        return HexFormat.of().formatHex(digest().digest(bytes));
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static String packageName(String object) {
        var dot = object.indexOf('.');
        return dot < 0 ? object : object.substring(0, dot);
    }

    private static Path relativePath(Path source) throws IOException {
        var filename = source.getFileName().toString();
        var dot = filename.lastIndexOf('.');
        var directory = dot < 0 ? null : DESTINATIONS.get(key(filename.substring(dot + 1)));
        if (directory == null || dot == 0) {
            throw new IOException("Unsupported package destination: " + filename);
        }
        return Path.of(directory, filename);
    }

    private static final class Graph {
        private final Path root;
        private final List<Entry> searchPaths;
        private final Map<String, List<Path>> installed = new HashMap<>();
        private final Map<String, Node> nodes = new LinkedHashMap<>();
        private final Map<Path, byte[]> files = new LinkedHashMap<>();
        private final Map<Path, String> fingerprints = new LinkedHashMap<>();
        private final List<Dependency> dependencies = new ArrayList<>();
        private final Set<String> errors = new LinkedHashSet<>();
        private final Set<String> notes = new LinkedHashSet<>();
        private final Set<String> sourceImports = new HashSet<>();
        private final Set<String> visited = new HashSet<>();
        private final Set<String> absent = new HashSet<>();

        Graph(Path root) throws IOException {
            this.root = root;
            searchPaths = ClientSearchPaths.read(root);
            try (var directories = Files.list(root)) {
                for (var directory :
                        directories.filter(Files::isDirectory).sorted().toList()) {
                    var extensions = new HashSet<String>();
                    for (var config : SEARCH) {
                        if (config.directory()
                                .equalsIgnoreCase(directory.getFileName().toString())) {
                            extensions.add(config.extension());
                        }
                    }
                    if (extensions.isEmpty()) {
                        continue;
                    }
                    try (var entries = Files.list(directory)) {
                        for (var file :
                                entries.filter(Files::isRegularFile).sorted().toList()) {
                            var filename = file.getFileName().toString();
                            var dot = filename.lastIndexOf('.');
                            if (dot > 0 && extensions.contains(key(filename.substring(dot + 1)))) {
                                installed
                                        .computeIfAbsent(key(filename.substring(0, dot)), ignored -> new ArrayList<>())
                                        .add(file);
                            }
                        }
                    }
                }
            }
        }

        private record Node(Path file, PackageInventory inventory, String sha256, String origin) {}

        void add(Path source, String expectedHash, byte[] prepared, String origin) throws IOException {
            source = source.toAbsolutePath().normalize();
            var sourceBytes = Files.readAllBytes(source);
            if (!hash(sourceBytes).equals(expectedHash)) {
                errors.add("Imported or source package changed during editing: " + source.getFileName());
                return;
            }
            if (!pinFingerprint(source, expectedHash)) {
                return;
            }
            var bytes = prepared == null ? sourceBytes : prepared.clone();
            var inventory = PackageInventory.inspect(source, bytes);
            if (origin.equals("replaced")) {
                rememberImports(PackageInventory.inspect(source, sourceBytes));
            }
            var normalized = key(inventory.name());
            var previous = nodes.get(normalized);
            if (previous != null) {
                if (!previous.sha256.equals(hash(bytes))
                        || !previous.file.getFileName().equals(source.getFileName())) {
                    errors.add("Conflicting supplied package identity: " + inventory.name());
                }
                return;
            }
            if (origin.equals("import")) {
                for (var existing : installed.getOrDefault(normalized, List.of())) {
                    if (!fileFingerprint(existing).equals(expectedHash)) {
                        errors.add("Imported package conflicts with installed client package: " + inventory.name());
                    }
                }
            }
            var destination = relativePath(source);
            if (files.keySet().stream().anyMatch(path -> path.toString().equalsIgnoreCase(destination.toString()))) {
                errors.add("Conflicting output filename: " + destination.getFileName());
                return;
            }
            files.put(destination, bytes);
            nodes.put(normalized, new Node(source, inventory, hash(bytes), origin));
        }

        void require(Path source, String expectedHash) throws IOException {
            source = source.toAbsolutePath().normalize();
            var filename = source.getFileName().toString();
            var dot = filename.lastIndexOf('.');
            var name = dot > 0 ? filename.substring(0, dot) : filename;
            var loaded = false;
            for (var file : installed.getOrDefault(key(name), List.of())) {
                try {
                    loaded |= Files.isSameFile(file, source);
                } catch (IOException missing) {
                }
            }
            if (!loaded) {
                add(source, expectedHash, null, "import");
                return;
            }
            if (!Files.isRegularFile(source) || !fileFingerprint(source).equals(expectedHash)) {
                errors.add("Imported or source package changed during editing: " + source.getFileName());
                return;
            }
            if (pinFingerprint(source, expectedHash)) {
                resolve(name, name);
            }
        }

        void addDerived(DerivedAssetInput input) throws IOException {
            var source = input.source().toAbsolutePath().normalize();
            var sourceBytes = Files.readAllBytes(source);
            if (!hash(sourceBytes).equals(input.sourceHash())) {
                errors.add("Derived asset source changed during editing: " + source.getFileName());
                return;
            }
            if (!pinFingerprint(source, input.sourceHash())) {
                return;
            }
            for (var origin : input.origins()) {
                var file = origin.source().toAbsolutePath().normalize();
                var originBytes = Files.readAllBytes(file);
                if (!hash(originBytes).equals(origin.sourceHash())) {
                    errors.add("Derived asset source changed during editing: " + file.getFileName());
                    return;
                }
                if (!pinFingerprint(file, origin.sourceHash())) {
                    return;
                }
                rememberImports(PackageInventory.inspect(file, originBytes));
            }
            var logicalFile = Path.of(input.filename());
            var bytes = input.bytes();
            var inventory = PackageInventory.inspect(logicalFile, bytes);
            var normalized = key(inventory.name());
            var sourceInventory = PackageInventory.inspect(source, sourceBytes);
            rememberImports(sourceInventory);
            var sourceName = sourceInventory.name();
            if (normalized.equals(key(sourceName)) || installed.containsKey(normalized)) {
                errors.add("Derived package conflicts with a source or installed client identity: " + inventory.name());
                return;
            }
            if (nodes.containsKey(normalized)) {
                errors.add("Conflicting supplied package identity: " + inventory.name());
                return;
            }
            var destination = relativePath(logicalFile);
            if (files.keySet().stream().anyMatch(path -> path.toString().equalsIgnoreCase(destination.toString()))) {
                errors.add("Conflicting output filename: " + destination.getFileName());
                return;
            }
            files.put(destination, bytes);
            nodes.put(normalized, new Node(logicalFile, inventory, hash(bytes), "derived"));
        }

        Plan finish() throws IOException {
            for (var node : List.copyOf(nodes.values())) {
                visit(node, node.inventory.name());
            }
            var packages = new ArrayList<PackageSummary>();
            var requirements = new ArrayList<PackageSummary>();
            for (var node : nodes.values()) {
                var item = toSummary(node);
                (node.origin.equals("client") ? requirements : packages).add(item);
            }
            packages.sort(Comparator.comparing(PackageSummary::name));
            requirements.sort(Comparator.comparing(PackageSummary::name));
            return new Plan(
                    root,
                    files,
                    fingerprints,
                    packages,
                    requirements,
                    dependencies,
                    List.copyOf(errors),
                    List.copyOf(notes));
        }

        private void rememberImports(PackageInventory source) {
            for (var reference : source.imports()) {
                sourceImports.add(key(reference.path()));
            }
        }

        private boolean pinFingerprint(Path file, String hash) {
            var previous = fingerprints.putIfAbsent(file, hash);
            if (previous != null && !previous.equals(hash)) {
                errors.add("Conflicting source revisions during export preparation: " + file.getFileName());
                return false;
            }
            return true;
        }

        private PackageSummary toSummary(Node node) throws IOException {
            var path = node.origin.equals("client") ? root.relativize(node.file) : relativePath(node.file);
            return new PackageSummary(
                    node.inventory.name(),
                    path.toString().replace('\\', '/'),
                    node.sha256,
                    node.inventory.fileVersion(),
                    node.inventory.licenseVersion(),
                    node.inventory.container(),
                    node.origin);
        }

        private void visit(Node node, String chain) {
            if (node.origin.equals("client") || !visited.add(key(node.inventory.name()))) {
                return;
            }
            for (var reference : node.inventory.imports()) {
                var next = chain + " -> " + reference.path();
                var problems =
                        !node.origin.equals("map") && sourceImports.contains(key(reference.path())) ? notes : errors;
                var target = resolve(packageName(reference.path()), next, problems);
                if (target == null) {
                    continue;
                }
                if (!reference.path().contains(".")) {
                    dependencies.add(new Dependency(
                            node.inventory.name(),
                            reference.path(),
                            reference.expectedClass(),
                            "Core.Package",
                            "package"));
                } else {
                    var definitions = target.inventory.definitions(reference.path());
                    var matching = definitions.stream()
                            .filter(item -> item.classPath().equalsIgnoreCase(reference.expectedClass()))
                            .toList();
                    if (matching.isEmpty()) {
                        matching = definitions.stream()
                                .filter(item -> isCompatible(
                                        item.classPath(), reference.expectedClass(), next, new HashSet<>()))
                                .toList();
                    }
                    if (matching.isEmpty()
                            && reference.expectedClass().equalsIgnoreCase("Core.Class")
                            && NATIVE_CLASSES.contains(key(reference.path()))) {
                        dependencies.add(new Dependency(
                                node.inventory.name(),
                                reference.path(),
                                reference.expectedClass(),
                                "Core.Class",
                                key(reference.path()).startsWith("core.")
                                        ? "native Core registration"
                                        : "native Engine registration"));
                    } else if (definitions.isEmpty()) {
                        problems.add(next + ": exported object is missing.");
                    } else if (matching.isEmpty()) {
                        problems.add(next
                                + ": expected "
                                + reference.expectedClass()
                                + ", found "
                                + String.join(
                                        ", ",
                                        definitions.stream()
                                                .map(Definition::classPath)
                                                .toList())
                                + ".");
                    } else if (matching.size() > 1) {
                        problems.add(next + ": ambiguous exported object for class " + reference.expectedClass() + ".");
                    } else {
                        dependencies.add(new Dependency(
                                node.inventory.name(),
                                reference.path(),
                                reference.expectedClass(),
                                matching.getFirst().classPath(),
                                "export"));
                    }
                }
                visit(target, next);
            }
        }

        private boolean isCompatible(String actual, String expected, String chain, Set<String> seen) {
            if (actual.equalsIgnoreCase(expected)) {
                return true;
            }
            if (!seen.add(key(actual))) {
                errors.add(chain + ": cyclic class inheritance at " + actual + ".");
                return false;
            }
            var owner = resolve(packageName(actual), chain + " -> " + actual);
            var type = owner == null ? null : owner.inventory.classDefinition(actual);
            return type != null && !type.superPath().isEmpty() && isCompatible(type.superPath(), expected, chain, seen);
        }

        private Node resolve(String name, String chain) {
            return resolve(name, chain, errors);
        }

        private Node resolve(String name, String chain, Set<String> problems) {
            var normalized = key(name);
            var found = nodes.get(normalized);
            if (found != null) {
                return found;
            }
            var candidates = new ArrayList<>(installed.getOrDefault(normalized, List.of()));
            candidates.sort(Comparator.comparingInt(file -> ClientSearchPaths.rank(searchPaths, root, file)));
            if (candidates.isEmpty()) {
                problems.add(chain + ": package " + name + " is missing. Supply the dependency package.");
                return null;
            }
            if (absent.contains(normalized)) {
                return null;
            }
            try {
                var file = candidates.getFirst();
                var bytes = Files.readAllBytes(file);
                var sha256 = hash(bytes);
                pinFingerprint(file, sha256);
                var rank = ClientSearchPaths.rank(searchPaths, root, file);
                for (var candidate : candidates.subList(1, candidates.size())) {
                    var actual = fileFingerprint(candidate);
                    pinFingerprint(candidate, actual);
                    if (!sha256.equals(actual) && ClientSearchPaths.rank(searchPaths, root, candidate) == rank) {
                        errors.add(chain + ": conflicting installed package identities for " + name + ".");
                    }
                }
                found = new Node(file, PackageInventory.inspect(file, bytes), sha256, "client");
                nodes.put(normalized, found);
                return found;
            } catch (IOException failure) {
                absent.add(normalized);
                errors.add(chain
                        + ": cannot inspect package "
                        + name
                        + " ("
                        + failure.getClass().getSimpleName()
                        + ").");
                return null;
            }
        }
    }
}
