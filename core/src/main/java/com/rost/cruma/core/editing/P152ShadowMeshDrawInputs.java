package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.NativeClassDefaults.Property;
import com.rost.cruma.core.editing.P152ShadowMeshGeometry.Placement;
import com.rost.cruma.core.editing.PackageBytes.Cursor;
import com.rost.cruma.core.editing.PackageBytes.ExportEntry;
import com.rost.cruma.core.editing.TerrainNativeCodec.Vector;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

final class P152ShadowMeshDrawInputs {
    private static final int MAX_VALUES = 1_000_000;
    private static final long MAX_RETAINED_VALUES = 4_000_000;

    private P152ShadowMeshDrawInputs() {}

    record Vertex(Vector position, Vector normal) {}

    record Uv(float u, float v) {
        boolean isFinite() {
            return Float.isFinite(u) && Float.isFinite(v);
        }
    }

    record UvStream(int coordinateIndex, int revision, List<Uv> values) {
        UvStream {
            values = List.copyOf(values);
        }
    }

    record ColorStream(int revision, List<Integer> argb) {
        ColorStream {
            argb = List.copyOf(argb);
        }
    }

    record Material(boolean referenceAuthored, int reference, String path, List<Property> properties) {
        Material {
            properties = List.copyOf(properties);
        }
    }

    record Section(
            int firstIndex,
            int minimumVertex,
            int maximumVertex,
            int savedTriangleCount,
            int triangleCount,
            Material material) {}

    record Mesh(
            List<Section> sections,
            List<Vertex> vertices,
            int vertexRevision,
            ColorStream colors,
            ColorStream alpha,
            List<UvStream> uvStreams,
            List<Integer> indices,
            int indexRevision) {
        Mesh {
            sections = List.copyOf(sections);
            vertices = List.copyOf(vertices);
            uvStreams = List.copyOf(uvStreams);
            indices = List.copyOf(indices);
        }

        int triangleCount() {
            int result = 0;
            for (var section : sections) {
                result += section.triangleCount();
            }
            return result;
        }

        long retainedValues() {
            long result = sections.size()
                    + uvStreams.size()
                    + vertices.size()
                    + indices.size()
                    + colors.argb().size()
                    + alpha.argb().size();
            for (var stream : uvStreams) {
                result += stream.values().size();
            }
            for (var section : sections) {
                for (var property : section.material().properties()) {
                    result += 1L + property.name().length() + property.payload().length;
                }
            }
            return result;
        }
    }

    record Binding(Placement placement, Mesh mesh) {
        Binding {
            Objects.requireNonNull(placement);
            Objects.requireNonNull(mesh);
            if (placement.triangleCount() != mesh.triangleCount()) {
                throw new IllegalArgumentException("Mesh draw inputs differ from captured geometry");
            }
        }
    }

    static Mesh read(PackageBytes source, ExportEntry entry, BooleanSupplier cancelled) throws IOException {
        Objects.requireNonNull(cancelled);
        var geometry = P152ActorDomainMesh.readRenderGeometry(source, entry, cancelled);
        var c = new Cursor(source.body, entry.serialOffset(), entry.serialOffset() + entry.serialSize());
        var materials = readMaterials(source, c, cancelled);
        c.skip(41);
        int sectionCount = count(c.readIndex(), 65536);
        if (sectionCount != materials.size()) {
            throw new IOException("StaticMesh draw section/material counts differ");
        }
        var sections = new ArrayList<Section>(sectionCount);
        for (int i = 0; i < sectionCount; i++) {
            P152FreshHashQuery.checkCancelled(cancelled);
            if (c.readI32() != 0) {
                throw new IOException("StaticMesh draw input requires triangle-list sections");
            }
            sections.add(
                    new Section(c.readU16(), c.readU16(), c.readU16(), c.readU16(), c.readU16(), materials.get(i)));
        }
        c.skip(25);
        int vertexCount = count(c.readIndex(), MAX_VALUES);
        var vertices = new ArrayList<Vertex>(vertexCount);
        for (int i = 0; i < vertexCount; i++) {
            P152FreshHashQuery.checkCancelled(cancelled);
            vertices.add(new Vertex(readVector(c), readVector(c)));
        }
        int vertexRevision = c.readI32();
        var colors = readColors(c, cancelled);
        var alpha = readColors(c, cancelled);
        long retained = (long) vertexCount + colors.argb().size() + alpha.argb().size();
        var uvStreams = new ArrayList<UvStream>();
        int streamCount = count(c.readIndex(), 32);
        for (int i = 0; i < streamCount; i++) {
            P152FreshHashQuery.checkCancelled(cancelled);
            int size = count(c.readIndex(), MAX_VALUES);
            retained += size;
            requireBudget(retained);
            var values = new ArrayList<Uv>(size);
            for (int j = 0; j < size; j++) {
                P152FreshHashQuery.checkCancelled(cancelled);
                values.add(new Uv(Float.intBitsToFloat(c.readI32()), Float.intBitsToFloat(c.readI32())));
            }
            uvStreams.add(new UvStream(c.readI32(), c.readI32(), values));
        }
        int indexCount = count(c.readIndex(), MAX_VALUES);
        requireBudget(retained + indexCount);
        var indices = new ArrayList<Integer>(indexCount);
        for (int i = 0; i < indexCount; i++) {
            P152FreshHashQuery.checkCancelled(cancelled);
            int index = c.readU16();
            if (index >= vertexCount) {
                throw new IOException("StaticMesh draw index exceeds its vertex stream");
            }
            indices.add(index);
        }
        var result = new Mesh(sections, vertices, vertexRevision, colors, alpha, uvStreams, indices, c.readI32());
        requireBudget(result.retainedValues());
        if (result.triangleCount() != geometry.triangles().size()) {
            throw new IOException("StaticMesh draw stream differs from its validated triangle count");
        }
        P152FreshHashQuery.checkCancelled(cancelled);
        return result;
    }

