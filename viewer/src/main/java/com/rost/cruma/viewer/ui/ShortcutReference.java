package com.rost.cruma.viewer.ui;

import com.rost.cruma.viewer.ui.HudWidgets.Glyph;
import com.rost.cruma.viewer.ui.ShortcutCatalog.Row;
import com.rost.cruma.viewer.ui.ShortcutCatalog.Section;
import com.rost.cruma.viewer.ui.ShortcutKeys.Kind;
import com.rost.cruma.viewer.ui.ShortcutKeys.Piece;
import imgui.ImDrawList;
import imgui.ImFont;
import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImString;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

public final class ShortcutReference {
    public record View(boolean effectsOnly, boolean geodata, boolean editing, boolean scenes, Path screenshots) {}

    public enum Action {
        NONE,
        CLOSE,
        GUIDE
    }

    private static final String WINDOW = "##hudShortcutHelp";
    private static final int SHOWN = "shortcuts/shown".hashCode();
    private static final int WINDOW_FLAGS = ImGuiWindowFlags.NoTitleBar
            | ImGuiWindowFlags.NoMove
            | ImGuiWindowFlags.NoResize
            | ImGuiWindowFlags.NoCollapse
            | ImGuiWindowFlags.NoSavedSettings
            | ImGuiWindowFlags.NoNavInputs
            | ImGuiWindowFlags.NoFocusOnAppearing
            | ImGuiWindowFlags.NoBringToFrontOnFocus
            | ImGuiWindowFlags.NoScrollbar
            | ImGuiWindowFlags.NoScrollWithMouse;
    private static final float PADDING_X = 14;
    private static final float PADDING_Y = 12;
    private static final float HEADER_BUTTON = 24;
    private static final float SCROLL_ROOM = 10;
    private static final float KEYS_WIDTH = 148;
    private static final float KEYS_SHARE = .4f;
    private static final float PIECE_GAP = 5;
    private static final float LINE_GAP = 4;

    private final ImString query = new ImString(64);
    private final float[] guideButton = new float[2];
    private String shownSearch = "";

    public float[] getGuideButton() {
        return guideButton;
    }

    public void search(String words) {
        query.set(words);
    }

