package com.rost.cruma.viewer.render.bgfx;

import com.rost.cruma.core.geometry.Bounds3f;
import com.rost.cruma.core.geometry.Frustum;
import com.rost.cruma.viewer.render.bgfx.BgfxGpuVisibility.Command;
import com.rost.cruma.viewer.render.bgfx.BgfxGpuVisibility.Group;
import java.util.List;

final class BgfxCommandRangeHierarchy {
    static final int ALL_VISIBLE = -1;
    private final int leafCommands;

    private float[] commandMinX = new float[0];
    private float[] commandMinY = new float[0];
    private float[] commandMinZ = new float[0];
    private float[] commandMaxX = new float[0];
    private float[] commandMaxY = new float[0];
    private float[] commandMaxZ = new float[0];
    private int[] commandOrder = new int[0];

    private float[] nodeMinX = new float[0];
    private float[] nodeMinY = new float[0];
    private float[] nodeMinZ = new float[0];
    private float[] nodeMaxX = new float[0];
    private float[] nodeMaxY = new float[0];
    private float[] nodeMaxZ = new float[0];
    private int[] nodeLeft = new int[0];
    private int[] nodeRight = new int[0];
    private int[] nodeFirst = new int[0];
    private int[] nodeCommands = new int[0];
    private int[] groupRoots = new int[0];

    private int commandCount;
    private int groupCount;
    private int nodeCount;
    private int leafCount;
    private int maximumGroupCommands;
    private int maximumDepth;
    private long buildNanos;
    private final Query directQuery = new Query();

    BgfxCommandRangeHierarchy(int leafCommands) {
        if (leafCommands < 2 || leafCommands > 32 || Integer.bitCount(leafCommands) != 1) {
            throw new IllegalArgumentException("leaf command count must be a power of two in [2, 32]");
        }
        this.leafCommands = leafCommands;
    }

    static BgfxCommandRangeHierarchy fromBounds(int leafCommands, List<Bounds3f> bounds, int... groupCommandCounts) {
        if (bounds == null || groupCommandCounts == null) {
            throw new IllegalArgumentException("command hierarchy inputs cannot be null");
        }
        var hierarchy = new BgfxCommandRangeHierarchy(leafCommands);
        hierarchy.beginBuild(bounds.size(), groupCommandCounts.length);
        for (var index = 0; index < bounds.size(); index++) {
            hierarchy.putCommandBounds(index, bounds.get(index));
        }
        var first = 0;
        for (var group = 0; group < groupCommandCounts.length; group++) {
            var count = groupCommandCounts[group];
            if (count < 1 || first > bounds.size() - count) {
                throw new IllegalArgumentException("invalid command hierarchy group partition");
            }
            hierarchy.buildGroup(group, first, count);
            first = Math.addExact(first, count);
        }
        if (first != bounds.size()) {
            throw new IllegalArgumentException("command hierarchy groups do not cover bounds");
        }
        hierarchy.finishBuild();
        return hierarchy;
    }

    void rebuild(List<Command> commands, List<Group> groups) {
        if (commands == null || groups == null) {
            throw new IllegalArgumentException("command hierarchy inputs cannot be null");
        }
        beginBuild(commands.size(), groups.size());
        for (var index = 0; index < commands.size(); index++) {
            putCommandBounds(index, commands.get(index).worldBounds());
        }
        var expectedFirst = 0;
        for (var groupIndex = 0; groupIndex < groups.size(); groupIndex++) {
            var group = groups.get(groupIndex);
            if (group.groupIndex() != groupIndex
                    || group.firstCommand() != expectedFirst
                    || group.commandCount() < 1
                    || group.firstCommand() > commands.size() - group.commandCount()) {
                throw new IllegalArgumentException("command groups must partition topology in index order");
            }
            buildGroup(groupIndex, group.firstCommand(), group.commandCount());
            expectedFirst = Math.addExact(expectedFirst, group.commandCount());
        }
        if (expectedFirst != commands.size()) {
            throw new IllegalArgumentException("command groups do not cover topology");
        }
        finishBuild();
    }

