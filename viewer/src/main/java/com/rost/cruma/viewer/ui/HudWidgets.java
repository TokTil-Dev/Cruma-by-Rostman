package com.rost.cruma.viewer.ui;

import com.rost.cruma.viewer.guide.GuideLibrary;
import com.rost.cruma.viewer.guide.GuideRequests;
import imgui.ImColor;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiHoveredFlags;
import imgui.flag.ImGuiMouseCursor;
import imgui.flag.ImGuiSliderFlags;
import imgui.flag.ImGuiStyleVar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

public final class HudWidgets {
    public static final float BAR_HEIGHT = 40f;
    public static final float CONTROL_HEIGHT = 28f;

    public static final float ROUND_LARGE = 44;

    public static final float ROUND_SMALL = 34;

    public static final float PANEL_RADIUS = 9f;
    public static final float TEXT_SIZE = 14f;
    public static final int TEXT = ImColor.rgb(231, 238, 246);
    public static final int MUTED = ImColor.rgb(164, 180, 195);
    public static final int ACCENT = ImColor.rgb(92, 219, 241);
    public static final int BORDER = ImColor.rgb(66, 84, 99);
    public static final int BACKGROUND = ImColor.rgb(23, 31, 39);

    public static final float MENU_ROW = 32f;

    public static final float MENU_PADDING = 6f;

    public static final float TOAST_HEIGHT = 40f;

    public static final int TOAST_CLICKED = 1;

    public static final int TOAST_ACTION = 2;

    public static final int PILL_ON = ImColor.rgba(120, 215, 255, 92);

    public static final int PILL_OFF = ImColor.rgba(255, 255, 255, 28);

    public static final int ROW_HOVER = ImColor.rgba(120, 215, 255, 18);

    public static final int RANGE_LOW = 1;

    public static final int RANGE_HIGH = 2;

    public static final float RANGE_FINE = .1f;

    public static final int DIAL_YAW = 1;

    public static final int DIAL_PITCH = 2;

    private static final int PILL_DISABLED = ImColor.rgba(255, 255, 255, 12);
    private static final int KNOB_OFF = ImColor.rgb(120, 134, 144);
    private static final int KNOB_DISABLED = ImColor.rgb(74, 84, 92);
    private static final int TEXT_DISABLED = ImColor.rgba(150, 165, 176, 130);

    private static final int HOVER = ImColor.rgb(34, 51, 63);
    private static final int PRESSED = ImColor.rgb(44, 70, 86);
    private static final int ACCENT_FILL = ImColor.rgb(30, 51, 61);
    private static final int OUTLINED_FILL = ImColor.rgb(27, 37, 46);

    private static int rippleId;
    private static float rippleX;
    private static float rippleY;

    private static boolean inMenu;
    private static boolean menuHovered;
    private static float menuHoverTop;
    private static float menuHoverLeft;
    private static float menuHoverWidth;
    private static final float RANGE_HEIGHT = 22f;
    private static final float RANGE_KNOB = 5f;
    private static final float RANGE_SNAP = 2f;
    private static final float[] NO_TICKS = {};

    private static int rangeKnob;
    private static float rangeGrab;
    private static int tooltipOwner;
    private static final Map<String, float[]> HELP_MARKS = new HashMap<>();

    private static final float DIAL_HEIGHT = 112;

    private HudWidgets() {}

    public enum Glyph {
        SEARCH("search", 0xe151),
        PLACE("map-pin", 0xe111),
        GEODATA("grid-3x3", 0xe0e9),
        PROFILE("chart-column", 0xe2a3),
        SETTINGS("settings", 0xe154),
        SLIDERS("sliders-horizontal", 0xe29a),
        MENU("menu", 0xe115),
        CLOSE("x", 0xe1b2),
        PIN("pin", 0xe259),
        FOCUS("locate-fixed", 0xe1db),
        EYE("eye", 0xe0ba),
        COPY("copy", 0xe09e),
        BOX("box", 0xe061),
        PLAY("play", 0xe13c),
        PAUSE("pause", 0xe12e),
        STEP("skip-forward", 0xe160),
        CHEVRON("chevron-down", 0xe06d),
        CHEVRON_UP("chevron-up", 0xe070),
        CHEVRON_RIGHT("chevron-right", 0xe06f),
        CHEVRON_LEFT("chevron-left", 0xe06e),
        HELP("circle-help", 0xe082),
        ARROW("navigation", 0xe123),
        FILTER("funnel", 0xe0dc),
        FILE("file-text", 0xe0cc),
        FILE_CODE("file-code", 0xe0c3),
        CAMERA("camera", 0xe064),
        LOGO(null, 0),
        CASTLE("castle", 0xe3e0),
        GLOBE("globe", 0xe0e8),
        SUN("sun", 0xe178),
        CLOUD("cloud", 0xe088),
        SPARKLES("sparkles", 0xe412),
        WAVES("waves", 0xe283),
        AUDIO("volume-2", 0xe1ab),
        MINUS("minus", 0xe11c),
        PLUS("plus", 0xe13d),
        CURSOR("mouse-pointer-2", 0xe1c3),
        ROUTE("route", 0xe53e),
        PENCIL("pencil", 0xe1f9),
        SHIELD("shield", 0xe158),
        UNDO("undo-2", 0xe2a1),
        REDO("redo-2", 0xe2a0),
        FOLDER("folder", 0xe0d7),
        RELOAD("rotate-cw", 0xe149),
        XRAY("scan-eye", 0xe536),
        NPC("circle-user-round", 0xe462),
        AREA("land-plot", 0xe528),
        BOOKMARK("bookmark", 0xe060),
        GO("arrow-right", 0xe049),
        CARD("user-round", 0xe468),
        LAYERS("layers", 0xe529),
        KEYBOARD("keyboard", 0xe284),
        TERRAIN("mountain", 0xe231),
        SPROUT("sprout", 0xe1eb),
        LOCK("lock", 0xe10b),
        EYE_OFF("eye-off", 0xe0bb),
        EYEDROPPER("pipette", 0xe13b),
        SWITCH("toggle-right", 0xe18c),
        STEP_BACK("skip-back", 0xe15f),
        REPEAT("repeat", 0xe146),
        CLAPPERBOARD("clapperboard", 0xe29b),
        STOP("square", 0xe167),
        ZOOM("zoom-in", 0xe1b6),
        ZOOM_OUT("zoom-out", 0xe1b7),
        ORBIT("orbit", 0xe3e7),
        SLICE("scissors", 0xe14e),
        RULER("ruler", 0xe14b),
        BOT("bot", 0xe1bb),
        CHECK("check", 0xe06c),
        MORE("ellipsis", 0xe0b6),
        TRASH("trash-2", 0xe18d),
        DROP("arrow-down-to-line", 0xe455),
        MIRROR("flip-horizontal-2", 0xe35e),
        DICE("dices", 0xe2c5),
        EQUAL("equal", 0xe1bd),
        SLOPE("trending-up", 0xe191),
        SHUFFLE("shuffle", 0xe15e),
        LEVEL("align-vertical-justify-center", 0xe277),
        LASSO("lasso", 0xe1ce),
        CIRCLE_OFF("circle-off", 0xe401),
        TRIANGLE("triangle", 0xe192),
        GROUP("group", 0xe464),
        UNGROUP("ungroup", 0xe467),
        ALIGN_LEFT("align-start-vertical", 0xe271),
        ALIGN_CENTER_X("align-center-vertical", 0xe26d),
        ALIGN_RIGHT("align-end-vertical", 0xe26f),
        ALIGN_FRONT("align-start-horizontal", 0xe270),
        ALIGN_CENTER_Y("align-center-horizontal", 0xe26c),
        ALIGN_BACK("align-end-horizontal", 0xe26e),
        SAME_HEIGHT("align-vertical-justify-end", 0xe278),
        DISTRIBUTE_X("align-horizontal-distribute-center", 0xe03c),
        DISTRIBUTE_Y("align-vertical-distribute-center", 0xe27e),
        STAR("star", 0xe176),
        BOOK_OPEN("book-open", 0xe05f),
        BACK("arrow-left", 0xe048),
        HOME("house", 0xe0f5),
        ALERT("triangle-alert", 0xe193),
        WRENCH("wrench", 0xe1b1),
        ENTER("corner-down-left", 0xe0a1),
        COMPASS("compass", 0xe09b),
        TABLE("table", 0xe17d);

