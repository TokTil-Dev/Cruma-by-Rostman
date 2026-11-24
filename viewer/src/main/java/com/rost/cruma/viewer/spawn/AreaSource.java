package com.rost.cruma.viewer.spawn;

import com.rost.cruma.core.area.AreaDataFile;
import com.rost.cruma.core.area.AreaDataWriter;
import com.rost.cruma.core.area.AreaDocument;
import com.rost.cruma.core.area.AreaDocument.State;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.apache.logging.log4j.LogManager;
import org.jetbrains.annotations.Nullable;

public final class AreaSource implements AutoCloseable {
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        var thread = new Thread(runnable, "server-areas");
        thread.setDaemon(true);
        return thread;
    });
    private Phase phase = Phase.EMPTY;
    private @Nullable Path folder;
    private @Nullable Path file;
    private @Nullable AreaDocument document;
    private String message = "";
    private boolean messageIsError;
    private @Nullable Future<Loaded> loading;
    private @Nullable Future<Saved> saving;

    private @Nullable FileTime stampTime;

    private long stampSize = -1;
    private long generation;
    private long saves;

    public enum Phase {
        EMPTY,
        LOADING,
        MISSING,
        READY,
        FAILED
    }

    private record Loaded(@Nullable Path file, @Nullable AreaDataFile data) {}

    private record Saved(
            AreaDocument document, State state, @Nullable Path backup) {}

    public void open(@Nullable Path scriptFolder) {
        cancel();
        generation++;
        stampTime = null;
        stampSize = -1;
        document = null;
        file = null;
        folder = scriptFolder;
        message = "";
        messageIsError = false;
        if (scriptFolder == null) {
            phase = Phase.EMPTY;
            return;
        }
        phase = Phase.LOADING;
        loading = worker.submit(() -> load(scriptFolder));
    }

    public void reload() {
        open(folder);
    }

    public void poll() {
        if (loading != null && loading.isDone()) {
            var finished = loading;
            loading = null;
            try {
                var loaded = finished.get();
                if (loaded.file() == null || loaded.data() == null) {
                    phase = Phase.MISSING;
                    message = "No " + AreaDataFile.FILE_NAME + " in this folder.";
                    messageIsError = false;
                } else {
                    file = loaded.file();
                    recordStamp(file);
                    document = new AreaDocument(loaded.data());
                    phase = Phase.READY;
                    var problems = loaded.data().getProblems();
                    message = problems.isEmpty()
                            ? ""
                            : String.format(
                                    Locale.ROOT,
                                    "%,d %s while reading. The first is on line %d: %s",
                                    problems.size(),
                                    problems.size() == 1 ? "problem" : "problems",
                                    problems.getFirst().line(),
                                    problems.getFirst().message());
                    messageIsError = !problems.isEmpty();
                }
            } catch (Exception failure) {
                phase = Phase.FAILED;
                message = causeMessage(failure);
                messageIsError = true;
            }
        }
        if (saving != null && saving.isDone()) {
            var finished = saving;
            saving = null;
            try {
                var saved = finished.get();
                saves++;
                if (saved.document() == document) {
                    recordStamp(file);
                    if (document.getState() == saved.state()) {
                        document.markSaved();
                        message = "Saved "
                                + AreaDataFile.FILE_NAME
                                + (saved.backup() == null
                                        ? "."
                                        : ". The previous file is kept as "
                                                + saved.backup().getFileName() + ".");
                    } else {
                        message = "Saved. Later edits remain unsaved.";
                    }
                    messageIsError = false;
                }
            } catch (Exception failure) {
                message = "Could not save: " + causeMessage(failure);
                messageIsError = true;
            }
        }
    }

    public void save(boolean backup) {
        if (document == null || file == null || saving != null) {
            return;
        }
        var target = file;
        var current = document;
        var state = current.getState();
        saving = worker.submit(() -> {
            var text = AreaDataWriter.write(current.getFile(), state);
            Path backupPath = null;
            if (backup) {
                backupPath = target.resolveSibling(
                        SpawnSource.backupName(target.getFileName().toString(), LocalDateTime.now()));
                Files.copy(target, backupPath, StandardCopyOption.REPLACE_EXISTING);
            }
            SpawnSource.write(target, text.encode());
            return new Saved(current, state, backupPath);
        });
        message = "Saving…";
        messageIsError = false;
    }

    public boolean hasChangedOnDisk() {
        var current = file;
        if (current == null || stampTime == null) {
            return false;
        }
        try {
            return !Files.getLastModifiedTime(current).equals(stampTime) || Files.size(current) != stampSize;
        } catch (IOException unreadable) {
            return true;
        }
    }

    public Phase getPhase() {
        return phase;
    }

    public @Nullable Path getFolder() {
        return folder;
    }

    public @Nullable Path getFile() {
        return file;
    }

    public @Nullable AreaDocument getDocument() {
        return document;
    }

    public String getMessage() {
        return message;
    }

    public boolean isMessageError() {
        return messageIsError;
    }

    public boolean isSaving() {
        return saving != null;
    }

    public boolean isModified() {
        return document != null && document.isModified();
    }

    public long getGeneration() {
        return generation;
    }

    public long getSaveCount() {
        return saves;
    }

    @Override
    public void close() {
        cancel();
        worker.shutdownNow();
    }

    private void recordStamp(@Nullable Path read) {
        try {
            stampTime = read == null ? null : Files.getLastModifiedTime(read);
            stampSize = read == null ? -1 : Files.size(read);
        } catch (IOException unreadable) {
            stampTime = null;
            stampSize = -1;
        }
    }

    private void cancel() {
        if (loading != null) {
            loading.cancel(true);
            loading = null;
        }
    }

    private static Loaded load(Path folder) throws IOException {
        var path = SpawnSource.find(folder, AreaDataFile.FILE_NAME);
        if (path == null) {
            return new Loaded(null, null);
        }
        var started = System.nanoTime();
        var data = AreaDataFile.read(path);
        LogManager.getLogger(AreaSource.class)
                .info(
                        "Read {} with {} areas and {} problems in {} ms",
                        path,
                        data.getAreas().size(),
                        data.getProblems().size(),
                        (System.nanoTime() - started) / 1_000_000);
        return new Loaded(path, data);
    }

    private static String causeMessage(Exception failure) {
        var cause = failure.getCause() == null ? failure : failure.getCause();
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
