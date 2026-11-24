package com.rost.cruma.viewer.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

final class SceneReading {
    static final long LONGEST_NANOS = 15_000_000_000L;

    private static final Logger log = LogManager.getLogger(SceneReading.class);

    private final LongSupplier clock;
    private final List<BooleanSupplier> parts = new ArrayList<>();
    private long since;

    SceneReading(LongSupplier clock) {
        this.clock = clock;
    }

    void start() {
        parts.clear();
        since = clock.getAsLong();
    }

    void add(BooleanSupplier read) {
        parts.add(read);
    }

    boolean isWaiting() {
        parts.removeIf(BooleanSupplier::getAsBoolean);
        if (parts.isEmpty()) {
            return false;
        }
        if (clock.getAsLong() - since < LONGEST_NANOS) {
            return true;
        }
        log.warn(
                "A cut scene goes on after {} seconds with {} parts not read",
                LONGEST_NANOS / 1_000_000_000L,
                parts.size());
        parts.clear();
        return false;
    }
}
