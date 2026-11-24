package com.rost.cruma.viewer.agent.geo;

import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.core.geodata.GeoCellRef;
import com.rost.cruma.core.geodata.GeoDataAccess;
import com.rost.cruma.core.geodata.GeoDataAccess.Availability;
import com.rost.cruma.core.geodata.GeoDataDiff.Bounds;
import com.rost.cruma.core.geodata.GeoDataRegion;
import com.rost.cruma.core.geodata.GeoDataSnapshot;
import com.rost.cruma.core.geodata.GeoPoint;
import com.rost.cruma.core.geodata.GeoQueryEngine.Direction;
import com.rost.cruma.core.geodata.GeoQueryEngine.Selection;
import com.rost.cruma.core.geodata.GeoTile;
import com.rost.cruma.viewer.agent.ToolArgs;
import com.rost.cruma.viewer.agent.ToolProblem;
import com.rost.cruma.viewer.geodata.GeoEditingSession;
import com.rost.cruma.viewer.geodata.GeoEditingSession.Clip;
import com.rost.cruma.viewer.geodata.GeoEditingSession.EdgeRef;
import com.rost.cruma.viewer.geodata.GeoEditingSession.EditResult;
import com.rost.cruma.viewer.geodata.GeoEditingSession.HeightRule;
import com.rost.cruma.viewer.geodata.GeoEditingSession.HeightRule.Flat;
import com.rost.cruma.viewer.geodata.GeoEditingSession.HeightRule.Offset;
import com.rost.cruma.viewer.geodata.GeoEditingSession.HeightRule.Slope;
import com.rost.cruma.viewer.geodata.GeoEditingSession.HeightRule.Smooth;
import com.rost.cruma.viewer.geodata.GeoEditingSession.LayerEdit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import org.jetbrains.annotations.Nullable;

final class GeoEdits {
    static final String PREFIX = "AI: ";

    private static final int CELL = GeoDataRegion.CELL_SIZE;
    private static final int DEFAULT_TOLERANCE = 32;
    private static final int REBUILD_TOLERANCE = 64;

    private GeoEdits() {}

    enum Operation {
        BLOCK("Block"),
        OPEN("Open"),
        SET("Set"),
        EDGE("Edge"),
        HEIGHT("Height"),
        REBUILD("Rebuild"),
        ADD_LAYER("Add layer"),
        FLOOR("Floor"),
        REMOVE_LAYER("Remove layer"),
        COPY("Copy"),
        PASTE("Paste"),
        REVERT("Revert"),
        FIX("Fix"),
        TAKE("Take");

        private final String title;

        Operation(String title) {
            this.title = title;
        }

        static List<String> words() {
            var words = new ArrayList<String>();
            for (var operation : values()) {
                words.add(operation.getWord());
            }
            return words;
        }

        static Operation of(String word) throws ToolProblem {
            for (var operation : values()) {
                if (operation.getWord().equals(word.strip().toLowerCase(Locale.ROOT))) {
                    return operation;
                }
            }
            throw new ToolProblem("There is no action \"" + word + "\". Use one of " + words() + ".");
        }

        String getWord() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    record Order(
            Operation operation,
            GeoSpot at,
            @Nullable GeoSpot to,
            int radius,
            boolean wholeRegion,
            boolean slope,
            int tolerance,
            String label,
            ToolArgs args) {
        @Nullable
        Bounds rectangle() {
            if (to == null || slope) {
                return null;
            }
            return between(at.x(), at.y(), to.x(), to.y());
        }

        Bounds needs() {
            var reach = CELL + radius;
            var far = to == null ? at : to;
            return new Bounds(
                    (int) Math.floor(Math.min(at.x(), far.x())) - reach,
                    (int) Math.floor(Math.min(at.y(), far.y())) - reach,
                    (int) Math.ceil(Math.max(at.x(), far.x())) + reach + 1,
                    (int) Math.ceil(Math.max(at.y(), far.y())) + reach + 1);
        }

        GeoTile tile() {
            return GeoWords.tile(at.x(), at.y());
        }
    }

