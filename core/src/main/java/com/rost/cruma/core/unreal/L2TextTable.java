package com.rost.cruma.core.unreal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import org.jetbrains.annotations.Nullable;

public final class L2TextTable {
    private static final int HEADER_BYTES = "Lineage2Ver211".length() * 2;
    private static final int TRAILER_BYTES = 20;
    private static final int BLOCK_BYTES = 8;
    private static final Map<String, String> KEYS = Map.of(
            "Lineage2Ver211", "31==-%&@!^+][;'.]94-",
            "Lineage2Ver212", "[;'.]94-&@%!^+]-31==");

    private L2TextTable() {}

    public static List<Map<String, String>> read(Path path) throws IOException {
        return parse(readText(path));
    }

    public static String readText(Path path) throws IOException {
        return decode(Files.readAllBytes(path), path.toString());
    }

    static String decode(byte[] data, String source) throws IOException {
        if (L2DataFile.isDataFile(data)) {
            return toText(L2DataFile.decode(data, source));
        }
        var header = data.length < HEADER_BYTES ? "" : new String(data, 0, HEADER_BYTES, StandardCharsets.UTF_16LE);
        var key = KEYS.get(header);
        if (key == null || data.length < HEADER_BYTES + TRAILER_BYTES) {
            throw new IOException(source + " is not a Lineage2Ver211, Lineage2Ver212 or Lineage2Ver411 file");
        }
        var text = Arrays.copyOfRange(data, HEADER_BYTES, data.length - TRAILER_BYTES);
        var whole = text.length / BLOCK_BYTES * BLOCK_BYTES;
        try {
            var cipher = Cipher.getInstance("Blowfish/ECB/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    new SecretKeySpec((key + "\0").getBytes(StandardCharsets.US_ASCII), "Blowfish"));
            swapWords(text, whole);
            cipher.doFinal(text, 0, whole, text, 0);
            swapWords(text, whole);
        } catch (GeneralSecurityException failure) {
            throw new IOException(source + " cannot be decrypted", failure);
        }
        return toText(text);
    }

    static List<Map<String, String>> parse(String text) {
        var records = new ArrayList<Map<String, String>>();
        for (var line : text.split("\r?\n")) {
            var fields = line.split("\t");
            if (fields.length == 0 || !fields[0].strip().endsWith("_begin")) {
                continue;
            }
            var record = new LinkedHashMap<String, String>();
            for (var i = 1; i < fields.length; i++) {
                var field = fields[i];
                var equals = field.indexOf('=');
                if (equals > 0) {
                    record.putIfAbsent(
                            field.substring(0, equals).strip(),
                            field.substring(equals + 1).strip());
                }
            }
            records.add(record);
        }
        return records;
    }

    public static String name(String value) {
        var trimmed = value.strip();
        if (trimmed.length() >= 2 && trimmed.startsWith("[") && trimmed.endsWith("]")) {
            return trimmed.substring(1, trimmed.length() - 1).strip();
        }
        return trimmed;
    }

    public static List<String> names(String value) {
        var trimmed = value.strip();
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        var names = new ArrayList<String>();
        for (var part : trimmed.split(";")) {
            var name = name(part);
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return names;
    }

    public static List<String> references(@Nullable String value) {
        if (value == null) {
            return List.of();
        }
        var named = new ArrayList<String>();
        for (var name : names(value)) {
            if (!name.isBlank() && !name.equalsIgnoreCase("none")) {
                named.add(name);
            }
        }
        return List.copyOf(named);
    }

    public static int integer(Map<String, String> record, String field, int fallback) {
        var value = record.get(field);
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.strip());
        } catch (NumberFormatException notANumber) {
            return fallback;
        }
    }

    private static String toText(byte[] text) {
        if (text.length >= 2 && (text[0] & 0xff) == 0xff && (text[1] & 0xff) == 0xfe) {
            return new String(text, 2, text.length - 2, StandardCharsets.UTF_16LE);
        }
        return new String(text, StandardCharsets.ISO_8859_1);
    }

    private static void swapWords(byte[] data, int length) {
        for (var i = 0; i + 4 <= length; i += 4) {
            var first = data[i];
            var second = data[i + 1];
            data[i] = data[i + 3];
            data[i + 1] = data[i + 2];
            data[i + 2] = second;
            data[i + 3] = first;
        }
    }
}
