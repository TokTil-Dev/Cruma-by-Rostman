package com.rost.cruma.core.editing;

import com.rost.cruma.core.unreal.Bsp.UModel;
import com.rost.cruma.core.unreal.UActor.LightActor;
import com.rost.cruma.core.unreal.UArchive;
import com.rost.cruma.core.unreal.ULevel;
import glm_.vec3.Vec3;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import org.eclipse.collections.impl.list.mutable.primitive.IntArrayList;
import org.jetbrains.annotations.Nullable;

public final class NativeBspLeafLights {
    public static final int LE_SUNLIGHT = 19;

    private static final int CAPACITY = 32;
    private static final float HALF_WORLD_MAX = 524288f;
    private static final float INFINITE_SCALE = 1.375f;
    private static final float THRESH_PRECISE = 0.01f;
    private static final float THRESH_FAST = 0.25f;
    private static final float THRESH_POINTS_ARE_SAME = 0.002f;
    private static final double SMALL_NUMBER = 1e-8;
    private static final int NOT_CSG_FLAGS = 0x25;
    private static final int BACK_SIDE = 0x40000000;
    private static final int SP_COPLANAR = 0;
    private static final int SP_FRONT = 1;
    private static final int SP_BACK = 2;
    private static final int SP_SPLIT = 3;
    private static final int VERTEX_LIMIT = 14;
    private static final long SPLIT_BUDGET = 2_000_000_000L;

    private NativeBspLeafLights() {}

    public static Input capture(UArchive map, boolean onlyVisible) {
        var levels = new ArrayList<ULevel>();
        map.loadObjects("Level", levels);
        if (levels.isEmpty()) {
            throw new IllegalStateException("Map " + map.getName() + " has no Level");
        }
        var level = levels.getFirst();
        var model = level.getModel().get();
        if (model == null) {
            throw new IllegalStateException("Map " + map.getName() + " has no level model");
        }
        var lights = new ArrayList<Light>();
        for (var reference : level.getActors()) {
            if (reference.isNull()) {
                continue;
            }
            Object object;
            try {
                object = map.getObjectLoader().loadObject(reference.getIndex());
            } catch (RuntimeException unreadable) {
                object = null;
            }
            if (!(object instanceof LightActor light) || light.getLightType() == 0) {
                continue;
            }
            if (onlyVisible && (light.isHiddenEd() || light.isHiddenEdGroup())) {
                continue;
            }
            var location = light.getLocation();
            lights.add(new Light(
                    reference.getIndex(),
                    location.getX(),
                    location.getY(),
                    location.getZ(),
                    25f * (light.getLightRadius() + 1f),
                    light.getLightEffect() == LE_SUNLIGHT));
        }
        return new Input(Geometry.of(model), lights);
    }

    public static Lists build(Input input, BooleanSupplier cancelled) {
        return new Build(input.geometry(), cancelled).run(input.lights());
    }

    public static final class Flood {
        private final Geometry geometry;
        private final Build build;

        private Flood(Geometry geometry, Build build) {
            this.geometry = geometry;
            this.build = build;
        }

        public static Flood of(Geometry geometry, BooleanSupplier cancelled) {
            var build = new Build(geometry, cancelled);
            if (geometry.nodeCount() > 0) {
                build.prepare();
            }
            return new Flood(geometry, build);
        }

        public int[] getLeaves(Light light) {
            return geometry.nodeCount() == 0 ? new int[0] : build.floodAlone(light);
        }

        public int[] getNeighbors(int leaf) {
            return geometry.nodeCount() == 0 ? new int[0] : build.getNeighbors(leaf);
        }

        public int getLeafAt(float x, float y, float z) {
            return geometry.nodeCount() == 0 ? -1 : build.getLeafAt(x, y, z);
        }

        public int getZone(int leaf) {
            return geometry.leafZones()[leaf];
        }

        public boolean isEmpty() {
            return geometry.nodeCount() == 0;
        }
    }

