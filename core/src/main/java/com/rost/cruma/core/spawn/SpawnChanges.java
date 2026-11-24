package com.rost.cruma.core.spawn;

import com.rost.cruma.core.spawn.LineDiff.Hunk;
import com.rost.cruma.core.spawn.NpcPosDocument.Batch;
import com.rost.cruma.core.spawn.NpcPosDocument.State;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

public final class SpawnChanges {
    private static final Pattern TOKEN = Pattern.compile("[^\\t ;{}=\\[\\],]+|[\\t ;{}=\\[\\],]");
    private static final Pattern BRACKET = Pattern.compile("\\[([^\\]]*)]");
    private static final Pattern NAME = Pattern.compile("\\bname=\\[([^\\]]*)]");
    private static final Pattern AREA_NAME = Pattern.compile("\\bname\\s*=\\s*\\[([^\\]]*)]");
    private static final int SHORT_VALUE = 16;
    private static final int MAX_WHAT = 3;
    private static final int CONTEXT_LINES = 4000;

    private SpawnChanges() {}

    public record Span(String text, boolean changed) {}

    public record Line(boolean removed, List<Span> spans) {}

    public record Change(int line, String where, String what, List<Line> lines) {
        public boolean isAddition() {
            return lines.stream().noneMatch(Line::removed);
        }
    }

    public record Section(String title, @Nullable Batch batch, List<Change> changes) {}

    public static List<Section> groupIntoSections(
            List<Change> changes, List<Batch> batches, State saved, String savedText, State now) {
        if (batches.isEmpty()) {
            return changes.isEmpty() ? List.of() : List.of(new Section("", null, changes));
        }
        var ranges = computeRanges(saved, savedText);
        var claimed = new boolean[changes.size()];
        var sections = new ArrayList<Section>();
        for (var batch : batches) {
            var members = new ArrayList<Change>();
            for (var i = 0; i < changes.size(); i++) {
                if (!claimed[i] && isOwnChange(batch, changes.get(i), ranges, now)) {
                    claimed[i] = true;
                    members.add(changes.get(i));
                }
            }
            if (!members.isEmpty()) {
                var makers = batch.makers().size();
                var zones = batch.territories().size();
                var count = makers > 0
                        ? makers + (makers == 1 ? " maker: " : " makers: ")
                        : zones + (zones == 1 ? " zone: " : " zones: ");
                sections.add(new Section(count + batch.label(), batch, members));
            }
        }
        var rest = new ArrayList<Change>();
        for (var i = 0; i < changes.size(); i++) {
            if (!claimed[i]) {
                rest.add(changes.get(i));
            }
        }
        if (!rest.isEmpty()) {
            sections.add(new Section("", null, rest));
        }
        return sections;
    }

    private record Ranges(Map<Integer, int[]> makers, Map<Integer, Integer> territories) {}

    public static List<Change> of(List<String> before, List<String> after, List<Hunk> hunks) {
        var changes = new ArrayList<Change>();
        for (var hunk : hunks) {
            splitHunk(before, after, hunk, changes);
        }
        return changes;
    }

    static String describe(List<String> lines) {
        var first = record(lines.getFirst());
        var more = lines.size() - 1;
        return more == 0 ? first : first + " and " + more + (more == 1 ? " more line" : " more lines");
    }

    static String record(String line) {
        var text = line.strip();
        if (text.startsWith("territory_begin")) {
            return "zone " + findBracketed(text);
        }
        if (text.startsWith("npcmaker")) {
            return text.contains("_end") && !text.contains("\t") ? "the end of a maker" : "maker " + makerName(text);
        }
        if (text.startsWith("npc_begin") || text.startsWith("npc_ex_begin")) {
            return "NPC " + findBracketed(text);
        }
        if (text.startsWith("domain_begin")) {
            return "domain " + findBracketed(text);
        }
        if (text.startsWith("area_begin")) {
            var named = AREA_NAME.matcher(text);
            return "area " + (named.find() ? named.group(1) : findBracketed(text));
        }
        if (text.isEmpty()) {
            return "an empty line";
        }
        return "\"" + (text.length() > 40 ? text.substring(0, 40) + "…" : text) + "\"";
    }

