package com.rost.cruma.viewer.application;

import java.nio.file.Path;
import java.util.Locale;
import org.jetbrains.annotations.Nullable;

public record RecordLine(Kind kind, String text, int frame, int frames) {
    static final String PREFIX = "cruma-record ";

    public enum Kind {
        STAGE,
        FRAME,
        SKIPPED,
        DONE,
        FAILED
    }

    public static RecordLine stage(String text) {
        return new RecordLine(Kind.STAGE, text, 0, 0);
    }

    public static RecordLine frame(int frame, int frames) {
        return new RecordLine(Kind.FRAME, "", frame, frames);
    }

    public static RecordLine skipped(String names) {
        return new RecordLine(Kind.SKIPPED, names, 0, 0);
    }

    public static RecordLine done(Path file, int frames) {
        return new RecordLine(Kind.DONE, file.toString(), frames, frames);
    }

    public static RecordLine failed(String reason) {
        return new RecordLine(Kind.FAILED, reason, 0, 0);
    }

    public static @Nullable RecordLine read(String line) {
        if (!line.startsWith(PREFIX)) {
            return null;
        }
        var parts = line.substring(PREFIX.length()).split(" ", 4);
        if (parts.length < 3) {
            return null;
        }
        try {
            return new RecordLine(
                    Kind.valueOf(parts[0].toUpperCase(Locale.ROOT)),
                    parts.length == 4 ? parts[3] : "",
                    Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2]));
        } catch (IllegalArgumentException other) {
            return null;
        }
    }

    public String write() {
        var name = kind.name().toLowerCase(Locale.ROOT);
        return PREFIX + name + ' ' + frame + ' ' + frames + ' ' + text.replaceAll("\\R", " ");
    }
}
