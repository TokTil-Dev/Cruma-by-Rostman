package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.TerrainHeightField.SectorCoordinate;
import java.util.ArrayDeque;
import java.util.BitSet;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

public final class TerrainQuadHistory {
    public static final int MAX_COMMANDS = 256;

    private final int width;
    private final int height;
    private final BitSet originalVisible;
    private final BitSet originalTurns;
    private final BitSet visible;
    private final BitSet turns;
    private BitSet savedVisible;
    private BitSet savedTurns;
    private final ArrayDeque<Command> undo = new ArrayDeque<>();
    private final ArrayDeque<Command> redo = new ArrayDeque<>();
    private final Preview preview;
    private BitSet strokeVisible;
    private BitSet strokeTurns;
    private long revision;

    public enum Brush {
        HIDE,
        SHOW,
        TURN,
        RESTORE_TURN
    }

    @FunctionalInterface
    public interface Preview {
        void publish(BitSet visible, BitSet turns, Set<SectorCoordinate> sectors);
    }

    private record Command(BitSet visibleFlips, BitSet turnFlips) {}

    public TerrainQuadHistory(int width, int height, BitSet visible, BitSet turns, Preview preview) {
        if (width < 8 || height < 8 || width > 4096 || height > 4096) {
            throw new IllegalArgumentException("Invalid Terrain quad grid");
        }
        this.width = width;
        this.height = height;
        originalVisible = (BitSet) visible.clone();
        originalTurns = (BitSet) turns.clone();
        this.visible = (BitSet) visible.clone();
        this.turns = (BitSet) turns.clone();
        savedVisible = (BitSet) visible.clone();
        savedTurns = (BitSet) turns.clone();
        this.preview = Objects.requireNonNull(preview);
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public BitSet getVisible() {
        return (BitSet) visible.clone();
    }

    public BitSet getTurns() {
        return (BitSet) turns.clone();
    }

    public boolean isVisible(int x, int y) {
        return visible.get(y * width + x);
    }

    public boolean isTurned(int x, int y) {
        return turns.get(y * width + x);
    }

    public long getRevision() {
        return revision;
    }

    public boolean isStrokeActive() {
        return strokeVisible != null;
    }

    public boolean canUndo() {
        return strokeVisible == null && !undo.isEmpty();
    }

    public boolean canRedo() {
        return strokeVisible == null && !redo.isEmpty();
    }

    public boolean isEditable(int x, int y) {
        return x >= 0 && y >= 0 && x < width - 1 && y < height - 1;
    }

    public void beginStroke() {
        if (strokeVisible != null) {
            throw new IllegalStateException("Finish the Terrain stroke first");
        }
        strokeVisible = (BitSet) visible.clone();
        strokeTurns = (BitSet) turns.clone();
    }

    public boolean stamp(Brush brush, float x, float y, float radius) {
        if (strokeVisible == null) {
            throw new IllegalStateException("Begin a Terrain stroke first");
        }
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(radius) || radius < 0) {
            throw new IllegalArgumentException("Invalid Terrain quad brush");
        }
        var nextVisible = (BitSet) visible.clone();
        var nextTurns = (BitSet) turns.clone();
        var minX = Math.max(0, (int) Math.floor(x - radius - 1));
        var maxX = Math.min(width - 2, (int) Math.ceil(x + radius));
        var minY = Math.max(0, (int) Math.floor(y - radius - 1));
        var maxY = Math.min(height - 2, (int) Math.ceil(y + radius));
        for (var qy = minY; qy <= maxY; qy++) {
            for (var qx = minX; qx <= maxX; qx++) {
                var dx = qx + .5f - x;
                var dy = qy + .5f - y;
                if (dx * dx + dy * dy > radius * radius || !isEditable(qx, qy)) {
                    continue;
                }
                var quad = qy * width + qx;
                switch (brush) {
                    case HIDE -> nextVisible.clear(quad);
                    case SHOW -> nextVisible.set(quad);
                    case TURN -> nextTurns.set(quad, !strokeTurns.get(quad));
                    case RESTORE_TURN -> nextTurns.set(quad, originalTurns.get(quad));
                }
            }
        }
        return apply(nextVisible, nextTurns);
    }

