package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.NativeLevelShadowQuery.State;
import com.rost.cruma.core.editing.P152BspReceiverModel.Receiver;
import com.rost.cruma.core.editing.P152BspReceiverModel.SavedMask;
import com.rost.cruma.core.editing.P152BspVisibilityReplay.Candidate;
import com.rost.cruma.core.editing.P152BspVisibilityReplay.Comparison;
import com.rost.cruma.core.editing.P152BspVisibilityReplay.Grid;
import com.rost.cruma.core.editing.P152BspVisibilityReplay.Query;
import com.rost.cruma.core.editing.P152BspVisibilityReplay.Sampling;
import com.rost.cruma.core.editing.P152FreshHashQuery.Ray;
import com.rost.cruma.core.editing.ShadowTriangleQuery.Hit;
import com.rost.cruma.core.editing.ShadowTriangleQuery.Sides;
import com.rost.cruma.core.editing.ShadowTriangleQuery.Triangle;
import com.rost.cruma.core.editing.TerrainNativeCodec.Vector;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

final class P152BspShadowWitnesses {
    private static final int MAX_WITNESSES_PER_DIRECTION = 16;

    private final ShadowTriangleQuery triangles;

    P152BspShadowWitnesses(ShadowTriangleQuery triangles) {
        this.triangles = Objects.requireNonNull(triangles);
    }

    record Intersection(Hit hit, Triangle triangle) {}

    record Witness(int x, int y, Ray ray, Optional<Intersection> intersection) {}

    record Blocker(int owner, int part, boolean front) {}

    record BlockerCount(Blocker blocker, int pixels, Witness first) {}

    record Result(
            Comparison comparison,
            int addedShadows,
            int missingShadows,
            int matchedClear,
            int matchedBlocked,
            List<Witness> added,
            List<Witness> missing,
            List<BlockerCount> blockers) {
        Result {
            added = List.copyOf(added);
            missing = List.copyOf(missing);
            blockers = List.copyOf(blockers);
        }
    }

    private record Sample(Ray ray, Optional<Intersection> intersection) {}

    static final class Capture {
        private final Grid grid;
        private final Candidate candidate;
        private final List<Sample> samples;

        private Capture(Grid grid, Candidate candidate, List<Sample> samples) {
            if (samples.size() != grid.width() * grid.height()) {
                throw new IllegalArgumentException("Incomplete BSP witness sample batch");
            }
            this.grid = grid;
            this.candidate = candidate;
            this.samples = List.copyOf(samples);
        }

        Candidate getCandidate() {
            return candidate;
        }

        Result compare(byte[] expected, BooleanSupplier cancelled) {
            Objects.requireNonNull(cancelled);
            var frozen = expected.clone();
            var comparison = candidate.compare(frozen, cancelled);
            int addedCount = 0, missingCount = 0, clear = 0, blocked = 0;
            var added = new ArrayList<Witness>();
            var missing = new ArrayList<Witness>();
            var groups = new LinkedHashMap<Blocker, BlockerCount>();
            for (int i = 0; i < samples.size(); i++) {
                P152FreshHashQuery.checkCancelled(cancelled);
                int x = i % grid.width(), y = i / grid.width();
                boolean visible = (frozen[y * grid.stride() + x / 8] & (1 << (x & 7))) != 0;
                var sample = samples.get(i);
                if (visible && sample.intersection().isPresent()) {
                    addedCount++;
                    var witness = new Witness(grid.minX() + x, grid.minY() + y, sample.ray(), sample.intersection());
                    if (added.size() < MAX_WITNESSES_PER_DIRECTION) {
                        added.add(witness);
                    }
                    var hit = sample.intersection().orElseThrow().hit();
                    var key = new Blocker(hit.owner(), hit.part(), hit.front());
                    var old = groups.get(key);
                    groups.put(
                            key,
                            new BlockerCount(
                                    key, old == null ? 1 : old.pixels() + 1, old == null ? witness : old.first()));
                } else if (!visible && sample.intersection().isEmpty()) {
                    missingCount++;
                    if (missing.size() < MAX_WITNESSES_PER_DIRECTION) {
                        missing.add(new Witness(grid.minX() + x, grid.minY() + y, sample.ray(), Optional.empty()));
                    }
                } else if (visible) {
                    clear++;
                } else {
                    blocked++;
                }
            }
            var blockers = new ArrayList<>(groups.values());
            blockers.sort(Comparator.comparingInt(BlockerCount::pixels)
                    .reversed()
                    .thenComparingInt(b -> b.blocker().owner())
                    .thenComparingInt(b -> b.blocker().part())
                    .thenComparing(b -> b.blocker().front()));
            P152FreshHashQuery.checkCancelled(cancelled);
            return new Result(comparison, addedCount, missingCount, clear, blocked, added, missing, blockers);
        }
    }

    Capture generate(Grid grid, Vector light, Sampling sampling, Sides sides, BooleanSupplier cancelled)
            throws IOException {
        var samples = new ArrayList<Sample>();
        var candidate = P152BspVisibilityReplay.generate(
                grid, light, sampling, newRecordingQuery(samples, sides, cancelled), cancelled);
        return new Capture(grid, candidate, samples);
    }

    Capture generateProjected(Receiver receiver, SavedMask mask, Vector light, Sides sides, BooleanSupplier cancelled)
            throws IOException {
        var grid = Grid.capture(receiver, mask);
        var samples = new ArrayList<Sample>();
        var candidate = P152BspVisibilityReplay.generateProjected(
                receiver, mask, light, 0, newRecordingQuery(samples, sides, cancelled), cancelled);
        return new Capture(grid, candidate, samples);
    }

    private Query newRecordingQuery(List<Sample> samples, Sides sides, BooleanSupplier cancelled) {
        Objects.requireNonNull(sides);
        Objects.requireNonNull(cancelled);
        return rays -> {
            var states = new ArrayList<State>(rays.size());
            for (var ray : rays) {
                P152FreshHashQuery.checkCancelled(cancelled);
                var hit = triangles.findFirstHit(ray, sides, cancelled);
                samples.add(new Sample(
                        ray, hit.map(value -> new Intersection(value, triangles.getTriangle(value.triangle())))));
                states.add(hit.isPresent() ? State.BLOCKED : State.CLEAR);
            }
            return states;
        };
    }
}
