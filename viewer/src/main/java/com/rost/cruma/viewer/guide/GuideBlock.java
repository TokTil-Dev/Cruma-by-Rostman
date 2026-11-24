package com.rost.cruma.viewer.guide;

import java.util.List;

public sealed interface GuideBlock {
    record Heading(int level, String text) implements GuideBlock {}

    record Paragraph(List<GuideSpan> spans) implements GuideBlock {
        public Paragraph {
            spans = List.copyOf(spans);
        }
    }

    record Bullets(List<List<GuideSpan>> items) implements GuideBlock {
        public Bullets {
            items = items.stream().map(List::copyOf).toList();
        }
    }

    record Steps(List<List<GuideSpan>> items, int first) implements GuideBlock {
        public Steps {
            items = items.stream().map(List::copyOf).toList();
        }

        public Steps(List<List<GuideSpan>> items) {
            this(items, 1);
        }
    }

    record Note(boolean warning, List<GuideSpan> spans) implements GuideBlock {
        public Note {
            spans = List.copyOf(spans);
        }
    }

    record Diagram(String id, String caption) implements GuideBlock {}

    record Image(String name, String caption) implements GuideBlock {}

    record Action(String id, String label) implements GuideBlock {}

    record Code(List<String> lines, Within within) implements GuideBlock {
        public Code {
            lines = List.copyOf(lines);
        }

        public Code(List<String> lines) {
            this(lines, Within.BODY);
        }
    }

    enum Within {
        BODY,
        BULLETS,
        STEPS
    }

    record Table(List<List<GuideSpan>> header, List<List<List<GuideSpan>>> rows) implements GuideBlock {
        public Table {
            header = header.stream().map(List::copyOf).toList();
            rows = rows.stream()
                    .map(row -> row.stream().map(List::copyOf).toList())
                    .toList();
        }
    }
}
