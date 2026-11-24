package com.rost.cruma.core.editing;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

final class PackageBytes {
    static final int MAGIC = 0x9e2a83c1;
    static final int MAX_ENTRIES = 1_000_000;
    final byte[] body;
    final int container;
    final String packageName;
    final int fileVersion;
    final int licenseVersion;
    final List<String> names = new ArrayList<>();
    final List<ImportEntry> imports = new ArrayList<>();
    final List<ExportEntry> exports = new ArrayList<>();
    final int nameOffset;
    final int nameEnd;
    final int importOffset;
    final int importEnd;
    final int exportOffset;
    final int exportEnd;
    final int lastGenerationNameOffset;

    PackageBytes(Path path, byte[] file) throws IOException {
        var filename = path.getFileName().toString();
        var dot = filename.lastIndexOf('.');
        packageName = dot < 0 ? filename : filename.substring(0, dot);
        if (file.length >= 4 && littleEndian(file).getInt(0) == MAGIC) {
            container = 0;
            body = file.clone();
        } else {
            if (file.length < 28) {
                throw malformed("Truncated Lineage container");
            }
            var header = new String(file, 0, 28, StandardCharsets.UTF_16LE);
            container = switch (header) {
                case "Lineage2Ver111" -> 111;
                case "Lineage2Ver121" -> 121;
                default -> throw malformed("Unsupported Lineage container: " + header);
            };
            body = Arrays.copyOfRange(file, 28, file.length);
            xorBytes(body, container == 111 ? 0xac : filenameKey(filename));
        }
        var cursor = new Cursor(body, 0, body.length);
        if (cursor.readI32() != MAGIC) {
            throw malformed("Invalid Unreal package signature");
        }
        fileVersion = cursor.readU16();
        licenseVersion = cursor.readU16();
        if (fileVersion < 100 || fileVersion > 133 || licenseVersion > 40) {
            throw malformed("Editing is not supported for package version " + fileVersion + "/" + licenseVersion);
        }
        cursor.readI32();
        var nameCount = count(cursor.readI32());
        nameOffset = cursor.readI32();
        var exportCount = count(cursor.readI32());
        exportOffset = cursor.readI32();
        var importCount = count(cursor.readI32());
        importOffset = cursor.readI32();
        cursor.skip(16);
        var generations = count(cursor.readI32());
        cursor.skip(Math.multiplyExact(generations, 8));
        lastGenerationNameOffset = generations == 0 ? -1 : cursor.position - 4;
        var headerEnd = cursor.position;

        cursor = openTable(nameOffset, nameCount, headerEnd);
        for (var i = 0; i < nameCount; i++) {
            names.add(cursor.readString());
            cursor.readI32();
        }
        nameEnd = cursor.position;
        cursor = openTable(importOffset, importCount, headerEnd);
        for (var i = 0; i < importCount; i++) {
            imports.add(new ImportEntry(cursor.readIndex(), cursor.readIndex(), cursor.readI32(), cursor.readIndex()));
        }
        importEnd = cursor.position;
        cursor = openTable(exportOffset, exportCount, headerEnd);
        for (var i = 0; i < exportCount; i++) {
            var start = cursor.position;
            var classIndex = cursor.readIndex();
            var superIndex = cursor.readIndex();
            var outer = cursor.readI32();
            var objectName = cursor.readIndex();
            var flags = cursor.readI32();
            var sizeOffset = cursor.position;
            var serialSize = cursor.readIndex();
            var serialOffset = serialSize > 0 ? cursor.readIndex() : 0;
            if (serialSize < 0
                    || serialOffset < 0
                    || (long) serialOffset + serialSize > body.length
                    || serialSize > 0 && serialOffset < headerEnd) {
                throw malformed("Invalid export payload bounds");
            }
            exports.add(new ExportEntry(
                    i + 1,
                    classIndex,
                    superIndex,
                    outer,
                    objectName,
                    flags,
                    serialSize,
                    serialOffset,
                    start,
                    sizeOffset,
                    cursor.position));
        }
        exportEnd = cursor.position;
        var ranges = new ArrayList<int[]>();
        ranges.add(new int[] {0, headerEnd});
        if (nameEnd > nameOffset) {
            ranges.add(new int[] {nameOffset, nameEnd});
        }
        if (importEnd > importOffset) {
            ranges.add(new int[] {importOffset, importEnd});
        }
        if (exportEnd > exportOffset) {
            ranges.add(new int[] {exportOffset, exportEnd});
        }
        for (var entry : exports) {
            if (entry.serialSize() > 0) {
                ranges.add(new int[] {entry.serialOffset(), entry.serialOffset() + entry.serialSize()});
            }
            getName(entry.objectName());
            checkReference(entry.classIndex());
            checkReference(entry.superIndex());
            checkReference(entry.outer());
        }
        ranges.sort((left, right) -> Integer.compare(left[0], right[0]));
        for (var i = 1; i < ranges.size(); i++) {
            if (ranges.get(i)[0] < ranges.get(i - 1)[1]) {
                throw malformed("Overlapping package payloads or tables are not editable");
            }
        }
        for (var entry : imports) {
            getName(entry.classPackage());
            getName(entry.className());
            getName(entry.objectName());
            checkReference(entry.outer());
        }
    }

