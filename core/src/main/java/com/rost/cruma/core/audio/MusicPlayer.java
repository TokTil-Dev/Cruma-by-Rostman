package com.rost.cruma.core.audio;

import java.nio.file.Path;

public interface MusicPlayer extends AutoCloseable {
    default void play(Path file, float fadeInSeconds, float fadeOutSeconds) {
        play(file, fadeInSeconds, fadeOutSeconds, 0);
    }

    void play(Path file, float fadeInSeconds, float fadeOutSeconds, double fromSeconds);

    void stop(float fadeOutSeconds);

    void setVolume(float volume);

    default void pause(boolean paused) {}

    boolean isPlaying();

    default double getPosition() {
        return Double.NaN;
    }

    boolean isAvailable();

    @Override
    void close();
}