    double getMinimumGroupDistanceSquared(int group, float x, float y, float z) {
        if (group < 0 || group >= groupCount) {
            throw new IllegalArgumentException("invalid command group");
        }
        var root = groupRoots[group];
        var first = nodeFirst[root];
        var limit = first + nodeCommands[root];
        var minimum = Double.POSITIVE_INFINITY;
        for (var command = first; command < limit; command++) {
            var dx = x < commandMinX[command]
                    ? commandMinX[command] - x
                    : x > commandMaxX[command] ? x - commandMaxX[command] : 0f;
            var dy = y < commandMinY[command]
                    ? commandMinY[command] - y
                    : y > commandMaxY[command] ? y - commandMaxY[command] : 0f;
            var dz = z < commandMinZ[command]
                    ? commandMinZ[command] - z
                    : z > commandMaxZ[command] ? z - commandMaxZ[command] : 0f;
            minimum = Math.min(minimum, (double) dx * dx + (double) dy * dy + (double) dz * dz);
        }
        return minimum;
    }

    int collectVisibleCommands(int group, Frustum frustum, int[] visibleCommands, int[] traversalStack) {
        return collectVisibleCommands(group, frustum, visibleCommands, traversalStack, directQuery);
    }

    int collectVisibleCommands(int group, Frustum frustum, int[] visibleCommands, int[] traversalStack, Query query) {
        if (group < 0
                || group >= groupCount
                || frustum == null
                || visibleCommands == null
                || visibleCommands.length < maximumGroupCommands
                || traversalStack == null
                || traversalStack.length < maximumDepth + 1
                || query == null) {
            throw new IllegalArgumentException("invalid command hierarchy traversal state");
        }
        var visitedNodes = 0;
        var testedCommands = 0;
        var root = groupRoots[group];
        var rootClassification = classify(frustum, root);
        visitedNodes++;
        if (rootClassification == Frustum.OUTSIDE) {
            return query.finish(0, visitedNodes, testedCommands);
        }
        if (rootClassification == Frustum.INSIDE) {
            return query.finish(ALL_VISIBLE, visitedNodes, testedCommands);
        }

        var stackSize = 0;
        traversalStack[stackSize++] = root;
        var visibleCount = 0;
        while (stackSize > 0) {
            var node = traversalStack[--stackSize];
            var classification = node == root ? rootClassification : classify(frustum, node);
            if (node != root) {
                visitedNodes++;
            }
            if (classification == Frustum.OUTSIDE) {
                continue;
            }
            if (classification == Frustum.INSIDE) {
                var first = nodeFirst[node];
                var end = first + nodeCommands[node];
                for (var offset = first; offset < end; offset++) {
                    visibleCommands[visibleCount++] = commandOrder[offset];
                }
                continue;
            }
            if (nodeLeft[node] < 0) {
                var first = nodeFirst[node];
                var end = first + nodeCommands[node];
                for (var offset = first; offset < end; offset++) {
                    var command = commandOrder[offset];
                    testedCommands++;
                    if (frustum.intersects(
                            commandMinX[command],
                            commandMinY[command],
                            commandMinZ[command],
                            commandMaxX[command],
                            commandMaxY[command],
                            commandMaxZ[command])) {
                        visibleCommands[visibleCount++] = command;
                    }
                }
                continue;
            }
            traversalStack[stackSize++] = nodeRight[node];
            traversalStack[stackSize++] = nodeLeft[node];
        }
        return query.finish(
                visibleCount == nodeCommands[root] ? ALL_VISIBLE : visibleCount, visitedNodes, testedCommands);
    }

    int getMaximumGroupCommands() {
        return maximumGroupCommands;
    }

    int getMaximumDepth() {
        return maximumDepth;
    }

    int getNodeCount() {
        return nodeCount;
    }

    int getLeafCount() {
        return leafCount;
    }

    int getLastVisitedNodes() {
        return directQuery.visitedNodes;
    }

    int getLastTestedCommands() {
        return directQuery.testedCommands;
    }

    long getBuildNanos() {
        return buildNanos;
    }

    static final class Query {
        int visitedNodes;
        int testedCommands;

