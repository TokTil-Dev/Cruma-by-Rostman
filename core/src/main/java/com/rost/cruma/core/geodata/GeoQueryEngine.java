package com.rost.cruma.core.geodata;

import com.rost.cruma.core.geodata.GeoDataAccess.Availability;
import com.rost.cruma.core.geodata.GeoDataAccess.Cell;
import com.rost.cruma.core.geodata.GeoDataAccess.Layer;
import com.rost.cruma.core.geodata.GeoDataAccess.Storage;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

public final class GeoQueryEngine {
    public static final double DEFAULT_DISTANCE = 8192.0;

    private final GeoDataAccess data;

    public GeoQueryEngine(GeoDataAccess data) {
        this.data = Objects.requireNonNull(data);
    }

    public enum Direction {
        EAST(1, 16, 0),
        WEST(2, -16, 0),
        SOUTH(4, 0, 16),
        NORTH(8, 0, -16);

        private final int mask;
        private final int dx;
        private final int dy;

        Direction(int mask, int dx, int dy) {
            this.mask = mask;
            this.dx = dx;
            this.dy = dy;
        }

        public int getMask() {
            return mask;
        }

        public int getDx() {
            return dx;
        }

        public int getDy() {
            return dy;
        }

        public Direction opposite() {
            return values()[ordinal() ^ 1];
        }
    }

    public enum Failure {
        NONE,
        DIRECTION_BLOCKED,
        NO_LAYER,
        DATA_UNAVAILABLE,
        HEIGHT_MISMATCH,
        UNEXPECTED_STEP,
        STEP_LIMIT,
        CANCELLED,
        SOURCE_CHANGED,
        VERTICAL_COLLISION
    }

    public enum Completeness {
        COMPLETE,
        DATA_UNAVAILABLE,
        LIMIT,
        CANCELLED,
        SOURCE_CHANGED
    }

    public record Selection(Cell cell, Layer layer) {
        public GeoPoint center() {
            return new GeoPoint(cell.x() + 8.0, cell.y() + 8.0, layer.height());
        }
    }

    public record Transition(
            Selection from, Selection to, GeoPoint target, Direction direction, boolean accepted, Failure failure) {}

    public record Sample(GeoPoint position, Selection selection) {}

    public record Limits(int maxSteps, int maxTrace, BooleanSupplier cancelled) {
        public Limits {
            if (maxSteps < 1 || maxTrace < 0) {
                throw new IllegalArgumentException("Invalid query limits");
            }
            Objects.requireNonNull(cancelled);
        }

        public static Limits defaults() {
            return new Limits(65536, 8192, () -> false);
        }
    }

    public record MoveResult(
            boolean success,
            boolean reachedDestination,
            boolean distanceExhausted,
            double traveledDistance,
            GeoPoint finalPosition,
            Failure failure,
            Completeness completeness,
            List<Transition> trace,
            List<Sample> samples,
            boolean traceTruncated,
            String sourceId,
            long revision) {
        public MoveResult {
            trace = List.copyOf(trace);
            samples = List.copyOf(samples);
        }
    }

    public record BothResult(boolean success, MoveResult forward, MoveResult reverse) {}

    public enum SightCoverage {
        GEODATA_ONLY_WORLD_COLLISION_NOT_CHECKED
    }

    public record SightResult(
            boolean visible,
            Failure failure,
            Completeness completeness,
            SightCoverage coverage,
            List<Transition> trace,
            List<Sample> samples,
            boolean traceTruncated,
            String sourceId,
            long revision) {
        public SightResult {
            trace = List.copyOf(trace);
            samples = List.copyOf(samples);
        }
    }

    public static int serverInt(double value) {
        return value < Integer.MIN_VALUE || value >= 2147483648.0 ? Integer.MIN_VALUE : (int) value;
    }

    public static int cellMinimum(double value) {
        return serverInt(value) & ~15;
    }

    static boolean isSameCell(Selection first, Selection second) {
        if (first != null
                && second != null
                && first.cell().storage() == Storage.FLAT
                && second.cell().storage() == Storage.FLAT
                && Math.floorDiv(first.cell().x(), 128)
                        == Math.floorDiv(second.cell().x(), 128)
                && Math.floorDiv(first.cell().y(), 128)
                        == Math.floorDiv(second.cell().y(), 128)) {
            return true;
        }
        return first != null
                && second != null
                && first.cell().x() == second.cell().x()
                && first.cell().y() == second.cell().y()
                && first.layer().index() == second.layer().index();
    }

