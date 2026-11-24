package com.rost.cruma.viewer.ui;

import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiStyleVar;

public final class HudTheme {
    private static final int COLORS = 32;
    private static final int VARS = 14;

    private HudTheme() {}

    public static void push(float padX, float padY) {
        ImGui.pushStyleColor(ImGuiCol.WindowBg, 23 / 255f, 31 / 255f, 39 / 255f, 1f);
        ImGui.pushStyleColor(ImGuiCol.ChildBg, 0.0f, 0.0f, 0.0f, 0.0f);
        ImGui.pushStyleColor(ImGuiCol.PopupBg, 0.125f, 0.165f, 0.212f, 1f);
        ImGui.pushStyleColor(ImGuiCol.Border, 66 / 255f, 84 / 255f, 99 / 255f, .9f);
        ImGui.pushStyleColor(ImGuiCol.Text, 231 / 255f, 238 / 255f, 246 / 255f, 1f);
        ImGui.pushStyleColor(ImGuiCol.TextDisabled, 0.64f, 0.70f, 0.76f, 1.0f);
        ImGui.pushStyleColor(ImGuiCol.FrameBg, 1.0f, 1.0f, 1.0f, 0.055f);
        ImGui.pushStyleColor(ImGuiCol.FrameBgHovered, 0.47f, 0.84f, 1.0f, 0.14f);
        ImGui.pushStyleColor(ImGuiCol.FrameBgActive, 0.47f, 0.84f, 1.0f, 0.22f);
        ImGui.pushStyleColor(ImGuiCol.SliderGrab, 0.47f, 0.84f, 1.0f, 0.90f);
        ImGui.pushStyleColor(ImGuiCol.SliderGrabActive, 0.65f, 0.91f, 1.0f, 1.0f);
        ImGui.pushStyleColor(ImGuiCol.CheckMark, 0.65f, 0.91f, 1.0f, 1.0f);
        ImGui.pushStyleColor(ImGuiCol.Button, 27 / 255f, 37 / 255f, 46 / 255f, 1f);
        ImGui.pushStyleColor(ImGuiCol.ButtonHovered, 0.47f, 0.84f, 1.0f, 0.22f);
        ImGui.pushStyleColor(ImGuiCol.ButtonActive, 0.47f, 0.84f, 1.0f, 0.34f);
        ImGui.pushStyleColor(ImGuiCol.Header, 0.47f, 0.84f, 1.0f, 0.16f);
        ImGui.pushStyleColor(ImGuiCol.HeaderHovered, 0.47f, 0.84f, 1.0f, 0.26f);
        ImGui.pushStyleColor(ImGuiCol.HeaderActive, 0.47f, 0.84f, 1.0f, 0.34f);
        ImGui.pushStyleColor(ImGuiCol.Separator, 0.47f, 0.84f, 1.0f, 0.20f);
        ImGui.pushStyleColor(ImGuiCol.ScrollbarBg, 0.0f, 0.0f, 0.0f, 0.0f);
        ImGui.pushStyleColor(ImGuiCol.ScrollbarGrab, 0.47f, 0.84f, 1.0f, 0.22f);
        ImGui.pushStyleColor(ImGuiCol.ScrollbarGrabHovered, 0.47f, 0.84f, 1.0f, 0.40f);
        ImGui.pushStyleColor(ImGuiCol.Tab, .09f, .118f, .153f, 1f);
        ImGui.pushStyleColor(ImGuiCol.TabHovered, .18f, .29f, .36f, 1f);
        ImGui.pushStyleColor(ImGuiCol.TabSelected, .18f, .29f, .36f, 1f);
        ImGui.pushStyleColor(ImGuiCol.TabSelectedOverline, .56f, .87f, .95f, 1f);
        ImGui.pushStyleColor(ImGuiCol.TabDimmed, .09f, .118f, .153f, 1f);
        ImGui.pushStyleColor(ImGuiCol.TabDimmedSelected, .16f, .23f, .29f, 1f);
        ImGui.pushStyleColor(ImGuiCol.TabDimmedSelectedOverline, .56f, .87f, .95f, 1f);
        ImGui.pushStyleColor(ImGuiCol.NavCursor, .47f, .84f, 1f, 1f);
        ImGui.pushStyleColor(ImGuiCol.TitleBg, .09f, .122f, .153f, 1f);
        ImGui.pushStyleColor(ImGuiCol.TitleBgActive, .09f, .122f, .153f, 1f);

        ImGui.pushStyleVar(ImGuiStyleVar.WindowRounding, 9f);
        ImGui.pushStyleVar(ImGuiStyleVar.ChildRounding, 7f);
        ImGui.pushStyleVar(ImGuiStyleVar.FrameRounding, 6f);
        ImGui.pushStyleVar(ImGuiStyleVar.GrabRounding, 5f);
        ImGui.pushStyleVar(ImGuiStyleVar.PopupRounding, 6f);
        ImGui.pushStyleVar(ImGuiStyleVar.ScrollbarSize, 6f);
        ImGui.pushStyleVar(ImGuiStyleVar.ScrollbarRounding, 3f);
        ImGui.pushStyleVar(ImGuiStyleVar.ScrollbarPadding, 2f);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowBorderSize, 1f);
        ImGui.pushStyleVar(ImGuiStyleVar.FrameBorderSize, 1f);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, padX, padY);
        ImGui.pushStyleVar(ImGuiStyleVar.FramePadding, 8f, 4f);
        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, 7f, 6f);
        ImGui.pushStyleVar(ImGuiStyleVar.GrabMinSize, 10f);
    }

    public static void pop() {
        ImGui.popStyleVar(VARS);
        ImGui.popStyleColor(COLORS);
    }
}
