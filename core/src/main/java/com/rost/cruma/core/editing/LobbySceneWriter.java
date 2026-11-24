package com.rost.cruma.core.editing;

import static com.rost.cruma.core.editing.PackageBytes.malformed;
import static com.rost.cruma.core.editing.TaggedProperties.BOOL;
import static com.rost.cruma.core.editing.TaggedProperties.BYTE;
import static com.rost.cruma.core.editing.TaggedProperties.FLOAT;
import static com.rost.cruma.core.editing.TaggedProperties.NAME;
import static com.rost.cruma.core.editing.TaggedProperties.OBJECT;
import static com.rost.cruma.core.editing.TaggedProperties.STRUCT;

import com.rost.cruma.core.editing.LobbySceneCheck.Change;
import com.rost.cruma.core.editing.LobbySceneCheck.Refusal;
import com.rost.cruma.core.editing.LobbySceneCheck.SceneChange;
import com.rost.cruma.core.editing.LobbySceneEdit.Handles;
import com.rost.cruma.core.editing.LobbySceneEdit.PathStyle;
import com.rost.cruma.core.editing.LobbySceneEdit.Point;
import com.rost.cruma.core.editing.LobbySceneEdit.SubValues;
import com.rost.cruma.core.editing.LobbySceneEdit.Time;
import com.rost.cruma.core.editing.PackageBytes.Cursor;
import com.rost.cruma.core.editing.TaggedProperties.Property;
import com.rost.cruma.core.unreal.UArchive;
import com.rost.cruma.core.world.MapScenes;
import com.rost.cruma.core.world.MapScenes.Action;
import com.rost.cruma.core.world.MapScenes.Effect;
import com.rost.cruma.core.world.MapScenes.Pose;
import com.rost.cruma.core.world.MapScenes.Scene;
import com.rost.cruma.core.world.MapScenes.Style;
import com.rost.cruma.core.world.MapScenes.SubAction;
import com.rost.cruma.core.world.MapScenes.Vector;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

public final class LobbySceneWriter {
    private static final int HAS_STACK = 0x02000000;
    private static final int FRAME_MIDDLE = 12;
    private static final int STRUCT_SIZE = 12;
    private static final String MANAGER = "SceneManager";
    private static final String POINT = "InterpolationPoint";
    private static final String MOVE = "ActionMoveCamera";
    private static final String WARP = "ActionWarp";
    private static final Set<String> ACTIONS = Set.of(MOVE, "ActionPause", WARP);
    private static final Map<String, Effect> SUB_ACTIONS = Map.of(
            "SubActionFOV", Effect.FOV,
            "SubActionFade", Effect.FADE,
            "SubActionSceneSpeed", Effect.SCENE_SPEED);
    private static final float[] START_CONTROL = {800, 800, 0};
    private static final float[] END_CONTROL = {-800, -800, 0};
    private static final float[] NO_PLACE = new float[3];
    private static final int TURN = 65536;
    private static final double DEGREES = 360;
    private static final double WIDEST_VIEW = 180;
    private static final double TURN_ROUNDING = DEGREES / TURN * .51;
    private static final double TIME_ROUNDING = 1e-6;
    private static final double OFFSET_ROUNDING = 1e-7;
    private static final double SMALLEST_OFFSET_ROUNDING = 1e-3;

    private final String map;
    private final PackageBytes source;
    private final List<Scene> scenes;
    private final Map<String, List<StoredScene>> stored;

    private LobbySceneWriter(
            String map, PackageBytes source, List<Scene> scenes, Map<String, List<StoredScene>> stored) {
        this.map = map;
        this.source = source;
        this.scenes = scenes;
        this.stored = stored;
    }

    public static LobbySceneWriter open(Path file, byte[] bytes) throws IOException {
        var source = new PackageBytes(file, bytes);
        var scenes = read(source.packageName, source.body);
        return new LobbySceneWriter(
                file.getFileName().toString(), source, scenes, walk(ClassPackageWriter.open(source), scenes));
    }

    public List<Scene> getScenes() {
        return scenes;
    }

    public LobbySceneCheck check(List<? extends LobbySceneEdit> edits) throws IOException {
        return new Run(edits).check();
    }

