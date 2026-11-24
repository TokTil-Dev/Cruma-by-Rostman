package com.rost.cruma.viewer.agent.film;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.core.world.GameScenes.Action;
import com.rost.cruma.core.world.GameScenes.Actor;
import com.rost.cruma.core.world.GameScenes.Item;
import com.rost.cruma.core.world.GameScenes.ScreenKind;
import com.rost.cruma.core.world.GameScenes.Visual;
import com.rost.cruma.core.world.SceneDocument;
import com.rost.cruma.core.world.SceneFields;
import com.rost.cruma.core.world.SceneItems;
import com.rost.cruma.core.world.SceneItems.Key;
import com.rost.cruma.core.world.SceneItems.Part;
import com.rost.cruma.core.world.SceneOrders;
import com.rost.cruma.core.world.SceneOrders.Sequences;
import com.rost.cruma.core.world.SceneOrders.Walk;
import com.rost.cruma.core.world.SceneOrders.Warning;
import com.rost.cruma.core.world.SceneScreens;
import com.rost.cruma.core.world.SoundSpans;
import com.rost.cruma.viewer.agent.ToolArgs;
import com.rost.cruma.viewer.agent.ToolProblem;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import org.jetbrains.annotations.Nullable;

final class FilmRead {
    static final String[] PARTS = {"cast", "orders", "camera", "sound", "screen", "walks", "warnings"};
    static final int DEFAULT_ROWS = 40;
    private static final int MOST_ROWS = 500;
    private static final Set<String> SHOWN = Set.of("player", "action", "index");

    private FilmRead() {}

    record Filter(
            @Nullable Integer actor,
            double from,
            double to,
            @Nullable String part,
            boolean sequences,
            int limit) {
        boolean shows(String name) {
            return part == null || part.equals(name);
        }

        boolean in(double moment) {
            return moment >= from - 1e-9 && moment <= to + 1e-9;
        }
    }

    static Filter filter(ToolArgs args) throws ToolProblem {
        var part = args.getText("part");
        if (part != null && !List.of(PARTS).contains(part)) {
            throw new ToolProblem("part must be one of " + String.join(", ", PARTS) + ".");
        }
        var actor = args.has("actor") ? (Integer) args.getInteger("actor", 0) : null;
        var sequences = args.readFlag("sequences", false);
        if (sequences && actor == null) {
            throw new ToolProblem("sequences needs actor, the Index whose model to read.");
        }
        return new Filter(
                actor,
                args.getNumber("from", 0),
                args.getNumber("to", Double.POSITIVE_INFINITY),
                part,
                sequences,
                Math.clamp(args.getInteger("limit", DEFAULT_ROWS), 1, MOST_ROWS));
    }

