package com.rost.cruma.viewer.ui;

import com.rost.cruma.viewer.agent.Access;
import com.rost.cruma.viewer.agent.AgentConnect;
import imgui.ImFont;
import imgui.ImGui;
import org.jetbrains.annotations.Nullable;

final class AgentControls {
    static final String SWITCH = "Let an AI agent use this window";

    private static final Access[] LEVELS = Access.values();
    private static final String[] LABELS = {Access.SEE.getLabel(), Access.EDIT.getLabel(), Access.WRITE.getLabel()};

    private AgentControls() {}

    static Access access(String id, Access current) {
        var left = ImGui.getCursorScreenPosX();
        var width = ImGui.getContentRegionAvailX();
        var clicked = GeodataWidgets.segmented(id, LABELS, current.ordinal(), width);
        if (ImGui.isItemHovered()) {
            var hovered = LEVELS[GeodataWidgets.hoveredSegment(left, width, LEVELS.length)];
            HudWidgets.showTooltip(hovered.getLabel() + ": " + hovered.getAllowed());
        }
        var chosen = clicked < 0 ? current : LEVELS[clicked];
        GeodataWidgets.space(GeodataWidgets.ROW);
        GeodataWidgets.note(
                chosen.getAllowed(), chosen == Access.WRITE ? GeodataWidgets.WARNING : GeodataWidgets.MUTED);
        return chosen;
    }

    static void connect(String id, AgentConnect connect, @Nullable ImFont mono) {
        GeodataWidgets.commandField(id + "/claude", "Claude Code", connect.claude(), mono);
        GeodataWidgets.space(GeodataWidgets.ROW);
        GeodataWidgets.commandField(id + "/json", "Other programs", connect.json(), mono);
    }
}
