package com.rost.cruma.viewer.launch;

import com.rost.cruma.viewer.config.Config.Viewer.Camera.Location;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

public final class StartPlaces {
    public static final int RESULTS = 9;

    private static final double TILE = 32768;

    private final List<Location> places;

    public StartPlaces(List<Location> places) {
        this.places = List.copyOf(places);
    }

    public record Result(@Nullable Location place, String tile, boolean available) {
        public String getTitle() {
            return place == null ? tile : place.name();
        }
    }

    public static String tileAt(double x, double y) {
        return ((int) Math.floor(x / TILE) + 20) + "_" + ((int) Math.floor(y / TILE) + 18);
    }

    public static String tileOf(Location place) {
        return tileAt(place.x(), place.y());
    }

    public static String shortName(String name) {
        var shortened = name;
        if (shortened.startsWith("Town of ")) {
            shortened = shortened.substring("Town of ".length());
        }
        for (var ending : List.of(" Castle Town", " Village", " Township")) {
            if (shortened.endsWith(ending) && shortened.length() > ending.length()) {
                shortened = shortened.substring(0, shortened.length() - ending.length());
                break;
            }
        }
        return shortened.isBlank() ? name : shortened;
    }

    public static String detail(List<String> tiles, int installed) {
        var count = tiles.size() + (tiles.size() == 1 ? " tile" : " tiles");
        if (tiles.size() <= 1 || tiles.size() == installed) {
            return count;
        }
        return count + " · " + TileBounds.of(tiles).getRange();
    }

    public List<Location> getPlaces() {
        return places;
    }

    public @Nullable Location findPlace(String name) {
        for (var place : places) {
            if (place.name().equalsIgnoreCase(name)) {
                return place;
            }
        }
        return null;
    }

    public List<Location> findPlacesIn(String tile) {
        var found = new ArrayList<Location>();
        for (var place : places) {
            if (tileOf(place).equals(tile)) {
                found.add(place);
            }
        }
        return found;
    }

    public List<Result> search(String query, List<String> installed) {
        var typed = query.strip().toLowerCase(Locale.ROOT);
        if (typed.isEmpty()) {
            return List.of();
        }
        var present = Set.copyOf(installed);
        var matching = new ArrayList<Location>();
        for (var place : places) {
            if (place.name().toLowerCase(Locale.ROOT).contains(typed)) {
                matching.add(place);
            }
        }
        matching.sort(Comparator.comparingInt(place -> startsWord(place.name(), typed) ? 0 : 1));
        var results = new ArrayList<Result>();
        for (var place : matching) {
            var tile = tileOf(place);
            results.add(new Result(place, tile, present.contains(tile)));
        }
        if (Character.isDigit(typed.charAt(0))) {
            var prefix = typed.replaceAll("[ ,x]+", "_");
            for (var tile : installed) {
                if (tile.startsWith(prefix)) {
                    results.add(new Result(null, tile, true));
                }
            }
        }
        return results.size() > RESULTS ? List.copyOf(results.subList(0, RESULTS)) : List.copyOf(results);
    }

    public @Nullable Location findNamingPlace(Collection<String> tiles) {
        if (tiles.isEmpty() || places.isEmpty()) {
            return null;
        }
        var middle = TileBounds.of(tiles).getMiddleTile();
        if (tiles.contains(middle)) {
            for (var place : places) {
                if (tileOf(place).equals(middle)) {
                    return place;
                }
            }
        }
        return findNearestPlace(tiles);
    }

    public @Nullable Location findNearestPlace(Collection<String> tiles) {
        if (tiles.isEmpty() || places.isEmpty()) {
            return null;
        }
        var bounds = TileBounds.of(tiles);
        var chosen = Set.copyOf(tiles);
        var middleX = (bounds.minX() + bounds.maxX() + 1) * .5 - 20;
        var middleY = (bounds.minY() + bounds.maxY() + 1) * .5 - 18;
        Location nearest = null;
        var best = Double.MAX_VALUE;
        for (var place : places) {
            if (!chosen.contains(tileOf(place))) {
                continue;
            }
            var dx = place.x() / TILE - middleX;
            var dy = place.y() / TILE - middleY;
            var distance = dx * dx + dy * dy;
            if (distance < best) {
                best = distance;
                nearest = place;
            }
        }
        return nearest;
    }

    public String describe(List<String> tiles, @Nullable Location chosen, int installed) {
        if (chosen != null) {
            return chosen.name();
        }
        if (tiles.isEmpty()) {
            return "";
        }
        if (tiles.size() == installed && installed > 1) {
            return "Whole world";
        }
        var place = findNamingPlace(tiles);
        if (place != null) {
            return place.name();
        }
        return tiles.size() == 1 ? tiles.getFirst() : TileBounds.of(tiles).getRange();
    }

    private static boolean startsWord(String name, String typed) {
        var lower = name.toLowerCase(Locale.ROOT);
        return lower.startsWith(typed) || lower.contains(" " + typed);
    }
}
