package com.rost.cruma.viewer.launch;

import com.rost.cruma.core.world.WorldVariant;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;

public final class MapPreviewCache {
    public static final int CAPTURE_RESOLUTION = 128;
    private final Path directory;
    private final Path image;
    private final Path legacyImage;
    private final int minX;
    private final int minY;
    private final int columns;
    private final int rows;
    private final int tileResolution;

    public MapPreviewCache(Path configDirectory, ClientInstallation installation) {
        directory = configDirectory.toAbsolutePath().normalize().resolve("maps");
        var tiles = installation.tiles();
        var x = tiles.stream()
                .mapToInt(tile -> Integer.parseInt(tile.split("_")[0]))
                .summaryStatistics();
        var y = tiles.stream()
                .mapToInt(tile -> Integer.parseInt(tile.split("_")[1]))
                .summaryStatistics();
        if (tiles.isEmpty()) {
            throw new IllegalArgumentException("No world tiles are available for a map image.");
        }
        minX = x.getMin();
        minY = y.getMin();
        columns = Math.toIntExact((long) x.getMax() - x.getMin() + 1);
        rows = Math.toIntExact((long) y.getMax() - y.getMin() + 1);
        tileResolution = Math.min(CAPTURE_RESOLUTION, 2048 / Math.max(columns, rows));
        if (tileResolution < 1) {
            throw new IllegalArgumentException("The map coordinate range is too large for a preview.");
        }
        var maps = installation.variant().getMapWorld();
        var variant = maps == WorldVariant.MAIN ? "" : "-" + maps.getKey();
        image = directory.resolve("v1-" + cacheKey(installation.root(), tiles) + variant + ".jpg");
        legacyImage = directory.resolveSibling("map-previews").resolve(image.getFileName());
    }

    public Path getImage() {
        return image;
    }

    public int getTileResolution() {
        return tileResolution;
    }

    public Path getCaptureDirectory() {
        return directory.resolve(".pending-" + UUID.randomUUID());
    }

    public BufferedImage read() throws IOException {
        var source = find();
        if (source == null) {
            return null;
        }
        var decoded = ImageIO.read(source.toFile());
        checkDimensions(decoded);
        if (source.equals(legacyImage)) {
            try {
                Files.createDirectories(directory);
                Files.move(legacyImage, image);
                Files.deleteIfExists(legacyImage.getParent());
            } catch (IOException ignored) {
            }
        }
        return decoded;
    }

    public boolean isReady() {
        var source = find();
        if (source == null) {
            return false;
        }
        try (var input = ImageIO.createImageInputStream(source.toFile())) {
            var readers = input == null ? null : ImageIO.getImageReaders(input);
            if (readers == null || !readers.hasNext()) {
                return false;
            }
            var reader = readers.next();
            try {
                reader.setInput(input, true, true);
                return reader.getWidth(0) == (long) columns * tileResolution
                        && reader.getHeight(0) == (long) rows * tileResolution;
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException unreadable) {
            return false;
        }
    }

    public void install(Path capture) throws IOException {
        var decoded = ImageIO.read(capture.toFile());
        checkDimensions(decoded);
        write(toRgb(decoded));
    }

    public boolean hasSameGrid(MapPreviewCache other) {
        return minX == other.minX
                && minY == other.minY
                && columns == other.columns
                && rows == other.rows
                && tileResolution == other.tileResolution;
    }

    public void patch(Path capture, Path base, List<String> tiles) throws IOException {
        var drawn = ImageIO.read(capture.toFile());
        checkDimensions(drawn);
        var before = ImageIO.read(base.toFile());
        checkDimensions(before);
        var result = toRgb(before);
        var graphics = result.createGraphics();
        try {
            for (var tile : tiles) {
                var coordinates = tile.split("_");
                var column = Integer.parseInt(coordinates[0]) - minX;
                var row = Integer.parseInt(coordinates[1]) - minY;
                if (column < 0 || column >= columns || row < 0 || row >= rows) {
                    throw new IOException("Tile " + tile + " lies outside the map image.");
                }
                var left = column * tileResolution;
                var top = row * tileResolution;
                graphics.drawImage(drawn.getSubimage(left, top, tileResolution, tileResolution), left, top, null);
            }
        } finally {
            graphics.dispose();
        }
        write(result);
    }

    public void removePending(Path pending) throws IOException {
        var path = pending.toAbsolutePath().normalize();
        if (!directory.equals(path.getParent())
                || !path.getFileName().toString().startsWith(".pending-")) {
            throw new IllegalArgumentException("Unexpected preview capture directory.");
        }
        Files.deleteIfExists(path.resolve("capture.png"));
        Files.deleteIfExists(path);
    }

    private void write(BufferedImage rgb) throws IOException {
        Files.createDirectories(directory);
        var temporary = Files.createTempFile(directory, ".overview-", ".jpg");
        try {
            if (!ImageIO.write(rgb, "jpg", temporary.toFile())) {
                throw new IOException("JPEG encoding is unavailable.");
            }
            try {
                Files.move(temporary, image, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, image, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private Path find() {
        if (Files.isRegularFile(image)) {
            return image;
        }
        return Files.isRegularFile(legacyImage) ? legacyImage : null;
    }

    private void checkDimensions(BufferedImage decoded) throws IOException {
        if (decoded == null
                || decoded.getWidth() != (long) columns * tileResolution
                || decoded.getHeight() != (long) rows * tileResolution) {
            throw new IOException("The map image does not match this client's tile coordinates.");
        }
    }

    private static BufferedImage toRgb(BufferedImage decoded) {
        var rgb = new BufferedImage(decoded.getWidth(), decoded.getHeight(), BufferedImage.TYPE_INT_RGB);
        var graphics = rgb.createGraphics();
        try {
            graphics.drawImage(decoded, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return rgb;
    }

    private static String cacheKey(Path client, List<String> tiles) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(client.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8));
            for (var tile : tiles) {
                digest.update(("\n" + tile).getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest(), 0, 12);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