    static Order order(ToolArgs args, GeoSpot at, @Nullable GeoSpot to, boolean wholeRegion) throws ToolProblem {
        var operation = Operation.of(args.getRequired("action"));
        var radius = args.getInteger("radius", 0);
        if (radius < 0 || radius > 4096) {
            throw new ToolProblem("radius must be from 1 to 4096 units.");
        }
        var tolerance =
                args.getInteger("tolerance", operation == Operation.REBUILD ? REBUILD_TOLERANCE : DEFAULT_TOLERANCE);
        var slope = operation == Operation.HEIGHT && "slope".equals(args.getText("rule"));
        if (radius > 0 && to != null && !slope) {
            throw new ToolProblem("Give radius for a round area or to for a rectangle, not both.");
        }
        var label = args.getText("label");
        var words = label != null ? label : operation.title + " at " + Math.round(at.x()) + "," + Math.round(at.y());
        return new Order(
                operation,
                at,
                to,
                radius,
                wholeRegion && radius == 0 && to == null,
                slope,
                tolerance,
                PREFIX + words,
                args);
    }

    static Bounds between(double x1, double y1, double x2, double y2) {
        var minX = Math.floorDiv((int) Math.floor(Math.min(x1, x2)), CELL) * CELL;
        var minY = Math.floorDiv((int) Math.floor(Math.min(y1, y2)), CELL) * CELL;
        var maxX = Math.floorDiv((int) Math.floor(Math.max(x1, x2)), CELL) * CELL + CELL;
        var maxY = Math.floorDiv((int) Math.floor(Math.max(y1, y2)), CELL) * CELL + CELL;
        return new Bounds(minX, minY, maxX, maxY);
    }

    static JSONObject apply(
            GeoEditingSession session,
            GeoDataAccess data,
            Order order,
            @Nullable Clip clip,
            @Nullable GeoDataSnapshot built)
            throws ToolProblem {
        var answer = new JSONObject();
        answer.put("action", order.operation().getWord());
        var revision = session.getDocument().revision();
        try {
            var result = switch (order.operation()) {
                case BLOCK, OPEN -> passability(session, data, order);
                case SET -> set(session, data, order);
                case EDGE -> edges(session, data, order);
                case HEIGHT -> heights(session, data, order);
                case REBUILD -> rebuild(session, data, order, built);
                case ADD_LAYER -> addLayer(session, data, order);
                case FLOOR -> addFloor(session, data, order);
                case REMOVE_LAYER -> removeLayer(session, data, order);
                case PASTE -> paste(session, data, order, clip);
                case REVERT -> revert(session, order);
                case TAKE -> take(session, order, built);
                case COPY -> throw new ToolProblem("Copy changes nothing, and is not an edit.");
                case FIX -> throw new ToolProblem("A fix scans the area first, and is not one edit of cells.");
            };
            var changed = session.getDocument().revision() != revision;
            answer.put(
                    order.operation() == Operation.EDGE ? "edges_set" : "changed_cells",
                    changed ? result.changedCells() : 0);
            answer.put("step", changed ? order.label() : "none");
            if (!changed) {
                answer.put("note", "The cells are like this already, so there is no new Undo step.");
            }
            if (order.operation() == Operation.FLOOR) {
                answer.put("new_floors", changed ? result.visitedCells() - result.skippedCells() : 0);
                answer.put("cells_with_floor", result.skippedCells());
                if (result.skippedCells() == result.visitedCells()) {
                    answer.put(
                            "note",
                            "Every cell of the area has a floor within the tolerance of " + order.tolerance()
                                    + " of that height already, so nothing was added.");
                }
            } else if (result.skippedCells() > 0) {
                answer.put("skipped_cells", result.skippedCells());
                answer.put(
                        "skipped_why",
                        "These cells have no floor within the tolerance of " + order.tolerance()
                                + ", or two floors are as near.");
            }
        } catch (IllegalArgumentException | IndexOutOfBoundsException refused) {
            throw new ToolProblem("The edit was refused, and nothing changed: " + refused.getMessage());
        }
        answer.put("undo_steps", session.getDocument().getUndoLabels().size());
        return answer;
    }