    static JSONObject answer(SceneDocument document, FilmLookup client, int rate, Filter filter) {
        var items = document.items();
        var times = document.scene().times();
        var length = document.seconds();
        var answer = new JSONObject();
        answer.put("scene", document.name());
        answer.put("seconds", round(length));
        if (!Double.isNaN(document.hour())) {
            answer.put("hour", round(document.hour()));
        }
        if (!document.client().isEmpty()) {
            answer.put("client", document.client());
        }
        if (!document.map().isEmpty()) {
            answer.put("map", document.map());
        }
        answer.put("tiles", document.scene().closeTiles());
        if (filter.shows("cast")) {
            answer.put("cast", cast(document, client, filter, answer));
        }
        if (filter.shows("orders")) {
            var orders = new JSONArray();
            var more = 0;
            for (var place = 0; place < items.size(); place++) {
                var item = items.get(place);
                for (var at = 0; at < item.actors().size(); at++) {
                    var order = item.actors().get(at);
                    if (!filter.in(times[place])
                            || filter.actor() != null
                                    && order.index() != filter.actor()
                                    && order.target() != filter.actor()) {
                        continue;
                    }
                    if (orders.size() < filter.limit()) {
                        orders.add(order(new Key(Part.ORDER, place, at), times[place], order));
                    } else {
                        more++;
                    }
                }
            }
            answer.put("orders", orders);
            more(answer, "orders_more", more);
        }
        if (filter.shows("camera") && filter.actor() == null) {
            answer.put("camera", camera(document, times, filter));
        }
        if (filter.shows("sound") && filter.actor() == null) {
            answer.put("sound", parts(items, times, Part.MUSIC, filter, answer, "sound_more"));
        }
        if (filter.shows("screen") && filter.actor() == null) {
            answer.put("screen", parts(items, times, Part.SCREEN, filter, answer, "screen_more"));
        }
        if (filter.shows("walks")) {
            var walks = new JSONArray();
            var more = 0;
            for (var walk : SceneOrders.walks(items, client.getSkills()::takesBody, client.getSpeeds())) {
                if ((filter.actor() == null || walk.index() == filter.actor()) && filter.in(walk.start())) {
                    if (walks.size() < filter.limit()) {
                        walks.add(walk(walk));
                    } else {
                        more++;
                    }
                }
            }
            answer.put("walks", walks);
            more(answer, "walks_more", more);
        }
        if (filter.shows("warnings")) {
            var warnings = new JSONArray();
            var more = 0;
            for (var warning : warnings(document, client, rate)) {
                var order = warning.key().part() == Part.ORDER
                        ? items.get(warning.key().item())
                                .actors()
                                .get(warning.key().order())
                        : null;
                if ((filter.actor() == null || order != null && order.index() == filter.actor())
                        && filter.in(times[warning.key().item()])) {
                    if (warnings.size() < filter.limit()) {
                        warnings.add(warning(document, warning, client));
                    } else {
                        more++;
                    }
                }
            }
            answer.put("warnings", warnings);
            more(answer, "warnings_more", more);
            answer.put(
                    "warnings_note",
                    "Two bodies in one place and a missing sequence of a character show only after a run in the"
                            + " viewer's video director.");
        }
        if (filter.sequences() && filter.actor() != null) {
            answer.put("sequences", sequences(document, client, filter.actor()));
        }
        return answer;
    }

    static List<Warning> warnings(SceneDocument document, FilmLookup client, int rate) {
        var items = document.items();
        var all = new ArrayList<>(SceneOrders.warnings(
                items, document.seconds(), 1.0 / rate, client.getSkills(), sequences(items, client)));
        all.addAll(SceneScreens.warnings(items, rate, SceneScreens.NO_RUN));
        return all;
    }

    static JSONObject warning(SceneDocument document, Warning warning, FilmLookup client) {
        var items = document.items();
        var order = warning.key().part() == Part.ORDER
                ? items.get(warning.key().item()).actors().get(warning.key().order())
                : null;
        var who = order == null ? "" : name(items, order.index(), client);
        var row = new JSONObject();
        row.put("key", warning.key().id());
        row.put("problem", warning.problem().name().toLowerCase(Locale.ROOT));
        row.put(
                "text",
                switch (warning.problem()) {
                    case ACTOR_GONE -> who + " does not stand in the scene here, so the client does nothing.";
                    case TARGET_GONE ->
                        "The target does not stand in the scene here, and the client stays silent unless both do.";
                    case NO_LEVEL_ONE -> "This skill has no level 1, and the client plays nothing else in a scene.";
                    case NO_SEQUENCE ->
                        "The model has no "
                                + (order == null
                                        ? "such sequence"
                                        : order.action() == Action.ANIMATION ? order.animation() : order.moveSequence())
                                + ", so the actor goes on as before.";
                    case FADE_DROPPED -> "Another fade runs here, and the client leaves this one out.";
                    case NO_WINDOW ->
                        "The client has no such window, so this line shows nowhere. Set window from 1 to 9.";
                    case SAME_PLACE ->
                        who + " stands in " + name(items, warning.other(), client)
                                + ", so the two go through each other.";
                });
        var fix = switch (warning.fix()) {
            case MOVE_ORDER -> String.format(Locale.ROOT, "fix moves it to %.2f s", warning.moment());
            case CAST_ON_SELF -> "fix casts it on the caster";
            case USE_SKILL ->
                "fix takes skill "
                        + warning.skill()
                        + (client.getSkillName(warning.skill()) == null
                                ? ""
                                : ", " + client.getSkillName(warning.skill()));
            case REMOVE_ORDER -> "fix deletes it";
            case MOVE_ASIDE -> "fix moves it aside";
            case NONE -> "";
        };
        if (!fix.isEmpty()) {
            row.put("fix", fix);
        }
        return row;
    }

