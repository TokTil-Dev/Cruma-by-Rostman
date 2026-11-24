package com.rost.cruma.core.geodata;

import com.rost.cruma.core.geodata.GeoDataAccess.Storage;
import com.rost.cruma.core.geodata.GeoPathNodeBuild.Region;
import java.io.IOException;

final class GeoPathNodeCandidates {
    private static final int[] DX = {16, -16, 0, 0};
    private static final int[] DY = {0, 0, 16, -16};
    private static final int[] LEFT = {3, 2, 0, 1};
    private static final int[] RIGHT = {2, 3, 1, 0};
    private static final int[] OPPOSITE = {1, 0, 3, 2};
    private static final int MAX_CONTOUR_STEPS = 30000;
    private static final double STRAIGHT_ANGLE_COSINE = Math.cos(2.792526803190927);

    private final GeoPathNodeBuild build;
    private final int[] stack = new int[100000];
    private int stackSize;
    private int work;

    private GeoPathNodeCandidates(GeoPathNodeBuild build) {
        this.build = build;
    }

    static void generate(GeoPathNodeBuild build) throws IOException {
        var candidates = new GeoPathNodeCandidates(build);
        candidates.addContourNodes();
        candidates.addCoverageNodes();
    }

    private void addContourNodes() throws IOException {
        build.checkpoint("Finding contour nodes");
        for (var region : build.regions) {
            var data = region.index();
            for (var cellX = 0; cellX < 2048; cellX++) {
                for (var cellY = 0; cellY < 2048; cellY++) {
                    if (data.getStorage(cellX, cellY) == Storage.FLAT) {
                        var skipped = 8 - (cellY & 7);
                        reportProgress(skipped);
                        cellY += skipped - 1;
                        continue;
                    }
                    reportProgress();
                    var x = region.minX() + cellX * 16;
                    var y = region.minY() + cellY * 16;
                    var count = data.getLayerCount(cellX, cellY);
                    for (var layer = 0; layer < count; layer++) {
                        reportProgress();
                        var reference = build.getLayer(x, y, layer);
                        if (!build.isMarked(reference, 0) && Integer.bitCount(build.getNswe(reference)) == 3) {
                            seedWall(x, y, reference);
                        }
                    }
                    for (var layer = 0; layer < count; layer++) {
                        reportProgress();
                        var reference = build.getLayer(x, y, layer);
                        if (!build.isMarked(reference, 1)) {
                            build.mark(reference, 1);
                            if (build.getNswe(reference) == 15) {
                                seedAsymmetric(x, y, reference);
                            }
                        }
                    }
                }
            }
        }
    }

    private void seedWall(int x, int y, long reference) throws IOException {
        var missing = 0;
        for (var direction = 0; direction < 4; direction++) {
            if (!allows(reference, direction)) {
                missing = direction;
            }
        }
        build.mark(reference, 0);
        var heading = RIGHT[missing];
        for (var reverse = 0; reverse < 2; reverse++) {
            stackSize = 0;
            var next = step(reference, heading, x, y);
            if (next != 0 && Math.abs(build.getHeight(next) - build.getHeight(reference)) < 32) {
                followWall(x + DX[heading], y + DY[heading], next, heading, reverse != 0);
            }
            heading = OPPOSITE[heading];
        }
    }

    private void seedAsymmetric(int x, int y, long reference) throws IOException {
        var mismatches = 0;
        var normal = 0;
        for (var direction = 0; direction < 4; direction++) {
            var neighbor = step(reference, direction, x, y);
            if (isAsymmetric(neighbor, direction)) {
                mismatches++;
                normal = direction;
            }
        }
        if (mismatches != 1) {
            return;
        }
        for (var reverse = 0; reverse < 2; reverse++) {
            stackSize = 0;
            var heading = reverse == 0 ? RIGHT[normal] : LEFT[normal];
            var next = step(reference, heading, x, y);
            if (next != 0 && allows(reference, heading) && allows(next, OPPOSITE[heading])) {
                asymmetricBorder(x + DX[heading], y + DY[heading], next, heading, reverse != 0);
            }
        }
    }

