package com.rost.cruma.viewer.agent.film;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.viewer.agent.Step;
import com.rost.cruma.viewer.agent.ToolProblem;
import com.rost.cruma.viewer.agent.ToolResult;
import com.rost.cruma.viewer.agent.ViewerContext;
import com.rost.cruma.viewer.agent.ViewerEndpoint;
import com.rost.cruma.viewer.agent.film.FilmScenes.Fitted;
import com.rost.cruma.viewer.agent.film.FilmSheet.Frame;
import com.rost.cruma.viewer.render.ScreenLens;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import org.jetbrains.annotations.Nullable;

final class FilmFrames implements Step {
    static final int MOST_FRAMES = 12;

    static final int DEFAULT_FRAMES = 6;

    private static final double FASTEST = 4;
    private static final double SLOWEST = .1;
    private static final double CLOSE_ENOUGH = 1.0 / 60;
    private static final double FIRST_MOMENT = 1.0 / 60;
    private static final double LAST_MOMENT = 2.0 / 60;
    private static final double FIRST_FRAME_SECONDS = 1.0 / 60;
    private static final int SETTLING_FRAMES = 3;
    private static final long SHOT_NANOS = TimeUnit.SECONDS.toNanos(20);
    private static final long RUN_NANOS = TimeUnit.MINUTES.toNanos(2);
    private static final double MOST_FOV = 170;
    private static final AtomicInteger next = new AtomicInteger(1);

    private final ViewerContext viewer;
    private final CompletableFuture<Fitted> finding;
    private final @Nullable Double hour;
    private final List<Double> asked;
    private final int count;
    private final int sheetWidth;
    private final CompletableFuture<Double> shape;
    private final List<Shot> shots = new ArrayList<>();
    private final AtomicReference<Object> taken = new AtomicReference<>();
    private Phase phase = Phase.FINDING;
    private List<Double> marks = List.of();
    private @Nullable Fitted fitted;
    private @Nullable FilmHold hold;
    private @Nullable Step making;
    private @Nullable Path file;
    private int settling;
    private float filmFov;
    private long deadline;
    private long lastFrame = System.nanoTime();
    private double frameSeconds = FIRST_FRAME_SECONDS;

    FilmFrames(
            ViewerContext viewer,
            CompletableFuture<Fitted> finding,
            @Nullable Double hour,
            List<Double> asked,
            int count,
            int sheetWidth,
            CompletableFuture<Double> shape) {
        this.viewer = viewer;
        this.finding = finding;
        this.hour = hour;
        this.asked = asked;
        this.count = count;
        this.sheetWidth = sheetWidth;
        this.shape = shape;
    }

    private enum Phase {
        FINDING,
        RUNNING,
        SETTLING,
        SHOOTING,
        MAKING
    }

    private record Shot(
            double asked,
            double at,
            Path file,
            JSONObject camera,
            float fade,
            int fadeColor,
            boolean bars,
            List<String> leftOut) {}

    static List<Double> moments(List<Double> asked, int count, double length) throws ToolProblem {
        var moments = new ArrayList<Double>();
        if (asked.isEmpty()) {
            for (var index = 0; index < count; index++) {
                moments.add(count == 1 ? length / 2 : length * index / (count - 1));
            }
        }
        for (var moment : asked) {
            if (!(moment >= 0) || moment > length) {
                throw new ToolProblem(String.format(
                        Locale.ROOT, "%.2f is not in this scene, which runs from 0 to %.2f seconds.", moment, length));
            }
            moments.add(moment);
        }
        moments.sort(null);
        return moments;
    }

    static double widened(double fov, double window, double film, ScreenLens lens) {
        var scale = Math.max(
                lens.computeHalfWidth(fov, film) / lens.computeHalfWidth(fov, window),
                lens.computeHalfHeight(fov, film) / lens.computeHalfHeight(fov, window));
        var wide = 2 * Math.toDegrees(Math.atan(Math.tan(Math.toRadians(fov / 2)) * scale));
        return Math.min(wide, MOST_FOV);
    }

    @Override
    public @Nullable ToolResult step() throws ToolProblem {
        var now = System.nanoTime();
        frameSeconds = Math.max(FIRST_FRAME_SECONDS, (now - lastFrame) / 1e9);
        lastFrame = now;
        try {
            return advance();
        } catch (ToolProblem | RuntimeException failure) {
            cancel();
            throw failure;
        }
    }

    @Override
    public void cancel() {
        if (making != null) {
            making.cancel();
        }
        if (hold != null) {
            hold.giveBack();
        }
    }

    private @Nullable ToolResult advance() throws ToolProblem {
        return switch (phase) {
            case FINDING -> {
                begin();
                yield null;
            }
            case RUNNING -> {
                run();
                yield null;
            }
            case SETTLING -> {
                settle();
                yield null;
            }
            case SHOOTING -> {
                collect();
                yield null;
            }
            case MAKING -> making.step();
        };
    }

    private void begin() throws ToolProblem {
        if (!finding.isDone() || !shape.isDone()) {
            return;
        }
        var found = FilmScenes.taken(finding);
        FilmScenes.requireFree(viewer);
        var scene = found.found().scene();
        var length = scene.seconds();
        marks = moments(asked, count, length);
        fitted = found;
        hold = new FilmHold(viewer, FilmScenes.hourOf(scene, hour));
        viewer.system().playScene(scene);
        deadline = nextDeadline();
        phase = Phase.RUNNING;
    }

    private long nextDeadline() {
        var length = fitted.found().scene().seconds();
        return System.nanoTime() + RUN_NANOS + TimeUnit.SECONDS.toNanos(Math.round(length));
    }