    static double @Nullable [] placeAt(SceneDocument document, int index, double time, FilmLookup client) {
        var items = document.items();
        double[] at = null;
        var entered = 0.0;
        var moment = 0.0;
        for (var item : items) {
            moment = Math.max(moment, item.seconds());
            for (var order : item.actors()) {
                if (order.index() == index
                        && order.action().enters()
                        && order.stands()
                        && SceneOrders.hasPlace(order)
                        && (at == null || moment <= time)) {
                    at = new double[] {order.x(), order.y(), order.z()};
                    entered = moment;
                }
            }
        }
        if (at == null) {
            return null;
        }
        Walk last = null;
        for (var walk : SceneOrders.walks(items, client.getSkills()::takesBody, client.getSpeeds())) {
            if (walk.index() == index && walk.start() >= entered - 1e-9 && walk.start() <= time + 1e-9) {
                last = walk;
            }
        }
        if (last == null) {
            return at;
        }
        var span = last.arrival() - last.start();
        var share = span <= 0 ? 1 : Math.clamp((Math.min(time, last.stop()) - last.start()) / span, 0, 1);
        return new double[] {
            last.fromX() + (last.toX() - last.fromX()) * share,
            last.fromY() + (last.toY() - last.fromY()) * share,
            last.fromZ() + (last.toZ() - last.fromZ()) * share
        };
    }

    static String name(List<Item> items, int index, FilmLookup client) {
        var entry = SceneOrders.entry(items, index);
        if (entry == null) {
            return index == 0 ? "the player" : "Index " + index;
        }
        if (entry.player()) {
            return "Character " + index;
        }
        if (entry.action() == Action.AIRSHIP_SPAWN) {
            return "Airship " + index;
        }
        if (castsOnAPlace(entry, client)) {
            return entry.description() + " " + index;
        }
        var name = client.getNpcName(entry.npc());
        return (name == null ? "NPC " + entry.npc() : name) + " " + index;
    }

    private static boolean castsOnAPlace(Actor entry, FilmLookup client) {
        var helper = client.getHelper();
        return helper > 0 && entry.npc() == helper && !entry.description().isEmpty();
    }

    private static Sequences sequences(List<Item> items, FilmLookup client) {
        var entries = SceneOrders.entries(items);
        return (index, name) -> {
            var entry = entries.get(index);
            var npc = entry == null ? 0 : SceneOrders.npc(entry);
            var known = npc > 0 ? client.getSequences(npc) : null;
            return known == null || known.keySet().stream().anyMatch(name::equalsIgnoreCase);
        };
    }

