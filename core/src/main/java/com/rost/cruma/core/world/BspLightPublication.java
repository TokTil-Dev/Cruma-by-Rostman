package com.rost.cruma.core.world;

import glm_.vec3.Vec3;
import glm_.vec4.Vec4;
import java.util.ArrayDeque;
import java.util.List;

public final class BspLightPublication {
    private static final int[] EMPTY = new int[0];

    private BspLightPublication() {}

    public record Node(Vec4 plane, long zoneMask, int back, int front, int backLeaf, int frontLeaf, int renderBound) {}

    public record Tree(List<Node> nodes, int leafCount) {
        public Tree {
            nodes = List.copyOf(nodes);
            if (leafCount < 0) {
                throw new IllegalArgumentException("negative BSP leaf count");
            }
        }
    }

    public interface Visibility {
        boolean isZoned();

        long getVisibleZoneMask();

        int[] getVisibleZones();

        boolean intersectsBound(int node, int zone);

        default void visitSurface(int node, int[] farSources) {}

        default void visitLeaf(int leaf, int[] publishedSources) {}
    }

    public static final class Result {
        private final int[][] leaves;

        private Result(int leafCount) {
            leaves = new int[leafCount][];
        }

        public int getLeafCount() {
            return leaves.length;
        }

        public int[] getLeaf(int index) {
            return leaves[index] == null ? EMPTY : leaves[index];
        }
    }

    public static Result empty(int leafCount) {
        if (leafCount < 0) {
            throw new IllegalArgumentException("negative BSP leaf count");
        }
        return new Result(leafCount);
    }

    public static Result publish(
            Tree tree,
            List<Light> sources,
            int[] frameSources,
            Vec3 viewOrigin,
            Visibility visibility,
            boolean wire,
            boolean editor) {
        if (frameSources.length > NativeLightCollector.LIMIT) {
            throw new IllegalArgumentException("BSP frame light list exceeds native admission limit");
        }
        var result = new Result(tree.leafCount());
        if (tree.nodes().isEmpty() || wire && editor) {
            return result;
        }
        var pending = new ArrayDeque<Visit>();
        pending.push(new Visit(0, frameSources, Kind.NODE));
        while (!pending.isEmpty()) {
            var visit = pending.pop();
            if (visit.kind() == Kind.LEAF) {
                if (visit.sources().length != 0) {
                    result.leaves[visit.index()] = visit.sources().clone();
                }
                visibility.visitLeaf(visit.index(), result.getLeaf(visit.index()));
                continue;
            }
            if (visit.kind() == Kind.SURFACE) {
                visibility.visitSurface(visit.index(), visit.sources());
                continue;
            }
            var node = tree.nodes().get(visit.index());
            if (!isVisible(node, visit.index(), visibility)) {
                continue;
            }
            var split = BspLightSelection.split(node.plane(), sources, visit.sources());
            var plane = node.plane();
            var distance = viewOrigin.getY() * plane.getY()
                    + plane.getX() * viewOrigin.getX()
                    + viewOrigin.getZ() * plane.getZ()
                    - plane.getW();
            var nearFront = distance > 0f;
            var near = nearFront ? split.front() : split.back();
            var far = nearFront ? split.back() : split.front();
            pushSide(pending, node, !nearFront, far);
            pending.push(new Visit(visit.index(), far, Kind.SURFACE));
            pushSide(pending, node, nearFront, near);
        }
        return result;
    }

    private static boolean isVisible(Node node, int index, Visibility visibility) {
        if (visibility.isZoned() && (visibility.getVisibleZoneMask() & node.zoneMask()) == 0) {
            return false;
        }
        if (node.renderBound() == -1) {
            return true;
        }
        if (!visibility.isZoned()) {
            return visibility.intersectsBound(index, -1);
        }
        for (var zone : visibility.getVisibleZones()) {
            if ((node.zoneMask() & 1L << zone) != 0 && visibility.intersectsBound(index, zone)) {
                return true;
            }
        }
        return false;
    }

    private static void pushSide(ArrayDeque<Visit> pending, Node node, boolean front, int[] sources) {
        var child = front ? node.front() : node.back();
        if (child != -1) {
            pending.push(new Visit(child, sources, Kind.NODE));
        } else {
            var leaf = front ? node.frontLeaf() : node.backLeaf();
            if (leaf != -1) {
                pending.push(new Visit(leaf, sources, Kind.LEAF));
            }
        }
    }

    private enum Kind {
        NODE,
        SURFACE,
        LEAF
    }

    private record Visit(int index, int[] sources, Kind kind) {}
}
