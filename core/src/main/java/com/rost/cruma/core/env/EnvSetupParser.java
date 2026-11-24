package com.rost.cruma.core.env;

import com.rost.cruma.core.unreal.UDecryptor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;

public final class EnvSetupParser {
    private EnvSetupParser() {}

    public static EnvSetup parse(Path path) {
        return parseText(decrypt(path));
    }

    public static List<String> timeEnvFiles(Path path, String family) {
        return timeEnvFiles(decrypt(path), family);
    }

    static List<String> timeEnvFiles(String text, String family) {
        var values = readValues(text);
        var prefix = family.toLowerCase(Locale.ROOT) + "envfilename";
        var count = leadingInt(values.getOrDefault(family.toLowerCase(Locale.ROOT) + "envfilenum", "0"));
        var files = new ArrayList<String>();
        for (var i = 0; i < count; i++) {
            var name = values.get(prefix + i);
            if (name != null && !name.isBlank()) {
                files.add(name);
            }
        }
        return List.copyOf(files);
    }

    static EnvSetup parseText(String text) {
        var values = readValues(text);
        var cloudCount = leadingInt(values.getOrDefault("numcloudcolor", "0"));
        var clouds = new ArrayList<String>();
        for (var i = 1; i <= cloudCount; i++) {
            var name = values.get("cloud" + i);
            if (name != null && !name.isBlank()) {
                clouds.add(name);
            }
        }
        if (cloudCount <= 0) {
            values.entrySet().stream()
                    .filter(entry -> cloudIndex(entry.getKey()) > 0)
                    .sorted(Comparator.comparingInt(entry -> cloudIndex(entry.getKey())))
                    .map(Entry::getValue)
                    .filter(name -> !name.isBlank())
                    .forEach(clouds::add);
        }
        if (clouds.isEmpty() && !values.getOrDefault("cloud", "").isBlank()) {
            clouds.add(values.get("cloud"));
        }
        return new EnvSetup(
                values.getOrDefault("skybox", ""),
                values.getOrDefault("hazering", ""),
                clouds,
                floatValue(values.get("slopesunangle"), 0f),
                floatValue(values.get("shadowtick"), -1f));
    }

    private static String decrypt(Path path) {
        var buffer = UDecryptor.decrypt(path);
        var bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    private static Map<String, String> readValues(String text) {
        var values = new LinkedHashMap<String, String>();
        var inSetup = false;
        for (var rawLine : text.split("\\R", -1)) {
            var line = rawLine.trim();
            if (line.startsWith("[") && line.endsWith("]")) {
                if (inSetup) {
                    break;
                }
                inSetup = "[envsetup]".equals(line.toLowerCase(Locale.ROOT));
                continue;
            }
            if (!inSetup || line.isEmpty() || line.startsWith(";")) {
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
            var key = line.substring(0, equals).trim().toLowerCase(Locale.ROOT);
            var value = line.substring(equals + 1).trim();
            values.putIfAbsent(key, value);
        }
        if (!inSetup && values.isEmpty()) {
            throw new IllegalStateException("EnvSetup section not found");
        }
        return values;
    }

    private static int cloudIndex(String key) {
        if (!key.startsWith("cloud") || key.length() == "cloud".length()) {
            return -1;
        }
        return leadingInt(key.substring("cloud".length()));
    }

    private static float floatValue(String value, float fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Float.parseFloat(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static int leadingInt(String value) {
        var result = 0;
        var found = false;
        for (var i = 0; i < value.length() && Character.isDigit(value.charAt(i)); i++) {
            result = result * 10 + value.charAt(i) - '0';
            found = true;
        }
        return found ? result : 0;
    }
}
