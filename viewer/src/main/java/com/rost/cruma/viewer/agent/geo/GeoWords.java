package com.rost.cruma.viewer.agent.geo;

import com.rost.cruma.core.geodata.GeoDataAccess.Cell;
import com.rost.cruma.core.geodata.GeoPoint;
import com.rost.cruma.core.geodata.GeoQueryEngine;
import com.rost.cruma.core.geodata.GeoQueryEngine.Direction;
import com.rost.cruma.core.geodata.GeoQueryEngine.Failure;
import com.rost.cruma.core.geodata.GeoQueryEngine.MoveResult;
import com.rost.cruma.core.geodata.GeoQueryEngine.SightResult;
import com.rost.cruma.core.geodata.GeoQueryEngine.Transition;
import com.rost.cruma.core.geodata.GeoTile;
import com.rost.cruma.core.geodata.ServerPathFinder.Status;
import com.rost.cruma.viewer.agent.ToolProblem;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jetbrains.annotations.Nullable;

final class GeoWords {
    private static final int ALL_WAYS = 15;
    private static final List<Direction> CLOCKWISE =
            List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST);

    private GeoWords() {}

    static String ways(int nswe) {
        if (nswe == ALL_WAYS) {
            return "open all ways";
        }
        if (nswe == 0) {
            return "blocked all ways";
        }
        var blocked = new ArrayList<String>();
        for (var direction : CLOCKWISE) {
            if ((nswe & direction.getMask()) == 0) {
                blocked.add(name(direction));
            }
        }
        return "blocked " + String.join(", ", blocked);
    }

    static String name(Direction direction) {
        return direction.name().toLowerCase(Locale.ROOT);
    }

    static Direction direction(String word) throws ToolProblem {
        for (var direction : Direction.values()) {
            if (name(direction).equals(word.strip().toLowerCase(Locale.ROOT))) {
                return direction;
            }
        }
        throw new ToolProblem("\"" + word + "\" is no direction. Use north, east, south or west.");
    }

    static int mask(List<String> words) throws ToolProblem {
        var mask = 0;
        for (var word : words) {
            switch (word.strip().toLowerCase(Locale.ROOT)) {
                case "all" -> mask = ALL_WAYS;
                case "none" -> {}
                default -> mask |= direction(word).getMask();
            }
        }
        return mask;
    }

    static String point(GeoPoint point) {
        return Math.round(point.x()) + "," + Math.round(point.y()) + "," + Math.round(point.z());
    }

    static String region(double x, double y) {
        return region(tile(x, y));
    }

    static String region(GeoTile tile) {
        return tile.x() + "_" + tile.y();
    }

    static GeoTile tile(double x, double y) {
        return GeoTile.fromWorld(GeoQueryEngine.cellMinimum(x), GeoQueryEngine.cellMinimum(y));
    }

    static String noData(Cell cell) {
        var region = region(cell.x(), cell.y());
        return switch (cell.availability()) {
            case AVAILABLE -> "The cell has data.";
            case MISSING -> "The folder has no file for region " + region + ".";
            case CORRUPT -> "The file of region " + region + " cannot be read: " + cell.detail();
            case OUTSIDE_WORLD -> "This point is outside the server world.";
            case NOT_LOADED -> "Region " + region + " is not loaded. Ask for a smaller area.";
        };
    }

    static String why(MoveResult move, GeoPoint destination) {
        if (move.failure() == Failure.HEIGHT_MISMATCH) {
            return "The move ends on a floor at height "
                    + Math.round(move.finalPosition().z())
                    + ", and the end point is at "
                    + Math.round(destination.z())
                    + ". That is another floor.";
        }
        return why(move.failure(), lastRefused(move.trace()), move.finalPosition());
    }

    static String why(SightResult sight, GeoPoint at) {
        if (sight.failure() == Failure.NONE) {
            return "A floor or a wall of the geodata cuts the line near " + point(at) + ".";
        }
        return why(sight.failure(), lastRefused(sight.trace()), at);
    }

    static String exit(Transition step) {
        if (!step.accepted()) {
            return switch (step.failure()) {
                case DIRECTION_BLOCKED -> "blocked";
                case NO_LAYER -> "no floor";
                case DATA_UNAVAILABLE -> "no data";
                default -> step.failure().name().toLowerCase(Locale.ROOT);
            };
        }
        var rise = step.to().layer().height() - step.from().layer().height();
        if (rise == 0) {
            return "same height";
        }
        return (rise > 0 ? "up " : "down ")
                + Math.abs(rise)
                + " to "
                + step.to().layer().height();
    }

    static String why(Status status, int visits, String detail) {
        return switch (status) {
            case CAN_GO, PATH_FOUND -> "A way exists.";
            case NO_START_ANCHOR ->
                "No pathnode can be reached from the start, so the server has no way into its graph there.";
            case NO_DESTINATION_ANCHOR ->
                "No pathnode can reach the end, so the server has no way out of its graph there.";
            case NO_PATH -> "The graph has no way between the pathnodes near the two points.";
            case VISIT_LIMIT, VISIT_POOL_LIMIT ->
                "The server gives up here after " + visits
                        + " visits. A longer way may exist. Try visits 1000, as for a chase.";
            case QUERY_LIMIT -> "The search ran out of its budget.";
            case DATA_UNAVAILABLE -> "A region on the way is missing or not loaded.";
            case GRAPH_MISSING -> "The folder has no pathnode graph.";
            case INVALID_GRAPH -> "The pathnode graph cannot be read: " + detail;
            case STALE_GRAPH -> "The pathnode graph is older than the edits.";
            case CANCELLED -> "The check was stopped.";
            case SOURCE_CHANGED -> "The geodata changed during the check. Check again.";
        };
    }

    private static @Nullable Transition lastRefused(List<Transition> trace) {
        for (var index = trace.size() - 1; index >= 0; index--) {
            if (!trace.get(index).accepted()) {
                return trace.get(index);
            }
        }
        return null;
    }

    private static String why(Failure failure, @Nullable Transition step, GeoPoint at) {
        var cell = step == null ? "" : "the cell at " + place(step);
        var way = step == null ? "" : name(step.direction());
        return switch (failure) {
            case NONE -> "Nothing stops it.";
            case DIRECTION_BLOCKED ->
                step == null
                        ? "A closed side stops the move at " + point(at) + "."
                        : "The " + way + " side of " + cell + " is closed.";
            case NO_LAYER ->
                step == null
                        ? "There is no floor at " + point(at) + "."
                        : "There is no floor " + way + " of " + cell + ".";
            case DATA_UNAVAILABLE -> "There is no geodata past " + point(at) + ". Its region is missing or not loaded.";
            case HEIGHT_MISMATCH -> "The two points are on different floors.";
            case UNEXPECTED_STEP -> "The move jumped over a cell near " + point(at) + ", which the server does not do.";
            case STEP_LIMIT -> "The check ran out of steps at " + point(at) + ".";
            case CANCELLED -> "The check was stopped.";
            case SOURCE_CHANGED -> "The geodata changed during the check. Check again.";
            case VERTICAL_COLLISION -> "A floor above or below cuts the line near " + point(at) + ".";
        };
    }

    private static String place(Transition step) {
        var from = step.from();
        return from.cell().x()
                + ","
                + from.cell().y()
                + " (height "
                + from.layer().height()
                + ")";
    }
}
