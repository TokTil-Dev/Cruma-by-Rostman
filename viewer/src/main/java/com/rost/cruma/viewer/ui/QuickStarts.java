package com.rost.cruma.viewer.ui;

import com.rost.cruma.core.world.CameraPath;
import com.rost.cruma.core.world.CameraPath.Ease;
import com.rost.cruma.core.world.CameraPath.Key;
import com.rost.cruma.core.world.CameraPath.Pose;
import com.rost.cruma.core.world.MapScenes.Vector;
import com.rost.cruma.core.world.SceneDocument;
import java.util.ArrayList;
import java.util.List;

public final class QuickStarts {
    public static final int KEYS_A_TURN = 8;

    public static final int LEAST_KEYS = 3;

    public static final double LEAST_RADIUS = 1;

    public static final double POINT_LIFT = 60;

    private static final double PLACE_STEPS = 100;
    private static final double ANGLE_STEPS = 1_000;

    private QuickStarts() {}

    public enum Direction {
        LEFT,
        RIGHT
    }

    public static int orbitKeys(double turns) {
        return Math.max(LEAST_KEYS, (int) Math.ceil(KEYS_A_TURN * turns - 1e-9));
    }

    public static double radius(Pose view, Vector center) {
        return Math.hypot(view.x() - center.x(), view.y() - center.y());
    }

    public static List<Key> orbit(
            Pose view, Vector center, int lookAt, double turns, Direction direction, double seconds) {
        var radius = radius(view, center);
        if (radius < LEAST_RADIUS || turns <= 0) {
            return List.of();
        }
        var count = orbitKeys(turns);
        var first = Math.atan2(view.y() - center.y(), view.x() - center.x());
        var sign = direction == Direction.LEFT ? 1 : -1;
        var keys = new ArrayList<Key>(count);
        for (var index = 0; index < count; index++) {
            var share = index / (double) (count - 1);
            var angle = first + sign * share * turns * 2 * Math.PI;
            var x = center.x() + radius * Math.cos(angle);
            var y = center.y() + radius * Math.sin(angle);
            var key = new Key(
                    share * seconds,
                    x,
                    y,
                    view.z(),
                    yawTo(x, y, center),
                    pitchTo(x, y, view.z(), center),
                    view.roll(),
                    view.fov(),
                    Ease.SMOOTH);
            keys.add(lookAt > 0 ? key.withLookAt(lookAt) : key);
        }
        return keys;
    }

    public static Vector overFloor(Vector floor) {
        return new Vector(floor.x(), floor.y(), floor.z() + POINT_LIFT);
    }

    public static List<Key> flyTo(Pose from, Pose to, double seconds) {
        return List.of(key(0, from), key(seconds, to));
    }

    public static List<Key> reveal(Pose view, double distance, double rise, double seconds) {
        var yaw = Math.toRadians(view.yaw());
        var back = new Pose(
                view.x() - distance * Math.cos(yaw),
                view.y() - distance * Math.sin(yaw),
                view.z() + rise,
                view.yaw(),
                view.pitch(),
                view.roll(),
                view.fov());
        return List.of(key(0, view), key(seconds, back));
    }

    public static SceneDocument withShot(SceneDocument document, List<Key> made, double start) {
        var path = document.path();
        var keys = new ArrayList<Key>(path == null ? List.of() : path.getKeys());
        var size = keys.size();
        if (size >= 2 && keys.getLast().seconds() == start && keys.get(size - 2).seconds() == start) {
            keys.removeLast();
        }
        for (var key : made) {
            keys.add(new Key(
                    start + key.seconds(),
                    rounded(key.x(), PLACE_STEPS),
                    rounded(key.y(), PLACE_STEPS),
                    rounded(key.z(), PLACE_STEPS),
                    rounded(key.yaw(), ANGLE_STEPS),
                    rounded(key.pitch(), ANGLE_STEPS),
                    rounded(key.roll(), ANGLE_STEPS),
                    rounded(key.fov(), ANGLE_STEPS),
                    key.ease(),
                    key.lookAt()));
        }
        var end = keys.getLast().seconds();
        return document.withPath(new CameraPath(keys, false)).withSeconds(Math.max(document.seconds(), end));
    }

    private static double rounded(double value, double steps) {
        return Math.round(value * steps) / steps;
    }

    private static double yawTo(double x, double y, Vector center) {
        return Math.toDegrees(Math.atan2(center.y() - y, center.x() - x));
    }

    private static double pitchTo(double x, double y, double z, Vector center) {
        return Math.toDegrees(Math.atan2(center.z() - z, Math.hypot(center.x() - x, center.y() - y)));
    }

    private static Key key(double seconds, Pose pose) {
        return new Key(
                seconds, pose.x(), pose.y(), pose.z(), pose.yaw(), pose.pitch(), pose.roll(), pose.fov(), Ease.SMOOTH);
    }
}
