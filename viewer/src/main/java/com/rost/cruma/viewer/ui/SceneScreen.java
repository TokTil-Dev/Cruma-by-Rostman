package com.rost.cruma.viewer.ui;

import com.rost.cruma.core.export.SceneTimeline.Grade;
import com.rost.cruma.core.export.SceneTimeline.Layer;
import com.rost.cruma.core.export.SceneTimeline.LayerKey;
import com.rost.cruma.core.export.SceneTimeline.Space;
import com.rost.cruma.core.export.SceneTrack;
import com.rost.cruma.core.npc.ClientIcons;
import com.rost.cruma.core.npc.ClientIcons.Icon;
import com.rost.cruma.core.world.PostEffects.Effect;
import com.rost.cruma.viewer.render.GameScenePlayer;
import com.rost.cruma.viewer.render.GameScenePlayer.Message;
import com.rost.cruma.viewer.render.GameSlides;
import com.rost.cruma.viewer.render.GameSlides.Crop;
import com.rost.cruma.viewer.render.GameSlides.Slide;
import com.rost.cruma.viewer.render.ScenePostEffects;
import com.rost.cruma.viewer.ui.GeodataWorkspace.Viewport;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jetbrains.annotations.Nullable;

final class SceneScreen {
    private static final int WIDTH = 1920;
    private static final int HEIGHT = 1080;
    private static final int DENSITY = 2;
    private static final int SHADOW_ALPHA = 220;
    private static final String FONT = "ui/fonts/inter/CrumaUI-SemiBold.ttf";
    private static final Grade NO_GRADE = new Grade(0, 1, 1, 0, 0);
    private static final float[][] COVERS = {{-2, -2, 2, 5}, {1, -2, 2, 5}, {0, -2, 1, 2}, {0, 1, 1, 2}};

    private final Path client;
    private final Function<Message, String> lines;
    private final double step;
    private final Map<Integer, Track> fades = new LinkedHashMap<>();
    private final Track topBar = new Track("Bar top", Space.SCREEN, 0, null);
    private final Track bottomBar = new Track("Bar bottom", Space.SCREEN, 0, null);
    private final Track backdrop = new Track("Slide window", Space.SCREEN, 0, null);
    private final List<Track> covers = new ArrayList<>();
    private final List<Map<String, Track>> slides = List.of(new LinkedHashMap<>(), new LinkedHashMap<>());
    private final List<@Nullable Track> showing = new ArrayList<>();
    private final Map<String, Line> written = new LinkedHashMap<>();
    private final List<Grade> grades = new ArrayList<>();
    private @Nullable ClientIcons icons;

    SceneScreen(Path client, Function<Message, String> lines, double step) {
        this.client = client;
        this.lines = lines;
        this.step = step;
        for (var cover = 0; cover < COVERS.length; cover++) {
            covers.add(new Track("Slide edge " + (cover + 1), Space.BOX, 0, null));
        }
        showing.add(null);
        showing.add(null);
    }

    static Grade grade(double seconds, @Nullable Effect effect, float share) {
        if (effect == null) {
            return new Grade(seconds, 1, 1, 0, 0);
        }
        var fromNone = effect.mode() == 1;
        var toNone = effect.mode() == 2;
        var keeps = effect.keepsChroma();
        var first = effect.first();
        var second = effect.second();
        return new Grade(
                seconds,
                mix(fromNone ? 1 : first.x(), toNone ? 1 : second.x(), share),
                mix(fromNone || keeps ? 1 : 0, toNone || keeps ? 1 : 0, share),
                mix(fromNone || keeps ? 0 : first.y(), toNone || keeps ? 0 : second.y(), share),
                mix(fromNone || keeps ? 0 : first.z(), toNone || keeps ? 0 : second.z(), share));
    }

    void record(double now, GameScenePlayer player) {
        recordFade(now, player.getFade(), player.getFadeColor());
        recordBars(now, player.isWide());
        recordSlides(now, player.getSlides());
        recordLines(now, player.getMessages());
        recordGrade(now, player.getPostEffects());
    }

    List<Layer> getLayers() {
        var layers = new ArrayList<Layer>();
        for (var fade : fades.values()) {
            layers.add(fade.getLayer());
        }
        addShown(layers, List.of(topBar, bottomBar, backdrop));
        for (var slide : slides) {
            addShown(layers, slide.values());
        }
        addShown(layers, covers);
        for (var line : written.values()) {
            layers.add(line.track.getLayer());
        }
        return layers;
    }

    List<Grade> getGrades() {
        return List.copyOf(grades);
    }

    private void recordFade(double now, float fade, int color) {
        for (var track : fades.values()) {
            track.hide(now);
        }
        if (fade > 0) {
            fades.computeIfAbsent(color, _ -> new Track(String.format("Fade %06X", color), Space.SCREEN, color, null))
                    .show(now, 0, 0, 1, 1, fade);
        }
    }

