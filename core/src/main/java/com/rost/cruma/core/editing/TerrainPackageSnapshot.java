package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.PackageBytes.Cursor;
import com.rost.cruma.core.editing.PackageBytes.ExportEntry;
import com.rost.cruma.core.editing.TerrainG16Codec.Mip;
import com.rost.cruma.core.editing.TerrainHeightField.SectorCoordinate;
import com.rost.cruma.core.editing.TerrainNativeCodec.Info;
import com.rost.cruma.core.editing.TerrainNativeCodec.Sector;
import com.rost.cruma.core.editing.TerrainNativeCodec.Vector;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

public final class TerrainPackageSnapshot {
    static final List<String> QUAD_BITMAPS =
            List.of("QuadVisibilityBitmap", "QuadVisibilityBitmapOrig", "EdgeTurnBitmap", "EdgeTurnBitmapOrig");

    private final PackageBytes source;
    private final Path sourcePath;
    private final String originalSha256;
    private final List<Terrain> terrains;
    private final Map<Integer, byte[]> rewrittenTails = new LinkedHashMap<>();
    private final Map<Integer, Integer> nativeOffsets = new LinkedHashMap<>();
    private final Map<String, HeightSource> heightSources = new LinkedHashMap<>();
    private final Path clientRoot;

    private TerrainPackageSnapshot(Path path, byte[] bytes, Path clientRoot) throws IOException {
        this.clientRoot = clientRoot;
        source = new PackageBytes(path, bytes);
        sourcePath = path.toAbsolutePath().normalize();
        try {
            originalSha256 = HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
        if (!TerrainNativeCodec.supportsReadOnly(source.fileVersion, source.licenseVersion)) {
            throw new IOException("Unaudited terrain revision " + source.fileVersion + "/" + source.licenseVersion);
        }
        var result = new ArrayList<Terrain>();
        var owned = new HashSet<Integer>();
        for (var entry : source.exports) {
            if (!source.getClassName(entry).equals("TerrainInfo")) {
                continue;
            }
            if (!source.getObjectPath(entry.classIndex()).equalsIgnoreCase("Engine.TerrainInfo")) {
                throw new IOException("TerrainInfo subclasses require their own native audit");
            }
            var object = readObject(entry);
            nativeOffsets.put(entry.index(), object.offset);
            var info = TerrainNativeCodec.readInfo(
                    source.fileVersion, source.licenseVersion, copyTail(entry, object.offset));
            rewrittenTails.put(
                    object.offset, TerrainNativeCodec.writeInfo(source.fileVersion, source.licenseVersion, info));
            if (info.beastLightmaps().isPresent()) {
                for (var reference : info.beastLightmaps().orElseThrow().references()) {
                    source.checkReference(reference);
                }
            }
            var sectors = new ArrayList<Sector>();
            var coordinates = new HashSet<Long>();
            for (var reference : info.sectors()) {
                source.checkReference(reference);
                var sectorEntry = source.exports.get(reference - 1);
                if (!source.getObjectPath(sectorEntry.classIndex()).equalsIgnoreCase("Engine.TerrainSector")
                        || !owned.add(reference)) {
                    throw new IOException("Terrain sector reference is not exclusively owned native TerrainSector");
                }
                var sectorObject = readObject(sectorEntry);
                nativeOffsets.put(sectorEntry.index(), sectorObject.offset);
                var sector = TerrainNativeCodec.readSector(
                        source.fileVersion, source.licenseVersion, copyTail(sectorEntry, sectorObject.offset));
                if (sector.owner() != entry.index()
                        || sector.offsetX() / 16 >= info.sectorsX()
                        || sector.offsetY() / 16 >= info.sectorsY()
                        || !coordinates.add((long) sector.offsetX() << 32 | Integer.toUnsignedLong(sector.offsetY()))) {
                    throw new IOException("Terrain sector ownership/grid mismatch");
                }
                for (var light : sector.lights()) {
                    source.checkReference(light.reference());
                }
                rewrittenTails.put(
                        sectorObject.offset,
                        TerrainNativeCodec.writeSector(source.fileVersion, source.licenseVersion, sector));
                sectors.add(sector);
            }
            var restrictions = new ArrayList<String>();
            var heightRef = readReference(object, "TerrainMap");
            var heightPath = source.getObjectPath(heightRef);
            List<Mip> heights = List.of();
            if (heightRef <= 0) {
                if (heightRef == 0 || clientRoot == null) {
                    restrictions.add("External heightmap resolution requires the source client root");
                } else {
                    var dot = heightPath.indexOf('.');
                    if (dot < 1) {
                        throw new IOException("Invalid external terrain heightmap path");
                    }
                    var packageName = heightPath.substring(0, dot);
                    var candidates = texturePackages(clientRoot, packageName);
                    if (candidates.size() != 1) {
                        restrictions.add("Heightmap package is missing or ambiguous: " + packageName);
                    } else {
                        var assetPath = candidates.getFirst();
                        var assetBytes = Files.readAllBytes(assetPath);
                        var asset = new PackageBytes(assetPath, assetBytes);
                        if (!supportsHeightTextureReadOnly(asset.fileVersion, asset.licenseVersion)) {
                            restrictions.add("Height texture has an unaudited package revision " + asset.fileVersion
                                    + "/" + asset.licenseVersion);
                        } else {
                            ExportEntry texture = null;
                            for (var candidate : asset.exports) {
                                if (asset.getObjectPath(candidate.index()).equalsIgnoreCase(heightPath)) {
                                    if (texture != null) {
                                        throw new IOException("Ambiguous height texture export");
                                    }
                                    texture = candidate;
                                }
                            }
                            if (texture == null) {
                                restrictions.add("Height texture export is missing");
                            } else {
                                try {
                                    heights = readHeightTexture(asset, texture, null);
                                } catch (IOException unreadable) {
                                    restrictions.add("Height texture cannot be read: " + unreadable.getMessage());
                                    heights = List.of();
                                }
                                if (!heights.isEmpty()) {
                                    heightSources.put(
                                            source.getObjectPath(entry.index()),
                                            new HeightSource(
                                                    assetPath.toAbsolutePath().normalize(),
                                                    HexFormat.of().formatHex(sha256(assetBytes))));
                                }
                            }
                        }
                    }
                }
            } else {
                source.checkReference(heightRef);
                var texture = source.exports.get(heightRef - 1);
                if (supportsHeightTextureReadOnly(source.fileVersion, source.licenseVersion)) {
                    heights = readHeightTexture(source, texture, rewrittenTails);
                } else {
                    restrictions.add("Local height texture has an unaudited material native prefix for revision "
                            + source.fileVersion + "/" + source.licenseVersion);
                }
            }
            if (!heights.isEmpty()
                    && (heights.getFirst().width() != info.width()
                            || heights.getFirst().height() != info.height())) {
                throw new IOException("TerrainInfo and G16 dimensions differ");
            }
            var decorations = object.properties.stream().anyMatch(p -> p.name.equals("DecoLayers"));
            result.add(new Terrain(
                    source.getObjectPath(entry.index()),
                    entry.index(),
                    info,
                    sectors,
                    heightRef,
                    heightPath,
                    heights,
                    decorations,
                    restrictions));
        }
        var references = new HashMap<Integer, Integer>();
        for (var terrain : result) {
            if (terrain.heightmapReference() > 0) {
                references.merge(terrain.heightmapReference(), 1, Integer::sum);
            }
        }
        for (var i = 0; i < result.size(); i++) {
            var terrain = result.get(i);
            if (references.getOrDefault(terrain.heightmapReference(), 0) > 1) {
                var reasons = new ArrayList<>(terrain.editRestrictions());
                reasons.add("Heightmap is shared by multiple TerrainInfo exports");
                result.set(
                        i,
                        new Terrain(
                                terrain.path(),
                                terrain.exportIndex(),
                                terrain.info(),
                                terrain.sectors(),
                                terrain.heightmapReference(),
                                terrain.heightmapObjectPath(),
                                terrain.heightmap(),
                                terrain.hasDecorations(),
                                reasons));
            }
        }
        terrains = List.copyOf(result);
    }

    public record Terrain(
            String path,
            int exportIndex,
            Info info,
            List<Sector> sectors,
            int heightmapReference,
            String heightmapObjectPath,
            List<Mip> heightmap,
            boolean hasDecorations,
            List<String> editRestrictions) {
        public Terrain {
            sectors = List.copyOf(sectors);
            heightmap = List.copyOf(heightmap);
            editRestrictions = List.copyOf(editRestrictions);
        }
    }

    private record Property(
            String name, int type, int arrayIndex, int start, int length, boolean boolValue, String structType) {}

    private record NativeObject(int offset, List<Property> properties) {}

    public record HeightSource(Path path, String sha256) {}

    public static TerrainPackageSnapshot open(Path path) throws IOException {
        return open(path, null);
    }

    public static TerrainPackageSnapshot open(Path path, Path clientRoot) throws IOException {
        return new TerrainPackageSnapshot(path, Files.readAllBytes(path), clientRoot);
    }

    static TerrainPackageSnapshot fromBytes(Path path, byte[] bytes) throws IOException {
        return new TerrainPackageSnapshot(path, bytes, null);
    }

    static List<Mip> capturedHeight(PackageBytes source, String objectPath) throws IOException {
        if (!supportsHeightTextureReadOnly(source.fileVersion, source.licenseVersion)) {
            throw new IOException("Unaudited height texture read layout");
        }
        ExportEntry found = null;
        for (var entry : source.exports) {
            if (!source.getObjectPath(entry.index()).equalsIgnoreCase(objectPath)) {
                continue;
            }
            if (found != null) {
                throw new IOException("Ambiguous height texture identity");
            }
            found = entry;
        }
        if (found == null || (found.flags() & 0x02000000) != 0 || found.superIndex() != 0) {
            throw new IOException("Missing or unsupported native height texture");
        }
        P152ActorDomain.requireEngineClass(source, found, "Texture");
        if (!P152ActorDomain.editorAssetLoadGaps(source, found).isEmpty()) {
            throw new IOException("Height texture is not admitted by fresh editor load flags");
        }
        return readHeightTexture(source, found, null);
    }

    static List<Mip> readHeights(PackageBytes source, String objectPath) throws IOException {
        ExportEntry found = null;
        for (var entry : source.exports) {
            if (source.getObjectPath(entry.index()).equalsIgnoreCase(objectPath)) {
                if (found != null) {
                    throw new IOException("Ambiguous height texture identity");
                }
                found = entry;
            }
        }
        if (found == null) {
            throw new IOException("Missing height texture " + objectPath);
        }
        return readHeightTexture(source, found, null);
    }

    static void writeLayer(byte[] target, int base, LayerFields fields, TerrainLayerMapping mapping, float[] matrix)
            throws IOException {
        if (!fields.isEditable() || fields.offsets() == null || matrix.length != 16) {
            throw new IOException(
                    fields.restriction().isEmpty() ? "This layer cannot take a new mapping" : fields.restriction());
        }
        var offsets = fields.offsets();
        putInt(target, offsets[0] - base, Float.floatToRawIntBits(mapping.uScale()));
        putInt(target, offsets[1] - base, Float.floatToRawIntBits(mapping.vScale()));
        putInt(target, offsets[2] - base, Float.floatToRawIntBits(mapping.uPan()));
        putInt(target, offsets[3] - base, Float.floatToRawIntBits(mapping.vPan()));
        target[offsets[4] - base] = (byte) mapping.axis();
        putInt(target, offsets[5] - base, Float.floatToRawIntBits(mapping.textureRotation()));
        putInt(target, offsets[6] - base, mapping.pitch());
        putInt(target, offsets[6] + 4 - base, mapping.yaw());
        putInt(target, offsets[6] + 8 - base, mapping.roll());
        for (var i = 0; i < 16; i++) {
            putInt(target, offsets[LayerFields.FIELDS + i] - base, Float.floatToRawIntBits(matrix[i]));
        }
    }

    static int exportScalar(PackageBytes source, ExportEntry entry, String name, int type, int fallback)
            throws IOException {
        return readScalar(source, readObject(source, entry), name, type, fallback);
    }

    static int exportReference(PackageBytes source, ExportEntry entry, String name) throws IOException {
        var property = findProperty(readObject(source, entry), name);
        if (property == null) {
            return 0;
        }
        if (property.type != 5) {
            throw new IOException("Invalid object property " + name);
        }
        var cursor = new Cursor(source.body, property.start, property.start + property.length);
        var reference = cursor.readIndex();
        if (cursor.position != cursor.end) {
            throw new IOException("Invalid object-reference length");
        }
        source.checkReference(reference);
        return reference;
    }

    static Cursor textureMips(PackageBytes source, ExportEntry texture) throws IOException {
        return materialMips(source, texture, readObject(source, texture));
    }

    public List<Terrain> getTerrains() {
        return terrains;
    }

    public Path getSourcePath() {
        return sourcePath;
    }

    public String getOriginalSha256() {
        return originalSha256;
    }

    public int getFileVersion() {
        return source.fileVersion;
    }

    PackageBytes getPackageBytes() {
        return source;
    }

    public Optional<HeightSource> findHeightSource(String ownerPath) {
        return Optional.ofNullable(heightSources.get(ownerPath));
    }

    Optional<Path> findTexturePackage(String packageName) throws IOException {
        if (clientRoot == null) {
            return Optional.empty();
        }
        var candidates = texturePackages(clientRoot, packageName);
        return candidates.size() == 1 ? Optional.of(candidates.getFirst()) : Optional.empty();
    }

    List<String> getDrawnLayerWeights(String ownerPath) throws IOException {
        var terrain = terrains.stream()
                .filter(t -> t.path().equals(ownerPath))
                .findFirst()
                .orElseThrow(() -> new IOException("Unknown terrain owner"));
        var textures = new int[NativeTerrainLayerPasses.LAYER_SLOTS];
        var weights = new int[NativeTerrainLayerPasses.LAYER_SLOTS];
        for (var property : readObject(source.exports.get(terrain.exportIndex() - 1)).properties) {
            if (!property.name.equalsIgnoreCase("Layers")) {
                continue;
            }
            if (property.type != 10 || property.arrayIndex < 0 || property.arrayIndex >= textures.length) {
                throw new IOException("Invalid Terrain layer property");
            }
            var c = new Cursor(source.body, property.start, property.start + property.length);
            for (var field : readProperties(source, c)) {
                var texture = field.name.equalsIgnoreCase("Texture");
                if (field.type != 5 || !texture && !field.name.equalsIgnoreCase("AlphaMap")) {
                    continue;
                }
                var reference = new Cursor(source.body, field.start, field.start + field.length).readIndex();
                source.checkReference(reference);
                (texture ? textures : weights)[property.arrayIndex] = reference;
            }
        }
        var result = new ArrayList<String>();
        for (var slot = 0; slot < textures.length && textures[slot] != 0 && weights[slot] != 0; slot++) {
            result.add(source.getObjectPath(weights[slot]));
        }
        return List.copyOf(result);
    }

    public record LayerFields(
            int order,
            int slot,
            @Nullable TerrainLayerMapping mapping,
            float @Nullable [] matrix,
            String restriction,
            int @Nullable [] offsets) {
        static final int FIELDS = 7;

        public boolean isEditable() {
            return restriction.isEmpty();
        }
    }

    public List<LayerFields> getLayerFields(String ownerPath) throws IOException {
        var terrain = terrains.stream()
                .filter(t -> t.path().equals(ownerPath))
                .findFirst()
                .orElseThrow(() -> new IOException("Unknown terrain owner"));
        var result = new ArrayList<LayerFields>();
        var order = 0;
        for (var property : readObject(source.exports.get(terrain.exportIndex() - 1)).properties) {
            if (!property.name.equalsIgnoreCase("Layers")) {
                continue;
            }
            result.add(readLayer(order++, property));
        }
        return List.copyOf(result);
    }

    public BitSet[] getQuadBitmaps(String ownerPath) throws IOException {
        var offsets = getQuadBitmapOffsets(ownerPath);
        var terrain = terrain(ownerPath);
        var count = terrain.info().width() * terrain.info().height();
        return new BitSet[] {readBits(offsets[0], count), readBits(offsets[2], count)};
    }

    int[] getQuadBitmapOffsets(String ownerPath) throws IOException {
        var terrain = terrain(ownerPath);
        var words = terrain.info().width() * terrain.info().height() / 32;
        var object = readObject(source.exports.get(terrain.exportIndex() - 1));
        var offsets = new int[QUAD_BITMAPS.size()];
        for (var i = 0; i < offsets.length; i++) {
            var property = findProperty(object, QUAD_BITMAPS.get(i));
            if (property == null) {
                if (i % 2 == 0) {
                    throw new IOException(
                            "This Terrain saves no " + QUAD_BITMAPS.get(i) + ", so its quads cannot be changed");
                }
                offsets[i] = -1;
                continue;
            }
            var cursor = new Cursor(source.body, property.start(), property.start() + property.length());
            if (property.type() != 9 || cursor.readIndex() != words || cursor.end - cursor.position != words * 4) {
                throw new IOException("The " + QUAD_BITMAPS.get(i) + " of this Terrain has another size than its grid");
            }
            offsets[i] = cursor.position;
        }
        return offsets;
    }

    void writeQuads(byte[] target, int base, String ownerPath, BitSet visible, BitSet turns) throws IOException {
        var offsets = getQuadBitmapOffsets(ownerPath);
        var saved = getQuadBitmaps(ownerPath);
        var wanted = new BitSet[] {visible, turns};
        for (var bitmap = 0; bitmap < 2; bitmap++) {
            var changed = (BitSet) saved[bitmap].clone();
            changed.xor(wanted[bitmap]);
            for (var copy = 0; copy < 2; copy++) {
                var offset = offsets[bitmap * 2 + copy];
                if (offset < 0) {
                    continue;
                }
                for (var quad = changed.nextSetBit(0); quad >= 0; quad = changed.nextSetBit(quad + 1)) {
                    var at = offset + (quad >>> 5) * 4 + (quad & 31) / 8 - base;
                    if (at < 0 || at >= target.length) {
                        throw new IOException("A Terrain quad bit lies outside its export");
                    }
                    var mask = 1 << (quad & 7);
                    target[at] = (byte) (wanted[bitmap].get(quad) ? target[at] | mask : target[at] & ~mask);
                }
            }
        }
    }

    byte[] getPayloadWithTail(int exportIndex, byte[] tail) throws IOException {
        var entry = source.exports.get(exportIndex - 1);
        var offset = nativeOffsets.get(exportIndex);
        if (offset == null) {
            throw new IOException("Export has no decoded Terrain native tail: " + source.getObjectPath(exportIndex));
        }
        var prefix = offset - entry.serialOffset();
        var result = new byte[prefix + tail.length];
        System.arraycopy(source.body, entry.serialOffset(), result, 0, prefix);
        System.arraycopy(tail, 0, result, prefix, tail.length);
        return result;
    }

    Optional<int[]> findTimeMapSpan(String ownerPath) throws IOException {
        var terrain = terrains.stream()
                .filter(t -> t.path().equals(ownerPath))
                .findFirst()
                .orElseThrow(() -> new IOException("Unknown terrain owner"));
        var property = findProperty(readObject(source.exports.get(terrain.exportIndex() - 1)), "TIntMap");
        return property == null ? Optional.empty() : Optional.of(new int[] {property.start(), property.length()});
    }

    public int getLicenseVersion() {
        return source.licenseVersion;
    }

    public Map<String, Boolean> getLightingOverrides(String ownerPath) throws IOException {
        var terrain = terrains.stream()
                .filter(t -> t.path().equals(ownerPath))
                .findFirst()
                .orElseThrow(() -> new IOException("Unknown terrain owner"));
        var object = readObject(source.exports.get(terrain.exportIndex() - 1));
        var result = new LinkedHashMap<String, Boolean>();
        for (var name : List.of("bStaticLighting", "bUnlit", "bShadowCast")) {
            var property = findProperty(object, name);
            if (property != null) {
                if (property.type() != 3) {
                    throw new IOException("Invalid terrain lighting flag " + name);
                }
                result.put(name, property.boolValue());
            }
        }
        return Map.copyOf(result);
    }

    public Optional<TerrainTimeMapCodec> findAuthoredTimeMaps(String ownerPath) throws IOException {
        var terrain = terrains.stream()
                .filter(t -> t.path().equals(ownerPath))
                .findFirst()
                .orElseThrow(() -> new IOException("Unknown terrain owner"));
        var object = readObject(source.exports.get(terrain.exportIndex() - 1));
        var property = findProperty(object, "TIntMap");
        if (property == null) {
            return Optional.empty();
        }
        if (property.type() != 9 || property.arrayIndex() != 0) {
            throw new IOException("Invalid Terrain TIntMap property");
        }
        var payload = Arrays.copyOfRange(source.body, property.start(), property.start() + property.length());
        return Optional.of(TerrainTimeMapCodec.read(payload, source.names));
    }

    public byte[] encodeUnchanged(Path target) {
        var body = source.body.clone();
        rewrittenTails.forEach((offset, tail) -> System.arraycopy(tail, 0, body, offset, tail.length));
        return source.encode(body, target);
    }

    public byte[] prepareUnlitHeightEdit(Path target, String ownerPath, TerrainHeightPatch patch) throws IOException {
        var output = target.toAbsolutePath().normalize();
        if (output.equals(sourcePath) || Files.exists(output) && Files.isSameFile(output, sourcePath)) {
            throw new IOException("Terrain export must use a separate output map");
        }
        if (!output.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".unr")) {
            throw new IOException("Terrain output must be a .unr map");
        }
        var prepared = prepareUnlitBody(ownerPath, patch);
        var encoded = source.encode(prepared.body(), output);
        verifyUnlitChanges(output, encoded, prepared.terrain());
        return encoded;
    }

