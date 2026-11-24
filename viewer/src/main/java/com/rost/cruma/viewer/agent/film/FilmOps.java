package com.rost.cruma.viewer.agent.film;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.core.spawn.PlayerEquipment.Slot;
import com.rost.cruma.core.world.GameScenes.Action;
import com.rost.cruma.core.world.GameScenes.Actor;
import com.rost.cruma.core.world.GameScenes.Item;
import com.rost.cruma.core.world.GameScenes.Music;
import com.rost.cruma.core.world.GameScenes.Screen;
import com.rost.cruma.core.world.GameScenes.ScreenKind;
import com.rost.cruma.core.world.GameScenes.Visual;
import com.rost.cruma.core.world.PlayerFigures;
import com.rost.cruma.core.world.SceneDocument;
import com.rost.cruma.core.world.SceneFields;
import com.rost.cruma.core.world.SceneFields.Field;
import com.rost.cruma.core.world.SceneFields.Kind;
import com.rost.cruma.core.world.SceneFit;
import com.rost.cruma.core.world.SceneItems;
import com.rost.cruma.core.world.SceneItems.Key;
import com.rost.cruma.core.world.SceneItems.Part;
import com.rost.cruma.core.world.SceneOrders;
import com.rost.cruma.core.world.SceneOrders.Fix;
import com.rost.cruma.core.world.SceneOrders.Problem;
import com.rost.cruma.core.world.SceneOrders.Warning;
import com.rost.cruma.core.world.SceneScreens;
import com.rost.cruma.core.world.SoundSpans;
import com.rost.cruma.viewer.agent.Target;
import com.rost.cruma.viewer.agent.ToolArgs;
import com.rost.cruma.viewer.agent.ToolProblem;
import com.rost.cruma.viewer.agent.film.FilmLookup.Picture;
import com.rost.cruma.viewer.config.Config.Viewer.Camera.Location;
import com.rost.cruma.viewer.spawn.SceneCharacter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

final class FilmOps {
    static final String[] OPS = {
        "npc",
        "character",
        "airship",
        "move",
        "path",
        "animation",
        "skill",
        "skill_at",
        "effect",
        "despawn",
        "music",
        "voice",
        "sound",
        "fade",
        "line",
        "slide",
        "end_slides",
        "post_effect",
        "set",
        "remove",
        "shift",
        "fix"
    };

    static final double AIRSHIP_LIFT = 1_000;
    private static final int TURN = 0x10000;
    private static final int PER_TURN = 12;
    private static final int MOST_POINTS = 400;
    private static final int MOST_FIXES = 200;
    private static final int LAST_WINDOW = 9;
    private static final double LINE_HOLD = 3;
    private static final double FULL = 255;
    private static final double SAME_PLACE = 1;
    private static final String PER_PLAYER = "_id";

    private final FilmLookup client;
    private final List<Location> places;
    private final int rate;
    private final double frame;
    private final List<Item> original;
    private final Map<Object, Object> replaced = new IdentityHashMap<>();
    private final List<String> notes = new ArrayList<>();
    private SceneDocument document;
    private double grewTo = Double.NaN;

    private FilmOps(SceneDocument document, FilmLookup client, List<Location> places, int rate) {
        this.document = document;
        this.client = client;
        this.places = places;
        this.rate = Math.max(1, rate);
        frame = 1.0 / this.rate;
        original = document.items();
    }

    record Done(SceneDocument document, JSONArray done, List<String> notes) {}

    static Done apply(SceneDocument document, JSONArray ops, FilmLookup client, List<Location> places, int rate)
            throws ToolProblem {
        var call = new FilmOps(document, client, places, rate);
        var made = new ArrayList<List<Object>>();
        var rows = new ArrayList<JSONObject>();
        for (var index = 0; index < ops.size(); index++) {
            if (!(ops.get(index) instanceof JSONObject object)) {
                throw new ToolProblem("ops " + index + " must be an object with op.");
            }
            var row = new JSONObject();
            var parts = new ArrayList<Object>();
            var where = "ops " + index;
            try {
                call.one(new ToolArgs(object), row, parts, where);
            } catch (ToolProblem problem) {
                throw new ToolProblem(
                        problem.getMessage().startsWith(where)
                                ? problem.getMessage()
                                : where + ": " + problem.getMessage());
            }
            rows.add(row);
            made.add(parts);
        }
        var done = new JSONArray();
        for (var index = 0; index < rows.size(); index++) {
            var row = rows.get(index);
            var keys = new JSONArray();
            for (var part : made.get(index)) {
                var key = call.find(part);
                if (key != null) {
                    keys.add(key.id());
                }
            }
            if (!keys.isEmpty()) {
                row.put("keys", keys);
            }
            done.add(row);
        }
        if (!Double.isNaN(call.grewTo)) {
            call.notes.add(String.format(
                    Locale.ROOT,
                    "The scene now ends at %.2f s, as something of this call comes then.",
                    call.document.seconds()));
        }
        return new Done(call.document, done, call.notes);
    }

    static double onFrame(double seconds, int rate) {
        return Math.round(seconds * rate) / (double) rate;
    }

    static int yawUnits(double degrees) {
        return Math.floorMod(Math.round(degrees * TURN / 360), TURN);
    }

    static double yawDegrees(int units) {
        return Math.round(units * 3600.0 / TURN) / 10.0;
    }

    static <R> R changed(SceneFields<R> table, R record, JSONObject changes) throws ToolProblem {
        var next = record;
        for (var change : changes.entrySet()) {
            Field<R> field = null;
            for (var each : table.getFields()) {
                if (each.name().equals(change.getKey())) {
                    field = each;
                }
            }
            if (field == null) {
                var names = new ArrayList<String>();
                for (var each : table.getFields()) {
                    names.add(each.name());
                }
                throw new ToolProblem(
                        "There is no field " + change.getKey() + " here. Fields: " + String.join(", ", names) + ".");
            }
            next = table.with(next, field.name(), value(field.kind(), change.getValue(), field.name()));
        }
        return next;
    }

