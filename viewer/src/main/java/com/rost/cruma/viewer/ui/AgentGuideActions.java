package com.rost.cruma.viewer.ui;

import java.util.Map;

final class AgentGuideActions {
    private static final String TOOL = "agent";
    private static final String NO_PANEL =
            "This window keeps AI agents out, so it has no AI agent panel. Settings of the start menu has the same"
                    + " switch.";

    private AgentGuideActions() {}

    static void addTo(Map<String, GuideAction> actions) {
        actions.put(
                "agent-open-panel",
                new NotedGuideAction(
                        "agent-open-panel", host -> NotedGuideAction.withoutTool(host, TOOL, NO_PANEL), host -> {
                            host.openTool(TOOL);
                            return null;
                        }));
    }
}
