package com.rost.cruma.viewer.ui;

import static com.rost.cruma.viewer.ui.GeodataWidgets.space;

import com.rost.cruma.core.export.SceneExport.Result;
import com.rost.cruma.core.world.GameScenes.Scene;
import com.rost.cruma.core.world.TextureQuality;
import com.rost.cruma.core.world.WorldVariant;
import com.rost.cruma.viewer.config.ExportSettings;
import com.rost.cruma.viewer.config.MapEventChoices;
import com.rost.cruma.viewer.launch.BlenderApp;
import com.rost.cruma.viewer.launch.FileManager;
import com.rost.cruma.viewer.ui.HudWidgets.Glyph;
import com.rost.cruma.viewer.ui.SceneBlenderJob.Plan;
import imgui.ImGui;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;
import org.jetbrains.annotations.Nullable;

final class SceneBlenderExport {
    static final String LABEL = "Export to Blender";

    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        var thread = new Thread(runnable, "cruma-scene-export");
        thread.setDaemon(true);
        return thread;
    });
    private final Path client;
    private final WorldVariant variant;
    private final TextureQuality quality;
    private final ExportSettings settings;
    private final MapEventChoices mapEvents;
    private final IntSupplier hour;
    private final DoubleSupplier musicFade;
    private Consumer<Scene> work = this::start;
    private @Nullable CompletableFuture<Result> running;
    private volatile String stage = "";
    private volatile double fraction;
    private volatile boolean stopping;
    private String exporting = "";
    private String outcome = "";
    private boolean failed;
    private @Nullable Path written;

    SceneBlenderExport(
            Path client,
            WorldVariant variant,
            TextureQuality quality,
            ExportSettings settings,
            MapEventChoices mapEvents,
            IntSupplier hour,
            DoubleSupplier musicFade) {
        this.client = client;
        this.variant = variant;
        this.quality = quality;
        this.settings = settings;
        this.mapEvents = mapEvents;
        this.hour = hour;
        this.musicFade = musicFade;
    }

    static String missing(@Nullable Scene scene) {
        if (scene == null) {
            return "Play a scene first: the export takes the scene chosen last.";
        }
        return "";
    }

    static String summary(Result result) {
        return String.format(
                Locale.ROOT,
                "Exported %s: %d %s, %d %s, %d %s.",
                result.folder().getFileName(),
                result.actors(),
                result.actors() == 1 ? "actor" : "actors",
                result.sounds(),
                result.sounds() == 1 ? "sound" : "sounds",
                result.effects(),
                result.effects() == 1 ? "mark of an effect" : "marks of effects");
    }

    boolean isBusy() {
        return running != null;
    }

    void draw(@Nullable Scene scene) {
        poll();
        space(GeodataWidgets.ROW);
        if (running != null) {
            if (GeodataWidgets.progress(
                    "scene-export", "Exporting " + exporting, stopping ? "Stopping…" : stage, fraction, "Stop")) {
                stopping = true;
            }
            return;
        }
        var missing = missing(scene);
        ImGui.beginDisabled(!missing.isEmpty());
        if (GeodataWidgets.actionButton("scene-export", Glyph.FILE, LABEL, ImGui.getContentRegionAvailX(), false)) {
            work.accept(scene);
        }
        ImGui.endDisabled();
        GeodataWidgets.tip(
                !missing.isEmpty()
                        ? missing
                        : "Writes " + scene.name()
                                + " into a folder for Blender: its tiles, its NPCs with their animations, its camera,"
                                + " its sounds and marks of its effects.");
        if (outcome.isEmpty()) {
            return;
        }
        space(GeodataWidgets.ROW);
        GeodataWidgets.note(outcome, failed ? GeodataWidgets.BLOCKED : GeodataWidgets.OPEN);
        if (written != null) {
            drawDone(written);
        }
    }

    private void drawDone(Path folder) {
        space(GeodataWidgets.ROW);
        var blender = BlenderApp.canOpen(folder) ? BlenderApp.find() : null;
        var columns = blender == null ? 1 : 2;
        if (blender != null && GeodataWidgets.rowButton("Open in Blender", 0, columns)) {
            try {
                BlenderApp.open(blender, folder);
                outcome = "Blender is opening " + folder.getFileName() + ". A large export takes a while.";
            } catch (IOException failure) {
                outcome = "Blender did not start: " + failure.getMessage();
            }
        }
        if (GeodataWidgets.rowButton("Show folder", columns - 1, columns)) {
            FileManager.tryOpenFolder(folder);
        }
    }

    private void start(Scene scene) {
        var plan = new Plan(
                client,
                variant,
                quality,
                settings.getFolder(),
                settings.getScale(),
                mapEvents.of(client),
                hour.getAsInt(),
                (float) musicFade.getAsDouble());
        exporting = scene.name();
        stage = "Starting";
        fraction = 0;
        stopping = false;
        outcome = "";
        written = null;
        running = CompletableFuture.supplyAsync(
                () -> {
                    try {
                        return SceneBlenderJob.run(
                                scene,
                                plan,
                                (text, done) -> {
                                    stage = text;
                                    fraction = done;
                                },
                                () -> stopping);
                    } catch (CancellationException stopped) {
                        throw stopped;
                    } catch (Exception failure) {
                        throw new IllegalStateException(failure.getMessage(), failure);
                    }
                },
                worker);
    }

    private void poll() {
        var job = running;
        if (job == null || !job.isDone()) {
            return;
        }
        running = null;
        try {
            var result = job.join();
            failed = false;
            written = result.folder();
            outcome = summary(result) + skipped(result.skipped());
        } catch (CancellationException | CompletionException failure) {
            failed = true;
            written = null;
            outcome = isStopped(failure) ? "Stopped. Nothing was written." : "The export failed: " + reason(failure);
        }
    }

    private static String skipped(List<String> names) {
        return names.isEmpty() ? "" : " Left out, as they could not be read: " + String.join(", ", names) + ".";
    }

    private static boolean isStopped(RuntimeException failure) {
        return failure instanceof CancellationException || failure.getCause() instanceof CancellationException;
    }

    private static String reason(RuntimeException failure) {
        var cause = failure.getCause();
        var message = cause == null ? failure.getMessage() : cause.getMessage();
        return message == null ? failure.toString() : message;
    }
}
