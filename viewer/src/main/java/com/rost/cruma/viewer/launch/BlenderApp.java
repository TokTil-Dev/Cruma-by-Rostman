package com.rost.cruma.viewer.launch;

import com.rost.cruma.core.export.LocationExport;
import java.io.File;
import java.io.IOException;
import java.lang.ProcessBuilder.Redirect;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.apache.logging.log4j.LogManager;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.Platform;

public final class BlenderApp {
    private static volatile @Nullable Path found;
    private static volatile boolean searched;

    private BlenderApp() {}

    public static @Nullable Path find() {
        if (!searched) {
            found = search();
            searched = true;
        }
        return found;
    }

    public static boolean canOpen(@Nullable Path folder) {
        return folder != null && Files.isRegularFile(folder.resolve(LocationExport.BLENDER_SCRIPT));
    }

    public static void open(Path blender, Path folder) throws IOException {
        var script = folder.resolve(LocationExport.BLENDER_SCRIPT);
        new ProcessBuilder(blender.toString(), "--python", script.toString(), "--", folder.toString())
                .directory(folder.toFile())
                .redirectOutput(Redirect.DISCARD)
                .redirectError(Redirect.DISCARD)
                .start();
        LogManager.getLogger(BlenderApp.class).info("Opened {} in Blender {}", folder, blender);
    }

    private static @Nullable Path search() {
        var candidates = new ArrayList<Path>();
        var home = Path.of(System.getProperty("user.home"));
        switch (Platform.get()) {
            case MACOSX -> {
                for (var applications : List.of(Path.of("/Applications"), home.resolve("Applications"))) {
                    for (var app : listByPrefix(applications, "blender")) {
                        candidates.add(app.resolve("Contents/MacOS/Blender"));
                    }
                }
            }
            case WINDOWS -> {
                for (var variable : List.of("ProgramFiles", "ProgramFiles(x86)")) {
                    var root = System.getenv(variable);
                    if (root != null) {
                        for (var folder : listByPrefix(Path.of(root, "Blender Foundation"), "blender")) {
                            candidates.add(folder.resolve("blender.exe"));
                        }
                    }
                }
                var local = System.getenv("LOCALAPPDATA");
                if (local != null) {
                    for (var folder : listByPrefix(Path.of(local, "Programs", "Blender Foundation"), "blender")) {
                        candidates.add(folder.resolve("blender.exe"));
                    }
                }
            }
            default -> {
                candidates.add(Path.of("/snap/bin/blender"));
                candidates.add(Path.of("/usr/bin/blender"));
                candidates.add(Path.of("/usr/local/bin/blender"));
            }
        }
        var pathVariable = System.getenv("PATH");
        if (pathVariable != null) {
            for (var entry : pathVariable.split(File.pathSeparator)) {
                if (!entry.isBlank()) {
                    candidates.add(Path.of(entry, Platform.get() == Platform.WINDOWS ? "blender.exe" : "blender"));
                }
            }
        }
        for (var candidate : candidates) {
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static List<Path> listByPrefix(Path folder, String prefix) {
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (var entries = Files.list(folder)) {
            return entries.filter(entry -> entry.getFileName()
                            .toString()
                            .toLowerCase(Locale.ROOT)
                            .startsWith(prefix))
                    .sorted(Comparator.comparing(
                                    (Path entry) -> entry.getFileName().toString())
                            .reversed())
                    .toList();
        } catch (IOException | RuntimeException unreadable) {
            return List.of();
        }
    }
}