    public static int[] assignLeaves(Geometry geometry) {
        var nodeLeaves = new int[geometry.nodeCount() * 2];
        Arrays.fill(nodeLeaves, -1);
        if (geometry.nodeCount() > 0) {
            assignLeaves(geometry, 0, geometry.rootOutside(), nodeLeaves, new int[1]);
        }
        return nodeLeaves;
    }

    public static boolean isRenumbering(int[] assigned, int[] saved, int leafCount) {
        if (assigned.length != saved.length) {
            return false;
        }
        var toSaved = new int[leafCount];
        var used = new boolean[leafCount];
        Arrays.fill(toSaved, -1);
        var highest = -1;
        for (var slot = 0; slot < assigned.length; slot++) {
            var a = assigned[slot];
            var s = saved[slot];
            if (a < 0 != s < 0) {
                return false;
            }
            if (a < 0) {
                continue;
            }
            if (a >= leafCount || s >= leafCount) {
                return false;
            }
            if (toSaved[a] == -1) {
                if (used[s]) {
                    return false;
                }
                toSaved[a] = s;
                used[s] = true;
            } else if (toSaved[a] != s) {
                return false;
            }
            highest = Math.max(highest, a);
        }
        return highest + 1 == leafCount;
    }

    private static void assignLeaves(Geometry geometry, int node, boolean outside, int[] nodeLeaves, int[] count) {
        for (var side = 0; side < 2; side++) {
            var child = side == 0 ? geometry.back()[node] : geometry.front()[node];
            var childOutside = isChildOutside(geometry, node, side, outside);
            if (child != -1) {
                assignLeaves(geometry, child, childOutside, nodeLeaves, count);
            } else if (childOutside) {
                nodeLeaves[node * 2 + side] = count[0]++;
            }
        }
    }

    private static boolean isChildOutside(Geometry geometry, int node, int side, boolean outside) {
        var notCsg = geometry.vertexCount()[node] == 0 || (geometry.flags()[node] & NOT_CSG_FLAGS) != 0;
        return side == 1 ? outside || !notCsg : outside && notCsg;
    }

    private static void splitInHalf(Poly poly, Poly half) {
        var middle = poly.n / 2;
        half.set(poly);
        half.n = poly.n - middle + 1;
        for (var i = 0; i < half.n - 1; i++) {
            System.arraycopy(poly.v, (middle + i) * 3, half.v, i * 3, 3);
        }
        System.arraycopy(poly.v, 0, half.v, (half.n - 1) * 3, 3);
        poly.n = middle + 1;
    }

    private static int fix(Poly poly) {
        var kept = 0;
        var previous = poly.n - 1;
        for (var i = 0; i < poly.n; i++) {
            if (!pointsAreSame(poly, i, previous)) {
                if (kept != i) {
                    System.arraycopy(poly.v, i * 3, poly.v, kept * 3, 3);
                }
                previous = kept;
                kept++;
            }
        }
        poly.n = kept >= 3 ? kept : 0;
        return poly.n;
    }

    private static boolean pointsAreSame(Poly poly, int a, int b) {
        for (var k = 0; k < 3; k++) {
            var difference = poly.v[a * 3 + k] - poly.v[b * 3 + k];
            if (!(-THRESH_POINTS_ARE_SAME < difference && difference < THRESH_POINTS_ARE_SAME)) {
                return false;
            }
        }
        return true;
    }

    private static float distance(Poly poly, int i, float bx, float by, float bz, float nx, float ny, float nz) {
        return ny * (poly.v[i * 3 + 1] - by) + nx * (poly.v[i * 3] - bx) + nz * (poly.v[i * 3 + 2] - bz);
    }

    private static float planeDot(float[] plane, float x, float y, float z) {
        return y * plane[1] + plane[0] * x + z * plane[2] - plane[3];
    }

    private static void plane(
            float ax, float ay, float az, float bx, float by, float bz, float cx, float cy, float cz, float[] plane) {
        var ux = bx - ax;
        var uy = by - ay;
        var uz = bz - az;
        var vx = cx - ax;
        var vy = cy - ay;
        var vz = cz - az;
        safeNormal(uy * vz - uz * vy, uz * vx - ux * vz, ux * vy - uy * vx, plane);
        plane[3] = plane[1] * ay + plane[0] * ax + plane[2] * az;
    }

