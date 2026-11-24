package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.NativeTerrainSteadyLighting.WorldQuery;
import com.rost.cruma.core.editing.TerrainNativeCodec.Vector;
import java.util.List;
import java.util.Objects;

public final class NativeLevelShadowQuery implements WorldQuery {
    private static final int INPUT_FLAGS = 0x197;
    private static final int MULTI_FLAGS = 0x597;

    private static final Result CLEAR = new Result(State.CLEAR, "Complete qualified scene is clear");
    private static final Result CURRENT_BSP_BLOCKED = new Result(State.BLOCKED, "Current Level BSP blocks the segment");
    private static final Result NEIGHBOR_BSP_BLOCKED =
            new Result(State.BLOCKED, "Neighbor Level BSP blocks the segment");

    private final NativeBspTrace currentBsp;
    private final boolean neighborBspEnabled;
    private final List<NativeBspTrace> neighborBsps;
    private final List<ActorDomain> actorDomains;

    public NativeLevelShadowQuery(
            NativeBspTrace currentBsp,
            boolean neighborBspEnabled,
            List<NativeBspTrace> neighborBsps,
            List<ActorDomain> actorDomains) {
        this.currentBsp = Objects.requireNonNull(currentBsp);
        if (neighborBsps.size() > 63 || actorDomains.size() > 4096) {
            throw new IllegalArgumentException("Native Level domain count exceeds qualified bounds");
        }
        this.neighborBspEnabled = neighborBspEnabled;
        this.neighborBsps = List.copyOf(neighborBsps);
        this.actorDomains = List.copyOf(actorDomains);
    }

    public enum State {
        CLEAR,
        BLOCKED,
        UNQUALIFIED
    }

    public record Result(State state, String evidence) {
        public Result {
            Objects.requireNonNull(state);
            if (evidence == null || evidence.isBlank()) {
                throw new IllegalArgumentException("Native query evidence is required");
            }
        }
    }

    @FunctionalInterface
    public interface ActorDomain {
        Result check(Vector start, Vector end, int traceFlags);
    }

    static Vector shortenedEnd(Vector start, Vector end, Vector hitPosition, float hitTime) {
        requireFinite(start);
        requireFinite(end);
        requireFinite(hitPosition);
        if (!Float.isFinite(hitTime) || hitTime < 0 || hitTime > 1) {
            throw new IllegalArgumentException("Invalid native BSP hit time");
        }
        var dx = hitPosition.x() - start.x();
        var dy = hitPosition.y() - start.y();
        var dz = hitPosition.z() - start.z();
        var distance = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        var alpha = ((distance + 5f) * hitTime) / (distance + .0001f);
        if (!Float.isFinite(distance) || !Float.isFinite(alpha)) {
            throw new IllegalArgumentException("BSP shortening exceeds native float range");
        }
        if (alpha >= 1f) {
            alpha = 1f;
        }
        var result = new Vector(
                start.x() + (end.x() - start.x()) * alpha,
                start.y() + (end.y() - start.y()) * alpha,
                start.z() + (end.z() - start.z()) * alpha);
        requireFinite(result);
        return result;
    }

    @Override
    public boolean clear(Vector start, Vector end, int traceFlags) {
        var result = check(start, end, traceFlags);
        if (result.state() == State.UNQUALIFIED) {
            throw new IllegalStateException("Unqualified native shadow query: " + result.evidence());
        }
        return result.state() == State.CLEAR;
    }

    public Result check(Vector start, Vector end, int traceFlags) {
        if (traceFlags != INPUT_FLAGS) {
            throw new IllegalArgumentException("Only Terrain shadow flags 0x197 are qualified");
        }
        requireFinite(start);
        requireFinite(end);
        var dx = end.x() - start.x();
        var dy = end.y() - start.y();
        var dz = end.z() - start.z();
        var square = dx * dx + dy * dy + dz * dz;
        if (!Float.isFinite(square) || square == 0) {
            throw new IllegalArgumentException("Native Level shadow query requires a finite nonzero segment");
        }
        var shortened = end;
        var bsp = currentBsp.checkLine(start, end, 0, MULTI_FLAGS);
        if (!bsp.clear()) {
            if (bsp.hit().isEmpty()) {
                return new Result(State.UNQUALIFIED, "BSP result has no native hit metadata");
            }
            var hit = bsp.hit().orElseThrow();
            if (!NativeBspTrace.doesSingleLineCheckSkip(hit, true, MULTI_FLAGS)) {
                return CURRENT_BSP_BLOCKED;
            }
            if (hit.travel().isEmpty()) {
                return new Result(State.UNQUALIFIED, "Backdrop result has no native travel value");
            }
            shortened = shortenedEnd(
                    start, end, hit.position(), hit.travel().orElseThrow().fraction());
        }
        if (neighborBspEnabled) {
            for (var neighbor : neighborBsps) {
                var result = neighbor.checkLine(start, shortened, 0, MULTI_FLAGS);
                if (!result.clear()) {
                    if (result.hit().isEmpty()
                            || result.hit().orElseThrow().travel().isEmpty()) {
                        return new Result(State.UNQUALIFIED, "Neighbor BSP result has indeterminate native metadata");
                    }
                    return NEIGHBOR_BSP_BLOCKED;
                }
            }
        }
        Result unknown = null;
        for (var domain : actorDomains) {
            var result = Objects.requireNonNull(domain.check(start, shortened, MULTI_FLAGS));
            if (result.state() == State.BLOCKED) {
                return result;
            }
            if (result.state() == State.UNQUALIFIED && unknown == null) {
                unknown = result;
            }
        }
        return unknown == null ? CLEAR : unknown;
    }

    private static void requireFinite(Vector point) {
        if (point == null || !Float.isFinite(point.x()) || !Float.isFinite(point.y()) || !Float.isFinite(point.z())) {
            throw new IllegalArgumentException("Non-finite native Level query point");
        }
    }
}
