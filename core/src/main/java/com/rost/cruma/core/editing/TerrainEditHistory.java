package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.TerrainBrushes.Changes;
import com.rost.cruma.core.editing.TerrainBrushes.Falloff;
import com.rost.cruma.core.editing.TerrainBrushes.RampPoint;
import com.rost.cruma.core.editing.TerrainBrushes.Shape;
import com.rost.cruma.core.editing.TerrainG16Codec.Mip;
import com.rost.cruma.core.editing.TerrainHeightField.Brush;
import com.rost.cruma.core.editing.TerrainHeightField.OutsideHeights;
import com.rost.cruma.core.editing.TerrainHeightField.SectorCoordinate;
import com.rost.cruma.core.editing.TerrainNativeCodec.Coords;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import org.jetbrains.annotations.Nullable;

public final class TerrainEditHistory {
    public static final int MAX_COMMANDS = 256;
    public static final long MAX_PATCH_BYTES = 8L * 1024 * 1024;

    private final TerrainHeightField field;
    private final short[] original;
    private short[] saved;
    private final Preview preview;
    private final ArrayDeque<TerrainHeightPatch> undo = new ArrayDeque<>();
    private final ArrayDeque<TerrainHeightPatch> redo = new ArrayDeque<>();
    private short[] strokeStart;
    private long retainedPatchBytes;
    private long revision;
    private long recoveryRevision;
    private int dirtySamples;

    public TerrainEditHistory(Mip heightmap, Coords toWorld, Preview preview) {
        if (heightmap.width() != 256 || heightmap.height() != 256) {
            throw new IllegalArgumentException("Terrain editing currently requires a 256x256 heightmap");
        }
        field = new TerrainHeightField(heightmap, toWorld);
        original = field.copySamples();
        saved = original.clone();
        this.preview = Objects.requireNonNull(preview);
    }

    @FunctionalInterface
    public interface Preview {
        void publish(Revision revision);
    }

    public static final class Revision {
        private final long number;
        private final short[] samples;
        private final Set<SectorCoordinate> affected;

        private Revision(long number, short[] samples, Set<SectorCoordinate> affected) {
            this.number = number;
            this.samples = samples;
            this.affected = Set.copyOf(affected);
        }

        public long getNumber() {
            return number;
        }

        public short[] getSamples() {
            return samples.clone();
        }

        public Set<SectorCoordinate> getAffectedSectors() {
            return affected;
        }
    }

    public record Checkpoint(
            TerrainHeightPatch current,
            TerrainHeightPatch saved,
            List<TerrainHeightPatch> undo,
            List<TerrainHeightPatch> redo) {
        public Checkpoint {
            undo = List.copyOf(undo);
            redo = List.copyOf(redo);
        }
    }

    public int getHeightAt(int x, int y) {
        return field.getHeightAt(x, y);
    }

    public long getRevision() {
        return revision;
    }

    public long getRecoveryRevision() {
        return recoveryRevision;
    }

    public short[] getSamples() {
        return field.copySamples();
    }

    public boolean isDirty() {
        return dirtySamples != 0;
    }

    public boolean isStrokeActive() {
        return strokeStart != null;
    }

    public boolean canUndo() {
        return strokeStart == null && !undo.isEmpty();
    }

    public boolean canRedo() {
        return strokeStart == null && !redo.isEmpty();
    }

    public int getUndoCount() {
        return undo.size();
    }

    public int getRedoCount() {
        return redo.size();
    }

    public void clearRedo() {
        if (redo.isEmpty()) {
            return;
        }
        while (!redo.isEmpty()) {
            retainedPatchBytes -= redo.removeFirst().getRetainedBytes();
        }
        recoveryRevision++;
    }

    public long getRetainedPatchBytes() {
        return retainedPatchBytes;
    }

    public TerrainHeightPatch getSourceChanges() {
        return field.getChangesSince(original);
    }

    public void beginStroke() {
        requireIdle();
        strokeStart = field.copySamples();
    }

    public void stamp(Brush brush, float x, float y, float radius, float strength, int target) {
        if (strokeStart == null) {
            throw new IllegalStateException("Begin a Terrain stroke first");
        }
        apply(field.computeBrushPatch(brush, x, y, radius, strength, target), false);
    }

    public void setOutsideHeights(@Nullable OutsideHeights heights) {
        field.setOutsideHeights(heights);
    }

    public void stamps(Brush brush, float radius, int target, List<TerrainBrushStamp> stamps) {
        stamps(Shape.of(brush, radius, target), stamps);
    }

