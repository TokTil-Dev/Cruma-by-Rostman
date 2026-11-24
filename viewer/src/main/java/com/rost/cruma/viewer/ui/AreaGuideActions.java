package com.rost.cruma.viewer.ui;

import java.util.Map;
import org.jetbrains.annotations.Nullable;

final class AreaGuideActions {
    static final String TOOL = "areas";
    static final String NO_AREAS = "This view has no Areas. Open the world of a client from the start menu.";

    private AreaGuideActions() {}

    static void addTo(Map<String, GuideAction> actions) {
        add(
                actions,
                new NotedGuideAction(
                        "areas-open-panel", host -> NotedGuideAction.withoutTool(host, TOOL, NO_AREAS), host -> {
                            host.openTool(TOOL);
                            return null;
                        }));
        add(actions, new NotedGuideAction("areas-show-nearest", AreaGuideActions::blocked, host -> {
            var areas = host.getAreas();
            if (areas == null) {
                return "Open a world first.";
            }
            var name = areas.showNearestArea();
            return name == null
                    ? "No area that the legend shows lies on the loaded tiles. Fly to a town and try again."
                    : "Selected " + name + ". The panel says what it does, and the world shows it on the ground.";
        }));
    }

    static @Nullable String blocked(GuideHost host) {
        var missing = NotedGuideAction.withoutTool(host, TOOL, NO_AREAS);
        if (missing != null) {
            return missing;
        }
        var areas = host.getAreas();
        return areas == null ? "Open a world first." : areas.getUnavailable();
    }

    private static void add(Map<String, GuideAction> actions, GuideAction action) {
        actions.put(action.getId(), action);
    }
}
