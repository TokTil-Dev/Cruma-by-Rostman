package com.rost.cruma.core.geodata;

import com.rost.cruma.core.geodata.GeoDataAccess.Availability;
import com.rost.cruma.core.geodata.GeoDataScanner.Rule;
import com.rost.cruma.core.geodata.GeoQueryEngine.Direction;
import com.rost.cruma.core.geodata.GeoQueryEngine.Failure;
import com.rost.cruma.core.geodata.GeoQueryEngine.Selection;
import com.rost.cruma.core.geodata.GeoReachability.Node;
import java.util.ArrayDeque;
import java.util.HashSet;

final class GeoLocalReach {
    private static final int RETURN_NODES = 4096;

    private static final int OPEN_GROUND = 256;

    private static final int SLOPE_RANGE = 48;

    private final GeoDataAccess data;
    private final GeoQueryEngine engine;

    GeoLocalReach(GeoDataAccess data, GeoQueryEngine engine) {
        this.data = data;
        this.engine = engine;
    }

    record WayBack(Rule rule, int pocket) {}

    WayBack findWayBack(Selection selection, Selection target, boolean trapOnly) {
        var home = Node.of(selection);
        var visited = new HashSet<Node>();
        var queue = new ArrayDeque<Selection>();
        visited.add(Node.of(target));
        queue.add(target);
        var closed = true;
        while (!queue.isEmpty()) {
            var current = queue.removeFirst();
            for (var direction : Direction.values()) {
                if ((current.layer().nswe() & direction.getMask()) == 0) {
                    continue;
                }
                var x = current.cell().x() + direction.getDx();
                var y = current.cell().y() + direction.getDy();
                if (Math.abs(x - target.cell().x()) > GeoDataScanner.RETURN_RADIUS
                        || Math.abs(y - target.cell().y()) > GeoDataScanner.RETURN_RADIUS) {
                    if (trapOnly) {
                        return null;
                    }
                    closed = false;
                    continue;
                }
                var edge = engine.computeTransition(current, direction);
                if (!edge.accepted()) {
                    if (edge.failure() == Failure.DATA_UNAVAILABLE) {
                        if (trapOnly) {
                            return null;
                        }
                        closed = false;
                    }
                    continue;
                }
                var node = Node.of(edge.to());
                if (node.equals(home)) {
                    return trapOnly ? null : new WayBack(Rule.SHORT_DETOUR, 0);
                }
                if (visited.size() >= RETURN_NODES) {
                    return trapOnly ? null : new WayBack(Rule.ONE_WAY_TRANSITION, 0);
                }
                if (visited.add(node)) {
                    queue.add(edge.to());
                }
            }
        }
        return new WayBack(closed ? Rule.ONE_WAY_TRAP : Rule.ONE_WAY_TRANSITION, visited.size());
    }

    boolean hasEnteredWidely(Selection start) {
        var visited = new HashSet<Node>();
        var queue = new ArrayDeque<Selection>();
        visited.add(Node.of(start));
        queue.add(start);
        while (!queue.isEmpty()) {
            var current = queue.removeFirst();
            for (var direction : Direction.values()) {
                var cell = data.cellAt(
                        current.cell().x() + direction.getDx(), current.cell().y() + direction.getDy());
                if (cell.availability() != Availability.AVAILABLE) {
                    return true;
                }
                for (var layer : cell.layers()) {
                    var from = new Selection(cell, layer);
                    var edge = engine.computeTransition(from, direction.opposite());
                    if (edge.accepted()
                            && GeoQueryEngine.isSameCell(edge.to(), current)
                            && visited.add(Node.of(from))) {
                        if (visited.size() >= OPEN_GROUND) {
                            return true;
                        }
                        queue.add(from);
                    }
                }
            }
        }
        return false;
    }

    boolean openGround(Selection start) {
        var visited = new HashSet<Node>();
        var queue = new ArrayDeque<Selection>();
        visited.add(Node.of(start));
        queue.add(start);
        while (!queue.isEmpty()) {
            var current = queue.removeFirst();
            for (var direction : Direction.values()) {
                var forward = engine.computeTransition(current, direction);
                if (!forward.accepted()) {
                    continue;
                }
                var back = engine.computeTransition(forward.to(), direction.opposite());
                if (back.accepted()
                        && GeoQueryEngine.isSameCell(back.to(), current)
                        && visited.add(Node.of(forward.to()))) {
                    if (visited.size() >= OPEN_GROUND) {
                        return true;
                    }
                    queue.add(forward.to());
                }
            }
        }
        return false;
    }

    boolean isNearSlope(Selection center) {
        for (var dx = -16; dx <= 16; dx += 16) {
            for (var dy = -16; dy <= 16; dy += 16) {
                var cell = data.cellAt(center.cell().x() + dx, center.cell().y() + dy);
                if (cell.availability() != Availability.AVAILABLE) {
                    continue;
                }
                for (var layer : cell.layers()) {
                    if (Math.abs(layer.height() - center.layer().height()) > SLOPE_RANGE) {
                        continue;
                    }
                    var from = new Selection(cell, layer);
                    for (var direction : Direction.values()) {
                        var down = engine.computeTransition(from, direction);
                        if (down.accepted()
                                && down.to().layer().height() < layer.height()
                                && !engine.computeTransition(down.to(), direction.opposite())
                                        .accepted()) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }
}
