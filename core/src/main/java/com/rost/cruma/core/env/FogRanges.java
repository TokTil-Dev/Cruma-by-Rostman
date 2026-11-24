package com.rost.cruma.core.env;

import com.rost.cruma.core.unreal.UDecryptor;
import glm_.vec4.Vec4;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.regex.Pattern;

public record FogRanges(float[] startRanges, float[] endRanges, UnderwaterFog underwater) {
    private static final int PRESETS = 5;
    private static final float RANGE_UNIT = 32_768f * 0.0625f;
    private static final float ALTITUDE_SCALE = 0.3f / 10_000f;

    public record UnderwaterFog(
            float red,
            float green,
            float blue,
            float alpha,
            float start,
            float end,
            float cellophaneRed,
            float cellophaneGreen,
            float cellophaneBlue,
            float cellophaneAlpha) {
        public UnderwaterFog(
                float red,
                float green,
                float blue,
                float start,
                float end,
                float cellophaneRed,
                float cellophaneGreen,
                float cellophaneBlue,
                float cellophaneAlpha) {
            this(red, green, blue, 1f, start, end, cellophaneRed, cellophaneGreen, cellophaneBlue, cellophaneAlpha);
        }
    }

    public record Range(float start, float end) {}

    public static Vec4 defaultCellophaneColor() {
        return new Vec4(1f, 1f, 1f, 128f / 255f);
    }

    public static FogRanges parse(Path envInt) {
        var buffer = UDecryptor.decrypt(envInt);
        var bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return parseText(new String(bytes, StandardCharsets.ISO_8859_1));
    }

    static FogRanges parseText(String text) {
        var values = new LinkedHashMap<String, String>();
        var underwater = new StringBuilder();
        var section = "";
        for (var rawLine : text.split("\\R", -1)) {
            var line = rawLine.trim();
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.toLowerCase(Locale.ROOT);
                continue;
            }
            var inFog = "[fog]".equals(section);
            if ("[watervolume]".equals(section) && !line.isEmpty() && !line.startsWith(";")) {
                underwater.append(line).append('\n');
            }
            if (!inFog || line.isEmpty() || line.startsWith(";")) {
                continue;
            }
            var comment = line.indexOf(';');
            if (comment >= 0) {
                line = line.substring(0, comment).trim();
            }
            var equals = line.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            values.putIfAbsent(
                    line.substring(0, equals).trim().toLowerCase(Locale.ROOT),
                    line.substring(equals + 1).trim());
        }
        var starts = new float[PRESETS];
        var ends = new float[PRESETS];
        for (var i = 0; i < PRESETS; i++) {
            starts[i] = parseFloat(values.get("startrange" + (i + 1)));
            ends[i] = parseFloat(values.get("endrange" + (i + 1)));
        }
        var water = underwater.toString();
        return new FogRanges(starts, ends, parseUnderwater(water));
    }

    public Range worldRange(int preset, float cameraHeight) {
        var index = Math.max(1, Math.min(PRESETS, preset)) - 1;
        var start = startRanges[index] * RANGE_UNIT;
        var end = endRanges[index] * RANGE_UNIT;
        if (cameraHeight > 0f) {
            var scale = cameraHeight * ALTITUDE_SCALE + 1f;
            start *= scale;
            end *= scale;
        }
        return new Range(start, end);
    }

    public boolean isUsable() {
        for (var i = 0; i < PRESETS; i++) {
            if (!(endRanges[i] > startRanges[i]) || startRanges[i] < 0f) {
                return false;
            }
        }
        return true;
    }

    private static UnderwaterFog parseUnderwater(String section) {
        var cellophanePresent = Pattern.compile("(?im)^WaterVolumeCellophaneColor\\s*=")
                .matcher(section)
                .find();
        return new UnderwaterFog(
                readComponent(section, "WaterVolumeFogColor", "R"),
                readComponent(section, "WaterVolumeFogColor", "G"),
                readComponent(section, "WaterVolumeFogColor", "B"),
                readComponent(section, "WaterVolumeFogColor", "A", 1f),
                readScalar(section, "WaterVolumeFogStart"),
                readScalar(section, "WaterVolumeFogEnd"),
                readComponent(section, "WaterVolumeCellophaneColor", "R", 1f),
                readComponent(section, "WaterVolumeCellophaneColor", "G", 1f),
                readComponent(section, "WaterVolumeCellophaneColor", "B", 1f),
                readComponent(section, "WaterVolumeCellophaneColor", "a", cellophanePresent ? 1f : 128f / 255f));
    }

    private static float readScalar(String section, String key) {
        var matcher = Pattern.compile("(?im)^" + key + "\\s*=\\s*([-\\d.]+)").matcher(section);
        return matcher.find() ? parseFloat(matcher.group(1)) : 0f;
    }

    private static float readComponent(String section, String key, String channel) {
        return readComponent(section, key, channel, 0f);
    }

    private static float readComponent(String section, String key, String channel, float fallback) {
        var matcher = Pattern.compile("(?im)^" + key + "\\s*=\\s*\\(([^)]*)\\)").matcher(section);
        if (!matcher.find()) {
            return fallback;
        }
        var inner = Pattern.compile("(?i)\\b" + channel + "\\s*=\\s*(\\d+)").matcher(matcher.group(1));
        return inner.find() ? Integer.parseInt(inner.group(1)) / 255f : fallback;
    }

    private static float parseFloat(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0f;
        }
        var end = 0;
        while (end < raw.length()
                && (Character.isDigit(raw.charAt(end))
                        || raw.charAt(end) == '.'
                        || raw.charAt(end) == '-'
                        || raw.charAt(end) == '+')) {
            end++;
        }
        try {
            return Float.parseFloat(raw.substring(0, end));
        } catch (RuntimeException e) {
            return 0f;
        }
    }
}
