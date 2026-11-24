package com.rost.cruma.viewer.geodata;

import com.rost.cruma.core.geodata.GeoDataAccess;
import com.rost.cruma.core.geodata.GeoDataAccess.Cell;
import com.rost.cruma.core.geodata.GeoDataAccess.Layer;
import com.rost.cruma.core.geodata.GeoPoint;
import java.util.Objects;

public record GeoDataDisplay(
        Mode mode,
        float opacity,
        float lineWidthPixels,
        boolean localGrid,
        int localGridRadius,
        boolean heightFilter,
        int minHeight,
        int maxHeight,
        boolean dimOtherHeights,
        GeoPoint focus,
        GeoSurfaceFilter surface) {
    public GeoDataDisplay(
            Mode mode,
            float opacity,
            float lineWidthPixels,
            boolean localGrid,
            int localGridRadius,
            boolean heightFilter,
            int minHeight,
            int maxHeight,
            boolean dimOtherHeights,
            GeoPoint focus) {
        this(
                mode,
                opacity,
                lineWidthPixels,
                localGrid,
                localGridRadius,
                heightFilter,
                minHeight,
                maxHeight,
                dimOtherHeights,
                focus,
                null);
    }

    public GeoDataDisplay {
        Objects.requireNonNull(mode);
        if (!Float.isFinite(opacity)
                || opacity < 0
                || opacity > 1
                || !Float.isFinite(lineWidthPixels)
                || lineWidthPixels < 0.5f
                || lineWidthPixels > 8
                || localGridRadius < 16
                || localGridRadius > 1024
                || minHeight < -32768
                || maxHeight > 32767
                || minHeight > maxHeight) {
            throw new IllegalArgumentException("Invalid geodata display range, opacity, width or radius");
        }
    }

    public enum Mode {
        EXACT_GRID,
        CONTOURS,
        SURFACE
    }

    public GeoDataDisplay withFocus(GeoPoint point) {
        return new GeoDataDisplay(
                mode,
                opacity,
                lineWidthPixels,
                localGrid,
                localGridRadius,
                heightFilter,
                minHeight,
                maxHeight,
                dimOtherHeights,
                point,
                surface);
    }

    public GeoDataDisplay withSurface(GeoSurfaceFilter filter) {
        return new GeoDataDisplay(
                mode,
                opacity,
                lineWidthPixels,
                localGrid,
                localGridRadius,
                heightFilter,
                minHeight,
                maxHeight,
                dimOtherHeights,
                focus,
                filter);
    }

    public GeoDataDisplay withHeightFilter(boolean enabled, int minimum, int maximum, boolean dim) {
        return new GeoDataDisplay(
                mode,
                opacity,
                lineWidthPixels,
                localGrid,
                localGridRadius,
                enabled,
                minimum,
                maximum,
                dim,
                focus,
                surface);
    }

    public GeoDataDisplay geometryOptions() {
        return new GeoDataDisplay(
                Mode.EXACT_GRID, 1, 1, false, 16, heightFilter, minHeight, maxHeight, dimOtherHeights, null, surface);
    }

    public GeoDataDisplay withMode(Mode next) {
        return new GeoDataDisplay(
                next,
                opacity,
                lineWidthPixels,
                localGrid,
                localGridRadius,
                heightFilter,
                minHeight,
                maxHeight,
                dimOtherHeights,
                focus,
                surface);
    }

    public GeoDataDisplay withStyle(float nextOpacity, float nextLineWidth) {
        return new GeoDataDisplay(
                mode,
                Math.clamp(nextOpacity, 0, 1),
                Math.clamp(nextLineWidth, .5f, 8),
                localGrid,
                localGridRadius,
                heightFilter,
                minHeight,
                maxHeight,
                dimOtherHeights,
                focus,
                surface);
    }

    public float layerAlpha(GeoDataAccess data, Cell cell, Layer layer) {
        return layerAlpha(layer.height()) * (surface == null ? 1 : surface.getAlpha(data, cell, layer));
    }

    public float layerAlpha(int height) {
        return !heightFilter || height >= minHeight && height <= maxHeight ? 1 : dimOtherHeights ? 0.12f : 0;
    }
}
