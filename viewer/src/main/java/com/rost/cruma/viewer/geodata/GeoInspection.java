package com.rost.cruma.viewer.geodata;

import com.rost.cruma.core.geodata.GeoCellRef;
import com.rost.cruma.core.geodata.GeoDataAccess;
import com.rost.cruma.core.geodata.GeoDataAccess.Availability;
import com.rost.cruma.core.geodata.GeoDataAccess.Layer;
import com.rost.cruma.core.geodata.GeoPoint;
import com.rost.cruma.core.geodata.GeoQueryEngine;
import com.rost.cruma.viewer.geodata.GeoDataPicker.Hit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;

public final class GeoInspection {
    public Selection selected;
    public Selection pointA;
    public Selection pointB;
    public String message = "Click a cell in the viewport to inspect its source data.";
    public final List<Bookmark> bookmarks = new ArrayList<>();

    public record Selection(GeoCellRef ref, int height, int nswe) {
        public GeoPoint center() {
            return new GeoPoint(ref.x() + 8, ref.y() + 8, height);
        }
    }

    public record Bookmark(String name, GeoPoint position) {}

    public static GeoQueryEngine.Selection resolve(GeoDataAccess data, Selection selected) {
        if (data == null || selected == null || !selected.ref().belongsTo(data)) {
            return null;
        }
        var cell = data.cellAt(selected.ref().x(), selected.ref().y());
        if (cell.availability() != Availability.AVAILABLE) {
            return null;
        }
        for (var layer : cell.layers()) {
            if (layer.index() == selected.ref().layer()
                    && layer.height() == selected.height()
                    && layer.nswe() == selected.nswe()) {
                return new GeoQueryEngine.Selection(cell, layer);
            }
        }
        return null;
    }

    public void update(GeoDataAccess data) {
        var chosen = follow(data, selected);
        var a = follow(data, pointA);
        var b = follow(data, pointB);
        if (selected != null && chosen == null || pointA != null && a == null || pointB != null && b == null) {
            message = "Data revision changed or available layers changed. Select the cells again.";
        }
        selected = chosen;
        pointA = a;
        pointB = b;
    }

    public void select(GeoDataAccess data, Hit hit) {
        if (hit == null || !hit.cell().belongsTo(data)) {
            message = "No loaded geodata layer under the pointer.";
            return;
        }
        select(data, hit.cell().x(), hit.cell().y(), hit.cell().layer());
    }

    public void selectNextHit(GeoDataAccess data, List<Hit> hits) {
        if (hits.isEmpty()) {
            select(data, (Hit) null);
            return;
        }
        var next = 0;
        if (selected != null) {
            for (var index = 0; index < hits.size(); index++) {
                if (hits.get(index).cell().equals(selected.ref())) {
                    next = (index + 1) % hits.size();
                    break;
                }
            }
        }
        select(data, hits.get(next));
    }

    public void select(GeoDataAccess data, int x, int y, int layerIndex) {
        var cell = data.cellAt(x, y);
        for (var layer : cell.layers()) {
            if (layer.index() == layerIndex) {
                selected = new Selection(
                        new GeoCellRef(data.getSourceId(), data.revision(), cell.x(), cell.y(), layer.index()),
                        layer.height(),
                        layer.nswe());
                message = "";
                return;
            }
        }
        selected = null;
        message = cell.availability() + (cell.detail().isBlank() ? "" : ": " + cell.detail());
    }

    public void cycleLayer(GeoDataAccess data) {
        if (resolve(data, selected) == null) {
            update(data);
            return;
        }
        var ref = selected.ref();
        var layers = data.cellAt(ref.x(), ref.y()).layers();
        for (var index = 0; index < layers.size(); index++) {
            if (layers.get(index).index() == ref.layer()) {
                select(
                        data,
                        ref.x(),
                        ref.y(),
                        layers.get((index + 1) % layers.size()).index());
                break;
            }
        }
    }

    public boolean bookmark(String name) {
        if (selected == null || bookmarks.size() >= 128 || getBookmarked() >= 0) {
            return false;
        }
        bookmarks.add(new Bookmark(Objects.requireNonNull(name), selected.center()));
        return true;
    }

    public int getBookmarked() {
        if (selected == null) {
            return -1;
        }
        var position = selected.center();
        for (var index = 0; index < bookmarks.size(); index++) {
            if (bookmarks.get(index).position().equals(position)) {
                return index;
            }
        }
        return -1;
    }

    private static @Nullable Selection follow(@Nullable GeoDataAccess data, @Nullable Selection selection) {
        if (selection == null || data == null || !selection.ref().sourceId().equals(data.getSourceId())) {
            return null;
        }
        if (resolve(data, selection) != null) {
            return selection;
        }
        var cell = data.cellAt(selection.ref().x(), selection.ref().y());
        if (selection.ref().revision() == data.revision() || cell.availability() != Availability.AVAILABLE) {
            return null;
        }
        Layer nearest = null;
        for (var layer : cell.layers()) {
            if (nearest == null
                    || Math.abs(layer.height() - selection.height())
                            < Math.abs(nearest.height() - selection.height())) {
                nearest = layer;
            }
        }
        return nearest == null
                ? null
                : new Selection(
                        new GeoCellRef(data.getSourceId(), data.revision(), cell.x(), cell.y(), nearest.index()),
                        nearest.height(),
                        nearest.nswe());
    }
}
