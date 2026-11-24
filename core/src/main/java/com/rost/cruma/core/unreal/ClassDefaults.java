package com.rost.cruma.core.unreal;

import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.Nullable;

public final class ClassDefaults {
    private static final int MOST_PARENTS = 8;
    private static final int MOST_ROWS = 4096;
    private static final int MOST_SCRIPT_BYTES = 1 << 20;
    private static final int STATE_AND_CLASS_BYTES = 16 + 2 + 4 + 4 + 16;
    private static final int BOOL = 3;
    private static final int FLOAT = 4;
    private static final int STRUCT = 10;

    private ClassDefaults() {}

    public static float findFloat(UArchive archive, String className, String name) {
        var index = EffectClasses.classIndex(archive, className);
        var saved = archive.getObjectEnd();
        try {
            for (var parents = 0; index > 0 && parents < MOST_PARENTS; parents++) {
                var value = readFloat(archive, index, name);
                if (value == null || !Float.isNaN(value)) {
                    return value == null ? Float.NaN : value;
                }
                index = parentIndex(archive, index);
            }
            return Float.NaN;
        } finally {
            archive.setObjectEnd(saved);
        }
    }

    public static List<UProperty> findAll(UArchive archive, String className) {
        var index = EffectClasses.classIndex(archive, className);
        var savedEnd = archive.getObjectEnd();
        var savedOffset = archive.getOffset();
        var chain = new ArrayList<List<UProperty>>();
        try {
            for (var parents = 0; index > 0 && parents < MOST_PARENTS; parents++) {
                var own = readAll(archive, index);
                if (own == null) {
                    break;
                }
                chain.add(own);
                index = parentIndex(archive, index);
            }
        } finally {
            archive.setObjectEnd(savedEnd);
            archive.setPosition(savedOffset);
        }
        var result = new ArrayList<UProperty>();
        for (var i = chain.size() - 1; i >= 0; i--) {
            result.addAll(chain.get(i));
        }
        return result;
    }

    private static @Nullable Float readFloat(UArchive archive, int index, String name) {
        var export = archive.getExportMap().get(index - 1);
        var end = export.getSerialOffset() + export.getSerialSize();
        archive.setObjectEnd(end);
        var start = findDefaultsStart(archive, export, end);
        return start < 0 ? null : readDefaults(archive, start, end, name);
    }

    private static @Nullable List<UProperty> readAll(UArchive archive, int index) {
        var export = archive.getExportMap().get(index - 1);
        var end = export.getSerialOffset() + export.getSerialSize();
        archive.setObjectEnd(end);
        var start = findDefaultsStart(archive, export, end);
        if (start < 0) {
            return null;
        }
        try {
            archive.setPosition(start);
            var properties = archive.getPropertyExtractor().extractProperties();
            return archive.getOffset() == end ? properties : null;
        } catch (RuntimeException otherLayout) {
            return null;
        }
    }

    private static int findDefaultsStart(UArchive archive, UObjectExport export, int end) {
        for (var cppText : new boolean[] {true, false}) {
            var code = readCode(archive, export, cppText);
            if (code == null) {
                continue;
            }
            var last = Math.min(end, code[0] + code[1]);
            for (var codeEnd = code[0]; codeEnd <= last; codeEnd++) {
                for (var hidden : new boolean[] {true, false}) {
                    var start = readAfterCode(archive, codeEnd, end, hidden);
                    if (start >= 0 && readDefaults(archive, start, end, null) != null) {
                        return start;
                    }
                }
            }
        }
        return -1;
    }

