package com.rost.cruma.core.editing;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Objects;
import java.util.function.IntUnaryOperator;

public final class TerrainPaintHistory {
    public static final int MAX_COMMANDS = 256;
    public static final long MAX_PATCH_BYTES = 64L * 1024 * 1024;

    private final int samplesWide;
    private final int samplesHigh;
    private final List<Layer> layers;
    private final byte[][] original;
    private final byte[][] current;
    private final byte[][] saved;
    private final Preview preview;
    private final ArrayDeque<List<LayerPatch>> undo = new ArrayDeque<>();
    private final ArrayDeque<List<LayerPatch>> redo = new ArrayDeque<>();
    private byte[][] strokeStart;
    private long retainedPatchBytes;
    private long revision;
    private int dirtyTexels;
    private int sourceTexels;

    public TerrainPaintHistory(
            int samplesWide, int samplesHigh, List<Layer> layers, List<byte[]> weights, Preview preview) {
        if (layers.size() != weights.size()) {
            throw new IllegalArgumentException("Every Terrain layer needs its weights or null");
        }
        this.samplesWide = samplesWide;
        this.samplesHigh = samplesHigh;
        this.layers = List.copyOf(layers);
        this.preview = Objects.requireNonNull(preview);
        original = new byte[layers.size()][];
        current = new byte[layers.size()][];
        saved = new byte[layers.size()][];
        for (var i = 0; i < layers.size(); i++) {
            var layer = layers.get(i);
            var values = weights.get(i);
            if (layer.isPaintable() != (values != null)
                    || values != null && values.length != layer.width() * layer.height()) {
                throw new IllegalArgumentException("Terrain layer weights do not match the layer " + layer.index());
            }
            if (values != null) {
                original[i] = values.clone();
                current[i] = values.clone();
                saved[i] = values.clone();
            }
        }
    }

    public enum Brush {
        PAINT,
        ERASE
    }

    public record Layer(int index, String texture, String weightTexture, int width, int height, String restriction) {
        public boolean isPaintable() {
            return restriction.isEmpty();
        }
    }

    public record Change(int layer, int x0, int y0, int x1, int y1, byte[] weights) {
        public int width() {
            return x1 - x0 + 1;
        }

        public int height() {
            return y1 - y0 + 1;
        }
    }

    @FunctionalInterface
    public interface Preview {
        void publish(List<Change> changes);
    }

    public record Edit(
            Layer layer, byte[] original, byte[] weights, int x0, int y0, int x1, int y1, int changedTexels) {}

    private record LayerPatch(int layer, int[] indices, byte[] before, byte[] after) {
        long retainedBytes() {
            return indices.length * 6L;
        }
    }

    public List<Layer> getLayers() {
        return layers;
    }

    public long getRevision() {
        return revision;
    }

    public boolean isDirty() {
        return dirtyTexels != 0;
    }

