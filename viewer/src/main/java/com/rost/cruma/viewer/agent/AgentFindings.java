package com.rost.cruma.viewer.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jetbrains.annotations.Nullable;

public final class AgentFindings {
    private static final int KEPT = 20;

    private final List<Findings> lists = new ArrayList<>();
    private volatile long revision;

    public enum Severity {
        ERROR,
        WARNING,
        INFO;

        public String getWord() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public record Item(
            String text,
            String place,
            float x,
            float y,
            @Nullable Float z,
            @Nullable Severity severity) {}

    public record Findings(String title, List<Item> items) {}

    void show(Findings findings) {
        lists.removeIf(list -> list.title().equals(findings.title()));
        lists.addFirst(findings);
        if (lists.size() > KEPT) {
            lists.removeLast();
        }
        revision++;
    }

    public List<Findings> getLists() {
        return List.copyOf(lists);
    }

    public long getRevision() {
        return revision;
    }
}
