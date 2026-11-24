package com.rost.cruma.core.spawn;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.jetbrains.annotations.Nullable;

public final class NpcPosDocument {
    private final NpcPosFile file;
    private final Deque<Step> undo = new ArrayDeque<>();
    private final Deque<Step> redo = new ArrayDeque<>();
    private final int maxHistory;
    private State state;
    private State saved;
    private long revision;

    public NpcPosDocument(NpcPosFile file) {
        this(file, 256);
    }

    public NpcPosDocument(NpcPosFile file, int maxHistory) {
        this.file = Objects.requireNonNull(file, "file");
        this.maxHistory = maxHistory;
        this.state = new State(file.getTerritories(), file.getMakers());
        this.saved = state;
    }

    public record State(List<SpawnTerritory> territories, List<SpawnMaker> makers) {
        public State {
            territories = unmodifiable(territories);
            makers = unmodifiable(makers);
        }

        public SpawnTerritory territory(String name) {
            for (var territory : territories) {
                if (territory != null && !territory.domain() && territory.name().equals(name)) {
                    return territory;
                }
            }
            return null;
        }

        public State withTerritory(SpawnTerritory territory) {
            var copy = new ArrayList<>(territories);
            if (territory.index() == copy.size()) {
                copy.add(territory);
            } else {
                copy.set(territory.index(), territory);
            }
            return new State(copy, makers);
        }

        public List<Integer> zonesOnlyUsedBy(int makerIndex) {
            var maker = makerIndex >= 0 && makerIndex < makers.size() ? makers.get(makerIndex) : null;
            if (maker == null) {
                return List.of();
            }
            var named = new LinkedHashSet<String>(maker.territoryNames());
            named.addAll(maker.bannedTerritoryNames());
            for (var i = 0; i < makers.size(); i++) {
                var other = makers.get(i);
                if (i != makerIndex && other != null) {
                    other.territoryNames().forEach(named::remove);
                    other.bannedTerritoryNames().forEach(named::remove);
                }
            }
            var zones = new ArrayList<Integer>();
            for (var name : named) {
                var zone = territory(name);
                if (zone != null) {
                    zones.add(zone.index());
                }
            }
            return zones;
        }

        public List<Integer> makersWithoutNpcs() {
            var empty = new ArrayList<Integer>();
            for (var i = 0; i < makers.size(); i++) {
                if (makers.get(i) != null && makers.get(i).npcs().isEmpty()) {
                    empty.add(i);
                }
            }
            return empty;
        }

        public State withTerritoryName(int index, String newName) {
            var zone = territories.get(index);
            var old = zone.name();
            var copy = new ArrayList<>(makers);
            for (var i = 0; i < copy.size(); i++) {
                var maker = copy.get(i);
                if (maker != null
                        && (maker.territoryNames().contains(old)
                                || maker.bannedTerritoryNames().contains(old))) {
                    copy.set(
                            i,
                            maker.withTerritories(
                                    replaceName(maker.territoryNames(), old, newName),
                                    replaceName(maker.bannedTerritoryNames(), old, newName)));
                }
            }
            return new State(territories, copy).withTerritory(zone.withName(newName));
        }

        public State withoutTerritory(int index) {
            var copy = new ArrayList<>(territories);
            copy.set(index, null);
            return new State(copy, makers);
        }

        public State withMaker(SpawnMaker maker) {
            var copy = new ArrayList<>(makers);
            if (maker.index() == copy.size()) {
                copy.add(maker);
            } else {
                copy.set(maker.index(), maker);
            }
            return new State(territories, copy);
        }

        public State withoutMaker(int index) {
            var copy = new ArrayList<>(makers);
            copy.set(index, null);
            return new State(territories, copy);
        }

        public State withMakers(Map<Integer, SpawnMaker> changed) {
            if (changed.isEmpty()) {
                return this;
            }
            var copy = new ArrayList<>(makers);
            changed.forEach((index, maker) -> copy.set(index, maker));
            return new State(territories, copy);
        }

        public State withoutTerritories(Collection<Integer> indices) {
            if (indices.isEmpty()) {
                return this;
            }
            var copy = new ArrayList<>(territories);
            for (var index : indices) {
                copy.set(index, null);
            }
            return new State(copy, makers);
        }

        public List<Integer> unusedZones() {
            var named = new HashSet<String>();
            for (var maker : makers) {
                if (maker != null) {
                    named.addAll(maker.territoryNames());
                    named.addAll(maker.bannedTerritoryNames());
                }
            }
            var seen = new HashSet<String>();
            var unused = new ArrayList<Integer>();
            for (var zone : territories) {
                if (zone == null || zone.domain()) {
                    continue;
                }
                var first = seen.add(zone.name());
                if (!first || !named.contains(zone.name())) {
                    unused.add(zone.index());
                }
            }
            return unused;
        }

        public State withNpc(int makerIndex, int slot, SpawnNpc npc) {
            var maker = makers.get(makerIndex);
            var npcs = new ArrayList<>(maker.npcs());
            if (slot == npcs.size()) {
                npcs.add(npc);
            } else if (npc == null) {
                npcs.remove(slot);
            } else {
                npcs.set(slot, npc);
            }
            return withMaker(maker.withNpcs(npcs));
        }

        private static <T> List<T> unmodifiable(List<T> values) {
            return Collections.unmodifiableList(new ArrayList<>(values));
        }

        private static List<String> replaceName(List<String> names, String old, String newName) {
            return names.stream().map(name -> name.equals(old) ? newName : name).toList();
        }
    }

    public record Batch(String label, Set<Integer> makers, Set<Integer> territories) {
        public Batch {
            makers = Set.copyOf(makers);
            territories = Set.copyOf(territories);
        }
    }

    private record Step(
            String label,
            State before,
            State after,
            @Nullable Batch batch) {}

    public NpcPosFile getFile() {
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
        return NpcPosWriter.write(file, saved);
    }

    public void apply(String label, UnaryOperator<State> edit) {
        apply(label, edit, null);
    }

    public void apply(String label, UnaryOperator<State> edit, @Nullable Batch batch) {
        var next = edit.apply(state);
        if (next == state) {
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
        if (next == state) {
            return;
        }
        var last = undo.pop();
        undo.push(new Step(last.label(), last.before(), next, last.batch()));
        state = next;
        revision++;
    }

    public List<Batch> getBatchesSinceSave() {
        var batches = new ArrayList<Batch>();
        for (var step : undo) {
            if (step.after() == saved) {
                break;
            }
            if (step.batch() != null) {
                batches.add(step.batch());
            }
        }
        Collections.reverse(batches);
        return batches;
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
        return NpcPosWriter.write(file, state);
    }
}