    static JSONObject schema() {
        var p = new LinkedHashMap<String, Object>();
        var op = property(
                "string",
                "What it does. Actors: npc, character, airship. Orders to actor: move, path (many moves), animation,"
                    + " skill, skill_at (a skill on a place), effect, despawn. Then music, voice, sound, fade, line,"
                    + " slide, end_slides, post_effect. set changes fields of a key, remove takes keys or a whole"
                    + " actor away, shift moves them in time, fix does what a warning offers.");
        op.put("enum", JSONArray.of((Object[]) OPS));
        p.put("op", op);
        p.put("time", property("number", "The second it happens. 0 when left out."));
        p.put("actor", property("integer", "The Index of the actor the order is for."));
        p.put("index", property("integer", "The Index of a new actor. The first free one when left out."));
        p.put("npc", property("string", "An NPC id, or its name in the client."));
        p.put(
                "at",
                property(
                        "string",
                        "Where: a place, a point x,y,z or #Index of an actor. A move's goal too. A body without fly"
                                + " stands on the drawn floor (world_probe), so give that height."));
        p.put("above", property("number", "Units over at. An airship stands 1000 over it when left out."));
        p.put("yaw", property("number", "The way it faces in degrees, 0 east, 90 south."));
        p.put("face", property("string", "A place, point or #Index to face, in place of yaw."));
        p.put(
                "watch",
                property(
                        "integer",
                        "npc, character: the Index of an actor it keeps turned to while it stands, also when that"
                            + " actor walks. A walk of its own still faces its way. Lives in the scene file only, as"
                            + " the field watches of its spawn: set it to 0 to stop."));
        p.put("fly", property("boolean", "npc: it keeps its height (IsFly), and its moves fly in a straight line."));
        p.put("run", property("boolean", "move, path: runs."));
        p.put("speed", property("number", "move, path: units a second."));
        p.put("seconds", property("number", "path: how long all of it takes. shift: how far, may be negative."));
        p.put(
                "sequence",
                property("string", "animation: the sequence. move, path: what plays while it goes, such as a flight."));
        p.put("loop", property("boolean", "animation, effect: again and again."));
        p.put("skill", property("string", "A skill id, or its name."));
        p.put("target", property("integer", "skill: the Index it hits. The caster when left out."));
        p.put("effect", property("string", "An effect class, as assets_find kind effect names it."));
        p.put("race", property("string", "character: human, elf, dark elf, orc, dwarf, kamael or ertheia."));
        p.put("sex", property("string", "character: male or female."));
        p.put("class", property("string", "character: fighter or mage."));
        p.put(
                "gear",
                list("character: what it wears, as slot:item id or name, slots weapon, shield, chest, legs, gloves,"
                        + " boots, hair. Not on High Five."));
        p.put("points", list("path: the points x,y,z it goes through, in order."));
        p.put("around", property("string", "path: the centre of a circle, a place, point or #Index."));
        p.put("radius", property("number", "path: of the circle."));
        p.put("turns", property("number", "path: 1 when left out."));
        p.put("direction", property("string", "path: left, clockwise seen from above, or right."));
        p.put("per_turn", property("integer", "path: points a turn, 12 when left out."));
        p.put(
                "angle",
                property(
                        "number",
                        "path: where on the circle it starts, in degrees as yaw. Nearest to the actor when left out."));
        p.put(
                "key",
                property(
                        "string",
                        "A key of film_read, such as order/3/0. Keys name the scene as it was before this call."));
        p.put("keys", list("Keys, for remove and shift."));
        p.put(
                "fields",
                property(
                        "object",
                        "set: field names of film_read with new values. On an op that adds, fields of what it adds."));
        p.put("all", property("boolean", "fix: every warning that has a fix."));
        p.put(
                "name",
                property(
                        "string",
                        "music, voice, sound: its name, such as AmbSound3.airship_arrive_02. slide: its picture."));
        p.put("volume", property("number", "music, voice, sound: 0 to 255, where 0 is full too."));
        p.put("fade", property("number", "Seconds a sound comes up, or a fade goes to its colour."));
        p.put("fade_out", property("number", "music: seconds it goes."));
        p.put("hold", property("number", "fade, line: seconds it stays."));
        p.put("back", property("number", "fade: seconds back from the colour."));
        p.put("color", property("string", "fade: #RRGGBB, black when left out."));
        p.put("text", property("string", "line: a text of its own."));
        p.put("message", property("integer", "line: a row of SystemMsg."));
        p.put("window", property("integer", "line: 1 to 9, 9 at the bottom when left out."));
        p.put("row", property("integer", "post_effect: a row of PostEffectData."));
        var item = new JSONObject();
        item.put("type", "object");
        item.put("properties", new JSONObject(p));
        item.put("required", JSONArray.of("op"));
        var schema = property(
                "array",
                "What to do, in this order. The answer gives new Indexes and keys, and the warnings after it.");
        schema.put("items", item);
        return schema;
    }

    private void one(ToolArgs op, JSONObject row, List<Object> made, String where) throws ToolProblem {
        var name = op.getRequired("op").toLowerCase(Locale.ROOT);
        row.put("op", name);
        switch (name) {
            case "npc" -> npc(op, row, made);
            case "character" -> character(op, row, made);
            case "airship" -> airship(op, row, made);
            case "move" -> move(op, row, made);
            case "path" -> path(op, row, made);
            case "animation" -> animation(op, row, made);
            case "skill" -> skill(op, row, made);
            case "skill_at" -> skillAt(op, row, made);
            case "effect" -> effect(op, row, made);
            case "despawn" -> despawn(op, made);
            case "music" -> music(op, SoundSpans.Kind.TRACK, made);
            case "voice" -> music(op, SoundSpans.Kind.VOICE, made);
            case "sound" -> music(op, SoundSpans.Kind.SOUND, made);
            case "fade" -> fade(op, made);
            case "line" -> line(op, made);
            case "slide" -> slide(op, made);
            case "end_slides" -> endSlides(op, made);
            case "post_effect" -> postEffect(op, made);
            case "set" -> set(op, row, made);
            case "remove" -> remove(op, row);
            case "shift" -> shift(op, row, made);
            case "fix" -> fix(op, row, made);
            default ->
                throw new ToolProblem(where + ": there is no op " + name + ". Ops: " + String.join(", ", OPS) + ".");
        }
    }

    private void npc(ToolArgs op, JSONObject row, List<Object> made) throws ToolProblem {
        var npc = client.findNpc(op.getRequired("npc"), notes);
        var time = time(op);
        var at = place(op, "at", time, null, 0);
        var index = newIndex(op);
        var order = SceneOrders.spawn(npc, index, at[0], at[1], at[2], yaw(op, at, time));
        if (op.readFlag("fly", false)) {
            order = SceneFields.ACTOR.with(order, "fly", true);
        }
        add(fields(watching(order, op, row), op), time, made);
        row.put("index", index);
        row.put("npc", npc);
        row.put("name", client.getNpcName(npc));
    }

