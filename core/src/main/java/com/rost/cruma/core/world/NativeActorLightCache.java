package com.rost.cruma.core.world;

import com.rost.cruma.core.unreal.primitives.USphere;
import com.rost.cruma.core.world.NativeLightCollector.Source;
import glm_.vec3.Vec3;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.IntFunction;
import org.jetbrains.annotations.Nullable;

public final class NativeActorLightCache {
    private static final double PERIOD = (double) .35f;
    private static final int CAPACITY = 16;

    private final Entry[] entries = new Entry[CAPACITY];
    private final Data[] output = new Data[8];
    private final float[] intensities = new float[8];
    private int entryCount;
    private int outputCount;
    private boolean initialized;

    public interface Actor {
        int getObjectIndex();

        Object getClassIdentity();

        boolean isDeleted();

        boolean isMovableSun();

        @Nullable
        Object getLevelIdentity();

        @Nullable
        Data getLightData();
    }

    public static final class Data {
        private final Actor actor;
        private Source source;
        private Vec3 direction;
        private float brightness;
        private int cone;
        private int priority;

        public Data(Actor actor, Source source, Vec3 direction, float brightness, int cone) {
            this.actor = Objects.requireNonNull(actor);
            update(source, direction, brightness, cone);
        }

        public void update(Source source, Vec3 direction, float brightness, int cone) {
            this.source = Objects.requireNonNull(source);
            this.direction = Objects.requireNonNull(direction);
            this.brightness = brightness;
            this.cone = cone;
        }

        public Actor getActor() {
            return actor;
        }

        public Source getSource() {
            return source;
        }

        public int getPriority() {
            return priority;
        }
    }

    @FunctionalInterface
    public interface SunVisibilityQuery {
        boolean isVisible(Data light, boolean present);
    }

    public record RecordState(Actor actor, float stamp, boolean present, boolean visible, boolean previous) {}

    private static final class Entry {
        Actor actor;
        int objectIndex;
        Object classIdentity;
        float stamp = -.35f;
        boolean present;
        boolean visible;
        boolean previous;

        Entry(Actor actor) {
            this.actor = actor;
            objectIndex = actor.getObjectIndex();
            classIdentity = actor.getClassIdentity();
        }
    }

    public int updateCollected(
            NativeLightCollector collected,
            Function<Object, Data> renderData,
            double now,
            USphere sphere,
            int maxLights,
            boolean programmable,
            @Nullable Object level,
            IntFunction<@Nullable Actor> indexedObject,
            SunVisibilityQuery sunlight) {
        if (collected.size() == 0) {
            clearOutput();
            return 0;
        }
        var supplied = new Data[collected.size()];
        for (var i = 0; i < supplied.length; i++) {
            supplied[i] = renderData.apply(collected.getSource(i).identity());
        }
        return update(supplied, now, sphere, maxLights, programmable, level, indexedObject, sunlight);
    }