    public NativePackageChanges prepareUnlitChanges(String ownerPath, TerrainHeightPatch patch) throws IOException {
        var prepared = prepareUnlitBody(ownerPath, patch);
        var replacements = new LinkedHashMap<Integer, byte[]>();
        for (var entry : source.exports) {
            var end = entry.serialOffset() + entry.serialSize();
            if (!Arrays.equals(source.body, entry.serialOffset(), end, prepared.body(), entry.serialOffset(), end)) {
                replacements.put(entry.index(), Arrays.copyOfRange(prepared.body(), entry.serialOffset(), end));
            }
        }
        return NativePackageChanges.create(
                sourcePath,
                originalSha256,
                source,
                replacements,
                Set.of(),
                (target, encoded) -> verifyUnlitChanges(target, encoded, prepared.terrain()));
    }

    private record PreparedUnlit(byte[] body, Terrain terrain) {}

    public String getUnlitHeightEditRestriction(String ownerPath) throws IOException {
        var terrain = terrains.stream()
                .filter(t -> t.path().equals(ownerPath))
                .findFirst()
                .orElseThrow(() -> new IOException("Unknown terrain owner"));
        if (!TerrainNativeCodec.supports(source.fileVersion, source.licenseVersion)) {
            return "Terrain revision " + source.fileVersion + "/" + source.licenseVersion
                    + " is read-only; native height export is not qualified";
        }
        if (terrain.sectors().stream().anyMatch(s -> s.hasBakedLighting() || s.hasTimeMaps() != 0)) {
            return "Terrain has baked visibility/time maps; exact native lighting regeneration is required";
        }
        if (terrains.size() != 1) {
            return "Height export currently requires one TerrainInfo; neighboring/shared terrain consumers need atomic"
                    + " rebuild";
        }
        if (terrain.info().width() != 256
                || terrain.info().height() != 256
                || terrain.info().sectorsX() != 16
                || terrain.info().sectorsY() != 16) {
            return "Height export currently requires a 256x256 heightmap and 16x16 sector grid";
        }
        if (terrain.heightmapReference() <= 0 || terrain.heightmap().size() != 1) {
            return "Height export currently requires a private local one-mip G16 heightmap";
        }
        if ((source.exports.get(terrain.heightmapReference() - 1).flags() & 4) != 0) {
            return "Public G16 height textures may have cross-package consumers; export requires a private local"
                    + " texture";
        }
        if (terrain.hasDecorations()) {
            return "Terrain decoration regeneration is not yet supported";
        }
        var transformRestriction = authoredTransformRestriction(terrain);
        if (!transformRestriction.isEmpty()) {
            return transformRestriction;
        }
        for (var sector : terrain.sectors()) {
            if (sector.sizeX() != 16 && !(sector.offsetX() == 240 && sector.sizeX() == 15)
                    || sector.sizeY() != 16 && !(sector.offsetY() == 240 && sector.sizeY() == 15)) {
                return "Height export requires complete native 16-quad sectors with the audited outer-edge fixup";
            }
            if (sector.quadRenderPasses().size() != 256) {
                return "Height export requires the complete native 256-entry per-sector topology masks";
            }
        }
        var colors = terrain.info().vertexColors();
        if (!colors.isEmpty() && colors.size() != 256 * 256 * 4) {
            return "Terrain steady-light colors have an unsupported vertex count";
        }
        for (var i = 0; i < colors.size(); i += 4) {
            if (colors.get(i) != 0 || colors.get(i + 1) != 0 || colors.get(i + 2) != 0 || colors.get(i + 3) != 255) {
                return "Terrain has non-neutral serialized steady lighting; native lighting regeneration is required";
            }
        }
        var classes = Set.of(
                "Engine.TerrainInfo",
                "Engine.TerrainSector",
                "Engine.Texture",
                "Engine.Level",
                "Engine.Model",
                "Engine.Polys");
        var hasLevel = false;
        for (var entry : source.exports) {
            var classPath = source.getObjectPath(entry.classIndex());
            if (!classes.contains(classPath)) {
                return "Unlit height export does not admit additional actor/asset class " + classPath;
            }
            if ((entry.flags() & 0x02000000) != 0) {
                return "Terrain height export does not admit serialized script state-frame references";
            }
            var consumer = checkHeightConsumers(
                    readObject(entry).properties(),
                    entry.index() == terrain.exportIndex(),
                    terrain.heightmapReference(),
                    false);
            if (!consumer.isEmpty()) {
                return consumer;
            }
            if (classPath.equals("Engine.Model")) {
                TerrainConsumerGate.requireEmptyModel(
                        source, entry, readObject(entry).offset());
            }
            if (classPath.equals("Engine.Polys")) {
                TerrainConsumerGate.requireEmptyPolys(
                        source, entry, readObject(entry).offset());
            }
            if (classPath.equals("Engine.Texture")) {
                verifyTextureNativeData(entry);
            }
            if (classPath.equals("Engine.Level")) {
                TerrainConsumerGate.requireTerrainLevel(
                        source, entry, readObject(entry).offset(), terrain.exportIndex());
            }
            hasLevel |= classPath.equals("Engine.Level");
        }
        if (hasLevel) {
            for (var ref : NativeLevelEditor.read(source).actors) {
                if (ref != 0 && ref != terrain.exportIndex()) {
                    return "Unlit terrain Level registry contains additional actor consumers";
                }
            }
        } else {
            return "Terrain height export requires an audited native Level registry";
        }
        return "";
    }