    private static void safeNormal(float x, float y, float z, float[] result) {
        var squared = y * y + x * x + z * z;
        if (squared < SMALL_NUMBER) {
            result[0] = 0f;
            result[1] = 0f;
            result[2] = 0f;
            return;
        }
        var scale = 1f / (float) Math.sqrt(squared);
        result[0] = x * scale;
        result[1] = y * scale;
        result[2] = z * scale;
    }

    private static float[] findBestAxisVectors(float x, float y, float z) {
        var ax = Math.abs(x);
        var ay = Math.abs(y);
        var az = Math.abs(z);
        var bx = az > ax && az > ay ? 1f : 0f;
        var bz = 1f - bx;
        var dot = bx * x + z * bz;
        var axis = new float[4];
        safeNormal(bx - x * dot, -(y * dot), bz - dot * z, axis);
        return new float[] {
            axis[0], axis[1], axis[2], axis[1] * z - y * axis[2], axis[2] * x - z * axis[0], y * axis[0] - axis[1] * x
        };
    }

    private static int[] filled(int length) {
        var result = new int[length];
        Arrays.fill(result, -1);
        return result;
    }

    public record Geometry(
            int nodeCount,
            float[] nodePlanes,
            int[] back,
            int[] front,
            int[] surface,
            int[] vertexPool,
            int[] vertexCount,
            int[] flags,
            int[] savedNodeLeaves,
            float[] surfacePlanes,
            int[] surfaceNormals,
            float[] vectors,
            float[] points,
            int[] vertexPoints,
            int[] leafZones,
            boolean rootOutside) {

        public static Geometry of(UModel model) {
            var nodes = model.getNodes();
            var surfaces = model.getSurfaces();
            var leaves = model.getLeaves();
            if (nodes == null || surfaces == null || leaves == null || model.getVertices() == null) {
                throw new IllegalStateException("The level model has no BSP leaves");
            }
            var count = nodes.length;
            var nodePlanes = new float[count * 4];
            var back = new int[count];
            var front = new int[count];
            var surface = new int[count];
            var vertexPool = new int[count];
            var vertexCount = new int[count];
            var flags = new int[count];
            var savedNodeLeaves = new int[count * 2];
            for (var i = 0; i < count; i++) {
                var node = nodes[i];
                var plane = node.getPlane();
                nodePlanes[i * 4] = plane.getX();
                nodePlanes[i * 4 + 1] = plane.getY();
                nodePlanes[i * 4 + 2] = plane.getZ();
                nodePlanes[i * 4 + 3] = plane.getW();
                back[i] = node.getBackIndex();
                front[i] = node.getFrontIndex();
                surface[i] = node.getSurfaceIndex();
                vertexPool[i] = node.getVertexPoolIndex();
                vertexCount[i] = node.getVertexCount();
                flags[i] = node.getFlags();
                savedNodeLeaves[i * 2] = node.getLeaf()[0];
                savedNodeLeaves[i * 2 + 1] = node.getLeaf()[1];
            }
            var surfacePlanes = new float[surfaces.length * 4];
            var surfaceNormals = new int[surfaces.length];
            for (var i = 0; i < surfaces.length; i++) {
                var plane = surfaces[i].getPlane();
                surfacePlanes[i * 4] = plane.getX();
                surfacePlanes[i * 4 + 1] = plane.getY();
                surfacePlanes[i * 4 + 2] = plane.getZ();
                surfacePlanes[i * 4 + 3] = plane.getW();
                surfaceNormals[i] = surfaces[i].getNormalIndex();
            }
            var vertices = model.getVertices();
            var vertexPoints = new int[vertices.length];
            for (var i = 0; i < vertices.length; i++) {
                vertexPoints[i] = vertices[i].getVertexIndex();
            }
            var leafZones = new int[leaves.length];
            for (var i = 0; i < leaves.length; i++) {
                leafZones[i] = leaves[i].zone();
            }
            return new Geometry(
                    count,
                    nodePlanes,
                    back,
                    front,
                    surface,
                    vertexPool,
                    vertexCount,
                    flags,
                    savedNodeLeaves,
                    surfacePlanes,
                    surfaceNormals,
                    flatten(model.getVectors()),
                    flatten(model.getPoints()),
                    vertexPoints,
                    leafZones,
                    model.isRootOutside());
        }

        private static float[] flatten(Vec3[] values) {
            var result = new float[values == null ? 0 : values.length * 3];
            for (var i = 0; i < result.length / 3; i++) {
                result[i * 3] = values[i].getX();
                result[i * 3 + 1] = values[i].getY();
                result[i * 3 + 2] = values[i].getZ();
            }
            return result;
        }
    }