    public byte[] write(List<? extends LobbySceneEdit> edits, Path target) throws IOException {
        var run = new Run(edits);
        if (!run.refused.isEmpty()) {
            throw malformed(run.refused.getFirst().reason());
        }
        var bytes = run.writer.encode(target);
        var written = new PackageBytes(target, bytes);
        if (!read(written.packageName, written.body).equals(run.after)) {
            throw malformed("The map does not read back as it was written");
        }
        return bytes;
    }

    private static List<Scene> read(String name, byte[] body) throws IOException {
        var buffer = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN);
        try (var archive = new UArchive(name, buffer, null)) {
            return MapScenes.read(archive);
        } catch (RuntimeException unreadable) {
            throw malformed("The scenes of this map cannot be read: " + unreadable.getMessage());
        }
    }

    private static Map<String, List<StoredScene>> walk(ClassPackageWriter writer, List<Scene> scenes)
            throws IOException {
        var read = new HashMap<String, Scene>();
        for (var scene : scenes) {
            read.put(key(scene.tag()), scene);
        }
        var result = new HashMap<String, List<StoredScene>>();
        for (var export = 1; export <= writer.getExportCount(); export++) {
            if (writer.getClassName(export).equals(MANAGER)) {
                var scene = scene(writer, export);
                if (!scene.actions.isEmpty() || scene.problem != null) {
                    scene = scene.against(read.get(key(scene.tag)));
                    result.computeIfAbsent(key(scene.tag), _ -> new ArrayList<>())
                            .add(scene);
                }
            }
        }
        return result;
    }

    private static StoredScene scene(ClassPackageWriter writer, int export) throws IOException {
        var name = writer.exportName(export);
        try {
            var tags = Block.read(writer, export).tags;
            name = tagOf(tags, name, writer);
            var actions = new ArrayList<StoredAction>();
            var dropped = false;
            for (var reference : references(tags, "Actions", writer)) {
                var action = action(writer, reference);
                if (action != null) {
                    actions.add(action);
                } else {
                    dropped = true;
                }
            }
            return new StoredScene(
                    name, export, actions, dropped ? "An action of this scene has no point the client can use" : null);
        } catch (IOException unreadable) {
            return new StoredScene(name, export, List.of(), unreadable.getMessage());
        }
    }

    private static String tagOf(TaggedProperties tags, String fallback, ClassPackageWriter writer) throws IOException {
        var tag = tags.find("Tag", writer);
        if (tag == null || tag.getType() != NAME) {
            return fallback;
        }
        var name = writer.getName(tag.getIndex());
        return name.isEmpty() || name.equalsIgnoreCase("None") ? fallback : name;
    }

    private static @Nullable StoredAction action(ClassPackageWriter writer, int reference) throws IOException {
        if (reference <= 0
                || reference > writer.getExportCount()
                || !writer.getClassName(reference).startsWith("Action")) {
            return null;
        }
        var tags = Block.read(writer, reference).tags;
        var point = tags.find("IntPoint", writer);
        if (point == null
                || point.getType() != OBJECT
                || point.getIndex() <= 0
                || point.getIndex() > writer.getExportCount()) {
            return null;
        }
        var subs = new ArrayList<StoredSub>();
        for (var sub : references(tags, "SubActions", writer)) {
            if (sub > 0 && sub <= writer.getExportCount()) {
                var effect = SUB_ACTIONS.get(writer.getClassName(sub));
                if (effect != null) {
                    subs.add(new StoredSub(sub, effect));
                }
            }
        }
        return new StoredAction(reference, writer.getClassName(reference), point.getIndex(), subs);
    }

    private static List<Integer> references(TaggedProperties tags, String field, ClassPackageWriter writer)
            throws IOException {
        var tag = tags.find(field, writer);
        if (tag == null) {
            return List.of();
        }
        if (tag.getArray() == null || tag.getArray().getObjects() == null) {
            throw malformed("The list " + field + " has an unknown layout");
        }
        return tag.getArray().getObjects();
    }

    private static int order(LobbySceneEdit edit) {
        return switch (edit) {
            case PathStyle _ -> 0;
            case Point _ -> 1;
            case Handles _ -> 2;
            case SubValues _ -> 3;
            case Time _ -> 4;
        };
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static float[] floats(double x, double y, double z) {
        return new float[] {(float) x, (float) y, (float) z};
    }

    private static float[] floats(Property tag) {
        var bytes = tag.getBinary();
        return new float[] {bytes.getFloat(0), bytes.getFloat(4), bytes.getFloat(8)};
    }

    private static boolean isFinite(float[] values) {
        for (var value : values) {
            if (!Float.isFinite(value)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isSame(float[] left, float[] right) {
        for (var i = 0; i < left.length; i++) {
            if (left[i] != right[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean closeTo(Vector read, Vector wanted) {
        var size = Math.max(Math.abs(wanted.x()), Math.max(Math.abs(wanted.y()), Math.abs(wanted.z())));
        return read.distance(wanted) <= SMALLEST_OFFSET_ROUNDING + size * OFFSET_ROUNDING;
    }

    private static boolean isSameTurn(double read, double wanted) {
        return Math.abs(Math.IEEEremainder(read - wanted, DEGREES)) <= TURN_ROUNDING;
    }

    private static int turned(int stored, double degrees) {
        var units = (int) Math.floorMod(Math.round(degrees * TURN / DEGREES), (long) TURN);
        var wanted = units >= TURN / 2 ? units - TURN : units;
        return Math.floorMod(stored - wanted, TURN) == 0 ? stored : wanted;
    }

    private final class Run {
        private final ClassPackageWriter writer;
        private final Map<Integer, Block> blocks = new LinkedHashMap<>();
        private final Map<String, Object> claims = new HashMap<>();
        private final List<Change> changes = new ArrayList<>();
        private final List<Refusal> refused = new ArrayList<>();
        private final List<LobbySceneEdit> applied = new ArrayList<>();
        private final Set<String> asked = new HashSet<>();
        private List<Scene> after;

        private Run(List<? extends LobbySceneEdit> edits) throws IOException {
            writer = ClassPackageWriter.open(source);
            after = scenes;
            var ordered = new ArrayList<LobbySceneEdit>(edits);
            ordered.sort(Comparator.comparingInt(LobbySceneWriter::order));
            var measured = false;
            for (var edit : ordered) {
                asked.add(key(edit.scene()));
                if (edit instanceof Time && !measured) {
                    readBack();
                    measured = true;
                }
                try {
                    apply(edit);
                    applied.add(edit);
                } catch (IOException refusal) {
                    refused.add(new Refusal(edit, refusal.getMessage()));
                }
            }
            readBack();
            verify();
        }

        private LobbySceneCheck check() throws IOException {
            var changed = new ArrayList<SceneChange>();
            for (var i = 0; i < scenes.size(); i++) {
                var before = scenes.get(i);
                if (!before.equals(after.get(i))) {
                    changed.add(new SceneChange(before, after.get(i), asked.contains(key(before.tag()))));
                }
            }
            var inPlace = 0;
            var grown = 0;
            for (var block : blocks.values()) {
                if (block.hasChanged()) {
                    if (block.getBytes().length == writer.getOriginalPayload(block.export).length) {
                        inPlace++;
                    } else {
                        grown++;
                    }
                }
            }
            return new LobbySceneCheck(map, changes, changed, refused, inPlace, grown);
        }

        private void apply(LobbySceneEdit edit) throws IOException {
            switch (edit) {
                case PathStyle style -> style(style);
                case Point point -> point(point);
                case Handles handles -> handles(handles);
                case SubValues values -> subValues(values);
                case Time time -> time(time);
            }
        }

        private void point(Point edit) throws IOException {
            var action = address(edit);
            var pose = edit.pose();
            var place = floats(pose.x(), pose.y(), pose.z());
            if (!isFinite(place) || !Double.isFinite(pose.yawDegrees()) || !Double.isFinite(pose.pitchDegrees())) {
                throw malformed("A point needs a place and a turn that are numbers");
            }
            admit(action.point, POINT);
            claim("point", action.point, pose);
            var block = block(action.point);
            vector(block, "Location", place, NO_PLACE);
            rotation(block, pose);
        }

        private void style(PathStyle edit) throws IOException {
            var action = address(edit);
            if (!action.className.equals(MOVE)) {
                throw malformed("Only an ActionMoveCamera has a path style");
            }
            admit(action);
            claim("path style", action.export, edit.style());
            var block = block(action.export);
            var tag = block.tags.find("PathStyle", writer);
            var stored = pathStyle(block);
            var wanted = edit.style() == Style.BEZIER ? 1 : 0;
            if ((stored == 1) != (wanted == 1)) {
                if (tag == null) {
                    tag = block.tags.add(writer.intern("PathStyle"), BYTE);
                }
                tag.setByteValue(wanted);
                change(block, "PathStyle", stored, wanted);
            }
        }

        private void handles(Handles edit) throws IOException {
            var scene = scene(edit.scene());
            var action = address(edit);
            var count = scene.actions.size();
            if (count < 2) {
                throw malformed(
                        "A scene of one action has no path, as the client measures no way from a point to itself");
            }
            if (!action.className.equals(MOVE) || pathStyle(block(action.export)) != 1) {
                throw malformed(
                        "Only a Bezier move has control points. A straight path works them out from its two points");
            }
            var before = scene.actions.get((edit.action() + count - 1) % count);
            admit(action);
            admit(before);
            var start = offset(edit.control1(), before.point);
            var end = offset(edit.control2(), action.point);
            if (!isFinite(start) || !isFinite(end)) {
                throw malformed("Control points need places that are numbers");
            }
            claim("start control point", before.export, Arrays.toString(start));
            claim("end control point", action.export, Arrays.toString(end));
            vector(block(before.export), "StartControlPoint", start, START_CONTROL);
            vector(block(action.export), "EndControlPoint", end, END_CONTROL);
        }

        private void subValues(SubValues edit) throws IOException {
            var action = address(edit);
            if (edit.index() < 0 || edit.index() >= action.subs.size()) {
                throw malformed("This action has no such sub-action. This writer changes numbers, so it adds none");
            }
            var sub = action.subs.get(edit.index());
            var value = edit.value();
            if (value.effect() != sub.effect) {
                throw malformed("This sub-action is of the kind " + sub.effect + ", which stays");
            }
            if (read(edit).subActions().get(edit.index()).equals(value)) {
                return;
            }
            if (!(value.delay() >= 0)
                    || !(value.seconds() >= 0)
                    || !Float.isFinite((float) value.delay())
                    || !Float.isFinite((float) value.seconds())) {
                throw malformed("The delay and the time of a sub-action cannot be below 0");
            }
            admit(sub.export, writer.getClassName(sub.export));
            checkValues(value);
            claim("sub-action", sub.export, value);
            var block = block(sub.export);
            number(block, block.tags, "Delay", "Delay", value.delay(), 0);
            number(block, block.tags, "Duration", "Duration", value.seconds(), 0);
            switch (value.effect()) {
                case FOV -> range(block, "FOV", value);
                case SCENE_SPEED -> range(block, "SceneSpeed", value);
                case FADE -> fade(block, value.to() == 1);
            }
        }

        private void checkValues(SubAction value) throws IOException {
            switch (value.effect()) {
                case FADE -> {
                    if (!(value.from() == 0 && value.to() == 1) && !(value.from() == 1 && value.to() == 0)) {
                        throw malformed("A fade goes to black or comes from black, and the map holds no other numbers");
                    }
                }
                case FOV -> {
                    if (!isValidView(value.to()) || !Double.isNaN(value.from()) && !isValidView(value.from())) {
                        throw malformed(
                                "A field of view lies between 0 and 180 degrees. NaN starts from the view of that"
                                        + " moment");
                    }
                }
                case SCENE_SPEED -> {
                    if (!isValidSpeed(value.to()) || !Double.isNaN(value.from()) && !isValidSpeed(value.from())) {
                        throw malformed(
                                "A speed is above 0, as at 0 the scene never ends. NaN starts from the speed of that"
                                        + " moment");
                    }
                }
            }
        }

        private void time(Time edit) throws IOException {
            var scene = scene(edit.scene());
            var action = address(edit);
            var seconds = edit.seconds();
            if (!(seconds >= 0) || !Float.isFinite((float) seconds)) {
                throw malformed("A time cannot be below 0");
            }
            if (scene.actions.getFirst().className.equals(WARP)) {
                throw malformed("A warp puts the camera on its point and ends at once, so a time does nothing");
            }
            admit(action);
            claim("time", action.export, seconds);
            var block = block(action.export);
            if (!hasConstantSpeed(block)) {
                number(block, block.tags, "Duration", "Duration", seconds, 0);
                return;
            }
            var path = read(edit);
            if (path.seconds() != seconds) {
                if (seconds == 0 || path.length() == 0) {
                    throw malformed(
                            "This action takes its time from its speed, and no speed makes this path take this long");
                }
                number(block, block.tags, "PathVelocity", "PathVelocity", path.length() / seconds, 0);
            }
        }

        private float[] offset(Vector control, int point) throws IOException {
            var tag = block(point).tags.find("Location", writer);
            var place = tag == null ? NO_PLACE : floats(tag);
            return floats(control.x() - place[0], control.y() - place[1], control.z() - place[2]);
        }

        private boolean hasConstantSpeed(Block block) throws IOException {
            var constant = block.tags.find("bConstantPathVelocity", writer);
            var speed = block.tags.find("PathVelocity", writer);
            return constant != null
                    && constant.getType() == BOOL
                    && constant.isTrue()
                    && speed != null
                    && speed.getType() == FLOAT
                    && speed.getFloatValue() != 0;
        }

        private int pathStyle(Block block) throws IOException {
            var tag = block.tags.find("PathStyle", writer);
            if (tag != null && tag.getType() != BYTE) {
                throw malformed("The tag PathStyle has an unknown layout");
            }
            return tag == null ? 0 : tag.getByteValue();
        }

        private void vector(Block block, String field, float[] wanted, float[] fallback) throws IOException {
            var tag = block.tags.find(field, writer);
            if (tag != null && (tag.getType() != STRUCT || tag.getRaw().length != STRUCT_SIZE)) {
                throw malformed("The tag " + field + " has an unknown layout");
            }
            var stored = tag == null ? fallback : floats(tag);
            if (isSame(stored, wanted)) {
                return;
            }
            if (tag == null) {
                tag = block.tags.add(writer.intern(field), STRUCT).setStructName(writer.intern("Vector"));
            }
            var bytes = ByteBuffer.allocate(STRUCT_SIZE).order(ByteOrder.LITTLE_ENDIAN);
            tag.setRaw(bytes.putFloat(wanted[0])
                    .putFloat(wanted[1])
                    .putFloat(wanted[2])
                    .array());
            change(block, field, Arrays.toString(stored), Arrays.toString(wanted));
        }

        private void rotation(Block block, Pose pose) throws IOException {
            var tag = block.tags.find("Rotation", writer);
            if (tag != null && (tag.getType() != STRUCT || tag.getRaw().length != STRUCT_SIZE)) {
                throw malformed("The tag Rotation has an unknown layout");
            }
            var bytes = tag == null ? null : tag.getBinary();
            var stored = bytes == null ? new int[3] : new int[] {bytes.getInt(0), bytes.getInt(4), bytes.getInt(8)};
            var wanted =
                    new int[] {turned(stored[0], pose.pitchDegrees()), turned(stored[1], pose.yawDegrees()), stored[2]};
            if (Arrays.equals(stored, wanted)) {
                return;
            }
            if (tag == null) {
                tag = block.tags.add(writer.intern("Rotation"), STRUCT).setStructName(writer.intern("Rotator"));
            }
            var written = ByteBuffer.allocate(STRUCT_SIZE).order(ByteOrder.LITTLE_ENDIAN);
            tag.setRaw(written.putInt(wanted[0])
                    .putInt(wanted[1])
                    .putInt(wanted[2])
                    .array());
            change(block, "Rotation", Arrays.toString(stored), Arrays.toString(wanted));
        }

        private void number(
                Block block, TaggedProperties tags, String field, String label, double value, float fallback)
                throws IOException {
            var tag = tags.find(field, writer);
            if (tag != null && tag.getType() != FLOAT) {
                throw malformed("The tag " + label + " has an unknown layout");
            }
            var stored = tag == null ? fallback : tag.getFloatValue();
            var wanted = (float) value;
            if (!Float.isFinite(wanted)) {
                throw malformed("The number for " + label + " does not fit the map");
            }
            if (stored == wanted) {
                return;
            }
            if (tag == null) {
                tag = tags.add(writer.intern(field), FLOAT);
            }
            tag.setFloatValue(wanted);
            change(block, label, stored, wanted);
        }

        private void range(Block block, String field, SubAction value) throws IOException {
            var tag = block.tags.find(field, writer);
            if (tag == null) {
                tag = block.tags.add(writer.intern(field), STRUCT).setStructName(writer.intern("Range"));
                tag.makeEmptyStruct(writer);
            }
            var fields = tag.getStruct();
            if (fields == null) {
                throw malformed("The tag " + field + " has an unknown layout");
            }
            number(block, fields, "Min", field + ".Min", Double.isNaN(value.from()) ? 0 : value.from(), 0);
            number(block, fields, "Max", field + ".Max", value.to(), 0);
        }

        private void fade(Block block, boolean out) throws IOException {
            var tag = block.tags.find("bFadeOut", writer);
            if (tag != null && tag.getType() != BOOL) {
                throw malformed("The tag bFadeOut has an unknown layout");
            }
            var stored = tag == null || tag.isTrue();
            if (stored != out) {
                if (tag == null) {
                    tag = block.tags.add(writer.intern("bFadeOut"), BOOL);
                }
                tag.setBool(out);
                change(block, "bFadeOut", stored, out);
            }
        }

        private StoredAction address(LobbySceneEdit edit) throws IOException {
            var scene = scene(edit.scene());
            if (edit.action() < 0 || edit.action() >= scene.actions.size()) {
                throw malformed(
                        "This scene has no such action. A new action needs a point, and a new point is a new actor in"
                                + " the arrays of the Level");
            }
            return scene.actions.get(edit.action());
        }

        private StoredScene scene(String tag) throws IOException {
            var found = stored.getOrDefault(key(tag), List.of());
            if (found.isEmpty()) {
                throw malformed("This map has no scene " + tag
                        + ". A new Tag would not play, as the interface of the game chooses the scenes");
            }
            if (found.size() > 1) {
                throw malformed("Two scenes of this map have the Tag " + tag);
            }
            var scene = found.getFirst();
            if (scene.problem != null) {
                throw malformed(scene.problem);
            }
            return scene;
        }

        private void admit(StoredAction action) throws IOException {
            if (!ACTIONS.contains(action.className)) {
                throw malformed("The action class " + action.className + " has no checked layout");
            }
            admit(action.export, action.className);
        }

        private void admit(int export, String className) throws IOException {
            var path = writer.getObjectPath(writer.export(export).classIndex());
            if (!path.equalsIgnoreCase("Engine." + className)) {
                throw malformed("Only Engine."
                        + className
                        + " has a checked layout, and "
                        + writer.exportName(export)
                        + " is of "
                        + path);
            }
        }

        private void claim(String part, int export, Object value) throws IOException {
            var other = claims.putIfAbsent(part + " " + export, value);
            if (other != null && !other.equals(value)) {
                throw malformed("Another edit gives this " + part + " other numbers, and scenes share it");
            }
        }

        private Block block(int export) throws IOException {
            var block = blocks.get(export);
            if (block == null) {
                block = Block.read(writer, export);
                blocks.put(export, block);
            }
            return block;
        }

        private void change(Block block, String field, Object before, Object now) throws IOException {
            changes.add(
                    new Change(writer.getObjectPath(block.export), field, String.valueOf(before), String.valueOf(now)));
        }

        private void readBack() throws IOException {
            for (var block : blocks.values()) {
                if (block.hasChanged()) {
                    writer.replace(block.export, block.getBytes());
                }
            }
            after = LobbySceneWriter.read(source.packageName, writer.getBody());
        }

        private Action read(LobbySceneEdit edit) throws IOException {
            for (var scene : after) {
                if (scene.tag().equalsIgnoreCase(edit.scene())) {
                    return scene.actions().get(edit.action());
                }
            }
            throw malformed("Scene " + edit.scene() + " does not read back");
        }

        private void verify() throws IOException {
            if (after.size() != scenes.size()) {
                throw malformed("The map does not read back with as many scenes as it had");
            }
            var touched = new HashSet<Integer>();
            for (var block : blocks.values()) {
                if (block.hasChanged()) {
                    touched.add(block.export);
                }
            }
            for (var i = 0; i < scenes.size(); i++) {
                var tag = scenes.get(i).tag();
                if (!scenes.get(i).equals(after.get(i)) && !reaches(tag, touched)) {
                    throw malformed("Scene " + tag + " changed, and no edit reaches it");
                }
            }
            for (var edit : applied) {
                if (!readsBack(edit)) {
                    throw malformed("Scene " + edit.scene() + " does not read back as it was written");
                }
            }
        }

        private boolean reaches(String tag, Set<Integer> touched) {
            for (var scene : stored.getOrDefault(key(tag), List.of())) {
                if (scene.holdsAny(touched)) {
                    return true;
                }
            }
            return false;
        }

        private boolean readsBack(LobbySceneEdit edit) throws IOException {
            var action = read(edit);
            return switch (edit) {
                case Point point -> {
                    var pose = point.pose();
                    yield isSame(
                                    floats(pose.x(), pose.y(), pose.z()),
                                    floats(
                                            action.to().x(),
                                            action.to().y(),
                                            action.to().z()))
                            && isSameTurn(action.to().yawDegrees(), pose.yawDegrees())
                            && isSameTurn(action.to().pitchDegrees(), pose.pitchDegrees());
                }
                case Time time ->
                    Math.abs(action.seconds() - time.seconds()) <= TIME_ROUNDING * Math.max(1, time.seconds());
                case PathStyle style -> action.style() == style.style();
                case Handles handles ->
                    closeTo(action.control1(), handles.control1()) && closeTo(action.control2(), handles.control2());
                case SubValues values -> isSameValues(action.subActions().get(values.index()), values.value());
            };
        }

        private boolean isSameValues(SubAction read, SubAction wanted) {
            return read.effect() == wanted.effect()
                    && (float) read.delay() == (float) wanted.delay()
                    && (float) read.seconds() == (float) wanted.seconds()
                    && (float) read.to() == (float) wanted.to()
                    && (Double.isNaN(wanted.from())
                            ? Double.isNaN(read.from())
                            : (float) read.from() == (float) wanted.from());
        }

        private boolean isValidView(double degrees) {
            return degrees > 0 && degrees < WIDEST_VIEW;
        }

        private boolean isValidSpeed(double speed) {
            return speed > 0 && Float.isFinite((float) speed);
        }
    }

    private record StoredScene(
            String tag,
            int export,
            List<StoredAction> actions,
            @Nullable String problem) {
        StoredScene against(@Nullable Scene read) {
            if (problem != null) {
                return this;
            }
            var agrees = read != null && read.actions().size() == actions.size();
            for (var i = 0; agrees && i < actions.size(); i++) {
                agrees = read.actions().get(i).subActions().size()
                        == actions.get(i).subs.size();
            }
            return agrees
                    ? this
                    : new StoredScene(tag, export, actions, "This scene reads in another way than it can be written");
        }

        boolean holdsAny(Set<Integer> exports) {
            for (var action : actions) {
                if (exports.contains(action.export) || exports.contains(action.point)) {
                    return true;
                }
                for (var sub : action.subs) {
                    if (exports.contains(sub.export)) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    private record StoredAction(int export, String className, int point, List<StoredSub> subs) {}

    private record StoredSub(int export, Effect effect) {}

    private static final class Block {
        private final int export;
        private final byte[] frame;
        private final TaggedProperties tags;
        private final byte[] storedTags;

        private Block(int export, byte[] frame, TaggedProperties tags) {
            this.export = export;
            this.frame = frame;
            this.tags = tags;
            storedTags = tags.toBytes();
        }

        static Block read(ClassPackageWriter writer, int export) throws IOException {
            var payload = writer.getPayload(export);
            var cursor = new Cursor(payload, 0, payload.length);
            if ((writer.export(export).flags() & HAS_STACK) != 0) {
                var node = cursor.readIndex();
                cursor.readIndex();
                cursor.skip(FRAME_MIDDLE);
                if (node != 0) {
                    cursor.readIndex();
                }
            }
            var start = cursor.position;
            var tags = TaggedProperties.read(cursor, writer);
            if (cursor.position != cursor.end) {
                throw malformed("Object " + writer.exportName(export)
                        + " has bytes after its tags, which no scene object of the clients has");
            }
            if (!Arrays.equals(tags.toBytes(), Arrays.copyOfRange(payload, start, cursor.position))) {
                throw malformed("Object " + writer.exportName(export)
                        + " does not rewrite byte for byte, so it stays as it is");
            }
            return new Block(export, Arrays.copyOf(payload, start), tags);
        }

        boolean hasChanged() {
            return !Arrays.equals(storedTags, tags.toBytes());
        }

        byte[] getBytes() {
            var tagged = tags.toBytes();
            var result = Arrays.copyOf(frame, frame.length + tagged.length);
            System.arraycopy(tagged, 0, result, frame.length, tagged.length);
            return result;
        }
    }
}
