package com.rost.cruma.core.editing;

import static com.rost.cruma.core.editing.PackageBytes.littleEndian;
import static com.rost.cruma.core.editing.PackageBytes.writeIndex;

import com.rost.cruma.core.editing.PackageBytes.Cursor;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.function.ToIntFunction;
import org.jetbrains.annotations.Nullable;

final class PointRegionTag {
    private PointRegionTag() {}

    static int @Nullable [] read(PackageBytes source, int start, int end) throws IOException {
        var cursor = new Cursor(source.body, start, end);
        var values = new int[4];
        var expected = List.of("Zone", "iLeaf", "ZoneNumber");
        for (var i = 0; i < expected.size(); i++) {
            if (!source.getName(cursor.readIndex()).equals(expected.get(i))) {
                return null;
            }
            var info = cursor.readU8();
            var size = switch (info >> 4 & 7) {
                case 0 -> 1;
                case 1 -> 2;
                case 2 -> 4;
                case 5 -> cursor.readU8();
                default -> -1;
            };
            var type = info & 15;
            var known = switch (i) {
                case 0 -> type == 5 && size > 0 && size <= 5;
                case 1 -> type == 2 && size == 4;
                default -> type == 1 && size == 1;
            };
            if (!known || (info & 128) != 0) {
                return null;
            }
            if (i == 0) {
                values[0] = cursor.position;
                values[1] = cursor.position + size;
                if (new Cursor(source.body, values[0], values[1]).readIndex() == 0) {
                    return null;
                }
            } else {
                values[i + 1] = cursor.position;
            }
            cursor.skip(size);
        }
        return source.getName(cursor.readIndex()).equalsIgnoreCase("None") && cursor.position == end ? values : null;
    }

    static LightRegion getRegion(PackageBytes source, int[] values) throws IOException {
        var zone = new Cursor(source.body, values[0], values[1]).readIndex();
        return new LightRegion(zone, littleEndian(source.body).getInt(values[2]), source.body[values[3]] & 255);
    }

    static int getZoneSize(LightRegion value) {
        var out = new ByteArrayOutputStream();
        writeIndex(out, value.zone());
        return out.size();
    }

    static byte[] write(
            PackageBytes source,
            int start,
            int info,
            int sizeStart,
            int end,
            int[] values,
            LightRegion saved,
            LightRegion value,
            ToIntFunction<String> intern) {
        if (value.zone() == saved.zone()) {
            var result = Arrays.copyOfRange(source.body, start, end);
            littleEndian(result).putInt(values[2] - start, value.leaf());
            result[values[3] - start] = (byte) value.zoneNumber();
            return result;
        }
        var struct = new ByteArrayOutputStream();
        var zone = new ByteArrayOutputStream();
        writeIndex(zone, value.zone());
        writeIndex(struct, intern.applyAsInt("Zone"));
        writeHeader(struct, 5, zone.size());
        struct.writeBytes(zone.toByteArray());
        writeIndex(struct, intern.applyAsInt("iLeaf"));
        writeHeader(struct, 2, 4);
        struct.writeBytes(littleEndian(new byte[4]).putInt(value.leaf()).array());
        writeIndex(struct, intern.applyAsInt("ZoneNumber"));
        writeHeader(struct, 1, 1);
        struct.write(value.zoneNumber());
        writeIndex(struct, intern.applyAsInt("None"));
        var out = new ByteArrayOutputStream();
        out.write(source.body, start, info - start);
        var kind = sizeKind(struct.size());
        out.write(source.body[info] & ~0x70 | kind << 4);
        out.write(source.body, info + 1, sizeStart - info - 1);
        writeSize(out, kind, struct.size());
        out.writeBytes(struct.toByteArray());
        return out.toByteArray();
    }

    private static void writeHeader(ByteArrayOutputStream out, int type, int size) {
        var kind = sizeKind(size);
        out.write(type | kind << 4);
        writeSize(out, kind, size);
    }

    private static int sizeKind(int size) {
        return switch (size) {
            case 1 -> 0;
            case 2 -> 1;
            case 4 -> 2;
            case 12 -> 3;
            case 16 -> 4;
            default -> size <= 255 ? 5 : size <= 65_535 ? 6 : 7;
        };
    }

    private static void writeSize(ByteArrayOutputStream out, int kind, int size) {
        switch (kind) {
            case 5 -> out.write(size);
            case 6 -> {
                out.write(size & 255);
                out.write(size >>> 8);
            }
            case 7 -> PackageBytes.writeInt(out, size);
            default -> {}
        }
    }
}
