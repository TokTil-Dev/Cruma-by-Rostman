package com.rost.cruma.core.world;

import com.rost.cruma.core.spawn.NpcGrpTable.Appearance;
import com.rost.cruma.core.world.GameScenes.Actor;
import com.rost.cruma.core.world.GameScenes.Scene;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import org.jetbrains.annotations.Nullable;

public final class SceneHelper {
    private SceneHelper() {}

    public static int of(List<Scene> scenes, IntFunction<@Nullable Appearance> looks) {
        var shots = new HashMap<Integer, Integer>();
        for (var scene : scenes) {
            var flying = new HashMap<Integer, Actor>();
            for (var item : scene.items()) {
                for (var order : item.actors()) {
                    if (order.action().enters()) {
                        flying.remove(order.index());
                        if (!order.player() && order.fly() && order.npc() > 0) {
                            flying.put(order.index(), order);
                        }
                    }
                }
                var shot = item.camera();
                var target = shot == null || shot.blank() ? null : flying.get(shot.target());
                if (target != null) {
                    shots.merge(target.npc(), 1, Integer::sum);
                }
            }
        }
        return best(shots, looks);
    }

    private static int best(Map<Integer, Integer> shots, IntFunction<@Nullable Appearance> looks) {
        var best = 0;
        var most = 0;
        for (var entry : shots.entrySet()) {
            var look = looks.apply(entry.getKey());
            if (look == null || !look.clear()) {
                continue;
            }
            var count = entry.getValue();
            if (count > most || count == most && entry.getKey() < best) {
                best = entry.getKey();
                most = count;
            }
        }
        return best;
    }
}
