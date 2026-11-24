package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.PackageBytes.Cursor;
import com.rost.cruma.core.editing.TerrainNativeCodec.Box;
import com.rost.cruma.core.editing.TerrainNativeCodec.Vector;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class NativeStaticMeshCollisionCodec {
    private static final int MAX_RECORDS = 1_000_000;

    private NativeStaticMeshCollisionCodec() {}

    public record Plane(Vector normal, float distance) {}

    public record Face(
            Plane surface,
            Plane edge0,
            Plane edge1,
            Plane edge2,
            int vertex0,
            int vertex1,
            int vertex2,
            int material) {}

    public record Node(int face, int coplanar, int back, int front, Box bounds) {}

    public record Collision(List<Face> faces, List<Node> nodes) {
        public Collision {
            faces = List.copyOf(faces);
            nodes = List.copyOf(nodes);
        }
    }

    public static Collision read(
            int fileVersion, int licenseVersion, byte[] arrays, int absoluteStart, int vertexCount, int materialCount)
            throws IOException {
        if (fileVersion < 114
                || fileVersion > 131
                || licenseVersion < 17
                || licenseVersion > 40
                || absoluteStart < 0
                || vertexCount < 0
                || materialCount < 0) {
            throw new IOException("Unsupported native collision layout or context");
        }
        var c = new Cursor(arrays, 0, arrays.length);
        var faceEnd = readEndpoint(c, absoluteStart);
        var faceCount = count(c.readIndex(), faceEnd - c.position, 68);
        var faces = new ArrayList<Face>(faceCount);
        for (var i = 0; i < faceCount; i++) {
            faces.add(new Face(
                    readPlane(c),
                    readPlane(c),
                    readPlane(c),
                    readPlane(c),
                    c.readIndex(),
                    c.readIndex(),
                    c.readIndex(),
                    c.readIndex()));
        }
        requireExactEnd(c, faceEnd);
        var nodeEnd = readEndpoint(c, absoluteStart);
        var nodeCount = count(c.readIndex(), nodeEnd - c.position, 29);
        var nodes = new ArrayList<Node>(nodeCount);
        for (var i = 0; i < nodeCount; i++) {
            var face = c.readIndex();
            var coplanar = c.readIndex();
            var back = c.readIndex();
            var front = c.readIndex();
            nodes.add(new Node(face, coplanar, back, front, new Box(readVector(c), readVector(c), c.readU8())));
        }
        requireExactEnd(c, nodeEnd);
        requireExactEnd(c, arrays.length);
        var result = new Collision(faces, nodes);
        validate(result, vertexCount, materialCount);
        if (!Arrays.equals(arrays, write(result, absoluteStart))) {
            throw new IOException("Noncanonical native collision encoding");
        }
        return result;
    }

    public static byte[] write(Collision collision, int absoluteStart) throws IOException {
        if (absoluteStart < 0
                || collision.faces().size() > MAX_RECORDS
                || collision.nodes().size() > MAX_RECORDS) {
            throw new IOException("Invalid native collision output context");
        }
        var out = new ByteArrayOutputStream();
        var faces = new ByteArrayOutputStream();
        PackageBytes.writeIndex(faces, collision.faces().size());
        for (var face : collision.faces()) {
            writePlane(faces, face.surface());
            writePlane(faces, face.edge0());
            writePlane(faces, face.edge1());
            writePlane(faces, face.edge2());
            PackageBytes.writeIndex(faces, face.vertex0());
            PackageBytes.writeIndex(faces, face.vertex1());
            PackageBytes.writeIndex(faces, face.vertex2());
            PackageBytes.writeIndex(faces, face.material());
        }
        writeLazy(out, faces, absoluteStart);
        var nodes = new ByteArrayOutputStream();
        PackageBytes.writeIndex(nodes, collision.nodes().size());
        for (var node : collision.nodes()) {
            PackageBytes.writeIndex(nodes, node.face());
            PackageBytes.writeIndex(nodes, node.coplanar());
            PackageBytes.writeIndex(nodes, node.back());
            PackageBytes.writeIndex(nodes, node.front());
            writeVector(nodes, node.bounds().min());
            writeVector(nodes, node.bounds().max());
            if (node.bounds().valid() != 0 && node.bounds().valid() != 1) {
                throw new IOException("Invalid native collision box validity");
            }
            nodes.write(node.bounds().valid());
        }
        writeLazy(out, nodes, absoluteStart);
        return out.toByteArray();
    }

    private static void validate(Collision collision, int vertexCount, int materialCount) throws IOException {
        for (var face : collision.faces()) {
            requireIndex(face.vertex0(), vertexCount, false);
            requireIndex(face.vertex1(), vertexCount, false);
            requireIndex(face.vertex2(), vertexCount, false);
            requireIndex(face.material(), materialCount, false);
        }
        var nodes = collision.nodes();
        for (var node : nodes) {
            requireIndex(node.face(), collision.faces().size(), false);
            requireIndex(node.coplanar(), nodes.size(), true);
            requireIndex(node.back(), nodes.size(), true);
            requireIndex(node.front(), nodes.size(), true);
            var box = node.bounds();
            if ((box.valid() != 0 && box.valid() != 1)
                    || box.valid() == 1
                            && (box.min().x() > box.max().x()
                                    || box.min().y() > box.max().y()
                                    || box.min().z() > box.max().z())) {
                throw new IOException("Invalid native collision node bounds");
            }
        }
        var states = new byte[nodes.size()];
        var stack = new int[nodes.size()];
        var nextChild = new byte[nodes.size()];
        for (var root = 0; root < nodes.size(); root++) {
            if (states[root] != 0) {
                continue;
            }
            var depth = 0;
            stack[0] = root;
            states[root] = 1;
            while (depth >= 0) {
                var index = stack[depth];
                var childSlot = nextChild[index]++;
                if (childSlot == 3) {
                    states[index] = 2;
                    depth--;
                    continue;
                }
                var node = nodes.get(index);
                var child = childSlot == 0 ? node.coplanar() : childSlot == 1 ? node.back() : node.front();
                if (child < 0 || states[child] == 2) {
                    continue;
                }
                if (states[child] == 1) {
                    throw new IOException("Cyclic native collision tree");
                }
                stack[++depth] = child;
                states[child] = 1;
            }
        }
    }

    private static void requireIndex(int value, int size, boolean nullable) throws IOException {
        if (nullable && value == -1) {
            return;
        }
        if (value < 0 || value >= size) {
            throw new IOException("Native collision index outside its owning array");
        }
    }

    private static int readEndpoint(Cursor c, int absoluteStart) throws IOException {
        var absolute = c.readI32();
        var relative = (long) absolute - absoluteStart;
        if (relative < c.position || relative > c.end) {
            throw new IOException("Invalid native collision lazy endpoint");
        }
        return (int) relative;
    }

    private static int count(int value, int remaining, int minimumBytes) throws IOException {
        if (value < 0 || value > MAX_RECORDS || (long) value * minimumBytes > remaining) {
            throw new IOException("Invalid native collision count");
        }
        return value;
    }

    private static void requireExactEnd(Cursor c, int expected) throws IOException {
        if (c.position != expected) {
            throw new IOException("Native collision array did not consume its exact span");
        }
    }

    private static Plane readPlane(Cursor c) throws IOException {
        return new Plane(readVector(c), readScalar(c));
    }

    private static Vector readVector(Cursor c) throws IOException {
        return new Vector(readScalar(c), readScalar(c), readScalar(c));
    }

    private static float readScalar(Cursor c) throws IOException {
        var result = Float.intBitsToFloat(c.readI32());
        if (!Float.isFinite(result)) {
            throw new IOException("Non-finite native collision coordinate");
        }
        return result;
    }

    private static void writePlane(ByteArrayOutputStream out, Plane plane) throws IOException {
        writeVector(out, plane.normal());
        writeScalar(out, plane.distance());
    }

    private static void writeVector(ByteArrayOutputStream out, Vector vector) throws IOException {
        writeScalar(out, vector.x());
        writeScalar(out, vector.y());
        writeScalar(out, vector.z());
    }

    private static void writeScalar(ByteArrayOutputStream out, float value) throws IOException {
        if (!Float.isFinite(value)) {
            throw new IOException("Non-finite native collision coordinate");
        }
        PackageBytes.writeInt(out, Float.floatToRawIntBits(value));
    }

    private static void writeLazy(ByteArrayOutputStream out, ByteArrayOutputStream payload, int absoluteStart)
            throws IOException {
        var end = (long) absoluteStart + out.size() + 4 + payload.size();
        if (end > Integer.MAX_VALUE) {
            throw new IOException("Native collision lazy endpoint overflow");
        }
        PackageBytes.writeInt(out, (int) end);
        payload.writeTo(out);
    }
}
