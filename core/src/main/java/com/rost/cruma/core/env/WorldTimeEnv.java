package com.rost.cruma.core.env;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class WorldTimeEnv {
    private static final int PRESETS = 4;

    private WorldTimeEnv() {}

    public static List<Path> files(Path clientRoot) {
        var system = clientRoot.resolve("system");
        var files = new ArrayList<Path>();
        for (var index = 0; index < PRESETS; index++) {
            var file = system.resolve("timeenv" + index + ".int");
            if (!Files.isRegularFile(file)) {
                break;
            }
            files.add(file);
        }
        if (files.isEmpty()) {
            var envInt = system.resolve("env.int");
            if (Files.isRegularFile(envInt)) {
                files.add(envInt);
            }
        }
        return List.copyOf(files);
    }

    public static List<Path> lightSets(Path clientRoot, String family) {
        var system = clientRoot.resolve("system");
        var envInt = LobbyTimeEnv.find(system, "env.int");
        if (envInt == null) {
            return List.of();
        }
        List<String> names;
        try {
            names = EnvSetupParser.timeEnvFiles(envInt, family);
        } catch (RuntimeException unreadable) {
            return List.of();
        }
        var files = new ArrayList<Path>();
        for (var name : names) {
            var file = LobbyTimeEnv.find(system, name);
            if (file == null) {
                return List.of();
            }
            files.add(file);
        }
        return List.copyOf(files);
    }
}
