package com.rost.cruma.core.world;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.jetbrains.annotations.Nullable;

final class ReflectionCoordinateText {
    static final String P152_HASH = "d844df6af225755a1e2190194756f8252f2ea279d02b9317196ff5ad48cd48bf";

    static final String SPECULAR_POINT_HASH = "1d0465dc61263b48d0c1c6d8b30cfc6c4db652d861f8ec145e7b4a42851292ee";

    static final String POINT_BLOCK = "cameraspacereflection && !cubemap";

    private static final String FUNCTION = "void CalcMaterialTexCoord(";
    private static final String BEAST_LIGHT_MAP = "BeastLightMapIntensity";
    private static final String END = "#endif";

    private ReflectionCoordinateText() {}

    static boolean matchesP152(Path clientRoot) {
        return matches(clientRoot.resolve("system/Predefinedshader.bin"), P152_HASH);
    }

    static boolean replacesReflectionWithoutLight(Path clientRoot) {
        var text = readText(clientRoot.resolve("system/Predefinedshader.bin"));
        return text != null && replacesReflectionWithoutLight(text, POINT_BLOCK, SPECULAR_POINT_HASH);
    }

    static boolean replacesReflectionWithoutLight(String text, String anchor, String expectedHash) {
        return text.contains(BEAST_LIGHT_MAP) && matchesBlocks(text, anchor, expectedHash);
    }

    static boolean matchesBlocks(String text, String anchor, String expectedHash) {
        var found = false;
        for (var start = text.indexOf(anchor); start >= 0; start = text.indexOf(anchor, start + 1)) {
            var end = text.indexOf(END, start);
            if (end < 0
                    || !computeHash(withoutWhiteSpace(text.substring(start, end + END.length())))
                            .equals(expectedHash)) {
                return false;
            }
            found = true;
        }
        return found;
    }

    static boolean matches(Path file, String expectedHash) {
        var text = readText(file);
        if (text == null) {
            return false;
        }
        var found = false;
        for (var start = text.indexOf(FUNCTION); start >= 0; start = text.indexOf(FUNCTION, start + 1)) {
            var body = functionBody(text, start);
            if (body == null || !computeHash(body).equals(expectedHash)) {
                return false;
            }
            found = true;
        }
        return found;
    }

    private static @Nullable String readText(Path file) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
        } catch (IOException missing) {
            return null;
        }
    }

    private static @Nullable String functionBody(String text, int start) {
        var open = text.indexOf('{', start);
        if (open < 0) {
            return null;
        }
        var body = new StringBuilder();
        var depth = 0;
        for (var index = open; index < text.length(); index++) {
            var character = text.charAt(index);
            if (character == '{') {
                depth++;
            } else if (character == '}') {
                depth--;
            }
            if (!isWhiteSpace(character)) {
                body.append(character);
            }
            if (depth == 0) {
                return body.toString();
            }
        }
        return null;
    }

    private static String withoutWhiteSpace(String text) {
        var result = new StringBuilder(text.length());
        for (var index = 0; index < text.length(); index++) {
            if (!isWhiteSpace(text.charAt(index))) {
                result.append(text.charAt(index));
            }
        }
        return result.toString();
    }

    private static boolean isWhiteSpace(char character) {
        return character == ' '
                || character == '\t'
                || character == '\r'
                || character == '\n'
                || character == '\f'
                || character == 0x0b;
    }

    private static String computeHash(String body) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(body.getBytes(StandardCharsets.ISO_8859_1)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
