package com.rost.cruma.viewer.ui;

import com.rost.cruma.viewer.ui.HudWidgets.Glyph;
import imgui.ImColor;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.flag.ImDrawFlags;
import imgui.flag.ImGuiMouseCursor;
import org.jetbrains.annotations.Nullable;

final class NpcPanelWidgets {
    static final float ICON = 32;

    static final float ROW_HEIGHT = ICON + 4;

    static final float BAR_HEIGHT = 19;

    private static final float SHARES_HEIGHT = 13;

    private static final float CHANCE_HEIGHT = 3;

    private static final int TILE = ImColor.rgb(20, 28, 36);

    private static int barDragging = -1;
    private static @Nullable String barDragId;

    private NpcPanelWidgets() {}

    enum ItemKind {
        ADENA("Adena", ImColor.rgb(232, 196, 92)),
        WEAPON("Weapons", ImColor.rgb(240, 128, 96)),
        ARMOR("Armour", ImColor.rgb(110, 160, 255)),
        JEWEL("Jewels", ImColor.rgb(196, 140, 230)),
        OTHER("Other", ImColor.rgb(96, 196, 176));

        final String label;
        final int color;

        ItemKind(String label, int color) {
            this.label = label;
            this.color = color;
        }

        static ItemKind of(String name, String type) {
            if (name.equals("adena")) {
                return ADENA;
            }
            return switch (type) {
                case "weapon" -> WEAPON;
                case "armor" -> ARMOR;
                case "accessary" -> JEWEL;
                default -> OTHER;
            };
        }
    }

    record BarDrag(int left, double share, boolean released) {}

    static void icon(ImDrawList draw, float x, float y, float size, long texture, Glyph fallback) {
        icon(draw, x, y, size, texture, 0, 0, 1, 1, 0xFFFFFFFF, fallback);
    }

    static void icon(
            ImDrawList draw,
            float x,
            float y,
            float size,
            long texture,
            float u0,
            float v0,
            float u1,
            float v1,
            int tint,
            Glyph fallback) {
        if (texture != 0) {
            draw.addImageRounded(texture, x, y, x + size, y + size, u0, v0, u1, v1, tint, 4);
            draw.addRect(x, y, x + size, y + size, GeodataWidgets.DIVIDER, 4, 0, 1);
            return;
        }
        draw.addRectFilled(x, y, x + size, y + size, TILE, 4f);
        draw.addRect(x, y, x + size, y + size, HudWidgets.BORDER, 4, 0, 1);
        var glyph = size * .56f;
        HudWidgets.glyph(
                draw, fallback, x + (size - glyph) * .5f, y + (size - glyph) * .5f, glyph, GeodataWidgets.FAINT);
    }

    static boolean iconRow(
            String id,
            long texture,
            Glyph fallback,
            String title,
            int titleColor,
            String detail,
            String value,
            int valueColor,
            boolean selected) {
        return iconRow(id, texture, fallback, title, titleColor, detail, value, valueColor, selected, false);
    }

    static boolean iconRow(
            String id,
            long texture,
            Glyph fallback,
            String title,
            int titleColor,
            String detail,
            String value,
            int valueColor,
            boolean selected,
            boolean chevron) {
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var width = ImGui.getContentRegionAvailX();
        var clicked = ImGui.invisibleButton(id, width, ROW_HEIGHT);
        var hovered = ImGui.isItemHovered();
        var draw = ImGui.getWindowDrawList();
        if (selected || hovered) {
            draw.addRectFilled(
                    x, y, x + width, y + ROW_HEIGHT, selected ? ImColor.rgb(30, 58, 70) : GeodataWidgets.HOVER, 6f);
        }
        if (selected) {
            draw.addRect(x, y, x + width, y + ROW_HEIGHT, GeodataWidgets.ACCENT, 6, 0, 1);
        }
        icon(draw, x + 6, y + (ROW_HEIGHT - ICON) * .5f, ICON, texture, fallback);
        var chevronWidth = chevron ? 14f : 0f;
        var right = x + width - chevronWidth;
        var valueWidth = value.isEmpty() ? 0 : ImGui.calcTextSizeX(value) + 10;
        var textLeft = x + ICON + 16;
        var textWidth = width - ICON - 22 - valueWidth - chevronWidth;
        var lineGap = (ROW_HEIGHT - 2 * ImGui.getFontSize()) / 3;
        draw.addText(textLeft, y + lineGap, titleColor, HudWidgets.truncateToWidth(title, textWidth));
        draw.addText(
                textLeft,
                y + 2 * lineGap + ImGui.getFontSize(),
                GeodataWidgets.MUTED,
                HudWidgets.truncateToWidth(detail, textWidth));
        if (!value.isEmpty()) {
            draw.addText(right - valueWidth, y + (ROW_HEIGHT - ImGui.getFontSize()) * .5f, valueColor, value);
        }
        if (chevron) {
            chevron(
                    draw,
                    x + width - 11,
                    y + ROW_HEIGHT * .5f,
                    selected,
                    hovered ? GeodataWidgets.MUTED : GeodataWidgets.FAINT);
        }
        ImGui.setCursorScreenPos(x, y + ROW_HEIGHT);
        ImGui.dummy(width, 0);
        return clicked;
    }

