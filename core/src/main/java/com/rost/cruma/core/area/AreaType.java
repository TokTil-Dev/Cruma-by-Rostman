package com.rost.cruma.core.area;

import org.jetbrains.annotations.Nullable;

public enum AreaType {
    BATTLE_ZONE("battle_zone", 0, false),
    PEACE_ZONE("peace_zone", 2, false),
    WATER("water", 3, false),
    ONLY_PVE("only_pve", 4, false),
    SWAMP("swamp", 5, false),
    MOTHER_TREE("mother_tree", 6, false),
    NO_RESTART("no_restart", 7, false),
    DAMAGE("damage", 8, true),
    POISON("poison", 9, true),
    TELEPORT("teleport", 10, true),
    INSTANT_SKILL("instant_skill", 11, true),
    SSQ_ZONE("ssq_zone", 12, false),
    AIRPORT("airport", 13, false),
    INZONE_ENTER("inzone_enter", 14, true),
    INZONE_LEAVE("inzone_leave", 15, true),
    FLYMOVE("flymove", 16, true),
    DRAGON_LAIR("dragon_lair", 18, false),
    NO_SKILL("no_skill", 19, false),
    SIEGE_ZONE("siege_zone", 20, false),
    FISHING("fishing", 21, true),
    TRAINING_ZONE("training_zone", 22, false);

    private final String keyword;
    private final int serverId;
    private final boolean instant;

    AreaType(String keyword, int serverId, boolean instant) {
        this.keyword = keyword;
        this.serverId = serverId;
        this.instant = instant;
    }

    public static @Nullable AreaType find(String word) {
        for (var type : values()) {
            if (type.keyword.equals(word)) {
                return type;
            }
        }
        return null;
    }

    public String getKeyword() {
        return keyword;
    }

    public int getServerId() {
        return serverId;
    }

    public boolean isInstant() {
        return instant;
    }

    public int getTypeUnitTick() {
        return switch (this) {
            case DAMAGE -> 9;
            case POISON, INSTANT_SKILL -> 45;
            case TELEPORT, INZONE_ENTER, INZONE_LEAVE -> 5;
            case FLYMOVE -> 1;
            default -> -1;
        };
    }
}
