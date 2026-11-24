package com.rost.cruma.viewer.agent.spawn;

import static com.rost.cruma.viewer.agent.spawn.AreaSteps.describeBriefly;
import static com.rost.cruma.viewer.agent.spawn.AreaSteps.describeFindings;
import static com.rost.cruma.viewer.agent.spawn.AreaSteps.document;
import static com.rost.cruma.viewer.agent.spawn.AreaSteps.findArea;
import static com.rost.cruma.viewer.agent.spawn.AreaSteps.isAreaDataReady;
import static com.rost.cruma.viewer.agent.spawn.AreaSteps.region;
import static com.rost.cruma.viewer.agent.spawn.AreaSteps.tileOf;
import static com.rost.cruma.viewer.agent.spawn.SpawnSteps.after;
import static com.rost.cruma.viewer.agent.spawn.SpawnSteps.limit;
import static com.rost.cruma.viewer.agent.spawn.SpawnSteps.point;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.core.area.AreaChecks;
import com.rost.cruma.core.area.AreaChecks.Finding;
import com.rost.cruma.core.area.AreaCopies;
import com.rost.cruma.core.area.AreaDataFile;
import com.rost.cruma.core.area.AreaDefaults;
import com.rost.cruma.core.area.AreaDocument.State;
import com.rost.cruma.core.area.AreaPoint;
import com.rost.cruma.core.area.AreaSkills;
import com.rost.cruma.core.area.AreaTiles;
import com.rost.cruma.core.area.AreaType;
import com.rost.cruma.core.area.ServerArea;
import com.rost.cruma.core.spawn.TerritoryPoint;
import com.rost.cruma.viewer.agent.Step;
import com.rost.cruma.viewer.agent.Target;
import com.rost.cruma.viewer.agent.ToolArgs;
import com.rost.cruma.viewer.agent.ToolProblem;
import com.rost.cruma.viewer.agent.ToolResult;
import com.rost.cruma.viewer.agent.spawn.AreaSteps.Region;
import com.rost.cruma.viewer.agent.spawn.AreaTools.Desk;
import com.rost.cruma.viewer.spawn.AreaStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.jetbrains.annotations.Nullable;

final class AreaFindTool {
    private AreaFindTool() {}

    private record Filter(
            @Nullable String type,
            @Nullable String query,
            @Nullable Boolean off,
            @Nullable Boolean instance) {
        boolean passes(ServerArea area) {
            if (type != null && !area.typeWord().equals(type)) {
                return false;
            }
            if (query != null && !matches(area, query)) {
                return false;
            }
            if (off != null && off == area.isOnByDefault()) {
                return false;
            }
            return instance == null || instance == area.hasSetting("instantzone_id");
        }
    }

    static Step areaFind(ToolArgs args, Desk desk) throws ToolProblem {
        SpawnSteps.checkScripts(args, desk.scriptPath());
        var listed = args.getText("list");
        var one = args.getText("area");
        var at = args.readFlag("at", false);
        if (at && args.getText("target") == null) {
            throw new ToolProblem("at needs a target: a point with a height, such as 83336,147972,-3204, or camera.");
        }
        var limit = limit(args);
        var query = args.getText("query");
        var filter = new Filter(
                args.getText("type"),
                query == null ? null : query.strip().toLowerCase(Locale.ROOT),
                args.has("off") ? args.readFlag("off", false) : null,
                args.has("instance") ? args.readFlag("instance", false) : null);
        return after(() -> isAreaDataReady(desk), () -> {
            var document = document(desk);
            var state = document.getState();
            var file = document.getFile();
            var types = document.getSavedTypes();
            var unsaved = document.isModified();
            if (listed != null) {
                return Step.done(ToolResult.data(list(listed, state, types, limit)));
            }
            if (one != null) {
                return Step.done(ToolResult.data(describeArea(findArea(state, one), state, file, types)));
            }
            var region = region(args, desk);
            return Step.onWorker(() -> {
                var result = at
                        ? holding(state, file, types, region.target(), filter, limit)
                        : listAreas(state, file.getDefaultValues(), types, region, filter, limit);
                result.put("unsaved", unsaved);
                return ToolResult.data(result);
            });
        });
    }

