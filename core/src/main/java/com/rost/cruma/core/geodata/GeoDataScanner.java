package com.rost.cruma.core.geodata;

import com.rost.cruma.core.geodata.GeoDataAccess.Availability;
import com.rost.cruma.core.geodata.GeoDataAccess.Cell;
import com.rost.cruma.core.geodata.GeoDataAccess.Layer;
import com.rost.cruma.core.geodata.GeoDataDiff.Bounds;
import com.rost.cruma.core.geodata.GeoQueryEngine.Completeness;
import com.rost.cruma.core.geodata.GeoQueryEngine.Direction;
import com.rost.cruma.core.geodata.GeoQueryEngine.Selection;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;

public final class GeoDataScanner {
    public static final int STEP_LIMIT = 26;

    public static final int WALL_CLIMB = 64;

    public static final int RETURN_RADIUS = 128;

    private static final int CLIENT_REFERENCE_RANGE = 256;

    private final GeoDataAccess data;
    private final SurfaceProbe surfaces;

    public GeoDataScanner(GeoDataAccess data) {
        this(data, null);
    }

    public GeoDataScanner(GeoDataAccess data, SurfaceProbe surfaces) {
        this.data = Objects.requireNonNull(data, "data");
        this.surfaces = surfaces;
    }

    public enum Confidence {
        ERROR,
        HEURISTIC,
        INFORMATION
    }

    public enum Rule {
        CORRUPT_REGION,
        UNAVAILABLE_REGION,
        EMPTY_CELL,
        INVALID_LAYER,
        DUPLICATE_HEIGHT,
        LAYER_ORDER,
        STEEP_CLIMB,
        LONG_DROP,
        REGION_SEAM,
        ONE_WAY_TRANSITION,
        ONE_WAY_TRAP,
        OBSTACLE_ENTRY,
        SHORT_DETOUR,
        OTHER_FLOOR_RETURN,
        ISOLATED_LAYER,
        CLIENT_SURFACE_MISMATCH,
        CLIENT_GEOMETRY_INCOMPLETE
    }

    public enum SurfaceKind {
        WALKABLE,
        DECORATIVE,
        UNKNOWN
    }

    public record ClientSurface(double height, SurfaceKind kind, String version) {
        public ClientSurface {
            if (!Double.isFinite(height)) {
                throw new IllegalArgumentException("Invalid client surface height");
            }
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(version, "version");
        }
    }

    @FunctionalInterface
    public interface SurfaceProbe {
        List<ClientSurface> getSurfacesAt(int worldX, int worldY);

        default ClientSample sampleAt(int worldX, int worldY) {
            return new ClientSample(getSurfacesAt(worldX, worldY), Completeness.COMPLETE, "");
        }

        default boolean isValid() {
            return true;
        }

        default int getTriangleTestLimit() {
            return 2_000_000;
        }

        default Passage checkPassage(GeoPoint from, GeoPoint to) {
            return Passage.UNKNOWN;
        }
    }

    public enum Passage {
        OPEN,
        BLOCKED,
        UNKNOWN
    }

    public record ClientSample(List<ClientSurface> surfaces, Completeness completeness, String detail) {
        public ClientSample {
            surfaces = List.copyOf(surfaces);
            Objects.requireNonNull(completeness, "completeness");
            Objects.requireNonNull(detail, "detail");
        }
    }

    public record Limits(
            int maxCells,
            int maxFindings,
            int maxTransitions,
            int abruptHeight,
            double surfaceTolerance,
            BooleanSupplier cancelled,
            IntConsumer progress) {
        public Limits {
            if (maxCells < 1
                    || maxFindings < 0
                    || maxTransitions < 1
                    || abruptHeight < 8
                    || !Double.isFinite(surfaceTolerance)
                    || surfaceTolerance < 0) {
                throw new IllegalArgumentException("Invalid scan limits");
            }
            Objects.requireNonNull(cancelled, "cancelled");
            Objects.requireNonNull(progress, "progress");
        }
    }

