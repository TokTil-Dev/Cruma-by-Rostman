package com.rost.cruma.viewer.geodata;

import com.rost.cruma.core.geodata.GeoDataAccess;
import com.rost.cruma.core.geodata.GeoDataAccess.Availability;
import com.rost.cruma.core.geodata.GeoDataAccess.Cell;
import com.rost.cruma.core.geodata.GeoDataAccess.Layer;
import com.rost.cruma.core.geodata.GeoDataRegion;
import java.util.function.BooleanSupplier;
import org.jetbrains.annotations.Nullable;

public final class GeoPlaces {
    private static final int CELL = GeoDataRegion.CELL_SIZE;
    private static final int ALL_WAYS =
            GeoDataRegion.EAST | GeoDataRegion.WEST | GeoDataRegion.SOUTH | GeoDataRegion.NORTH;

    private GeoPlaces() {}

    @FunctionalInterface
    public interface Wanted {
        @Nullable
        Layer in(Cell cell);
    }

    public record Place(int x, int y, Layer layer, int layers) {
        public double middleX() {
            return x + CELL / 2.0;
        }

        public double middleY() {
            return y + CELL / 2.0;
        }
    }

    public static @Nullable Place nearest(
            GeoDataAccess data, double x, double y, int radius, Wanted wanted, BooleanSupplier stopped) {
        var column = Math.floorDiv((int) Math.floor(x), CELL);
        var row = Math.floorDiv((int) Math.floor(y), CELL);
        var rings = Math.max(0, radius / CELL);
        for (var ring = 0; ring <= rings; ring++) {
            if (stopped.getAsBoolean()) {
                return null;
            }
            for (var step = -ring; step <= ring; step++) {
                var found = check(data, column + step, row - ring, wanted);
                if (found == null && ring > 0) {
                    found = check(data, column + step, row + ring, wanted);
                }
                if (found == null && ring > 0 && step > -ring && step < ring) {
                    found = check(data, column - ring, row + step, wanted);
                    if (found == null) {
                        found = check(data, column + ring, row + step, wanted);
                    }
                }
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    public static @Nullable Layer severalFloors(Cell cell) {
        return cell.layers().size() >= 2 ? cell.layers().getFirst() : null;
    }

    public static @Nullable Layer closedSide(Cell cell) {
        for (var layer : cell.layers()) {
            var ways = layer.nswe() & ALL_WAYS;
            if (ways != 0 && ways != ALL_WAYS) {
                return layer;
            }
        }
        return null;
    }

    private static @Nullable Place check(GeoDataAccess data, int column, int row, Wanted wanted) {
        var cell = data.cellAt(column * CELL, row * CELL);
        if (cell.availability() != Availability.AVAILABLE) {
            return null;
        }
        var layer = wanted.in(cell);
        return layer == null
                ? null
                : new Place(cell.x(), cell.y(), layer, cell.layers().size());
    }
}
