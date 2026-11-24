package com.rost.cruma.core.area;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

public final class AreaKeys {
    public static final Set<String> COMMON = Set.of(
            "name",
            "map_no",
            "type",
            "range",
            "water_range",
            "banned_range",
            "default_status",
            "event_id",
            "instantzone_id",
            "initial_delay",
            "on_time",
            "off_time",
            "random_time");

    private static final Map<String, Key> KEYS = collectKeys();

    private AreaKeys() {}

    public record Key(String key, String label, String meaning) {}

    public static List<Key> keys() {
        return List.copyOf(KEYS.values());
    }

    public static @Nullable Key find(String key) {
        return KEYS.get(key);
    }

    public static String label(String key) {
        var known = KEYS.get(key);
        return known == null ? key : known.label();
    }

    public static String meaning(String key) {
        var known = KEYS.get(key);
        return known == null ? "" : known.meaning();
    }

    public static Set<String> usedBy(@Nullable AreaType type) {
        var keys = new LinkedHashSet<String>();
        if (type == null) {
            return keys;
        }
        if (type.isInstant()) {
            keys.add("unit_tick");
            keys.add("target");
        }
        switch (type) {
            case POISON, INSTANT_SKILL ->
                keys.addAll(List.of("skill_name", "skill_prob", "skill_list", "skill_action_type", "show_dangerzone"));
            case DAMAGE -> keys.addAll(List.of("damage_on_hp", "damage_on_mp", "message_no"));
            case SWAMP -> keys.add("move_bonus");
            case MOTHER_TREE ->
                keys.addAll(List.of(
                        "affect_race",
                        "hp_regen_bonus",
                        "mp_regen_bonus",
                        "entering_message_no",
                        "leaving_message_no"));
            case NO_RESTART -> keys.addAll(List.of("restart_time", "restart_allowed_time", "restart_points"));
            case TELEPORT -> keys.addAll(List.of("teleport_points", "teleport_range"));
            case SSQ_ZONE -> keys.addAll(List.of("exp_penalty_per", "item_drop"));
            case PEACE_ZONE -> keys.add("blocked_actions");
            case INZONE_ENTER -> keys.add("enter_inzone_id");
            default -> {}
        }
        return keys;
    }

    public static boolean isRead(String key, @Nullable AreaType type) {
        return COMMON.contains(key) || usedBy(type).contains(key);
    }

    private static Map<String, Key> collectKeys() {
        var keys = new LinkedHashMap<String, Key>();
        for (var key : List.of(
                new Key("name", "Name", "The name scripts and events find the area by."),
                new Key("map_no", "Map tile", "The tile the server files the area under. Cruma keeps it right."),
                new Key("type", "Type", "What the area does."),
                new Key("range", "Outline", "The corners, with one bottom and one top for the whole area."),
                new Key("water_range", "Water box", "The box of the water: its corners and its surface."),
                new Key("banned_range", "Excluded parts", "Parts inside the area where it does not hold, 5 at most."),
                new Key(
                        "default_status",
                        "On at start",
                        "on or off when the server starts. An event or an AI turns" + " an area on that starts off."),
                new Key("event_id", "Event", "The map event that is on and off with the area."),
                new Key(
                        "instantzone_id",
                        "Instance zones",
                        "The instance zones it works in. Elsewhere it does nothing."),
                new Key("initial_delay", "First delay", "Seconds before the area works the first time."),
                new Key("on_time", "On for", "Seconds the timer keeps the area on."),
                new Key("off_time", "Off for", "Seconds the timer keeps the area off."),
                new Key("random_time", "Random time", "Seconds the timer adds at random."),
                new Key("unit_tick", "Every", "Ticks between two times it works on each creature inside."),
                new Key("target", "Works on", "Who it works on: all, pc, npc or only_pc."),
                new Key("skill_name", "Skill", "The skill it casts."),
                new Key("skill_prob", "Chance", "The chance in percent that it casts each time."),
                new Key("skill_list", "Skills", "Skills it casts in place of skill_name."),
                new Key("skill_action_type", "Order", "serial casts the skills of skill_list in order."),
                new Key("show_dangerzone", "Danger sign", "Whether the client shows the danger sign inside."),
                new Key("damage_on_hp", "HP per tick", "HP it takes each time."),
                new Key("damage_on_mp", "MP per tick", "MP it takes each time."),
                new Key("message_no", "Message", "The number of the system message the client shows."),
                new Key("move_bonus", "Speed change", "Movement speed in percent: -50 halves it."),
                new Key("affect_race", "Race", "The race it works on besides elves, or all."),
                new Key("hp_regen_bonus", "HP regen bonus", "HP regained faster inside."),
                new Key("mp_regen_bonus", "MP regen bonus", "MP regained faster inside."),
                new Key("entering_message_no", "Entering message", "The system message on the way in."),
                new Key("leaving_message_no", "Leaving message", "The system message on the way out."),
                new Key(
                        "restart_time",
                        "Restart after",
                        "Seconds after which a character that logged out here" + " comes back in a town."),
                new Key("restart_allowed_time", "Restart allowed", "How long a character may log back in here."),
                new Key("restart_points", "Restart points", "Where a character that logged out here comes back."),
                new Key("teleport_points", "Teleport points", "Where it sends whoever steps in."),
                new Key("teleport_range", "Teleport range", "An outline it sends to, at a place inside."),
                new Key("exp_penalty_per", "XP penalty", "The share of the experience lost on death, in percent."),
                new Key("item_drop", "Item drop", "Whether items drop on death: on or off."),
                new Key("blocked_actions", "Blocked actions", "Actions not allowed inside, such as priv_store."),
                new Key("enter_inzone_id", "Enters instance zone", "The instance zone it sends to."))) {
            keys.put(key.key(), key);
        }
        return keys;
    }
}
