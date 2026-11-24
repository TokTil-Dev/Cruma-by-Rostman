package com.rost.cruma.viewer.editing;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import org.jetbrains.annotations.Nullable;

public final class EditingTimeline {
    static final int LIMIT = 1024;

    static final String BEFORE_FIRST = "Before the first step";

    private final EnumMap<Kind, History> histories = new EnumMap<>(Kind.class);
    private final EnumMap<Kind, Seen> seen = new EnumMap<>(Kind.class);
    private final ArrayDeque<Step> done = new ArrayDeque<>();
    private final ArrayDeque<Step> undone = new ArrayDeque<>();
    private final EnumSet<Kind> clearRedo = EnumSet.noneOf(Kind.class);
    private final IdentityHashMap<Step, Long> numbers = new IdentityHashMap<>();
    private @Nullable TerrainViewportSession terrain;

    public enum Kind {
        ACTORS("Actor change"),
        TERRAIN("Terrain stroke");

        final String fallback;

        Kind(String fallback) {
            this.fallback = fallback;
        }
    }

    public record Step(Kind kind, String label) {}

    public record HistoryRow(String label, int steps, boolean redo, boolean current) {}

    public record Status(
            long generation, long recorded, int undoSteps, int redoSteps, String lastLabel, long @Nullable [] steps) {
        public Status(long generation, long recorded, int undoSteps, int redoSteps, String lastLabel) {
            this(generation, recorded, undoSteps, redoSteps, lastLabel, null);
        }
    }

    public interface History {
        Status getStatus();

        void undo();

        void redo();

        void clearRedo();

        boolean isReady();
    }

    private record Seen(long generation, long recorded) {}

    private record TerrainHistory(TerrainViewportSession viewport) implements History {
        @Override
        public Status getStatus() {
            return viewport.getController().getHistoryStatus();
        }

        @Override
        public void undo() {
            viewport.undo();
        }

        @Override
        public void redo() {
            viewport.redo();
        }

        @Override
        public void clearRedo() {
            viewport.clearRedo();
        }

        @Override
        public boolean isReady() {
            var controller = viewport.getController();
            return !viewport.isBusy() && !controller.isRecoveryGestureActive() && !controller.isExportLocked();
        }
    }

    public void bindTerrain(@Nullable TerrainViewportSession viewport) {
        if (viewport == terrain) {
            return;
        }
        terrain = viewport;
        bind(Kind.TERRAIN, viewport == null ? null : new TerrainHistory(viewport));
    }

    void bind(Kind kind, @Nullable History history) {
        if (history == null) {
            histories.remove(kind);
        } else {
            histories.put(kind, history);
        }
        seen.remove(kind);
    }

    public void sync() {
        clearPendingRedo();
        var statuses = new EnumMap<Kind, Status>(Kind.class);
        var replaced = new EnumMap<Kind, Status>(Kind.class);
        for (var kind : Kind.values()) {
            var history = histories.get(kind);
            if (history == null) {
                forget(kind);
                seen.remove(kind);
            } else if (history.isReady()) {
                var status = history.getStatus();
                statuses.put(kind, status);
                var last = seen.get(kind);
                if (last == null || last.generation() != status.generation()) {
                    replaced.put(kind, status);
                }
            }
        }
        rebuild(replaced);
        statuses.forEach((kind, status) -> {
            var last = seen.get(kind);
            if (last != null && last.generation() == status.generation() && status.recorded() > last.recorded()) {
                clearOtherRedo(kind, statuses);
                var added = status.recorded() - last.recorded();
                for (var i = 1; i <= added; i++) {
                    var step = new Step(
                            kind, i == added && !status.lastLabel().isEmpty() ? status.lastLabel() : kind.fallback);
                    record(step);
                    if (status.steps() != null) {
                        numbers.put(step, last.recorded() + i);
                    }
                }
            }
            seen.put(kind, new Seen(status.generation(), status.recorded()));
            if (status.steps() != null) {
                forgetLeft(kind, status);
            }
            trim(done, kind, status.undoSteps());
            trim(undone, kind, status.redoSteps());
        });
        if (numbers.size() > done.size() + undone.size()) {
            var kept = Collections.newSetFromMap(new IdentityHashMap<Step, Boolean>());
            kept.addAll(done);
            kept.addAll(undone);
            numbers.keySet().removeIf(step -> !kept.contains(step));
        }
    }

    @Nullable
    Step nextUndo() {
        return done.peekFirst();
    }

    @Nullable
    Step nextRedo() {
        return undone.peekFirst();
    }

    public List<Step> getDone() {
        return List.copyOf(done);
    }

    public List<Step> getUndone() {
        return List.copyOf(undone);
    }