    private LayerFields readLayer(int order, Property layer) throws IOException {
        if (layer.type != 10 || !"TerrainLayer".equals(layer.structType)) {
            return new LayerFields(order, layer.arrayIndex, null, null, "This layer is saved in an unknown form", null);
        }
        var c = new Cursor(source.body, layer.start, layer.start + layer.length);
        var fields = readProperties(source, c);
        var offsets = new int[LayerFields.FIELDS + 16];
        Arrays.fill(offsets, -1);
        var names = List.of("UScale", "VScale", "UPan", "VPan", "TextureMapAxis", "TextureRotation", "LayerRotation");
        var alpha = false;
        for (var field : fields) {
            if (field.name.equalsIgnoreCase("AlphaMap") && field.type == 5) {
                alpha = new Cursor(source.body, field.start, field.start + field.length).readIndex() != 0;
            }
            var at = names.indexOf(field.name);
            if (at >= 0) {
                var size = at == 4 ? 1 : at == 6 ? 12 : 4;
                var type = at == 4 ? 1 : at == 6 ? 10 : 4;
                if (field.type == type && field.length == size && (at != 6 || "Rotator".equals(field.structType))) {
                    offsets[at] = field.start;
                }
            } else if (field.name.equals("TerrainMatrix") && field.type == 10 && "Matrix".equals(field.structType)) {
                readMatrix(field, offsets);
            }
        }
        if (!alpha) {
            return new LayerFields(
                    order,
                    layer.arrayIndex,
                    null,
                    null,
                    "This layer has no weight texture, so the game does not draw it",
                    null);
        }
        for (var i = 0; i < offsets.length; i++) {
            if (offsets[i] < 0) {
                var name = i < LayerFields.FIELDS ? names.get(i) : "TerrainMatrix";
                return new LayerFields(
                        order,
                        layer.arrayIndex,
                        null,
                        null,
                        "This layer leaves out its " + name + ", so a new mapping would change the size of the map",
                        null);
            }
        }
        TerrainLayerMapping mapping;
        try {
            mapping = new TerrainLayerMapping(
                    floatAt(offsets[0]),
                    floatAt(offsets[1]),
                    floatAt(offsets[2]),
                    floatAt(offsets[3]),
                    Byte.toUnsignedInt(source.body[offsets[4]]),
                    floatAt(offsets[5]),
                    intAt(offsets[6]),
                    intAt(offsets[6] + 4),
                    intAt(offsets[6] + 8));
        } catch (IllegalArgumentException unusual) {
            return new LayerFields(
                    order,
                    layer.arrayIndex,
                    null,
                    null,
                    "This layer has a texture axis or a scale that Cruma cannot read",
                    null);
        }
        if (mapping.uScale() == 0 || mapping.vScale() == 0) {
            return new LayerFields(
                    order,
                    layer.arrayIndex,
                    null,
                    null,
                    "This layer has a scale of 0, so its texture has no size",
                    null);
        }
        var matrix = new float[16];
        for (var i = 0; i < 16; i++) {
            matrix[i] = floatAt(offsets[LayerFields.FIELDS + i]);
        }
        return new LayerFields(order, layer.arrayIndex, mapping, matrix, "", offsets);
    }

