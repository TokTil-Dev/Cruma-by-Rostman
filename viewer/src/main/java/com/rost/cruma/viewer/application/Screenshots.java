package com.rost.cruma.viewer.application;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;

public final class Screenshots {
    private Path folder = defaultFolder();
    private boolean includeInterface = true;
    private Path saved;
    private String failure = "";
    private long revision;
    private boolean windowCaptured;

    public static Path defaultFolder() {
        return Path.of(System.getProperty("user.home"), "Pictures", "Cruma", "Screenshots");
    }

    public static Path nextFile(Path folder, LocalDateTime now) {
        var name = "cruma-" + now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        var file = folder.resolve(name + ".jpg");
        for (var index = 2; Files.exists(file, LinkOption.NOFOLLOW_LINKS); index++) {
            file = folder.resolve(name + "-" + index + ".jpg");
        }
        return file;
    }

    public static boolean convertTga(Path tga, Path jpeg) throws IOException {
        if (!Files.isRegularFile(tga)) {
            return false;
        }
        var bytes = Files.readAllBytes(tga);
        if (bytes.length < 18) {
            return false;
        }
        var idLength = bytes[0] & 0xff;
        var type = bytes[2] & 0xff;
        var width = (bytes[12] & 0xff) | (bytes[13] & 0xff) << 8;
        var height = (bytes[14] & 0xff) | (bytes[15] & 0xff) << 8;
        var bits = bytes[16] & 0xff;
        var topDown = (bytes[17] & 0x20) != 0;
        if (type != 2 || bits != 32 && bits != 24 || width == 0 || height == 0) {
            throw new IOException("The screen image is not an uncompressed colour TGA");
        }
        var stride = bits / 8;
        var offset = 18 + idLength;
        if (bytes.length < offset + (long) width * height * stride) {
            return false;
        }
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var row = new int[width];
        for (var y = 0; y < height; y++) {
            var source = offset + (long) (topDown ? y : height - 1 - y) * width * stride;
            for (var x = 0; x < width; x++) {
                var index = (int) (source + (long) x * stride);
                row[x] = (bytes[index + 2] & 0xff) << 16 | (bytes[index + 1] & 0xff) << 8 | bytes[index] & 0xff;
            }
            image.getRaster().setDataElements(0, y, width, 1, row);
        }
        Files.createDirectories(jpeg.toAbsolutePath().getParent());
        var writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        var parameters = writer.getDefaultWriteParam();
        parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        parameters.setCompressionQuality(.92f);
        try (var output = ImageIO.createImageOutputStream(jpeg.toFile())) {
            writer.setOutput(output);
            writer.write(null, new IIOImage(image, null, null), parameters);
        } finally {
            writer.dispose();
        }
        return true;
    }

    public synchronized Path getFolder() {
        return folder;
    }

    public synchronized void setFolder(Path folder) {
        this.folder = folder;
    }

    public synchronized boolean isIncludeInterface() {
        return includeInterface;
    }

    public synchronized void includeInterface(boolean include) {
        includeInterface = include;
    }

    public synchronized void recordSaved(Path file) {
        saved = file;
        failure = "";
        revision++;
    }

    public synchronized void recordFailure(Path file, String reason) {
        saved = file;
        failure = reason == null || reason.isBlank() ? "unknown error" : reason;
        revision++;
    }

    public synchronized Path getLast() {
        return saved;
    }

    public synchronized String getFailure() {
        return failure;
    }

    public synchronized long getRevision() {
        return revision;
    }

    public synchronized boolean isWindowCaptured() {
        return windowCaptured;
    }

    public synchronized void setWindowCaptured(boolean captured) {
        windowCaptured = captured;
    }
}
