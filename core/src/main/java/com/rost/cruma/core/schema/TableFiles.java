package com.rost.cruma.core.schema;

import com.rost.cruma.core.unreal.UDecryptor;
import com.rost.cruma.core.world.WorldVariant;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

public final class TableFiles {
    private static final String LOCALIZATION = "Localization.ini";
    private static final String LANGUAGE_SET = "languageset";

    private TableFiles() {}

    public static @Nullable Path find(Path system, WorldVariant variant, String name) {
        var file = variant.find(system, name);
        if (file != null) {
            return file;
        }
        var language = findLanguageFolder(system);
        return language == null ? null : variant.find(language, name);
    }

    public static boolean hasOwnTables(Path system, WorldVariant variant) {
        if (variant == WorldVariant.MAIN) {
            return true;
        }
        var suffix = variant.getTableSuffix().toLowerCase(Locale.ROOT);
        for (var folder : getFolders(system)) {
            if (!Files.isDirectory(folder)) {
                continue;
            }
            try (var files = Files.list(folder)) {
                var found = files.anyMatch(file -> {
                    var name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                    var at = name.lastIndexOf(suffix);
                    return name.endsWith(".dat")
                            && at > 0
                            && (name.startsWith(".", at + suffix.length())
                                    || name.startsWith("-", at + suffix.length()));
                });
                if (found) {
                    return true;
                }
            } catch (IOException | RuntimeException unreadable) {
            }
        }
        return false;
    }

    public static List<Path> getFolders(Path system) {
        var language = findLanguageFolder(system);
        return language == null ? List.of(system) : List.of(system, language);
    }

    private static @Nullable Path findLanguageFolder(Path system) {
        var ini = WorldVariant.MAIN.find(system, LOCALIZATION);
        if (ini == null) {
            return null;
        }
        Map<String, Map<String, String>> sections;
        try {
            var buffer = UDecryptor.decrypt(ini);
            var bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            sections = sections(
                    bytes.length > 1 && bytes[1] == 0
                            ? new String(bytes, StandardCharsets.UTF_16LE)
                            : new String(bytes, StandardCharsets.ISO_8859_1));
        } catch (RuntimeException unreadable) {
            return null;
        }
        var set = sections.getOrDefault(LANGUAGE_SET, Map.of());
        var name = set.get(set.getOrDefault("language", "").toLowerCase(Locale.ROOT));
        if (name == null) {
            return null;
        }
        var key = name.toLowerCase(Locale.ROOT);
        var code = sections.getOrDefault(key, Map.of()).get(key + "_code");
        if (code == null || code.isBlank() || !code.chars().allMatch(Character::isLetterOrDigit)) {
            return null;
        }
        var folder = system.resolve(code);
        return Files.isDirectory(folder) ? folder : null;
    }

    private static Map<String, Map<String, String>> sections(String text) {
        var sections = new HashMap<String, Map<String, String>>();
        var section = new HashMap<String, String>();
        for (var raw : text.split("\\R")) {
            var line = raw.strip();
            if (line.startsWith("[") && line.endsWith("]")) {
                section = new HashMap<>();
                sections.putIfAbsent(
                        line.substring(1, line.length() - 1).strip().toLowerCase(Locale.ROOT), section);
                continue;
            }
            var equals = line.indexOf('=');
            if (equals > 0 && !line.startsWith(";")) {
                section.putIfAbsent(
                        line.substring(0, equals).strip().toLowerCase(Locale.ROOT),
                        line.substring(equals + 1).strip());
            }
        }
        return sections;
    }
}
