package com.rost.cruma.core.geodata.generation;

import com.rost.cruma.core.geodata.generation.CollisionWorld.Hit;
import com.rost.cruma.core.geodata.generation.CollisionWorld.Query;

public final class ScoutMover {
    public static final int BLOCKED = 0;
    public static final int MOVED = 1;
    public static final int FELL_OUT = 2;
    public static final int FREE = 5;
    private static final int MAXIMUM_FALL = 10000;

    private boolean fellOut;
    private boolean skipFreeFalls = true;

    private final Query query;
    private final float radius;
    private final float halfHeight;
    private final Hit sweepHit = new Hit();
    private final Hit hit = new Hit();

    public ScoutMover(CollisionWorld world, float radius, float halfHeight) {
        this.query = world.query();
        this.radius = radius;
        this.halfHeight = halfHeight;
    }

    public Hit getHit() {
        return hit;
    }

    public int move(float[] location, float dx, float dy, float dz) {
        if (dx == 0 && dy == 0 && dz == 0) {
            hit.time = 0;
            return BLOCKED;
        }
        var size = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        var inverse = 1f / size;
        var directionX = dx * inverse;
        var directionY = dy * inverse;
        var directionZ = dz * inverse;
        var testX = dx + directionX * 2f;
        var testY = dy + directionY * 2f;
        var testZ = dz + directionZ * 2f;
        if (query.sweep(
                        location[0],
                        location[1],
                        location[2],
                        testX,
                        testY,
                        testZ,
                        radius,
                        radius,
                        halfHeight,
                        sweepHit)
                && sweepHit.time < 1) {
            var time = (float) sweepHit.time;
            var distance = (size + 2f) * time;
            if (distance <= 2f) {
                hit.time = 0;
            } else {
                location[0] += time * testX - directionX * 2f;
                location[1] += time * testY - directionY * 2f;
                location[2] += time * testZ - directionZ * 2f;
                hit.time = (distance - 2f) / size;
            }
            hit.normalX = sweepHit.normalX;
            hit.normalY = sweepHit.normalY;
            hit.normalZ = sweepHit.normalZ;
            hit.kind = sweepHit.kind;
            return BLOCKED;
        }
        location[0] += dx;
        location[1] += dy;
        location[2] += dz;
        hit.time = 1;
        return FREE;
    }

    public int walk(float[] location, float dx, float dy, float dz, float minimumDistance, float stepScale) {
        var startX = location[0];
        var startY = location[1];
        var step = stepScale * 10f;
        if (move(location, dx, dy, dz) == BLOCKED) {
            var savedX = location[0];
            var savedY = location[1];
            var savedZ = location[2];
            var rest = 1f - (float) hit.time;
            move(location, 0, 0, step);
            move(location, dx * rest, dy * rest, dz * rest);
            if (move(location, 0, 0, -step) == BLOCKED && hit.normalZ < 0.7f) {
                location[0] = savedX;
                location[1] = savedY;
                location[2] = savedZ;
                return BLOCKED;
            }
        }
        var drop = -(step + 2f);
        move(location, 0, 0, drop);
        var left = MAXIMUM_FALL;
        var after = left;
        while (hit.time == 1) {
            var free = skipFreeFalls ? Math.min(freeFalls(location, drop, left + 1), left + 1) - 1 : 0;
            for (var i = 0; i < free; i++) {
                location[2] += drop;
            }
            if (free > 0) {
                left -= free;
            }
            after = left - 1;
            if (left < 0) {
                break;
            }
            move(location, 0, 0, drop);
            left = after;
            after = left;
        }
        if (after > -1) {
            var movedX = startX - location[0];
            var movedY = startY - location[1];
            return movedX * movedX + movedY * movedY < minimumDistance * minimumDistance ? BLOCKED : MOVED;
        }
        fellOut = true;
        return FELL_OUT;
    }

    public boolean reach(float[] location, float targetX, float targetY, float stepScale) {
        fellOut = false;
        var reached = false;
        var result = MOVED;
        var left = 100;
        while (result == MOVED) {
            var dx = targetX - location[0];
            var dy = targetY - location[1];
            float minimumDistance;
            var lengthSquared = dx * dx + dy * dy;
            if (lengthSquared >= 100f) {
                var inverse = 1f / (float) Math.sqrt(lengthSquared);
                dx = dx * inverse * 10f;
                dy = dy * inverse * 10f;
                minimumDistance = 4.1f;
            } else {
                minimumDistance = 0.6f;
            }
            result = walk(location, dx, dy, 0, minimumDistance, stepScale);
            if (result == BLOCKED) {
                var restX = location[0] - targetX;
                var restY = location[1] - targetY;
                reached = restY * restY + restX * restX < 0.36f;
            }
            left--;
            if (left < 0) {
                result = BLOCKED;
            }
        }
        return reached;
    }

    public boolean hasFallenOut() {
        return fellOut;
    }

    void skipFreeFalls(boolean skip) {
        skipFreeFalls = skip;
    }

    private int freeFalls(float[] location, float drop, int moves) {
        if (moves <= 2) {
            return 0;
        }
        var length = -drop * (double) moves + 2;
        if (!query.sweepToContact(
                location[0], location[1], location[2], 0, 0, -length, radius, radius, halfHeight, sweepHit)) {
            return moves;
        }
        var contact = sweepHit.time * length;
        var free = (int) Math.floor((contact + drop - 2) / -drop) - 1;
        return Math.max(0, Math.min(moves, free));
    }
}
