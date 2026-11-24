package com.rost.cruma.viewer.ui;

import com.rost.cruma.core.world.MapEventCatalog.Group;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;
import java.util.function.Predicate;
import org.jetbrains.annotations.Nullable;

final class MapEventTiles {
    private static final Comparator<Tile> NEAREST_TILE =
            Comparator.comparingDouble(Tile::distance).thenComparing(Tile::map);

    private MapEventTiles() {}

    enum Section {
        CASTLES,
        BY_TIME,
        ZONE_STATES,
        EVENTS
    }

    record Tile(String map, double distance, List<Group> events, int total, int on) {
        Tile {
            events = List.copyOf(events);
        }
    }

    record Row(Tile tile, @Nullable Group event) {}

    private record Near(Group event, double distance) {}

    static List<Tile> group(List<Group> events, Predicate<Group> shown, IntPredicate on, float x, float y, float z) {
        var shownByMap = new HashMap<String, List<Near>>();
        var totals = new HashMap<String, Integer>();
        for (var event : events) {
            if (event.actors().isEmpty()) {
                continue;
            }
            var nearest = event.nearest(x, y, z);
            totals.merge(nearest.map(), 1, Integer::sum);
            if (!shown.test(event)) {
                continue;
            }
            double dx = nearest.x() - x;
            double dy = nearest.y() - y;
            double dz = nearest.z() - z;
            shownByMap
                    .computeIfAbsent(nearest.map(), ignored -> new ArrayList<>())
                    .add(new Near(event, Math.sqrt(dx * dx + dy * dy + dz * dz)));
        }
        var tiles = new ArrayList<Tile>(shownByMap.size());
        for (var entry : shownByMap.entrySet()) {
            var near = entry.getValue();
            near.sort(Comparator.comparingDouble(Near::distance)
                    .thenComparingInt(item -> item.event().id()));
            var listed = new ArrayList<Group>(near.size());
            var onCount = 0;
            for (var item : near) {
                listed.add(item.event());
                if (on.test(item.event().id())) {
                    onCount++;
                }
            }
            tiles.add(
                    new Tile(entry.getKey(), near.getFirst().distance(), listed, totals.get(entry.getKey()), onCount));
        }
        tiles.sort(NEAREST_TILE);
        return tiles;
    }

    static final class Folds {
        private final Map<String, Boolean> chosen = new HashMap<>();
        private final Set<String> foldedInSearch = new HashSet<>();
        private String search = "";

        boolean isOpen(String map, String nearest, String query) {
            if (!query.isEmpty()) {
                return !query.equals(search) || !foldedInSearch.contains(map);
            }
            var choice = chosen.get(map);
            return choice != null ? choice : map.equals(nearest);
        }

        void toggle(String map, String nearest, String query) {
            if (query.isEmpty()) {
                chosen.put(map, !isOpen(map, nearest, query));
                return;
            }
            if (!query.equals(search)) {
                search = query;
                foldedInSearch.clear();
            }
            if (!foldedInSearch.remove(map)) {
                foldedInSearch.add(map);
            }
        }

        void open(String map, String query) {
            chosen.put(map, true);
            if (query.equals(search)) {
                foldedInSearch.remove(map);
            }
        }

        List<Row> getRows(List<Tile> tiles, String query) {
            var nearest = tiles.isEmpty() ? "" : tiles.getFirst().map();
            var rows = new ArrayList<Row>();
            for (var tile : tiles) {
                rows.add(new Row(tile, null));
                if (isOpen(tile.map(), nearest, query)) {
                    for (var event : tile.events()) {
                        rows.add(new Row(tile, event));
                    }
                }
            }
            return rows;
        }
    }

    static final class SectionFolds {
        private final Set<Section> closed = EnumSet.noneOf(Section.class);
        private final Map<Section, Boolean> chosenInSearch = new EnumMap<>(Section.class);
        private String search = "";

        boolean isOpen(Section section, boolean matches, String query) {
            if (query.isEmpty()) {
                return !closed.contains(section);
            }
            var choice = query.equals(search) ? chosenInSearch.get(section) : null;
            return choice != null ? choice : matches;
        }

        void toggle(Section section, boolean matches, String query) {
            if (query.isEmpty()) {
                if (!closed.remove(section)) {
                    closed.add(section);
                }
                return;
            }
            var open = isOpen(section, matches, query);
            remember(query);
            chosenInSearch.put(section, !open);
        }

        void open(Section section, String query) {
            closed.remove(section);
            if (!query.isEmpty()) {
                remember(query);
                chosenInSearch.put(section, true);
            }
        }

        private void remember(String query) {
            if (!query.equals(search)) {
                search = query;
                chosenInSearch.clear();
            }
        }
    }
}
