package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.ShadowTriangleQuery.Triangle;
import com.rost.cruma.core.unreal.Bsp.UModel;
import com.rost.cruma.core.unreal.UArchive;
import com.rost.cruma.core.unreal.UArchiveLoader;
import com.rost.cruma.core.world.NativeBspShadowProjection.Bounds;
import com.rost.cruma.core.world.NativeBspShadowProjection.Plane;
import com.rost.cruma.core.world.NativeBspShadowProjection.Sphere;
import com.rost.cruma.core.world.NativeBspShadowProjection.Vector;
import com.rost.cruma.core.world.NativeBspShadowProjection.Volume;
import glm_.vec3.Vec3;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

final class P152BspShadowGeometry {
    private static final int MAX_RECORDS = 1_000_000;
    private static final int SINGLE_ZONE_SURFACE_BITS = 0x0048a1a8;
    private final List<Node> nodes;
    private final int zoneCount;
    private final List<Bounds> bounds;
    private final List<Face> faces;
    private final Face[] faceByNode;

    private P152BspShadowGeometry(List<Node> nodes, List<Bounds> bounds, List<Face> faces, int zoneCount) {
        this.nodes = List.copyOf(nodes);
        this.zoneCount = zoneCount;
        this.bounds = List.copyOf(bounds);
        this.faces = List.copyOf(faces);
        faceByNode = new Face[nodes.size()];
        for (var face : faces) {
            faceByNode[face.node] = face;
        }
    }

    record Vertex(Vector position, Vector normal, float u, float v, float lightU, float lightV) {
        boolean hasFiniteTextureCoordinates() {
            return Float.isFinite(u) && Float.isFinite(v);
        }

        boolean hasFiniteLightCoordinates() {
            return Float.isFinite(lightU) && Float.isFinite(lightV);
        }
    }

    record Face(
            int node,
            int surface,
            int section,
            int materialReference,
            int surfaceFlags,
            int sectionFlags,
            List<Vertex> vertices) {
        Face {
            vertices = List.copyOf(vertices);
        }
    }

    private record Node(
            int front,
            int back,
            int coplanar,
            int renderBound,
            Sphere sphere,
            Plane plane,
            long zoneMask,
            int frontZone,
            int backZone,
            int surfaceFlags) {}

    static P152BspShadowGeometry capture(P152FreshBakeContext context, BooleanSupplier cancelled) throws IOException {
        context.verifySourcesUnchanged(cancelled);
        var source = new PackageBytes(context.getMapPath(), context.getMapBytes());
        P152BspReceiverModel.decode(source, context.getModelReference());
        var loader = new UArchiveLoader(context.getClientRoot(), List.of()) {
            @Override
            public UArchive loadArchive(String name) {
                throw new IllegalStateException("Frozen BSP draw inputs cannot load external packages");
            }

            @Override
            public UArchive loadArchiveIfPresent(String name) {
                throw new IllegalStateException("Frozen BSP draw inputs cannot load external packages");
            }
        };
        try (var archive =
                new UArchive(source.packageName, ByteBuffer.wrap(source.body).order(ByteOrder.LITTLE_ENDIAN), loader)) {
            var object = archive.getObjectLoader().loadObject(context.getModelReference());
            if (!(object instanceof UModel model)) {
                throw new IOException("BSP draw inputs require a native Model");
            }
            var result = snapshot(model, cancelled);
            context.verifySourcesUnchanged(cancelled);
            return result;
        } catch (CancellationException cancelledCapture) {
            throw cancelledCapture;
        } catch (IllegalArgumentException | IllegalStateException malformed) {
            throw new IOException("Incomplete BSP draw inputs", malformed);
        }
    }

