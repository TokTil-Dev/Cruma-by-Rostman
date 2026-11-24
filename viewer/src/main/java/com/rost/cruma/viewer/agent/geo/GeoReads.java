package com.rost.cruma.viewer.agent.geo;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.core.geodata.GeoDataAccess;
import com.rost.cruma.core.geodata.GeoDataAccess.Availability;
import com.rost.cruma.core.geodata.GeoDataDiff.Bounds;
import com.rost.cruma.core.geodata.GeoDataRegion;
import com.rost.cruma.core.geodata.GeoQueryEngine;
import com.rost.cruma.core.geodata.GeoQueryEngine.Direction;
import com.rost.cruma.core.geodata.GeoQueryEngine.Selection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;

final class GeoReads {
    static final long MOST_CELLS = (long) GeoDataRegion.CELLS_PER_AXIS * GeoDataRegion.CELLS_PER_AXIS;

    private static final int CELL = GeoDataRegion.CELL_SIZE;
    private static final int ALL_WAYS = 15;

    private GeoReads() {}

    static JSONObject cell(GeoDataAccess data, GeoSpot spot) {
        var cell = spot.cell(data);
        var answer = new JSONObject();
        answer.put("cell", cell.x() + "," + cell.y());
        answer.put("region", GeoWords.region(spot.x(), spot.y()));
        if (cell.availability() != Availability.AVAILABLE) {
            answer.put("data", cell.availability().name().toLowerCase(Locale.ROOT));
            answer.put("why", GeoWords.noData(cell));
            return answer;
        }
        answer.put("storage", cell.storage().name().toLowerCase(Locale.ROOT));
        var chosen = spot.floor(data);
        var floors = new JSONArray();
        for (var layer : cell.layers()) {
            var floor = new JSONObject();
            floor.put("height", layer.height());
            floor.put("ways", GeoWords.ways(layer.nswe()));
            if (chosen != null && chosen.layer().index() == layer.index()) {
                floor.put("chosen", true);
            }
            floors.add(floor);
        }
        answer.put("floors", floors);
        if (chosen == null) {
            answer.put("why", "This cell has no floor.");
            return answer;
        }
        if (spot.z() == null && cell.layers().size() > 1) {
            answer.put("note", "No height was given, so the top floor is chosen. Give x,y,z to choose another.");
        }
        answer.put("leaves", exits(data, chosen));
        return answer;
    }

    static JSONObject summary(GeoDataAccess data, Bounds area, int limit, BooleanSupplier stopped) {
        var minX = Math.floorDiv(area.minX(), CELL) * CELL;
        var minY = Math.floorDiv(area.minY(), CELL) * CELL;
        var columns = Math.ceilDiv(area.maxX() - minX, CELL);
        var rows = Math.ceilDiv(area.maxY() - minY, CELL);
        var holes = new BitSet(columns * rows);
        var noData = new TreeSet<String>();
        var noDataCells = 0;
        var noFloor = 0;
        var severalFloors = 0;
        var mostFloors = 0;
        var floors = 0L;
        var open = 0L;
        var partly = 0L;
        var closed = 0L;
        var lowest = Integer.MAX_VALUE;
        var highest = Integer.MIN_VALUE;
        for (var column = 0; column < columns; column++) {
            if (stopped.getAsBoolean()) {
                break;
            }
            for (var row = 0; row < rows; row++) {
                var cell = data.cellAt(minX + column * CELL, minY + row * CELL);
                if (cell.availability() != Availability.AVAILABLE) {
                    noDataCells++;
                    noData.add(GeoWords.region(cell.x(), cell.y()));
                    continue;
                }
                var count = cell.layers().size();
                if (count == 0) {
                    noFloor++;
                }
                severalFloors += count > 1 ? 1 : 0;
                mostFloors = Math.max(mostFloors, count);
                floors += count;
                var walkable = false;
                for (var layer : cell.layers()) {
                    lowest = Math.min(lowest, layer.height());
                    highest = Math.max(highest, layer.height());
                    if (layer.nswe() == ALL_WAYS) {
                        open++;
                    } else if (layer.nswe() == 0) {
                        closed++;
                    } else {
                        partly++;
                    }
                    walkable |= layer.nswe() != 0;
                }
                if (!walkable) {
                    holes.set(column * rows + row);
                }
            }
        }
        var answer = new JSONObject();
        answer.put("from", minX + "," + minY);
        answer.put("to", (minX + columns * CELL) + "," + (minY + rows * CELL));
        answer.put("cells", (long) columns * rows);
        if (noDataCells > 0) {
            answer.put("cells_without_data", noDataCells);
            answer.put("regions_without_data", String.join(", ", noData));
        }
        answer.put("floors", floors);
        answer.put("cells_with_no_floor", noFloor);
        answer.put("cells_with_several_floors", severalFloors);
        answer.put("most_floors_in_a_cell", mostFloors);
        if (floors > 0) {
            answer.put("lowest", lowest);
            answer.put("highest", highest);
        }
        answer.put("floors_open_all_ways", open);
        answer.put("floors_blocked_some_ways", partly);
        answer.put("floors_blocked_all_ways", closed);
        var found = holes(holes, columns, rows, minX, minY);
        answer.put("holes", found.size());
        var shown = new JSONArray();
        for (var hole : found.subList(0, Math.min(limit, found.size()))) {
            var item = new JSONObject();
            item.put("at", hole.middleX() + "," + hole.middleY());
            item.put("cells", hole.cells());
            item.put("size", hole.width() + " x " + hole.height());
            shown.add(item);
        }
        answer.put("largest_holes", shown);
        if (found.size() > limit) {
            answer.put("holes_left_out", found.size() - limit);
        }
        return answer;
    }