    public record Light(int reference, float x, float y, float z, float radius, boolean sunlight) {}

    public record Input(Geometry geometry, List<Light> lights) {}

    public record Lists(int[] permeating, int[] volumetric, int[] slots, int portals, int[] leavesPerLight) {}

    private static final class Poly {
        final float[] v = new float[CAPACITY * 3];
        float nx;
        float ny;
        float nz;
        int n;

        Poly copy() {
            var result = new Poly();
            result.set(this);
            return result;
        }

        void set(Poly other) {
            nx = other.nx;
            ny = other.ny;
            nz = other.nz;
            n = other.n;
            System.arraycopy(other.v, 0, v, 0, other.n * 3);
        }

        void add(float x, float y, float z) {
            if (n >= CAPACITY) {
                throw new IllegalStateException("Portal polygon has more than " + CAPACITY + " vertices");
            }
            v[n * 3] = x;
            v[n * 3 + 1] = y;
            v[n * 3 + 2] = z;
            n++;
        }

        void addVertex(Poly source, int index) {
            add(source.v[index * 3], source.v[index * 3 + 1], source.v[index * 3 + 2]);
        }

        void reverse() {
            nx = -nx;
            ny = -ny;
            nz = -nz;
            for (var i = 0; i < n / 2; i++) {
                var j = n - 1 - i;
                for (var k = 0; k < 3; k++) {
                    var swap = v[i * 3 + k];
                    v[i * 3 + k] = v[j * 3 + k];
                    v[j * 3 + k] = swap;
                }
            }
        }
    }

    private record Portal(Poly poly, int frontLeaf, int backLeaf) {}

    private static final class Build {
        private final Geometry geometry;
        private final BooleanSupplier cancelled;
        private final IntArrayList nodeStack = new IntArrayList();
        private final List<Portal> portals = new ArrayList<>();
        private int[] nodeLeaves;
        private IntArrayList[] leafPortals;
        private IntArrayList[] leafLights;
        private long splits;

        Build(Geometry geometry, BooleanSupplier cancelled) {
            this.geometry = geometry;
            this.cancelled = cancelled;
        }

        Lists run(List<Light> lights) {
            var leafCount = geometry.leafZones().length;
            if (geometry.nodeCount() == 0) {
                return new Lists(filled(leafCount), filled(leafCount), new int[0], 0, new int[lights.size()]);
            }
            prepare();
            var leavesPerLight = new int[lights.size()];
            for (var i = 0; i < lights.size(); i++) {
                checkCancelled();
                leavesPerLight[i] = actorVisibility(lights.get(i), -1, -1, null);
            }
            var slots = new IntArrayList();
            var permeating = form(slots);
            var volumetric = form(slots);
            return new Lists(permeating, volumetric, slots.toArray(), portals.size(), leavesPerLight);
        }

        void prepare() {
            var leafCount = geometry.leafZones().length;
            var assigned = assignLeaves(geometry);
            if (!isRenumbering(assigned, geometry.savedNodeLeaves(), leafCount)) {
                throw new IllegalStateException("The editor's leaf numbering does not give the saved leaves back");
            }
            nodeLeaves = geometry.savedNodeLeaves().clone();
            leafPortals = new IntArrayList[leafCount];
            leafLights = new IntArrayList[leafCount];
            for (var i = 0; i < leafCount; i++) {
                leafPortals[i] = new IntArrayList();
                leafLights[i] = new IntArrayList();
            }
            makePortals(0);
        }

