package com.rost.cruma.viewer.ui;

import com.rost.cruma.viewer.ui.HudWidgets.Glyph;
import imgui.ImGui;
import imgui.flag.ImDrawFlags;
import imgui.flag.ImGuiCol;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class FindingWidgets {
    public static final int NONE = -1;

    public static final int FLY = -2;

    private FindingWidgets() {}

    public enum Severity {
        ERROR(GeodataWidgets.BLOCKED, "Blocks the save"),
        WARNING(GeodataWidgets.WARNING, "Worth a look"),
        INFO(GeodataWidgets.ACCENT, "For your information");

        public final int color;
        public final String word;

        Severity(int color, String word) {
            this.color = color;
            this.word = word;
        }
    }

    public static int card(
            String id, Severity severity, String title, String detail, List<String> fixes, boolean selected) {
        return card(id, severity, title, detail, fixes, selected, true);
    }

    public static int card(
            String id,
            Severity severity,
            String title,
            String detail,
            List<String> fixes,
            boolean selected,
            boolean fly) {
        var draw = ImGui.getWindowDrawList();
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var width = ImGui.getContentRegionAvailX();
        draw.channelsSplit(2);
        draw.channelsSetCurrent(1);
        ImGui.setCursorScreenPos(x + 14, y + 8);
        ImGui.beginGroup();
        var room = width - 14 - 10;
        var titleTop = ImGui.getCursorScreenPosY();
        draw.addCircleFilled(x + 14 + 4, HudWidgets.textMiddle(titleTop), 4, severity.color, 12);
        ImGui.setCursorScreenPos(x + 14 + 14, titleTop);
        text(GeodataWidgets.TEXT, HudWidgets.truncateToWidth(title, room - 14));
        ImGui.setCursorScreenPos(x + 14, ImGui.getCursorScreenPosY());
        ImGui.pushTextWrapPos(ImGui.getCursorPosX() + room);
        text(GeodataWidgets.MUTED, detail);
        ImGui.popTextWrapPos();
        var pressed = NONE;
        if (fly || !fixes.isEmpty()) {
            GeodataWidgets.space(GeodataWidgets.ROW);
        }
        var used = 0f;
        if (fly) {
            used = 72f;
            if (HudWidgets.button(id + "-fly", Glyph.FOCUS, "Fly to", used, 24, false, true)) {
                pressed = FLY;
            }
            GeodataWidgets.tip("Move the camera there.");
        }
        for (var i = 0; i < fixes.size(); i++) {
            var label = fixes.get(i);
            var buttonWidth = ImGui.calcTextSizeX(label) + 20;
            if (used == 0) {
                used = buttonWidth;
            } else if (used + 4 + buttonWidth <= room) {
                ImGui.sameLine(0, 4);
                used += 4 + buttonWidth;
            } else {
                used = buttonWidth;
            }
            if (HudWidgets.button(id + "-fix" + i, null, label, buttonWidth, 24, i == 0, true)) {
                pressed = i;
            }
        }
        ImGui.endGroup();
        var bottom = ImGui.getItemRectMaxY() + 8;
        draw.channelsSetCurrent(0);
        draw.addRectFilled(x, y, x + width, bottom, GeodataWidgets.CARD, 8f);
        draw.addRectFilled(x, y, x + 4, bottom, severity.color, 8, ImDrawFlags.RoundCornersLeft);
        if (selected) {
            draw.addRect(x, y, x + width, bottom, GeodataWidgets.ACCENT, 8, 0, 1.5f);
        }
        draw.channelsMerge();
        ImGui.setCursorScreenPos(x, bottom);
        ImGui.dummy(width, 0);
        return pressed;
    }

    private static void text(int color, String value) {
        ImGui.pushStyleColor(ImGuiCol.Text, color);
        ImGui.textUnformatted(value);
        ImGui.popStyleColor();
    }

    public static final class FindingList {
        private final Set<String> hidden = new HashSet<>();
        private final int pageSize;
        private int page;

        public FindingList(int pageSize) {
            this.pageSize = pageSize;
        }

        public record Group(String key, String title, Severity severity, int count, String help) {}

        public void reset(Collection<String> hiddenGroups) {
            hidden.clear();
            hidden.addAll(hiddenGroups);
            page = 0;
        }

        public boolean isShown(String key) {
            return !hidden.contains(key);
        }

        public int getPage() {
            return page;
        }

        public void showPageOf(int index) {
            page = Math.max(0, index / pageSize);
        }

        public boolean groupCheckboxes(String id, List<Group> groups) {
            var changed = false;
            for (var group : groups) {
                ImGui.pushID(id + "/" + group.key());
                var shown = isShown(group.key());
                if (ImGui.checkbox("##shown", shown)) {
                    if (shown) {
                        hidden.add(group.key());
                    } else {
                        hidden.remove(group.key());
                    }
                    page = 0;
                    changed = true;
                }
                GeodataWidgets.tip(group.help());
                ImGui.sameLine();
                GeodataWidgets.dot(group.severity().color, true);
                var count = String.format(Locale.ROOT, "%,d", group.count());
                var room = ImGui.getContentRegionAvailX()
                        - ImGui.calcTextSizeX(count)
                        - ImGui.getStyle().getItemSpacingX();
                ImGui.alignTextToFramePadding();
                text(
                        shown ? GeodataWidgets.TEXT : GeodataWidgets.FAINT,
                        HudWidgets.truncateToWidth(group.title(), room));
                GeodataWidgets.tip(group.help());
                ImGui.sameLine();
                text(GeodataWidgets.MUTED, count);
                ImGui.popID();
            }
            return changed;
        }

        public int[] drawPager(String id, int total) {
            return drawPager(id, total, "finding", "findings");
        }

        public int[] drawPager(String id, int total, String one, String many) {
            var pages = Math.max(1, (total + pageSize - 1) / pageSize);
            page = Math.clamp(page, 0, pages - 1);
            if (pages > 1) {
                ImGui.beginDisabled(page == 0);
                var half = GeodataWidgets.columnWidth(2);
                if (HudWidgets.button(id + "-previous", null, "Previous", half, 26, false, true)) {
                    page--;
                }
                ImGui.endDisabled();
                ImGui.beginDisabled(page + 1 >= pages);
                ImGui.sameLine();
                if (HudWidgets.button(id + "-next", null, "Next", half, 26, false, true)) {
                    page++;
                }
                ImGui.endDisabled();
                text(
                        GeodataWidgets.FAINT,
                        String.format(
                                Locale.ROOT,
                                "Page %d of %d · %,d %s",
                                page + 1,
                                pages,
                                total,
                                total == 1 ? one : many));
            } else {
                text(GeodataWidgets.FAINT, total == 1 ? "1 " + one : String.format(Locale.ROOT, "%,d %s", total, many));
            }
            return new int[] {page * pageSize, Math.min(total, (page + 1) * pageSize)};
        }
    }
}
