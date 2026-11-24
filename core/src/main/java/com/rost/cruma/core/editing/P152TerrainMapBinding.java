package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.NativeTerrainTimeLighting.Result;
import com.rost.cruma.core.editing.PackageBytes.Cursor;
import com.rost.cruma.core.editing.PackageBytes.ExportEntry;
import com.rost.cruma.core.editing.TerrainNativeCodec.Sector;
import com.rost.cruma.core.editing.TerrainPackageSnapshot.Terrain;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class P152TerrainMapBinding {
    private P152TerrainMapBinding() {}

    record Prepared(
            NativePackageChanges changes,
            Path mapSource,
            String mapSha256,
            Path heightSource,
            String heightSha256,
            String owner,
            String heightObjectPath) {
        void verifySourcesUnchanged() throws IOException {
            requireHash(mapSource, mapSha256, "Source map changed after height binding preparation");
            requireHash(heightSource, heightSha256, "Source height package changed after binding preparation");
        }
    }

    private record Reference(int payload, int end, int reference) {}

    private record PropertySpan(int start, int end) {}

    static Prepared prepare(
            PackageEditDocument document,
            Path clientRoot,
            String ownerPath,
            IsolatedHeightPackage.Prepared clone,
            MaterialReference registeredClone)
            throws IOException {
        return prepare(document, clientRoot, ownerPath, clone, registeredClone, Optional.empty());
    }

    static Prepared prepare(
            PackageEditDocument document,
            Path clientRoot,
            String ownerPath,
            IsolatedHeightPackage.Prepared clone,
            MaterialReference registeredClone,
            Result sunlight)
            throws IOException {
        return prepare(document, clientRoot, ownerPath, clone, registeredClone, List.of(sunlight));
    }

    static Prepared prepare(
            PackageEditDocument document,
            Path clientRoot,
            String ownerPath,
            IsolatedHeightPackage.Prepared clone,
            MaterialReference registeredClone,
            List<Result> sunlight)
            throws IOException {
        if (sunlight.isEmpty()) {
            throw new IOException("Terrain sunlight composition needs at least one rebuilt window");
        }
        return prepare(document, clientRoot, ownerPath, clone, registeredClone, Optional.of(List.copyOf(sunlight)));
    }

    static void requireTextureImport(PackageBytes source, int reference, String expected) throws IOException {
        source.checkReference(reference);
        if (reference >= 0 || !source.getObjectPath(reference).equals(expected)) {
            throw invalidBinding("Registered clone import no longer resolves to the prepared height");
        }
        var components = expected.split("\\.", -1);
        if (components.length < 2) {
            throw invalidBinding("Registered clone requires a package and texture name");
        }
        for (var component = components.length - 1; component >= 0; component--) {
            source.checkReference(reference);
            if (reference >= 0) {
                throw invalidBinding("Registered clone outer chain must contain only imports");
            }
            var entry = source.imports.get(-reference - 1);
            var texture = component == components.length - 1;
            if (components[component].isEmpty()
                    || !source.getName(entry.objectName()).equals(components[component])
                    || !source.getName(entry.classPackage()).equals(texture ? "Engine" : "Core")
                    || !source.getName(entry.className()).equals(texture ? "Texture" : "Package")) {
                throw invalidBinding("Registered clone requires an exact Engine.Texture / Core.Package outer chain");
            }
            reference = entry.outer();
        }
        if (reference != 0) {
            throw invalidBinding("Registered clone outer chain does not terminate at its package root");
        }
    }

    private static Prepared prepare(
            PackageEditDocument document,
            Path clientRoot,
            String ownerPath,
            IsolatedHeightPackage.Prepared clone,
            MaterialReference registeredClone,
            Optional<List<Result>> sunlight)
            throws IOException {
        document.verifySourceUnchanged();
        IsolatedHeightPackage.verifyPrepared(clone);
        var root = clientRoot.toRealPath();
        if (!document.getSource().toRealPath().startsWith(root)) {
            throw invalidBinding("Terrain map is outside the source client root");
        }
        var file = Files.readAllBytes(document.getSource());
        if (!hash(file).equals(document.getOriginalSha256())) {
            throw invalidBinding("Source map changed while preparing height binding");
        }
        var original = new PackageBytes(document.getSource(), file);
        if (original.fileVersion != 128 || original.licenseVersion != 39) {
            throw invalidBinding("Pinned height binding requires P152 128/39");
        }
        var snapshot = TerrainPackageSnapshot.open(document.getSource(), root);
        if (!snapshot.getOriginalSha256().equals(document.getOriginalSha256())) {
            throw invalidBinding("Source map changed while reading native terrain");
        }
        var owner = snapshot.getTerrains().stream()
                .filter(t -> t.path().equals(ownerPath))
                .findFirst()
                .orElseThrow(() -> invalidBinding("Unknown native TerrainInfo owner"));
        var sourceName = clone.source().getFileName().toString();
        var originalHeightPath = sourceName.substring(0, sourceName.lastIndexOf('.'))
                + clone.objectPath().substring(clone.packageName().length());
        if (owner.heightmapReference() >= 0
                || !owner.heightmapObjectPath().equalsIgnoreCase(originalHeightPath)
                || owner.heightmap().size() != 1
                || owner.info().width() != 256
                || owner.info().height() != 256) {
            throw invalidBinding("Prepared height source does not match the owner's external one-mip G16");
        }
        requireSourceAsset(root, clone.source());
        for (var i = 0; i < original.imports.size(); i++) {
            var path = original.getObjectPath(-i - 1);
            if (path.equalsIgnoreCase(clone.packageName())
                    || path.regionMatches(
                            true,
                            0,
                            clone.packageName() + ".",
                            0,
                            clone.packageName().length() + 1)) {
                throw invalidBinding("Clone package identity is already referenced by the original map");
            }
        }
        var current = new PackageBytes(document.getSource(), document.previewBody());
        requireAppendOnlyTables(original, current);
        if (registeredClone == null
                || registeredClone.objectIndex() >= -original.imports.size()
                || !registeredClone.className().equals("Texture")
                || !registeredClone.sourcePath().equals(clone.objectPath())) {
            throw invalidBinding("Height clone requires its newly appended Engine.Texture import");
        }
        requireTextureImport(current, registeredClone.objectIndex(), clone.objectPath());
        var entry = original.exports.get(owner.exportIndex() - 1);
        var reference = terrainMap(original, entry);
        if (reference.reference() != owner.heightmapReference()) {
            throw invalidBinding("TerrainMap changed between native readers");
        }
        var serializedReference = new ByteArrayOutputStream();
        PackageBytes.writeIndex(serializedReference, registeredClone.objectIndex());
        if (serializedReference.size() != reference.end() - reference.payload()) {
            throw invalidBinding("TerrainMap compact reference width would change from "
                    + (reference.end() - reference.payload())
                    + " to "
                    + serializedReference.size()
                    + " bytes; P152 TerrainInfo relocation is not admitted");
        }
        var replacement = copyPayload(original, entry);
        System.arraycopy(
                serializedReference.toByteArray(),
                0,
                replacement,
                reference.payload() - entry.serialOffset(),
                serializedReference.size());
        var replacements = new LinkedHashMap<Integer, byte[]>();
        replacements.put(entry.index(), replacement);
        if (sunlight.isPresent()) {
            composeTimeMaps(original, snapshot, owner, replacement, sunlight.orElseThrow(), replacements);
        }
        var changes = NativePackageChanges.create(
                document.getSource(),
                document.getOriginalSha256(),
                original,
                replacements,
                Set.of(),
                (target, encoded) -> {
                    requireHash(
                            document.getSource(),
                            document.getOriginalSha256(),
                            "Source map changed after height binding preparation");
                    IsolatedHeightPackage.verifyPrepared(clone);
                    var result = new PackageBytes(target, encoded);
                    requireAppendOnlyTables(current, result);
                    requireTextureImport(result, registeredClone.objectIndex(), clone.objectPath());
                    for (var terrain : snapshot.getTerrains()) {
                        var before = original.exports.get(terrain.exportIndex() - 1);
                        var after = result.exports.get(terrain.exportIndex() - 1);
                        var expected = before.index() == entry.index() ? replacement : copyPayload(original, before);
                        if (before.serialOffset() != after.serialOffset()
                                || before.serialSize() != after.serialSize()
                                || before.classIndex() != after.classIndex()
                                || before.superIndex() != after.superIndex()
                                || before.outer() != after.outer()
                                || before.objectName() != after.objectName()
                                || before.flags() != after.flags()
                                || !Arrays.equals(expected, copyPayload(result, after))) {
                            throw invalidBinding(
                                    "Height binding changed another owner or moved native TerrainInfo bytes");
                        }
                        var expectedReference = before.index() == entry.index()
                                ? registeredClone.objectIndex()
                                : terrain.heightmapReference();
                        if (terrainMap(result, after).reference() != expectedReference) {
                            throw invalidBinding("Composed TerrainMap reference differs");
                        }
                        for (var sector : terrain.info().sectors()) {
                            var oldSector = original.exports.get(sector - 1);
                            var newSector = result.exports.get(sector - 1);
                            var expectedSector = replacements.getOrDefault(sector, copyPayload(original, oldSector));
                            if (oldSector.serialOffset() != newSector.serialOffset()
                                    || oldSector.serialSize() != newSector.serialSize()
                                    || oldSector.classIndex() != newSector.classIndex()
                                    || oldSector.superIndex() != newSector.superIndex()
                                    || oldSector.outer() != newSector.outer()
                                    || oldSector.objectName() != newSector.objectName()
                                    || oldSector.flags() != newSector.flags()
                                    || !Arrays.equals(expectedSector, copyPayload(result, newSector))) {
                                throw invalidBinding(
                                        "Time-map composition moved a sector or changed unrelated native bytes");
                            }
                        }
                    }
                });
        var prepared = new Prepared(
                changes,
                document.getSource(),
                document.getOriginalSha256(),
                clone.source(),
                clone.originalSha256(),
                ownerPath,
                clone.objectPath());
        prepared.verifySourcesUnchanged();
        return prepared;
    }

    private static void composeTimeMaps(
            PackageBytes source,
            TerrainPackageSnapshot snapshot,
            Terrain owner,
            byte[] ownerPayload,
            List<Result> sunlight,
            Map<Integer, byte[]> replacements)
            throws IOException {
        var before = snapshot.findAuthoredTimeMaps(owner.path())
                .orElseThrow(() -> invalidBinding("Owner has no authored TIntMap"));
        if (owner.sectors().isEmpty()
                || owner.sectors().stream().anyMatch(sector -> sector.hasTimeMaps() == 0)
                || before.compareSectorMaps(owner.sectors()).mismatchingSamples() != 0) {
            throw invalidBinding("Existing owner and sector time maps are incomplete or inconsistent");
        }
        TerrainTimeMapCodec after = before;
        try {
            for (var window : sunlight) {
                after = after.withWindow(window);
            }
        } catch (IllegalArgumentException invalidWindow) {
            throw new IOException("Invalid composed sunlight window", invalidWindow);
        }
        var entry = source.exports.get(owner.exportIndex() - 1);
        var span = findProperty(source, entry, "TIntMap", 9);
        var bytes = after.getOriginalBytes();
        if (bytes.length != span.end() - span.start()
                || !Arrays.equals(
                        before.getOriginalBytes(), Arrays.copyOfRange(source.body, span.start(), span.end()))) {
            throw invalidBinding("TIntMap readers disagree on the original fixed-width payload");
        }
        System.arraycopy(bytes, 0, ownerPayload, span.start() - entry.serialOffset(), bytes.length);
        for (var index = 0; index < owner.sectors().size(); index++) {
            var sector = owner.sectors().get(index);
            var frames = new ArrayList<List<Integer>>(after.getFrameCount());
            for (var slot = 0; slot < after.getFrameCount(); slot++) {
                frames.add(after.getSectorMap(slot, sector.offsetX(), sector.offsetY()));
            }
            if (frames.equals(sector.timeMaps())) {
                continue;
            }
            var updated = new Sector(
                    sector.owner(),
                    sector.sizeX(),
                    sector.sizeY(),
                    sector.offsetX(),
                    sector.offsetY(),
                    sector.bounds(),
                    sector.lights(),
                    sector.hasTimeMaps(),
                    frames,
                    sector.layerUniformity(),
                    sector.quadRenderPasses());
            var sectorEntry = source.exports.get(owner.info().sectors().get(index) - 1);
            var cursor = new Cursor(
                    source.body, sectorEntry.serialOffset(), sectorEntry.serialOffset() + sectorEntry.serialSize());
            NativeLevelEditor.skipProperties(source, cursor, sectorEntry.flags());
            var tail = TerrainNativeCodec.writeSector(updated);
            if (tail.length != cursor.end - cursor.position) {
                throw invalidBinding("Time-map composition would resize a native sector");
            }
            var payload = copyPayload(source, sectorEntry);
            System.arraycopy(tail, 0, payload, cursor.position - sectorEntry.serialOffset(), tail.length);
            replacements.put(sectorEntry.index(), payload);
        }
    }

    private static void requireSourceAsset(Path root, Path source) throws IOException {
        var matches = new ArrayList<Path>();
        for (var directory : List.of("Textures", "SysTextures")) {
            var folder = root.resolve(directory);
            if (!Files.isDirectory(folder)) {
                continue;
            }
            try (var files = Files.list(folder)) {
                matches.addAll(files.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName()
                                .toString()
                                .equalsIgnoreCase(source.getFileName().toString()))
                        .toList());
            }
        }
        if (matches.size() != 1 || !Files.isSameFile(matches.getFirst(), source)) {
            throw invalidBinding("Prepared height source is missing, ambiguous or outside the map's source client");
        }
    }

    private static void requireAppendOnlyTables(PackageBytes before, PackageBytes after) throws IOException {
        if (after.names.size() < before.names.size()
                || after.imports.size() < before.imports.size()
                || !after.names.subList(0, before.names.size()).equals(before.names)
                || !after.imports.subList(0, before.imports.size()).equals(before.imports)) {
            throw invalidBinding(
                    "Height binding requires unchanged original names/imports and appended clone references");
        }
    }

    private static Reference terrainMap(PackageBytes source, ExportEntry entry) throws IOException {
        var span = findProperty(source, entry, "TerrainMap", 5);
        var value = new Cursor(source.body, span.start(), span.end());
        var reference = value.readIndex();
        if (value.position != value.end) {
            throw invalidBinding("TerrainMap reference has a trailing payload");
        }
        source.checkReference(reference);
        return new Reference(span.start(), span.end(), reference);
    }

    private static PropertySpan findProperty(PackageBytes source, ExportEntry entry, String property, int expectedType)
            throws IOException {
        var cursor = new Cursor(source.body, entry.serialOffset(), entry.serialOffset() + entry.serialSize());
        if ((entry.flags() & 0x02000000) != 0) {
            var node = cursor.readIndex();
            cursor.readIndex();
            cursor.skip(12);
            if (node != 0) {
                cursor.readIndex();
            }
        }
        PropertySpan found = null;
        while (true) {
            var name = source.getName(cursor.readIndex());
            if (name.equalsIgnoreCase("None")) {
                if (found == null) {
                    throw invalidBinding("Owner has no authored " + property);
                }
                return found;
            }
            var tag = cursor.readU8();
            var type = tag & 15;
            if (type == 10) {
                source.getName(cursor.readIndex());
            }
            var size = switch (tag >> 4 & 7) {
                case 0 -> 1;
                case 1 -> 2;
                case 2 -> 4;
                case 3 -> 12;
                case 4 -> 16;
                case 5 -> cursor.readU8();
                case 6 -> cursor.readU16();
                default -> cursor.readI32();
            };
            var index = type != 3 && (tag & 128) != 0 ? cursor.readArrayIndex() : 0;
            var start = cursor.position;
            cursor.skip(type == 3 ? 0 : size);
            if (name.equalsIgnoreCase(property)) {
                if (found != null || type != expectedType || index != 0) {
                    throw invalidBinding("Ambiguous or unsupported " + property + " tag");
                }
                found = new PropertySpan(start, cursor.position);
            }
        }
    }

    private static byte[] copyPayload(PackageBytes source, ExportEntry entry) {
        return Arrays.copyOfRange(source.body, entry.serialOffset(), entry.serialOffset() + entry.serialSize());
    }

    private static void requireHash(Path source, String expected, String message) throws IOException {
        if (!hash(Files.readAllBytes(source)).equals(expected)) {
            throw invalidBinding(message);
        }
    }

    private static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static IOException invalidBinding(String message) {
        return new IOException(message);
    }
}
