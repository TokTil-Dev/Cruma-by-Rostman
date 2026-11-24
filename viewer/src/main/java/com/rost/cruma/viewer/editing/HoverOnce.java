package com.rost.cruma.viewer.editing;

import java.util.Objects;
import org.jetbrains.annotations.Nullable;

final class HoverOnce {
    private @Nullable String key;
    private long since;
    private @Nullable String tried;

    void point(@Nullable String key, long now) {
        if (Objects.equals(key, this.key)) {
            return;
        }
        this.key = key;
        since = now;
        if (key == null) {
            tried = null;
        }
    }

    void again(long now) {
        tried = null;
        since = now;
    }

    boolean isDue(long now, long restNanos) {
        if (key == null || key.equals(tried) || now - since < restNanos) {
            return false;
        }
        tried = key;
        return true;
    }
}
