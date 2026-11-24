package com.rost.cruma.viewer.ui;

import glm_.vec3.Vec3;
import java.util.List;
import org.jetbrains.annotations.Nullable;

final class SkillProjectile {
    static final float STEP = 1f / 60f;

    static final float LONGEST_SECONDS = 100f;

    private static final float CURVE_SPEED = .1f;
    private static final float CURVE_ACCELERATION = 450f;

    record Bend(float share, float side, float up) {
        static final Bend RETURN = new Bend(.05f, 0f, 60f);

        static Bend drain(boolean first) {
            return new Bend(-.2f, first ? 30f : -30f, 30f);
        }
    }

    private Vec3 place;
    private Vec3 velocity = new Vec3();
    private Vec3 acceleration = new Vec3();
    private float speed;
    private final float accelerationSize;
    private final @Nullable Bend bend;
    private Vec3 origin = new Vec3();
    private List<Vec3> path = List.of();
    private Vec3 point = new Vec3();
    private float pointSpeed;
    private int index;
    private boolean straight;
    private boolean arrived;

    private @Nullable Curve curve;
    private float duration;
    private float time;
    private Vec3 start = new Vec3();
    private Vec3 leaving = new Vec3();
    private Vec3 arriving = new Vec3();
    private float way;
    private Vec3[] points = new Vec3[0];

    private enum Curve {
        HERMITE,
        BEZIER
    }

    private SkillProjectile(Vec3 from, float speed, float acceleration, @Nullable Bend bend) {
        this.place = from;
        this.speed = speed;
        this.accelerationSize = acceleration;
        this.bend = bend;
        this.straight = bend == null;
    }

    static SkillProjectile toTarget(Vec3 from, float speed, float acceleration) {
        return new SkillProjectile(from, speed, acceleration, null);
    }

    static SkillProjectile curved(Vec3 from, Vec3 start, Vec3 end, Bend bend) {
        var shot = new SkillProjectile(from, CURVE_SPEED, CURVE_ACCELERATION, bend);
        shot.path = points(start, end, bend);
        shot.origin = shot.path.getFirst();
        shot.point = shot.origin;
        shot.pointSpeed = CURVE_SPEED;
        return shot;
    }

    static float flightSeconds(SkillProjectile shot, Vec3 aim) {
        var steps = 0;
        while (!shot.step(aim) && steps * STEP < LONGEST_SECONDS) {
            steps++;
        }
        return (steps + 1) * STEP;
    }

    static SkillProjectile hermite(Vec3 from, Vec3 casterPlace, Vec3 aim, Vec3 leaving, float duration) {
        var shot = new SkillProjectile(from, 0f, 0f, null);
        var toTarget = minus(aim, casterPlace);
        shot.curve = Curve.HERMITE;
        shot.duration = duration;
        shot.start = from;
        shot.way = length(toTarget);
        shot.arriving = scaled(normal(minus(normal(toTarget), new Vec3(0f, 0f, .3f))), shot.way / 1.5f);
        shot.leaving = scaled(normal(leaving), shot.way / 1.5f);
        return shot;
    }

    static SkillProjectile bezier(Vec3 from, Vec3 end, List<Vec3> offsets, float duration) {
        var shot = new SkillProjectile(from, 0f, 0f, null);
        shot.curve = Curve.BEZIER;
        shot.duration = duration;
        var way = minus(end, from);
        var middle = scaled(plus(from, end), .5f);
        var scale = (float) Math.hypot(way.getX(), way.getY()) / 10f;
        var yaw = Math.atan2(way.getY(), way.getX());
        var pitch = Math.atan2(way.getZ(), Math.hypot(way.getX(), way.getY()));
        var forward =
                new Vec3((float) (Math.cos(pitch) * Math.cos(yaw)), (float) (Math.cos(pitch) * Math.sin(yaw)), (float)
                        Math.sin(pitch));
        var right = new Vec3((float) -Math.sin(yaw), (float) Math.cos(yaw), 0f);
        var up =
                new Vec3((float) (-Math.sin(pitch) * Math.cos(yaw)), (float) (-Math.sin(pitch) * Math.sin(yaw)), (float)
                        Math.cos(pitch));
        var points = new Vec3[offsets.size() + 2];
        points[0] = from;
        for (var i = 0; i < offsets.size(); i++) {
            var offset = scaled(offsets.get(i), scale);
            points[i + 1] = plus(
                    middle,
                    plus(
                            scaled(forward, offset.getX()),
                            plus(scaled(right, offset.getY()), scaled(up, offset.getZ()))));
        }
        points[points.length - 1] = end;
        shot.points = points;
        return shot;
    }

    Vec3 getPlace() {
        return place;
    }

