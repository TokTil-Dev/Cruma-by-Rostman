package com.rost.cruma.core.area;

import com.rost.cruma.core.area.AreaDocument.State;
import com.rost.cruma.core.spawn.Polygons;
import com.rost.cruma.core.spawn.SpawnEdits;
import com.rost.cruma.core.spawn.TerritoryPoint;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

public final class AreaEdits {
    public static final double MIN_PART_AREA = 1024;

    private static final Set<String> RECORD_WORDS =
            Set.of("area_begin", "area_end", "default_setting_begin", "default_setting_end");

    private static final Pattern NUMBERED = Pattern.compile("(.+)_(\\d{1,4})");

    private AreaEdits() {}

    public static State setSetting(State state, int index, String key, String value) {
        return change(state, index, area -> {
            var next = area.withSetting(key, value);
            return key.equals("range") || key.equals("water_range") ? followMap(next) : next;
        });
    }

    public static State removeSetting(State state, int index, String key) {
        return change(state, index, area -> area.withoutSetting(key));
    }

    public static State rename(State state, int index, String name) {
        return setSetting(state, index, "name", "[" + name + "]");
    }

    public static boolean isValidName(String name) {
        if (name.isEmpty()) {
            return false;
        }
        for (var i = 0; i < name.length(); i++) {
            var c = name.charAt(i);
            if (!(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '_')) {
                return false;
            }
        }
        return true;
    }

    public static boolean isValidWord(String word) {
        return isValidName(word) && Character.isLetter(word.charAt(0)) && !isRecordWord(word);
    }

    public static boolean isRecordWord(String word) {
        return RECORD_WORDS.contains(word);
    }

    public static boolean isValidValue(String key, String value) {
        if (value.isBlank() || !isValidName(key)) {
            return false;
        }
        var file = AreaDataFile.parse("area_begin\tname=[x]\t" + key + "=" + value + "\tarea_end");
        if (!file.getProblems().isEmpty() || file.getAreas().size() != 1) {
            return false;
        }
        var area = file.getAreas().getFirst();
        return area.settings().size() == 2 && area.values(key).equals(List.of(value));
    }

    public static State setNote(State state, int index, String note) {
        var clean = cleanNote(note);
        return change(state, index, area -> area.note().equals(clean) ? area : area.withNote(clean));
    }

    public static String cleanNote(String note) {
        return note.replace('\r', ' ').replace('\n', ' ').strip();
    }

    public static State setPoints(State state, int index, List<TerritoryPoint> points) {
        return change(state, index, area -> withPoints(area, points));
    }

    public static ServerArea withPoints(ServerArea area, List<TerritoryPoint> points) {
        var box = area.waterBox();
        if (box == null) {
            return followMap(area.withSetting("range", AreaDataWriter.formatPoints(points)));
        }
        if (points.isEmpty()) {
            return area;
        }
        var minX = Integer.MAX_VALUE;
        var minY = Integer.MAX_VALUE;
        var maxX = Integer.MIN_VALUE;
        var maxY = Integer.MIN_VALUE;
        var bottom = Integer.MAX_VALUE;
        var top = Integer.MIN_VALUE;
        for (var point : points) {
            minX = Math.min(minX, point.x());
            minY = Math.min(minY, point.y());
            maxX = Math.max(maxX, point.x());
            maxY = Math.max(maxY, point.y());
            bottom = Math.min(bottom, point.bottom());
            top = Math.max(top, point.top());
        }
        var next = new WaterBox(
                (int) box.minX() == minX ? box.minX() : minX,
                (int) box.minY() == minY ? box.minY() : minY,
                box.bottom() == bottom ? box.minZ() : bottom + WaterBox.HEIGHT_SHIFT,
                (int) box.maxX() == maxX ? box.maxX() : maxX,
                (int) box.maxY() == maxY ? box.maxY() : maxY,
                box.top() == top ? box.maxZ() : top + WaterBox.HEIGHT_SHIFT);
        return next.equals(box) ? area : followMap(withWaterBox(area, next));
    }