    private void character(ToolArgs op, JSONObject row, List<Object> made) throws ToolProblem {
        var model = client.getModel();
        var pc = model == null ? Set.<String>of() : model.pc();
        if (model != null && pc.isEmpty()) {
            notes.add(SceneCharacter.NO_PLAYERS);
        }
        var race = op.getText("race", "human").toLowerCase(Locale.ROOT).replace("_", " ");
        for (var classFigure : PlayerFigures.getClassFigures()) {
            if (race.contains(classFigure.name().toLowerCase(Locale.ROOT))
                    || race.equalsIgnoreCase(classFigure.figure().replace('_', ' '))) {
                throw new ToolProblem("A scene keeps a character's race as a number and gives a class only to human"
                        + " and orc mages, so it has no place for the " + classFigure.name()
                        + ". Take a race: human, elf,"
                        + " dark elf, orc, dwarf, kamael, ertheia, sylph or high elf.");
            }
        }
        if (race.equals("crow")) {
            throw new ToolProblem("A scene has no place for the Crow: it needs Race 5 with a class from 260 to 264,"
                    + " and a scene gives no class.");
        }
        var female = switch (op.getText("sex", "male").toLowerCase(Locale.ROOT)) {
            case "male", "m" -> false;
            case "female", "f" -> true;
            default -> throw new ToolProblem("sex must be male or female.");
        };
        var mage = switch (op.getText("class", "fighter").toLowerCase(Locale.ROOT)) {
            case "fighter" -> false;
            case "mage", "mystic" -> true;
            default -> throw new ToolProblem("class must be fighter or mage.");
        };
        var people = switch (race) {
            case "human" -> mage ? "Magic" : "Fighter";
            case "elf" -> "Elf";
            case "dark elf", "darkelf" -> "DarkElf";
            case "orc" -> mage ? "Shaman" : "Orc";
            case "dwarf" -> "Dwarf";
            case "kamael" -> "Kamael";
            case "ertheia", "sylph" -> "Ertheia";
            case "high elf", "highelf" -> "HighElf";
            default ->
                throw new ToolProblem(
                        "race must be human, elf, dark elf, orc, dwarf, kamael, ertheia, sylph or high elf.");
        };
        var figure = race.equals("sylph")
                ? female ? "FSylph" : "MSylph"
                : people.equals("HighElf") ? female ? "FHighElf" : "MHighElf" : null;
        var missing = figure == null ? null : client.findMissingFigure(figure);
        if (missing != null) {
            throw new ToolProblem(missing);
        }
        if ((people.equals("Magic") || people.equals("Shaman")) && !SceneCharacter.hasMages(pc)) {
            throw new ToolProblem(
                    "The scenes of this client have no mage's body, so a human or orc mage cannot stand in them. Take"
                            + " class fighter.");
        }
        var face = people + "." + (female ? "F" : "M") + people + "_m000_f";
        var gear = op.list("gear");
        var items = new EnumMap<Slot, Integer>(Slot.class);
        if (!gear.isEmpty() && !SceneCharacter.hasClothes(pc)) {
            notes.add("The scenes of this client keep no clothes, so the character stands in its bare body. High Five,"
                    + " P152 and 162 keep them.");
        } else {
            for (var piece : gear) {
                var colon = piece.indexOf(':');
                if (colon < 0) {
                    throw new ToolProblem("gear " + piece + " must be slot:item, such as weapon:Elven Long Bow.");
                }
                items.put(slot(piece.substring(0, colon)), client.findItem(piece.substring(colon + 1)));
            }
        }
        var time = time(op);
        var at = place(op, "at", time, null, 0);
        var index = newIndex(op);
        var order = figure == null
                ? SceneCharacter.order(face, items, SceneCharacter.hasSlots(pc))
                : SceneCharacter.order(face, figure, items, SceneCharacter.hasSlots(pc));
        order = SceneOrders.facing(
                SceneOrders.at(SceneFields.ACTOR.with(order, "index", index), at[0], at[1], at[2]), yaw(op, at, time));
        add(fields(watching(order, op, row), op), time, made);
        row.put("index", index);
        row.put("name", "Character " + index);
        if (!race.equals("human") && !race.equals("orc") && mage) {
            notes.add("A mage of this race has the body of its fighters, as in the game.");
        }
    }

    private Actor watching(Actor order, ToolArgs op, JSONObject row) throws ToolProblem {
        if (!op.has("watch")) {
            return order;
        }
        var watched = op.getInteger("watch", 0);
        if (watched == order.index()) {
            throw new ToolProblem("watch names another actor, and " + watched + " is this one.");
        }
        if (SceneOrders.entry(document.items(), watched) == null) {
            throw new ToolProblem("watch " + watched + " is no actor of this scene. " + cast()
                    + " Add that actor first. For two that face each other, set watches on the key of the"
                    + " first one afterwards.");
        }
        row.put("watches", watched);
        return SceneFields.ACTOR.with(order, "watches", watched);
    }

    private void airship(ToolArgs op, JSONObject row, List<Object> made) throws ToolProblem {
        var time = time(op);
        var at = place(op, "at", time, null, AIRSHIP_LIFT);
        var index = newIndex(op);
        add(fields(SceneOrders.airship(index, at[0], at[1], at[2], yaw(op, at, time)), op), time, made);
        row.put("index", index);
        row.put("name", "Airship " + index);
    }

    private void move(ToolArgs op, JSONObject row, List<Object> made) throws ToolProblem {
        var actor = actor(op);
        var time = time(op);
        var goal = place(op, op.has("at") ? "at" : "to", time, actor, 0);
        var order = SceneOrders.move(actor, goal[0], goal[1], goal[2], op.readFlag("run", false));
        var speed = op.getNumber("speed");
        if (speed != null) {
            order = SceneFields.ACTOR.with(order, "speed", positive(speed, "speed"));
        }
        var sequence = op.getText("sequence");
        if (sequence != null) {
            order = SceneFields.ACTOR.with(order, "moveSequence", sequence);
            noteSequence(actor, sequence);
        }
        add(fields(order, op), time, made);
        row.put("actor", actor.index());
    }