    public void stamps(Shape shape, List<TerrainBrushStamp> stamps) {
        if (strokeStart == null) {
            throw new IllegalStateException("Begin a Terrain stroke first");
        }
        var patches = new ArrayList<Supplier<TerrainHeightPatch>>();
        for (var stamp : stamps) {
            patches.add(() -> field.computeBrushPatch(
                    shape, stamp.x(), stamp.y(), stamp.strength(), shape.brush() == Brush.NOISE ? strokeStart : null));
        }
        applyAll(patches);
    }

    public void ramp(RampPoint a, RampPoint b, float width, Falloff falloff) {
        if (strokeStart == null) {
            throw new IllegalStateException("Begin a Terrain stroke first");
        }
        applyAll(List.of(() -> TerrainBrushes.ramp(field, a, b, width, falloff)));
    }

    public void moveSelection(float[] weights, float raw) {
        if (strokeStart == null) {
            throw new IllegalStateException("Begin a Terrain stroke first");
        }
        if (weights.length != field.getWidth() * field.getHeight() || !Float.isFinite(raw)) {
            throw new IllegalArgumentException("Invalid Terrain selection move");
        }
        applyAll(List.of(() -> {
            var changes = new Changes();
            var width = field.getWidth();
            for (var index = 0; index < weights.length; index++) {
                var x = index % width;
                var y = index / width;
                if (weights[index] <= 0) {
                    continue;
                }
                var start = Short.toUnsignedInt(strokeStart[index]);
                changes.add(field, x, y, field.getHeightAt(x, y), start + (double) raw * weights[index]);
            }
            return changes.createPatch(field);
        }));
    }

    public void revertToSource() {
        if (strokeStart == null) {
            throw new IllegalStateException("Begin a Terrain stroke first");
        }
        var changes = field.getChangesSince(original);
        var count = changes.size();
        var indices = new int[count];
        var now = new short[count];
        var then = new short[count];
        for (var i = 0; i < count; i++) {
            indices[i] = changes.getIndex(i);
            now[i] = (short) changes.getAfter(i);
            then[i] = (short) changes.getBefore(i);
        }
        applyAll(List.of(() -> new TerrainHeightPatch(field.getWidth(), field.getHeight(), indices, now, then)));
    }

    public boolean finishStroke() {
        if (strokeStart == null) {
            throw new IllegalStateException("No Terrain stroke is active");
        }
        var command = field.getChangesSince(strokeStart);
        strokeStart = null;
        recoveryRevision++;
        if (command.size() == 0) {
            return false;
        }
        while (!redo.isEmpty()) {
            retainedPatchBytes -= redo.removeFirst().getRetainedBytes();
        }
        undo.addLast(command);
        retainedPatchBytes += command.getRetainedBytes();
        while (undo.size() > MAX_COMMANDS || retainedPatchBytes > MAX_PATCH_BYTES) {
            retainedPatchBytes -= undo.removeFirst().getRetainedBytes();
        }
        return true;
    }

    public void cancelStroke() {
        if (strokeStart == null) {
            return;
        }
        apply(field.getChangesSince(strokeStart), true);
        strokeStart = null;
        recoveryRevision++;
    }

    public void undo() {
        requireIdle();
        if (undo.isEmpty()) {
            return;
        }
        var command = undo.getLast();
        apply(command, true);
        undo.removeLast();
        redo.addLast(command);
        recoveryRevision++;
    }

    public void redo() {
        requireIdle();
        if (redo.isEmpty()) {
            return;
        }
        var command = redo.getLast();
        apply(command, false);
        redo.removeLast();
        undo.addLast(command);
        recoveryRevision++;
    }

    public void markExported(long expectedRevision) {
        requireIdle();
        if (revision != expectedRevision) {
            throw new IllegalStateException("Terrain changed during export");
        }
        saved = field.copySamples();
        dirtySamples = 0;
        recoveryRevision++;
    }

    public Checkpoint getCheckpoint() {
        requireIdle();
        var current = getSourceChanges();
        var indices = new int[dirtyFromOriginal(saved)];
        var before = new short[indices.length];
        var after = new short[indices.length];
        for (int i = 0, next = 0; i < saved.length; i++) {
            if (saved[i] != original[i]) {
                indices[next] = i;
                before[next] = original[i];
                after[next++] = saved[i];
            }
        }
        return new Checkpoint(
                current,
                new TerrainHeightPatch(256, 256, indices, before, after),
                List.copyOf(undo),
                List.copyOf(redo));
    }

