package com.rost.cruma.core.geodata;

import com.rost.cruma.core.geodata.GeoEditDocument.CellKey;
import java.util.List;
import java.util.Map;

public interface GeoDataAccess {
    enum Availability {
        AVAILABLE,
        NOT_LOADED,
        MISSING,
        CORRUPT,
        OUTSIDE_WORLD
    }

    enum Storage {
        FLAT,
        SINGLE,
        MULTI
    }

    record Layer(int index, int height, int nswe) {}

    record Cell(int x, int y, Availability availability, List<Layer> layers, String detail, Storage storage) {
        public Cell {
            layers = List.copyOf(layers);
        }

        public Cell(int x, int y, Availability availability, List<Layer> layers, String detail) {
            this(x, y, availability, layers, detail, Storage.MULTI);
        }
    }

    Cell cellAt(int worldX, int worldY);

    long revision();

    default long getContentRevision() {
        return revision();
    }

    String getSourceId();

    default GeoDataAccess editBase() {
        return this;
    }

    default Map<CellKey, List<Layer>> getEditedCells() {
        return Map.of();
    }
}
