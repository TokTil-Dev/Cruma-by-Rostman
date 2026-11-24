package com.rost.cruma.core.world;

import java.util.Objects;
import org.eclipse.collections.impl.list.mutable.primitive.IntArrayList;

public final class NativeZoneMembership {
    private final int[] leafZones;
    private final IntArrayList[] zones;

    public NativeZoneMembership(int[] leafZones, int[][] initialZones) {
        if (initialZones.length > 64) {
            throw new IllegalArgumentException("native BSP has at most 64 zones");
        }
        this.leafZones = leafZones.clone();
        zones = new IntArrayList[initialZones.length];
        for (var zone : leafZones) {
            Objects.checkIndex(zone, zones.length);
        }
        for (var zone = 0; zone < zones.length; zone++) {
            zones[zone] = new IntArrayList(initialZones[zone].clone());
        }
    }

    public int addActor(int actor, int drawType, boolean hasAntiPortal, int editor, int[] leaves) {
        if (editor != 0 || drawType != 11 || !hasAntiPortal) {
            return 0;
        }
        for (var leaf : leaves) {
            var zone = zones[leafZones[leaf]];
            if (!zone.contains(actor)) {
                zone.add(actor);
            }
        }
        return leaves.length;
    }

    public int removeActor(int actor, boolean hasAntiPortal, int editor, int[] leaves) {
        if (editor != 0 || !hasAntiPortal) {
            return 0;
        }
        for (var leaf : leaves) {
            zones[leafZones[leaf]].removeIf(value -> value == actor);
        }
        return leaves.length;
    }

    public int[][] snapshot() {
        var result = new int[zones.length][];
        for (var zone = 0; zone < zones.length; zone++) {
            result[zone] = zones[zone].toArray();
        }
        return result;
    }
}
