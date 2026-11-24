package com.rost.cruma.viewer.ui;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

public final class NativeDialogs {
    private static volatile boolean enabled;

    private NativeDialogs() {}

    public enum Outcome {
        CHOSEN,
        CANCELLED,
        UNAVAILABLE
    }

    public record Result(Outcome outcome, @Nullable Path path) {
        static final Result CANCELLED = new Result(Outcome.CANCELLED, null);
        static final Result UNAVAILABLE = new Result(Outcome.UNAVAILABLE, null);
    }

    public static void enable() {
        var os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        enabled = os.contains("win") || os.contains("mac");
    }

    public static boolean isAvailable() {
        return enabled;
    }

    public static Result chooseFolder(String title, @Nullable Path initial) {
        if (!enabled) {
            return Result.UNAVAILABLE;
        }
        try {
            var chosen = TinyFileDialogs.tinyfd_selectFolderDialog(title, start(initial));
            return toResult(chosen, true);
        } catch (LinkageError | RuntimeException unavailable) {
            enabled = false;
            return Result.UNAVAILABLE;
        }
    }

    public static Result chooseFile(String title, @Nullable Path initial, List<String> patterns, String description) {
        if (!enabled) {
            return Result.UNAVAILABLE;
        }
        try (var stack = MemoryStack.stackPush()) {
            var filters = stack.mallocPointer(patterns.size());
            for (var pattern : patterns) {
                filters.put(stack.UTF8(pattern));
            }
            filters.flip();
            var chosen = TinyFileDialogs.tinyfd_openFileDialog(title, start(initial), filters, description, false);
            return toResult(chosen, false);
        } catch (LinkageError | RuntimeException unavailable) {
            enabled = false;
            return Result.UNAVAILABLE;
        }
    }

    static String start(@Nullable Path initial) {
        var path = initial;
        while (path != null && !Files.isDirectory(path)) {
            path = path.getParent();
        }
        if (path == null) {
            path = Path.of(System.getProperty("user.home"));
        }
        var text = path.toAbsolutePath().toString();
        return text.endsWith(File.separator) ? text : text + File.separator;
    }

    static Result toResult(@Nullable String chosen, boolean folder) {
        if (chosen == null || chosen.isBlank()) {
            return Result.CANCELLED;
        }
        try {
            var path = Path.of(chosen);
            return folder == Files.isDirectory(path) && Files.exists(path)
                    ? new Result(Outcome.CHOSEN, path)
                    : Result.CANCELLED;
        } catch (InvalidPathException invalid) {
            return Result.CANCELLED;
        }
    }
}
