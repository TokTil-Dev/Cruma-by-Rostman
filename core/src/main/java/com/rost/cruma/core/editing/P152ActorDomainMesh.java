package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.NativeStaticMeshCollisionCodec.Collision;
import com.rost.cruma.core.editing.PackageBytes.Cursor;
import com.rost.cruma.core.editing.PackageBytes.ExportEntry;
import com.rost.cruma.core.editing.ShadowTriangleQuery.Triangle;
import com.rost.cruma.core.editing.TerrainNativeCodec.Box;
import com.rost.cruma.core.editing.TerrainNativeCodec.Vector;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;

final class P152ActorDomainMesh {
    private P152ActorDomainMesh() {}

    record Mesh(
            Box primitiveBounds,
            Collision collision,
            int simpleCollisionModel,
            int internalVersion,
            Box simpleCollisionBounds) {}

    record RenderGeometry(Box primitiveBounds, List<Triangle> triangles) {}

    static List<Triangle> readRenderTriangles(PackageBytes source, ExportEntry entry, BooleanSupplier cancelled)
            throws IOException {
        return readRenderGeometry(source, entry, cancelled).triangles();
    }

    static RenderGeometry readRenderGeometry(PackageBytes source, ExportEntry entry, BooleanSupplier cancelled)
            throws IOException {
        var mesh = read(source, entry, cancelled);
        var c = new Cursor(source.body, entry.serialOffset(), entry.serialOffset() + entry.serialSize());
        int materials = readMaterialCount(source, c);
        readBox(c);
        readVector(c);
        readFloat(c);
        int sections = count(c.readIndex(), 65536);
        if (sections != materials) {
            throw new IOException("StaticMesh section/material counts differ");
        }
        var first = new int[sections];
        var faces = new int[sections];
        for (int s = 0; s < sections; s++) {
            P152FreshHashQuery.checkCancelled(cancelled);
            if (c.readI32() != 0) {
                throw new IOException("Only StaticMesh triangle-list sections are captured");
            }
            first[s] = c.readU16();
            c.readU16();
            c.readU16();
            c.readU16();
            faces[s] = c.readU16();
        }
        readBox(c);
        int vertices = count(c.readIndex(), 1_000_000);
        var positions = new Vector[vertices];
        for (int v = 0; v < vertices; v++) {
            P152FreshHashQuery.checkCancelled(cancelled);
            positions[v] = readVector(c);
            readVector(c);
        }
        c.readI32();
        skipArray(c, 4);
        c.readI32();
        skipArray(c, 4);
        c.readI32();
        int streams = count(c.readIndex(), 32);
        for (int s = 0; s < streams; s++) {
            skipUv(c);
        }
        int count = count(c.readIndex(), 1_000_000);
        var indices = new int[count];
        for (int i = 0; i < count; i++) {
            P152FreshHashQuery.checkCancelled(cancelled);
            indices[i] = c.readU16();
            if (indices[i] >= vertices) {
                throw new IOException("StaticMesh render index exceeds vertex stream");
            }
        }
        var result = new ArrayList<Triangle>();
        for (int s = 0; s < sections; s++) {
            if ((long) first[s] + 3L * faces[s] > count || (long) result.size() + faces[s] > 1_000_000) {
                throw new IOException("StaticMesh render section exceeds its streams");
            }
            for (int f = 0; f < faces[s]; f++) {
                P152FreshHashQuery.checkCancelled(cancelled);
                int i = first[s] + f * 3;
                result.add(new Triangle(
                        0, s, f, positions[indices[i]], positions[indices[i + 1]], positions[indices[i + 2]]));
            }
        }
        P152FreshHashQuery.checkCancelled(cancelled);
        return new RenderGeometry(mesh.primitiveBounds(), List.copyOf(result));
    }

    static Mesh read(PackageBytes source, ExportEntry entry) throws IOException {
        return read(source, entry, () -> false);
    }

