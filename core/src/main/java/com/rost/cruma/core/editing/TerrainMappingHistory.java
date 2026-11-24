package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.TerrainPackageSnapshot.LayerFields;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;

public final class TerrainMappingHistory {
    public static final int MAX_COMMANDS = 256;

    private final Map<Integer, LayerFields> layers = new LinkedHashMap<>();
    private final Map<Integer, TerrainLayerMapping> current = new LinkedHashMap<>();
    private final Map<Integer, TerrainLayerMapping> saved = new LinkedHashMap<>();
    private final ArrayDeque<Command> undo = new ArrayDeque<>();
    private final ArrayDeque<Command> redo = new ArrayDeque<>();
    private final Preview preview;
    private long revision;

    @FunctionalInterface
    public interface Preview {
        void publish(int layer, TerrainLayerMapping mapping);
    }

    private record Command(int layer, TerrainLayerMapping before, TerrainLayerMapping after) {}

    public TerrainMappingHistory(List<LayerFields> fields, Preview preview) {
        this.preview = Objects.requireNonNull(preview);
        for (var layer : fields) {
            layers.put(layer.order(), layer);
            if (layer.isEditable()) {
                current.put(layer.order(), layer.mapping());
                saved.put(layer.order(), layer.mapping());
            }
        }
    }

    public @Nullable LayerFields getFields(int layer) {
        return layers.get(layer);
    }

    public @Nullable TerrainLayerMapping getMapping(int layer) {
        return current.get(layer);
    }

    public String getRestriction(int layer) {
        var fields = layers.get(layer);
        return fields == null ? "This Terrain has no such layer" : fields.restriction();
    }

    public long getRevision() {
        return revision;
    }

    public boolean set(int layer, TerrainLayerMapping mapping) {
        Objects.requireNonNull(mapping);
        var before = current.get(layer);
        if (before == null) {
            throw new IllegalArgumentException(getRestriction(layer));
        }
        if (before.equals(mapping)) {
            return false;
        }
        apply(layer, mapping);
        redo.clear();
        undo.addLast(new Command(layer, before, mapping));
        while (undo.size() > MAX_COMMANDS) {
            undo.removeFirst();
        }
        return true;
    }

    public boolean revert(int layer) {
        var fields = layers.get(layer);
        return fields != null && fields.isEditable() && set(layer, fields.mapping());
    }

    public boolean canUndo() {
        return !undo.isEmpty();
    }

    public boolean canRedo() {
        return !redo.isEmpty();
    }

    public void undo() {
        var command = undo.peekLast();
        if (command == null) {
            return;
        }
        apply(command.layer(), command.before());
        redo.addLast(undo.removeLast());
    }

    public void redo() {
        var command = redo.peekLast();
        if (command == null) {
            return;
        }
        apply(command.layer(), command.after());
        undo.addLast(redo.removeLast());
    }

    public void clearRedo() {
        if (!redo.isEmpty()) {
            redo.clear();
            revision++;
        }
    }

    public boolean isDirty() {
        return !current.equals(saved);
    }

    public boolean hasSourceChanges() {
        return !getSourceChanges().isEmpty();
    }

    public Map<Integer, TerrainLayerMapping> getSourceChanges() {
        var result = new LinkedHashMap<Integer, TerrainLayerMapping>();
        current.forEach((layer, mapping) -> {
            if (!mapping.equals(layers.get(layer).mapping())) {
                result.put(layer, mapping);
            }
        });
        return result;
    }

    public void markExported(long expectedRevision) {
        if (revision != expectedRevision) {
            throw new IllegalStateException("Terrain layers changed during export");
        }
        saved.clear();
        saved.putAll(current);
        revision++;
    }

    private void apply(int layer, TerrainLayerMapping mapping) {
        preview.publish(layer, mapping);
        current.put(layer, mapping);
        revision++;
    }
}
