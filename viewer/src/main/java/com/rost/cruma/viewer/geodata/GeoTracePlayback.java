package com.rost.cruma.viewer.geodata;

import com.rost.cruma.core.geodata.GeoPoint;
import com.rost.cruma.core.geodata.GeoQueryEngine.MoveResult;
import com.rost.cruma.core.geodata.GeoQueryEngine.Sample;
import com.rost.cruma.core.geodata.GeoQueryEngine.SightResult;
import com.rost.cruma.core.geodata.GeoQueryEngine.Transition;
import com.rost.cruma.core.geodata.ServerPathFinder.Replay;
import com.rost.cruma.core.geodata.ServerPathFinder.Result;
import java.util.List;

public final class GeoTracePlayback {
    private Object owner;
    private int index;

    public record Step(GeoPoint from, GeoPoint to, String description) {}

    public void bind(Object result) {
        if (owner != result) {
            owner = result;
            index = 0;
        }
    }

    public int getCount() {
        return switch (owner) {
            case MoveResult move -> count(move.trace(), move.samples());
            case SightResult sight -> count(sight.trace(), sight.samples());
            case Result path -> path.trace().size();
            case Replay replay -> Math.max(0, replay.waypoints().size() - 1);
            case null, default -> 0;
        };
    }

    public int getIndex() {
        return index;
    }

    public void seek(int requested) {
        index = Math.clamp(requested, 0, Math.max(0, getCount() - 1));
    }

    public void advance() {
        seek(index + 1);
    }

    public Step getSelected() {
        return getCount() == 0 ? null : step(index);
    }

    public Step step(int at) {
        if (at < 0 || at >= getCount()) {
            throw new IndexOutOfBoundsException(at);
        }
        return switch (owner) {
            case MoveResult move -> step(move.trace(), move.samples(), at);
            case SightResult sight -> step(sight.trace(), sight.samples(), at);
            case Result path -> {
                var trace = path.trace().get(at);
                yield new Step(
                        trace.from(),
                        trace.to(),
                        trace.phase()
                                + " "
                                + trace.fromIndex()
                                + " → "
                                + trace.toIndex()
                                + " · g "
                                + trace.distance()
                                + " · h "
                                + trace.lowerBound()
                                + " · depth "
                                + trace.depth()
                                + " · "
                                + trace.detail());
            }
            case Replay replay ->
                new Step(
                        replay.waypoints().get(at),
                        replay.waypoints().get(at + 1),
                        "Server waypoint movement " + (at + 1));
            default -> throw new IllegalStateException("No trace bound");
        };
    }

    public boolean isTruncated() {
        return switch (owner) {
            case MoveResult move -> move.traceTruncated();
            case SightResult sight -> sight.traceTruncated();
            case Result path -> path.traceTruncated();
            case null, default -> false;
        };
    }

    private static int count(List<Transition> trace, List<Sample> samples) {
        return trace.isEmpty() ? Math.max(0, samples.size() - 1) : trace.size();
    }

    private static Step step(List<Transition> trace, List<Sample> samples, int at) {
        if (trace.isEmpty()) {
            return new Step(samples.get(at).position(), samples.get(at + 1).position(), "Position sample " + (at + 1));
        }
        var edge = trace.get(at);
        var from = edge.from();
        var layer = from == null
                ? ""
                : " · source layer "
                        + from.layer().index()
                        + " Z "
                        + from.layer().height()
                        + " NSWE "
                        + from.layer().nswe();
        return new Step(
                from == null ? null : from.center(), edge.target(), edge.direction() + " · " + edge.failure() + layer);
    }
}
