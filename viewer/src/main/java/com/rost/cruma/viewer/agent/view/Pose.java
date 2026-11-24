package com.rost.cruma.viewer.agent.view;

import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.viewer.render.Camera;

public record Pose(double x, double y, double z, double yaw, double pitch) {
    private static final int FULL_TURN = 360;

    static Pose around(double targetX, double targetY, double targetZ, double distance, double yaw, double pitch) {
        var turn = Math.toRadians(yaw);
        var tilt = Math.toRadians(pitch);
        var level = Math.cos(tilt);
        return new Pose(
                targetX - Math.cos(turn) * level * distance,
                targetY - Math.sin(turn) * level * distance,
                targetZ - Math.sin(tilt) * distance,
                yaw,
                pitch);
    }

    static Pose looking(double x, double y, double z, double atX, double atY, double atZ) {
        var dx = atX - x;
        var dy = atY - y;
        var dz = atZ - z;
        return new Pose(
                x, y, z, Math.toDegrees(Math.atan2(dy, dx)), Math.toDegrees(Math.atan2(dz, Math.hypot(dx, dy))));
    }

    public static JSONObject write(Camera camera) {
        var eye = camera.getWorldFocusPosition();
        var pose = new JSONObject();
        pose.put("x", Math.round(eye.getX()));
        pose.put("y", Math.round(eye.getY()));
        pose.put("z", Math.round(eye.getZ()));
        pose.put("yaw", Math.floorMod(Math.round(camera.getYawDegrees()), FULL_TURN));
        pose.put("pitch", Math.round(camera.getPitchDegrees()));
        return pose;
    }

    void applyTo(Camera camera) {
        camera.setPosition((float) x, (float) y, (float) z);
        camera.setDirection(yaw, pitch);
    }
}
