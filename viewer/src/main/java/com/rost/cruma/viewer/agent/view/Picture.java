package com.rost.cruma.viewer.agent.view;

import com.rost.cruma.viewer.agent.view.SeenWorld.View;
import com.rost.cruma.viewer.render.Camera.Snapshot;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Rectangle2D;
import java.awt.geom.Rectangle2D.Double;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.stream.MemoryCacheImageOutputStream;

final class Picture {
    static final int DEFAULT_WIDTH = 1280;

    static final int MOST_WIDTH = 1920;

    static final int LEAST_WIDTH = 160;

    private static final int SHEET_HEIGHT = 720;
    private static final float JPEG_QUALITY = .82f;
    private static final Color MARK_LINE = new Color(255, 225, 60);
    private static final Color LABEL_BACK = new Color(0, 0, 0, 190);
    private static final int TAG_PADDING = 3;
    private static final int CHANGE_STEP = 2;
    private static final int CHANGE_LEVEL = 12;
    private static final double MOST_BOX_AREA = .3;

    private Picture() {}

    record Box(int number, double left, double top, double right, double bottom, double spotX, double spotY) {
        double area() {
            return (right - left) * (bottom - top);
        }
    }

    record Layout(int columns, int rows, int cellWidth, int cellHeight) {
        int width() {
            return columns * cellWidth;
        }

        int height() {
            return rows * cellHeight;
        }
    }

    record Sheet(BufferedImage image, String label, Snapshot camera) {}

    static BufferedImage read(Path file) throws IOException {
        var image = ImageIO.read(file.toFile());
        if (image == null) {
            throw new IOException("The picture " + file + " cannot be read.");
        }
        return image;
    }

    static BufferedImage scaled(BufferedImage image, int width) {
        var height = Math.max(1, Math.round(image.getHeight() * (float) width / image.getWidth()));
        return scaled(image, width, height);
    }

