package com.rost.cruma.viewer.launch;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class TileSelection {
    public static final String NONE = "none";

    private TileSelection() {}

    public static boolean isExplicit(String selection) {
        return selection != null && selection.matches("[0-9]+_[0-9]+(?:,[0-9]+_[0-9]+)*");
    }

    public static List<String> parse(String selection) {
        if (!isExplicit(selection)) {
            throw new IllegalArgumentException("Invalid world tiles: " + selection);
        }
        return List.copyOf(new LinkedHashSet<>(List.of(selection.split(","))));
    }

    public static List<String> resolve(String selection, List<String> available, List<String> configured) {
        if (ClientInstallation.ALL_TILES.equals(selection)) {
            return List.copyOf(available);
        }
        if (selection.isEmpty()) {
            return List.copyOf(configured);
        }
        if (NONE.equals(selection)) {
            return List.of();
        }
        var requested = Set.copyOf(parse(selection));
        return available.stream().filter(requested::contains).toList();
    }

    public static String encode(List<String> selected, List<String> available) {
        var chosen = Set.copyOf(selected);
        var ordered = available.stream().filter(chosen::contains).toList();
        return ordered.isEmpty()
                ? NONE
                : ordered.size() == available.size() ? ClientInstallation.ALL_TILES : String.join(",", ordered);
    }
}
