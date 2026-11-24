package com.rost.cruma.viewer.ui;

import com.rost.cruma.core.npc.ClientCatalog;
import com.rost.cruma.core.unreal.SkillEffects.Phase;
import com.rost.cruma.core.unreal.SkillEffects.Skill;
import com.rost.cruma.viewer.ui.SkillShow.Cast;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jetbrains.annotations.Nullable;

public final class SkillCasting {
    public static final int MIN_SPEED = 100;

    public static final int MAX_SPEED = 3000;

    public static final int BEYOND_RANGE = 2;

    final int[] distance = new int[1];
    private ClientCatalog catalog = ClientCatalog.empty();
    private int level = 1;
    private int castingSpeed = ClientCatalog.Cast.BASE_SPEED;
    private int attackSpeed = ClientCatalog.Cast.BASE_SPEED;
    private @Nullable String distanceFor;

    public static int furthest(ClientCatalog.Cast cast) {
        return BEYOND_RANGE * cast.range();
    }

    static String animationLine(SkillShow show) {
        var cast = show.getCast();
        String line;
        if (cast.animation().isEmpty()) {
            line = cast.parts().isEmpty() ? "No animation" : String.join(", ", cast.parts());
        } else {
            line = cast.parts().isEmpty() ? "Animation " + cast.animation() : String.join(", ", cast.parts());
        }
        return show.getCastChoices() > 1 ? line + " · 1 of " + show.getCastChoices() + " at random" : line;
    }

    public static String phases(Skill skill) {
        var parts = new ArrayList<String>();
        for (var phase : Phase.values()) {
            var count = skill.actions(phase).size();
            if (count > 0) {
                parts.add(phaseName(phase).toLowerCase(Locale.ROOT) + " " + count);
            }
        }
        return String.join(" · ", parts);
    }

    public static String phaseName(Phase phase) {
        return switch (phase) {
            case CASTING -> "Cast";
            case CHANNELING -> "Channel";
            case PRESHOT -> "Before the shot";
            case SHOT -> "Shot";
            case EXPLOSION -> "Hit";
            case UNION_TARGET -> "Linked targets";
        };
    }

    public ClientCatalog getCatalog() {
        return catalog;
    }

    void setCatalog(ClientCatalog value) {
        catalog = value;
    }

    public int getLevel() {
        return level;
    }

    void setLevel(int value) {
        level = value;
    }

    public int getCastingSpeed() {
        return castingSpeed;
    }

    void setCastingSpeed(int value) {
        castingSpeed = Math.clamp(value, MIN_SPEED, MAX_SPEED);
    }

    public int getAttackSpeed() {
        return attackSpeed;
    }

    void setAttackSpeed(int value) {
        attackSpeed = Math.clamp(value, MIN_SPEED, MAX_SPEED);
    }

    public ClientCatalog.@Nullable Cast getCast(Skill skill) {
        return catalog.getCast(skill.skillId(), level);
    }

    public int getLastLevel(Skill skill) {
        return Math.max(1, catalog.getLastCastLevel(skill.skillId()));
    }

    public boolean onSelf(Skill skill) {
        var cast = getCast(skill);
        return cast == null ? !SkillShow.isTargeted(skill) : cast.onSelf();
    }

    public boolean isMagic(Skill skill) {
        var cast = getCast(skill);
        return cast == null || cast.magic();
    }

    public boolean isFixed(Skill skill) {
        var cast = getCast(skill);
        return cast != null && cast.fixed();
    }

    public int getSpeed(Skill skill) {
        return isMagic(skill) ? castingSpeed : attackSpeed;
    }

    void getSpeed(Skill skill, int value) {
        if (isMagic(skill)) {
            setCastingSpeed(value);
        } else {
            setAttackSpeed(value);
        }
    }

    List<Cast> getCasts(Skill skill, int npc) {
        var own = npc > 0 ? catalog.getNpcSkillAnimation(npc, skill.skillId()) : null;
        return SkillShow.casts(getCast(skill), catalog::getCombo, own);
    }

    public float getSpeedFactor(Skill skill) {
        var cast = getCast(skill);
        if (cast == null) {
            return castingSpeed / (float) ClientCatalog.Cast.BASE_SPEED;
        }
        return cast.speedFactor(cast.magic() ? castingSpeed : attackSpeed);
    }

    public int getDistance(Skill skill, ClientCatalog.Cast cast) {
        var key = skill.skillId() + "|" + cast.range();
        if (!key.equals(distanceFor)) {
            distanceFor = key;
            distance[0] = cast.range();
        }
        return Math.clamp(distance[0], 0, furthest(cast));
    }

    void getDistance(Skill skill, ClientCatalog.Cast cast, int value) {
        getDistance(skill, cast);
        distance[0] = Math.clamp(value, 0, furthest(cast));
    }

    public String getSkillName(Skill skill) {
        var named = catalog.getSkill(skill.skillId(), 1);
        return named == null || named.name().isBlank() ? "Skill " + skill.skillId() : named.name();
    }

    public String getRangeText(Skill skill) {
        var cast = getCast(skill);
        if (cast == null) {
            return "";
        }
        return cast.onSelf() ? " · on itself" : " · range " + cast.range();
    }

    String getTimeLine(Skill skill, SkillShow show) {
        var base = show.getCast().seconds();
        var seconds = show.getCastSeconds();
        var time = String.format(Locale.ROOT, "Casts %.1f s", seconds);
        var cast = getCast(skill);
        if (cast == null) {
            return time + " · no cast in Skillgrp";
        }
        if (cast.fixed()) {
            return time + " · static, no speed changes it";
        }
        return Math.abs(seconds - base) < .05f ? time : time + String.format(Locale.ROOT, " · %.1f s at 333", base);
    }
}
