package com.rost.cruma.viewer.spawn;

import com.rost.cruma.core.geodata.GeoDataAccess;
import org.jetbrains.annotations.Nullable;

public interface AreaAccess {
    AreaSource getSource();

    boolean isHeld();

    boolean hasGeodata();

    @Nullable
    GeoDataAccess readArea(double minX, double minY, double maxX, double maxY);

    double findClientFloor(double x, double y, double z);
}
