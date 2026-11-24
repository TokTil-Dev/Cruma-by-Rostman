package com.rost.cruma.viewer.ui;

import static com.rost.cruma.viewer.ui.GeodataWidgets.space;

import com.rost.cruma.core.world.GameScenes.Scene;
import com.rost.cruma.core.world.SceneDocument;
import com.rost.cruma.core.world.WorldVariant;
import com.rost.cruma.viewer.application.RecordLine;
import com.rost.cruma.viewer.application.RecordingProcess;
import com.rost.cruma.viewer.application.SceneRequest;
import com.rost.cruma.viewer.application.SceneVideoFile;
import com.rost.cruma.viewer.config.ExportSettings;
import com.rost.cruma.viewer.config.MapEventChoices;
import com.rost.cruma.viewer.config.RecordOptions;
import com.rost.cruma.viewer.editing.SceneFileStore;
import com.rost.cruma.viewer.launch.FfmpegApp;
import com.rost.cruma.viewer.launch.FileManager;
import com.rost.cruma.viewer.launch.OwnProgram;
import com.rost.cruma.viewer.ui.HudWidgets.Glyph;
import imgui.ImGui;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

final class SceneVideoExport {
    static final String LABEL = "Export MP4";

    private static final Logger log = LogManager.getLogger(SceneVideoExport.class);

    private final Path client;
    private final WorldVariant variant;
    private final Path configFolder;
    private final @Nullable Path scripts;
    private final ExportSettings settings;
    private final MapEventChoices mapEvents;
    private final DoubleSupplier hour;
    private Supplier<@Nullable Path> ffmpeg;
    private Function<List<String>, List<String>> program = OwnProgram::command;
    private @Nullable RecordingProcess running;
    private String exporting = "";
    private volatile String stage = "";
    private volatile double fraction = -1;
    private volatile boolean stopping;
    private String outcome = "";
    private boolean failed;
    private @Nullable Path written;

    SceneVideoExport(
            Path client,
            WorldVariant variant,
            Path configFolder,
            @Nullable Path scripts,
            ExportSettings settings,
            MapEventChoices mapEvents,
            DoubleSupplier hour) {
        this(client, variant, configFolder, scripts, settings, mapEvents, hour, FfmpegApp::find);
    }

    SceneVideoExport(
            Path client,
            WorldVariant variant,
            Path configFolder,
            @Nullable Path scripts,
            ExportSettings settings,
            MapEventChoices mapEvents,
            DoubleSupplier hour,
            Supplier<@Nullable Path> ffmpeg) {
        this.client = client;
        this.variant = variant;
        this.configFolder = configFolder;
        this.scripts = scripts;
        this.settings = settings;
        this.mapEvents = mapEvents;
        this.hour = hour;
        this.ffmpeg = ffmpeg;
    }

    static String missing(@Nullable Scene scene, @Nullable Path ffmpeg) {
        if (ffmpeg == null) {
            return FfmpegApp.installHint();
        }
        return scene == null ? "Play a scene first: the export takes the scene chosen last." : "";
    }

    static String summary(Path file, int frames, int rate, int width, int height, long bytes) {
        return String.format(
                Locale.ROOT,
                "Exported %s: %.1f s at %dx%d, %d frames a second, %d MB.",
                file.getFileName(),
                (double) frames / rate,
                width,
                height,
                rate,
                Math.max(1, Math.round(bytes / 1e6)));
    }

    static List<String> sceneArguments(Scene scene, Path temporary) throws IOException {
        if (scene.paths().isEmpty() && scene.seed() == null) {
            return List.of("--scene", scene.name());
        }
        var current = temporary.resolve(SceneFileStore.safeName(scene.name()) + SceneFileStore.EXTENSION);
        SceneFileStore.write(current, SceneDocument.of(scene));
        return List.of("--scene-file", current.toString());
    }

    boolean isBusy() {
        return running != null;
    }