    static Clip copy(GeoDataAccess data, Order order) throws ToolProblem {
        var floor = floor(data, order.at());
        var rectangle = order.rectangle() != null ? order.rectangle() : cellOf(floor);
        try {
            return GeoEditingSession.copy(data, rectangle, floor.layer().height());
        } catch (IllegalArgumentException refused) {
            throw new ToolProblem("The copy was refused: " + refused.getMessage());
        }
    }

    private static Selection floor(GeoDataAccess data, GeoSpot spot) throws ToolProblem {
        var cell = spot.cell(data);
        if (cell.availability() != Availability.AVAILABLE) {
            throw new ToolProblem(GeoWords.noData(cell));
        }
        var floor = spot.floor(data);
        if (floor == null) {
            throw new ToolProblem("The cell at " + cell.x() + "," + cell.y() + " has no floor. Use add_layer first.");
        }
        return floor;
    }

    private static GeoCellRef seed(GeoEditingSession session, Selection floor) {
        return session.select(floor.cell().x(), floor.cell().y(), floor.layer().index());
    }

    private static Bounds cellOf(Selection floor) {
        return new Bounds(
                floor.cell().x(),
                floor.cell().y(),
                floor.cell().x() + CELL,
                floor.cell().y() + CELL);
    }

    private static GeoPoint center(GeoSpot spot, Selection floor) {
        return new GeoPoint(spot.x(), spot.y(), floor.layer().height());
    }

    private static <T> T named(GeoEditingSession session, Order order, Supplier<T> edit) {
        return session.runNamed(order.label(), edit);
    }

    private static EditResult passability(GeoEditingSession session, GeoDataAccess data, Order order)
            throws ToolProblem {
        var floor = floor(data, order.at());
        var seed = seed(session, floor);
        var open = order.operation() == Operation.OPEN;
        var tolerance = order.tolerance();
        if (order.radius() > 0) {
            var centers = List.of(center(order.at(), floor));
            return named(
                    session, order, () -> session.paintPassability(seed, centers, order.radius(), tolerance, open));
        }
        var rectangle = order.rectangle() != null ? order.rectangle() : cellOf(floor);
        return named(session, order, () -> session.paintPassabilityRectangle(seed, rectangle, tolerance, open));
    }

    private static EditResult set(GeoEditingSession session, GeoDataAccess data, Order order) throws ToolProblem {
        var floor = floor(data, order.at());
        var seed = seed(session, floor);
        var height = order.args().getNumber("height");
        var ways = order.args().list("ways");
        if (height == null && ways.isEmpty()) {
            throw new ToolProblem("set needs height, ways, or both.");
        }
        var nswe = ways.isEmpty() ? null : Integer.valueOf(GeoWords.mask(ways));
        var edit = new LayerEdit(height == null ? null : Integer.valueOf((int) Math.round(height)), nswe);
        if (order.radius() > 0) {
            var centers = List.of(center(order.at(), floor));
            return named(
                    session, order, () -> session.paintBrush(seed, centers, order.radius(), order.tolerance(), edit));
        }
        var rectangle = order.rectangle() != null ? order.rectangle() : cellOf(floor);
        return named(session, order, () -> session.paintRectangle(seed, rectangle, order.tolerance(), edit));
    }