    public boolean revertToSource() {
        if (strokeVisible == null) {
            throw new IllegalStateException("Begin a Terrain stroke first");
        }
        return apply(originalVisible, originalTurns);
    }

    public boolean finishStroke() {
        if (strokeVisible == null) {
            throw new IllegalStateException("No Terrain stroke is active");
        }
        var command = new Command(flips(strokeVisible, visible), flips(strokeTurns, turns));
        strokeVisible = null;
        strokeTurns = null;
        revision++;
        if (command.visibleFlips().isEmpty() && command.turnFlips().isEmpty()) {
            return false;
        }
        redo.clear();
        undo.addLast(command);
        while (undo.size() > MAX_COMMANDS) {
            undo.removeFirst();
        }
        return true;
    }

    public void cancelStroke() {
        if (strokeVisible == null) {
            return;
        }
        var before = strokeVisible;
        var turned = strokeTurns;
        apply(before, turned);
        strokeVisible = null;
        strokeTurns = null;
        revision++;
    }

    public void undo() {
        requireIdle();
        var command = undo.peekLast();
        if (command == null) {
            return;
        }
        flip(command);
        redo.addLast(undo.removeLast());
    }

    public void redo() {
        requireIdle();
        var command = redo.peekLast();
        if (command == null) {
            return;
        }
        flip(command);
        undo.addLast(redo.removeLast());
    }

    public void clearRedo() {
        if (!redo.isEmpty()) {
            redo.clear();
            revision++;
        }
    }

    public boolean isDirty() {
        return !visible.equals(savedVisible) || !turns.equals(savedTurns);
    }

    public boolean hasSourceChanges() {
        return !visible.equals(originalVisible) || !turns.equals(originalTurns);
    }

    public BitSet getChangedVisibility() {
        return flips(originalVisible, visible);
    }

    public BitSet getChangedTurns() {
        return flips(originalTurns, turns);
    }

    public void markExported(long expectedRevision) {
        requireIdle();
        if (revision != expectedRevision) {
            throw new IllegalStateException("Terrain quads changed during export");
        }
        savedVisible = (BitSet) visible.clone();
        savedTurns = (BitSet) turns.clone();
        revision++;
    }

    public Set<SectorCoordinate> getSectorsOf(BitSet quads) {
        var sectors = new LinkedHashSet<SectorCoordinate>();
        var columns = Math.max(1, (width - 1 + 15) / 16);
        var rows = Math.max(1, (height - 1 + 15) / 16);
        for (var quad = quads.nextSetBit(0); quad >= 0; quad = quads.nextSetBit(quad + 1)) {
            var x = quad % width;
            var y = quad / width;
            for (var ny = Math.max(0, y - 1); ny <= y + 1; ny++) {
                for (var nx = Math.max(0, x - 1); nx <= x + 1; nx++) {
                    sectors.add(new SectorCoordinate(Math.min(columns - 1, nx / 16), Math.min(rows - 1, ny / 16)));
                }
            }
        }
        return sectors;
    }

    private boolean apply(BitSet nextVisible, BitSet nextTurns) {
        var changed = flips(visible, nextVisible);
        changed.or(flips(turns, nextTurns));
        if (changed.isEmpty()) {
            return false;
        }
        preview.publish((BitSet) nextVisible.clone(), (BitSet) nextTurns.clone(), getSectorsOf(changed));
        visible.clear();
        visible.or(nextVisible);
        turns.clear();
        turns.or(nextTurns);
        revision++;
        return true;
    }

    private void flip(Command command) {
        var nextVisible = (BitSet) visible.clone();
        nextVisible.xor(command.visibleFlips());
        var nextTurns = (BitSet) turns.clone();
        nextTurns.xor(command.turnFlips());
        apply(nextVisible, nextTurns);
    }

    private void requireIdle() {
        if (strokeVisible != null) {
            throw new IllegalStateException("Finish the Terrain stroke first");
        }
    }

    private static BitSet flips(BitSet before, BitSet after) {
        var result = (BitSet) before.clone();
        result.xor(after);
        return result;
    }
}
