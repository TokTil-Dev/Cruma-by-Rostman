package com.rost.cruma.core.world;

import com.rost.cruma.core.world.MapPawn.Animations;
import java.util.SplittableRandom;
import java.util.function.ToDoubleFunction;
import org.jetbrains.annotations.Nullable;

public final class MapPawnPlayer {
    private static final int MOST_PICKS = 10_000;

    private final Animations animations;
    private final ToDoubleFunction<String> lengths;
    private final long seed;
    private final boolean lobby;
    private final boolean canPlay;
    private SplittableRandom random;
    private int index;
    private boolean intervalNext;
    private @Nullable String playing;
    private double start;
    private double length;
    private boolean done;
    private double clock;

    public MapPawnPlayer(Animations animations, ToDoubleFunction<String> lengths, long seed, boolean lobby) {
        this.animations = animations;
        this.lengths = lengths;
        this.seed = seed;
        this.lobby = lobby;
        var playable = false;
        for (var name : animations.list()) {
            playable |= lengths.applyAsDouble(name) > 0;
        }
        canPlay = !animations.list().isEmpty()
                && (playable
                        || !animations.interval().isEmpty() && lengths.applyAsDouble(animations.interval()) > 0
                        || lobby && !animations.first().isEmpty() && lengths.applyAsDouble(animations.first()) > 0);
        reset();
    }

    public record Moment(String sequence, float seconds) {}

    public static boolean isLobby(String map) {
        return !WorldTile.isMapName(map);
    }

    public @Nullable Moment findMoment(double time) {
        if (time < clock) {
            reset();
        }
        clock = time;
        while (true) {
            if (playing != null && (done || time < start + length)) {
                return new Moment(playing, (float) Math.min(time - start, length));
            }
            if (done) {
                return null;
            }
            var end = playing == null ? 0 : start + length;
            var found = false;
            for (var attempt = 0; attempt < MOST_PICKS && !found && canPlay; attempt++) {
                var name = next();
                var seconds = name == null ? 0 : lengths.applyAsDouble(name);
                if (name != null && seconds > 0) {
                    playing = name;
                    start = end;
                    length = seconds;
                    found = true;
                }
            }
            done = !found;
        }
    }

    private void reset() {
        random = new SplittableRandom(seed);
        index = lobby ? -1 : 0;
        intervalNext = false;
        playing = null;
        start = 0;
        length = 0;
        done = false;
        clock = 0;
    }

    private @Nullable String next() {
        var list = animations.list();
        if (intervalNext) {
            intervalNext = false;
            return animations.interval().isEmpty() ? null : animations.interval();
        }
        if (list.isEmpty()) {
            return null;
        }
        if (index == -1 && !animations.first().isEmpty()) {
            index++;
            intervalNext = true;
            return animations.first();
        }
        if (animations.random()) {
            index = random.nextInt(list.size());
        } else {
            index = index + 1 >= list.size() ? 0 : index + 1;
        }
        intervalNext = true;
        return list.get(index);
    }
}
