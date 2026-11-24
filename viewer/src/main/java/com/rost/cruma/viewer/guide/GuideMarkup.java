package com.rost.cruma.viewer.guide;

import com.rost.cruma.viewer.guide.GuideArticle.Kind;
import com.rost.cruma.viewer.guide.GuideBlock.Action;
import com.rost.cruma.viewer.guide.GuideBlock.Bullets;
import com.rost.cruma.viewer.guide.GuideBlock.Diagram;
import com.rost.cruma.viewer.guide.GuideBlock.Heading;
import com.rost.cruma.viewer.guide.GuideBlock.Image;
import com.rost.cruma.viewer.guide.GuideBlock.Note;
import com.rost.cruma.viewer.guide.GuideBlock.Paragraph;
import com.rost.cruma.viewer.guide.GuideBlock.Steps;
import com.rost.cruma.viewer.guide.GuideBlock.Table;
import com.rost.cruma.viewer.guide.GuideBlock.Within;
import com.rost.cruma.viewer.guide.GuideSpan.Code;
import com.rost.cruma.viewer.guide.GuideSpan.Key;
import com.rost.cruma.viewer.guide.GuideSpan.Link;
import com.rost.cruma.viewer.guide.GuideSpan.Name;
import com.rost.cruma.viewer.guide.GuideSpan.Text;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

public final class GuideMarkup {
    public static final int MOST_COLUMNS = 4;

    public static final int MOST_CODE_CHARACTERS = 60;

    public static final char EM_DASH = (char) 0x2014;

    private static final String FENCE = "---";
    private static final String CODE_FENCE = "```";
    private static final Pattern CODE_OPENING = Pattern.compile("```[A-Za-z0-9_+-]*");
    private static final Set<String> KEYS = Set.of("title", "kind", "summary", "tool", "tab", "next", "related");
    private static final Pattern STEP = Pattern.compile("(\\d+)\\. (.*)");
    private static final Pattern PICTURE = Pattern.compile("!\\[([^\\]]*)]\\(([a-z]+):([^)]+)\\)");
    private static final Pattern ACTION = Pattern.compile("\\[([^\\]]+)]\\(action:([a-z0-9-]+)\\)");
    private static final Pattern ID = Pattern.compile("[a-z0-9-]+/[a-z0-9-]+");
    private static final Pattern SEPARATOR = Pattern.compile("\\|(\\s*:?-{3,}:?\\s*\\|)+");
    private static final String NOTE = "> **Note:** ";
    private static final String WARNING = "> **Warning:** ";

    private GuideMarkup() {}

    public static final class Problem extends Exception {
        private final String file;
        private final int line;

        public Problem(String file, int line, String message) {
            super(file + (line > 0 ? ":" + line : "") + ": " + message);
            this.file = file;
            this.line = line;
        }

        public String getFile() {
            return file;
        }

        public int getLine() {
            return line;
        }
    }

    public static GuideArticle parse(String id, String file, String source) throws Problem {
        var lines = source.replace("\r\n", "\n").split("\n", -1);
        if (lines.length == 0 || !lines[0].equals(FENCE)) {
            throw new Problem(file, 1, "an article starts with front matter: a line \"---\"");
        }
        var front = new LinkedHashMap<String, String>();
        var index = 1;
        for (; index < lines.length && !lines[index].equals(FENCE); index++) {
            var line = lines[index];
            var colon = line.indexOf(": ");
            if (colon <= 0) {
                throw new Problem(file, index + 1, "a front matter line is \"key: value\", not \"" + line + "\"");
            }
            var key = line.substring(0, colon);
            if (!KEYS.contains(key)) {
                throw new Problem(file, index + 1, "unknown front matter key \"" + key + "\"");
            }
            if (front.put(key, line.substring(colon + 2).strip()) != null) {
                throw new Problem(file, index + 1, "\"" + key + "\" is given twice");
            }
        }
        if (index >= lines.length) {
            throw new Problem(file, lines.length, "the front matter has no closing \"---\"");
        }
        var title = required(front, "title", file);
        var kindWord = required(front, "kind", file);
        var kind = Kind.of(kindWord);
        if (kind == null) {
            throw new Problem(file, 0, "kind is concept, howto, reference or term, not \"" + kindWord + "\"");
        }
        var summary = required(front, "summary", file);
        var next = front.get("next");
        var related = new ArrayList<String>();
        var relatedText = front.getOrDefault("related", "");
        for (var part : relatedText.split(",")) {
            if (!part.isBlank()) {
                related.add(part.strip());
            }
        }
        for (var target : related) {
            requireId(target, file, 0);
        }
        if (next != null) {
            requireId(next, file, 0);
        }
        var blocks = new Body(file, lines, index + 1).read();
        return new GuideArticle(
                id, title, kind, summary, front.get("tool"), front.get("tab"), next, related, blocks, file);
    }

