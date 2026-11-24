package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.PackageBytes.Cursor;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class BspLightmapTail {
    final int fileVersion;
    final int licenseVersion;
    final int serialOffset;
    private final byte[] payload;
    private final int leavesStart;
    private final int lightsEnd;
    private final int[] leafZones;
    private final long[] visibleZones;
    private final LeafLights leafLights;
    private final int lightmapsStart;
    private final int[] lightmapStarts;
    private final int[] lightmapEnds;
    private final int texturesStart;
    private final int texturesEnd;
    final List<Lightmap> lightmaps;
    final List<Texture> textures;

    private BspLightmapTail(
            int fileVersion,
            int licenseVersion,
            int serialOffset,
            byte[] payload,
            int leavesStart,
            int lightsEnd,
            int[] leafZones,
            long[] visibleZones,
            LeafLights leafLights,
            int lightmapsStart,
            int[] lightmapStarts,
            int[] lightmapEnds,
            int texturesStart,
            int texturesEnd,
            List<Lightmap> lightmaps,
            List<Texture> textures) {
        this.fileVersion = fileVersion;
        this.licenseVersion = licenseVersion;
        this.serialOffset = serialOffset;
        this.payload = payload;
        this.leavesStart = leavesStart;
        this.lightsEnd = lightsEnd;
        this.leafZones = leafZones;
        this.visibleZones = visibleZones;
        this.leafLights = leafLights;
        this.lightmapsStart = lightmapsStart;
        this.lightmapStarts = lightmapStarts;
        this.lightmapEnds = lightmapEnds;
        this.texturesStart = texturesStart;
        this.texturesEnd = texturesEnd;
        this.lightmaps = List.copyOf(lightmaps);
        this.textures = List.copyOf(textures);
    }

    record LeafLights(int[] permeating, int[] volumetric, int[] slots) {
        LeafLights {
            if (permeating.length != volumetric.length) {
                throw new IllegalArgumentException("Every leaf has a permeating and a volumetric run");
            }
            permeating = permeating.clone();
            volumetric = volumetric.clone();
            slots = slots.clone();
        }

        @Override
        public int[] permeating() {
            return permeating.clone();
        }

        @Override
        public int[] volumetric() {
            return volumetric.clone();
        }

        @Override
        public int[] slots() {
            return slots.clone();
        }

        int[] run(int start) {
            if (start < 0) {
                return new int[0];
            }
            var end = start;
            while (end < slots.length && slots[end] != 0) {
                end++;
            }
            return Arrays.copyOfRange(slots, start, end);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof LeafLights lists
                    && Arrays.equals(permeating, lists.permeating)
                    && Arrays.equals(volumetric, lists.volumetric)
                    && Arrays.equals(slots, lists.slots);
        }

        @Override
        public int hashCode() {
            return Objects.hash(Arrays.hashCode(permeating), Arrays.hashCode(volumetric), Arrays.hashCode(slots));
        }
    }

    record Entry(int light, byte[] bitmap, int sizeX, int sizeY, int stride, int minX, int minY, int maxX, int maxY) {
        Entry {
            Objects.requireNonNull(bitmap);
        }

        boolean bit(int x, int y) {
            var index = y * stride + (x >> 3);
            return index >= 0 && index < bitmap.length && (bitmap[index] & 1 << (x & 7)) != 0;
        }

        boolean empty() {
            for (var value : bitmap) {
                if (value != 0) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Entry e
                    && light == e.light
                    && sizeX == e.sizeX
                    && sizeY == e.sizeY
                    && stride == e.stride
                    && minX == e.minX
                    && minY == e.minY
                    && maxX == e.maxX
                    && maxY == e.maxY
                    && Arrays.equals(bitmap, e.bitmap);
        }

        @Override
        public int hashCode() {
            return Objects.hash(light, Arrays.hashCode(bitmap), minX, minY, maxX, maxY);
        }
    }

    record Lightmap(
            int texture,
            int surface,
            int zone,
            int offsetX,
            int offsetY,
            int sizeX,
            int sizeY,
            float[] matrix,
            float[] base,
            float[] axisX,
            float[] axisY,
            List<Entry> entries,
            int level,
            int trailer) {
        Lightmap {
            if (matrix.length != 16 || base.length != 3 || axisX.length != 3 || axisY.length != 3) {
                throw new IllegalArgumentException("Invalid lightmap vectors");
            }
            entries = List.copyOf(entries);
        }

        Lightmap withEntries(List<Entry> replacement) {
            return new Lightmap(
                    texture,
                    surface,
                    zone,
                    offsetX,
                    offsetY,
                    sizeX,
                    sizeY,
                    matrix,
                    base,
                    axisX,
                    axisY,
                    replacement,
                    level,
                    trailer);
        }
    }

    record Variant(
            int level,
            int[] lightmaps,
            long time,
            int revision,
            byte[] full,
            byte[] half,
            int format,
            int width,
            int height,
            int staticRevision,
            int fullEndPosition,
            int halfEndPosition,
            int fullLazyAt,
            int halfLazyAt) {
        boolean isEmpty() {
            return width == 0 && height == 0 && full.length == 0;
        }
    }

    record Texture(List<Variant> variants, int[] lightmaps, int owner) {
        Texture {
            variants = List.copyOf(variants);
        }
    }

    record VariantKey(int texture, int variant) {}

    record Texels(byte[] full, byte[] half) {}

    static BspLightmapTail read(PackageBytes source, int modelExport) throws IOException {
        if (modelExport <= 0 || modelExport > source.exports.size()) {
            throw new IOException("The level model is not an export of this map");
        }
        var entry = source.exports.get(modelExport - 1);
        if (entry.serialSize() <= 0) {
            throw new IOException("The level model has no saved data");
        }
        var payload = Arrays.copyOfRange(source.body, entry.serialOffset(), entry.serialOffset() + entry.serialSize());
        return read(source, payload, entry.serialOffset(), entry.flags());
    }

    static BspLightmapTail read(PackageBytes source, byte[] payload, int serialOffset, int flags) throws IOException {
        var fileVersion = source.fileVersion;
        var licenseVersion = source.licenseVersion;
        if (fileVersion < 123 || fileVersion > 133) {
            throw new IOException("BSP lightmaps of package version " + fileVersion + " are not supported");
        }
        var c = new Cursor(payload, 0, payload.length);
        NativeLevelEditor.skipProperties(source, c, flags);
        c.skip(25 + 16);
        c.skip(count(c, 12) * 12);
        c.skip(count(c, 12) * 12);
        for (var n = count(c, 60); n > 0; n--) {
            c.skip(16 + 8 + 1);
            for (var i = 0; i < 7; i++) {
                c.readIndex();
            }
            c.skip(32);
            c.readIndex();
            c.readIndex();
            c.skip(1 + 8 + 12);
        }
        for (var n = count(c, 20); n > 0; n--) {
            c.readIndex();
            c.skip(4);
            for (var i = 0; i < 4; i++) {
                c.readIndex();
            }
            c.readIndex();
            c.readIndex();
            c.skip(16 + 4);
            if (licenseVersion > 20) {
                c.skip(4);
            }
        }
        for (var n = count(c, 2); n > 0; n--) {
            c.readIndex();
            c.readIndex();
        }
        c.skip(4);
        var zones = c.readI32();
        if (zones < 0 || zones > 64) {
            throw new IOException("Invalid BSP zone count " + zones);
        }
        for (var i = 0; i < zones; i++) {
            c.readIndex();
            c.skip(20);
        }
        c.readIndex();
        c.skip(count(c, 25) * 25);
        c.skip(count(c, 4) * 4);
        var leavesStart = c.position;
        var leafCount = count(c, 11);
        var leafZones = new int[leafCount];
        var visibleZones = new long[leafCount];
        var permeating = new int[leafCount];
        var volumetric = new int[leafCount];
        for (var i = 0; i < leafCount; i++) {
            leafZones[i] = c.readIndex();
            permeating[i] = c.readIndex();
            volumetric[i] = c.readIndex();
            visibleZones[i] = (c.readI32() & 0xffffffffL) | (long) c.readI32() << 32;
        }
        var slots = new int[count(c, 1)];
        for (var i = 0; i < slots.length; i++) {
            slots[i] = c.readIndex();
        }
        var lightsEnd = c.position;
        c.skip(8);
        var vertexBytes = fileVersion > 0x6c ? 40 : 28;
        for (var n = count(c, 18); n > 0; n--) {
            c.skip(count(c, vertexBytes) * vertexBytes);
            c.skip(4);
            c.readIndex();
            c.skip(12);
        }
        var lightmapsStart = c.position;
        var lightmapCount = count(c, 100);
        var lightmaps = new ArrayList<Lightmap>(lightmapCount);
        var starts = new int[lightmapCount];
        var ends = new int[lightmapCount];
        for (var i = 0; i < lightmapCount; i++) {
            starts[i] = c.position;
            lightmaps.add(readLightmap(c));
            ends[i] = c.position;
        }
        var texturesStart = c.position;
        var textures = new ArrayList<Texture>();
        for (var n = count(c, 3); n > 0; n--) {
            var variants = new ArrayList<Variant>();
            for (var v = count(c, 30); v > 0; v--) {
                variants.add(readVariant(c, serialOffset));
            }
            var indices = indices(c);
            var owner = fileVersion > 125 ? c.readIndex() : 0;
            textures.add(new Texture(variants, indices, owner));
        }
        var texturesEnd = c.position;
        for (var lightmap : lightmaps) {
            if (lightmap.texture() < 0 || lightmap.texture() >= textures.size() && !textures.isEmpty()) {
                throw new IOException("A lightmap refers to a missing texture");
            }
        }
        return new BspLightmapTail(
                fileVersion,
                licenseVersion,
                serialOffset,
                payload,
                leavesStart,
                lightsEnd,
                leafZones,
                visibleZones,
                new LeafLights(permeating, volumetric, slots),
                lightmapsStart,
                starts,
                ends,
                texturesStart,
                texturesEnd,
                lightmaps,
                textures);
    }

    byte[] getPayload() {
        return payload.clone();
    }

    byte[] encode(Map<Integer, Lightmap> replaced, Map<VariantKey, Texels> texels, int newSerialOffset)
            throws IOException {
        return encode(leafLights, replaced, texels, newSerialOffset);
    }

    LeafLights getLeafLights() {
        return leafLights;
    }

    byte[] encode(
            LeafLights lists, Map<Integer, Lightmap> replaced, Map<VariantKey, Texels> texels, int newSerialOffset)
            throws IOException {
        if (lists.permeating.length != leafZones.length) {
            throw new IOException("The leaf light lists must keep the number of leaves");
        }
        var out = new ByteArrayOutputStream(payload.length + 1024);
        if (lists.equals(leafLights)) {
            out.write(payload, 0, lightmapsStart);
        } else {
            out.write(payload, 0, leavesStart);
            PackageBytes.writeIndex(out, leafZones.length);
            for (var i = 0; i < leafZones.length; i++) {
                PackageBytes.writeIndex(out, leafZones[i]);
                PackageBytes.writeIndex(out, lists.permeating[i]);
                PackageBytes.writeIndex(out, lists.volumetric[i]);
                PackageBytes.writeInt(out, (int) visibleZones[i]);
                PackageBytes.writeInt(out, (int) (visibleZones[i] >>> 32));
            }
            PackageBytes.writeIndex(out, lists.slots.length);
            for (var slot : lists.slots) {
                PackageBytes.writeIndex(out, slot);
            }
            out.write(payload, lightsEnd, lightmapsStart - lightsEnd);
        }
        if (replaced.isEmpty()) {
            out.write(payload, lightmapsStart, texturesStart - lightmapsStart);
        } else {
            PackageBytes.writeIndex(out, lightmaps.size());
            for (var i = 0; i < lightmaps.size(); i++) {
                var replacement = replaced.get(i);
                if (replacement == null) {
                    out.write(payload, lightmapStarts[i], lightmapEnds[i] - lightmapStarts[i]);
                } else {
                    writeLightmap(out, replacement);
                }
            }
        }
        var shift = newSerialOffset - serialOffset + (out.size() - texturesStart);
        var section = Arrays.copyOfRange(payload, texturesStart, texturesEnd);
        var buffer = PackageBytes.littleEndian(section);
        for (var t = 0; t < textures.size(); t++) {
            var variants = textures.get(t).variants();
            for (var v = 0; v < variants.size(); v++) {
                var variant = variants.get(v);
                var fullEnd = variant.fullEndPosition() - serialOffset - texturesStart;
                var fullStart = fullEnd - variant.full().length;
                var halfEnd = variant.halfEndPosition() - serialOffset - texturesStart;
                var halfStart = halfEnd - variant.half().length;
                var replacement = texels.get(new VariantKey(t, v));
                if (replacement != null) {
                    if (replacement.full().length != variant.full().length
                            || replacement.half().length != variant.half().length) {
                        throw new IOException("Replacement lightmap texels must keep their saved size");
                    }
                    System.arraycopy(replacement.full(), 0, section, fullStart, replacement.full().length);
                    System.arraycopy(replacement.half(), 0, section, halfStart, replacement.half().length);
                }
                buffer.putInt(variant.fullLazyAt() - texturesStart, variant.fullEndPosition() + shift);
                buffer.putInt(variant.halfLazyAt() - texturesStart, variant.halfEndPosition() + shift);
            }
        }
        out.writeBytes(section);
        out.write(payload, texturesEnd, payload.length - texturesEnd);
        var result = out.toByteArray();
        return result;
    }

    private static Lightmap readLightmap(Cursor c) throws IOException {
        var texture = c.readIndex();
        var surface = c.readIndex();
        var zone = c.readIndex();
        var offsetX = c.readIndex();
        var offsetY = c.readIndex();
        var sizeX = c.readIndex();
        var sizeY = c.readIndex();
        if (sizeX < 0 || sizeY < 0 || sizeX > 4096 || sizeY > 4096 || offsetX < 0 || offsetY < 0) {
            throw new IOException("Invalid lightmap placement");
        }
        var matrix = readFloats(c, 16);
        var base = readFloats(c, 3);
        var axisX = readFloats(c, 3);
        var axisY = readFloats(c, 3);
        var entries = new ArrayList<Entry>();
        for (var n = count(c, 30); n > 0; n--) {
            var light = c.readIndex();
            var length = count(c, 1);
            var bitmap = Arrays.copyOfRange(c.bytes, c.position, c.position + length);
            c.skip(length);
            var entry = new Entry(
                    light,
                    bitmap,
                    c.readI32(),
                    c.readI32(),
                    c.readI32(),
                    c.readI32(),
                    c.readI32(),
                    c.readI32(),
                    c.readI32());
            entries.add(entry);
        }
        var level = c.readIndex();
        var trailer = c.readI32();
        return new Lightmap(
                texture, surface, zone, offsetX, offsetY, sizeX, sizeY, matrix, base, axisX, axisY, entries, level,
                trailer);
    }

    private static Variant readVariant(Cursor c, int serialOffset) throws IOException {
        var level = c.readIndex();
        var indices = indices(c);
        var time = (c.readI32() & 0xffffffffL) | (long) c.readI32() << 32;
        var revision = c.readI32();
        var fullLazyAt = c.position;
        var fullEnd = c.readI32();
        var full = readBytes(c);
        if (fullEnd != c.position + serialOffset) {
            throw new IOException("Lightmap texels end at an unexpected position: stored " + fullEnd + ", actual "
                    + (c.position + serialOffset));
        }
        var halfLazyAt = c.position;
        var halfEnd = c.readI32();
        var half = readBytes(c);
        if (halfEnd != c.position + serialOffset) {
            throw new IOException("Half resolution lightmap texels end at an unexpected position");
        }
        var format = c.readU8();
        var width = c.readI32();
        var height = c.readI32();
        var staticRevision = c.readI32();
        return new Variant(
                level,
                indices,
                time,
                revision,
                full,
                half,
                format,
                width,
                height,
                staticRevision,
                fullEnd,
                halfEnd,
                fullLazyAt,
                halfLazyAt);
    }

    private static void writeLightmap(ByteArrayOutputStream out, Lightmap lightmap) {
        PackageBytes.writeIndex(out, lightmap.texture());
        PackageBytes.writeIndex(out, lightmap.surface());
        PackageBytes.writeIndex(out, lightmap.zone());
        PackageBytes.writeIndex(out, lightmap.offsetX());
        PackageBytes.writeIndex(out, lightmap.offsetY());
        PackageBytes.writeIndex(out, lightmap.sizeX());
        PackageBytes.writeIndex(out, lightmap.sizeY());
        for (var value : lightmap.matrix()) {
            PackageBytes.writeInt(out, Float.floatToRawIntBits(value));
        }
        for (var vector : new float[][] {lightmap.base(), lightmap.axisX(), lightmap.axisY()}) {
            for (var value : vector) {
                PackageBytes.writeInt(out, Float.floatToRawIntBits(value));
            }
        }
        PackageBytes.writeIndex(out, lightmap.entries().size());
        for (var entry : lightmap.entries()) {
            PackageBytes.writeIndex(out, entry.light());
            PackageBytes.writeIndex(out, entry.bitmap().length);
            out.writeBytes(entry.bitmap());
            for (var value : new int[] {
                entry.sizeX(), entry.sizeY(), entry.stride(), entry.minX(), entry.minY(), entry.maxX(), entry.maxY()
            }) {
                PackageBytes.writeInt(out, value);
            }
        }
        PackageBytes.writeIndex(out, lightmap.level());
        PackageBytes.writeInt(out, lightmap.trailer());
    }

    private static int count(Cursor c, int minimumBytes) throws IOException {
        var value = c.readIndex();
        if (value < 0 || (long) value * minimumBytes > c.end - c.position) {
            throw new IOException("Invalid BSP array count " + value);
        }
        return value;
    }

    private static int[] indices(Cursor c) throws IOException {
        var result = new int[count(c, 4)];
        for (var i = 0; i < result.length; i++) {
            result[i] = c.readI32();
        }
        return result;
    }

    private static byte[] readBytes(Cursor c) throws IOException {
        var length = count(c, 1);
        var result = Arrays.copyOfRange(c.bytes, c.position, c.position + length);
        c.skip(length);
        return result;
    }

    private static float[] readFloats(Cursor c, int count) throws IOException {
        var result = new float[count];
        for (var i = 0; i < count; i++) {
            result[i] = Float.intBitsToFloat(c.readI32());
        }
        return result;
    }
}
