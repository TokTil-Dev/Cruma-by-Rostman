package com.rost.cruma.core.audio;

public interface AmbientOutput extends AutoCloseable {
    AmbientVoice play(SoundClip clip, boolean loop, float pitch, float gain, float pan);

    default AmbientVoice play(SoundClip clip, boolean loop, float pitch, float gain, float pan, double fromSeconds) {
        return play(clip, loop, pitch, gain, pan);
    }

    void setVolume(float volume);

    default void pause(boolean paused) {}

    boolean isAvailable();

    int getActiveVoices();

    @Override
    void close();
}
