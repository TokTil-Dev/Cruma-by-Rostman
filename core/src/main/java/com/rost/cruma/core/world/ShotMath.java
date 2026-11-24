package com.rost.cruma.core.world;

import com.rost.cruma.core.world.CameraPath.Pose;
import com.rost.cruma.core.world.GameScenes.Camera;
import com.rost.cruma.core.world.MapScenes.Vector;
import org.jetbrains.annotations.Nullable;

public final class ShotMath {
    private static final double BEHIND = 220;
    private static final int TURN = 0x10000;
    private static final int HALF_TURN = 0x8000;
    private static final double FOV_STEPS = 100;
    private static final double NO_DISTANCE = 1e-3;

    private ShotMath() {}

    public record Target(double x, double y, double z, int yaw) {}

    public record Aim(Camera shot, Pose pose, double moved, double turned) {}

    public static Pose pose(Camera shot, Target target, double fov) {
        var yaw = units(-shot.yaw()) + (shot.relative() ? target.yaw() : 0);
        var pitch = units(-shot.pitch());
        var p = pitch * 2 * Math.PI / TURN;
        var w = yaw * 2 * Math.PI / TURN;
        var away = shot.distance() + BEHIND;
        var dx = Math.cos(p) * Math.cos(w) * away;
        var dy = Math.cos(p) * Math.sin(w) * away;
        var dz = Math.sin(p) * away;
        var viewYaw = wrap(yawOf(dx, dy) + units(shot.relYaw()));
        var viewPitch = wrap(pitchOf(dx, dy, dz) + units(shot.relPitch()));
        var viewRoll = wrap(units(shot.relRoll()));
        return new Pose(
                target.x() - dx,
                target.y() - dy,
                target.z() - dz,
                degrees(viewYaw),
                degrees(viewPitch),
                degrees(viewRoll),
                shot.fov() != 0 ? shot.fov() : fov);
    }

    public static @Nullable Aim aim(Pose view, Target target, Camera shot, boolean keepFov) {
        var placed = placed(shot, target, view.x(), view.y(), view.z());
        if (placed == null) {
            return null;
        }
        var level = new Camera(
                placed.mode(),
                placed.target(),
                placed.distance(),
                placed.yaw(),
                placed.pitch(),
                placed.moveSeconds(),
                placed.holdSeconds(),
                0,
                0,
                0,
                placed.wide(),
                placed.relative(),
                placed.immediate(),
                placed.fov());
        var plain = pose(level, target, view.fov());
        var aimed = new Camera(
                placed.mode(),
                placed.target(),
                placed.distance(),
                placed.yaw(),
                placed.pitch(),
                placed.moveSeconds(),
                placed.holdSeconds(),
                (int) Math.round(Math.IEEEremainder(view.yaw() - plain.yaw(), 360)),
                (int) Math.round(Math.IEEEremainder(view.pitch() - plain.pitch(), 360)),
                (int) Math.round(Math.IEEEremainder(view.roll(), 360)),
                placed.wide(),
                placed.relative(),
                placed.immediate(),
                keepFov ? placed.fov() : Math.round(view.fov() * FOV_STEPS) / FOV_STEPS);
        var pose = pose(aimed, target, view.fov());
        return new Aim(aimed, pose, distance(view, pose), turned(view, pose));
    }

    public static @Nullable Camera placed(Camera shot, Target target, double x, double y, double z) {
        var dx = target.x() - x;
        var dy = target.y() - y;
        var dz = target.z() - z;
        var length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < NO_DISTANCE) {
            return null;
        }
        var yaw = Math.toDegrees(Math.atan2(dy, dx));
        if (shot.relative()) {
            yaw -= target.yaw() * 360.0 / TURN;
        }
        var pitch = Math.toDegrees(Math.atan2(dz, Math.hypot(dx, dy)));
        return new Camera(
                shot.mode(),
                shot.target(),
                Math.round(length - BEHIND),
                (int) -Math.round(Math.IEEEremainder(yaw, 360)),
                (int) -Math.round(pitch),
                shot.moveSeconds(),
                shot.holdSeconds(),
                shot.relYaw(),
                shot.relPitch(),
                shot.relRoll(),
                shot.wide(),
                shot.relative(),
                shot.immediate(),
                shot.fov());
    }

    public static Vector helper(Pose view, double distance) {
        var p = Math.toRadians(Math.rint(view.pitch()));
        var w = Math.toRadians(Math.rint(view.yaw()));
        var far = Math.rint(distance);
        return new Vector(
                view.x() + Math.cos(p) * Math.cos(w) * far,
                view.y() + Math.cos(p) * Math.sin(w) * far,
                view.z() + Math.sin(p) * far);
    }

    public static double distance(Pose a, Pose b) {
        return Math.sqrt(square(a.x() - b.x()) + square(a.y() - b.y()) + square(a.z() - b.z()));
    }

    public static double turned(Pose a, Pose b) {
        var pa = Math.toRadians(a.pitch());
        var pb = Math.toRadians(b.pitch());
        var wa = Math.toRadians(a.yaw());
        var wb = Math.toRadians(b.yaw());
        var dot = Math.cos(pa) * Math.cos(wa) * Math.cos(pb) * Math.cos(wb)
                + Math.cos(pa) * Math.sin(wa) * Math.cos(pb) * Math.sin(wb)
                + Math.sin(pa) * Math.sin(pb);
        var look = Math.toDegrees(Math.acos(Math.clamp(dot, -1, 1)));
        return Math.max(look, Math.abs(Math.IEEEremainder(a.roll() - b.roll(), 360)));
    }

    private static double square(double value) {
        return value * value;
    }

    private static int units(int degrees) {
        return degrees * TURN / 360;
    }

    private static double degrees(int units) {
        return units * 360.0 / TURN;
    }

    private static int yawOf(double x, double y) {
        return (int) Math.round(Math.atan2(y, x) * TURN / (2 * Math.PI));
    }

    private static int pitchOf(double x, double y, double z) {
        return (int) Math.round(Math.atan2(z, Math.hypot(x, y)) * TURN / (2 * Math.PI));
    }

    private static int wrap(int angle) {
        var wrapped = angle;
        while (wrapped > HALF_TURN) {
            wrapped -= TURN;
        }
        while (wrapped < -HALF_TURN + 1) {
            wrapped += TURN;
        }
        return wrapped;
    }
}