    private void path(ToolArgs op, JSONObject row, List<Object> made) throws ToolProblem {
        var actor = actor(op);
        var time = time(op);
        var flies = actor.fly() || actor.action() == Action.AIRSHIP_SPAWN;
        var start = placeAt(actor.index(), time);
        var points = op.has("around") ? circle(op, start, time) : points(op, start);
        if (points.isEmpty()) {
            throw new ToolProblem("The path has no point away from where the actor stands.");
        }
        var length = 0.0;
        var from = start;
        for (var point : points) {
            length += distance(from, point, flies);
            from = point;
        }
        var seconds = op.getNumber("seconds");
        var asked = op.getNumber("speed");
        double speed;
        if (seconds != null) {
            speed = length / positive(seconds, "seconds");
        } else if (asked != null) {
            speed = positive(asked, "speed");
        } else {
            var probe = SceneOrders.move(actor, 0, 0, 0, op.readFlag("run", false));
            speed = SceneOrders.speed(probe, SceneOrders.npc(actor), client.getSpeeds());
        }
        var sequence = op.getText("sequence");
        if (sequence != null) {
            noteSequence(actor, sequence);
        }
        var first = onFrame(time, rate);
        var moment = first;
        var way = 0.0;
        from = start;
        for (var point : points) {
            var step = distance(from, point, flies);
            way += step;
            var next = Math.max(moment + frame, Math.ceil((first + way / speed) * rate - 1e-6) / rate);
            var order = SceneFields.ACTOR.with(
                    SceneOrders.move(actor, point[0], point[1], point[2], op.readFlag("run", false)),
                    "speed",
                    Math.ceil(step / (next - moment) * 100) / 100);
            if (sequence != null) {
                order = SceneFields.ACTOR.with(order, "moveSequence", sequence);
            }
            add(fields(order, op), moment, made);
            moment = next;
            from = point;
        }
        grown(moment);
        if (!flies && points.stream().anyMatch(point -> Math.abs(point[2] - start[2]) > 1)) {
            notes.add("Index " + actor.index()
                    + " has no fly, so it walks the path on the floor. Set fly on its spawn to go through the air.");
        }
        row.put("actor", actor.index());
        row.put("moves", points.size());
        row.put("speed", Math.round(speed * 10) / 10.0);
        row.put("ends", Math.round(moment * 100) / 100.0);
    }

    private List<double[]> circle(ToolArgs op, double[] start, double time) throws ToolProblem {
        var center = place(op, "around", time, null, 0);
        var radius = positive(op.getNumber("radius", 0), "radius");
        var turns = positive(op.getNumber("turns", 1), "turns");
        var perTurn = Math.max(3, op.getInteger("per_turn", PER_TURN));
        var count = (int) Math.ceil(turns * perTurn - 1e-9);
        if (count > MOST_POINTS) {
            throw new ToolProblem("The circle would take " + count + " points, and a path takes at most " + MOST_POINTS
                    + ". Give fewer turns or per_turn.");
        }
        var sign = switch (op.getText("direction", "left").toLowerCase(Locale.ROOT)) {
            case "left", "clockwise" -> 1;
            case "right", "counterclockwise", "anticlockwise" -> -1;
            default -> throw new ToolProblem("direction must be left or right.");
        };
        var angle = op.has("angle")
                ? Math.toRadians(op.getNumber("angle", 0))
                : Math.atan2(start[1] - center[1], start[0] - center[0]);
        var points = new ArrayList<double[]>();
        for (var step = 0; step <= count; step++) {
            var turn = angle + sign * step * turns * 2 * Math.PI / count;
            var point =
                    new double[] {center[0] + radius * Math.cos(turn), center[1] + radius * Math.sin(turn), center[2]};
            if (step > 0 || distance(start, point, true) > SAME_PLACE) {
                points.add(point);
            }
        }
        return points;
    }

    private List<double[]> points(ToolArgs op, double[] start) throws ToolProblem {
        var texts = op.list("points");
        if (texts.isEmpty()) {
            throw new ToolProblem("A path needs points, or around with radius.");
        }
        if (texts.size() > MOST_POINTS) {
            throw new ToolProblem("A path takes at most " + MOST_POINTS + " points.");
        }
        var points = new ArrayList<double[]>();
        var last = start;
        for (var index = 0; index < texts.size(); index++) {
            var target = Target.parse(texts.get(index));
            if (target.kind() != Target.Kind.POINT) {
                throw new ToolProblem("points " + index + " must be a point x,y,z.");
            }
            var point = new double[] {
                target.x(), target.y(), target.z() == null ? last[2] : target.z() + op.getNumber("above", 0)
            };
            if (distance(last, point, true) > SAME_PLACE) {
                points.add(point);
                last = point;
            }
        }
        return points;
    }

    private void animation(ToolArgs op, JSONObject row, List<Object> made) throws ToolProblem {
        var actor = actor(op);
        var time = time(op);
        var sequence = op.getRequired("sequence");
        var order = fields(SceneOrders.animation(actor, sequence, op.readFlag("loop", false)), op);
        add(order, time, made);
        withDeathWait(actor, sequence, time, row);
        noteSequence(actor, sequence);
        row.put("actor", actor.index());
    }

    private void skill(ToolArgs op, JSONObject row, List<Object> made) throws ToolProblem {
        var actor = actor(op);
        var time = time(op);
        var skill = client.findSkill(op.getRequired("skill"), notes);
        var target = op.getInteger("target", actor.index());
        if (target != actor.index() && SceneOrders.entry(document.items(), target) == null) {
            throw new ToolProblem("target " + target + " is no actor of this scene. " + cast());
        }
        add(fields(SceneOrders.skill(actor, skill, target), op), time, made);
        row.put("actor", actor.index());
        row.put("skill", skill);
        row.put("name", client.getSkillName(skill));
        if (!client.getSkills().hasLevelOne(skill)) {
            notes.add("Skill " + skill
                    + " has no level 1, and a scene plays no other level, so it plays nothing. fix offers another.");
        }
    }

    private void skillAt(ToolArgs op, JSONObject row, List<Object> made) throws ToolProblem {
        var helper = client.getHelper();
        if (helper <= 0) {
            throw new ToolProblem("This client has no invisible NPC in its scenes to cast it.");
        }
        var skill = client.findSkill(op.getRequired("skill"), notes);
        var name = client.getSkillName(skill);
        var time = time(op);
        var at = place(op, "at", time, null, 0);
        var index = newIndex(op);
        var caster = SceneFields.ACTOR.with(
                SceneOrders.spawn(helper, index, at[0], at[1], at[2], yaw(op, at, time)),
                "description",
                name == null ? "Skill " + skill : name);
        var moment = grown(time);
        var moved = SceneItems.withNewOrder(document.items(), moment, caster, frame / 2);
        var item = moved.keys().first().item();
        var cast = SceneOrders.skill(caster, skill, caster.index());
        document = document.withItems(SceneItems.withNewOrder(moved.items(), item, cast));
        made.add(caster);
        made.add(cast);
        row.put("index", index);
        row.put("skill", skill);
        row.put("name", caster.description());
    }

