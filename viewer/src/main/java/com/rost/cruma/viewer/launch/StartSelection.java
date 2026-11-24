package com.rost.cruma.viewer.launch;

import com.rost.cruma.viewer.config.Config.Viewer.Camera.Location;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

public record StartSelection(
        List<String> tiles,
        @Nullable String focus,
        @Nullable Location place) {
    public static final StartSelection NONE = new StartSelection(List.of(), null, null);

    private static final String HOME = "22_22";

    public StartSelection {
        tiles = List.copyOf(tiles);
        if (focus != null && !tiles.contains(focus)) {
            throw new IllegalArgumentException("The focus " + focus + " is not chosen.");
        }
        if (place != null && !tiles.contains(StartPlaces.tileOf(place))) {
            throw new IllegalArgumentException("The place " + place.name() + " lies outside the chosen tiles.");
        }
    }

    public enum Size {
        ONE(0, "1 tile", "Only this tile"),
        THREE(1, "3×3", "With neighbours, 3×3"),
        FIVE(2, "5×5", "5×5 around it"),
        WORLD(-1, "World", "Whole world");

        private final int reach;
        private final String label;
        private final String menuLabel;

        Size(int reach, String label, String menuLabel) {
            this.reach = reach;
            this.label = label;
            this.menuLabel = menuLabel;
        }

        public String getLabel() {
            return label;
        }

        public String getMenuLabel() {
            return menuLabel;
        }
    }

    public static StartSelection of(List<String> tiles, List<String> installed) {
        var chosen = ordered(Set.copyOf(tiles), installed);
        return new StartSelection(chosen, nearestToMiddle(chosen), null);
    }

    public static StartSelection restore(@Nullable String saved, List<String> installed, List<String> configured) {
        var tiles = List.<String>of();
        if (saved != null) {
            try {
                tiles = TileSelection.resolve(saved, installed, configured);
            } catch (IllegalArgumentException unreadable) {
                tiles = List.of();
            }
        }
        if (tiles.isEmpty()) {
            tiles = fallback(installed);
        }
        return of(tiles, installed);
    }

    public StartSelection click(String tile) {
        return new StartSelection(List.of(tile), tile, null);
    }

    public StartSelection toggle(String tile, List<String> installed) {
        var chosen = new LinkedHashSet<>(tiles);
        if (chosen.remove(tile)) {
            var left = ordered(chosen, installed);
            var newFocus = tile.equals(focus) ? nearestTo(left, tile) : focus;
            return new StartSelection(left, newFocus, keptPlace(left));
        }
        chosen.add(tile);
        var added = ordered(chosen, installed);
        return new StartSelection(added, tile, keptPlace(added));
    }

    public StartSelection box(String from, String to, boolean add, List<String> installed) {
        var bounds = TileBounds.of(List.of(from, to));
        var inside = new ArrayList<String>();
        for (var tile : installed) {
            if (bounds.contains(TileBounds.x(tile), TileBounds.y(tile))) {
                inside.add(tile);
            }
        }
        if (inside.isEmpty()) {
            return add ? this : NONE;
        }
        var middleX = (bounds.minX() + bounds.maxX()) * .5;
        var middleY = (bounds.minY() + bounds.maxY()) * .5;
        var newFocus = nearest(inside, middleX, middleY);
        var chosen = new LinkedHashSet<String>();
        if (add) {
            chosen.addAll(tiles);
        }
        chosen.addAll(inside);
        var result = ordered(chosen, installed);
        return new StartSelection(result, newFocus, keptPlace(result));
    }

    public StartSelection resize(Size size, List<String> installed) {
        if (size == Size.WORLD) {
            var everything = List.copyOf(installed);
            var newFocus = focus != null ? focus : nearestToMiddle(everything);
            return new StartSelection(everything, newFocus, place);
        }
        if (focus == null) {
            return this;
        }
        var around = around(focus, size.reach, installed);
        return new StartSelection(around, focus, keptPlace(around));
    }

    public @Nullable Size findSize(List<String> installed) {
        if (tiles.isEmpty()) {
            return null;
        }
        if (tiles.size() > 1 && Set.copyOf(tiles).equals(Set.copyOf(installed))) {
            return Size.WORLD;
        }
        if (focus == null) {
            return null;
        }
        for (var size : List.of(Size.ONE, Size.THREE, Size.FIVE)) {
            if (Set.copyOf(around(focus, size.reach, installed)).equals(Set.copyOf(tiles))) {
                return size;
            }
        }
        return null;
    }

    public StartSelection take(Location chosen) {
        var tile = StartPlaces.tileOf(chosen);
        return new StartSelection(List.of(tile), tile, chosen);
    }

    public StartSelection switchClient(List<String> installed, @Nullable String saved, List<String> configured) {
        var present = Set.copyOf(installed);
        var kept = new ArrayList<String>();
        for (var tile : tiles) {
            if (present.contains(tile)) {
                kept.add(tile);
            }
        }
        if (kept.isEmpty()) {
            return restore(saved, installed, configured);
        }
        var chosen = ordered(Set.copyOf(kept), installed);
        var newFocus = focus != null && chosen.contains(focus) ? focus : nearestToMiddle(chosen);
        return new StartSelection(chosen, newFocus, keptPlace(chosen));
    }

    public StartSelection withSavedPlace(
            @Nullable SessionHistory.Session newest, StartPlaces places, List<String> installed) {
        if (newest == null || newest.place().isEmpty() || place != null || tiles.isEmpty()) {
            return this;
        }
        var saved = ClientInstallation.ALL_TILES.equals(newest.tiles())
                ? installed
                : TileSelection.isExplicit(newest.tiles()) ? TileSelection.parse(newest.tiles()) : List.<String>of();
        if (!Set.copyOf(saved).equals(Set.copyOf(tiles))) {
            return this;
        }
        var found = places.findPlace(newest.place());
        if (found == null || !tiles.contains(StartPlaces.tileOf(found))) {
            return this;
        }
        return new StartSelection(tiles, StartPlaces.tileOf(found), found);
    }

    public StartSelection keepPlaceIn(StartPlaces places) {
        if (place == null) {
            return this;
        }
        var found = places.findPlace(place.name());
        if (found == null || !tiles.contains(StartPlaces.tileOf(found))) {
            return new StartSelection(tiles, focus, null);
        }
        return found.equals(place) ? this : new StartSelection(tiles, focus, found);
    }

    private @Nullable Location keptPlace(List<String> chosen) {
        return place != null && chosen.contains(StartPlaces.tileOf(place)) ? place : null;
    }

    private static List<String> ordered(Set<String> chosen, List<String> installed) {
        var result = new ArrayList<String>();
        for (var tile : installed) {
            if (chosen.contains(tile)) {
                result.add(tile);
            }
        }
        return result;
    }

    private static List<String> fallback(List<String> installed) {
        if (installed.contains(HOME)) {
            return List.of(HOME);
        }
        return installed.isEmpty() ? List.of() : List.of(installed.getFirst());
    }

    private static List<String> around(String center, int reach, List<String> installed) {
        var x = TileBounds.x(center);
        var y = TileBounds.y(center);
        var result = new ArrayList<String>();
        for (var tile : installed) {
            if (Math.abs(TileBounds.x(tile) - x) <= reach && Math.abs(TileBounds.y(tile) - y) <= reach) {
                result.add(tile);
            }
        }
        return result;
    }

    private static @Nullable String nearestToMiddle(List<String> tiles) {
        if (tiles.isEmpty()) {
            return null;
        }
        var bounds = TileBounds.of(tiles);
        return nearest(tiles, (bounds.minX() + bounds.maxX()) * .5, (bounds.minY() + bounds.maxY()) * .5);
    }

    private static @Nullable String nearestTo(List<String> tiles, String tile) {
        return tiles.isEmpty() ? null : nearest(tiles, TileBounds.x(tile), TileBounds.y(tile));
    }

    private static String nearest(List<String> tiles, double x, double y) {
        String best = tiles.getFirst();
        var shortest = Double.MAX_VALUE;
        for (var tile : tiles) {
            var dx = TileBounds.x(tile) - x;
            var dy = TileBounds.y(tile) - y;
            var distance = dx * dx + dy * dy;
            if (distance < shortest) {
                shortest = distance;
                best = tile;
            }
        }
        return best;
    }
}
