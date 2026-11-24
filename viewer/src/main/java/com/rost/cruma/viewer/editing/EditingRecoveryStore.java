package com.rost.cruma.viewer.editing;

import com.rost.cruma.viewer.editing.EditingRecoveryCodec.Capture;
import com.rost.cruma.viewer.editing.EditingSession.RecoveryState;
import com.rost.cruma.viewer.editing.TerrainRecoveryCodec.State;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

final class EditingRecoveryStore {
    private final Path directory;
    private final long limit;
    private final int generations;
    private final Fault fault;

    EditingRecoveryStore(Path directory, long limit, int generations) {
        this(directory, limit, generations, stage -> {});
    }

    EditingRecoveryStore(Path directory, long limit, int generations, Fault fault) {
        this.directory = directory.toAbsolutePath().normalize();
        this.limit = limit;
        this.generations = generations;
        this.fault = fault;
    }

    static final class UnsupportedFormat extends IOException {
        UnsupportedFormat(String message) {
            super(message);
        }
    }

    enum Stage {
        AFTER_ASSETS,
        AFTER_PAYLOAD,
        BEFORE_PUBLISH,
        AFTER_PUBLISH
    }

    @FunctionalInterface
    interface Fault {
        void at(Stage stage) throws IOException;
    }

    record Candidate(Path directory, long revision, Instant savedAt, int maps, long bytes) {}

    record Restored(RecoveryState state, State terrain, Candidate candidate, String warning) {}

    Path getDirectory() {
        return directory;
    }

