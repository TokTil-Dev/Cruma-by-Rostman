package com.rost.cruma.viewer.geodata;

import com.rost.cruma.core.geodata.GeoDataAccess;
import com.rost.cruma.core.geodata.GeoDataAccess.Availability;
import com.rost.cruma.core.geodata.GeoDataAccess.Cell;
import com.rost.cruma.core.geodata.GeoPoint;
import java.util.ArrayList;
import java.util.List;

public final class GeoDataCrossSection {
    private static final int MAX_LAYERS = 16_384;

    private GeoDataCrossSection() {}

    public record Span(double start, double end, Cell cell) {}

    public record Section(List<Span> spans, double length, boolean truncated, boolean unavailable) {
        public Section {
            spans = List.copyOf(spans);
        }
    }

    public static Section sample(GeoDataAccess data, GeoPoint a, GeoPoint b, int maxCells) {
        if (maxCells < 1 || maxCells > 65536) {
            throw new IllegalArgumentException("Invalid slice cell limit");
        }
        var dx = b.x() - a.x();
        var dy = b.y() - a.y();
        var length = Math.hypot(dx, dy);
        var x = (int) Math.floor(a.x() / 16) * 16;
        var y = (int) Math.floor(a.y() / 16) * 16;
        if (length < 1e-9) {
            var cell = data.cellAt(x, y);
            if (cell.layers().size() > MAX_LAYERS) {
                return new Section(List.of(), 16, true, cell.availability() != Availability.AVAILABLE);
            }
            return new Section(
                    List.of(new Span(0, 16, cell)), 16, false, cell.availability() != Availability.AVAILABLE);
        }
        dx /= length;
        dy /= length;
        var tx = dx == 0 ? Double.POSITIVE_INFINITY : ((dx < 0 ? x : x + 16) - a.x()) / dx;
        var ty = dy == 0 ? Double.POSITIVE_INFINITY : ((dy < 0 ? y : y + 16) - a.y()) / dy;
        var stepX = dx < 0 ? -16 : 16;
        var stepY = dy < 0 ? -16 : 16;
        var dtx = dx == 0 ? Double.POSITIVE_INFINITY : Math.abs(16 / dx);
        var dty = dy == 0 ? Double.POSITIVE_INFINITY : Math.abs(16 / dy);
        var spans = new ArrayList<Span>();
        var entered = 0.0;
        var unavailable = false;
        var layers = 0;
        while (spans.size() < maxCells && entered < length) {
            var end = Math.min(length, Math.min(tx, ty));
            if (end > entered) {
                var cell = data.cellAt(x, y);
                unavailable |= cell.availability() != Availability.AVAILABLE;
                if (layers + cell.layers().size() > MAX_LAYERS) {
                    break;
                }
                layers += cell.layers().size();
                spans.add(new Span(entered, end, cell));
            }
            var crossX = tx <= ty;
            var crossY = ty <= tx;
            entered = end;
            if (crossX) {
                x += stepX;
                tx += dtx;
            }
            if (crossY) {
                y += stepY;
                ty += dty;
            }
        }
        return new Section(spans, length, entered < length, unavailable);
    }
}
