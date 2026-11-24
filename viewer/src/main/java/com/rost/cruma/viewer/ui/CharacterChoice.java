package com.rost.cruma.viewer.ui;

import org.jetbrains.annotations.Nullable;

enum CharacterChoice {
    NONE,
    NPC,
    PLAYER;

    static CharacterChoice of(boolean onCharacter, boolean playerCharacter) {
        if (!onCharacter) {
            return NONE;
        }
        return playerCharacter ? PLAYER : NPC;
    }

    static @Nullable CharacterChoice clicked(int segment, CharacterChoice current) {
        if (segment < 0 || segment == current.ordinal()) {
            return null;
        }
        return values()[segment];
    }

    static String[] labels(boolean withSpot) {
        return new String[] {withSpot ? "Ground" : "None", "NPC", "Player"};
    }

    static String npcSummary(int id, @Nullable String name) {
        if (id <= 0) {
            return "Choose an NPC";
        }
        return name == null || name.isEmpty() ? "NPC " + id : name + " · " + id;
    }

    boolean onCharacter() {
        return this != NONE;
    }

    boolean isPlayerCharacter(boolean before) {
        return this == NONE ? before : this == PLAYER;
    }

    String getTip(boolean withSpot) {
        if (withSpot) {
            return switch (this) {
                case NONE -> "On the ground: the effect plays at the place, with no character.";
                case NPC ->
                    "An NPC of the client stands at the place, facing the camera, and the effect hangs on it. It plays"
                            + " the client's animations.";
                case PLAYER ->
                    "A player character of any race, in the armour and weapons you choose, stands at the place, facing"
                            + " the camera, and the effect hangs on it. It plays the client's animations.";
            };
        }
        var casts = " stands as the caster, and a copy of it as the target unless Target chooses another. The caster"
                + " casts and releases the spell, and the skill's effects hang on their hands and bones as the skill"
                + " says. It plays the client's animations.";
        return switch (this) {
            case NONE -> "No character: the skill's effects play where the caster and the target would stand.";
            case NPC -> "An NPC of the client" + casts;
            case PLAYER -> "A player character of any race, in the armour and weapons you choose," + casts;
        };
    }
}