        int[] floodAlone(Light light) {
            for (var list : leafLights) {
                list.clear();
            }
            var budget = splits;
            try {
                actorVisibility(light, -1, -1, null);
            } finally {
                splits = budget;
            }
            var reached = new IntArrayList();
            for (var leaf = 0; leaf < leafLights.length; leaf++) {
                if (!leafLights[leaf].isEmpty()) {
                    reached.add(leaf);
                }
                leafLights[leaf].clear();
            }
            return reached.toArray();
        }

        int getLeafAt(float x, float y, float z) {
            return pointLeaf(new Light(0, x, y, z, 0, false));
        }

        int[] getNeighbors(int leaf) {
            var list = leafPortals[leaf];
            var result = new int[list.size()];
            for (var i = 0; i < list.size(); i++) {
                var portal = portals.get(list.get(i));
                result[i] = portal.frontLeaf() == leaf ? portal.backLeaf() : portal.frontLeaf();
            }
            return result;
        }

        private int[] form(IntArrayList slots) {
            var starts = filled(leafLights.length);
            for (var leaf = 0; leaf < leafLights.length; leaf++) {
                var list = leafLights[leaf];
                if (list.isEmpty()) {
                    continue;
                }
                starts[leaf] = slots.size();
                for (var i = list.size() - 1; i >= 0; i--) {
                    slots.add(list.get(i));
                }
                slots.add(0);
            }
            return starts;
        }

        private void makePortals(int node) {
            checkCancelled();
            makePortalsClip(node, buildInfinitePoly(node), 0);
            if (geometry.front()[node] != -1) {
                nodeStack.add(node);
                makePortals(geometry.front()[node]);
                nodeStack.removeAtIndex(nodeStack.size() - 1);
            }
            if (geometry.back()[node] != -1) {
                nodeStack.add(node | BACK_SIDE);
                makePortals(geometry.back()[node]);
                nodeStack.removeAtIndex(nodeStack.size() - 1);
            }
        }

        private void makePortalsClip(int node, Poly poly, int clip) {
            while (clip < nodeStack.size()) {
                var entry = nodeStack.get(clip);
                var clipNode = entry & ~BACK_SIDE;
                var backSide = (entry & BACK_SIDE) != 0;
                if (poly.n >= VERTEX_LIMIT) {
                    var half = new Poly();
                    splitInHalf(poly, half);
                    makePortalsClip(node, half, clip);
                }
                var front = new Poly();
                var back = new Poly();
                var split = splitWithNode(poly, clipNode, front, back);
                if (split == SP_COPLANAR || split == SP_FRONT && backSide || split == SP_BACK && !backSide) {
                    return;
                }
                if (split == SP_SPLIT) {
                    poly = backSide ? back : front;
                }
                clip++;
            }
            filter(0, node, nodeLeaves[node * 2], geometry.back()[node], poly, -1);
        }

        private void filter(int pass, int generating, int parentLeaf, int node, Poly poly, int backLeaf) {
            while (true) {
                if (node == -1) {
                    if (pass == 0) {
                        filter(
                                1,
                                generating,
                                nodeLeaves[generating * 2 + 1],
                                geometry.front()[generating],
                                poly,
                                parentLeaf);
                    } else if (parentLeaf != -1 && backLeaf != -1) {
                        addPortal(poly, parentLeaf, backLeaf);
                    }
                    return;
                }
                if (poly.n > VERTEX_LIMIT) {
                    var half = new Poly();
                    splitInHalf(poly, half);
                    filter(pass, generating, parentLeaf, node, half, backLeaf);
                }
                var front = new Poly();
                var back = new Poly();
                var split = splitWithNode(poly, node, front, back);
                if (split == SP_FRONT || split == SP_SPLIT) {
                    filter(
                            pass,
                            generating,
                            nodeLeaves[node * 2 + 1],
                            geometry.front()[node],
                            split == SP_FRONT ? poly : front,
                            backLeaf);
                }
                if (split != SP_BACK && split != SP_SPLIT) {
                    return;
                }
                if (split == SP_SPLIT) {
                    poly = back;
                }
                parentLeaf = nodeLeaves[node * 2];
                node = geometry.back()[node];
            }
        }

