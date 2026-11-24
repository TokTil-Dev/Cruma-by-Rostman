package com.rost.cruma.viewer.application;

import com.rost.cruma.viewer.render.api.ReadbackTicket;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public final class SceneRecording {
    private static final int SETTLING_FRAMES = 3;
    private static final int MOST_IN_FLIGHT = 4;
    private static final float LAYOUT_HEIGHT = 1080;
    private static final int REPORT_FRAMES = 6;
    private static final double SAME_MOMENT = 1e-6;
    private static final Logger log = LogManager.getLogger(SceneRecording.class);

    private final Plan plan;
    private final FrameSink sink;
    private final Consumer<RecordLine> report;
    private final ArrayDeque<ReadbackTicket> tickets = new ArrayDeque<>();
    private final double step;
    private final double first;
    private Phase phase = Phase.WAITING;
    private int settling;
    private boolean frameWanted;
    private boolean picturesRead = true;
    private @Nullable CompletableFuture<List<String>> sound;
    private int frames;
    private int written;
    private String stage = "";
    private @Nullable String failure;
    private boolean settled;
    private boolean flip;
    private Consumer<ReadbackTicket> finishedTickets = _ -> {};
    private volatile boolean stopped;

    public SceneRecording(Plan plan, FrameSink sink, Consumer<RecordLine> report) {
        this.plan = plan;
        this.sink = sink;
        this.report = report;
        step = 1.0 / plan.rate();
        first = plan.from() > 0 ? Math.ceil(plan.from() * plan.rate() - SAME_MOMENT) / plan.rate() : step;
    }

    public static SceneRecording withFfmpeg(Path ffmpeg, Plan plan, Consumer<RecordLine> report) {
        return new SceneRecording(plan, new FfmpegFrames(ffmpeg, plan), report);
    }

    public record Plan(Path file, int width, int height, int rate, double from, double to, List<String> tiles) {
        public Plan {
            tiles = List.copyOf(tiles);
        }
    }

    public record SceneState(boolean loading, boolean playing, boolean reading, boolean shadowsBuilt, double seconds) {}

    public interface FrameSink {
        void open(boolean flip) throws IOException;

        boolean isFull();

        void write(ByteBuffer frame, Runnable written);

        @Nullable
        String getFailure();

        void finish() throws IOException;

        void abort();
    }

    private enum Phase {
        WAITING,
        SETTLING,
        RECORDING,
        FINISHING,
        DONE
    }

    public Path getFile() {
        return plan.file();
    }

    public List<String> getTiles() {
        return plan.tiles();
    }

    public int getWidth() {
        return plan.width();
    }

    public int getHeight() {
        return plan.height();
    }

    public float getInterfaceScale() {
        return plan.height() / LAYOUT_HEIGHT;
    }

    public double getFirstSeconds() {
        return first;
    }

    public double getLastSeconds(double length) {
        return Math.min(plan.to(), length) + 2 * step;
    }

    public void scene(double length, CompletableFuture<List<String>> mix) {
        frames = Math.max(1, (int) Math.ceil((Math.min(plan.to(), length) - first) * plan.rate() - SAME_MOMENT) + 1);
        sound = mix;
    }

    public void setTicketTaker(Consumer<ReadbackTicket> taker) {
        finishedTickets = taker;
    }

    public void setOriginBottomLeft(boolean bottomLeft) {
        flip = bottomLeft;
    }

    public void setPicturesRead(boolean read) {
        picturesRead = read;
    }

    public void stop() {
        stopped = true;
    }

    public boolean isStopped() {
        return stopped;
    }

    public void fail(String reason) {
        if (failure == null) {
            failure = reason;
            log.warn("The recording failed: {}", reason);
            report.accept(RecordLine.failed(reason));
        }
    }

    public double advance(SceneState scene) {
        frameWanted = false;
        settled = false;
        return switch (phase) {
            case WAITING -> waitFor(scene);
            case SETTLING -> {
                settled = settling == SETTLING_FRAMES;
                settling = Math.max(0, settling - 1);
                if (settling == 0 && scene.shadowsBuilt()) {
                    open();
                }
                yield 0;
            }
            case RECORDING -> record(scene);
            case FINISHING, DONE -> 0;
        };
    }

    public boolean isSettled() {
        return settled;
    }

    public boolean isStarted() {
        return phase != Phase.WAITING;
    }

    public boolean wantsFrame() {
        return frameWanted;
    }

    public void requested(ReadbackTicket ticket) {
        tickets.addLast(ticket);
    }

    public void poll() {
        if (isFinished()) {
            return;
        }
        if (stopped) {
            end();
            return;
        }
        var problem = sink.getFailure();
        if (problem != null) {
            fail(problem);
        }
        while (failure == null && !tickets.isEmpty() && !sink.isFull()) {
            var ticket = tickets.peekFirst();
            switch (ticket.getState()) {
                case READY -> {
                    tickets.removeFirst();
                    sink.write(ticket.getData(), () -> finishedTickets.accept(ticket));
                    written++;
                    if (written % REPORT_FRAMES == 0) {
                        report.accept(RecordLine.frame(written, Math.max(frames, written)));
                    }
                }
                case FAILED, CANCELLED -> fail("A frame did not come back: " + ticket.getFailure());
                case QUEUED, SUBMITTED -> {
                    return;
                }
            }
        }
        if (failure != null) {
            end();
        } else if (phase == Phase.FINISHING && tickets.isEmpty()) {
            finish();
        }
    }

    public boolean waitsForEncoder() {
        return phase == Phase.RECORDING && sink.isFull();
    }

    public boolean isFinished() {
        return phase == Phase.DONE;
    }

    public @Nullable String getFailure() {
        return failure;
    }

    public void close() {
        if (phase != Phase.DONE) {
            end();
        }
    }

    private double waitFor(SceneState scene) {
        var reading = sound;
        if (stopped) {
            end();
            return 0;
        }
        if (reading != null && reading.isCompletedExceptionally()) {
            fail("The sound of the scene could not be read: "
                    + reading.exceptionNow().getMessage());
            end();
            return 0;
        }
        var soundRead = reading != null && reading.isDone();
        var wait = scene.loading()
                ? "Loading the tiles"
                : scene.reading() || !picturesRead ? "Reading the scene" : !soundRead ? "Mixing the sound" : "";
        if (!wait.equals(stage)) {
            stage = wait;
            if (!wait.isEmpty()) {
                report.accept(RecordLine.stage(wait));
            }
        }
        if (!wait.isEmpty() || !scene.playing()) {
            return 0;
        }
        var skipped = reading.join();
        if (!skipped.isEmpty()) {
            report.accept(RecordLine.skipped(String.join(", ", skipped)));
        }
        phase = Phase.SETTLING;
        settling = SETTLING_FRAMES;
        return step;
    }

    private void open() {
        try {
            sink.open(flip);
            report.accept(RecordLine.frame(0, frames));
            phase = Phase.RECORDING;
        } catch (IOException | RuntimeException failed) {
            fail("ffmpeg did not start: " + failed.getMessage());
            end();
        }
    }

    private double record(SceneState scene) {
        if (!scene.playing() || scene.seconds() >= plan.to() - SAME_MOMENT) {
            phase = Phase.FINISHING;
            return 0;
        }
        if (scene.seconds() < first - SAME_MOMENT) {
            return step;
        }
        if (tickets.size() >= MOST_IN_FLIGHT || scene.reading() || !picturesRead) {
            return 0;
        }
        frameWanted = true;
        return step;
    }

    private void finish() {
        report.accept(RecordLine.stage("Writing the file"));
        try {
            sink.finish();
            phase = Phase.DONE;
            log.info("Wrote {} frames to {}", written, plan.file());
            report.accept(RecordLine.done(plan.file(), written));
        } catch (IOException | RuntimeException failed) {
            fail(failed.getMessage());
            end();
        }
    }

    private void end() {
        phase = Phase.DONE;
        sink.abort();
    }
}
