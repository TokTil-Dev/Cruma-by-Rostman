package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.ActorTransform.Rotator;
import com.rost.cruma.core.editing.ActorTransform.Vector3;
import com.rost.cruma.core.editing.PackageBytes.ImportEntry;
import com.rost.cruma.core.editing.PackageEditDocument.UnsupportedRecoveryFormat;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jetbrains.annotations.Nullable;

final class PackageRecoveryCodec {
    private static final int MAGIC = 0x43524543;
    private static final int VERSION = 10;
    private static final int FIRST_VERSION = 1;
    private static final int MAX_BYTES = 64 * 1024 * 1024;
    private static final int MAX_ITEMS = 1_000_000;
    private static final int MAX_FRAMES = 1026;
    private static final int MAX_STRING_BYTES = 4096;

    private PackageRecoveryCodec() {}

    record Scalar(String name, int type, boolean truth, byte[] payload) {
        Scalar {
            payload = payload.clone();
        }
    }

    record Child(int source, int nameIndex) {}

    record Definition(
            int exportIndex,
            boolean created,
            String path,
            int classReference,
            int nameIndex,
            int meshReference,
            String meshPath,
            String template,
            byte[] tail,
            List<Scalar> scalars,
            List<Child> children,
            @Nullable List<String> movementTags,
            @Nullable String moveEvent) {
        Definition {
            tail = tail.clone();
            scalars = List.copyOf(scalars);
            children = List.copyOf(children);
            movementTags = movementTags == null ? null : List.copyOf(movementTags);
        }

        Definition(
                int exportIndex,
                boolean created,
                String path,
                int classReference,
                int nameIndex,
                int meshReference,
                String meshPath,
                String template,
                byte[] tail,
                List<Scalar> scalars,
                List<Child> children) {
            this(
                    exportIndex,
                    created,
                    path,
                    classReference,
                    nameIndex,
                    meshReference,
                    meshPath,
                    template,
                    tail,
                    scalars,
                    children,
                    null,
                    null);
        }

        Definition(
                int exportIndex,
                boolean created,
                String path,
                int classReference,
                int nameIndex,
                int meshReference,
                String meshPath,
                String template,
                byte[] tail,
                List<Scalar> scalars) {
            this(
                    exportIndex,
                    created,
                    path,
                    classReference,
                    nameIndex,
                    meshReference,
                    meshPath,
                    template,
                    tail,
                    scalars,
                    List.of());
        }
    }

    record Value(int definition, ActorTransform transform, List<Integer> skins, boolean deleted) {
        Value {
            skins = List.copyOf(skins);
        }
    }

    record Tables(List<String> names, List<ImportEntry> imports, Map<String, MaterialReference> materials) {
        Tables {
            names = List.copyOf(names);
            imports = List.copyOf(imports);
            materials = Map.copyOf(materials);
        }
    }

    record LightValue(
            String path,
            LightColor color,
            @Nullable LightPlacement placement,
            boolean deleted,
            @Nullable String template,
            int exportIndex,
            @Nullable LightAim aim,
            @Nullable String moveEvent) {
        LightValue(String path, LightColor color, @Nullable LightPlacement placement) {
            this(path, color, placement, false, null, 0, null, null);
        }

        LightValue(
                String path,
                LightColor color,
                @Nullable LightPlacement placement,
                boolean deleted,
                @Nullable String template,
                int exportIndex,
                @Nullable LightAim aim) {
            this(path, color, placement, deleted, template, exportIndex, aim, null);
        }
    }

    record LightAim(Rotator rotation, int cone) {
        static LightAim of(LightShape shape) {
            return new LightAim(shape.rotation(), shape.cone());
        }

        LightShape applyTo(LightShape saved) {
            return saved.withRotation(rotation).withCone(cone);
        }
    }

    record SurfaceValue(int surface, BspSurfaceMapping mapping) {}

    record SurfaceMaterialValue(int surface, int reference, int uSize, int vSize) {}

    record SurfaceFlagValue(int surface, int flags) {}

