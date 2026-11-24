package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.PackageBytes.Cursor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

final class ClassSchema {
    private static final int MOST_PARENTS = 32;
    private static final int MOST_FIELDS = 256;
    private static final Field UNKNOWN = new Field("", null, null, null);

    private final Map<String, Field> fields = new HashMap<>();
    private final Map<String, String> parents = new HashMap<>();
    private final Map<String, Signature> functions = new HashMap<>();
    private final Map<Integer, Signature> natives = new HashMap<>();

    private ClassSchema() {}

    record Field(
            String kind,
            @Nullable List<String> values,
            @Nullable String struct,
            @Nullable Field inner) {}

    record Signature(
            String name,
            String owner,
            int flags,
            int precedence,
            List<Field> parameters,
            List<Integer> parameterFlags,
            @Nullable Field result) {
        boolean is(int flag) {
            return (flags & flag) != 0;
        }

        boolean isOut(int index) {
            return index < parameterFlags.size() && (parameterFlags.get(index) & ScriptProperty.CPF_OUT_PARM) != 0;
        }

        boolean isCoerced(int index) {
            return index < parameterFlags.size() && (parameterFlags.get(index) & ScriptProperty.CPF_COERCE_PARM) != 0;
        }
    }

    static ClassSchema read(@Nullable Path systemDirectory) {
        var schema = new ClassSchema();
        if (systemDirectory == null || !Files.isDirectory(systemDirectory)) {
            return schema;
        }
        for (var name : List.of("Engine.u", "Core.u")) {
            try (var entries = Files.list(systemDirectory)) {
                var file = entries.filter(
                                entry -> entry.getFileName().toString().equalsIgnoreCase(name))
                        .findFirst()
                        .orElse(null);
                if (file != null) {
                    schema.add(ClassPackageWriter.open(new PackageBytes(file, Files.readAllBytes(file))));
                }
            } catch (IOException | RuntimeException unreadable) {
            }
        }
        return schema;
    }

    static ClassSchema empty() {
        return new ClassSchema();
    }

    static @Nullable Field field(ClassPackageWriter writer, int export) {
        var property = ScriptProperty.read(writer, export);
        return property == null ? null : field(writer, property, 0);
    }

    ClassSchema with(ClassPackageWriter writer) throws IOException {
        var result = new ClassSchema();
        result.fields.putAll(fields);
        result.parents.putAll(parents);
        result.functions.putAll(functions);
        result.natives.putAll(natives);
        result.add(writer);
        return result;
    }

    @Nullable
    Field find(@Nullable String owner, String name) {
        var at = owner == null ? null : key(owner);
        for (var steps = 0; at != null && steps < MOST_PARENTS; steps++) {
            var field = fields.get(at + "." + key(name));
            if (field != null) {
                return field;
            }
            at = parents.get(at);
        }
        return null;
    }

    @Nullable
    Signature findFunction(@Nullable String owner, String name) {
        var at = owner == null ? null : key(owner);
        for (var steps = 0; at != null && steps < MOST_PARENTS; steps++) {
            var function = functions.get(at + "." + key(name));
            if (function != null) {
                return function;
            }
            at = parents.get(at);
        }
        return null;
    }

    boolean isA(String child, String ancestor) {
        var at = key(child);
        var wanted = key(ancestor);
        for (var steps = 0; at != null && steps < MOST_PARENTS; steps++) {
            if (at.equals(wanted)) {
                return true;
            }
            at = parents.get(at);
        }
        return false;
    }

    @Nullable
    Signature findNative(int number) {
        return natives.get(number);
    }

