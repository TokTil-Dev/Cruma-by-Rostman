package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.PackageBytes.Cursor;
import com.rost.cruma.core.editing.PackageBytes.ExportEntry;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class NativeLevelEditor {
    private final PackageBytes source;
    final ExportEntry entry;
    final List<Integer> auxiliary;
    final List<Integer> actors;
    private final int nativeStart;
    private final int arraysEnd;

    private NativeLevelEditor(
            PackageBytes source,
            ExportEntry entry,
            List<Integer> auxiliary,
            List<Integer> actors,
            int nativeStart,
            int arraysEnd) {
        this.source = source;
        this.entry = entry;
        this.auxiliary = List.copyOf(auxiliary);
        this.actors = List.copyOf(actors);
        this.nativeStart = nativeStart;
        this.arraysEnd = arraysEnd;
    }

    static NativeLevelEditor read(PackageBytes source) throws IOException {
        ExportEntry level = null;
        for (var entry : source.exports) {
            if (source.getClassName(entry).equals("Level")) {
                if (level != null) {
                    throw PackageBytes.malformed("Structural edits require one native Level export");
                }
                level = entry;
            }
        }
        if (level == null) {
            throw PackageBytes.malformed("This map has no Level actor registry");
        }
        if (!source.getObjectPath(level.classIndex()).equalsIgnoreCase("Engine.Level")) {
            throw PackageBytes.malformed("Structural editing requires the native Engine.Level class");
        }
        var cursor = new Cursor(source.body, level.serialOffset(), level.serialOffset() + level.serialSize());
        skipProperties(source, cursor, level.flags());
        var start = cursor.position;
        var auxiliary = source.licenseVersion >= 23 ? readArray(source, cursor) : List.<Integer>of();
        var actors = readArray(source, cursor);
        var arraysEnd = cursor.position;
        for (var i = 0; i < 4; i++) {
            cursor.readString();
        }
        var options = PackageBytes.count(cursor.readIndex());
        for (var i = 0; i < options; i++) {
            cursor.readString();
        }
        cursor.readI32();
        cursor.skip(3);
        if (cursor.readIndex() != 0) {
            throw PackageBytes.malformed("Unsupported Level URL flag");
        }
        source.checkReference(cursor.readIndex());
        if (cursor.position != cursor.end) {
            if (!Float.isFinite(Float.intBitsToFloat(cursor.readI32()))) {
                throw PackageBytes.malformed("Invalid Level time");
            }
            for (var i = 0; i < 17; i++) {
                source.checkReference(cursor.readIndex());
            }
            var travel = PackageBytes.count(cursor.readIndex());
            for (var i = 0; i < travel * 2; i++) {
                cursor.readString();
            }
            readLicenseeTail(source, cursor);
        }
        if (cursor.position != cursor.end) {
            throw PackageBytes.malformed("Level has an unaudited native tail");
        }
        return new NativeLevelEditor(source, level, auxiliary, actors, start, arraysEnd);
    }

    static void skipProperties(PackageBytes source, Cursor cursor, int flags) throws IOException {
        if ((flags & 0x02000000) != 0) {
            var node = cursor.readIndex();
            cursor.readIndex();
            cursor.skip(12);
            if (node != 0) {
                cursor.readIndex();
            }
        }
        while (true) {
            var name = source.getName(cursor.readIndex());
            if (name.equalsIgnoreCase("None")) {
                return;
            }
            var info = cursor.readU8();
            var type = info & 15;
            if (type == 0) {
                throw PackageBytes.malformed("Invalid property tag");
            }
            if (type == 10) {
                source.getName(cursor.readIndex());
            }
            var size = switch (info >> 4 & 7) {
                case 0 -> 1;
                case 1 -> 2;
                case 2 -> 4;
                case 3 -> 12;
                case 4 -> 16;
                case 5 -> cursor.readU8();
                case 6 -> cursor.readU16();
                default -> cursor.readI32();
            };
            if (type != 3 && (info & 128) != 0) {
                cursor.readArrayIndex();
            }
            source.skipValue(cursor, name, type, size);
        }
    }

    int getLevelInfo() throws IOException {
        var first = actors.isEmpty() ? 0 : actors.getFirst();
        if (first < 0
                || first > 0
                        && !source.getClassName(source.exports.get(first - 1)).equals("LevelInfo")) {
            throw PackageBytes.malformed("The first actor of the Level is not its LevelInfo");
        }
        return first;
    }

    byte[] rewrite(Set<Integer> removed, List<Integer> added) {
        var out = new ByteArrayOutputStream();
        out.write(source.body, entry.serialOffset(), nativeStart - entry.serialOffset());
        if (source.licenseVersion >= 23) {
            writeArray(out, auxiliary, Set.of(), List.of());
        }
        writeArray(out, actors, removed, added);
        out.write(source.body, arraysEnd, entry.serialOffset() + entry.serialSize() - arraysEnd);
        return out.toByteArray();
    }

    private static void readLicenseeTail(PackageBytes source, Cursor cursor) throws IOException {
        if (source.licenseVersion == 32 || source.licenseVersion == 33) {
            cursor.readI32();
            var shaders = PackageBytes.count(cursor.readIndex());
            for (var i = 0; i < shaders; i++) {
                cursor.readString();
                skipWords(cursor);
                skipWords(cursor);
            }
        }
        if (source.licenseVersion > 38 && source.fileVersion < 127) {
            cursor.readI32();
        }
    }

    private static void skipWords(Cursor cursor) throws IOException {
        var count = PackageBytes.count(cursor.readIndex());
        cursor.skip(Math.multiplyExact(count, 4));
    }

    private static void writeArray(
            ByteArrayOutputStream out, List<Integer> original, Set<Integer> removed, List<Integer> added) {
        PackageBytes.writeInt(out, original.size() + added.size());
        PackageBytes.writeInt(out, original.size() + added.size());
        for (var reference : original) {
            PackageBytes.writeIndex(out, removed.contains(reference) ? 0 : reference);
        }
        for (var reference : added) {
            PackageBytes.writeIndex(out, reference);
        }
    }

    private static List<Integer> readArray(PackageBytes source, Cursor cursor) throws IOException {
        var count = PackageBytes.count(cursor.readI32());
        cursor.readI32();
        var result = new ArrayList<Integer>(count);
        for (var i = 0; i < count; i++) {
            var reference = cursor.readIndex();
            source.checkReference(reference);
            result.add(reference);
        }
        return result;
    }
}
