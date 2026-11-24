package com.rost.cruma.viewer.agent.film;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.core.world.CameraPath;
import com.rost.cruma.core.world.CameraPath.Ease;
import com.rost.cruma.core.world.CameraPath.Key;
import com.rost.cruma.core.world.CameraPath.Places;
import com.rost.cruma.core.world.CameraPath.Pose;
import com.rost.cruma.core.world.MapScenes.Vector;
import com.rost.cruma.core.world.SceneDocument;
import com.rost.cruma.core.world.SceneOrders;
import com.rost.cruma.viewer.agent.Target;
import com.rost.cruma.viewer.agent.ToolArgs;
import com.rost.cruma.viewer.agent.ToolProblem;
import com.rost.cruma.viewer.config.Config.Viewer.Camera.Location;
import com.rost.cruma.viewer.ui.QuickStarts;
import com.rost.cruma.viewer.ui.QuickStarts.Direction;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import org.jetbrains.annotations.Nullable;

final class FilmKeys {
    static final double DEFAULT_FOV = 60;

    private static final String WHERE_NEW = "Key %d";
    private static final String WHERE_ADDED = "add_keys %d";
    private static final String WHERE_MOVED = "move_keys %d";
    private static final double LEAST_QUICK = .5;
    private static final double MOST_QUICK = 600;
    private static final double LEAST_TURNS = .25;
    private static final double MOST_TURNS = 4;
    private static final double REVEAL_DISTANCE = 1_500;
    private static final double LEAST_DISTANCE = 100;
    private static final double MOST_DISTANCE = 20_000;
    private static final double REVEAL_RISE = 300;
    private static final double MOST_RISE = 5_000;

    private final List<Location> places;

    FilmKeys(List<Location> places) {
        this.places = places;
    }

    record Edited(SceneDocument document, List<String> notes) {}

    static double[] look(double fromX, double fromY, double fromZ, double toX, double toY, double toZ) {
        var x = toX - fromX;
        var y = toY - fromY;
        var z = toZ - fromZ;
        return new double[] {Math.toDegrees(Math.atan2(y, x)), Math.toDegrees(Math.atan2(z, Math.hypot(x, y)))};
    }

    Edited create(String name, String client, String variant, long seed, ToolArgs args) throws ToolProblem {
        var keys = keys(args.getJson().getJSONArray("keys"), WHERE_NEW, Set.of());
        var length = length(args);
        var quick = args.getJson().getJSONObject("quick");
        if (keys.isEmpty() && length == null && quick == null) {
            throw new ToolProblem("A new scene needs keys, quick or a length.");
        }
        var path = keys.isEmpty() ? null : new CameraPath(keys, args.readFlag("even_speed", false));
        var seconds = length != null
                ? length
                : path != null
                        ? path.getEnd()
                        : quick.getDoubleValue("time") + Math.max(LEAST_QUICK, quick.getDoubleValue("seconds"));
        if (seconds <= 0) {
            throw new ToolProblem("The scene has no length: its last key stands at 0. Give length, or a later time.");
        }
        var document = SceneDocument.blank(name, seconds, client, variant, seed).withPath(path);
        return new Edited(document, notes(document, length, path));
    }

    Edited change(SceneDocument document, ToolArgs args) throws ToolProblem {
        var actors = actors(document);
        var path = document.path();
        var keys = new ArrayList<>(path == null ? List.<Key>of() : path.getKeys());
        var replaced = args.getJson().getJSONArray("keys");
        if (replaced != null) {
            keys = new ArrayList<>(keys(replaced, WHERE_NEW, actors));
        }
        move(keys, args.getJson().getJSONArray("move_keys"), actors);
        remove(keys, args.getJson().getJSONArray("remove_keys"));
        keys.addAll(keys(args.getJson().getJSONArray("add_keys"), WHERE_ADDED, actors));
        var even = args.readFlag("even_speed", path != null && path.hasEvenSpeed());
        var next = keys.isEmpty() ? null : new CameraPath(keys, even);
        var changed = document.withPath(next);
        var length = length(args);
        if (length != null) {
            changed = changed.withSeconds(length);
        }
        return new Edited(changed, notes(changed, length, next));
    }