    private void readMatrix(Property matrix, int[] offsets) throws IOException {
        var c = new Cursor(source.body, matrix.start, matrix.start + matrix.length);
        var planes = List.of("XPlane", "YPlane", "ZPlane", "WPlane");
        var components = List.of("X", "Y", "Z", "W");
        for (var plane : readProperties(source, c)) {
            var row = planes.indexOf(plane.name);
            if (row < 0 || plane.type != 10 || !"Plane".equals(plane.structType)) {
                continue;
            }
            var inner = new Cursor(source.body, plane.start, plane.start + plane.length);
            for (var value : readProperties(source, inner)) {
                var column = components.indexOf(value.name);
                if (column >= 0 && value.type == 4 && value.length == 4) {
                    offsets[LayerFields.FIELDS + row * 4 + column] = value.start;
                }
            }
        }
    }

    private float floatAt(int offset) {
        return Float.intBitsToFloat(intAt(offset));
    }

    private int intAt(int offset) {
        var b = source.body;
        return b[offset] & 0xff | (b[offset + 1] & 0xff) << 8 | (b[offset + 2] & 0xff) << 16 | b[offset + 3] << 24;
    }

    private BitSet readBits(int offset, int count) {
        var bits = new BitSet(count);
        for (var i = 0; i < count; i++) {
            if ((intAt(offset + (i >>> 5) * 4) >>> (i & 31) & 1) != 0) {
                bits.set(i);
            }
        }
        return bits;
    }

