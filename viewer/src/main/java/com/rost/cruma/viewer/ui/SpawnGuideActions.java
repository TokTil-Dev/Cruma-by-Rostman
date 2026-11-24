package com.rost.cruma.viewer.ui;

import com.rost.cruma.viewer.ui.GuideHost.Spawns;
import java.util.Map;
import java.util.function.Function;
import org.jetbrains.annotations.Nullable;

final class SpawnGuideActions {
    static final String TOOL = "spawns";
    static final String NO_SPAWNS = "This view has no NPC Spawns. Open the world of a client from the start menu.";
    private static final String NO_WORLD = "Open a world first.";

    private SpawnGuideActions() {}

    static void addTo(Map<String, GuideAction> actions) {
        add(
                actions,
                new NotedGuideAction(
                        "spawns-open-panel", host -> NotedGuideAction.withoutTool(host, TOOL, NO_SPAWNS), host -> {
                            host.openTool(TOOL);
                            return null;
                        }));
        add(
                actions,
                new NotedGuideAction(
                        "spawns-show-nearest",
                        SpawnGuideActions::blocked,
                        host -> withSpawns(host, spawns -> {
                            var name = spawns.showNearestMaker();
                            return name == null
                                    ? "No maker spawns near the camera. Fly over a hunting ground or a town and try"
                                            + " again."
                                    : "Selected " + name + ". The panel shows its zones from above and its NPCs.";
                        })));
        add(
                actions,
                new NotedGuideAction(
                        "spawns-show-fixed",
                        SpawnGuideActions::blocked,
                        host -> withSpawns(host, spawns -> {
                            var name = spawns.showNearestFixedNpc();
                            return name == null
                                    ? "No NPC stands at a fixed point near the camera. Fly into a town and try again."
                                    : "Selected " + name + ". Its entry shows its X, Y, Z and Heading.";
                        })));
    }

    static @Nullable String blocked(GuideHost host) {
        var missing = NotedGuideAction.withoutTool(host, TOOL, NO_SPAWNS);
        if (missing != null) {
            return missing;
        }
        var spawns = host.getNpcSpawns();
        return spawns == null ? NO_WORLD : spawns.getUnavailable();
    }

    static @Nullable String withSpawns(GuideHost host, Function<Spawns, @Nullable String> work) {
        var spawns = host.getNpcSpawns();
        return spawns == null ? NO_WORLD : work.apply(spawns);
    }

    private static void add(Map<String, GuideAction> actions, GuideAction action) {
        actions.put(action.getId(), action);
    }
}
