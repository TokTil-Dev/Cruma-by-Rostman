package com.rost.cruma.viewer.ui;

import com.rost.cruma.core.geodata.GeoPoint;
import com.rost.cruma.viewer.geodata.GeoOverlayLines;
import imgui.ImColor;

final class WorldHandles {
    static final float PIXELS = 9;

    static final float CLOSE_PIXELS = 14;

    private static final int DARK = ImColor.rgba(20, 28, 36, 230);

    private WorldHandles() {}

    static void corner(GeoOverlayLines lines, GeoPoint point, boolean hot) {
        lines.handle(point, 6, DARK, hot ? 15 : 12);
        lines.handle(point, 6, ImColor.rgba(255, 255, 255, hot ? 255 : 225), hot ? 10 : 8);
    }

    static void closingCorner(GeoOverlayLines lines, GeoPoint point, int color, boolean hot) {
        lines.handle(point, 6, color, hot ? 22 : 17);
        corner(lines, point, hot);
    }

    static void midpoint(GeoOverlayLines lines, GeoPoint point, boolean hot) {
        lines.handle(point, 6, ImColor.rgba(255, 255, 255, hot ? 220 : 120), hot ? 9 : 6);
    }

    static void move(GeoOverlayLines lines, GeoPoint point, boolean hot) {
        lines.handle(point, 6, DARK, hot ? 18 : 15);
        lines.handle(point, 6, HudWidgets.ACCENT, hot ? 12 : 10);
    }

    static void blockedCorner(GeoOverlayLines lines, GeoPoint point, boolean hot) {
        lines.handle(point, 6, DARK, hot ? 15 : 12);
        lines.handle(point, 6, ImColor.rgba(255, 255, 255, hot ? 255 : 225), hot ? 10 : 8);
        lines.handle(point, 6, GeodataWidgets.BLOCKED, hot ? 6 : 5);
    }

    static void height(GeoOverlayLines lines, GeoPoint point, boolean hot) {
        lines.handle(point, 6, DARK, hot ? 17 : 14);
        lines.handle(point, 6, HudWidgets.ACCENT, hot ? 12 : 10);
        lines.handle(point, 6, ImColor.rgba(255, 255, 255, 235), hot ? 5 : 4);
    }

    static void point(GeoOverlayLines lines, GeoPoint point, boolean hot) {
        lines.handle(point, 4, DARK, hot ? 15 : 12);
        lines.handle(point, 4, ImColor.rgba(255, 255, 255, hot ? 255 : 210), hot ? 10 : 8);
    }
}
