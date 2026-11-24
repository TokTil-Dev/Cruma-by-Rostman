package com.rost.cruma.core.world;

import com.rost.cruma.core.audio.SoundClip;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.IntSupplier;

public record AmbientSoundList(List<SoundClip> clips, PlayMode mode, Randomizer initialDelay, Randomizer duration) {
    public AmbientSoundList {
        if (clips == null || mode == null || initialDelay == null || duration == null) {
            throw new IllegalArgumentException("a sound list needs its clips, its mode and its waits");
        }
        clips = Collections.unmodifiableList(new ArrayList<>(clips));
    }

    public enum PlayMode {
        STEP,
        CONTINUOUS,
        LOOP_STEP;

        public static PlayMode of(int value) {
            return value == 1 ? CONTINUOUS : value == 2 ? LOOP_STEP : STEP;
        }
    }

    public record Randomizer(int time, int offsetMin, int offsetMax) {
        public static final Randomizer NONE = new Randomizer(0, 0, 0);

        public float draw(IntSupplier rand) {
            var value = (float) (time + offsetMin);
            var range = offsetMax - offsetMin;
            if (range != 0) {
                value += (float) (rand.getAsInt() % range);
            }
            return Math.max(0f, value);
        }
    }
}
