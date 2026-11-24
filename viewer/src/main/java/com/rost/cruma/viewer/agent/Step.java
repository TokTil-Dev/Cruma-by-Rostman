package com.rost.cruma.viewer.agent;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.jetbrains.annotations.Nullable;

@FunctionalInterface
public interface Step {
    static Step done(ToolResult result) {
        return () -> result;
    }

    static Step afterFrames(int frames, Answer answer) {
        var left = new int[] {frames};
        return () -> left[0]-- > 0 ? null : answer.get();
    }

    static Step until(BooleanSupplier ready, long timeoutMillis, String timeoutReason, Answer answer) {
        var deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        return () -> {
            if (ready.getAsBoolean()) {
                return answer.get();
            }
            if (System.nanoTime() > deadline) {
                throw new ToolProblem(timeoutReason);
            }
            return null;
        };
    }

    static Step onWorker(Callable<ToolResult> work) {
        var future = new CompletableFuture<ToolResult>();
        var thread = Thread.ofPlatform().name("cruma-agent-work").daemon().start(() -> {
            try {
                future.complete(work.call());
            } catch (Exception | Error failure) {
                future.completeExceptionally(failure);
            }
        });
        return new Step() {
            @Override
            public @Nullable ToolResult step() throws ToolProblem {
                if (!future.isDone()) {
                    return null;
                }
                try {
                    return future.join();
                } catch (RuntimeException failure) {
                    var cause = failure.getCause();
                    if (cause instanceof ToolProblem problem) {
                        throw problem;
                    }
                    throw failure;
                }
            }

            @Override
            public void cancel() {
                thread.interrupt();
            }
        };
    }

    @Nullable
    ToolResult step() throws ToolProblem;

    default void cancel() {}

    @FunctionalInterface
    interface Answer {
        ToolResult get() throws ToolProblem;
    }
}