    public Selection findBaseCell(GeoPoint point) {
        return findBaseCell(data.cellAt(serverInt(point.x()), serverInt(point.y())), serverInt(point.z()));
    }

    public Selection findBaseCell(Cell cell, int z) {
        if (cell.availability() != Availability.AVAILABLE || cell.layers().isEmpty()) {
            return null;
        }
        var limit = z + 26;
        for (var layer : cell.layers()) {
            if (layer.height() <= limit) {
                return new Selection(cell, layer);
            }
        }
        return new Selection(cell, cell.layers().getLast());
    }

    public MoveResult moveStraight(GeoPoint source, GeoPoint destination) {
        return moveStraight(source, destination, DEFAULT_DISTANCE);
    }

    public MoveResult moveStraight(GeoPoint source, GeoPoint destination, double distance) {
        return moveStraight(source, destination, distance, Limits.defaults());
    }

    public MoveResult moveStraight(GeoPoint source, GeoPoint destination, double distance, Limits limits) {
        if (!Double.isFinite(distance) || distance < 0) {
            throw new IllegalArgumentException("Invalid distance");
        }
        var query = new Query(limits);
        var current = source;
        var selected = query.findBase(source);
        query.sample(current, selected);
        if (query.isStopped()) {
            return query.createResult(false, false, false, 0, current);
        }
        var dx = destination.x() - source.x();
        var dy = destination.y() - source.y();
        var horizontalSquared = dx * dx + dy * dy;
        if (horizontalSquared < 1e-6) {
            var target = query.findBase(destination);
            var same = isSameCell(selected, target);
            if (!same && query.failure == Failure.NONE) {
                query.fail(Failure.HEIGHT_MISMATCH);
            }
            return query.createResult(same, same, false, 0, current);
        }
        if (selected == null) {
            return query.createResult(false, false, false, 0, current);
        }
        var length = Math.sqrt(horizontalSquared);
        var stepX = dx / length * 16.0;
        var stepY = dy / length * 16.0;
        var stepSquared = stepX * stepX + stepY * stepY;
        var xDirection = dx > 0 ? Direction.EAST : Direction.WEST;
        var yDirection = dy > 0 ? Direction.SOUTH : Direction.NORTH;
        var traveled = 0.0;
        var reached = false;
        var reduced = false;
        while (traveled <= distance && traveled != distance && !reached) {
            if (!query.tick()) {
                return query.createResult(false, false, false, traveled, current);
            }
            if (!reduced && distance - traveled < 16) {
                if (stepSquared / 64 >= 0.5) {
                    stepX *= 0.125;
                    stepY *= 0.125;
                    stepSquared = stepX * stepX + stepY * stepY;
                }
                reduced = true;
            }
            var remainingX = destination.x() - current.x();
            var remainingY = destination.y() - current.y();
            GeoPoint candidate;
            if (stepSquared <= remainingX * remainingX + remainingY * remainingY) {
                candidate = new GeoPoint(current.x() + stepX, current.y() + stepY, current.z());
            } else {
                candidate = destination;
                reached = true;
                stepSquared = remainingX * remainingX + remainingY * remainingY;
            }
            var oldX = cellMinimum(current.x());
            var oldY = cellMinimum(current.y());
            var newX = cellMinimum(candidate.x());
            var newY = cellMinimum(candidate.y());
            var deltaX = Math.abs(newX - oldX);
            var deltaY = Math.abs(newY - oldY);
            var next = selected;
            if (deltaX == 16 && deltaY == 0) {
                next = query.next(
                        selected, xDirection, newX, oldY, selected.layer().height(), false);
            } else if (deltaX == 0 && deltaY == 16) {
                next = query.next(
                        selected, yDirection, oldX, newY, selected.layer().height(), false);
            } else if (deltaX == 16 && deltaY == 16) {
                var first = query.next(
                        selected, xDirection, newX, oldY, selected.layer().height(), false);
                var corner = first == null
                        ? null
                        : query.next(
                                first, yDirection, newX, newY, first.layer().height(), false);
                if (corner == null) {
                    return query.createResult(false, false, false, traveled, current);
                }
                var second = query.next(
                        selected, yDirection, oldX, newY, selected.layer().height(), false);
                next = second == null
                        ? null
                        : query.next(
                                second, xDirection, newX, newY, second.layer().height(), false);
            } else if (deltaX != 0 || deltaY != 0) {
                query.fail(Failure.UNEXPECTED_STEP);
                return query.createResult(false, false, false, traveled, current);
            }
            if (next == null || query.isStopped()) {
                return query.createResult(false, false, false, traveled, current);
            }
            traveled += Math.sqrt(stepSquared);
            current = new GeoPoint(candidate.x(), candidate.y(), next.layer().height());
            selected = next;
            query.sample(current, selected);
        }
        if (reached && Math.abs(selected.layer().height() - serverInt(destination.z())) > 48) {
            query.fail(Failure.HEIGHT_MISMATCH);
            return query.createResult(false, true, true, traveled, current);
        }
        if (!reached) {
            query.completeness = Completeness.LIMIT;
        }
        return query.createResult(true, reached, !reached, traveled, current);
    }