    record Frame(
            Tables tables,
            List<Value> values,
            List<LightValue> lights,
            List<SurfaceValue> surfaces,
            List<Integer> hiddenSurfaces,
            List<SurfaceMaterialValue> surfaceMaterials,
            List<Integer> deletedBrushes,
            List<SurfaceFlagValue> surfaceFlags) {
        Frame {
            values = List.copyOf(values);
            lights = List.copyOf(lights);
            surfaces = List.copyOf(surfaces);
            hiddenSurfaces = List.copyOf(hiddenSurfaces);
            surfaceMaterials = List.copyOf(surfaceMaterials);
            deletedBrushes = List.copyOf(deletedBrushes);
            surfaceFlags = List.copyOf(surfaceFlags);
        }

        Frame(Tables tables, List<Value> values, List<LightValue> lights) {
            this(tables, values, lights, List.of(), List.of(), List.of(), List.of(), List.of());
        }
    }

    record Bundle(
            String sourceHash,
            String packageName,
            List<Definition> definitions,
            Frame current,
            Frame saved,
            List<Frame> history) {
        Bundle {
            definitions = List.copyOf(definitions);
            history = List.copyOf(history);
        }
    }

    static byte[] encode(Bundle bundle) throws IOException {
        return encode(bundle, VERSION);
    }

