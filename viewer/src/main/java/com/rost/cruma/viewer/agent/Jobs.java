package com.rost.cruma.viewer.agent;

import com.alibaba.fastjson2.JSONObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public final class Jobs {
    @FunctionalInterface
    public interface Work {
        ToolResult run(Job job) throws ToolProblem, InterruptedException;
    }

    private static final int MOST_KEPT = 32;
    private static final Logger log = LogManager.getLogger(Jobs.class);

    private final LinkedHashMap<String, Job> jobs = new LinkedHashMap<>();
    private final AtomicInteger next = new AtomicInteger(1);

    public Job start(String tool, Work work) {
        var job = new Job("j" + next.getAndIncrement(), tool);
        synchronized (jobs) {
            jobs.put(job.getId(), job);
            prune();
        }
        Thread.ofPlatform().name("cruma-agent-" + job.getId()).daemon().start(() -> run(job, work));
        return job;
    }

    public @Nullable Job find(String id) {
        synchronized (jobs) {
            return jobs.get(id);
        }
    }

    public List<Job> getRunning() {
        synchronized (jobs) {
            var running = new ArrayList<Job>();
            for (var job : jobs.values()) {
                if (!job.getResult().isDone()) {
                    running.add(job);
                }
            }
            return running;
        }
    }

    public ToolResult answer(Job job, long waitMillis) throws InterruptedException {
        try {
            var result = job.getResult().get(waitMillis, TimeUnit.MILLISECONDS);
            synchronized (jobs) {
                jobs.remove(job.getId());
            }
            return result;
        } catch (TimeoutException stillRunning) {
            var running = new JSONObject();
            running.put("job", job.getId());
            running.put("tool", job.getTool());
            running.put("status", "running");
            running.put("progress", job.getProgress());
            running.put("next", "Call job_wait with this job for the answer.");
            return ToolResult.data(running);
        } catch (ExecutionException impossible) {
            return ToolResult.failure(job.getTool() + " failed: " + impossible.getCause());
        }
    }

    private void prune() {
        var ended =
                jobs.values().stream().filter(job -> job.getResult().isDone()).toList();
        for (var index = 0; index < ended.size() - MOST_KEPT; index++) {
            jobs.remove(ended.get(index).getId());
        }
    }

    private static void run(Job job, Work work) {
        try {
            job.getResult().complete(work.run(job));
        } catch (ToolProblem problem) {
            job.getResult().complete(ToolResult.failure(problem.getMessage()));
        } catch (InterruptedException stopped) {
            job.getResult().complete(ToolResult.failure("The job was stopped."));
        } catch (RuntimeException | Error failure) {
            log.error("Tool {} failed", job.getTool(), failure);
            job.getResult().complete(ToolResult.failure(job.getTool() + " failed: " + failure));
        }
    }
}
