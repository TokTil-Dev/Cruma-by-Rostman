package com.rost.cruma.viewer.geodata;

public record GeoDataChunk(int x, int y, int size, GeoDataMesh mesh, float minZ, float maxZ) {
    public record Place(int x, int y, int size) {}

    public Place place() {
        return new Place(x, y, size);
    }
}
