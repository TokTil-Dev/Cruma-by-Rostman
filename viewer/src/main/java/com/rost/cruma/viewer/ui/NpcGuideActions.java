package com.rost.cruma.viewer.ui;

import java.util.Map;
import org.jetbrains.annotations.Nullable;

final class NpcGuideActions {
    private static final String TOOL = "npcs";
    private static final String NO_NPCS =
            "This view has no NPCs panel. Open the world of a client from the start menu.";

    private NpcGuideActions() {}

    static void addTo(Map<String, GuideAction> actions) {
        add(
                actions,
                new NotedGuideAction(
                        "npcs-open-panel", host -> NotedGuideAction.withoutTool(host, TOOL, NO_NPCS), host -> {
                            host.openTool(TOOL);
                            return null;
                        }));
        add(
                actions,
                new NotedGuideAction(
                        "npcs-open-nearest",
                        NpcGuideActions::nearestBlocked,
                        host -> SpawnGuideActions.withSpawns(host, spawns -> {
                            var name = spawns.openNearestNpc();
                            return name == null
                                    ? "No maker spawns near the camera. Fly over a hunting ground or a town and try"
                                            + " again."
                                    : "Opened " + name + ", the first NPC of the maker nearest to you.";
                        })));
        add(actions, new NotedGuideAction("npcs-open-export", GuideHost::getNpcExportUnavailable, host -> {
            host.openNpcExport();
            return null;
        }));
    }

    private static @Nullable String nearestBlocked(GuideHost host) {
        var missing = NotedGuideAction.withoutTool(host, TOOL, NO_NPCS);
        return missing != null ? missing : SpawnGuideActions.blocked(host);
    }

    private static void add(Map<String, GuideAction> actions, GuideAction action) {
        actions.put(action.getId(), action);
    }
}
