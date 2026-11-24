package com.rost.cruma.viewer.ui;

import java.util.Map;
import org.jetbrains.annotations.Nullable;

final class EventGuideActions {
    private static final String TOOL = "events";
    private static final String NO_EVENTS =
            "This view has no Map events. Open the world of a client from the start menu.";

    private EventGuideActions() {}

    static void addTo(Map<String, GuideAction> actions) {
        add(
                actions,
                new NotedGuideAction(
                        "events-open-panel", host -> NotedGuideAction.withoutTool(host, TOOL, NO_EVENTS), host -> {
                            host.openTool(TOOL);
                            return null;
                        }));
        add(actions, new NotedGuideAction("events-show-nearest", EventGuideActions::blocked, host -> {
            var events = host.getMapEvents();
            var name = events == null ? null : events.showNearest();
            return name == null
                    ? "No map event in the loaded tiles."
                    : "Selected " + name + ". Its card in the panel turns it on or off.";
        }));
    }

    private static @Nullable String blocked(GuideHost host) {
        var missing = NotedGuideAction.withoutTool(host, TOOL, NO_EVENTS);
        if (missing != null) {
            return missing;
        }
        var events = host.getMapEvents();
        return events == null ? "Open a world first." : events.getUnavailable();
    }

    private static void add(Map<String, GuideAction> actions, GuideAction action) {
        actions.put(action.getId(), action);
    }
}