    Edited applyQuick(Edited edited, ToolArgs quick, FilmLookup client) throws ToolProblem {
        var document = edited.document();
        var path = document.path();
        if (path != null && path.hasEvenSpeed()) {
            throw new ToolProblem(
                    "quick: even speed spreads the keys along the path, so it has one shot. Set even_speed false"
                            + " first.");
        }
        var kind = quick.getRequired("kind").toLowerCase(Locale.ROOT);
        var seconds = quick.getNumber("seconds", 0);
        if (!(seconds >= LEAST_QUICK && seconds <= MOST_QUICK)) {
            throw new ToolProblem("quick: seconds must be from " + LEAST_QUICK + " to " + MOST_QUICK + ".");
        }
        var start = path != null ? path.getEnd() : quick.getNumber("time", 0);
        if (!(start >= 0)) {
            throw new ToolProblem("quick: time must be 0 or more seconds.");
        }
        Places places = (index, moment) -> {
            var at = FilmRead.placeAt(document, index, moment, client);
            return at == null ? null : new Vector(at[0], at[1], at[2]);
        };
        var orbit = kind.equals("orbit");
        Pose view;
        if (quick.has("from")) {
            view = pose(quick, "from", orbit);
        } else if (path != null) {
            view = path.getPose(path.getEnd(), places);
        } else {
            throw new ToolProblem("quick: the path is empty, so give from: where the camera starts, as a key.");
        }
        List<Key> made = switch (kind) {
            case "orbit" -> orbit(quick, view, document, start, client);
            case "fly_to" -> {
                if (!quick.has("to")) {
                    throw new ToolProblem("quick: fly_to needs to: where the flight ends, as a key.");
                }
                yield QuickStarts.flyTo(view, pose(quick, "to", false), seconds);
            }
            case "reveal" ->
                QuickStarts.reveal(
                        view,
                        Math.clamp(quick.getNumber("distance", REVEAL_DISTANCE), LEAST_DISTANCE, MOST_DISTANCE),
                        Math.clamp(quick.getNumber("rise", REVEAL_RISE), 0, MOST_RISE),
                        seconds);
            default -> throw new ToolProblem("quick: kind must be orbit, fly_to or reveal.");
        };
        var next = QuickStarts.withShot(document, made, start);
        var notes = new ArrayList<>(edited.notes());
        notes.add(String.format(
                Locale.ROOT,
                "The %s made %d keys, from %.2f s to %.2f s, as a new shot.",
                kind,
                made.size(),
                start,
                start + made.getLast().seconds()));
        return new Edited(next, notes);
    }

    private List<Key> orbit(ToolArgs quick, Pose view, SceneDocument document, double start, FilmLookup client)
            throws ToolProblem {
        var actor = quick.getInteger("actor", 0);
        Vector center;
        if (actor > 0) {
            var at = FilmRead.placeAt(document, actor, start, client);
            if (at == null) {
                throw new ToolProblem("quick: this scene has no actor " + actor + ". Call film_read for its cast.");
            }
            var entry = SceneOrders.entry(document.items(), actor);
            center = new Vector(at[0], at[1], at[2] + (entry != null && entry.fly() ? 0 : QuickStarts.POINT_LIFT));
        } else {
            var around = quick.getText("around");
            if (around == null) {
                throw new ToolProblem("quick: an orbit needs around, a place or point, or actor, an Index.");
            }
            var point = point(around, "quick around");
            center = QuickStarts.overFloor(new Vector(point[0], point[1], point[2]));
        }
        if (QuickStarts.radius(view, center) < QuickStarts.LEAST_RADIUS) {
            throw new ToolProblem(
                    "quick: the camera stands right over the centre. Give from aside of it, as the distance across is"
                            + " the radius.");
        }
        var direction = switch (quick.getText("direction", "left").toLowerCase(Locale.ROOT)) {
            case "left", "clockwise" -> Direction.LEFT;
            case "right", "counterclockwise", "anticlockwise" -> Direction.RIGHT;
            default -> throw new ToolProblem("quick: direction must be left or right.");
        };
        return QuickStarts.orbit(
                view,
                center,
                Math.max(actor, 0),
                Math.clamp(quick.getNumber("turns", 1), LEAST_TURNS, MOST_TURNS),
                direction,
                quick.getNumber("seconds", 0));
    }

    private Pose pose(ToolArgs quick, String name, boolean placeOnly) throws ToolProblem {
        if (!(quick.getJson().get(name) instanceof JSONObject object)) {
            throw new ToolProblem("quick: " + name
                    + " must be a key, such as {\"target\": \"83000,148000,-2500\", \"look_at\":"
                    + " \"83300,148000,-3000\"}.");
        }
        var key = new ToolArgs(object);
        var where = "quick " + name;
        var eye = place(key, where);
        var look = placeOnly && !key.has("look_at") && !key.has("yaw") && !key.has("pitch")
                ? new double[] {0, 0}
                : look(key, eye, null, where, Set.of());
        return new Pose(
                eye[0], eye[1], eye[2], look[0], look[1], key.getNumber("roll", 0), fov(key, DEFAULT_FOV, where));
    }