    public record Finding(
            Rule rule,
            Confidence confidence,
            GeoPoint position,
            GeoPoint target,
            Cell source,
            Cell neighbor,
            Direction direction,
            Completeness completeness,
            String detail,
            List<Finding> joined) {
        public Finding {
            joined = List.copyOf(joined);
        }

        public Finding(
                Rule rule,
                Confidence confidence,
                GeoPoint position,
                GeoPoint target,
                Cell source,
                Cell neighbor,
                Direction direction,
                Completeness completeness,
                String detail) {
            this(rule, confidence, position, target, source, neighbor, direction, completeness, detail, List.of());
        }

        public int edges() {
            return Math.max(1, joined.size());
        }
    }

    public record Result(
            List<Finding> findings,
            int scannedCells,
            int testedTransitions,
            int unavailableCells,
            Completeness completeness,
            boolean findingsTruncated,
            String sourceId,
            long revision,
            boolean clientGeometryChecked,
            int clientCheckedCells,
            int clientUnavailableCells) {
        public Result {
            findings = List.copyOf(findings);
        }
    }

    public Result scan(Bounds bounds, Limits limits) {
        return new Scan(limits).run(bounds);
    }

    private final class Scan {
        private final Limits limits;
        private final GeoQueryEngine engine = new GeoQueryEngine(data);
        private final GeoLocalReach reach = new GeoLocalReach(data, engine);
        private final String sourceId = data.getSourceId();
        private final long revision = data.revision();
        private final List<Finding> findings = new ArrayList<>();
        private final HashSet<GeoTile> unavailableRegions = new HashSet<>();
        private final HashSet<GeoTile> unavailableClientRegions = new HashSet<>();
        private final HashSet<Long> unavailableCells = new HashSet<>();
        private int scanned;
        private int transitions;
        private int clientChecked;
        private int clientUnavailable;
        private boolean truncated;
        private Completeness completeness = Completeness.COMPLETE;

        Scan(Limits limits) {
            this.limits = Objects.requireNonNull(limits, "limits");
        }

        Result run(Bounds bounds) {
            if (bounds.minX() == bounds.maxX() || bounds.minY() == bounds.maxY()) {
                return result();
            }
            for (var x = (long) Math.floorDiv(bounds.minX(), 16) * 16; x < bounds.maxX(); x += 16) {
                for (var y = (long) Math.floorDiv(bounds.minY(), 16) * 16; y < bounds.maxY(); y += 16) {
                    if (shouldStop()) {
                        return result();
                    }
                    if (scanned >= limits.maxCells()) {
                        completeness = Completeness.LIMIT;
                        return result();
                    }
                    var cell = data.cellAt((int) x, (int) y);
                    scanned++;
                    limits.progress().accept(scanned);
                    if (!checkAvailable(cell)) {
                        continue;
                    }
                    inspect(cell);
                    if (shouldStop() || completeness == Completeness.LIMIT) {
                        return result();
                    }
                }
            }
            shouldStop();
            return result();
        }

        private void inspect(Cell cell) {
            var clientSample = sampleClient(cell);
            if (clientSample != null && clientSample.completeness() == Completeness.LIMIT) {
                return;
            }
            if (cell.layers().isEmpty()) {
                add(new Finding(
                        Rule.EMPTY_CELL,
                        Confidence.HEURISTIC,
                        new GeoPoint(cell.x() + 8.0, cell.y() + 8.0, 0),
                        null,
                        cell,
                        null,
                        null,
                        Completeness.COMPLETE,
                        "No layer in this cell. Characters cannot stand here, and moves into it fail"));
                return;
            }
            var heights = new HashSet<Integer>();
            var previous = Integer.MAX_VALUE;
            for (var layer : cell.layers()) {
                if (shouldStop()) {
                    return;
                }
                var position = new GeoPoint(cell.x() + 8.0, cell.y() + 8.0, layer.height());
                if (layer.nswe() < 0
                        || layer.nswe() > 15
                        || layer.height() < -16384
                        || layer.height() > 16376
                        || Math.floorMod(layer.height(), 8) != 0) {
                    add(new Finding(
                            Rule.INVALID_LAYER,
                            Confidence.ERROR,
                            position,
                            null,
                            cell,
                            null,
                            null,
                            Completeness.COMPLETE,
                            "Layer cannot be saved as L2OFF data: the height must be a multiple of 8 from"
                                    + " -16384 to 16376 and directions 0 to 15"));
                }
                if (!heights.add(layer.height())) {
                    add(new Finding(
                            Rule.DUPLICATE_HEIGHT,
                            Confidence.HEURISTIC,
                            position,
                            null,
                            cell,
                            null,
                            null,
                            Completeness.COMPLETE,
                            "Two layers share Z " + layer.height()
                                    + ". The server always takes the first, so the other can never be reached"));
                }
                if (layer.height() > previous) {
                    add(new Finding(
                            Rule.LAYER_ORDER,
                            Confidence.HEURISTIC,
                            position,
                            null,
                            cell,
                            null,
                            null,
                            Completeness.COMPLETE,
                            "Layers are not stored highest first. The server takes the first layer at or below"
                                    + " a step, so this order can hide a floor"));
                }
                previous = layer.height();
                inspectTransitions(new Selection(cell, layer));
                if (completeness == Completeness.LIMIT || shouldStop()) {
                    return;
                }
                inspectSurface(cell, layer, position, clientSample);
            }
        }

