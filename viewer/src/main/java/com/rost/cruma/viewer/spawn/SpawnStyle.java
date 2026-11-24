package com.rost.cruma.viewer.spawn;

import com.rost.cruma.core.npc.NpcKind;
import com.rost.cruma.core.spawn.NpcDataTable;
import com.rost.cruma.core.spawn.NpcDataTable.Npc;
import com.rost.cruma.core.spawn.SpawnMaker;
import java.util.EnumMap;

public enum SpawnStyle {
    MONSTER("Monsters", 0xFFF0A048),
    BOSS("Bosses", 0xFFFF5A7A),
    CITIZEN("Townsfolk", 0xFF5ADCC8),
    GUARD("Guards", 0xFF6EA0FF),
    SUMMON("Summons and pets", 0xFFB49CFF),
    OBJECT("Objects and traps", 0xFFAABE8C),
    UNKNOWN("Unknown NPCs", 0xFFB4BCC8),
    EVENT("Event and siege spawns", 0xFFC88CE6);

    public final String label;

    public final int argb;

    SpawnStyle(String label, int argb) {
        this.label = label;
        this.argb = argb;
    }

    public static SpawnStyle of(Npc npc) {
        return npc == null ? UNKNOWN : ofType(npc.type());
    }

    public static SpawnStyle ofType(String type) {
        return of(NpcKind.ofType(type));
    }

    public static SpawnStyle of(NpcKind kind) {
        return switch (kind) {
            case MONSTER -> MONSTER;
            case BOSS -> BOSS;
            case GUARD -> GUARD;
            case SUMMON -> SUMMON;
            case OBJECT -> OBJECT;
            case CITIZEN -> CITIZEN;
        };
    }

    public static SpawnStyle of(SpawnMaker maker, NpcDataTable npcs) {
        if (maker.isConditional()) {
            return EVENT;
        }
        var totals = new EnumMap<SpawnStyle, Integer>(SpawnStyle.class);
        for (var npc : maker.npcs()) {
            totals.merge(of(npcs.getNpc(npc.npcName())), Math.max(1, npc.total()), Integer::sum);
        }
        if (totals.containsKey(BOSS)) {
            return BOSS;
        }
        var style = UNKNOWN;
        var most = 0;
        for (var total : totals.entrySet()) {
            if (total.getValue() > most) {
                style = total.getKey();
                most = total.getValue();
            }
        }
        return style;
    }

    public static int getImguiColor(int argb, float alpha, float brightness) {
        var r = Math.clamp(Math.round(((argb >> 16) & 0xff) * brightness), 0, 255);
        var g = Math.clamp(Math.round(((argb >> 8) & 0xff) * brightness), 0, 255);
        var b = Math.clamp(Math.round((argb & 0xff) * brightness), 0, 255);
        var a = Math.clamp(Math.round(alpha * 255), 0, 255);
        return a << 24 | b << 16 | g << 8 | r;
    }

    public int getImguiColor(float alpha) {
        return getImguiColor(argb, alpha, 1f);
    }

    public int getImguiColor(float alpha, float brightness) {
        return getImguiColor(argb, alpha, brightness);
    }
}
