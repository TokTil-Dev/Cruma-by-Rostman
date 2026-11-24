package com.rost.cruma.viewer.geodata;

import com.rost.cruma.core.geodata.GeoDataAccess;
import com.rost.cruma.core.geodata.GeoDataAccess.Layer;
import com.rost.cruma.core.geodata.GeoPoint;

public final class GeoDataViewFocus {
    private GeoDataViewFocus() {}

    public record Focus(GeoPoint point, boolean onLayer) {}

    public static Focus resolve(
            GeoDataAccess data, GeoPoint origin, GeoPoint direction, int radius, GeoDataDisplay display) {
        var length = Math.sqrt(direction.distanceSquared(new GeoPoint(0, 0, 0)));
        if (!Double.isFinite(length) || length < 1e-8) {
            return new Focus(origin, false);
        }
        var dx = direction.x() / length;
        var dy = direction.y() / length;
        var dz = direction.z() / length;
        var distanceLimit = Math.min(300_000, 32768 / Math.max(.001, Math.hypot(dx, dy)));
        var filter = display.heightFilter() && !display.dimOtherHeights();
        var hit = GeoDataPicker.pickBounded(
                data,
                origin,
                direction,
                distanceLimit,
                filter ? display.minHeight() : Short.MIN_VALUE,
                filter ? display.maxHeight() : Short.MAX_VALUE);
        if (!hit.truncated()) {
            Focus faint = null;
            for (var candidate : hit.hits()) {
                var cell = data.cellAt(candidate.cell().x(), candidate.cell().y());
                var layer = cell.layers().get(candidate.cell().layer());
                var alpha = display.layerAlpha(data, cell, layer);
                if (alpha >= 1) {
                    return new Focus(candidate.position(), true);
                }
                if (alpha > 0 && faint == null) {
                    faint = new Focus(candidate.position(), true);
                }
            }
            if (faint != null) {
                return faint;
            }
        }
        var distance = Math.min(distanceLimit, Math.max(512, radius * 2));
        if (data != null && dz < -.01) {
            var height = data.cellAt((int) Math.floor(origin.x()), (int) Math.floor(origin.y())).layers().stream()
                    .filter(layer -> layer.height() < origin.z())
                    .mapToInt(Layer::height)
                    .max();
            if (height.isPresent()) {
                distance = Math.min(distanceLimit, (height.getAsInt() - origin.z()) / dz);
            }
        }
        return new Focus(
                new GeoPoint(origin.x() + dx * distance, origin.y() + dy * distance, origin.z() + dz * distance),
                false);
    }
}
