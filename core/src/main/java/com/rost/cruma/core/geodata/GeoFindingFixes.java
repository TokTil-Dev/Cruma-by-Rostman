package com.rost.cruma.core.geodata;

import com.rost.cruma.core.geodata.GeoDataAccess.Availability;
import com.rost.cruma.core.geodata.GeoDataAccess.Cell;
import com.rost.cruma.core.geodata.GeoDataAccess.Layer;
import com.rost.cruma.core.geodata.GeoDataScanner.Finding;
import com.rost.cruma.core.geodata.GeoEditDocument.CellKey;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class GeoFindingFixes {
    public static final int SIMILAR_HEIGHT = 32;

    private GeoFindingFixes() {}

    public enum Fix {
        OPEN_BOTH_WAYS("Open both ways", "Opens the edge from this cell and the way back from the neighbor."),
        BLOCK_BOTH_WAYS(
                "Block both ways",
                "Closes the edge from this cell and the way back from the neighbor, as a wall does."),
        BLOCK_WAY_IN("Block the way in", "Closes the edge that leads in, and the way back, so nobody walks into it."),
        FILL(
                "Fill",
                "Raises the lower floor to the height of the upper one. When the lower cell has other floors, adds a"
                        + " floor at that height instead."),
        REMOVE_LAYER("Remove layer", "Deletes the layer. Nothing could reach it."),
        CLIENT_HEIGHT(
                "Set height from the client", "Moves the layer to the height of the walkable client ground there."),
        SORT_LAYERS("Sort layers", "Stores the layers of the cell highest first, as the server expects.");

        public final String label;
        public final String help;

        Fix(String label, String help) {
            this.label = label;
            this.help = help;
        }
    }

    public static List<Fix> findFixes(Finding finding) {
        var rise = finding.target() == null
                ? 0
                : finding.target().z() - finding.position().z();
        var similar = finding.target() != null && Math.abs(rise) <= SIMILAR_HEIGHT;
        var walkableTarget = finding.neighbor() != null && isWalkable(finding.neighbor(), finding.target());
        return switch (finding.rule()) {
            case ONE_WAY_TRANSITION, SHORT_DETOUR -> {
                var wall = finding.detail().contains("client wall stands");
                if (!similar || !walkableTarget) {
                    yield List.of(Fix.BLOCK_BOTH_WAYS);
                }
                yield wall
                        ? List.of(Fix.BLOCK_BOTH_WAYS, Fix.OPEN_BOTH_WAYS)
                        : List.of(Fix.OPEN_BOTH_WAYS, Fix.BLOCK_BOTH_WAYS);
            }
            case ONE_WAY_TRAP ->
                similar && walkableTarget ? List.of(Fix.OPEN_BOTH_WAYS, Fix.BLOCK_WAY_IN) : List.of(Fix.BLOCK_WAY_IN);
            case OBSTACLE_ENTRY -> List.of(Fix.BLOCK_WAY_IN);
            case STEEP_CLIMB -> List.of(Fix.BLOCK_BOTH_WAYS);
            case LONG_DROP -> List.of(Fix.FILL);
            case REGION_SEAM -> rise < 0 ? List.of(Fix.FILL) : List.of(Fix.BLOCK_BOTH_WAYS);
            case ISOLATED_LAYER, DUPLICATE_HEIGHT -> List.of(Fix.REMOVE_LAYER);
            case LAYER_ORDER -> List.of(Fix.SORT_LAYERS);
            case CLIENT_SURFACE_MISMATCH -> finding.target() == null ? List.of() : List.of(Fix.CLIENT_HEIGHT);
            default -> List.of();
        };
    }

    public static Map<CellKey, List<Layer>> computeEdits(GeoDataAccess data, Collection<Finding> findings, Fix fix) {
        var work = new Work(data);
        for (var finding : findings) {
            for (var part : finding.joined().isEmpty() ? List.of(finding) : finding.joined()) {
                apply(work, part, fix);
            }
        }
        return work.getChanges();
    }

    static int round8(double height) {
        return Math.clamp(Math.round(height / 8) * 8, -16384, 16376);
    }

    private static void apply(Work work, Finding finding, Fix fix) {
        var x = cellMinimum(finding.position().x());
        var y = cellMinimum(finding.position().y());
        var height = (int) Math.round(finding.position().z());
        switch (fix) {
            case OPEN_BOTH_WAYS, BLOCK_BOTH_WAYS, BLOCK_WAY_IN -> {
                if (finding.direction() == null || finding.target() == null) {
                    return;
                }
                var open = fix == Fix.OPEN_BOTH_WAYS;
                work.edge(x, y, height, finding.direction().getMask(), open);
                work.edge(
                        x + finding.direction().getDx(),
                        y + finding.direction().getDy(),
                        (int) Math.round(finding.target().z()),
                        finding.direction().opposite().getMask(),
                        open);
            }
            case FILL -> fill(work, finding);
            case REMOVE_LAYER -> work.remove(x, y, height);
            case CLIENT_HEIGHT -> {
                if (finding.target() != null) {
                    work.move(x, y, height, round8(finding.target().z()));
                }
            }
            case SORT_LAYERS -> work.sort(x, y);
        }
    }

    private static void fill(Work work, Finding finding) {
        if (finding.direction() == null || finding.target() == null) {
            return;
        }
        var fromX = cellMinimum(finding.position().x());
        var fromY = cellMinimum(finding.position().y());
        var upper = (int) Math.round(finding.position().z());
        var lower = (int) Math.round(finding.target().z());
        var x = fromX + finding.direction().getDx();
        var y = fromY + finding.direction().getDy();
        if (lower > upper) {
            x = fromX;
            y = fromY;
            var swap = upper;
            upper = lower;
            lower = swap;
        }
        var layers = work.getLayers(x, y);
        if (layers == null || indexOf(layers, lower) < 0) {
            return;
        }
        var target = round8(upper);
        if (layers.size() == 1) {
            work.move(x, y, lower, target);
            return;
        }
        for (var layer : layers) {
            if (Math.abs(layer.height() - target) < 16) {
                return;
            }
        }
        layers.add(new Layer(layers.size(), target, 15));
        work.sorted(x, y);
    }

    private static boolean isWalkable(Cell cell, GeoPoint at) {
        if (at == null) {
            return false;
        }
        for (var layer : cell.layers()) {
            if (layer.height() == (int) Math.round(at.z())) {
                return layer.nswe() != 0;
            }
        }
        return false;
    }

    private static int indexOf(List<Layer> layers, int height) {
        var best = -1;
        var distance = Integer.MAX_VALUE;
        for (var index = 0; index < layers.size(); index++) {
            var candidate = Math.abs(layers.get(index).height() - height);
            if (candidate < distance) {
                distance = candidate;
                best = index;
            }
        }
        return distance <= 8 ? best : -1;
    }

    private static int cellMinimum(double value) {
        return Math.floorDiv((int) Math.floor(value), 16) * 16;
    }

    private static final class Work {
        private final GeoDataAccess data;
        private final LinkedHashMap<CellKey, ArrayList<Layer>> cells = new LinkedHashMap<>();

        Work(GeoDataAccess data) {
            this.data = data;
        }

        ArrayList<Layer> getLayers(int x, int y) {
            var key = new CellKey(x, y);
            var layers = cells.get(key);
            if (layers != null) {
                return layers;
            }
            var cell = data.cellAt(x, y);
            if (cell.availability() != Availability.AVAILABLE) {
                return null;
            }
            layers = new ArrayList<>(cell.layers());
            cells.put(key, layers);
            return layers;
        }

        void edge(int x, int y, int height, int mask, boolean open) {
            var layers = getLayers(x, y);
            if (layers == null) {
                return;
            }
            var index = indexOf(layers, height);
            if (index < 0) {
                return;
            }
            var layer = layers.get(index);
            layers.set(index, new Layer(index, layer.height(), open ? layer.nswe() | mask : layer.nswe() & ~mask));
        }

        void remove(int x, int y, int height) {
            var layers = getLayers(x, y);
            if (layers == null) {
                return;
            }
            for (var index = layers.size() - 1; index >= 0; index--) {
                if (layers.get(index).height() == height) {
                    layers.remove(index);
                    return;
                }
            }
        }

        void move(int x, int y, int from, int to) {
            var layers = getLayers(x, y);
            if (layers == null) {
                return;
            }
            var index = indexOf(layers, from);
            if (index < 0) {
                return;
            }
            for (var other = 0; other < layers.size(); other++) {
                if (other != index && layers.get(other).height() == to) {
                    return;
                }
            }
            var layer = layers.get(index);
            layers.set(index, new Layer(index, to, layer.nswe()));
            sorted(x, y);
        }

        void sort(int x, int y) {
            if (getLayers(x, y) != null) {
                sorted(x, y);
            }
        }

        void sorted(int x, int y) {
            cells.get(new CellKey(x, y))
                    .sort(Comparator.comparingInt(Layer::height).reversed());
        }

        Map<CellKey, List<Layer>> getChanges() {
            var result = new LinkedHashMap<CellKey, List<Layer>>();
            for (var entry : cells.entrySet()) {
                var key = entry.getKey();
                var layers = new ArrayList<Layer>();
                for (var layer : entry.getValue()) {
                    layers.add(new Layer(layers.size(), layer.height(), layer.nswe()));
                }
                if (!layers.equals(data.cellAt(key.x(), key.y()).layers())) {
                    result.put(key, List.copyOf(layers));
                }
            }
            return result;
        }
    }
}
