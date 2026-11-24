package com.rost.cruma.viewer.ui;

import com.rost.cruma.core.geodata.GeoQueryEngine.Completeness;
import com.rost.cruma.core.geodata.GeoQueryEngine.Direction;
import com.rost.cruma.viewer.guide.GuideLibrary;
import com.rost.cruma.viewer.guide.GuideRequests;
import com.rost.cruma.viewer.launch.FileManager;
import com.rost.cruma.viewer.ui.GeodataWorkspace.Viewport;
import com.rost.cruma.viewer.ui.HudWidgets.Glyph;
import imgui.ImColor;
import imgui.ImDrawList;
import imgui.ImFont;
import imgui.ImGui;
import imgui.flag.ImGuiChildFlags;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiColorEditFlags;
import imgui.flag.ImGuiComboFlags;
import imgui.flag.ImGuiFocusedFlags;
import imgui.flag.ImGuiHoveredFlags;
import imgui.flag.ImGuiKey;
import imgui.flag.ImGuiMouseButton;
import imgui.flag.ImGuiMouseCursor;
import imgui.flag.ImGuiPopupFlags;
import imgui.flag.ImGuiSliderFlags;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImFloat;
import imgui.type.ImInt;
import imgui.type.ImString;
import java.io.File;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import org.jetbrains.annotations.Nullable;

public final class GeodataWidgets {
    public static final int TEXT = HudWidgets.TEXT;
    public static final int MUTED = HudWidgets.MUTED;
    public static final int ACCENT = HudWidgets.ACCENT;
    public static final int OPEN = ImColor.rgb(110, 222, 140);
    public static final int BLOCKED = ImColor.rgb(255, 98, 86);
    public static final int WARNING = ImColor.rgb(255, 200, 90);
    public static final int FAINT = ImColor.rgb(92, 110, 126);
    public static final int DIVIDER = ImColor.rgba(255, 255, 255, 28);
    public static final int CARD = ImColor.rgb(28, 38, 48);
    public static final int HOVER = ImColor.rgb(34, 51, 63);

    public static final float SAVE_BAR = 46;

    public static final int BAR_UNDO = 0;

    public static final int BAR_REDO = 1;

    public static final int BAR_ACTION = 2;

    public static final int BAR_SECOND = 3;

    public static final double FACTOR_STEP = .01;

    public static final float BLOCK = 12;

    public static final float SECTION = 20;
    public static final float ROW = 8;

    public static final float LINE_ROW = HudWidgets.MENU_ROW;

    public static final float LINE_INSET = 10;

    public static final float KEYCAP_ICON = 22;

    public static final float FOOTER = SAVE_BAR - 6;

    public static final float DIALOG_SIDE = 20;

    public static final float DIALOG_EDGE = 16;

    public static final float DIALOG_TITLE = 20;

    public static final float DIALOG_CHROME =
            DIALOG_EDGE + DIALOG_TITLE + BLOCK + BLOCK + 1 + BLOCK + FOOTER + DIALOG_EDGE;

    public static final float INTEGER_WIDTH = 140;

    static final float SIDE_MIN = .2f;

    static final float SIDE_MARGIN = .25f;

    private static final int SELECTED_ROW = ImColor.rgb(30, 58, 70);

    private static final int STRIP_FILL = ImColor.rgba(10, 16, 22, 205);
    private static final int STRIP_EDGE = ImColor.rgba(255, 255, 255, 20);

    private static final float STEPS_PADDING = 16;
    private static final float STEPS_ICON = 22;

    private static final float BAR_PAIR_BUTTON = 56;
    private static final float BAR_PAIR_PADDING = 24;
    private static final float BAR_PAIR_GAP = 4;

    private static final int COMMAND_SHOWN = 160;
    private static final float COMMAND_FONT = 13;
    private static final double COMMAND_COPIED_SECONDS = 1.5;

    private static final float SEARCH_LIST_WIDTH = 260;

    private static final float SCREEN_SHAPE = 9f / 16;

    private static final float COLOR_PICKER = 200;

    private static final float SEGMENT_PADDING = 12;

    private static final float SETTING_GAP = 16;
    private static final float SETTING_LABEL_LEAST = 120;

    private static final float SETTING_LINE = 3;
    private static final float SETTING_DETAIL = 5;

    private static final float SWITCH_WIDTH = 30;
    private static final float SWITCH_HEIGHT = 16;

    private static final float FOLDER_PARENT_LEAST = 60;

    private static final float CURRENT_BAR = 3;
    private static final float CURRENT_BAR_HEIGHT = 18;

    private static float settingLeft;
    private static float settingTop;
    private static float settingWidth;
    private static float settingControlTop;
    private static float settingLabelBottom;

    private static @Nullable String activeFolderInput;

    private static boolean chooseFirst;

    private static final float[] dialogGuide = new float[4];
    private static final float[] dialogWindow = new float[4];

    private static float hintsRightOf;

    private static @Nullable String commandCopied;
    private static double commandCopiedUntil;

    private GeodataWidgets() {}

    public static void space(float pixels) {
        ImGui.dummy(0, Math.max(0, pixels - 2 * ImGui.getStyle().getItemSpacingY()));
    }

    public static void section(String label) {
        section(label, SECTION);
    }

    public static void section(String label, float above) {
        space(above);
        ImGui.textColored(MUTED, label);
    }

    public static boolean callout(String id, Glyph glyph, String title, String detail, String action) {
        var draw = ImGui.getWindowDrawList();
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var width = ImGui.getContentRegionAvailX();
        var height = 50f;
        draw.addRectFilled(x, y, x + width, y + height, ImColor.rgba(255, 200, 90, 22), 8f);
        draw.addRect(x, y, x + width, y + height, ImColor.rgba(255, 200, 90, 70), 8, 0, 1);
        HudWidgets.glyph(draw, glyph, x + 10, y + 13, 22, WARNING);
        var buttonWidth = Math.max(70, ImGui.calcTextSizeX(action) + 24);
        draw.pushClipRect(x, y, x + width - buttonWidth - 14, y + height, true);
        draw.addText(x + 42, y + 8, TEXT, title);
        draw.addText(x + 42, y + 26, MUTED, detail);
        draw.popClipRect();
        ImGui.setCursorScreenPos(x + width - buttonWidth - 8, y + 11);
        var pressed = HudWidgets.button(id, null, action, buttonWidth, 28, true, true);
        ImGui.setCursorScreenPos(x, y + height);
        ImGui.dummy(width, 0);
        return pressed;
    }

    public static boolean stackedCallout(
            String id, Glyph glyph, String title, String detail, String action, float width) {
        var draw = ImGui.getWindowDrawList();
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var height = stackedCalloutHeight(detail, width);
        draw.addRectFilled(x, y, x + width, y + height, ImColor.rgba(255, 200, 90, 22), 8f);
        draw.addRect(x, y, x + width, y + height, ImColor.rgba(255, 200, 90, 70), 8, 0, 1);
        HudWidgets.glyph(draw, glyph, x + STEPS_PADDING, y + STEPS_PADDING, STEPS_ICON, WARNING);
        var textLeft = x + STEPS_PADDING + STEPS_ICON + BLOCK;
        var textRight = x + width - STEPS_PADDING;
        draw.addText(
                textLeft,
                y + STEPS_PADDING + (STEPS_ICON - ImGui.getFontSize()) * .5f,
                TEXT,
                HudWidgets.truncateToWidth(title, textRight - textLeft));
        var line = ImGui.getFontSize() + 4;
        var top = y + STEPS_PADDING + STEPS_ICON + ROW;
        for (var part : wrapWords(detail, textRight - textLeft)) {
            draw.addText(textLeft, top, MUTED, part);
            top += line;
        }
        var buttonWidth = Math.min(textRight - textLeft, Math.max(80, ImGui.calcTextSizeX(action) + 28));
        ImGui.setCursorScreenPos(textLeft, top + BLOCK);
        var pressed = HudWidgets.button(id, null, action, buttonWidth, HudWidgets.CONTROL_HEIGHT, true, true);
        ImGui.setCursorScreenPos(x, y + height);
        ImGui.dummy(width, 0);
        return pressed;
    }

    public static float stackedCalloutHeight(String detail, float width) {
        var wrap = width - STEPS_PADDING - STEPS_ICON - BLOCK - STEPS_PADDING;
        return STEPS_PADDING
                + STEPS_ICON
                + ROW
                + wrapWords(detail, wrap).size() * (ImGui.getFontSize() + 4)
                + BLOCK
                + HudWidgets.CONTROL_HEIGHT
                + STEPS_PADDING;
    }

    public static boolean callout(
            String id, Glyph glyph, String title, String detail, String action, List<String> points) {
        if (points.isEmpty()) {
            return callout(id, glyph, title, detail, action);
        }
        var draw = ImGui.getWindowDrawList();
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var width = ImGui.getContentRegionAvailX();
        var line = ImGui.getFontSize() + 4;
        var height = 50f + points.size() * line + 6;
        draw.addRectFilled(x, y, x + width, y + height, ImColor.rgba(255, 200, 90, 22), 8f);
        draw.addRect(x, y, x + width, y + height, ImColor.rgba(255, 200, 90, 70), 8, 0, 1);
        HudWidgets.glyph(draw, glyph, x + 10, y + 13, 22, WARNING);
        var buttonWidth = Math.max(70, ImGui.calcTextSizeX(action) + 24);
        draw.pushClipRect(x, y, x + width - buttonWidth - 14, y + 50, true);
        draw.addText(x + 42, y + 8, TEXT, title);
        draw.addText(x + 42, y + 26, MUTED, detail);
        draw.popClipRect();
        draw.pushClipRect(x, y, x + width - 8, y + height, true);
        for (var i = 0; i < points.size(); i++) {
            var top = y + 48 + i * line;
            draw.addCircleFilled(x + 46, HudWidgets.textMiddle(top), 2f, WARNING);
            draw.addText(x + 56, top, MUTED, points.get(i));
        }
        draw.popClipRect();
        ImGui.setCursorScreenPos(x + width - buttonWidth - 8, y + 11);
        var pressed = HudWidgets.button(id, null, action, buttonWidth, 28, true, true);
        ImGui.setCursorScreenPos(x, y + height);
        ImGui.dummy(width, 0);
        return pressed;
    }

    public static boolean disclosureCard(
            String id, Glyph glyph, String title, String detail, int detailColor, boolean open) {
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var width = ImGui.getContentRegionAvailX();
        var height = 46f;
        var pressed = ImGui.invisibleButton(id, width, height);
        var hovered = ImGui.isItemHovered();
        var item = ImGui.getItemID();
        var draw = ImGui.getWindowDrawList();
        draw.addRectFilled(
                x, y, x + width, y + height, Motion.mix(CARD, HOVER, Motion.toggle(item, hovered, Motion.QUICK)), 8f);
        draw.addRect(x, y, x + width, y + height, DIVIDER, 8, 0, 1);
        HudWidgets.glyph(draw, glyph, x + 12, y + (height - 18) * .5f, 18, ACCENT);
        draw.pushClipRect(x, y, x + width - 32, y + height, true);
        draw.addText(x + 40, y + 6, TEXT, title);
        draw.addText(x + 40, y + 24, detailColor, detail);
        draw.popClipRect();
        var now = pressed != open;
        HudWidgets.chevron(
                draw, x + width - 18, y + height * .5f, Motion.toggle(item + 1, now, Motion.QUICK * 1.5f), MUTED, 1.4f);
        ImGui.setCursorScreenPos(x, y + height);
        ImGui.dummy(width, 0);
        return now;
    }

    public static boolean saveBar(String id, String status, String action, boolean enabled) {
        var draw = ImGui.getWindowDrawList();
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY() + 4;
        var width = ImGui.getContentRegionAvailX();
        var height = SAVE_BAR - 6;
        draw.addRectFilled(x, y, x + width, y + height, ImColor.rgba(255, 200, 90, 24), 8f);
        draw.addRect(x, y, x + width, y + height, ImColor.rgba(255, 200, 90, 80), 8, 0, 1);
        draw.addCircleFilled(x + 14, HudWidgets.textMiddle(y + (height - ImGui.getFontSize()) * .5f), 4, WARNING, 12);
        var buttonWidth = Math.max(72, ImGui.calcTextSizeX(action) + 28);
        draw.pushClipRect(x, y, x + width - buttonWidth - 14, y + height, true);
        draw.addText(x + 26, y + (height - ImGui.getFontSize()) * .5f, WARNING, status);
        draw.popClipRect();
        ImGui.setCursorScreenPos(x + width - buttonWidth - 6, y + (height - 28) * .5f);
        ImGui.beginDisabled(!enabled);
        var pressed = HudWidgets.button(id, null, action, buttonWidth, 28, true, true);
        ImGui.endDisabled();
        ImGui.setCursorScreenPos(x, y + height);
        ImGui.dummy(width, 0);
        return pressed;
    }

