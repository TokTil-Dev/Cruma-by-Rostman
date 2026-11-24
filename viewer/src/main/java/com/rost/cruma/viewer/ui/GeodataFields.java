package com.rost.cruma.viewer.ui;

import com.rost.cruma.core.geodata.GeoQueryEngine.Completeness;
import imgui.ImGui;
import imgui.type.ImInt;
import imgui.type.ImString;

public final class GeodataFields {
    private GeodataFields() {}

    public static boolean textField(String label, ImString value) {
        ImGui.textWrapped(label);
        ImGui.setNextItemWidth(-1);
        return ImGui.inputText("##" + label, value);
    }

    public static void integer(String label, ImInt value) {
        integer(label, value, 1, 100);
    }

    public static void integer(String label, ImInt value, int step, int fastStep) {
        ImGui.textWrapped(label);
        ImGui.setNextItemWidth(-1);
        ImGui.inputInt("##" + label, value, step, fastStep);
    }

    public static boolean rowButton(String label, int column, int columns) {
        if (column > 0) {
            ImGui.sameLine();
        }
        var remaining = columns - column;
        var width = (ImGui.getContentRegionAvailX() - ImGui.getStyle().getItemSpacingX() * (remaining - 1)) / remaining;
        return ImGui.button(label, width, 0);
    }

    public static void choice(String label, ImInt value, String[] choices) {
        ImGui.textWrapped(label);
        ImGui.setNextItemWidth(-1);
        ImGui.combo("##" + label, value, choices);
    }

    public static void slider(String label, int[] value, int min, int max, String help) {
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        HudWidgets.sliderInt(label, value, min, max);
        if (ImGui.isWindowHovered()
                && ImGui.isMouseHoveringRect(x, y, ImGui.getItemRectMaxX(), ImGui.getItemRectMaxY())) {
            HudWidgets.showTooltip(help);
        }
    }

    public static String completenessLabel(Completeness value) {
        return switch (value) {
            case COMPLETE -> "complete within radius";
            case DATA_UNAVAILABLE -> "partial · missing geodata";
            case LIMIT -> "partial · search limit reached";
            case CANCELLED -> "partial · cancelled";
            case SOURCE_CHANGED -> "partial · source changed";
        };
    }
}
