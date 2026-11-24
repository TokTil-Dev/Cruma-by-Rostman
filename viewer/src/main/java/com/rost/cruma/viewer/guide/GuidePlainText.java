package com.rost.cruma.viewer.guide;

import com.rost.cruma.viewer.guide.GuideBlock.Action;
import com.rost.cruma.viewer.guide.GuideBlock.Bullets;
import com.rost.cruma.viewer.guide.GuideBlock.Code;
import com.rost.cruma.viewer.guide.GuideBlock.Diagram;
import com.rost.cruma.viewer.guide.GuideBlock.Heading;
import com.rost.cruma.viewer.guide.GuideBlock.Image;
import com.rost.cruma.viewer.guide.GuideBlock.Note;
import com.rost.cruma.viewer.guide.GuideBlock.Paragraph;
import com.rost.cruma.viewer.guide.GuideBlock.Steps;
import com.rost.cruma.viewer.guide.GuideBlock.Table;
import com.rost.cruma.viewer.guide.GuideSpan.Link;
import java.util.ArrayList;
import java.util.List;

public final class GuidePlainText {
    private GuidePlainText() {}

    public static String of(GuideArticle article, GuideLibrary library) {
        var lines = new ArrayList<String>();
        lines.add(article.title());
        var section = library.findSection(article.section());
        var where = article.isTerm() ? "Glossary" : section == null ? article.section() : section.title();
        lines.add(article.kind().getLabel() + " · " + where + " · " + article.id());
        lines.add(article.summary());
        for (var block : article.blocks()) {
            lines.add("");
            block(block, lines);
        }
        var next = article.next() == null ? null : library.getArticle(article.next());
        if (next != null) {
            lines.add("");
            lines.add("Next: " + next.title() + " [" + next.id() + "]");
        }
        var related = new ArrayList<String>();
        for (var id : article.related()) {
            var found = library.getArticle(id);
            if (found != null) {
                related.add(found.title() + " [" + found.id() + "]");
            }
        }
        if (!related.isEmpty()) {
            lines.add("");
            lines.add("Related: " + String.join(", ", related));
        }
        return String.join("\n", lines);
    }

    static String text(List<GuideSpan> spans) {
        var text = new StringBuilder();
        for (var span : spans) {
            text.append(span.text());
            if (span instanceof Link link) {
                text.append(" [").append(link.target()).append(']');
            }
        }
        return text.toString();
    }

    private static void block(GuideBlock block, List<String> lines) {
        switch (block) {
            case Heading heading -> lines.add(heading.text());
            case Paragraph paragraph -> lines.add(text(paragraph.spans()));
            case Bullets bullets -> bullets.items().forEach(item -> lines.add("- " + text(item)));
            case Steps steps -> {
                for (var index = 0; index < steps.items().size(); index++) {
                    lines.add(
                            (steps.first() + index) + ". " + text(steps.items().get(index)));
                }
            }
            case Code code -> {
                lines.add("```");
                lines.addAll(code.lines());
                lines.add("```");
            }
            case Note note -> lines.add((note.warning() ? "Warning: " : "Note: ") + text(note.spans()));
            case Diagram diagram -> lines.add("Picture: " + diagram.caption());
            case Image image -> lines.add("Picture: " + image.caption());
            case Action action -> lines.add("Button in the Guide: " + action.label());
            case Table table -> {
                lines.add(row(table.header()));
                table.rows().forEach(row -> lines.add(row(row)));
            }
        }
    }

    private static String row(List<List<GuideSpan>> cells) {
        var parts = new ArrayList<String>();
        for (var cell : cells) {
            parts.add(text(cell));
        }
        return "| " + String.join(" | ", parts) + " |";
    }
}
