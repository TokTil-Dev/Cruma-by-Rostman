package com.rost.cruma.core.area;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class AreaSummary {
    private static final Map<String, String> RACES = Map.of(
            "all", "Everyone",
            "human", "Humans",
            "elf", "Elves",
            "darkelf", "Dark elves",
            "orc", "Orcs",
            "dwarf", "Dwarves",
            "kamael", "Kamael",
            "ertheia", "Ertheia");

    private AreaSummary() {}

    public static String describe(ServerArea area) {
        return describe(area, AreaDefaults.NONE);
    }

    public static String describe(ServerArea area, AreaDefaults defaults) {
        return String.join(" ", describeSentences(area, defaults));
    }

    public static List<String> describeSentences(ServerArea area, AreaDefaults defaults) {
        var sentences = new ArrayList<String>();
        sentences.add(describeType(area, defaults));
        var blocked = area.get("blocked_actions");
        if (blocked != null) {
            var actions = readList(blocked);
            if (actions.contains("priv_store") && actions.contains("priv_rstore")) {
                sentences.add("Private stores are not allowed.");
            } else if (!actions.isEmpty()) {
                sentences.add("Blocks " + String.join(", ", actions) + ".");
            }
        }
        var zones = area.get("instantzone_id");
        if (zones != null) {
            var ids = readList(zones);
            sentences.add(
                    ids.size() == 1
                            ? "Works only inside instance zone " + ids.getFirst() + "."
                            : "Works only inside instance zones " + joinWithAnd(ids) + ".");
        }
        if (!area.isOnByDefault()) {
            sentences.add("Off until an event turns it on.");
        }
        return sentences;
    }

    public static String describePurpose(AreaType type) {
        return switch (type) {
            case PEACE_ZONE -> "nobody can fight here";
            case WATER -> "characters swim here";
            case POISON, INSTANT_SKILL -> "casts a skill every few ticks";
            case DAMAGE -> "takes HP or MP every few ticks";
            case SWAMP -> "changes movement speed";
            case MOTHER_TREE -> "HP and MP come back faster";
            case NO_RESTART -> "logging out here returns to a town";
            case TELEPORT -> "moves whoever steps in";
            case INZONE_ENTER -> "an entrance to an instance zone";
            case INZONE_LEAVE -> "an exit from an instance zone";
            case SSQ_ZONE -> "its own XP penalty and item drop";
            case FLYMOVE, FISHING -> "checked every few ticks";
            default -> "no settings of its own";
        };
    }

    public static String describeTimer(ServerArea area) {
        if (!area.hasSetting("on_time")
                && !area.hasSetting("off_time")
                && !area.hasSetting("initial_delay")
                && !area.hasSetting("random_time")) {
            return "";
        }
        var on = area.getSeconds("on_time", ServerArea.DEFAULT_ON_OFF_TIME);
        var off = area.getSeconds("off_time", ServerArea.DEFAULT_ON_OFF_TIME);
        var delay = area.getSeconds("initial_delay", 0);
        var random = area.getSeconds("random_time", 0);
        var text = new StringBuilder("The timer keeps it on for ")
                .append(AreaTimes.describe(on))
                .append(", then off for ")
                .append(AreaTimes.describe(off))
                .append(", and so on.");
        if (delay > 0) {
            text.append(" It starts after ").append(AreaTimes.describe(delay)).append('.');
        }
        if (random > 0) {
            text.append(" Up to ").append(AreaTimes.describe(random)).append(" more at random.");
        }
        return text.toString();
    }

    public static String describeTarget(String target) {
        return switch (target) {
            case "all" -> "everyone";
            case "pc" -> "players and their summons";
            case "npc" -> "NPCs";
            case "only_pc" -> "players only";
            default -> "target " + target;
        };
    }

    private static String describeType(ServerArea area, AreaDefaults defaults) {
        var word = area.typeWord();
        if (word.isEmpty()) {
            return "Has no type, so the server does not know what it does.";
        }
        var type = area.type();
        if (type == null) {
            return "Type " + word + ", which this tool does not know.";
        }
        var tick = area.effectiveUnitTick();
        return switch (type) {
            case PEACE_ZONE -> "Nobody can fight here.";
            case BATTLE_ZONE -> "A battle zone.";
            case ONLY_PVE -> "An only_pve zone.";
            case WATER -> "Water: characters swim here.";
            case SWAMP -> describeMove(area, defaults);
            case MOTHER_TREE -> describeRegen(area, defaults);
            case NO_RESTART ->
                "A character that logs out here returns to a town after "
                        + AreaTimes.describe(area.getSeconds("restart_time", ServerArea.DEFAULT_RESTART_TIME))
                        + ".";
            case DAMAGE -> describeDamage(area, defaults, tick);
            case POISON, INSTANT_SKILL -> describeSkill(area, tick);
            case TELEPORT -> describeTeleport(area);
            case SSQ_ZONE -> describeSevenSigns(area);
            case AIRPORT -> "An airport area.";
            case INZONE_ENTER -> {
                var zone = area.get("enter_inzone_id");
                yield zone == null
                        ? "An entrance to an instance zone."
                        : "An entrance to instance zone " + joinWithAnd(readList(zone)) + ".";
            }
            case INZONE_LEAVE -> "An exit from an instance zone.";
            case FLYMOVE -> "A flymove area, checked every " + formatTicks(tick) + ".";
            case DRAGON_LAIR -> "A dragon lair area.";
            case NO_SKILL -> "A no_skill area.";
            case SIEGE_ZONE -> "A siege zone.";
            case FISHING -> "A fishing area.";
            case TRAINING_ZONE -> "A training zone.";
        };
    }

    private static String describeMove(ServerArea area, AreaDefaults defaults) {
        var bonus = area.getInt("move_bonus", defaults.moveBonus());
        if (bonus < 0) {
            return "Slows movement by " + -bonus + "%.";
        }
        if (bonus > 0) {
            return "Speeds up movement by " + bonus + "%.";
        }
        return "A swamp that does not change movement.";
    }

    private static String describeRegen(ServerArea area, AreaDefaults defaults) {
        var races = new ArrayList<String>();
        races.add(RACES.get("elf"));
        for (var value : area.values("affect_race")) {
            var race = RACES.getOrDefault(value.strip(), value.strip());
            if (!races.contains(race)) {
                races.add(race);
            }
        }
        var who = races.contains("Everyone") ? "Everyone" : joinWithAnd(races);
        var hp = area.getInt("hp_regen_bonus", defaults.hpRegenBonus()) != 0;
        var mp = area.getInt("mp_regen_bonus", defaults.mpRegenBonus()) != 0;
        var what = hp && mp ? "HP and MP" : hp ? "HP" : mp ? "MP" : "";
        if (what.isEmpty()) {
            return "A mother tree area without a regeneration bonus.";
        }
        return who + (who.equals("Everyone") ? " regains " : " regain ") + what + " faster.";
    }

    private static String describeDamage(ServerArea area, AreaDefaults defaults, int tick) {
        var hp = area.getInt("damage_on_hp", defaults.damageOnHp());
        var mp = area.getInt("damage_on_mp", defaults.damageOnMp());
        String what;
        if (hp != 0 && mp != 0) {
            what = hp + " HP and " + mp + " MP";
        } else if (mp != 0) {
            what = mp + " MP";
        } else {
            what = hp + " HP";
        }
        var target = area.target();
        return "Takes " + what + " every " + formatTicks(tick)
                + (target.equals("all") ? "" : " from " + describeTarget(target)) + ".";
    }

    private static String describeSkill(ServerArea area, int tick) {
        var skills = new ArrayList<String>();
        for (var value : area.values("skill_name")) {
            skills.add(stripName(value));
        }
        var list = area.get("skill_list");
        if (list != null) {
            for (var skill : readList(list)) {
                skills.add(stripName(skill));
            }
        }
        if (skills.isEmpty()) {
            return "Casts no skill: it has no skill_name or skill_list.";
        }
        String what;
        if (skills.size() == 1) {
            what = skills.getFirst();
        } else if ("serial".equals(area.get("skill_action_type"))) {
            what = joinWithAnd(skills) + " in order";
        } else {
            what = "one of " + joinWithAnd(skills).replace(" and ", " or ") + " at random";
        }
        var chance = area.getInt("skill_prob", ServerArea.DEFAULT_SKILL_PROB);
        return "Every " + formatTicks(tick) + ", with a " + chance + "% chance, casts " + what + " on "
                + describeTarget(area.target()) + ".";
    }

    private static String describeTeleport(ServerArea area) {
        var moves = "Moves " + describeTarget(area.target());
        var points = area.teleportPoints().size();
        if (points > 0) {
            return moves + " to " + points + (points == 1 ? " point." : " points.");
        }
        if (area.hasSetting("teleport_range")) {
            return moves + " to a place inside its teleport_range.";
        }
        return "Has no place to move anyone to.";
    }

    private static String describeSevenSigns(ServerArea area) {
        var parts = new ArrayList<String>();
        var penalty = area.get("exp_penalty_per");
        if (penalty != null) {
            parts.add("XP penalty on death " + penalty + "%");
        }
        var drop = area.get("item_drop");
        if (drop != null) {
            parts.add("off".equals(drop) ? "no item drop" : "on".equals(drop) ? "items drop" : "item drop " + drop);
        }
        return parts.isEmpty() ? "A Seven Signs zone." : "A Seven Signs zone: " + String.join(", ", parts) + ".";
    }

    private static String formatTicks(int tick) {
        return tick == 1 ? "tick" : tick + " ticks";
    }

    private static List<String> readList(String value) {
        var items = new ArrayList<String>();
        for (var item : value.replace("{", " ").replace("}", " ").split("[;\\s]+")) {
            if (!item.isEmpty()) {
                items.add(item);
            }
        }
        return items;
    }

    private static String stripName(String value) {
        var name = value.strip();
        if (name.startsWith("[") && name.endsWith("]") && name.length() >= 2) {
            name = name.substring(1, name.length() - 1);
        }
        return name.startsWith("@") ? name.substring(1) : name;
    }

    private static String joinWithAnd(List<String> items) {
        if (items.size() <= 1) {
            return String.join("", items);
        }
        return String.join(", ", items.subList(0, items.size() - 1)) + " and " + items.getLast();
    }
}