    private void effect(ToolArgs op, JSONObject row, List<Object> made) throws ToolProblem {
        var name = effectName(op.getRequired("effect"));
        var time = time(op);
        Actor order;
        if (op.has("actor")) {
            var actor = actor(op);
            order = SceneOrders.effect(actor, name);
            row.put("actor", actor.index());
        } else {
            var at = place(op, "at", time, null, 0);
            order = SceneOrders.effectAt(name, at[0], at[1], at[2]);
        }
        if (op.readFlag("loop", false)) {
            order = SceneFields.ACTOR.with(order, "loop", true);
        }
        add(fields(order, op), time, made);
    }

    private void despawn(ToolArgs op, List<Object> made) throws ToolProblem {
        add(fields(SceneOrders.despawn(actor(op)), op), time(op), made);
    }

    private void music(ToolArgs op, SoundSpans.Kind kind, List<Object> made) throws ToolProblem {
        if (kind == SoundSpans.Kind.VOICE && !SceneFit.holdsVoices(client.getModel())) {
            throw new ToolProblem("This client has no field for voice lines.");
        }
        var name = clip(kind, op.getRequired("name"));
        var cue = switch (kind) {
            case TRACK -> SoundSpans.track(name);
            case VOICE -> SoundSpans.voice(name);
            case SOUND -> SoundSpans.sound(name);
        };
        var volume = op.getNumber("volume");
        if (volume != null) {
            var level = (int) Math.round(volume);
            if (level < 0 || level > FULL) {
                throw new ToolProblem("volume must be from 0 to 255, and 0 is full too.");
            }
            cue = SceneFields.MUSIC.with(SceneFields.MUSIC.with(cue, "level", level), "volume", Music.volumeOf(level));
        }
        if (op.has("fade")) {
            cue = SceneFields.MUSIC.with(cue, "fadeSeconds", notNegative(op.getNumber("fade", 0), "fade"));
        }
        if (op.has("fade_out")) {
            cue = SceneFields.MUSIC.with(cue, "fadeOutSeconds", notNegative(op.getNumber("fade_out", 0), "fade_out"));
        }
        cue = fields(SceneFields.MUSIC, cue, op);
        var moved = SceneItems.withNewMusic(document.items(), grown(time(op)), cue, frame / 2);
        document = document.withItems(moved.items());
        made.add(cue);
    }

    private void fade(ToolArgs op, List<Object> made) throws ToolProblem {
        var screen = SceneScreens.fade(
                notNegative(op.getNumber("fade", 1), "fade"),
                notNegative(op.getNumber("hold", 1), "hold"),
                notNegative(op.getNumber("back", 1), "back"));
        var color = op.getText("color");
        if (color != null) {
            screen = SceneFields.SCREEN.with(screen, "color", color(color));
        }
        screens(List.of(fields(SceneFields.SCREEN, screen, op)), time(op), made);
    }

    private void line(ToolArgs op, List<Object> made) throws ToolProblem {
        var text = op.getText("text", "");
        var message = op.getInteger("message", 0);
        if (text.isEmpty() && message <= 0) {
            throw new ToolProblem("A line needs text, or message with a row of SystemMsg.");
        }
        var window = op.getInteger("window", LAST_WINDOW);
        if (window < 1 || window > LAST_WINDOW) {
            throw new ToolProblem("window must be from 1 to 9. The client has no other.");
        }
        var screen = SceneScreens.line(text);
        screen = SceneFields.SCREEN.with(screen, "window", window);
        screen = SceneFields.SCREEN.with(screen, "message", Math.max(0, message));
        screen = SceneFields.SCREEN.with(screen, "hold", notNegative(op.getNumber("hold", LINE_HOLD), "hold"));
        screens(List.of(fields(SceneFields.SCREEN, screen, op)), time(op), made);
    }

    private void slide(ToolArgs op, List<Object> made) throws ToolProblem {
        if (!SceneFit.holdsSlides(client.getModel())) {
            throw new ToolProblem("This client has no field for slides.");
        }
        var asked = op.getRequired("name");
        Picture picture = null;
        var near = new ArrayList<String>();
        for (var each : client.getSlides()) {
            var bare = each.name().substring(each.name().lastIndexOf('.') + 1);
            if (each.name().equalsIgnoreCase(asked) || bare.equalsIgnoreCase(asked)) {
                picture = each;
            } else if (each.name().toLowerCase(Locale.ROOT).contains(asked.toLowerCase(Locale.ROOT))) {
                near.add(each.name());
            }
        }
        if (picture == null) {
            throw new ToolProblem("This client has no slide " + asked + "." + FilmClient.near(near)
                    + " assets_find kind texture with LineageSlideImage lists them.");
        }
        var time = onFrame(time(op), rate);
        var slide =
                fields(SceneFields.SCREEN, SceneScreens.slide(picture.name(), picture.width(), picture.height()), op);
        screens(
                SceneItems.areSlidesOpenAt(document.items(), time)
                        ? List.of(slide)
                        : List.of(SceneScreens.slideStart(), slide),
                time,
                made);
    }

    private void endSlides(ToolArgs op, List<Object> made) throws ToolProblem {
        if (!SceneFit.holdsSlides(client.getModel())) {
            throw new ToolProblem("This client has no field for slides.");
        }
        var time = onFrame(time(op), rate);
        if (!SceneItems.areSlidesOpenAt(document.items(), time)) {
            throw new ToolProblem("No slides stand open at that time.");
        }
        screens(List.of(SceneScreens.slideEnd()), time, made);
    }

    private void postEffect(ToolArgs op, List<Object> made) throws ToolProblem {
        if (!SceneFit.holdsPostEffects(client.getModel())) {
            throw new ToolProblem("This client has no field for post effects.");
        }
        var row = op.getInteger("row", -1);
        var rows = client.getPostEffects();
        if (!rows.isEmpty() && !rows.contains(row)) {
            throw new ToolProblem("PostEffectData of this client has no row " + row + ". It has " + rows + ".");
        }
        screens(List.of(fields(SceneFields.SCREEN, SceneScreens.postEffect(row), op)), time(op), made);
    }

    private void screens(List<Screen> screens, double time, List<Object> made) {
        var moved = SceneItems.withNewScreens(document.items(), grown(time), screens, frame / 2);
        document = document.withItems(moved.items());
        made.addAll(screens);
    }

