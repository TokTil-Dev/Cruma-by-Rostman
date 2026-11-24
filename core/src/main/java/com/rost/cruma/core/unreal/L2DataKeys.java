package com.rost.cruma.core.unreal;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

public final class L2DataKeys {
    public static final String FILE_NAME = "keys.txt";

    public static final int MAX_FILE_BYTES = 64 << 10;

    private static final int MAX_BITS = 1024;
    private static final int MIN_BITS = MAX_BITS - 7;
    private static final int MAX_EXPONENT_BITS = 32;
    private static final Pattern HEX = Pattern.compile("(?:0[xX])?([0-9a-fA-F]+)");
    private static final Pattern SPACE = Pattern.compile("\\s+");
    private static final Map<Path, Cached> cache = new ConcurrentHashMap<>();

    private L2DataKeys() {}

    public record Key(BigInteger exponent, BigInteger modulus) {}

    public record KeyFile(List<Key> keys, List<String> problems) {
        public static final KeyFile NONE = new KeyFile(List.of(), List.of());

        public KeyFile {
            keys = List.copyOf(keys);
            problems = List.copyOf(problems);
        }
    }

    private record Cached(long size, FileTime time, KeyFile keys) {}

    public static KeyFile read(Path file) {
        var path = file.toAbsolutePath().normalize();
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(path, BasicFileAttributes.class);
        } catch (NoSuchFileException absent) {
            cache.remove(path);
            return KeyFile.NONE;
        } catch (IOException | RuntimeException unreadable) {
            return new KeyFile(List.of(), List.of(path + ": cannot be read: " + unreadable.getMessage()));
        }
        if (!attributes.isRegularFile()) {
            return new KeyFile(List.of(), List.of(path + ": is not a file"));
        }
        if (attributes.size() > MAX_FILE_BYTES) {
            return new KeyFile(List.of(), List.of(path + ": the file is over 64 KB, so it is not read"));
        }
        var cached = cache.get(path);
        if (cached != null
                && cached.size() == attributes.size()
                && cached.time().equals(attributes.lastModifiedTime())) {
            return cached.keys();
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(path);
        } catch (IOException | RuntimeException unreadable) {
            return new KeyFile(List.of(), List.of(path + ": cannot be read: " + unreadable.getMessage()));
        }
        var keys = parse(text(bytes), path.toString());
        cache.put(path, new Cached(attributes.size(), attributes.lastModifiedTime(), keys));
        return keys;
    }

    public static KeyFile parse(String text, String source) {
        var keys = new ArrayList<Key>();
        var problems = new ArrayList<String>();
        var lines = text.split("\r\n|\r|\n", -1);
        for (var index = 0; index < lines.length; index++) {
            var line = lines[index];
            var comment = line.indexOf('#');
            line = (comment < 0 ? line : line.substring(0, comment)).strip();
            if (line.isEmpty()) {
                continue;
            }
            var where = source + " line " + (index + 1) + ": ";
            var parts = SPACE.split(line);
            if (parts.length != 2) {
                problems.add(where + "a key is two hexadecimal numbers, the exponent and then the modulus");
                continue;
            }
            var exponent = hex(parts[0]);
            var modulus = hex(parts[1]);
            if (exponent == null || modulus == null) {
                problems.add(where + (exponent == null ? parts[0] : parts[1]) + " is not a hexadecimal number");
            } else if (!exponent.testBit(0) || exponent.compareTo(BigInteger.valueOf(3)) < 0) {
                problems.add(where + "the exponent " + exponent.toString(16)
                        + " is no exponent of a key: it is odd and 3 or more, as 1d is");
            } else if (exponent.bitLength() > MAX_EXPONENT_BITS) {
                problems.add(where + "the exponent has " + exponent.bitLength() + " bits, more than the "
                        + MAX_EXPONENT_BITS + " a key of a client has");
            } else if (modulus.bitLength() > MAX_BITS || modulus.bitLength() < MIN_BITS) {
                problems.add(where + "the modulus has " + modulus.bitLength() + " bits, and the modulus of a block of"
                        + " 128 bytes has 1017 to 1024");
            } else {
                keys.add(new Key(exponent, modulus));
            }
        }
        return new KeyFile(keys, problems);
    }

    private static String text(byte[] bytes) {
        if (bytes.length >= 2 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xfe) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xff) == 0xfe && (bytes[1] & 0xff) == 0xff) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        }
        var text = new String(bytes, StandardCharsets.UTF_8);
        return text.startsWith("﻿") ? text.substring(1) : text;
    }

    private static @Nullable BigInteger hex(String text) {
        var matcher = HEX.matcher(text);
        return matcher.matches() ? new BigInteger(matcher.group(1), 16) : null;
    }
}
