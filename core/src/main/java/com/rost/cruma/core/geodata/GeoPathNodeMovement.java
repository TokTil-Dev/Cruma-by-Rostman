package com.rost.cruma.core.geodata;

final class GeoPathNodeMovement {
    private static final double DISTANCE = 8192.0;
    private static final int MAX_STEPS = 65536;

    private GeoPathNodeMovement() {}

    static boolean canMove(GeoPathNodeBuild build, int ax, int ay, int az, int bx, int by, int bz) {
        var thread = Thread.currentThread();
        var selected = build.getBase(ax, ay, az);
        if (thread.isInterrupted()) {
            return false;
        }
        var dx = (double) bx - ax;
        var dy = (double) by - ay;
        var horizontalSquared = dx * dx + dy * dy;
        if (horizontalSquared < 1e-6) {
            var target = build.getBase(bx, by, bz);
            return selected != 0 && selected == target && !thread.isInterrupted();
        }
        if (selected == 0) {
            return false;
        }
        var length = Math.sqrt(horizontalSquared);
        var stepX = dx / length * 16.0;
        var stepY = dy / length * 16.0;
        var stepSquared = stepX * stepX + stepY * stepY;
        var xDirection = dx > 0 ? 0 : 1;
        var yDirection = dy > 0 ? 2 : 3;
        var currentX = (double) ax;
        var currentY = (double) ay;
        var traveled = 0.0;
        var reached = false;
        var reduced = false;
        var steps = 0;
        while (traveled <= DISTANCE && traveled != DISTANCE && !reached) {
            if (thread.isInterrupted() || ++steps > MAX_STEPS) {
                return false;
            }
            if (!reduced && DISTANCE - traveled < 16) {
                if (stepSquared / 64 >= 0.5) {
                    stepX *= 0.125;
                    stepY *= 0.125;
                    stepSquared = stepX * stepX + stepY * stepY;
                }
                reduced = true;
            }
            var remainingX = bx - currentX;
            var remainingY = by - currentY;
            double candidateX;
            double candidateY;
            if (stepSquared <= remainingX * remainingX + remainingY * remainingY) {
                candidateX = currentX + stepX;
                candidateY = currentY + stepY;
            } else {
                candidateX = bx;
                candidateY = by;
                reached = true;
                stepSquared = remainingX * remainingX + remainingY * remainingY;
            }
            var oldX = GeoQueryEngine.cellMinimum(currentX);
            var oldY = GeoQueryEngine.cellMinimum(currentY);
            var newX = GeoQueryEngine.cellMinimum(candidateX);
            var newY = GeoQueryEngine.cellMinimum(candidateY);
            var deltaX = Math.abs(newX - oldX);
            var deltaY = Math.abs(newY - oldY);
            var next = selected;
            if (deltaX == 16 && deltaY == 0) {
                next = build.step(selected, xDirection, newX, oldY);
            } else if (deltaX == 0 && deltaY == 16) {
                next = build.step(selected, yDirection, oldX, newY);
            } else if (deltaX == 16 && deltaY == 16) {
                var first = build.step(selected, xDirection, newX, oldY);
                var corner = first == 0 ? 0 : build.step(first, yDirection, newX, newY);
                if (corner == 0) {
                    return false;
                }
                var second = build.step(selected, yDirection, oldX, newY);
                next = second == 0 ? 0 : build.step(second, xDirection, newX, newY);
            } else if (deltaX != 0 || deltaY != 0) {
                return false;
            }
            if (next == 0 || thread.isInterrupted()) {
                return false;
            }
            traveled += Math.sqrt(stepSquared);
            currentX = candidateX;
            currentY = candidateY;
            selected = next;
        }
        if (reached && Math.abs(build.getHeight(selected) - bz) > 48) {
            return false;
        }
        return !thread.isInterrupted();
    }
}
