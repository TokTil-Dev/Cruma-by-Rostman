package com.rost.cruma.viewer.application;

import com.rost.cruma.viewer.application.SceneRecording.FrameSink;
import com.rost.cruma.viewer.application.SceneRecording.Plan;
import com.rost.cruma.viewer.launch.FfmpegApp;
import com.rost.cruma.viewer.launch.FfmpegApp.Job;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.ProcessBuilder.Redirect;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.concurrent.ArrayBlockingQueue;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

final class FfmpegFrames implements FrameSink {
    private static final int WAITING_FRAMES = 3;
    private static final int CHUNK = 1 << 20;
    private static final int KEPT_MESSAGES = 8;
    private static final Waiting END = new Waiting(ByteBuffer.allocate(0), () -> {});
    private static final Logger log = LogManager.getLogger(FfmpegFrames.class);

    private final ArrayBlockingQueue<Waiting> waiting = new ArrayBlockingQueue<>(WAITING_FRAMES);
    private final ArrayDeque<String> messages = new ArrayDeque<>();
    private final Path ffmpeg;
    private final Plan plan;
    private @Nullable Process process;
    private @Nullable Thread sender;
    private @Nullable Thread reader;
    private volatile @Nullable String failure;

    FfmpegFrames(Path ffmpeg, Plan plan) {
        this.ffmpeg = ffmpeg;
        this.plan = plan;
    }

    @Override
    public void open(boolean flip) throws IOException {
        var encoder = FfmpegApp.encoder(ffmpeg, plan.width(), plan.height(), plan.rate());
        var job = new Job(
                plan.width(),
                plan.height(),
                plan.rate(),
                flip,
                SceneVideoFile.sound(plan.file()),
                SceneVideoFile.part(plan.file()));
        var arguments = FfmpegApp.arguments(ffmpeg, encoder, job);
        log.info("Starting ffmpeg: {}", arguments);
        var started =
                new ProcessBuilder(arguments).redirectOutput(Redirect.DISCARD).start();
        process = started;
        reader = Thread.ofPlatform().name("cruma-ffmpeg-messages").daemon().start(() -> readMessages(started));
        sender = Thread.ofPlatform().name("cruma-ffmpeg-frames").daemon().start(() -> send(started));
    }

    @Override
    public boolean isFull() {
        return waiting.remainingCapacity() == 0;
    }

    @Override
    public void write(ByteBuffer frame, Runnable written) {
        if (!waiting.offer(new Waiting(frame, written))) {
            throw new IllegalStateException("A frame came while the encoder was full");
        }
    }

    @Override
    public @Nullable String getFailure() {
        return failure;
    }

    @Override
    public void finish() throws IOException {
        var running = process;
        if (running == null) {
            throw new IOException("ffmpeg never started");
        }
        try {
            waiting.put(END);
            sender.join();
            var code = running.waitFor();
            reader.join();
            if (failure != null) {
                throw new IOException(failure);
            }
            if (code != 0) {
                throw new IOException("ffmpeg stopped with code " + code + ": " + messages());
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Stopped while ffmpeg wrote the file", interrupted);
        }
        Files.move(SceneVideoFile.part(plan.file()), plan.file());
        SceneVideoFile.clean(plan.file());
    }

    @Override
    public void abort() {
        var running = process;
        if (running != null) {
            running.destroyForcibly();
            sender.interrupt();
            try {
                running.waitFor();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        try {
            SceneVideoFile.clean(plan.file());
        } catch (IOException | RuntimeException left) {
            log.warn("Could not remove the parts of {}: {}", plan.file(), left.toString());
        }
    }

    private void send(Process running) {
        var chunk = new byte[CHUNK];
        try (OutputStream input = running.getOutputStream()) {
            while (true) {
                var next = waiting.take();
                if (next == END) {
                    return;
                }
                var frame = next.frame();
                while (frame.hasRemaining()) {
                    var length = Math.min(chunk.length, frame.remaining());
                    frame.get(chunk, 0, length);
                    input.write(chunk, 0, length);
                }
                next.written().run();
            }
        } catch (IOException closed) {
            failure = "ffmpeg stopped: " + reason(running, closed);
            waiting.clear();
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        }
    }

    private String reason(Process running, IOException closed) {
        try {
            running.waitFor();
            reader.join();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        var said = messages();
        return said.isEmpty() ? closed.getMessage() : said;
    }

    private void readMessages(Process running) {
        try (var lines = new BufferedReader(new InputStreamReader(running.getErrorStream(), StandardCharsets.UTF_8))) {
            for (var line = lines.readLine(); line != null; line = lines.readLine()) {
                synchronized (messages) {
                    messages.addLast(line);
                    if (messages.size() > KEPT_MESSAGES) {
                        messages.removeFirst();
                    }
                }
            }
        } catch (IOException closed) {
            log.debug("The messages of ffmpeg ended: {}", closed.toString());
        }
    }

    private String messages() {
        synchronized (messages) {
            return String.join(" ", messages);
        }
    }

    private record Waiting(ByteBuffer frame, Runnable written) {}
}
