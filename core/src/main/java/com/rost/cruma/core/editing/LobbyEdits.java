package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.LobbySceneEdit.Handles;
import com.rost.cruma.core.editing.LobbySceneEdit.PathStyle;
import com.rost.cruma.core.editing.LobbySceneEdit.Point;
import com.rost.cruma.core.editing.LobbySceneEdit.SubValues;
import com.rost.cruma.core.editing.LobbySceneEdit.Time;
import com.rost.cruma.core.world.CameraPath;
import com.rost.cruma.core.world.CameraPath.Key;
import com.rost.cruma.core.world.GameScenes.Item;
import com.rost.cruma.core.world.GameScenes.ScreenKind;
import com.rost.cruma.core.world.LobbyPath;
import com.rost.cruma.core.world.LobbyPath.Clock;
import com.rost.cruma.core.world.LobbyPath.Sub;
import com.rost.cruma.core.world.MapScenes.Action;
import com.rost.cruma.core.world.MapScenes.Effect;
import com.rost.cruma.core.world.MapScenes.Kind;
import com.rost.cruma.core.world.MapScenes.Pose;
import com.rost.cruma.core.world.MapScenes.Scene;
import com.rost.cruma.core.world.MapScenes.Style;
import com.rost.cruma.core.world.MapScenes.SubAction;
import com.rost.cruma.core.world.MapScenes.Vector;
import com.rost.cruma.core.world.SceneDocument;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import org.jetbrains.annotations.Nullable;