    static P152BspShadowGeometry snapshot(UModel model, BooleanSupplier cancelled) {
        P152FreshHashQuery.checkCancelled(cancelled);
        NativeBspTrace.requireCompleteModel(model);
        var savedBounds = model.getBounds();
        if (savedBounds == null || savedBounds.length > MAX_RECORDS || model.getBspSections().length > MAX_RECORDS) {
            throw new IllegalArgumentException("Missing or oversized BSP draw bounds");
        }
        var bounds = new ArrayList<Bounds>();
        for (var box : savedBounds) {
            P152FreshHashQuery.checkCancelled(cancelled);
            if (box == null || !box.isValid()) {
                throw new IllegalArgumentException("Invalid BSP render bound");
            }
            bounds.add(new Bounds(toVector(box.min()), toVector(box.max())));
        }
        var nodes = new ArrayList<Node>();
        var faces = new ArrayList<Face>();
        var savedNodes = model.getNodes();
        int capturedVertices = 0;
        for (int index = 0; index < savedNodes.length; index++) {
            P152FreshHashQuery.checkCancelled(cancelled);
            var node = savedNodes[index];
            if (node == null || node.getExclusiveSphereBound() == null || node.getPlane() == null) {
                throw new IllegalArgumentException("Missing BSP draw node");
            }
            link(node.getFrontIndex(), savedNodes.length);
            link(node.getBackIndex(), savedNodes.length);
            link(node.getPlaneIndex(), savedNodes.length);
            link(node.getRenderBound(), bounds.size());
            var sphere = node.getExclusiveSphereBound();
            var plane = node.getPlane();
            int surface = node.getSurfaceIndex(), count = node.getVertexCount();
            if (surface < 0
                    || surface >= model.getSurfaces().length
                    || model.getSurfaces()[surface] == null
                    || count < 0
                    || count > 255) {
                throw new IllegalArgumentException("Invalid BSP surface or vertex count");
            }
            var zones = node.getZone();
            boolean completeZones = zones != null && zones.length == 2;
            nodes.add(new Node(
                    node.getFrontIndex(),
                    node.getBackIndex(),
                    node.getPlaneIndex(),
                    node.getRenderBound(),
                    new Sphere(new Vector(sphere.getX(), sphere.getY(), sphere.getZ()), sphere.getW()),
                    new Plane(plane.getX(), plane.getY(), plane.getZ(), plane.getW()),
                    node.getZoneMask(),
                    completeZones ? zones[1] : -1,
                    completeZones ? zones[0] : -1,
                    (int) model.getSurfaces()[surface].getPolygonFlags()));
            int sectionIndex = node.getISection();
            link(sectionIndex, model.getBspSections().length);
            if (sectionIndex == -1 || count == 0) {
                continue;
            }
            var section = model.getBspSections()[sectionIndex];
            int first = node.getIFirstVertex();
            if (section == null
                    || section.vertices() == null
                    || first < 0
                    || (long) first + count > section.vertices().length
                    || (long) first + count > 65536) {
                throw new IllegalArgumentException("BSP draw indices are outside their section");
            }
            capturedVertices += count;
            if (capturedVertices > MAX_RECORDS) {
                throw new IllegalArgumentException("BSP draw vertices exceed editor bounds");
            }
            var vertices = new ArrayList<Vertex>();
            for (int i = first; i < first + count; i++) {
                P152FreshHashQuery.checkCancelled(cancelled);
                var vertex = section.vertices()[i];
                if (vertex == null) {
                    throw new IllegalArgumentException("Missing BSP draw vertex");
                }
                vertices.add(new Vertex(
                        toVector(vertex.position()),
                        toVector(vertex.normal()),
                        vertex.u(),
                        vertex.v(),
                        vertex.u2(),
                        vertex.v2()));
            }
            faces.add(new Face(
                    index,
                    surface,
                    sectionIndex,
                    section.materialRef(),
                    (int) model.getSurfaces()[surface].getPolygonFlags(),
                    section.polygonFlags(),
                    vertices));
        }
        var result = new P152BspShadowGeometry(nodes, bounds, faces, model.getZones().length);
        result.visit(null, cancelled);
        return result;
    }

    List<Face> getFaces() {
        return faces;
    }

    List<Triangle> collectTriangles(int excludedSurfaceFlags, BooleanSupplier cancelled) {
        var result = new ArrayList<Triangle>();
        P152FreshHashQuery.checkCancelled(cancelled);
        for (var face : faces) {
            P152FreshHashQuery.checkCancelled(cancelled);
            if ((face.surfaceFlags & excludedSurfaceFlags) != 0) {
                continue;
            }
            for (int i = 2; i < face.vertices.size(); i++) {
                result.add(new Triangle(
                        ownerOfNode(face.node),
                        face.surface,
                        face.node,
                        positionOf(face.vertices.getFirst()),
                        positionOf(face.vertices.get(i)),
                        positionOf(face.vertices.get(i - 1))));
            }
        }
        P152FreshHashQuery.checkCancelled(cancelled);
        return List.copyOf(result);
    }

    Set<Integer> collectExcludedBySunBounds(Volume volume, BooleanSupplier cancelled) {
        var accepted = visit(Objects.requireNonNull(volume), cancelled);
        var excluded = new HashSet<Integer>();
        for (int i = 0; i < nodes.size(); i++) {
            P152FreshHashQuery.checkCancelled(cancelled);
            if (!accepted.get(i)) {
                excluded.add(ownerOfNode(i));
            }
        }
        P152FreshHashQuery.checkCancelled(cancelled);
        return Set.copyOf(excluded);
    }

    List<Face> orderSunFaces(Volume volume, Vector viewOrigin, BooleanSupplier cancelled) {
        return orderSunFaces(volume, viewOrigin, 0, cancelled);
    }

    List<Face> orderSingleZoneSunFaces(Volume volume, Vector viewOrigin, int viewZone, BooleanSupplier cancelled) {
        P152FreshHashQuery.checkCancelled(cancelled);
        if (zoneCount < 1 || zoneCount > 64 || viewZone <= 0 || viewZone >= zoneCount) {
            throw new IllegalArgumentException("Single-zone BSP admission requires a current nonzero view zone");
        }
        for (var node : nodes) {
            P152FreshHashQuery.checkCancelled(cancelled);
            if (node.frontZone < 0
                    || node.frontZone >= zoneCount
                    || node.backZone < 0
                    || node.backZone >= zoneCount
                    || (node.surfaceFlags & ~SINGLE_ZONE_SURFACE_BITS) != 0) {
                throw new IllegalArgumentException("BSP zones or surface flags exceed the single-zone draw audit");
            }
        }
        return orderSunFaces(volume, viewOrigin, viewZone, cancelled);
    }