    public Action draw(View view, ImFont heading, float left, float top, float bottom, float width) {
        ImGui.setNextWindowPos(Math.round(left), Math.round(top), ImGuiCond.Always);
        ImGui.setNextWindowSize(Math.round(width), Math.max(160, Math.round(bottom - top)), ImGuiCond.Always);
        ImGui.setNextWindowBgAlpha(.985f);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowRounding, HudWidgets.PANEL_RADIUS);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, PADDING_X, PADDING_Y);
        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, 8, 4);
        ImGui.begin(WINDOW, WINDOW_FLAGS);
        Motion.open(Motion.appear(SHOWN, Motion.OPEN), -20, 0, .98f);
        var action = header(heading);
        searchField();
        GeodataWidgets.space(GeodataWidgets.BLOCK);
        body(ShortcutCatalog.sections(view));
        ImGui.end();
        ImGui.popStyleVar(3);
        return action;
    }

    private Action header(ImFont heading) {
        var action = Action.NONE;
        var draw = ImGui.getWindowDrawList();
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var right = x + ImGui.getContentRegionAvailX();
        HudWidgets.glyph(draw, Glyph.KEYBOARD, x, y + 3, 18, HudWidgets.ACCENT);
        ImGui.setCursorScreenPos(x + 26, y + 1);
        ImGui.pushFont(heading, 18);
        ImGui.textUnformatted("Controls & shortcuts");
        ImGui.popFont();
        var closeX = right - HEADER_BUTTON;
        var guideX = closeX - GeodataWidgets.ROW - HEADER_BUTTON;
        ImGui.setCursorScreenPos(guideX, y);
        if (HudWidgets.button("shortcuts-guide", Glyph.BOOK_OPEN, "", HEADER_BUTTON, HEADER_BUTTON, false, false)) {
            action = Action.GUIDE;
        }
        guideButton[0] = ImGui.getItemRectMinX();
        guideButton[1] = ImGui.getItemRectMinY();
        GeodataWidgets.tip("Guide: what the tools are for and how to use them · Shift+F1");
        var dividerX = Math.round(closeX - GeodataWidgets.ROW / 2f);
        draw.addLine(dividerX, y + 5, dividerX, y + HEADER_BUTTON - 5, HudWidgets.BORDER, 1);
        ImGui.setCursorScreenPos(closeX, y);
        if (HudWidgets.button("shortcuts-close", Glyph.CLOSE, "", HEADER_BUTTON, HEADER_BUTTON, false, false)) {
            action = Action.CLOSE;
        }
        GeodataWidgets.tip("Close · Esc or F1");
        ImGui.setCursorScreenPos(x, y + HEADER_BUTTON);
        ImGui.dummy(0, 0);
        GeodataWidgets.space(6);
        HudWidgets.line(ImGui.getContentRegionAvailX());
        GeodataWidgets.space(GeodataWidgets.ROW);
        return action;
    }

    private void searchField() {
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        ImGui.pushStyleVar(ImGuiStyleVar.FramePadding, 30, 7);
        GeodataWidgets.searchField(
                "##shortcutSearch", "Search keys and actions", query, ImGui.getContentRegionAvailX(), false);
        ImGui.popStyleVar();
        HudWidgets.glyph(ImGui.getWindowDrawList(), Glyph.SEARCH, x + 9, y + 7, 15, HudWidgets.MUTED);
    }

    private void body(List<Section> sections) {
        var search = query.get().strip().toLowerCase(Locale.ROOT);
        var words = search.isEmpty() ? new String[0] : search.split("\\s+");
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0, 0);
        ImGui.beginChild("##shortcutBody", 0, 0, false);
        ImGui.popStyleVar();
        if (!search.equals(shownSearch)) {
            ImGui.setScrollY(0);
            shownSearch = search;
        }
        var width = (float) Math.floor(ImGui.getWindowWidth() - SCROLL_ROOM);
        var keysWidth = (float) Math.floor(Math.min(KEYS_WIDTH, width * KEYS_SHARE));
        var first = true;
        for (var section : sections) {
            var whole = words.length > 0 && matches(section.getTitle(), words);
            var shown = section.getRows().stream()
                    .filter(row -> whole || row.matches(words))
                    .toList();
            if (shown.isEmpty()) {
                continue;
            }
            GeodataWidgets.section(section.getTitle(), first ? 0 : GeodataWidgets.SECTION);
            GeodataWidgets.space(GeodataWidgets.ROW);
            first = false;
            for (var index = 0; index < shown.size(); index++) {
                if (index > 0) {
                    GeodataWidgets.space(GeodataWidgets.ROW);
                }
                row(shown.get(index), width, keysWidth);
            }
        }
        if (first) {
            GeodataWidgets.note("No key or action has these words.", GeodataWidgets.FAINT, width);
        }
        GeodataWidgets.space(GeodataWidgets.BLOCK);
        ImGui.endChild();
    }

    private static void row(Row row, float width, float keysWidth) {
        var draw = ImGui.getWindowDrawList();
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var keysBottom = keys(draw, row.pieces(), x, y, keysWidth);
        var actionX = x + keysWidth + GeodataWidgets.BLOCK;
        ImGui.setCursorScreenPos(actionX, y + 2);
        GeodataWidgets.note(row.action(), GeodataWidgets.MUTED, x + width - actionX);
        var hovered = ImGui.isItemHovered();
        var bottom = Math.max(keysBottom, ImGui.getItemRectMaxY() + 2);
        ImGui.setCursorScreenPos(x, bottom);
        ImGui.dummy(width, 0);
        if (hovered && row.tip() != null) {
            HudWidgets.showTooltip(row.tip());
        }
    }

    private static float keys(ImDrawList draw, List<Piece> pieces, float left, float top, float width) {
        var capHeight = ImGui.getFontSize() + 4;
        var space = ImGui.calcTextSizeX(" ");
        var x = left;
        var y = top;
        for (var piece : pieces) {
            if (piece.kind() == Kind.KEY) {
                var capWidth = Math.max(18, ImGui.calcTextSizeX(piece.text()) + 10);
                if (x > left && x + capWidth > left + width) {
                    x = left;
                    y += capHeight + LINE_GAP;
                }
                x += GeodataWidgets.keycap(draw, x, y, piece.text()) + PIECE_GAP;
                continue;
            }
            var color = piece.kind() == Kind.WORD ? GeodataWidgets.TEXT : GeodataWidgets.FAINT;
            for (var word : piece.text().split(" ")) {
                var wordWidth = ImGui.calcTextSizeX(word);
                if (x > left && x + wordWidth > left + width) {
                    x = left;
                    y += capHeight + LINE_GAP;
                }
                draw.addText(x, y + 2, color, word);
                x += wordWidth + space;
            }
            x += PIECE_GAP - space;
        }
        return y + capHeight;
    }

    private static boolean matches(String title, String[] words) {
        var text = title.toLowerCase(Locale.ROOT);
        for (var word : words) {
            if (!text.contains(word)) {
                return false;
            }
        }
        return true;
    }
}