    static String findMakerName(List<String> lines, int index) {
        if (index < 0 || index >= lines.size() || lines.get(index).strip().startsWith("npcmaker")) {
            return "";
        }
        for (var i = index - 1; i >= Math.max(0, index - CONTEXT_LINES); i--) {
            var line = lines.get(i).strip();
            if (line.startsWith("npcmaker")) {
                return line.contains("\t") || line.contains(" ") ? makerName(line) : "";
            }
            if (line.startsWith("territory_begin")) {
                return "";
            }
        }
        return "";
    }

    static List<String> diffSettings(String before, String after) {
        var old = settings(before);
        var changed = new ArrayList<String>();
        for (var entry : settings(after).entrySet()) {
            var was = old.remove(entry.getKey());
            var now = entry.getValue();
            if (now.equals(was)) {
                continue;
            }
            var key = entry.getKey().startsWith("#") ? describeValue(now) : entry.getKey();
            if (was == null) {
                changed.add(key + " added");
            } else if (was.length() <= SHORT_VALUE && now.length() <= SHORT_VALUE) {
                changed.add(key + " " + was + " → " + now);
            } else {
                changed.add(key);
            }
        }
        for (var entry : old.entrySet()) {
            changed.add(
                    (entry.getKey().startsWith("#") ? describeValue(entry.getValue()) : entry.getKey()) + " removed");
        }
        return changed;
    }

    public static List<List<Span>> diffWords(String before, String after) {
        var a = tokens(before);
        var b = tokens(after);
        var common = new int[a.size() + 1][b.size() + 1];
        for (var i = a.size() - 1; i >= 0; i--) {
            for (var j = b.size() - 1; j >= 0; j--) {
                common[i][j] = a.get(i).equals(b.get(j))
                        ? common[i + 1][j + 1] + 1
                        : Math.max(common[i + 1][j], common[i][j + 1]);
            }
        }
        var left = new ArrayList<Span>();
        var right = new ArrayList<Span>();
        var i = 0;
        var j = 0;
        while (i < a.size() || j < b.size()) {
            if (i < a.size() && j < b.size() && a.get(i).equals(b.get(j))) {
                add(left, a.get(i++), false);
                add(right, b.get(j++), false);
            } else if (j < b.size() && (i == a.size() || common[i][j + 1] >= common[i + 1][j])) {
                add(right, b.get(j++), true);
            } else {
                add(left, a.get(i++), true);
            }
        }
        return List.of(left, right);
    }

    private static Ranges computeRanges(State saved, String savedText) {
        var file = NpcPosFile.parse(savedText);
        var starts = new ArrayList<Integer>();
        starts.add(0);
        for (var i = 0; i < savedText.length(); i++) {
            if (savedText.charAt(i) == '\n') {
                starts.add(i + 1);
            }
        }
        var makers = new HashMap<Integer, int[]>();
        var parsed = 0;
        for (var i = 0; i < saved.makers().size() && parsed < file.getMakers().size(); i++) {
            if (saved.makers().get(i) == null) {
                continue;
            }
            var maker = file.getMakers().get(parsed++);
            makers.put(i, new int[] {
                maker.line(),
                lineAt(starts, Math.max(maker.span().start(), maker.span().end() - 1))
            });
        }
        var territories = new HashMap<Integer, Integer>();
        parsed = 0;
        for (var i = 0;
                i < saved.territories().size() && parsed < file.getTerritories().size();
                i++) {
            if (saved.territories().get(i) == null) {
                continue;
            }
            territories.put(i, file.getTerritories().get(parsed++).line());
        }
        return new Ranges(makers, territories);
    }

