package com.rost.cruma.core.world;

import com.rost.cruma.core.world.Entity.Texture;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

public record TextureSequence(
        List<Texture> frames,
        List<Integer> nextFrames,
        float maxFrameRate,
        int totalFrameNum,
        int primeCount,
        boolean oneTimeAnimLoop,
        List<String> sourceIds,
        double loadedAtSeconds) {
    public TextureSequence(
            List<Texture> frames,
            List<Integer> nextFrames,
            float maxFrameRate,
            int totalFrameNum,
            int primeCount,
            boolean oneTimeAnimLoop) {
        this(frames, nextFrames, maxFrameRate, totalFrameNum, primeCount, oneTimeAnimLoop, List.of(), 0.0);
    }

    public TextureSequence {
        frames = List.copyOf(frames);
        nextFrames = List.copyOf(nextFrames);
        sourceIds = List.copyOf(sourceIds);
        if (frames.isEmpty()
                || frames.size() != nextFrames.size()
                || totalFrameNum < 0
                || primeCount < 0
                || primeCount > 255
                || !sourceIds.isEmpty() && sourceIds.size() != frames.size()) {
            throw new IllegalArgumentException("invalid texture sequence");
        }
        for (var next : nextFrames) {
            if (next < 0 || next >= frames.size()) {
                throw new IllegalArgumentException("texture frame link is out of range");
            }
        }
    }

    public Clock newClock(double loadedAtSeconds) {
        return new Clock(this, loadedAtSeconds, newFlags(frames.size()));
    }

    public static final class Clocks {
        private final Map<String, Clock> roots = new HashMap<>();
        private final Map<String, LoopFlag> flags = new HashMap<>();
        private final IdentityHashMap<TextureSequence, Clock> anonymous = new IdentityHashMap<>();

        public Clock getClockFor(TextureSequence sequence, double anonymousLoadedAtSeconds) {
            if (sequence.sourceIds().isEmpty()) {
                return anonymous.computeIfAbsent(sequence, s -> s.newClock(anonymousLoadedAtSeconds));
            }
            return roots.computeIfAbsent(sequence.sourceIds().getFirst(), ignored -> {
                var shared = sequence.sourceIds().stream()
                        .map(id -> flags.computeIfAbsent(id, key -> new LoopFlag()))
                        .toArray(LoopFlag[]::new);
                return new Clock(sequence, sequence.loadedAtSeconds(), shared);
            });
        }

        public void clear() {
            roots.clear();
            flags.clear();
            anonymous.clear();
        }
    }

    private static LoopFlag[] newFlags(int count) {
        var flags = new LoopFlag[count];
        for (var i = 0; i < count; i++) {
            flags[i] = new LoopFlag();
        }
        return flags;
    }

    private static final class LoopFlag {
        boolean value;
    }

    private record Timing(
            List<Integer> nextFrames, float maxFrameRate, int totalFrameNum, int primeCount, boolean oneTimeAnimLoop) {}

    public static final class Clock {
        private final Timing sequence;
        private final LoopFlag[] loopFlags;
        private double lastUpdate;
        private float surplus;
        private int primeCurrent;
        private int current = -1;

        private Clock(TextureSequence sequence, double loadedAtSeconds, LoopFlag[] loopFlags) {
            this.sequence = new Timing(
                    sequence.nextFrames(),
                    sequence.maxFrameRate(),
                    sequence.totalFrameNum(),
                    sequence.primeCount(),
                    sequence.oneTimeAnimLoop());
            this.loopFlags = loopFlags;
            lastUpdate = loadedAtSeconds;
        }

        public int frameAt(double seconds) {
            if (lastUpdate < seconds) {
                tick(seconds - lastUpdate);
            }
            lastUpdate = seconds;
            return current < 0 ? 0 : current;
        }

        public void startOneTimeLoop() {
            loopFlags[0].value = !loopFlags[0].value;
        }

        public void timeReset() {
            current = -1;
        }

        private void tick(double delta) {
            if (sequence.primeCount() != 0) {
                current = -1;
                while (primeCurrent < sequence.primeCount()) {
                    primeCurrent++;
                    advance();
                }
            }
            if (sequence.maxFrameRate() == 0f) {
                advance();
            } else if (sequence.totalFrameNum() == 0) {
                current = -1;
            } else {
                var rate = sequence.maxFrameRate();
                rate = !(rate >= 0.1f) ? 0.1f : Math.min(rate, 100f);
                var step = (double) (1f / rate);
                var period = sequence.totalFrameNum() * step;
                var accumulated = (double) surplus + delta;
                accumulated -= (int) (accumulated / period) * period;
                while (accumulated >= step) {
                    advance();
                    accumulated -= step;
                }
                surplus = (float) accumulated;
            }
        }

        private void advance() {
            current = sequence.nextFrames().get(current < 0 ? 0 : current);
            if (sequence.oneTimeAnimLoop()) {
                if (loopFlags[current].value == loopFlags[0].value) {
                    current = -1;
                } else {
                    loopFlags[current].value = !loopFlags[current].value;
                }
            }
        }
    }
}