    static void requireBudget(long values) throws IOException {
        if (values > MAX_RETAINED_VALUES) {
            throw new IOException("StaticMesh draw streams exceed editor retention bounds");
        }
    }

    private static List<Material> readMaterials(PackageBytes source, Cursor c, BooleanSupplier cancelled)
            throws IOException {
        var properties = P152EffectiveInputs.taggedProperties(source, c);
        Property array = null;
        for (var property : properties) {
            P152FreshHashQuery.checkCancelled(cancelled);
            if (property.name().equalsIgnoreCase("Materials")) {
                if (array != null || property.type() != 9 || property.arrayIndex() != 0) {
                    throw new IOException("Invalid StaticMesh draw Materials tag");
                }
                array = property;
            }
        }
        if (array == null) {
            throw new IOException("StaticMesh draw inputs require authored Materials");
        }
        var bytes = array.payload();
        var fields = new Cursor(bytes, 0, bytes.length);
        int size = count(fields.readIndex(), 65536);
        var materials = new ArrayList<Material>(size);
        for (int i = 0; i < size; i++) {
            P152FreshHashQuery.checkCancelled(cancelled);
            var tags = P152EffectiveInputs.taggedProperties(source, fields);
            boolean authored = false;
            int reference = 0;
            for (var tag : tags) {
                if (tag.name().equalsIgnoreCase("Material")) {
                    if (authored || tag.type() != 5 || tag.arrayIndex() != 0) {
                        throw new IOException("Invalid StaticMesh authored material reference");
                    }
                    var payload = tag.payload();
                    var operand = new Cursor(payload, 0, payload.length);
                    reference = operand.readIndex();
                    source.checkReference(reference);
                    if (operand.position != operand.end) {
                        throw new IOException("Unread StaticMesh material reference suffix");
                    }
                    authored = true;
                }
            }
            materials.add(new Material(authored, reference, authored ? source.getObjectPath(reference) : "", tags));
        }
        if (fields.position != fields.end) {
            throw new IOException("Unread StaticMesh Materials array suffix");
        }
        return List.copyOf(materials);
    }

    private static ColorStream readColors(Cursor c, BooleanSupplier cancelled) throws IOException {
        int size = count(c.readIndex(), MAX_VALUES);
        var colors = new ArrayList<Integer>(size);
        for (int i = 0; i < size; i++) {
            P152FreshHashQuery.checkCancelled(cancelled);
            colors.add(c.readI32());
        }
        return new ColorStream(c.readI32(), colors);
    }

    private static Vector readVector(Cursor c) throws IOException {
        float x = Float.intBitsToFloat(c.readI32()),
                y = Float.intBitsToFloat(c.readI32()),
                z = Float.intBitsToFloat(c.readI32());
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) {
            throw new IOException("Non-finite StaticMesh draw vector");
        }
        return new Vector(x, y, z);
    }

    private static int count(int count, int maximum) throws IOException {
        if (count < 0 || count > maximum) {
            throw new IOException("StaticMesh draw count exceeds editor bounds");
        }
        return count;
    }
}