    private List<Key> keys(@Nullable JSONArray items, String where, Set<Integer> actors) throws ToolProblem {
        var keys = new ArrayList<Key>();
        if (items == null) {
            return keys;
        }
        for (var index = 0; index < items.size(); index++) {
            var name = where.formatted(index);
            keys.add(read(item(items, index, name), name, actors));
        }
        return keys;
    }

    private void move(List<Key> keys, @Nullable JSONArray items, Set<Integer> actors) throws ToolProblem {
        if (items == null) {
            return;
        }
        for (var index = 0; index < items.size(); index++) {
            var name = WHERE_MOVED.formatted(index);
            var item = item(items, index, name);
            var number = number(item.getNumber("index"), keys.size(), name);
            keys.set(number, changed(keys.get(number), item, name, actors));
        }
    }

    private Key read(ToolArgs key, String where, Set<Integer> actors) throws ToolProblem {
        var time = key.getNumber("time");
        if (time == null) {
            throw new ToolProblem(where + " has no time, in seconds from the start of the scene.");
        }
        var eye = place(key, where);
        var look = look(key, eye, null, where, actors);
        return new Key(
                seconds(time, where),
                eye[0],
                eye[1],
                eye[2],
                look[0],
                look[1],
                key.getNumber("roll", 0),
                fov(key, DEFAULT_FOV, where),
                ease(key, Ease.SMOOTH, where),
                actor(key, 0, where, actors));
    }

    private Key changed(Key old, ToolArgs key, String where, Set<Integer> actors) throws ToolProblem {
        if (old.rides() && (key.has("target") || key.has("time"))) {
            throw new ToolProblem(where
                    + ": this key rides on a way of a lobby scene, and the path gives its place and moment."
                    + " Change the keys beside it.");
        }
        var eye = key.has("target")
                ? place(key, where)
                : new double[] {old.x(), old.y(), old.z() + key.getNumber("above", 0)};
        var look = look(key, eye, old, where, actors);
        var moved = old.with(
                        eye[0],
                        eye[1],
                        eye[2],
                        look[0],
                        look[1],
                        key.getNumber("roll", old.roll()),
                        fov(key, old.fov(), where))
                .withEase(ease(key, old.ease(), where))
                .withLookAt(actor(key, old.lookAt(), where, actors));
        var time = key.getNumber("time");
        return time == null ? moved : moved.withSeconds(seconds(time, where));
    }

    private double[] place(ToolArgs key, String where) throws ToolProblem {
        var text = key.getText("target");
        if (text == null) {
            throw new ToolProblem(where + " has no target: a place, or a point x,y,z where the camera stands.");
        }
        var point = point(text, where);
        return new double[] {point[0], point[1], point[2] + key.getNumber("above", 0)};
    }

    private double[] point(String text, String where) throws ToolProblem {
        var target = Target.parse(text);
        return switch (target.kind()) {
            case POINT, TILE -> {
                if (target.z() == null) {
                    throw new ToolProblem(where + ": " + text + " has no height. Give a point x,y,z.");
                }
                yield new double[] {target.x(), target.y(), target.z()};
            }
            case NAME -> {
                var place = Target.place(places, text);
                if (place.isEmpty()) {
                    throw new ToolProblem(where + ": there is no place named \"" + text
                            + "\". Call place_find, or give a point x,y,z.");
                }
                yield new double[] {
                    place.get().x(), place.get().y(), place.get().z()
                };
            }
            default ->
                throw new ToolProblem(where + ": " + text
                        + " needs an open world, and a film is written without one. Give a place or a point x,y,z.");
        };
    }

    private double[] look(ToolArgs key, double[] eye, @Nullable Key old, String where, Set<Integer> actors)
            throws ToolProblem {
        var target = key.getText("look_at");
        if (target != null) {
            if (key.has("yaw") || key.has("pitch")) {
                throw new ToolProblem(where + " has look_at and yaw or pitch. Give one of them.");
            }
            var point = point(target, where);
            if (point[0] == eye[0] && point[1] == eye[1] && point[2] == eye[2]) {
                throw new ToolProblem(
                        where + " looks at the point it stands on. Give it another point, or yaw and pitch.");
            }
            return look(eye[0], eye[1], eye[2], point[0], point[1], point[2]);
        }
        if (old != null) {
            return new double[] {key.getNumber("yaw", old.yaw()), key.getNumber("pitch", old.pitch())};
        }
        if (!key.has("yaw") && !key.has("pitch") && actor(key, 0, where, actors) == 0) {
            throw new ToolProblem(where + " does not say where it looks. Give look_at, or yaw and pitch.");
        }
        return new double[] {key.getNumber("yaw", 0), key.getNumber("pitch", 0)};
    }