        private int finish(int visible, int visited, int tested) {
            visitedNodes = visited;
            testedCommands = tested;
            return visible;
        }
    }

    long getRetainedBytes() {
        return (long) commandMinX.length * Float.BYTES * 6L
                + (long) commandOrder.length * Integer.BYTES
                + (long) nodeMinX.length * Float.BYTES * 6L
                + (long) nodeLeft.length * Integer.BYTES * 4L
                + (long) groupRoots.length * Integer.BYTES;
    }

    private void beginBuild(int commands, int groups) {
        if (commands < 1 || groups < 1 || groups > commands) {
            throw new IllegalArgumentException("invalid command hierarchy dimensions");
        }
        var started = System.nanoTime();
        ensureCommandCapacity(commands);
        ensureNodeCapacity(Math.multiplyExact(commands, 2));
        ensureGroupCapacity(groups);
        commandCount = commands;
        groupCount = groups;
        nodeCount = 0;
        leafCount = 0;
        maximumGroupCommands = 0;
        maximumDepth = 0;
        buildNanos = -started;
    }

    private void finishBuild() {
        buildNanos += System.nanoTime();
        buildNanos = Math.max(0L, buildNanos);
    }

    private void putCommandBounds(int command, Bounds3f bounds) {
        if (bounds == null) {
            throw new IllegalArgumentException("command bounds cannot be null");
        }
        commandMinX[command] = bounds.minX();
        commandMinY[command] = bounds.minY();
        commandMinZ[command] = bounds.minZ();
        commandMaxX[command] = bounds.maxX();
        commandMaxY[command] = bounds.maxY();
        commandMaxZ[command] = bounds.maxZ();
    }

    private void buildGroup(int group, int firstCommand, int commands) {
        maximumGroupCommands = Math.max(maximumGroupCommands, commands);
        var end = Math.addExact(firstCommand, commands);
        for (var command = firstCommand; command < end; command++) {
            commandOrder[command] = command;
        }
        groupRoots[group] = buildNode(firstCommand, commands, 1);
    }

    private int buildNode(int first, int commands, int depth) {
        var node = nodeCount++;
        maximumDepth = Math.max(maximumDepth, depth);
        nodeFirst[node] = first;
        nodeCommands[node] = commands;

        var minimumX = Float.POSITIVE_INFINITY;
        var minimumY = Float.POSITIVE_INFINITY;
        var minimumZ = Float.POSITIVE_INFINITY;
        var maximumX = Float.NEGATIVE_INFINITY;
        var maximumY = Float.NEGATIVE_INFINITY;
        var maximumZ = Float.NEGATIVE_INFINITY;
        var centroidMinimumX = Float.POSITIVE_INFINITY;
        var centroidMinimumY = Float.POSITIVE_INFINITY;
        var centroidMinimumZ = Float.POSITIVE_INFINITY;
        var centroidMaximumX = Float.NEGATIVE_INFINITY;
        var centroidMaximumY = Float.NEGATIVE_INFINITY;
        var centroidMaximumZ = Float.NEGATIVE_INFINITY;
        var end = Math.addExact(first, commands);
        for (var offset = first; offset < end; offset++) {
            var command = commandOrder[offset];
            minimumX = Math.min(minimumX, commandMinX[command]);
            minimumY = Math.min(minimumY, commandMinY[command]);
            minimumZ = Math.min(minimumZ, commandMinZ[command]);
            maximumX = Math.max(maximumX, commandMaxX[command]);
            maximumY = Math.max(maximumY, commandMaxY[command]);
            maximumZ = Math.max(maximumZ, commandMaxZ[command]);
            var centerX = center(commandMinX[command], commandMaxX[command]);
            var centerY = center(commandMinY[command], commandMaxY[command]);
            var centerZ = center(commandMinZ[command], commandMaxZ[command]);
            centroidMinimumX = Math.min(centroidMinimumX, centerX);
            centroidMinimumY = Math.min(centroidMinimumY, centerY);
            centroidMinimumZ = Math.min(centroidMinimumZ, centerZ);
            centroidMaximumX = Math.max(centroidMaximumX, centerX);
            centroidMaximumY = Math.max(centroidMaximumY, centerY);
            centroidMaximumZ = Math.max(centroidMaximumZ, centerZ);
        }
        nodeMinX[node] = minimumX;
        nodeMinY[node] = minimumY;
        nodeMinZ[node] = minimumZ;
        nodeMaxX[node] = maximumX;
        nodeMaxY[node] = maximumY;
        nodeMaxZ[node] = maximumZ;

        if (commands <= leafCommands) {
            nodeLeft[node] = -1;
            nodeRight[node] = -1;
            leafCount++;
            return node;
        }
        var extentX = centroidMaximumX - centroidMinimumX;
        var extentY = centroidMaximumY - centroidMinimumY;
        var extentZ = centroidMaximumZ - centroidMinimumZ;
        var axis = extentY > extentX ? 1 : 0;
        if (extentZ > (axis == 0 ? extentX : extentY)) {
            axis = 2;
        }
        var middle = first + commands / 2;
        selectMedian(first, end, middle, axis);
        nodeLeft[node] = buildNode(first, middle - first, depth + 1);
        nodeRight[node] = buildNode(middle, end - middle, depth + 1);
        return node;
    }

