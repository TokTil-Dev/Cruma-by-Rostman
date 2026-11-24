package com.rost.cruma.viewer.agent;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.jetbrains.annotations.Nullable;

public final class Job {
    private final String id;
    private final String tool;
    private final CompletableFuture<ToolResult> result = new CompletableFuture<>();
    private volatile String progress = "";
    private volatile boolean cancelled;
    private @Nullable Runnable onCancel;
    private volatile @Nullable Consumer<String> onProgress;

    public Job(String id, String tool) {
        this.id = id;
        this.tool = tool;
    }

    public String getId() {
        return id;
    }

    public String getTool() {
        return tool;
    }

    public String getProgress() {
        return progress;
    }

    public void setProgress(String text) {
        if (text.equals(progress)) {
            return;
        }
        progress = text;
        var listener = onProgress;
        if (listener != null) {
            listener.accept(text);
        }
    }

    void onProgress(Consumer<String> listener) {
        onProgress = listener;
        var now = progress;
        if (!now.isEmpty()) {
            listener.accept(now);
        }
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public void onCancel(Runnable action) {
        boolean late;
        synchronized (this) {
            onCancel = action;
            late = cancelled;
        }
        if (late) {
            action.run();
        }
    }

    public void cancel() {
        Runnable action;
        synchronized (this) {
            cancelled = true;
            action = onCancel;
        }
        if (action != null) {
            action.run();
        }
    }

    CompletableFuture<ToolResult> getResult() {
        return result;
    }
}