    public static List<GuideSpan> spans(String text, String file, int line) throws Problem {
        var spans = new ArrayList<GuideSpan>();
        var plain = new StringBuilder();
        var at = 0;
        while (at < text.length()) {
            var c = text.charAt(at);
            if (c == EM_DASH) {
                throw new Problem(file, line, "the em-dash is not used in the Guide: write two sentences or a colon");
            }
            if (text.startsWith("**", at)) {
                var end = text.indexOf("**", at + 2);
                if (end < 0) {
                    throw new Problem(file, line, "** has no closing **");
                }
                flush(plain, spans);
                spans.add(new Name(inner(text.substring(at + 2, end), file, line)));
                at = end + 2;
            } else if (c == '`') {
                var end = text.indexOf('`', at + 1);
                if (end < 0) {
                    throw new Problem(file, line, "` has no closing `");
                }
                flush(plain, spans);
                spans.add(new Code(text.substring(at + 1, end)));
                at = end + 1;
            } else if (text.startsWith("<kbd>", at)) {
                var end = text.indexOf("</kbd>", at);
                if (end < 0) {
                    throw new Problem(file, line, "<kbd> has no closing </kbd>");
                }
                flush(plain, spans);
                spans.add(new Key(key(text.substring(at + 5, end), file, line)));
                at = end + 6;
            } else if (c == '[') {
                var close = text.indexOf("](", at);
                var end = close < 0 ? -1 : text.indexOf(')', close);
                if (end < 0) {
                    throw new Problem(file, line, "a link is written [text](guide:section/name)");
                }
                var target = text.substring(close + 2, end);
                if (!target.startsWith("guide:")) {
                    throw new Problem(
                            file,
                            line,
                            "a link inside text goes to an article, guide:section/name, not \"" + target + "\"");
                }
                var id = target.substring("guide:".length());
                requireId(id, file, line);
                flush(plain, spans);
                spans.add(new Link(inner(text.substring(at + 1, close), file, line), id));
                at = end + 1;
            } else if (c == '*' || c == '<' || c == '\\' || c == '|' || c == '#') {
                throw new Problem(file, line, "\"" + c + "\" is markup the Guide does not have");
            } else {
                plain.append(c);
                at++;
            }
        }
        flush(plain, spans);
        return spans;
    }

    private static String key(String text, String file, int line) throws Problem {
        var bare = text.replace("[", "").replace("]", "");
        if (!bare.isEmpty()) {
            inner(bare, file, line);
        }
        if (text.isBlank()) {
            throw new Problem(file, line, "a key has no words");
        }
        return text;
    }

    private static String inner(String text, String file, int line) throws Problem {
        for (var c : new char[] {'*', '`', '<', '[', ']', EM_DASH}) {
            if (text.indexOf(c) >= 0) {
                throw new Problem(file, line, "\"" + c + "\" cannot stand inside a name, key or link");
            }
        }
        if (text.isBlank()) {
            throw new Problem(file, line, "a name, key or link has no words");
        }
        return text;
    }

    private static void flush(StringBuilder plain, List<GuideSpan> spans) {
        if (!plain.isEmpty()) {
            spans.add(new Text(plain.toString()));
            plain.setLength(0);
        }
    }

    private static String required(Map<String, String> front, String key, String file) throws Problem {
        var value = front.get(key);
        if (value == null || value.isBlank()) {
            throw new Problem(file, 0, "the front matter needs \"" + key + "\"");
        }
        return value;
    }

    private static void requireId(String id, String file, int line) throws Problem {
        if (!ID.matcher(id).matches()) {
            throw new Problem(file, line, "\"" + id + "\" is not an article id such as geodata/edit-cells");
        }
    }

    private static final class Body {
        private final String file;
        private final String[] lines;
        private final List<GuideBlock> blocks = new ArrayList<>();
        private int at;
        private final StringBuilder paragraph = new StringBuilder();
        private int paragraphLine;
        private @Nullable List<List<GuideSpan>> items;
        private boolean numbered;
        private final StringBuilder item = new StringBuilder();
        private int itemLine;
        private int firstNumber;
        private int lastNumber;
        private @Nullable Within joinable;
        private int joinableNumber;
        private @Nullable Within resumable;
        private int resumableNumber;

        Body(String file, String[] lines, int start) {
            this.file = file;
            this.lines = lines;
            this.at = start;
        }

