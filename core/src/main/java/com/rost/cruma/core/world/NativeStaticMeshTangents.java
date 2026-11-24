package com.rost.cruma.core.world;

import com.rost.cruma.core.unreal.UStaticMesh;
import java.util.Objects;

public final class NativeStaticMeshTangents {
    private NativeStaticMeshTangents() {}

    public static float[] generate(UStaticMesh mesh) {
        var vertices = mesh.getVertexStream().getVertices();
        var uvs = mesh.getUvStream()[0].getUvs();
        var input = new float[vertices.length * 6];
        var coordinates = new float[uvs.length * 2];
        for (var index = 0; index < vertices.length; index++) {
            var vertex = vertices[index];
            input[index * 6] = vertex.getLocation().getX();
            input[index * 6 + 1] = vertex.getLocation().getY();
            input[index * 6 + 2] = vertex.getLocation().getZ();
            input[index * 6 + 3] = vertex.getNormal().getX();
            input[index * 6 + 4] = vertex.getNormal().getY();
            input[index * 6 + 5] = vertex.getNormal().getZ();
        }
        for (var index = 0; index < uvs.length; index++) {
            coordinates[index * 2] = uvs[index].getX();
            coordinates[index * 2 + 1] = uvs[index].getY();
        }
        return generate(input, coordinates, mesh.getIndexStream().getIndices());
    }

    public static float[] generate(float[] vertices, float[] uvs, int[] indices) {
        if (vertices.length % 6 != 0 || uvs.length != vertices.length / 3 || indices.length % 3 != 0) {
            throw new IllegalArgumentException("invalid tangent stream dimensions");
        }
        var count = vertices.length / 6;
        var tangents = new float[count * 3];
        var bitangents = new float[count * 3];
        for (var first = 0; first < indices.length; first += 3) {
            var a = Objects.checkIndex(indices[first], count);
            var b = Objects.checkIndex(indices[first + 1], count);
            var c = Objects.checkIndex(indices[first + 2], count);
            var du1 = uvs[b * 2] - uvs[a * 2];
            var dv1 = uvs[b * 2 + 1] - uvs[a * 2 + 1];
            var du2 = uvs[c * 2] - uvs[a * 2];
            var dv2 = uvs[c * 2 + 1] - uvs[a * 2 + 1];
            var inverse = 1f / (du2 * dv1 - dv2 * du1);
            for (var channel = 0; channel < 3; channel++) {
                var edge1 = vertices[b * 6 + channel] - vertices[a * 6 + channel];
                var edge2 = vertices[c * 6 + channel] - vertices[a * 6 + channel];
                var tangent = (-dv2 * edge1 + dv1 * edge2) * inverse;
                var bitangent = (-du1 * edge2 + du2 * edge1) * inverse;
                tangents[a * 3 + channel] += tangent;
                tangents[b * 3 + channel] += tangent;
                tangents[c * 3 + channel] += tangent;
                bitangents[a * 3 + channel] += bitangent;
                bitangents[b * 3 + channel] += bitangent;
                bitangents[c * 3 + channel] += bitangent;
            }
        }
        var output = new float[count * 4];
        for (var index = 0; index < count; index++) {
            var x = tangents[index * 3];
            var y = tangents[index * 3 + 1];
            var z = tangents[index * 3 + 2];
            var nx = vertices[index * 6 + 3];
            var ny = vertices[index * 6 + 4];
            var nz = vertices[index * 6 + 5];
            var dot = (y * ny + x * nx) + z * nz;
            var tx = x - nx * dot;
            var ty = y - ny * dot;
            var tz = z - nz * dot;
            var lengthSquared = (tx * tx + ty * ty) + tz * tz;
            if ((double) lengthSquared >= 1e-8) {
                var inverse = (float) (1.0 / Math.sqrt(lengthSquared));
                tx *= inverse;
                ty *= inverse;
                tz *= inverse;
            }
            var orientation =
                    (bitangents[index * 3] * (z * ny - nz * y) + bitangents[index * 3 + 1] * (nz * x - z * nx))
                            + bitangents[index * 3 + 2] * (y * nx - ny * x);
            output[index * 4] = tx;
            output[index * 4 + 1] = ty;
            output[index * 4 + 2] = tz;
            output[index * 4 + 3] = orientation < 0f ? -1f : 1f;
        }
        return output;
    }
}
