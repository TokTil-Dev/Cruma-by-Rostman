package com.rost.cruma.core.unreal;

import com.rost.cruma.core.unreal.UProperty.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jetbrains.annotations.Nullable;

public final class StructLayouts {
    private static final int MOST_FIELDS = 256;
    private static final int CPF_NET = 0x20;
    private static final int CPF_NATIVE = 0x1000;
    private static final int CPF_TRANSIENT = 0x2000;

    private StructLayouts() {}

    public record Field(
            String name, Type type, int arrayDim, @Nullable String structName) {}

    public record Layout(List<Field> fields, @Nullable String parent) {}

    public static @Nullable Layout read(UArchive archive, String structName) {
        var exports = archive.getExportMap();
        for (var index = 1; index <= exports.size(); index++) {
            var export = exports.get(index - 1);
            if (isStruct(export) && export.getObjectName().equalsIgnoreCase(structName)) {
                return read(archive, index);
            }
        }
        return null;
    }

    public static @Nullable String readElementStruct(UArchive archive, String arrayName) {
        var exports = archive.getExportMap();
        String found = null;
        for (var index = 1; index <= exports.size(); index++) {
            var export = exports.get(index - 1);
            if (!"ArrayProperty".equals(export.getClassName())
                    || !export.getObjectName().equalsIgnoreCase(arrayName)) {
                continue;
            }
            var array = readField(archive, index);
            var inner = array == null || array.reference <= 0 ? null : readField(archive, array.reference);
            if (inner == null || inner.field == null || inner.field.type() != Type.Struct) {
                continue;
            }
            var struct = inner.field.structName();
            if (found != null && !found.equalsIgnoreCase(struct)) {
                return null;
            }
            found = struct;
        }
        return found;
    }

    private static @Nullable Layout read(UArchive archive, int struct) {
        var saved = archive.getOffset();
        var savedEnd = archive.getObjectEnd();
        int parent;
        int child;
        try {
            var export = archive.getExportMap().get(struct - 1);
            archive.setPosition(export.getSerialOffset());
            archive.setObjectEnd(export.getSerialOffset() + export.getSerialSize());
            archive.getPropertyExtractor().extractProperties();
            parent = archive.readIndex();
            archive.readIndex();
            archive.readIndex();
            child = archive.readIndex();
        } catch (RuntimeException otherLayout) {
            return null;
        } finally {
            archive.setObjectEnd(savedEnd);
            archive.setPosition(saved);
        }
        var fields = new ArrayList<Field>();
        var exports = archive.getExportMap().size();
        for (var steps = 0; child != 0; steps++) {
            var read = child < 0 || child > exports || steps == MOST_FIELDS ? null : readField(archive, child);
            if (read == null) {
                return null;
            }
            if (read.field != null && (read.flags & (CPF_NATIVE | CPF_TRANSIENT)) == 0) {
                fields.add(read.field);
            }
            child = read.next;
        }
        return new Layout(List.copyOf(fields), parent == 0 ? null : objectName(archive, parent));
    }

    private static @Nullable Read readField(UArchive archive, int index) {
        var export = archive.getExportMap().get(index - 1);
        var kind = export.getClassName();
        var saved = archive.getOffset();
        var savedEnd = archive.getObjectEnd();
        try {
            archive.setPosition(export.getSerialOffset());
            archive.setObjectEnd(export.getSerialOffset() + export.getSerialSize());
            archive.getPropertyExtractor().extractProperties();
            archive.readIndex();
            var next = archive.readIndex();
            if (!kind.endsWith("Property")) {
                return new Read(null, next, 0, 0);
            }
            var arrayDim = archive.readInt();
            var flags = archive.readInt();
            archive.readIndex();
            if ((flags & CPF_NET) != 0) {
                archive.readUInt16();
            }
            var type = typeOf(kind);
            if (type == null || arrayDim < 1 || arrayDim > MOST_FIELDS) {
                return null;
            }
            var reference = switch (kind) {
                case "ByteProperty", "StructProperty", "ArrayProperty", "ObjectProperty", "ClassProperty" ->
                    archive.readIndex();
                default -> 0;
            };
            var structName = type == Type.Struct ? objectName(archive, reference) : null;
            if (type == Type.Struct && structName == null) {
                return null;
            }
            return new Read(new Field(export.getObjectName(), type, arrayDim, structName), next, flags, reference);
        } catch (RuntimeException otherLayout) {
            return null;
        } finally {
            archive.setObjectEnd(savedEnd);
            archive.setPosition(saved);
        }
    }

    private static boolean isStruct(UObjectExport export) {
        var kind = export.getClassName().toLowerCase(Locale.ROOT);
        return kind.equals("struct") || kind.equals("scriptstruct");
    }

    private static @Nullable Type typeOf(String kind) {
        return switch (kind) {
            case "ByteProperty" -> Type.Byte;
            case "IntProperty" -> Type.Int;
            case "BoolProperty" -> Type.Bool;
            case "FloatProperty" -> Type.Float;
            case "ObjectProperty", "ClassProperty" -> Type.Object;
            case "NameProperty" -> Type.Name;
            case "StrProperty" -> Type.Str;
            case "StructProperty" -> Type.Struct;
            case "ArrayProperty" -> Type.Array;
            default -> null;
        };
    }

    private static @Nullable String objectName(UArchive archive, int reference) {
        if (reference > 0 && reference <= archive.getExportMap().size()) {
            return archive.getExportMap().get(reference - 1).getObjectName();
        }
        if (reference < 0 && -reference <= archive.getImportMap().size()) {
            return archive.getImportMap().get(-reference - 1).getObjectName();
        }
        return null;
    }

    private record Read(@Nullable Field field, int next, int flags, int reference) {}
}