    private Terrain terrain(String ownerPath) throws IOException {
        return terrains.stream()
                .filter(t -> t.path().equals(ownerPath))
                .findFirst()
                .orElseThrow(() -> new IOException("Unknown terrain owner"));
    }

    private PreparedUnlit prepareUnlitBody(String ownerPath, TerrainHeightPatch patch) throws IOException {
        var terrain = terrains.stream()
                .filter(t -> t.path().equals(ownerPath))
                .findFirst()
                .orElseThrow(() -> new IOException("Unknown terrain owner"));
        var restriction = getUnlitHeightEditRestriction(ownerPath);
        if (!restriction.isEmpty()) {
            throw new IOException(restriction);
        }
        if (!Arrays.equals(Files.readAllBytes(sourcePath), source.encode(source.body, sourcePath))) {
            throw new IOException("Source terrain map changed; reopen before exporting");
        }
        var base = terrain.heightmap().getFirst();
        var field = new TerrainHeightField(base, terrain.info().toWorld());
        try {
            field.apply(patch, false);
        } catch (IllegalArgumentException | IllegalStateException badPatch) {
            throw new IOException("Terrain patch no longer applies: " + badPatch.getMessage(), badPatch);
        }
        var body = source.body.clone();
        for (var i = 0; i < patch.size(); i++) {
            var offset = Math.addExact(base.dataOffset(), patch.getIndex(i) * 2);
            var value = patch.getAfter(i);
            body[offset] = (byte) value;
            body[offset + 1] = (byte) (value >>> 8);
        }
        var affected = field.getAffectedSectors(patch);
        for (var i = 0; i < terrain.sectors().size(); i++) {
            var sector = terrain.sectors().get(i);
            if (!affected.contains(new SectorCoordinate(sector.offsetX() / 16, sector.offsetY() / 16))) {
                continue;
            }
            var bounds = field.getBounds(
                    sector.offsetX(),
                    sector.offsetY(),
                    Math.min(sector.sizeX(), field.getWidth() - 1 - sector.offsetX()),
                    Math.min(sector.sizeY(), field.getHeight() - 1 - sector.offsetY()));
            var edited = new Sector(
                    sector.owner(),
                    sector.sizeX(),
                    sector.sizeY(),
                    sector.offsetX(),
                    sector.offsetY(),
                    bounds,
                    sector.lights(),
                    sector.hasTimeMaps(),
                    sector.timeMaps(),
                    sector.layerUniformity(),
                    sector.quadRenderPasses());
            var tail = TerrainNativeCodec.writeSector(source.fileVersion, source.licenseVersion, edited);
            var offset = nativeOffsets.get(terrain.info().sectors().get(i));
            if (tail.length != rewrittenTails.get(offset).length) {
                throw new IOException("Terrain bounds rewrite unexpectedly changes native export size");
            }
            System.arraycopy(tail, 0, body, offset, tail.length);
        }
        var expected = new TerrainPackageSnapshot(sourcePath, source.encode(body, sourcePath), null);
        return new PreparedUnlit(body, expected.terrains.getFirst());
    }

