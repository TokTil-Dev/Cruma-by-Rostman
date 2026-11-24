package com.rost.cruma.viewer.ui;

import com.rost.cruma.viewer.application.UiContext.Rendering;
import com.rost.cruma.viewer.config.FrameLimitSettings;
import com.rost.cruma.viewer.render.api.ViewerGraphicsConfig;
import imgui.ImGui;
import imgui.flag.ImGuiKey;
import imgui.type.ImFloat;

final class FrameLimitPopup {
    static final float WIDTH = 304;

    private static final String POPUP = "##frameLimit";
    private static final String[] LABELS =
            FrameLimitSettings.PRESETS.stream().map(String::valueOf).toArray(String[]::new);

    private final FontManager fonts;
    private final ImFloat typed = new ImFloat();
    private boolean refused;
    private boolean closeRequested;
    private boolean open;
    private boolean pressedWhileOpen;
    private final float[] buttonRect = new float[4];
    private final float[] presetsRect = new float[4];
    private final float[] fieldRect = new float[4];

    FrameLimitPopup(FontManager fonts) {
        this.fonts = fonts;
    }

    static String describe(int cap) {
        return "Frame limit: " + cap + " FPS · Click to change it\n"
                + "Completed frames per second. Open Performance · F4 for frame timings and details.";
    }

    boolean isOpen() {
        return open;
    }

    boolean close() {
        if (!isOpen()) {
            return false;
        }
        closeRequested = true;
        return true;
    }

    void drawButton(String text, int color, float width, int cap) {
        var clicked = HudWidgets.textButton("fps", text, color, width, HudWidgets.CONTROL_HEIGHT);
        keepItemRect(buttonRect);
        if (ImGui.isItemActivated()) {
            pressedWhileOpen = isOpen();
        }
        if (clicked) {
            if (!pressedWhileOpen) {
                typed.set(cap);
                refused = false;
                closeRequested = false;
                ImGui.openPopup(POPUP);
            }
            pressedWhileOpen = false;
        }
        if (ImGui.isItemHovered() && !isOpen()) {
            HudWidgets.showTooltip(describe(cap));
        }
    }

    void draw(Rendering rendering, float right, float bottom) {
        open = HudWidgets.beginPopupBelow(POPUP, WIDTH, right, bottom);
        if (!open) {
            return;
        }
        if (closeRequested || ImGui.isKeyPressed(ImGuiKey.Escape, false) && !ImGui.isAnyItemActive()) {
            closeRequested = false;
            open = false;
            ImGui.closeCurrentPopup();
        }
        ImGui.pushFont(fonts.getHeadingFont(15), 15f);
        ImGui.textUnformatted("Frame limit");
        ImGui.popFont();
        GeodataWidgets.space(GeodataWidgets.ROW);
        var cap = rendering.frameRateCap;
        var chosen = GeodataWidgets.segmented(
                "frameLimit/presets", LABELS, FrameLimitSettings.PRESETS.indexOf(cap), ImGui.getContentRegionAvailX());
        keepItemRect(presetsRect);
        GeodataWidgets.tip("At most this many frames a second. A lower limit gives the graphics card less work.");
        if (chosen >= 0) {
            choose(rendering, FrameLimitSettings.PRESETS.get(chosen));
        } else if (!ImGui.isAnyItemActive() && typed.get() != cap) {
            typed.set(cap);
        }
        GeodataWidgets.space(GeodataWidgets.ROW);
        var left = GeodataWidgets.decimal("Custom", typed, " FPS");
        keepItemRect(fieldRect);
        GeodataWidgets.tip("Any whole number from " + ViewerGraphicsConfig.MIN_FRAME_RATE_CAP + " to "
                + ViewerGraphicsConfig.MAX_FRAME_RATE_CAP + ". Enter or a click elsewhere keeps it.");
        if (left) {
            var number = typed.get();
            if (number == Math.rint(number) && FrameLimitSettings.isAllowed((long) number)) {
                choose(rendering, (int) number);
            } else {
                refused = true;
                typed.set(rendering.frameRateCap);
            }
        }
        if (refused) {
            GeodataWidgets.space(GeodataWidgets.ROW);
            GeodataWidgets.note(
                    "Type a whole number from " + ViewerGraphicsConfig.MIN_FRAME_RATE_CAP + " to "
                            + ViewerGraphicsConfig.MAX_FRAME_RATE_CAP + ".",
                    GeodataWidgets.WARNING);
        }
        if (rendering.stats.vsync) {
            GeodataWidgets.space(GeodataWidgets.ROW);
            GeodataWidgets.hint("Vertical sync is on: the display can hold it lower.");
        }
        ImGui.endPopup();
    }

    private void choose(Rendering rendering, int cap) {
        rendering.frameRateCap = cap;
        typed.set(cap);
        refused = false;
    }

    private static void keepItemRect(float[] rect) {
        rect[0] = ImGui.getItemRectMinX();
        rect[1] = ImGui.getItemRectMinY();
        rect[2] = ImGui.getItemRectMaxX();
        rect[3] = ImGui.getItemRectMaxY();
    }
}
