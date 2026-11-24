package com.rost.cruma.core.spawn;

public record TerritoryPoint(int x, int y, int bottom, int top) {
    public TerritoryPoint withXy(int newX, int newY) {
        return new TerritoryPoint(newX, newY, bottom, top);
    }

    public TerritoryPoint withHeights(int newBottom, int newTop) {
        return new TerritoryPoint(x, y, newBottom, newTop);
    }
}