        List<GuideBlock> read() throws Problem {
            for (; at < lines.length; at++) {
                var line = lines[at];
                var number = at + 1;
                if (line.isBlank()) {
                    endAll();
                    continue;
                }
                if (line.startsWith(CODE_FENCE)) {
                    code(number);
                    continue;
                }
                if (line.strip().startsWith(CODE_FENCE)) {
                    throw new Problem(
                            file,
                            number,
                            "a code block starts at the start of its line: under a list item it ends the item, and"
                                    + " the list goes on after it");
                }
                var resumed = resumable;
                var resumedNumber = resumableNumber;
                resumable = null;
                if (line.startsWith("#")) {
                    endAll();
                    heading(line, number);
                } else if (line.startsWith("- ")) {
                    startItem(false, line.substring(2), number, 0);
                } else if (STEP.matcher(line).matches()) {
                    var matcher = STEP.matcher(line);
                    matcher.matches();
                    var value = Integer.parseInt(matcher.group(1));
                    var after = items != null && numbered
                            ? lastNumber
                            : items == null && resumed == Within.STEPS ? resumedNumber : 0;
                    if (value != after + 1) {
                        throw new Problem(file, number, "steps count from 1 up without gaps");
                    }
                    startItem(true, matcher.group(2), number, value);
                } else if (line.startsWith(">")) {
                    endAll();
                    note(number);
                } else if (line.startsWith("|")) {
                    endAll();
                    table(number);
                } else if (line.startsWith("![")) {
                    endAll();
                    picture(line, number);
                } else if (line.startsWith("[") && line.contains("](action:")) {
                    endAll();
                    action(line, number);
                } else if (line.startsWith(" ") || line.startsWith("\t")) {
                    if (items == null) {
                        throw new Problem(file, number, "a line starts with a space outside a list");
                    }
                    item.append(' ').append(line.strip());
                } else if (items != null) {
                    item.append(' ').append(line.strip());
                } else {
                    if (paragraph.isEmpty()) {
                        paragraphLine = number;
                    } else {
                        paragraph.append(' ');
                    }
                    paragraph.append(line.strip());
                }
                joinable = null;
            }
            endAll();
            return blocks;
        }

        private void heading(String line, int number) throws Problem {
            int level;
            if (line.startsWith("### ")) {
                level = 3;
            } else if (line.startsWith("## ")) {
                level = 2;
            } else {
                throw new Problem(
                        file,
                        number,
                        "a heading is \"## Heading\" or \"### Heading\": the title comes from the front matter");
            }
            var text = line.substring(level + 1).strip();
            if (text.isEmpty() || !GuideSpan.plain(spans(text, file, number)).equals(text)) {
                throw new Problem(file, number, "a heading is plain words");
            }
            blocks.add(new Heading(level, text));
        }

        private void startItem(boolean steps, String text, int number, int value) throws Problem {
            endParagraph();
            if (items != null && numbered != steps) {
                endList();
            }
            if (items != null) {
                endItem();
            } else {
                items = new ArrayList<>();
                numbered = steps;
                firstNumber = value;
            }
            lastNumber = value;
            item.append(text.strip());
            itemLine = number;
        }

        private void endItem() throws Problem {
            if (items != null && !item.isEmpty()) {
                items.add(spans(item.toString(), file, itemLine));
            }
            item.setLength(0);
        }

        private void endList() throws Problem {
            if (items == null) {
                return;
            }
            endItem();
            blocks.add(numbered ? new Steps(items, firstNumber) : new Bullets(items));
            joinable = numbered ? Within.STEPS : Within.BULLETS;
            joinableNumber = lastNumber;
            items = null;
        }

        private void endParagraph() throws Problem {
            if (!paragraph.isEmpty()) {
                blocks.add(new Paragraph(spans(paragraph.toString(), file, paragraphLine)));
                paragraph.setLength(0);
            }
        }

        private void endAll() throws Problem {
            endParagraph();
            endList();
        }

        private void code(int number) throws Problem {
            var opening = lines[at].stripTrailing();
            if (!CODE_OPENING.matcher(opening).matches()) {
                throw new Problem(
                        file,
                        number,
                        "a code block opens with a line ``` or ```word, such as ```schema, and nothing more");
            }
            endParagraph();
            endList();
            var within = Within.BODY;
            var last = 0;
            if (joinable != null) {
                within = joinable;
                last = joinableNumber;
            } else if (resumable != null) {
                within = resumable;
                last = resumableNumber;
            }
            var code = new ArrayList<String>();
            var closed = false;
            while (++at < lines.length) {
                var line = lines[at].stripTrailing();
                if (line.equals(CODE_FENCE)) {
                    closed = true;
                    break;
                }
                if (line.strip().equals(CODE_FENCE)) {
                    throw new Problem(file, at + 1, "the closing ``` of a code block stands at the start of its line");
                }
                codeLine(line, at + 1);
                code.add(line);
            }
            if (!closed) {
                throw new Problem(file, number, "this code block has no closing line ```");
            }
            if (code.stream().allMatch(String::isEmpty)) {
                throw new Problem(file, number, "this code block holds no lines");
            }
            blocks.add(new GuideBlock.Code(code, within));
            joinable = null;
            resumable = within == Within.BODY ? null : within;
            resumableNumber = last;
        }

