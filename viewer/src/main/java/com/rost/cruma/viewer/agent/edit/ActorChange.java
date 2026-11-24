package com.rost.cruma.viewer.agent.edit;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.core.editing.ActorRecord;
import com.rost.cruma.core.editing.ActorTransform;
import com.rost.cruma.core.editing.ActorTransform.Rotator;
import com.rost.cruma.core.editing.ActorTransform.Vector3;
import com.rost.cruma.viewer.agent.ToolArgs;
import com.rost.cruma.viewer.agent.ToolProblem;
import com.rost.cruma.viewer.editing.EditorTransforms;
import java.util.Collection;
import org.jetbrains.annotations.Nullable;

record ActorChange(
        @Nullable Vector3 moveBy,
        @Nullable Vector3 moveTo,
        Rotator turn,
        float scale,
        boolean toGround,
        boolean copy,
        @Nullable Exact set,
        @Nullable Vector3 face) {
    private static final double UNITS_PER_DEGREE = 65536 / 360.0;

    record Exact(
            @Nullable Vector3 location,
            float @Nullable [] rotation,
            @Nullable Float drawScale,
            @Nullable Vector3 drawScale3d) {
        ActorTransform applied(ActorTransform t) {
            var turn = t.rotation();
            if (rotation != null) {
                turn = rotation.length == 1
                        ? new Rotator(turn.pitch(), units(rotation[0]), turn.roll())
                        : new Rotator(units(rotation[1]), units(rotation[0]), units(rotation[2]));
            }
            return new ActorTransform(
                    location != null ? location : t.location(),
                    turn,
                    drawScale != null ? drawScale : t.drawScale(),
                    drawScale3d != null ? drawScale3d : t.drawScale3d(),
                    t.prePivot());
        }
    }

    static ActorChange read(ToolArgs args, @Nullable Vector3 moveTo) throws ToolProblem {
        return read(args, moveTo, null);
    }

    static ActorChange read(ToolArgs args, @Nullable Vector3 moveTo, @Nullable Vector3 face) throws ToolProblem {
        var moveBy = vector(args, "move_by");
        if (moveBy != null && moveTo != null) {
            throw new ToolProblem("Give move_by or move_to, not both.");
        }
        var scale = (float) args.getNumber("scale", 1);
        if (!(scale > 0) || !Float.isFinite(scale)) {
            throw new ToolProblem("scale must be a factor above 0, such as 1.5.");
        }
        var set = exact(args);
        if (set != null && set.location() != null && moveTo != null) {
            throw new ToolProblem("Give set.location or move_to, not both.");
        }
        var change = new ActorChange(
                moveBy,
                moveTo,
                rotator(args, "turn_by"),
                scale,
                args.readFlag("to_ground", false),
                args.readFlag("copy", false),
                set,
                face);
        if (!change.toGround
                && !change.copy
                && !change.moves()
                && !change.turns()
                && scale == 1
                && set == null
                && face == null) {
            throw new ToolProblem(
                    "Say what to change: move_by, move_to, turn_by, scale, set, face, to_ground or copy.");
        }
        return change;
    }

    static @Nullable Vector3 vector(ToolArgs args, String key) throws ToolProblem {
        var value = args.getJson().get(key);
        if (value == null) {
            return null;
        }
        var numbers = numbers(value, key);
        if (numbers.length != 3) {
            throw new ToolProblem(key + " must be three numbers: x, y and z.");
        }
        return new Vector3(numbers[0], numbers[1], numbers[2]);
    }

    static Rotator rotator(ToolArgs args, String key) throws ToolProblem {
        var value = args.getJson().get(key);
        if (value == null) {
            return new Rotator(0, 0, 0);
        }
        var numbers = numbers(value, key);
        if (numbers.length != 1 && numbers.length != 3) {
            throw new ToolProblem(key + " must be a yaw in degrees, or yaw, pitch and roll.");
        }
        var pitch = numbers.length == 3 ? numbers[1] : 0;
        var roll = numbers.length == 3 ? numbers[2] : 0;
        return new Rotator(units(pitch), units(numbers[0]), units(roll));
    }

    static int units(float degrees) {
        return (int) Math.round(degrees * UNITS_PER_DEGREE);
    }

    static float[] numbers(Object value, String key) throws ToolProblem {
        if (value instanceof Number number) {
            return new float[] {number.floatValue()};
        }
        try {
            if (value instanceof JSONArray array) {
                var numbers = new float[array.size()];
                for (var i = 0; i < numbers.length; i++) {
                    numbers[i] = array.getFloatValue(i);
                }
                return finite(numbers, key);
            }
            if (value instanceof String text) {
                var parts = text.split(",");
                var numbers = new float[parts.length];
                for (var i = 0; i < numbers.length; i++) {
                    numbers[i] = Float.parseFloat(parts[i].strip());
                }
                return finite(numbers, key);
            }
        } catch (RuntimeException wrong) {
            throw new ToolProblem(key + " must hold numbers, not " + value + ".");
        }
        throw new ToolProblem(key + " must hold numbers.");
    }

    static ActorTransform atHeight(ActorTransform t, float z) {
        var location = t.location();
        return new ActorTransform(
                new Vector3(location.x(), location.y(), z), t.rotation(), t.drawScale(), t.drawScale3d(), t.prePivot());
    }

    boolean moves() {
        return moveBy != null && !moveBy.equals(Vector3.ZERO) || moveTo != null;
    }

    boolean turns() {
        return !turn.equals(new Rotator(0, 0, 0));
    }

    Vector3 offset(Collection<ActorRecord> actors) {
        if (moveTo == null || actors.isEmpty()) {
            return moveBy == null ? Vector3.ZERO : moveBy;
        }
        double x = 0, y = 0, z = 0;
        for (var actor : actors) {
            var location = actor.transform().location();
            x += location.x();
            y += location.y();
            z += location.z();
        }
        var count = actors.size();
        return new Vector3(
                (float) (moveTo.x() - x / count), (float) (moveTo.y() - y / count), (float) (moveTo.z() - z / count));
    }

    ActorTransform applied(ActorTransform from, Vector3 offset) {
        var t = set == null ? from : set.applied(from);
        var location = t.location();
        var rotation = t.rotation();
        var result = new ActorTransform(
                new Vector3(location.x() + offset.x(), location.y() + offset.y(), location.z() + offset.z()),
                new Rotator(
                        rotation.pitch() + turn.pitch(), rotation.yaw() + turn.yaw(), rotation.roll() + turn.roll()),
                t.drawScale() * scale,
                t.drawScale3d(),
                t.prePivot());
        return face == null ? result : EditorTransforms.facing(result, face.x(), face.y());
    }

    String verb() {
        if (copy) {
            return "Copy";
        }
        if (set != null) {
            return "Set";
        }
        if (moves()) {
            return "Move";
        }
        if (turns() || face != null) {
            return "Turn";
        }
        return scale != 1 ? "Scale" : "Drop";
    }

    private static @Nullable Exact exact(ToolArgs args) throws ToolProblem {
        var value = args.getJson().get("set");
        if (value == null) {
            return null;
        }
        if (!(value instanceof JSONObject json)) {
            throw new ToolProblem("set must be an object with location, rotation, scale or scale3d.");
        }
        var parts = new ToolArgs(json);
        var location = vector(parts, "location");
        float[] rotation = null;
        if (json.get("rotation") != null) {
            rotation = numbers(json.get("rotation"), "set.rotation");
            if (rotation.length != 1 && rotation.length != 3) {
                throw new ToolProblem("set.rotation must be a yaw in degrees, or yaw, pitch and roll.");
            }
        }
        var drawScale = parts.getNumber("scale");
        if (drawScale != null && (!(drawScale > 0) || !Double.isFinite(drawScale))) {
            throw new ToolProblem("set.scale must be above 0.");
        }
        var drawScale3d = vector(parts, "scale3d");
        if (drawScale3d != null && (drawScale3d.x() == 0 || drawScale3d.y() == 0 || drawScale3d.z() == 0)) {
            throw new ToolProblem("set.scale3d must not hold 0. A negative axis mirrors the mesh.");
        }
        if (location == null && rotation == null && drawScale == null && drawScale3d == null) {
            throw new ToolProblem("set is empty. Give location, rotation, scale or scale3d.");
        }
        return new Exact(location, rotation, drawScale == null ? null : drawScale.floatValue(), drawScale3d);
    }

    private static float[] finite(float[] numbers, String key) throws ToolProblem {
        for (var number : numbers) {
            if (!Float.isFinite(number)) {
                throw new ToolProblem(key + " must hold finite numbers.");
            }
        }
        return numbers;
    }
}