    private List<Face> orderSunFaces(Volume volume, Vector viewOrigin, int viewZone, BooleanSupplier cancelled) {
        Objects.requireNonNull(volume);
        Objects.requireNonNull(viewOrigin);
        var result = new ArrayList<Face>();
        var stack = new ArrayDeque<Integer>();
        P152FreshHashQuery.checkCancelled(cancelled);
        if (!nodes.isEmpty()) {
            stack.push(0);
        }
        while (!stack.isEmpty()) {
            P152FreshHashQuery.checkCancelled(cancelled);
            int index = stack.pop();
            if (index < 0) {
                for (int chain = ~index; chain >= 0; chain = nodes.get(chain).coplanar) {
                    P152FreshHashQuery.checkCancelled(cancelled);
                    var face = faceByNode[chain];
                    var node = nodes.get(chain);
                    if (face != null
                            && (viewZone == 0 || isAdmittedInSingleZone(node, viewOrigin, viewZone))
                            && volume.checkSphere(node.sphere) != 2) {
                        result.add(face);
                    }
                }
                continue;
            }
            var node = nodes.get(index);
            if (viewZone != 0 && (node.zoneMask & (1L << viewZone)) == 0) {
                continue;
            }
            if (node.renderBound >= 0 && volume.checkBox(bounds.get(node.renderBound)) == 2) {
                continue;
            }
            float distance = distance(node.plane, viewOrigin);
            int near = distance > 0 ? node.front : node.back;
            int far = distance > 0 ? node.back : node.front;
            if (far >= 0) {
                stack.push(far);
            }
            stack.push(~index);
            if (near >= 0) {
                stack.push(near);
            }
        }
        P152FreshHashQuery.checkCancelled(cancelled);
        return List.copyOf(result);
    }

    private BitSet visit(Volume volume, BooleanSupplier cancelled) {
        var accepted = new BitSet(nodes.size());
        var seen = new BitSet(nodes.size());
        var stack = new ArrayDeque<Integer>();
        P152FreshHashQuery.checkCancelled(cancelled);
        if (!nodes.isEmpty()) {
            stack.push(0);
        }
        while (!stack.isEmpty()) {
            P152FreshHashQuery.checkCancelled(cancelled);
            int index = stack.pop();
            var node = nodes.get(index);
            if (seen.get(index)) {
                throw new IllegalArgumentException("Shared or cyclic BSP draw traversal");
            }
            if (volume != null && node.renderBound >= 0 && volume.checkBox(bounds.get(node.renderBound)) == 2) {
                seen.set(index);
                continue;
            }
            if (node.front >= 0) {
                stack.push(node.front);
            }
            if (node.back >= 0) {
                stack.push(node.back);
            }
            for (int chain = index; chain >= 0; chain = nodes.get(chain).coplanar) {
                P152FreshHashQuery.checkCancelled(cancelled);
                if (seen.get(chain)) {
                    throw new IllegalArgumentException("Shared or cyclic BSP draw traversal");
                }
                seen.set(chain);
                if (volume == null || volume.checkSphere(nodes.get(chain).sphere) != 2) {
                    accepted.set(chain);
                }
            }
        }
        P152FreshHashQuery.checkCancelled(cancelled);
        return accepted;
    }

    private static boolean isAdmittedInSingleZone(Node node, Vector origin, int viewZone) {
        boolean front = distance(node.plane, origin) > 0;
        if (!front && (node.surfaceFlags & 0x00080100) == 0) {
            return false;
        }
        return (front ? node.frontZone : node.backZone) == viewZone && (node.surfaceFlags & 0x2000) == 0;
    }

    private static float distance(Plane plane, Vector origin) {
        float result = ((origin.y() * plane.y() + origin.x() * plane.x()) + origin.z() * plane.z()) - plane.w();
        if (!Float.isFinite(result)) {
            throw new IllegalArgumentException("Non-finite BSP view-plane distance");
        }
        return result;
    }

    private static int ownerOfNode(int node) {
        return -1 - node;
    }

    private static void link(int index, int count) {
        if (index < -1 || index >= count) {
            throw new IllegalArgumentException("BSP draw link is outside its array");
        }
    }

    private static Vector toVector(Vec3 value) {
        if (value == null) {
            throw new IllegalArgumentException("Missing BSP draw vector");
        }
        return new Vector(value.getX(), value.getY(), value.getZ());
    }

    private static TerrainNativeCodec.Vector positionOf(Vertex vertex) {
        var p = vertex.position;
        return new TerrainNativeCodec.Vector(p.x(), p.y(), p.z());
    }
}