    private record Hole(int cells, int minX, int minY, int maxX, int maxY) {
        int middleX() {
            return (minX + maxX) / 2;
        }

        int middleY() {
            return (minY + maxY) / 2;
        }

        int width() {
            return maxX - minX;
        }

        int height() {
            return maxY - minY;
        }
    }

    private static JSONObject exits(GeoDataAccess data, Selection floor) {
        var engine = new GeoQueryEngine(data);
        var exits = new JSONObject();
        for (var direction : List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST)) {
            exits.put(GeoWords.name(direction), GeoWords.exit(engine.computeTransition(floor, direction)));
        }
        return exits;
    }

    private static List<Hole> holes(BitSet holes, int columns, int rows, int minX, int minY) {
        var found = new ArrayList<Hole>();
        var seen = new BitSet(columns * rows);
        var stack = new int[Math.max(16, columns * 4)];
        for (var start = holes.nextSetBit(0); start >= 0; start = holes.nextSetBit(start + 1)) {
            if (seen.get(start)) {
                continue;
            }
            var size = 0;
            var cells = 0;
            var lowColumn = Integer.MAX_VALUE;
            var lowRow = Integer.MAX_VALUE;
            var highColumn = 0;
            var highRow = 0;
            seen.set(start);
            stack[size++] = start;
            while (size > 0) {
                var index = stack[--size];
                var column = index / rows;
                var row = index % rows;
                cells++;
                lowColumn = Math.min(lowColumn, column);
                lowRow = Math.min(lowRow, row);
                highColumn = Math.max(highColumn, column);
                highRow = Math.max(highRow, row);
                for (var side = 0; side < 4; side++) {
                    var nextColumn = column + (side == 0 ? 1 : side == 1 ? -1 : 0);
                    var nextRow = row + (side == 2 ? 1 : side == 3 ? -1 : 0);
                    if (nextColumn < 0 || nextRow < 0 || nextColumn >= columns || nextRow >= rows) {
                        continue;
                    }
                    var next = nextColumn * rows + nextRow;
                    if (holes.get(next) && !seen.get(next)) {
                        seen.set(next);
                        if (size == stack.length) {
                            stack = Arrays.copyOf(stack, size * 2);
                        }
                        stack[size++] = next;
                    }
                }
            }
            found.add(new Hole(
                    cells,
                    minX + lowColumn * CELL,
                    minY + lowRow * CELL,
                    minX + (highColumn + 1) * CELL,
                    minY + (highRow + 1) * CELL));
        }
        found.sort(Comparator.comparingInt(Hole::cells).reversed());
        return found;
    }
}