        private final @Nullable String lucide;
        private final String text;

        Glyph(@Nullable String lucide, int code) {
            this.lucide = lucide;
            this.text = code == 0 ? "" : Character.toString(code);
        }

        public @Nullable String getLucide() {
            return lucide;
        }

        String getText() {
            return text;
        }

        public int getCode() {
            return text.isEmpty() ? 0 : text.codePointAt(0);
        }
    }

    public static boolean button(
            String id, Glyph glyph, String label, float width, float height, boolean accent, boolean outlined) {
        return button(id, glyph, label, width, height, accent, outlined, false);
    }

    public static boolean keyButton(
            String id,
            Glyph glyph,
            String label,
            Glyph key,
            float width,
            float height,
            boolean accent,
            boolean outlined) {
        return button(id, glyph, label, width, height, accent, outlined, false, Math.min(18f, height - 10f), TEXT, key);
    }

    public static boolean listButton(String id, Glyph glyph, String label, float width, float height, boolean accent) {
        return button(id, glyph, label, width, height, accent, true, true);
    }

    public static boolean iconButton(String id, Glyph glyph, boolean on, int tint) {
        return button(id, glyph, "", CONTROL_HEIGHT, CONTROL_HEIGHT, on, false, false, 18f, tint);
    }

    public static boolean roundButton(String id, Glyph glyph, boolean large, String tip) {
        var size = large ? ROUND_LARGE : ROUND_SMALL;
        var icon = large ? 20f : 16f;
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var pressed = ImGui.invisibleButton("##" + id, size, size);
        var hovered = ImGui.isItemHovered();
        var draw = ImGui.getWindowDrawList();
        float middleX = x + size * .5f, middleY = y + size * .5f;
        draw.addCircleFilled(middleX, middleY, size * .5f, hovered ? ROW_HOVER : 0, 32);
        draw.addCircle(middleX, middleY, size * .5f - 1, ACCENT, 32, large ? 2f : 1.5f);
        glyph(draw, glyph, middleX - icon * .5f, middleY - icon * .5f, icon, ACCENT);
        if (hovered) {
            showTooltip(tip);
        }
        return pressed;
    }

    public static float toastWidth(String text, @Nullable String action, float most) {
        var actionWidth = action == null ? 0 : toastActionWidth(action) + GeodataWidgets.ROW;
        return Math.min(most, ImGui.calcTextSizeX(text) + 40 + actionWidth);
    }

    public static int toast(
            String id, String text, int dot, @Nullable Glyph glyph, @Nullable String action, float width) {
        var draw = ImGui.getWindowDrawList();
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var height = TOAST_HEIGHT;
        draw.addRectFilled(x, y, x + width, y + height, fade(Motion.alpha(BACKGROUND, .985f)), PANEL_RADIUS);
        draw.addRect(x, y, x + width, y + height, fade(GeodataWidgets.DIVIDER), PANEL_RADIUS, 0, 1);
        var actionWidth = action == null ? 0 : toastActionWidth(action);
        var textRight = x + width - 14 - (action == null ? 0 : actionWidth + GeodataWidgets.ROW - 6);
        var pressed = ImGui.invisibleButton(id, Math.max(1, textRight - x), height);
        var hovered = ImGui.isItemHovered();
        var textTop = y + (height - ImGui.getFontSize()) * .5f - 1;
        draw.addCircleFilled(x + 14, textMiddle(textTop), 4, fade(dot), 12);
        var shown = truncateToWidth(text, textRight - x - 26);
        draw.addText(x + 26, textTop, fade(TEXT), shown);
        if (hovered) {
            showTooltip(shown.equals(text) ? "Click to dismiss." : text + "\n\nClick to dismiss.");
        }
        var result = pressed ? TOAST_CLICKED : 0;
        if (action != null) {
            ImGui.setCursorScreenPos(x + width - actionWidth - 6, y + (height - CONTROL_HEIGHT) * .5f);
            if (button(id + "-action", glyph, action, actionWidth, CONTROL_HEIGHT, false, false)) {
                result = TOAST_ACTION;
            }
        }
        ImGui.setCursorScreenPos(x, y + height);
        ImGui.dummy(width, 0);
        return result;
    }

    public static boolean beginMenu(String id, float width) {
        return beginMenu(id, width, ImGui.getItemRectMaxX(), ImGui.getItemRectMaxY());
    }