    private static JSONArray cast(SceneDocument document, FilmLookup client, Filter filter, JSONObject answer) {
        var items = document.items();
        var stays = SceneOrders.stays(items, document.seconds());
        var cast = new JSONArray();
        var more = 0;
        for (var index : SceneOrders.indexes(items)) {
            if (filter.actor() != null && index != filter.actor()) {
                continue;
            }
            if (cast.size() >= filter.limit()) {
                more++;
                continue;
            }
            var entry = SceneOrders.entry(items, index);
            var row = new JSONObject();
            row.put("index", index);
            row.put("name", name(items, index, client));
            row.put(
                    "kind",
                    entry.player()
                            ? "character"
                            : entry.action() == Action.AIRSHIP_SPAWN
                                    ? "airship"
                                    : castsOnAPlace(entry, client)
                                            ? "skill on a place"
                                            : entry.npc() == client.getHelper() && client.getHelper() > 0
                                                    ? "helper"
                                                    : "npc");
            if (!entry.player() && entry.action() != Action.AIRSHIP_SPAWN) {
                row.put("npc", entry.npc());
            }
            var times = new JSONArray();
            for (var stay : stays) {
                if (stay.index() == index) {
                    times.add(JSONArray.of(round(stay.from()), round(stay.to())));
                }
            }
            row.put("stands", times);
            row.put("at", point(entry.x(), entry.y(), entry.z()));
            row.put("faces", FilmOps.yawDegrees(entry.yaw()));
            if (entry.watches() != 0) {
                row.put("watches", entry.watches());
            }
            if (entry.fly()) {
                row.put("fly", true);
            }
            if (entry.player()) {
                row.put("race", entry.race());
                row.put("sex", entry.sex() == 1 ? "female" : "male");
                if (entry.mage()) {
                    row.put("mage", true);
                }
                if (!entry.visuals().isEmpty()) {
                    row.put("visuals", visuals(entry.visuals()));
                }
            }
            var npc = SceneOrders.npc(entry);
            var speed = npc > 0 ? client.getSpeeds().of(npc) : null;
            if (speed != null && (speed.walk() > 0 || speed.run() > 0)) {
                row.put(
                        "speed",
                        String.format(
                                Locale.ROOT,
                                "walk %.0f, run %.0f, from %s",
                                speed.walk(),
                                speed.run(),
                                speed.server() ? "npcdata.txt" : "npcgrp"));
            } else if (entry.action() == Action.AIRSHIP_SPAWN) {
                row.put("speed", String.format(Locale.ROOT, "%.0f, the airship's", SceneOrders.AIRSHIP_SPEED));
            } else {
                row.put(
                        "speed",
                        String.format(
                                Locale.ROOT,
                                "walk %.0f, run %.0f, Cruma's guess, as no table says",
                                SceneOrders.WALK_SPEED,
                                SceneOrders.RUN_SPEED));
            }
            cast.add(row);
        }
        more(answer, "cast_more", more);
        return cast;
    }

    private static JSONObject order(Key key, double moment, Actor order) {
        var row = new JSONObject();
        row.put("key", key.id());
        row.put("time", round(moment));
        row.put("actor", order.index());
        row.put("action", order.action().name().toLowerCase(Locale.ROOT));
        if (order.player()) {
            row.put("player", true);
        }
        fields(SceneFields.ACTOR, order, SHOWN, row);
        return row;
    }

    private static JSONObject camera(SceneDocument document, double[] times, Filter filter) {
        var camera = new JSONObject();
        var path = document.path();
        if (path != null) {
            var keys = path.getKeys();
            camera.put("path_keys", keys.size());
            camera.put("path_from", round(keys.getFirst().seconds()));
            camera.put("path_to", round(keys.getLast().seconds()));
            if (path.hasEvenSpeed()) {
                camera.put("even_speed", true);
            }
            var follows = new TreeSet<Integer>();
            for (var key : keys) {
                if (key.lookAt() > 0) {
                    follows.add(key.lookAt());
                }
            }
            if (!follows.isEmpty()) {
                camera.put("follows", follows);
            }
            camera.put("note", "film_edit reads and changes the keys of the path.");
        }
        if (document.paths().size() > 1) {
            camera.put("more_paths", document.paths().size() - 1);
        }
        var items = document.items();
        var shots = new JSONArray();
        var more = 0;
        for (var place = 0; place < items.size(); place++) {
            var shot = items.get(place).camera();
            if (shot == null || shot.blank() || !filter.in(times[place])) {
                continue;
            }
            if (shots.size() < filter.limit()) {
                var row = new JSONObject();
                row.put("key", new Key(Part.SHOT, place, 0).id());
                row.put("time", round(times[place]));
                fields(SceneFields.CAMERA, shot, Set.of(), row);
                shots.add(row);
            } else {
                more++;
            }
        }
        camera.put("shots", shots);
        more(camera, "shots_more", more);
        var bars = new JSONArray();
        for (var span : SceneItems.bars(items, document.seconds())) {
            bars.add(JSONArray.of(round(span.from()), round(span.to())));
        }
        camera.put("bars", bars);
        return camera;
    }