    private void run() throws ToolProblem {
        var player = viewer.scene().gameScenes;
        if (!player.isPlaying()) {
            throw new ToolProblem(
                    "The scene stopped before its frames were taken. Something else stopped it in this viewer.");
        }
        if (System.nanoTime() > deadline) {
            throw new ToolProblem(
                    "The scene did not reach its next frame in time. This viewer draws too slowly. Open fewer tiles.");
        }
        if (player.isReading()) {
            return;
        }
        var length = fitted.found().scene().seconds();
        var mark = Math.clamp(marks.get(shots.size()), FIRST_MOMENT, Math.max(FIRST_MOMENT, length - LAST_MOMENT));
        var gap = mark - player.getSeconds();
        var playback = viewer.scene().playback;
        if (gap <= CLOSE_ENOUGH) {
            playback.setPaused(true);
            settling = SETTLING_FRAMES;
            phase = Phase.SETTLING;
            return;
        }
        playback.setSpeed(Math.clamp(gap / (2 * frameSeconds), SLOWEST, FASTEST));
        playback.setPaused(false);
    }

    private void settle() throws ToolProblem {
        if (viewer.scene().playback.getLastDelta() > 0) {
            settling = SETTLING_FRAMES;
            return;
        }
        if (settling-- > 0) {
            return;
        }
        var directory = viewer.configDirectory();
        if (directory == null) {
            throw new ToolProblem("This viewer keeps no files.");
        }
        var folder = ViewerEndpoint.folder(directory).resolve("captures");
        try {
            Files.createDirectories(folder);
        } catch (IOException failed) {
            throw new ToolProblem("Cannot write to " + folder + ": " + failed.getMessage());
        }
        file = folder.resolve(ProcessHandle.current().pid() + "-film-" + next.getAndIncrement() + ".jpg");
        var camera = viewer.scene().camera;
        var size = viewer.window().framebuffer;
        filmFov = camera.getFov();
        camera.setFov((float) widened(filmFov, (double) size.width / size.height, shape.join(), camera.getLens()));
        taken.set(null);
        viewer.backend().takeAgentShot(file, (written, failure) -> taken.set(failure == null ? written : failure));
        deadline = System.nanoTime() + SHOT_NANOS;
        phase = Phase.SHOOTING;
    }

    private void collect() throws ToolProblem {
        var result = taken.get();
        if (result instanceof Throwable failure) {
            throw new ToolProblem("A frame failed: " + failure.getMessage());
        }
        if (result == null) {
            if (System.nanoTime() > deadline) {
                throw new ToolProblem("A frame did not come back in time.");
            }
            return;
        }
        viewer.scene().camera.setFov(filmFov);
        shots.add(caught());
        if (shots.size() < marks.size()) {
            deadline = nextDeadline();
            phase = Phase.RUNNING;
            return;
        }
        var worth = hold.getHour();
        hold.giveBack();
        var notes = FilmScenes.notes(fitted, viewer.scene().spawns.wanted);
        making = Step.onWorker(() -> answer(worth, notes));
        phase = Phase.MAKING;
    }

    private Shot caught() {
        var scene = viewer.scene();
        var player = scene.gameScenes;
        var overlay = player.getOverlay();
        var leftOut = new ArrayList<String>();
        if (overlay.slidesShow()) {
            leftOut.add("slides");
        }
        if (!overlay.messages().isEmpty()) {
            leftOut.add("lines");
        }
        var eye = scene.camera.getPosition();
        var camera = new JSONObject();
        camera.put("x", Math.round(eye.getX()));
        camera.put("y", Math.round(eye.getY()));
        camera.put("z", Math.round(eye.getZ()));
        camera.put("yaw", Math.round(scene.camera.getYawDegrees()));
        camera.put("pitch", Math.round(scene.camera.getPitchDegrees()));
        return new Shot(
                marks.get(shots.size()),
                player.getSeconds(),
                file,
                camera,
                overlay.fade(),
                overlay.fadeColor(),
                overlay.wide(),
                leftOut);
    }

    private ToolResult answer(double shownHour, List<String> notes) throws IOException {
        var frames = new ArrayList<Frame>();
        var listed = new JSONArray();
        for (var shot : shots) {
            var picture = ImageIO.read(shot.file().toFile());
            frames.add(new Frame(picture, label(shot.at(), shownHour), shot.fade(), shot.fadeColor(), shot.bars()));
            var item = new JSONObject();
            item.put("time", round(shot.asked()));
            item.put("at", round(shot.at()));
            item.put("camera", shot.camera());
            if (!shot.leftOut().isEmpty()) {
                item.put("left_out", shot.leftOut());
            }
            listed.add(item);
        }
        var sheet = FilmSheet.draw(frames, sheetWidth, shape.join());
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(sheet, "jpeg", bytes);
        var written = shots.getFirst()
                .file()
                .resolveSibling(ProcessHandle.current().pid() + "-sheet-" + next.getAndIncrement() + ".jpg");
        Files.write(written, bytes.toByteArray());
        for (var shot : shots) {
            Files.deleteIfExists(shot.file());
        }
        var scene = fitted.found().scene();
        var data = new JSONObject();
        data.put("scene", scene.name());
        data.put("seconds", round(scene.seconds()));
        data.put("hour", shownHour);
        data.put("sheet", written.toString());
        data.put("size", sheet.getWidth() + "x" + sheet.getHeight());
        data.put("frames", listed);
        if (!notes.isEmpty()) {
            data.put("notes", notes);
        }
        return ToolResult.data(data).withImage(bytes.toByteArray(), "image/jpeg");
    }

    private static String label(double at, double hour) {
        var whole = (int) hour;
        return String.format(Locale.ROOT, "%.2f s   %02d:%02d", at, whole, (int) Math.round((hour - whole) * 60));
    }

    private static double round(double seconds) {
        return Math.round(seconds * 100) / 100.0;
    }
}
