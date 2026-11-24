package com.rost.cruma.core.spawn;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

public final class LineDiff {
    private LineDiff() {}

    public record Hunk(int oldLine, List<String> removed, int newLine, List<String> added) {}

    public static List<String> splitLines(String text) {
        return Arrays.asList(text.split("\r\n|\n", -1));
    }

    public static List<Hunk> diff(List<String> before, List<String> after) {
        var hunks = new ArrayList<Hunk>();
        compare(before, 0, before.size(), after, 0, after.size(), hunks);
        return hunks;
    }

    private static void compare(
            List<String> a, int aFrom, int aTo, List<String> b, int bFrom, int bTo, List<Hunk> hunks) {
        while (aFrom < aTo && bFrom < bTo && a.get(aFrom).equals(b.get(bFrom))) {
            aFrom++;
            bFrom++;
        }
        while (aTo > aFrom && bTo > bFrom && a.get(aTo - 1).equals(b.get(bTo - 1))) {
            aTo--;
            bTo--;
        }
        if (aFrom == aTo && bFrom == bTo) {
            return;
        }
        var anchors = findAnchors(a, aFrom, aTo, b, bFrom, bTo);
        if (anchors.isEmpty()) {
            hunks.add(new Hunk(
                    aFrom + 1, List.copyOf(a.subList(aFrom, aTo)), bFrom + 1, List.copyOf(b.subList(bFrom, bTo))));
            return;
        }
        var lastA = aFrom;
        var lastB = bFrom;
        for (var anchor : anchors) {
            compare(a, lastA, anchor[0], b, lastB, anchor[1], hunks);
            lastA = anchor[0] + 1;
            lastB = anchor[1] + 1;
        }
        compare(a, lastA, aTo, b, lastB, bTo, hunks);
    }

    private static List<int[]> findAnchors(List<String> a, int aFrom, int aTo, List<String> b, int bFrom, int bTo) {
        var countA = new HashMap<String, Integer>();
        for (var i = aFrom; i < aTo; i++) {
            countA.merge(a.get(i), 1, Integer::sum);
        }
        var countB = new HashMap<String, Integer>();
        var positionB = new HashMap<String, Integer>();
        for (var i = bFrom; i < bTo; i++) {
            countB.merge(b.get(i), 1, Integer::sum);
            positionB.put(b.get(i), i);
        }
        var pairs = new ArrayList<int[]>();
        for (var i = aFrom; i < aTo; i++) {
            var line = a.get(i);
            if (countA.get(line) == 1 && countB.getOrDefault(line, 0) == 1) {
                pairs.add(new int[] {i, positionB.get(line)});
            }
        }
        var tops = new ArrayList<Integer>();
        var previous = new int[pairs.size()];
        for (var i = 0; i < pairs.size(); i++) {
            var value = pairs.get(i)[1];
            var low = 0;
            var high = tops.size();
            while (low < high) {
                var middle = (low + high) >>> 1;
                if (pairs.get(tops.get(middle))[1] < value) {
                    low = middle + 1;
                } else {
                    high = middle;
                }
            }
            previous[i] = low > 0 ? tops.get(low - 1) : -1;
            if (low == tops.size()) {
                tops.add(i);
            } else {
                tops.set(low, i);
            }
        }
        var run = new ArrayList<int[]>();
        for (var i = tops.isEmpty() ? -1 : tops.getLast(); i >= 0; i = previous[i]) {
            run.add(pairs.get(i));
        }
        return run.reversed();
    }
}
