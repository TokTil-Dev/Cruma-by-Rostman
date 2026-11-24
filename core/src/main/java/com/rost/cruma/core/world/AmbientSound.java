package com.rost.cruma.core.world;

import com.rost.cruma.core.audio.SoundClip;
import glm_.vec3.Vec3;
import org.jetbrains.annotations.Nullable;

public record AmbientSound(
        Vec3 position,
        float radius,
        float volume,
        float pitch,
        Type type,
        int chance,
        SoundClip clip,
        String actor,
        ZoneRender zoneRender,
        @Nullable AmbientSoundList soundList,
        float startTime,
        AmbientSoundHours hours) {
    public static final int LOOPS = 100;

    public AmbientSound {
        if (position == null || type == null || clip == null) {
            throw new IllegalArgumentException("an ambient sound needs its position, type and clip");
        }
        if (radius <= 0f || !Float.isFinite(radius)) {
            throw new IllegalArgumentException("SoundRadius must be positive, got " + radius);
        }
        position = new Vec3(position);
        volume = Math.clamp(volume, 0f, 1f);
        pitch = pitch > 0f && Float.isFinite(pitch) ? pitch : 1f;
        chance = chanceOf(chance);
        actor = actor == null ? "" : actor;
        zoneRender = zoneRender == null ? ZoneRender.EVERY_STATE : zoneRender;
        startTime = Float.isFinite(startTime) ? startTime : 0f;
        hours = hours == null ? AmbientSoundHours.NONE : hours;
    }

    public AmbientSound(
            Vec3 position,
            float radius,
            float volume,
            float pitch,
            Type type,
            int chance,
            SoundClip clip,
            String actor,
            ZoneRender zoneRender,
            @Nullable AmbientSoundList soundList) {
        this(position, radius, volume, pitch, type, chance, clip, actor, zoneRender, soundList, 0f, null);
    }

    public AmbientSound(
            Vec3 position,
            float radius,
            float volume,
            float pitch,
            Type type,
            int chance,
            SoundClip clip,
            String actor,
            ZoneRender zoneRender) {
        this(position, radius, volume, pitch, type, chance, clip, actor, zoneRender, null);
    }

    public AmbientSound(
            Vec3 position,
            float radius,
            float volume,
            float pitch,
            Type type,
            int chance,
            SoundClip clip,
            String actor) {
        this(position, radius, volume, pitch, type, chance, clip, actor, ZoneRender.EVERY_STATE);
    }

    public AmbientSound(Vec3 position, float radius, float volume, float pitch, Type type, int chance, SoundClip clip) {
        this(position, radius, volume, pitch, type, chance, clip, "");
    }

    public static int chanceOf(int ambientRandom) {
        return ambientRandom > 0 && ambientRandom < LOOPS ? ambientRandom : LOOPS;
    }

    public enum Type {
        ALWAYS,
        DAY,
        NIGHT,
        WATER,
        RANGE_TIME;

        public static @Nullable Type of(int ordinal) {
            var values = values();
            return ordinal >= 0 && ordinal < values.length ? values[ordinal] : null;
        }
    }

    public boolean isLooping() {
        return chance == LOOPS;
    }

    public boolean playsAt(double hour, boolean underWater) {
        if (underWater) {
            return type == Type.WATER;
        }
        return switch (type) {
            case ALWAYS -> true;
            case DAY -> AmbientSoundHours.isSoundDay(hour);
            case NIGHT -> AmbientSoundHours.isSoundNight(hour);
            case WATER -> false;
            case RANGE_TIME -> hours.contains(hour);
        };
    }

    public boolean playsAt(double hour, boolean underWater, MapEventState events) {
        if (type != Type.RANGE_TIME || underWater) {
            return playsAt(hour, underWater);
        }
        return events.playsInHours(hours, hour);
    }

    public int zoneStateMask() {
        return zoneRender.mask();
    }

    public boolean playsInZoneState(ZoneStateChoice choice) {
        return zoneRender.shows(choice);
    }

    public float gainAt(float distance) {
        if (distance <= radius) {
            return volume;
        }
        return volume * radius / distance;
    }
}