    static void chevron(ImDrawList draw, float x, float y, boolean open, int color) {
        if (open) {
            draw.addLine(x - 4, y - 2, x, y + 2, color, 1.6f);
            draw.addLine(x, y + 2, x + 4, y - 2, color, 1.6f);
        } else {
            draw.addLine(x - 2, y - 4, x + 2, y, color, 1.6f);
            draw.addLine(x + 2, y, x - 2, y + 4, color, 1.6f);
        }
    }

    static @Nullable BarDrag dropBar(
            String id, float width, double groupChance, double[] shares, int[] colors, boolean[] locked) {
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var draw = ImGui.getWindowDrawList();
        var height = SHARES_HEIGHT;
        draw.addRectFilled(x, y, x + width, y + height, TILE, 4f);
        var filled = width;
        var sum = 0.0;
        for (var share : shares) {
            sum += Math.max(0, share);
        }
        var bounds = new float[shares.length + 1];
        bounds[0] = x;
        var at = 0.0;
        for (var i = 0; i < shares.length; i++) {
            at += Math.max(0, shares[i]);
            bounds[i + 1] = x + (float) (sum > 0 ? filled * at / sum : 0);
        }
        for (var i = 0; i < shares.length; i++) {
            var left = bounds[i];
            var right = Math.max(left + 1, bounds[i + 1]);
            var last = i == shares.length - 1;
            var flags = (i == 0 ? ImDrawFlags.RoundCornersLeft : 0) | (last ? ImDrawFlags.RoundCornersRight : 0);
            flags = flags == 0 ? ImDrawFlags.RoundCornersNone : flags;
            draw.addRectFilled(left, y, right, y + height, (colors[i] & 0x00FFFFFF) | 0xC0000000, 4, flags);
        }
        draw.addRect(x, y, x + width, y + height, HudWidgets.BORDER, 4, 0, 1);
        var lineY = y + BAR_HEIGHT - CHANCE_HEIGHT;
        draw.addRectFilled(x, lineY, x + width, lineY + CHANCE_HEIGHT, TILE, CHANCE_HEIGHT * .5f);
        if (groupChance > 0) {
            var chance = Math.max(CHANCE_HEIGHT, (float) (width * Math.clamp(groupChance, 0, 100) / 100));
            draw.addRectFilled(x, lineY, x + chance, lineY + CHANCE_HEIGHT, GeodataWidgets.MUTED, CHANCE_HEIGHT * .5f);
        }
        BarDrag drag = null;
        var mouseX = ImGui.getMousePosX();
        for (var i = 0; i + 1 < shares.length; i++) {
            var bx = bounds[i + 1];
            var movable = !locked[i] && !locked[i + 1];
            draw.addLine(bx, y + 1, bx, y + height - 1, ImColor.rgba(16, 22, 28, 220), 2);
            if (!movable) {
                continue;
            }
            ImGui.setCursorScreenPos(bx - 4, y - 2);
            ImGui.invisibleButton(id + "/edge/" + i, 8, height + 4);
            var active = ImGui.isItemActive();
            if (ImGui.isItemHovered() || active) {
                ImGui.setMouseCursor(ImGuiMouseCursor.ResizeEW);
                draw.addLine(bx, y - 2, bx, y + height + 2, GeodataWidgets.ACCENT, 2);
            }
            var key = id + "/" + i;
            if (active) {
                barDragId = id;
                barDragging = i;
                var pair = Math.max(0, shares[i]) + Math.max(0, shares[i + 1]);
                var share = (mouseX - bounds[i]) / filled * sum;
                drag = new BarDrag(i, Math.clamp(share, 0, pair), false);
            } else if (ImGui.isItemDeactivated() && key.equals(barDragId + "/" + barDragging)) {
                barDragId = null;
                barDragging = -1;
                var pair = Math.max(0, shares[i]) + Math.max(0, shares[i + 1]);
                drag = new BarDrag(i, Math.clamp((mouseX - bounds[i]) / filled * sum, 0, pair), true);
            }
        }
        ImGui.setCursorScreenPos(x, y + BAR_HEIGHT);
        ImGui.dummy(width, 0);
        return drag;
    }

    static void kindLegend(float width) {
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var draw = ImGui.getWindowDrawList();
        var at = x;
        for (var kind : ItemKind.values()) {
            var labelWidth = ImGui.calcTextSizeX(kind.label);
            if (at + 14 + labelWidth > x + width) {
                break;
            }
            draw.addRectFilled(
                    at,
                    HudWidgets.textMiddle(y) - 4,
                    at + 8,
                    HudWidgets.textMiddle(y) + 4,
                    (kind.color & 0x00FFFFFF) | 0xC0000000,
                    2f);
            draw.addText(at + 12, y, GeodataWidgets.MUTED, kind.label);
            at += 12 + labelWidth + 14;
        }
        ImGui.setCursorScreenPos(x, y + ImGui.getFontSize());
        ImGui.dummy(width, 0);
    }
}