    List<Candidate> getCandidates() throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        var result = new ArrayList<Candidate>();
        try (var entries = Files.list(directory)) {
            for (var entry : entries.filter(
                            path -> path.getFileName().toString().startsWith("generation-"))
                    .toList()) {
                try {
                    var values = readMetadata(entry);
                    validateGeneration(entry, values);
                    result.add(new Candidate(
                            entry,
                            Long.parseLong(values.getProperty("revision")),
                            Instant.ofEpochMilli(Long.parseLong(values.getProperty("savedAt"))),
                            Integer.parseInt(values.getProperty("maps")),
                            Long.parseLong(values.getProperty("bytes"))));
                } catch (UnsupportedFormat future) {
                    throw future;
                } catch (IOException | RuntimeException invalid) {
                }
            }
        }
        result.sort(Comparator.comparing(Candidate::savedAt)
                .reversed()
                .thenComparing(value -> value.directory().toString(), Comparator.reverseOrder()));
        return List.copyOf(result);
    }

    Candidate write(Capture capture) throws IOException {
        Files.createDirectories(directory);
        prune();
        var staging = directory.resolve("pending-" + UUID.randomUUID());
        Files.createDirectory(staging);
        var published = false;
        try {
            var body = capture.encode(staging.resolve("assets"), limit);
            fault.at(Stage.AFTER_ASSETS);
            if (body.length > limit || treeBytes(staging) + body.length > limit) {
                throw new IOException("Recovery draft exceeds the configured disk limit.");
            }
            writeDurably(staging.resolve("session.bin"), body);
            fault.at(Stage.AFTER_PAYLOAD);
            var previous = getCandidates();
            var previousMillis =
                    previous.isEmpty() ? 0 : previous.getFirst().savedAt().toEpochMilli();
            var now = Instant.ofEpochMilli(Math.max(System.currentTimeMillis(), previousMillis + 1));
            var values = new Properties();
            values.setProperty("format", "1");
            values.setProperty("revision", Long.toString(capture.state().revision()));
            values.setProperty("savedAt", Long.toString(now.toEpochMilli()));
            values.setProperty("maps", Integer.toString(capture.mapCount()));
            values.setProperty("bytes", Long.toString(treeBytes(staging)));
            values.setProperty("sha256", EditingRecoveryCodec.hash(body));
            var metadata = new ByteArrayOutputStream();
            values.store(metadata, "Cruma editor recovery generation");
            writeDurably(staging.resolve("manifest.properties"), metadata.toByteArray());
            fault.at(Stage.BEFORE_PUBLISH);
            var destination = directory.resolve("generation-" + now.toEpochMilli() + "-" + UUID.randomUUID());
            try {
                Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                throw new IOException("Recovery directory does not support atomic publication.", unsupported);
            }
            published = true;
            fault.at(Stage.AFTER_PUBLISH);
            var candidate = new Candidate(
                    destination,
                    capture.state().revision(),
                    now,
                    capture.mapCount(),
                    Long.parseLong(values.getProperty("bytes")));
            prune();
            var retainedHashes = new HashSet<String>();
            for (var asset : capture.copiedAssets()) {
                retainedHashes.add(asset.fingerprint());
            }
            var activeAssets = directory.resolve("active-assets");
            if (Files.isDirectory(activeAssets)) {
                try (var entries = Files.list(activeAssets)) {
                    for (var entry : entries.toList()) {
                        if (!retainedHashes.contains(entry.getFileName().toString())) {
                            deleteOwned(entry);
                        }
                    }
                }
            }
            return candidate;
        } finally {
            if (!published) {
                deleteOwned(staging);
            }
        }
    }

    Restored restore(Path clientRoot) throws IOException {
        var failures = new ArrayList<String>();
        for (var candidate : getCandidates()) {
            try {
                var values = readMetadata(candidate.directory());
                var file = candidate.directory().resolve("session.bin");
                if (Files.size(file) > limit) {
                    throw new IOException("Recovery state exceeds its disk limit.");
                }
                var body = Files.readAllBytes(file);
                if (!EditingRecoveryCodec.hash(body).equals(values.getProperty("sha256"))) {
                    throw new IOException("Recovery checksum does not match.");
                }
                var state = EditingRecoveryCodec.decodeWorkspace(
                        clientRoot, candidate.directory().resolve("assets"), directory.resolve("active-assets"), body);
                return new Restored(
                        state.state(),
                        state.terrain(),
                        candidate,
                        failures.isEmpty()
                                ? ""
                                : "Restored the previous valid generation. The newest draft was incomplete or"
                                        + " unavailable.");
            } catch (UnsupportedFormat future) {
                throw future;
            } catch (IOException | RuntimeException failure) {
                failures.add(failure.getMessage());
            }
        }
        throw new IOException(
                failures.isEmpty()
                        ? "No recovery generation is available."
                        : "Recovery could not be verified: " + String.join("; ", failures));
    }

    void discard() throws IOException {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (var entries = Files.list(directory)) {
            for (var entry : entries.toList()) {
                if (entry.getFileName().toString().startsWith("generation-")
                        || entry.getFileName().toString().startsWith("pending-")
                        || entry.getFileName().toString().equals("active-assets")) {
                    deleteOwned(entry);
                }
            }
        }
    }

    private void prune() throws IOException {
        var current = getCandidates();
        for (var index = generations; index < current.size(); index++) {
            deleteOwned(current.get(index).directory());
        }
        var valid = new HashSet<Path>();
        for (var candidate : current) {
            valid.add(candidate.directory());
        }
        try (var entries = Files.list(directory)) {
            for (var entry : entries.toList()) {
                var name = entry.getFileName().toString();
                if (name.startsWith("pending-") || name.startsWith("generation-") && !valid.contains(entry)) {
                    deleteOwned(entry);
                }
            }
        }
    }

    private void validateGeneration(Path generation, Properties values) throws IOException {
        var payload = generation.resolve("session.bin");
        if (!Files.isRegularFile(payload) || Files.size(payload) > limit) {
            throw new IOException("Invalid recovery payload size.");
        }
        var body = Files.readAllBytes(payload);
        if (!EditingRecoveryCodec.hash(body).equals(values.getProperty("sha256"))) {
            throw new IOException("Invalid recovery payload checksum.");
        }
        EditingRecoveryCodec.verifyStoredAssets(generation.resolve("assets"), body, limit);
    }

    private Properties readMetadata(Path generation) throws IOException {
        var file = generation.resolve("manifest.properties");
        if (!Files.isRegularFile(file) || Files.size(file) > 65536) {
            throw new IOException("Invalid recovery manifest.");
        }
        var result = new Properties();
        try (var input = Files.newInputStream(file)) {
            result.load(input);
        }
        if (result.getProperty("format") == null) {
            throw new IOException("Invalid recovery manifest.");
        }
        if (!"1".equals(result.getProperty("format"))) {
            throw new UnsupportedFormat(
                    "This recovery draft uses an unsupported application version. Open it with the matching version,"
                            + " or explicitly discard it.");
        }
        return result;
    }

    private void deleteOwned(Path path) throws IOException {
        var target = path.toAbsolutePath().normalize();
        if (target.equals(directory) || !target.startsWith(directory)) {
            throw new IOException("Recovery cleanup path escaped its managed directory.");
        }
        if (!Files.exists(target)) {
            return;
        }
        try (var entries = Files.walk(target)) {
            for (var entry : entries.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(entry);
            }
        }
    }

    private static long treeBytes(Path path) throws IOException {
        try (var files = Files.walk(path)) {
            var total = 0L;
            for (var file : files.filter(Files::isRegularFile).toList()) {
                total = Math.addExact(total, Files.size(file));
            }
            return total;
        }
    }

    private static void writeDurably(Path path, byte[] bytes) throws IOException {
        try (var channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            var buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
    }
}