        private void codeLine(String line, int number) throws Problem {
            if (line.indexOf('\t') >= 0) {
                throw new Problem(file, number, "a code block is indented with spaces, not with a tab");
            }
            if (line.indexOf(EM_DASH) >= 0) {
                throw new Problem(file, number, "the em-dash is not used in the Guide, in a code block either");
            }
            var length = line.codePointCount(0, line.length());
            if (length > MOST_CODE_CHARACTERS) {
                throw new Problem(
                        file,
                        number,
                        "a line of a code block has at most " + MOST_CODE_CHARACTERS + " characters, this one "
                                + length);
            }
        }

        private void note(int number) throws Problem {
            var first = lines[at];
            boolean warning;
            String text;
            if (first.startsWith(NOTE)) {
                warning = false;
                text = first.substring(NOTE.length());
            } else if (first.startsWith(WARNING)) {
                warning = true;
                text = first.substring(WARNING.length());
            } else {
                throw new Problem(
                        file, number, "a quote is a note, \"> **Note:** text\", or a warning, \"> **Warning:** text\"");
            }
            var all = new StringBuilder(text.strip());
            while (at + 1 < lines.length && lines[at + 1].startsWith(">")) {
                at++;
                var more = lines[at];
                if (!more.startsWith("> ") || more.startsWith(NOTE) || more.startsWith(WARNING)) {
                    throw new Problem(
                            file,
                            at + 1,
                            "a note goes on with lines \"> text\"; leave an empty line before the next note");
                }
                all.append(' ').append(more.substring(2).strip());
            }
            blocks.add(new Note(warning, spans(all.toString(), file, number)));
        }

        private void picture(String line, int number) throws Problem {
            var matcher = PICTURE.matcher(line.strip());
            if (!matcher.matches()) {
                throw new Problem(
                        file,
                        number,
                        "a picture is ![caption](diagram:name) or ![caption](image:name.png), alone on its line");
            }
            var caption = matcher.group(1).strip();
            if (!GuideSpan.plain(spans(caption, file, number)).equals(caption)) {
                throw new Problem(file, number, "a caption is plain words");
            }
            var name = matcher.group(3);
            switch (matcher.group(2)) {
                case "diagram" -> blocks.add(new Diagram(name, caption));
                case "image" -> {
                    if (!name.matches("[a-z0-9-]+\\.png")) {
                        throw new Problem(file, number, "an image is a .png file of guide/images, named in lower case");
                    }
                    blocks.add(new Image(name, caption));
                }
                default ->
                    throw new Problem(
                            file, number, "a picture is a diagram: or an image:, not " + matcher.group(2) + ":");
            }
        }

        private void action(String line, int number) throws Problem {
            var matcher = ACTION.matcher(line.strip());
            if (!matcher.matches()) {
                throw new Problem(file, number, "an action is [Show me in the world](action:name), alone on its line");
            }
            blocks.add(new Action(matcher.group(2), inner(matcher.group(1), file, number)));
        }

        private void table(int number) throws Problem {
            var header = cells(lines[at], number);
            if (header.size() < 2 || header.size() > MOST_COLUMNS) {
                throw new Problem(file, number, "a table has 2 to " + MOST_COLUMNS + " columns");
            }
            if (at + 1 >= lines.length
                    || !SEPARATOR.matcher(lines[at + 1].strip()).matches()) {
                throw new Problem(file, number + 1, "the header of a table is followed by a line such as |---|---|");
            }
            at++;
            if (cells(lines[at], at + 1).size() != header.size()) {
                throw new Problem(file, at + 1, "the line under the header has " + header.size() + " columns too");
            }
            var rows = new ArrayList<List<List<GuideSpan>>>();
            while (at + 1 < lines.length && lines[at + 1].startsWith("|")) {
                at++;
                var row = cells(lines[at], at + 1);
                if (row.size() != header.size()) {
                    throw new Problem(
                            file, at + 1, "this row has " + row.size() + " cells, the header " + header.size());
                }
                rows.add(row);
            }
            if (rows.isEmpty()) {
                throw new Problem(file, number, "a table has at least one row under its header");
            }
            blocks.add(new Table(header, rows));
        }

        private List<List<GuideSpan>> cells(String line, int number) throws Problem {
            var text = line.strip();
            if (!text.startsWith("|") || !text.endsWith("|") || text.length() < 2) {
                throw new Problem(file, number, "a row of a table starts and ends with |");
            }
            var cells = new ArrayList<List<GuideSpan>>();
            for (var cell : text.substring(1, text.length() - 1).split("\\|", -1)) {
                cells.add(spans(cell.strip(), file, number));
            }
            return cells;
        }
    }
}
