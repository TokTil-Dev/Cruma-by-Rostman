package com.rost.cruma.viewer.agent;

import java.nio.file.Path;
import java.time.LocalTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.Nullable;

public final class AgentActivity {
    public record Entry(
            LocalTime time,
            String tool,
            String summary,
            boolean failed,
            @Nullable Path folder) {}

    private static final int KEPT = 100;

    private final ArrayDeque<Entry> entries = new ArrayDeque<>();
    private volatile long revision;

    synchronized void add(Entry entry) {
        entries.addFirst(entry);
        if (entries.size() > KEPT) {
            entries.removeLast();
        }
        revision++;
    }

    public synchronized List<Entry> getEntries() {
        return new ArrayList<>(entries);
    }

    public long getRevision() {
        return revision;
    }
}
