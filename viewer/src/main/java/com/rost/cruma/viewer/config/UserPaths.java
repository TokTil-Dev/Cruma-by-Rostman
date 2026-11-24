package com.rost.cruma.viewer.config;

import com.rost.cruma.core.world.WorldVariant;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

public final class UserPaths {
    private UserPaths() {}

    public static Path resolve(String text, Path base) {
        var value = text.strip();
        var home = Path.of(System.getProperty("user.home"));
        Path path;
        if (value.equals("~")) {
            path = home;
        } else if (value.startsWith("~/") || value.startsWith("~\\")) {
            path = home.resolve(value.substring(2));
        } else {
            path = Path.of(value);
        }
        if (!path.isAbsolute()) {
            path = base.resolve(path);
        }
        return path.toAbsolutePath().normalize();
    }

    public static String toDisplayString(Path path) {
        var home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        var absolute = path.toAbsolutePath().normalize();
        if (absolute.equals(home)) {
            return "~";
        }
        return absolute.startsWith(home)
                ? "~/" + home.relativize(absolute).toString().replace('\\', '/')
                : absolute.toString();
    }

    public static String clientFolder(Path clientRoot) {
        var absolute = clientRoot.toAbsolutePath().normalize();
        var name = absolute.getFileName() == null ? "" : absolute.getFileName().toString();
        return safeName(name) + "-" + hash(absolute.toString().toLowerCase(Locale.ROOT));
    }

    public static String clientFolder(Path clientRoot, WorldVariant variant) {
        var maps = variant.getMapWorld();
        return clientFolder(clientRoot) + (maps == WorldVariant.MAIN ? "" : "-" + maps.getKey());
    }

    static String safeName(String name) {
        var safe = name.replaceAll("[<>:\"/\\\\|?*\\p{Cntrl}]", "_")
                .replaceAll("[. ]+$", "")
                .strip();
        return safe.isEmpty() ? "client" : safe;
    }

    private static String hash(String text) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 4);
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", missing);
        }
    }
}
