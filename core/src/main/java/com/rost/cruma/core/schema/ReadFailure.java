package com.rost.cruma.core.schema;

public record ReadFailure(int row, int offset, String path, String reason, String where) {
    public String describe() {
        var text = new StringBuilder();
        if (row >= 0) {
            text.append("row ").append(row).append(", ");
        }
        text.append("byte ").append(offset);
        if (!path.isEmpty()) {
            text.append(", ").append(path);
        }
        if (!where.isEmpty()) {
            text.append(" (").append(where).append(')');
        }
        return text.append(": ").append(reason).toString();
    }
}
