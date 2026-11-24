package com.rost.cruma.viewer.render;

import com.rost.cruma.core.world.GameScenes.Music;
import org.jetbrains.annotations.Nullable;

public interface SceneAudio {
    record Cue(Music music, double from) {}

    @Nullable
    Music takeMusic();

    default @Nullable Cue takeCue() {
        var music = takeMusic();
        return music == null ? null : new Cue(music, 0);
    }

    boolean isPlaying();

    boolean hasRunOut();

    long getRevision();

    boolean stopsAmbient();

    boolean stopsEffects();

    final class Switch implements SceneAudio {
        private final SceneAudio first;
        private SceneAudio current;

        public Switch(SceneAudio first) {
            this.first = first;
            current = first;
        }

        public void follow(@Nullable SceneAudio scene) {
            current = scene == null ? first : scene;
        }

        @Override
        public @Nullable Music takeMusic() {
            return current.takeMusic();
        }

        @Override
        public @Nullable Cue takeCue() {
            return current.takeCue();
        }

        @Override
        public boolean isPlaying() {
            return current.isPlaying();
        }

        @Override
        public boolean hasRunOut() {
            return current.hasRunOut();
        }

        @Override
        public long getRevision() {
            return current.getRevision();
        }

        @Override
        public boolean stopsAmbient() {
            return current.stopsAmbient();
        }

        @Override
        public boolean stopsEffects() {
            return current.stopsEffects();
        }
    }
}
