package com.rost.cruma.viewer.ui;

import com.rost.cruma.viewer.application.UiContext.Rendering;
import com.rost.cruma.viewer.render.Camera;
import imgui.ImGui;
import imgui.flag.ImGuiInputTextFlags;
import imgui.flag.ImGuiStyleVar;
import imgui.type.ImString;
import java.util.Locale;

public final class OrthoGridControls {
    private static final float FIELD_WIDTH = 84f;
    private static final float LABEL_WIDTH = 56f;
    private static final String AUTO = "Auto";
    private static final ImString TYPED = new ImString(24);
    private static boolean editing;

    private OrthoGridControls() {}

    public static void draw(Rendering r, Camera camera, String gridTip) {
        r.orthoGrid = HudWidgets.switchRow("Grid", r.orthoGrid, true, gridTip);
        GeodataWidgets.space(GeodataWidgets.ROW);
        var left = ImGui.getCursorScreenPosX();
        ImGui.beginDisabled(r.orthoGridStep > 0);
        ImGui.alignTextToFramePadding();
        ImGui.textColored(HudWidgets.MUTED, "Density");
        ImGui.sameLine(0, 0);
        ImGui.setCursorScreenPos(left + LABEL_WIDTH, ImGui.getCursorScreenPosY());
        var chosen = GeodataWidgets.segmented("orthoGridDensity", OrthoGridSteps.DENSITY_LABELS, r.orthoGridDensity);
        if (chosen >= 0) {
            r.orthoGridDensity = chosen;
        }
        GeodataWidgets.tip(
                "How close the lines stand on the screen. The step follows the zoom and stays a round number of world"
                        + " units.");
        ImGui.endDisabled();
        GeodataWidgets.space(GeodataWidgets.ROW);
        ImGui.pushStyleVar(ImGuiStyleVar.FramePadding, 8, 3);
        ImGui.alignTextToFramePadding();
        ImGui.textColored(HudWidgets.MUTED, "Step");
        ImGui.sameLine(0, 0);
        ImGui.setCursorScreenPos(left + LABEL_WIDTH, ImGui.getCursorScreenPosY());
        if (!editing) {
            TYPED.set(r.orthoGridStep > 0 ? Integer.toString(r.orthoGridStep) : AUTO);
        }
        ImGui.setNextItemWidth(FIELD_WIDTH);
        ImGui.inputText("##orthoGridStep", TYPED, ImGuiInputTextFlags.CharsDecimal | ImGuiInputTextFlags.AutoSelectAll);
        if (ImGui.isItemDeactivatedAfterEdit()) {
            r.orthoGridStep = OrthoGridSteps.parseStep(TYPED.get());
        }
        editing = ImGui.isItemActive();
        GeodataWidgets.tip(
                "A line at every this many world units, at any zoom. An empty field gives the density back.");
        ImGui.popStyleVar();
        GeodataWidgets.space(GeodataWidgets.ROW);
        GeodataWidgets.hint(describe(r, camera));
    }

    private static String describe(Rendering r, Camera camera) {
        var screen = ImGui.getIO().getDisplaySizeX();
        var shown = OrthoGrid.step(camera, screen, r.orthoGridDensity, r.orthoGridStep);
        var every = String.format(Locale.ROOT, "%,d", shown);
        if (r.orthoGridStep > 0 && shown != r.orthoGridStep) {
            return "A step of " + String.format(Locale.ROOT, "%,d", r.orthoGridStep)
                    + " is too dense at this zoom, so the lines stand every " + every
                    + " world units. Zoom in to see them all.";
        }
        return "The lines stand every " + every + " world units at this zoom, and every fifth is stronger.";
    }
}