    private void recordBars(double now, boolean wide) {
        if (wide) {
            topBar.show(now, 0, 0, 1, GameSceneStage.BAR_SHARE, 1);
            bottomBar.show(now, 0, 1 - GameSceneStage.BAR_SHARE, 1, GameSceneStage.BAR_SHARE, 1);
        } else {
            topBar.hide(now);
            bottomBar.hide(now);
        }
    }

    private void recordSlides(double now, GameSlides window) {
        var shows = window.shows();
        if (shows) {
            backdrop.show(now, 0, 0, 1, 1, 1);
        } else {
            backdrop.hide(now);
        }
        for (var cover = 0; cover < COVERS.length; cover++) {
            if (shows) {
                var place = COVERS[cover];
                covers.get(cover).show(now, place[0], place[1], place[2], place[3], 1);
            } else {
                covers.get(cover).hide(now);
            }
        }
        for (var index = 0; index < slides.size(); index++) {
            var slide = window.getSlides().get(index);
            var alpha = (float) slide.getAlpha();
            var track = shows && alpha > 0 ? slide(index, slide) : null;
            var last = showing.get(index);
            if (last != null && last != track) {
                last.hide(now);
            }
            showing.set(index, track);
            if (track != null) {
                track.show(
                        now,
                        (float) slide.getX(),
                        (float) slide.getY(),
                        (float) slide.getWidth(),
                        (float) slide.getHeight(),
                        alpha);
            }
        }
    }

    private Track slide(int index, Slide slide) {
        var picture = slide.getPicture();
        var crop = slide.getCrop();
        return slides.get(index)
                .computeIfAbsent(
                        picture + " " + crop,
                        _ -> new Track(
                                picture.isEmpty() ? "Slide black" : "Slide " + picture,
                                Space.BOX,
                                0,
                                picture.isEmpty() ? null : () -> read(picture, crop)));
    }

    private @Nullable Icon read(String picture, Crop crop) {
        if (icons == null) {
            icons = new ClientIcons(client);
        }
        var icon = icons.load(picture);
        if (icon == null) {
            return null;
        }
        var width = Math.max(1, Math.round(crop.u(icon.width()) * icon.width()));
        var height = Math.max(1, Math.round(crop.v(icon.height()) * icon.height()));
        if (width == icon.width() && height == icon.height()) {
            return icon;
        }
        var rgba = new byte[width * height * 4];
        for (var row = 0; row < height; row++) {
            System.arraycopy(icon.rgba(), row * icon.width() * 4, rgba, row * width * 4, width * 4);
        }
        return new Icon(width, height, rgba);
    }

    private void recordLines(double now, List<Message> messages) {
        var shown = new HashSet<Line>();
        for (var message : messages) {
            var text = lines.apply(message);
            if (text.isEmpty() || message.alpha() <= 0) {
                continue;
            }
            var line = written.computeIfAbsent(message.window() + " " + text, _ -> line(text, message.window()));
            if (line.drawn) {
                line.track.show(now, line.x, line.y, line.width, line.height, message.alpha() / 255f);
                shown.add(line);
            }
        }
        for (var line : written.values()) {
            if (!shown.contains(line)) {
                line.track.hide(now);
            }
        }
    }

    private Line line(String text, int window) {
        var font = font(text);
        if (font == null) {
            return new Line(new Track(text, Space.SCREEN, 0, null), false, 0, 0, 0, 0);
        }
        var metrics = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
                .createGraphics()
                .getFontMetrics(font);
        var room = GameSlides.box(WIDTH, HEIGHT).width() * GameSceneStage.LINE_ROOM;
        var rows =
                GameSceneStage.wrap(GameSceneStage.rows(text), room, row -> (float) metrics.stringWidth(row) / DENSITY);
        var widest = 0f;
        for (var row : rows) {
            widest = Math.max(widest, (float) metrics.stringWidth(row) / DENSITY);
        }
        var blockHeight = GameSceneStage.LINE_ROW * rows.size();
        var place =
                GameSceneStage.linePlace(window, new Viewport(0, 0, WIDTH, HEIGHT), WIDTH, HEIGHT, widest, blockHeight);
        var edge = GameSceneStage.LINE_SHADOW * 2;
        var width = widest + edge * 2;
        var height = blockHeight + edge * 2;
        var column = window >= 9 ? 1 : window % 3;
        var name = "Line " + (text.length() > 24 ? text.substring(0, 24) : text);
        var picture = (Supplier<@Nullable Icon>) () -> picture(rows, font, column, width, height);
        return new Line(
                new Track(name, Space.SCREEN, 0xFFFFFF, picture),
                true,
                (place[0] - edge) / WIDTH,
                (place[1] - edge) / HEIGHT,
                width / WIDTH,
                height / HEIGHT);
    }

