package com.rost.cruma.core.world;

import com.rost.cruma.core.unreal.UArchive;
import com.rost.cruma.core.unreal.UArchiveLoader;
import com.rost.cruma.core.unreal.UArchiveLoader.USearchConfig;
import com.rost.cruma.core.unreal.UProperty;
import com.rost.cruma.core.unreal.UProperty.Type;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

public final class MapScenes {
    static final int PATH_STEPS = 99;
    private static final Vector START_CONTROL = new Vector(800, 800, 0);
    private static final Vector END_CONTROL = new Vector(-800, -800, 0);

    private final UArchive archive;
    private final Map<Integer, Map<String, UProperty>> stored = new HashMap<>();

    private MapScenes(UArchive archive) {
        this.archive = archive;
    }

    public static List<Scene> find(Path clientRoot, String map) {
        var archives = new UArchiveLoader(clientRoot, List.of(new USearchConfig("Maps", "unr")));
        var archive = archives.loadArchiveIfPresent(map);
        if (archive == null) {
            return List.of();
        }
        try {
            return read(archive);
        } finally {
            archives.removeArchive(map);
        }
    }

    public static List<Scene> read(UArchive archive) {
        var reader = new MapScenes(archive);
        var exports = archive.getExportMap();
        var scenes = new ArrayList<Scene>();
        for (var index = 1; index <= exports.size(); index++) {
            if (exports.get(index - 1).getClassName().equals("SceneManager")) {
                var scene = reader.scene(index);
                if (scene != null) {
                    scenes.add(scene);
                }
            }
        }
        scenes.sort(Comparator.comparing(Scene::tag, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(scenes);
    }

    public record Scene(String tag, int id, boolean looping, List<Action> actions) {
        public Scene {
            if (actions.isEmpty()) {
                throw new IllegalArgumentException("A scene needs an action");
            }
            actions = List.copyOf(actions);
        }

        public boolean warp() {
            return actions.getFirst().kind() == Kind.WARP;
        }

        public double seconds() {
            if (warp()) {
                return 0;
            }
            var total = 0.0;
            for (var action : actions) {
                total += action.seconds();
            }
            return total;
        }

        public int moves() {
            var moves = 0;
            for (var action : actions) {
                if (action.kind() == Kind.MOVE) {
                    moves++;
                }
            }
            return moves;
        }

        public Pose pose(double seconds) {
            if (warp()) {
                return actions.getFirst().to();
            }
            var start = 0.0;
            Action last = null;
            for (var action : actions) {
                if (action.seconds() > 0) {
                    if (seconds <= start + action.seconds()) {
                        return action.at((seconds - start) / action.seconds());
                    }
                    last = action;
                }
                start += action.seconds();
            }
            return last != null ? last.at(1) : actions.getFirst().to();
        }

        public List<Timed> timed() {
            if (warp()) {
                return List.of();
            }
            var timed = new ArrayList<Timed>();
            var start = 0.0;
            for (var action : actions) {
                for (var subAction : action.subActions()) {
                    var begin = start + subAction.delay();
                    timed.add(new Timed(subAction, begin, begin + subAction.seconds()));
                }
                start += action.seconds();
            }
            return timed;
        }
    }

    public enum Kind {
        WARP,
        MOVE,
        PAUSE
    }

    public enum Style {
        LINEAR,
        BEZIER
    }

    public record Action(
            String name,
            Kind kind,
            Style style,
            Pose from,
            Vector control1,
            Vector control2,
            Pose to,
            double seconds,
            List<SubAction> subActions) {
        public Action {
            subActions = List.copyOf(subActions);
        }

        public Pose at(double progress) {
            var t = Math.clamp(progress, 0, 1);
            var place = place(t);
            return new Pose(
                    place.x(),
                    place.y(),
                    place.z(),
                    turn(from.yawDegrees(), to.yawDegrees(), t),
                    turn(from.pitchDegrees(), to.pitchDegrees(), t));
        }

        public Vector place(double t) {
            var u = 1 - t;
            var a = u * u * u;
            var b = 3 * u * u * t;
            var c = 3 * u * t * t;
            var d = t * t * t;
            return new Vector(
                    a * from.x() + b * control1.x() + c * control2.x() + d * to.x(),
                    a * from.y() + b * control1.y() + c * control2.y() + d * to.y(),
                    a * from.z() + b * control1.z() + c * control2.z() + d * to.z());
        }

        public double length() {
            var length = 0.0;
            var previous = place(0);
            for (var step = 1; step <= PATH_STEPS; step++) {
                var next = place((double) step / PATH_STEPS);
                length += next.distance(previous);
                previous = next;
            }
            return length;
        }

        private Action withSeconds(double value) {
            return new Action(name, kind, style, from, control1, control2, to, value, subActions);
        }
    }

    public record Pose(double x, double y, double z, double yawDegrees, double pitchDegrees) {
        public Vector place() {
            return new Vector(x, y, z);
        }
    }

    public record Vector(double x, double y, double z) {
        public Vector plus(Vector other) {
            return new Vector(x + other.x, y + other.y, z + other.z);
        }

        public Vector minus(Vector other) {
            return new Vector(x - other.x, y - other.y, z - other.z);
        }

        public Vector times(double factor) {
            return new Vector(x * factor, y * factor, z * factor);
        }

        public double distance(Vector other) {
            var dx = x - other.x;
            var dy = y - other.y;
            var dz = z - other.z;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
    }

    public enum Effect {
        FOV,
        FADE,
        SCENE_SPEED
    }

    public record SubAction(Effect effect, double delay, double seconds, double from, double to) {
        public double value(double progress, double current) {
            var start = Double.isNaN(from) ? current : from;
            return start + (to - start) * progress;
        }
    }

    public record Timed(SubAction subAction, double start, double end) {
        public double progress(double seconds) {
            if (seconds <= start) {
                return 0;
            }
            return subAction.seconds() > 0 ? Math.min(1, (seconds - start) / subAction.seconds()) : 1;
        }
    }

    static double turn(double from, double to, double t) {
        return Math.IEEEremainder(from + Math.IEEEremainder(to - from, 360) * t, 360);
    }

    static List<Integer> references(@Nullable UProperty property) {
        if (property == null) {
            return List.of();
        }
        if (property.getObjectArray() != null) {
            var references = new ArrayList<Integer>();
            for (var reference : property.getObjectArray()) {
                references.add(reference);
            }
            return references;
        }
        var data = property.getDataValue();
        if (data == null) {
            return List.of();
        }
        var references = new ArrayList<Integer>();
        var at = 0;
        while (at < data.length && references.size() < property.getArraySize()) {
            var first = data[at++] & 0xFF;
            var value = first & 0x3F;
            var more = (first & 0x40) != 0;
            var shift = 6;
            while (more && at < data.length && shift < 32) {
                var next = data[at++] & 0xFF;
                value |= (next & 0x7F) << shift;
                more = (next & 0x80) != 0;
                shift += 7;
            }
            references.add((first & 0x80) != 0 ? -value : value);
        }
        return references;
    }

    private @Nullable Scene scene(int index) {
        var properties = properties(index);
        var export = archive.getExportMap().get(index - 1);
        var actions = new ArrayList<StoredAction>();
        for (var reference : references(properties.get("Actions"))) {
            var action = action(reference);
            if (action != null) {
                actions.add(action);
            }
        }
        if (actions.isEmpty()) {
            return null;
        }
        var resolved = new ArrayList<Action>(actions.size());
        for (var i = 0; i < actions.size(); i++) {
            resolved.add(resolve(actions.get(i), actions.get((i + actions.size() - 1) % actions.size())));
        }
        return new Scene(
                name(properties.get("Tag"), export.getObjectName()),
                integer(properties.get("nSceneManagerID")),
                readBool(properties.get("bLooping"), false),
                resolved);
    }

    private @Nullable StoredAction action(int reference) {
        var className = className(reference);
        if (className == null || !className.startsWith("Action")) {
            return null;
        }
        var properties = properties(reference);
        var point = point(properties.get("IntPoint"));
        if (point == null) {
            return null;
        }
        var kind = switch (className) {
            case "ActionWarp" -> Kind.WARP;
            case "ActionMoveCamera" -> Kind.MOVE;
            default -> Kind.PAUSE;
        };
        var style = kind == Kind.MOVE && integer(properties.get("PathStyle")) == 1 ? Style.BEZIER : Style.LINEAR;
        var subActions = new ArrayList<SubAction>();
        for (var subReference : references(properties.get("SubActions"))) {
            var subAction = subAction(subReference);
            if (subAction != null) {
                subActions.add(subAction);
            }
        }
        return new StoredAction(
                archive.getExportMap().get(reference - 1).getObjectName(),
                kind,
                style,
                point,
                number(properties.get("Duration")),
                vector(properties.get("StartControlPoint"), START_CONTROL),
                vector(properties.get("EndControlPoint"), END_CONTROL),
                readBool(properties.get("bConstantPathVelocity"), false),
                number(properties.get("PathVelocity")),
                subActions);
    }

    private @Nullable SubAction subAction(int reference) {
        var className = className(reference);
        if (className == null) {
            return null;
        }
        var properties = properties(reference);
        var delay = number(properties.get("Delay"));
        var seconds = number(properties.get("Duration"));
        return switch (className) {
            case "SubActionFOV" -> {
                var range = range(properties.get("FOV"));
                yield new SubAction(Effect.FOV, delay, seconds, range[0] == 0 ? Double.NaN : range[0], range[1]);
            }
            case "SubActionSceneSpeed" -> {
                var range = range(properties.get("SceneSpeed"));
                yield new SubAction(
                        Effect.SCENE_SPEED, delay, seconds, range[0] == 0 ? Double.NaN : range[0], range[1]);
            }
            case "SubActionFade" ->
                readBool(properties.get("bFadeOut"), true)
                        ? new SubAction(Effect.FADE, delay, seconds, 0, 1)
                        : new SubAction(Effect.FADE, delay, seconds, 1, 0);
            default -> null;
        };
    }

    private @Nullable Pose point(@Nullable UProperty reference) {
        if (reference == null || reference.getType() != Type.Object || className(reference.getIndexValue()) == null) {
            return null;
        }
        var properties = properties(reference.getIndexValue());
        var location = properties.get("Location");
        var rotation = properties.get("Rotation");
        var place = location == null || location.getVectorValue() == null
                ? new Vector(0, 0, 0)
                : new Vector(
                        location.getVectorValue().getX(),
                        location.getVectorValue().getY(),
                        location.getVectorValue().getZ());
        var turned = rotation != null && rotation.getRotatorValue() != null;
        return new Pose(
                place.x(),
                place.y(),
                place.z(),
                turned ? NamedMapStart.toDegrees(rotation.getRotatorValue().yaw()) : 0,
                turned ? NamedMapStart.toDegrees(rotation.getRotatorValue().pitch()) : 0);
    }

    private @Nullable String className(int reference) {
        var exports = archive.getExportMap();
        return reference > 0 && reference <= exports.size()
                ? exports.get(reference - 1).getClassName()
                : null;
    }

    private Map<String, UProperty> properties(int reference) {
        return stored.computeIfAbsent(reference, index -> {
            var byName = new HashMap<String, UProperty>();
            for (var property : archive.getObjectLoader()
                    .exportProperties(archive.getExportMap().get(index - 1))) {
                byName.putIfAbsent(property.getName(), property);
            }
            return byName;
        });
    }

    private String name(@Nullable UProperty property, String fallback) {
        if (property == null || property.getType() != Type.Name) {
            return fallback;
        }
        var name = archive.resolveName(property.getIndexValue());
        return name.isEmpty() || name.equals(UArchive.NONE_NAME) ? fallback : name;
    }

    private static Action resolve(StoredAction own, StoredAction before) {
        var from = before == own ? own.point() : before.point();
        var start = from.place();
        var end = own.point().place();
        Vector control1;
        Vector control2;
        if (own.kind() == Kind.MOVE && own.style() == Style.BEZIER) {
            control1 = start.plus(before.startControl());
            control2 = end.plus(own.endControl());
        } else {
            var third = end.minus(start).times(1.0 / 3);
            control1 = start.plus(third);
            control2 = end.minus(third);
        }
        var action = new Action(
                own.name(),
                own.kind(),
                own.style(),
                from,
                control1,
                control2,
                own.point(),
                Math.max(0, own.seconds()),
                own.subActions());
        if (own.constantVelocity() && own.velocity() != 0) {
            action = action.withSeconds(Math.max(0, action.length() / own.velocity()));
        }
        return action;
    }

    private record StoredAction(
            String name,
            Kind kind,
            Style style,
            Pose point,
            double seconds,
            Vector startControl,
            Vector endControl,
            boolean constantVelocity,
            double velocity,
            List<SubAction> subActions) {}

    private static int integer(@Nullable UProperty property) {
        if (property == null) {
            return 0;
        }
        return switch (property.getType()) {
            case Int -> property.getInt32Value();
            case Byte -> property.getUInt8Value();
            default -> 0;
        };
    }

    private static double number(@Nullable UProperty property) {
        return property != null && property.getType() == Type.Float && Float.isFinite(property.getFloatValue())
                ? property.getFloatValue()
                : 0;
    }

    private static boolean readBool(@Nullable UProperty property, boolean fallback) {
        return property == null || property.getType() != Type.Bool ? fallback : property.isTrue();
    }

    private static Vector vector(@Nullable UProperty property, Vector fallback) {
        if (property == null || property.getVectorValue() == null) {
            return fallback;
        }
        var value = property.getVectorValue();
        return new Vector(value.getX(), value.getY(), value.getZ());
    }

    private static double[] range(@Nullable UProperty property) {
        if (property == null || property.getSubProperties().isEmpty()) {
            return new double[] {0, 0};
        }
        var fields = property.getSubProperties().getFirst();
        return new double[] {number(fields.get("Min")), number(fields.get("Max"))};
    }
}