    static byte[] encode(Bundle bundle, int version) throws IOException {
        if (version < 4 || version > VERSION) {
            throw new IllegalArgumentException("Only drafts of version 4 to " + VERSION + " can be written");
        }
        if (bundle.history.size() + 2 > MAX_FRAMES) {
            throw invalidRecovery("Too many recovery snapshots");
        }
        var frames = new LinkedHashMap<Frame, Integer>();
        intern(frames, bundle.current);
        intern(frames, bundle.saved);
        for (var frame : bundle.history) {
            intern(frames, frame);
        }
        var tables = new LinkedHashMap<Tables, Integer>();
        for (var frame : frames.keySet()) {
            intern(tables, frame.tables);
        }
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            writeString(out, bundle.sourceHash);
            writeString(out, bundle.packageName);
            count(out, bundle.definitions.size(), MAX_ITEMS);
            for (var definition : bundle.definitions) {
                out.writeInt(definition.exportIndex);
                out.writeBoolean(definition.created);
                writeString(out, definition.path);
                if (definition.created) {
                    out.writeInt(definition.classReference);
                    out.writeInt(definition.nameIndex);
                    out.writeInt(definition.meshReference);
                    writeString(out, definition.meshPath);
                    writeString(out, definition.template);
                    writeBlob(out, definition.tail, 2);
                    count(out, definition.scalars.size(), 256);
                    for (var scalar : definition.scalars) {
                        writeString(out, scalar.name);
                        out.writeInt(scalar.type);
                        out.writeBoolean(scalar.truth);
                        writeBlob(out, scalar.payload, 4);
                    }
                    if (version >= 9) {
                        count(out, definition.children.size(), MAX_ITEMS);
                        for (var child : definition.children) {
                            out.writeInt(child.source);
                            out.writeInt(child.nameIndex);
                        }
                    } else if (!definition.children.isEmpty()) {
                        throw new IllegalArgumentException("A draft before version 9 holds no copy of an effect");
                    }
                    if (version >= 10) {
                        out.writeBoolean(definition.movementTags != null);
                        if (definition.movementTags != null) {
                            count(out, definition.movementTags.size(), 12);
                            for (var tag : definition.movementTags) {
                                writeString(out, tag);
                            }
                        }
                        out.writeBoolean(definition.moveEvent != null);
                        if (definition.moveEvent != null) {
                            writeString(out, definition.moveEvent);
                        }
                    } else if (definition.movementTags != null || definition.moveEvent != null) {
                        throw new IllegalArgumentException("A draft before version 10 holds no copy of a carrier");
                    }
                }
                checkLimit(bytes);
            }
            count(out, tables.size(), MAX_FRAMES);
            for (var table : tables.keySet()) {
                count(out, table.names.size(), MAX_ITEMS);
                for (var name : table.names) {
                    writeString(out, name);
                    checkLimit(bytes);
                }
                count(out, table.imports.size(), MAX_ITEMS);
                for (var entry : table.imports) {
                    out.writeInt(entry.classPackage());
                    out.writeInt(entry.className());
                    out.writeInt(entry.outer());
                    out.writeInt(entry.objectName());
                }
                var materials = table.materials.values().stream()
                        .sorted(Comparator.comparing(MaterialReference::sourcePath))
                        .toList();
                count(out, materials.size(), MAX_ITEMS);
                for (var material : materials) {
                    writeString(out, material.sourcePath());
                    writeString(out, material.className());
                    out.writeInt(material.objectIndex());
                }
                checkLimit(bytes);
            }
            count(out, frames.size(), MAX_FRAMES);
            for (var frame : frames.keySet()) {
                out.writeInt(tables.get(frame.tables));
                count(out, frame.values.size(), MAX_ITEMS);
                for (var value : frame.values) {
                    out.writeInt(value.definition);
                    transform(out, value.transform);
                    count(out, value.skins.size(), 256);
                    for (var skin : value.skins) {
                        out.writeInt(skin);
                    }
                    out.writeBoolean(value.deleted);
                    checkLimit(bytes);
                }
                count(out, frame.lights.size(), MAX_ITEMS);
                for (var light : frame.lights) {
                    writeString(out, light.path);
                    out.writeFloat(light.color.brightness());
                    out.writeByte(light.color.hue());
                    out.writeByte(light.color.saturation());
                    var placement = light.placement;
                    if (placement == null) {
                        throw new IOException("A recovery light needs its placement");
                    }
                    out.writeFloat(placement.location().x());
                    out.writeFloat(placement.location().y());
                    out.writeFloat(placement.location().z());
                    out.writeFloat(placement.radius());
                    out.writeBoolean(light.deleted);
                    out.writeBoolean(light.template != null);
                    if (light.template != null) {
                        writeString(out, light.template);
                        out.writeInt(light.exportIndex);
                        if (version >= 10) {
                            out.writeBoolean(light.moveEvent != null);
                            if (light.moveEvent != null) {
                                writeString(out, light.moveEvent);
                            }
                        }
                    }
                    if (version >= 5) {
                        if (light.aim == null) {
                            throw new IOException("A recovery light needs its direction and cone");
                        }
                        out.writeInt(light.aim.rotation().pitch());
                        out.writeInt(light.aim.rotation().yaw());
                        out.writeInt(light.aim.rotation().roll());
                        out.writeByte(light.aim.cone());
                    }
                    checkLimit(bytes);
                }
                count(out, frame.surfaces.size(), MAX_ITEMS);
                for (var surface : frame.surfaces) {
                    out.writeInt(surface.surface);
                    for (var vector :
                            new Vector3[] {surface.mapping.base(), surface.mapping.u(), surface.mapping.v()}) {
                        out.writeFloat(vector.x());
                        out.writeFloat(vector.y());
                        out.writeFloat(vector.z());
                    }
                    checkLimit(bytes);
                }
                if (version >= 6) {
                    count(out, frame.hiddenSurfaces.size(), MAX_ITEMS);
                    for (var surface : frame.hiddenSurfaces) {
                        out.writeInt(surface);
                    }
                    count(out, frame.surfaceMaterials.size(), MAX_ITEMS);
                    for (var material : frame.surfaceMaterials) {
                        out.writeInt(material.surface);
                        out.writeInt(material.reference);
                        out.writeInt(material.uSize);
                        out.writeInt(material.vSize);
                    }
                    checkLimit(bytes);
                } else if (!frame.hiddenSurfaces.isEmpty() || !frame.surfaceMaterials.isEmpty()) {
                    throw new IOException("A draft before version 6 cannot keep hidden surfaces or their materials");
                }
                if (version >= 7) {
                    count(out, frame.deletedBrushes.size(), MAX_ITEMS);
                    for (var brush : frame.deletedBrushes) {
                        out.writeInt(brush);
                    }
                    checkLimit(bytes);
                } else if (!frame.deletedBrushes.isEmpty()) {
                    throw new IOException("A draft before version 7 cannot keep deleted brushes");
                }
                if (version >= 8) {
                    count(out, frame.surfaceFlags.size(), MAX_ITEMS);
                    for (var flags : frame.surfaceFlags) {
                        out.writeInt(flags.surface);
                        out.writeInt(flags.flags);
                    }
                    checkLimit(bytes);
                } else if (!frame.surfaceFlags.isEmpty()) {
                    throw new IOException("A draft before version 8 cannot keep the flags of surfaces");
                }
            }
            out.writeInt(frames.get(bundle.current));
            out.writeInt(frames.get(bundle.saved));
            count(out, bundle.history.size(), MAX_FRAMES - 2);
            for (var frame : bundle.history) {
                out.writeInt(frames.get(frame));
            }
        }
        checkLimit(bytes);
        var body = bytes.toByteArray();
        var result = new ByteArrayOutputStream(body.length + 44);
        try (var out = new DataOutputStream(result)) {
            out.writeInt(MAGIC);
            out.writeInt(version);
            out.writeInt(body.length);
            out.write(hash(body));
            out.write(body);
        }
        return result.toByteArray();
    }

    static Bundle decode(byte[] encoded) throws IOException {
        try {
            return decodeBody(envelopeBody(encoded), envelopeVersion(encoded));
        } catch (IllegalArgumentException | IndexOutOfBoundsException failure) {
            throw new IOException("Malformed recovery data", failure);
        }
    }

    static void validateEnvelope(byte[] encoded) throws IOException {
        envelopeBody(encoded);
    }

    private static byte[] envelopeBody(byte[] encoded) throws IOException {
        if (encoded == null || encoded.length < 8) {
            throw invalidRecovery("Invalid recovery payload size");
        }
        try (var input = new DataInputStream(new ByteArrayInputStream(encoded))) {
            if (input.readInt() != MAGIC) {
                throw invalidRecovery("Invalid recovery format");
            }
            var version = input.readInt();
            if (version < FIRST_VERSION || version > VERSION) {
                throw new UnsupportedRecoveryFormat(
                        "This native package recovery uses an unsupported version. It has been preserved.");
            }
            if (encoded.length < 44 || encoded.length > MAX_BYTES + 44) {
                throw invalidRecovery("Invalid recovery payload size");
            }
            var length = input.readInt();
            var expected = input.readNBytes(32);
            if (length < 0 || length != encoded.length - 44) {
                throw invalidRecovery("Recovery payload length differs");
            }
            var body = input.readNBytes(length);
            if (!MessageDigest.isEqual(expected, hash(body))) {
                throw invalidRecovery("Recovery checksum differs");
            }
            return body;
        }
    }

    private static int envelopeVersion(byte[] encoded) {
        return ByteBuffer.wrap(encoded, 4, 4).getInt();
    }

    private static Bundle decodeBody(byte[] body, int version) throws IOException {
        try (var input = new DataInputStream(new ByteArrayInputStream(body))) {
            var sourceHash = readString(input);
            var packageName = readString(input);
            if (!sourceHash.matches("[0-9a-f]{64}")) {
                throw invalidRecovery("Invalid recovery source hash");
            }
            var definitions = new ArrayList<Definition>();
            for (var remaining = count(input, MAX_ITEMS, 9); remaining > 0; remaining--) {
                var export = input.readInt();
                var created = readBool(input);
                var path = readString(input);
                if (!created) {
                    definitions.add(new Definition(export, false, path, 0, 0, 0, "", "", new byte[0], List.of()));
                } else {
                    var classReference = input.readInt();
                    var name = input.readInt();
                    var mesh = input.readInt();
                    var meshPath = readString(input);
                    var template = readString(input);
                    var tail = readBlob(input, 2);
                    var scalars = new ArrayList<Scalar>();
                    for (var count = count(input, 256, 13); count > 0; count--) {
                        scalars.add(
                                new Scalar(readString(input), input.readInt(), readBool(input), readBlob(input, 4)));
                    }
                    var children = new ArrayList<Child>();
                    if (version >= 9) {
                        for (var count = count(input, MAX_ITEMS, 8); count > 0; count--) {
                            children.add(new Child(input.readInt(), input.readInt()));
                        }
                    }
                    List<String> movementTags = null;
                    String moveEvent = null;
                    if (version >= 10) {
                        if (readBool(input)) {
                            movementTags = new ArrayList<>();
                            for (var count = count(input, 12, 2); count > 0; count--) {
                                movementTags.add(readString(input));
                            }
                        }
                        if (readBool(input)) {
                            moveEvent = readString(input);
                        }
                    }
                    definitions.add(new Definition(
                            export,
                            true,
                            path,
                            classReference,
                            name,
                            mesh,
                            meshPath,
                            template,
                            tail,
                            scalars,
                            children,
                            movementTags,
                            moveEvent));
                }
            }
            var tables = new ArrayList<Tables>();
            for (var remaining = count(input, MAX_FRAMES, 12); remaining > 0; remaining--) {
                var names = new ArrayList<String>();
                for (var count = count(input, MAX_ITEMS, 4); count > 0; count--) {
                    names.add(readString(input));
                }
                var imports = new ArrayList<ImportEntry>();
                for (var count = count(input, MAX_ITEMS, 16); count > 0; count--) {
                    imports.add(new ImportEntry(input.readInt(), input.readInt(), input.readInt(), input.readInt()));
                }
                var materials = new TreeMap<String, MaterialReference>(String.CASE_INSENSITIVE_ORDER);
                for (var count = count(input, MAX_ITEMS, 12); count > 0; count--) {
                    var path = readString(input);
                    var material = new MaterialReference(path, readString(input), input.readInt());
                    if (materials.put(path, material) != null) {
                        throw invalidRecovery("Duplicate recovery material");
                    }
                }
                tables.add(new Tables(names, imports, materials));
            }
            var frames = new ArrayList<Frame>();
            for (var remaining = count(input, MAX_FRAMES, 8); remaining > 0; remaining--) {
                var table = at(tables, input.readInt());
                var values = new ArrayList<Value>();
                for (var count = count(input, MAX_ITEMS, 61); count > 0; count--) {
                    var definition = input.readInt();
                    at(definitions, definition);
                    var placement = transform(input);
                    var skins = new ArrayList<Integer>();
                    for (var slots = count(input, 256, 4); slots > 0; slots--) {
                        skins.add(input.readInt());
                    }
                    values.add(new Value(definition, placement, skins, readBool(input)));
                }
                var lights = new ArrayList<LightValue>();
                if (version >= 2) {
                    for (var count = count(input, MAX_ITEMS, 10); count > 0; count--) {
                        var path = readString(input);
                        var color =
                                new LightColor(input.readFloat(), input.readUnsignedByte(), input.readUnsignedByte());
                        var placement = version >= 3
                                ? new LightPlacement(
                                        new Vector3(input.readFloat(), input.readFloat(), input.readFloat()),
                                        input.readFloat())
                                : null;
                        var deleted = version >= 4 && readBool(input);
                        String template = null;
                        var exportIndex = 0;
                        String moveEvent = null;
                        if (version >= 4 && readBool(input)) {
                            template = readString(input);
                            exportIndex = input.readInt();
                            if (version >= 10 && readBool(input)) {
                                moveEvent = readString(input);
                            }
                        }
                        var aim = version >= 5
                                ? new LightAim(
                                        new Rotator(input.readInt(), input.readInt(), input.readInt()),
                                        input.readUnsignedByte())
                                : null;
                        lights.add(
                                new LightValue(path, color, placement, deleted, template, exportIndex, aim, moveEvent));
                    }
                }
                var surfaces = new ArrayList<SurfaceValue>();
                if (version >= 4) {
                    for (var count = count(input, MAX_ITEMS, 40); count > 0; count--) {
                        var surface = input.readInt();
                        var vectors = new Vector3[3];
                        for (var i = 0; i < 3; i++) {
                            vectors[i] = new Vector3(input.readFloat(), input.readFloat(), input.readFloat());
                        }
                        surfaces.add(
                                new SurfaceValue(surface, new BspSurfaceMapping(vectors[0], vectors[1], vectors[2])));
                    }
                }
                var hiddenSurfaces = new ArrayList<Integer>();
                var surfaceMaterials = new ArrayList<SurfaceMaterialValue>();
                if (version >= 6) {
                    for (var count = count(input, MAX_ITEMS, 4); count > 0; count--) {
                        hiddenSurfaces.add(input.readInt());
                    }
                    for (var count = count(input, MAX_ITEMS, 16); count > 0; count--) {
                        surfaceMaterials.add(new SurfaceMaterialValue(
                                input.readInt(), input.readInt(), input.readInt(), input.readInt()));
                    }
                }
                var deletedBrushes = new ArrayList<Integer>();
                if (version >= 7) {
                    for (var count = count(input, MAX_ITEMS, 4); count > 0; count--) {
                        deletedBrushes.add(input.readInt());
                    }
                }
                var surfaceFlags = new ArrayList<SurfaceFlagValue>();
                if (version >= 8) {
                    for (var count = count(input, MAX_ITEMS, 8); count > 0; count--) {
                        surfaceFlags.add(new SurfaceFlagValue(input.readInt(), input.readInt()));
                    }
                }
                frames.add(new Frame(
                        table,
                        values,
                        lights,
                        surfaces,
                        hiddenSurfaces,
                        surfaceMaterials,
                        deletedBrushes,
                        surfaceFlags));
            }
            var current = at(frames, input.readInt());
            var saved = at(frames, input.readInt());
            var history = new ArrayList<Frame>();
            for (var count = count(input, MAX_FRAMES - 2, 4); count > 0; count--) {
                history.add(at(frames, input.readInt()));
            }
            if (input.available() != 0) {
                throw invalidRecovery("Trailing recovery bytes");
            }
            return new Bundle(sourceHash, packageName, definitions, current, saved, history);
        }
    }

    private static void transform(DataOutputStream out, ActorTransform value) throws IOException {
        writeVector(out, value.location());
        out.writeInt(value.rotation().pitch());
        out.writeInt(value.rotation().yaw());
        out.writeInt(value.rotation().roll());
        out.writeFloat(value.drawScale());
        writeVector(out, value.drawScale3d());
        writeVector(out, value.prePivot());
    }

    private static ActorTransform transform(DataInputStream input) throws IOException {
        return new ActorTransform(
                readVector(input),
                new Rotator(input.readInt(), input.readInt(), input.readInt()),
                input.readFloat(),
                readVector(input),
                readVector(input));
    }

    private static void writeVector(DataOutputStream out, Vector3 value) throws IOException {
        out.writeFloat(value.x());
        out.writeFloat(value.y());
        out.writeFloat(value.z());
    }

    private static Vector3 readVector(DataInputStream input) throws IOException {
        return new Vector3(input.readFloat(), input.readFloat(), input.readFloat());
    }

    private static <T> void intern(Map<T, Integer> map, T value) {
        map.computeIfAbsent(value, _ -> map.size());
    }

    private static <T> T at(List<T> values, int index) throws IOException {
        if (index < 0 || index >= values.size()) {
            throw invalidRecovery("Invalid recovery table index");
        }
        return values.get(index);
    }

    private static boolean readBool(DataInputStream input) throws IOException {
        var value = input.readUnsignedByte();
        if (value > 1) {
            throw invalidRecovery("Invalid recovery boolean");
        }
        return value == 1;
    }

    private static void checkLimit(ByteArrayOutputStream output) throws IOException {
        if (output.size() > MAX_BYTES) {
            throw invalidRecovery("Recovery exceeds 64 MiB limit");
        }
    }

    private static void count(DataOutputStream out, int count, int maximum) throws IOException {
        if (count < 0 || count > maximum) {
            throw invalidRecovery("Recovery count exceeds limit");
        }
        out.writeInt(count);
    }

    private static int count(DataInputStream input, int maximum, int minimumBytes) throws IOException {
        var count = input.readInt();
        if (count < 0 || count > maximum || (long) count * minimumBytes > input.available()) {
            throw invalidRecovery("Invalid recovery count");
        }
        return count;
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        writeBlob(out, value.getBytes(StandardCharsets.UTF_8), MAX_STRING_BYTES);
    }

    private static String readString(DataInputStream input) throws IOException {
        return StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(readBlob(input, MAX_STRING_BYTES)))
                .toString();
    }

    private static void writeBlob(DataOutputStream out, byte[] value, int maximum) throws IOException {
        count(out, value.length, maximum);
        out.write(value);
    }

    private static byte[] readBlob(DataInputStream input, int maximum) throws IOException {
        var size = count(input, maximum, 1);
        return input.readNBytes(size);
    }

    private static byte[] hash(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static IOException invalidRecovery(String message) {
        return new IOException(message);
    }
}
