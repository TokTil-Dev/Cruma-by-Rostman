package com.rost.cruma.core.world;

import com.rost.cruma.core.unreal.Bsp.BspPolygonFlags;
import com.rost.cruma.core.unreal.Bsp.FBspVertex;
import com.rost.cruma.core.unreal.Bsp.FLightmapIndex;
import com.rost.cruma.core.unreal.Bsp.UBspNode;
import com.rost.cruma.core.unreal.Bsp.UModel;
import com.rost.cruma.core.unreal.UMaterial;
import com.rost.cruma.core.unreal.UMaterial.UBitMapMaterial;
import com.rost.cruma.core.unreal.UMaterial.UCombiner;
import com.rost.cruma.core.unreal.UMaterial.UModifier;
import com.rost.cruma.core.unreal.UMaterial.UShader;
import com.rost.cruma.core.unreal.UObjectRef;
import glm_.vec3.Vec3;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class BspRenderGeometry {
    private BspRenderGeometry() {}

    static FBspVertex[] buildVertices(UModel model, UBspNode node, UMaterial material, FLightmapIndex lightmap) {
        var surface = model.getSurfaces()[node.getSurfaceIndex()];
        var base = model.getPoints()[surface.getBaseIndex()];
        var uAxis = model.getVectors()[surface.getUIndex()];
        var vAxis = model.getVectors()[surface.getVIndex()];
        var uTranslation = translation(base, uAxis);
        var vTranslation = translation(base, vAxis);
        var uSize = (float) mappingSize(material, false);
        var vSize = (float) mappingSize(material, true);
        var plane = node.getPlane();
        var normal = new Vec3(plane.getX(), plane.getY(), plane.getZ());
        var vertices = new FBspVertex[node.getVertexCount()];
        var totalU = 0f;
        var totalV = 0f;
        for (var i = 0; i < vertices.length; i++) {
            var pointIndex = model.getVertices()[node.getVertexPoolIndex() + i].getVertexIndex();
            var point = model.getPoints()[pointIndex];
            var u = coordinate(point, uAxis, uTranslation) / uSize;
            var v = coordinate(point, vAxis, vTranslation) / vSize;
            totalU += u;
            totalV += v;
            var u2 = 0f;
            var v2 = 0f;
            if (lightmap != null) {
                var m = lightmap.uvMatrix();
                u2 = (lightmap.offsetX() + (m[4] * point.getY() + m[0] * point.getX() + m[8] * point.getZ() + m[12]))
                        * (1f / 512f);
                v2 = (lightmap.offsetY() + (m[1] * point.getX() + m[5] * point.getY() + m[9] * point.getZ() + m[13]))
                        * (1f / 512f);
            }
            vertices[i] = new FBspVertex(point, normal, u, v, u2, v2);
        }
        var centerU = roundMean(totalU / vertices.length);
        var centerV = roundMean(totalV / vertices.length);
        for (var i = 0; i < vertices.length; i++) {
            var vertex = vertices[i];
            vertices[i] = new FBspVertex(
                    vertex.position(), normal, vertex.u() - centerU, vertex.v() - centerV, vertex.u2(), vertex.v2());
        }
        return vertices;
    }

    static int mappingSize(UMaterial material, boolean vertical) {
        return mappingSize(material, vertical, 0);
    }

    static Map<UBspNode, float[]> waterTangents(UModel model) {
        var hasWater = false;
        for (var surface : model.getSurfaces()) {
            if ((surface.getPolygonFlags() & BspPolygonFlags.PF_L2_Water) != 0) {
                hasWater = true;
                break;
            }
        }
        if (!hasWater) {
            return Map.of();
        }
        var groups = new LinkedHashMap<SectionKey, List<TangentSection>>();
        var lightmaps = model.getLightmaps();
        for (var node : model.getNodes()) {
            if (node.getVertexCount() == 0) {
                continue;
            }
            var surface = model.getSurfaces()[node.getSurfaceIndex()];
            var materialName = surface.getMaterial().peekFullName();
            var lightmapIndex = node.getILightmapIndex();
            var atlasIndex = lightmaps != null && lightmapIndex >= 0 && lightmapIndex < lightmaps.length
                    ? lightmaps[lightmapIndex].iLightmapTexture()
                    : -1;
            var key = new SectionKey(
                    materialName == null ? null : materialName.toLowerCase(Locale.ROOT),
                    surface.getPolygonFlags() & 0x02400100L,
                    atlasIndex);
            var sections = groups.computeIfAbsent(key, _ -> new ArrayList<>());
            var section = sectionFor(sections, node.getVertexCount());
            section.nodes.add(node);
            section.vertexCount += node.getVertexCount();
            section.water |= (surface.getPolygonFlags() & BspPolygonFlags.PF_L2_Water) != 0;
        }

        var result = new IdentityHashMap<UBspNode, float[]>();
        for (var sections : groups.values()) {
            for (var section : sections) {
                if (!section.water) {
                    continue;
                }
                var tangents = new float[section.vertexCount * 3];
                var normals = new float[tangents.length];
                var first = 0;
                for (var node : section.nodes) {
                    var material = model.getSurfaces()[node.getSurfaceIndex()]
                            .getMaterial()
                            .get();
                    var vertices = buildVertices(model, node, material, null);
                    for (var i = 0; i < vertices.length; i++) {
                        var normal = vertices[i].normal();
                        put(normals, first + i, normal.getX(), normal.getY(), normal.getZ());
                    }
                    for (var i = 2; i < vertices.length; i++) {
                        var a = vertices[0];
                        var b = vertices[i];
                        var c = vertices[i - 1];
                        var du1 = b.u() - a.u();
                        var dv1 = b.v() - a.v();
                        var du2 = c.u() - a.u();
                        var dv2 = c.v() - a.v();
                        var reciprocal = 1f / (du2 * dv1 - dv2 * du1);
                        var x = (-dv2 * (b.position().getX() - a.position().getX())
                                        + dv1
                                                * (c.position().getX()
                                                        - a.position().getX()))
                                * reciprocal;
                        var y = (-dv2 * (b.position().getY() - a.position().getY())
                                        + dv1
                                                * (c.position().getY()
                                                        - a.position().getY()))
                                * reciprocal;
                        var z = (-dv2 * (b.position().getZ() - a.position().getZ())
                                        + dv1
                                                * (c.position().getZ()
                                                        - a.position().getZ()))
                                * reciprocal;
                        put(tangents, first, x, y, z);
                        put(tangents, first + i, x, y, z);
                        put(tangents, first + i - 1, x, y, z);
                    }
                    for (var i = 0; i < vertices.length; i++) {
                        orthogonalize(tangents, normals, i);
                    }
                    first += vertices.length;
                }
                first = 0;
                for (var node : section.nodes) {
                    var end = first + node.getVertexCount();
                    result.put(node, Arrays.copyOfRange(tangents, first * 3, end * 3));
                    first = end;
                }
            }
        }
        return result;
    }

    private record SectionKey(String materialName, long polygonFlags, int atlasIndex) {}

    private static TangentSection sectionFor(List<TangentSection> sections, int count) {
        for (var candidate : sections) {
            if (candidate.vertexCount + count <= 65534) {
                return candidate;
            }
        }
        var section = new TangentSection();
        sections.add(section);
        return section;
    }

    private static final class TangentSection {
        private final List<UBspNode> nodes = new ArrayList<>();
        private int vertexCount;
        private boolean water;
    }

    private static void orthogonalize(float[] tangents, float[] normals, int index) {
        var offset = index * 3;
        var x = tangents[offset];
        var y = tangents[offset + 1];
        var z = tangents[offset + 2];
        var nx = normals[offset];
        var ny = normals[offset + 1];
        var nz = normals[offset + 2];
        var projection = y * ny + x * nx + z * nz;
        x -= nx * projection;
        y -= ny * projection;
        z -= nz * projection;
        var lengthSquared = x * x + y * y + z * z;
        if ((double) lengthSquared < 1.0e-8) {
            put(tangents, index, 0f, 0f, 0f);
        } else {
            var inverseLength = 1f / (float) Math.sqrt((double) lengthSquared);
            put(tangents, index, x * inverseLength, y * inverseLength, z * inverseLength);
        }
    }

    private static void put(float[] values, int index, float x, float y, float z) {
        var offset = index * 3;
        values[offset] = x;
        values[offset + 1] = y;
        values[offset + 2] = z;
    }

    private static int mappingSize(UMaterial material, boolean vertical, int depth) {
        if (material == null || depth >= 64) {
            return 0;
        }
        return switch (material) {
            case UBitMapMaterial bitmap -> vertical ? bitmap.getVSize() : bitmap.getUSize();
            case UModifier modifier -> mappingSize(modifier.getMaterial().get(), vertical, depth + 1);
            case UCombiner combiner ->
                Math.max(
                        mappingSize(combiner.getMaterial1().get(), vertical, depth + 1),
                        mappingSize(combiner.getMaterial2().get(), vertical, depth + 1));
            case UShader shader -> {
                var diffuse = shader.getDiffuse().get();
                yield mappingSize(
                        diffuse != null ? diffuse : materialInSlot(shader.getSelfIllumination()), vertical, depth + 1);
            }
            default -> 0;
        };
    }

    private static UMaterial materialInSlot(UObjectRef<? extends UMaterial, ?> slot) {
        return slot.peekFullName() == null ? null : slot.get();
    }

    private static float translation(Vec3 base, Vec3 axis) {
        return axis.getY() * -base.getY() + axis.getX() * -base.getX() + axis.getZ() * -base.getZ();
    }

    private static float coordinate(Vec3 point, Vec3 axis, float translation) {
        return axis.getY() * point.getY() + axis.getX() * point.getX() + axis.getZ() * point.getZ() + translation;
    }

    private static float roundMean(float value) {
        return (int) Math.floor((double) value + 0.5);
    }
}
