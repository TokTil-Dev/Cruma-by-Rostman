package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.NativeTerrainTimeLighting.Window;
import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.TreeSet;

final class P152TerrainEditScope {
    static final int GRID = 256;
    static final int MAX_SAMPLES = 1024;
    static final int MAX_WINDOWS = 9;
    static final int RAYS_PER_WINDOW = 4624;
    static final int KEPT_RINGS = 2;
    private static final int SECTOR = 16;

    private final BitSet changed;
    private final BitSet halo;
    private final List<Window> windows;

    private P152TerrainEditScope(BitSet changed, BitSet halo, List<Window> windows) {
        this.changed = changed;
        this.halo = halo;
        this.windows = List.copyOf(windows);
    }

    static P152TerrainEditScope of(TerrainHeightPatch patch) throws IOException {
        if (patch == null || patch.getWidth() != GRID || patch.getHeight() != GRID) {
            throw new IOException("This Terrain profile edits only the 256 by 256 height grid of 12_24.TerrainInfo0");
        }
        if (patch.size() == 0) {
            throw new IOException("The Terrain edit changes no height");
        }
        if (patch.size() > MAX_SAMPLES) {
            throw new IOException("The Terrain edit changes " + patch.size() + " heights; at most " + MAX_SAMPLES
                    + " can be verified in one export");
        }
        var changed = new BitSet(GRID * GRID);
        var halo = new BitSet(GRID * GRID);
        for (var i = 0; i < patch.size(); i++) {
            var index = patch.getIndex(i);
            var x = index % GRID;
            var y = index / GRID;
            if (isInOuterRings(x, y)) {
                throw new IOException("This verified 12_24 profile keeps the outer two rows of Terrain samples (sample "
                        + x + ", " + y + ")");
            }
            if (patch.getBefore(i) == patch.getAfter(i)) {
                throw new IOException("The Terrain edit lists an unchanged sample " + x + ", " + y);
            }
            changed.set(index);
            for (var dy = -1; dy <= 1; dy++) {
                for (var dx = -1; dx <= 1; dx++) {
                    halo.set((y + dy) * GRID + x + dx);
                }
            }
        }
        var origins = new TreeSet<Integer>();
        for (var index = halo.nextSetBit(0); index >= 0; index = halo.nextSetBit(index + 1)) {
            origins.add(index / GRID / SECTOR * GRID + index % GRID / SECTOR);
        }
        if (origins.size() > MAX_WINDOWS) {
            throw new IOException("The Terrain edit spans " + origins.size() + " sectors; at most " + MAX_WINDOWS
                    + " can be verified in one export");
        }
        var windows = new ArrayList<Window>();
        for (var origin : origins) {
            var offsetX = origin % GRID * SECTOR;
            var offsetY = origin / GRID * SECTOR;
            windows.add(new Window(
                    offsetX, offsetY, Math.min(SECTOR + 1, GRID - offsetX), Math.min(SECTOR + 1, GRID - offsetY)));
        }
        return new P152TerrainEditScope(changed, halo, windows);
    }

    static boolean touchesOuterRings(TerrainHeightPatch patch) {
        for (var i = 0; i < patch.size(); i++) {
            if (isInOuterRings(patch.getIndex(i) % patch.getWidth(), patch.getIndex(i) / patch.getWidth())) {
                return true;
            }
        }
        return false;
    }

    static boolean isInOuterRings(int x, int y) {
        return x < KEPT_RINGS || y < KEPT_RINGS || x >= GRID - KEPT_RINGS || y >= GRID - KEPT_RINGS;
    }

    static boolean contains(Window window, int x, int y) {
        return x >= window.offsetX()
                && y >= window.offsetY()
                && x < window.offsetX() + window.width()
                && y < window.offsetY() + window.height();
    }

    static boolean isCovered(List<Window> windows, int x, int y) {
        for (var window : windows) {
            if (contains(window, x, y)) {
                return true;
            }
        }
        return false;
    }

    BitSet getChanged() {
        return (BitSet) changed.clone();
    }

    BitSet getHalo() {
        return (BitSet) halo.clone();
    }

    List<Window> getWindows() {
        return windows;
    }

    int getMaximumRays() {
        return windows.size() * RAYS_PER_WINDOW;
    }
}
