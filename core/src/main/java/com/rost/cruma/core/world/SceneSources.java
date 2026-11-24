package com.rost.cruma.core.world;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.jetbrains.annotations.Nullable;

public final class SceneSources {
    private static final String PACKAGE = "LineageSceneInfo.u";
    private static final String AI = "ai.obj";

    private SceneSources() {}

    public static String clientSha256(Path client) {
        var file = clientFile(client);
        return file == null ? "" : sha256(file);
    }

    public static @Nullable Path clientFile(Path client) {
        return find(client, "System", PACKAGE);
    }

    public static @Nullable Path mapFile(Path client, String map) {
        return find(client, "Maps", map + ".unr");
    }

    public static String mapSha256(Path client, String map) {
        var file = mapFile(client, map);
        return file == null ? "" : sha256(file);
    }

    public static String serverSha256(@Nullable Path scripts) {
        if (scripts != null) {
            for (var inside : List.of(".", "script", "scripts")) {
                var file = scripts.resolve(inside).resolve(AI);
                if (Files.isRegularFile(file)) {
                    return sha256(file);
                }
            }
        }
        return "";
    }

    private static @Nullable Path find(Path client, String folder, String name) {
        var directory = entry(client, folder);
        return directory == null ? null : entry(directory, name);
    }

    private static @Nullable Path entry(Path folder, String name) {
        try (var entries = Files.list(folder)) {
            return entries.filter(entry -> entry.getFileName().toString().equalsIgnoreCase(name))
                    .findFirst()
                    .orElse(null);
        } catch (IOException failure) {
            LogManager.getLogger(SceneSources.class).debug("Could not list {}: {}", folder, failure.toString());
            return null;
        }
    }

    private static String sha256(Path file) {
        try (InputStream stream =
                new DigestInputStream(Files.newInputStream(file), MessageDigest.getInstance("SHA-256"))) {
            stream.transferTo(OutputStream.nullOutputStream());
            return HexFormat.of()
                    .formatHex(((DigestInputStream) stream).getMessageDigest().digest());
        } catch (IOException | NoSuchAlgorithmException failure) {
            LogManager.getLogger(SceneSources.class)
                    .warn("Could not read {}: {}", file.getFileName(), failure.toString());
            return "";
        }
    }
}
