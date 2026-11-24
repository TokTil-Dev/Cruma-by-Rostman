package com.rost.cruma.viewer.spawn;

import com.rost.cruma.core.area.AreaType;
import com.rost.cruma.core.area.ServerArea;
import org.jetbrains.annotations.Nullable;

public enum AreaStyle {
    PEACE("Peace zones", "Peace", 0xFF68D680),
    BATTLE("Battle zones", "Battle", 0xFFEC4260),
    SIEGE("Siege zones", "Siege", 0xFFC2533A),
    WATER("Water", "Water", 0xFF528CFF),
    DAMAGE("Damage areas", "Damage", 0xFFFF9638),
    POISON("Poison areas", "Poison", 0xFFA070FF),
    INSTANT_SKILL("Instant skill areas", "Skill", 0xFFEC68D6),
    SWAMP("Swamps", "Swamp", 0xFF96A040),
    MOTHER_TREE("Mother Trees", "Mother Tree", 0xFFC8EE8C),
    NO_RESTART("No-restart zones", "No restart", 0xFFC4BA88),
    TELEPORT("Teleport areas", "Teleport", 0xFF4CD8E8),
    SEVEN_SIGNS("Seven Signs zones", "Seven Signs", 0xFFE8C440),
    OTHER("Other", "Other", 0xFFA0ACBC);

    public final String label;

    public final String word;

    public final int argb;

    AreaStyle(String label, String word, int argb) {
        this.label = label;
        this.word = word;
        this.argb = argb;
    }

    public static AreaStyle of(@Nullable AreaType type) {
        if (type == null) {
            return OTHER;
        }
        return switch (type) {
            case PEACE_ZONE -> PEACE;
            case BATTLE_ZONE -> BATTLE;
            case SIEGE_ZONE -> SIEGE;
            case WATER -> WATER;
            case DAMAGE -> DAMAGE;
            case POISON -> POISON;
            case INSTANT_SKILL -> INSTANT_SKILL;
            case SWAMP -> SWAMP;
            case MOTHER_TREE -> MOTHER_TREE;
            case NO_RESTART -> NO_RESTART;
            case TELEPORT -> TELEPORT;
            case SSQ_ZONE -> SEVEN_SIGNS;
            default -> OTHER;
        };
    }

    public static AreaStyle of(ServerArea area) {
        return of(area.type());
    }

    public static String title(ServerArea area) {
        var type = area.type();
        if (type != null) {
            return title(type);
        }
        return area.typeWord().isEmpty() ? "No type" : "Type " + area.typeWord();
    }

    public static String title(AreaType type) {
        return switch (type) {
            case BATTLE_ZONE -> "Battle zone";
            case PEACE_ZONE -> "Peace zone";
            case WATER -> "Water";
            case ONLY_PVE -> "PvE-only zone";
            case SWAMP -> "Swamp";
            case MOTHER_TREE -> "Mother Tree";
            case NO_RESTART -> "No-restart zone";
            case DAMAGE -> "Damage area";
            case POISON -> "Poison area";
            case TELEPORT -> "Teleport area";
            case INSTANT_SKILL -> "Instant skill area";
            case SSQ_ZONE -> "Seven Signs zone";
            case AIRPORT -> "Airport";
            case INZONE_ENTER -> "Instance entrance";
            case INZONE_LEAVE -> "Instance exit";
            case FLYMOVE -> "Flying area";
            case DRAGON_LAIR -> "Dragon lair";
            case NO_SKILL -> "No-skill zone";
            case SIEGE_ZONE -> "Siege zone";
            case FISHING -> "Fishing area";
            case TRAINING_ZONE -> "Training zone";
        };
    }

    public static String tagWord(ServerArea area) {
        var style = of(area);
        return style == OTHER ? title(area) : style.word;
    }

    public int getImguiColor(float alpha) {
        return SpawnStyle.getImguiColor(argb, alpha, 1f);
    }

    public int getImguiColor(float alpha, float brightness) {
        return SpawnStyle.getImguiColor(argb, alpha, brightness);
    }
}