    public int update(
            Data[] supplied,
            double now,
            USphere sphere,
            int maxLights,
            boolean programmable,
            @Nullable Object level,
            IntFunction<@Nullable Actor> indexedObject,
            SunVisibilityQuery sunlight) {
        clearOutput();
        var created = !initialized;
        initialized = true;
        var kept = 0;
        for (var i = 0; i < entryCount; i++) {
            var entry = entries[i];
            var actor = entry.actor;
            if (indexedObject.apply(entry.objectIndex) != actor
                    || actor.getClassIdentity() != entry.classIdentity
                    || actor.isDeleted()
                    || actor.getLightData() == null) {
                continue;
            }
            entry.present = false;
            var data = requireData(actor);
            updatePriority(data, sphere, programmable);
            if (data.priority <= 0 || (actor.isMovableSun() && level != null && level != actor.getLevelIdentity())) {
                continue;
            }
            entries[kept++] = entry;
        }
        Arrays.fill(entries, kept, entryCount, null);
        entryCount = kept;
        var candidates = supplied.clone();
        for (var data : candidates) {
            updatePriority(data, sphere, programmable);
        }
        sort(candidates, 0, candidates.length - 1, (left, right) -> left.priority - right.priority);
        for (var data : candidates) {
            var slot = -1;
            for (var i = 0; i < entryCount; i++) {
                if (entries[i].actor == data.actor) {
                    slot = i;
                    break;
                }
            }
            if (slot < 0 && data.priority > 0) {
                if (entryCount < CAPACITY) {
                    slot = entryCount++;
                } else {
                    var lowest = data.priority;
                    for (var i = 0; i < entryCount; i++) {
                        var other = requireData(entries[i].actor);
                        if (other.priority < lowest) {
                            lowest = other.priority;
                            slot = i;
                        }
                    }
                }
                if (slot >= 0) {
                    entries[slot] = new Entry(data.actor);
                }
            }
            if (slot >= 0) {
                entries[slot].present = true;
            }
        }
        sort(entries, 0, entryCount - 1, (left, right) -> {
            var rightData = requireData(right.actor);
            var leftData = requireData(left.actor);
            return leftData.priority - rightData.priority;
        });
        for (var i = 0; i < entryCount; i++) {
            var entry = entries[i];
            var data = requireData(entry.actor);
            if ((double) entry.stamp <= now - PERIOD) {
                entry.previous = entry.visible;
                entry.visible = data.source.effect() == 19 ? sunlight.isVisible(data, entry.present) : entry.present;
                entry.stamp = (float) (now - (created ? PERIOD : 0.0));
            }
        }
        var maximum = Math.min(maxLights & 255, 8);
        for (var i = 0; i < entryCount; i++) {
            var entry = entries[i];
            var data = requireData(entry.actor);
            if (outputCount < maximum && (entry.visible || entry.previous)) {
                var fraction = (float) ((now - (double) entry.stamp) / PERIOD);
                var previous = entry.previous ? 1f : 0f;
                var current = entry.visible ? 1f : 0f;
                intensities[outputCount] = fraction * (current - previous) + previous;
                output[outputCount++] = data;
            }
        }
        return outputCount;
    }

    public int size() {
        return outputCount;
    }

    public Data getLight(int index) {
        return output[Objects.checkIndex(index, outputCount)];
    }

    public float getIntensity(int index) {
        return intensities[Objects.checkIndex(index, outputCount)];
    }

    public int getRecordCount() {
        return entryCount;
    }

    public RecordState record(int index) {
        var entry = entries[Objects.checkIndex(index, entryCount)];
        return new RecordState(entry.actor, entry.stamp, entry.present, entry.visible, entry.previous);
    }

    public void clear() {
        Arrays.fill(entries, null);
        entryCount = 0;
        initialized = false;
        clearOutput();
    }

    private void clearOutput() {
        Arrays.fill(output, 0, outputCount, null);
        outputCount = 0;
    }

    private static Data requireData(Actor actor) {
        return Objects.requireNonNull(actor.getLightData(), "Cached light lost its render data");
    }

    private static void updatePriority(Data data, USphere sphere, boolean programmable) {
        var source = data.source;
        data.priority = StaticMeshLightPriority.score(
                source.position(),
                data.direction,
                source.radius(),
                data.brightness,
                source.effect(),
                data.cone,
                sphere.location(),
                sphere.radius(),
                programmable);
    }

    private static <T> void sort(T[] values, int first, int last, Comparator<T> compare) {
        while (first < last) {
            var size = last - first + 1;
            if (size <= 8) {
                while (first < last) {
                    var minimum = first;
                    for (var i = first + 1; i <= last; i++) {
                        if (compare.compare(values[minimum], values[i]) > 0) {
                            minimum = i;
                        }
                    }
                    swap(values, minimum, last--);
                }
                return;
            }
            swap(values, first, first + size / 2);
            var left = first;
            var right = last + 1;
            while (true) {
                do {
                    left++;
                } while (left <= last && compare.compare(values[first], values[left]) <= 0);
                do {
                    right--;
                } while (right > first && compare.compare(values[first], values[right]) >= 0);
                if (left > right) {
                    break;
                }
                swap(values, left, right);
            }
            swap(values, first, right);
            if (right - first - 1 < last - left) {
                sort(values, first, right - 1, compare);
                first = left;
            } else {
                sort(values, left, last, compare);
                last = right - 1;
            }
        }
    }

    private static <T> void swap(T[] values, int left, int right) {
        var value = values[left];
        values[left] = values[right];
        values[right] = value;
    }
}
