package com.rost.cruma.viewer.agent.edit;

import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.core.editing.ActorTransform.Rotator;
import com.rost.cruma.core.editing.ActorTransform.Vector3;
import com.rost.cruma.core.editing.LightShape;
import com.rost.cruma.viewer.agent.ToolArgs;
import com.rost.cruma.viewer.agent.ToolProblem;
import org.jetbrains.annotations.Nullable;

record LightAim(
        @Nullable Double yaw,
        @Nullable Double pitch,
        @Nullable Vector3 face,
        @Nullable Double cone) {
    static final double MOST_CONE = 179;

    private static final int QUARTER = 16384;

    static LightAim of(ToolArgs args, @Nullable Vector3 face) throws ToolProblem {
        var yaw = args.getNumber("yaw");
        var pitch = args.getNumber("pitch");
        var cone = args.getNumber("cone");
        if (face != null && (yaw != null || pitch != null)) {
            throw new ToolProblem("Give face, or yaw and pitch, not both.");
        }
        if (yaw != null && !Double.isFinite(yaw)) {
            throw new ToolProblem("yaw must be a number of degrees.");
        }
        if (pitch != null && !(pitch >= -90 && pitch <= 90)) {
            throw new ToolProblem("pitch must be between -90 and 90.");
        }
        if (cone != null && !(cone >= 0 && cone <= MOST_CONE)) {
            throw new ToolProblem("cone must be between 0 and " + (int) MOST_CONE + " degrees.");
        }
        return new LightAim(yaw, pitch, face, cone);
    }

    static void write(LightShape shape, JSONObject row) {
        if (!shape.isSpot()) {
            return;
        }
        var shown = shown(shape);
        row.put("spot", true);
        row.put("yaw", tenths(degrees(shown.yaw())));
        row.put("pitch", tenths(degrees(shown.pitch())));
        row.put("cone", tenths(shape.coneDegrees() * 2));
    }

    boolean any() {
        return turns() || cone != null;
    }

    boolean turns() {
        return yaw != null || pitch != null || face != null;
    }

    LightShape apply(LightShape shape, Vector3 at) throws ToolProblem {
        var result = shape;
        if (face != null) {
            var direction = new Vector3(face.x() - at.x(), face.y() - at.y(), face.z() - at.z());
            if (Math.hypot(Math.hypot(direction.x(), direction.y()), direction.z()) < 1) {
                throw new ToolProblem("face is where the light stands. Give a point away from it.");
            }
            result = result.withRotation(result.facing(direction));
        }
        if (yaw != null || pitch != null) {
            var base = shown(result);
            result = result.withRotation(new Rotator(
                    pitch == null ? base.pitch() : units(pitch), yaw == null ? base.yaw() : units(yaw), base.roll()));
        }
        if (cone != null) {
            result = result.withCone(LightShape.coneFor(cone / 2));
        }
        return result;
    }

    private static Rotator shown(LightShape shape) {
        var rotation = shape.rotation();
        return Math.abs((short) rotation.pitch()) <= QUARTER ? rotation : shape.facing(shape.direction());
    }

    private static double degrees(int units) {
        return (short) units * 360.0 / 65536;
    }

    private static int units(double degrees) {
        return (int) Math.round(degrees * 65536 / 360);
    }

    private static double tenths(double value) {
        return Math.round(value * 10) / 10.0;
    }
}
