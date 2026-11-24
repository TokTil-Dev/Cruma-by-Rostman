package com.rost.cruma.core.npc;

import com.rost.cruma.core.spawn.SpawnText;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.UnaryOperator;
import org.jetbrains.annotations.Nullable;

public final class NpcDataDocument {
    private final NpcDataFile file;
    private final Deque<Step> undo = new ArrayDeque<>();
    private final Deque<Step> redo = new ArrayDeque<>();
    private final int maxHistory;
    private final Map<String, NpcRecord> addedRecords = new HashMap<>();
    private int lines = -1;
    private State state = State.NONE;
    private State saved = State.NONE;
    private long revision;

    public NpcDataDocument(NpcDataFile file) {
        this(file, 256);
    }

    public NpcDataDocument(NpcDataFile file, int maxHistory) {
        this.file = Objects.requireNonNull(file, "file");
        this.maxHistory = maxHistory;
    }

    public record State(Map<Integer, Map<String, String>> edits, List<String> added, SortedSet<Integer> removed) {
        public static final State NONE = new State(Map.of());

        public State {
            var copy = new TreeMap<Integer, Map<String, String>>();
            edits.forEach((record, values) -> {
                if (!values.isEmpty()) {
                    copy.put(record, Collections.unmodifiableMap(new LinkedHashMap<>(values)));
                }
            });
            edits = Collections.unmodifiableMap(copy);
            added = List.copyOf(added);
            removed = Collections.unmodifiableSortedSet(new TreeSet<>(removed));
        }

        public State(Map<Integer, Map<String, String>> edits) {
            this(edits, List.of(), new TreeSet<>());
        }

        public @Nullable String editedValue(int record, String key) {
            var values = edits.get(record);
            return values == null ? null : values.get(key);
        }

        public boolean isEdited(int record) {
            return edits.containsKey(record) || removed.contains(record);
        }

        public boolean isRemoved(int record) {
            return removed.contains(record);
        }

        public boolean isEmpty() {
            return edits.isEmpty() && added.isEmpty() && removed.isEmpty();
        }

        State withEdits(Map<Integer, Map<String, String>> value) {
            return new State(value, added, removed);
        }
    }

    public record Batch(String label, Set<Integer> records, Set<String> keys) {
        public Batch {
            records = Set.copyOf(records);
            keys = Set.copyOf(keys);
        }
    }

    public record Copy(State state, int index, int id, String name) {}

    private record Step(
            String label,
            State before,
            State after,
            @Nullable Batch batch) {}