    public static int undoBar(
            String id,
            String status,
            String shortStatus,
            boolean unsaved,
            boolean canUndo,
            boolean canRedo,
            String undoKey,
            String action,
            boolean enabled,
            String actionTip) {
        return undoBar(
                id, status, shortStatus, unsaved, canUndo, canRedo, undoKey, null, "", action, enabled, actionTip);
    }

    public static int undoBar(
            String id,
            String status,
            String shortStatus,
            boolean unsaved,
            boolean canUndo,
            boolean canRedo,
            String undoKey,
            @Nullable String second,
            String secondTip,
            String action,
            boolean enabled,
            String actionTip) {
        var draw = ImGui.getWindowDrawList();
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY() + 4;
        var width = ImGui.getContentRegionAvailX();
        var height = SAVE_BAR - 6;
        draw.addRectFilled(x, y, x + width, y + height, unsaved ? ImColor.rgba(255, 200, 90, 24) : CARD, 8f);
        draw.addRect(x, y, x + width, y + height, unsaved ? ImColor.rgba(255, 200, 90, 80) : DIVIDER, 8, 0, 1);
        var pressed = -1;
        ImGui.setCursorScreenPos(x + 4, y + (height - 26) * .5f);
        if (iconButton(id + "-undo", Glyph.UNDO, canUndo)) {
            pressed = BAR_UNDO;
        }
        tip(canUndo ? "Undo the last change." : "Nothing to undo.", undoKey);
        ImGui.sameLine(0, 2);
        if (iconButton(id + "-redo", Glyph.REDO, canRedo)) {
            pressed = BAR_REDO;
        }
        tip(canRedo ? "Redo the change." : "Nothing to redo.", "Shift+" + undoKey);
        var textLeft = ImGui.getItemRectMaxX() + ROW;
        var buttonWidth = second == null
                ? Math.max(72, ImGui.calcTextSizeX(action) + 28)
                : Math.max(BAR_PAIR_BUTTON, ImGui.calcTextSizeX(action) + BAR_PAIR_PADDING);
        var secondWidth = second == null
                ? 0
                : Math.max(BAR_PAIR_BUTTON, ImGui.calcTextSizeX(second) + BAR_PAIR_PADDING) + BAR_PAIR_GAP;
        var room = x + width - buttonWidth - secondWidth - 14 - textLeft;
        var textTop = y + (height - ImGui.getFontSize()) * .5f;
        var shown = ImGui.calcTextSizeX(status) <= room ? status : truncateToWidth(shortStatus, room);
        draw.addText(textLeft, textTop, unsaved ? WARNING : MUTED, shown);
        if (!shown.equals(status) && ImGui.isMouseHoveringRect(textLeft, y, textLeft + room, y + height)) {
            HudWidgets.showTooltip(status);
        }
        if (second != null) {
            ImGui.setCursorScreenPos(x + width - buttonWidth - secondWidth - 6, y + (height - 28) * .5f);
            if (HudWidgets.button(id + "-second", null, second, secondWidth - BAR_PAIR_GAP, 28, false, true)) {
                pressed = BAR_SECOND;
            }
            tip(secondTip);
        }
        ImGui.setCursorScreenPos(x + width - buttonWidth - 6, y + (height - 28) * .5f);
        ImGui.beginDisabled(!enabled);
        if (HudWidgets.button(id, null, action, buttonWidth, 28, unsaved, true)) {
            pressed = BAR_ACTION;
        }
        ImGui.endDisabled();
        tip(actionTip);
        ImGui.setCursorScreenPos(x, y + height);
        ImGui.dummy(width, 0);
        return pressed;
    }

    public static boolean busyBar(String id, String status, double fraction, String action, boolean enabled) {
        var draw = ImGui.getWindowDrawList();
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY() + 4;
        var width = ImGui.getContentRegionAvailX();
        var height = SAVE_BAR - 6;
        draw.addRectFilled(x, y, x + width, y + height, ImColor.rgba(92, 219, 241, 20), 8f);
        draw.addRect(x, y, x + width, y + height, ImColor.rgba(92, 219, 241, 70), 8, 0, 1);
        var buttonWidth = Math.max(72, ImGui.calcTextSizeX(action) + 28);
        var textTop = y + (height - ImGui.getFontSize()) * .5f - 3;
        draw.addCircleFilled(x + 14, HudWidgets.textMiddle(textTop), 4, ACCENT, 12);
        var right = x + width - buttonWidth - 14;
        draw.pushClipRect(x, y, right, y + height, true);
        draw.addText(x + 26, textTop, ACCENT, status);
        draw.popClipRect();
        var barLeft = x + 26;
        var barTop = y + height - 10;
        draw.addRectFilled(barLeft, barTop, right, barTop + 3, DIVIDER, 2f);
        if (fraction < 0) {
            var span = (right - barLeft) * .3f;
            var start = (float) ((ImGui.getTime() * .6) % 1.3 - .3) * (right - barLeft);
            draw.addRectFilled(
                    barLeft + Math.max(0, start),
                    barTop,
                    barLeft + Math.min(right - barLeft, start + span),
                    barTop + 3,
                    ACCENT,
                    2f);
        } else if (fraction > 0) {
            var done = smoothFraction(id, (float) Math.min(fraction, 1));
            draw.addRectFilled(
                    barLeft, barTop, barLeft + Math.max(3, (right - barLeft) * done), barTop + 3, ACCENT, 2f);
        }
        ImGui.setCursorScreenPos(x + width - buttonWidth - 6, y + (height - 28) * .5f);
        ImGui.beginDisabled(!enabled);
        var pressed = HudWidgets.button(id, null, action, buttonWidth, 28, false, true);
        ImGui.endDisabled();
        ImGui.setCursorScreenPos(x, y + height);
        ImGui.dummy(width, 0);
        return pressed;
    }

    public static boolean isEscapePressed() {
        return ImGui.isKeyPressed(ImGuiKey.Escape, false)
                && ImGui.isWindowFocused(ImGuiFocusedFlags.RootAndChildWindows);
    }

    public static boolean searchField(String id, String hint, ImString value, float width, boolean focus) {
        if (focus) {
            ImGui.setKeyboardFocusHere();
        }
        ImGui.setNextItemWidth(width);
        ImGui.pushStyleColor(ImGuiCol.NavCursor, 0);
        var changed = ImGui.inputTextWithHint(id, hint, value);
        ImGui.popStyleColor();
        return changed;
    }

    public static boolean searchField(String id, String hint, ImString value, float width, boolean focus, Glyph glyph) {
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var height = ImGui.getFrameHeight();
        var icon = 16f;
        ImGui.pushStyleVar(
                ImGuiStyleVar.FramePadding,
                LINE_INSET + icon + ROW,
                ImGui.getStyle().getFramePaddingY());
        var changed = searchField(id, hint, value, width, focus);
        ImGui.popStyleVar();
        HudWidgets.glyph(ImGui.getWindowDrawList(), glyph, x + LINE_INSET, y + (height - icon) * .5f, icon, MUTED);
        return changed;
    }

    public static boolean isPopupOver(boolean before) {
        return before || ImGui.isPopupOpen("", ImGuiPopupFlags.AnyPopupId);
    }

    public static boolean wasClickedOutside() {
        return ImGui.isMouseClicked(ImGuiMouseButton.Left)
                && !ImGui.isWindowAppearing()
                && !ImGui.isWindowHovered(ImGuiHoveredFlags.RootAndChildWindows
                        | ImGuiHoveredFlags.AllowWhenBlockedByPopup
                        | ImGuiHoveredFlags.AllowWhenBlockedByActiveItem)
                && !ImGui.isPopupOpen("", ImGuiPopupFlags.AnyPopupId)
                && !GuideWindow.covers(ImGui.getMousePosX(), ImGui.getMousePosY());
    }

    public static void writtenResult(String id, String text, int color, @Nullable Path written) {
        note(text, color);
        if (written != null) {
            space(ROW);
            showFolder(id, written);
        }
    }

    public static boolean showFolder(String id, Path written) {
        var pressed = HudWidgets.button(id, Glyph.FOLDER, "Show folder", 132, 28, false, true);
        tip(written.toString());
        if (pressed) {
            FileManager.tryShow(written);
        }
        return pressed;
    }

    public static boolean beginDialog(String title, float width, float height) {
        ImGui.setNextWindowSize(width, height);
        var style = ImGui.getStyle();
        ImGui.setNextWindowContentSize(
                Math.max(1, width - 2 * style.getWindowPaddingX()),
                Math.max(1, height - 2 * style.getWindowPaddingY()));
        return ImGui.beginPopupModal(
                title,
                ImGuiWindowFlags.NoDecoration
                        | ImGuiWindowFlags.NoMove
                        | ImGuiWindowFlags.NoSavedSettings
                        | ImGuiWindowFlags.NoScrollWithMouse);
    }

    public static boolean dialogTitle(FontManager fonts, String title, String closeId) {
        Motion.dialog();
        var y = ImGui.getCursorPosY();
        ImGui.pushFont(fonts.getHeadingFont(20), 20);
        ImGui.textUnformatted(title);
        ImGui.popFont();
        ImGui.setCursorPos(ImGui.getWindowWidth() - DIALOG_SIDE - 28, y + (DIALOG_TITLE - 28) * .5f);
        var close = HudWidgets.button(closeId, Glyph.CLOSE, "", 28, 28, false, false);
        ImGui.setCursorPosY(y + DIALOG_TITLE + BLOCK);
        return close;
    }

    public static float[] dialogGuidePlace() {
        return dialogGuide.clone();
    }

    public static float[] dialogPlace() {
        return dialogWindow.clone();
    }

    public static boolean dialogTitle(FontManager fonts, String title, String closeId, String guideArticle) {
        dialogWindow[0] = ImGui.getWindowPosX();
        dialogWindow[1] = ImGui.getWindowPosY();
        dialogWindow[2] = dialogWindow[0] + ImGui.getWindowWidth();
        dialogWindow[3] = dialogWindow[1] + ImGui.getWindowHeight();
        var y = ImGui.getCursorPosY();
        var close = dialogTitle(fonts, title, closeId);
        var after = ImGui.getCursorPosY();
        ImGui.setCursorPos(ImGui.getWindowWidth() - DIALOG_SIDE - 28 - 4 - 28, y + (DIALOG_TITLE - 28) * .5f);
        if (HudWidgets.button(closeId + "-guide", Glyph.HELP, "", 28, 28, false, false)) {
            GuideRequests.open(guideArticle);
        }
        dialogGuide[0] = ImGui.getItemRectMinX();
        dialogGuide[1] = ImGui.getItemRectMinY();
        dialogGuide[2] = ImGui.getItemRectMaxX();
        dialogGuide[3] = ImGui.getItemRectMaxY();
        if (ImGui.isItemHovered()) {
            var found = GuideLibrary.shared().getArticle(guideArticle);
            HudWidgets.showTooltip(
                    found == null ? "Read about this in the Guide." : "Read \"" + found.title() + "\" in the Guide.");
        }
        ImGui.setCursorPosY(after);
        return close;
    }

    public static void beginDialogPanel(String id, float width, float height) {
        ImGui.beginChild(id, width, height, ImGuiChildFlags.NavFlattened, 0);
        var gutter = (float) Math.ceil(ImGui.getStyle().getScrollbarSize()) + 4;
        ImGui.beginChild(
                id + "/content",
                width - gutter,
                0,
                ImGuiChildFlags.AutoResizeY | ImGuiChildFlags.NavFlattened,
                ImGuiWindowFlags.NoScrollWithMouse);
    }

    public static void endDialogPanel() {
        ImGui.endChild();
        ImGui.endChild();
    }

    public static void dialogRule(float bodyBottom) {
        ImGui.setCursorPosY(bodyBottom + BLOCK);
        ImGui.separator();
        ImGui.setCursorPosY(bodyBottom + BLOCK + 1 + BLOCK);
    }

    public record FooterButton(
            String id, @Nullable Glyph glyph, String label, float width, boolean primary, boolean enabled, String tip) {
        public FooterButton(String id, @Nullable Glyph glyph, String label, float width, boolean primary) {
            this(id, glyph, label, width, primary, true, "");
        }
    }

