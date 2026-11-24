package com.rost.cruma.viewer.agent.edit;

import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.core.editing.LightColor;
import com.rost.cruma.viewer.agent.ToolArgs;
import com.rost.cruma.viewer.agent.ToolProblem;
import com.rost.cruma.viewer.editing.LightColors;
import java.util.List;
import java.util.Locale;

final class LightPaint {
    private static final List<String> KEYS =
            List.of("brightness", "hue", "saturation", "saturation_pct", "temperature", "preset");

    private LightPaint() {}

    static boolean isAsked(ToolArgs args) {
        for (var key : KEYS) {
            if (args.has(key)) {
                return true;
            }
        }
        return false;
    }

    static LightColor of(ToolArgs args, LightColor from) throws ToolProblem {
        var hues = args.has("hue") || args.has("saturation") || args.has("saturation_pct");
        if (args.has("saturation") && args.has("saturation_pct")) {
            throw new ToolProblem("Give saturation or saturation_pct, not both.");
        }
        var color = from;
        var presetName = args.getText("preset");
        if (presetName != null) {
            if (hues || args.has("temperature")) {
                throw new ToolProblem(
                        "A preset sets the whole colour. Give preset, temperature or hue and saturation, one of them.");
            }
            var preset = LightColors.preset(presetName);
            if (preset == null) {
                throw new ToolProblem("preset must be one of " + names() + ".");
            }
            color = preset.color();
        }
        var kelvin = args.getNumber("temperature");
        if (kelvin != null) {
            if (hues) {
                throw new ToolProblem(
                        "temperature sets hue and saturation. Give temperature or hue and saturation, not both.");
            }
            if (kelvin < LightColors.WARMEST || kelvin > LightColors.COOLEST) {
                throw new ToolProblem("temperature must be between "
                        + (int) LightColors.WARMEST
                        + " and "
                        + (int) LightColors.COOLEST
                        + " kelvin.");
            }
            color = LightColors.temperatureColor(kelvin.floatValue(), color.brightness());
        }
        var hue = args.getInteger("hue", color.hue());
        var saturation = args.getInteger("saturation", color.saturation());
        var percent = args.getNumber("saturation_pct");
        if (percent != null) {
            if (percent < 0 || percent > 100) {
                throw new ToolProblem("saturation_pct must be between 0, white, and 100, the full colour.");
            }
            saturation = LightColors.saturationOf(percent.floatValue());
        }
        var brightness = args.getNumber("brightness", color.brightness());
        if (brightness < 0 || brightness > LightColor.MAX_BRIGHTNESS) {
            throw new ToolProblem("brightness must be between 0 and " + (int) LightColor.MAX_BRIGHTNESS + ".");
        }
        if (hue < 0 || hue > 255 || saturation < 0 || saturation > 255) {
            throw new ToolProblem("hue and saturation must be between 0 and 255.");
        }
        return new LightColor((float) brightness, hue, saturation);
    }

    static void write(LightColor color, JSONObject row) {
        row.put("brightness", EditSteps.round(color.brightness()));
        row.put("hue", color.hue());
        row.put("saturation", color.saturation());
        row.put("saturation_pct", Math.round(LightColors.saturationPercent(color.saturation())));
        var kelvin = LightColors.temperature(color.hue(), color.saturation());
        if (!Float.isNaN(kelvin)) {
            row.put("temperature", Math.round(kelvin));
        }
        row.put("colour", LightColors.colorName(color));
    }

    private static String names() {
        var names = new StringBuilder();
        for (var preset : LightColors.PRESETS) {
            if (!names.isEmpty()) {
                names.append(", ");
            }
            names.append(preset.name().toLowerCase(Locale.ROOT));
        }
        return names.toString();
    }
}
