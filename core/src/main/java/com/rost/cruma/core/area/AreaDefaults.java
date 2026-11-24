package com.rost.cruma.core.area;

import java.util.List;

public record AreaDefaults(int moveBonus, int damageOnHp, int damageOnMp, int hpRegenBonus, int mpRegenBonus) {
    public static final AreaDefaults NONE = new AreaDefaults(0, 0, 0, 0, 0);

    public static AreaDefaults of(List<ServerArea> records) {
        var values = NONE;
        for (var record : records) {
            values = new AreaDefaults(
                    record.getInt("move_bonus", values.moveBonus),
                    record.getInt("damage_on_hp", values.damageOnHp),
                    record.getInt("damage_on_mp", values.damageOnMp),
                    record.getInt("hp_regen_bonus", values.hpRegenBonus),
                    record.getInt("mp_regen_bonus", values.mpRegenBonus));
        }
        return values;
    }
}