    private void set(ToolArgs op, JSONObject row, List<Object> made) throws ToolProblem {
        var key = current(op.getRequired("key"));
        var changes = op.getJson().getJSONObject("fields");
        if (changes == null || changes.isEmpty()) {
            throw new ToolProblem("set needs fields, such as {\"run\": true}.");
        }
        var items = document.items();
        var item = items.get(key.item());
        switch (key.part()) {
            case ORDER -> {
                var old = item.actors().get(key.order());
                var next = changed(SceneFields.ACTOR, old, changes);
                replace(old, next);
                var changedItems = SceneItems.withOrder(items, key.item(), key.order(), next);
                document = document.withItems(changedItems);
                if (next.action() == Action.ANIMATION && changes.containsKey("animation")) {
                    withDeathWait(next, next.animation(), item.seconds(), row);
                }
                made.add(next);
            }
            case SHOT -> {
                var next = changed(SceneFields.CAMERA, item.camera(), changes);
                replace(item.camera(), next);
                document = document.withItems(SceneItems.withShot(items, key.item(), next));
                made.add(next);
            }
            case SCREEN -> {
                var next = changed(SceneFields.SCREEN, item.screen(), changes);
                replace(item.screen(), next);
                document = document.withItems(SceneItems.withScreen(items, key.item(), next));
                made.add(next);
            }
            case MUSIC -> {
                var next = changed(SceneFields.MUSIC, item.music(), changes);
                if (changes.containsKey("level") && !changes.containsKey("volume")) {
                    next = SceneFields.MUSIC.with(next, "volume", Music.volumeOf(next.level()));
                }
                replace(item.music(), next);
                document = document.withItems(SceneItems.withMusic(items, key.item(), next));
                made.add(next);
            }
        }
    }

    private void remove(ToolArgs op, JSONObject row) throws ToolProblem {
        var keys = keys(op);
        document = document.withItems(SceneItems.remove(document.items(), keys));
        row.put("removed", keys.size());
    }

    private void shift(ToolArgs op, JSONObject row, List<Object> made) throws ToolProblem {
        var keys = keys(op);
        var asked = op.getNumber("seconds");
        if (asked == null) {
            throw new ToolProblem("shift needs seconds, such as 1.5 or -2.");
        }
        var items = document.items();
        var seconds = SceneItems.shift(items, keys, onFrame(asked, rate), document.seconds());
        if (Math.abs(seconds - asked) > frame) {
            notes.add(String.format(
                    Locale.ROOT, "The shift went %.2f s and not %.2f s, so all stays in the scene.", seconds, asked));
        }
        for (var key : keys) {
            made.add(partOf(items, key));
        }
        document = document.withItems(
                SceneItems.move(items, keys, seconds, frame / 2).items());
        row.put("seconds", Math.round(seconds * 100) / 100.0);
    }

    private void fix(ToolArgs op, JSONObject row, List<Object> made) throws ToolProblem {
        var fixed = 0;
        if (op.readFlag("all", false)) {
            var tried = new IdentityHashMap<Object, Set<Problem>>();
            for (var round = 0; round < MOST_FIXES; round++) {
                Warning next = null;
                for (var warning : FilmRead.warnings(document, client, rate)) {
                    var part = partOf(document.items(), warning.key());
                    if (warning.fix() != Fix.NONE
                            && !tried.getOrDefault(part, Set.of()).contains(warning.problem())) {
                        next = warning;
                        break;
                    }
                }
                if (next == null) {
                    break;
                }
                tried.computeIfAbsent(partOf(document.items(), next.key()), _ -> EnumSet.noneOf(Problem.class))
                        .add(next.problem());
                var after = fixOne(next, made);
                if (after != null) {
                    tried.computeIfAbsent(after, _ -> EnumSet.noneOf(Problem.class))
                            .add(next.problem());
                }
                fixed++;
            }
        } else {
            var key = current(op.getRequired("key"));
            Warning chosen = null;
            for (var warning : FilmRead.warnings(document, client, rate)) {
                if (warning.key().equals(key) && warning.fix() != Fix.NONE && chosen == null) {
                    chosen = warning;
                }
            }
            if (chosen == null) {
                throw new ToolProblem("Key " + op.getRequired("key") + " has no warning with a fix now.");
            }
            fixOne(chosen, made);
            fixed++;
        }
        row.put("fixed", fixed);
    }

    private @Nullable Object fixOne(Warning warning, List<Object> made) {
        var items = document.items();
        var before = partOf(items, warning.key());
        var fixed = SceneOrders.fixed(items, warning, frame / 2);
        document = document.withItems(fixed.items());
        if (fixed.keys().isEmpty()) {
            return null;
        }
        var after = partOf(fixed.items(), fixed.keys().first());
        if (after != before) {
            replace(before, after);
        }
        made.add(after);
        return after;
    }

    private List<Key> keys(ToolArgs op) throws ToolProblem {
        var keys = new ArrayList<Key>();
        for (var text : op.list("keys")) {
            keys.add(current(text));
        }
        var one = op.getText("key");
        if (one != null) {
            keys.add(current(one));
        }
        if (op.has("actor")) {
            var index = op.getInteger("actor", 0);
            var items = document.items();
            for (var place = 0; place < items.size(); place++) {
                var orders = items.get(place).actors();
                for (var at = 0; at < orders.size(); at++) {
                    if (orders.get(at).index() == index) {
                        keys.add(new Key(Part.ORDER, place, at));
                    }
                }
            }
            if (keys.isEmpty()) {
                throw new ToolProblem("This scene has no order of Index " + index + ". " + cast());
            }
        }
        if (keys.isEmpty()) {
            throw new ToolProblem("Name keys, key or actor.");
        }
        return keys;
    }

    private Key current(String id) throws ToolProblem {
        Key key;
        try {
            key = Key.of(id);
        } catch (NumberFormatException wrong) {
            key = null;
        }
        if (key == null) {
            throw new ToolProblem("key " + id
                    + " must look like order/3/0, shot/2/0, screen/4/0 or music/1/0, as film_read gives it.");
        }
        if (!SceneItems.holds(original, key)) {
            throw new ToolProblem("The scene has no key " + id + ". Call film_read for its keys.");
        }
        var found = find(partOf(original, key));
        if (found == null) {
            throw new ToolProblem("Key " + id + " was taken away by an op before this one.");
        }
        return found;
    }

    private @Nullable Key find(Object part) {
        var now = part;
        while (replaced.containsKey(now)) {
            now = replaced.get(now);
        }
        var items = document.items();
        for (var place = 0; place < items.size(); place++) {
            var item = items.get(place);
            if (item.camera() == now) {
                return new Key(Part.SHOT, place, 0);
            }
            if (item.screen() == now) {
                return new Key(Part.SCREEN, place, 0);
            }
            if (item.music() == now) {
                return new Key(Part.MUSIC, place, 0);
            }
            for (var at = 0; at < item.actors().size(); at++) {
                if (item.actors().get(at) == now) {
                    return new Key(Part.ORDER, place, at);
                }
            }
        }
        return null;
    }

    private void replace(@Nullable Object old, Object next) {
        if (old != null && old != next) {
            replaced.put(old, next);
        }
    }

