package com.rost.cruma.viewer.agent.effect;

import com.rost.cruma.core.editing.EffectEdits;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.Nullable;

public final class EffectSteps {
    private final List<Made> made = new ArrayList<>();
    private final List<Made> undone = new ArrayList<>();
    private @Nullable Path lastExport;

    private record Made(EffectEdits before, EffectEdits after) {}

    synchronized void made(EffectEdits before, EffectEdits after) {
        made.add(new Made(before, after));
        undone.clear();
    }

    synchronized boolean canUndo(EffectEdits now) {
        return !made.isEmpty() && made.getLast().after().equals(now);
    }

    synchronized void undid() {
        undone.add(made.removeLast());
    }

    synchronized boolean canRedo(EffectEdits now) {
        return !undone.isEmpty() && undone.getLast().before().equals(now);
    }

    synchronized EffectEdits getRedone() {
        var step = undone.removeLast();
        made.add(step);
        return step.after();
    }

    synchronized int countUndoable(EffectEdits now) {
        var count = 0;
        var at = now;
        for (var i = made.size() - 1; i >= 0 && made.get(i).after().equals(at); i--) {
            at = made.get(i).before();
            count++;
        }
        return count;
    }

    synchronized void cleared() {
        made.clear();
        undone.clear();
    }

    synchronized @Nullable Path getLastExport() {
        return lastExport;
    }

    synchronized void setLastExport(Path folder) {
        lastExport = folder;
    }
}