    private static List<String> notes(SceneDocument document, @Nullable Double length, @Nullable CameraPath path) {
        var notes = new ArrayList<String>();
        if (length != null && document.seconds() > length) {
            notes.add(String.format(
                    Locale.ROOT,
                    "The scene ends at %.2f s and not at %.2f s, as it holds something until then.",
                    document.seconds(),
                    length));
        }
        if (path != null && path.hasEvenSpeed() && path.getKeys().size() > 2) {
            notes.add(
                    "even_speed gave the keys between the first and the last new times, from the length of the path.");
        }
        return notes;
    }

    private static @Nullable Double length(ToolArgs args) throws ToolProblem {
        var length = args.getNumber("length");
        if (length != null && !(length > 0 && Double.isFinite(length))) {
            throw new ToolProblem("length must be more than 0 seconds.");
        }
        return length;
    }

    private static void remove(List<Key> keys, @Nullable JSONArray items) throws ToolProblem {
        if (items == null) {
            return;
        }
        var numbers = new TreeSet<Integer>();
        for (var index = 0; index < items.size(); index++) {
            if (!(items.get(index) instanceof Number number)) {
                throw new ToolProblem("remove_keys must be a list of key numbers.");
            }
            numbers.add(number((double) number.intValue(), keys.size(), "remove_keys " + index));
        }
        for (var number : numbers.descendingSet()) {
            if (keys.get(number).rides()) {
                throw new ToolProblem("Key " + number
                        + " rides on a way of a lobby scene, and the path gives its place. Change the keys beside it.");
            }
            keys.remove((int) number);
        }
    }

    private static int number(@Nullable Double index, int size, String where) throws ToolProblem {
        if (index == null) {
            throw new ToolProblem(where + " has no index.");
        }
        var number = (int) Math.round(index);
        if (number < 0 || number >= size) {
            throw new ToolProblem(where + " names key " + number + ", and the scene has " + size + " keys, from 0.");
        }
        return number;
    }

    private static ToolArgs item(JSONArray items, int index, String where) throws ToolProblem {
        if (!(items.get(index) instanceof JSONObject object)) {
            throw new ToolProblem(where + " must be an object.");
        }
        return new ToolArgs(object);
    }

    private static double seconds(double time, String where) throws ToolProblem {
        if (!(time >= 0) || !Double.isFinite(time)) {
            throw new ToolProblem(where + ": time must be 0 or more seconds.");
        }
        return time;
    }

    private static double fov(ToolArgs key, double fallback, String where) throws ToolProblem {
        var fov = key.getNumber("fov", fallback);
        if (!(fov > 0 && fov < 180)) {
            throw new ToolProblem(where + ": fov must be between 0 and 180 degrees.");
        }
        return fov;
    }

    private static Ease ease(ToolArgs key, Ease fallback, String where) throws ToolProblem {
        var text = key.getText("ease");
        if (text == null) {
            return fallback;
        }
        return switch (text.toLowerCase(Locale.ROOT)) {
            case "smooth" -> Ease.SMOOTH;
            case "linear" -> Ease.LINEAR;
            default -> throw new ToolProblem(where + ": ease must be smooth or linear.");
        };
    }

    private static int actor(ToolArgs key, int fallback, String where, Set<Integer> actors) throws ToolProblem {
        var asked = key.getNumber("actor");
        if (asked == null) {
            return fallback;
        }
        var index = (int) Math.round(asked);
        if (index != 0 && !actors.contains(index)) {
            throw new ToolProblem(where
                    + ": this scene has no actor "
                    + index
                    + (actors.isEmpty()
                            ? ". It has no actors. Use look_at for a place."
                            : ". It has " + new TreeSet<>(actors) + "."));
        }
        return index;
    }

    private static Set<Integer> actors(SceneDocument document) {
        var actors = new HashSet<Integer>();
        for (var item : document.items()) {
            for (var actor : item.actors()) {
                actors.add(actor.index());
            }
        }
        return actors;
    }
}