    private static int lineAt(List<Integer> starts, int offset) {
        var low = 0;
        var high = starts.size() - 1;
        while (low < high) {
            var middle = (low + high + 1) >>> 1;
            if (starts.get(middle) <= offset) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        return low + 1;
    }

    private static boolean isOwnChange(Batch batch, Change change, Ranges ranges, State now) {
        var removal = change.lines().stream().allMatch(Line::removed);
        var edit = !removal && !change.isAddition();
        if (!edit && !removal) {
            return false;
        }
        for (var maker : batch.makers()) {
            var range = ranges.makers().get(maker);
            if (range == null || change.line() < range[0] || change.line() > range[1]) {
                continue;
            }
            var deleted = maker >= now.makers().size() || now.makers().get(maker) == null;
            if (edit != deleted) {
                return true;
            }
        }
        if (!removal) {
            return false;
        }
        for (var territory : batch.territories()) {
            var line = ranges.territories().get(territory);
            var deleted =
                    territory >= now.territories().size() || now.territories().get(territory) == null;
            if (line != null && change.line() == line && deleted) {
                return true;
            }
        }
        return false;
    }

    private static void splitHunk(List<String> before, List<String> after, Hunk hunk, List<Change> changes) {
        var removed = hunk.removed();
        var added = hunk.added();
        var a = removed.stream().map(SpawnChanges::record).toList();
        var b = added.stream().map(SpawnChanges::record).toList();
        var common = new int[a.size() + 1][b.size() + 1];
        for (var i = a.size() - 1; i >= 0; i--) {
            for (var j = b.size() - 1; j >= 0; j--) {
                common[i][j] = a.get(i).equals(b.get(j))
                        ? common[i + 1][j + 1] + 1
                        : Math.max(common[i + 1][j], common[i][j + 1]);
            }
        }
        var i = 0;
        var j = 0;
        while (i < a.size() || j < b.size()) {
            if (i < a.size() && j < b.size() && a.get(i).equals(b.get(j))) {
                var spans = diffWords(removed.get(i), added.get(j));
                var what = diffSettings(removed.get(i), added.get(j));
                var text = String.join(", ", what.subList(0, Math.min(what.size(), MAX_WHAT)))
                        + (what.size() > MAX_WHAT ? ", …" : "");
                changes.add(new Change(
                        hunk.oldLine() + i,
                        findMakerName(after, hunk.newLine() - 1 + j),
                        text.isEmpty() ? "Spacing" : text,
                        List.of(new Line(true, spans.get(0)), new Line(false, spans.get(1)))));
                i++;
                j++;
            } else if (i < a.size() && (j == b.size() || common[i + 1][j] >= common[i][j + 1])) {
                var from = i;
                while (i < a.size()
                        && (j == b.size() || common[i + 1][j] >= common[i][j + 1])
                        && !(j < b.size() && a.get(i).equals(b.get(j)))) {
                    i++;
                }
                var run = removed.subList(from, i);
                changes.add(new Change(
                        hunk.oldLine() + from,
                        findMakerName(before, hunk.oldLine() - 1 + from),
                        "Removed " + describe(run),
                        run.stream().map(line -> wholeLine(true, line)).toList()));
            } else {
                var from = j;
                while (j < b.size()
                        && (i == a.size() || common[i + 1][j] < common[i][j + 1])
                        && !(i < a.size() && a.get(i).equals(b.get(j)))) {
                    j++;
                }
                var run = added.subList(from, j);
                changes.add(new Change(
                        hunk.oldLine() - 1 + i,
                        findMakerName(after, hunk.newLine() - 1 + from),
                        "Added " + describe(run),
                        run.stream().map(line -> wholeLine(false, line)).toList()));
            }
        }
    }

    private static Line wholeLine(boolean removed, String line) {
        return new Line(removed, List.of(new Span(line, true)));
    }

    private static String findBracketed(String line) {
        var matcher = BRACKET.matcher(line);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String makerName(String line) {
        var named = NAME.matcher(line);
        return named.find() ? named.group(1) : findBracketed(line);
    }

    private static LinkedHashMap<String, String> settings(String line) {
        var settings = new LinkedHashMap<String, String>();
        var fields = line.strip().split("\t+| (?=\\w+=)");
        for (var i = 0; i < fields.length; i++) {
            var field = fields[i];
            var equals = field.indexOf('=');
            if (equals > 0 && !field.startsWith("{") && !field.startsWith("[")) {
                settings.put(field.substring(0, equals), field.substring(equals + 1));
            } else {
                settings.put("#" + i, field);
            }
        }
        return settings;
    }

    private static String describeValue(String value) {
        if (value.startsWith("{{")) {
            return "corners";
        }
        if (value.startsWith("[")) {
            return "name " + value;
        }
        return value.length() <= SHORT_VALUE ? value : "a value";
    }

    private static List<String> tokens(String line) {
        var tokens = new ArrayList<String>();
        var matcher = TOKEN.matcher(line);
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        return tokens;
    }

    private static void add(List<Span> spans, String token, boolean changed) {
        if (!spans.isEmpty() && spans.getLast().changed() == changed) {
            var last = spans.removeLast();
            spans.add(new Span(last.text() + token, changed));
        } else {
            spans.add(new Span(token, changed));
        }
    }
}