    public boolean hasSourceChanges() {
        return sourceTexels != 0;
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

    public void clearRedo() {
        while (!redo.isEmpty()) {
            retainedPatchBytes -= bytes(redo.removeFirst());
        }
    }

    public byte[] getWeights(int layer) {
        return current[layer] == null ? null : current[layer].clone();
    }

    public void beginStroke() {
        requireIdle();
        strokeStart = new byte[layers.size()][];
    }

    public void stamps(int layer, Brush brush, float radius, boolean coverAbove, List<TerrainBrushStamp> stamps) {
        if (strokeStart == null) {
            throw new IllegalStateException("Begin a Terrain paint stroke first");
        }
        if (layer < 0 || layer >= layers.size() || current[layer] == null) {
            throw new IllegalArgumentException("This Terrain layer cannot be painted");
        }
        if (!Float.isFinite(radius) || radius <= 0 || radius > 256) {
            throw new IllegalArgumentException("Invalid Terrain paint radius");
        }
        var targets = new ArrayList<Integer>();
        targets.add(layer);
        if (brush == Brush.PAINT && coverAbove) {
            for (var above = layer + 1; above < layers.size(); above++) {
                if (current[above] != null) {
                    targets.add(above);
                }
            }
        }
        var bounds = new int[layers.size()][];
        var journal = new Journal();
        for (var stamp : stamps) {
            if (!Float.isFinite(stamp.x())
                    || !Float.isFinite(stamp.y())
                    || !Float.isFinite(stamp.strength())
                    || stamp.strength() < 0
                    || stamp.strength() > 1) {
                journal.revert();
                throw new IllegalArgumentException("Invalid Terrain paint stamp");
            }
            for (var target : targets) {
                var raise = target == layer && brush == Brush.PAINT;
                var touched = stamp(target, stamp, radius, raise, journal);
                if (touched != null) {
                    bounds[target] = union(bounds[target], touched);
                }
            }
        }
        var changes = new ArrayList<Change>();
        for (var target = 0; target < bounds.length; target++) {
            if (bounds[target] != null) {
                changes.add(change(target, bounds[target]));
            }
        }
        publish(changes, journal::revert);
        revision++;
    }

    public boolean fill(int layer, BitSet texels, boolean coverAbove) {
        requireIdle();
        if (layer < 0 || layer >= layers.size() || current[layer] == null) {
            throw new IllegalArgumentException("This Terrain layer cannot be painted");
        }
        var chosen = layers.get(layer);
        if (texels.length() > chosen.width() * chosen.height()) {
            throw new IllegalArgumentException("The fill does not match the layer's weight texture");
        }
        strokeStart = new byte[layers.size()][];
        var journal = new Journal();
        var bounds = new int[layers.size()][];
        bounds[layer] = fillLayer(layer, journal, index -> texels.get(index) ? 255 : -1);
        if (coverAbove) {
            for (var above = layer + 1; above < layers.size(); above++) {
                if (current[above] == null) {
                    continue;
                }
                var info = layers.get(above);
                bounds[above] = fillLayer(above, journal, index -> {
                    var x = (int) ((index % info.width() + .5) * chosen.width() / info.width());
                    var y = (int) ((index / info.width() + .5) * chosen.height() / info.height());
                    return texels.get(
                                    Math.min(chosen.height() - 1, y) * chosen.width() + Math.min(chosen.width() - 1, x))
                            ? 0
                            : -1;
                });
            }
        }
        var changes = new ArrayList<Change>();
        for (var target = 0; target < bounds.length; target++) {
            if (bounds[target] != null) {
                changes.add(change(target, bounds[target]));
            }
        }
        try {
            publish(changes, journal::revert);
        } catch (RuntimeException | Error failure) {
            strokeStart = null;
            throw failure;
        }
        revision++;
        return finishStroke();
    }

    public boolean revertLayer(int layer) {
        requireIdle();
        if (layer < 0 || layer >= layers.size() || current[layer] == null) {
            throw new IllegalArgumentException("This Terrain layer cannot be painted");
        }
        strokeStart = new byte[layers.size()][];
        var journal = new Journal();
        var bounds = fillLayer(layer, journal, index -> original[layer][index] & 255);
        try {
            publish(bounds == null ? List.of() : List.of(change(layer, bounds)), journal::revert);
        } catch (RuntimeException | Error failure) {
            strokeStart = null;
            throw failure;
        }
        revision++;
        return finishStroke();
    }

    public int getWeightAt(int layer, float sampleX, float sampleY) {
        if (layer < 0 || layer >= layers.size() || current[layer] == null) {
            return -1;
        }
        var info = layers.get(layer);
        var x = Math.clamp(Math.round((sampleX + .5f) * info.width() / samplesWide - .5f), 0, info.width() - 1);
        var y = Math.clamp(Math.round((sampleY + .5f) * info.height() / samplesHigh - .5f), 0, info.height() - 1);
        return current[layer][y * info.width() + x] & 255;
    }

    public boolean finishStroke() {
        if (strokeStart == null) {
            throw new IllegalStateException("No Terrain paint stroke is active");
        }
        var command = new ArrayList<LayerPatch>();
        for (var i = 0; i < layers.size(); i++) {
            if (strokeStart[i] != null) {
                var patch = difference(i, strokeStart[i], current[i]);
                if (patch.indices().length != 0) {
                    command.add(patch);
                }
            }
        }
        strokeStart = null;
        if (command.isEmpty()) {
            return false;
        }
        while (!redo.isEmpty()) {
            retainedPatchBytes -= bytes(redo.removeFirst());
        }
        undo.addLast(List.copyOf(command));
        retainedPatchBytes += bytes(command);
        while (undo.size() > MAX_COMMANDS || retainedPatchBytes > MAX_PATCH_BYTES) {
            retainedPatchBytes -= bytes(undo.removeFirst());
        }
        return true;
    }

    public void cancelStroke() {
        if (strokeStart == null) {
            return;
        }
        var command = new ArrayList<LayerPatch>();
        for (var i = 0; i < layers.size(); i++) {
            if (strokeStart[i] != null) {
                command.add(difference(i, strokeStart[i], current[i]));
            }
        }
        strokeStart = null;
        apply(command, true);
    }

    public void undo() {
        requireIdle();
        if (undo.isEmpty()) {
            return;
        }
        apply(undo.getLast(), true);
        redo.addLast(undo.removeLast());
    }

    public void redo() {
        requireIdle();
        if (redo.isEmpty()) {
            return;
        }
        apply(redo.getLast(), false);
        undo.addLast(redo.removeLast());
    }

    public List<Edit> getSourceChanges() {
        var edits = new ArrayList<Edit>();
        for (var i = 0; i < layers.size(); i++) {
            if (current[i] == null) {
                continue;
            }
            var layer = layers.get(i);
            int x0 = layer.width(), y0 = layer.height(), x1 = -1, y1 = -1, count = 0;
            for (var texel = 0; texel < current[i].length; texel++) {
                if (current[i][texel] != original[i][texel]) {
                    var x = texel % layer.width();
                    var y = texel / layer.width();
                    x0 = Math.min(x0, x);
                    y0 = Math.min(y0, y);
                    x1 = Math.max(x1, x);
                    y1 = Math.max(y1, y);
                    count++;
                }
            }
            if (count != 0) {
                edits.add(new Edit(layer, original[i].clone(), current[i].clone(), x0, y0, x1, y1, count));
            }
        }
        return List.copyOf(edits);
    }

    public void markExported(long expectedRevision) {
        requireIdle();
        if (revision != expectedRevision) {
            throw new IllegalStateException("Terrain paint changed during export");
        }
        for (var i = 0; i < layers.size(); i++) {
            if (current[i] != null) {
                saved[i] = current[i].clone();
            }
        }
        dirtyTexels = 0;
    }

    private int[] stamp(int layer, TerrainBrushStamp stamp, float radius, boolean raise, Journal journal) {
        var info = layers.get(layer);
        var scaleX = info.width() / (float) samplesWide;
        var scaleY = info.height() / (float) samplesHigh;
        var centerX = (stamp.x() + .5f) * scaleX - .5f;
        var centerY = (stamp.y() + .5f) * scaleY - .5f;
        var radiusX = radius * scaleX;
        var radiusY = radius * scaleY;
        var minX = Math.max(0, (int) Math.floor(centerX - radiusX));
        var maxX = Math.min(info.width() - 1, (int) Math.ceil(centerX + radiusX));
        var minY = Math.max(0, (int) Math.floor(centerY - radiusY));
        var maxY = Math.min(info.height() - 1, (int) Math.ceil(centerY + radiusY));
        var weights = current[layer];
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = -1, y1 = -1;
        for (var y = minY; y <= maxY; y++) {
            for (var x = minX; x <= maxX; x++) {
                var dx = (x - centerX) / radiusX;
                var dy = (y - centerY) / radiusY;
                var distance = Math.sqrt(dx * dx + dy * dy);
                if (distance >= 1) {
                    continue;
                }
                var t = 1 - distance;
                var falloff = t * t * (3 - 2 * t);
                var index = y * info.width() + x;
                var old = weights[index] & 255;
                var amount = 255 * stamp.strength() * falloff;
                var next = (int) Math.max(0, Math.min(255, Math.round(raise ? old + amount : old - amount)));
                if (next == old) {
                    continue;
                }
                if (strokeStart[layer] == null) {
                    strokeStart[layer] = weights.clone();
                }
                journal.add(layer, index, old);
                countDirty(layer, index, old, next);
                weights[index] = (byte) next;
                x0 = Math.min(x0, x);
                y0 = Math.min(y0, y);
                x1 = Math.max(x1, x);
                y1 = Math.max(y1, y);
            }
        }
        return x1 < 0 ? null : new int[] {x0, y0, x1, y1};
    }

    private int[] fillLayer(int layer, Journal journal, IntUnaryOperator target) {
        var info = layers.get(layer);
        var weights = current[layer];
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = -1, y1 = -1;
        for (var index = 0; index < weights.length; index++) {
            var next = target.applyAsInt(index);
            var old = weights[index] & 255;
            if (next < 0 || next == old) {
                continue;
            }
            if (strokeStart[layer] == null) {
                strokeStart[layer] = weights.clone();
            }
            journal.add(layer, index, old);
            countDirty(layer, index, old, next);
            weights[index] = (byte) next;
            var x = index % info.width();
            var y = index / info.width();
            x0 = Math.min(x0, x);
            y0 = Math.min(y0, y);
            x1 = Math.max(x1, x);
            y1 = Math.max(y1, y);
        }
        return x1 < 0 ? null : new int[] {x0, y0, x1, y1};
    }

    private final class Journal {
        private long[] entries = new long[256];
        private int size;

        void add(int layer, int index, int old) {
            if (size == entries.length) {
                entries = Arrays.copyOf(entries, size * 2);
            }
            entries[size++] = (long) layer << 40 | (long) index << 8 | old;
        }

        void revert() {
            for (var i = size - 1; i >= 0; i--) {
                var layer = (int) (entries[i] >>> 40);
                var index = (int) (entries[i] >>> 8 & 0xffffffffL);
                var old = (int) (entries[i] & 255);
                countDirty(layer, index, current[layer][index] & 255, old);
                current[layer][index] = (byte) old;
            }
            size = 0;
        }
    }

    private void apply(List<LayerPatch> command, boolean reverse) {
        var bounds = new int[layers.size()][];
        for (var patch : command) {
            var weights = current[patch.layer()];
            var width = layers.get(patch.layer()).width();
            for (var i = 0; i < patch.indices().length; i++) {
                var index = patch.indices()[i];
                var from = (reverse ? patch.after() : patch.before())[i] & 255;
                var to = (reverse ? patch.before() : patch.after())[i] & 255;
                countDirty(patch.layer(), index, from, to);
                weights[index] = (byte) to;
                bounds[patch.layer()] = union(
                        bounds[patch.layer()], new int[] {index % width, index / width, index % width, index / width});
            }
        }
        var changes = new ArrayList<Change>();
        for (var layer = 0; layer < bounds.length; layer++) {
            if (bounds[layer] != null) {
                changes.add(change(layer, bounds[layer]));
            }
        }
        publish(changes, () -> {
            for (var patch : command) {
                var weights = current[patch.layer()];
                for (var i = 0; i < patch.indices().length; i++) {
                    var from = (reverse ? patch.before() : patch.after())[i] & 255;
                    var to = (reverse ? patch.after() : patch.before())[i] & 255;
                    countDirty(patch.layer(), patch.indices()[i], from, to);
                    weights[patch.indices()[i]] = (byte) to;
                }
            }
        });
        revision++;
    }

    private void publish(List<Change> changes, Runnable rollback) {
        if (changes.isEmpty()) {
            return;
        }
        try {
            preview.publish(List.copyOf(changes));
        } catch (RuntimeException | Error failure) {
            rollback.run();
            throw failure;
        }
    }

    private void countDirty(int layer, int index, int from, int to) {
        var baseline = saved[layer][index] & 255;
        if (from != baseline) {
            dirtyTexels--;
        }
        if (to != baseline) {
            dirtyTexels++;
        }
        var source = original[layer][index] & 255;
        if (from != source) {
            sourceTexels--;
        }
        if (to != source) {
            sourceTexels++;
        }
    }

    private Change change(int layer, int[] bounds) {
        var info = layers.get(layer);
        var width = bounds[2] - bounds[0] + 1;
        var height = bounds[3] - bounds[1] + 1;
        var weights = new byte[width * height];
        for (var row = 0; row < height; row++) {
            System.arraycopy(current[layer], (bounds[1] + row) * info.width() + bounds[0], weights, row * width, width);
        }
        return new Change(layer, bounds[0], bounds[1], bounds[2], bounds[3], weights);
    }

    private void requireIdle() {
        if (strokeStart != null) {
            throw new IllegalStateException("Finish the Terrain paint stroke first");
        }
    }

    private static LayerPatch difference(int layer, byte[] before, byte[] after) {
        var count = 0;
        for (var i = 0; i < before.length; i++) {
            if (before[i] != after[i]) {
                count++;
            }
        }
        var indices = new int[count];
        var old = new byte[count];
        var next = new byte[count];
        for (int i = 0, n = 0; i < before.length; i++) {
            if (before[i] != after[i]) {
                indices[n] = i;
                old[n] = before[i];
                next[n++] = after[i];
            }
        }
        return new LayerPatch(layer, indices, old, next);
    }

    private static long bytes(List<LayerPatch> command) {
        var total = 0L;
        for (var patch : command) {
            total += patch.retainedBytes();
        }
        return total;
    }

    private static int[] union(int[] bounds, int[] other) {
        if (bounds == null) {
            return other.clone();
        }
        return new int[] {
            Math.min(bounds[0], other[0]),
            Math.min(bounds[1], other[1]),
            Math.max(bounds[2], other[2]),
            Math.max(bounds[3], other[3])
        };
    }
}
