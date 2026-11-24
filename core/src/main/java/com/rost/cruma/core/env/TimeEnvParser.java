package com.rost.cruma.core.env;

import com.rost.cruma.core.unreal.UDecryptor;
import com.rost.cruma.core.world.MaterialClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import org.jetbrains.annotations.Nullable;

public final class TimeEnvParser {
    private TimeEnvParser() {}

    public static TimeEnvPreset parse(Path path) {
        return parse(path, MaterialClient.UNKNOWN);
    }

    public static TimeEnvPreset parse(Path path, MaterialClient client) {
        var buffer = UDecryptor.decrypt(path);
        var bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return parseText(new String(bytes, StandardCharsets.ISO_8859_1), client);
    }

    static TimeEnvPreset parseText(String text) {
        return parseText(text, MaterialClient.UNKNOWN);
    }

    static TimeEnvPreset parseText(String text, MaterialClient client) {
        var lines = text.split("\r\n", -1);
        var oneCloud = !hasSection(lines, "CloudColor1") && hasSection(lines, "CloudColor");
        var cloud = oneCloud ? parseColorSection(lines, "CloudColor") : null;
        return new TimeEnvPreset(
                hasSection(lines, "EnvType") ? parseScalar(lines, "EnvType") : 0,
                parseColorSection(lines, "SkyBoxColor"),
                parseColorSection(lines, "HazeringColor"),
                oneCloud ? cloud : parseColorSection(lines, "CloudColor1"),
                oneCloud ? cloud : parseColorSection(lines, "CloudColor2"),
                oneCloud ? cloud : parseColorSection(lines, "CloudColor3"),
                parseColorSection(lines, "SunColor"),
                parseColorSection(lines, "MoonColor"),
                parseScaleSection(lines, "SunScale"),
                parseScaleSection(lines, "MoonScale"),
                parseColorSection(lines, "TerrainAmbient"),
                parseColorSection(lines, "ActorAmbient"),
                parseColorSection(lines, "StaticMeshAmbient"),
                parseColorSection(lines, "BSPAmbient"),
                parseHsvSection(lines, "HSVTerrainLight", client),
                parseHsvSection(lines, "HSVActorLight", client),
                parseHsvSection(lines, "HSVStaticMeshLight", client),
                parseHsvSection(lines, "HSVBSPLight", client),
                parseOptionalScaleSection(lines, "BeastLightMap1Scale"),
                parseOptionalScaleSection(lines, "BeastLightMap2Scale"),
                parseMoreClouds(lines));
    }

    private static TimeColorKey[][] parseMoreClouds(String[] lines) {
        var clouds = new ArrayList<TimeColorKey[]>();
        for (var index = 4; hasSection(lines, "CloudColor" + index); index++) {
            clouds.add(parseColorSection(lines, "CloudColor" + index));
        }
        return clouds.toArray(new TimeColorKey[0][]);
    }

