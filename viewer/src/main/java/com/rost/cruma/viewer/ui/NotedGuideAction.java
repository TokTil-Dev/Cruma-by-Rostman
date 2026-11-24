package com.rost.cruma.viewer.ui;

import java.util.function.Function;
import org.jetbrains.annotations.Nullable;

final class NotedGuideAction implements GuideAction {
    private final String id;
    private final Function<GuideHost, @Nullable String> blocked;
    private final Function<GuideHost, @Nullable String> work;
    private @Nullable String note;

    NotedGuideAction(
            String id, Function<GuideHost, @Nullable String> blocked, Function<GuideHost, @Nullable String> work) {
        this.id = id;
        this.blocked = blocked;
        this.work = work;
    }

    static @Nullable String withoutTool(GuideHost host, String tool, String missing) {
        if (!host.hasWorld()) {
            return "Open a world first.";
        }
        if (host.hasTool(tool)) {
            return null;
        }
        var hidden = host.getToolHidden(tool);
        return hidden != null ? hidden : missing;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public @Nullable String getBlocked(GuideHost host) {
        return blocked.apply(host);
    }

    @Override
    public void run(GuideHost host) {
        note = work.apply(host);
    }

    @Override
    public @Nullable String getStatus(GuideHost host) {
        return note;
    }

    @Override
    public void reset() {
        note = null;
    }
}
