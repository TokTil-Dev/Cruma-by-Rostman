package com.rost.cruma.core.area;

import com.rost.cruma.core.spawn.SourceSpan;
import com.rost.cruma.core.spawn.TerritoryPoint;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;

public record ServerArea(
        int index,
        List<AreaSetting> settings,
        String note,
        SourceSpan noteSpan,
        int line,
        SourceSpan span,
        AreaShape shape) {
    public static final int DEFAULT_UNIT_TICK = 9;

    public static final int DEFAULT_SKILL_PROB = 100;

    public static final int DEFAULT_RESTART_TIME = 600;

    public static final int DEFAULT_ON_OFF_TIME = 30;

    public ServerArea {
        settings = List.copyOf(settings);
        Objects.requireNonNull(note, "note");
        Objects.requireNonNull(noteSpan, "noteSpan");
        Objects.requireNonNull(span, "span");
        shape = AreaShape.of(settings);
    }

    public ServerArea(
            int index, List<AreaSetting> settings, String note, SourceSpan noteSpan, int line, SourceSpan span) {
        this(index, settings, note, noteSpan, line, span, AreaShape.EMPTY);
    }

    public static ServerArea of(int index, List<AreaSetting> settings, String note) {
        return new ServerArea(index, settings, note, SourceSpan.NONE, -1, SourceSpan.NONE);
    }

    public boolean isInFile() {
        return span.isPresent();
    }

    public String name() {
        var value = get("name");
        if (value == null) {
            return "";
        }
        return value.length() >= 2 && value.startsWith("[") && value.endsWith("]")
                ? value.substring(1, value.length() - 1)
                : value;
    }

    public String typeWord() {
        var value = get("type");
        return value == null ? "" : value;
    }

    public @Nullable AreaType type() {
        return AreaType.find(typeWord());
    }

    public boolean hasMap() {
        return get("map_no") != null;
    }

    public int mapX() {
        var map = readMap();
        return map == null ? -1 : map[0];
    }

    public int mapY() {
        var map = readMap();
        return map == null ? -1 : map[1];
    }

    public boolean isWater() {
        return shape.waterBox() != null;
    }

    public List<TerritoryPoint> points() {
        return shape.points();
    }

    public @Nullable WaterBox waterBox() {
        return shape.waterBox();
    }

    public List<List<TerritoryPoint>> banned() {
        return shape.banned();
    }

    public List<TerritoryPoint> teleportRange() {
        return shape.teleportRange();
    }

    public List<AreaPoint> teleportPoints() {
        return shape.teleportPoints();
    }

    public List<AreaPoint> restartPoints() {
        return shape.restartPoints();
    }

    public int bottom() {
        return shape.bottom();
    }

    public int top() {
        return shape.top();
    }

    public int minX() {
        return shape.minX();
    }

    public int maxX() {
        return shape.maxX();
    }

    public int minY() {
        return shape.minY();
    }

    public int maxY() {
        return shape.maxY();
    }

    public double area() {
        return shape.area();
    }

    public double centerX() {
        return shape.centerX();
    }

    public double centerY() {
        return shape.centerY();
    }

    public int centerTileX() {
        return AreaTiles.tileX(centerX());
    }

    public int centerTileY() {
        return AreaTiles.tileY(centerY());
    }

    public boolean containsXy(double x, double y) {
        return shape.containsXy(x, y);
    }

    public boolean contains(double x, double y, double z) {
        return shape.contains(x, y, z);
    }

    public boolean isOnByDefault() {
        return !"off".equals(get("default_status"));
    }

    public String target() {
        var value = get("target");
        return value == null ? "all" : value;
    }

    public boolean hasSetting(String key) {
        return get(key) != null;
    }

    public @Nullable String get(String key) {
        for (var i = settings.size() - 1; i >= 0; i--) {
            if (settings.get(i).key().equals(key)) {
                return settings.get(i).value();
            }
        }
        return null;
    }

    public int getInt(String key, int fallback) {
        var value = get(key);
        var number = value == null ? null : AreaValues.parseNumber(value.strip());
        return number == null ? fallback : (int) (double) number;
    }

    public long getSeconds(String key, long fallback) {
        var value = get(key);
        var seconds = value == null ? -1 : AreaValues.parseSeconds(value);
        return seconds < 0 ? fallback : seconds;
    }

    public List<String> values(String key) {
        var values = new ArrayList<String>();
        for (var setting : settings) {
            if (setting.key().equals(key)) {
                values.add(setting.value());
            }
        }
        return values;
    }

    public int indexOf(String key) {
        for (var i = 0; i < settings.size(); i++) {
            if (settings.get(i).key().equals(key)) {
                return i;
            }
        }
        return -1;
    }

    public int effectiveUnitTick() {
        var tick = DEFAULT_UNIT_TICK;
        for (var setting : settings) {
            if (setting.key().equals("type")) {
                var type = AreaType.find(setting.value());
                if (type != null && type.getTypeUnitTick() > 0) {
                    tick = type.getTypeUnitTick();
                }
            } else if (setting.key().equals("unit_tick")) {
                var number = AreaValues.parseNumber(setting.value().strip());
                if (number != null) {
                    tick = (int) (double) number;
                }
            }
        }
        return tick;
    }

    public boolean hasLostUnitTick() {
        var tick = indexOf("unit_tick");
        if (tick < 0) {
            return false;
        }
        for (var i = tick + 1; i < settings.size(); i++) {
            var setting = settings.get(i);
            if (setting.key().equals("unit_tick")) {
                return false;
            }
            if (setting.key().equals("type")) {
                var type = AreaType.find(setting.value());
                if (type != null && type.getTypeUnitTick() > 0) {
                    return true;
                }
            }
        }
        return false;
    }

    public ServerArea withSettings(List<AreaSetting> newSettings) {
        return new ServerArea(index, newSettings, note, noteSpan, line, span);
    }

    public ServerArea withSetting(String key, String value) {
        var copy = new ArrayList<>(settings);
        for (var i = copy.size() - 1; i >= 0; i--) {
            if (copy.get(i).key().equals(key)) {
                if (copy.get(i).value().equals(value)) {
                    return this;
                }
                copy.set(i, copy.get(i).withValue(value));
                return withSettings(copy);
            }
        }
        copy.add(AreaSetting.of(key, value));
        return withSettings(copy);
    }

    public ServerArea withoutSetting(String key) {
        if (!hasSetting(key)) {
            return this;
        }
        return withSettings(
                settings.stream().filter(setting -> !setting.key().equals(key)).toList());
    }

    public ServerArea withNote(String newNote) {
        return new ServerArea(index, settings, newNote, noteSpan, line, span);
    }

    public ServerArea detached(int newIndex) {
        return of(newIndex, settings.stream().map(AreaSetting::detached).toList(), note);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ServerArea area
                && index == area.index
                && line == area.line
                && settings.equals(area.settings)
                && note.equals(area.note)
                && noteSpan.equals(area.noteSpan)
                && span.equals(area.span);
    }

    @Override
    public int hashCode() {
        return Objects.hash(index, settings, note, noteSpan, line, span);
    }

    private int @Nullable [] readMap() {
        var value = get("map_no");
        var numbers = value == null ? null : AreaValues.parseInts(value);
        return numbers == null || numbers.length != 2 ? null : numbers;
    }
}
