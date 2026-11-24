package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.TexturePackageWriter.PackagedTexture;
import com.rost.cruma.core.npc.ClientIcons.Icon;
import com.rost.cruma.core.texture.TexturePixels;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

final class EffectImages {
    private static final long CACHE_BYTES = 256L << 20;
    private static final int PICTURE_SIZE = 64;

    private final LinkedHashMap<String, PackagedTexture> decoded = new LinkedHashMap<>(16, .75f, true);
    private final Map<String, String> unreadable = new HashMap<>();
    private long decodedBytes;
    private @Nullable Path folder;

    void setFolder(@Nullable Path value) {
        folder = value;
    }

    String store(Path image) throws IOException {
        var target = folder;
        if (target == null) {
            throw new IOException("There is no folder to keep imported images in.");
        }
        if (!Files.isRegularFile(image)) {
            throw new IOException("There is no image file at " + image);
        }
        var texture = TexturePackageWriter.readTexture(image);
        var bytes = Files.readAllBytes(image);
        var file = sha256(bytes) + extension(image);
        Files.createDirectories(target);
        if (!Files.isRegularFile(target.resolve(file))) {
            write(target, file, bytes);
        }
        unreadable.remove(file);
        keep(file, texture);
        return file;
    }

    @Nullable
    PackagedTexture read(String file) {
        var known = decoded.get(file);
        if (known != null) {
            return known;
        }
        var path = folder == null ? null : folder.resolve(file);
        if (path == null || unreadable.containsKey(file) || !Files.isRegularFile(path)) {
            return null;
        }
        try {
            var texture = TexturePackageWriter.readTexture(path);
            keep(file, texture);
            return texture;
        } catch (IOException | RuntimeException failure) {
            unreadable.put(file, failure.getMessage() == null ? failure.toString() : failure.getMessage());
            return null;
        }
    }

    @Nullable
    String getProblem(String name, String file) {
        if (read(file) != null) {
            return null;
        }
        var reason = unreadable.get(file);
        return (reason == null
                        ? "The image of " + name + " is gone"
                        : "The image of " + name + " cannot be read: " + reason)
                + ". The emitter keeps its old texture until you import the image again.";
    }

    @Nullable
    Icon getPicture(String file) {
        var image = read(file);
        if (image == null) {
            return null;
        }
        var level = 0;
        while (level + 1 < image.levels().size()
                && Math.max(image.width() >> level, image.height() >> level) > PICTURE_SIZE) {
            level++;
        }
        return new Icon(
                Math.max(1, image.width() >> level),
                Math.max(1, image.height() >> level),
                TexturePixels.decodeMipRgba(image.entity(), level));
    }

    void close() {
        decoded.clear();
        unreadable.clear();
        decodedBytes = 0;
    }

    private void keep(String file, PackagedTexture texture) {
        if (decoded.put(file, texture) == null) {
            decodedBytes += size(texture);
        }
        var eldest = decoded.entrySet().iterator();
        while (decodedBytes > CACHE_BYTES && decoded.size() > 1) {
            decodedBytes -= size(eldest.next().getValue());
            eldest.remove();
        }
    }

    private static long size(PackagedTexture texture) {
        var bytes = 0L;
        for (var level : texture.levels()) {
            bytes += level.length;
        }
        return bytes;
    }

    private static void write(Path folder, String file, byte[] bytes) throws IOException {
        var temporary = Files.createTempFile(folder, ".image-", ".tmp");
        try {
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, folder.resolve(file), StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, folder.resolve(file));
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String extension(Path image) {
        var name = image.getFileName().toString();
        var dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
