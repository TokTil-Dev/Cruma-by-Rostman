package com.rost.cruma.core.schema;

public final class SchemaException extends RuntimeException {
    private final String source;
    private final int line;
    private final String reason;

    public SchemaException(String source, int line, String reason) {
        super(line > 0 ? source + ":" + line + ": " + reason : source + ": " + reason);
        this.source = source;
        this.line = line;
        this.reason = reason;
    }

    public String getSource() {
        return source;
    }

    public int getLine() {
        return line;
    }

    public String getReason() {
        return reason;
    }
}