    public static int footer(String status, int color, List<FooterButton> buttons) {
        return footer(status, status, color, "", buttons);
    }

    public static int footer(
            String status, String shortStatus, int color, String statusTip, List<FooterButton> buttons) {
        return drawFooter(status, shortStatus, "", color, statusTip, buttons);
    }

    public static int footer(String status, String detail, int color, List<FooterButton> buttons) {
        return drawFooter(status, status, detail, color, "", buttons);
    }

    static String truncateToWidth(String text, float width) {
        if (ImGui.calcTextSizeX(text) <= width) {
            return text;
        }
        var end = text.length();
        while (end > 0 && ImGui.calcTextSizeX(text.substring(0, end) + "…") > width) {
            end--;
        }
        return text.substring(0, end) + "…";
    }

    public static boolean progress(String id, String title, String detail, double fraction, String action) {
        var draw = ImGui.getWindowDrawList();
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var width = ImGui.getContentRegionAvailX();
        var height = 60f;
        draw.addRectFilled(x, y, x + width, y + height, CARD, 8f);
        draw.addRect(x, y, x + width, y + height, DIVIDER, 8, 0, 1);
        var buttonWidth = Math.max(70, ImGui.calcTextSizeX(action) + 24);
        var room = width - buttonWidth - 26;
        draw.addText(x + 12, y + 8, TEXT, HudWidgets.truncateToWidth(title, room));
        draw.addText(x + 12, y + 26, MUTED, HudWidgets.truncateToWidth(detail, room));
        var barLeft = x + 12;
        var barRight = x + width - 12;
        var barTop = y + height - 12;
        draw.addRectFilled(barLeft, barTop, barRight, barTop + 4, DIVIDER, 2f);
        if (fraction < 0) {
            var span = (barRight - barLeft) * .3f;
            var start = (float) ((ImGui.getTime() * .6) % 1.3 - .3) * (barRight - barLeft);
            draw.addRectFilled(
                    barLeft + Math.max(0, start),
                    barTop,
                    barLeft + Math.min(barRight - barLeft, start + span),
                    barTop + 4,
                    ACCENT,
                    2f);
        }
        var done = fraction < 0 ? 0 : smoothFraction(id, (float) Math.clamp(fraction, 0, 1));
        if (done > 0) {
            draw.addRectFilled(
                    barLeft, barTop, barLeft + Math.max(4, (barRight - barLeft) * done), barTop + 4, ACCENT, 2f);
        }
        ImGui.setCursorScreenPos(x + width - buttonWidth - 8, y + 8);
        var pressed = HudWidgets.button(id, null, action, buttonWidth, 28, false, true);
        ImGui.setCursorScreenPos(x, y + height);
        ImGui.dummy(width, 0);
        return pressed;
    }

    public static int toolTabs(String id, List<ToolTab> tabs, int selected) {
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var width = ImGui.getContentRegionAvailX();
        var height = 46f;
        var draw = ImGui.getWindowDrawList();
        var segment = width / tabs.size();
        var clicked = -1;
        draw.addLine(x, y + height, x + width, y + height, HudWidgets.BORDER, 1);
        if (selected >= 0 && selected < tabs.size()) {
            var line = Motion.spring(id + "/line", segment * selected);
            draw.addRectFilled(x + line + 4, y + height - 3, x + line + segment - 4, y + height, ACCENT, 1f);
        }
        for (var index = 0; index < tabs.size(); index++) {
            var each = tabs.get(index);
            var left = x + segment * index;
            ImGui.setCursorScreenPos(left, y);
            if (ImGui.invisibleButton(id + "/" + index, segment, height)) {
                clicked = index;
            }
            var hovered = ImGui.isItemHovered();
            var item = ImGui.getItemID();
            if (hovered) {
                HudWidgets.showTooltip(
                        each.tip().isEmpty()
                                ? each.label() + (each.key().isEmpty() ? "" : "  ·  " + each.key())
                                : each.tip() + (each.key().isEmpty() ? "" : "\n\nShortcut: " + each.key()));
            }
            var chosen = index == selected;
            var hover = Motion.toggle(item, hovered && !chosen, Motion.QUICK);
            var lit = Motion.toggle(item + 1, chosen, Motion.QUICK * 1.5f);
            if (hover > 0) {
                draw.addRectFilled(
                        left + 2,
                        y + 2,
                        left + segment - 2,
                        y + height - 4,
                        Motion.alpha(ImColor.rgb(30, 44, 54), hover),
                        6f);
            }
            var color = Motion.mix(Motion.mix(MUTED, TEXT, hover), ACCENT, lit);
            HudWidgets.glyph(draw, each.glyph(), left + (segment - 18) * .5f, y + 5 - lit, 18, color);
            draw.addText(
                    left + (segment - ImGui.calcTextSizeX(each.label())) * .5f,
                    y + 25,
                    Motion.mix(color, TEXT, lit),
                    each.label());
            if (!each.key().isEmpty()) {
                miniKey(
                        draw,
                        left + segment - miniKeyWidth(each.key()) - 3,
                        y + 2,
                        each.key(),
                        Motion.mix(FAINT, ACCENT, lit));
            }
        }
        ImGui.setCursorScreenPos(x, y + height);
        ImGui.dummy(width, 0);
        return clicked;
    }

    public record ToolTab(String label, Glyph glyph, String key, String tip) {}

    public static int segmented(String id, String[] labels, int selected) {
        return segmented(id, labels, selected, ImGui.getContentRegionAvailX());
    }

    public static int segmented(String id, String[] labels, int selected, float width) {
        var widths = new float[labels.length];
        Arrays.fill(widths, width / labels.length);
        return drawSegments(id, labels, selected, widths);
    }

    public static int fittedSegmented(String id, String[] labels, int selected) {
        var widths = new float[labels.length];
        for (var index = 0; index < labels.length; index++) {
            widths[index] = ImGui.calcTextSizeX(labels[index]) + 2 * SEGMENT_PADDING;
        }
        return drawSegments(id, labels, selected, widths);
    }

    public static float fittedSegmentedWidth(String[] labels) {
        var width = 0f;
        for (var label : labels) {
            width += ImGui.calcTextSizeX(label) + 2 * SEGMENT_PADDING;
        }
        return width;
    }

    public static int hoveredSegment(float left, float width, int count) {
        return segmentAt(ImGui.getMousePosX(), left, width, count);
    }

    static int segmentAt(float pointer, float left, float width, int count) {
        return Math.clamp((int) ((pointer - left) / (width / count)), 0, count - 1);
    }

    public static boolean chip(String id, Glyph glyph, String label, boolean on, float width) {
        return chip(id, glyph, label, "", on, width);
    }

    public static boolean chip(String id, Glyph glyph, String label, String key, boolean on, float width) {
        return chip(id, glyph, label, key, on, width, 26);
    }

    public static boolean chip(
            String id, Glyph glyph, String label, String key, boolean on, float width, float height) {
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var pressed = ImGui.invisibleButton(id, width, height);
        var hovered = ImGui.isItemHovered();
        var item = ImGui.getItemID();
        var lit = Motion.toggle(item, on, Motion.QUICK * 1.5f);
        var hover = Motion.toggle(item + 1, hovered, Motion.QUICK);
        var draw = ImGui.getWindowDrawList();
        draw.addRectFilled(
                x,
                y,
                x + width,
                y + height,
                Motion.mix(
                        Motion.mix(ImColor.rgb(24, 33, 42), ImColor.rgb(34, 51, 63), hover),
                        ImColor.rgb(30, 58, 70),
                        lit),
                6f);
        draw.addRect(x, y, x + width, y + height, Motion.mix(HudWidgets.BORDER, ACCENT, lit), 6, 0, 1);
        HudWidgets.ripple(draw, item, x, y, width, height);
        var textWidth = label.isEmpty() ? 0 : ImGui.calcTextSizeX(label) + 6;
        var iconSize = glyph != null && (label.isEmpty() || 16 + textWidth + 8 <= width) ? 16f : 0f;
        var keyWidth = miniKeyWidth(key);
        var showKey = !key.isEmpty() && iconSize + textWidth + keyWidth + 16 <= width;
        var content = width - (showKey ? keyWidth + 6 : 0);
        var left = x + (content - iconSize - (iconSize > 0 ? textWidth : textWidth - 6)) * .5f;
        if (iconSize > 0) {
            HudWidgets.glyph(
                    draw, glyph, left, y + (height - iconSize) * .5f, iconSize, Motion.mix(MUTED, ACCENT, lit));
        }
        if (!label.isEmpty()) {
            draw.addText(
                    left + (iconSize > 0 ? iconSize + 6 : 0),
                    y + (height - ImGui.getFontSize()) * .5f - 1,
                    Motion.mix(MUTED, TEXT, lit),
                    label);
        }
        if (showKey) {
            miniKey(
                    draw,
                    x + width - keyWidth - 6,
                    y + (height - miniKeyHeight()) * .5f,
                    key,
                    Motion.mix(FAINT, ACCENT, lit));
        }
        return pressed;
    }

    public static void miniKey(ImDrawList draw, float x, float y, String key, int color) {
        var width = miniKeyWidth(key);
        var height = miniKeyHeight();
        draw.addRect(x, y, x + width, y + height, color, 3, 0, 1);
        draw.addText(
                x + (width - ImGui.calcTextSizeX(key)) * .5f, y + (height - ImGui.getFontSize()) * .5f, color, key);
    }

    public static float miniKeyWidth(String key) {
        return key.isEmpty() ? 0 : Math.max(miniKeyHeight(), ImGui.calcTextSizeX(key) + 8);
    }

    public static boolean iconButton(String id, Glyph glyph, boolean enabled) {
        ImGui.beginDisabled(!enabled);
        var pressed = HudWidgets.button(id, glyph, "", 26, 26, false, false);
        ImGui.endDisabled();
        return pressed;
    }

    public static boolean actionButton(String id, Glyph glyph, String label, float width, boolean primary) {
        return HudWidgets.button(id, glyph, label, width, 28, primary, true);
    }

    public static boolean rowButton(String label, int column, int columns) {
        if (column > 0) {
            ImGui.sameLine();
        }
        var remaining = columns - column;
        var width = (ImGui.getContentRegionAvailX() - ImGui.getStyle().getItemSpacingX() * (remaining - 1)) / remaining;
        return HudWidgets.button("row/" + label, null, label, width, 28, false, true);
    }

    public static float columnWidth(int columns) {
        return (ImGui.getContentRegionAvailX() - ImGui.getStyle().getItemSpacingX() * (columns - 1)) / columns;
    }

    public static void tip(String text) {
        tip(text, null);
    }

    public static void tip(String text, String shortcut) {
        if (ImGui.isItemHovered(ImGuiHoveredFlags.AllowWhenDisabled)) {
            HudWidgets.showTooltip(shortcut == null ? text : text + "\n\nShortcut: " + shortcut);
        }
    }

    public static float keycap(ImDrawList draw, float x, float y, String key) {
        var width = Math.max(18, ImGui.calcTextSizeX(key) + 10);
        var height = ImGui.getFontSize() + 4;
        draw.addRectFilled(x, y, x + width, y + height, ImColor.rgba(255, 255, 255, 22), 4f);
        draw.addRect(x, y, x + width, y + height, ImColor.rgba(255, 255, 255, 60), 4, 0, 1);
        draw.addText(x + (width - ImGui.calcTextSizeX(key)) * .5f, y + 2, TEXT, key);
        return width;
    }

    public static float keycap(ImDrawList draw, float x, float y, Glyph key) {
        var height = ImGui.getFontSize() + 4;
        draw.addRectFilled(x, y, x + KEYCAP_ICON, y + height, ImColor.rgba(255, 255, 255, 22), 4f);
        draw.addRect(x, y, x + KEYCAP_ICON, y + height, ImColor.rgba(255, 255, 255, 60), 4, 0, 1);
        HudWidgets.glyph(draw, key, x + (KEYCAP_ICON - 14) * .5f, y + (height - 14) * .5f, 14, TEXT);
        return KEYCAP_ICON;
    }

    public static void value(String label, String value) {
        value(label, value, TEXT);
    }

    public static void value(String label, String value, int color) {
        ImGui.pushStyleColor(ImGuiCol.Text, MUTED);
        ImGui.textUnformatted(label);
        ImGui.popStyleColor();
        ImGui.sameLine(Math.max(ImGui.getCursorPosX(), 92));
        ImGui.pushTextWrapPos(0);
        ImGui.pushStyleColor(ImGuiCol.Text, color);
        ImGui.textUnformatted(value);
        ImGui.popStyleColor();
        ImGui.popTextWrapPos();
    }

