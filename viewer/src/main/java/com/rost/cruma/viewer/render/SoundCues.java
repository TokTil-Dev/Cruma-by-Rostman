package com.rost.cruma.viewer.render;

import com.rost.cruma.core.world.EmitterSound;
import glm_.vec3.Vec3;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.Nullable;

public final class SoundCues {
    private static final int MAX_PENDING = 256;

    private final List<Cue> played = new ArrayList<>();
    private final List<String> stopped = new ArrayList<>();

    public record Cue(String owner, int index, @Nullable Vec3 position, EmitterSound sound, double from) {}

    public void play(String owner, int index, Vec3 position, EmitterSound sound) {
        play(owner, index, position, sound, 0);
    }

    public void play(String owner, int index, @Nullable Vec3 position, EmitterSound sound, double from) {
        if (played.size() < MAX_PENDING) {
            played.add(new Cue(owner, index, position == null ? null : new Vec3(position), sound, from));
        }
    }

    public void playHere(String owner, int index, EmitterSound sound) {
        play(owner, index, null, sound, 0);
    }

    public void stop(String owner) {
        played.removeIf(cue -> cue.owner().equals(owner));
        if (stopped.size() < MAX_PENDING && !stopped.contains(owner)) {
            stopped.add(owner);
        }
    }

    public List<Cue> drainPlayed() {
        if (played.isEmpty()) {
            return List.of();
        }
        var drained = new ArrayList<>(played);
        played.clear();
        return drained;
    }

    public List<String> drainStopped() {
        if (stopped.isEmpty()) {
            return List.of();
        }
        var drained = new ArrayList<>(stopped);
        stopped.clear();
        return drained;
    }
}