    private Actor fields(Actor order, ToolArgs op) throws ToolProblem {
        return fields(SceneFields.ACTOR, order, op);
    }

    private void add(Actor order, double time, List<Object> made) {
        var moment = grown(time);
        document = document.withItems(SceneItems.withNewOrder(document.items(), moment, order, frame / 2)
                .items());
        made.add(order);
    }

    private double grown(double time) {
        var moment = onFrame(time, rate);
        if (moment > document.seconds()) {
            var items = document.items();
            if (items.getLast().quiet()) {
                document = document.withSeconds(moment);
            } else {
                var longer = new ArrayList<>(items);
                longer.add(SceneDocument.blankItem(moment));
                document = document.withItems(longer);
            }
            grewTo = moment;
        }
        return moment;
    }

    private void withDeathWait(Actor actor, String sequence, double time, JSONObject row) {
        var entry = SceneOrders.entry(document.items(), actor.index());
        var npc = entry == null ? 0 : SceneOrders.npc(entry);
        var known = npc > 0 ? client.getSequences(npc) : null;
        if (known == null) {
            if (sequence.toLowerCase(Locale.ROOT).contains("death") && entry != null && entry.player()) {
                notes.add("The sequences of a character are not read here, so a death gets no wait. Add an animation of"
                        + " its deathwait with loop.");
            }
            return;
        }
        var names = new ArrayList<>(known.keySet());
        Double seconds = null;
        for (var length : known.entrySet()) {
            if (length.getKey().equalsIgnoreCase(sequence)) {
                seconds = length.getValue();
            }
        }
        var moment = onFrame(time, rate);
        var ends = seconds == null ? Double.NaN : onFrame(moment + seconds, rate);
        var before = document.items();
        var after = SceneOrders.withDeathWait(before, actor, sequence, moment, ends, names, document.seconds(), frame);
        if (after != before) {
            document = document.withItems(after);
            row.put("wait", SceneOrders.deathWait(sequence, names) + String.format(Locale.ROOT, " at %.2f s", ends));
        }
    }

    private void noteSequence(Actor actor, String sequence) {
        var npc = SceneOrders.npc(actor);
        var known = npc > 0 ? client.getSequences(npc) : null;
        if (known != null && known.keySet().stream().noneMatch(sequence::equalsIgnoreCase)) {
            notes.add("The model of Index " + actor.index() + " has no " + sequence
                    + ", so it shows its walk or wait. film_read with actor and sequences lists what it has.");
        }
    }

    private double time(ToolArgs op) throws ToolProblem {
        var time = op.getNumber("time", 0);
        if (!(time >= 0) || !Double.isFinite(time)) {
            throw new ToolProblem("time must be 0 or more seconds.");
        }
        return time;
    }

    private Actor actor(ToolArgs op) throws ToolProblem {
        if (!op.has("actor")) {
            throw new ToolProblem("Name the actor by its Index. " + cast());
        }
        var index = op.getInteger("actor", 0);
        var entry = SceneOrders.entry(document.items(), index);
        if (entry == null) {
            throw new ToolProblem("This scene has no actor " + index + ". " + cast());
        }
        return entry;
    }

    private String cast() {
        var indexes = SceneOrders.indexes(document.items());
        return indexes.isEmpty()
                ? "It has no actors yet: add one with op npc, character or airship."
                : "Its actors: " + indexes + ".";
    }

    private int newIndex(ToolArgs op) throws ToolProblem {
        var items = document.items();
        if (!op.has("index")) {
            return SceneItems.freeIndex(items, document.paths());
        }
        var index = op.getInteger("index", 0);
        if (index < 1) {
            throw new ToolProblem("index must be 1 or more.");
        }
        if (SceneOrders.entry(items, index) != null) {
            throw new ToolProblem("Index " + index + " is taken. Leave index out for the first free one.");
        }
        return index;
    }

    private double[] place(ToolArgs op, String key, double time, @Nullable Actor keeps, double lift)
            throws ToolProblem {
        var text = op.getText(key);
        if (text == null) {
            throw new ToolProblem("Give " + key + ": a place, a point x,y,z or #Index of an actor.");
        }
        var above = op.getNumber("above", lift);
        if (text.startsWith("#")) {
            int index;
            try {
                index = Integer.parseInt(text.substring(1).strip());
            } catch (NumberFormatException wrong) {
                throw new ToolProblem(text + " must be # and the Index of an actor, such as #2.");
            }
            var at = placeAt(index, time);
            return new double[] {at[0], at[1], at[2] + above};
        }
        var target = Target.parse(text);
        return switch (target.kind()) {
            case POINT -> {
                if (target.z() == null) {
                    if (keeps != null) {
                        yield new double[] {target.x(), target.y(), placeAt(keeps.index(), time)[2] + above};
                    }
                    throw new ToolProblem(text + " has no height. Give a point x,y,z.");
                }
                yield new double[] {target.x(), target.y(), target.z() + above};
            }
            case NAME -> {
                var place = Target.place(places, text);
                if (place.isEmpty()) {
                    throw new ToolProblem(
                            "There is no place named \"" + text + "\". Call place_find, or give a point x,y,z.");
                }
                yield new double[] {
                    place.get().x(), place.get().y(), place.get().z() + above
                };
            }
            default ->
                throw new ToolProblem(text
                        + " needs an open world, and a scene is written without one. Give a place, a point"
                        + " x,y,z or #Index.");
        };
    }

    private double[] placeAt(int index, double time) throws ToolProblem {
        var at = FilmRead.placeAt(document, index, time, client);
        if (at == null) {
            throw new ToolProblem("This scene has no actor " + index + ". " + cast());
        }
        return at;
    }

    private int yaw(ToolArgs op, double[] at, double time) throws ToolProblem {
        var face = op.getText("face");
        if (face != null) {
            if (op.has("yaw")) {
                throw new ToolProblem("Give face or yaw, not both.");
            }
            var other = new ToolArgs(new JSONObject(Map.of("at", face)));
            var to = place(other, "at", time, null, 0);
            return SceneOrders.yaw(to[0] - at[0], to[1] - at[1]);
        }
        return yawUnits(op.getNumber("yaw", 0));
    }

    private String effectName(String named) throws ToolProblem {
        var asked = named.substring(named.lastIndexOf('.') + 1);
        var effects = client.getEffects();
        if (effects.isEmpty()) {
            return asked;
        }
        var near = new ArrayList<String>();
        for (var effect : effects) {
            if (effect.equalsIgnoreCase(asked)) {
                return effect;
            }
            if (effect.toLowerCase(Locale.ROOT).contains(asked.toLowerCase(Locale.ROOT))) {
                near.add(effect);
            }
        }
        throw new ToolProblem("This client has no effect class " + asked + "." + FilmClient.near(near)
                + " assets_find kind effect finds them by name.");
    }

