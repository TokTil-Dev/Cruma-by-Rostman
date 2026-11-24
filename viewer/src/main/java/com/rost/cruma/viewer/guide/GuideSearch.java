package com.rost.cruma.viewer.guide;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import org.jetbrains.annotations.Nullable;

public final class GuideSearch {
    public static final int MOST_RESULTS = 50;

    private static final int BEFORE_MATCH = 24;
    private static final String ELLIPSIS = "…";

    private static final int TITLE = 0;
    private static final int SUMMARY = 1;
    private static final int HEADING = 2;
    private static final int BODY = 3;

    private GuideSearch() {}

    public record Hit(GuideArticle article, String line, List<int[]> matches, int rank) {}

    public static List<Hit> search(GuideLibrary library, String query) {
        var words = words(query);
        if (words.isEmpty()) {
            return List.of();
        }
        var hits = new ArrayList<Hit>();
        var order = new ArrayList<GuideArticle>();
        for (var article : library.getAll()) {
            var hit = match(article, words);
            if (hit != null) {
                hits.add(hit);
                order.add(article);
            }
        }
        hits.sort(Comparator.comparingInt(Hit::rank).thenComparingInt(hit -> order.indexOf(hit.article())));
        return hits.size() > MOST_RESULTS ? List.copyOf(hits.subList(0, MOST_RESULTS)) : hits;
    }

    public static final class Cache {
        private static final int KEPT = 16;

        private final Map<String, List<Hit>> results = new LinkedHashMap<>(KEPT, .75f, true) {
            @Override
            protected boolean removeEldestEntry(Entry<String, List<Hit>> eldest) {
                return size() > KEPT;
            }
        };
        private @Nullable GuideLibrary library;

        public List<Hit> search(GuideLibrary library, String query) {
            if (library != this.library) {
                results.clear();
                this.library = library;
            }
            return results.computeIfAbsent(query, key -> List.copyOf(GuideSearch.search(library, key)));
        }
    }

    public static List<String> words(String query) {
        var words = new ArrayList<String>();
        for (var word : query.toLowerCase(Locale.ROOT).split("\\s+")) {
            if (!word.isEmpty() && !words.contains(word)) {
                words.add(word);
            }
        }
        return words;
    }

    private static Hit match(GuideArticle article, List<String> words) {
        var lines = new ArrayList<String>();
        var kinds = new ArrayList<Integer>();
        add(lines, kinds, article.title(), TITLE);
        add(lines, kinds, article.summary(), SUMMARY);
        for (var heading : article.headings()) {
            add(lines, kinds, heading, HEADING);
        }
        for (var line : article.bodyLines()) {
            add(lines, kinds, line, BODY);
        }
        var all = String.join("\n", lines).toLowerCase(Locale.ROOT);
        for (var word : words) {
            if (!all.contains(word)) {
                return null;
            }
        }
        var best = -1;
        var bestCount = 0;
        for (var index = 0; index < lines.size(); index++) {
            var count = count(lines.get(index), words);
            if (count > bestCount) {
                best = index;
                bestCount = count;
            }
        }
        var kind = kinds.get(best);
        var rank = bestCount == words.size() ? kind : BODY + 1 + kind;
        return shown(article, kind == TITLE ? article.summary() : lines.get(best), words, rank);
    }

    private static void add(List<String> lines, List<Integer> kinds, String line, int kind) {
        lines.add(line);
        kinds.add(kind);
    }

    private static int count(String line, List<String> words) {
        var lower = line.toLowerCase(Locale.ROOT);
        var count = 0;
        for (var word : words) {
            if (lower.contains(word)) {
                count++;
            }
        }
        return count;
    }

    private static Hit shown(GuideArticle article, String line, List<String> words, int rank) {
        var lower = line.toLowerCase(Locale.ROOT);
        var first = Integer.MAX_VALUE;
        for (var word : words) {
            var at = lower.indexOf(word);
            if (at >= 0) {
                first = Math.min(first, at);
            }
        }
        var shown = line;
        if (first > BEFORE_MATCH) {
            var start = line.lastIndexOf(' ', first - BEFORE_MATCH);
            start = start < 0 ? first - BEFORE_MATCH : start + 1;
            shown = ELLIPSIS + line.substring(start);
        }
        var matches = new ArrayList<int[]>();
        var shownLower = shown.toLowerCase(Locale.ROOT);
        for (var word : words) {
            var at = shownLower.indexOf(word);
            while (at >= 0) {
                matches.add(new int[] {at, at + word.length()});
                at = shownLower.indexOf(word, at + word.length());
            }
        }
        matches.sort(Comparator.comparingInt(range -> range[0]));
        var merged = new ArrayList<int[]>();
        for (var range : matches) {
            if (!merged.isEmpty() && range[0] <= merged.getLast()[1]) {
                merged.getLast()[1] = Math.max(merged.getLast()[1], range[1]);
            } else {
                merged.add(range.clone());
            }
        }
        return new Hit(article, shown, merged, rank);
    }
}