    private void recordGrade(double now, ScenePostEffects effects) {
        var grade = grade(now, effects.getColor(), effects.getColorShare());
        if (grades.isEmpty()) {
            if (grade.sameAs(NO_GRADE)) {
                return;
            }
            grades.add(new Grade(now - step, 1, 1, 0, 0));
        }
        SceneTrack.keep(grades, grade, Grade::sameAs);
    }

    private static final class Line {
        final Track track;
        final boolean drawn;
        final float x;
        final float y;
        final float width;
        final float height;

        Line(Track track, boolean drawn, float x, float y, float width, float height) {
            this.track = track;
            this.drawn = drawn;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }
    }

    private final class Track {
        final String name;
        final Space space;
        final int color;
        final @Nullable Supplier<@Nullable Icon> picture;
        final List<LayerKey> keys = new ArrayList<>();

        Track(String name, Space space, int color, @Nullable Supplier<@Nullable Icon> picture) {
            this.name = name;
            this.space = space;
            this.color = color;
            this.picture = picture;
        }

        void show(double now, float x, float y, float width, float height, float alpha) {
            if (keys.isEmpty()) {
                keys.add(new LayerKey(now - step, x, y, width, height, 0));
            }
            SceneTrack.keep(keys, new LayerKey(now, x, y, width, height, alpha), LayerKey::sameAs);
        }

        void hide(double now) {
            if (!keys.isEmpty()) {
                var last = keys.getLast();
                SceneTrack.keep(
                        keys, new LayerKey(now, last.x(), last.y(), last.width(), last.height(), 0), LayerKey::sameAs);
            }
        }

        Layer getLayer() {
            return new Layer(name, space, color, picture, keys);
        }
    }

    private static float mix(float from, float to, float share) {
        return from + (to - from) * share;
    }

    private static void addShown(List<Layer> layers, Iterable<Track> tracks) {
        for (var track : tracks) {
            if (!track.keys.isEmpty()) {
                layers.add(track.getLayer());
            }
        }
    }

    private static Icon picture(List<String> rows, Font font, int column, float width, float height) {
        var pixelsWide = Math.round(width * DENSITY);
        var pixelsHigh = Math.round(height * DENSITY);
        var image = new BufferedImage(pixelsWide, pixelsHigh, BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setFont(font);
        var edge = GameSceneStage.LINE_SHADOW * 2 * DENSITY;
        var shadow = GameSceneStage.LINE_SHADOW * DENSITY;
        var metrics = graphics.getFontMetrics();
        for (var index = 0; index < rows.size(); index++) {
            var row = rows.get(index);
            var room = pixelsWide - 2 * edge - metrics.stringWidth(row);
            var x = edge + (column == 0 ? 0 : column == 2 ? room : room / 2);
            var y = edge + GameSceneStage.LINE_ROW * DENSITY * index + metrics.getAscent();
            write(graphics, row, x + shadow, y + shadow, new Color(0, 0, 0, SHADOW_ALPHA));
            write(graphics, row, x - shadow / 2, y + shadow / 2, new Color(0, 0, 0, SHADOW_ALPHA));
            write(graphics, row, x, y, Color.WHITE);
        }
        graphics.dispose();
        var rgba = new byte[pixelsWide * pixelsHigh * 4];
        var pixels = image.getRGB(0, 0, pixelsWide, pixelsHigh, null, 0, pixelsWide);
        for (var pixel = 0; pixel < pixels.length; pixel++) {
            rgba[pixel * 4] = (byte) (pixels[pixel] >> 16);
            rgba[pixel * 4 + 1] = (byte) (pixels[pixel] >> 8);
            rgba[pixel * 4 + 2] = (byte) pixels[pixel];
            rgba[pixel * 4 + 3] = (byte) (pixels[pixel] >> 24);
        }
        return new Icon(pixelsWide, pixelsHigh, rgba);
    }

    private static void write(Graphics2D graphics, String row, float x, float y, Color color) {
        graphics.setColor(color);
        graphics.drawString(row, x, y);
    }

    private static @Nullable Font font(String text) {
        var size = GameSceneStage.LINE_FONT * DENSITY;
        if (GameSceneStage.isWritable(text)) {
            try (var file = SceneScreen.class.getClassLoader().getResourceAsStream(FONT)) {
                if (file != null) {
                    var font = Font.createFont(Font.TRUETYPE_FONT, file).deriveFont((float) size);
                    if (font.canDisplayUpTo(text) < 0) {
                        return font;
                    }
                }
            } catch (IOException | FontFormatException unreadable) {
            }
        }
        return LinePictures.fontFor(text, size);
    }
}