    public void restore(Checkpoint checkpoint) {
        requireIdle();
        Objects.requireNonNull(checkpoint);
        if (checkpoint.undo().size() + checkpoint.redo().size() > MAX_COMMANDS) {
            throw new IllegalArgumentException("Terrain recovery exceeds command limit");
        }
        long bytes = 0;
        for (var commands : List.of(checkpoint.undo(), checkpoint.redo())) {
            for (var command : commands) {
                requireFullGrid(command);
                bytes = Math.addExact(bytes, command.getRetainedBytes());
            }
        }
        if (bytes > MAX_PATCH_BYTES) {
            throw new IllegalArgumentException("Terrain recovery exceeds patch byte limit");
        }
        requireFullGrid(checkpoint.current());
        requireFullGrid(checkpoint.saved());
        var proposed = original.clone();
        checkpoint.current().apply(proposed, false);
        var proposedSaved = original.clone();
        checkpoint.saved().apply(proposedSaved, false);
        var probe = proposed.clone();
        for (var command : checkpoint.undo().reversed()) {
            command.apply(probe, true);
        }
        probe = proposed.clone();
        for (var command : checkpoint.redo().reversed()) {
            command.apply(probe, false);
        }
        var old = field.copySamples();
        var transition = computeDifference(old, proposed);
        apply(transition, false);
        saved = proposedSaved;
        dirtySamples = 0;
        for (var i = 0; i < proposed.length; i++) {
            if (proposed[i] != saved[i]) {
                dirtySamples++;
            }
        }
        undo.clear();
        undo.addAll(checkpoint.undo());
        redo.clear();
        redo.addAll(checkpoint.redo());
        retainedPatchBytes = bytes;
        recoveryRevision++;
    }

    private void applyAll(List<Supplier<TerrainHeightPatch>> patches) {
        var applied = new ArrayList<TerrainHeightPatch>();
        var sectors = new LinkedHashSet<SectorCoordinate>();
        try {
            for (var next : patches) {
                var patch = next.get();
                if (patch.size() == 0) {
                    continue;
                }
                field.apply(patch, false);
                applied.add(patch);
                sectors.addAll(field.getAffectedSectors(patch));
            }
            if (applied.isEmpty()) {
                return;
            }
            preview.publish(new Revision(revision + 1, field.copySamples(), sectors));
        } catch (RuntimeException | Error failure) {
            for (var patch : applied.reversed()) {
                field.apply(patch, true);
            }
            throw failure;
        }
        for (var patch : applied) {
            countDirty(patch, false);
        }
        revision++;
    }

    private void apply(TerrainHeightPatch patch, boolean reverse) {
        if (patch.size() == 0) {
            return;
        }
        field.apply(patch, reverse);
        try {
            preview.publish(new Revision(revision + 1, field.copySamples(), field.getAffectedSectors(patch)));
        } catch (RuntimeException | Error failure) {
            field.apply(patch, !reverse);
            throw failure;
        }
        countDirty(patch, reverse);
        revision++;
    }

    private void countDirty(TerrainHeightPatch patch, boolean reverse) {
        for (var i = 0; i < patch.size(); i++) {
            var baseline = Short.toUnsignedInt(saved[patch.getIndex(i)]);
            if ((reverse ? patch.getAfter(i) : patch.getBefore(i)) != baseline) {
                dirtySamples--;
            }
            if ((reverse ? patch.getBefore(i) : patch.getAfter(i)) != baseline) {
                dirtySamples++;
            }
        }
    }

    private TerrainHeightPatch computeDifference(short[] before, short[] after) {
        var count = 0;
        for (var i = 0; i < before.length; i++) {
            if (before[i] != after[i]) {
                count++;
            }
        }
        var indices = new int[count];
        var old = new short[count];
        var next = new short[count];
        for (int i = 0, n = 0; i < before.length; i++) {
            if (before[i] != after[i]) {
                indices[n] = i;
                old[n] = before[i];
                next[n++] = after[i];
            }
        }
        return new TerrainHeightPatch(256, 256, indices, old, next);
    }

    private int dirtyFromOriginal(short[] values) {
        var count = 0;
        for (var i = 0; i < values.length; i++) {
            if (values[i] != original[i]) {
                count++;
            }
        }
        return count;
    }

    private void requireIdle() {
        if (strokeStart != null) {
            throw new IllegalStateException("Finish the Terrain stroke first");
        }
    }

    private static void requireFullGrid(TerrainHeightPatch patch) {
        if (patch.getWidth() != 256 || patch.getHeight() != 256) {
            throw new IllegalArgumentException("Terrain recovery grid differs");
        }
    }
}
