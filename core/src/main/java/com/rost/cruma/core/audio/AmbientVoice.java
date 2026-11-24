package com.rost.cruma.core.audio;

public interface AmbientVoice {
    void set(float gain, float pan);

    void stop();

    boolean isFinished();
}
