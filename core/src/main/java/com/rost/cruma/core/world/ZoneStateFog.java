package com.rost.cruma.core.world;

import com.rost.cruma.core.world.Entity.DistanceFog;

public record ZoneStateFog(String zone, String map, int state, int zoneDefault, DistanceFog fog) {
    public String colorText() {
        return Math.round(fog.color().getX() * 255)
                + ", "
                + Math.round(fog.color().getY() * 255)
                + ", "
                + Math.round(fog.color().getZ() * 255);
    }
}
