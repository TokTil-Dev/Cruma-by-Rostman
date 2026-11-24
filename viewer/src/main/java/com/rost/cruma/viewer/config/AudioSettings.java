package com.rost.cruma.viewer.config;

import java.io.IOException;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.jetbrains.annotations.Nullable;

public final class AudioSettings {
    private final @Nullable ConfigLoader loader;
    private Values kept;

    private AudioSettings(@Nullable ConfigLoader loader, Values start) {
        this.loader = loader;
        kept = start;
    }

    public static AudioSettings in(ConfigLoader loader, Values start) {
        return new AudioSettings(loader, start);
    }

    public static AudioSettings session(Values start) {
        return new AudioSettings(null, start);
    }

    public void follow(Values values) {
        if (values.equals(kept)) {
            return;
        }
        var before = kept.settings();
        kept = values;
        if (loader == null) {
            return;
        }
        try {
            loader.reload();
            var model = new OptionsModel(loader);
            values.settings().forEach((path, value) -> {
                if (!value.equals(before.get(path))) {
                    model.set(path, value);
                }
            });
            if (model.hasChanges()) {
                model.save();
            }
        } catch (IOException | RuntimeException failure) {
            LogManager.getLogger(AudioSettings.class).warn("Could not save the sound settings: {}", failure.toString());
        }
    }

    public record Values(
            boolean musicEnabled,
            float musicVolume,
            float musicWaitSeconds,
            float musicIdleWaitSeconds,
            float musicFadeSeconds,
            boolean soundEnabled,
            float soundVolume,
            int soundMaxVoices) {
        private Map<String, Object> settings() {
            return Map.of(
                    "viewer.world.music.enabled", musicEnabled,
                    "viewer.world.music.volume", share(musicVolume),
                    "viewer.world.music.wait_seconds", seconds(musicWaitSeconds),
                    "viewer.world.music.idle_wait_seconds", seconds(musicIdleWaitSeconds),
                    "viewer.world.music.fade_seconds", seconds(musicFadeSeconds),
                    "viewer.world.sound.enabled", soundEnabled,
                    "viewer.world.sound.volume", share(soundVolume),
                    "viewer.world.sound.max_voices", soundMaxVoices);
        }

        private static double share(float percent) {
            return Math.round(percent * 10.0) / 1000.0;
        }

        private static double seconds(float value) {
            return Math.round(value * 10.0) / 10.0;
        }
    }
}
