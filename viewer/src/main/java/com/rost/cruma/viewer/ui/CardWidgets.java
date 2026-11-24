package com.rost.cruma.viewer.ui;

import com.rost.cruma.viewer.config.UserPaths;
import imgui.ImGui;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

public final class CardWidgets {
    public static final float PAD = GeodataWidgets.BLOCK + 4;

    public static final float GAP = GeodataWidgets.BLOCK;

    public static final float NUMBER_HEIGHT = 74;

    public static final int NUMBER_SIZE = 28;

    public record Number(String value, String label, String tip) {}

    private CardWidgets() {}

    public static float beginCard(float left) {
        var top = ImGui.getCursorScreenPosY();
        var draw = ImGui.getWindowDrawList();
        draw.channelsSplit(2);
        draw.channelsSetCurrent(1);
        ImGui.setCursorScreenPos(left + PAD, top + PAD - 2);
        ImGui.beginGroup();
        return top;
    }

    public static void endCard(Map<String, float[]> places, float left, float width, float top, String place) {
        ImGui.endGroup();
        var bottom = ImGui.getItemRectMaxY() + PAD;
        var draw = ImGui.getWindowDrawList();
        draw.channelsSetCurrent(0);
        draw.addRectFilled(left, top, left + width, bottom, HudWidgets.BACKGROUND, HudWidgets.PANEL_RADIUS);
        draw.addRect(left, top, left + width, bottom, GeodataWidgets.DIVIDER, HudWidgets.PANEL_RADIUS, 0, 1);
        draw.channelsMerge();
        places.put(place, new float[] {left, top, left + width, bottom});
        ImGui.setCursorScreenPos(left, bottom + GAP);
        ImGui.dummy(0, 0);
    }

    public static void emptyCard(
            Map<String, float[]> places,
            float left,
            float width,
            String title,
            String detail,
            @Nullable Path folder,
            String place) {
        var top = beginCard(left);
        ImGui.textColored(HudWidgets.TEXT, title);
        ImGui.pushTextWrapPos(ImGui.getCursorPosX() + width - 2 * PAD);
        ImGui.textColored(GeodataWidgets.MUTED, detail);
        ImGui.popTextWrapPos();
        if (folder != null) {
            folderLine(folder, width - 2 * PAD);
        }
        endCard(places, left, width, top, place);
    }

    public static void numberRow(
            Map<String, float[]> places,
            @Nullable FontManager fonts,
            List<Number> numbers,
            float left,
            float width,
            String place) {
        var perRow = numbers.size() == 4 ? 2 : Math.min(3, numbers.size());
        var cardWidth = (float) Math.floor((width - (perRow - 1) * GAP) / perRow);
        var top = ImGui.getCursorScreenPosY();
        var draw = ImGui.getWindowDrawList();
        var font = fonts == null ? null : fonts.getHeadingFont(NUMBER_SIZE);
        for (var index = 0; index < numbers.size(); index++) {
            var number = numbers.get(index);
            var x = left + (index % perRow) * (cardWidth + GAP);
            var y = top + (index / perRow) * (NUMBER_HEIGHT + GAP);
            draw.addRectFilled(x, y, x + cardWidth, y + NUMBER_HEIGHT, HudWidgets.BACKGROUND, HudWidgets.PANEL_RADIUS);
            draw.addRect(x, y, x + cardWidth, y + NUMBER_HEIGHT, GeodataWidgets.DIVIDER, HudWidgets.PANEL_RADIUS, 0, 1);
            var valueColor = number.value().equals("…") ? GeodataWidgets.FAINT : HudWidgets.TEXT;
            var value = HudWidgets.truncateToWidth(number.value(), cardWidth - 2 * PAD);
            if (font != null) {
                draw.addText(font, NUMBER_SIZE, x + PAD, y + 10, valueColor, value);
            } else {
                draw.addText(x + PAD, y + 10, valueColor, value);
            }
            draw.addText(
                    x + PAD,
                    y + 12 + NUMBER_SIZE + 4,
                    GeodataWidgets.MUTED,
                    HudWidgets.truncateToWidth(number.label(), cardWidth - 2 * PAD));
            if (ImGui.isMouseHoveringRect(x, y, x + cardWidth, y + NUMBER_HEIGHT) && ImGui.isWindowHovered()) {
                HudWidgets.showTooltip(number.tip());
            }
            places.put(place + "/" + number.label(), new float[] {x, y, x + cardWidth, y + NUMBER_HEIGHT});
        }
        var rows = (numbers.size() + perRow - 1) / perRow;
        ImGui.setCursorScreenPos(left, top + rows * NUMBER_HEIGHT + (rows - 1) * GAP + GAP);
        ImGui.dummy(0, 0);
    }

    public static boolean statusRow(String label, ClientFolderStatus status, float inner) {
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var textTop = y + (HudWidgets.CONTROL_HEIGHT - ImGui.getFontSize()) * .5f - 1;
        ImGui.getWindowDrawList().addText(x, textTop, HudWidgets.TEXT, label);
        var hovered = ImGui.isWindowHovered()
                && ImGui.isMouseHoveringRect(x, y, x + ImGui.calcTextSizeX(label), y + HudWidgets.CONTROL_HEIGHT);
        ImGui.setCursorScreenPos(x + inner - GeodataWidgets.statusWidth(status.text(), status.dot()), y);
        GeodataWidgets.status(status.text(), status.dot());
        return hovered;
    }

    public static void folderLine(Path root, float width) {
        ImGui.textColored(GeodataWidgets.MUTED, cutMiddle(UserPaths.toDisplayString(root), width));
        if (ImGui.isItemHovered()) {
            HudWidgets.showTooltip(root.toString());
        }
    }

    public static boolean link(String id, String text, String tip) {
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var width = ImGui.calcTextSizeX(text);
        var pressed = ImGui.invisibleButton("##" + id, width, ImGui.getFontSize());
        var draw = ImGui.getWindowDrawList();
        draw.addText(x, y, HudWidgets.ACCENT, text);
        if (ImGui.isItemHovered()) {
            draw.addLine(x, y + ImGui.getFontSize(), x + width, y + ImGui.getFontSize(), HudWidgets.ACCENT, 1);
            HudWidgets.showTooltip(tip);
        }
        return pressed;
    }

    public static void bigText(@Nullable FontManager fonts, int size, int color, String text) {
        var font = fonts == null ? null : fonts.getHeadingFont(size);
        if (font != null) {
            ImGui.pushFont(font, size);
        }
        ImGui.textColored(color, text);
        if (font != null) {
            ImGui.popFont();
        }
    }

    public static String compact(long value) {
        if (value < 10_000) {
            return String.format(Locale.ROOT, "%,d", value);
        }
        if (value < 1_000_000) {
            return Math.round(value / 1000.0) + "k";
        }
        return String.format(Locale.ROOT, "%.1fM", value / 1_000_000.0);
    }

    public static String cutMiddle(String text, float width) {
        if (ImGui.calcTextSizeX(text) <= width) {
            return text;
        }
        var start = text.length() / 2;
        var end = start;
        while (start > 0 && end < text.length()) {
            start--;
            end++;
            var cut = text.substring(0, start) + "…" + text.substring(end);
            if (ImGui.calcTextSizeX(cut) <= width) {
                return cut;
            }
        }
        return "…";
    }
}
