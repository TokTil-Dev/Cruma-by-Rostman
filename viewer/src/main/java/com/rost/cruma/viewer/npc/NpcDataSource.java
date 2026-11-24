package com.rost.cruma.viewer.npc;

import com.rost.cruma.core.npc.ClientCatalog;
import com.rost.cruma.core.npc.NpcDataDocument;
import com.rost.cruma.core.npc.NpcDataDocument.State;
import com.rost.cruma.core.npc.NpcDataFile;
import com.rost.cruma.core.npc.NpcDataWriter;
import com.rost.cruma.core.npc.NpcPch;
import com.rost.cruma.core.npc.NpcPch.Change;
import com.rost.cruma.core.npc.ServerItems;
import com.rost.cruma.core.npc.ServerSkills;
import com.rost.cruma.core.spawn.SpawnText;
import com.rost.cruma.core.world.WorldVariant;
import com.rost.cruma.viewer.spawn.SpawnSource;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
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

public final class NpcDataSource implements AutoCloseable {
    public static final String NPCS = SpawnSource.NPCS;

    public static final String PCH = "npc_pch.txt";

    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        var thread = new Thread(runnable, "npc-data");
        thread.setDaemon(true);
        return thread;
    });
    private final ExecutorService clientWorker = Executors.newSingleThreadExecutor(runnable -> {
        var thread = new Thread(runnable, "npc-client-catalog");
        thread.setDaemon(true);
        return thread;
    });
    private Phase phase = Phase.EMPTY;
    private @Nullable Path folder;
    private @Nullable Path file;
    private @Nullable NpcDataDocument document;
    private ServerItems items = ServerItems.empty();
    private ServerSkills skills = ServerSkills.empty();
    private ClientCatalog catalog = ClientCatalog.empty();
    private @Nullable Path pchFile;
    private @Nullable NpcPch pch;
    private boolean catalogLoading;
    private String message = "";
    private boolean messageIsError;
    private @Nullable Future<Loaded> loading;
    private @Nullable Future<Saved> saving;
    private @Nullable Future<ClientCatalog> catalogFuture;
    private @Nullable FileTime stampTime;
    private long stampSize = -1;
    private long generation;
    private @Nullable SpawnText lastSaved;

    public enum Phase {
        EMPTY,
        LOADING,
        READY,
        FAILED
    }

    private record Loaded(
            Path file,
            NpcDataFile data,
            ServerItems items,
            ServerSkills skills,
            String note,
            @Nullable Path pchFile,
            @Nullable NpcPch pch) {}

    private record Saved(
            NpcDataDocument document, State state, @Nullable Path backup, SpawnText text) {}

    public void openClient(@Nullable Path client) {
        openClient(client, WorldVariant.MAIN);
    }

    public void openClient(@Nullable Path client, WorldVariant variant) {
        if (client == null) {
            return;
        }
        catalogLoading = true;
        catalogFuture = clientWorker.submit(() -> {
            var started = System.nanoTime();
            var read = ClientCatalog.read(client, variant);
            LogManager.getLogger(NpcDataSource.class)
                    .info(
                            "Client catalog of the {} world: {} items, {} skills, {} NPCs in {} ms{}",
                            variant.getLabel(),
                            read.getItemCount(),
                            read.getSkillCount(),
                            read.getNpcCount(),
                            (System.nanoTime() - started) / 1_000_000,
                            read.getNotes().isEmpty() ? "" : "; " + String.join("; ", read.getNotes()));
            return read;
        });
    }

    public void open(@Nullable Path chosen) {
        cancel();
        generation++;
        document = null;
        items = ServerItems.empty();
        skills = ServerSkills.empty();
        stampTime = null;
        stampSize = -1;
        message = "";
        messageIsError = false;
        pch = null;
        pchFile = null;
        folder = chosen == null ? null : SpawnSource.resolveFolder(chosen);
        file = null;
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
        if (catalogFuture != null && catalogFuture.isDone()) {
            try {
                catalog = catalogFuture.get();
            } catch (Exception failure) {
                LogManager.getLogger(NpcDataSource.class)
                        .warn("Could not read the client catalog: {}", failure.toString());
            }
            catalogFuture = null;
            catalogLoading = false;
        }
        if (loading != null && loading.isDone()) {
            var finished = loading;
            loading = null;
            try {
                var loaded = finished.get();
                file = loaded.file();
                recordStamp(file);
                document = new NpcDataDocument(loaded.data());
                items = loaded.items();
                skills = loaded.skills();
                pchFile = loaded.pchFile();
                pch = loaded.pch();
                phase = Phase.READY;
                var problems = loaded.data().getProblems();
                message = problems.isEmpty()
                        ? loaded.note()
                        : String.format(
                                Locale.ROOT,
                                "%,d %s while reading. The first is on line %d: %s",
                                problems.size(),
                                problems.size() == 1 ? "problem" : "problems",
                                problems.getFirst().line(),
                                problems.getFirst().message());
                messageIsError = !problems.isEmpty();
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
                    recordStamp(file);
                    lastSaved = saved.text();
                    if (document.getState().equals(saved.state())) {
                        document.markSaved();
                        message = "Saved "
                                + NPCS
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
        var pchTarget = pchFile;
        var pchText = pch == null || pchTarget == null ? null : pch.write(pch.computeChange(current, state));
        saving = worker.submit(() -> {
            var text = NpcDataWriter.write(current, state);
            Path backupPath = null;
            var now = LocalDateTime.now();
            if (backup) {
                backupPath = target.resolveSibling(
                        SpawnSource.backupName(target.getFileName().toString(), now));
                Files.copy(target, backupPath, StandardCopyOption.REPLACE_EXISTING);
            }
            var pchChanged = pchText != null && !pchText.text().equals(currentPchText(pchTarget));
            if (pchChanged && backup) {
                Files.copy(
                        pchTarget,
                        pchTarget.resolveSibling(
                                SpawnSource.backupName(pchTarget.getFileName().toString(), now)),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            write(target, text.encode());
            if (pchChanged) {
                write(pchTarget, pchText.encode());
            }
            return new Saved(current, state, backupPath, text);
        });
        message = "Saving…";
        messageIsError = false;
    }

    public @Nullable SpawnText consumeSaved() {
        var text = lastSaved;
        lastSaved = null;
        return text;
    }

    public boolean hasChangedOnDisk() {
        var path = file;
        if (path == null || stampTime == null) {
            return false;
        }
        try {
            return !Files.getLastModifiedTime(path).equals(stampTime) || Files.size(path) != stampSize;
        } catch (IOException unreadable) {
            return true;
        }
    }

    public Change getPchChange() {
        return pch == null || document == null ? null : pch.computeChange(document, document.getState());
    }

    public boolean hasPch() {
        return pch != null;
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

    public @Nullable NpcDataDocument getDocument() {
        return document;
    }

    public ServerItems getItems() {
        return items;
    }

    public ServerSkills getSkills() {
        return skills;
    }

    public ClientCatalog getCatalog() {
        return catalog;
    }

    public boolean isCatalogLoading() {
        return catalogLoading;
    }

    public String getMessage() {
        return message;
    }

    public void showNote(String text) {
        message = text;
        messageIsError = false;
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
        clientWorker.shutdownNow();
    }

    private void recordStamp(@Nullable Path path) {
        try {
            stampTime = path == null ? null : Files.getLastModifiedTime(path);
            stampSize = path == null ? -1 : Files.size(path);
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
        var path = SpawnSource.find(folder, NPCS);
        if (path == null) {
            throw new IOException("No " + NPCS + " in " + folder);
        }
        var started = System.nanoTime();
        var data = NpcDataFile.read(path);
        var itemData = SpawnSource.find(folder, "itemdata.txt");
        var items = ServerItems.read(itemData, itemData == null ? SpawnSource.find(folder, "item_pch.txt") : null);
        var skillData = SpawnSource.find(folder, "skilldata.txt");
        var skills = ServerSkills.read(skillData, skillData == null ? SpawnSource.find(folder, "skill_pch.txt") : null);
        var missing = new StringBuilder();
        if (items.size() == 0) {
            missing.append("No itemdata.txt or item_pch.txt: item names are not checked. ");
        }
        if (skills.size() == 0) {
            missing.append("No skilldata.txt or skill_pch.txt: skill names are not checked.");
        }
        LogManager.getLogger(NpcDataSource.class)
                .info(
                        "Read {} with {} NPCs, {} items and {} skills in {} ms",
                        path,
                        data.size(),
                        items.size(),
                        skills.size(),
                        (System.nanoTime() - started) / 1_000_000);
        var pchFile = SpawnSource.find(folder, PCH);
        var pch = pchFile == null ? null : NpcPch.parse(SpawnText.read(pchFile));
        return new Loaded(path, data, items, skills, missing.toString().strip(), pchFile, pch);
    }

    private static @Nullable String currentPchText(Path path) {
        try {
            return SpawnText.read(path).text();
        } catch (IOException unreadable) {
            return null;
        }
    }

    private static void write(Path target, byte[] bytes) throws IOException {
        var temporary = Files.createTempFile(target.toAbsolutePath().getParent(), ".npcdata-", ".tmp");
        try {
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String causeMessage(Exception failure) {
        var cause = failure.getCause() == null ? failure : failure.getCause();
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