    private void followWall(int x, int y, long reference, int heading, boolean reverse) throws IOException {
        var straight = 0;
        var previousTurn = 0;
        var steps = 0;
        while (true) {
            checkContourProgress(++steps, x, y);
            if (build.isMarked(reference, 0)) {
                return;
            }
            var left = reverse ? RIGHT[heading] : LEFT[heading];
            var right = reverse ? LEFT[heading] : RIGHT[heading];
            if (allows(reference, OPPOSITE[heading])) {
                build.mark(reference, 0);
            }
            var z = build.getHeight(reference);
            if (previousTurn == -1 && allows(reference, left)) {
                addCornerNode(x, y, z, heading, right);
                return;
            }
            final int direction;
            if (allows(reference, left)) {
                addCornerNode(x, y, z, heading, right);
                direction = left;
                straight = 0;
                previousTurn = -1;
            } else if (allows(reference, heading)) {
                if (++straight == 20) {
                    add(x, y, z);
                    straight = 0;
                }
                direction = heading;
                previousTurn = 0;
            } else {
                if (!allows(reference, right)) {
                    return;
                }
                simplifyLast(x + 8, y + 8, z);
                direction = right;
                straight = 0;
                previousTurn = 1;
            }
            var next = step(reference, direction, x, y);
            if (next == 0 || Math.abs(build.getHeight(next) - z) > 32) {
                return;
            }
            x += DX[direction];
            y += DY[direction];
            reference = next;
            heading = direction;
        }
    }

    private void asymmetricBorder(int x, int y, long reference, int heading, boolean reverse) throws IOException {
        var straight = 0;
        var steps = 0;
        while (!build.isMarked(reference, 1)) {
            checkContourProgress(++steps, x, y);
            build.mark(reference, 1);
            var left = reverse ? RIGHT[heading] : LEFT[heading];
            var right = reverse ? LEFT[heading] : RIGHT[heading];
            var neighbor = step(reference, left, x, y);
            if (!isAsymmetric(neighbor, left)) {
                addCornerNode(x, y, build.getHeight(reference), heading, right);
                return;
            }
            neighbor = step(reference, heading, x, y);
            if (!isAsymmetric(neighbor, heading)) {
                if (neighbor == 0) {
                    return;
                }
                if (++straight == 20) {
                    add(x - (int) (DX[right] * -0.4), y - (int) (DY[right] * -0.4), build.getHeight(reference));
                    straight = 0;
                }
            } else {
                neighbor = step(reference, right, x, y);
                if (neighbor == 0 || !allows(reference, right) || !allows(neighbor, OPPOSITE[right])) {
                    return;
                }
                heading = right;
            }
            x += DX[heading];
            y += DY[heading];
            reference = neighbor;
        }
    }

    private void addCornerNode(int x, int y, int z, int heading, int right) throws IOException {
        add(
                x + (int) (DX[heading] * 0.4) - (int) (DX[right] * -0.4),
                y + (int) (DY[heading] * 0.4) - (int) (DY[right] * -0.4),
                z);
    }

    private void add(int x, int y, int z) throws IOException {
        var id = build.nodes.add(x + 8, y + 8, z, 1);
        if (id > 0) {
            stack[stackSize++] = id;
            if (stackSize == stack.length) {
                throw new IOException("Path-node contour stack exceeded 100000 nodes");
            }
        }
    }

