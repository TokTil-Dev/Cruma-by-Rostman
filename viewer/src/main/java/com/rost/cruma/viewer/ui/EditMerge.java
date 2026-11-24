package com.rost.cruma.viewer.ui;

final class EditMerge {
    static final long MERGE_NANOS = 1_500_000_000L;

    private String lastKey = "";
    private long lastTime;
    private long lastRevision = -1;

    boolean shouldAmend(String key, long now, boolean canUndo, long revision) {
        return key.equals(lastKey) && now - lastTime < MERGE_NANOS && canUndo && revision == lastRevision;
    }

    void record(String key, long now, long revision) {
        lastKey = key;
        lastTime = now;
        lastRevision = revision;
    }

    void reset() {
        lastKey = "";
    }
}
