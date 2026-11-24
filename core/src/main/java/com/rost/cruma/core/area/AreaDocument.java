package com.rost.cruma.core.area;

import com.rost.cruma.core.spawn.SpawnText;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.jetbrains.annotations.Nullable;

public final class AreaDocument {
    private final AreaDataFile file;
    private final Deque<Step> undo = new ArrayDeque<>();
    private final Deque<Step> redo = new ArrayDeque<>();
    private final int maxHistory;
    private State state;
    private State saved;
    private long revision;
    private @Nullable State typesState;
    private Set<String> savedTypes = Set.of();

    public AreaDocument(AreaDataFile file) {
        this(file, 256);
    }

    public AreaDocument(AreaDataFile file, int maxHistory) {
        this.file = Objects.requireNonNull(file, "file");
        this.maxHistory = maxHistory;
        this.state = new State(file.getAreas());
        this.saved = state;
    }

    public static Set<String> collectTypes(AreaDataFile file, State state) {
        var types = new LinkedHashSet<String>();
        for (var area : state.areas()) {
            if (area != null && !area.typeWord().isEmpty()) {
                types.add(area.typeWord());
            }
        }
        if (types.isEmpty()) {
            return Set.of();
        }
        for (var record : file.getDefaults()) {
            if (!record.typeWord().isEmpty()) {
                types.add(record.typeWord());
            }
        }
        return Set.copyOf(types);
    }

    public record State(List<ServerArea> areas) {
        public State {
            areas = Collections.unmodifiableList(new ArrayList<>(areas));
        }

        public @Nullable ServerArea area(int index) {
            return index >= 0 && index < areas.size() ? areas.get(index) : null;
        }

        public @Nullable ServerArea findArea(String name) {
            for (var i = areas.size() - 1; i >= 0; i--) {
                var area = areas.get(i);
                if (area != null && area.name().equals(name)) {
                    return area;
                }
            }
            return null;
        }

        public List<ServerArea> liveAreas() {
            var live = new ArrayList<ServerArea>(areas.size());
            for (var area : areas) {
                if (area != null) {
                    live.add(area);
                }
            }
            return live;
        }

        public State withArea(ServerArea area) {
            var copy = new ArrayList<>(areas);
            if (area.index() == copy.size()) {
                copy.add(area);
            } else {
                copy.set(area.index(), area);
            }
            return new State(copy);
        }

        public State withoutArea(int index) {
            if (area(index) == null) {
                return this;
            }
            var copy = new ArrayList<>(areas);
            copy.set(index, null);
            return new State(copy);
        }

        public int nextIndex() {
            return areas.size();
        }
    }

    private record Step(String label, State before, State after) {}

    public AreaDataFile getFile() {
        return file;
    }

    public State getState() {
        return state;
    }

    public long getRevision() {
        return revision;
    }

    public State getSavedState() {
        return saved;
    }

    public Set<String> getSavedTypes() {
        if (typesState != saved) {
            savedTypes = collectTypes(file, saved);
            typesState = saved;
        }
        return savedTypes;
    }

    public boolean isModified() {
        return state != saved;
    }

    public void markSaved() {
        saved = state;
    }

    public void discardChanges() {
        if (isModified()) {
            var target = saved;
            apply("Discard changes", current -> target);
        }
    }

    public SpawnText getSavedText() {
        return AreaDataWriter.write(file, saved);
    }

    public SpawnText getText() {
        return AreaDataWriter.write(file, state);
    }

    public void apply(String label, UnaryOperator<State> edit) {
        var next = edit.apply(state);
        if (next == state) {
            return;
        }
        undo.push(new Step(label, state, next));
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
        if (next == state) {
            return;
        }
        var last = undo.pop();
        undo.push(new Step(last.label(), last.before(), next));
        redo.clear();
        state = next;
        revision++;
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
}
