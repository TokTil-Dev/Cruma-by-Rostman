package com.rost.cruma.viewer.render.api;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class UiFade {
    public static final float MARK = -8191.5f;

    public static final int MARK_VERTICES = 4;

    public static final int VERTEX_BYTES = 20;

    private static final int U = 8;
    private static final int V = 12;
    private static final int ALPHA = 19;

    private static volatile boolean marked;
    private boolean active;
    private float minX;
    private float minY;
    private float maxX;
    private float maxY;
    private float frameAlpha;
    private float contentAlpha;
    private float offsetX;
    private float offsetY;
    private float scale;
    private float contentOffsetY;

    public static void marked() {
        marked = true;
    }

    public static boolean takeMarked() {
        var result = marked;
        marked = false;
        return result;
    }

    public static int find(ByteBuffer vertices, int count) {
        var data = vertices.duplicate().order(ByteOrder.nativeOrder());
        var base = data.position();
        for (var vertex = 0; vertex + MARK_VERTICES <= count; vertex++) {
            if (data.getFloat(base + vertex * VERTEX_BYTES + U) == MARK) {
                return vertex;
            }
        }
        return -1;
    }

    public void reset() {
        active = false;
    }

    public boolean isActive() {
        return active;
    }

    public void apply(ByteBuffer vertices, int count, boolean inside) {
        var data = vertices.duplicate().order(ByteOrder.nativeOrder());
        var base = data.position();
        var mark = find(vertices, count);
        if (mark >= 0) {
            read(data, base + mark * VERTEX_BYTES);
            active = true;
            change(data, base, 0, mark, frameAlpha, 0);
            change(data, base, mark + MARK_VERTICES, count, frameAlpha * contentAlpha, contentOffsetY);
        } else if (active && inside) {
            change(data, base, 0, count, frameAlpha * contentAlpha, contentOffsetY);
        } else {
            active = false;
        }
    }

    public boolean contains(float left, float top, float right, float bottom) {
        return active && left >= minX - 1 && top >= minY - 1 && right <= maxX + 1 && bottom <= maxY + 1;
    }

    public float clipX(float x) {
        return active ? centerX() + (x - centerX()) * scale + offsetX : x;
    }

    public float clipY(float y) {
        return active ? centerY() + (y - centerY()) * scale + offsetY : y;
    }

    private void read(ByteBuffer data, int at) {
        minX = data.getFloat(at);
        minY = data.getFloat(at + 4);
        frameAlpha = clamp(data.getFloat(at + V));
        maxX = data.getFloat(at + VERTEX_BYTES);
        maxY = data.getFloat(at + VERTEX_BYTES + 4);
        contentAlpha = clamp(data.getFloat(at + VERTEX_BYTES + V));
        offsetX = data.getFloat(at + 2 * VERTEX_BYTES);
        offsetY = data.getFloat(at + 2 * VERTEX_BYTES + 4);
        scale = data.getFloat(at + 3 * VERTEX_BYTES);
        contentOffsetY = data.getFloat(at + 3 * VERTEX_BYTES + 4);
        if (!(scale > 0)) {
            scale = 1;
        }
    }

    private void change(ByteBuffer data, int base, int from, int to, float alpha, float lift) {
        var moves = offsetX != 0 || offsetY != 0 || scale != 1 || lift != 0;
        var centerX = centerX();
        var centerY = centerY();
        for (var vertex = from; vertex < to; vertex++) {
            var at = base + vertex * VERTEX_BYTES;
            if (moves) {
                var x = data.getFloat(at);
                var y = data.getFloat(at + 4) + lift;
                data.putFloat(at, centerX + (x - centerX) * scale + offsetX);
                data.putFloat(at + 4, centerY + (y - centerY) * scale + offsetY);
            }
            if (alpha < 1) {
                var a = data.get(at + ALPHA) & 255;
                data.put(at + ALPHA, (byte) Math.round(a * alpha));
            }
        }
    }

    private float centerX() {
        return (minX + maxX) * .5f;
    }

    private float centerY() {
        return (minY + maxY) * .5f;
    }

    private static float clamp(float value) {
        return Float.isFinite(value) ? Math.clamp(value, 0, 1) : 1;
    }
}