    private static EditResult edges(GeoEditingSession session, GeoDataAccess data, Order order) throws ToolProblem {
        var floor = floor(data, order.at());
        var open = order.args().readFlag("open", false);
        var sides = order.args().getText("sides", "both");
        var words = order.args().list("directions");
        if (words.isEmpty()) {
            throw new ToolProblem("edge needs directions, such as north, east.");
        }
        var directions = new ArrayList<Direction>();
        for (var word : words) {
            directions.add(GeoWords.direction(word));
        }
        if (sides.equals("opposite")) {
            if (directions.size() != 1 || order.to() != null) {
                throw new ToolProblem("sides opposite works on one direction of one cell.");
            }
            var seed = seed(session, floor);
            var direction = directions.getFirst();
            return named(session, order, () -> {
                session.editOppositeEdge(seed, direction, open);
                return new EditResult(1, 1, 0, session.getDocument().revision());
            });
        }
        var cells = order.rectangle() != null ? order.rectangle() : cellOf(floor);
        var edges = new ArrayList<EdgeRef>();
        var skipped = 0;
        var visited = 0;
        for (var x = cells.minX(); x < cells.maxX(); x += CELL) {
            for (var y = cells.minY(); y < cells.maxY(); y += CELL) {
                visited++;
                var index = nearest(data, x, y, floor.layer().height(), order.tolerance());
                if (index < 0) {
                    skipped++;
                    continue;
                }
                var ref = session.select(x, y, index);
                for (var direction : directions) {
                    edges.add(new EdgeRef(ref, direction));
                }
            }
        }
        var both = !sides.equals("this");
        var result = named(session, order, () -> session.setEdges(edges, open, both));
        return new EditResult(visited, result, skipped, session.getDocument().revision());
    }

    private static int nearest(GeoDataAccess data, int x, int y, int height, int tolerance) {
        var index = -1;
        var distance = Integer.MAX_VALUE;
        for (var layer : data.cellAt(x, y).layers()) {
            var candidate = Math.abs(layer.height() - height);
            if (candidate < distance && candidate <= tolerance) {
                index = layer.index();
                distance = candidate;
            }
        }
        return index;
    }

    private static EditResult heights(GeoEditingSession session, GeoDataAccess data, Order order) throws ToolProblem {
        var floor = floor(data, order.at());
        var seed = seed(session, floor);
        var rule = rule(order, floor, data);
        if (rule instanceof Slope slope) {
            var ends = List.of(slope.from(), slope.to());
            var width = Math.max(order.radius(), CELL);
            return named(session, order, () -> session.paintHeights(seed, ends, width, order.tolerance(), rule));
        }
        if (order.radius() > 0) {
            var centers = List.of(center(order.at(), floor));
            return named(
                    session, order, () -> session.paintHeights(seed, centers, order.radius(), order.tolerance(), rule));
        }
        var rectangle = order.rectangle() != null ? order.rectangle() : cellOf(floor);
        return named(session, order, () -> session.paintHeightsRectangle(seed, rectangle, order.tolerance(), rule));
    }

    private static HeightRule rule(Order order, Selection floor, GeoDataAccess data) throws ToolProblem {
        var args = order.args();
        return switch (args.getText("rule", "flat")) {
            case "flat" -> {
                var height = args.getNumber("height");
                if (height == null) {
                    throw new ToolProblem("rule flat needs height.");
                }
                yield new Flat((int) Math.round(height));
            }
            case "raise" -> {
                var by = args.getNumber("by");
                if (by == null) {
                    throw new ToolProblem("rule raise needs by, the units up, or below 0 for down.");
                }
                yield new Offset((int) Math.round(by));
            }
            case "smooth" -> new Smooth();
            case "slope" -> {
                if (order.to() == null) {
                    throw new ToolProblem("rule slope needs to, the end of the slope with its height.");
                }
                yield new Slope(center(order.at(), floor), order.to().point(data), args.getInteger("step", 0));
            }
            default -> throw new ToolProblem("rule must be flat, raise, smooth or slope.");
        };
    }

    private static EditResult rebuild(
            GeoEditingSession session, GeoDataAccess data, Order order, @Nullable GeoDataSnapshot built)
            throws ToolProblem {
        if (built == null) {
            throw new ToolProblem("The region was not built from the client.");
        }
        var tile = order.tile();
        if (order.wholeRegion()) {
            var region = built.getRegion(tile.x(), tile.y());
            if (region == null) {
                throw new ToolProblem("The client built no data for " + GeoWords.region(tile) + ".");
            }
            var changed = named(session, order, () -> session.replaceRegion(tile, region, order.label()));
            return new EditResult(changed, changed, 0, session.getDocument().revision());
        }
        var floor = floor(data, order.at());
        var seed = seed(session, floor);
        if (order.radius() > 0) {
            var centers = List.of(center(order.at(), floor));
            return named(
                    session,
                    order,
                    () -> session.paintRebuild(seed, centers, order.radius(), order.tolerance(), built));
        }
        var rectangle = order.rectangle() != null ? order.rectangle() : cellOf(floor);
        return named(session, order, () -> session.paintRebuildRectangle(seed, rectangle, order.tolerance(), built));
    }