        private void addPortal(Poly poly, int frontLeaf, int backLeaf) {
            var index = portals.size();
            portals.add(new Portal(poly.copy(), frontLeaf, backLeaf));
            leafPortals[frontLeaf].add(index);
            leafPortals[backLeaf].add(index);
        }

        private Poly portalPoly(Portal portal, int leaf) {
            var poly = portal.poly().copy();
            if (leaf == portal.frontLeaf()) {
                poly.reverse();
            }
            return poly;
        }

        private int actorVisibility(Light light, int leaf, int sourceLeaf, @Nullable Poly clip) {
            var count = 0;
            if (leaf == -1) {
                leaf = pointLeaf(light);
                if (leaf == -1) {
                    return 0;
                }
                if (light.sunlight()) {
                    var zone = geometry.leafZones()[leaf];
                    for (var i = 0; i < leafLights.length; i++) {
                        if (geometry.leafZones()[i] == zone) {
                            leafLights[i].add(light.reference());
                            count++;
                        }
                    }
                    return count;
                }
            } else if (!isWithinPortals(light, leaf)) {
                return 0;
            }
            if (!leafLights[leaf].contains(light.reference())) {
                leafLights[leaf].add(light.reference());
                count++;
            }
            var portalList = leafPortals[leaf];
            for (var p = 0; p < portalList.size(); p++) {
                var portal = portals.get(portalList.get(p));
                var poly = portalPoly(portal, leaf);
                var distance = poly.nx * (light.x() - poly.v[0])
                        + poly.ny * (light.y() - poly.v[1])
                        + poly.nz * (light.z() - poly.v[2]);
                if (!(distance < 0f) || !(-light.radius() < distance)) {
                    continue;
                }
                var neighbor = portal.frontLeaf() == leaf ? portal.backLeaf() : portal.frontLeaf();
                if (neighbor == sourceLeaf) {
                    continue;
                }
                if (clip != null && !clipToPrevious(light, clip, poly)) {
                    continue;
                }
                if (poly.n > 0) {
                    count += actorVisibility(light, neighbor, leaf, poly);
                }
            }
            return count;
        }

        private boolean isWithinPortals(Light light, int leaf) {
            var plane = new float[4];
            var portalList = leafPortals[leaf];
            for (var p = 0; p < portalList.size(); p++) {
                var poly = portalPoly(portals.get(portalList.get(p)), leaf);
                var squared = light.radius() * light.radius();
                var w = poly.ny * poly.v[1] + poly.nx * poly.v[0] + poly.nz * poly.v[2];
                if (squared < light.y() * poly.ny + light.x() * poly.nx + light.z() * poly.nz - w) {
                    return false;
                }
                for (int i = 0, previous = poly.n - 1; i < poly.n; previous = i++) {
                    plane(
                            poly.v[previous * 3],
                            poly.v[previous * 3 + 1],
                            poly.v[previous * 3 + 2],
                            poly.v[i * 3],
                            poly.v[i * 3 + 1],
                            poly.v[i * 3 + 2],
                            poly.v[i * 3] + poly.nx,
                            poly.v[i * 3 + 1] + poly.ny,
                            poly.v[i * 3 + 2] + poly.nz,
                            plane);
                    if (squared < planeDot(plane, light.x(), light.y(), light.z())) {
                        return false;
                    }
                }
            }
            return true;
        }