    public static boolean textField(String label, ImString value) {
        ImGui.textColored(MUTED, label);
        ImGui.setNextItemWidth(-1);
        return ImGui.inputText("##" + label, value);
    }

    public static Field actionField(
            String id, String hint, ImString value, String fieldTip, Glyph glyph, String actionTip, boolean enabled) {
        return drawActionField(
                id, hint, value, fieldTip, glyph, actionTip, enabled, ImGui.getContentRegionAvailX(), false, ACCENT);
    }

    public static Field folderInput(String id, String hint, ImString value, String fieldTip, float width) {
        var typed = value.get();
        var idle = !typed.isBlank() && !id.equals(activeFolderInput);
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var field = drawActionField(id, hint, value, fieldTip, Glyph.FOLDER, "Browse", true, width, idle, MUTED);
        if (field.editing()) {
            activeFolderInput = id;
        } else if (id.equals(activeFolderInput)) {
            activeFolderInput = null;
        }
        if (idle) {
            var height = ImGui.getFrameHeight();
            var keyWidth = height - 6 + 8;
            var left = x + ImGui.getStyle().getFramePaddingX();
            drawFolderName(
                    ImGui.getWindowDrawList(),
                    left,
                    y + (height - ImGui.getFontSize()) * .5f,
                    typed,
                    x + width - keyWidth - 3 - 4 - left);
        }
        return field;
    }

    public static float folderInputRoom(float width) {
        var keyWidth = ImGui.getFrameHeight() - 6 + 8;
        return width - keyWidth - 3 - 2 * ImGui.getStyle().getFramePaddingX();
    }

    public static void folderText(Path folder, float width) {
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        drawFolderName(
                ImGui.getWindowDrawList(),
                x,
                y + (HudWidgets.CONTROL_HEIGHT - ImGui.getFontSize()) * .5f - 1,
                folder.toString(),
                width);
        ImGui.dummy(width, HudWidgets.CONTROL_HEIGHT);
        tip(folder.toString());
    }

    public static void beginSettingRow(String label, String detail, float controlWidth) {
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var width = ImGui.getContentRegionAvailX();
        var room = width - controlWidth - SETTING_GAP;
        var stacked = room < SETTING_LABEL_LEAST;
        var labelBottom = drawSettingLabel(x, y, stacked ? width : room, label, detail, !stacked);
        settingLeft = x;
        settingTop = y;
        settingWidth = width;
        settingControlTop = stacked ? labelBottom + ROW : y;
        settingLabelBottom = labelBottom;
        ImGui.setCursorScreenPos(x + Math.max(0, width - controlWidth), settingControlTop);
    }

    public static boolean endSettingRow() {
        var bottom = Math.max(settingLabelBottom, settingControlTop + HudWidgets.CONTROL_HEIGHT);
        if (ImGui.getItemRectMinY() >= settingTop) {
            bottom = Math.max(bottom, ImGui.getItemRectMaxY());
        }
        var hovered = ImGui.isWindowHovered()
                && ImGui.isMouseHoveringRect(settingLeft, settingTop, settingLeft + settingWidth, bottom);
        ImGui.setCursorScreenPos(settingLeft, bottom);
        ImGui.dummy(settingWidth, 0);
        return hovered;
    }

    public static boolean settingSwitchRow(
            String id, String label, String detail, boolean value, boolean enabled, @Nullable String tooltip) {
        return settingSwitchRow(id, label, detail, value, enabled, tooltip, ImGui.getContentRegionAvailX());
    }

    public static boolean settingSwitchRow(
            String id,
            String label,
            String detail,
            boolean value,
            boolean enabled,
            @Nullable String tooltip,
            float width) {
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var room = Math.max(SETTING_LABEL_LEAST, width - SWITCH_WIDTH - SETTING_GAP);
        var height = Math.max(HudWidgets.CONTROL_HEIGHT, measureSettingLabel(y, room, label, detail, true) - y);
        ImGui.beginDisabled(!enabled);
        var pressed = ImGui.invisibleButton(id, width, height);
        var hovered = ImGui.isItemHovered(ImGuiHoveredFlags.AllowWhenDisabled);
        var next = enabled && pressed ? !value : value;
        var draw = ImGui.getWindowDrawList();
        var lit = Motion.toggle(ImGui.getItemID(), enabled && hovered, Motion.QUICK);
        if (lit > 0) {
            draw.addRectFilled(x, y, x + width, y + height, Motion.alpha(HudWidgets.ROW_HOVER, lit), 4f);
        }
        drawSettingLabel(x, y, room, label, detail, true);
        HudWidgets.drawSwitch(
                draw, x + width - SWITCH_WIDTH, y + (HudWidgets.CONTROL_HEIGHT - SWITCH_HEIGHT) * .5f, next, enabled);
        ImGui.endDisabled();
        if (tooltip != null && hovered) {
            HudWidgets.showTooltip(tooltip);
        }
        ImGui.setCursorScreenPos(x, y + height);
        ImGui.dummy(width, 0);
        return next;
    }

    public static boolean settingChevronRow(String id, String label, String detail, String note, boolean open) {
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var width = ImGui.getContentRegionAvailX();
        var chevron = 16f;
        var noteWidth = note.isEmpty() ? 0 : ImGui.calcTextSizeX(note) + ROW;
        var room = Math.max(SETTING_LABEL_LEAST, width - chevron - noteWidth - SETTING_GAP);
        var height = Math.max(HudWidgets.CONTROL_HEIGHT, measureSettingLabel(y, room, label, detail, true) - y);
        var pressed = ImGui.invisibleButton(id, width, height);
        var hovered = ImGui.isItemHovered();
        var item = ImGui.getItemID();
        var draw = ImGui.getWindowDrawList();
        var lit = Motion.toggle(item, hovered, Motion.QUICK);
        if (lit > 0) {
            draw.addRectFilled(x, y, x + width, y + height, Motion.alpha(HudWidgets.ROW_HOVER, lit), 4f);
        }
        drawSettingLabel(x, y, room, label, detail, true);
        var middle = y + HudWidgets.CONTROL_HEIGHT * .5f;
        var turn = Motion.toggle(item + 1, open, Motion.QUICK * 1.5f);
        HudWidgets.chevron(draw, x + width - chevron * .5f, middle, turn, Motion.mix(MUTED, TEXT, lit), 1.2f);
        if (!note.isEmpty()) {
            draw.addText(
                    x + width - chevron - noteWidth,
                    y + (HudWidgets.CONTROL_HEIGHT - ImGui.getFontSize()) * .5f - 1,
                    FAINT,
                    note);
        }
        return pressed;
    }

    public static void status(String text, int dot) {
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var draw = ImGui.getWindowDrawList();
        var textTop = y + (HudWidgets.CONTROL_HEIGHT - ImGui.getFontSize()) * .5f - 1;
        var left = x;
        if (dot != 0) {
            draw.addCircleFilled(x + 4, HudWidgets.textMiddle(textTop), 4, ImGui.getColorU32i(dot), 12);
            left += 4 + 4 + 6;
        }
        draw.addText(left, textTop, ImGui.getColorU32i(MUTED), text);
        ImGui.dummy(statusWidth(text, dot), HudWidgets.CONTROL_HEIGHT);
    }

    public static float statusWidth(String text, int dot) {
        return (dot == 0 ? 0 : 4 + 4 + 6) + ImGui.calcTextSizeX(text);
    }

    public record Field(boolean action, boolean editing) {}

    public static boolean commandField(String id, String label, String text, @Nullable ImFont mono) {
        ImGui.textColored(MUTED, label);
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var width = ImGui.getContentRegionAvailX();
        var height = HudWidgets.CONTROL_HEIGHT;
        var draw = ImGui.getWindowDrawList();
        draw.addRectFilled(x, y, x + width, y + height, ImGui.getColorU32(ImGuiCol.FrameBg), 6f);
        draw.addRect(x, y, x + width, y + height, HudWidgets.BORDER, 6, 0, 1);
        var start = text.length() > COMMAND_SHOWN ? text.substring(0, COMMAND_SHOWN) : text;
        if (mono != null) {
            ImGui.pushFont(mono, COMMAND_FONT);
        }
        var shown = truncateToWidth(start, width - height - 12);
        draw.addText(x + 8, y + (height - ImGui.getFontSize()) * .5f, HudWidgets.TEXT, shown);
        if (mono != null) {
            ImGui.popFont();
        }
        ImGui.setCursorScreenPos(x, y);
        ImGui.dummy(width - height, height);
        tip(start.length() < text.length() ? start + "…" : text);
        ImGui.setCursorScreenPos(x + width - height, y);
        var done = id.equals(commandCopied) && ImGui.getTime() < commandCopiedUntil;
        var pressed = HudWidgets.button(id, done ? Glyph.CHECK : Glyph.COPY, "", height, height, false, false);
        tip(done ? "Copied" : "Copy");
        if (pressed) {
            ImGui.setClipboardText(text);
            commandCopied = id;
            commandCopiedUntil = ImGui.getTime() + COMMAND_COPIED_SECONDS;
        }
        ImGui.setCursorScreenPos(x, y + height);
        ImGui.dummy(width, 0);
        return pressed;
    }

    public static boolean pathField(String id, String label, ImString value, String fieldTip) {
        ImGui.textColored(MUTED, label);
        return actionField(id, "Path", value, fieldTip, Glyph.FOLDER, "Browse", true)
                .action();
    }

    public static boolean folderField(String id, String label, String path, String fieldTip) {
        if (!label.isEmpty()) {
            ImGui.textColored(MUTED, label);
        }
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var width = ImGui.getContentRegionAvailX();
        var height = ImGui.getFrameHeight();
        var pressed = ImGui.invisibleButton("##" + id, width, height);
        var hovered = ImGui.isItemHovered();
        if (hovered) {
            ImGui.setMouseCursor(ImGuiMouseCursor.Hand);
        }
        Path folder = null;
        try {
            folder = path.isBlank() ? null : Path.of(path.trim());
        } catch (InvalidPathException invalid) {
        }
        var name = folder == null || folder.getFileName() == null
                ? path.trim()
                : folder.getFileName().toString();
        var parent = folder == null ? null : folder.getParent();
        tip(parent == null ? fieldTip : "In " + parent + "\n\n" + fieldTip);
        var draw = ImGui.getWindowDrawList();
        var style = ImGui.getStyle();
        var fill = ImGui.getColorU32(
                ImGui.isItemActive() ? ImGuiCol.FrameBgActive : hovered ? ImGuiCol.FrameBgHovered : ImGuiCol.FrameBg);
        draw.addRectFilled(x, y, x + width, y + height, fill, style.getFrameRounding());
        if (style.getFrameBorderSize() > 0) {
            draw.addRect(
                    x,
                    y,
                    x + width,
                    y + height,
                    ImGui.getColorU32(ImGuiCol.Border),
                    style.getFrameRounding(),
                    0,
                    style.getFrameBorderSize());
        }
        var keyHeight = height - 6;
        var keyWidth = keyHeight + 8;
        var left = x + width - keyWidth - 3;
        var textLeft = x + style.getFramePaddingX();
        var shown = name.isEmpty() ? "No folder chosen" : name;
        draw.addText(
                textLeft,
                y + (height - ImGui.getFontSize()) * .5f,
                name.isEmpty() ? FAINT : TEXT,
                truncateToWidth(shown, left - textLeft - 4));
        var glyphSize = keyHeight + 2;
        HudWidgets.glyph(
                draw,
                Glyph.FOLDER,
                left + (keyWidth - glyphSize) * .5f,
                y + 3 + (keyHeight - glyphSize) * .5f,
                glyphSize,
                hovered ? TEXT : ACCENT);
        return pressed;
    }

    public static void hint(String text) {
        note(text, MUTED);
    }

    public static void note(String text, int color) {
        wrapped(text, color, 0);
    }

    public static void note(String text, int color, float width) {
        wrapped(text, color, ImGui.getCursorPosX() + width);
    }

    public static boolean integer(String label, ImInt value, int step) {
        ImGui.alignTextToFramePadding();
        ImGui.textColored(MUTED, label);
        ImGui.sameLine(0, ROW);
        ImGui.setNextItemWidth(Math.min(INTEGER_WIDTH, ImGui.getContentRegionAvailX()));
        return ImGui.inputInt("##" + label, value, step, step * 8);
    }