    static JSONObject describeArea(ServerArea area, State state, AreaDataFile file, Set<String> types) {
        var findings = AreaChecks.check(state, area.index(), types);
        var item = describeBriefly(area, file.getDefaultValues(), findings.size());
        if (!area.note().isEmpty()) {
            item.put("note", area.note());
        }
        var settings = new JSONArray();
        for (var setting : area.settings()) {
            var pair = new JSONObject();
            pair.put("key", setting.key());
            pair.put("value", setting.value());
            settings.add(pair);
        }
        item.put("settings", settings);
        var shape = new JSONObject();
        shape.put("corners", corners(area.points()));
        shape.put("bottom", area.bottom());
        shape.put("top", area.top());
        if (area.isWater()) {
            shape.put("water", "a box: its top is the surface, and the file writes each height 7 higher");
        }
        var parts = new JSONArray();
        for (var number = 0; number < area.banned().size(); number++) {
            var part = new JSONObject();
            part.put("number", number);
            part.put("corners", corners(area.banned().get(number)));
            parts.add(part);
        }
        shape.put("excluded_parts", parts);
        shape.put("teleport_points", places(area.teleportPoints()));
        shape.put("restart_points", places(area.restartPoints()));
        var reached = otherTiles(area);
        if (!reached.isEmpty()) {
            shape.put("also_reaches", reached);
        }
        item.put("shape", shape);
        item.put("problems", describeFindings(findings));
        item.put("text", AreaCopies.copyAsText(file, area));
        return item;
    }

    private static JSONObject listAreas(
            State state, AreaDefaults defaults, Set<String> types, @Nullable Region region, Filter filter, int limit) {
        var counts = countFindings(state, types);
        var found = new ArrayList<ServerArea>();
        for (var area : state.areas()) {
            if (area != null && filter.passes(area) && (region == null || region.reaches(area))) {
                found.add(area);
            }
        }
        if (region != null) {
            found.sort(Comparator.comparingDouble(region::distance).thenComparingDouble(ServerArea::area));
        }
        var list = new JSONArray();
        for (var area : found.subList(0, Math.min(limit, found.size()))) {
            var item = describeBriefly(area, defaults, counts.getOrDefault(area.index(), 0));
            if (region != null) {
                item.put("distance", Math.round(region.distance(area)));
            }
            list.add(item);
        }
        var result = new JSONObject();
        result.put("areas", found.size());
        result.put("more", found.size() - list.size());
        result.put("list", list);
        if (region == null) {
            result.put("scope", "the whole file, also tiles the viewer does not hold");
        }
        return result;
    }

    private static JSONObject holding(
            State state, AreaDataFile file, Set<String> types, Target target, Filter filter, int limit)
            throws ToolProblem {
        var z = target.z();
        if (z == null) {
            throw new ToolProblem("at needs a point with a height, such as 83336,147972,-3204, or camera. "
                    + target.text() + " has none.");
        }
        var tileX = AreaTiles.tileX(target.x());
        var tileY = AreaTiles.tileY(target.y());
        var here = new ArrayList<ServerArea>();
        var elsewhere = new ArrayList<ServerArea>();
        for (var area : state.areas()) {
            if (area == null || !filter.passes(area) || !area.contains(target.x(), target.y(), z)) {
                continue;
            }
            if (area.centerTileX() == tileX && area.centerTileY() == tileY) {
                here.add(area);
            } else {
                elsewhere.add(area);
            }
        }
        here.sort(Comparator.comparingDouble(ServerArea::area));
        var counts = countFindings(state, types);
        var list = new JSONArray();
        for (var area : here.subList(0, Math.min(limit, here.size()))) {
            list.add(describeBriefly(area, file.getDefaultValues(), counts.getOrDefault(area.index(), 0)));
        }
        var result = new JSONObject();
        result.put("point", point(target.x(), target.y(), z));
        result.put("tile", AreaTiles.format(tileX, tileY));
        result.put("areas", here.size());
        result.put("more", here.size() - list.size());
        result.put("list", list);
        if (!elsewhere.isEmpty()) {
            var other = new JSONArray();
            for (var area : elsewhere.subList(0, Math.min(limit, elsewhere.size()))) {
                var item = new JSONObject();
                item.put("area", SpawnEditor.number(area.index()));
                item.put("name", area.name());
                item.put("type", area.typeWord());
                item.put("tile", tileOf(area));
                other.add(item);
            }
            result.put("from_other_tiles", other);
            result.put(
                    "note",
                    "The areas of from_other_tiles hold the point but are filed under another tile, the tile of their"
                            + " middle. The server looks only at the areas of the tile where a character stands, so"
                            + " they do nothing here.");
        }
        return result;
    }

