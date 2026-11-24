package com.rost.cruma.viewer.agent.film;

import com.rost.cruma.viewer.ui.GameSceneStage;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.List;

final class FilmSheet {
    private static final int LABEL_HEIGHT = 22;
    private static final int LABEL_FONT = 15;
    private static final int LABEL_MARGIN = 6;
    private static final int GAP = 2;
    private static final Color BEHIND_LABEL = new Color(0, 0, 0, 170);

    private FilmSheet() {}

    record Frame(BufferedImage picture, String label, float fade, int fadeColor, boolean bars) {}

    static int columns(int frames) {
        if (frames <= 3) {
            return frames;
        }
        return frames == 4 ? 2 : frames <= 9 ? 3 : 4;
    }

    static BufferedImage draw(List<Frame> frames, int width, double shape) {
        var columns = columns(frames.size());
        var rows = (frames.size() + columns - 1) / columns;
        var cellWidth = (width - GAP * (columns - 1)) / columns;
        var cellHeight = (int) Math.round(cellWidth / shape);
        var sheet = new BufferedImage(width, rows * cellHeight + GAP * (rows - 1), BufferedImage.TYPE_INT_RGB);
        var graphics = sheet.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, LABEL_FONT));
        for (var index = 0; index < frames.size(); index++) {
            var left = index % columns * (cellWidth + GAP);
            var top = index / columns * (cellHeight + GAP);
            drawFrame(graphics, frames.get(index), left, top, cellWidth, cellHeight, shape);
        }
        graphics.dispose();
        return sheet;
    }

    private static void drawFrame(
            Graphics2D graphics, Frame frame, int left, int top, int width, int height, double shape) {
        var picture = frame.picture();
        var cutWidth = Math.min(picture.getWidth(), (int) Math.round(picture.getHeight() * shape));
        var cutHeight = Math.min(picture.getHeight(), (int) Math.round(cutWidth / shape));
        var cutLeft = (picture.getWidth() - cutWidth) / 2;
        var cutTop = (picture.getHeight() - cutHeight) / 2;
        graphics.drawImage(
                picture,
                left,
                top,
                left + width,
                top + height,
                cutLeft,
                cutTop,
                cutLeft + cutWidth,
                cutTop + cutHeight,
                null);
        if (frame.fade() > 0) {
            var kept = graphics.getComposite();
            graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.min(1, frame.fade())));
            graphics.setColor(new Color(frame.fadeColor()));
            graphics.fillRect(left, top, width, height);
            graphics.setComposite(kept);
        }
        if (frame.bars()) {
            var bar = Math.round(height * GameSceneStage.BAR_SHARE);
            graphics.setColor(Color.BLACK);
            graphics.fillRect(left, top, width, bar);
            graphics.fillRect(left, top + height - bar, width, bar);
        }
        var metrics = graphics.getFontMetrics();
        var labelWidth = metrics.stringWidth(frame.label()) + 2 * LABEL_MARGIN;
        graphics.setColor(BEHIND_LABEL);
        graphics.fillRect(left, top + height - LABEL_HEIGHT, labelWidth, LABEL_HEIGHT);
        graphics.setColor(Color.WHITE);
        graphics.drawString(
                frame.label(),
                left + LABEL_MARGIN,
                top + height - LABEL_HEIGHT + (LABEL_HEIGHT + metrics.getAscent() - metrics.getDescent()) / 2);
    }
}
