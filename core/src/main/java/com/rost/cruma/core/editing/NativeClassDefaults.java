package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.PackageBytes.Cursor;
import com.rost.cruma.core.editing.PackageBytes.ExportEntry;
import java.io.IOException;
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
import java.util.Optional;

public final class NativeClassDefaults {
    private final PackageBytes source;
    private final String sourceSha256;

    private NativeClassDefaults(Path path, byte[] bytes) throws IOException {
        var frozen = bytes.clone();
        source = new PackageBytes(path, frozen);
        if (source.fileVersion != 132 || source.licenseVersion != 40) {
            throw new IOException(
                    "Unaudited compiled class revision " + source.fileVersion + "/" + source.licenseVersion);
        }
        try {
            sourceSha256 = HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(frozen));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    public record Property(
            String name,
            int type,
            int arrayIndex,
            String structType,
            boolean booleanValue,
            byte[] payload,
            String referencedObject) {
        public Property {
            payload = payload.clone();
        }

        @Override
        public byte[] payload() {
            return payload.clone();
        }
    }

    public record Definition(
            String path,
            String superClass,
            String sourceSha256,
            int classFlags,
            int defaultsOffset,
            List<Property> properties) {
        public Definition {
            properties = List.copyOf(properties);
        }
    }

    public record Origin(String declaringClass, String sourceSha256, Property property) {}

    public record Inheritance(List<Definition> chain) {
        public Inheritance {
            chain = List.copyOf(chain);
        }

        public Optional<Origin> findProperty(String name, int arrayIndex) {
            for (var definition : chain) {
                for (var property : definition.properties()) {
                    if (property.name().equalsIgnoreCase(name) && property.arrayIndex() == arrayIndex) {
                        return Optional.of(new Origin(definition.path(), definition.sourceSha256(), property));
                    }
                }
            }
            return Optional.empty();
        }

        public Optional<Boolean> booleanValue(String name) throws IOException {
            var value = findProperty(name, 0);
            if (value.isEmpty()) {
                return Optional.empty();
            }
            if (value.orElseThrow().property().type() != 3) {
                throw new IOException("Class default is not a Boolean: " + name);
            }
            return Optional.of(value.orElseThrow().property().booleanValue());
        }
    }

    public static NativeClassDefaults read(Path path, byte[] bytes) throws IOException {
        return new NativeClassDefaults(path, bytes);
    }

    public static Inheritance resolve(List<NativeClassDefaults> packages, String classPath) throws IOException {
        var byName = new LinkedHashMap<String, NativeClassDefaults>();
        for (var source : packages) {
            if (byName.putIfAbsent(toKey(source.getPackageName()), source) != null) {
                throw new IOException("Ambiguous compiled class package " + source.getPackageName());
            }
        }
        var result = new ArrayList<Definition>();
        var seen = new HashSet<String>();
        var path = classPath;
        while (!path.isEmpty()) {
            if (!seen.add(toKey(path)) || result.size() >= 256) {
                throw new IOException("Cyclic or excessive compiled class inheritance");
            }
            var dot = path.indexOf('.');
            var source = dot > 0 ? byName.get(toKey(path.substring(0, dot))) : null;
            if (source == null) {
                throw new IOException("Missing compiled class package for " + path);
            }
            var definition = source.readDefinition(path);
            result.add(definition);
            path = definition.superClass();
        }
        if (result.isEmpty() || !result.getLast().path().equalsIgnoreCase("Core.Object")) {
            throw new IOException("Compiled class inheritance does not end at Core.Object");
        }
        return new Inheritance(result);
    }

    public String getPackageName() {
        return source.packageName;
    }

    public String getSourceSha256() {
        return sourceSha256;
    }

    public Definition readDefinition(String path) throws IOException {
        ExportEntry found = null;
        for (var entry : source.exports) {
            if (entry.classIndex() == 0 && source.getObjectPath(entry.index()).equalsIgnoreCase(path)) {
                if (found != null) {
                    throw new IOException("Ambiguous compiled class " + path);
                }
                found = entry;
            }
        }
        if (found == null) {
            throw new IOException("Missing compiled class " + path);
        }
        if ((found.flags() & 0x02000000) != 0 || found.serialSize() > 1024 * 1024) {
            throw new IOException("Unaudited compiled class state frame or size");
        }
        var c = new Cursor(source.body, found.serialOffset(), found.serialOffset() + found.serialSize());
        var superReference = classReference(c);
        if (superReference != found.superIndex()) {
            throw new IOException("Compiled class superclass disagrees with its export table");
        }
        readReference(c);
        readReference(c);
        readReference(c);
        var friendlyName = source.getName(c.readIndex());
        if (!friendlyName.equalsIgnoreCase(source.getName(found.objectName()))) {
            throw new IOException("Compiled class friendly name disagrees with its export table");
        }
        readReference(c);
        c.readI32();
        c.readI32();
        var scriptMemoryBytes = c.readI32();
        P152ClassScript.skip(c, scriptMemoryBytes, source::checkReference);
        c.skip(16);
        c.readU16();
        c.readI32();
        var classFlags = c.readI32();
        c.skip(16);
        var dependencies = count(c, 4096);
        for (var i = 0; i < dependencies; i++) {
            classReference(c);
            c.readI32();
            c.readI32();
        }
        skipNames(c);
        classReference(c);
        source.getName(c.readIndex());
        skipNames(c);
        var defaultsOffset = c.position;
        var properties = readProperties(c);
        if (c.position != c.end) {
            throw new IOException("Unread compiled class suffix");
        }
        return new Definition(
                source.getObjectPath(found.index()),
                superReference == 0 ? "" : source.getObjectPath(superReference),
                sourceSha256,
                classFlags,
                defaultsOffset,
                properties);
    }

    private int readReference(Cursor c) throws IOException {
        var value = c.readIndex();
        source.checkReference(value);
        return value;
    }

    private int classReference(Cursor c) throws IOException {
        var value = readReference(c);
        if (value > 0 && source.exports.get(value - 1).classIndex() != 0) {
            throw new IOException("Compiled class reference does not refer to a Class");
        }
        if (value < 0) {
            var entry = source.imports.get(-value - 1);
            if (!source.getName(entry.classPackage()).equalsIgnoreCase("Core")
                    || !source.getName(entry.className()).equalsIgnoreCase("Class")) {
                throw new IOException("Compiled class reference import is not Core.Class");
            }
        }
        return value;
    }

    private void skipNames(Cursor c) throws IOException {
        var count = count(c, 4096);
        for (var i = 0; i < count; i++) {
            source.getName(c.readIndex());
        }
    }

    private List<Property> readProperties(Cursor c) throws IOException {
        var result = new ArrayList<Property>();
        var seen = new HashSet<String>();
        while (result.size() < 16384) {
            var name = source.getName(c.readIndex());
            if (name.equalsIgnoreCase("None")) {
                return List.copyOf(result);
            }
            var info = c.readU8();
            var type = info & 15;
            if (type == 0) {
                throw new IOException("Invalid compiled default property type");
            }
            var struct = type == 10 ? source.getName(c.readIndex()) : "";
            var sizeCode = info >> 4 & 7;
            var length = switch (sizeCode) {
                case 0 -> 1;
                case 1 -> 2;
                case 2 -> 4;
                case 3 -> 12;
                case 4 -> 16;
                case 5 -> c.readU8();
                case 6 -> c.readU16();
                default -> c.readI32();
            };
            var arrayIndex = type != 3 && (info & 128) != 0 ? c.readArrayIndex() : 0;
            if (!seen.add(toKey(name) + ":" + arrayIndex)) {
                throw new IOException("Duplicate compiled default property " + name);
            }
            if (type == 3 && length != 0 && length != 1
                    || type == 1 && length != 1
                    || (type == 2 || type == 4) && length != 4
                    || (type == 11 || type == 12) && length != 12) {
                throw new IOException("Invalid compiled default scalar size: " + name);
            }
            var start = c.position;
            c.skip(type == 3 ? 0 : length);
            var objectPath = "";
            if (type == 5 || type == 8 || type == 6) {
                var operand = new Cursor(source.body, start, c.position);
                if (type == 6) {
                    source.getName(operand.readIndex());
                } else {
                    objectPath = source.getObjectPath(type == 8 ? classReference(operand) : readReference(operand));
                }
                if (operand.position != operand.end) {
                    throw new IOException("Unread compiled default reference payload");
                }
            }
            result.add(new Property(
                    name,
                    type,
                    arrayIndex,
                    struct,
                    type == 3 && (info & 128) != 0,
                    Arrays.copyOfRange(source.body, start, c.position),
                    objectPath));
        }
        throw new IOException("Excessive compiled class defaults");
    }

    private static int count(Cursor c, int maximum) throws IOException {
        var count = c.readIndex();
        if (count < 0 || count > maximum) {
            throw new IOException("Invalid compiled class array count");
        }
        return count;
    }

    private static String toKey(String value) {
        return value.toLowerCase(Locale.ROOT);
    }
}