        private void inspectTransitions(Selection selection) {
            var covered = true;
            var outgoing = false;
            var incoming = false;
            for (var direction : Direction.values()) {
                if (!tickTransition()) {
                    return;
                }
                var neighbor = data.cellAt(
                        selection.cell().x() + direction.getDx(),
                        selection.cell().y() + direction.getDy());
                if (!checkAvailable(neighbor)) {
                    covered = false;
                    continue;
                }
                var forward = engine.computeTransition(selection, direction);
                outgoing |= forward.accepted();
                if (forward.accepted() && !inspectMove(selection, neighbor, direction, forward.to())) {
                    return;
                }
                for (var layer : neighbor.layers()) {
                    if (!tickTransition()) {
                        return;
                    }
                    var reverse = engine.computeTransition(new Selection(neighbor, layer), direction.opposite());
                    incoming |= reverse.accepted() && GeoQueryEngine.isSameCell(reverse.to(), selection);
                }
            }
            if (covered && !incoming && !outgoing && selection.layer().nswe() != 0) {
                add(new Finding(
                        Rule.ISOLATED_LAYER,
                        Confidence.HEURISTIC,
                        selection.center(),
                        null,
                        selection.cell(),
                        null,
                        null,
                        Completeness.COMPLETE,
                        "No move leads into or out of this layer, although all four neighbors are loaded"));
            }
        }

