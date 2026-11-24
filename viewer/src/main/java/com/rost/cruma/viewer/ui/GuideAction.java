package com.rost.cruma.viewer.ui;

import org.jetbrains.annotations.Nullable;

public interface GuideAction {
    String getId();

    @Nullable
    String getBlocked(GuideHost host);

    void run(GuideHost host);

    default @Nullable String getStatus(GuideHost host) {
        return null;
    }

    default boolean isBusy() {
        return false;
    }

    default void reset() {}
}