    private static boolean hasSection(String[] lines, String name) {
        var header = "[" + name + "]";
        for (var line : lines) {
            if (isHeader(line, header)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isHeader(String line, String header) {
        return line.trim().startsWith(header);
    }

    private static TimeScaleKey @Nullable [] parseOptionalScaleSection(String[] lines, String name) {
        var header = "[" + name + "]";
        for (var line : lines) {
            if (isHeader(line, header)) {
                return parseScaleSection(lines, name);
            }
        }
        return null;
    }

    private static int parseScalar(String[] lines, String name) {
        var idx = nextValue(lines, findSection(lines, name));
        return leadingInt(parseValue(lines[idx]));
    }

    private static TimeColorKey[] parseColorSection(String[] lines, String name) {
        var idx = findSection(lines, name);
        idx = nextValue(lines, idx);
        var num = leadingInt(parseValue(lines[idx]));
        idx++;
        var keys = new TimeColorKey[num];
        for (var i = 0; i < num; i++) {
            idx = nextValue(lines, idx);
            keys[i] = parseColorTuple(lines[idx]);
            idx++;
        }
        return keys;
    }

    private static TimeScaleKey[] parseScaleSection(String[] lines, String name) {
        var idx = findSection(lines, name);
        idx = nextValue(lines, idx);
        var num = leadingInt(parseValue(lines[idx]));
        idx++;
        var keys = new TimeScaleKey[num];
        for (var i = 0; i < num; i++) {
            idx = nextValue(lines, idx);
            keys[i] = parseScaleTuple(lines[idx]);
            idx++;
        }
        return keys;
    }

    private static TimeHsvKey[] parseHsvSection(String[] lines, String name, MaterialClient client) {
        var idx = findSection(lines, name);
        idx = nextValue(lines, idx);
        var num = leadingInt(parseValue(lines[idx]));
        idx++;
        var keys = new TimeHsvKey[num];
        for (var i = 0; i < num; i++) {
            idx = nextValue(lines, idx);
            keys[i] = parseHsvTuple(lines[idx], i, client);
            idx++;
        }
        return keys;
    }

    private static TimeColorKey parseColorTuple(String line) {
        var t = 0;
        var r = 0;
        var g = 0;
        var b = 0;
        var a = 255;
        for (var param : tupleParams(line)) {
            var eq = param.indexOf('=');
            if (eq < 0) {
                continue;
            }
            var key = param.substring(0, eq).trim().toLowerCase();
            var raw = param.substring(eq + 1).trim();
            switch (key) {
                case "t" -> t = leadingInt(raw);
                case "r" -> r = leadingInt(raw);
                case "g" -> g = leadingInt(raw);
                case "b" -> b = leadingInt(raw);
                case "a" -> a = leadingInt(raw);
                default -> {}
            }
        }
        return new TimeColorKey(t, r, g, b, a);
    }

    private static TimeScaleKey parseScaleTuple(String line) {
        var t = 0;
        var s = 1f;
        for (var param : tupleParams(line)) {
            var eq = param.indexOf('=');
            if (eq < 0) {
                continue;
            }
            var key = param.substring(0, eq).trim().toLowerCase();
            var raw = param.substring(eq + 1).trim();
            switch (key) {
                case "t" -> t = leadingInt(raw);
                case "s" -> s = leadingFloat(raw);
                default -> {}
            }
        }
        return new TimeScaleKey(t, s);
    }

    private static TimeHsvKey parseHsvTuple(String line, int index, MaterialClient client) {
        var nativeDefaults = client != MaterialClient.UNKNOWN;
        var t = nativeDefaults ? (float) index : 0f;
        var hue = nativeDefaults ? 255 : 0;
        var sat = nativeDefaults ? 255 : 0;
        var bri = nativeDefaults ? 255f : 0f;
        for (var param : tupleParams(line)) {
            var eq = param.indexOf('=');
            if (eq < 0) {
                continue;
            }
            var key = param.substring(0, eq).trim().toLowerCase();
            var raw = param.substring(eq + 1).trim();
            switch (key) {
                case "t" -> t = leadingFloat(raw);
                case "hue" -> hue = leadingInt(raw);
                case "sat" -> sat = leadingInt(raw);
                case "bri" -> bri = leadingFloat(raw);
                default -> {}
            }
        }
        return new TimeHsvKey(t, nativeDefaults ? hue & 255 : hue, nativeDefaults ? sat & 255 : sat, bri);
    }

    private static String[] tupleParams(String line) {
        var open = line.indexOf('(');
        if (open < 0) {
            throw new IllegalStateException("Malformed TimeEnv tuple, no opening parenthesis: " + line);
        }
        var close = line.indexOf(')', open + 1);
        if (close < 0) {
            close = line.length();
        }
        return line.substring(open + 1, close).split(",");
    }

    private static int findSection(String[] lines, String name) {
        var header = "[" + name + "]";
        for (var i = 0; i < lines.length; i++) {
            if (isHeader(lines[i], header)) {
                return i + 1;
            }
        }
        throw new IllegalStateException("TimeEnv section not found: " + header);
    }

    private static int nextValue(String[] lines, int idx) {
        while (idx < lines.length) {
            var trimmed = lines[idx].trim();
            if (!trimmed.isEmpty() && trimmed.charAt(0) != ';') {
                return idx;
            }
            idx++;
        }
        throw new IllegalStateException("Unexpected end of TimeEnv file while reading a value");
    }

    private static String parseValue(String line) {
        var eq = line.indexOf('=');
        return eq < 0 ? "" : line.substring(eq + 1).trim();
    }

    private static int leadingInt(String s) {
        var i = 0;
        var n = s.length();
        var sign = 1;
        if (i < n && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
            if (s.charAt(i) == '-') {
                sign = -1;
            }
            i++;
        }
        var value = 0;
        var any = false;
        while (i < n && Character.isDigit(s.charAt(i))) {
            value = value * 10 + (s.charAt(i) - '0');
            any = true;
            i++;
        }
        return any ? sign * value : 0;
    }

    private static float leadingFloat(String s) {
        var i = 0;
        var n = s.length();
        var start = i;
        if (i < n && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
            i++;
        }
        var any = false;
        while (i < n && Character.isDigit(s.charAt(i))) {
            i++;
            any = true;
        }
        if (i < n && s.charAt(i) == '.') {
            i++;
            while (i < n && Character.isDigit(s.charAt(i))) {
                i++;
                any = true;
            }
        }
        return any ? Float.parseFloat(s.substring(start, i)) : 0f;
    }
}