        private boolean inspectMove(Selection selection, Cell neighbor, Direction direction, Selection target) {
            var change = target.layer().height() - selection.layer().height();
            var seam = !GeoTile.fromWorld(selection.cell().x(), selection.cell().y())
                    .equals(GeoTile.fromWorld(neighbor.x(), neighbor.y()));
            var way = direction.name().toLowerCase(Locale.ROOT);
            if (change >= WALL_CLIMB || -change >= limits.abruptHeight()) {
                var climb = change > 0;
                var rule = seam ? Rule.REGION_SEAM : climb ? Rule.STEEP_CLIMB : Rule.LONG_DROP;
                var detail = seam
                        ? "The region border "
                                + (climb ? "rises " : "falls ")
                                + Math.abs(change)
                                + " units going "
                                + way
                                + ". Generated region files often end in a flat strip, so most borders jump a little."
                                + " Check it when characters cross here"
                        : climb
                                ? "Open edge climbs "
                                        + change
                                        + " units going "
                                        + way
                                        + ". The server allows it because the neighbor has no layer at most "
                                        + STEP_LIMIT
                                        + " units higher, so characters can walk up a wall here"
                                : "Open edge drops " + -change + " units going " + way
                                        + ". Characters fall to the lower layer here";
                add(new Finding(
                        rule,
                        rule == Rule.STEEP_CLIMB ? Confidence.HEURISTIC : Confidence.INFORMATION,
                        selection.center(),
                        target.center(),
                        selection.cell(),
                        neighbor,
                        direction,
                        Completeness.COMPLETE,
                        detail));
            }
            if (!tickTransition()) {
                return false;
            }
            var reverse = engine.computeTransition(target, direction.opposite());
            if (reverse.accepted() && (GeoQueryEngine.isSameCell(reverse.to(), selection) || change < -STEP_LIMIT)) {
                return true;
            }
            Rule rule;
            String detail;
            var pocket = 0;
            if (reverse.accepted()) {
                rule = Rule.OTHER_FLOOR_RETURN;
                detail = "Walking back from the neighbor lands on another layer of this cell, at Z "
                        + reverse.to().layer().height();
            } else if (target.layer().nswe() == 0) {
                rule = Rule.OBSTACLE_ENTRY;
                detail = "The edge going " + way
                        + " leads into an obstacle cell. A character that enters it cannot move on";
            } else {
                var slope = change < 0 || reach.isNearSlope(selection) || reach.isNearSlope(target);
                var back = reach.findWayBack(selection, target, slope);
                if (back == null) {
                    return true;
                }
                rule = back.rule();
                pocket = back.pocket();
                detail = switch (rule) {
                    case SHORT_DETOUR ->
                        "Characters walk " + way
                                + " into the neighbor but cannot step back. Another way back is within "
                                + RETURN_RADIUS / 16 + " cells, so this is usually only the thickness of a wall";
                    case ONE_WAY_TRAP ->
                        "Characters walk " + way + " into a closed area of " + pocket
                                + " cells and cannot leave it again";
                    default ->
                        "Characters walk " + way + " into the neighbor but find no way back within "
                                + RETURN_RADIUS / 16
                                + " cells. Usually a wall closed on one side only, which lets characters pass it one"
                                + " way";
                };
            }
            if (rule == Rule.ONE_WAY_TRANSITION && surfaces != null) {
                detail += switch (surfaces.checkPassage(selection.center(), target.center())) {
                    case BLOCKED -> ". A client wall stands on this edge, so close this side too";
                    case OPEN ->
                        ". Nothing in the client blocks this edge, so the neighbor's closed side is probably wrong";
                    case UNKNOWN -> "";
                };
            }
            var confidence = switch (rule) {
                case SHORT_DETOUR, OTHER_FLOOR_RETURN -> Confidence.INFORMATION;
                case ONE_WAY_TRAP, OBSTACLE_ENTRY ->
                    reach.openGround(selection) ? Confidence.HEURISTIC : Confidence.INFORMATION;
                default -> reach.hasEnteredWidely(selection) ? Confidence.HEURISTIC : Confidence.INFORMATION;
            };
            if (confidence == Confidence.INFORMATION && rule == Rule.ONE_WAY_TRANSITION) {
                detail += ". Only a few cells lead to this side, so characters rarely get here";
            }
            if (confidence == Confidence.INFORMATION && (rule == Rule.ONE_WAY_TRAP || rule == Rule.OBSTACLE_ENTRY)) {
                detail += ". The way in starts on steep ground that only leads down, so characters rarely get here";
            }
            add(new Finding(
                    rule,
                    confidence,
                    selection.center(),
                    target.center(),
                    selection.cell(),
                    neighbor,
                    direction,
                    Completeness.COMPLETE,
                    detail));
            return true;
        }

        private ClientSample sampleClient(Cell cell) {
            if (surfaces == null) {
                return null;
            }
            var sample = surfaces.sampleAt(cell.x() + 8, cell.y() + 8);
            if (sample.completeness() == Completeness.COMPLETE) {
                clientChecked++;
            } else {
                clientUnavailable++;
                completeness = sample.completeness();
                if (unavailableClientRegions.add(GeoTile.fromWorld(cell.x(), cell.y()))) {
                    add(new Finding(
                            Rule.CLIENT_GEOMETRY_INCOMPLETE,
                            Confidence.INFORMATION,
                            new GeoPoint(cell.x() + 8.0, cell.y() + 8.0, 0),
                            null,
                            cell,
                            null,
                            null,
                            sample.completeness(),
                            sample.detail()));
                }
            }
            return sample;
        }

