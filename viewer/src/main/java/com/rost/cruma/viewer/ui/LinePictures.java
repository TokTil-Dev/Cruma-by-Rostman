package com.rost.cruma.viewer.ui;

import com.rost.cruma.core.npc.ClientIcons.Icon;
import com.rost.cruma.core.world.GameScenes.Scene;
import com.rost.cruma.viewer.render.api.ViewerUiRenderer;
import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.BufferUtils;

final class LinePictures {
    private static final List<String> FONTS = List.of(
            "Apple SD Gothic Neo", "Malgun Gothic", "Noto Sans CJK KR", "Noto Sans KR", "NanumGothic", Font.SANS_SERIF);
    private static final float ROW_ROOM = 1.3f;

    private final Map<String, Picture> pictures = new HashMap<>();
    private @Nullable Scene scene;
    private @Nullable ViewerUiRenderer renderer;
    private @Nullable Font family;

    static @Nullable Icon raster(String row, @Nullable Font font) {
        if (font == null || row.isEmpty()) {
            return null;
        }
        var probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics();
        var metrics = probe.getFontMetrics(font);
        var width = Math.max(1, metrics.stringWidth(row));
        var height = Math.max(1, (int) Math.ceil(font.getSize() * ROW_ROOM));
        probe.dispose();
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setFont(font);
        graphics.setColor(Color.WHITE);
        graphics.drawString(row, 0, metrics.getAscent());
        graphics.dispose();
        var rgba = new byte[width * height * 4];
        var pixels = image.getRGB(0, 0, width, height, null, 0, width);
        for (var i = 0; i < pixels.length; i++) {
            rgba[i * 4] = (byte) (pixels[i] >> 16);
            rgba[i * 4 + 1] = (byte) (pixels[i] >> 8);
            rgba[i * 4 + 2] = (byte) pixels[i];
            rgba[i * 4 + 3] = (byte) (pixels[i] >> 24);
        }
        return new Icon(width, height, rgba);
    }

    static @Nullable Font fontFor(String row, int size) {
        var names = List.of(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
        for (var name : FONTS) {
            if (names.contains(name) || Font.SANS_SERIF.equals(name)) {
                var font = new Font(name, Font.BOLD, size);
                if (font.canDisplayUpTo(row) < 0) {
                    return font;
                }
            }
        }
        return null;
    }

    void follow(@Nullable Scene playing, ViewerUiRenderer ui) {
        renderer = ui;
        if (playing != scene) {
            scene = playing;
            release();
        }
    }

    @Nullable
    Picture getRow(String row, int size, float density) {
        var key = size + "/" + density + "/" + row;
        var known = pictures.get(key);
        if (known != null || pictures.containsKey(key) || renderer == null) {
            return known;
        }
        if (family == null) {
            family = fontFor(row, Math.round(size * density));
        }
        var font = family;
        var icon = raster(
                row, font != null && font.canDisplayUpTo(row) < 0 ? font : fontFor(row, Math.round(size * density)));
        if (icon == null) {
            pictures.put(key, null);
            return null;
        }
        var data = BufferUtils.createByteBuffer(icon.rgba().length);
        data.put(icon.rgba()).flip();
        var picture = new Picture(
                renderer.createTexture("ui/line/" + pictures.size(), icon.width(), icon.height(), data),
                icon.width() / density,
                icon.height() / density);
        pictures.put(key, picture);
        return picture;
    }

    void release() {
        if (renderer != null) {
            for (var picture : pictures.values()) {
                if (picture != null) {
                    renderer.destroyTexture(picture.texture());
                }
            }
        }
        pictures.clear();
        family = null;
    }

    record Picture(long texture, float width, float height) {}
}
