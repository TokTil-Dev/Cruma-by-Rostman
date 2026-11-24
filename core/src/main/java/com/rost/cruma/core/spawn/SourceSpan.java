package com.rost.cruma.core.spawn;

public record SourceSpan(int start, int end) {
    public static final SourceSpan NONE = new SourceSpan(-1, -1);

    public SourceSpan {
        if (start > end || (start < 0 && (start != -1 || end != -1))) {
            throw new IllegalArgumentException("Invalid span " + start + ".." + end);
        }
    }

    public boolean isPresent() {
        return start >= 0;
    }

    public int length() {
        return end - start;
    }
}