    public BothResult moveStraightTestBoth(
            GeoPoint source, GeoPoint destination, double distance, boolean bothDirections) {
        return moveStraightTestBoth(source, destination, distance, bothDirections, Limits.defaults());
    }

    public BothResult moveStraightTestBoth(
            GeoPoint source, GeoPoint destination, double distance, boolean bothDirections, Limits limits) {
        var forward = moveStraight(source, destination, distance, limits);
        if (forward.success() != bothDirections) {
            return new BothResult(forward.success(), forward, null);
        }
        var reverse = moveStraight(destination, source, distance, limits);
        return new BothResult(reverse.success(), forward, reverse);
    }

    public Transition computeTransition(Selection source, Direction direction) {
        var query = new Query(Limits.defaults());
        query.next(
                source,
                direction,
                source.cell().x() + direction.getDx(),
                source.cell().y() + direction.getDy(),
                source.layer().height(),
                false);
        return query.trace.getFirst();
    }

    public SightResult checkSight(GeoPoint source, GeoPoint destination) {
        return checkSight(source, destination, Limits.defaults());
    }

    public SightResult checkSight(GeoPoint source, GeoPoint destination, Limits limits) {
        var query = new Query(limits);
        var visible = source.distanceSquared(destination) < 0.5;
        if (!visible) {
            var upper = destination.z() < source.z() ? source : destination;
            var lower = destination.z() < source.z() ? destination : source;
            var dx = lower.x() - upper.x();
            var dy = lower.y() - upper.y();
            var length = Math.sqrt(dx * dx + dy * dy);
            var probeDistance = Math.min(64, length);
            upper = new GeoPoint(upper.x(), upper.y(), sightHeight(query, upper));
            lower = new GeoPoint(lower.x(), lower.y(), sightHeight(query, lower));
            var probe = new GeoPoint(
                    upper.x() + (length == 0 ? 0 : dx / length * probeDistance),
                    upper.y() + (length == 0 ? 0 : dy / length * probeDistance),
                    upper.z() + 16);
            visible = !query.isStopped()
                    && hasSightLine(query, probe, upper)
                    && hasSightLine(query, upper, probe)
                    && hasSightLine(query, probe, lower)
                    && hasSightLine(query, lower, probe);
        }
        query.failIfStopped();
        return new SightResult(
                visible && query.completeness == Completeness.COMPLETE,
                query.failure,
                query.completeness,
                SightCoverage.GEODATA_ONLY_WORLD_COLLISION_NOT_CHECKED,
                query.trace,
                query.samples,
                query.truncated,
                query.sourceId,
                query.revision);
    }

    Selection findUpperCell(Cell cell, int z, boolean requireAbove) {
        if (cell.availability() != Availability.AVAILABLE || cell.layers().isEmpty()) {
            return null;
        }
        var selected = cell.layers().getFirst();
        if (cell.storage() == Storage.FLAT) {
            return new Selection(cell, selected);
        }
        for (var i = 1; i < cell.layers().size(); i++) {
            var next = cell.layers().get(i);
            if (selected.height() <= z || next.height() <= z) {
                break;
            }
            selected = next;
        }
        return requireAbove && selected.height() < z ? null : new Selection(cell, selected);
    }

