package com.rost.cruma.core.schema;

import java.util.Collection;
import java.util.Locale;

final class Suggestions {
    private Suggestions() {}

    static String suggest(String word, Collection<String> candidates) {
        if (word.length() > SchemaParser.MAX_NAME) {
            return "";
        }
        var lower = word.toLowerCase(Locale.ROOT);
        String best = null;
        var bestDistance = Integer.MAX_VALUE;
        for (var candidate : candidates) {
            if (candidate.equals(word) || candidate.length() > SchemaParser.MAX_NAME) {
                continue;
            }
            var distance = distance(lower, candidate.toLowerCase(Locale.ROOT));
            if (distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }
        var allowed = word.length() >= 5 ? 2 : 1;
        return best != null && bestDistance <= allowed ? " Did you mean \"" + best + "\"?" : "";
    }

    private static int distance(String a, String b) {
        var rows = new int[a.length() + 1][b.length() + 1];
        for (var i = 0; i <= a.length(); i++) {
            rows[i][0] = i;
        }
        for (var j = 0; j <= b.length(); j++) {
            rows[0][j] = j;
        }
        for (var i = 1; i <= a.length(); i++) {
            for (var j = 1; j <= b.length(); j++) {
                var cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                var best = Math.min(Math.min(rows[i - 1][j] + 1, rows[i][j - 1] + 1), rows[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    best = Math.min(best, rows[i - 2][j - 2] + 1);
                }
                rows[i][j] = best;
            }
        }
        return rows[a.length()][b.length()];
    }
}
