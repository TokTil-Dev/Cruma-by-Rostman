package com.rost.cruma.viewer.ui;

import com.rost.cruma.viewer.guide.GuideSpan;
import com.rost.cruma.viewer.guide.GuideSpan.Code;
import com.rost.cruma.viewer.guide.GuideSpan.Key;
import com.rost.cruma.viewer.guide.GuideSpan.Link;
import com.rost.cruma.viewer.guide.GuideSpan.Name;
import imgui.ImColor;
import imgui.ImDrawList;
import imgui.ImFont;
import imgui.ImGui;
import imgui.flag.ImGuiMouseCursor;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import org.jetbrains.annotations.Nullable;

public final class GuideText {
    public static final float LINE = 20;

    static final float TUCK = 2;

    static final int CODE_FILL = ImColor.rgba(255, 255, 255, 14);

    static final int SIZE = 14;

    private static final float KEY_PADDING = 10;
    private static final float KEY_LEAST = 18;
    private static final float CODE_PADDING = 4;
    private static final String PUNCTUATION = ",.:;!?)";

    private GuideText() {}

    public enum Style {
        TEXT,
        NAME,
        CODE,
        KEY,
        LINK
    }

    @FunctionalInterface
    public interface Measure {
        float getWidth(Style style, String text);
    }

    public record Piece(
            Style style,
            String text,
            float x,
            float width,
            @Nullable String link) {}

    public record Line(List<Piece> pieces, float width) {}

    private record Part(Style style, String text, @Nullable String link) {}

    public static List<Line> layout(List<GuideSpan> spans, float width, Measure measure) {
        var flow = new Flow(width, measure);
        for (var word : words(spans)) {
            flow.word(word);
        }
        return flow.finish();
    }

    private static final class Flow {
        private static final String BREAKS = "/\\_.-,";

        private final float width;
        private final Measure measure;
        private final float space;
        private final List<Line> lines = new ArrayList<>();
        private final List<Piece> pieces = new ArrayList<>();
        private float x;
        private @Nullable Part last;

        Flow(float width, Measure measure) {
            this.width = width;
            this.measure = measure;
            space = measure.getWidth(Style.TEXT, " ");
        }

        void word(List<Part> word) {
            var wordWidth = 0f;
            for (var part : word) {
                wordWidth += measure.getWidth(part.style(), part.text());
            }
            if (!pieces.isEmpty() && x + space + wordWidth > width) {
                newLine();
            }
            if (wordWidth > width) {
                broken(word);
                return;
            }
            for (var index = 0; index < word.size(); index++) {
                var part = word.get(index);
                var partWidth = measure.getWidth(part.style(), part.text());
                var gap = index == 0 && !pieces.isEmpty();
                if (gap && last != null && joins(last, part)) {
                    var previous = pieces.removeLast();
                    var text = previous.text() + " " + part.text();
                    pieces.add(new Piece(
                            part.style(), text, previous.x(), measure.getWidth(part.style(), text), part.link()));
                    x = previous.x() + pieces.getLast().width();
                } else {
                    if (gap) {
                        x += space;
                    }
                    if (index > 0 && tucks(last, part)) {
                        var value = pieces.removeLast();
                        pieces.add(
                                new Piece(value.style(), value.text(), value.x(), value.width() - TUCK, value.link()));
                        x -= TUCK;
                    }
                    pieces.add(new Piece(part.style(), part.text(), x, partWidth, part.link()));
                    x += partWidth;
                }
                last = part;
            }
        }

        List<Line> finish() {
            if (!pieces.isEmpty()) {
                lines.add(new Line(List.copyOf(pieces), x));
            }
            return lines;
        }

        private void broken(List<Part> word) {
            for (var part : word) {
                var text = part.text();
                while (!text.isEmpty()) {
                    var cut = cut(part.style(), text, width - x);
                    if (cut == 0) {
                        if (!pieces.isEmpty()) {
                            newLine();
                            continue;
                        }
                        cut = Character.charCount(text.codePointAt(0));
                    }
                    var piece = text.substring(0, cut);
                    var pieceWidth = measure.getWidth(part.style(), piece);
                    pieces.add(new Piece(part.style(), piece, x, pieceWidth, part.link()));
                    x += pieceWidth;
                    last = part;
                    text = text.substring(cut);
                    if (!text.isEmpty()) {
                        newLine();
                    }
                }
            }
            last = null;
        }

        private int cut(Style style, String text, float room) {
            var fits = 0;
            for (var end = 1; end <= text.length(); end++) {
                if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) {
                    continue;
                }
                if (measure.getWidth(style, text.substring(0, end)) > room) {
                    break;
                }
                fits = end;
            }
            if (fits == text.length() || fits == 0) {
                return fits;
            }
            for (var end = fits; end > fits / 2; end--) {
                if (BREAKS.indexOf(text.charAt(end - 1)) >= 0) {
                    return end;
                }
            }
            return fits;
        }