    public static boolean beginMenu(String id, float width, float right, float bottom) {
        ImGui.setNextWindowPos(right, bottom + GeodataWidgets.ROW, ImGuiCond.Always, 1, 0);
        ImGui.setNextWindowSize(width, 0);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, MENU_PADDING, MENU_PADDING);
        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, 0, 0);
        ImGui.pushStyleVar(ImGuiStyleVar.PopupRounding, PANEL_RADIUS);
        var open = ImGui.beginPopup(id);
        if (!open) {
            ImGui.popStyleVar(3);
            return false;
        }
        Motion.open(Motion.appear(ImGui.getID("##menuOpen"), Motion.OPEN * .8f), 0, -6, .97f);
        var draw = ImGui.getWindowDrawList();
        draw.channelsSplit(2);
        draw.channelsSetCurrent(1);
        inMenu = true;
        menuHovered = false;
        return true;
    }

    public static boolean beginMenuAt(String id, float width, float height, float x, float y) {
        var io = ImGui.getIO();
        var tall = Math.min(height, io.getDisplaySizeY() - 2 * GeodataWidgets.ROW);
        if (tall < height) {
            ImGui.setNextWindowSizeConstraints(width, 0, width, tall);
        }
        var left = Math.max(0, Math.min(x, io.getDisplaySizeX() - width - GeodataWidgets.ROW));
        var top = Math.max(0, Math.min(y, io.getDisplaySizeY() - tall - GeodataWidgets.ROW));
        return beginMenu(id, width, left + width, top - GeodataWidgets.ROW);
    }

    public static boolean beginFittedMenu(String id, float width, float height) {
        var right = ImGui.getItemRectMaxX();
        var below = ImGui.getItemRectMaxY() + GeodataWidgets.ROW;
        var room = ImGui.getIO().getDisplaySizeY() - GeodataWidgets.ROW;
        var top = below + height <= room ? below : ImGui.getItemRectMinY() - GeodataWidgets.ROW - height;
        return beginMenuAt(id, width, height, right - width, top);
    }

    public static float menuHeight(int rows, int rules) {
        return rows * MENU_ROW + rules * (2 * MENU_PADDING + 1) + 2 * MENU_PADDING;
    }

    public static void endMenu() {
        var draw = ImGui.getWindowDrawList();
        draw.channelsSetCurrent(0);
        var shownId = ImGui.getID("##menuHover");
        var topId = ImGui.getID("##menuHoverTop");
        var windowTop = ImGui.getWindowPosY();
        if (menuHovered && Motion.peek(shownId, 0) < .05f) {
            Motion.jump(topId, menuHoverTop - windowTop);
        }
        var shown = Motion.toggle(shownId, menuHovered, Motion.QUICK);
        var top =
                windowTop + Motion.follow(topId, menuHovered ? menuHoverTop - windowTop : Motion.peek(topId, 0), .09f);
        if (shown > 0 && menuHoverWidth > 0) {
            draw.addRectFilled(
                    menuHoverLeft, top, menuHoverLeft + menuHoverWidth, top + MENU_ROW, Motion.alpha(HOVER, shown), 6f);
        }
        draw.channelsMerge();
        inMenu = false;
        ImGui.endPopup();
        ImGui.popStyleVar(3);
    }

    public static boolean menuRow(String id, @Nullable Glyph glyph, String label, String key) {
        return menuRow(id, glyph, label, key, true);
    }

    public static boolean menuRow(String id, @Nullable Glyph glyph, String label, String key, boolean enabled) {
        var draw = ImGui.getWindowDrawList();
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var width = ImGui.getContentRegionAvailX();
        var clicked = ImGui.invisibleButton(id, width, MENU_ROW) && enabled;
        var hovered = enabled && ImGui.isItemHovered();
        var lit = Motion.toggle(ImGui.getItemID(), hovered, Motion.QUICK);
        if (hovered && inMenu) {
            menuHovered = true;
            menuHoverTop = y;
            menuHoverLeft = x;
            menuHoverWidth = width;
        } else if (lit > 0 && !inMenu) {
            draw.addRectFilled(x, y, x + width, y + MENU_ROW, Motion.alpha(HOVER, lit), 6f);
        }
        if (glyph != null) {
            glyph(
                    draw,
                    glyph,
                    x + 9,
                    y + (MENU_ROW - 18) * .5f,
                    18,
                    !enabled ? GeodataWidgets.FAINT : Motion.mix(MUTED, TEXT, lit));
        }
        var textTop = y + (MENU_ROW - ImGui.getFontSize()) * .5f - 1;
        draw.addText(x + 36, textTop, enabled ? TEXT : GeodataWidgets.FAINT, label);
        if (!key.isEmpty()) {
            draw.addText(
                    x + width - 10 - ImGui.calcTextSizeX(key), textTop, enabled ? MUTED : GeodataWidgets.FAINT, key);
        }
        if (clicked) {
            ImGui.closeCurrentPopup();
        }
        return clicked;
    }

    public static boolean menuDotRow(String id, int color, String label, String note) {
        return menuDotRow(id, color, label, note, false);
    }

    public static boolean menuDotRow(String id, int color, String label, String note, boolean faint) {
        var draw = ImGui.getWindowDrawList();
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var width = ImGui.getContentRegionAvailX();
        var clicked = ImGui.invisibleButton(id, width, MENU_ROW);
        var hovered = ImGui.isItemHovered();
        var lit = Motion.toggle(ImGui.getItemID(), hovered, Motion.QUICK);
        if (hovered && inMenu) {
            menuHovered = true;
            menuHoverTop = y;
            menuHoverLeft = x;
            menuHoverWidth = width;
        } else if (lit > 0 && !inMenu) {
            draw.addRectFilled(x, y, x + width, y + MENU_ROW, Motion.alpha(HOVER, lit), 6f);
        }
        var textTop = y + (MENU_ROW - ImGui.getFontSize()) * .5f - 1;
        draw.addCircleFilled(x + 18, textMiddle(textTop), 4.5f, faint ? Motion.alpha(color, .45f) : color, 14);
        draw.addText(x + 36, textTop, faint ? GeodataWidgets.FAINT : TEXT, label);
        var noteLeft = x + 36 + ImGui.calcTextSizeX(label) + 10;
        draw.addText(
                noteLeft,
                textTop,
                faint ? GeodataWidgets.FAINT : MUTED,
                truncateToWidth(note, x + width - 10 - noteLeft));
        if (clicked) {
            ImGui.closeCurrentPopup();
        }
        return clicked;
    }

    public static void menuNote(String text) {
        menuNote(text, GeodataWidgets.FAINT);
    }

    public static void menuNote(String text, int color) {
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var textTop = y + (MENU_ROW - ImGui.getFontSize()) * .5f - 1;
        ImGui.getWindowDrawList().addText(x + 10, textTop, color, text);
        ImGui.dummy(ImGui.getContentRegionAvailX(), MENU_ROW);
    }

    public static void menuRule() {
        var draw = ImGui.getWindowDrawList();
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY() + MENU_PADDING;
        draw.addLine(x + 4, y, x + ImGui.getContentRegionAvailX() - 4, y, ImColor.rgba(255, 255, 255, 28));
        ImGui.dummy(0, 2 * MENU_PADDING + 1);
    }

    public static boolean menuButton(String id, Glyph icon, String label, float width, float height, boolean selected) {
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var pressed = button(id, null, "", width, height, selected, true);
        var draw = ImGui.getWindowDrawList();
        glyph(draw, icon, x + 10, y + (height - 18) * .5f, 18, selected ? ACCENT : TEXT);
        draw.addText(x + 36, y + (height - ImGui.getFontSize()) * .5f - 1, fade(TEXT), label);
        glyph(draw, Glyph.CHEVRON, x + width - 23, y + (height - 18) * .5f, 18, MUTED);
        return pressed;
    }

    public static boolean foldButton(
            String id, String label, Glyph chevron, float width, float height, boolean selected) {
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var pressed = button(id, null, "", width, height, selected, true);
        var draw = ImGui.getWindowDrawList();
        if (!label.isEmpty()) {
            draw.addText(x + 10, y + (height - ImGui.getFontSize()) * .5f - 1, fade(TEXT), label);
        }
        glyph(
                draw,
                chevron,
                label.isEmpty() ? x + (width - 18) * .5f : x + width - 23,
                y + (height - 18) * .5f,
                18,
                MUTED);
        return pressed;
    }

    public static boolean textButton(String id, String text, int color, float width, float height) {
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var pressed = button(id, null, "", width, height, false, false);
        ImGui.getWindowDrawList()
                .addText(
                        x + (width - ImGui.calcTextSizeX(text)) * .5f,
                        y + (height - ImGui.getFontSize()) * .5f - 1,
                        fade(color),
                        text);
        return pressed;
    }

    public static boolean metricButton(
            String id,
            Glyph icon,
            String label,
            String value,
            int valueColor,
            float width,
            float height,
            boolean selected) {
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var pressed = button(id, null, "", width, height, selected, false);
        var iconSize = 18f;
        var labelWidth = ImGui.calcTextSizeX(label);
        var left = x + (width - iconSize - 8 - labelWidth - 12 - ImGui.calcTextSizeX(value)) * .5f;
        var textY = y + (height - ImGui.getFontSize()) * .5f - 1;
        var draw = ImGui.getWindowDrawList();
        glyph(draw, icon, left, y + (height - iconSize) * .5f, iconSize, selected ? ACCENT : TEXT);
        draw.addText(left + iconSize + 8, textY, fade(TEXT), label);
        draw.addText(left + iconSize + 8 + labelWidth + 12, textY, fade(valueColor), value);
        return pressed;
    }

    public static int fpsColor(int fps) {
        return fps >= 55
                ? ImColor.rgb(134, 229, 160)
                : fps >= 30 ? ImColor.rgb(255, 200, 90) : ImColor.rgb(255, 107, 107);
    }

    public static boolean navigationItem(String id, String label, boolean selected, float width) {
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var pressed = button(id, null, "", width, CONTROL_HEIGHT, selected, false);
        ImGui.getWindowDrawList()
                .addText(
                        x + 8,
                        y + (CONTROL_HEIGHT - ImGui.getFontSize()) * .5f - 1,
                        fade(selected ? ACCENT : TEXT),
                        label);
        return pressed;
    }

    public static void sliderFloat(String label, float[] value, float min, float max, String format) {
        beginCompactSlider();
        drawSliderHeading(label);
        ImGui.dragFloat("##value/" + label, value, (max - min) / 400f, min, max, format, ImGuiSliderFlags.AlwaysClamp);
        showSliderValueHint();
        var origin = ImGui.getCursorScreenPos();
        var width = ImGui.getContentRegionAvailX();
        beginSliderTrack(width, 0);
        ImGui.sliderFloat("##track/" + label, value, min, max, "", ImGuiSliderFlags.NoInput);
        endSliderTrack(origin.x, origin.y, width, (value[0] - min) / (max - min));
        ImGui.popStyleVar(2);
    }

    public static boolean gradientSlider(
            String label, float[] value, float min, float max, String format, float curve, int[] stops, String none) {
        var before = value[0];
        var shown = Float.isFinite(value[0]);
        beginCompactSlider();
        drawSliderHeading(label);
        if (shown) {
            ImGui.dragFloat(
                    "##value/" + label, value, (max - min) / 400f, min, max, format, ImGuiSliderFlags.AlwaysClamp);
        } else {
            var typed = new float[] {(min + max) / 2};
            if (ImGui.dragFloat(
                    "##value/" + label, typed, (max - min) / 400f, min, max, none, ImGuiSliderFlags.AlwaysClamp)) {
                value[0] = typed[0];
            }
        }
        showSliderValueHint();
        var origin = ImGui.getCursorScreenPos();
        var width = ImGui.getContentRegionAvailX();
        var place = new float[] {
            shown && max > min ? (float) Math.pow(Math.clamp((value[0] - min) / (max - min), 0, 1), 1 / curve) : .5f
        };
        beginSliderTrack(width, 3);
        if (ImGui.sliderFloat(
                "##track/" + label, place, 0, 1, "", ImGuiSliderFlags.NoInput | ImGuiSliderFlags.AlwaysClamp)) {
            value[0] = min + (max - min) * (float) Math.pow(place[0], curve);
        }
        ImGui.popStyleVar(2);
        ImGui.popStyleColor(5);
        var hot = ImGui.isItemHovered() || ImGui.isItemActive();
        drawGradientTrack(
                origin.x,
                origin.y,
                width,
                ImGui.getItemRectMaxY() - origin.y,
                stops,
                Float.isFinite(value[0]) && max > min
                        ? (float) Math.pow(Math.clamp((value[0] - min) / (max - min), 0, 1), 1 / curve)
                        : Float.NaN,
                hot);
        ImGui.popStyleVar(2);
        return value[0] != before && !(Float.isNaN(value[0]) && Float.isNaN(before));
    }

    public static int gradientColor(int[] stops, float place) {
        if (stops.length == 1) {
            return stops[0];
        }
        var at = Math.clamp(place, 0, 1) * (stops.length - 1);
        var first = Math.min((int) at, stops.length - 2);
        var mix = at - first;
        var a = stops[first];
        var b = stops[first + 1];
        var result = 0;
        for (var shift = 0; shift <= 24; shift += 8) {
            var from = a >>> shift & 0xff;
            var to = b >>> shift & 0xff;
            result |= Math.round(from + (to - from) * mix) << shift;
        }
        return result;
    }

    public static int directionDials(
            String id, float[] yaw, float[] pitch, float coneDegrees, float innerDegrees, int color, float width) {
        return directionDials(id, yaw, pitch, coneDegrees, innerDegrees, color, width, DIAL_HEIGHT);
    }

    public static int directionDials(
            String id,
            float[] yaw,
            float[] pitch,
            float coneDegrees,
            float innerDegrees,
            int color,
            float width,
            float tallest) {
        var spacing = ImGui.getStyle().getItemSpacingX();
        var size = (width - spacing) / 2;
        var height = Math.min(size, tallest);
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var changed = 0;
        ImGui.invisibleButton(id + "/yaw", size, height);
        var compassHot = ImGui.isItemHovered() || ImGui.isItemActive();
        var cx = x + size / 2;
        var cy = y + height / 2;
        if (ImGui.isItemActive()) {
            var next = compassYaw(ImGui.getMousePosX() - cx, ImGui.getMousePosY() - cy);
            if (Float.isFinite(next) && next != yaw[0]) {
                yaw[0] = next;
                changed = DIAL_YAW;
            }
        }
        if (ImGui.isItemHovered() && !ImGui.isItemActive()) {
            showTooltip("Direction: seen from above, north up. Drag around it to turn the way it faces.");
        }
        ImGui.sameLine(0, spacing);
        var sx = x + size + spacing;
        ImGui.invisibleButton(id + "/pitch", size, height);
        var tiltHot = ImGui.isItemHovered() || ImGui.isItemActive();
        var ax = sx + size * .3f;
        var ay = cy;
        if (ImGui.isItemActive()) {
            var next = tiltPitch(ImGui.getMousePosX() - ax, ay - ImGui.getMousePosY());
            if (next != pitch[0]) {
                pitch[0] = next;
                changed = DIAL_PITCH;
            }
        }
        if (ImGui.isItemHovered() && !ImGui.isItemActive()) {
            showTooltip("Seen from the side, along the way it faces. Drag to tilt it from straight up to straight down."
                    + " The wedge is its cone, brighter where it gives half its light or more.");
        }
        var draw = ImGui.getWindowDrawList();
        drawCompass(draw, x, y, size, height, yaw[0], pitch[0], color, compassHot);
        drawTilt(draw, sx, y, size, height, ax, ay, pitch[0], coneDegrees, innerDegrees, color, tiltHot);
        return changed;
    }

    static float compassYaw(float dx, float dy) {
        return Math.hypot(dx, dy) > 4 ? Math.round((float) Math.toDegrees(Math.atan2(dy, dx))) : Float.NaN;
    }

    static float tiltPitch(float dx, float dy) {
        var pitch = dx > 1 ? Math.round((float) Math.toDegrees(Math.atan2(dy, dx))) : dy >= 0 ? 90 : -90;
        return Math.clamp(pitch, -90, 90);
    }

    public static float timeline(String id, float seconds, float length, float[] marks, float width) {
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var labelWidth = ImGui.calcTextSizeX("00.00 s") + 8;
        var barWidth = Math.max(40, width - labelWidth);
        ImGui.invisibleButton(id, barWidth, CONTROL_HEIGHT);
        var left = x + 5;
        var right = x + barWidth - 5;
        var picked = Float.NaN;
        if (ImGui.isItemActive() && length > 0) {
            picked = Math.clamp((ImGui.getMousePosX() - left) / (right - left), 0, 1) * length;
        }
        var draw = ImGui.getWindowDrawList();
        var centerY = y + CONTROL_HEIGHT * .5f;
        var knobX = left + (right - left) * (length > 0 ? Math.clamp(seconds / length, 0, 1) : 0);
        draw.addRectFilled(left, centerY - 1.5f, right, centerY + 1.5f, BORDER, 1.5f);
        draw.addRectFilled(left, centerY - 1.5f, knobX, centerY + 1.5f, ACCENT, 1.5f);
        for (var mark : marks) {
            if (length > 0 && mark >= 0 && mark <= length) {
                var markX = left + (right - left) * mark / length;
                draw.addRectFilled(markX - 1, centerY - 6, markX + 1, centerY + 6, MUTED, 1f);
            }
        }
        draw.addCircleFilled(knobX, centerY, ImGui.isItemActive() || ImGui.isItemHovered() ? 6 : 5, TEXT, 20);
        draw.addText(
                x + barWidth + 8,
                y + (CONTROL_HEIGHT - ImGui.getFontSize()) * .5f - 1,
                MUTED,
                String.format(Locale.ROOT, "%.2f s", seconds));
        return picked;
    }

    public static boolean inlineLogSlider(String id, float[] value, float min, float max, float width) {
        var origin = ImGui.getCursorScreenPos();
        beginSliderTrack(width, 3);
        var changed = ImGui.sliderFloat(
                id,
                value,
                min,
                max,
                "",
                ImGuiSliderFlags.Logarithmic | ImGuiSliderFlags.NoInput | ImGuiSliderFlags.AlwaysClamp);
        endSliderTrack(origin.x, origin.y, width, (float) (Math.log(value[0] / min) / Math.log(max / min)));
        return changed;
    }

    public static boolean beginCombo(String id, String preview, int flags) {
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 9, 6);
        boolean open = ImGui.beginCombo(id, preview, flags);
        ImGui.popStyleVar();
        if (open) {
            Motion.open(Motion.appear(ImGui.getID("##comboOpen"), Motion.OPEN * .8f), 0, -4, 1);
        }
        return open;
    }

    public static float textMiddle(float textTop) {
        return textTop + ImGui.getFontSize() * .625f;
    }

    public static void showTooltip(String text) {
        var owner = ImGui.getItemID();
        var held = ImGui.isItemActive() || ImGui.isItemDeactivated();
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 12, 9);
        ImGui.beginTooltip();
        var shownId = ImGui.getID("##tooltipShown");
        if (owner != tooltipOwner && !held) {
            tooltipOwner = owner;
            Motion.restart(shownId);
        }
        Motion.open(Motion.appear(shownId, .14f), 0, 4, .98f);
        ImGui.pushTextWrapPos(ImGui.getCursorPosX() + 340);
        ImGui.textUnformatted(text);
        ImGui.popTextWrapPos();
        ImGui.endTooltip();
        ImGui.popStyleVar();
    }

    public static void showHoverCard(int owner, String title, List<String> details, String actions) {
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 12, 9);
        ImGui.beginTooltip();
        var shownId = ImGui.getID("##tooltipShown");
        if (owner != tooltipOwner) {
            tooltipOwner = owner;
            Motion.restart(shownId);
        }
        Motion.open(Motion.appear(shownId, .14f), 0, 4, .98f);
        ImGui.pushTextWrapPos(ImGui.getCursorPosX() + 340);
        ImGui.textUnformatted(title);
        ImGui.pushStyleColor(ImGuiCol.Text, GeodataWidgets.MUTED);
        for (var detail : details) {
            ImGui.textUnformatted(detail);
        }
        ImGui.popStyleColor();
        GeodataWidgets.space(GeodataWidgets.ROW);
        ImGui.pushStyleColor(ImGuiCol.Text, GeodataWidgets.FAINT);
        ImGui.textUnformatted(actions);
        ImGui.popStyleColor();
        ImGui.popTextWrapPos();
        ImGui.endTooltip();
        ImGui.popStyleVar();
    }

    public static void sliderInt(String label, int[] value, int min, int max) {
        sliderInt(label, value, min, max, ImGui.getContentRegionAvailX());
    }

    public static boolean sliderInt(String label, int[] value, int min, int max, float width) {
        return sliderInt(label, value, min, max, min, max, width);
    }

    public static boolean sliderInt(
            String label, int[] value, int min, int max, int fieldMin, int fieldMax, float width) {
        var before = value[0];
        var left = ImGui.getCursorScreenPosX();
        beginCompactSlider();
        ImGui.alignTextToFramePadding();
        ImGui.textColored(MUTED, label);
        ImGui.sameLine();
        ImGui.setCursorScreenPos(left + width - 72, ImGui.getCursorScreenPosY());
        ImGui.setNextItemWidth(72);
        ImGui.dragInt(
                "##value/" + label,
                value,
                Math.max(1, (max - min) / 400f),
                fieldMin,
                fieldMax,
                "%d",
                ImGuiSliderFlags.AlwaysClamp);
        showSliderValueHint();
        var origin = ImGui.getCursorScreenPos();
        beginSliderTrack(width, 0);
        ImGui.sliderInt("##track/" + label, value, min, max, "", ImGuiSliderFlags.NoInput);
        endSliderTrack(
                origin.x, origin.y, width, max > min ? Math.clamp((value[0] - min) / (float) (max - min), 0f, 1f) : 1f);
        ImGui.popStyleVar(2);
        return value[0] != before;
    }

    public static int rangeSlider(String id, float[] low, float[] high, float width) {
        return rangeSlider(id, low, high, width, NO_TICKS, Float.NaN);
    }

    public static int rangeSlider(String id, float[] low, float[] high, float width, float[] ticks, float mark) {
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        ImGui.invisibleButton(id, width, RANGE_HEIGHT);
        var left = x + RANGE_KNOB + 1;
        var right = x + width - RANGE_KNOB - 1;
        var length = Math.max(1f, right - left);
        var raw = (ImGui.getMousePosX() - left) / length;
        var pointer = Math.clamp(raw, 0f, 1f);
        var moved = 0;
        if (ImGui.isItemActivated()) {
            rangeKnob = nearerKnob(pointer, low[0], high[0]);
            rangeGrab = rangeGrab(raw, rangeKnob == RANGE_LOW ? low[0] : high[0], (RANGE_KNOB + 3) / length);
        }
        if (ImGui.isItemActive()) {
            var fine = ImGui.getIO().getKeyShift();
            if (fine) {
                rangeGrab -= ImGui.getIO().getMouseDeltaX() / length * (1 - RANGE_FINE);
            }
            var place = knobPlace(raw + rangeGrab, fine ? 0 : RANGE_SNAP / length);
            if (rangeKnob == RANGE_LOW) {
                var next = Math.min(place, high[0]);
                if (next != low[0]) {
                    low[0] = next;
                    moved = RANGE_LOW;
                }
            } else {
                var next = Math.max(place, low[0]);
                if (next != high[0]) {
                    high[0] = next;
                    moved = RANGE_HIGH;
                }
            }
        }
        var hovered = ImGui.isItemHovered() || ImGui.isItemActive();
        var hot = !hovered ? 0 : ImGui.isItemActive() ? rangeKnob : nearerKnob(pointer, low[0], high[0]);
        var centerY = y + RANGE_HEIGHT * .5f;
        var lowX = left + (right - left) * low[0];
        var highX = left + (right - left) * high[0];
        var draw = ImGui.getWindowDrawList();
        for (var tick : ticks) {
            var tickX = thinLine(left + (right - left) * tick);
            draw.addLine(tickX, centerY + 4, tickX, centerY + 9, BORDER, 1);
        }
        draw.addRectFilled(left, centerY - 1.5f, right, centerY + 1.5f, BORDER, 1.5f);
        draw.addRectFilled(lowX, centerY - 1.5f, highX, centerY + 1.5f, ACCENT, 1.5f);
        if (!Float.isNaN(mark)) {
            var markX = left + (right - left) * mark;
            draw.addTriangleFilled(markX - 4, centerY - 10, markX + 4, centerY - 10, markX, centerY - 5, MUTED);
        }
        draw.addCircleFilled(lowX, centerY, hot == RANGE_LOW ? RANGE_KNOB + 1 : RANGE_KNOB, TEXT, 20);
        draw.addCircleFilled(highX, centerY, hot == RANGE_HIGH ? RANGE_KNOB + 1 : RANGE_KNOB, TEXT, 20);
        return moved;
    }

    static float rangeGrab(float pointer, float knob, float reach) {
        return Math.abs(pointer - knob) <= reach ? knob - pointer : 0;
    }

    static float knobPlace(float wanted, float snap) {
        if (wanted <= snap) {
            return 0;
        }
        return wanted >= 1 - snap ? 1 : Math.min(wanted, 1);
    }

    static int nearerKnob(float pointer, float low, float high) {
        var toLow = Math.abs(pointer - low);
        var toHigh = Math.abs(pointer - high);
        if (toLow == toHigh) {
            return pointer < low ? RANGE_LOW : RANGE_HIGH;
        }
        return toLow < toHigh ? RANGE_LOW : RANGE_HIGH;
    }

    public static boolean switchRow(String label, boolean value, boolean enabled, @Nullable String tooltip) {
        var origin = ImGui.getCursorScreenPos();
        var width = ImGui.getContentRegionAvailX();
        var height = 30f;
        ImGui.beginDisabled(!enabled);
        var pressed = button("tg_" + label, null, "", width, height, false, false);
        var hovered = ImGui.isItemHovered(ImGuiHoveredFlags.AllowWhenDisabled);
        var next = enabled && pressed ? !value : value;
        var draw = ImGui.getWindowDrawList();
        if (enabled && hovered) {
            draw.addRectFilled(origin.x, origin.y, origin.x + width, origin.y + height, ROW_HOVER, 4f);
        }
        draw.addText(origin.x, origin.y + (height - ImGui.getFontSize()) * .5f, enabled ? TEXT : TEXT_DISABLED, label);
        drawSwitch(draw, origin.x + width - 30, origin.y + (height - 16) * .5f, next, enabled);
        if (tooltip != null && hovered) {
            showTooltip(tooltip);
        }
        ImGui.endDisabled();
        return next;
    }

    public static void drawSwitch(ImDrawList draw, float x, float y, boolean on, boolean enabled) {
        draw.addRectFilled(x, y, x + 30, y + 16, !enabled ? PILL_DISABLED : on ? PILL_ON : PILL_OFF, 8f);
        draw.addCircleFilled(x + (on ? 22 : 8), y + 8, 5, !enabled ? KNOB_DISABLED : on ? ACCENT : KNOB_OFF);
    }

    public static boolean beginPopupAbove(String id, float centerX, float width, float bottom) {
        var edge = GeodataWidgets.BLOCK;
        var left = Math.max(edge, Math.min(centerX - width * .5f, ImGui.getIO().getDisplaySizeX() - width - edge));
        ImGui.setNextWindowPos(left, bottom, ImGuiCond.Always, 0, 1);
        ImGui.setNextWindowSizeConstraints(width, 0, width, Math.max(edge, bottom - edge));
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, edge, edge);
        ImGui.pushStyleVar(ImGuiStyleVar.PopupRounding, PANEL_RADIUS);
        var open = ImGui.beginPopup(id);
        ImGui.popStyleVar(2);
        if (open) {
            Motion.open(Motion.appear(ImGui.getID("##popupAboveOpen"), Motion.OPEN * .8f), 0, 6, .97f);
        }
        return open;
    }

    public static boolean beginPopupBelow(String id, float width, float right, float bottom) {
        var edge = GeodataWidgets.BLOCK;
        var left = Math.max(edge, Math.min(right - width, ImGui.getIO().getDisplaySizeX() - width - edge));
        ImGui.setNextWindowPos(left, bottom + GeodataWidgets.ROW, ImGuiCond.Always);
        ImGui.setNextWindowSize(width, 0);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, edge, edge);
        ImGui.pushStyleVar(ImGuiStyleVar.PopupRounding, PANEL_RADIUS);
        var open = ImGui.beginPopup(id);
        ImGui.popStyleVar(2);
        if (open) {
            Motion.open(Motion.appear(ImGui.getID("##popupBelowOpen"), Motion.OPEN * .8f), 0, -6, .97f);
        }
        return open;
    }

    public static boolean railButton(
            String id, Glyph glyph, float width, float height, float iconSize, boolean accent) {
        return button(id, glyph, "", width, height, accent, false, false, iconSize, TEXT);
    }

    static void ripple(ImDrawList draw, int item, float x, float y, float width, float height) {
        if (ImGui.isItemActivated()) {
            rippleId = item;
            rippleX = ImGui.getMousePosX() - x;
            rippleY = ImGui.getMousePosY() - y;
            Motion.restart(item + 3);
        }
        if (rippleId != item) {
            return;
        }
        var progress = Motion.since(item + 3, .42f);
        if (progress >= 1) {
            return;
        }
        var reach = (float) Math.hypot(Math.max(rippleX, width - rippleX), Math.max(rippleY, height - rippleY));
        var radius = reach * (.25f + .75f * Motion.easeOut(progress));
        draw.pushClipRect(x, y, x + width, y + height, true);
        draw.addCircleFilled(x + rippleX, y + rippleY, radius, fade(Motion.alpha(ACCENT, .2f * (1 - progress))), 32);
        draw.popClipRect();
    }

    public static void chevron(ImDrawList draw, float cx, float cy, float turn, int color, float thickness) {
        var angle = turn * Math.PI * .5;
        var cos = (float) Math.cos(angle);
        var sin = (float) Math.sin(angle);
        draw.addLine(cx - 2 * cos + 4 * sin, cy - 2 * sin - 4 * cos, cx + 2 * cos, cy + 2 * sin, color, thickness);
        draw.addLine(cx + 2 * cos, cy + 2 * sin, cx - 2 * cos - 4 * sin, cy - 2 * sin + 4 * cos, color, thickness);
    }

    public static boolean disclosure(String id, String label, boolean open) {
        var width = ImGui.getContentRegionAvailX();
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var pressed = button(id, null, truncateToWidth(label, width - 44), width, 26, false, false, true);
        var turn = Motion.toggle(ImGui.getItemID() + 4, pressed != open, Motion.QUICK * 1.5f);
        chevron(ImGui.getWindowDrawList(), x + width - 16, y + 13, turn, MUTED, 1.2f);
        return pressed;
    }

    public static boolean helpMark(String article) {
        var size = 14f;
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var line = ImGui.getTextLineHeight();
        var pressed = ImGui.invisibleButton("##help/" + article, size, line);
        HELP_MARKS.put(article, new float[] {x, y, x + size, y + line});
        var hovered = ImGui.isItemHovered();
        var lit = Motion.toggle(ImGui.getItemID(), hovered, Motion.QUICK);
        glyph(
                ImGui.getWindowDrawList(),
                Glyph.HELP,
                Math.round(x),
                Math.round(y + (line - size) * .5f),
                size,
                Motion.mix(GeodataWidgets.FAINT, ACCENT, lit));
        if (hovered) {
            ImGui.setMouseCursor(ImGuiMouseCursor.Hand);
            var found = GuideLibrary.shared().getArticle(article);
            showHoverCard(
                    ImGui.getItemID(),
                    found == null ? "Guide" : found.title(),
                    found == null ? List.of() : List.of(found.summary()),
                    "Click to read more");
        }
        if (pressed) {
            GuideRequests.open(article);
        }
        return pressed;
    }

    public static float @Nullable [] helpMarkPlace(String article) {
        return HELP_MARKS.get(article);
    }

    public static boolean guideLink(String label, String article) {
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var line = Math.max(16, ImGui.getTextLineHeight());
        var width = Math.min(ImGui.getContentRegionAvailX(), 16 + 8 + ImGui.calcTextSizeX(label));
        var pressed = ImGui.invisibleButton("##guideLink/" + article, Math.max(1, width), line);
        var hovered = ImGui.isItemHovered();
        var draw = ImGui.getWindowDrawList();
        glyph(draw, Glyph.BOOK_OPEN, Math.round(x), Math.round(y + (line - 16) * .5f), 16, ACCENT);
        var shown = truncateToWidth(label, width - 24);
        var textY = y + (line - ImGui.getFontSize()) * .5f;
        draw.addText(x + 24, textY, ACCENT, shown);
        if (hovered) {
            ImGui.setMouseCursor(ImGuiMouseCursor.Hand);
            var under = Math.round(textY + ImGui.getFontSize() + 1);
            draw.addLine(x + 24, under, x + 24 + ImGui.calcTextSizeX(shown), under, ACCENT, 1);
            HudWidgets.showTooltip("Opens the Guide beside the panel.");
        }
        if (pressed) {
            GuideRequests.open(article);
        }
        return pressed;
    }

    public static boolean guideButton(String id, String article) {
        var pressed = button(id, Glyph.HELP, "", 24, 24, false, false);
        if (ImGui.isItemHovered(ImGuiHoveredFlags.AllowWhenDisabled)) {
            var found = GuideLibrary.shared().getArticle(article);
            showTooltip((found == null
                            ? "Read about this tool in the Guide."
                            : "Read \"" + found.title() + "\" in the Guide.")
                    + "\n\nShortcut: Shift+F1");
        }
        if (pressed) {
            GuideRequests.open(article);
        }
        return pressed;
    }

    public static String truncateToWidth(String text, float width) {
        if (ImGui.calcTextSizeX(text) <= width) {
            return text;
        }
        var end = text.length();
        while (end > 0 && ImGui.calcTextSizeX(text.substring(0, end) + "…") > width) {
            end--;
        }
        return text.substring(0, end).stripTrailing() + "…";
    }

    public static boolean tab(String label, boolean selected, float width, float height) {
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var pressed = button("tab/" + label, null, "", width, height, false, false);
        var draw = ImGui.getWindowDrawList();
        var chosen = Motion.toggle(ImGui.getItemID() + 5, selected, Motion.QUICK);
        draw.addText(
                x + (width - ImGui.calcTextSizeX(label)) * .5f,
                y + (height - ImGui.getFontSize()) * .5f,
                fade(Motion.mix(MUTED, TEXT, chosen)),
                label);
        if (selected) {
            var left = Motion.spring("##tabLine", x - ImGui.getWindowPosX());
            var right = Motion.spring("##tabLineEnd", x + width - ImGui.getWindowPosX());
            var origin = ImGui.getWindowPosX();
            draw.addRectFilled(origin + left + 4, y + height - 3, origin + right - 4, y + height, fade(ACCENT), 1f);
        }
        return pressed;
    }

    public static void line(float width) {
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        ImGui.getWindowDrawList().addLine(x, y, x + width, y, BORDER, 1);
        ImGui.dummy(0, 1);
    }

    public static void glyph(ImDrawList d, Glyph icon, float x, float y, float size, int color) {
        if (icon == Glyph.LOGO) {
            CrumaBranding.drawMark(d, x, y, size);
            return;
        }
        var pixels = Math.round(size);
        var font = FontManager.iconFont(pixels);
        if (font == null) {
            return;
        }
        var inset = (size - pixels) * .5f;
        d.addText(font, pixels, x + inset, y + inset, fade(color), icon.getText());
    }

    private static float toastActionWidth(String action) {
        return ImGui.calcTextSizeX(action) + 18 + 8 + 20;
    }

    private static void drawGradientTrack(
            float x, float y, float width, float height, int[] stops, float place, boolean hot) {
        var draw = ImGui.getWindowDrawList();
        var bar = 8f;
        var centerY = y + height * .5f;
        var top = centerY - bar / 2;
        var bottom = centerY + bar / 2;
        var left = x + 7;
        var right = x + width - 7;
        draw.addCircleFilled(left, centerY, bar / 2, stops[0], 16);
        draw.addCircleFilled(right, centerY, bar / 2, stops[stops.length - 1], 16);
        var segments = Math.max(1, stops.length - 1);
        for (var i = 0; i < segments; i++) {
            var a = left + (right - left) * i / segments;
            var b = left + (right - left) * (i + 1) / segments;
            var from = stops[Math.min(i, stops.length - 1)];
            var to = stops[Math.min(i + 1, stops.length - 1)];
            draw.addRectFilledMultiColor(a, top, b + .5f, bottom, from, to, to, from);
        }
        draw.addRect(left - bar / 2, top - .5f, right + bar / 2, bottom + .5f, BORDER, bar / 2, 0, 1);
        var radius = hot ? 7.5f : 6.5f;
        if (Float.isNaN(place)) {
            var middle = (left + right) / 2;
            draw.addCircle(middle, centerY, radius, BACKGROUND, 20, 3.5f);
            draw.addCircle(middle, centerY, radius, MUTED, 20, 1.5f);
            return;
        }
        var knobX = left + (right - left) * place;
        draw.addCircleFilled(knobX, centerY, radius + 1.5f, BACKGROUND, 20);
        draw.addCircleFilled(knobX, centerY, radius, TEXT, 20);
        draw.addCircleFilled(knobX, centerY, radius - 2, gradientColor(stops, place) | 0xff000000, 20);
    }

    private static void drawCompass(
            ImDrawList draw,
            float x,
            float y,
            float size,
            float height,
            float yaw,
            float pitch,
            int color,
            boolean hot) {
        draw.addRectFilled(x, y, x + size, y + height, GeodataWidgets.CARD, 6f);
        draw.addRect(x, y, x + size, y + height, hot ? ACCENT : BORDER, 6f, 0, 1);
        var cx = x + size / 2;
        var cy = y + height / 2;
        var radius = Math.min(size, height) / 2 - 18;
        draw.addCircle(cx, cy, radius, BORDER, 48, 1.5f);
        for (var i = 0; i < 8; i++) {
            var turn = Math.PI * i / 4;
            var inner = i % 2 == 0 ? radius - 6 : radius - 3;
            draw.addLine(
                    cx + (float) Math.cos(turn) * inner,
                    cy + (float) Math.sin(turn) * inner,
                    cx + (float) Math.cos(turn) * radius,
                    cy + (float) Math.sin(turn) * radius,
                    BORDER,
                    1);
        }
        var label = radius + 9;
        String[] names = {"E", "S", "W", "N"};
        for (var i = 0; i < 4; i++) {
            var turn = Math.PI * i / 2;
            var name = names[i];
            var tx = cx + (float) Math.cos(turn) * label - ImGui.calcTextSizeX(name) / 2;
            var ty = cy + (float) Math.sin(turn) * label - ImGui.getFontSize() / 2;
            draw.addText(tx, ty, "N".equals(name) ? TEXT : GeodataWidgets.FAINT, name);
        }
        var level = (float) Math.cos(Math.toRadians(pitch));
        var length = (radius - 4) * level;
        var dx = (float) Math.cos(Math.toRadians(yaw));
        var dy = (float) Math.sin(Math.toRadians(yaw));
        if (length > 8) {
            var tipX = cx + dx * length;
            var tipY = cy + dy * length;
            draw.addLine(cx, cy, tipX, tipY, BACKGROUND, 5);
            draw.addLine(cx, cy, tipX, tipY, color, 2.5f);
            var head = 8f;
            var wide = 5f;
            var bx = tipX - dx * head;
            var by = tipY - dy * head;
            draw.addTriangleFilled(
                    tipX + dx * 1.5f,
                    tipY + dy * 1.5f,
                    bx - dy * (wide + 1.5f),
                    by + dx * (wide + 1.5f),
                    bx + dy * (wide + 1.5f),
                    by - dx * (wide + 1.5f),
                    BACKGROUND);
            draw.addTriangleFilled(tipX, tipY, bx - dy * wide, by + dx * wide, bx + dy * wide, by - dx * wide, color);
        }
        draw.addCircleFilled(cx, cy, 4.5f, BACKGROUND, 16);
        draw.addCircleFilled(cx, cy, 3, color, 16);
        if (length <= 8) {
            draw.addCircle(cx, cy, 9, color, 20, 1.5f);
        }
    }

    private static void drawTilt(
            ImDrawList draw,
            float x,
            float y,
            float size,
            float height,
            float ax,
            float ay,
            float pitch,
            float coneDegrees,
            float innerDegrees,
            int color,
            boolean hot) {
        draw.addRectFilled(x, y, x + size, y + height, GeodataWidgets.CARD, 6f);
        draw.addRect(x, y, x + size, y + height, hot ? ACCENT : BORDER, 6f, 0, 1);
        var radius = Math.min(height / 2 - 10, x + size - ax - 10);
        draw.pushClipRect(x + 1, y + 1, x + size - 1, y + height - 1, true);
        draw.addLine(ax, ay, ax + radius, ay, GeodataWidgets.FAINT, 1);
        draw.pathClear();
        draw.pathArcTo(ax, ay, radius, (float) -Math.PI / 2, (float) Math.PI / 2, 32);
        draw.pathStroke(BORDER, 0, 1.5f);
        var axis = (float) Math.toRadians(-pitch);
        wedge(draw, ax, ay, radius, axis, (float) Math.toRadians(coneDegrees), alpha(color, .28f));
        wedge(draw, ax, ay, radius, axis, (float) Math.toRadians(innerDegrees), alpha(color, .5f));
        var tipX = ax + (float) Math.cos(axis) * radius;
        var tipY = ay + (float) Math.sin(axis) * radius;
        draw.addLine(ax, ay, tipX, tipY, BACKGROUND, 5);
        draw.addLine(ax, ay, tipX, tipY, color, 2.5f);
        draw.addCircleFilled(ax, ay, 4.5f, BACKGROUND, 16);
        draw.addCircleFilled(ax, ay, 3, color, 16);
        draw.popClipRect();
        draw.addText(x + size - ImGui.calcTextSizeX("up") - 6, y + 4, GeodataWidgets.FAINT, "up");
        draw.addText(
                x + size - ImGui.calcTextSizeX("down") - 6,
                y + height - ImGui.getFontSize() - 4,
                GeodataWidgets.FAINT,
                "down");
    }

    private static void wedge(ImDrawList draw, float x, float y, float radius, float middle, float half, int color) {
        if (!(half > 0)) {
            return;
        }
        draw.pathClear();
        draw.pathLineTo(x, y);
        draw.pathArcTo(x, y, radius, middle - half, middle + half, Math.max(6, Math.round(half * 24)));
        draw.pathFillConvex(color);
    }

    private static int alpha(int color, float opacity) {
        var a = Math.round(Math.clamp(opacity, 0f, 1f) * ((color >>> 24) & 0xff));
        return color & 0x00ffffff | a << 24;
    }

    private static float thinLine(float place) {
        return (float) Math.floor(place) + .5f;
    }

    private static void beginCompactSlider() {
        ImGui.pushStyleVar(ImGuiStyleVar.FramePadding, 4, 1);
        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, 8, 2);
    }

    private static void drawSliderHeading(String label) {
        ImGui.alignTextToFramePadding();
        ImGui.textColored(MUTED, label);
        ImGui.sameLine(ImGui.getWindowContentRegionMaxX() - 72);
        ImGui.setNextItemWidth(72);
    }

    private static void showSliderValueHint() {
        if (ImGui.isItemHovered()) {
            showTooltip("Drag the value, or Ctrl-click to type.");
        }
    }

    private static void beginSliderTrack(float width, float verticalPadding) {
        for (var color : new int[] {
            ImGuiCol.FrameBg,
            ImGuiCol.FrameBgHovered,
            ImGuiCol.FrameBgActive,
            ImGuiCol.SliderGrab,
            ImGuiCol.SliderGrabActive
        }) {
            ImGui.pushStyleColor(color, 0);
        }
        ImGui.pushStyleVar(ImGuiStyleVar.FramePadding, 0, verticalPadding);
        ImGui.pushStyleVar(ImGuiStyleVar.FrameBorderSize, 0);
        ImGui.setNextItemWidth(width);
    }

    private static void endSliderTrack(float x, float y, float width, float fraction) {
        ImGui.popStyleVar(2);
        ImGui.popStyleColor(5);
        var left = x + 5;
        var right = x + width - 5;
        var centerY = y + (ImGui.getItemRectMaxY() - y) * .5f;
        var knobX = left + (right - left) * Math.clamp(fraction, 0, 1);
        var draw = ImGui.getWindowDrawList();
        draw.addRectFilled(left, centerY - 1.5f, right, centerY + 1.5f, BORDER, 1.5f);
        draw.addRectFilled(left, centerY - 1.5f, knobX, centerY + 1.5f, ACCENT, 1.5f);
        draw.addCircleFilled(knobX, centerY, 4, TEXT, 20);
    }

    private static boolean button(
            String id,
            Glyph glyph,
            String label,
            float width,
            float height,
            boolean accent,
            boolean outlined,
            boolean alignLeft) {
        return button(id, glyph, label, width, height, accent, outlined, alignLeft, Math.min(18f, height - 10f), TEXT);
    }

    private static boolean button(
            String id,
            Glyph glyph,
            String label,
            float width,
            float height,
            boolean accent,
            boolean outlined,
            boolean alignLeft,
            float iconSize,
            int iconColor) {
        return button(id, glyph, label, width, height, accent, outlined, alignLeft, iconSize, iconColor, null);
    }

    private static boolean button(
            String id,
            Glyph glyph,
            String label,
            float width,
            float height,
            boolean accent,
            boolean outlined,
            boolean alignLeft,
            float iconSize,
            int iconColor,
            @Nullable Glyph key) {
        if (width < 0) {
            width = ImGui.getContentRegionAvailX();
        }
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        ImGui.pushStyleColor(ImGuiCol.Button, 0);
        ImGui.pushStyleColor(ImGuiCol.ButtonHovered, 0);
        ImGui.pushStyleColor(ImGuiCol.ButtonActive, 0);
        ImGui.pushStyleVar(ImGuiStyleVar.FrameBorderSize, 0);
        var pressed = ImGui.button("##" + id, width, height);
        ImGui.popStyleVar();
        ImGui.popStyleColor(3);
        var draw = ImGui.getWindowDrawList();
        var item = ImGui.getItemID();
        var hover = Motion.toggle(item, ImGui.isItemHovered() || ImGui.isItemActive(), Motion.QUICK);
        var held = Motion.toggle(item + 1, ImGui.isItemActive(), .06f);
        var lit = Motion.toggle(item + 2, accent, Motion.QUICK);
        var rest = Motion.mix(outlined ? OUTLINED_FILL : Motion.alpha(HOVER, 0), ACCENT_FILL, lit);
        var surface = Motion.mix(Motion.mix(rest, HOVER, hover), PRESSED, held * .7f);
        if ((surface >>> 24) > 0) {
            draw.addRectFilled(x, y, x + width, y + height, fade(surface), 6f);
        }
        ripple(draw, item, x, y, width, height);
        if (outlined) {
            draw.addRect(x, y, x + width, y + height, fade(Motion.mix(BORDER, ACCENT, lit)), 6, 0, 1);
        }
        var textWidth = ImGui.calcTextSizeX(label);
        var iconSpace = iconSize + 8f;
        var keySpace = GeodataWidgets.KEYCAP_ICON + 8f;
        if (key != null && textWidth + (glyph == null ? 0 : iconSpace) + keySpace + 16 > width) {
            key = null;
        }
        if (glyph != null && !label.isEmpty() && textWidth + iconSpace + 16 > width) {
            glyph = null;
        }
        if (!label.isEmpty() && textWidth + 16 > width) {
            label = truncateToWidth(label, width - 16);
            textWidth = ImGui.calcTextSizeX(label);
        }
        var content =
                textWidth + (glyph == null ? 0 : label.isEmpty() ? iconSize : iconSpace) + (key == null ? 0 : keySpace);
        var left = x + (alignLeft ? 10 : (width - content) * .5f);
        if (glyph != null) {
            glyph(draw, glyph, left, y + (height - iconSize) * .5f, iconSize, Motion.mix(iconColor, ACCENT, lit));
        }
        if (!label.isEmpty()) {
            draw.addText(
                    left + (glyph == null ? 0 : iconSpace),
                    y + (height - ImGui.getFontSize()) * .5f - 1,
                    fade(TEXT),
                    label);
        }
        if (key != null) {
            GeodataWidgets.keycap(
                    draw,
                    left + content - GeodataWidgets.KEYCAP_ICON,
                    y + (height - ImGui.getFontSize() - 4) * .5f,
                    key);
        }
        return pressed;
    }

    private static int fade(int color) {
        return (color & 0x00ffffff) | ((int) ((color >>> 24) * ImGui.getStyle().getAlpha()) << 24);
    }
}