    private static JSONObject list(String listed, State state, Set<String> types, int limit) throws ToolProblem {
        return switch (listed) {
            case "types" -> listTypes(state, types);
            case "skills" -> listSkills(state, limit);
            default -> throw new ToolProblem("list is types or skills.");
        };
    }

    private static JSONObject listTypes(State state, Set<String> types) {
        var counts = new TreeMap<String, Integer>();
        for (var area : state.areas()) {
            if (area != null) {
                counts.merge(area.typeWord(), 1, Integer::sum);
            }
        }
        var list = new JSONArray();
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(entry -> {
                    var word = entry.getKey();
                    var known = AreaType.find(word);
                    var item = new JSONObject();
                    item.put("type", word.isEmpty() ? "(none)" : word);
                    item.put("name", known == null ? "" : AreaStyle.title(known));
                    item.put("areas", entry.getValue());
                    if (known == null && !word.isEmpty()) {
                        item.put("known", false);
                    }
                    if (!types.isEmpty() && !types.contains(word)) {
                        item.put("saved", false);
                    }
                    list.add(item);
                });
        var missing = new JSONArray();
        for (var type : AreaType.values()) {
            if (!types.contains(type.getKeyword())) {
                missing.add(type.getKeyword());
            }
        }
        var result = new JSONObject();
        result.put("types", list);
        result.put("of_the_file_as_saved", types.stream().sorted().toList());
        result.put("not_in_the_file", missing);
        result.put(
                "note",
                types.isEmpty()
                        ? "The file has no areas, so no type is new to it."
                        : "A type that no area of the file as saved has gives the warning Type new to this file: a"
                                + " server of another version may not know it. known false: a type this tool does not"
                                + " know, such as bless of High Five, which the file's own server reads.");
        return result;
    }

    private static JSONObject listSkills(State state, int limit) {
        var skills = AreaSkills.byUse(state);
        var list = new JSONArray();
        for (var skill : skills.subList(0, Math.min(limit, skills.size()))) {
            var item = new JSONObject();
            item.put("skill", skill.name());
            item.put("areas", skill.uses());
            list.add(item);
        }
        var result = new JSONObject();
        result.put("skills", list);
        result.put("more", skills.size() - list.size());
        return result;
    }

    private static Map<Integer, Integer> countFindings(State state, Set<String> types) {
        var counts = new HashMap<Integer, Integer>();
        for (Finding finding : AreaChecks.findAll(state, types)) {
            counts.merge(finding.area(), 1, Integer::sum);
        }
        return counts;
    }

    private static boolean matches(ServerArea area, String query) {
        if (area.name().toLowerCase(Locale.ROOT).contains(query)
                || area.typeWord().toLowerCase(Locale.ROOT).contains(query)
                || AreaStyle.title(area).toLowerCase(Locale.ROOT).contains(query)
                || area.note().toLowerCase(Locale.ROOT).contains(query)) {
            return true;
        }
        for (var key : List.of("skill_name", "skill_list")) {
            for (var value : area.values(key)) {
                if (value.toLowerCase(Locale.ROOT).contains(query)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static JSONArray corners(List<TerritoryPoint> points) {
        var list = new JSONArray();
        for (var corner : points) {
            list.add(corner.x() + "," + corner.y());
        }
        return list;
    }

    private static JSONArray places(List<AreaPoint> points) {
        var list = new JSONArray();
        for (var number = 0; number < points.size(); number++) {
            var place = points.get(number);
            var item = new JSONObject();
            item.put("number", number);
            item.put("point", place.x() + "," + place.y() + "," + place.z());
            list.add(item);
        }
        return list;
    }

    private static List<String> otherTiles(ServerArea area) {
        var slack = AreaChecks.BORDER_SLACK;
        var reached = new ArrayList<String>();
        for (var x = AreaTiles.tileX(area.minX() + slack); x <= AreaTiles.tileX(area.maxX() - slack); x++) {
            for (var y = AreaTiles.tileY(area.minY() + slack); y <= AreaTiles.tileY(area.maxY() - slack); y++) {
                if (x != area.centerTileX() || y != area.centerTileY()) {
                    reached.add(AreaTiles.format(x, y));
                }
            }
        }
        return reached;
    }
}