    static void mark(BufferedImage image, List<Box> boxes) {
        var graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.max(12, image.getWidth() / 90)));
        var width = image.getWidth();
        var height = image.getHeight();
        var metrics = graphics.getFontMetrics();
        var tagHeight = metrics.getAscent() + TAG_PADDING * 2;
        var tags = new ArrayList<Rectangle2D>();
        for (var box : boxes) {
            if (box.area() > MOST_BOX_AREA) {
                continue;
            }
            var left = (int) Math.round(box.left() * width);
            var top = (int) Math.round(box.top() * height);
            var right = (int) Math.round(box.right() * width);
            var bottom = (int) Math.round(box.bottom() * height);
            graphics.setStroke(new BasicStroke(3f));
            graphics.setColor(Color.BLACK);
            graphics.drawRect(left, top, right - left, bottom - top);
            graphics.setStroke(new BasicStroke(1.5f));
            graphics.setColor(MARK_LINE);
            graphics.drawRect(left, top, right - left, bottom - top);
        }
        for (var box : boxes) {
            var text = Integer.toString(box.number());
            var tagWidth = metrics.stringWidth(text) + TAG_PADDING * 2;
            var x = Math.clamp(Math.round(box.spotX() * width - tagWidth / 2.0), 0L, Math.max(0, width - tagWidth));
            var y = Math.clamp(Math.round(box.spotY() * height - tagHeight / 2.0), 0L, Math.max(0, height - tagHeight));
            var tag = new Double(x, y, tagWidth, tagHeight);
            while (overlaps(tag, tags) && tag.getY() + tagHeight * 2 < height) {
                tag.y += tagHeight + 1;
            }
            tags.add(tag);
            graphics.setColor(MARK_LINE);
            graphics.fill(tag);
            graphics.setColor(Color.BLACK);
            graphics.drawString(
                    text, (float) tag.getX() + TAG_PADDING, (float) tag.getY() + TAG_PADDING + metrics.getAscent());
        }
        graphics.dispose();
    }

    static Layout layout(int views, int width, double aspect) {
        var height = SHEET_HEIGHT * width / DEFAULT_WIDTH;
        Layout best = null;
        for (var columns = 1; columns <= views; columns++) {
            var rows = (views + columns - 1) / columns;
            var cellHeight = height / (double) rows;
            var cellWidth = cellHeight * aspect;
            if (cellWidth * columns > width) {
                cellWidth = width / (double) columns;
                cellHeight = cellWidth / aspect;
            }
            if (best == null || cellWidth > best.cellWidth()) {
                best = new Layout(columns, rows, (int) cellWidth, (int) cellHeight);
            }
        }
        return best;
    }

    static BufferedImage sheet(List<Sheet> views, Layout layout) {
        var sheet = new BufferedImage(layout.width(), layout.height(), BufferedImage.TYPE_INT_RGB);
        var graphics = sheet.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        for (var index = 0; index < views.size(); index++) {
            var view = views.get(index);
            var x = index % layout.columns() * layout.cellWidth();
            var y = index / layout.columns() * layout.cellHeight();
            graphics.drawImage(view.image(), x, y, layout.cellWidth(), layout.cellHeight(), null);
            label(graphics, view.label(), x, y);
        }
        graphics.setColor(Color.BLACK);
        for (var column = 1; column < layout.columns(); column++) {
            graphics.drawLine(column * layout.cellWidth(), 0, column * layout.cellWidth(), layout.height());
        }
        for (var row = 1; row < layout.rows(); row++) {
            graphics.drawLine(0, row * layout.cellHeight(), layout.width(), row * layout.cellHeight());
        }
        graphics.dispose();
        return sheet;
    }

    static List<View> views(List<Sheet> views, Layout layout) {
        var result = new ArrayList<View>();
        for (var index = 0; index < views.size(); index++) {
            result.add(new View(
                    index % layout.columns() * layout.cellWidth(),
                    index / layout.columns() * layout.cellHeight(),
                    layout.cellWidth(),
                    layout.cellHeight(),
                    views.get(index).camera()));
        }
        return result;
    }

    static double changed(BufferedImage before, BufferedImage after) {
        var width = Math.min(before.getWidth(), after.getWidth());
        var height = Math.min(before.getHeight(), after.getHeight());
        var changed = 0;
        var pixels = 0;
        for (var y = 0; y < height; y += CHANGE_STEP) {
            for (var x = 0; x < width; x += CHANGE_STEP) {
                pixels++;
                if (differs(before.getRGB(x, y), after.getRGB(x, y))) {
                    changed++;
                }
            }
        }
        return pixels == 0 ? 0 : Math.round(changed * 1000.0 / pixels) / 10.0;
    }

    static byte[] jpeg(BufferedImage image) throws IOException {
        var writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        var parameters = writer.getDefaultWriteParam();
        parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        parameters.setCompressionQuality(JPEG_QUALITY);
        var bytes = new ByteArrayOutputStream();
        try (var output = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(output);
            writer.write(null, new IIOImage(image, null, null), parameters);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    static void keepLast(Path folder, int count) throws IOException {
        var pictures = new ArrayList<Path>();
        try (var files = Files.list(folder)) {
            files.filter(file -> file.getFileName().toString().endsWith(".jpg")).forEach(pictures::add);
        }
        if (pictures.size() <= count) {
            return;
        }
        pictures.sort(Comparator.comparingLong(Picture::modified));
        for (var old : pictures.subList(0, pictures.size() - count)) {
            Files.deleteIfExists(old);
        }
    }

    private static BufferedImage scaled(BufferedImage image, int width, int height) {
        var small = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = small.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.drawImage(image, 0, 0, width, height, null);
        graphics.dispose();
        return small;
    }

    private static boolean overlaps(Rectangle2D tag, List<Rectangle2D> others) {
        for (var other : others) {
            if (tag.intersects(other)) {
                return true;
            }
        }
        return false;
    }

    private static void label(Graphics2D graphics, String text, int x, int y) {
        var metrics = graphics.getFontMetrics();
        graphics.setColor(LABEL_BACK);
        graphics.fillRect(x, y, metrics.stringWidth(text) + 12, metrics.getHeight() + 6);
        graphics.setColor(Color.WHITE);
        graphics.drawString(text, x + 6, y + 3 + metrics.getAscent());
    }

    private static boolean differs(int one, int other) {
        return Math.abs((one >> 16 & 0xff) - (other >> 16 & 0xff)) > CHANGE_LEVEL
                || Math.abs((one >> 8 & 0xff) - (other >> 8 & 0xff)) > CHANGE_LEVEL
                || Math.abs((one & 0xff) - (other & 0xff)) > CHANGE_LEVEL;
    }

    private static long modified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException gone) {
            return 0;
        }
    }
}
