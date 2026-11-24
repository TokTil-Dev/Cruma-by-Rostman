package com.rost.cruma.core.geodata;

public record GeoCellRef(String sourceId, long revision, int x, int y, int layer) {
    public boolean belongsTo(GeoDataAccess data) {
        return revision == data.revision() && sourceId.equals(data.getSourceId());
    }
}