    private String authoredTransformRestriction(Terrain terrain) throws IOException {
        var tagged = readObject(source.exports.get(terrain.exportIndex() - 1));
        for (var name : List.of("QuadVisibilityBitmap", "EdgeTurnBitmap")) {
            var bitmap = findProperty(tagged, name);
            if (bitmap == null || bitmap.type() != 9) {
                return "Terrain export requires explicit native visibility and edge-turn bitmaps";
            }
            var cursor = new Cursor(source.body, bitmap.start(), bitmap.start() + bitmap.length());
            if (cursor.readIndex() != 2048 || cursor.end - cursor.position != 8192) {
                return "Terrain visibility or edge-turn bitmap dimensions differ from the native grid";
            }
        }
        var scale = readVector(tagged, "TerrainScale");
        var location = readVector(tagged, "Location");
        if (scale == null || location == null || scale.x() <= 0 || scale.y() <= 0 || scale.z() <= 0) {
            return "Terrain export requires explicit finite Location and positive TerrainScale";
        }
        var sx = scale.x();
        var sy = scale.y();
        var sz = scale.z() / 256f;
        var origin = new Vector(location.x() - 128f * sx, location.y() - 128f * sy, location.z() - 32768f * sz);
        if (origin.z() < -16384 || origin.z() + 65535f * sz > 16384) {
            return "Terrain height range exceeds the admitted native world visibility bounds";
        }
        for (var sample : new int[][] {{0, 0, 0}, {255, 0, 65535}, {0, 255, 32768}, {255, 255, 65535}}) {
            var expected =
                    new Vector(origin.x() + sample[0] * sx, origin.y() + sample[1] * sy, origin.z() + sample[2] * sz);
            var actual = terrain.info().toWorld().transform(sample[0], sample[1], sample[2]);
            if (!isNear(expected.x(), actual.x())
                    || !isNear(expected.y(), actual.y())
                    || !isNear(expected.z(), actual.z())) {
                return "Terrain authored transform differs from serialized native FCoords";
            }
            var inverse = terrain.info().toHeightmap().transform(expected.x(), expected.y(), expected.z());
            if (Math.abs(inverse.x() - sample[0]) > .001f
                    || Math.abs(inverse.y() - sample[1]) > .001f
                    || Math.abs(inverse.z() - sample[2]) > .01f) {
                return "Terrain native heightmap coordinates are not the authored transform inverse";
            }
        }
        return "";
    }

