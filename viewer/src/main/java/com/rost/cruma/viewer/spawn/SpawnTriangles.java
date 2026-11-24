package com.rost.cruma.viewer.spawn;

import java.util.Arrays;

public final class SpawnTriangles {
    public static final int FLOATS = 4;
    public static final int MAX_VERTICES = 600_000;

    private float[] building = new float[3 * 1024 * FLOATS];
    private int buildingCount;
    private float[] shown = new float[0];
    private int shownCount;

    public void begin() {
        buildingCount = 0;
    }

    public boolean wouldOverflow(int vertices) {
        return buildingCount + vertices > MAX_VERTICES;
    }

    public void addTriangle(
            float ax,
            float ay,
            float az,
            int colorA,
            float bx,
            float by,
            float bz,
            int colorB,
            float cx,
            float cy,
            float cz,
            int colorC) {
        if (wouldOverflow(3)) {
            return;
        }
        addVertex(ax, ay, az, colorA);
        addVertex(bx, by, bz, colorB);
        addVertex(cx, cy, cz, colorC);
    }

    public void addQuad(
            float ax,
            float ay,
            float az,
            int colorA,
            float bx,
            float by,
            float bz,
            int colorB,
            float cx,
            float cy,
            float cz,
            int colorC,
            float dx,
            float dy,
            float dz,
            int colorD) {
        addTriangle(ax, ay, az, colorA, bx, by, bz, colorB, cx, cy, cz, colorC);
        addTriangle(ax, ay, az, colorA, cx, cy, cz, colorC, dx, dy, dz, colorD);
    }

    public synchronized void publish() {
        if (shown.length < buildingCount * FLOATS) {
            shown = new float[building.length];
        }
        System.arraycopy(building, 0, shown, 0, buildingCount * FLOATS);
        shownCount = buildingCount;
    }

    public synchronized void clear() {
        buildingCount = 0;
        shownCount = 0;
    }

    public synchronized float[] getShown() {
        return shown;
    }

    public synchronized int getShownCount() {
        return shownCount;
    }

    private void addVertex(float x, float y, float z, int color) {
        if ((buildingCount + 1) * FLOATS > building.length) {
            building = Arrays.copyOf(building, building.length * 2);
        }
        var offset = buildingCount * FLOATS;
        building[offset] = x;
        building[offset + 1] = y;
        building[offset + 2] = z;
        building[offset + 3] = Float.intBitsToFloat(color);
        buildingCount++;
    }
}
