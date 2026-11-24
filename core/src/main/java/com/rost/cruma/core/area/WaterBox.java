package com.rost.cruma.core.area;

import com.rost.cruma.core.spawn.TerritoryPoint;
import java.util.List;

public record WaterBox(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
    public static final int HEIGHT_SHIFT = 7;

    public int bottom() {
        return (int) minZ - HEIGHT_SHIFT;
    }

    public int top() {
        return (int) maxZ - HEIGHT_SHIFT;
    }

    public List<TerritoryPoint> corners() {
        var bottom = bottom();
        var top = top();
        return List.of(
                new TerritoryPoint((int) minX, (int) minY, bottom, top),
                new TerritoryPoint((int) maxX, (int) minY, bottom, top),
                new TerritoryPoint((int) maxX, (int) maxY, bottom, top),
                new TerritoryPoint((int) minX, (int) maxY, bottom, top));
    }

    public WaterBox moved(double dx, double dy) {
        return new WaterBox(minX + dx, minY + dy, minZ, maxX + dx, maxY + dy, maxZ);
    }
}
