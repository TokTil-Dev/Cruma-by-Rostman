package com.rost.cruma.viewer.ui;

import com.rost.cruma.core.export.ExportScale;
import com.rost.cruma.core.export.LocationExport.Progress;
import com.rost.cruma.core.export.LocationExport.Settings;
import com.rost.cruma.core.export.SceneExport;
import com.rost.cruma.core.export.SceneExport.Result;
import com.rost.cruma.core.world.GameScenes.Scene;
import com.rost.cruma.core.world.MapEventState;
import com.rost.cruma.core.world.TextureQuality;
import com.rost.cruma.core.world.WorldVariant;
import com.rost.cruma.viewer.application.SceneRequest;
import com.rost.cruma.viewer.render.bgfx.ParticleStill;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;

public final class SceneBlenderJob {
    private static final int TERRAIN_SIZE = 4096;
    private static final double PLAYING = .1;
    private static final int HOURS = 24;

    private SceneBlenderJob() {}

    public record Plan(
            Path client,
            WorldVariant variant,
            TextureQuality quality,
            Path parent,
            ExportScale scale,
            MapEventState events,
            int hour,
            float musicFade) {}

    public static Result run(Scene scene, Plan plan, Progress progress, BooleanSupplier stopped) throws IOException {
        Progress playing = (text, done) -> progress.update(text, done * PLAYING);
        Progress writing = (text, done) -> progress.update(text, PLAYING + done * (1 - PLAYING));
        var request = SceneRequest.close(scene, plan.client(), plan.variant());
        var lack = request.lack(scene);
        if (lack != null) {
            throw new IOException(lack);
        }
        var tiles = request.tiles();
        var events = plan.events();
        var exact = scene.hourOr(plan.hour());
        var time = wholeHour(exact);
        var location = new Settings(
                        TERRAIN_SIZE,
                        true,
                        false,
                        null,
                        time,
                        plan.scale(),
                        true,
                        true,
                        false,
                        true,
                        events.isEmpty() ? "" : events.describe(),
                        events.zoneStates())
                .withClocks(events.clocks());
        var timeline = SceneSampler.sample(
                scene,
                tiles,
                plan.client(),
                plan.variant(),
                plan.quality(),
                plan.musicFade(),
                (float) exact,
                events,
                playing,
                stopped);
        return SceneExport.export(
                plan.client(),
                plan.variant(),
                timeline,
                tiles,
                plan.parent(),
                location,
                new ParticleStill(events, time).litBy(plan.client()),
                writing,
                stopped);
    }

    static int wholeHour(double hour) {
        return Math.floorMod((int) Math.floor(hour), HOURS);
    }
}