    private Vector readVector(NativeObject object, String name) throws IOException {
        var p = findProperty(object, name);
        if (p == null) {
            return null;
        }
        if (p.type() != 10 || !"Vector".equals(p.structType()) || p.length() != 12) {
            throw new IOException("Invalid Terrain " + name);
        }
        var c = new Cursor(source.body, p.start(), p.start() + p.length());
        var x = Float.intBitsToFloat(c.readI32());
        var y = Float.intBitsToFloat(c.readI32());
        var z = Float.intBitsToFloat(c.readI32());
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) {
            throw new IOException("Non-finite Terrain " + name);
        }
        return new Vector(x, y, z);
    }

    private void verifyTextureNativeData(ExportEntry texture) throws IOException {
        if (!TerrainNativeCodec.supports(source.fileVersion, source.licenseVersion)) {
            throw new IOException("Texture consumer export is not admitted for this read-only terrain revision");
        }
        var tagged = readObject(texture);
        var c = materialMips(source, texture, tagged);
        var width = readScalar(source, tagged, "USize", 2, -1);
        var height = readScalar(source, tagged, "VSize", 2, -1);
        if (width < 1
                || height < 1
                || width > 4096
                || height > 4096
                || Integer.bitCount(width) != 1
                || Integer.bitCount(height) != 1) {
            throw new IOException("Terrain consumer texture dimensions are not admitted");
        }
        var count =
                boundedCount(c.readIndex(), 32 - Integer.numberOfLeadingZeros(Math.max(width, height)), "texture mips");
        if (count == 0) {
            throw new IOException("Terrain consumer texture has no mips");
        }
        for (var mip = 0; mip < count; mip++) {
            var end = c.readI32();
            var bytes = boundedCount(c.readIndex(), 64 * 1024 * 1024, "texture mip bytes");
            if ((long) c.position + bytes != end) {
                throw new IOException("Terrain consumer texture lazy offset differs");
            }
            c.skip(bytes);
            if (c.readI32() != width
                    || c.readI32() != height
                    || c.readU8() != Integer.numberOfTrailingZeros(width)
                    || c.readU8() != Integer.numberOfTrailingZeros(height)) {
                throw new IOException("Terrain consumer texture mip dimensions differ");
            }
            width = Math.max(1, width >> 1);
            height = Math.max(1, height >> 1);
        }
        if (c.position != c.end) {
            throw new IOException("Terrain consumer texture has an unaudited native suffix");
        }
    }

    private String checkHeightConsumers(List<Property> properties, boolean owner, int heightReference, boolean nested)
            throws IOException {
        for (var property : properties) {
            if (property.type() == 5 || property.type() == 8) {
                var c = new Cursor(source.body, property.start(), property.start() + property.length());
                var reference = c.readIndex();
                source.checkReference(reference);
                if (c.position != c.end) {
                    throw new IOException("Invalid Terrain consumer object-reference length");
                }
                if (reference == heightReference
                        && !(owner && !nested && property.name().equals("TerrainMap"))) {
                    return "G16 height texture is also used by " + property.name()
                            + "; shared height/material consumers are not admitted";
                }
            } else if (property.type() == 10) {
                if (owner
                        && !nested
                        && property.name().equals("Layers")
                        && "TerrainLayer".equals(property.structType())) {
                    var c = new Cursor(source.body, property.start(), property.start() + property.length());
                    var fields = readProperties(source, c);
                    if (c.position != c.end) {
                        throw new IOException("Unknown TerrainLayer property suffix");
                    }
                    var restriction = checkHeightConsumers(fields, owner, heightReference, true);
                    if (!restriction.isEmpty()) {
                        return restriction;
                    }
                } else if (!Set.of("Vector", "Rotator", "Color", "Plane", "Matrix")
                        .contains(property.structType())) {
                    return "Terrain height export has an unaudited property container " + property.name();
                }
            } else if (property.type() == 9) {
                if (!owner
                        || nested
                        || !(property.name().equals("QuadVisibilityBitmap")
                                || property.name().equals("EdgeTurnBitmap"))) {
                    return "Terrain height export has an unaudited array consumer " + property.name();
                }
            } else if (property.type() >= 14) {
                return "Terrain height export has an unaudited property container " + property.name();
            }
        }
        return "";
    }

    private byte[] copyTail(ExportEntry entry, int offset) {
        return Arrays.copyOfRange(source.body, offset, entry.serialOffset() + entry.serialSize());
    }

    private int readReference(NativeObject object, String name) throws IOException {
        var property = findProperty(object, name);
        if (property == null) {
            return 0;
        }
        if (property.type != 5) {
            throw new IOException("Invalid terrain object property " + name);
        }
        var cursor = new Cursor(source.body, property.start, property.start + property.length);
        var reference = cursor.readIndex();
        if (cursor.position != cursor.end) {
            throw new IOException("Invalid terrain object-reference length");
        }
        source.checkReference(reference);
        return reference;
    }

    private NativeObject readObject(ExportEntry entry) throws IOException {
        return readObject(source, entry);
    }

    private static List<Path> texturePackages(Path clientRoot, String packageName) throws IOException {
        var candidates = new ArrayList<Path>();
        for (var folder : List.of("Textures", "SysTextures")) {
            var directory = clientRoot.resolve(folder);
            if (!Files.isDirectory(directory)) {
                continue;
            }
            try (var files = Files.list(directory)) {
                candidates.addAll(files.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().equalsIgnoreCase(packageName + ".utx"))
                        .toList());
            }
        }
        return candidates;
    }

    private static void putInt(byte[] target, int at, int value) throws IOException {
        if (at < 0 || at + 4 > target.length) {
            throw new IOException("A Terrain layer field lies outside its export");
        }
        target[at] = (byte) value;
        target[at + 1] = (byte) (value >>> 8);
        target[at + 2] = (byte) (value >>> 16);
        target[at + 3] = (byte) (value >>> 24);
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static void verifyUnlitChanges(Path target, byte[] encoded, Terrain expected) throws IOException {
        var verified = new TerrainPackageSnapshot(target, encoded, null);
        var result = verified.terrains.stream()
                .filter(t -> t.exportIndex() == expected.exportIndex())
                .findFirst()
                .orElseThrow(() -> new IOException("Prepared terrain owner is missing"));
        var restriction = verified.getUnlitHeightEditRestriction(result.path());
        if (!restriction.isEmpty()) {
            throw new IOException("Composed map no longer satisfies the unlit Terrain contract: " + restriction);
        }
        if (!result.info().equals(expected.info())
                || !result.sectors().equals(expected.sectors())
                || !result.heightmap().equals(expected.heightmap())) {
            throw new IOException("Prepared terrain heights, bounds or native data did not round-trip");
        }
    }

    private static boolean isNear(float a, float b) {
        return Math.abs(a - b) <= Math.max(.001f, Math.ulp(a) * 2);
    }

    private static List<Mip> readHeightTexture(PackageBytes asset, ExportEntry texture, Map<Integer, byte[]> rewritten)
            throws IOException {
        if (!asset.getObjectPath(texture.classIndex()).equalsIgnoreCase("Engine.Texture")) {
            throw new IOException("Heightmap must be native Engine.Texture");
        }
        var object = readObject(asset, texture);
        if (readScalar(asset, object, "Format", 1, -1) != 10) {
            throw new IOException("Heightmap is not G16");
        }
        var c = materialMips(asset, texture, object);
        var mips = TerrainG16Codec.read(
                Arrays.copyOfRange(asset.body, c.position, c.end),
                c.position,
                readScalar(asset, object, "USize", 2, -1),
                readScalar(asset, object, "VSize", 2, -1));
        if (rewritten != null) {
            rewritten.put(c.position, TerrainG16Codec.write(mips, c.position));
        }
        return mips;
    }

    private static Cursor materialMips(PackageBytes asset, ExportEntry texture, NativeObject object)
            throws IOException {
        var c = new Cursor(asset.body, object.offset, texture.serialOffset() + texture.serialSize());
        TerrainTextureLayout.readPrefix(asset, c);
        return c;
    }

    private static boolean supportsHeightTextureReadOnly(int fileVersion, int licenseVersion) {
        return TerrainTextureLayout.supportsReadOnly(fileVersion, licenseVersion);
    }

    private static int boundedCount(int count, int maximum, String field) throws IOException {
        if (count < 0 || count > maximum) {
            throw new IOException("Invalid terrain height texture " + field);
        }
        return count;
    }

    private static int readScalar(PackageBytes source, NativeObject object, String name, int type, int fallback)
            throws IOException {
        var property = findProperty(object, name);
        if (property == null) {
            return fallback;
        }
        if (property.type != type || property.length != (type == 1 ? 1 : 4)) {
            throw new IOException("Invalid terrain property " + name);
        }
        var cursor = new Cursor(source.body, property.start, property.start + property.length);
        return type == 1 ? cursor.readU8() : cursor.readI32();
    }

    private static Property findProperty(NativeObject object, String name) throws IOException {
        Property found = null;
        for (var property : object.properties) {
            if (property.name.equals(name)) {
                if (found != null || property.arrayIndex != 0) {
                    throw new IOException("Ambiguous terrain property " + name);
                }
                found = property;
            }
        }
        return found;
    }

    private static NativeObject readObject(PackageBytes source, ExportEntry entry) throws IOException {
        var c = new Cursor(source.body, entry.serialOffset(), entry.serialOffset() + entry.serialSize());
        if ((entry.flags() & 0x02000000) != 0) {
            var node = c.readIndex();
            c.readIndex();
            c.skip(12);
            if (node != 0) {
                c.readIndex();
            }
        }
        var props = readProperties(source, c);
        return new NativeObject(c.position, props);
    }

    private static List<Property> readProperties(PackageBytes source, Cursor c) throws IOException {
        var props = new ArrayList<Property>();
        while (true) {
            var name = source.getName(c.readIndex());
            if (name.equalsIgnoreCase("None")) {
                return List.copyOf(props);
            }
            var tag = c.readU8();
            var type = tag & 15;
            if (type < 1 || type > 15) {
                throw new IOException("Invalid terrain property type");
            }
            var structType = type == 10 ? source.getName(c.readIndex()) : null;
            var length = switch (tag >> 4 & 7) {
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
            var start = c.position;
            c.skip(type == 3 ? 0 : length);
            props.add(new Property(
                    name, type, index, start, type == 3 ? 0 : length, type == 3 && (tag & 128) != 0, structType));
        }
    }
}
