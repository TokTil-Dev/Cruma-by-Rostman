package com.rost.cruma.core.world;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public enum MaterialClient {
    UNKNOWN,
    P152,
    P162;

    public static MaterialClient detect(Path clientRoot) {
        var renderer = clientRoot.resolve("system/D3DDrv.dll");
        if (!Files.isRegularFile(renderer)) {
            return UNKNOWN;
        }
        try (var input = Files.newInputStream(renderer)) {
            var digest = MessageDigest.getInstance("SHA-256");
            var buffer = new byte[16384];
            for (var count = input.read(buffer); count >= 0; count = input.read(buffer)) {
                digest.update(buffer, 0, count);
            }
            return switch (HexFormat.of().formatHex(digest.digest())) {
                case "69432750a96ec3648c99e425a2f0a4ad4daeb13f94c8a22ec1549218bd9f0601" -> P152;
                case "a9f532638c404ac2d5d4a9d813e00d35d43e38862fbb497807d6d708dca7dbea" -> P162;
                default -> UNKNOWN;
            };
        } catch (IOException ignored) {
            return UNKNOWN;
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
