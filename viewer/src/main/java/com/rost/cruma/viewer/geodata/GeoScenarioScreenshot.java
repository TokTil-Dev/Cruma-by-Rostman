package com.rost.cruma.viewer.geodata;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

public final class GeoScenarioScreenshot {
    public static final long MAX_PIXELS = 16_777_216;

    private GeoScenarioScreenshot() {}

    public record Marker(String label, float x, float y, int rgb) {}

    public static BufferedImage annotate(
            int width,
            int height,
            ByteBuffer rgba,
            boolean originBottomLeft,
            List<Marker> markers,
            List<String> notes) {
        if (width < 1
                || height < 1
                || (long) width * height > MAX_PIXELS
                || rgba == null
                || rgba.remaining() < (long) width * height * 4
                || notes.size() > 32
                || markers.size() > 16) {
            throw new IllegalArgumentException("Invalid or oversized screenshot data");
        }
        var wrapped = new ArrayList<String>();
        var columns = Math.max(12, (width - 20) / 8);
        for (var note : notes) {
            var line = note == null ? "" : note.replace('\n', ' ').replace('\r', ' ');
            if (line.length() > 2048) {
                throw new IllegalArgumentException("Screenshot annotation is too long");
            }
            for (var start = 0; start < line.length(); start += columns) {
                wrapped.add(line.substring(start, Math.min(line.length(), start + columns)));
            }
        }
        var footer = Math.min(2048, 24 + wrapped.size() * 19);
        var image = new BufferedImage(width, height + footer, BufferedImage.TYPE_INT_RGB);
        var buffer = rgba.asReadOnlyBuffer();
        var base = buffer.position();
        var row = new int[width];
        for (var y = 0; y < height; y++) {
            var offset = base + (originBottomLeft ? height - 1 - y : y) * width * 4;
            for (var x = 0; x < width; x++) {
                var pixel = offset + x * 4;
                row[x] = (buffer.get(pixel) & 255) << 16
                        | (buffer.get(pixel + 1) & 255) << 8
                        | buffer.get(pixel + 2) & 255;
            }
            image.setRGB(0, y, width, 1, row, 0, width);
        }
        var graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setFont(new Font(Font.MONOSPACED, Font.BOLD, 14));
            graphics.setStroke(new BasicStroke(2));
            for (var marker : markers) {
                if (!Float.isFinite(marker.x())
                        || !Float.isFinite(marker.y())
                        || marker.x() < 0
                        || marker.y() < 0
                        || marker.x() >= width
                        || marker.y() >= height) {
                    continue;
                }
                var x = Math.round(marker.x());
                var y = Math.round(marker.y());
                graphics.setColor(Color.BLACK);
                graphics.fillOval(x - 7, y - 7, 14, 14);
                graphics.setColor(new Color(marker.rgb()));
                graphics.drawOval(x - 6, y - 6, 12, 12);
                graphics.drawString(marker.label(), Math.min(width - 20, x + 10), Math.max(16, y - 8));
            }
            graphics.setColor(new Color(18, 24, 30));
            graphics.fillRect(0, height, width, footer);
            graphics.setColor(new Color(220, 233, 240));
            graphics.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
            var baseline = height + 20;
            for (var line : wrapped) {
                if (baseline >= image.getHeight() - 3) {
                    break;
                }
                graphics.drawString(line, 10, baseline);
                baseline += 19;
            }
        } finally {
            graphics.dispose();
        }
        return image;
    }

    public static void write(
            Path path,
            int width,
            int height,
            ByteBuffer rgba,
            boolean originBottomLeft,
            List<Marker> markers,
            List<String> notes,
            boolean replaceExisting)
            throws IOException {
        var image = annotate(width, height, rgba, originBottomLeft, markers, notes);
        var bytes = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", bytes)) {
            throw new IOException("PNG encoder unavailable");
        }
        GeoScenarioStore.writeBytes(path, bytes.toByteArray(), replaceExisting);
    }
}
