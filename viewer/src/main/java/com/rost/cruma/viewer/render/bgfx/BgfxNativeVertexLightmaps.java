package com.rost.cruma.viewer.render.bgfx;

import com.rost.cruma.core.world.NativeVertexLightmapState;
import com.rost.cruma.core.world.NativeVertexLightmapState.Binding;
import com.rost.cruma.viewer.render.bgfx.BgfxActorLightingBuffer.Slice;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

final class BgfxNativeVertexLightmaps implements AutoCloseable {
    private final BgfxActorLightingBuffer buffer;
    private final Map<String, Entry> instances = new HashMap<>();
    private final Map<String, Set<String>> owners = new HashMap<>();

    BgfxNativeVertexLightmaps(BgfxActorLightingBuffer buffer) {
        this.buffer = buffer;
    }

    record Stream(int offset, int vertexCount, long revision) {}

    void register(String owner, String instanceId) {
        if (owners.computeIfAbsent(owner, _ -> new HashSet<>()).add(instanceId)) {
            instances.computeIfAbsent(instanceId, _ -> new Entry()).references++;
        }
    }

    @Nullable
    Stream prepare(Binding binding) {
        var selected = binding.getStream();
        return prepare(
                binding.getInstanceId(), selected != null && selected == binding.getEnvironmentStream(), selected);
    }

    @Nullable
    Stream prepare(String instanceId, boolean environment, NativeVertexLightmapState.@Nullable Stream selected) {
        var entry = instances.get(instanceId);
        if (entry == null) {
            throw new IllegalStateException("unregistered directional instance: " + instanceId);
        }
        if (selected == null || selected.coefficients().length == 0) {
            return null;
        }
        var slot = environment ? entry.environment : entry.base;
        if (slot.source != selected) {
            var replacement = buffer.allocateDirectional(
                    instanceId + (environment ? "/environment" : "/base"), selected.coefficients());
            var gpu = new Stream(replacement.getOffset(), selected.coefficients().length / 9, selected.revision());
            slot.close();
            slot.slice = replacement;
            slot.source = selected;
            slot.gpu = gpu;
        }
        return slot.gpu;
    }

    void remove(String owner) {
        var ids = owners.remove(owner);
        if (ids == null) {
            return;
        }
        for (var id : ids) {
            var entry = instances.get(id);
            if (--entry.references == 0) {
                entry.close();
                instances.remove(id);
            }
        }
    }

    @Override
    public void close() {
        for (var entry : instances.values()) {
            entry.close();
        }
        instances.clear();
        owners.clear();
    }

    private static final class Entry implements AutoCloseable {
        final Slot base = new Slot();
        final Slot environment = new Slot();
        int references;

        @Override
        public void close() {
            base.close();
            environment.close();
        }
    }

    private static final class Slot implements AutoCloseable {
        NativeVertexLightmapState.Stream source;
        Slice slice;
        Stream gpu;

        @Override
        public void close() {
            if (slice != null) {
                slice.close();
            }
            source = null;
            slice = null;
            gpu = null;
        }
    }
}
