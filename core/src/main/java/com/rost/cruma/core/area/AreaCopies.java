package com.rost.cruma.core.area;

import com.rost.cruma.core.area.AreaDocument.State;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.Nullable;

public final class AreaCopies {
    private AreaCopies() {}

    public record Pasted(State state, List<Integer> indexes) {}

    public static String copyAsText(AreaDataFile file, ServerArea area) {
        var lineEnding = file.getSource().lineEnding();
        var text = file.getSource().text();
        var span = area.span();
        var original = area.index() < file.getAreas().size() ? file.getAreas().get(area.index()) : null;
        var record = span.isPresent() && span.end() <= text.length() && area.equals(original)
                ? text.substring(span.start(), span.end())
                : AreaDataWriter.formatArea(area);
        return (area.note().isEmpty() ? "" : "//" + area.note() + lineEnding) + record + lineEnding;
    }

    public static @Nullable Pasted paste(State state, AreaDataFile file, String text, double x, double y, double z) {
        var areas = AreaDataFile.parse(text).getAreas().stream()
                .filter(area -> area.points().size() >= 3)
                .toList();
        if (areas.isEmpty()) {
            return null;
        }
        var minX = Integer.MAX_VALUE;
        var minY = Integer.MAX_VALUE;
        var maxX = Integer.MIN_VALUE;
        var maxY = Integer.MIN_VALUE;
        var bottom = Integer.MAX_VALUE;
        var top = Integer.MIN_VALUE;
        for (var area : areas) {
            minX = Math.min(minX, area.minX());
            minY = Math.min(minY, area.minY());
            maxX = Math.max(maxX, area.maxX());
            maxY = Math.max(maxY, area.maxY());
            bottom = Math.min(bottom, area.bottom());
            top = Math.max(top, area.top());
        }
        var dx = (int) Math.round(x - (minX + (double) maxX) / 2);
        var dy = (int) Math.round(y - (minY + (double) maxY) / 2);
        var dz = (int) Math.round(z - (bottom + (double) top) / 2);
        var next = state;
        var indexes = new ArrayList<Integer>();
        for (var area : areas) {
            var index = next.nextIndex();
            var copy = area.detached(index).withSetting("name", "[" + AreaEdits.freeName(next, area.name()) + "]");
            if (file.usesMapNumbers() && !copy.hasMap()) {
                var settings = new ArrayList<>(copy.settings());
                settings.add(Math.min(1, settings.size()), AreaSetting.of("map_no", AreaDataWriter.formatMap(0, 0)));
                copy = copy.withSettings(settings);
            } else if (!file.usesMapNumbers() && copy.hasMap()) {
                copy = copy.withoutSetting("map_no");
            }
            copy = AreaEdits.raised(AreaEdits.moved(copy, dx, dy), dz);
            if (copy.hasMap()) {
                copy = copy.withSetting("map_no", AreaDataWriter.formatMap(copy.centerTileX(), copy.centerTileY()));
            }
            next = next.withArea(copy);
            indexes.add(index);
        }
        return new Pasted(next, indexes);
    }
}