    public List<HistoryRow> getHistory(int limit) {
        var rows = new ArrayList<HistoryRow>();
        var steps = List.copyOf(done);
        var later = List.copyOf(undone);
        var redoShown = Math.min(later.size(), Math.max(limit / 3, limit - 1 - steps.size()));
        for (var index = redoShown - 1; index >= 0; index--) {
            rows.add(new HistoryRow(later.get(index).label(), index + 1, true, false));
        }
        var doneShown = Math.min(steps.size(), limit - rows.size());
        for (var index = 0; index < doneShown; index++) {
            rows.add(new HistoryRow(steps.get(index).label(), -index, false, index == 0));
        }
        if (doneShown == steps.size() && rows.size() < limit && (!steps.isEmpty() || !later.isEmpty())) {
            rows.add(new HistoryRow(BEFORE_FIRST, -steps.size(), false, steps.isEmpty()));
        }
        return rows;
    }

    boolean canUndo() {
        var step = done.peekFirst();
        return step != null && isReady(step.kind());
    }

    boolean canRedo() {
        var step = undone.peekFirst();
        return step != null && isReady(step.kind());
    }

    @Nullable
    public Step undo() {
        sync();
        var step = done.peekFirst();
        if (step == null || !isReady(step.kind())) {
            return null;
        }
        histories.get(step.kind()).undo();
        done.removeFirst();
        undone.addFirst(step);
        return step;
    }

    @Nullable
    public Step redo() {
        sync();
        var step = undone.peekFirst();
        if (step == null || !isReady(step.kind())) {
            return null;
        }
        histories.get(step.kind()).redo();
        undone.removeFirst();
        done.addFirst(step);
        return step;
    }

    private void forgetLeft(Kind kind, Status status) {
        var count = 0;
        for (var deque : List.of(done, undone)) {
            for (var step : deque) {
                if (step.kind() == kind) {
                    count++;
                }
            }
        }
        if (count <= status.undoSteps() + status.redoSteps()) {
            return;
        }
        var held = new HashSet<Long>();
        for (var number : status.steps()) {
            held.add(number);
        }
        for (var deque : List.of(done, undone)) {
            deque.removeIf(step -> {
                var number = numbers.get(step);
                return step.kind() == kind && number != null && !held.contains(number);
            });
        }
    }

    private void clearOtherRedo(Kind recorded, EnumMap<Kind, Status> statuses) {
        for (var kind : Kind.values()) {
            var history = histories.get(kind);
            if (kind == recorded || history == null) {
                continue;
            }
            var status = statuses.get(kind);
            var pending = status == null || status.redoSteps() > 0;
            for (var step : undone) {
                pending |= step.kind() == kind;
            }
            if (pending) {
                clearRedo.add(kind);
            }
        }
        clearPendingRedo();
    }

    private void clearPendingRedo() {
        for (var kind : Kind.values()) {
            var history = histories.get(kind);
            if (!clearRedo.contains(kind)) {
                continue;
            }
            if (history == null) {
                clearRedo.remove(kind);
            } else if (history.isReady()) {
                history.clearRedo();
                clearRedo.remove(kind);
            }
        }
    }

    private boolean isReady(Kind kind) {
        var history = histories.get(kind);
        return history != null && history.isReady();
    }

    private void record(Step step) {
        done.addFirst(step);
        undone.clear();
        while (done.size() > LIMIT) {
            done.removeLast();
        }
    }

    private void rebuild(EnumMap<Kind, Status> replaced) {
        replaced.keySet().forEach(this::forget);
        for (var kind : new Kind[] {Kind.TERRAIN, Kind.ACTORS}) {
            var status = replaced.get(kind);
            for (var i = 0; status != null && i < status.undoSteps(); i++) {
                var newest =
                        i == 0 && status.redoSteps() == 0 && !status.lastLabel().isEmpty();
                var step = new Step(kind, newest ? status.lastLabel() : kind.fallback);
                done.addLast(step);
                number(step, status, status.undoSteps() - 1 - i);
            }
        }
        for (var kind : new Kind[] {Kind.ACTORS, Kind.TERRAIN}) {
            var status = replaced.get(kind);
            for (var i = 0; status != null && i < status.redoSteps(); i++) {
                var step = new Step(kind, kind.fallback);
                undone.addLast(step);
                number(step, status, status.undoSteps() + status.redoSteps() - 1 - i);
            }
        }
    }

    private void number(Step step, Status status, int place) {
        var steps = status.steps();
        if (steps != null && steps.length == status.undoSteps() + status.redoSteps() && place >= 0) {
            numbers.put(step, steps[place]);
        }
    }

    private void forget(Kind kind) {
        done.removeIf(step -> step.kind() == kind);
        undone.removeIf(step -> step.kind() == kind);
    }

    private static void trim(ArrayDeque<Step> steps, Kind kind, int keep) {
        var count = 0;
        for (var step : steps) {
            if (step.kind() == kind) {
                count++;
            }
        }
        for (Iterator<Step> oldest = steps.descendingIterator(); count > keep && oldest.hasNext(); ) {
            if (oldest.next().kind() == kind) {
                oldest.remove();
                count--;
            }
        }
    }
}