public record LobbyEdits(
        LobbySceneCheck check,
        List<LobbySceneEdit> edits,
        List<String> refused,
        List<String> stays,
        List<String> notes) {
    private static final double SAME_MOMENT = 1e-5;
    private static final double SAME_PLACE = 1e-3;
    private static final double SAME_FOV = 1e-4;
    private static final double UNITS_PER_DEGREE = 65536.0 / 360;
    private static final double FAR_AWAY = 1e7;

    public LobbyEdits {
        edits = List.copyOf(edits);
        refused = List.copyOf(refused);
        stays = List.copyOf(stays);
        notes = List.copyOf(notes);
    }

    public static LobbyEdits of(SceneDocument document, LobbySceneWriter writer) throws IOException {
        return new Run(document, writer).run();
    }

    public boolean canWrite() {
        return refused.isEmpty() && check.canWrite();
    }

    private static String name(int place) {
        return "Key " + (place + 1);
    }

    private static final class Run {
        private final SceneDocument document;
        private final LobbySceneWriter writer;
        private final String tag;
        private final List<LobbySceneEdit> edits = new ArrayList<>();
        private final List<String> refused = new ArrayList<>();
        private final LinkedHashSet<String> stays = new LinkedHashSet<>();
        private final List<String> notes = new ArrayList<>();

        Run(SceneDocument document, LobbySceneWriter writer) {
            this.document = document;
            this.writer = writer;
            tag = document.basedOn();
        }

        LobbyEdits run() throws IOException {
            var original = scene(writer.getScenes());
            var path = document.path();
            if (original == null) {
                refused.add("This map has no scene " + tag
                        + ". The interface of the game picks a scene by its Tag, so a new one would never play.");
            } else if (path == null) {
                refused.add("The path of this scene is gone, and a map keeps the points of its scenes.");
            } else {
                walk(original, path);
            }
            items();
            var check = writer.check(edits);
            if (original != null && path != null && refused.isEmpty()) {
                compare(check, original, path);
            }
            return new LobbyEdits(check, edits, refused, List.copyOf(stays), notes);
        }

        private void walk(Scene original, CameraPath path) throws IOException {
            var reference = LobbyPath.keys(original, 0);
            var map = new CameraPath(reference, false);
            var stops = path.getStops();
            if (!isSameStops(path, stops, map.getStops())) {
                return;
            }
            var points = points(original, stops, map.getStops());
            if (!refused.isEmpty()) {
                return;
            }
            edits.addAll(points);
            var after = scene(writer.check(points), original);
            var seconds = ways(after, path, stops);
            var timed = LobbyPath.retimed(after, seconds);
            subs(original, timed, path, map);
        }

        private boolean isSameStops(CameraPath path, List<Key> stops, List<Key> wanted) {
            for (var index = 0; index < path.getKeys().size(); index++) {
                var key = path.getKeys().get(index);
                if (key.link() == null) {
                    refused.add(name(index)
                            + " is new. A new point would be a new actor of the map, so its keys have to stay"
                            + " those of the copy.");
                }
            }
            if (!refused.isEmpty()) {
                return false;
            }
            var actions = new ArrayList<Integer>(stops.size());
            for (var key : stops) {
                actions.add(key.rides() ? -1 : key.link().action());
            }
            var mapActions = new ArrayList<Integer>(wanted.size());
            for (var key : wanted) {
                mapActions.add(key.link().action());
            }
            if (actions.equals(mapActions)) {
                return true;
            }
            if (actions.size() < mapActions.size() || actions.contains(-1)) {
                refused.add(
                        "A key on a point of the map was taken away. A map keeps its points, so bring the key back.");
            } else {
                refused.add("Two keys passed each other. A map keeps the order of its points.");
            }
            return false;
        }

        private List<LobbySceneEdit> points(Scene original, List<Key> stops, List<Key> wanted) {
            var points = new ArrayList<LobbySceneEdit>();
            var byAction = new HashMap<Integer, Integer>();
            for (var index = 0; index < stops.size(); index++) {
                var key = stops.get(index);
                var action = key.link().action();
                var first = byAction.putIfAbsent(action, index);
                if (first != null) {
                    if (!isSamePose(pose(key), pose(stops.get(first)))) {
                        refused.add(name(first) + " and " + name(index).toLowerCase(Locale.ROOT)
                                + " stand on one point of the map. Give them the same place and turn.");
                    }
                    continue;
                }
                if (!isSamePose(pose(key), pose(wanted.get(index)))) {
                    points.add(new Point(original.tag(), action, pose(key)));
                }
            }
            return points;
        }

        private double[] ways(Scene after, CameraPath path, List<Key> stops) {
            var actions = after.actions();
            var seconds = new double[actions.size()];
            for (var place = 0; place < seconds.length; place++) {
                seconds[place] = actions.get(place).seconds();
            }
            for (var index = 1; index < stops.size(); index++) {
                var place = stops.get(index).link().action();
                curve(actions.get(place), place, path.getWays().get(index - 1), index);
                var wanted = stops.get(index).seconds() - stops.get(index - 1).seconds();
                var solved = solve(after, seconds, place, wanted);
                if (Math.abs(solved - seconds[place]) > SAME_MOMENT) {
                    seconds[place] = solved;
                    if (after.warp()) {
                        refused.add("A warp ends at once, so its keys have no time.");
                    } else {
                        edits.add(new Time(tag, place, solved));
                    }
                }
            }
            return seconds;
        }

        private void curve(Action action, int place, Action way, int index) {
            var straight = isStraight(way);
            if (action.kind() != Kind.MOVE) {
                if (!straight) {
                    refused.add(name(index) + " has a curve, and only a move of the map can have one.");
                }
                return;
            }
            if (straight) {
                if (action.style() == Style.BEZIER && !isSame(way, action)) {
                    edits.add(new PathStyle(tag, place, Style.LINEAR));
                }
                return;
            }
            if (action.style() != Style.BEZIER) {
                edits.add(new PathStyle(tag, place, Style.BEZIER));
            }
            if (action.style() != Style.BEZIER || !isSame(way, action)) {
                edits.add(new Handles(
                        tag,
                        place,
                        action.from().place().plus(leaves(way)),
                        action.to().place().plus(reaches(way))));
            }
        }

        private void subs(Scene original, Scene timed, CameraPath path, CameraPath map) {
            var before = LobbyPath.clock(original);
            var after = LobbyPath.clock(timed);
            var starts = starts(timed);
            var marked = new ArrayList<Key>();
            for (var sub : LobbyPath.subs(original)) {
                var value = sub.value();
                var edited = switch (value.effect()) {
                    case FOV -> fov(sub, before, after, starts, path, map, marked);
                    case FADE -> fade(sub, before, after, starts);
                    case SCENE_SPEED -> value;
                };
                if (edited != null && !isSame(edited, value)) {
                    edits.add(new SubValues(tag, sub.action(), sub.index(), edited));
                }
            }
            for (var index = 0; index < path.getKeys().size(); index++) {
                var key = path.getKeys().get(index);
                if (Math.abs(key.fov() - key.link().fov()) > SAME_FOV && !marked.contains(key)) {
                    refused.add(name(index)
                            + " has a field of view of its own, and no sub-action of the map starts or ends there.");
                }
            }
        }

        private @Nullable SubAction fov(
                Sub sub,
                Clock before,
                Clock after,
                double[] starts,
                CameraPath path,
                CameraPath map,
                List<Key> marked) {
            var length = map.getEnd();
            var first = before.toReal(sub.start());
            var last = before.toReal(sub.end());
            var clipped = last > length + SAME_MOMENT;
            var start = key(path, keyAt(map, Math.min(first, length), false));
            var end = key(path, keyAt(map, Math.min(last, length), true));
            if (start == null || end == null) {
                refused.add("A key of a change of the field of view was taken away, and a map keeps its sub-actions.");
                return null;
            }
            marked.add(start);
            marked.add(end);
            var value = sub.value();
            var from = hasChanged(start) ? start.fov() : value.from();
            var to = value.to();
            if (hasChanged(end)) {
                var held = Double.isNaN(from) ? start.fov() : from;
                var share = clipped ? (length - first) / (last - first) : 1;
                to = held + (end.fov() - held) / share;
            }
            var moment = after.toScene(start.seconds());
            var seconds = clipped ? value.seconds() : after.toScene(end.seconds()) - moment;
            return new SubAction(Effect.FOV, Math.max(0, moment - starts[sub.action()]), seconds, from, to);
        }

        private @Nullable SubAction fade(Sub sub, Clock before, Clock after, double[] starts) {
            var name = LobbyPath.fadeName(sub.action(), sub.index());
            for (var item : document.items()) {
                if (name.equals(item.source())) {
                    return fade(sub, item, after, starts);
                }
            }
            if (sub.start() >= new CameraPath(LobbyPath.keys(scene(), 0), false).getEnd()) {
                return sub.value();
            }
            refused.add("The fade at " + seconds(before.toReal(sub.start()))
                    + " is gone, and a map keeps its sub-actions.");
            return null;
        }

        private Scene scene() {
            return scene(writer.getScenes());
        }

        private @Nullable SubAction fade(Sub sub, Item item, Clock after, double[] starts) {
            var screen = item.screen();
            if (screen == null || screen.kind() != ScreenKind.FADE) {
                refused.add("The fade at " + seconds(item.seconds())
                        + " is no fade any more, and a map keeps its sub-actions.");
                return null;
            }
            if (screen.color() != 0) {
                refused.add("The fade at " + seconds(item.seconds()) + " has a colour, and a fade of a map is black.");
                return null;
            }
            if (screen.fadeOut() > 0 && screen.fadeIn() > 0) {
                refused.add("The fade at " + seconds(item.seconds())
                        + " goes to black and back, and a fade of a map goes one way.");
                return null;
            }
            var toBlack = screen.fadeIn() <= 0
                    && (screen.fadeOut() > 0 || sub.value().to() >= sub.value().from());
            var length = toBlack ? screen.fadeOut() : screen.fadeIn();
            if (holdStays(item, screen.hold(), length, toBlack)) {
                stays.add("the hold of a fade");
            }
            var moment = after.toScene(item.seconds());
            if (moment < starts[sub.action()] - SAME_MOMENT) {
                refused.add("The fade at " + seconds(item.seconds())
                        + " starts before the action that holds it, and a delay cannot be below 0.");
                return null;
            }
            return new SubAction(
                    Effect.FADE,
                    Math.max(0, moment - starts[sub.action()]),
                    after.toScene(item.seconds() + length) - moment,
                    toBlack ? 0 : 1,
                    toBlack ? 1 : 0);
        }

        private boolean holdStays(Item item, double hold, double length, boolean toBlack) {
            if (!toBlack) {
                return hold > SAME_MOMENT;
            }
            for (var other : document.items()) {
                if (other != item && other.seconds() >= item.seconds() && LobbyPath.fadeOf(other.source()) != null) {
                    return false;
                }
            }
            return item.seconds() + length + hold < document.seconds() - SAME_MOMENT;
        }

        private void items() {
            if (!Double.isNaN(document.hour())) {
                stays.add("the hour");
            }
            var fades = 0;
            for (var item : document.items()) {
                if (item.screen() != null
                        && item.screen().kind() == ScreenKind.FADE
                        && LobbyPath.fadeOf(item.source()) == null) {
                    fades++;
                } else if (item.screen() != null && item.screen().kind() != ScreenKind.FADE) {
                    stays.add("what shows on the screen");
                }
                if (!item.actors().isEmpty()) {
                    stays.add("actors");
                }
                if (item.music() != null) {
                    stays.add("sound");
                }
                if (item.camera() != null && !item.camera().blank()) {
                    stays.add("shots");
                }
            }
            if (fades > 0) {
                stays.add(fades == 1 ? "a new fade" : fades + " new fades");
            }
            var path = document.path();
            if (path != null) {
                for (var key : path.getKeys()) {
                    if (key.roll() != 0) {
                        stays.add("the roll of the keys");
                    }
                    if (key.lookAt() != 0) {
                        stays.add("Look at");
                    }
                }
            }
        }

        private void compare(LobbySceneCheck check, Scene original, CameraPath path) {
            var after = scene(check, original);
            var stops = path.getStops();
            var wanted = new CameraPath(LobbyPath.keys(after, 0), false).getStops();
            for (var index = 0; index < Math.min(stops.size(), wanted.size()); index++) {
                if (!isSamePose(pose(stops.get(index)), pose(wanted.get(index)))) {
                    notes.add(name(index) + " moves too, as its point of the map is the point of another key.");
                }
            }
        }

        private @Nullable Scene scene(List<Scene> scenes) {
            for (var scene : scenes) {
                if (scene.tag().equalsIgnoreCase(tag)) {
                    return scene;
                }
            }
            return null;
        }

        private Scene scene(LobbySceneCheck check, Scene original) {
            for (var change : check.scenes()) {
                if (change.tag().equalsIgnoreCase(tag)) {
                    return change.after();
                }
            }
            return original;
        }

        private static Pose pose(Key key) {
            return new Pose(key.x(), key.y(), key.z(), key.yaw(), key.pitch());
        }

        private static boolean isSamePose(Pose first, Pose second) {
            return (float) first.x() == (float) second.x()
                    && (float) first.y() == (float) second.y()
                    && (float) first.z() == (float) second.z()
                    && units(first.yawDegrees()) == units(second.yawDegrees())
                    && units(first.pitchDegrees()) == units(second.pitchDegrees());
        }

        private static int units(double degrees) {
            return Math.floorMod(Math.round(degrees * UNITS_PER_DEGREE), 65536);
        }

        private static double solve(Scene after, double[] seconds, int place, double wanted) {
            var start = 0.0;
            for (var index = 0; index < place; index++) {
                start += seconds[index];
            }
            var held = LobbyPath.clock(LobbyPath.retimed(after, seconds));
            if (Math.abs(held.toReal(start + seconds[place]) - held.toReal(start) - wanted) <= SAME_MOMENT) {
                return seconds[place];
            }
            var free = seconds.clone();
            free[place] = FAR_AWAY;
            var clock = LobbyPath.clock(LobbyPath.retimed(after, free));
            return clock.toScene(clock.toReal(start) + wanted) - start;
        }

        private static Vector leaves(Action way) {
            return way.control1().minus(way.from().place());
        }

        private static Vector reaches(Action way) {
            return way.control2().minus(way.to().place());
        }

        private static boolean isStraight(Action way) {
            var third = way.to().place().minus(way.from().place()).times(1.0 / 3);
            return way.control1().distance(way.from().place().plus(third)) < SAME_PLACE
                    && way.control2().distance(way.to().place().minus(third)) < SAME_PLACE;
        }

        private static boolean isSame(Action way, Action action) {
            return leaves(way).distance(leaves(action)) < SAME_PLACE
                    && reaches(way).distance(reaches(action)) < SAME_PLACE;
        }

        private static double[] starts(Scene scene) {
            var starts = new double[scene.actions().size()];
            for (var place = 1; place < starts.length; place++) {
                starts[place] =
                        starts[place - 1] + scene.actions().get(place - 1).seconds();
            }
            return starts;
        }

        private static boolean isSame(SubAction first, SubAction second) {
            return Math.abs(first.delay() - second.delay()) <= SAME_MOMENT
                    && Math.abs(first.seconds() - second.seconds()) <= SAME_MOMENT
                    && isSameValue(first.from(), second.from())
                    && isSameValue(first.to(), second.to());
        }

        private static boolean isSameValue(double first, double second) {
            return Double.compare(first, second) == 0 || Math.abs(first - second) <= SAME_FOV;
        }

        private static boolean hasChanged(Key key) {
            return Math.abs(key.fov() - key.link().fov()) > SAME_FOV;
        }

        private static @Nullable Key keyAt(CameraPath map, double seconds, boolean earlier) {
            Key found = null;
            for (var key : map.getKeys()) {
                if (Math.abs(key.seconds() - seconds) <= SAME_MOMENT && (found == null || !earlier)) {
                    found = key;
                }
            }
            return found;
        }

        private static @Nullable Key key(CameraPath path, @Nullable Key wanted) {
            if (wanted == null) {
                return null;
            }
            for (var key : path.getKeys()) {
                var link = key.link();
                if (link.action() == wanted.link().action()
                        && Math.abs(link.along() - wanted.link().along()) < 1e-9) {
                    return key;
                }
            }
            return null;
        }

        private static String seconds(double value) {
            return String.format(Locale.ROOT, "%.2f s", value);
        }
    }
}
