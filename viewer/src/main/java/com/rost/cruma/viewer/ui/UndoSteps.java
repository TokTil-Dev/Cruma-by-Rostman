package com.rost.cruma.viewer.ui;

import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.Nullable;

final class UndoSteps<T> {
    private static final int MOST_STEPS = 200;

    private final List<T> undo = new ArrayList<>();
    private final List<T> redo = new ArrayList<>();
    private T value;
    private @Nullable T dragBase;

    UndoSteps(T value) {
        this.value = value;
    }

    T getValue() {
        return value;
    }

    void change(T next) {
        settle();
        if (!next.equals(value)) {
            step(value);
            value = next;
        }
    }

    void drag(T next) {
        if (dragBase == null) {
            dragBase = value;
        }
        value = next;
    }

    boolean isDragging() {
        return dragBase != null;
    }

    void settle() {
        var base = dragBase;
        if (base == null) {
            return;
        }
        dragBase = null;
        if (!base.equals(value)) {
            step(base);
        }
    }

    void cancel() {
        var base = dragBase;
        if (base != null) {
            dragBase = null;
            value = base;
        }
    }

    boolean canUndo() {
        return !undo.isEmpty();
    }

    boolean canRedo() {
        return !redo.isEmpty();
    }

    void undo() {
        settle();
        if (!undo.isEmpty()) {
            redo.add(value);
            value = undo.removeLast();
        }
    }

    void redo() {
        settle();
        if (!redo.isEmpty()) {
            undo.add(value);
            value = redo.removeLast();
        }
    }

    private void step(T before) {
        undo.add(before);
        if (undo.size() > MOST_STEPS) {
            undo.removeFirst();
        }
        redo.clear();
    }
}
