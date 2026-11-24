package com.rost.cruma.viewer.agent.geo;

import com.rost.cruma.core.geodata.GeoDataAccess;
import com.rost.cruma.core.geodata.GeoDataAccess.Availability;
import com.rost.cruma.core.geodata.GeoDataAccess.Cell;
import com.rost.cruma.core.geodata.GeoPoint;
import com.rost.cruma.core.geodata.GeoQueryEngine;
import com.rost.cruma.core.geodata.GeoQueryEngine.Selection;
import com.rost.cruma.viewer.agent.Target;
import com.rost.cruma.viewer.agent.Target.Kind;
import org.jetbrains.annotations.Nullable;

record GeoSpot(double x, double y, @Nullable Double z, boolean exact) {
    static GeoSpot of(Target located, String text) {
        var named = Target.parse(text);
        return new GeoSpot(
                located.x(),
                located.y(),
                located.z() == null ? null : Double.valueOf(located.z()),
                named.kind() == Kind.POINT && named.z() != null);
    }

    Cell cell(GeoDataAccess data) {
        return data.cellAt(GeoQueryEngine.serverInt(x), GeoQueryEngine.serverInt(y));
    }

    @Nullable
    Selection floor(GeoDataAccess data) {
        var cell = cell(data);
        if (cell.availability() != Availability.AVAILABLE || cell.layers().isEmpty()) {
            return null;
        }
        if (z == null) {
            return new Selection(cell, cell.layers().getFirst());
        }
        return new GeoQueryEngine(data).findBaseCell(cell, GeoQueryEngine.serverInt(z));
    }

    GeoPoint point(GeoDataAccess data) {
        if (z != null) {
            return new GeoPoint(x, y, z);
        }
        var floor = floor(data);
        return new GeoPoint(x, y, floor == null ? 0 : floor.layer().height());
    }
}
