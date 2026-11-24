package com.rost.cruma.core.npc;

public enum NpcKind {
    MONSTER,
    BOSS,
    GUARD,
    SUMMON,
    OBJECT,
    CITIZEN;

    public static NpcKind ofType(String type) {
        return switch (type) {
            case "warrior", "zzoldagu", "monrace", "treasure", "doppelganger" -> MONSTER;
            case "boss", "fieldboss" -> BOSS;
            case "guard", "siege_attacker" -> GUARD;
            case "summon", "pet" -> SUMMON;
            case "holything", "ownthing", "collection", "xmastree", "world_trap", "pc_trap" -> OBJECT;
            default -> CITIZEN;
        };
    }

    public NpcKind band() {
        return this == MONSTER || this == BOSS ? this : CITIZEN;
    }

    public String getBandLabel() {
        return switch (band()) {
            case MONSTER -> "monsters";
            case BOSS -> "bosses";
            default -> "other NPCs";
        };
    }
}
