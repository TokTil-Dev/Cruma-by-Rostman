package com.rost.cruma.viewer.spawn;

import com.rost.cruma.core.spawn.NpcDataTable;
import com.rost.cruma.core.spawn.NpcPosDocument;
import com.rost.cruma.core.spawn.NpcPosDocument.State;
import com.rost.cruma.core.spawn.NpcPosFile;
import com.rost.cruma.core.spawn.NpcPosWriter;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributeView;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.apache.logging.log4j.LogManager;
import org.jetbrains.annotations.Nullable;

public final class SpawnSource implements AutoCloseable {
    public static final String POSITIONS = "npcpos.txt";
    public static final String NPCS = "npcdata.txt";

    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        var thread = new Thread(runnable, "npc-spawns");
        thread.setDaemon(true);
        return thread;
    });
    private Phase phase = Phase.EMPTY;
    private @Nullable Path folder;
    private @Nullable Path positions;
    private @Nullable NpcPosDocument document;
    private NpcDataTable npcs = NpcDataTable.empty();
    private String message = "";
    private boolean messageIsError;
    private @Nullable Future<Loaded> loading;
    private @Nullable Future<Saved> saving;

    private @Nullable FileTime stampTime;

    private long stampSize = -1;
    private long generation;

    public enum Phase {
        EMPTY,
        LOADING,
        READY,
        FAILED
    }

    private record Loaded(Path folder, Path positions, NpcPosFile file, NpcDataTable npcs, String note) {}

    private record Saved(NpcPosDocument document, State state, Path backup) {}

    public static @Nullable Path resolveFolder(Path chosen) {
        if (chosen == null) {
            return null;
        }
        for (var candidate : List.of(chosen, chosen.resolve("script"), chosen.resolve("scripts"))) {
            if (find(candidate, POSITIONS) != null) {
                return candidate;
            }
        }
        return chosen;
    }

    public static @Nullable Path find(Path folder, String name) {
        try (var entries = Files.list(folder)) {
            var matches = entries.filter(path -> path.getFileName().toString().equalsIgnoreCase(name))
                    .filter(Files::isRegularFile)
                    .toList();
            return matches.stream()
                    .filter(path -> path.getFileName().toString().equals(name))
                    .findFirst()
                    .orElse(matches.isEmpty() ? null : matches.getFirst());
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    public static String backupName(String file, LocalDateTime time) {
        return file + "." + time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss", Locale.ROOT)) + ".bak";
    }

    static void write(Path target, byte[] bytes) throws IOException {
        var temporary = Files.createTempFile(target.toAbsolutePath().getParent(), ".script-", ".tmp");
        try {
            Files.write(temporary, bytes);
            keepMode(target, temporary);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public void open(@Nullable Path chosen) {
        cancel();
        generation++;
        stampTime = null;
        stampSize = -1;
        document = null;
        npcs = NpcDataTable.empty();
        positions = null;
        folder = resolveFolder(chosen);
        message = "";
        messageIsError = false;
        if (folder == null) {
            phase = Phase.EMPTY;
            return;
        }
        phase = Phase.LOADING;
        var target = folder;
        loading = worker.submit(() -> load(target));
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
                positions = loaded.positions();
                recordStamp(positions);
                document = new NpcPosDocument(loaded.file());
                npcs = loaded.npcs();
                phase = Phase.READY;
                var problems = loaded.file().getProblems().size();
                message = problems == 0
                        ? loaded.note()
                        : String.format(
                                Locale.ROOT,
                                "%,d %s while reading. The first is on line %d: %s",
                                problems,
                                problems == 1 ? "problem" : "problems",
                                loaded.file().getProblems().getFirst().line(),
                                loaded.file().getProblems().getFirst().message());
                messageIsError = problems > 0;
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
                if (saved.document() == document) {
                    recordStamp(positions);
                    if (document.getState() == saved.state()) {
                        document.markSaved();
                        message = "Saved "
                                + POSITIONS
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
        if (document == null || positions == null || saving != null) {
            return;
        }
        var target = positions;
        var current = document;
        var state = current.getState();
        saving = worker.submit(() -> {
            var text = NpcPosWriter.write(current.getFile(), state);
            Path backupPath = null;
            if (backup) {
                backupPath =
                        target.resolveSibling(backupName(target.getFileName().toString(), LocalDateTime.now()));
                Files.copy(target, backupPath, StandardCopyOption.REPLACE_EXISTING);
            }
            write(target, text.encode());
            return new Saved(current, state, backupPath);
        });
        message = "Saving…";
        messageIsError = false;
    }

    public boolean hasChangedOnDisk() {
        var file = positions;
        if (file == null || stampTime == null) {
            return false;
        }
        try {
            return !Files.getLastModifiedTime(file).equals(stampTime) || Files.size(file) != stampSize;
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

    public @Nullable Path getPositionsFile() {
        return positions;
    }

    public @Nullable NpcPosDocument getDocument() {
        return document;
    }

    public NpcDataTable getNpcs() {
        return npcs;
    }

    public void replaceNpcs(NpcDataTable table) {
        npcs = table;
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

    @Override
    public void close() {
        cancel();
        worker.shutdownNow();
    }

    private void recordStamp(@Nullable Path file) {
        try {
            stampTime = file == null ? null : Files.getLastModifiedTime(file);
            stampSize = file == null ? -1 : Files.size(file);
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
        var positions = find(folder, POSITIONS);
        if (positions == null) {
            throw new IOException("No " + POSITIONS + " in " + folder);
        }
        var started = System.nanoTime();
        var file = NpcPosFile.read(positions);
        var npcPath = find(folder, NPCS);
        var npcs = npcPath == null ? NpcDataTable.empty() : NpcDataTable.read(npcPath);
        var note = npcPath == null ? "No " + NPCS + ": NPC levels and shapes are unknown." : "";
        LogManager.getLogger(SpawnSource.class)
                .info(
                        "Read {} with {} territories, {} makers and {} NPC entries in {} ms",
                        positions,
                        file.getTerritories().size(),
                        file.getMakers().size(),
                        file.getNpcs().size(),
                        (System.nanoTime() - started) / 1_000_000);
        return new Loaded(folder, positions, file, npcs, note);
    }

    private static void keepMode(Path target, Path temporary) throws IOException {
        var view = Files.getFileAttributeView(target, PosixFileAttributeView.class);
        if (view != null && Files.exists(target)) {
            Files.setPosixFilePermissions(temporary, view.readAttributes().permissions());
        }
    }

    private static String causeMessage(Exception failure) {
        var cause = failure.getCause() == null ? failure : failure.getCause();
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