    private String clip(SoundSpans.Kind kind, String asked) throws ToolProblem {
        var names = client.getClips(kind);
        var what = switch (kind) {
            case TRACK -> "music";
            case VOICE -> "voice line";
            case SOUND -> "sound";
        };
        if (names.isEmpty()) {
            throw new ToolProblem("This client has no " + what + ".");
        }
        var wanted = asked.toLowerCase(Locale.ROOT);
        var first = wanted.endsWith(PER_PLAYER)
                ? wanted.substring(0, wanted.length() - PER_PLAYER.length()) + "_1"
                : wanted;
        var near = new ArrayList<String>();
        for (var name : names) {
            if (name.equalsIgnoreCase(asked)) {
                return name;
            }
            if (name.equalsIgnoreCase(first)) {
                return asked;
            }
            if (name.toLowerCase(Locale.ROOT).contains(wanted)) {
                near.add(name);
            }
        }
        throw new ToolProblem("This client has no "
                + what
                + " "
                + asked
                + "."
                + FilmClient.near(near)
                + " assets_find kind "
                + (kind == SoundSpans.Kind.SOUND
                        ? "sound finds sounds, as Package.Name."
                        : kind == SoundSpans.Kind.VOICE ? "voice finds them." : "music finds them."));
    }

    private static Object partOf(List<Item> items, Key key) {
        var item = items.get(key.item());
        return switch (key.part()) {
            case SHOT -> item.camera();
            case SCREEN -> item.screen();
            case MUSIC -> item.music();
            case ORDER -> item.actors().get(key.order());
        };
    }

    private static <R> R fields(SceneFields<R> table, R record, ToolArgs op) throws ToolProblem {
        var changes = op.getJson().getJSONObject("fields");
        return changes == null || changes.isEmpty() ? record : changed(table, record, changes);
    }

    private static Object value(Kind kind, @Nullable Object raw, String name) throws ToolProblem {
        if (raw == null) {
            throw new ToolProblem(name + " has no value.");
        }
        try {
            return switch (kind) {
                case WHOLE ->
                    raw instanceof Number number
                            ? (Object) (int) Math.round(number.doubleValue())
                            : (Object) (int) Math.round(Double.parseDouble(raw.toString()));
                case NUMBER -> raw instanceof Number number ? number.doubleValue() : Double.parseDouble(raw.toString());
                case SWITCH ->
                    switch (raw.toString().toLowerCase(Locale.ROOT)) {
                        case "true" -> true;
                        case "false" -> false;
                        default -> throw new ToolProblem(name + " must be true or false.");
                    };
                case TEXT -> raw.toString();
                case ACTION -> Action.valueOf(raw.toString().toUpperCase(Locale.ROOT));
                case SCREEN_KIND -> ScreenKind.valueOf(raw.toString().toUpperCase(Locale.ROOT));
                case VISUALS -> visuals(raw, name);
                case SLOTS -> slots(raw, name);
            };
        } catch (IllegalArgumentException wrong) {
            throw new ToolProblem(name + " cannot take " + raw + ".");
        }
    }

    private static List<Visual> visuals(Object raw, String name) throws ToolProblem {
        if (!(raw instanceof JSONArray array)) {
            throw new ToolProblem(name + " must be a list of {\"slot\": 8, \"item\": 1234}.");
        }
        var visuals = new ArrayList<Visual>();
        for (var each : array) {
            if (!(each instanceof JSONObject object) || !object.containsKey("slot") || !object.containsKey("item")) {
                throw new ToolProblem(name + " must be a list of {\"slot\": 8, \"item\": 1234}.");
            }
            visuals.add(new Visual(object.getIntValue("slot"), object.getIntValue("item")));
        }
        return visuals;
    }

    private static List<Integer> slots(Object raw, String name) throws ToolProblem {
        if (!(raw instanceof JSONArray array)) {
            throw new ToolProblem(name + " must be a list of whole numbers.");
        }
        var slots = new ArrayList<Integer>();
        for (var each : array) {
            if (!(each instanceof Number number)) {
                throw new ToolProblem(name + " must be a list of whole numbers.");
            }
            slots.add(number.intValue());
        }
        return slots;
    }

    private static Slot slot(String text) throws ToolProblem {
        return switch (text.strip().toLowerCase(Locale.ROOT).replace(' ', '_')) {
            case "weapon", "right_hand", "right" -> Slot.RIGHT_HAND;
            case "shield", "left_hand", "left" -> Slot.LEFT_HAND;
            case "chest", "body" -> Slot.CHEST;
            case "legs" -> Slot.LEGS;
            case "gloves" -> Slot.GLOVES;
            case "boots", "feet" -> Slot.BOOTS;
            case "hair", "head", "back", "accessory" -> Slot.ACCESSORY;
            default ->
                throw new ToolProblem(
                        "gear slot " + text + " must be weapon, shield, chest, legs, gloves, boots or hair.");
        };
    }

    private static int color(String text) throws ToolProblem {
        var hex = text.strip();
        if (hex.startsWith("#")) {
            hex = hex.substring(1);
        } else if (hex.toLowerCase(Locale.ROOT).startsWith("0x")) {
            hex = hex.substring(2);
        }
        try {
            if (hex.length() != 6) {
                throw new NumberFormatException(hex);
            }
            return Integer.parseInt(hex, 16);
        } catch (NumberFormatException wrong) {
            throw new ToolProblem("color must be #RRGGBB, such as #000000 for black.");
        }
    }

    private static double positive(double value, String name) throws ToolProblem {
        if (!(value > 0) || !Double.isFinite(value)) {
            throw new ToolProblem(name + " must be more than 0.");
        }
        return value;
    }

    private static double notNegative(double value, String name) throws ToolProblem {
        if (!(value >= 0) || !Double.isFinite(value)) {
            throw new ToolProblem(name + " must be 0 or more seconds.");
        }
        return value;
    }

    private static double distance(double[] from, double[] to, boolean flies) {
        var dx = to[0] - from[0];
        var dy = to[1] - from[1];
        var dz = to[2] - from[2];
        return flies ? Math.sqrt(dx * dx + dy * dy + dz * dz) : Math.hypot(dx, dy);
    }

    private static JSONObject property(String type, String text) {
        var property = new JSONObject();
        property.put("type", type);
        property.put("description", text);
        return property;
    }

    private static JSONObject list(String text) {
        var schema = property("array", text);
        var item = new JSONObject();
        item.put("type", "string");
        schema.put("items", item);
        return schema;
    }
}
