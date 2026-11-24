package com.rost.cruma.viewer.agent;

import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.viewer.agent.AgentActivity.Entry;
import com.rost.cruma.viewer.agent.Tools.OnViewer;
import java.time.LocalTime;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public final class AgentFrames {
    @FunctionalInterface
    public interface Gate {
        @Nullable
        String getRefusal(ToolSpec spec);
    }

    public static final class Call {
        private final String tool;
        private final ToolArgs args;
        private final CompletableFuture<ToolResult> result = new CompletableFuture<>();
        private volatile boolean cancelled;

        private Call(String tool, ToolArgs args) {
            this.tool = tool;
            this.args = args;
        }

        public CompletableFuture<ToolResult> getResult() {
            return result;
        }

        public void cancel() {
            cancelled = true;
        }
    }

    private static final long FRAME_BUDGET_NANOS = TimeUnit.MILLISECONDS.toNanos(4);
    private static final String STOPPED = "The call was stopped.";
    private static final Logger log = LogManager.getLogger(AgentFrames.class);

    private final Tools tools;
    private final Gate gate;
    private final AgentActivity activity;
    private final Runnable wake;
    private final Queue<Call> waiting = new ConcurrentLinkedQueue<>();
    private @Nullable Call running;
    private @Nullable Step step;

    public AgentFrames(Tools tools, Gate gate, AgentActivity activity, Runnable wake) {
        this.tools = tools;
        this.gate = gate;
        this.activity = activity;
        this.wake = wake;
    }

    public Call submit(String tool, JSONObject args) {
        var call = new Call(tool, new ToolArgs(args));
        waiting.add(call);
        wake.run();
        return call;
    }

    public void abandon() {
        waiting.forEach(Call::cancel);
        if (running != null) {
            running.cancel();
        }
    }

    public boolean isBusy() {
        return running != null || !waiting.isEmpty();
    }

    public void frame(ViewerContext viewer) {
        var deadline = System.nanoTime() + FRAME_BUDGET_NANOS;
        while (true) {
            if (running == null) {
                running = waiting.poll();
                if (running == null) {
                    return;
                }
                begin(running, viewer);
            }
            if (running != null && !advance(running)) {
                return;
            }
            if (System.nanoTime() > deadline) {
                return;
            }
        }
    }

    private void begin(Call call, ViewerContext viewer) {
        if (call.cancelled) {
            finish(call, ToolResult.failure(STOPPED));
            return;
        }
        var entry = tools.find(call.tool);
        if (!(entry instanceof OnViewer tool)) {
            finish(call, ToolResult.failure("A viewer has no tool " + call.tool + "."));
            return;
        }
        var refused = gate.getRefusal(tool.spec());
        if (refused != null) {
            finish(call, ToolResult.failure(refused));
            return;
        }
        try {
            step = tool.tool().start(call.args, viewer);
        } catch (ToolProblem problem) {
            finish(call, ToolResult.failure(problem.getMessage()));
        } catch (RuntimeException | Error failure) {
            finish(call, failed(call, failure));
        }
    }

    private boolean advance(Call call) {
        var current = step;
        if (call.cancelled) {
            if (current != null) {
                current.cancel();
            }
            finish(call, ToolResult.failure(STOPPED));
            return true;
        }
        try {
            var result = current.step();
            if (result == null) {
                return false;
            }
            finish(call, result);
        } catch (ToolProblem problem) {
            finish(call, ToolResult.failure(problem.getMessage()));
        } catch (RuntimeException | Error failure) {
            finish(call, failed(call, failure));
        }
        return true;
    }

    private void finish(Call call, ToolResult result) {
        running = null;
        step = null;
        activity.add(new Entry(LocalTime.now(), call.tool, said(call, result), result.hasFailed(), result.getFolder()));
        call.result.complete(result);
    }

    private String said(Call call, ToolResult result) {
        var summary = result.getSummary();
        var entry = tools.find(call.tool);
        return summary.isEmpty() && entry != null ? entry.spec().title() : summary;
    }

    private static ToolResult failed(Call call, Throwable failure) {
        log.error("Tool {} failed", call.tool, failure);
        return ToolResult.failure(call.tool + " failed: " + failure);
    }
}
