package com.rost.cruma.viewer.agent.view;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.core.geodata.GeoDataAccess.Availability;
import com.rost.cruma.core.geodata.GeoDataAccess.Layer;
import com.rost.cruma.core.geodata.GeoDataRepository;
import com.rost.cruma.core.geodata.GeoTile;
import com.rost.cruma.viewer.agent.ToolArgs;
import com.rost.cruma.viewer.agent.ToolProblem;
import com.rost.cruma.viewer.agent.ViewerContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import org.jetbrains.annotations.Nullable;

final class WorldFacts {
    private WorldFacts() {}

    static @Nullable String choice(ToolArgs args, String name, String... choices) throws ToolProblem {
        var text = args.getText(name);
        if (text != null && !List.of(choices).contains(text)) {
            throw new ToolProblem(name + " takes " + String.join(", ", choices) + ", not \"" + text + "\".");
        }
        return text;
    }

    static ActorBook book(ViewerContext viewer) {
        return viewer.agent().getSeen().getBook(viewer.world().clientPath());
    }

    static ScenePlacement named(ActorBook book, ScenePlacement placement) {
        return placement.actor() == null ? placement : placement.with(book.getActor(placement.actor()));
    }

    static List<ScenePlacement> named(ActorBook book, List<ScenePlacement> placements) {
        var result = new ArrayList<ScenePlacement>(placements.size());
        for (var placement : placements) {
            result.add(named(book, placement));
        }
        return result;
    }

    static @Nullable GeoDataRepository regions(ViewScript script) throws ToolProblem, InterruptedException {
        var regions = script.read(viewer -> {
            var folder = viewer.scene().geodata.path;
            return Optional.ofNullable(
                    folder == null ? null : viewer.agent().getSeen().getRegions(folder));
        });
        return regions.orElse(null);
    }

    static int @Nullable [] geodata(@Nullable GeoDataRepository regions, int x, int y) {
        if (regions == null) {
            return null;
        }
        var tile = GeoTile.fromWorld(x, y);
        var cell = regions.loadRegion(tile.x(), tile.y()).cellAt(x, y);
        if (cell.availability() != Availability.AVAILABLE) {
            return null;
        }
        var heights = new int[cell.layers().size()];
        var index = 0;
        for (Layer layer : cell.layers()) {
            heights[index++] = layer.height();
        }
        return heights;
    }

    static @Nullable Integer nearest(int @Nullable [] heights, double z) {
        if (heights == null || heights.length == 0) {
            return null;
        }
        var best = heights[0];
        for (var height : heights) {
            if (Math.abs(height - z) < Math.abs(best - z)) {
                best = height;
            }
        }
        return best;
    }

    static JSONObject table(List<String> columns, List<? extends List<?>> rows) {
        var names = new JSONArray();
        names.addAll(columns);
        var lines = new JSONArray();
        for (var row : rows) {
            var line = new JSONArray();
            line.addAll(row);
            lines.add(line);
        }
        var table = new JSONObject();
        table.put("columns", names);
        table.put("rows", lines);
        return table;
    }

    static Map<String, Integer> census(List<String> keys) {
        var counts = new LinkedHashMap<String, Integer>();
        for (var key : keys) {
            counts.merge(key, 1, Integer::sum);
        }
        var sorted = new ArrayList<>(counts.entrySet());
        sorted.sort(Entry.<String, Integer>comparingByValue().reversed().thenComparing(Entry.comparingByKey()));
        var result = new LinkedHashMap<String, Integer>();
        for (var entry : sorted) {
            result.put(entry.getKey(), entry.getValue());
        }
        return result;
    }
}
