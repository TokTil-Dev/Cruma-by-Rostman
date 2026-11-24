package com.rost.cruma.core.unreal;

import com.rost.cruma.core.unreal.Bsp.UPrimitive;
import com.rost.cruma.core.unreal.primitives.UBox;
import glm_.vec3.Vec3;
import glm_.vec4.Vec4;
import java.util.ArrayList;
import java.util.List;

public final class UConvexVolume extends UPrimitive {
    private List<Face> faces = List.of();
    private List<Edge> edges = List.of();
    private UBox convexBounds;

    public UConvexVolume(UArchive archive) {
        super(archive);
    }

    @Override
    public void deserialize() {
        super.deserialize();
        var faceCount = readCount("face", 17, 26);
        var importedFaces = new ArrayList<Face>(faceCount);
        for (var face = 0; face < faceCount; face++) {
            var plane = new Vec4(archive.readFloat(), archive.readFloat(), archive.readFloat(), archive.readFloat());
            var vertexCount = readCount("vertex", 12, 26 + 17 * (faceCount - face - 1));
            var vertices = new ArrayList<Vec3>(vertexCount);
            for (var vertex = 0; vertex < vertexCount; vertex++) {
                vertices.add(archive.readVector());
            }
            importedFaces.add(new Face(plane, List.copyOf(vertices)));
        }
        var edgeCount = readCount("edge", 16, 25);
        var importedEdges = new ArrayList<Edge>(edgeCount);
        for (var edge = 0; edge < edgeCount; edge++) {
            importedEdges.add(new Edge(archive.readInt(), archive.readInt(), archive.readInt(), archive.readInt()));
        }
        convexBounds = UBox.of(archive);
        faces = List.copyOf(importedFaces);
        edges = List.copyOf(importedEdges);
    }

    public List<Face> getFaces() {
        return faces;
    }

    public List<Edge> getEdges() {
        return edges;
    }

    public UBox getConvexBounds() {
        return convexBounds;
    }

    public record Face(Vec4 plane, List<Vec3> vertices) {}

    public record Edge(int face0, int face1, int vertex0, int vertex1) {}

    private int readCount(String kind, int minimumElementBytes, int reservedTailBytes) {
        var count = archive.readIndex();
        var available = archive.getRemainingInObject() - reservedTailBytes;
        if (count < 0 || available < 0 || count > available / minimumElementBytes) {
            throw new IllegalStateException(
                    "Invalid ConvexVolume " + kind + " count " + count + " in " + getFullName());
        }
        return count;
    }
}