    private static JSONArray parts(
            List<Item> items, double[] times, Part part, Filter filter, JSONObject answer, String moreName) {
        var rows = new JSONArray();
        var more = 0;
        for (var place = 0; place < items.size(); place++) {
            var item = items.get(place);
            if (!filter.in(times[place]) || (part == Part.MUSIC ? item.music() == null : item.screen() == null)) {
                continue;
            }
            if (rows.size() >= filter.limit()) {
                more++;
                continue;
            }
            var row = new JSONObject();
            row.put("key", new Key(part, place, 0).id());
            row.put("time", round(times[place]));
            if (part == Part.MUSIC) {
                row.put("kind", SoundSpans.kind(item.music()).name().toLowerCase(Locale.ROOT));
                fields(SceneFields.MUSIC, item.music(), Set.of("sound", "voice"), row);
            } else {
                fields(SceneFields.SCREEN, item.screen(), Set.of(), row);
            }
            rows.add(row);
        }
        more(answer, moreName, more);
        return rows;
    }

    private static JSONObject walk(Walk walk) {
        var row = new JSONObject();
        row.put("key", walk.key().id());
        row.put("actor", walk.index());
        row.put("start", round(walk.start()));
        row.put("arrives", round(walk.arrival()));
        row.put("seconds", round(walk.seconds()));
        if (walk.cut()) {
            row.put("cut_at", round(walk.stop()));
        }
        row.put("from", point(walk.fromX(), walk.fromY(), walk.fromZ()));
        row.put("to", point(walk.toX(), walk.toY(), walk.toZ()));
        row.put("speed", round(walk.speed()));
        row.put(
                "speed_from",
                switch (walk.source()) {
                    case ORDER -> "the order";
                    case SERVER -> "npcdata.txt";
                    case CLIENT -> "npcgrp";
                    case CRUMA -> "Cruma's guess";
                });
        if (walk.flies()) {
            row.put("flies", true);
        }
        return row;
    }

    private static JSONArray sequences(SceneDocument document, FilmLookup client, int index) {
        var entry = SceneOrders.entry(document.items(), index);
        var rows = new JSONArray();
        var npc = entry == null ? 0 : SceneOrders.npc(entry);
        var known = npc > 0 ? client.getSequences(npc) : null;
        if (known == null) {
            rows.add(
                    entry != null && entry.player()
                            ? "A character's sequences come with a run in the viewer's video director."
                            : "The model of this actor could not be read.");
            return rows;
        }
        for (var sequence : known.entrySet()) {
            rows.add(sequence.getKey() + " " + String.format(Locale.ROOT, "%.2f s", sequence.getValue()));
        }
        return rows;
    }

    private static <R> void fields(SceneFields<R> table, R record, Set<String> skip, JSONObject row) {
        for (var field : table.getFields()) {
            if (skip.contains(field.name())) {
                continue;
            }
            var value = field.reader().apply(record);
            var shown = shown(value);
            if (shown != null) {
                row.put(field.name(), shown);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static @Nullable Object shown(@Nullable Object value) {
        return switch (value) {
            case null -> null;
            case Boolean flag -> flag ? flag : null;
            case Integer whole -> whole != 0 ? whole : null;
            case Double number -> number != 0 ? round(number) : null;
            case String text -> text.isEmpty() ? null : text;
            case Action action -> action.name().toLowerCase(Locale.ROOT);
            case ScreenKind kind -> kind == ScreenKind.NONE ? null : kind.name().toLowerCase(Locale.ROOT);
            case List<?> list when list.isEmpty() -> null;
            case List<?> list when list.getFirst() instanceof Visual -> visuals((List<Visual>) list);
            default -> value;
        };
    }

    private static JSONArray visuals(List<Visual> visuals) {
        var rows = new JSONArray();
        for (var visual : visuals) {
            var row = new JSONObject();
            row.put("slot", visual.slot());
            row.put("item", visual.item());
            rows.add(row);
        }
        return rows;
    }

    private static void more(JSONObject answer, String name, int more) {
        if (more > 0) {
            answer.put(name, more);
        }
    }

    private static String point(double x, double y, double z) {
        return Math.round(x) + "," + Math.round(y) + "," + Math.round(z);
    }

    private static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }
}