    public static State setHeights(State state, int index, @Nullable Integer bottom, @Nullable Integer top) {
        return change(state, index, area -> {
            var points = area.points().stream()
                    .map(point -> point.withHeights(
                            bottom == null ? point.bottom() : bottom, top == null ? point.top() : top))
                    .toList();
            return points.equals(area.points()) ? area : withPoints(area, points);
        });
    }

    public static State move(State state, int index, int dx, int dy) {
        return change(state, index, area -> moved(area, dx, dy));
    }

    public static ServerArea moved(ServerArea area, int dx, int dy) {
        if (dx == 0 && dy == 0) {
            return area;
        }
        var box = area.waterBox();
        var next = box != null
                ? withWaterBox(area, box.moved(dx, dy))
                : area.withSetting("range", AreaDataWriter.formatPoints(SpawnEdits.shifted(area.points(), dx, dy, 0)));
        if (!area.banned().isEmpty()) {
            next = next.withSetting(
                    "banned_range",
                    AreaDataWriter.formatPolygons(area.banned().stream()
                            .map(polygon -> SpawnEdits.shifted(polygon, dx, dy, 0))
                            .toList()));
        }
        return followMap(next);
    }

    public static State setBanned(State state, int index, List<List<TerritoryPoint>> polygons) {
        return change(
                state,
                index,
                area -> polygons.isEmpty()
                        ? area.withoutSetting("banned_range")
                        : area.withSetting("banned_range", AreaDataWriter.formatPolygons(polygons)));
    }

    public static State setTeleportPoints(State state, int index, List<AreaPoint> points) {
        return change(
                state,
                index,
                area -> points.isEmpty()
                        ? area.withoutSetting("teleport_points")
                        : area.withSetting("teleport_points", AreaDataWriter.formatPlaces(points)));
    }

    public static State setRestartPoints(State state, int index, List<AreaPoint> points) {
        return change(
                state,
                index,
                area -> points.isEmpty()
                        ? area.withoutSetting("restart_points")
                        : area.withSetting("restart_points", AreaDataWriter.formatPlaces(points)));
    }

    public static ServerArea raised(ServerArea area, int dz) {
        if (dz == 0 || area.points().isEmpty()) {
            return area;
        }
        var next = withPoints(
                area,
                area.points().stream()
                        .map(point -> point.withHeights(point.bottom() + dz, point.top() + dz))
                        .toList());
        if (!area.banned().isEmpty()) {
            next = next.withSetting(
                    "banned_range",
                    AreaDataWriter.formatPolygons(area.banned().stream()
                            .map(polygon -> SpawnEdits.shifted(polygon, 0, 0, dz))
                            .toList()));
        }
        return next;
    }

    public static State delete(State state, int index) {
        return state.withoutArea(index);
    }

    public static State duplicate(State state, int index, int dx, int dy) {
        var area = state.area(index);
        if (area == null) {
            return state;
        }
        var copy = area.detached(state.nextIndex()).withSetting("name", "[" + freeName(state, area.name()) + "]");
        return state.withArea(moved(copy, dx, dy));
    }

    public static ServerArea createArea(
            int index, boolean mapNumbers, AreaType type, String name, List<TerritoryPoint> points) {
        return createArea(index, mapNumbers, type.getKeyword(), name, points);
    }

    public static ServerArea createArea(
            int index, boolean mapNumbers, String typeWord, String name, List<TerritoryPoint> points) {
        var type = AreaType.find(typeWord);
        AreaSetting geometry;
        if (type == AreaType.WATER) {
            var bounds = AreaShape.of(List.of(AreaSetting.of("range", AreaDataWriter.formatPoints(points))));
            geometry = AreaSetting.of(
                    "water_range",
                    AreaDataWriter.formatWaterBox(new WaterBox(
                            bounds.minX(),
                            bounds.minY(),
                            bounds.bottom() + WaterBox.HEIGHT_SHIFT,
                            bounds.maxX(),
                            bounds.maxY(),
                            bounds.top() + WaterBox.HEIGHT_SHIFT)));
        } else {
            geometry = AreaSetting.of("range", AreaDataWriter.formatPoints(points));
        }
        var settings = new ArrayList<AreaSetting>();
        settings.add(AreaSetting.of("name", "[" + name + "]"));
        if (mapNumbers) {
            var shape = AreaShape.of(List.of(geometry));
            settings.add(AreaSetting.of(
                    "map_no",
                    AreaDataWriter.formatMap(AreaTiles.tileX(shape.centerX()), AreaTiles.tileY(shape.centerY()))));
        }
        settings.add(AreaSetting.of("type", typeWord));
        for (var setting : type == null ? List.<String>of() : typeSettings(type)) {
            var equals = setting.indexOf('=');
            settings.add(AreaSetting.of(setting.substring(0, equals), setting.substring(equals + 1)));
        }
        settings.add(geometry);
        return ServerArea.of(index, settings, "");
    }

