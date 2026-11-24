package com.rost.cruma.viewer.diagnostics;

import java.io.IOException;
import java.lang.ProcessBuilder.Redirect;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.lwjgl.system.Platform;

public final class ErrorDialog {
    private static final int MB_ICONERROR = 0x10;
    private static final int MB_SETFOREGROUND = 0x10000;

    private ErrorDialog() {}

    public static void show(String title, String message) {
        try {
            switch (Platform.get()) {
                case WINDOWS -> showWindows(title, message);
                case MACOSX ->
                    run(List.of(
                            "/usr/bin/osascript",
                            "-e",
                            "display alert "
                                    + appleScriptText(title)
                                    + " message "
                                    + appleScriptText(message)
                                    + " as critical"));
                case LINUX -> showLinux(title, message);
                default -> {
                }
            }
        } catch (Throwable failure) {
            LogManager.getLogger(ErrorDialog.class).warn("Cannot show the error message window", failure);
        }
    }

    static String appleScriptText(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    private static void showWindows(String title, String message) throws Throwable {
        try (var arena = Arena.ofConfined()) {
            var user32 = SymbolLookup.libraryLookup("user32", arena);
            var messageBox = Linker.nativeLinker()
                    .downcallHandle(
                            user32.findOrThrow("MessageBoxW"),
                            FunctionDescriptor.of(
                                    ValueLayout.JAVA_INT,
                                    ValueLayout.ADDRESS,
                                    ValueLayout.ADDRESS,
                                    ValueLayout.ADDRESS,
                                    ValueLayout.JAVA_INT));
            int ignored = (int) messageBox.invokeExact(
                    MemorySegment.NULL,
                    arena.allocateFrom(message, StandardCharsets.UTF_16LE),
                    arena.allocateFrom(title, StandardCharsets.UTF_16LE),
                    MB_ICONERROR | MB_SETFOREGROUND);
        }
    }

    private static void showLinux(String title, String message) throws Exception {
        for (var command : List.of(
                List.of("zenity", "--error", "--no-markup", "--title=" + title, "--text=" + message),
                List.of("kdialog", "--title", title, "--error", message))) {
            try {
                run(command);
                return;
            } catch (IOException missing) {
            }
        }
    }

    private static void run(List<String> command) throws Exception {
        var process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(Redirect.DISCARD)
                .start();
        if (!process.waitFor(30, TimeUnit.MINUTES)) {
            process.destroy();
        }
    }
}