        private void inspectSurface(Cell cell, Layer layer, GeoPoint position, ClientSample sample) {
            if (sample == null || sample.completeness() != Completeness.COMPLETE || layer.nswe() == 0) {
                return;
            }
            ClientSurface closest = null;
            var referenced = false;
            for (var candidate : sample.surfaces()) {
                referenced |= Math.abs(candidate.height() - position.z()) <= CLIENT_REFERENCE_RANGE;
                if (matches(candidate, position)) {
                    return;
                }
                if (candidate.kind() == SurfaceKind.WALKABLE
                        && (closest == null
                                || Math.abs(candidate.height() - position.z())
                                        < Math.abs(closest.height() - position.z()))) {
                    closest = candidate;
                }
            }
            if (!referenced
                    || closest == null
                    || Math.abs(closest.height() - position.z()) <= limits.surfaceTolerance()) {
                return;
            }
            for (var dx = -6; dx <= 6; dx += 6) {
                for (var dy = -6; dy <= 6; dy += 6) {
                    if (dx == 0 && dy == 0) {
                        continue;
                    }
                    var nearby = surfaces.sampleAt(cell.x() + 8 + dx, cell.y() + 8 + dy);
                    if (nearby.completeness() != Completeness.COMPLETE) {
                        return;
                    }
                    for (var candidate : nearby.surfaces()) {
                        if (matches(candidate, position)) {
                            return;
                        }
                    }
                }
            }
            add(new Finding(
                    Rule.CLIENT_SURFACE_MISMATCH,
                    Confidence.HEURISTIC,
                    position,
                    new GeoPoint(position.x(), position.y(), closest.height()),
                    cell,
                    null,
                    null,
                    Completeness.COMPLETE,
                    "Geodata is "
                            + Math.round(Math.abs(position.z() - closest.height()))
                            + " units "
                            + (position.z() > closest.height() ? "above" : "below")
                            + " the nearest walkable client surface (tolerance "
                            + Math.round(limits.surfaceTolerance())
                            + "). Reference: "
                            + closest.version()));
        }

        private boolean matches(ClientSurface surface, GeoPoint position) {
            return surface.kind() != SurfaceKind.DECORATIVE
                    && Math.abs(surface.height() - position.z()) <= limits.surfaceTolerance();
        }

        private boolean checkAvailable(Cell cell) {
            if (cell.availability() == Availability.AVAILABLE) {
                return true;
            }
            unavailableCells.add((long) cell.x() << 32 | (cell.y() & 0xffffffffL));
            completeness = Completeness.DATA_UNAVAILABLE;
            if (unavailableRegions.add(GeoTile.fromWorld(cell.x(), cell.y()))) {
                var corrupt = cell.availability() == Availability.CORRUPT;
                add(new Finding(
                        corrupt ? Rule.CORRUPT_REGION : Rule.UNAVAILABLE_REGION,
                        corrupt ? Confidence.ERROR : Confidence.INFORMATION,
                        new GeoPoint(cell.x() + 8.0, cell.y() + 8.0, 0),
                        null,
                        cell,
                        null,
                        null,
                        Completeness.DATA_UNAVAILABLE,
                        describeUnavailable(cell)));
            }
            return false;
        }

        private boolean tickTransition() {
            if (shouldStop()) {
                return false;
            }
            if (transitions >= limits.maxTransitions()) {
                completeness = Completeness.LIMIT;
                return false;
            }
            transitions++;
            return true;
        }

        private boolean shouldStop() {
            if (revision != data.revision()
                    || !sourceId.equals(data.getSourceId())
                    || surfaces != null && !surfaces.isValid()) {
                completeness = Completeness.SOURCE_CHANGED;
                return true;
            }
            if (limits.cancelled().getAsBoolean() || Thread.currentThread().isInterrupted()) {
                completeness = Completeness.CANCELLED;
                return true;
            }
            return false;
        }

        private void add(Finding finding) {
            if (findings.size() < limits.maxFindings()) {
                findings.add(finding);
            } else {
                truncated = true;
            }
        }

        private Result result() {
            return new Result(
                    GeoFindingJoiner.join(findings),
                    scanned,
                    transitions,
                    unavailableCells.size(),
                    completeness,
                    truncated,
                    sourceId,
                    revision,
                    surfaces != null,
                    clientChecked,
                    clientUnavailable);
        }

        private static String describeUnavailable(Cell cell) {
            var tile = GeoTile.fromWorld(cell.x(), cell.y());
            return switch (cell.availability()) {
                case MISSING -> "Region " + tile.fileName() + " is not in the folder";
                case NOT_LOADED -> "Region " + tile.fileName() + " is not loaded. Scan closer to the camera or reload";
                case OUTSIDE_WORLD -> "Outside the server world grid";
                case CORRUPT -> "Region " + tile.fileName() + " cannot be read: " + cell.detail();
                case AVAILABLE -> "";
            };
        }
    }
}