    private void simplifyLast(int x, int y, int z) throws IOException {
        if (stackSize < 2) {
            return;
        }
        var last = stack[stackSize - 1];
        var prior = stack[stackSize - 2];
        double ax = build.nodes.getX(prior) - build.nodes.getX(last);
        double ay = build.nodes.getY(prior) - build.nodes.getY(last);
        double az = build.nodes.getZ(prior) - build.nodes.getZ(last);
        double bx = x - build.nodes.getX(last);
        double by = y - build.nodes.getY(last);
        double bz = z - build.nodes.getZ(last);
        var aLength = Math.sqrt(az * az + ay * ay + ax * ax);
        var bLength = Math.sqrt(bz * bz + by * by + bx * bx);
        if (aLength == 0 || bLength == 0) {
            return;
        }
        var priorX = build.nodes.getX(prior);
        var priorY = build.nodes.getY(prior);
        var priorZ = build.nodes.getZ(prior);
        double dx = priorX - x;
        double dy = priorY - y;
        double dz = priorZ - z;
        if (Math.sqrt(dy * dy + dx * dx + dz * dz) <= 128
                && ((by * ay + bx * ax + bz * az) / aLength) / bLength < STRAIGHT_ANGLE_COSINE
                && build.canMove(x, y, z, priorX, priorY, priorZ)
                && build.canMove(priorX, priorY, priorZ, x, y, z)) {
            build.nodes.remove(build.nodes.getX(last), build.nodes.getY(last), build.nodes.getZ(last));
            stackSize--;
        }
    }

    private void addCoverageNodes() throws IOException {
        build.checkpoint("Adding coverage nodes to empty sections");
        for (var region : build.regions) {
            for (var sectionX = 0; sectionX < 2048; sectionX += 16) {
                for (var sectionY = 0; sectionY < 2048; sectionY += 16) {
                    reportProgress();
                    var x = region.minX() + sectionX * 16;
                    var y = region.minY() + sectionY * 16;
                    if (build.nodes.count(x, y) == 0) {
                        addSectionCoverageNode(region, sectionX, sectionY, x, y);
                    }
                }
            }
        }
    }

    private void addSectionCoverageNode(Region region, int sectionX, int sectionY, int x, int y) throws IOException {
        var selectedX = x + 128;
        var selectedY = y + 128;
        var maximumLayers = 1;
        var data = region.index();
        for (var sectorX = sectionX; sectorX < sectionX + 16; sectorX += 8) {
            for (var sectorY = sectionY; sectorY < sectionY + 16; sectorY += 8) {
                if (data.getStorage(sectorX, sectorY) != Storage.MULTI) {
                    continue;
                }
                for (var cellX = sectorX; cellX < sectorX + 8; cellX++) {
                    for (var cellY = sectorY; cellY < sectorY + 8; cellY++) {
                        reportProgress();
                        var count = data.getLayerCount(cellX, cellY);
                        if (count > maximumLayers) {
                            maximumLayers = count;
                            selectedX = region.minX() + cellX * 16;
                            selectedY = region.minY() + cellY * 16;
                        }
                    }
                }
            }
        }
        if (maximumLayers > 1) {
            for (var layer = 0; layer < maximumLayers; layer++) {
                reportProgress();
                var reference = build.getLayer(selectedX, selectedY, layer);
                if (reference == 0) {
                    throw new IOException("Path-node coverage layer is unavailable at " + selectedX + ", " + selectedY);
                }
                build.nodes.add(selectedX, selectedY, build.getHeight(reference), 3);
            }
        } else {
            var reference = build.getBase(selectedX, selectedY, 0);
            if (reference == 0) {
                throw new IOException("Path-node coverage cell is empty at " + selectedX + ", " + selectedY);
            }
            build.nodes.add(selectedX, selectedY, build.getHeight(reference), 3);
        }
    }

    private long step(long reference, int direction, int x, int y) throws IOException {
        return build.step(reference, direction, x + DX[direction], y + DY[direction]);
    }

    private boolean allows(long reference, int direction) {
        return (build.getNswe(reference) & (1 << direction)) != 0;
    }

    private boolean isAsymmetric(long neighbor, int direction) {
        return neighbor != 0 && !allows(neighbor, OPPOSITE[direction]);
    }

    private void checkContourProgress(int steps, int x, int y) throws IOException {
        reportProgress();
        if (steps > MAX_CONTOUR_STEPS) {
            throw new IOException("Path-node contour exceeded 30000 steps at " + x + ", " + y);
        }
    }

    private void reportProgress() throws IOException {
        reportProgress(1);
    }

    private void reportProgress(int amount) throws IOException {
        work += amount;
        if (work >= 4096) {
            work -= 4096;
            build.checkpoint("Finding path-node candidates");
        }
    }
}