    private double sightHeight(Query query, GeoPoint point) {
        var limit = point.z() + 40;
        var cell = query.getCell(serverInt(point.x()), serverInt(point.y()));
        if (cell.storage() != Storage.MULTI) {
            return limit;
        }
        var upper = findUpperCell(cell, serverInt(point.z()), false);
        if (upper != null && point.z() < upper.layer().height()) {
            limit = Math.min(limit, upper.layer().height() - 5);
            var ground = query.findBase(cell, serverInt(point.z()));
            if (ground != null) {
                limit = Math.max(limit, ground.layer().height());
            }
        }
        return limit;
    }

    private boolean hasSightLine(Query query, GeoPoint source, GeoPoint destination) {
        if (source.distanceSquared(destination) < 0.5) {
            return true;
        }
        var x = cellMinimum(source.x());
        var y = cellMinimum(source.y());
        var endX = cellMinimum(destination.x());
        var endY = cellMinimum(destination.y());
        var dirX = x < endX ? Direction.EAST : Direction.WEST;
        var dirY = y < endY ? Direction.SOUTH : Direction.NORTH;
        var dx = Math.abs(endX - x) / 16;
        var dy = Math.abs(endY - y) / 16;
        var major = Math.max(dx, dy);
        var minor = Math.min(dx, dy);
        var error = minor * 2 - major;
        var verticalStep = major == 0 ? destination.z() - source.z() : (destination.z() - source.z()) / major;
        var collisionDistance = Math.abs(verticalStep);
        var sightZ = source.z();
        var current = query.findBase(source);
        query.sample(source, current);
        if (current == null) {
            return false;
        }
        if (major == 0) {
            return !hasVerticalCollision(query, x, y, sightZ, collisionDistance);
        }
        for (var i = 0; i < major; i++) {
            if (!query.tick() || hasVerticalCollision(query, x, y, sightZ, collisionDistance)) {
                return false;
            }
            Selection next;
            if (error < 1) {
                var direction = dy <= dx ? dirX : dirY;
                x += direction.getDx();
                y += direction.getDy();
                next = sightNext(query, current, direction, x, y, serverInt(sightZ));
                error += 2 * minor;
            } else {
                var initialFailure = query.failure;
                var intermediate = sightNext(query, current, dirX, x + dirX.getDx(), y, serverInt(sightZ));
                next = intermediate == null
                        ? null
                        : sightNext(query, intermediate, dirY, x + dirX.getDx(), y + dirY.getDy(), serverInt(sightZ));
                if (next == null) {
                    query.failure = initialFailure;
                    intermediate = sightNext(query, current, dirY, x, y + dirY.getDy(), serverInt(sightZ));
                    next = intermediate == null
                            ? null
                            : sightNext(
                                    query, intermediate, dirX, x + dirX.getDx(), y + dirY.getDy(), serverInt(sightZ));
                }
                x += dirX.getDx();
                y += dirY.getDy();
                error += 2 * (minor - major);
            }
            if (next == null || query.isStopped()) {
                return false;
            }
            current = next;
            sightZ += verticalStep;
            query.sample(new GeoPoint(x + 8, y + 8, sightZ), current);
        }
        return true;
    }

    private boolean hasVerticalCollision(Query query, int x, int y, double z, double distance) {
        if (distance <= 0) {
            return false;
        }
        var destinationZ = serverInt(z) + serverInt(distance);
        var ground = query.findBase(query.getCell(x + 8, y + 8), destinationZ);
        if (ground == null) {
            return true;
        }
        if (destinationZ < ground.layer().height()
                || serverInt(z) < ground.layer().height()) {
            query.fail(Failure.VERTICAL_COLLISION);
            return true;
        }
        return false;
    }

    private Selection sightNext(Query query, Selection current, Direction direction, int x, int y, int z) {
        var previousFailure = query.failure;
        var next = query.next(current, direction, x, y, z, true);
        if (next != null) {
            return next;
        }
        var target = query.findBase(query.getCell(x + 8, y + 8), z);
        if (target == null || z < target.layer().height()) {
            return null;
        }
        var reverse = query.next(
                target,
                direction.opposite(),
                x - direction.getDx(),
                y - direction.getDy(),
                target.layer().height(),
                true);
        if (!isSameCell(reverse, current)) {
            return null;
        }
        query.failure = previousFailure;
        return target;
    }

