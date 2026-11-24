package com.rost.cruma.viewer.agent;

import java.util.Locale;

public enum Access {
    SEE,
    EDIT,
    WRITE;

    public static Access parse(String word) {
        return switch (word.strip().toLowerCase(Locale.ROOT)) {
            case "edit" -> EDIT;
            case "write" -> WRITE;
            default -> SEE;
        };
    }

    public boolean allows(Access needed) {
        return compareTo(needed) >= 0;
    }

    public String getWord() {
        return name().toLowerCase(Locale.ROOT);
    }

    public String getLabel() {
        return switch (this) {
            case SEE -> "See";
            case EDIT -> "Edit";
            case WRITE -> "Write";
        };
    }

    public String getAllowed() {
        return switch (this) {
            case SEE -> "Moves the camera, takes pictures, reads all data and exports. Changes nothing.";
            case EDIT ->
                "Also changes the world, geodata and spawns in memory, where Undo takes it back, and writes scene"
                        + " files.";
            case WRITE -> "Also saves into the client and server folders, and writes to other folders.";
        };
    }
}
