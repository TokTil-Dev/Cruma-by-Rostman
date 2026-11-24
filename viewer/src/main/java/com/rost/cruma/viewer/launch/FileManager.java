package com.rost.cruma.viewer.launch;

import java.io.IOException;
import java.lang.ProcessBuilder.Redirect;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.system.Platform;

public final class FileManager {
    private static final Logger log = LogManager.getLogger(FileManager.class);

    private FileManager() {}

    public static void openFolder(Path directory) throws IOException {
        var folder = directory.toAbsolutePath().normalize();
        if (!Files.isDirectory(folder)) {
            throw new IOException("Result folder does not exist: " + folder);
        }
        var command = switch (Platform.get()) {
            case MACOSX -> List.of("/usr/bin/open", folder.toString());
            case WINDOWS -> List.of("explorer.exe", folder.toString());
            case LINUX -> List.of("xdg-open", folder.toString());
            default -> throw new IOException("Opening folders is not supported on " + Platform.get());
        };
        var process = new ProcessBuilder(command)
                .redirectOutput(Redirect.DISCARD)
                .redirectError(Redirect.INHERIT)
                .start();
        if (Platform.get() == Platform.MACOSX) {
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    throw new IOException("Finder did not accept the folder request in time: " + folder);
                }
                if (process.exitValue() != 0) {
                    throw new IOException(
                            "Finder rejected the folder request (exit " + process.exitValue() + "): " + folder);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Opening result folder was interrupted", interrupted);
            }
        }
        log.info("Requested result folder: {}", folder);
    }

    public static void tryOpenFolder(Path directory) {
        try {
            openFolder(directory);
        } catch (IOException | RuntimeException failure) {
            log.warn("Images were saved, but the result folder could not be opened: {}", directory, failure);
        }
    }

    public static void tryOpen(Path file) {
        try {
            var target = file.toAbsolutePath().normalize();
            if (!Files.isRegularFile(target)) {
                throw new IOException("Result file does not exist: " + target);
            }
            var command = switch (Platform.get()) {
                case MACOSX -> List.of("/usr/bin/open", target.toString());
                case WINDOWS -> List.of("cmd.exe", "/c", "start", "", target.toString());
                case LINUX -> List.of("xdg-open", target.toString());
                default -> throw new IOException("Opening files is not supported on " + Platform.get());
            };
            new ProcessBuilder(command)
                    .redirectOutput(Redirect.DISCARD)
                    .redirectError(Redirect.DISCARD)
                    .start();
        } catch (IOException | RuntimeException failure) {
            log.warn("The file could not be opened: {}", file, failure);
        }
    }

    public static void tryShow(Path path) {
        try {
            var target = path.toAbsolutePath().normalize();
            if (Files.isDirectory(target)) {
                openFolder(target);
                return;
            }
            if (!Files.exists(target)) {
                throw new IOException("Result file does not exist: " + target);
            }
            var command = switch (Platform.get()) {
                case MACOSX -> List.of("/usr/bin/open", "-R", target.toString());
                case WINDOWS -> List.of("explorer.exe", "/select," + target);
                default -> null;
            };
            if (command == null) {
                openFolder(target.getParent());
                return;
            }
            new ProcessBuilder(command)
                    .redirectOutput(Redirect.DISCARD)
                    .redirectError(Redirect.INHERIT)
                    .start();
            log.info("Requested to show {}", target);
        } catch (IOException | RuntimeException failure) {
            log.warn("The result could not be shown: {}", path, failure);
        }
    }
}
