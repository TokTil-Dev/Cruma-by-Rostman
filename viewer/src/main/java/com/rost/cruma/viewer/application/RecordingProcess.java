package com.rost.cruma.viewer.application;

import com.rost.cruma.viewer.launch.OwnProgram;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.ProcessBuilder.Redirect;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public final class RecordingProcess {
    private static final long STOP_SECONDS = 5;
    private static final Logger log = LogManager.getLogger(RecordingProcess.class);

    private final Path file;
    private final Path temporary;
    private final Consumer<RecordLine> lines;
    private final Process process;
    private final Thread reader;
    private volatile @Nullable RecordLine done;
    private volatile String failure = "the recording stopped without saying why. Its log has more.";
    private volatile String skipped = "";

    public RecordingProcess(List<String> command, Path file, Path temporary, Consumer<RecordLine> lines)
            throws IOException {
        this.file = file;
        this.temporary = temporary;
        this.lines = lines;
        var builder = new ProcessBuilder(command).redirectError(Redirect.DISCARD);
        builder.environment()
                .put("JAVA_TOOL_OPTIONS", OwnProgram.recordingOptions(System.getenv("JAVA_TOOL_OPTIONS"), temporary));
        process = builder.start();
        reader = Thread.ofPlatform().name("cruma-record-lines").daemon().start(this::read);
    }

    public boolean isEnded() {
        return !reader.isAlive();
    }

    public void await() throws InterruptedException {
        reader.join();
    }

    public void stop() {
        try {
            process.getOutputStream().close();
        } catch (IOException gone) {
            log.debug("The recording has gone: {}", gone.toString());
        }
        Thread.ofPlatform().name("cruma-record-stop").daemon().start(this::endWhenStuck);
    }

    public void end() {
        stop();
        try {
            reader.join(TimeUnit.SECONDS.toMillis(STOP_SECONDS * 2));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    public @Nullable RecordLine getDone() {
        return done;
    }

    public String getFailure() {
        return failure;
    }

    public String getSkipped() {
        return skipped;
    }

    private void read() {
        try (var input = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            for (var line = input.readLine(); line != null; line = input.readLine()) {
                var said = RecordLine.read(line);
                if (said != null) {
                    take(said);
                }
            }
        } catch (IOException gone) {
            log.debug("The lines of the recording ended: {}", gone.toString());
        }
        clean();
    }

    private void take(RecordLine line) {
        switch (line.kind()) {
            case SKIPPED -> skipped = " Left out, as they could not be read: " + line.text() + ".";
            case DONE -> done = line;
            case FAILED -> failure = line.text();
            case STAGE, FRAME -> {
            }
        }
        lines.accept(line);
    }

    private void endWhenStuck() {
        try {
            if (!process.waitFor(STOP_SECONDS, TimeUnit.SECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void clean() {
        try {
            process.waitFor(STOP_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        try {
            SceneVideoFile.clean(file);
            try (var left = Files.walk(temporary)) {
                for (var path : left.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        } catch (IOException | RuntimeException left) {
            log.warn("Could not remove what a recording left: {}", left.toString());
        }
    }
}
