package com.rost.cruma.viewer.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.logging.log4j.LogManager;

final class AgentStop {
    private static final String PREFIX = "stopped-";

    private AgentStop() {}

    static void mark(Path folder, boolean stopped) {
        var file = folder.resolve(PREFIX + ProcessHandle.current().pid());
        try {
            if (stopped) {
                Files.createDirectories(folder);
                Files.writeString(file, "");
            } else {
                Files.deleteIfExists(file);
            }
        } catch (IOException failed) {
            LogManager.getLogger(AgentStop.class).warn("Could not write {}", file, failed);
        }
    }

    static boolean isActive(Path folder) {
        if (!Files.isDirectory(folder)) {
            return false;
        }
        try (var files = Files.list(folder)) {
            for (var file : files.filter(AgentStop::isMark).toList()) {
                var pid = Long.parseLong(file.getFileName().toString().substring(PREFIX.length()));
                if (ProcessHandle.of(pid).filter(ProcessHandle::isAlive).isPresent()) {
                    return true;
                }
                Files.deleteIfExists(file);
            }
        } catch (IOException unreadable) {
            return true;
        }
        return false;
    }

    private static boolean isMark(Path file) {
        return file.getFileName().toString().matches(PREFIX + "\\d+");
    }
}