    static byte[] encodeNew(byte[] body, String filename) {
        var texture = isTexturePackage(filename);
        var header = (texture ? "Lineage2Ver121" : "Lineage2Ver111").getBytes(StandardCharsets.UTF_16LE);
        var key = texture ? filenameKey(filename) : 0xac;
        var result = Arrays.copyOf(header, header.length + body.length);
        for (var i = 0; i < body.length; i++) {
            result[header.length + i] = (byte) (body[i] ^ key);
        }
        return result;
    }

    static boolean isTexturePackage(String filename) {
        return filename.toLowerCase(Locale.ROOT).endsWith(".utx");
    }

    static ByteBuffer littleEndian(byte[] bytes) {
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    static void writeInt(ByteArrayOutputStream out, int value) {
        for (var shift = 0; shift < 32; shift += 8) {
            out.write(value >>> shift & 255);
        }
    }

    static void writeIndex(ByteArrayOutputStream out, int value) {
        var negative = value < 0;
        long remaining = Math.abs((long) value);
        var first = (int) (remaining & 63);
        remaining >>>= 6;
        out.write(first | (negative ? 128 : 0) | (remaining != 0 ? 64 : 0));
        while (remaining != 0) {
            var next = (int) (remaining & 127);
            remaining >>>= 7;
            out.write(next | (remaining != 0 ? 128 : 0));
        }
    }

    static void writeName(ByteArrayOutputStream out, String value) {
        if (value.chars().allMatch(c -> c > 0 && c < 128)) {
            var bytes = value.getBytes(StandardCharsets.US_ASCII);
            writeIndex(out, bytes.length + 1);
            out.writeBytes(bytes);
            out.write(0);
        } else {
            writeIndex(out, -(value.length() + 1));
            out.writeBytes(value.getBytes(StandardCharsets.UTF_16LE));
            out.write(0);
            out.write(0);
        }
        writeInt(out, 0x00070010);
    }

    static int filenameKey(String filename) {
        var key = 0;
        for (var c : filename.toCharArray()) {
            key += Character.toLowerCase(c);
        }
        return key & 255;
    }

    static int count(int value) throws IOException {
        if (value < 0 || value > MAX_ENTRIES) {
            throw malformed("Invalid package array count " + value);
        }
        return value;
    }

    static IOException malformed(String message) {
        return new IOException(message);
    }

    record ImportEntry(int classPackage, int className, int outer, int objectName) {}

    record ExportEntry(
            int index,
            int classIndex,
            int superIndex,
            int outer,
            int objectName,
            int flags,
            int serialSize,
            int serialOffset,
            int rowStart,
            int sizeOffset,
            int rowEnd) {}

    static final class Cursor {
        final byte[] bytes;
        final int end;
        int position;

        Cursor(byte[] bytes, int start, int end) throws IOException {
            if (start < 0 || end < start || end > bytes.length) {
                throw malformed("Invalid byte span");
            }
            this.bytes = bytes;
            this.position = start;
            this.end = end;
        }

        int readU8() throws IOException {
            if (position >= end) {
                throw malformed("Truncated package data");
            }
            return bytes[position++] & 255;
        }

        int readU16() throws IOException {
            return readU8() | readU8() << 8;
        }

        int readI32() throws IOException {
            return readU8() | readU8() << 8 | readU8() << 16 | readU8() << 24;
        }

        void skip(int count) throws IOException {
            if (count < 0 || (long) position + count > end) {
                throw malformed("Truncated package data");
            }
            position += count;
        }

        int readIndex() throws IOException {
            var first = readU8();
            long value = first & 63;
            var next = first;
            var shift = 6;
            if ((first & 64) != 0) {
                do {
                    next = readU8();
                    value |= (long) (next & 127) << shift;
                    shift += 7;
                    if (shift >= 34 && (next & 128) != 0) {
                        throw malformed("Invalid compact index");
                    }
                } while ((next & 128) != 0);
            }
            var signed = (first & 128) != 0 ? -value : value;
            if (signed < Integer.MIN_VALUE || signed > Integer.MAX_VALUE) {
                throw malformed("Compact index overflow");
            }
            return (int) signed;
        }

        int readArrayIndex() throws IOException {
            var first = readU8();
            if ((first & 128) == 0) {
                return first;
            }
            if ((first & 64) == 0) {
                return (first & 63) << 8 | readU8();
            }
            return (first & 63) << 24 | readU8() << 16 | readU8() << 8 | readU8();
        }

        String readString() throws IOException {
            var length = readIndex();
            if (length == 0) {
                return "";
            }
            var byteLength = Math.abs((long) length) * (length < 0 ? 2 : 1);
            if (byteLength > Integer.MAX_VALUE) {
                throw malformed("Invalid name length");
            }
            var start = position;
            skip((int) byteLength);
            if (bytes[position - 1] != 0 || length < 0 && bytes[position - 2] != 0) {
                throw malformed("Unterminated package name");
            }
            return new String(
                    bytes,
                    start,
                    (int) byteLength - (length < 0 ? 2 : 1),
                    length < 0 ? StandardCharsets.UTF_16LE : StandardCharsets.ISO_8859_1);
        }
    }

    String getName(int index) throws IOException {
        if (index < 0 || index >= names.size()) {
            throw malformed("Invalid name index " + index);
        }
        return names.get(index);
    }

    void checkReference(int index) throws IOException {
        if (index > exports.size() || index < -(long) imports.size()) {
            throw malformed("Invalid object reference " + index);
        }
    }

    void skipValue(Cursor cursor, String name, int type, int size) throws IOException {
        if (type == 9 && name.equalsIgnoreCase("ZoneRenderState")) {
            var elements = new Cursor(body, cursor.position, cursor.end);
            if (skipZoneStates(elements)) {
                cursor.position = elements.position;
                return;
            }
        }
        cursor.skip(type == 3 ? 0 : size);
    }

    String getClassName(ExportEntry entry) throws IOException {
        if (entry.classIndex() == 0) {
            return "Class";
        }
        return getName(
                entry.classIndex() < 0
                        ? imports.get(-entry.classIndex() - 1).objectName()
                        : exports.get(entry.classIndex() - 1).objectName());
    }

    String getObjectPath(int index) throws IOException {
        checkReference(index);
        if (index == 0) {
            return "None";
        }
        var parts = new ArrayList<String>();
        var seen = new HashSet<Integer>();
        var rootIsExport = index > 0;
        while (index != 0) {
            if (!seen.add(index)) {
                throw malformed("Cyclic package outer chain");
            }
            checkReference(index);
            if (index < 0) {
                var entry = imports.get(-index - 1);
                parts.add(getName(entry.objectName()));
                index = entry.outer();
                rootIsExport = false;
            } else {
                var entry = exports.get(index - 1);
                parts.add(getName(entry.objectName()));
                index = entry.outer();
            }
        }
        if (rootIsExport) {
            parts.add(packageName);
        }
        return String.join(".", parts.reversed());
    }

    byte[] encode(byte[] editedBody, Path target) {
        if (container == 0) {
            return editedBody;
        }
        var header = ("Lineage2Ver" + container).getBytes(StandardCharsets.UTF_16LE);
        var result = new byte[header.length + editedBody.length];
        System.arraycopy(header, 0, result, 0, header.length);
        var key = container == 111 ? 0xac : filenameKey(target.getFileName().toString());
        for (var i = 0; i < editedBody.length; i++) {
            result[header.length + i] = (byte) (editedBody[i] ^ key);
        }
        return result;
    }

    private Cursor openTable(int offset, int count, int headerEnd) throws IOException {
        if (offset < 0 || offset > body.length || count > 0 && offset < headerEnd) {
            throw malformed("Invalid package table offset");
        }
        return new Cursor(body, offset, body.length);
    }

    private boolean skipZoneStates(Cursor cursor) {
        try {
            var count = count(cursor.readIndex());
            for (var i = 0; i < count; i++) {
                var zoneState = false;
                while (true) {
                    var name = getName(cursor.readIndex());
                    if (name.equalsIgnoreCase("None")) {
                        break;
                    }
                    var info = cursor.readU8();
                    var type = info & 15;
                    if (type == 0) {
                        return false;
                    }
                    if (type == 10) {
                        getName(cursor.readIndex());
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
                    cursor.skip(type == 3 ? 0 : size);
                    zoneState |= name.equalsIgnoreCase("ZoneState");
                }
                if (!zoneState) {
                    return false;
                }
            }
            return true;
        } catch (IOException otherLayout) {
            return false;
        }
    }

    private static void xorBytes(byte[] bytes, int key) {
        for (var i = 0; i < bytes.length; i++) {
            bytes[i] ^= (byte) key;
        }
    }
}
