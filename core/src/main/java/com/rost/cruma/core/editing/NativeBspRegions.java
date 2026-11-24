package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.TerrainNativeCodec.Vector;
import com.rost.cruma.core.unreal.Bsp.UModel;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class NativeBspRegions {
    private final List<Node> nodes;
    private final int zoneCount;

    public NativeBspRegions(List<Node> nodes, int zoneCount) {
        if (nodes.size() > 1_000_000 || zoneCount < 0 || zoneCount > 64) {
            throw new IllegalArgumentException("Unqualified BSP region dimensions");
        }
        this.nodes = List.copyOf(nodes);
        this.zoneCount = zoneCount;
        var incoming = new int[nodes.size()];
        for (var node : this.nodes) {
            requireFinite(node.normal());
            var length = node.normal().x() * node.normal().x()
                    + node.normal().y() * node.normal().y()
                    + node.normal().z() * node.normal().z();
            if (!Float.isFinite(node.distance()) || !Float.isFinite(length) || length == 0) {
                throw new IllegalArgumentException("Invalid BSP region plane");
            }
            countIncoming(node.front(), incoming);
            countIncoming(node.back(), incoming);
            requireValidZone(node.frontZone());
            requireValidZone(node.backZone());
        }
        var queue = new int[nodes.size()];
        var head = 0;
        var tail = 0;
        for (var i = 0; i < incoming.length; i++) {
            if (incoming[i] == 0) {
                queue[tail++] = i;
            }
        }
        while (head < tail) {
            var node = this.nodes.get(queue[head++]);
            if (node.front() >= 0 && --incoming[node.front()] == 0) {
                queue[tail++] = node.front();
            }
            if (node.back() >= 0 && --incoming[node.back()] == 0) {
                queue[tail++] = node.back();
            }
        }
        if (head != nodes.size()) {
            throw new IllegalArgumentException("Cyclic BSP region topology");
        }
    }

    public record Node(
            Vector normal,
            float distance,
            int front,
            int back,
            int frontZone,
            int backZone,
            int frontLeaf,
            int backLeaf) {}

    public record Region(int zone, int leaf) {}

    public static NativeBspRegions snapshot(UModel model) {
        Objects.requireNonNull(model);
        NativeBspTrace.requireCompleteModel(model);
        return copy(model);
    }

    public static NativeBspRegions shadowSnapshot(UModel model) {
        Objects.requireNonNull(model);
        NativeBspTrace.requireTraceModel(model);
        return copy(model);
    }

    public Region pointRegion(Vector point) {
        requireFinite(point);
        if (nodes.isEmpty()) {
            return new Region(0, -1);
        }
        var index = 0;
        for (var visited = 0; visited < nodes.size(); visited++) {
            var node = nodes.get(index);
            var distance = node.normal().y() * point.y()
                    + point.x() * node.normal().x()
                    + node.normal().z() * point.z()
                    - node.distance();
            if (!Float.isFinite(distance)) {
                throw new IllegalArgumentException("BSP region query exceeds float range");
            }
            var front = distance >= 0;
            var next = front ? node.front() : node.back();
            if (next == -1) {
                return new Region(
                        zoneCount == 0 ? 0 : front ? node.frontZone() : node.backZone(),
                        front ? node.frontLeaf() : node.backLeaf());
            }
            index = next;
        }
        throw new IllegalArgumentException("BSP region query exceeds topology bound");
    }

    private static NativeBspRegions copy(UModel model) {
        var nodes = new ArrayList<Node>(model.getNodes().length);
        for (var node : model.getNodes()) {
            if (node == null
                    || node.getPlane() == null
                    || node.getZone() == null
                    || node.getLeaf() == null
                    || node.getZone().length != 2
                    || node.getLeaf().length != 2) {
                throw new IllegalArgumentException("Incomplete BSP region node");
            }
            var plane = node.getPlane();
            nodes.add(new Node(
                    new Vector(plane.getX(), plane.getY(), plane.getZ()),
                    plane.getW(),
                    node.getFrontIndex(),
                    node.getBackIndex(),
                    node.getZone()[1],
                    node.getZone()[0],
                    node.getLeaf()[1],
                    node.getLeaf()[0]));
        }
        return new NativeBspRegions(nodes, model.getZones().length);
    }

    private static void countIncoming(int child, int[] incoming) {
        if (child < -1 || child >= incoming.length) {
            throw new IllegalArgumentException("Invalid BSP region child");
        }
        if (child >= 0) {
            incoming[child]++;
        }
    }

    private static void requireValidZone(int zone) {
        if (zone < 0 || zone >= 64) {
            throw new IllegalArgumentException("Invalid BSP region zone");
        }
    }

    private static void requireFinite(Vector point) {
        if (point == null || !Float.isFinite(point.x()) || !Float.isFinite(point.y()) || !Float.isFinite(point.z())) {
            throw new IllegalArgumentException("Non-finite BSP region point");
        }
    }
}
