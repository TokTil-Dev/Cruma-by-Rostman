package com.rost.cruma.viewer.ui;

import org.jetbrains.annotations.Nullable;

enum TargetChoice {
    SELF,
    COPY,
    NPC,
    PLAYER;

    static TargetChoice of(boolean self, boolean onCharacter, boolean playerCharacter) {
        if (self) {
            return SELF;
        }
        if (!onCharacter) {
            return COPY;
        }
        return playerCharacter ? PLAYER : NPC;
    }

    static @Nullable TargetChoice clicked(int segment, TargetChoice current) {
        if (segment < 0 || segment >= values().length || segment == current.ordinal()) {
            return null;
        }
        return values()[segment];
    }

    static String[] labels() {
        return new String[] {"Self", "Copy", "NPC", "Player"};
    }

    boolean onSelf() {
        return this == SELF;
    }

    boolean onCharacter() {
        return this == NPC || this == PLAYER;
    }

    boolean isPlayerCharacter(boolean before) {
        return onCharacter() ? this == PLAYER : before;
    }

    String getTip() {
        return switch (this) {
            case SELF ->
                "The caster casts the skill on itself, as when you target yourself in the game. Nobody else stands,"
                        + " and the effects for the target play on the caster.";
            case COPY -> "A copy of the caster stands as the target, at the skill's range, and waits.";
            case NPC ->
                "An NPC of the client stands as the target and waits. The skill's effects hang on it as the skill"
                        + " says.";
            case PLAYER ->
                "A player character of any race, in the armour and weapons you choose, stands as the target and waits.";
        };
    }
}
