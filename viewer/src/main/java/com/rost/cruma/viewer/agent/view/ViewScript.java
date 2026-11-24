package com.rost.cruma.viewer.agent.view;

import com.rost.cruma.viewer.agent.Step;
import com.rost.cruma.viewer.agent.ToolProblem;
import com.rost.cruma.viewer.agent.ToolResult;
import com.rost.cruma.viewer.agent.ViewerContext;
import com.rost.cruma.viewer.render.Camera.Snapshot;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.jetbrains.annotations.Nullable;

final class ViewScript implements Step {
    @FunctionalInterface
    interface Body {
        ToolResult run(ViewScript script) throws Exception;
    }

    @FunctionalInterface
    interface Poll<T> {
        @Nullable
        T poll(ViewerContext viewer) throws ToolProblem;
    }

    @FunctionalInterface
    interface Read<T> {
        T read(ViewerContext viewer) throws ToolProblem;
    }

    @FunctionalInterface
    interface Change {
        void run(ViewerContext viewer) throws ToolProblem;
    }

    private static final long POLL_MILLIS = 20_000;

    private final ViewerContext viewer;
    private final AtomicReference<@Nullable Pending<?>> pending = new AtomicReference<>();
    private final CompletableFuture<ToolResult> result = new CompletableFuture<>();
    private final Thread thread;
    private volatile boolean needsDepth;
    private @Nullable Snapshot held;

    private ViewScript(ViewerContext viewer, Body body) {
        this.viewer = viewer;
        thread = Thread.ofPlatform().name("cruma-agent-script").daemon().unstarted(() -> {
            try {
                result.complete(body.run(this));
            } catch (InterruptedException stopped) {
                result.completeExceptionally(new ToolProblem("The call was stopped."));
            } catch (Exception | Error failure) {
                result.completeExceptionally(failure);
            }
        });
    }

    static ViewScript start(ViewerContext viewer, Body body) {
        var script = new ViewScript(viewer, body);
        script.thread.start();
        return script;
    }

    @Override
    public @Nullable ToolResult step() throws ToolProblem {
        if (needsDepth) {
            viewer.holdDepth();
        }
        var task = pending.get();
        if (task != null) {
            task.run(viewer);
        }
        if (!result.isDone()) {
            return null;
        }
        putBackCamera();
        try {
            return result.join();
        } catch (CompletionException failure) {
            if (failure.getCause() instanceof ToolProblem problem) {
                throw problem;
            }
            throw failure;
        }
    }

    @Override
    public void cancel() {
        thread.interrupt();
        putBackCamera();
    }

    <T> T poll(Poll<T> poll, long waitMillis, String reason) throws ToolProblem, InterruptedException {
        var task = new Pending<>(poll, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMillis), reason);
        pending.set(task);
        try {
            return task.answer.get();
        } catch (ExecutionException failure) {
            throw rethrown(failure.getCause());
        } finally {
            pending.compareAndSet(task, null);
        }
    }

    <T> T read(Read<T> read) throws ToolProblem, InterruptedException {
        return poll(read::read, POLL_MILLIS, "The viewer does not answer.");
    }

    void run(Change change) throws ToolProblem, InterruptedException {
        read(viewer -> {
            change.run(viewer);
            return Boolean.TRUE;
        });
    }

    void frames(int count) throws ToolProblem, InterruptedException {
        var left = new int[] {count};
        poll(viewer -> left[0]-- <= 0 ? Boolean.TRUE : null, POLL_MILLIS, "The viewer draws no frames.");
    }

    <T> T await(CompletableFuture<T> work, long waitMillis, String reason) throws ToolProblem, InterruptedException {
        try {
            return work.get(waitMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException late) {
            throw new ToolProblem(reason);
        } catch (ExecutionException failure) {
            throw rethrown(failure.getCause());
        }
    }

    void wantDepth() {
        needsDepth = true;
    }

    void holdCamera(ViewerContext context) {
        if (held == null) {
            held = context.scene().camera.snapshot();
        }
    }

    void keepCamera() {
        held = null;
    }

    void putCameraBack(ViewerContext context) {
        putBackCamera();
    }

    private void putBackCamera() {
        var pose = held;
        held = null;
        if (pose != null) {
            viewer.scene().camera.restoreSnapshot(pose);
            viewer.backend().onCameraRestored();
        }
    }

    private static ToolProblem rethrown(Throwable failure) throws ToolProblem {
        if (failure instanceof ToolProblem problem) {
            return problem;
        }
        if (failure instanceof RuntimeException unexpected) {
            throw unexpected;
        }
        if (failure instanceof Error fatal) {
            throw fatal;
        }
        return new ToolProblem(String.valueOf(failure));
    }

    private static final class Pending<T> {
        private final Poll<T> poll;
        private final long deadline;
        private final String reason;
        private final CompletableFuture<T> answer = new CompletableFuture<>();

        Pending(Poll<T> poll, long deadline, String reason) {
            this.poll = poll;
            this.deadline = deadline;
            this.reason = reason;
        }

        void run(ViewerContext viewer) {
            if (answer.isDone()) {
                return;
            }
            try {
                var value = poll.poll(viewer);
                if (value != null) {
                    answer.complete(value);
                } else if (System.nanoTime() > deadline) {
                    answer.completeExceptionally(new ToolProblem(reason));
                }
            } catch (ToolProblem | RuntimeException | Error failure) {
                answer.completeExceptionally(failure);
            }
        }
    }
}
