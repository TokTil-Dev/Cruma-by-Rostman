package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.TerrainNativeCodec.Vector;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

public final class NativeTerrainSectorOrder {
    private NativeTerrainSectorOrder() {}

    public static int[] order(List<Vector> locations, Vector origin) {
        return order(locations, origin, () -> false);
    }

    public static int[] order(List<Vector> locations, Vector origin, BooleanSupplier cancelled) {
        Objects.requireNonNull(locations);
        Objects.requireNonNull(cancelled);
        P152FreshHashQuery.checkCancelled(cancelled);
        requireFinite(origin);
        if (locations.size() > 4096) {
            throw new IllegalArgumentException("Terrain sector index exceeds the native 12-bit field");
        }
        var keys = new int[locations.size()];
        for (var i = 0; i < keys.length; i++) {
            P152FreshHashQuery.checkCancelled(cancelled);
            var location = locations.get(i);
            requireFinite(location);
            var x = location.x() - origin.x();
            var y = location.y() - origin.y();
            var squared = y * y + x * x;
            var distance = squared >= 0x1p31f ? Integer.MIN_VALUE : (int) squared;
            keys[i] = (distance << 12) + i;
        }
        sort(keys, 0, keys.length - 1, cancelled);
        for (var i = 0; i < keys.length; i++) {
            keys[i] &= 0xfff;
        }
        P152FreshHashQuery.checkCancelled(cancelled);
        return keys;
    }

    static int[] sortPackedKeys(int[] supplied) {
        if (supplied.length > 4096) {
            throw new IllegalArgumentException("Too many native sector keys");
        }
        var keys = supplied.clone();
        sort(keys, 0, keys.length - 1, () -> false);
        return keys;
    }

    private static void sort(int[] values, int first, int last, BooleanSupplier cancelled) {
        while (first < last) {
            P152FreshHashQuery.checkCancelled(cancelled);
            var size = last - first + 1;
            if (size <= 8) {
                while (first < last) {
                    var maximum = first;
                    for (var i = first + 1; i <= last; i++) {
                        if (values[i] - values[maximum] > 0) {
                            maximum = i;
                        }
                    }
                    swap(values, maximum, last--);
                }
                return;
            }
            swap(values, first, first + size / 2);
            var left = first;
            var right = last + 1;
            while (true) {
                do {
                    left++;
                } while (left <= last && values[left] - values[first] <= 0);
                do {
                    right--;
                } while (right > first && values[right] - values[first] >= 0);
                if (left > right) {
                    break;
                }
                swap(values, left, right);
            }
            swap(values, first, right);
            if (right - first - 1 < last - left) {
                sort(values, first, right - 1, cancelled);
                first = left;
            } else {
                sort(values, left, last, cancelled);
                last = right - 1;
            }
        }
    }

    private static void swap(int[] values, int a, int b) {
        var value = values[a];
        values[a] = values[b];
        values[b] = value;
    }

    private static void requireFinite(Vector vector) {
        Objects.requireNonNull(vector);
        if (!Float.isFinite(vector.x()) || !Float.isFinite(vector.y()) || !Float.isFinite(vector.z())) {
            throw new IllegalArgumentException("Non-finite native Terrain sector location or sort origin");
        }
    }
}
