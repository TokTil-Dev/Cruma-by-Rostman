package com.rost.cruma.viewer.ui;

import com.rost.cruma.viewer.config.Config.Viewer.Camera.Location;
import imgui.ImDrawList;
import org.jetbrains.annotations.Nullable;

public interface MapLayer {
    record Click(@Nullable Location destination, boolean close) {
        public static final Click HANDLED = new Click(null, true);
    }

    String name();

    default boolean isAvailable() {
        return true;
    }

    boolean isShown();

    void show(boolean shown);

    void draw(ImDrawList draw, MapTransform map);

    default @Nullable String getTooltipAt(MapTransform map, float x, float y) {
        return null;
    }

    default @Nullable Click getClickAt(MapTransform map, float x, float y) {
        return null;
    }

    default boolean openMenu(MapTransform map, float x, float y) {
        return false;
    }

    default @Nullable Click getMenu() {
        return null;
    }

    default boolean selectsArea() {
        return false;
    }

    default void selectArea(MapTransform map, float left, float top, float right, float bottom) {}

    default void drawOptions() {}

    default float drawLegend(ImDrawList draw, float left, float bottom) {
        return 0;
    }
}
