package com.rost.cruma.core.world;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.IntSupplier;

public final class AmbientSoundChance {
    private final IntSupplier rand;
    private final Map<AmbientSound, Double> startTimes = new IdentityHashMap<>();
    private double now = Double.NaN;
    private double last = Double.NaN;

    public AmbientSoundChance(IntSupplier rand) {
        this.rand = rand;
    }

    public static boolean isNewLength(double start, double length, double now, double last) {
        return truncate((now - start) / length) > truncate((last - start) / length);
    }

    public void advance(double seconds) {
        last = Double.isNaN(now) ? seconds : now;
        now = seconds;
    }

    public boolean roll(AmbientSound sound) {
        if (Double.isNaN(now)) {
            throw new IllegalStateException("roll before the first update");
        }
        var start = getStartTime(sound);
        if (Double.isNaN(start)) {
            startTimes.put(sound, (double) (float) now);
        } else if (!isNewLength(start, sound.clip().seconds(), now, last)) {
            return false;
        }
        return rand.getAsInt() % 100 < sound.chance();
    }

    public double getStartTime(AmbientSound sound) {
        if (sound.startTime() != 0f) {
            return sound.startTime();
        }
        var set = startTimes.get(sound);
        return set == null ? Double.NaN : set;
    }

    public void retain(Set<AmbientSound> loaded) {
        startTimes.keySet().retainAll(loaded);
    }

    public int getStartedCount() {
        return startTimes.size();
    }

    private static int truncate(double value) {
        return value > Integer.MIN_VALUE - 1.0 && value < Integer.MAX_VALUE + 1.0 ? (int) value : Integer.MIN_VALUE;
    }
}
