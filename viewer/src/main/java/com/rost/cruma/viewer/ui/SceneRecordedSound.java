package com.rost.cruma.viewer.ui;

import com.rost.cruma.core.audio.WavWriter;
import com.rost.cruma.core.export.SceneAudioMix;
import com.rost.cruma.core.export.SceneAudioMix.Mix;
import com.rost.cruma.core.world.GameScenes.Scene;
import com.rost.cruma.core.world.MapEventState;
import com.rost.cruma.viewer.application.SceneRecording;
import com.rost.cruma.viewer.application.SceneVideoFile;
import com.rost.cruma.viewer.config.Config.Viewer.World;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class SceneRecordedSound {
    private SceneRecordedSound() {}

    public static CompletableFuture<List<String>> mix(
            Scene scene, World world, float musicFade, float hour, MapEventState events, SceneRecording recording) {
        var mixed = new CompletableFuture<List<String>>();
        Thread.ofPlatform().name("cruma-scene-sound").daemon().start(() -> {
            try {
                var timeline = SceneSampler.sound(
                        scene,
                        recording.getTiles(),
                        world.clientPath(),
                        world.variant(),
                        world.rendering().textureQuality(),
                        musicFade,
                        hour,
                        events,
                        recording::isStopped);
                var mix = SceneAudioMix.mix(
                        timeline,
                        recording.getFirstSeconds(),
                        recording.getLastSeconds(scene.seconds()),
                        recording::isStopped);
                write(mix, SceneVideoFile.sound(recording.getFile()));
                mixed.complete(mix.skipped());
            } catch (IOException | RuntimeException failure) {
                mixed.completeExceptionally(failure);
            }
        });
        return mixed;
    }

    private static void write(Mix mix, Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, WavWriter.encode(mix.clip()));
    }
}