    static Mesh read(PackageBytes source, ExportEntry entry, BooleanSupplier cancelled) throws IOException {
        P152FreshHashQuery.checkCancelled(cancelled);
        if (!(source.fileVersion == 123 && source.licenseVersion == 35
                || source.fileVersion == 127 && source.licenseVersion == 39
                || source.fileVersion == 128 && source.licenseVersion == 39
                || source.fileVersion == 130 && source.licenseVersion == 40)) {
            throw new IOException("Unaudited StaticMesh layout " + source.fileVersion + "/" + source.licenseVersion);
        }
        P152ActorDomain.requireEngineClass(source, entry, "StaticMesh");
        if ((entry.flags() & 0x02000000) != 0) {
            throw new IOException("StaticMesh state frame is not qualified");
        }
        var c = new Cursor(source.body, entry.serialOffset(), entry.serialOffset() + entry.serialSize());
        var materials = readMaterialCount(source, c);
        var bounds = readBox(c);
        readVector(c);
        var radius = readFloat(c);
        if (radius < 0) {
            throw new IOException("Negative native primitive sphere radius");
        }
        skipArray(c, 14);
        readBox(c);
        var vertices = skipArray(c, 24);
        c.readI32();
        skipArray(c, 4);
        c.readI32();
        skipArray(c, 4);
        c.readI32();
        var uvStreams = count(c.readIndex(), 32);
        for (var i = 0; i < uvStreams; i++) {
            skipUv(c);
        }
        skipArray(c, 2);
        c.readI32();
        skipArray(c, 2);
        c.readI32();
        var model = readReference(source, c);
        var collisionStart = c.position;
        skipLazyArray(c);
        skipLazyArray(c);
        var collision = NativeStaticMeshCollisionCodec.read(
                source.fileVersion,
                source.licenseVersion,
                Arrays.copyOfRange(source.body, collisionStart, c.position),
                collisionStart,
                vertices,
                materials);
        c.readI32();
        readReference(source, c);
        readReference(source, c);
        readFloat(c);
        readFloat(c);
        c.readI32();
        readFloat(c);
        readFloat(c);
        c.readI32();
        c.readI32();
        c.readI32();
        c.readI32();
        if (source.licenseVersion > 36) {
            c.readI32();
        }
        skipLazyArray(c);
        var version = c.readI32();
        readReference(source, c);
        c.readI32();
        if (source.fileVersion >= 124) {
            skipUv(c);
            c.readI32();
            c.readI32();
        }
        if (c.position != c.end) {
            throw new IOException("Unaudited StaticMesh native suffix");
        }
        if (version != 8) {
            throw new IOException(
                    "StaticMesh PostLoad collision preservation requires native mesh version 8, got " + version);
        }
        var simpleBounds =
                model == 0 ? null : P152BspReceiverModel.captureSimpleCollisionBounds(source, model, cancelled);
        P152FreshHashQuery.checkCancelled(cancelled);
        return new Mesh(bounds, collision, model, version, simpleBounds);
    }

    private static int readMaterialCount(PackageBytes source, Cursor c) throws IOException {
        var materials = -1;
        var seen = new HashSet<String>();
        for (var tags = 0; tags < 16384; tags++) {
            var name = source.getName(c.readIndex());
            if (name.equalsIgnoreCase("None")) {
                if (materials < 0) {
                    throw new IOException("StaticMesh has no authored Materials array");
                }
                return materials;
            }
            var tag = c.readU8();
            var type = tag & 15;
            if (type == 0) {
                throw new IOException("Invalid StaticMesh property type");
            }
            if (type == 10) {
                source.getName(c.readIndex());
            }
            var length = switch (tag >> 4 & 7) {
                case 0 -> 1;
                case 1 -> 2;
                case 2 -> 4;
                case 3 -> 12;
                case 4 -> 16;
                case 5 -> c.readU8();
                case 6 -> c.readU16();
                default -> c.readI32();
            };
            var index = type != 3 && (tag & 128) != 0 ? c.readArrayIndex() : 0;
            if (!seen.add(name.toLowerCase(Locale.ROOT) + "/" + index)) {
                throw new IOException("Duplicate StaticMesh property " + name);
            }
            if (type == 3 && length != 0 && length != 1) {
                throw new IOException("Invalid StaticMesh Boolean width");
            }
            var start = c.position;
            c.skip(type == 3 ? 0 : length);
            if (name.equalsIgnoreCase("Materials")) {
                if (type != 9 || index != 0) {
                    throw new IOException("Invalid StaticMesh Materials tag");
                }
                materials = count(new Cursor(source.body, start, c.position).readIndex(), 65536);
            }
        }
        throw new IOException("StaticMesh property count exceeds editor bounds");
    }

    private static void skipLazyArray(Cursor c) throws IOException {
        var end = c.readI32();
        if (end <= c.position || end > c.end) {
            throw new IOException("Invalid native StaticMesh lazy endpoint");
        }
        var elements = c.readIndex();
        if (elements < 0 || c.position > end) {
            throw new IOException("Invalid native StaticMesh lazy count");
        }
        c.position = end;
    }

    private static void skipUv(Cursor c) throws IOException {
        skipArray(c, 8);
        c.skip(8);
    }

    private static int skipArray(Cursor c, int width) throws IOException {
        var count = count(c.readIndex(), 1_000_000);
        if ((long) count * width > c.end - c.position) {
            throw new IOException("Truncated native StaticMesh stream");
        }
        c.skip(count * width);
        return count;
    }

    private static int count(int value, int maximum) throws IOException {
        if (value < 0 || value > maximum) {
            throw new IOException("Native StaticMesh count exceeds editor bounds");
        }
        return value;
    }

    private static int readReference(PackageBytes source, Cursor c) throws IOException {
        var ref = c.readIndex();
        source.checkReference(ref);
        return ref;
    }

    private static Box readBox(Cursor c) throws IOException {
        var min = readVector(c);
        var max = readVector(c);
        var valid = c.readU8();
        if (valid != 1 || min.x() > max.x() || min.y() > max.y() || min.z() > max.z()) {
            throw new IOException("Invalid native StaticMesh box");
        }
        return new Box(min, max, valid);
    }

    private static Vector readVector(Cursor c) throws IOException {
        return new Vector(readFloat(c), readFloat(c), readFloat(c));
    }

    private static float readFloat(Cursor c) throws IOException {
        var value = Float.intBitsToFloat(c.readI32());
        if (!Float.isFinite(value)) {
            throw new IOException("Non-finite native StaticMesh field");
        }
        return value;
    }
}
