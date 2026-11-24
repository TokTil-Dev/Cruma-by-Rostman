package com.rost.cruma.viewer.editing;

import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

final class UserMessages {
    static final String TERRAIN_BUSY = "The Terrain is still busy. Try again in a moment.";

    static final String WRITE_FAILED = "The save did not finish. Your changes are kept. Check again and save.";

    static final String NOT_IN_MAP = "Actor is not present in its map: ";

    private static final Logger log = LogManager.getLogger(UserMessages.class);

    private static final List<String> TERRAIN = List.of(
            "Terrain UI operation requires the render thread",
            "Wait for the pending Terrain operation",
            "Terrain publication already pending",
            "Terrain operation is still in progress",
            "Terrain geometry is not loaded",
            "Finish the stroke first",
            "Finish the Terrain stroke first",
            "Finish the sculpt stroke first",
            "Finish the paint stroke first",
            "Finish the Terrain operation before");

    private static final List<String> WRITE = List.of(
            "Both Save checkpoints remain unchanged",
            "Export publication already awaiting approval",
            "Validate the combined export again",
            "Unified export requires the editing owner thread",
            "The editing session changed",
            "changed while export was being validated");

    private UserMessages() {}

    static @Nullable String plain(@Nullable String message) {
        if (message == null || message.isEmpty()) {
            return message;
        }
        var shown = replacement(message);
        if (shown == null) {
            return message;
        }
        log.debug("Shown as \"{}\": {}", shown, message);
        return shown;
    }

    static String plain(Throwable failure, String fallback) {
        var message = failure.getMessage();
        if (message == null || message.isBlank()) {
            log.debug("Shown as \"{}\"", fallback, failure);
            return fallback;
        }
        return plain(message);
    }

    private static @Nullable String replacement(String message) {
        if (message.startsWith(NOT_IN_MAP)) {
            return EditorUi.cannotEdit(message.substring(NOT_IN_MAP.length()).strip());
        }
        for (var part : TERRAIN) {
            if (message.contains(part)) {
                return TERRAIN_BUSY;
            }
        }
        for (var part : WRITE) {
            if (message.contains(part)) {
                return WRITE_FAILED;
            }
        }
        return null;
    }
}
