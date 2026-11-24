package com.rost.cruma.viewer.render.bgfx;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;

final class WeakIdentityMap<K, V> {
    private final ReferenceQueue<K> queue = new ReferenceQueue<>();
    private final HashMap<Key<K>, V> entries = new HashMap<>();

    V get(K key) {
        drain();
        return entries.get(new Key<>(key, null));
    }

    V put(K key, V value) {
        drain();
        return entries.put(new Key<>(key, queue), value);
    }

    V remove(K key) {
        drain();
        return entries.remove(new Key<>(key, null));
    }

    int size() {
        drain();
        return entries.size();
    }

    List<K> getKeySet() {
        drain();
        var keys = new ArrayList<K>(entries.size());
        for (var reference : entries.keySet()) {
            var key = reference.get();
            if (key != null) {
                keys.add(key);
            }
        }
        return keys;
    }

    void clear() {
        entries.clear();
        drain();
    }

    private void drain() {
        for (var key = queue.poll(); key != null; key = queue.poll()) {
            entries.remove(key);
        }
    }

    private static final class Key<K> extends WeakReference<K> {
        private final int hash;

        Key(K key, ReferenceQueue<K> queue) {
            super(Objects.requireNonNull(key), queue);
            hash = System.identityHashCode(key);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            var key = get();
            return key != null && other instanceof Key<?> reference && key == reference.get();
        }
    }
}