    private void selectMedian(int first, int end, int median, int axis) {
        var left = first;
        var right = end - 1;
        while (left < right) {
            var pivot = commandCenter(commandOrder[(left + right) >>> 1], axis);
            var low = left;
            var high = right;
            while (low <= high) {
                while (low <= high && commandCenter(commandOrder[low], axis) < pivot) {
                    low++;
                }
                while (low <= high && commandCenter(commandOrder[high], axis) > pivot) {
                    high--;
                }
                if (low <= high) {
                    var swap = commandOrder[low];
                    commandOrder[low++] = commandOrder[high];
                    commandOrder[high--] = swap;
                }
            }
            if (median <= high) {
                right = high;
            } else if (median >= low) {
                left = low;
            } else {
                return;
            }
        }
    }

    private float commandCenter(int command, int axis) {
        return switch (axis) {
            case 0 -> center(commandMinX[command], commandMaxX[command]);
            case 1 -> center(commandMinY[command], commandMaxY[command]);
            case 2 -> center(commandMinZ[command], commandMaxZ[command]);
            default -> throw new IllegalArgumentException("invalid hierarchy split axis");
        };
    }

    private int classify(Frustum frustum, int node) {
        return frustum.classify(
                nodeMinX[node], nodeMinY[node], nodeMinZ[node], nodeMaxX[node], nodeMaxY[node], nodeMaxZ[node]);
    }

    private void ensureCommandCapacity(int required) {
        if (commandOrder.length >= required) {
            return;
        }
        var capacity = grownCapacity(commandOrder.length, required);
        commandMinX = new float[capacity];
        commandMinY = new float[capacity];
        commandMinZ = new float[capacity];
        commandMaxX = new float[capacity];
        commandMaxY = new float[capacity];
        commandMaxZ = new float[capacity];
        commandOrder = new int[capacity];
    }

    private void ensureNodeCapacity(int required) {
        if (nodeLeft.length >= required) {
            return;
        }
        var capacity = grownCapacity(nodeLeft.length, required);
        nodeMinX = new float[capacity];
        nodeMinY = new float[capacity];
        nodeMinZ = new float[capacity];
        nodeMaxX = new float[capacity];
        nodeMaxY = new float[capacity];
        nodeMaxZ = new float[capacity];
        nodeLeft = new int[capacity];
        nodeRight = new int[capacity];
        nodeFirst = new int[capacity];
        nodeCommands = new int[capacity];
    }

    private void ensureGroupCapacity(int required) {
        if (groupRoots.length < required) {
            groupRoots = new int[grownCapacity(groupRoots.length, required)];
        }
    }

    private static float center(float minimum, float maximum) {
        return minimum * 0.5f + maximum * 0.5f;
    }

    private static int grownCapacity(int current, int required) {
        var capacity = Math.max(16, current);
        while (capacity < required) {
            if (capacity > Integer.MAX_VALUE / 2) {
                return required;
            }
            capacity <<= 1;
        }
        return capacity;
    }
}
