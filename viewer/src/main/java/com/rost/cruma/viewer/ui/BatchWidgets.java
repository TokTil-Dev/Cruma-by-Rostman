package com.rost.cruma.viewer.ui;

import com.rost.cruma.viewer.ui.HudWidgets.Glyph;
import imgui.ImColor;
import imgui.ImGui;
import imgui.flag.ImGuiCol;
import java.util.List;
import java.util.Locale;
import org.jetbrains.annotations.Nullable;

public final class BatchWidgets {
    private static final float PAD_X = 10;
    private static final float PAD_Y = 8;
    private static float left;
    private static float top;
    private static float width;

    private BatchWidgets() {}

    public record Action(String id, @Nullable Glyph glyph, String label, String tip) {}

    public enum Pick {
        ALL,
        NONE
    }

    public enum Confirm {
        APPLY,
        CANCEL
    }

    public static float inner() {
        return width - 2 * PAD_X;
    }

    public static @Nullable Pick begin(
            String id, int selected, String one, String many, String allLabel, String allTip) {
        var draw = ImGui.getWindowDrawList();
        left = ImGui.getCursorScreenPosX();
        top = ImGui.getCursorScreenPosY();
        width = ImGui.getContentRegionAvailX();
        draw.channelsSplit(2);
        draw.channelsSetCurrent(1);
        ImGui.setCursorScreenPos(left + PAD_X, top + PAD_Y);
        ImGui.beginGroup();
        var room = inner();
        ImGui.alignTextToFramePadding();
        var text = selected == 0 ? "None selected" : String.format(Locale.ROOT, "%,d selected", selected);
        var allWidth = Math.max(76, ImGui.calcTextSizeX(allLabel) + 20);
        ImGui.pushStyleColor(ImGuiCol.Text, selected == 0 ? GeodataWidgets.MUTED : GeodataWidgets.TEXT);
        ImGui.textUnformatted(HudWidgets.truncateToWidth(text, room - allWidth - 66 - 12));
        ImGui.popStyleColor();
        GeodataWidgets.tip(
                selected == 0
                        ? "No " + one + " selected yet."
                        : String.format(Locale.ROOT, "%,d %s selected.", selected, selected == 1 ? one : many));
        Pick pick = null;
        ImGui.sameLine(room - allWidth - 66 - 4);
        if (HudWidgets.button(id + "-all", null, allLabel, allWidth, 24, false, true)) {
            pick = Pick.ALL;
        }
        GeodataWidgets.tip(allTip);
        ImGui.sameLine(0, 4);
        ImGui.beginDisabled(selected == 0);
        if (HudWidgets.button(id + "-none", null, "None", 66, 24, false, true)) {
            pick = Pick.NONE;
        }
        ImGui.endDisabled();
        GeodataWidgets.tip("Clear the selection.");
        GeodataWidgets.space(GeodataWidgets.ROW);
        ImGui.beginDisabled(selected == 0);
        return pick;
    }

    public static int actionGrid(String id, List<Action> actions) {
        var spacing = ImGui.getStyle().getItemSpacingX();
        var third = (inner() - 2 * spacing) / 3;
        var clicked = -1;
        for (var i = 0; i < actions.size(); i++) {
            if (i % 3 != 0) {
                ImGui.sameLine();
            }
            var action = actions.get(i);
            if (HudWidgets.button(id + "-" + action.id(), action.glyph(), action.label(), third, 26, false, true)) {
                clicked = i;
            }
            GeodataWidgets.tip(action.tip());
        }
        return clicked;
    }

    public static @Nullable Confirm applyCancelButtons(String id, String applyLabel, boolean enabled) {
        GeodataWidgets.space(GeodataWidgets.ROW);
        var spacing = ImGui.getStyle().getItemSpacingX();
        var half = (inner() - spacing) / 2;
        Confirm result = null;
        ImGui.beginDisabled(!enabled);
        if (HudWidgets.button(id + "-apply", null, applyLabel, half, 26, true, true)) {
            result = Confirm.APPLY;
        }
        ImGui.endDisabled();
        ImGui.sameLine();
        if (HudWidgets.button(id + "-cancel", null, "Cancel", half, 26, false, true)) {
            result = Confirm.CANCEL;
        }
        return result;
    }

    public static void end() {
        ImGui.endDisabled();
        ImGui.endGroup();
        var draw = ImGui.getWindowDrawList();
        var bottom = ImGui.getItemRectMaxY() + PAD_Y;
        draw.channelsSetCurrent(0);
        draw.addRectFilled(left, top, left + width, bottom, GeodataWidgets.CARD, 8f);
        draw.addRect(left, top, left + width, bottom, ImColor.rgba(92, 219, 241, 90), 8, 0, 1);
        draw.channelsMerge();
        ImGui.setCursorScreenPos(left, bottom);
        ImGui.dummy(width, 0);
    }
}
