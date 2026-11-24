package com.rost.cruma.viewer.spawn;

import com.rost.cruma.core.spawn.SpawnMaker;
import com.rost.cruma.core.spawn.SpawnPosition.Anywhere;
import com.rost.cruma.core.spawn.SpawnPosition.Fixed;
import com.rost.cruma.core.spawn.SpawnPosition.Point;
import com.rost.cruma.core.spawn.SpawnPosition.Weighted;
import com.rost.cruma.core.spawn.SpawnSampler;
import com.rost.cruma.core.spawn.SpawnSampler.Candidates;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public final class SpawnPreview {
    public static final int MAX_PER_ENTRY = 64;

    private SpawnPreview() {}

    public record Placed(
            int makerIndex,
            int slot,
            String npcName,
            double x,
            double y,
            double z,
            int heading,
            boolean random,
            int count) {}

    public static List<Placed> place(SpawnMaker maker, Candidates candidates, long seed) {
        var placed = new ArrayList<Placed>();
        for (var slot = 0; slot < maker.npcs().size(); slot++) {
            var npc = maker.npcs().get(slot);
            var random =
                    new Random(seed * 1_000_003L + slot * 7919L + npc.npcName().hashCode());
            var count = Math.min(Math.max(1, npc.total()), MAX_PER_ENTRY);
            switch (npc.position()) {
                case Fixed fixed ->
                    placed.add(new Placed(
                            maker.index(),
                            slot,
                            npc.npcName(),
                            fixed.x(),
                            fixed.y(),
                            fixed.z(),
                            fixed.heading(),
                            false,
                            npc.total()));
                case Weighted weighted -> {
                    for (var i = 0; i < count && !weighted.points().isEmpty(); i++) {
                        var point = pickWeighted(weighted.points(), random);
                        placed.add(new Placed(
                                maker.index(),
                                slot,
                                npc.npcName(),
                                point.x(),
                                point.y(),
                                point.z(),
                                point.heading(),
                                false,
                                1));
                    }
                }
                case Anywhere _ -> {
                    if (candidates == null) {
                        continue;
                    }
                    for (var point : SpawnSampler.pick(candidates.points(), count, random)) {
                        placed.add(new Placed(
                                maker.index(),
                                slot,
                                npc.npcName(),
                                point.x(),
                                point.y(),
                                point.z(),
                                random.nextInt(65536),
                                true,
                                1));
                    }
                }
            }
        }
        return placed;
    }

    private static Point pickWeighted(List<Point> points, Random random) {
        var total =
                points.stream().mapToInt(point -> Math.max(0, point.percent())).sum();
        if (total <= 0) {
            return points.get(random.nextInt(points.size()));
        }
        var roll = random.nextInt(total);
        for (var point : points) {
            roll -= Math.max(0, point.percent());
            if (roll < 0) {
                return point;
            }
        }
        return points.getLast();
    }
}