    private static int @Nullable [] readCode(UArchive archive, UObjectExport export, boolean cppText) {
        try {
            archive.setPosition(export.getSerialOffset());
            archive.readIndex();
            archive.readIndex();
            archive.readIndex();
            archive.readIndex();
            var friendly = archive.readIndex();
            if (!isName(archive, friendly) || !archive.resolveName(friendly).equalsIgnoreCase(export.getObjectName())) {
                return null;
            }
            if (cppText) {
                archive.readIndex();
            }
            archive.readInt();
            archive.readInt();
            var scriptSize = archive.readInt();
            return scriptSize < 0 || scriptSize > MOST_SCRIPT_BYTES
                    ? null
                    : new int[] {archive.getOffset(), scriptSize};
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    private static int readAfterCode(UArchive archive, int at, int end, boolean hidden) {
        try {
            archive.setPosition(at);
            archive.skipBytes(STATE_AND_CLASS_BYTES);
            var dependencies = count(archive);
            for (var row = 0; row < dependencies; row++) {
                var dependency = archive.readIndex();
                var deep = archive.readInt();
                archive.readInt();
                if (dependency == 0 || !isClass(archive, dependency) || deep != 0 && deep != 1) {
                    return -1;
                }
            }
            if (!readNames(archive)) {
                return -1;
            }
            var within = archive.readIndex();
            if (within != 0 && !isClass(archive, within) || !isName(archive, archive.readIndex())) {
                return -1;
            }
            if (hidden && !readNames(archive)) {
                return -1;
            }
            return archive.getOffset() <= end ? archive.getOffset() : -1;
        } catch (RuntimeException unreadable) {
            return -1;
        }
    }

    private static @Nullable Float readDefaults(UArchive archive, int start, int end, @Nullable String name) {
        try {
            archive.setPosition(start);
            return readTags(archive, end, name);
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    private static @Nullable Float readTags(UArchive archive, int end, @Nullable String name) {
        var value = Float.NaN;
        while (archive.getOffset() < end) {
            var tag = archive.readIndex();
            if (!isName(archive, tag)) {
                return null;
            }
            var tagName = archive.resolveName(tag);
            if (tagName.equalsIgnoreCase(UArchive.NONE_NAME)) {
                return archive.getOffset() == end ? value : null;
            }
            var info = archive.readUInt8();
            var type = info & 0x0f;
            if (type == 0 || type == STRUCT && !isName(archive, archive.readIndex())) {
                return null;
            }
            var size = switch ((info >> 4) & 7) {
                case 0 -> 1;
                case 1 -> 2;
                case 2 -> 4;
                case 3 -> 12;
                case 4 -> 16;
                case 5 -> archive.readUInt8();
                case 6 -> archive.readUInt16();
                default -> archive.readInt();
            };
            var arrayIndex = 0;
            if (type == BOOL) {
                size = 0;
            } else if ((info & 0x80) != 0) {
                arrayIndex = archive.readArrayIndex();
            }
            if (size < 0 || archive.getOffset() + size > end) {
                return null;
            }
            if (type == FLOAT && size == 4 && arrayIndex == 0 && tagName.equalsIgnoreCase(name)) {
                value = archive.readFloat();
            } else {
                archive.skipBytes(size);
            }
        }
        return null;
    }

    private static boolean readNames(UArchive archive) {
        var names = count(archive);
        for (var row = 0; row < names; row++) {
            if (!isName(archive, archive.readIndex())) {
                return false;
            }
        }
        return true;
    }

    private static int count(UArchive archive) {
        var count = archive.readIndex();
        if (count < 0 || count > MOST_ROWS) {
            throw new IllegalStateException("Implausible class table size " + count);
        }
        return count;
    }

    private static boolean isName(UArchive archive, int index) {
        return index >= 0 && index < archive.getHeader().getNameCount();
    }

    private static boolean isClass(UArchive archive, int reference) {
        if (reference > 0) {
            return reference <= archive.getExportMap().size()
                    && UArchive.NONE_NAME.equals(
                            archive.getExportMap().get(reference - 1).getClassName());
        }
        return -reference <= archive.getImportMap().size()
                && "Class"
                        .equalsIgnoreCase(
                                archive.getImportMap().get(-reference - 1).getClassName());
    }

    private static int parentIndex(UArchive archive, int index) {
        try {
            archive.setPosition(archive.getExportMap().get(index - 1).getSerialOffset());
            var parent = archive.readIndex();
            return parent > 0 && isClass(archive, parent) ? parent : 0;
        } catch (RuntimeException unreadable) {
            return 0;
        }
    }
}
