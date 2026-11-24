package com.rost.cruma.core.world;

import com.rost.cruma.core.world.MapPawnPlayer.Moment;
import java.util.function.ToDoubleFunction;
import org.jetbrains.annotations.Nullable;

public final class MapPawnChannel {
    public static final double TICK_SECONDS = 1.0 / 30;

    private static final int MOST_ENDS = 10_000;

    private final ToDoubleFunction<String> lengths;
    private @Nullable String sequence;
    private double start;
    private double length;
    private float rate = 1;
    private boolean looping;
    private boolean ended;
    private double endedAt;
    private double clock;

    public MapPawnChannel(ToDoubleFunction<String> lengths) {
        this.lengths = lengths;
    }

    public boolean play(String name, float speed) {
        return start(name, speed, false);
    }

    public boolean loop(String name, float speed) {
        if (looping && name.equalsIgnoreCase(sequence) && lengths.applyAsDouble(name) > 0) {
            var seconds = getSeconds();
            rate = speed;
            start = speed > 0 ? clock - seconds / speed : clock;
            return true;
        }
        return start(name, speed, true);
    }

    public void runTo(double time, Runnable animEnd) {
        for (var ends = 0; ends < MOST_ENDS && sequence != null && !looping && rate > 0; ends++) {
            var end = ended ? endedAt + TICK_SECONDS : start + length / rate;
            if (end > time) {
                break;
            }
            clock = Math.max(clock, end);
            ended = true;
            endedAt = clock;
            animEnd.run();
        }
        clock = Math.max(clock, time);
    }

    public @Nullable Moment getMoment() {
        return sequence == null ? null : new Moment(sequence, (float) getSeconds());
    }

    public double getClock() {
        return clock;
    }

    private boolean start(String name, float speed, boolean loops) {
        var seconds = lengths.applyAsDouble(name);
        if (seconds <= 0) {
            return false;
        }
        sequence = name;
        length = seconds;
        rate = speed;
        looping = loops;
        ended = false;
        start = clock;
        return true;
    }

    private double getSeconds() {
        var played = Math.max(0, clock - start) * Math.max(0, rate);
        if (looping) {
            return played % length;
        }
        return Math.min(played, length);
    }
}