    boolean hasArrived() {
        return arrived;
    }

    @Nullable
    Vec3 getTurn() {
        if (length(velocity) == 0f) {
            return null;
        }
        var flat = (float) Math.hypot(velocity.getX(), velocity.getY());
        var pitch = (float) Math.atan2(velocity.getZ(), flat);
        var yaw = (float) Math.atan2(velocity.getY(), velocity.getX());
        return new Vec3(0f, -pitch, yaw);
    }

    boolean step(Vec3 aim) {
        if (arrived) {
            return true;
        }
        var before = place;
        if (curve != null) {
            place = onCurve(aim);
            velocity = scaled(minus(place, before), 1f / STEP);
        } else {
            velocity = plus(velocity, scaled(acceleration, STEP));
            place = plus(place, scaled(velocity, STEP));
        }
        if (length(minus(place, before)) >= length(minus(aim, before))) {
            place = aim;
            arrived = true;
            return true;
        }
        if (curve != null) {
            return false;
        }
        var way = straight ? minus(aim, place) : alongSpline(aim);
        var direction = normal(way);
        var size = length(velocity);
        if (size != 0f) {
            speed = size;
        }
        velocity = scaled(direction, speed);
        acceleration = scaled(direction, accelerationSize);
        return false;
    }

    private Vec3 onCurve(Vec3 aim) {
        time += STEP;
        if (duration <= 0f) {
            return aim;
        }
        var share = time / duration;
        if (curve == Curve.HERMITE) {
            var now = length(minus(aim, start));
            if (way > 0f) {
                leaving = scaled(leaving, now / way);
                arriving = scaled(arriving, now / way);
            }
            way = now;
            var t2 = share * share;
            var t3 = t2 * share;
            return plus(
                    plus(scaled(start, 2 * t3 - 3 * t2 + 1), scaled(leaving, t3 - 2 * t2 + share)),
                    plus(scaled(aim, -2 * t3 + 3 * t2), scaled(arriving, t3 - t2)));
        }
        points[points.length - 1] = aim;
        if (share >= 1f) {
            return aim;
        }
        var level = points.clone();
        for (var count = level.length - 1; count > 0; count--) {
            for (var i = 0; i < count; i++) {
                level[i] = plus(scaled(level[i], 1 - share), scaled(level[i + 1], share));
            }
        }
        return level[0];
    }

    private Vec3 alongSpline(Vec3 end) {
        if (index >= path.size() - 1) {
            straight = true;
            return minus(end, place);
        }
        var segment = length(minus(path.get(index + 1), path.get(index)));
        var share = segment > 0f ? length(minus(point, path.get(index))) / segment : 0f;
        path = points(origin, end, bend);
        var from = path.get(index);
        var to = path.get(index + 1);
        point = plus(from, scaled(minus(to, from), share));
        var moved = scaled(normal(minus(to, from)), pointSpeed + CURVE_ACCELERATION * STEP);
        var before = point;
        point = plus(point, scaled(moved, STEP));
        if (length(minus(to, before)) <= length(minus(point, before))) {
            index++;
        }
        pointSpeed = length(moved);
        return minus(point, place);
    }

    private static List<Vec3> points(Vec3 start, Vec3 end, @Nullable Bend bend) {
        if (bend == null) {
            return List.of(end);
        }
        var along = plus(start, scaled(minus(end, start), bend.share()));
        return List.of(plus(sideways(start, along, bend.side()), new Vec3(0f, 0f, bend.up())), end);
    }

    private static Vec3 sideways(Vec3 start, Vec3 point, float side) {
        var angle = Math.atan2(point.getY() - start.getY(), point.getX() - start.getX());
        return new Vec3(
                point.getX() + (float) Math.sin(angle) * side,
                point.getY() - (float) Math.cos(angle) * side,
                point.getZ());
    }

    private static Vec3 plus(Vec3 a, Vec3 b) {
        return new Vec3(a.getX() + b.getX(), a.getY() + b.getY(), a.getZ() + b.getZ());
    }

    private static Vec3 minus(Vec3 a, Vec3 b) {
        return new Vec3(a.getX() - b.getX(), a.getY() - b.getY(), a.getZ() - b.getZ());
    }

    private static Vec3 scaled(Vec3 a, float by) {
        return new Vec3(a.getX() * by, a.getY() * by, a.getZ() * by);
    }

    private static float length(Vec3 a) {
        return (float) Math.sqrt(a.getX() * a.getX() + a.getY() * a.getY() + a.getZ() * a.getZ());
    }

    private static Vec3 normal(Vec3 a) {
        var size = length(a);
        return size < 1e-4f ? new Vec3() : scaled(a, 1f / size);
    }
}
