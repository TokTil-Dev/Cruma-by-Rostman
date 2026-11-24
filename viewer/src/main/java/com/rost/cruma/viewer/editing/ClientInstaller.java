package com.rost.cruma.viewer.editing;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

public final class ClientInstaller {
    static final String BACKUP_DIRECTORY = "client-backup";
    static final String BACKUP_RECORD = "backup.json";
    private static final String SCHEMA = "cruma.editor.install/v1";

    private ClientInstaller() {}

    public enum State {
        NEW,
        ORIGINAL,
        INSTALLED,
        CHANGED
    }

    public record Item(
            String path, String exportHash, @Nullable String originalHash, State state) {}

    public record Plan(Path export, Path client, List<Item> items) {
        public Plan {
            items = List.copyOf(items);
        }

        public List<Item> changed() {
            return items.stream().filter(item -> item.state() == State.CHANGED).toList();
        }

        public boolean isInstalled() {
            return !items.isEmpty() && items.stream().allMatch(item -> item.state() == State.INSTALLED);
        }

        public @Nullable String problem() {
            if (items.isEmpty()) {
                return "The export contains no packages.";
            }
            if (!changed().isEmpty()) {
                return changed().size() == 1
                        ? "The client file " + changed().getFirst().path()
                                + " changed after the export. Export again from the current client."
                        : changed().size()
                                + " client files changed after the export. Export again from the current client.";
            }
            if (isInstalled()) {
                return "The client already has this export.";
            }
            if (items.stream().anyMatch(item -> item.state() == State.INSTALLED)) {
                return "Part of this export is already in the client. Restore the client first.";
            }
            return null;
        }
    }

    public static Plan inspect(Path export, Path client) throws IOException {
        export = export.toAbsolutePath().normalize();
        client = client.toAbsolutePath().normalize();
        if (!Files.isDirectory(client)) {
            throw new IOException("Client folder does not exist: " + client);
        }
        if (export.startsWith(client)) {
            throw new IOException("The export folder is inside the client folder.");
        }
        var manifest = readJson(export.resolve("manifest.json"), "export manifest");
        var packages = manifest.getJSONArray("packages");
        if (packages == null) {
            throw new IOException("The export manifest lists no packages.");
        }
        var sources = manifest.getJSONObject("clientSources");
        var items = new ArrayList<Item>();
        for (var i = 0; i < packages.size(); i++) {
            var entry = packages.getJSONObject(i);
            var path = requireRelative(entry.getString("path"));
            var exportHash = sha256(entry.getString("sha256"), path);
            var exported = export.resolve(path);
            if (!Files.isRegularFile(exported)
                    || !PackageExportService.fileFingerprint(exported).equals(exportHash)) {
                throw new IOException("The exported file " + path + " is missing or changed.");
            }
            var original = sources == null ? null : sources.getString(path);
            if (original != null) {
                sha256(original, path);
            }
            var target = resolveTarget(client, path);
            State state;
            if (!Files.exists(target)) {
                state = original == null ? State.NEW : State.CHANGED;
            } else {
                var current = PackageExportService.fileFingerprint(target);
                if (current.equals(exportHash)) {
                    state = State.INSTALLED;
                } else if (current.equals(original)) {
                    state = State.ORIGINAL;
                } else {
                    state = State.CHANGED;
                }
            }
            items.add(new Item(path, exportHash, original, state));
        }
        return new Plan(export, client, items);
    }

