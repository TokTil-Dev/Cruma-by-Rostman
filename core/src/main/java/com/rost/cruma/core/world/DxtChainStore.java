package com.rost.cruma.core.world;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

final class DxtChainStore {
    private static final int MAGIC = 0x4C32444D;
    private static final int VERSION = 1;
    private static final int MAX_LEVELS = 32;
    private static final int MAX_LEVEL_BYTES = 1 << 28;
    private static final int BUFFER_BYTES = 1 << 16;

    private final Path directory;

    DxtChainStore(Path directory) throws IOException {
        this.directory = directory;
        Files.createDirectories(directory);
    }

    static Path clientDirectory(Path root, Path clientRoot) {
        var absolute = clientRoot.toAbsolutePath().normalize();
        var folder = absolute.getFileName() == null
                ? "client"
                : absolute.getFileName().toString();
        var safeFolder = folder.replaceAll("[^A-Za-z0-9._-]", "_");
        var hash = sha256Hex(absolute.toString().toLowerCase(Locale.ROOT)).substring(0, 16);
        return root.resolve(safeFolder + "-" + hash);
    }

    List<byte[]> load(String key) {
        var file = fileFor(key);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try (var in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file), BUFFER_BYTES))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) {
                return null;
            }
            var count = in.readInt();
            if (count < 0 || count > MAX_LEVELS) {
                return null;
            }
            var levels = new ArrayList<byte[]>(count);
            for (var level = 0; level < count; level++) {
                var length = in.readInt();
                if (length < 0 || length > MAX_LEVEL_BYTES) {
                    return null;
                }
                var bytes = new byte[length];
                in.readFully(bytes);
                levels.add(bytes);
            }
            return List.copyOf(levels);
        } catch (IOException e) {
            return null;
        }
    }

    void save(String key, List<byte[]> tail) throws IOException {
        var file = fileFor(key);
        if (Files.exists(file)) {
            return;
        }
        var temp = Files.createTempFile(directory, "chain-", ".tmp");
        try {
            try (var out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temp), BUFFER_BYTES))) {
                out.writeInt(MAGIC);
                out.writeInt(VERSION);
                out.writeInt(tail.size());
                for (var level : tail) {
                    out.writeInt(level.length);
                    out.write(level);
                }
            }
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException raced) {
                if (!Files.exists(file)) {
                    throw raced;
                }
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private Path fileFor(String key) {
        return directory.resolve(sha256Hex(key) + ".dxtmips");
    }

    private static String sha256Hex(String text) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
        return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
    }
}