    final class Query {
        final Limits limits;
        final long revision = data.revision();
        final String sourceId = data.getSourceId();
        final ArrayList<Transition> trace = new ArrayList<>();
        final ArrayList<Sample> samples = new ArrayList<>();
        Failure failure = Failure.NONE;
        Completeness completeness = Completeness.COMPLETE;
        boolean truncated;
        int steps;

        Query(Limits limits) {
            this.limits = limits;
        }

        boolean tick() {
            if (isStopped()) {
                return false;
            }
            if (++steps > limits.maxSteps()) {
                fail(Failure.STEP_LIMIT);
                return false;
            }
            return true;
        }

        boolean isStopped() {
            if (limits.cancelled().getAsBoolean() || Thread.currentThread().isInterrupted()) {
                fail(Failure.CANCELLED);
            }
            if (data.revision() != revision || !data.getSourceId().equals(sourceId)) {
                fail(Failure.SOURCE_CHANGED);
            }
            return completeness != Completeness.COMPLETE;
        }

        void fail(Failure reason) {
            if (failure == Failure.NONE) {
                failure = reason;
            }
            switch (reason) {
                case DATA_UNAVAILABLE -> completeness = Completeness.DATA_UNAVAILABLE;
                case STEP_LIMIT -> completeness = Completeness.LIMIT;
                case CANCELLED -> completeness = Completeness.CANCELLED;
                case SOURCE_CHANGED -> completeness = Completeness.SOURCE_CHANGED;
                default -> {}
            }
        }

        Cell getCell(int x, int y) {
            var cell = data.cellAt(x, y);
            if (cell.availability() != Availability.AVAILABLE) {
                fail(Failure.DATA_UNAVAILABLE);
            } else if (cell.layers().isEmpty()) {
                fail(Failure.NO_LAYER);
            }
            return cell;
        }

        Selection findBase(GeoPoint point) {
            return findBase(getCell(serverInt(point.x()), serverInt(point.y())), serverInt(point.z()));
        }

        Selection findBase(Cell cell, int z) {
            return findBaseCell(cell, z);
        }

        Selection next(Selection current, Direction direction, int x, int y, int z, boolean jump) {
            Selection selected = null;
            var reason = Failure.NONE;
            if ((current.layer().nswe() & direction.getMask()) == 0 && !jump) {
                reason = Failure.DIRECTION_BLOCKED;
            } else {
                var target = getCell(x + 8, y + 8);
                if (target.availability() != Availability.AVAILABLE) {
                    reason = Failure.DATA_UNAVAILABLE;
                } else if (target.layers().isEmpty()) {
                    reason = Failure.NO_LAYER;
                } else if ((current.layer().nswe() & direction.getMask()) != 0) {
                    selected = findBase(target, z);
                } else {
                    selected = findUpperCell(target, z, true);
                    if (selected != null && z + 32 < selected.layer().height()) {
                        selected = null;
                    }
                    if (selected == null) {
                        reason = Failure.DIRECTION_BLOCKED;
                    }
                }
            }
            var targetPoint = new GeoPoint(
                    x + 8.0, y + 8.0, selected == null ? z : selected.layer().height());
            if (trace.size() < limits.maxTrace()) {
                trace.add(new Transition(current, selected, targetPoint, direction, selected != null, reason));
            } else {
                truncated = true;
            }
            if (reason != Failure.NONE) {
                fail(reason);
            }
            return selected;
        }

        void sample(GeoPoint point, Selection selected) {
            if (samples.size() < limits.maxTrace()) {
                samples.add(new Sample(point, selected));
            } else {
                truncated = true;
            }
        }

        MoveResult createResult(
                boolean success, boolean reached, boolean exhausted, double traveled, GeoPoint position) {
            failIfStopped();
            return new MoveResult(
                    success && completeness != Completeness.CANCELLED && completeness != Completeness.SOURCE_CHANGED,
                    reached,
                    exhausted,
                    traveled,
                    position,
                    failure,
                    completeness,
                    trace,
                    samples,
                    truncated,
                    sourceId,
                    revision);
        }

        private void failIfStopped() {
            if (limits.cancelled().getAsBoolean()) {
                fail(Failure.CANCELLED);
            }
            if (data.revision() != revision || !data.getSourceId().equals(sourceId)) {
                fail(Failure.SOURCE_CHANGED);
            }
        }
    }
}