    void draw(@Nullable Scene scene) {
        poll();
        space(GeodataWidgets.ROW);
        if (running != null) {
            if (GeodataWidgets.progress(
                    "scene-video", "Recording " + exporting, stopping ? "Stopping…" : stage, fraction, "Stop")) {
                stop();
            }
            return;
        }
        var missing = missing(scene, ffmpeg.get());
        ImGui.beginDisabled(!missing.isEmpty());
        if (GeodataWidgets.actionButton(
                "scene-video", Glyph.CLAPPERBOARD, LABEL, ImGui.getContentRegionAvailX(), false)) {
            start(scene);
        }
        ImGui.endDisabled();
        GeodataWidgets.tip(
                !missing.isEmpty()
                        ? missing
                        : "Writes " + scene.name()
                                + " as a video with its sound, its fades, its bars and its lines. It draws by itself,"
                                + " so this window stays free.");
        if (outcome.isEmpty()) {
            return;
        }
        space(GeodataWidgets.ROW);
        GeodataWidgets.note(outcome, failed ? GeodataWidgets.BLOCKED : GeodataWidgets.OPEN);
        if (written != null) {
            drawDone(written);
        }
    }

    void close() {
        var recording = running;
        if (recording != null) {
            running = null;
            recording.end();
        }
    }

    private void drawDone(Path file) {
        space(GeodataWidgets.ROW);
        if (GeodataWidgets.rowButton("Open video", 0, 2)) {
            FileManager.tryOpen(file);
        }
        if (GeodataWidgets.rowButton("Show folder", 1, 2)) {
            FileManager.tryShow(file);
        }
    }

    private void start(Scene scene) {
        exporting = scene.name();
        stage = "Starting";
        fraction = -1;
        stopping = false;
        outcome = "";
        written = null;
        try {
            var request = SceneRequest.of(scene, client, variant);
            var lack = request.lack(scene);
            if (lack != null) {
                throw new IOException(lack);
            }
            var tiles = request.tiles();
            var parent = settings.getFolder();
            Files.createDirectories(parent);
            var file = SceneVideoFile.in(parent, scene.name(), variant);
            var temporary = Files.createTempDirectory("cruma-record-");
            running = new RecordingProcess(
                    program.apply(arguments(scene, file, tiles, temporary)), file, temporary, this::take);
        } catch (IOException | RuntimeException failure) {
            log.warn("A recording did not start", failure);
            failed = true;
            outcome = "The export failed: " + failure.getMessage();
        }
    }

    private List<String> arguments(Scene scene, Path file, List<String> tiles, Path temporary) throws IOException {
        var arguments =
                new ArrayList<>(List.of("viewer", "-c", configFolder.toString(), "--client", client.toString()));
        arguments.addAll(sceneArguments(scene, temporary));
        var options = new RecordOptions(
                file,
                settings.getVideoWidth(),
                settings.getVideoHeight(),
                settings.getVideoRate(),
                0,
                Double.POSITIVE_INFINITY,
                scene.hourOr(hour.getAsDouble()),
                MapEventChoices.text(mapEvents.of(client)),
                scripts,
                variant,
                tiles,
                true);
        arguments.addAll(options.arguments());
        return arguments;
    }

    private void stop() {
        stopping = true;
        if (running != null) {
            running.stop();
        }
    }

    private void poll() {
        var recording = running;
        if (recording == null || !recording.isEnded()) {
            return;
        }
        running = null;
        var done = recording.getDone();
        if (done != null) {
            failed = false;
            written = Path.of(done.text());
            outcome = summary(
                            written,
                            done.frames(),
                            settings.getVideoRate(),
                            settings.getVideoWidth(),
                            settings.getVideoHeight(),
                            size(written))
                    + recording.getSkipped();
        } else {
            failed = true;
            written = null;
            outcome = stopping ? "Stopped. Nothing was written." : "The export failed: " + recording.getFailure();
        }
    }

    private void take(RecordLine line) {
        switch (line.kind()) {
            case STAGE -> {
                stage = line.text();
                fraction = -1;
            }
            case FRAME -> {
                stage = "Frame " + line.frame() + " of " + line.frames();
                fraction = line.frames() == 0 ? -1 : (double) line.frame() / line.frames();
            }
            case SKIPPED, DONE, FAILED -> {
            }
        }
    }

    private static long size(Path file) {
        try {
            return Files.size(file);
        } catch (IOException gone) {
            return 0;
        }
    }
}
