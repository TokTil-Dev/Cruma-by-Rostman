package com.rost.cruma.core.export;

import com.rost.cruma.core.export.SceneTimeline.Place;
import com.rost.cruma.core.export.SceneTimeline.Strip;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;
import org.jetbrains.annotations.Nullable;

public final class SceneTrack {
    private static final double SAME = 1e-3;

    private final List<Place> places = new ArrayList<>();
    private final List<Strip> strips = new ArrayList<>();
    private @Nullable Run run;

    public static <T> void keep(List<T> keys, T key, BiPredicate<T, T> same) {
        var count = keys.size();
        if (count >= 2 && same.test(keys.get(count - 1), key) && same.test(keys.get(count - 2), key)) {
            keys.set(count - 1, key);
        } else {
            keys.add(key);
        }
    }

    public void place(double seconds, float x, float y, float z, float yaw) {
        keep(places, new Place(seconds, x, y, z, yaw), Place::sameAs);
    }

    public void plays(double seconds, String sequence, float moment, float length) {
        var at = Math.min((double) moment, length);
        if (run != null && run.goesOn(seconds, sequence, at)) {
            return;
        }
        end();
        run = new Run(sequence, seconds, at, length);
    }

    public List<Place> getPlaces() {
        return List.copyOf(places);
    }

    public List<Strip> getStrips() {
        end();
        return List.copyOf(strips);
    }

    private void end() {
        if (run != null) {
            run.addTo(strips);
            run = null;
        }
    }

    private static final class Run {
        private final String sequence;
        private final double start;
        private final double from;
        private final double length;
        private double seconds;
        private double moment;
        private double played;
        private double rate = Double.NaN;

        Run(String sequence, double start, double from, double length) {
            this.sequence = sequence;
            this.start = start;
            this.from = from;
            this.length = length;
            seconds = start;
            moment = from;
        }

        boolean goesOn(double now, String name, double at) {
            if (!sequence.equals(name)) {
                return false;
            }
            var passed = now - seconds;
            var step = at - moment;
            if (step < -length / 2) {
                step += length;
            }
            if (step < -SAME || passed <= 0) {
                return false;
            }
            if (Double.isNaN(rate)) {
                rate = step < SAME ? 0 : step / passed;
            } else if (Math.abs(step - rate * passed) > SAME) {
                return false;
            }
            seconds = now;
            moment = at;
            played += step;
            return true;
        }

        void addTo(List<Strip> strips) {
            if (played < SAME) {
                if (!strips.isEmpty()) {
                    var last = strips.getLast();
                    if (last.sequence().equals(sequence) && last.repeat() == 1 && Math.abs(last.to() - from) < SAME) {
                        return;
                    }
                }
                strips.add(new Strip(sequence, start, seconds, from, from, 1));
            } else if (from + played <= length + SAME) {
                strips.add(new Strip(sequence, start, seconds, from, Math.min(from + played, length), 1));
            } else if (from < SAME || length - from < SAME) {
                strips.add(new Strip(sequence, start, seconds, 0, length, played / length));
            } else {
                var head = length - from;
                var turn = start + head / rate;
                strips.add(new Strip(sequence, start, turn, from, length, 1));
                strips.add(new Strip(sequence, turn, seconds, 0, length, (played - head) / length));
            }
        }
    }
}
