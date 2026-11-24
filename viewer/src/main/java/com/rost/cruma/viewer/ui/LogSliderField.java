package com.rost.cruma.viewer.ui;

import imgui.ImGui;
import imgui.flag.ImGuiInputTextFlags;
import imgui.flag.ImGuiStyleVar;
import imgui.type.ImString;
import java.util.Locale;

public final class LogSliderField {
    public static final float TRACK_WIDTH = 112f;

    public static final float FIELD_WIDTH = 60f;

    private static final float GAP = GeodataWidgets.ROW;
    private final String id;
    private final String label;
    private final float min;
    private final float max;
    private final String unit;
    private final String labelTip;
    private final String sliderTip;
    private final String fieldTip;
    private final ImString typed = new ImString(24);
    private boolean editing;

    public LogSliderField(
            String id,
            String label,
            float min,
            float max,
            String unit,
            String labelTip,
            String sliderTip,
            String fieldTip) {
        if (!(min > 0) || !(max > min)) {
            throw new IllegalArgumentException("A log slider needs 0 < min < max: " + min + ", " + max);
        }
        this.id = id;
        this.label = label;
        this.min = min;
        this.max = max;
        this.unit = unit;
        this.labelTip = labelTip;
        this.sliderTip = sliderTip;
        this.fieldTip = fieldTip;
    }

    public static float width(float labelWidth) {
        return labelWidth + GAP + TRACK_WIDTH + GAP + FIELD_WIDTH;
    }

    public static float parse(String typed, float previous, float min, float max) {
        var text = typed.trim();
        while (!text.isEmpty()
                && !Character.isDigit(text.charAt(text.length() - 1))
                && text.charAt(text.length() - 1) != '.') {
            text = text.substring(0, text.length() - 1).trim();
        }
        try {
            var number = Float.parseFloat(text);
            return Float.isFinite(number) ? Math.clamp(number, min, max) : previous;
        } catch (NumberFormatException invalid) {
            return previous;
        }
    }

    public boolean draw(float[] value, float labelWidth) {
        var x = ImGui.getCursorPosX();
        var y = ImGui.getCursorPosY();
        ImGui.pushStyleVar(ImGuiStyleVar.FramePadding, 8, 7);
        ImGui.alignTextToFramePadding();
        ImGui.textColored(HudWidgets.MUTED, label);
        GeodataWidgets.tip(labelTip);
        ImGui.sameLine(0, 0);
        ImGui.setCursorPos(x + labelWidth + GAP, y + 4);
        var changed = HudWidgets.inlineLogSlider("##" + id + "Slider", value, min, max, TRACK_WIDTH);
        GeodataWidgets.tip(sliderTip);
        ImGui.sameLine(0, GAP);
        ImGui.setCursorPosY(y);
        if (!editing) {
            typed.set(format(value[0]));
        }
        ImGui.setNextItemWidth(FIELD_WIDTH);
        ImGui.inputText(
                "##" + id + "Value", typed, ImGuiInputTextFlags.CharsDecimal | ImGuiInputTextFlags.AutoSelectAll);
        if (ImGui.isItemDeactivatedAfterEdit()) {
            var next = parse(typed.get(), value[0], min, max);
            changed |= Float.floatToIntBits(next) != Float.floatToIntBits(value[0]);
            value[0] = next;
        }
        editing = ImGui.isItemActive();
        GeodataWidgets.tip(fieldTip);
        ImGui.popStyleVar();
        return changed;
    }

    public String format(float value) {
        return String.format(Locale.ROOT, "%.0f", value) + unit;
    }
}