    private void add(ClassPackageWriter writer) throws IOException {
        for (var index = 1; index <= writer.getExportCount(); index++) {
            var row = writer.export(index);
            var kind = writer.getClassName(index);
            if ((kind.equals("Class") || kind.equals("Struct")) && row.superIndex() != 0) {
                parents.putIfAbsent(key(writer.exportName(index)), key(writer.getObjectName(row.superIndex())));
            }
        }
        for (var index = 1; index <= writer.getExportCount(); index++) {
            var outer = writer.export(index).outer();
            if (outer <= 0 || !writer.getClassName(index).endsWith("Property")) {
                continue;
            }
            var ownerKind = writer.getClassName(outer);
            if (!ownerKind.equals("Class") && !ownerKind.equals("Struct")) {
                continue;
            }
            var field = field(writer, index, 0);
            if (field != null) {
                fields.putIfAbsent(key(writer.exportName(outer)) + "." + key(writer.exportName(index)), field);
            }
        }
        for (var index = 1; index <= writer.getExportCount(); index++) {
            var outer = writer.export(index).outer();
            if (outer <= 0
                    || !writer.getClassName(index).equals("Function")
                    || !writer.getClassName(outer).equals("Class")) {
                continue;
            }
            var function = ScriptFunction.read(writer.getPayload(index), writer);
            if (function == null) {
                continue;
            }
            var owner = writer.exportName(outer);
            var signature = signature(writer, function, owner);
            functions.putIfAbsent(key(owner) + "." + key(writer.exportName(index)), signature);
            if (function.nativeIndex != 0) {
                natives.putIfAbsent(function.nativeIndex, signature);
            }
        }
    }

    private static Signature signature(ClassPackageWriter writer, ScriptFunction function, String owner)
            throws IOException {
        var parameters = new ArrayList<Field>();
        var parameterFlags = new ArrayList<Integer>();
        Field result = null;
        var at = function.children;
        for (var steps = 0; at > 0 && steps < MOST_FIELDS; steps++) {
            var property = ScriptProperty.read(writer, at);
            if (property == null) {
                break;
            }
            var field = field(writer, property, 0);
            if (property.is(ScriptProperty.CPF_RETURN_PARM)) {
                result = field == null ? UNKNOWN : field;
            } else if (property.is(ScriptProperty.CPF_PARM)) {
                parameters.add(field == null ? UNKNOWN : field);
                parameterFlags.add(property.flags());
            }
            at = property.next();
        }
        return new Signature(
                writer.getName(function.friendlyName),
                owner,
                function.flags,
                function.precedence,
                List.copyOf(parameters),
                List.copyOf(parameterFlags),
                result);
    }

    private static @Nullable Field field(ClassPackageWriter writer, int export, int depth) {
        var property = depth > 2 ? null : ScriptProperty.read(writer, export);
        return property == null ? null : field(writer, property, depth);
    }

    private static @Nullable Field field(ClassPackageWriter writer, ScriptProperty property, int depth) {
        try {
            List<String> values = null;
            String struct = null;
            Field inner = null;
            switch (property.kind()) {
                case "ByteProperty" -> values = enumValues(writer, property.first());
                case "StructProperty" -> struct = writer.getObjectName(property.first());
                case "ArrayProperty" ->
                    inner = property.first() > 0 ? field(writer, property.first(), depth + 1) : null;
                default -> {
                }
            }
            return new Field(property.kind(), values, struct, inner);
        } catch (IOException | RuntimeException otherLayout) {
            return null;
        }
    }

    private static @Nullable List<String> enumValues(ClassPackageWriter writer, int reference) throws IOException {
        if (reference <= 0 || !writer.getClassName(reference).equals("Enum")) {
            return null;
        }
        var payload = writer.getPayload(reference);
        var cursor = new Cursor(payload, 0, payload.length);
        TaggedProperties.read(cursor, writer);
        cursor.readIndex();
        cursor.readIndex();
        var count = PackageBytes.count(cursor.readIndex());
        var values = new ArrayList<String>(count);
        for (var i = 0; i < count; i++) {
            values.add(writer.getName(cursor.readIndex()));
        }
        return List.copyOf(values);
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