    public static List<Path> install(Plan plan) throws IOException {
        var problem = plan.problem();
        if (problem != null) {
            throw new IOException(problem);
        }
        var current = inspect(plan.export(), plan.client());
        if (!Set.copyOf(current.items()).equals(Set.copyOf(plan.items()))) {
            throw new IOException("The client folder changed. Check the installation again.");
        }
        var backup = plan.export().resolve(BACKUP_DIRECTORY);
        var record = backup.resolve(BACKUP_RECORD);
        if (Files.exists(record)
                && "installed".equals(readJson(record, "client backup").getString("state"))) {
            throw new IOException("This export is already installed in "
                    + readJson(record, "client backup").getString("client") + ". Restore that client first.");
        }
        Files.createDirectories(backup);
        for (var item : plan.items()) {
            if (item.state() == State.ORIGINAL) {
                var saved = backup.resolve(item.path());
                Files.createDirectories(saved.getParent());
                replace(saved, Files.readAllBytes(resolveTarget(plan.client(), item.path())));
                if (!PackageExportService.fileFingerprint(saved).equals(item.originalHash())) {
                    throw new IOException("The backup of " + item.path() + " does not match the client file.");
                }
            }
        }
        writeRecord(record, plan, "installing");
        var replaced = new ArrayList<Item>();
        try {
            for (var item : plan.items()) {
                var target = resolveTarget(plan.client(), item.path());
                Files.createDirectories(target.getParent());
                replace(target, Files.readAllBytes(plan.export().resolve(item.path())));
                replaced.add(item);
                if (!PackageExportService.fileFingerprint(target).equals(item.exportHash())) {
                    throw new IOException("The installed file " + item.path() + " does not match the export.");
                }
            }
        } catch (IOException | RuntimeException failure) {
            var rollback = putBack(plan.client(), backup, replaced.reversed());
            writeRecord(record, plan, rollback.isEmpty() ? "restored" : "installing");
            if (!rollback.isEmpty()) {
                failure.addSuppressed(new IOException("Could not put back: " + String.join(", ", rollback)));
            }
            throw failure;
        }
        writeRecord(record, plan, "installed");
        return plan.items().stream()
                .map(item -> resolveTarget(plan.client(), item.path()))
                .toList();
    }

    public static @Nullable Path installedClient(Path export) {
        var record =
                export.toAbsolutePath().normalize().resolve(BACKUP_DIRECTORY).resolve(BACKUP_RECORD);
        try {
            var json = readJson(record, "client backup");
            return "installed".equals(json.getString("state")) ? Path.of(json.getString("client")) : null;
        } catch (IOException | RuntimeException failure) {
            return null;
        }
    }

    public static int @Nullable [] installedFiles(Path export) {
        var record =
                export.toAbsolutePath().normalize().resolve(BACKUP_DIRECTORY).resolve(BACKUP_RECORD);
        try {
            var files = readJson(record, "client backup").getJSONArray("files");
            var replaced = 0;
            for (var i = 0; i < files.size(); i++) {
                if (files.getJSONObject(i).getString("originalHash") != null) {
                    replaced++;
                }
            }
            return new int[] {replaced, files.size() - replaced};
        } catch (IOException | RuntimeException failure) {
            return null;
        }
    }

    public static List<Path> restore(Path export) throws IOException {
        export = export.toAbsolutePath().normalize();
        var backup = export.resolve(BACKUP_DIRECTORY);
        var record = backup.resolve(BACKUP_RECORD);
        var json = readJson(record, "client backup");
        if (!SCHEMA.equals(json.getString("schema")) || !"installed".equals(json.getString("state"))) {
            throw new IOException("This export is not installed in a client.");
        }
        var client = Path.of(json.getString("client")).toAbsolutePath().normalize();
        var files = json.getJSONArray("files");
        var items = new ArrayList<Item>();
        var installed = new ArrayList<Item>();
        for (var i = 0; i < files.size(); i++) {
            var entry = files.getJSONObject(i);
            var path = requireRelative(entry.getString("path"));
            var exportHash = sha256(entry.getString("exportHash"), path);
            var original = entry.getString("originalHash");
            var target = resolveTarget(client, path);
            var currentHash = Files.exists(target) ? PackageExportService.fileFingerprint(target) : null;
            var item = new Item(path, exportHash, original, original == null ? State.NEW : State.ORIGINAL);
            items.add(item);
            if (Objects.equals(currentHash, original)) {
                continue;
            }
            if (!exportHash.equals(currentHash)) {
                throw new IOException(
                        "The client file " + path + " changed after the installation. Nothing was restored.");
            }
            if (original != null
                    && !PackageExportService.fileFingerprint(backup.resolve(path))
                            .equals(original)) {
                throw new IOException("The backup of " + path + " is damaged. Nothing was restored.");
            }
            installed.add(item);
        }
        var restored = new ArrayList<Item>();
        try {
            for (var item : installed) {
                restored.add(item);
                putBack(client, backup, item);
            }
        } catch (IOException | RuntimeException failure) {
            var left = reinstall(client, export, restored.reversed());
            var message =
                    "Could not restore " + restored.getLast().path() + ", so the client stays as the export left it.";
            throw new IOException(
                    left.isEmpty()
                            ? message
                            : message + " These files could not be put back: " + String.join(", ", left),
                    failure);
        }
        writeRecord(record, new Plan(export, client, items), "restored");
        return items.stream().map(item -> resolveTarget(client, item.path())).toList();
    }