    private static EditResult take(GeoEditingSession session, Order order, @Nullable GeoDataSnapshot folderB)
            throws ToolProblem {
        var tile = order.tile();
        var region = folderB == null ? null : folderB.getRegion(tile.x(), tile.y());
        if (region == null) {
            var why = folderB == null ? "" : ": " + folderB.getState(tile).detail();
            throw new ToolProblem("Folder B has no readable file for region " + GeoWords.region(tile) + why + ".");
        }
        var changed = named(session, order, () -> session.replaceRegion(tile, region, order.label()));
        return new EditResult(changed, changed, 0, session.getDocument().revision());
    }

    private static EditResult addLayer(GeoEditingSession session, GeoDataAccess data, Order order) throws ToolProblem {
        var height = order.args().getNumber("height");
        if (height == null) {
            throw new ToolProblem("add_layer needs height.");
        }
        var cell = order.at().cell(data);
        if (cell.availability() != Availability.AVAILABLE) {
            throw new ToolProblem(GeoWords.noData(cell));
        }
        var ways = order.args().list("ways");
        var nswe = ways.isEmpty() ? 15 : GeoWords.mask(ways);
        var level = (int) Math.round(height);
        var index = 0;
        while (index < cell.layers().size() && cell.layers().get(index).height() > level) {
            index++;
        }
        var ref = session.select(cell.x(), cell.y(), cell.layers().isEmpty() ? -1 : 0);
        var place = index;
        return named(session, order, () -> {
            session.addLayer(ref, place, level, nswe);
            return new EditResult(1, 1, 0, session.getDocument().revision());
        });
    }

    private static EditResult addFloor(GeoEditingSession session, GeoDataAccess data, Order order) throws ToolProblem {
        var floor = floor(data, order.at());
        var seed = seed(session, floor);
        var height = order.args().getNumber("height");
        var level = height == null ? floor.layer().height() : (int) Math.round(height);
        if (order.radius() > 0) {
            var centers = List.of(new GeoPoint(order.at().x(), order.at().y(), level));
            return named(
                    session, order, () -> session.paintFloor(seed, centers, order.radius(), level, order.tolerance()));
        }
        var rectangle = order.rectangle() != null ? order.rectangle() : cellOf(floor);
        return named(session, order, () -> session.paintFloorRectangle(seed, rectangle, level, order.tolerance()));
    }

    private static EditResult removeLayer(GeoEditingSession session, GeoDataAccess data, Order order)
            throws ToolProblem {
        var floor = floor(data, order.at());
        var seed = seed(session, floor);
        var rectangle = order.rectangle();
        if (rectangle == null) {
            return named(session, order, () -> {
                session.removeLayer(seed);
                return new EditResult(1, 1, 0, session.getDocument().revision());
            });
        }
        return named(session, order, () -> session.removeLayers(seed, rectangle, order.tolerance()));
    }

    private static EditResult paste(GeoEditingSession session, GeoDataAccess data, Order order, @Nullable Clip clip)
            throws ToolProblem {
        if (clip == null) {
            throw new ToolProblem("Nothing is copied. Call geo_edit with action copy first.");
        }
        var floor = floor(data, order.at());
        var changed = named(
                session,
                order,
                () -> session.paste(
                        clip, floor.cell().x(), floor.cell().y(), floor.layer().height()));
        return new EditResult(clip.count(), changed, 0, session.getDocument().revision());
    }

    private static EditResult revert(GeoEditingSession session, Order order) {
        var changed = named(session, order, () -> session.revertRegion(order.tile()));
        return new EditResult(changed, changed, 0, session.getDocument().revision());
    }
}
