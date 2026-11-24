package com.rost.cruma.viewer.editing;

import com.rost.cruma.core.world.Entity.Texture;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class EditingLightmapUploads {
    public record Upload(String map, int key, Texture texels) {}

    private record Key(String map, int key) {}

    private record Shown(Texture texels, Texture saved) {}

    private final long frameBytes;
    private final Map<String, Map<Integer, Shown>> shown = new HashMap<>();
    private final LinkedHashMap<Key, Upload> queue = new LinkedHashMap<>();

    public EditingLightmapUploads(long frameBytes) {
        if (frameBytes < 1) {
            throw new IllegalArgumentException("A frame must write something");
        }
        this.frameBytes = frameBytes;
    }

    static long bytes(Texture texture) {
        long bytes = 0;
        for (var level : texture.data()) {
            bytes += level.length;
        }
        return bytes;
    }

    public void offer(String map, Map<Integer, Texture> lightmaps, Map<Integer, Texture> saved) {
        var before = shown.getOrDefault(map, Map.of());
        var after = new HashMap<Integer, Shown>();
        for (var entry : lightmaps.entrySet()) {
            var original = saved.get(entry.getKey());
            var old = before.get(entry.getKey());
            if (original == null) {
                continue;
            }
            after.put(entry.getKey(), new Shown(entry.getValue(), old == null ? original : old.saved()));
            if (old == null || !isSameTexels(old.texels(), entry.getValue())) {
                enqueue(new Upload(map, entry.getKey(), entry.getValue()));
            }
        }
        for (var entry : before.entrySet()) {
            if (!after.containsKey(entry.getKey())) {
                enqueue(new Upload(map, entry.getKey(), entry.getValue().saved()));
            }
        }
        if (after.isEmpty()) {
            shown.remove(map);
        } else {
            shown.put(map, after);
        }
    }

    public void mapLoaded(String map) {
        shown.getOrDefault(map, Map.of()).forEach((key, texture) -> enqueue(new Upload(map, key, texture.texels())));
    }

    public void mapEvicted(String map) {
        queue.keySet().removeIf(key -> key.map().equals(map));
    }

    public List<Upload> drain() {
        var result = new ArrayList<Upload>();
        long bytes = 0;
        var iterator = queue.values().iterator();
        while (iterator.hasNext()) {
            var next = iterator.next();
            var size = bytes(next.texels());
            if (!result.isEmpty() && bytes + size > frameBytes) {
                break;
            }
            result.add(next);
            bytes += size;
            iterator.remove();
        }
        return result;
    }

    public boolean isIdle() {
        return queue.isEmpty();
    }

    int getShownCount() {
        return shown.values().stream().mapToInt(Map::size).sum();
    }

    public void clear() {
        shown.clear();
        queue.clear();
    }

    private void enqueue(Upload upload) {
        var key = new Key(upload.map(), upload.key());
        queue.remove(key);
        queue.put(key, upload);
    }

    private static boolean isSameTexels(Texture a, Texture b) {
        return a.width() == b.width()
                && a.height() == b.height()
                && !a.data().isEmpty()
                && !b.data().isEmpty()
                && Arrays.equals(a.data().getFirst(), b.data().getFirst());
    }
}
