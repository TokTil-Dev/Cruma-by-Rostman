package com.rost.cruma.viewer.render;

import com.rost.cruma.core.editing.SceneCheck.PathError;
import com.rost.cruma.core.world.CameraPath.Pose;
import com.rost.cruma.core.world.GameScenes.Scene;
import glm_.vec3.Vec3;
import java.util.ArrayList;
import java.util.List;

public final class ShotBakeReplay {
    private static final float FOV = 60;
    private static final float NEAR = 10;
    private static final double START = 0.1;
    private static final int CUT_STEPS = 3;

    private ShotBakeReplay() {}

    public static PathError measure(Scene path, Scene shots, int rate) {
        var camera = new Camera(new Context(), FOV, NEAR, new Vec3(0, 0, 0));
        var player = new GameScenePlayer(camera);
        player.play(shots, (x, y, z) -> Double.NaN);
        var delta = 1.0 / rate;
        var result = new Worst();
        var seconds = 0.0;
        var cuts = cuts(path);
        while (true) {
            player.update(delta);
            seconds += delta;
            if (!player.isPlaying()) {
                break;
            }
            if (player.holdsCamera() && !isJustCut(cuts, seconds, delta)) {
                result.compare(seconds, camera, path.flown(seconds));
            }
        }
        return result.getError(rate);
    }

    private static List<Double> cuts(Scene path) {
        var cuts = new ArrayList<Double>();
        for (var flight : path.paths()) {
            var keys = flight.getKeys();
            for (var index = 1; index < keys.size(); index++) {
                if (keys.get(index).seconds() == keys.get(index - 1).seconds()) {
                    cuts.add(keys.get(index).seconds());
                }
            }
        }
        return cuts;
    }

    private static boolean isJustCut(List<Double> cuts, double seconds, double delta) {
        for (var cut : cuts) {
            if (seconds >= cut - delta && seconds < cut + CUT_STEPS * delta) {
                return true;
            }
        }
        return false;
    }

    private static double[] look(double yawDegrees, double pitchDegrees) {
        var yaw = Math.toRadians(yawDegrees);
        var pitch = Math.toRadians(pitchDegrees);
        return new double[] {Math.cos(pitch) * Math.cos(yaw), Math.cos(pitch) * Math.sin(yaw), Math.sin(pitch)};
    }

    private static final class Worst {
        private double units;
        private double unitsAt;
        private double degrees;
        private double degreesAt;
        private double startDegrees;
        private double roll;
        private double fov;
        private int steps;

        void compare(double seconds, Camera camera, Pose pose) {
            steps++;
            var place = camera.getPosition();
            var dx = place.getX() - pose.x();
            var dy = place.getY() - pose.y();
            var dz = place.getZ() - pose.z();
            var away = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (away > units) {
                units = away;
                unitsAt = seconds;
            }
            var seen = look(camera.getYawDegrees(), camera.getPitchDegrees());
            var wanted = look(pose.yaw(), pose.pitch());
            var dot = seen[0] * wanted[0] + seen[1] * wanted[1] + seen[2] * wanted[2];
            var turned = Math.toDegrees(Math.acos(Math.clamp(dot, -1, 1)));
            if (seconds <= START) {
                startDegrees = Math.max(startDegrees, turned);
            } else if (turned > degrees) {
                degrees = turned;
                degreesAt = seconds;
            }
            roll = Math.max(roll, Math.abs(Math.IEEEremainder(camera.getRollDegrees() - pose.roll(), 360)));
            fov = Math.max(fov, Math.abs(camera.getFov() - pose.fov()));
        }

        PathError getError(int rate) {
            return new PathError(rate, units, unitsAt, degrees, degreesAt, startDegrees, roll, fov, steps);
        }
    }
}
