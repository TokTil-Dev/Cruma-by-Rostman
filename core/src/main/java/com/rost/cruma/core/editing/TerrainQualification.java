package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.PackageBytes.Cursor;
import com.rost.cruma.core.editing.PackageBytes.ExportEntry;
import com.rost.cruma.core.editing.TerrainG16Codec.Mip;
import com.rost.cruma.core.editing.TerrainHeightField.SectorCoordinate;
import com.rost.cruma.core.editing.TerrainNativeCodec.Box;
import com.rost.cruma.core.editing.TerrainNativeCodec.Vector;
import com.rost.cruma.core.editing.TerrainPackageSnapshot.Terrain;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public final class TerrainQualification {
    private TerrainQualification() {}

    public record Source(String packageName, String sha256, int fileVersion, int licenseVersion, int container) {}

    public record Property(
            String name,
            int arrayIndex,
            int type,
            String structType,
            int bytes,
            String value,
            List<Property> children) {
        public Property {
            children = List.copyOf(children);
        }
    }

    public record ObjectFact(
            String path,
            String classPath,
            int exportIndex,
            int flags,
            boolean levelActor,
            int nativeBytes,
            List<Property> properties,
            String unresolved) {
        public ObjectFact {
            properties = List.copyOf(properties);
        }
    }

    public record HeightAsset(
            String objectPath,
            boolean external,
            Source source,
            int flags,
            List<Integer> mipDataOffsets,
            List<String> directConsumers,
            String unresolved) {
        public HeightAsset {
            mipDataOffsets = List.copyOf(mipDataOffsets);
            directConsumers = List.copyOf(directConsumers);
        }
    }

    public record TimeFrames(
            List<Float> times,
            long nonzeroSamples,
            long comparedSectorSamples,
            long differingSectorSamples,
            int comparedSectors,
            String unresolved) {
        public TimeFrames {
            times = List.copyOf(times);
        }
    }

    public record Owner(
            String path,
            int exportIndex,
            HeightAsset heightAsset,
            Box worldBounds,
            int steadyEntries,
            int sectorTimeMaps,
            int colors,
            boolean decorations,
            TimeFrames taggedTimeFrames,
            List<Property> properties) {
        public Owner {
            properties = List.copyOf(properties);
        }
    }

    public record Consumer(Source source, String objectPath, String property, String evidence) {}

    public record ConsumerInventory(
            String objectPath, int scannedPackages, List<Consumer> consumers, List<String> failures, String coverage) {
        public ConsumerInventory {
            consumers = List.copyOf(consumers);
            failures = List.copyOf(failures);
        }
    }

    public record Footprint(
            int changedSamples,
            int affectedQuads,
            Box oldGeometry,
            Box newGeometry,
            Box sweptGeometry,
            Box normalReceiverOrigins,
            List<SectorCoordinate> sectors) {
        public Footprint {
            sectors = List.copyOf(sectors);
        }
    }

    public record Candidate(
            int x,
            int y,
            int before,
            int after,
            Vector world,
            int adjacentVisibleQuads,
            int savedSectorLights,
            float localHeightRange) {}

    public record Report(
            Source source,
            String selectedOwner,
            Footprint footprint,
            List<Owner> owners,
            Map<String, Integer> classCounts,
            List<ObjectFact> objects,
            ConsumerInventory reverseConsumers,
            List<Candidate> candidateRegions,
            List<String> blockers,
            String currentExportRestriction) {
        public Report {
            owners = List.copyOf(owners);
            classCounts = Collections.unmodifiableMap(new TreeMap<>(classCounts));
            objects = List.copyOf(objects);
            candidateRegions = List.copyOf(candidateRegions);
            blockers = List.copyOf(blockers);
        }

        public String status() {
            return "UNQUALIFIED";
        }
    }

    private record Tags(int nativeOffset, List<Property> values) {}

    public static String toJson(Report report) {
        var out = new StringBuilder();
        appendJson(out, Map.of("schemaVersion", 1, "status", report.status(), "report", report));
        return out.append('\n').toString();
    }

    public static ConsumerInventory inspectConsumers(String objectPath, List<Path> packages, String coverage)
            throws IOException {
        if (objectPath == null || objectPath.indexOf('.') < 1 || coverage == null || coverage.isBlank()) {
            throw new IllegalArgumentException("A full native object identity and explicit scan coverage are required");
        }
        var result = new ArrayList<Consumer>();
        var failures = new ArrayList<String>();
        var unique = new HashSet<Path>();
        for (var file : packages) {
            var path = file.toAbsolutePath().normalize();
            if (!unique.add(path)) {
                continue;
            }
            try {
                var bytes = Files.readAllBytes(path);
                var source = new PackageBytes(path, bytes);
                var references = new HashSet<Integer>();
                for (var i = 0; i < source.imports.size(); i++) {
                    if (source.getObjectPath(-i - 1).equalsIgnoreCase(objectPath)) {
                        references.add(-i - 1);
                    }
                }
                for (var entry : source.exports) {
                    if (source.getObjectPath(entry.index()).equalsIgnoreCase(objectPath)) {
                        references.add(entry.index());
                    }
                }
                if (references.isEmpty()) {
                    continue;
                }
                var identity = computeIdentity(source, bytes);
                var found = false;
                for (var entry : source.exports) {
                    if (!source.getClassName(entry).equals("TerrainInfo")) {
                        continue;
                    }
                    try {
                        for (var property : readTags(source, entry).values()) {
                            if (property.type() == 5 && property.value().equalsIgnoreCase(objectPath)) {
                                found = true;
                                result.add(new Consumer(
                                        identity,
                                        source.getObjectPath(entry.index()),
                                        property.name(),
                                        "authored object reference"));
                            }
                        }
                    } catch (IOException unsupported) {
                        failures.add(source.packageName + ": TerrainInfo tagged properties are unresolved");
                    }
                }
                if (!found) {
                    result.add(new Consumer(identity, "", "", "package table reference; runtime consumers unresolved"));
                }
            } catch (IOException unsupported) {
                failures.add(path.getFileName() + ": package inventory failed");
            }
        }
        result.sort(
                Comparator.comparing((Consumer c) -> c.source().packageName()).thenComparing(Consumer::objectPath));
        return new ConsumerInventory(objectPath, unique.size(), result, failures, coverage);
    }

    public static Report inspect(
            Path map, Path clientRoot, String ownerPath, TerrainHeightPatch patch, ConsumerInventory reverseConsumers)
            throws IOException {
        var bytes = Files.readAllBytes(map);
        var source = new PackageBytes(map, bytes);
        var snapshot = TerrainPackageSnapshot.open(map, clientRoot);
        if (!computeIdentity(source, bytes).sha256().equals(snapshot.getOriginalSha256())) {
            throw new IOException("Source map changed during qualification");
        }
        var selected = findOwner(snapshot, ownerPath);
        if (selected.heightmap().isEmpty()) {
            throw new IOException("The selected heightmap is unresolved");
        }
        if (reverseConsumers != null
                && !reverseConsumers.objectPath().equalsIgnoreCase(selected.heightmapObjectPath())) {
            throw new IOException("Reverse consumer inventory belongs to another height texture");
        }
        var blockers = new ArrayList<String>();
        var levelActors = new HashSet<Integer>();
        try {
            levelActors.addAll(NativeLevelEditor.read(source).actors);
        } catch (IOException unsupported) {
            blockers.add("Native Level registry, navigation or tail is unresolved");
        }
        var counts = new TreeMap<String, Integer>();
        var objects = new ArrayList<ObjectFact>();
        for (var entry : source.exports) {
            var classPath = entry.classIndex() == 0 ? "Core.Class" : source.getObjectPath(entry.classIndex());
            counts.merge(classPath, 1, Integer::sum);
            if (levelActors.contains(entry.index())
                    || classPath.equals("Engine.Model")
                    || classPath.equals("Engine.Polys")
                    || classPath.equals("Engine.StaticMeshInstance")) {
                try {
                    var tags = readTags(source, entry);
                    objects.add(new ObjectFact(
                            source.getObjectPath(entry.index()),
                            classPath,
                            entry.index(),
                            entry.flags(),
                            levelActors.contains(entry.index()),
                            entry.serialOffset() + entry.serialSize() - tags.nativeOffset(),
                            tags.values(),
                            ""));
                } catch (IOException unsupported) {
                    objects.add(new ObjectFact(
                            source.getObjectPath(entry.index()),
                            classPath,
                            entry.index(),
                            entry.flags(),
                            levelActors.contains(entry.index()),
                            entry.serialSize(),
                            List.of(),
                            "Tagged/native boundary is unresolved"));
                }
            }
        }
        var owners = new ArrayList<Owner>();
        for (var terrain : snapshot.getTerrains()) {
            var properties = readTags(source, source.exports.get(terrain.exportIndex() - 1))
                    .values();
            owners.add(new Owner(
                    terrain.path(),
                    terrain.exportIndex(),
                    heightAsset(source, bytes, terrain, clientRoot),
                    worldBounds(terrain),
                    terrain.sectors().stream().mapToInt(s -> s.lights().size()).sum(),
                    terrain.sectors().stream()
                            .mapToInt(s -> s.timeMaps().size())
                            .sum(),
                    terrain.info().vertexColors().size() / 4,
                    terrain.hasDecorations(),
                    timeFrames(snapshot, terrain),
                    properties));
        }
        var candidates = findCandidates(source, selected, 16);
        var footprint = computeFootprint(selected, patch);
        var selectedFact = owners.stream()
                .filter(o -> o.exportIndex() == selected.exportIndex())
                .findFirst()
                .orElseThrow();
        if (selected.heightmapReference() <= 0) {
            blockers.add("External G16 requires verified private-copy isolation or an atomic rebuild of every resource"
                    + " consumer");
        }
        if ((selectedFact.heightAsset().flags() & 4) != 0) {
            blockers.add("Public height texture requires a complete cross-package consumer proof");
        }
        if (reverseConsumers == null || !reverseConsumers.failures().isEmpty()) {
            blockers.add("Height texture reverse-consumer coverage is missing or incomplete");
        } else if (reverseConsumers.consumers().stream()
                .anyMatch(c -> !c.objectPath().equals(selected.path()))) {
            blockers.add("Other authored or potential height consumers must be rebuilt or isolated");
        }
        if (owners.size() > 1) {
            blockers.add(
                    "Other Terrain owners require all-light old/new segment independence or a coordinated rebuild");
        }
        if (selectedFact.properties().stream().anyMatch(p -> p.name().equals("TIntMap"))) {
            blockers.add("Tagged Terrain intensity frames need exact wire and runtime agreement with sector time maps");
        }
        if (owners.stream().anyMatch(Owner::decorations)) {
            blockers.add("Terrain decoration placement and lighting consumers need a supported rebuild");
        }
        for (var className : List.of("Engine.Model", "Engine.Polys", "Engine.StaticMeshInstance")) {
            if (counts.getOrDefault(className, 0) > 0) {
                blockers.add(className + " receivers require exact old/new trace independence or native rebuild");
            }
        }
        blockers.add("Resolve applicable authored lights and class defaults, zones, collision flags and all saved time"
                + " directions");
        blockers.add(
                "Validate all old/new light-to-receiver segments, including lights absent from saved sector lists");
        blockers.add("Rebuild unchanged native lighting and explain every membership, visibility and color discrepancy"
                + " before export");
        blockers.add("Neighbor tile seams remain unchecked in the game: the saved lighting of each tile ignores its"
                + " neighbours, but a step or crack at the seam was not measured");
        return new Report(
                computeIdentity(source, bytes),
                selected.path(),
                footprint,
                owners,
                counts,
                objects,
                reverseConsumers,
                candidates,
                blockers,
                snapshot.getUnlitHeightEditRestriction(selected.path()));
    }

    public static Footprint computeFootprint(Terrain terrain, TerrainHeightPatch patch) throws IOException {
        if (patch.size() == 0) {
            throw new IOException("Qualification requires a nonzero height change");
        }
        var mip = terrain.heightmap().getFirst();
        var old = new TerrainHeightField(mip, terrain.info().toWorld());
        var edited = new TerrainHeightField(mip, terrain.info().toWorld());
        try {
            edited.apply(patch, false);
        } catch (IllegalArgumentException | IllegalStateException mismatch) {
            throw new IOException("Qualification patch does not match the source height field", mismatch);
        }
        var quads = new BitSet(old.getWidth() * old.getHeight());
        for (var i = 0; i < patch.size(); i++) {
            var x = patch.getIndex(i) % old.getWidth();
            var y = patch.getIndex(i) / old.getWidth();
            for (var dy = -1; dy <= 0; dy++) {
                for (var dx = -1; dx <= 0; dx++) {
                    quads.set((y + dy) * old.getWidth() + x + dx);
                }
            }
        }
        var before = new Bounds();
        var after = new Bounds();
        for (var i = quads.nextSetBit(0); i >= 0; i = quads.nextSetBit(i + 1)) {
            var x = i % old.getWidth();
            var y = i / old.getWidth();
            for (var dy = 0; dy <= 1; dy++) {
                for (var dx = 0; dx <= 1; dx++) {
                    before.add(old.getWorldAt(x + dx, y + dy));
                    after.add(edited.getWorldAt(x + dx, y + dy));
                }
            }
        }
        var swept = union(before.getBox(), after.getBox());
        var sectors = old.getAffectedSectors(patch).stream()
                .sorted(Comparator.comparingInt(SectorCoordinate::y).thenComparingInt(SectorCoordinate::x))
                .toList();
        var biased = new Box(
                new Vector(swept.min().x() - 1, swept.min().y() - 1, swept.min().z() - 1),
                new Vector(swept.max().x() + 1, swept.max().y() + 1, swept.max().z() + 1),
                1);
        return new Footprint(
                patch.size(), quads.cardinality(), before.getBox(), after.getBox(), swept, biased, sectors);
    }

    public static List<Candidate> findCandidates(Path map, Path clientRoot, String ownerPath, int limit)
            throws IOException {
        var source = new PackageBytes(map, Files.readAllBytes(map));
        return findCandidates(source, findOwner(TerrainPackageSnapshot.open(map, clientRoot), ownerPath), limit);
    }

    private static List<Candidate> findCandidates(PackageBytes source, Terrain terrain, int limit) throws IOException {
        if (limit < 1 || limit > 256) {
            throw new IllegalArgumentException("Candidate limit must be in 1..256");
        }
        var entry = source.exports.get(terrain.exportIndex() - 1);
        var visibility = rawProperty(source, entry, "QuadVisibilityBitmap");
        if (visibility == null || terrain.heightmap().isEmpty()) {
            return List.of();
        }
        var cursor = new Cursor(source.body, visibility[0], visibility[0] + visibility[1]);
        var count = cursor.readIndex();
        var field = new TerrainHeightField(
                terrain.heightmap().getFirst(), terrain.info().toWorld());
        if (count != (field.getWidth() * field.getHeight() + 31) / 32 || cursor.end - cursor.position != count * 4) {
            return List.of();
        }
        var bitsStart = cursor.position;
        var candidates = new ArrayList<Candidate>();
        for (var y = 4; y < field.getHeight() - 4; y += 4) {
            for (var x = 4; x < field.getWidth() - 4; x += 4) {
                var height = field.getHeightAt(x, y);
                if (height == 65535) {
                    continue;
                }
                var visible = 0;
                for (var dy = -1; dy <= 0; dy++) {
                    for (var dx = -1; dx <= 0; dx++) {
                        var bit = (y + dy) * field.getWidth() + x + dx;
                        if ((source.body[bitsStart + bit / 8] & 1 << (bit & 7)) != 0) {
                            visible++;
                        }
                    }
                }
                if (visible != 4) {
                    continue;
                }
                var lights = 0;
                for (var sector : terrain.sectors()) {
                    if (x >= sector.offsetX()
                            && x <= sector.offsetX() + sector.sizeX()
                            && y >= sector.offsetY()
                            && y <= sector.offsetY() + sector.sizeY()) {
                        lights += sector.lights().size();
                    }
                }
                var min = Float.POSITIVE_INFINITY;
                var max = Float.NEGATIVE_INFINITY;
                for (var dy = -1; dy <= 1; dy++) {
                    for (var dx = -1; dx <= 1; dx++) {
                        var z = field.getWorldAt(x + dx, y + dy).z();
                        min = Math.min(min, z);
                        max = Math.max(max, z);
                    }
                }
                candidates.add(
                        new Candidate(x, y, height, height + 1, field.getWorldAt(x, y), visible, lights, max - min));
            }
        }
        candidates.sort(Comparator.comparingInt(Candidate::savedSectorLights)
                .thenComparingDouble(Candidate::localHeightRange)
                .thenComparingInt(c -> (c.x() - field.getWidth() / 2) * (c.x() - field.getWidth() / 2)
                        + (c.y() - field.getHeight() / 2) * (c.y() - field.getHeight() / 2))
                .thenComparingInt(Candidate::y)
                .thenComparingInt(Candidate::x));
        return List.copyOf(candidates.subList(0, Math.min(limit, candidates.size())));
    }

    private static Terrain findOwner(TerrainPackageSnapshot snapshot, String path) throws IOException {
        return snapshot.getTerrains().stream()
                .filter(t -> t.path().equals(path))
                .findFirst()
                .orElseThrow(() -> new IOException("Unknown Terrain owner"));
    }

    private static HeightAsset heightAsset(PackageBytes map, byte[] mapBytes, Terrain terrain, Path root)
            throws IOException {
        var source = map;
        var bytes = mapBytes;
        ExportEntry texture = null;
        if (terrain.heightmapReference() > 0) {
            texture = map.exports.get(terrain.heightmapReference() - 1);
        } else if (root != null) {
            var packageName = terrain.heightmapObjectPath().split("\\.", 2)[0];
            var paths = new ArrayList<Path>();
            for (var folder : List.of("Textures", "SysTextures")) {
                var directory = root.resolve(folder);
                if (!Files.isDirectory(directory)) {
                    continue;
                }
                try (var files = Files.list(directory)) {
                    paths.addAll(files.filter(Files::isRegularFile)
                            .filter(f -> f.getFileName().toString().equalsIgnoreCase(packageName + ".utx"))
                            .toList());
                }
            }
            if (paths.size() == 1) {
                bytes = Files.readAllBytes(paths.getFirst());
                source = new PackageBytes(paths.getFirst(), bytes);
                for (var entry : source.exports) {
                    if (source.getObjectPath(entry.index()).equalsIgnoreCase(terrain.heightmapObjectPath())) {
                        if (texture != null) {
                            throw new IOException("Ambiguous height texture identity");
                        }
                        texture = entry;
                    }
                }
            }
        }
        var direct = new ArrayList<String>();
        if (texture != null) {
            for (var entry : source.exports) {
                if (source.getObjectPath(entry.classIndex()).equals("Core.Class")) {
                    continue;
                }
                try {
                    collectConsumers(
                            readTags(source, entry).values(),
                            terrain.heightmapObjectPath(),
                            source.getObjectPath(entry.index()),
                            direct);
                } catch (IOException ignored) {
                }
            }
        }
        return new HeightAsset(
                terrain.heightmapObjectPath(),
                terrain.heightmapReference() <= 0,
                texture == null ? null : computeIdentity(source, bytes),
                texture == null ? 0 : texture.flags(),
                terrain.heightmap().stream().map(Mip::dataOffset).toList(),
                direct,
                texture == null
                        ? "Height texture package/export identity is unresolved"
                        : "Native and opaque cross-package consumers still require proof");
    }

    private static void collectConsumers(List<Property> properties, String path, String owner, List<String> result) {
        for (var p : properties) {
            if (p.type() == 5 && p.value().equalsIgnoreCase(path)) {
                result.add(owner + ":" + p.name());
            }
            collectConsumers(p.children(), path, owner + ":" + p.name(), result);
        }
    }

    private static TimeFrames timeFrames(TerrainPackageSnapshot snapshot, Terrain terrain) {
        try {
            var authored = snapshot.findAuthoredTimeMaps(terrain.path());
            if (authored.isEmpty()) {
                return new TimeFrames(
                        List.of(), 0, 0, 0, 0, "No authored TIntMap property; class default is not inferred");
            }
            var codec = authored.get();
            var times = new ArrayList<Float>();
            long nonzero = 0;
            for (var frame = 0; frame < codec.getFrameCount(); frame++) {
                times.add(codec.getTime(frame));
                for (var value : codec.getIntensity(frame)) {
                    if (value != 0) {
                        nonzero++;
                    }
                }
            }
            var consistency = codec.compareSectorMaps(terrain.sectors());
            return new TimeFrames(
                    times,
                    nonzero,
                    consistency.comparedSamples(),
                    consistency.mismatchingSamples(),
                    consistency.bakedSectors(),
                    "");
        } catch (IOException unsupported) {
            return new TimeFrames(List.of(), 0, 0, 0, 0, "Tagged and sector time maps could not be reconciled");
        }
    }

    private static Box worldBounds(Terrain terrain) throws IOException {
        if (terrain.heightmap().isEmpty()) {
            return new Box(new Vector(0, 0, 0), new Vector(0, 0, 0), 0);
        }
        var field = new TerrainHeightField(
                terrain.heightmap().getFirst(), terrain.info().toWorld());
        var bounds = new Bounds();
        for (var y = 0; y < field.getHeight(); y++) {
            for (var x = 0; x < field.getWidth(); x++) {
                bounds.add(field.getWorldAt(x, y));
            }
        }
        return bounds.getBox();
    }

    private static Source computeIdentity(PackageBytes source, byte[] bytes) {
        try {
            return new Source(
                    source.packageName,
                    HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
                    source.fileVersion,
                    source.licenseVersion,
                    source.container);
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static Tags readTags(PackageBytes source, ExportEntry entry) throws IOException {
        var cursor = objectCursor(source, entry);
        var properties = readProperties(source, cursor, 0, null);
        return new Tags(cursor.position, properties);
    }

    private static int[] rawProperty(PackageBytes source, ExportEntry entry, String wanted) throws IOException {
        var found = new int[2];
        Arrays.fill(found, -1);
        readProperties(source, objectCursor(source, entry), 0, (name, start, length) -> {
            if (name.equals(wanted)) {
                if (found[0] != -1) {
                    throw new IOException("Ambiguous qualification property");
                }
                found[0] = start;
                found[1] = length;
            }
        });
        return found[0] < 0 ? null : found;
    }

    @FunctionalInterface
    private interface PropertySpan {
        void accept(String name, int start, int length) throws IOException;
    }

    private static Cursor objectCursor(PackageBytes source, ExportEntry entry) throws IOException {
        var cursor = new Cursor(source.body, entry.serialOffset(), entry.serialOffset() + entry.serialSize());
        if ((entry.flags() & 0x02000000) != 0) {
            var node = cursor.readIndex();
            cursor.readIndex();
            cursor.skip(12);
            if (node != 0) {
                cursor.readIndex();
            }
        }
        return cursor;
    }

    private static List<Property> readProperties(PackageBytes source, Cursor cursor, int depth, PropertySpan span)
            throws IOException {
        if (depth > 8) {
            throw new IOException("Nested qualification properties exceed the depth limit");
        }
        var result = new ArrayList<Property>();
        for (var count = 0; count < 65536; count++) {
            var name = source.getName(cursor.readIndex());
            if (name.equals("None")) {
                return List.copyOf(result);
            }
            var info = cursor.readU8();
            var type = info & 15;
            if (type == 0) {
                throw new IOException("Invalid qualification property type");
            }
            var struct = type == 10 ? source.getName(cursor.readIndex()) : "";
            var length = switch (info >> 4 & 7) {
                case 0 -> 1;
                case 1 -> 2;
                case 2 -> 4;
                case 3 -> 12;
                case 4 -> 16;
                case 5 -> cursor.readU8();
                case 6 -> cursor.readU16();
                default -> cursor.readI32();
            };
            var array = type != 3 && (info & 128) != 0 ? cursor.readArrayIndex() : 0;
            var start = cursor.position;
            cursor.skip(type == 3 ? 0 : length);
            var end = cursor.position;
            var data = new Cursor(source.body, start, end);
            var value = "opaque";
            List<Property> children = List.of();
            if (type == 3) {
                value = Boolean.toString((info & 128) != 0);
            } else if (type == 1 && length == 1) {
                value = Integer.toString(data.readU8());
            } else if (type == 2 && length == 4) {
                value = Integer.toString(data.readI32());
            } else if (type == 4 && length == 4) {
                var number = Float.intBitsToFloat(data.readI32());
                if (!Float.isFinite(number)) {
                    throw new IOException("Non-finite authored property");
                }
                value = Float.toString(number);
            } else if (type == 5) {
                value = source.getObjectPath(data.readIndex());
                if (data.position != data.end) {
                    throw new IOException("Invalid object reference length");
                }
            } else if (type == 6) {
                value = source.getName(data.readIndex());
            } else if (type == 9) {
                value = "count=" + data.readIndex();
            } else if (type == 10 && (struct.equals("Vector") || struct.equals("Rotator")) && length == 12) {
                var parts = new ArrayList<String>();
                for (var i = 0; i < 3; i++) {
                    var raw = data.readI32();
                    parts.add(
                            struct.equals("Vector")
                                    ? Float.toString(Float.intBitsToFloat(raw))
                                    : Integer.toString(raw));
                }
                value = String.join(",", parts);
            } else if (type == 10 && Set.of("PointRegion", "TerrainLayer").contains(struct)) {
                children = readProperties(source, data, depth + 1, null);
                if (data.position != data.end) {
                    throw new IOException("Trailing tagged struct bytes");
                }
                value = "tagged";
            }
            if (span != null) {
                span.accept(name, start, end - start);
            }
            result.add(new Property(name, array, type, struct, end - start, value, children));
        }
        throw new IOException("Qualification property count exceeds limit");
    }

    private static Box union(Box a, Box b) {
        return new Box(
                new Vector(
                        Math.min(a.min().x(), b.min().x()),
                        Math.min(a.min().y(), b.min().y()),
                        Math.min(a.min().z(), b.min().z())),
                new Vector(
                        Math.max(a.max().x(), b.max().x()),
                        Math.max(a.max().y(), b.max().y()),
                        Math.max(a.max().z(), b.max().z())),
                1);
    }

    private static final class Bounds {
        private float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
        private float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;

        void add(Vector p) throws IOException {
            if (!Float.isFinite(p.x()) || !Float.isFinite(p.y()) || !Float.isFinite(p.z())) {
                throw new IOException("Non-finite native geometry");
            }
            minX = Math.min(minX, p.x());
            minY = Math.min(minY, p.y());
            minZ = Math.min(minZ, p.z());
            maxX = Math.max(maxX, p.x());
            maxY = Math.max(maxY, p.y());
            maxZ = Math.max(maxZ, p.z());
        }

        Box getBox() {
            return new Box(new Vector(minX, minY, minZ), new Vector(maxX, maxY, maxZ), 1);
        }
    }

    private static void appendJson(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
            return;
        }
        if (value instanceof Number || value instanceof Boolean) {
            out.append(value);
            return;
        }
        if (value instanceof String string) {
            out.append('"');
            for (var i = 0; i < string.length(); i++) {
                var c = string.charAt(i);
                if (c == '"' || c == '\\') {
                    out.append('\\').append(c);
                } else if (c < 32) {
                    out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                } else {
                    out.append(c);
                }
            }
            out.append('"');
            return;
        }
        if (value instanceof Iterable<?> list) {
            out.append('[');
            var first = true;
            for (var item : list) {
                if (!first) {
                    out.append(',');
                }
                appendJson(out, item);
                first = false;
            }
            out.append(']');
            return;
        }
        if (value instanceof Map<?, ?> map) {
            out.append('{');
            var first = true;
            var entries = new TreeMap<String, Object>();
            map.forEach((key, item) -> entries.put(key.toString(), item));
            for (var entry : entries.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                appendJson(out, entry.getKey());
                out.append(':');
                appendJson(out, entry.getValue());
                first = false;
            }
            out.append('}');
            return;
        }
        if (value.getClass().isRecord()) {
            out.append('{');
            var first = true;
            for (var component : value.getClass().getRecordComponents()) {
                if (!first) {
                    out.append(',');
                }
                appendJson(out, component.getName());
                out.append(':');
                try {
                    appendJson(out, component.getAccessor().invoke(value));
                } catch (ReflectiveOperationException impossible) {
                    throw new IllegalStateException("Cannot encode qualification metadata", impossible);
                }
                first = false;
            }
            out.append('}');
            return;
        }
        throw new IllegalArgumentException("Unsupported qualification metadata type");
    }
}