    public static State newArea(
            State state, AreaDataFile file, AreaType type, String name, List<TerritoryPoint> points) {
        return newArea(state, file, type.getKeyword(), name, points);
    }

    public static State newArea(
            State state, AreaDataFile file, String typeWord, String name, List<TerritoryPoint> points) {
        return state.withArea(createArea(state.nextIndex(), file.usesMapNumbers(), typeWord, name, points));
    }

    public static State splitAtTileBorders(State state, int index) {
        var area = state.area(index);
        if (area == null || area.points().size() < 3) {
            return state;
        }
        var parts = cutIntoTiles(area);
        if (parts.size() < 2) {
            return state;
        }
        var main = parts.getFirst();
        for (var part : parts) {
            if (part.tileX() == area.centerTileX() && part.tileY() == area.centerTileY()) {
                main = part;
                break;
            }
            if (part.size() > main.size()) {
                main = part;
            }
        }
        var banned = new ArrayList<List<List<TerritoryPoint>>>();
        for (var ignored : parts) {
            banned.add(new ArrayList<>());
        }
        for (var polygon : area.banned()) {
            var shape = AreaShape.of(List.of(AreaSetting.of("range", AreaDataWriter.formatPoints(polygon))));
            var owner = parts.indexOf(main);
            for (var i = 0; i < parts.size(); i++) {
                if (parts.get(i).tileX() == AreaTiles.tileX(shape.centerX())
                        && parts.get(i).tileY() == AreaTiles.tileY(shape.centerY())) {
                    owner = i;
                }
            }
            banned.get(owner).add(polygon);
        }
        var next = state.withArea(withPart(area, main, banned.get(parts.indexOf(main))));
        for (var i = 0; i < parts.size(); i++) {
            var part = parts.get(i);
            if (part == main) {
                continue;
            }
            var copy = area.detached(next.nextIndex())
                    .withNote("")
                    .withSetting("name", "[" + freeName(next, area.name()) + "]");
            next = next.withArea(withPart(copy, part, banned.get(i)));
        }
        return next;
    }

    public static String freeName(State state, String base) {
        var used = collectNames(state);
        if (!used.contains(base)) {
            return base;
        }
        var stem = base;
        var number = 2;
        var digits = 1;
        var numbered = NUMBERED.matcher(base);
        if (numbered.matches()) {
            stem = numbered.group(1);
            number = Integer.parseInt(numbered.group(2)) + 1;
            digits = numbered.group(2).length();
        }
        for (; ; number++) {
            var name = stem + "_" + String.format(Locale.ROOT, "%0" + digits + "d", number);
            if (!used.contains(name)) {
                return name;
            }
        }
    }

    public static String newName(State state, AreaType type, int tileX, int tileY) {
        return newName(state, type.getKeyword(), tileX, tileY);
    }

    public static String newName(State state, String typeWord, int tileX, int tileY) {
        var word = typeWord.endsWith("_zone") ? typeWord.substring(0, typeWord.length() - "_zone".length()) : typeWord;
        var stem = String.format(Locale.ROOT, "%s_%02d%02d_", word, tileX, tileY);
        var used = collectNames(state);
        var highest = 0;
        for (var name : used) {
            if (name.startsWith(stem)) {
                var rest = name.substring(stem.length());
                if (!rest.isEmpty() && rest.length() <= 9 && rest.chars().allMatch(Character::isDigit)) {
                    highest = Math.max(highest, Integer.parseInt(rest));
                }
            }
        }
        for (var number = highest + 1; ; number++) {
            var name = stem + String.format(Locale.ROOT, "%02d", number);
            if (!used.contains(name)) {
                return name;
            }
        }
    }

