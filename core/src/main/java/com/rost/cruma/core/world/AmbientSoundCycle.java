package com.rost.cruma.core.world;

import com.rost.cruma.core.audio.SoundClip;
import com.rost.cruma.core.world.AmbientSoundList.PlayMode;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;
import org.jetbrains.annotations.Nullable;

public final class AmbientSoundCycle {
    private final AmbientSoundList settings;
    private final IntSupplier rand;
    private final List<SoundClip> sounds;
    private State state = State.EXIT;
    private int playIndex = -1;
    private float remainDuration;

    public AmbientSoundCycle(AmbientSoundList settings, IntSupplier rand) {
        this.settings = settings;
        this.rand = rand;
        this.sounds = new ArrayList<>(settings.clips());
    }

    public enum State {
        EXIT,
        ENTER,
        INITIAL_DELAY,
        DELAY,
        FINISH,
        CAN_RUN,
        RUN
    }

    public State getState() {
        return state;
    }

    public int getPlayIndex() {
        return playIndex;
    }

    public float getRemainDuration() {
        return remainDuration;
    }

    public void overlap(double seconds) {
        var passed = seconds;
        if (state == State.EXIT) {
            state = State.ENTER;
            if (isLoop()) {
                selectSound();
            }
            remainDuration = settings.initialDelay().draw(rand);
            state = State.INITIAL_DELAY;
            passed = 0d;
        }
        if (state == State.INITIAL_DELAY || state == State.DELAY) {
            remainDuration = (float) (remainDuration - passed);
            if (remainDuration <= 0f) {
                remainDuration = 0f;
                state = State.CAN_RUN;
                if (!isLoop()) {
                    selectSound();
                }
            }
        }
    }

    public void nonOverlap() {
        if (state != State.EXIT) {
            playIndex = -1;
            remainDuration = 0f;
            state = State.EXIT;
        }
    }

    public void onPlay() {
        state = State.RUN;
    }

    public void onStop() {
        if (state != State.CAN_RUN && state != State.RUN) {
            return;
        }
        switch (settings.mode()) {
            case STEP -> state = State.FINISH;
            case CONTINUOUS -> {
                remainDuration = settings.duration().draw(rand);
                state = State.DELAY;
            }
            case LOOP_STEP -> state = State.CAN_RUN;
        }
    }

    public boolean canRun() {
        return state == State.CAN_RUN;
    }

    public boolean isLoop() {
        return settings.mode() == PlayMode.LOOP_STEP;
    }

    public boolean validateSelectedSound() {
        if (isValidPlayIndex()) {
            if (sounds.get(playIndex) != null) {
                return true;
            }
            sounds.remove(playIndex);
        }
        selectSound();
        return false;
    }

    public @Nullable SoundClip getSelectedSound() {
        return isValidPlayIndex() ? sounds.get(playIndex) : null;
    }

    private void selectSound() {
        playIndex = sounds.isEmpty() ? -1 : rand.getAsInt() % sounds.size();
    }

    private boolean isValidPlayIndex() {
        return playIndex >= 0 && playIndex < sounds.size();
    }
}
