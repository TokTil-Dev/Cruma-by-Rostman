package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.PackageBytes.Cursor;
import com.rost.cruma.core.editing.PackageBytes.ExportEntry;
import com.rost.cruma.core.editing.TerrainG16Codec.Mip;
import com.rost.cruma.core.editing.TerrainTextureLayout.Text;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class IsolatedHeightPackage {
    private static final int MAX_PACKAGE_BYTES = 128 * 1024 * 1024;
    private static final Set<String> BYTE_PROPERTIES = Set.of("Format", "UBits", "VBits");
    private static final Set<String> INT_PROPERTIES = Set.of("USize", "VSize", "UClamp", "VClamp", "InternalTime");
    private static final Set<String> INACTIVE_PROPERTIES = Set.of("bRealtime", "bParametric", "bHasComp");
    private final Path sourcePath;
    private final String sourceHash;
    private final PackageBytes source;
    private final List<Texture> textures;
    private final Texture selected;

    private IsolatedHeightPackage(Path path, byte[] file, String heightPath) throws IOException {
        if (file.length > MAX_PACKAGE_BYTES) {
            throw invalidPackage("Height texture package exceeds the size limit");
        }
        sourcePath = path.toAbsolutePath().normalize();
        sourceHash = hash(file);
        source = new PackageBytes(path, file);
        if (!(source.fileVersion == 128 && source.licenseVersion == 39
                || source.fileVersion == 123 && source.licenseVersion == 37)) {
            throw invalidPackage("Height isolation requires an audited P152 128/39 or 123/37 texture profile");
        }
        if (!source.packageName.matches("[A-Za-z0-9_]{1,64}")) {
            throw invalidPackage("Height isolation requires an ASCII package name");
        }
        validateImports();
        var found = new ArrayList<Texture>();
        var paths = new HashSet<String>();
        for (var entry : source.exports) {
            if (!paths.add(source.getObjectPath(entry.index()).toLowerCase(Locale.ROOT))) {
                throw invalidPackage("Ambiguous package object identity");
            }
            if ((entry.flags() & 0x02000000) != 0 || entry.superIndex() != 0) {
                throw invalidPackage("Height isolation does not admit state frames or subclasses");
            }
            validateOuter(entry.outer());
            var classPath = source.getObjectPath(entry.classIndex());
            var cursor = new Cursor(source.body, entry.serialOffset(), entry.serialOffset() + entry.serialSize());
            var properties = readProperties(cursor);
            if (classPath.equals("Engine.Texture")) {
                found.add(readTexture(entry, cursor, properties));
            } else if (classPath.equals("Engine.Palette")) {
                if (!properties.isEmpty() || cursor.readIndex() != 256) {
                    throw invalidPackage("Height isolation requires a plain 256-color Palette");
                }
                cursor.skip(256 * 4);
                requireExactEnd(cursor);
            } else if (classPath.equals("Core.Package")) {
                if (!properties.isEmpty()) {
                    throw invalidPackage("Height package groups cannot contain properties");
                }
                requireExactEnd(cursor);
            } else {
                throw invalidPackage("Unaudited height-package export class: " + classPath);
            }
        }
        textures = List.copyOf(found);
        var candidates = found.stream()
                .filter(t -> {
                    try {
                        return source.getObjectPath(t.reference()).equalsIgnoreCase(heightPath);
                    } catch (IOException impossible) {
                        throw new IllegalStateException(impossible);
                    }
                })
                .toList();
        if (candidates.size() != 1
                || candidates.getFirst().format() != 10
                || candidates.getFirst().width() != 256
                || candidates.getFirst().height() != 256) {
            throw invalidPackage("Selected height must be one 256x256 native G16 export");
        }
        selected = candidates.getFirst();
    }

    public record Prepared(
            Path source, String originalSha256, String packageName, String objectPath, String guid, byte[] bytes) {
        public Prepared {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    private record Property(String name, int type, String structure, int index, int start, int size, boolean truth) {}

    private record Texture(
            int reference, int format, int width, int height, int mipOffset, Text stage, List<Mip> heights) {}

    public static IsolatedHeightPackage open(Path source, String heightObjectPath) throws IOException {
        if (!source.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".utx")
                || Files.size(source) > MAX_PACKAGE_BYTES) {
            throw invalidPackage("Invalid or oversized height texture package");
        }
        return new IsolatedHeightPackage(source, Files.readAllBytes(source), heightObjectPath);
    }

    static void verifyPrepared(Prepared candidate) throws IOException {
        if (candidate == null
                || candidate.source() == null
                || candidate.source().getFileName() == null
                || candidate.packageName() == null
                || !candidate.packageName().matches("[A-Za-z0-9_]{1,64}")
                || candidate.objectPath() == null
                || !candidate.objectPath().startsWith(candidate.packageName() + ".")) {
            throw invalidPackage("Invalid prepared height package identity");
        }
        var sourceName = candidate.source().getFileName().toString();
        var dot = sourceName.lastIndexOf('.');
        if (dot < 1) {
            throw invalidPackage("Invalid prepared source package filename");
        }
        var originalObject = sourceName.substring(0, dot)
                + candidate.objectPath().substring(candidate.packageName().length());
        var original = open(candidate.source(), originalObject);
        if (!original.getOriginalSha256().equals(candidate.originalSha256())) {
            throw invalidPackage("Prepared source height package fingerprint changed");
        }
        var clone = new IsolatedHeightPackage(
                Path.of(candidate.packageName() + ".utx"), candidate.bytes(), candidate.objectPath());
        var before = original.selected.heights().getFirst().heights();
        var after = clone.selected.heights().getFirst().heights();
        var indices = new ArrayList<Integer>();
        for (var i = 0; i < before.size(); i++) {
            if (!before.get(i).equals(after.get(i))) {
                indices.add(i);
            }
        }
        var positions = new int[indices.size()];
        var oldValues = new short[indices.size()];
        var newValues = new short[indices.size()];
        for (var i = 0; i < indices.size(); i++) {
            positions[i] = indices.get(i);
            oldValues[i] = before.get(positions[i]).shortValue();
            newValues[i] = after.get(positions[i]).shortValue();
        }
        TerrainHeightPatch patch;
        try {
            patch = new TerrainHeightPatch(256, 256, positions, oldValues, newValues);
        } catch (IllegalArgumentException rejected) {
            throw new IOException("Prepared height package contains unsupported height changes", rejected);
        }
        if (P152TerrainEditScope.touchesOuterRings(patch)) {
            throw new IOException(
                    "Prepared height package contains unsupported height changes in the outer two sample rings");
        }
        var replay = original.prepare(candidate.packageName(), patch);
        if (!replay.source().equals(candidate.source())
                || !replay.originalSha256().equals(candidate.originalSha256())
                || !replay.packageName().equals(candidate.packageName())
                || !replay.objectPath().equals(candidate.objectPath())
                || !replay.guid().equals(candidate.guid())
                || !Arrays.equals(replay.bytes(), candidate.bytes())) {
            throw invalidPackage("Prepared height package does not match authenticated preparation replay");
        }
    }

    public Path getSource() {
        return sourcePath;
    }

    public String getOriginalSha256() {
        return sourceHash;
    }

    Mip getCapturedHeightmap() {
        return selected.heights().getFirst();
    }

    public String getHeightObjectPath() throws IOException {
        return source.getObjectPath(selected.reference());
    }

    public Prepared prepare(String cloneName, TerrainHeightPatch patch) throws IOException {
        if (cloneName == null
                || !cloneName.matches("[A-Za-z0-9_]{1,64}")
                || cloneName.length() != source.packageName.length()
                || cloneName.equalsIgnoreCase(source.packageName)) {
            throw invalidPackage("Use a different ASCII clone name with the original name length");
        }
        if (!sourceHash.equals(hash(Files.readAllBytes(sourcePath)))) {
            throw invalidPackage("Source height package changed; reopen before preparing a clone");
        }
        if (patch.getWidth() != selected.width() || patch.getHeight() != selected.height()) {
            throw invalidPackage("Height patch dimensions differ");
        }
        if (patch.getWidth() == P152TerrainEditScope.GRID
                && patch.getHeight() == P152TerrainEditScope.GRID
                && P152TerrainEditScope.touchesOuterRings(patch)) {
            throw invalidPackage("This verified 12_24 profile keeps the outer two sample rings");
        }
        var mip = selected.heights().getFirst();
        for (var i = 0; i < patch.size(); i++) {
            if (mip.heights().get(patch.getIndex(i)) != patch.getBefore(i)) {
                throw invalidPackage("Height patch source changed at sample " + patch.getIndex(i));
            }
        }
        var body = source.body.clone();
        var allowed = new BitSet(body.length);
        for (var texture : textures) {
            var stage = texture.stage();
            if (stage.value().indexOf('.') < 0) {
                continue;
            }
            var replacement = cloneName + stage.value().substring(source.packageName.length());
            var bytes = (replacement + "\0").getBytes(StandardCharsets.US_ASCII);
            if (stage.wide() || bytes.length != stage.end() - stage.dataOffset()) {
                throw invalidPackage("Clone stage binding would change the native payload size");
            }
            System.arraycopy(bytes, 0, body, stage.dataOffset(), bytes.length);
            allowed.set(stage.dataOffset(), stage.end());
        }
        for (var i = 0; i < patch.size(); i++) {
            var offset = mip.dataOffset() + patch.getIndex(i) * 2;
            body[offset] = (byte) patch.getAfter(i);
            body[offset + 1] = (byte) (patch.getAfter(i) >>> 8);
            allowed.set(offset, offset + 2);
        }
        var digest = digest();
        digest.update("cruma.height-isolation/v1\0".getBytes(StandardCharsets.UTF_8));
        digest.update(cloneName.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) 0);
        var guid = Arrays.copyOf(digest.digest(HexFormat.of().parseHex(sourceHash)), 16);
        if (Arrays.equals(guid, Arrays.copyOfRange(source.body, 36, 52))) {
            throw invalidPackage("Clone GUID unexpectedly equals the source GUID");
        }
        System.arraycopy(guid, 0, body, 36, 16);
        allowed.set(36, 52);
        var outputPath = Path.of(cloneName + ".utx");
        var bytes = source.encode(body, outputPath);
        var object = cloneName + getHeightObjectPath().substring(source.packageName.length());
        var verified = new IsolatedHeightPackage(outputPath, bytes, object);
        if (!verified.source.exports.equals(source.exports)
                || !verified.source.imports.equals(source.imports)
                || !verified.source.names.equals(source.names)
                || verified.source.body.length != source.body.length) {
            throw invalidPackage("Clone package identities or layout changed unexpectedly");
        }
        for (var i = 0; i < body.length; i++) {
            if (source.body[i] != verified.source.body[i] && !allowed.get(i)) {
                throw invalidPackage("Clone changed an unrelated source byte");
            }
        }
        for (var i = 0; i < patch.size(); i++) {
            if (verified.selected.heights().getFirst().heights().get(patch.getIndex(i)) != patch.getAfter(i)) {
                throw invalidPackage("Cloned G16 sample did not round-trip");
            }
        }
        return new Prepared(
                sourcePath, sourceHash, cloneName, object, HexFormat.of().formatHex(guid), bytes);
    }

    private void validateImports() throws IOException {
        for (var i = 0; i < source.imports.size(); i++) {
            var entry = source.imports.get(i);
            var path = source.getObjectPath(-i - 1);
            var expected = source.getName(entry.classPackage()) + "." + source.getName(entry.className());
            if (entry.outer() == 0 && Set.of("Core", "Engine").contains(path) && expected.equals("Core.Package")) {
                continue;
            }
            if (!Set.of("Engine.Texture", "Engine.Palette", "Core.Package").contains(path)
                    || !expected.equals("Core.Class")) {
                throw invalidPackage("Height isolation has an unaudited or self import: " + path);
            }
        }
    }

    private void validateOuter(int outer) throws IOException {
        var visited = new HashSet<Integer>();
        while (outer != 0) {
            if (outer < 0 || !visited.add(outer)) {
                throw invalidPackage("Height exports require local package-group outers");
            }
            var group = source.exports.get(outer - 1);
            if (!source.getObjectPath(group.classIndex()).equals("Core.Package")) {
                throw invalidPackage("Height export outer is not a plain package group");
            }
            outer = group.outer();
        }
    }

    private Texture readTexture(ExportEntry entry, Cursor cursor, List<Property> properties) throws IOException {
        var values = new HashMap<String, Integer>();
        for (var property : properties) {
            var data = new Cursor(source.body, property.start(), property.start() + property.size());
            if (BYTE_PROPERTIES.contains(property.name()) && property.type() == 1 && property.size() == 1) {
                values.put(property.name(), data.readU8());
            } else if (INT_PROPERTIES.contains(property.name()) && property.type() == 2 && property.size() == 4) {
                values.put(property.name(), data.readI32());
            } else if (Set.of("MipZero", "MaxColor").contains(property.name())
                    && property.type() == 10
                    && "Color".equals(property.structure())
                    && property.size() == 4) {
                data.skip(4);
            } else if (property.name().equals("Palette") && property.type() == 5) {
                var reference = data.readIndex();
                source.checkReference(reference);
                if (reference <= 0
                        || !source.getObjectPath(
                                        source.exports.get(reference - 1).classIndex())
                                .equals("Engine.Palette")) {
                    throw invalidPackage("Texture palette must remain inside the cloned package");
                }
            } else if (INACTIVE_PROPERTIES.contains(property.name()) && property.type() == 3 && !property.truth()) {
            } else {
                throw invalidPackage("Unaudited height-package texture property: " + property.name());
            }
            requireExactEnd(data);
            if (property.index() != 0 && !(property.name().equals("InternalTime") && property.index() == 1)) {
                throw invalidPackage("Unaudited texture property array index");
            }
        }
        int width = values.getOrDefault("USize", 0);
        int height = values.getOrDefault("VSize", 0);
        if (width < 1
                || height < 1
                || width > 4096
                || height > 4096
                || Integer.bitCount(width) != 1
                || Integer.bitCount(height) != 1
                || values.getOrDefault("UBits", -1) != Integer.numberOfTrailingZeros(width)
                || values.getOrDefault("VBits", -1) != Integer.numberOfTrailingZeros(height)
                || values.getOrDefault("UClamp", width) != width
                || values.getOrDefault("VClamp", height) != height) {
            throw invalidPackage("Unaudited height-package texture dimensions");
        }
        var format = values.getOrDefault("Format", -1);
        if (format != -1 && format != 0 && format != 3 && format != 10) {
            throw invalidPackage("Unaudited height-package texture format");
        }
        var prefix = TerrainTextureLayout.readPrefix(source, cursor);
        if (prefix.stages().size() != 1) {
            throw invalidPackage("Texture requires exactly one ASCII native stage bound to itself and no modifiers");
        }
        var stage = prefix.stages().getFirst();
        var binding = stage.material();
        var fullSelf = binding.value().equals(source.getObjectPath(entry.index()))
                && binding.value().matches("[A-Za-z0-9_.]+");
        var shortSelf = source.fileVersion == 123
                && source.licenseVersion == 37
                && entry.outer() > 0
                && binding.value().matches("[A-Za-z0-9_]{1,64}")
                && binding.value().equals(source.getName(entry.objectName()));
        if (!stage.modifiers().isEmpty() || binding.wide() || !fullSelf && !shortSelf) {
            throw invalidPackage("Texture requires exactly one ASCII native stage bound to itself and no modifiers");
        }
        var shader = prefix.shader().value();
        if (!shader.matches("[A-Za-z0-9_.,;=+*/(){}\\[\\]<>!&|% \t\r\n-]*")
                || shader.toLowerCase(Locale.ROOT).contains(source.packageName.toLowerCase(Locale.ROOT))) {
            throw invalidPackage("Shader cache contains an unaudited string or package binding");
        }
        var mipOffset = cursor.position;
        List<Mip> heights = List.of();
        if (format == 10) {
            heights = TerrainG16Codec.read(
                    Arrays.copyOfRange(source.body, cursor.position, cursor.end), cursor.position, width, height);
            if (heights.size() != 1) {
                throw invalidPackage("Height isolation requires one mip per texture");
            }
            cursor.position = cursor.end;
        } else {
            if (cursor.readIndex() != 1) {
                throw invalidPackage("Height isolation requires one mip per texture");
            }
            var end = cursor.readI32();
            var size = cursor.readIndex();
            var expected = format == -1
                    ? -1
                    : format == 0 ? width * height : Math.max(1, width / 4) * Math.max(1, height / 4) * 8;
            if (size <= 0 || expected >= 0 && size != expected || (long) cursor.position + size != end) {
                throw invalidPackage("Texture lazy mip endpoint or size differs");
            }
            cursor.skip(size);
            if (cursor.readI32() != width
                    || cursor.readI32() != height
                    || cursor.readU8() != Integer.numberOfTrailingZeros(width)
                    || cursor.readU8() != Integer.numberOfTrailingZeros(height)) {
                throw invalidPackage("Texture mip trailer differs");
            }
            requireExactEnd(cursor);
        }
        return new Texture(
                entry.index(),
                format,
                width,
                height,
                mipOffset,
                prefix.stages().getFirst().material(),
                heights);
    }

    private List<Property> readProperties(Cursor cursor) throws IOException {
        var result = new ArrayList<Property>();
        var seen = new HashSet<String>();
        while (true) {
            var name = source.getName(cursor.readIndex());
            if (name.equals("None")) {
                return List.copyOf(result);
            }
            var tag = cursor.readU8();
            var type = tag & 15;
            var structure = type == 10 ? source.getName(cursor.readIndex()) : "";
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
            if (!seen.add(name + "#" + index)) {
                throw invalidPackage("Duplicate texture property");
            }
            var start = cursor.position;
            cursor.skip(type == 3 ? 0 : size);
            result.add(new Property(
                    name, type, structure, index, start, type == 3 ? 0 : size, type == 3 && (tag & 128) != 0));
        }
    }

    private static void requireExactEnd(Cursor cursor) throws IOException {
        if (cursor.position != cursor.end) {
            throw invalidPackage("Unaudited height-package native tail or property suffix");
        }
    }

    private static IOException invalidPackage(String reason) {
        return new IOException(reason);
    }

    private static String hash(byte[] bytes) {
        return HexFormat.of().formatHex(digest().digest(bytes));
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
