package com.rost.cruma.core.geodata;

import com.rost.cruma.core.geodata.GeoDataScanner.Finding;
import com.rost.cruma.core.geodata.GeoDataScanner.Rule;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

final class GeoFindingJoiner {
    private static final int CLIENT_MISMATCH_AREA = 3;

    private static final Set<Rule> JOINED_RULES = EnumSet.of(
            Rule.STEEP_CLIMB,
            Rule.LONG_DROP,
            Rule.REGION_SEAM,
            Rule.ONE_WAY_TRANSITION,
            Rule.ONE_WAY_TRAP,
            Rule.OBSTACLE_ENTRY,
            Rule.SHORT_DETOUR,
            Rule.CLIENT_SURFACE_MISMATCH);

    private static final int JOIN_DISTANCE = 32;

    private GeoFindingJoiner() {}

    static List<Finding> join(List<Finding> findings) {
        var parent = new int[findings.size()];
        var buckets = new HashMap<Long, List<Integer>>();
        for (var index = 0; index < findings.size(); index++) {
            parent[index] = index;
            var finding = findings.get(index);
            if (!JOINED_RULES.contains(finding.rule())) {
                continue;
            }
            var x = Math.floorDiv((int) finding.position().x(), 16);
            var y = Math.floorDiv((int) finding.position().y(), 16);
            for (var dx = -JOIN_DISTANCE / 16; dx <= JOIN_DISTANCE / 16; dx++) {
                for (var dy = -JOIN_DISTANCE / 16; dy <= JOIN_DISTANCE / 16; dy++) {
                    for (var other : buckets.getOrDefault(bucketKey(x + dx, y + dy), List.of())) {
                        var candidate = findings.get(other);
                        if (candidate.rule() == finding.rule()
                                && candidate.confidence() == finding.confidence()
                                && Math.abs(candidate.position().z()
                                                - finding.position().z())
                                        <= JOIN_DISTANCE) {
                            union(parent, index, other);
                        }
                    }
                }
            }
            buckets.computeIfAbsent(bucketKey(x, y), key -> new ArrayList<>()).add(index);
        }
        var groups = new LinkedHashMap<Integer, List<Finding>>();
        for (var index = 0; index < findings.size(); index++) {
            groups.computeIfAbsent(findRoot(parent, index), key -> new ArrayList<>())
                    .add(findings.get(index));
        }
        var result = new ArrayList<Finding>();
        for (var group : groups.values()) {
            var first = group.getFirst();
            if (first.rule() == Rule.CLIENT_SURFACE_MISMATCH && group.size() < CLIENT_MISMATCH_AREA) {
                continue;
            }
            result.add(
                    group.size() == 1
                            ? first
                            : new Finding(
                                    first.rule(),
                                    first.confidence(),
                                    first.position(),
                                    first.target(),
                                    first.source(),
                                    first.neighbor(),
                                    first.direction(),
                                    first.completeness(),
                                    group.size()
                                            + (first.rule() == Rule.CLIENT_SURFACE_MISMATCH ? " cells" : " edges")
                                            + " close together. "
                                            + first.detail(),
                                    group));
        }
        return result;
    }

    private static long bucketKey(int x, int y) {
        return (long) x << 32 | (y & 0xffffffffL);
    }

    private static int findRoot(int[] parent, int index) {
        while (parent[index] != index) {
            parent[index] = parent[parent[index]];
            index = parent[index];
        }
        return index;
    }

    private static void union(int[] parent, int first, int second) {
        var a = findRoot(parent, first);
        var b = findRoot(parent, second);
        if (a != b) {
            parent[Math.max(a, b)] = Math.min(a, b);
        }
    }
}
