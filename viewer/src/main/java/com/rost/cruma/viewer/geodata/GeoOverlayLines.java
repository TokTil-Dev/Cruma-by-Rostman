package com.rost.cruma.viewer.geodata;

import com.rost.cruma.core.geodata.GeoPoint;
import com.rost.cruma.core.geometry.Bounds3f;
import java.util.Arrays;

public final class GeoOverlayLines {
    public static final int FLOATS = 10;

    public static final int MAX_PRIMITIVES = 32_768;

    public static final float LINE = 0;
    public static final float DOT = 1;

    public static final float HANDLE = 2;

    public static final int[][] BOX_EDGES = {
        {0, 1},
        {2, 3},
        {4, 5},
        {6, 7},
        {0, 2},
        {1, 3},
        {4, 6},
        {5, 7},
        {0, 4},
        {1, 5},
        {2, 6},
        {3, 7}
    };

    private float[] building = new float[256 * FLOATS];
    private int buildingCount;
    private float[] shown = new float[0];
    private int shownCount;
    private float shownPoints = 1;

    public void begin() {
        buildingCount = 0;
    }

    public void addLine(GeoPoint a, GeoPoint b, float lift, int color, float width) {
        add(
                (float) a.x(),
                (float) a.y(),
                (float) a.z() + lift,
                (float) b.x(),
                (float) b.y(),
                (float) b.z() + lift,
                width,
                color,
                LINE,
                (float) Math.sqrt(a.distanceSquared(b)));
    }

    public void addLine(float ax, float ay, float az, float bx, float by, float bz, int color, float width) {
        var dx = bx - ax;
        var dy = by - ay;
        var dz = bz - az;
        add(ax, ay, az, bx, by, bz, width, color, LINE, (float) Math.sqrt(dx * dx + dy * dy + dz * dz));
    }

    public void addBox(Bounds3f box, int color, float width) {
        var corners = new GeoPoint[8];
        for (var corner = 0; corner < 8; corner++) {
            corners[corner] = new GeoPoint(
                    (corner & 1) == 0 ? box.minX() : box.maxX(),
                    (corner & 2) == 0 ? box.minY() : box.maxY(),
                    (corner & 4) == 0 ? box.minZ() : box.maxZ());
        }
        for (var edge : BOX_EDGES) {
            addLine(corners[edge[0]], corners[edge[1]], 0, color, width);
        }
    }

    public void addDot(GeoPoint point, float lift, int color, float diameter) {
        var x = (float) point.x();
        var y = (float) point.y();
        var z = (float) point.z() + lift;
        add(x, y, z, x, y, z, diameter, color, DOT, 0);
    }

    public void handle(GeoPoint point, float lift, int color, float diameter) {
        var x = (float) point.x();
        var y = (float) point.y();
        var z = (float) point.z() + lift;
        add(x, y, z, x, y, z, diameter, color, HANDLE, 0);
    }

    public void handle(float x, float y, float z, int color, float diameter) {
        add(x, y, z, x, y, z, diameter, color, HANDLE, 0);
    }

    public synchronized void publish(float viewportPoints) {
        shownPoints = viewportPoints;
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

    public synchronized float getShownPoints() {
        return shownPoints;
    }

    private void add(
            float ax,
            float ay,
            float az,
            float bx,
            float by,
            float bz,
            float width,
            int color,
            float kind,
            float length) {
        if (buildingCount >= MAX_PRIMITIVES) {
            return;
        }
        if ((buildingCount + 1) * FLOATS > building.length) {
            building = Arrays.copyOf(building, building.length * 2);
        }
        var offset = buildingCount * FLOATS;
        building[offset] = ax;
        building[offset + 1] = ay;
        building[offset + 2] = az;
        building[offset + 3] = bx;
        building[offset + 4] = by;
        building[offset + 5] = bz;
        building[offset + 6] = width;
        building[offset + 7] = Float.intBitsToFloat(color);
        building[offset + 8] = kind;
        building[offset + 9] = length;
        buildingCount++;
    }
}
