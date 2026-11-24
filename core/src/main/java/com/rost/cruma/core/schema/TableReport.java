package com.rost.cruma.core.schema;

import java.nio.file.Path;
import java.util.List;

public record TableReport(
        String table,
        Path file,
        State state,
        String schemaTitle,
        String schemaSource,
        int rows,
        String lost,
        String problem,
        List<Attempt> attempts) {
    public static final String BUILT_IN = "built in";

    public TableReport {
        attempts = List.copyOf(attempts);
    }

    public enum State {
        READ,
        PARTIAL,
        NOT_READ,
        NOT_USED,
        NO_FILE
    }

    public enum Outcome {
        READ,
        PARTIAL,
        CHECK_FAILED,
        FAILED,
        SKIPPED
    }

    public record Attempt(String title, String source, Outcome outcome, int rowsRead, int offset, String reason) {}
}
