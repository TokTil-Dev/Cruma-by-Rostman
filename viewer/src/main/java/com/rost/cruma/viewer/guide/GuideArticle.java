package com.rost.cruma.viewer.guide;

import com.rost.cruma.viewer.guide.GuideBlock.Bullets;
import com.rost.cruma.viewer.guide.GuideBlock.Code;
import com.rost.cruma.viewer.guide.GuideBlock.Diagram;
import com.rost.cruma.viewer.guide.GuideBlock.Heading;
import com.rost.cruma.viewer.guide.GuideBlock.Image;
import com.rost.cruma.viewer.guide.GuideBlock.Note;
import com.rost.cruma.viewer.guide.GuideBlock.Paragraph;
import com.rost.cruma.viewer.guide.GuideBlock.Steps;
import com.rost.cruma.viewer.guide.GuideBlock.Table;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.Nullable;

public record GuideArticle(
        String id,
        String title,
        Kind kind,
        String summary,
        @Nullable String tool,
        @Nullable String tab,
        @Nullable String next,
        List<String> related,
        List<GuideBlock> blocks,
        String file) {
    public GuideArticle {
        related = List.copyOf(related);
        blocks = List.copyOf(blocks);
    }

    public enum Kind {
        CONCEPT("concept", "Concept"),
        HOWTO("howto", "How-to"),
        REFERENCE("reference", "Reference"),
        TERM("term", "Term");

        private final String key;
        private final String label;

        Kind(String key, String label) {
            this.key = key;
            this.label = label;
        }

        public static @Nullable Kind of(String key) {
            for (var kind : values()) {
                if (kind.key.equals(key)) {
                    return kind;
                }
            }
            return null;
        }

        public String getKey() {
            return key;
        }

        public String getLabel() {
            return label;
        }
    }

    public String section() {
        return id.substring(0, id.indexOf('/'));
    }

    public boolean isTerm() {
        return section().equals(GuideLibrary.TERMS);
    }

    public List<String> headings() {
        var headings = new ArrayList<String>();
        for (var block : blocks) {
            if (block instanceof Heading heading) {
                headings.add(heading.text());
            }
        }
        return headings;
    }

    public List<String> bodyLines() {
        var lines = new ArrayList<String>();
        for (var block : blocks) {
            switch (block) {
                case Paragraph paragraph -> lines.add(GuideSpan.plain(paragraph.spans()));
                case Bullets bullets -> bullets.items().forEach(item -> lines.add(GuideSpan.plain(item)));
                case Steps steps -> steps.items().forEach(item -> lines.add(GuideSpan.plain(item)));
                case Note note -> lines.add(GuideSpan.plain(note.spans()));
                case Diagram diagram -> lines.add(diagram.caption());
                case Image image -> lines.add(image.caption());
                case Code code -> code.lines().forEach(line -> lines.add(line.strip()));
                case Table table -> {
                    lines.add(row(table.header()));
                    table.rows().forEach(row -> lines.add(row(row)));
                }
                default -> {}
            }
        }
        lines.removeIf(String::isBlank);
        return lines;
    }

    private static String row(List<List<GuideSpan>> cells) {
        var parts = new ArrayList<String>();
        for (var cell : cells) {
            parts.add(GuideSpan.plain(cell));
        }
        return String.join(" · ", parts);
    }
}
