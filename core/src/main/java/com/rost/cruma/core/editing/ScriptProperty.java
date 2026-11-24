package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.PackageBytes.Cursor;
import java.io.IOException;
import org.jetbrains.annotations.Nullable;

record ScriptProperty(
        String name, String kind, int next, int arrayDim, int flags, int category, int first, int second) {
    static final int CPF_OPTIONAL_PARM = 0x10;
    static final int CPF_NET = 0x20;
    static final int CPF_PARM = 0x80;
    static final int CPF_OUT_PARM = 0x100;
    static final int CPF_RETURN_PARM = 0x400;
    static final int CPF_COERCE_PARM = 0x800;

    static @Nullable ScriptProperty read(ClassPackageWriter writer, int export) {
        try {
            var kind = writer.getClassName(export);
            var payload = writer.getPayload(export);
            var cursor = new Cursor(payload, 0, payload.length);
            TaggedProperties.read(cursor, writer);
            cursor.readIndex();
            var next = cursor.readIndex();
            var arrayDim = cursor.readI32();
            var flags = cursor.readI32();
            var category = cursor.readIndex();
            if ((flags & CPF_NET) != 0) {
                cursor.readU16();
            }
            var first = 0;
            var second = 0;
            switch (kind) {
                case "ByteProperty", "StructProperty", "ArrayProperty", "ObjectProperty" -> first = cursor.readIndex();
                case "ClassProperty" -> {
                    first = cursor.readIndex();
                    second = cursor.readIndex();
                }
                default -> {
                }
            }
            var name = writer.exportName(export);
            return cursor.position == cursor.end
                    ? new ScriptProperty(name, kind, next, arrayDim, flags, category, first, second)
                    : null;
        } catch (IOException | RuntimeException otherLayout) {
            return null;
        }
    }

    boolean is(int flag) {
        return (flags & flag) != 0;
    }

    String type(ClassPackageWriter writer) throws IOException {
        return switch (kind) {
            case "ByteProperty" -> first == 0 ? "byte" : writer.getObjectName(first);
            case "IntProperty" -> "int";
            case "BoolProperty" -> "bool";
            case "FloatProperty" -> "float";
            case "NameProperty" -> "name";
            case "StrProperty" -> "string";
            case "ObjectProperty", "StructProperty" -> writer.getObjectName(first);
            case "ClassProperty" ->
                second == 0 || writer.getObjectName(second).equalsIgnoreCase("Object")
                        ? "class"
                        : "class<" + writer.getObjectName(second) + ">";
            case "ArrayProperty" -> {
                var inner = first > 0 ? read(writer, first) : null;
                if (inner == null) {
                    throw PackageBytes.malformed("Unreadable array element of " + name);
                }
                var type = inner.type(writer);
                yield "array<" + type + (type.endsWith(">") ? " >" : ">");
            }
            default -> throw PackageBytes.malformed("Unknown property kind " + kind);
        };
    }
}