        private boolean clipToPrevious(Light light, Poly clip, Poly poly) {
            var plane = new float[4];
            var front = new Poly();
            var back = new Poly();
            for (int i = 0, previous = clip.n - 1; i < clip.n && poly.n < VERTEX_LIMIT; previous = i++) {
                plane(
                        light.x(),
                        light.y(),
                        light.z(),
                        clip.v[i * 3],
                        clip.v[i * 3 + 1],
                        clip.v[i * 3 + 2],
                        clip.v[previous * 3],
                        clip.v[previous * 3 + 1],
                        clip.v[previous * 3 + 2],
                        plane);
                var split = splitWithPlaneFast(poly, plane, front, back);
                if (split == SP_BACK) {
                    return false;
                }
                if (split == SP_SPLIT) {
                    poly.set(front);
                }
            }
            return true;
        }

        private int pointLeaf(Light light) {
            var planes = geometry.nodePlanes();
            var node = 0;
            var last = 0;
            var side = 0;
            while (node != -1) {
                var p = node * 4;
                var distance =
                        light.y() * planes[p + 1] + light.x() * planes[p] + light.z() * planes[p + 2] - planes[p + 3];
                side = distance > 0f ? 1 : 0;
                last = node;
                node = side == 1 ? geometry.front()[node] : geometry.back()[node];
            }
            return nodeLeaves[last * 2 + side];
        }

        private Poly buildInfinitePoly(int node) {
            var planes = geometry.surfacePlanes();
            var s = geometry.surface()[node] * 4;
            var px = planes[s];
            var py = planes[s + 1];
            var pz = planes[s + 2];
            var pw = planes[s + 3];
            var ox = pw * px;
            var oy = py * pw;
            var oz = pz * pw;
            var axes = findBestAxisVectors(px, py, pz);
            for (var i = 0; i < 6; i++) {
                axes[i] = axes[i] * HALF_WORLD_MAX * INFINITE_SCALE;
            }
            var poly = new Poly();
            poly.nx = px;
            poly.ny = py;
            poly.nz = pz;
            poly.add(axes[0] + ox + axes[3], oy + axes[1] + axes[4], oz + axes[2] + axes[5]);
            poly.add(ox - axes[0] + axes[3], oy - axes[1] + axes[4], oz - axes[2] + axes[5]);
            poly.add(ox - axes[0] - axes[3], oy - axes[1] - axes[4], oz - axes[2] - axes[5]);
            poly.add(axes[0] + ox - axes[3], oy + axes[1] - axes[4], oz + axes[2] - axes[5]);
            return poly;
        }

        private int splitWithNode(Poly poly, int node, Poly front, Poly back) {
            var point = geometry.vertexPoints()[geometry.vertexPool()[node]] * 3;
            var normal = geometry.surfaceNormals()[geometry.surface()[node]] * 3;
            var points = geometry.points();
            var vectors = geometry.vectors();
            return splitWithPlane(
                    poly,
                    points[point],
                    points[point + 1],
                    points[point + 2],
                    vectors[normal],
                    vectors[normal + 1],
                    vectors[normal + 2],
                    front,
                    back);
        }

