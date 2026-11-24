package com.rost.cruma.core.env;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.jetbrains.annotations.Nullable;

public final class LobbyTimeEnv {
    private static final String FAMILY = "LobbyTime";
    private static final String CLASSIC_FAMILY = "LobbyTime_Classic";

    private LobbyTimeEnv() {}

    public static boolean isLobby(@Nullable String map) {
        return map != null && map.toLowerCase(Locale.ROOT).startsWith("lobby");
    }

    public static List<Path> files(Path clientRoot, @Nullable String map) {
        if (!isLobby(map)) {
            return List.of();
        }
        var system = clientRoot.resolve("system");
        var envInt = find(system, "env.int");
        if (envInt == null) {
            return List.of();
        }
        var family = map.toLowerCase(Locale.ROOT).endsWith("_classic") ? CLASSIC_FAMILY : FAMILY;
        List<String> names;
        try {
            names = EnvSetupParser.timeEnvFiles(envInt, family);
        } catch (RuntimeException unreadable) {
            return List.of();
        }
        var files = new ArrayList<Path>();
        for (var name : names) {
            var file = find(system, name);
            if (file == null) {
                return List.of();
            }
            files.add(file);
        }
        return List.copyOf(files);
    }

    static @Nullable Path find(Path folder, String name) {
        if (!Files.isDirectory(folder)) {
            return null;
        }
        try (var files = Files.list(folder)) {
            return files.filter(file -> file.getFileName().toString().equalsIgnoreCase(name))
                    .filter(Files::isRegularFile)
                    .min(Comparator.comparing(
                            (Path file) -> !file.getFileName().toString().equals(name)))
                    .orElse(null);
        } catch (IOException unreadable) {
            return null;
        }
    }
}