    private record Part(
            int tileX,
            int tileY,
            List<TerritoryPoint> points,
            @Nullable WaterBox box,
            double size) {}

    private static List<Part> cutIntoTiles(ServerArea area) {
        var parts = new ArrayList<Part>();
        var box = area.waterBox();
        for (var tileX = AreaTiles.tileX(area.minX()); tileX <= AreaTiles.tileX(area.maxX()); tileX++) {
            for (var tileY = AreaTiles.tileY(area.minY()); tileY <= AreaTiles.tileY(area.maxY()); tileY++) {
                double minX = AreaTiles.originX(tileX);
                double minY = AreaTiles.originY(tileY);
                var maxX = minX + AreaTiles.SIZE;
                var maxY = minY + AreaTiles.SIZE;
                if (box != null) {
                    var cut = new WaterBox(
                            Math.max(box.minX(), minX),
                            Math.max(box.minY(), minY),
                            box.minZ(),
                            Math.min(box.maxX(), maxX),
                            Math.min(box.maxY(), maxY),
                            box.maxZ());
                    var size = (cut.maxX() - cut.minX()) * (cut.maxY() - cut.minY());
                    if (cut.maxX() > cut.minX() && cut.maxY() > cut.minY() && size >= MIN_PART_AREA) {
                        parts.add(new Part(tileX, tileY, cut.corners(), cut, size));
                    }
                    continue;
                }
                var bottom = area.bottom();
                var top = area.top();
                var points = Polygons.clip(area.points(), minX, minY, maxX, maxY).stream()
                        .map(point -> point.withHeights(bottom, top))
                        .toList();
                var size = Polygons.area(points);
                if (points.size() >= 3 && size >= MIN_PART_AREA) {
                    parts.add(new Part(tileX, tileY, points, null, size));
                }
            }
        }
        return parts;
    }

    private static ServerArea withPart(ServerArea area, Part part, List<List<TerritoryPoint>> banned) {
        var next = part.box() != null
                ? withWaterBox(area, part.box())
                : area.withSetting("range", AreaDataWriter.formatPoints(part.points()));
        next = banned.isEmpty()
                ? next.withoutSetting("banned_range")
                : next.withSetting("banned_range", AreaDataWriter.formatPolygons(banned));
        return followMap(next);
    }

    private static ServerArea withWaterBox(ServerArea area, WaterBox box) {
        return area.withSetting("water_range", AreaDataWriter.formatWaterBox(box));
    }

    private static ServerArea followMap(ServerArea area) {
        if (!area.hasMap() || area.points().isEmpty()) {
            return area;
        }
        var tileX = area.centerTileX();
        var tileY = area.centerTileY();
        return area.mapX() == tileX && area.mapY() == tileY
                ? area
                : area.withSetting("map_no", AreaDataWriter.formatMap(tileX, tileY));
    }

    private static List<String> typeSettings(AreaType type) {
        return switch (type) {
            case POISON, INSTANT_SKILL -> List.of("target=pc", "skill_prob=100", "unit_tick=5", "default_status=on");
            case DAMAGE -> List.of("damage_on_hp=100", "damage_on_mp=0", "unit_tick=5", "default_status=on");
            case SWAMP -> List.of("move_bonus=-50");
            case NO_RESTART -> List.of("restart_time=600");
            case TELEPORT -> List.of("unit_tick=5", "target=pc");
            case MOTHER_TREE -> List.of("affect_race=elf", "hp_regen_bonus=2", "mp_regen_bonus=1");
            case SSQ_ZONE -> List.of("exp_penalty_per=25", "item_drop=off");
            default -> List.of();
        };
    }

    private static Set<String> collectNames(State state) {
        var names = new HashSet<String>();
        for (var area : state.areas()) {
            if (area != null) {
                names.add(area.name());
            }
        }
        return names;
    }

    private static State change(State state, int index, UnaryOperator<ServerArea> edit) {
        var area = state.area(index);
        if (area == null) {
            return state;
        }
        var next = edit.apply(area);
        return next == area || next.equals(area) ? state : state.withArea(next);
    }
}