        private int splitWithPlane(
                Poly poly, float bx, float by, float bz, float nx, float ny, float nz, Poly front, Poly back) {
            countSplit();
            var maxDistance = 0f;
            var minDistance = 0f;
            var distance = 0f;
            var previousStatus = 2;
            for (var i = 0; i < poly.n; i++) {
                distance = distance(poly, i, bx, by, bz, nx, ny, nz);
                if (i == 0 || maxDistance < distance) {
                    maxDistance = distance;
                }
                if (i == 0 || distance < minDistance) {
                    minDistance = distance;
                }
                if (distance > THRESH_PRECISE) {
                    previousStatus = 0;
                } else if (distance < -THRESH_PRECISE) {
                    previousStatus = 1;
                }
            }
            if (maxDistance < THRESH_PRECISE) {
                return minDistance <= -THRESH_PRECISE ? SP_BACK : SP_COPLANAR;
            }
            if (minDistance > -THRESH_PRECISE) {
                return SP_FRONT;
            }
            front.nx = back.nx = poly.nx;
            front.ny = back.ny = poly.ny;
            front.nz = back.nz = poly.nz;
            front.n = 0;
            back.n = 0;
            var previous = poly.n - 1;
            var previousDistance = distance;
            for (var i = 0; i < poly.n; i++) {
                distance = distance(poly, i, bx, by, bz, nx, ny, nz);
                int status;
                if (distance > THRESH_PRECISE) {
                    status = 0;
                } else if (distance < -THRESH_PRECISE) {
                    status = 1;
                } else {
                    status = previousStatus;
                }
                if (status == previousStatus) {
                    (status != 0 ? back : front).addVertex(poly, i);
                } else if (previousDistance < -THRESH_PRECISE || THRESH_PRECISE <= previousDistance) {
                    var px = poly.v[previous * 3];
                    var py = poly.v[previous * 3 + 1];
                    var pz = poly.v[previous * 3 + 2];
                    var dx = poly.v[i * 3] - px;
                    var dy = poly.v[i * 3 + 1] - py;
                    var dz = poly.v[i * 3 + 2] - pz;
                    var t = (ny * (by - py) + nx * (bx - px) + nz * (bz - pz)) / (ny * dy + nx * dx + nz * dz);
                    var ix = px + dx * t;
                    var iy = py + dy * t;
                    var iz = pz + dz * t;
                    front.add(ix, iy, iz);
                    back.add(ix, iy, iz);
                    (previousStatus == 0 ? back : front).addVertex(poly, i);
                } else {
                    var side = status == 0 ? front : back;
                    side.addVertex(poly, previous);
                    side.addVertex(poly, i);
                }
                previous = i;
                previousStatus = status;
                previousDistance = distance;
            }
            if (fix(front) < 3) {
                return SP_BACK;
            }
            if (fix(back) < 3) {
                return SP_FRONT;
            }
            return SP_SPLIT;
        }

        private int splitWithPlaneFast(Poly poly, float[] plane, Poly front, Poly back) {
            countSplit();
            var behind = new boolean[poly.n];
            var hasFront = false;
            var hasBack = false;
            for (var i = 0; i < poly.n; i++) {
                var distance = planeDot(plane, poly.v[i * 3], poly.v[i * 3 + 1], poly.v[i * 3 + 2]);
                if (0f <= distance) {
                    hasFront |= distance > THRESH_FAST;
                } else {
                    behind[i] = true;
                    hasBack |= distance < -THRESH_FAST;
                }
            }
            if (!hasFront) {
                return hasBack ? SP_BACK : SP_COPLANAR;
            }
            if (!hasBack) {
                return SP_FRONT;
            }
            front.nx = back.nx = poly.nx;
            front.ny = back.ny = poly.ny;
            front.nz = back.nz = poly.nz;
            front.n = 0;
            back.n = 0;
            var previous = poly.n - 1;
            for (var i = 0; i < poly.n; i++) {
                if (behind[i] == behind[previous]) {
                    (behind[i] ? back : front).addVertex(poly, i);
                } else {
                    var wx = poly.v[previous * 3];
                    var wy = poly.v[previous * 3 + 1];
                    var wz = poly.v[previous * 3 + 2];
                    var dx = poly.v[i * 3] - wx;
                    var dy = poly.v[i * 3 + 1] - wy;
                    var dz = poly.v[i * 3 + 2] - wz;
                    var t = (plane[3] - (wx * plane[0] + plane[1] * wy + plane[2] * wz))
                            / (plane[0] * dx + plane[1] * dy + plane[2] * dz);
                    var ix = wx + dx * t;
                    var iy = wy + dy * t;
                    var iz = wz + dz * t;
                    back.add(ix, iy, iz);
                    front.add(ix, iy, iz);
                    (behind[previous] ? front : back).addVertex(poly, i);
                }
                previous = i;
            }
            return SP_SPLIT;
        }

        private void countSplit() {
            if (++splits > SPLIT_BUDGET) {
                throw new IllegalStateException("The light build needs more than " + SPLIT_BUDGET + " polygon splits");
            }
            if ((splits & 0xFFFF) == 0) {
                checkCancelled();
            }
        }

        private void checkCancelled() {
            if (cancelled.getAsBoolean()) {
                throw new CancellationException("The light build was cancelled");
            }
        }
    }
}