        private void newLine() {
            lines.add(new Line(List.copyOf(pieces), x));
            pieces.clear();
            x = 0;
            last = null;
        }
    }

    public static Measure measure(ImFont text, ImFont mono) {
        return (style, value) -> switch (style) {
            case CODE -> width(mono, value) + 2 * CODE_PADDING;
            case KEY -> Math.max(KEY_LEAST, width(text, value) + KEY_PADDING);
            default -> width(text, value);
        };
    }

    public static @Nullable String draw(
            String id,
            List<Line> lines,
            ImFont text,
            ImFont mono,
            int color,
            @Nullable BiConsumer<String, float[]> links) {
        var draw = ImGui.getWindowDrawList();
        var left = (float) Math.round(ImGui.getCursorScreenPosX());
        var top = (float) Math.round(ImGui.getCursorScreenPosY());
        String pressed = null;
        ImGui.pushID(id);
        var count = 0;
        for (var row = 0; row < lines.size(); row++) {
            var y = top + row * LINE;
            var textY = y + (LINE - SIZE) * .5f - 1;
            for (var piece : lines.get(row).pieces()) {
                var x = (float) Math.round(left + piece.x());
                switch (piece.style()) {
                    case CODE -> {
                        draw.addRectFilled(x, y + 1, x + piece.width(), y + LINE - 1, CODE_FILL, 4f);
                        draw.addText(mono, SIZE, x + CODE_PADDING, textY, HudWidgets.TEXT, piece.text());
                    }
                    case KEY -> keycap(draw, text, x, y, piece);
                    case NAME -> draw.addText(text, SIZE, x, textY, HudWidgets.TEXT, piece.text());
                    case LINK -> {
                        ImGui.setCursorScreenPos(x, y);
                        if (ImGui.invisibleButton("##link" + count++, Math.max(1, piece.width()), LINE)) {
                            pressed = piece.link();
                        }
                        var hovered = ImGui.isItemHovered();
                        if (links != null) {
                            links.accept(piece.link(), new float[] {x, y, x + piece.width(), y + LINE});
                        }
                        draw.addText(text, SIZE, x, textY, HudWidgets.ACCENT, piece.text());
                        if (hovered) {
                            ImGui.setMouseCursor(ImGuiMouseCursor.Hand);
                            var under = textY + SIZE + 1;
                            draw.addLine(x, under, x + piece.width(), under, HudWidgets.ACCENT, 1);
                        }
                    }
                    default -> draw.addText(text, SIZE, x, textY, color, piece.text());
                }
            }
        }
        ImGui.popID();
        ImGui.setCursorScreenPos(left, top + lines.size() * LINE);
        ImGui.dummy(0, 0);
        return pressed;
    }

    public static float height(List<Line> lines) {
        return lines.size() * LINE;
    }

    private static boolean tucks(@Nullable Part before, Part after) {
        return before != null
                && before.style() == Style.CODE
                && after.style() != Style.CODE
                && !after.text().isEmpty()
                && PUNCTUATION.indexOf(after.text().charAt(0)) >= 0;
    }

    private static boolean joins(Part before, Part after) {
        return before.style() == after.style()
                && before.style() != Style.CODE
                && before.style() != Style.KEY
                && Objects.equals(before.link(), after.link());
    }

    private static List<List<Part>> words(List<GuideSpan> spans) {
        var words = new ArrayList<List<Part>>();
        var word = new ArrayList<Part>();
        for (var span : spans) {
            var style = style(span);
            var link = span instanceof Link to ? to.target() : null;
            if (style == Style.CODE || style == Style.KEY) {
                word.add(new Part(style, span.text(), null));
                continue;
            }
            var tokens = span.text().split(" ", -1);
            for (var index = 0; index < tokens.length; index++) {
                if (index > 0 && !word.isEmpty()) {
                    words.add(word);
                    word = new ArrayList<>();
                }
                if (!tokens[index].isEmpty()) {
                    word.add(new Part(style, tokens[index], link));
                }
            }
        }
        if (!word.isEmpty()) {
            words.add(word);
        }
        return words;
    }

    private static Style style(GuideSpan span) {
        return switch (span) {
            case Name _ -> Style.NAME;
            case Code _ -> Style.CODE;
            case Key _ -> Style.KEY;
            case Link _ -> Style.LINK;
            default -> Style.TEXT;
        };
    }

    private static float width(ImFont font, String text) {
        return font.calcTextSizeA(SIZE, Float.MAX_VALUE, 0, text).x;
    }

    private static void keycap(ImDrawList draw, ImFont font, float x, float y, Piece piece) {
        var height = SIZE + 4;
        var top = y + (LINE - height) * .5f;
        draw.addRectFilled(x, top, x + piece.width(), top + height, ImColor.rgba(255, 255, 255, 22), 4f);
        draw.addRect(x, top, x + piece.width(), top + height, ImColor.rgba(255, 255, 255, 60), 4, 0, 1);
        var textWidth = width(font, piece.text());
        draw.addText(font, SIZE, x + (piece.width() - textWidth) * .5f, top + 2, HudWidgets.TEXT, piece.text());
    }
}
