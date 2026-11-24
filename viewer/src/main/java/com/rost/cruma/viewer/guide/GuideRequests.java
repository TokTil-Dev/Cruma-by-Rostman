package com.rost.cruma.viewer.guide;

import org.jetbrains.annotations.Nullable;

public final class GuideRequests {
    private static @Nullable Request pending;

    private GuideRequests() {}

    public record Request(@Nullable String page, boolean toggle) {}

    public static void open(String page) {
        pending = new Request(page, false);
    }

    public static void openTopic() {
        pending = new Request(null, false);
    }

    public static void toggleTopic() {
        pending = new Request(null, true);
    }

    public static @Nullable Request take() {
        var request = pending;
        pending = null;
        return request;
    }
}
