package com.rost.cruma.viewer.render;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

public final class EditorTopology {
    private static final int JOURNAL = 64;
    private static final Set<String> WHOLE = Set.of("");

    private final List<Source> sources;
    private final long[] seen;
    private boolean seenOn;
    private long revision;
    private final LinkedHashMap<Long, Set<String>> journal = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Set<String>> eldest) {
            return size() > JOURNAL;
        }
    };

    public EditorTopology(List<Source> sources) {
        this.sources = List.copyOf(sources);
        seen = new long[this.sources.size()];
        Arrays.fill(seen, Long.MIN_VALUE);
    }

    public interface Source {
        long getRevision();

        @Nullable
        Set<String> getActorsSince(long since);
    }

    public long getRevision(boolean on) {
        follow(on);
        return revision;
    }

    public @Nullable Set<String> getActorsSince(long since, boolean on) {
        follow(on);
        if (since > revision) {
            return null;
        }
        var result = new HashSet<String>();
        for (var change = since + 1; change <= revision; change++) {
            var actors = journal.get(change);
            if (actors == null || actors == WHOLE) {
                return null;
            }
            result.addAll(actors);
        }
        return result;
    }

    private void follow(boolean on) {
        var whole = on != seenOn;
        var changed = whole;
        var actors = new HashSet<String>();
        for (var i = 0; i < sources.size(); i++) {
            if (i > 0 && !on) {
                continue;
            }
            var now = sources.get(i).getRevision();
            if (now == seen[i]) {
                continue;
            }
            changed = true;
            if (seen[i] == Long.MIN_VALUE) {
                whole = true;
            } else if (!whole) {
                var since = sources.get(i).getActorsSince(seen[i]);
                if (since == null) {
                    whole = true;
                } else {
                    actors.addAll(since);
                }
            }
            seen[i] = now;
        }
        seenOn = on;
        if (changed) {
            revision++;
            journal.put(revision, whole ? WHOLE : Set.copyOf(actors));
        }
    }
}