    public static boolean decimal(String label, ImFloat value, String unit) {
        ImGui.alignTextToFramePadding();
        ImGui.textColored(MUTED, label);
        ImGui.sameLine(0, ROW);
        ImGui.setNextItemWidth(Math.min(INTEGER_WIDTH, ImGui.getContentRegionAvailX()));
        ImGui.inputFloat("##" + label, value, 0, 0, "%g" + unit.replace("%", "%%"));
        return ImGui.isItemDeactivatedAfterEdit();
    }

    public static boolean header(String label) {
        return header(label, 0);
    }

    public static boolean header(String label, int flags) {
        ImGui.pushStyleColor(ImGuiCol.Header, 1f, 1f, 1f, .035f);
        ImGui.pushStyleColor(ImGuiCol.HeaderHovered, .47f, .84f, 1f, .12f);
        ImGui.pushStyleColor(ImGuiCol.HeaderActive, .47f, .84f, 1f, .18f);
        var open = ImGui.collapsingHeader(label, flags);
        ImGui.popStyleColor(3);
        return open;
    }

    public static void dot(int color) {
        dot(color, false);
    }

    public static void dot(int color, boolean framed) {
        var x = ImGui.getCursorScreenPosX();
        var y = HudWidgets.textMiddle(
                ImGui.getCursorScreenPosY() + (framed ? ImGui.getStyle().getFramePaddingY() : 0));
        ImGui.getWindowDrawList().addCircleFilled(x + 4, y, 4, color, 12);
        ImGui.dummy(10, ImGui.getTextLineHeight());
        ImGui.sameLine();
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

    public static Direction compass(String id, int nswe, float size, boolean editable) {
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        ImGui.invisibleButton(id, size, size);
        var draw = ImGui.getWindowDrawList();
        var mouseX = ImGui.getMousePosX();
        var mouseY = ImGui.getMousePosY();
        var inside = ImGui.isItemHovered();
        var cx = x + size * .5f;
        var cy = y + size * .5f;
        var half = size * .26f;
        var bar = Math.max(4, size * .07f);
        draw.addRectFilled(x, y, x + size, y + size, ImColor.rgb(20, 28, 36), 8f);
        draw.addRectFilled(cx - half, cy - half, cx + half, cy + half, ImColor.rgb(36, 50, 60), 3f);
        Direction hovered = null;
        if (editable && inside) {
            var reach = half + bar + size * .1f;
            hovered = sideAt((mouseX - cx) / reach, (mouseY - cy) / reach);
        }
        for (var direction : Direction.values()) {
            var open = (nswe & direction.getMask()) != 0;
            float x1, y1, x2, y2;
            switch (direction) {
                case NORTH -> {
                    x1 = cx - half;
                    x2 = cx + half;
                    y1 = cy - half - bar;
                    y2 = cy - half;
                }
                case SOUTH -> {
                    x1 = cx - half;
                    x2 = cx + half;
                    y1 = cy + half;
                    y2 = cy + half + bar;
                }
                case WEST -> {
                    x1 = cx - half - bar;
                    x2 = cx - half;
                    y1 = cy - half;
                    y2 = cy + half;
                }
                default -> {
                    x1 = cx + half;
                    x2 = cx + half + bar;
                    y1 = cy - half;
                    y2 = cy + half;
                }
            }
            var over = direction == hovered;
            var color = open ? OPEN : BLOCKED;
            draw.addRectFilled(x1, y1, x2, y2, over ? TEXT : color, 2f);
            var labelX = cx
                    + direction.getDx() / 16f * (half + bar + size * .1f)
                    - ImGui.calcTextSizeX(letterOf(direction)) * .5f;
            var labelY = cy + direction.getDy() / 16f * (half + bar + size * .1f) - ImGui.getFontSize() * .5f;
            draw.addText(labelX, labelY, open ? MUTED : color, letterOf(direction));
        }
        if (hovered != null) {
            ImGui.setMouseCursor(ImGuiMouseCursor.Hand);
            HudWidgets.showTooltip((((nswe & hovered.getMask()) != 0) ? "Block " : "Open ")
                    + name(hovered).toLowerCase()
                    + " edge\nShift: change only this cell");
        }
        return hovered != null && ImGui.isItemClicked() ? hovered : null;
    }

    public static Direction sideAt(float dx, float dy) {
        var ax = Math.abs(dx);
        var ay = Math.abs(dy);
        if (Math.max(ax, ay) < SIDE_MIN || Math.abs(ax - ay) < SIDE_MARGIN) {
            return null;
        }
        if (ax > ay) {
            return dx < 0 ? Direction.WEST : Direction.EAST;
        }
        return dy < 0 ? Direction.NORTH : Direction.SOUTH;
    }

    public static String letterOf(Direction direction) {
        return switch (direction) {
            case NORTH -> "N";
            case SOUTH -> "S";
            case WEST -> "W";
            case EAST -> "E";
        };
    }

    public static String name(Direction direction) {
        return switch (direction) {
            case NORTH -> "North";
            case SOUTH -> "South";
            case WEST -> "West";
            case EAST -> "East";
        };
    }

    public static void hintStrip(
            ImDrawList draw, Viewport viewport, String label, List<String[]> items, List<float[]> areas) {
        var capitalized = new ArrayList<String[]>();
        for (var item : items) {
            var action = item[1].isEmpty() ? item[1] : Character.toUpperCase(item[1].charAt(0)) + item[1].substring(1);
            capitalized.add(new String[] {item[0], action});
        }
        items = capitalized;
        var font = ImGui.getFontSize();
        var capHeight = font + 4;
        var barHeight = capHeight + 10;
        var padding = 10f;
        var gap = 14f;
        var labelWidth = ImGui.calcTextSizeX(label);
        var lead = labelWidth + 2 * 9 + 1;
        var left = hintStripLeft(viewport);
        var right = viewport.right() - 12;
        var rows = new ArrayList<ArrayList<String[]>>();
        var widths = new ArrayList<Float>();
        var row = new ArrayList<String[]>();
        var rowWidth = padding + lead;
        for (var item : items) {
            var itemWidth = Math.max(18, ImGui.calcTextSizeX(item[0]) + 10) + 6 + ImGui.calcTextSizeX(item[1]);
            var needed = (row.isEmpty() ? 0 : gap) + itemWidth;
            if (!row.isEmpty() && left + rowWidth + needed + padding > right) {
                rows.add(row);
                widths.add(rowWidth + padding);
                row = new ArrayList<>();
                rowWidth = padding;
                needed = itemWidth;
            }
            row.add(item);
            rowWidth += needed;
        }
        rows.add(row);
        widths.add(rowWidth + padding);
        var top = viewport.top();
        areas.clear();
        for (var index = 0; index < rows.size(); index++) {
            var width = widths.get(index);
            areas.add(new float[] {left, top, left + width, top + barHeight});
            draw.addRectFilled(left, top, left + width, top + barHeight, STRIP_FILL, 8f);
            draw.addRect(left, top, left + width, top + barHeight, STRIP_EDGE, 8f, 0, 1f);
            var textY = top + (barHeight - font) * .5f;
            var capY = top + (barHeight - capHeight) * .5f;
            var x = left + padding;
            if (index == 0) {
                draw.addText(x, textY, GeodataWidgets.ACCENT, label);
                x += labelWidth + 9;
                draw.addLine(x, top + 7, x, top + barHeight - 7, ImColor.rgba(255, 255, 255, 36), 1);
                x += 1 + 9;
            }
            var first = true;
            for (var item : rows.get(index)) {
                if (!first) {
                    x += gap;
                }
                first = false;
                x += GeodataWidgets.keycap(draw, x, capY, item[0]) + 6;
                draw.addText(x, textY, GeodataWidgets.MUTED, item[1]);
                x += ImGui.calcTextSizeX(item[1]);
            }
            top += barHeight + 6;
        }
    }

    public static void keepHintsRightOf(float x) {
        hintsRightOf = x;
    }

    public static float hintStripLeft(Viewport viewport) {
        return Math.max(viewport.left(), hintsRightOf) + 12;
    }

    public static float viewTag(ImDrawList draw, float right, float top, String text, int color) {
        var font = ImGui.getFontSize();
        var height = font + 4 + 10;
        var padding = 10f;
        var left = right - viewTagWidth(text);
        draw.addRectFilled(left, top, right, top + height, STRIP_FILL, 8f);
        draw.addRect(left, top, right, top + height, STRIP_EDGE, 8f, 0, 1f);
        draw.addText(left + padding, top + (height - font) * .5f, color, text);
        return left;
    }

    public static float viewTagWidth(String text) {
        return ImGui.calcTextSizeX(text) + 2 * 10f;
    }

    public static int stepsCard(
            String id, Glyph glyph, String title, List<String> steps, String primary, String secondary, float width) {
        var draw = ImGui.getWindowDrawList();
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var height = stepsCardHeight(steps, width);
        draw.addRectFilled(x, y, x + width, y + height, HudWidgets.BACKGROUND, HudWidgets.PANEL_RADIUS);
        draw.addRect(x, y, x + width, y + height, DIVIDER, HudWidgets.PANEL_RADIUS, 0, 1);
        var text = x + STEPS_PADDING + STEPS_ICON + BLOCK;
        HudWidgets.glyph(draw, glyph, x + STEPS_PADDING, y + STEPS_PADDING, STEPS_ICON, ACCENT);
        draw.addText(text, y + STEPS_PADDING + (STEPS_ICON - ImGui.getFontSize()) * .5f, TEXT, title);
        var line = ImGui.getFontSize() + 4;
        var top = y + STEPS_PADDING + STEPS_ICON + ROW;
        for (var step : steps) {
            for (var part : wrapWords(step, width - (text - x) - STEPS_PADDING)) {
                draw.addText(text, top, MUTED, part);
                top += line;
            }
        }
        var buttonTop = top + BLOCK;
        var primaryWidth = Math.max(80, ImGui.calcTextSizeX(primary) + 28);
        var secondaryWidth = Math.max(80, ImGui.calcTextSizeX(secondary) + 28);
        var right = x + width - STEPS_PADDING;
        ImGui.setCursorScreenPos(right - primaryWidth - ROW - secondaryWidth, buttonTop);
        var result = 0;
        if (HudWidgets.button(
                id + "/secondary", null, secondary, secondaryWidth, HudWidgets.CONTROL_HEIGHT, false, true)) {
            result = 2;
        }
        ImGui.setCursorScreenPos(right - primaryWidth, buttonTop);
        if (HudWidgets.button(id + "/primary", null, primary, primaryWidth, HudWidgets.CONTROL_HEIGHT, true, true)) {
            result = 1;
        }
        ImGui.setCursorScreenPos(x, y + height);
        ImGui.dummy(width, 0);
        return result;
    }

    public static float stepsCardHeight(List<String> steps, float width) {
        var lines = 0;
        var wrap = width - STEPS_PADDING - STEPS_ICON - BLOCK - STEPS_PADDING;
        for (var step : steps) {
            lines += wrapWords(step, wrap).size();
        }
        return STEPS_PADDING
                + STEPS_ICON
                + ROW
                + lines * (ImGui.getFontSize() + 4)
                + BLOCK
                + HudWidgets.CONTROL_HEIGHT
                + STEPS_PADDING;
    }

    public static float hintStripWidth(String label, List<String[]> items) {
        var width = 2 * 10f + ImGui.calcTextSizeX(label) + 2 * 9 + 1;
        for (var index = 0; index < items.size(); index++) {
            var item = items.get(index);
            var action = item[1].isEmpty() ? item[1] : Character.toUpperCase(item[1].charAt(0)) + item[1].substring(1);
            width += (index == 0 ? 0 : 14)
                    + Math.max(18, ImGui.calcTextSizeX(item[0]) + 10)
                    + 6
                    + ImGui.calcTextSizeX(action);
        }
        return width;
    }

    public static float hintStripFirst(String label, List<String[]> items) {
        var width = 2 * 10f + ImGui.calcTextSizeX(label) + 2 * 9 + 1;
        if (!items.isEmpty()) {
            var item = items.getFirst();
            width += Math.max(18, ImGui.calcTextSizeX(item[0]) + 10) + 6 + ImGui.calcTextSizeX(item[1]);
        }
        return width;
    }

    public static boolean[] columns(BooleanSupplier... fields) {
        return columns(ImGui.getContentRegionAvailX(), fields);
    }

    public static boolean[] columns(float total, BooleanSupplier... fields) {
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var spacing = ImGui.getStyle().getItemSpacingX();
        var width = (total - spacing * (fields.length - 1)) / fields.length;
        var result = new boolean[fields.length];
        var bottom = y;
        for (var i = 0; i < fields.length; i++) {
            ImGui.setCursorScreenPos(x + i * (width + spacing), y);
            result[i] = fields[i].getAsBoolean();
            bottom = Math.max(bottom, ImGui.getItemRectMaxY());
        }
        ImGui.setCursorScreenPos(x, bottom);
        ImGui.dummy(0, 0);
        return result;
    }

    public static boolean compactInt(String label, String id, ImInt value, float width, int step) {
        ImGui.beginGroup();
        ImGui.textColored(MUTED, label);
        ImGui.setNextItemWidth(width);
        var changed = ImGui.inputInt(id, value, step, step * 10);
        ImGui.endGroup();
        return changed;
    }

    public static boolean compactDrag(
            String label, String id, int[] value, float width, float speed, int min, int max) {
        ImGui.beginGroup();
        ImGui.textColored(MUTED, label);
        ImGui.setNextItemWidth(width);
        ImGui.dragInt(id, value, speed, min, max, "%d", ImGuiSliderFlags.AlwaysClamp);
        var left = ImGui.isItemDeactivatedAfterEdit();
        ImGui.endGroup();
        return left;
    }

    public static boolean compactDrag(
            String label,
            String id,
            int[] value,
            float width,
            float speed,
            int min,
            int max,
            String format,
            int labelColor) {
        ImGui.beginGroup();
        ImGui.textColored(labelColor, label);
        ImGui.setNextItemWidth(width);
        ImGui.dragInt(id, value, speed, min, max, format, ImGuiSliderFlags.AlwaysClamp);
        var left = ImGui.isItemDeactivated();
        ImGui.endGroup();
        return left;
    }

    public static boolean compactDrag(
            String label,
            String id,
            float[] value,
            float width,
            float speed,
            float min,
            float max,
            String format,
            int labelColor) {
        ImGui.beginGroup();
        ImGui.textColored(labelColor, label);
        ImGui.setNextItemWidth(width);
        ImGui.dragFloat(id, value, speed, min, max, format, ImGuiSliderFlags.AlwaysClamp);
        var left = ImGui.isItemDeactivated();
        ImGui.endGroup();
        return left;
    }

    public static boolean compactFactor(
            String label, String id, float[] value, float width, float min, float max, int labelColor) {
        ImGui.beginGroup();
        ImGui.textColored(labelColor, label);
        ImGui.setNextItemWidth(width);
        ImGui.dragFloat(
                id,
                value,
                factorSpeed(min, max),
                min,
                max,
                "%.2fx",
                ImGuiSliderFlags.Logarithmic | ImGuiSliderFlags.AlwaysClamp | ImGuiSliderFlags.NoRoundToFormat);
        var left = ImGui.isItemDeactivated();
        ImGui.endGroup();
        return left;
    }

    public static boolean compactRange(
            String label, String id, float[] value, float width, float min, float max, String format, int labelColor) {
        ImGui.beginGroup();
        ImGui.textColored(labelColor, label);
        ImGui.setNextItemWidth(width);
        ImGui.dragFloat(
                id,
                value,
                factorSpeed(min, max),
                min,
                max,
                format,
                ImGuiSliderFlags.Logarithmic | ImGuiSliderFlags.NoRoundToFormat);
        var left = ImGui.isItemDeactivated();
        ImGui.endGroup();
        return left;
    }

    static float factorSpeed(float min, float max) {
        return (float) ((max - min) * Math.log1p(FACTOR_STEP) / Math.log((double) max / min));
    }

    public static boolean compactSwatch(String label, String id, float[] rgb, float width, int labelColor) {
        ImGui.beginGroup();
        ImGui.textColored(labelColor, label);
        var clicked = ImGui.colorButton(
                id,
                rgb[0],
                rgb[1],
                rgb[2],
                1,
                ImGuiColorEditFlags.NoAlpha | ImGuiColorEditFlags.NoTooltip | ImGuiColorEditFlags.NoDragDrop,
                width,
                ImGui.getFrameHeight());
        ImGui.endGroup();
        return clicked;
    }

    public static boolean colorPopup(String id, float[] rgb) {
        if (!ImGui.beginPopup(id)) {
            return false;
        }
        ImGui.setNextItemWidth(COLOR_PICKER);
        ImGui.colorPicker3(
                id + "-picker",
                rgb,
                ImGuiColorEditFlags.NoSidePreview
                        | ImGuiColorEditFlags.NoSmallPreview
                        | ImGuiColorEditFlags.NoOptions
                        | ImGuiColorEditFlags.NoAlpha);
        var left = ImGui.isItemDeactivated();
        if (ImGui.isKeyPressed(ImGuiKey.Escape, false)) {
            ImGui.closeCurrentPopup();
        }
        ImGui.endPopup();
        return left;
    }

    public static int swatchRow(String id, float[][] colors, String[] names, int selected) {
        return swatchRow(id, colors, names, selected, ImGui.getContentRegionAvailX());
    }

    public static int swatchRow(String id, float[][] colors, String[] names, int selected, float total) {
        var count = colors.length;
        var gap = 6f;
        var width = Math.max(12, (total - gap * (count - 1)) / count);
        var clicked = -1;
        for (var i = 0; i < count; i++) {
            if (i > 0) {
                ImGui.sameLine(0, gap);
            }
            var color = colors[i];
            if (ImGui.colorButton(
                    id + "/" + i,
                    color[0],
                    color[1],
                    color[2],
                    1,
                    ImGuiColorEditFlags.NoAlpha | ImGuiColorEditFlags.NoTooltip | ImGuiColorEditFlags.NoDragDrop,
                    width,
                    ImGui.getFrameHeight())) {
                clicked = i;
            }
            if (i == selected) {
                ImGui.getWindowDrawList()
                        .addRect(
                                ImGui.getItemRectMinX() - 2,
                                ImGui.getItemRectMinY() - 2,
                                ImGui.getItemRectMaxX() + 2,
                                ImGui.getItemRectMaxY() + 2,
                                ACCENT,
                                6f,
                                0,
                                2);
            }
            tip(names[i]);
        }
        return clicked;
    }

    public static int toolTiles(String id, Glyph[] glyphs, String[] tips, int selected, int split, float width) {
        var count = glyphs.length;
        var gap = 4f;
        var divider = split > 0 && split < count ? 3 * gap : 0;
        var tile = (width - gap * (count - 1) - divider) / count;
        var height = tile < 30 ? 28f : 32f;
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var draw = ImGui.getWindowDrawList();
        var clicked = -1;
        var left = x;
        for (var index = 0; index < count; index++) {
            if (index == split && divider > 0) {
                var line = left - gap + (divider + gap) * .5f;
                draw.addLine(line, y + 4, line, y + height - 4, DIVIDER, 1);
                left += divider;
            }
            ImGui.setCursorScreenPos(left, y);
            if (ImGui.invisibleButton(id + "/" + index, tile, height)) {
                clicked = index;
            }
            var hovered = ImGui.isItemHovered();
            var item = ImGui.getItemID();
            var lit = Motion.toggle(item, index == selected, Motion.QUICK * 1.5f);
            var hover = Motion.toggle(item + 1, hovered, Motion.QUICK);
            draw.addRectFilled(
                    left,
                    y,
                    left + tile,
                    y + height,
                    Motion.mix(Motion.mix(ImColor.rgb(24, 33, 42), HOVER, hover), SELECTED_ROW, lit),
                    6f);
            draw.addRect(left, y, left + tile, y + height, Motion.mix(HudWidgets.BORDER, ACCENT, lit), 6, 0, 1);
            HudWidgets.ripple(draw, item, left, y, tile, height);
            HudWidgets.glyph(
                    draw,
                    glyphs[index],
                    left + (tile - 18) * .5f,
                    y + (height - 18) * .5f,
                    18,
                    Motion.mix(Motion.mix(MUTED, TEXT, hover), ACCENT, lit));
            if (hovered) {
                HudWidgets.showTooltip(tips[index]);
            }
            left += tile + gap;
        }
        ImGui.setCursorScreenPos(x, y + height);
        ImGui.dummy(width, 0);
        return clicked;
    }

    public static int pictureGrid(
            String id,
            long[] textures,
            Glyph fallback,
            boolean[] locked,
            String[] tips,
            int selected,
            float size,
            float width) {
        var count = textures.length;
        var gap = ROW;
        var edge = 2f;
        var columns = Math.max(1, (int) ((width - 2 * edge + gap) / (size + gap)));
        var rows = (count + columns - 1) / columns;
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var draw = ImGui.getWindowDrawList();
        var clicked = -1;
        for (var index = 0; index < count; index++) {
            var left = x + edge + index % columns * (size + gap);
            var top = y + edge + index / columns * (size + gap);
            ImGui.setCursorScreenPos(left, top);
            if (ImGui.invisibleButton(id + "/" + index, size, size) && !locked[index]) {
                clicked = index;
            }
            var hovered = ImGui.isItemHovered();
            var hover = Motion.toggle(ImGui.getItemID(), hovered && !locked[index] && index != selected, Motion.QUICK);
            picture(draw, left, top, size, textures[index], fallback);
            if (locked[index]) {
                draw.addRectFilled(left, top, left + size, top + size, Motion.alpha(HudWidgets.BACKGROUND, .6f), 4f);
                var markLeft = left + size - 16;
                var markTop = top + size - 16;
                draw.addRectFilled(markLeft, markTop, markLeft + 14, markTop + 14, HudWidgets.BACKGROUND, 3f);
                HudWidgets.glyph(draw, Glyph.LOCK, markLeft + 1, markTop + 1, 12, TEXT);
            }
            if (index == selected) {
                draw.addRect(left - edge, top - edge, left + size + edge, top + size + edge, ACCENT, 6, 0, 2);
            } else if (hover > 0) {
                draw.addRect(
                        left - 1, top - 1, left + size + 1, top + size + 1, Motion.alpha(TEXT, hover * .6f), 5, 0, 1);
            }
            if (hovered) {
                HudWidgets.showTooltip(tips[index]);
            }
        }
        ImGui.setCursorScreenPos(x, y + 2 * edge + rows * size + (rows - 1) * gap);
        ImGui.dummy(width, 0);
        return clicked;
    }

    public static boolean compactText(String label, String id, String hint, ImString value, float width) {
        ImGui.beginGroup();
        ImGui.textColored(MUTED, label);
        ImGui.setNextItemWidth(width);
        ImGui.inputTextWithHint(id, hint, value);
        var left = ImGui.isItemDeactivatedAfterEdit();
        ImGui.endGroup();
        return left;
    }

    public static Toggle compactToggle(String label, String id, boolean on, ImFloat value, float width) {
        ImGui.beginGroup();
        ImGui.textColored(on ? MUTED : FAINT, label);
        var box = ImGui.getFrameHeight();
        var toggled = ImGui.checkbox(id + "-on", on);
        ImGui.sameLine(0, 6);
        ImGui.setNextItemWidth(Math.max(24, width - box - 6));
        ImGui.inputFloat(id, value, 0, 0, "%g");
        var left = ImGui.isItemDeactivatedAfterEdit();
        ImGui.endGroup();
        return new Toggle(toggled, left);
    }

    public static boolean compactDecimal(String label, String id, ImFloat value, String unit, float width) {
        ImGui.beginGroup();
        ImGui.textColored(MUTED, label);
        ImGui.setNextItemWidth(width);
        ImGui.inputFloat(id, value, 0, 0, "%g" + unit.replace("%", "%%"));
        var left = ImGui.isItemDeactivatedAfterEdit();
        ImGui.endGroup();
        return left;
    }

    public static boolean axisField(String axis, String id, ImString value, float width) {
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var style = ImGui.getStyle();
        var padX = style.getFramePaddingX();
        var padY = style.getFramePaddingY();
        ImGui.pushStyleVar(ImGuiStyleVar.FramePadding, padX + ImGui.calcTextSizeX(axis) + 5, padY);
        ImGui.setNextItemWidth(width);
        ImGui.inputText(id, value);
        ImGui.popStyleVar();
        var left = ImGui.isItemDeactivatedAfterEdit();
        ImGui.getWindowDrawList().addText(x + padX, y + padY, Motion.alpha(FAINT, style.getAlpha()), axis);
        return left;
    }

    public static void rowLabel(String label, float width) {
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        ImGui.alignTextToFramePadding();
        ImGui.textColored(MUTED, label);
        ImGui.sameLine(0, 0);
        ImGui.setCursorScreenPos(x + width, y);
    }

    public record Toggle(boolean toggled, boolean left) {}

    public static @Nullable String searchList(String id, String preview, ImString filter, String hint, float width) {
        return searchList(id, preview, TEXT, filter, hint, width);
    }

    public static @Nullable String searchList(
            String id, String preview, int previewColor, ImString filter, String hint, float width) {
        ImGui.setNextItemWidth(width);
        var fieldWidth = width > 0 ? width : ImGui.getContentRegionAvailX();
        var rows = (ImGui.getFontSize() + ImGui.getStyle().getItemSpacingY()) * 20;
        ImGui.setNextWindowSizeConstraints(
                Math.max(fieldWidth, SEARCH_LIST_WIDTH),
                0,
                Float.MAX_VALUE,
                ImGui.getFrameHeightWithSpacing() + rows + 12);
        ImGui.pushStyleColor(ImGuiCol.Text, previewColor);
        var open = HudWidgets.beginCombo(id, preview, ImGuiComboFlags.HeightLarge);
        ImGui.popStyleColor();
        if (!open) {
            return null;
        }
        if (ImGui.isKeyPressed(ImGuiKey.Escape, false)) {
            ImGui.closeCurrentPopup();
        }
        if (ImGui.isWindowAppearing()) {
            filter.set("");
            ImGui.setKeyboardFocusHere();
        }
        ImGui.setNextItemWidth(-1);
        ImGui.pushStyleColor(ImGuiCol.NavCursor, 0);
        ImGui.inputTextWithHint(id + "-filter", hint, filter);
        ImGui.popStyleColor();
        chooseFirst = ImGui.isItemDeactivated()
                && (ImGui.isKeyPressed(ImGuiKey.Enter) || ImGui.isKeyPressed(ImGuiKey.KeypadEnter));
        return filter.get().strip().toLowerCase(Locale.ROOT);
    }

    public static void openSearchList(String id) {
        ImGui.pushID(id);
        ImGui.openPopup("##ComboPopup");
        ImGui.popID();
    }

    public static void picture(ImDrawList draw, float x, float y, float size, long texture, Glyph fallback) {
        NpcPanelWidgets.icon(draw, x, y, size, texture, fallback);
    }

    public record Picture(long texture, float u0, float v0, float u1, float v1, Glyph fallback) {
        public static Picture of(long texture, Glyph fallback) {
            return new Picture(texture, 0, 0, 1, 1, fallback);
        }
    }

    public static void picture(ImDrawList draw, float x, float y, float size, Picture picture, boolean faint) {
        NpcPanelWidgets.icon(
                draw,
                x,
                y,
                size,
                picture.texture(),
                picture.u0(),
                picture.v0(),
                picture.u1(),
                picture.v1(),
                faint ? ImColor.rgba(255, 255, 255, 110) : 0xFFFFFFFF,
                picture.fallback());
    }

    public enum LineLook {
        PLAIN,
        SELECTED,
        MARKED,
        FAINT,
        CURRENT
    }

    public static boolean lineRow(
            String id, @Nullable Glyph glyph, String label, String note, LineLook look, boolean chevron) {
        return lineRow(id, glyph, label, note, look, chevron, 0);
    }

    public static boolean lineRow(
            String id,
            @Nullable Glyph glyph,
            String label,
            String note,
            LineLook look,
            boolean chevron,
            float reserve) {
        var draw = ImGui.getWindowDrawList();
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var width = ImGui.getContentRegionAvailX();
        var faint = look == LineLook.FAINT;
        var clicked = ImGui.invisibleButton(id, width, LINE_ROW) && !faint;
        var hovered = !faint && ImGui.isItemHovered();
        var lit = Motion.toggle(ImGui.getItemID(), hovered, Motion.QUICK);
        if (look == LineLook.SELECTED) {
            draw.addRectFilled(x, y, x + width, y + LINE_ROW, Motion.mix(SELECTED_ROW, HOVER, lit * .5f), 6f);
            draw.addRect(x, y, x + width, y + LINE_ROW, ACCENT, 6, 0, 1);
        } else if (look == LineLook.CURRENT) {
            draw.addRectFilled(x, y, x + width, y + LINE_ROW, Motion.mix(CARD, HOVER, lit), 6f);
            var barTop = y + (LINE_ROW - CURRENT_BAR_HEIGHT) * .5f;
            draw.addRectFilled(x, barTop, x + CURRENT_BAR, barTop + CURRENT_BAR_HEIGHT, ACCENT, CURRENT_BAR * .5f);
        } else if (look == LineLook.MARKED) {
            draw.addRectFilled(x, y, x + width, y + LINE_ROW, HOVER, 6f);
        } else if (lit > 0) {
            draw.addRectFilled(x, y, x + width, y + LINE_ROW, Motion.alpha(HOVER, lit), 6f);
        }
        var textTop = y + (LINE_ROW - ImGui.getFontSize()) * .5f - 1;
        var left = x + LINE_INSET;
        if (glyph != null) {
            HudWidgets.glyph(
                    draw,
                    glyph,
                    x + LINE_INSET,
                    y + (LINE_ROW - 18) * .5f,
                    18,
                    faint ? FAINT : look == LineLook.CURRENT ? TEXT : Motion.mix(MUTED, TEXT, lit));
            left = x + LINE_INSET + 18 + ROW;
        }
        var right = x + width - LINE_INSET - reserve;
        if (chevron) {
            HudWidgets.glyph(draw, Glyph.CHEVRON_RIGHT, right - 16, y + (LINE_ROW - 16) * .5f, 16, MUTED);
            right -= 16 + ROW;
        }
        if (look == LineLook.MARKED) {
            keycap(draw, right - KEYCAP_ICON, y + (LINE_ROW - ImGui.getFontSize() - 4) * .5f, Glyph.ENTER);
            right -= KEYCAP_ICON + ROW;
        }
        var shown = HudWidgets.truncateToWidth(label, right - left);
        draw.addText(left, textTop, faint ? FAINT : TEXT, shown);
        var noteWidth = ImGui.calcTextSizeX(note);
        if (!note.isEmpty() && shown.equals(label) && left + ImGui.calcTextSizeX(label) + ROW + noteWidth <= right) {
            draw.addText(right - noteWidth, textTop, FAINT, note);
        }
        return clicked;
    }

    public static boolean thumbnailRow(
            String id, long texture, Glyph fallback, String title, String detail, float size, boolean selected) {
        return thumbnailRow(id, texture, fallback, title, detail, size, selected, ImGui.getContentRegionAvailX());
    }

    public static boolean thumbnailRow(
            String id,
            long texture,
            Glyph fallback,
            String title,
            String detail,
            float size,
            boolean selected,
            float width) {
        return thumbnailRow(id, Picture.of(texture, fallback), title, detail, size, selected, false, width);
    }

    public static boolean thumbnailRow(
            String id,
            Picture picture,
            String title,
            String detail,
            float size,
            boolean selected,
            boolean faint,
            float width) {
        return thumbnailRow(id, picture, title, detail, size, selected, faint, width, 4);
    }

    public static boolean thumbnailRow(
            String id,
            Picture picture,
            String title,
            String detail,
            float size,
            boolean selected,
            boolean faint,
            float width,
            float inset) {
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var height = Math.max(size + 8, 2 * ImGui.getFontSize() + 12);
        var clicked = ImGui.invisibleButton(id, width, height) && !faint;
        var hovered = !faint && ImGui.isItemHovered();
        var draw = ImGui.getWindowDrawList();
        if (selected || hovered) {
            draw.addRectFilled(x, y, x + width, y + height, selected ? SELECTED_ROW : HOVER, 6f);
        }
        if (selected) {
            draw.addRect(x, y, x + width, y + height, ACCENT, 6, 0, 1);
        }
        picture(draw, x + inset, y + (height - size) * .5f, size, picture, faint);
        var textLeft = x + size + inset + ROW + 2;
        var textWidth = x + width - 8 - textLeft;
        var lineGap = (height - 2 * ImGui.getFontSize()) / 3;
        draw.addText(textLeft, y + lineGap, faint ? FAINT : TEXT, HudWidgets.truncateToWidth(title, textWidth));
        draw.addText(
                textLeft,
                y + 2 * lineGap + ImGui.getFontSize(),
                faint ? FAINT : MUTED,
                HudWidgets.truncateToWidth(detail, textWidth));
        ImGui.setCursorScreenPos(x, y + height);
        ImGui.dummy(width, 0);
        return clicked;
    }

    public static boolean thumbnailLine(
            String id,
            long texture,
            Glyph fallback,
            String title,
            String detail,
            float size,
            boolean selected,
            float width) {
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var height = size + 8;
        var clicked = ImGui.invisibleButton(id, width, height);
        var hovered = ImGui.isItemHovered();
        var draw = ImGui.getWindowDrawList();
        if (selected || hovered) {
            draw.addRectFilled(x, y, x + width, y + height, selected ? SELECTED_ROW : HOVER, 6f);
        }
        if (selected) {
            draw.addRect(x, y, x + width, y + height, ACCENT, 6, 0, 1);
        }
        picture(draw, x + 4, y + 4, size, texture, fallback);
        var textLeft = x + size + 4 + ROW + 2;
        var room = x + width - 8 - textLeft;
        var textTop = y + (height - ImGui.getFontSize()) * .5f - 1;
        var shownTitle = HudWidgets.truncateToWidth(title, room);
        draw.addText(textLeft, textTop, TEXT, shownTitle);
        var titleWidth = ImGui.calcTextSizeX(shownTitle);
        var detailRoom = room - titleWidth - ROW;
        if (!detail.isEmpty() && shownTitle.equals(title) && detailRoom > ImGui.calcTextSizeX("ab…")) {
            draw.addText(textLeft + titleWidth + ROW, textTop, MUTED, HudWidgets.truncateToWidth(detail, detailRoom));
        }
        ImGui.setCursorScreenPos(x, y + height);
        ImGui.dummy(width, 0);
        return clicked;
    }

    public static boolean pictureItem(
            String id, long texture, Glyph fallback, String title, String detail, float size) {
        var chosen = thumbnailRow(id, texture, fallback, title, detail, size, false) || chooseFirst;
        if (chosen) {
            chooseFirst = false;
            ImGui.closeCurrentPopup();
        }
        return chosen;
    }

    public static int screenPlaces(String id, float[][] places, String[] names, int chosen, float width) {
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var height = Math.round(width * SCREEN_SHAPE);
        var pressed = ImGui.invisibleButton(id, width, height);
        var hovered = ImGui.isItemHovered();
        var draw = ImGui.getWindowDrawList();
        draw.addRectFilled(x, y, x + width, y + height, CARD, 6f);
        draw.addRect(x, y, x + width, y + height, HudWidgets.BORDER, 6f);
        var under = -1;
        for (var index = 0; index < places.length; index++) {
            var place = places[index];
            var left = x + place[0] * width;
            var top = y + place[1] * height;
            var right = left + place[2] * width;
            var bottom = top + place[3] * height;
            var inside = hovered
                    && ImGui.getMousePosX() >= left
                    && ImGui.getMousePosX() <= right
                    && ImGui.getMousePosY() >= top
                    && ImGui.getMousePosY() <= bottom;
            under = inside ? index : under;
            draw.addRectFilled(
                    left,
                    top,
                    right,
                    bottom,
                    index == chosen ? ACCENT : inside ? HudWidgets.MUTED : HudWidgets.BORDER,
                    2f);
        }
        if (under >= 0) {
            tip(names[under]);
        }
        ImGui.setCursorScreenPos(x, y + height);
        ImGui.dummy(width, 0);
        return pressed ? under : -1;
    }

    public static boolean listItem(String id, String label, String note, boolean selected) {
        return listItem(id, label, note, selected, false);
    }

    public static boolean listItem(String id, String label, String note, boolean selected, boolean faint) {
        ImGui.pushID(id);
        if (faint) {
            ImGui.pushStyleColor(ImGuiCol.Text, FAINT);
        }
        boolean chosen;
        if (note.isEmpty()) {
            chosen = ImGui.selectable(label, selected) || chooseFirst;
            if (faint) {
                ImGui.popStyleColor();
            }
        } else {
            var cut = label.indexOf("##");
            var text = cut >= 0 ? label.substring(0, cut) : label;
            var x = ImGui.getCursorScreenPosX();
            var y = ImGui.getCursorScreenPosY();
            var noteWidth = ImGui.calcTextSizeX(note);
            var air = ImGui.getScrollMaxY() > 0 ? ROW : 2;
            var right = x + ImGui.getContentRegionAvailX();
            var shown = truncateToWidth(text, ImGui.getContentRegionAvailX() - noteWidth - air - ROW);
            chosen = ImGui.selectable("##row", selected) || chooseFirst;
            var draw = ImGui.getWindowDrawList();
            draw.addText(x, y, ImGui.getColorU32(ImGuiCol.Text), shown);
            draw.addText(
                    Math.min(ImGui.getItemRectMaxX(), right) - noteWidth - air,
                    ImGui.getItemRectMinY(),
                    faint ? FAINT : MUTED,
                    note);
            if (faint) {
                ImGui.popStyleColor();
            }
            if (!shown.equals(text) && ImGui.isItemHovered()) {
                HudWidgets.showTooltip(text.strip());
            }
        }
        ImGui.popID();
        if (chooseFirst) {
            chooseFirst = false;
            ImGui.closeCurrentPopup();
        }
        return chosen;
    }

    private static float miniKeyHeight() {
        return ImGui.getFontSize() + 3;
    }

    private static float smoothFraction(String id, float fraction) {
        var key = ImGui.getID(id + "/bar");
        if (fraction < Motion.peek(key, fraction)) {
            Motion.jump(key, fraction);
        }
        return Motion.follow(key, fraction, .35f);
    }

    private static Field drawActionField(
            String id,
            String hint,
            ImString value,
            String fieldTip,
            Glyph glyph,
            String actionTip,
            boolean enabled,
            float width,
            boolean hideText,
            int glyphColor) {
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var height = ImGui.getFrameHeight();
        var keyHeight = height - 6;
        var keyWidth = keyHeight + 8;
        var left = x + width - keyWidth - 3;
        var top = y + 3;
        var draw = ImGui.getWindowDrawList();
        draw.channelsSplit(2);
        draw.channelsSetCurrent(1);
        ImGui.pushStyleColor(ImGuiCol.FrameBg, 0);
        ImGui.pushStyleColor(ImGuiCol.FrameBgHovered, 0);
        ImGui.pushStyleColor(ImGuiCol.FrameBgActive, 0);
        ImGui.pushStyleVar(ImGuiStyleVar.FrameBorderSize, 0);
        ImGui.setNextItemWidth(left - x);
        if (hideText) {
            ImGui.pushStyleColor(ImGuiCol.Text, 0);
        }
        ImGui.inputTextWithHint("##" + id, hint, value);
        if (hideText) {
            ImGui.popStyleColor();
        }
        ImGui.popStyleVar();
        ImGui.popStyleColor(3);
        var entered = ImGui.isItemDeactivated()
                && (ImGui.isKeyPressed(ImGuiKey.Enter) || ImGui.isKeyPressed(ImGuiKey.KeypadEnter));
        var editing = ImGui.isItemActive();
        var fieldHovered = ImGui.isItemHovered();
        tip(fieldTip);
        var after = ImGui.getCursorScreenPosY();
        ImGui.setCursorScreenPos(left, top);
        var pressed = ImGui.invisibleButton("##" + id + "-action", keyWidth, keyHeight);
        var hovered = ImGui.isItemHovered();
        if (hovered && enabled) {
            ImGui.setMouseCursor(ImGuiMouseCursor.Hand);
        }
        draw.channelsSetCurrent(0);
        var style = ImGui.getStyle();
        var fill = ImGui.getColorU32(
                editing
                        ? ImGuiCol.FrameBgActive
                        : fieldHovered || hovered ? ImGuiCol.FrameBgHovered : ImGuiCol.FrameBg);
        draw.addRectFilled(x, y, x + width, y + height, fill, style.getFrameRounding());
        if (style.getFrameBorderSize() > 0) {
            draw.addRect(
                    x,
                    y,
                    x + width,
                    y + height,
                    ImGui.getColorU32(ImGuiCol.Border),
                    style.getFrameRounding(),
                    0,
                    style.getFrameBorderSize());
        }
        draw.channelsMerge();
        var glyphSize = keyHeight + 2;
        HudWidgets.glyph(
                ImGui.getWindowDrawList(),
                glyph,
                left + (keyWidth - glyphSize) * .5f,
                top + (keyHeight - glyphSize) * .5f,
                glyphSize,
                !enabled ? FAINT : hovered ? TEXT : glyphColor);
        tip(actionTip, "Enter");
        ImGui.setCursorScreenPos(x, after);
        ImGui.dummy(0, 0);
        return new Field(enabled && (pressed || entered), editing);
    }

    private static int drawFooter(
            String status, String shortStatus, String detail, int color, String statusTip, List<FooterButton> buttons) {
        var draw = ImGui.getWindowDrawList();
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var width = ImGui.getContentRegionAvailX();
        var buttonsWidth = 0f;
        for (var button : buttons) {
            buttonsWidth += button.width() + ROW;
        }
        var lines = detail.isEmpty() ? ImGui.getFontSize() : 2 * ImGui.getFontSize() + SETTING_LINE;
        var textTop = y + (FOOTER - lines) * .5f - 1;
        var left = x;
        if (color != MUTED && !status.isEmpty()) {
            draw.addCircleFilled(x + 4, HudWidgets.textMiddle(textTop), 4, color, 12);
            left = x + 16;
        }
        var room = x + width - buttonsWidth - BLOCK - left;
        var shown = ImGui.calcTextSizeX(status) <= room ? status : truncateToWidth(shortStatus, room);
        draw.addText(left, textTop, color, shown);
        if (!detail.isEmpty()) {
            draw.addText(left, textTop + ImGui.getFontSize() + SETTING_LINE, MUTED, truncateToWidth(detail, room));
        }
        var cut = !shown.equals(status);
        if ((cut || !statusTip.isEmpty()) && ImGui.isMouseHoveringRect(x, y, left + room, y + FOOTER)) {
            HudWidgets.showTooltip(
                    cut && !statusTip.isEmpty() ? status + "\n\n" + statusTip : cut ? status : statusTip);
        }
        var pressed = -1;
        var buttonX = x + width - buttonsWidth + ROW;
        for (var index = 0; index < buttons.size(); index++) {
            var button = buttons.get(index);
            ImGui.setCursorScreenPos(buttonX, y + (FOOTER - 32) * .5f);
            ImGui.beginDisabled(!button.enabled());
            if (HudWidgets.button(
                    button.id(), button.glyph(), button.label(), button.width(), 32, button.primary(), true)) {
                pressed = index;
            }
            ImGui.endDisabled();
            if (!button.tip().isEmpty()) {
                tip(button.tip());
            }
            buttonX += button.width() + ROW;
        }
        ImGui.setCursorScreenPos(x, y + FOOTER);
        ImGui.dummy(width, 0);
        return pressed;
    }

    private static int drawSegments(String id, String[] labels, int selected, float[] widths) {
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var height = 26f;
        var width = 0f;
        var lefts = new float[labels.length];
        for (var index = 0; index < labels.length; index++) {
            lefts[index] = width;
            width += widths[index];
        }
        var draw = ImGui.getWindowDrawList();
        draw.addRectFilled(x, y, x + width, y + height, ImGui.getColorU32i(ImColor.rgb(20, 28, 36)), 6f);
        draw.addRect(x, y, x + width, y + height, ImGui.getColorU32i(HudWidgets.BORDER), 6, 0, 1);
        var clicked = -1;
        if (selected >= 0 && selected < labels.length) {
            var left = x + Motion.spring(id + "/pill", lefts[selected]);
            var right = left + Motion.spring(id + "/pill-width", widths[selected]);
            draw.addRectFilled(
                    left + 2, y + 2, right - 2, y + height - 2, ImGui.getColorU32i(ImColor.rgb(36, 66, 80)), 5f);
            draw.addRect(left + 2, y + 2, right - 2, y + height - 2, ImGui.getColorU32i(ACCENT), 5, 0, 1);
        }
        for (var index = 0; index < labels.length; index++) {
            var left = x + lefts[index];
            var segment = widths[index];
            ImGui.setCursorScreenPos(left, y);
            if (ImGui.invisibleButton(id + "/" + index, segment, height)) {
                clicked = index;
            }
            var hovered = ImGui.isItemHovered();
            var item = ImGui.getItemID();
            var hover = Motion.toggle(item, hovered && index != selected, Motion.QUICK);
            var lit = Motion.toggle(item + 1, index == selected, Motion.QUICK * 1.5f);
            if (hover > 0) {
                draw.addRectFilled(
                        left + 2,
                        y + 2,
                        left + segment - 2,
                        y + height - 2,
                        ImGui.getColorU32i(Motion.alpha(ImColor.rgb(34, 51, 63), hover * .8f)),
                        5f);
            }
            var label = labels[index];
            var textWidth = ImGui.calcTextSizeX(label);
            draw.addText(
                    left + (segment - textWidth) * .5f,
                    y + (height - ImGui.getFontSize()) * .5f - 1,
                    ImGui.getColorU32i(Motion.mix(MUTED, TEXT, lit)),
                    label);
        }
        ImGui.setCursorScreenPos(x, y);
        ImGui.dummy(width, height);
        return clicked;
    }

    private static void wrapped(String text, int color, float wrapAt) {
        ImGui.pushTextWrapPos(wrapAt);
        ImGui.pushStyleColor(ImGuiCol.Text, color);
        ImGui.textUnformatted(text);
        ImGui.popStyleColor();
        ImGui.popTextWrapPos();
    }

    private static List<String> wrapWords(String text, float width) {
        var rows = new ArrayList<String>();
        var row = new StringBuilder();
        for (var word : text.split(" ")) {
            var next = row.isEmpty() ? word : row + " " + word;
            if (!row.isEmpty() && ImGui.calcTextSizeX(next) > width) {
                rows.add(row.toString());
                row.setLength(0);
                row.append(word);
            } else {
                row.setLength(0);
                row.append(next);
            }
        }
        if (!row.isEmpty()) {
            rows.add(row.toString());
        }
        return rows;
    }

    private static float drawSettingLabel(
            float x, float y, float width, String label, String detail, boolean centered) {
        return layoutSettingLabel(x, y, width, label, detail, centered, true);
    }

    private static float measureSettingLabel(float y, float width, String label, String detail, boolean centered) {
        return layoutSettingLabel(0, y, width, label, detail, centered, false);
    }

    private static float layoutSettingLabel(
            float x, float y, float width, String label, String detail, boolean centered, boolean drawn) {
        var draw = drawn ? ImGui.getWindowDrawList() : null;
        var size = ImGui.getFontSize();
        var top = centered ? y + (HudWidgets.CONTROL_HEIGHT - size) * .5f - 1 : y;
        var bottom = top;
        for (var line : wrapWords(label, width)) {
            if (draw != null) {
                draw.addText(x, top, ImGui.getColorU32i(TEXT), line);
            }
            bottom = top + size;
            top += size + SETTING_LINE;
        }
        if (!detail.isEmpty()) {
            top = bottom + SETTING_DETAIL;
            for (var line : wrapWords(detail, width)) {
                if (draw != null) {
                    draw.addText(x, top, ImGui.getColorU32i(FAINT), line);
                }
                bottom = top + size;
                top += size + SETTING_LINE;
            }
        }
        return bottom;
    }

    private static void drawFolderName(ImDrawList draw, float x, float y, String typed, float room) {
        Path folder = null;
        try {
            folder = Path.of(typed.strip());
        } catch (InvalidPathException invalid) {
        }
        var name = folder == null || folder.getFileName() == null
                ? typed.strip()
                : folder.getFileName().toString();
        var parent = folder == null || folder.getParent() == null
                ? ""
                : homeShort(folder.getParent().toString());
        var shownName = truncateToWidth(name, room);
        draw.addText(x, y, ImGui.getColorU32i(TEXT), shownName);
        var nameWidth = ImGui.calcTextSizeX(shownName);
        var rest = room - nameWidth - ROW;
        if (!parent.isEmpty() && shownName.equals(name) && rest >= FOLDER_PARENT_LEAST) {
            draw.addText(x + nameWidth + ROW, y, ImGui.getColorU32i(FAINT), cutLeft(parent, rest));
        }
    }

    private static String homeShort(String path) {
        var home = System.getProperty("user.home");
        if (home != null && !home.isEmpty() && (path.equals(home) || path.startsWith(home + File.separator))) {
            return "~" + path.substring(home.length());
        }
        return path;
    }

    private static String cutLeft(String text, float width) {
        if (ImGui.calcTextSizeX(text) <= width) {
            return text;
        }
        var start = 0;
        while (start < text.length() && ImGui.calcTextSizeX("…" + text.substring(start)) > width) {
            start++;
        }
        return "…" + text.substring(start);
    }
}