    private static List<String> putBack(Path client, Path backup, List<Item> items) {
        var failed = new ArrayList<String>();
        for (var item : items) {
            try {
                putBack(client, backup, item);
            } catch (IOException | RuntimeException failure) {
                failed.add(item.path());
            }
        }
        return failed;
    }

    private static void putBack(Path client, Path backup, Item item) throws IOException {
        var target = resolveTarget(client, item.path());
        if (item.originalHash() == null || item.state() == State.NEW) {
            Files.deleteIfExists(target);
        } else {
            replace(target, Files.readAllBytes(backup.resolve(item.path())));
            if (!PackageExportService.fileFingerprint(target).equals(item.originalHash())) {
                throw new IOException("The restored file " + item.path() + " does not match its backup.");
            }
        }
    }

    private static List<String> reinstall(Path client, Path export, List<Item> items) {
        var failed = new ArrayList<String>();
        for (var item : items) {
            try {
                var target = resolveTarget(client, item.path());
                if (!Files.exists(target)
                        || !PackageExportService.fileFingerprint(target).equals(item.exportHash())) {
                    replace(target, Files.readAllBytes(export.resolve(item.path())));
                    if (!PackageExportService.fileFingerprint(target).equals(item.exportHash())) {
                        failed.add(item.path());
                    }
                }
            } catch (IOException | RuntimeException failure) {
                failed.add(item.path());
            }
        }
        return failed;
    }

    private static void writeRecord(Path record, Plan plan, String state) throws IOException {
        var files = new ArrayList<Map<String, Object>>();
        for (var item : plan.items()) {
            var file = new LinkedHashMap<String, Object>();
            file.put("path", item.path());
            file.put("exportHash", item.exportHash());
            file.put("originalHash", item.state() == State.NEW ? null : item.originalHash());
            files.add(file);
        }
        var json = new LinkedHashMap<String, Object>();
        json.put("schema", SCHEMA);
        json.put("state", state);
        json.put("client", plan.client().toString());
        json.put("files", files);
        replace(record, JSON.toJSONBytes(json));
    }

    private static void replace(Path target, byte[] bytes) throws IOException {
        var temporary = Files.createTempFile(target.getParent(), ".cruma-install-", ".tmp");
        try {
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                var buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static JSONObject readJson(Path file, String what) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IOException("No " + what + " in " + file.getParent());
        }
        try {
            var json = JSON.parseObject(Files.readString(file));
            if (json == null) {
                throw new IOException("Empty " + what);
            }
            return json;
        } catch (RuntimeException failure) {
            throw new IOException("Unreadable " + what + ": " + failure.getMessage(), failure);
        }
    }

    private static String requireRelative(@Nullable String path) throws IOException {
        if (path == null || path.isBlank() || path.startsWith("/") || path.startsWith("\\") || path.contains(":")) {
            throw new IOException("Invalid package path in the export: " + path);
        }
        var normalized = Path.of(path).normalize();
        if (normalized.startsWith("..") || normalized.isAbsolute()) {
            throw new IOException("Invalid package path in the export: " + path);
        }
        return normalized.toString().replace('\\', '/');
    }

    private static String sha256(@Nullable String value, String path) throws IOException {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IOException("Invalid SHA-256 for " + path + " in the export.");
        }
        return value;
    }

    private static Path resolveTarget(Path client, String path) {
        var target = client;
        for (var part : Path.of(path).normalize()) {
            target = existing(target, part.toString());
        }
        if (!target.startsWith(client)) {
            throw new IllegalArgumentException("Package path leaves the client folder: " + path);
        }
        return target;
    }

    private static Path existing(Path folder, String name) {
        var exact = folder.resolve(name);
        if (Files.exists(exact) || !Files.isDirectory(folder)) {
            return exact;
        }
        try (var entries = Files.list(folder)) {
            return entries.filter(entry -> entry.getFileName().toString().equalsIgnoreCase(name))
                    .findFirst()
                    .orElse(exact);
        } catch (IOException unreadable) {
            return exact;
        }
    }
}