    static String renameHeader(String text, int id, String name) {
        var at = 0;
        var tokens = new int[8];
        for (var token = 0; token < 4; token++) {
            while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
                at++;
            }
            tokens[token * 2] = at;
            while (at < text.length() && !Character.isWhitespace(text.charAt(at))) {
                at++;
            }
            tokens[token * 2 + 1] = at;
        }
        return text.substring(0, tokens[4])
                + id
                + text.substring(tokens[5], tokens[6])
                + "["
                + name
                + "]"
                + text.substring(tokens[7]);
    }

    public NpcDataFile getFile() {
        return file;
    }

    public State getState() {
        return state;
    }

    public State getSavedState() {
        return saved;
    }

    public long getRevision() {
        return revision;
    }

    public boolean isModified() {
        return !state.equals(saved);
    }

    public void markSaved() {
        saved = state;
    }

    public int getSize(State in) {
        return file.size() + in.added().size();
    }

    public int size() {
        return getSize(state);
    }

    public boolean isAdded(int record) {
        return record >= file.size();
    }

    public NpcRecord record(State in, int index) {
        if (index < file.size()) {
            return file.record(index);
        }
        var text = in.added().get(index - file.size());
        return addedRecords.computeIfAbsent(index + "\n" + text, unused -> {
            var parsed = NpcDataFile.parse(text);
            if (parsed.size() != 1) {
                throw new IllegalArgumentException("An added NPC must be one record: " + text);
            }
            return parsed.record(0).reindexed(index, lineCount() + index - file.size());
        });
    }

    public NpcRecord record(int index) {
        return record(state, index);
    }

    public List<NpcRecord> getRecords(State in) {
        var records = new ArrayList<NpcRecord>(getSize(in));
        for (var i = 0; i < getSize(in); i++) {
            if (!in.isRemoved(i)) {
                records.add(record(in, i));
            }
        }
        return records;
    }

    public @Nullable NpcRecord byName(State in, String name) {
        for (var i = file.size(); i < getSize(in); i++) {
            if (!in.isRemoved(i) && record(in, i).name().equals(name)) {
                return record(in, i);
            }
        }
        var record = file.byName(name);
        return record == null || in.isRemoved(record.getIndex()) ? null : record;
    }

    public @Nullable String getValue(int record, String key) {
        return getValue(state, record, key);
    }

    public @Nullable String getValue(State in, int record, String key) {
        var edited = in.editedValue(record, key);
        return edited != null ? edited : record(in, record).getValue(key);
    }

    public String recordText(State in, int index) {
        return NpcDataWriter.recordText(record(in, index), in.edits().getOrDefault(index, Map.of()));
    }

    public State with(State from, int record, String key, String value) {
        var original = record(from, record).getValue(key);
        var edits = new TreeMap<>(from.edits());
        var values = new LinkedHashMap<>(edits.getOrDefault(record, Map.of()));
        if (value.equals(original)) {
            values.remove(key);
        } else {
            values.put(key, value);
        }
        edits.put(record, values);
        var next = from.withEdits(edits);
        return next.equals(from) ? from : next;
    }

    public State withRemoved(State from, int record, boolean removed) {
        var set = new TreeSet<>(from.removed());
        if (removed) {
            set.add(record);
        } else {
            set.remove(record);
        }
        return new State(from.edits(), from.added(), set);
    }

    public State withoutEdits(State from, int record) {
        var edits = new TreeMap<>(from.edits());
        edits.remove(record);
        return from.withEdits(edits);
    }

    public Copy copy(State from, int source) {
        var original = record(from, source);
        var ids = new HashSet<Integer>();
        var names = new HashSet<String>();
        for (var i = 0; i < getSize(from); i++) {
            var record = record(from, i);
            ids.add(record.getId());
            names.add(record.name());
        }
        var id = original.getId() + 1;
        while (ids.contains(id)) {
            id++;
        }
        var suffix = 1;
        while (names.contains(original.name() + "_" + suffix)) {
            suffix++;
        }
        var name = original.name() + "_" + suffix;
        var text = renameHeader(recordText(from, source), id, name);
        var added = new ArrayList<>(from.added());
        added.add(text);
        return new Copy(new State(from.edits(), added, from.removed()), getSize(from), id, name);
    }

    public void set(String label, int record, String key, String value) {
        apply(label, current -> with(current, record, key, value));
    }

    public void amend(int record, String key, String value) {
        amend(current -> with(current, record, key, value));
    }

    public void apply(String label, UnaryOperator<State> edit) {
        apply(label, edit, null);
    }

    public void apply(String label, UnaryOperator<State> edit, @Nullable Batch batch) {
        var next = edit.apply(state);
        if (next.equals(state)) {
            return;
        }
        undo.push(new Step(label, state, next, batch));
        while (undo.size() > maxHistory) {
            undo.removeLast();
        }
        redo.clear();
        state = next;
        revision++;
    }

    public void amend(UnaryOperator<State> edit) {
        if (undo.isEmpty()) {
            apply("Edit", edit);
            return;
        }
        var next = edit.apply(state);
        if (next.equals(state)) {
            return;
        }
        var last = undo.pop();
        undo.push(new Step(last.label(), last.before(), next, last.batch()));
        redo.clear();
        state = next;
        revision++;
    }

    public List<Batch> getBatchesSinceSave() {
        var batches = new ArrayList<Batch>();
        for (var step : undo) {
            if (step.after().equals(saved)) {
                break;
            }
            if (step.batch() != null) {
                batches.add(step.batch());
            }
        }
        Collections.reverse(batches);
        return batches;
    }

    public void discardChanges() {
        if (isModified()) {
            var target = saved;
            apply("Discard changes", current -> target);
        }
    }

    public boolean canUndo() {
        return !undo.isEmpty();
    }

    public boolean canRedo() {
        return !redo.isEmpty();
    }

    public String getUndoLabel() {
        return undo.isEmpty() ? "" : undo.peek().label();
    }

    public String getRedoLabel() {
        return redo.isEmpty() ? "" : redo.peek().label();
    }

    public void undo() {
        if (undo.isEmpty()) {
            return;
        }
        var step = undo.pop();
        redo.push(step);
        state = step.before();
        revision++;
    }

    public void redo() {
        if (redo.isEmpty()) {
            return;
        }
        var step = redo.pop();
        undo.push(step);
        state = step.after();
        revision++;
    }

    public SpawnText getText() {
        return NpcDataWriter.write(this, state);
    }

    private int lineCount() {
        if (lines < 0) {
            var text = file.getSource().text();
            var count = 1;
            for (var i = 0; i < text.length(); i++) {
                if (text.charAt(i) == '\n') {
                    count++;
                }
            }
            lines = text.endsWith("\n") ? count : count + 1;
        }
        return lines;
    }
}
