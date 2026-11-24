package com.rost.cruma.core.world;

import com.rost.cruma.core.audio.SoundClip;
import glm_.vec3.Vec3;
import java.util.List;
import org.jetbrains.annotations.Nullable;

public final class AmbientVolume {
    private final String name;
    private final BrushVolume shape;
    private final @Nullable WorldTile ownerTile;
    private final int priority;
    private final float fadeInSeconds;
    private final float fadeOutSeconds;
    private final List<Sound> sounds;
    private final ZoneRender zoneRender;
    private final Events events;
    private final boolean silentUnderWater;

    public record Sound(int type, int volume, float pitch, int chance, SoundClip clip) {
        public static final int ALWAYS = 0;

        public static final int DAY = 1;
        public static final int NIGHT = 2;

        public Sound {
            if (clip == null) {
                throw new IllegalArgumentException("a sound of an AmbientVolume needs its clip");
            }
        }

        public float gain() {
            return Math.clamp(volume / 255f, 0f, 2f);
        }

        public float playPitch() {
            return Float.isFinite(pitch) ? Math.clamp(pitch, 0.5f, 2f) : 1f;
        }

        public boolean loops() {
            return chance == 100;
        }
    }

    public record Events(boolean gated, boolean playing, List<Integer> playIds) {
        public static final Events NONE = new Events(false, true, List.of());

        public Events {
            playIds = List.copyOf(playIds);
        }

        public boolean plays(@Nullable MapEventState state) {
            if (!gated) {
                return true;
            }
            if (playIds.isEmpty()) {
                return playing;
            }
            if (state == null || state.isEmpty()) {
                return true;
            }
            var on = !state.gameStart();
            for (var id : playIds) {
                var eventOn = state.isOn(id);
                if (on && !eventOn) {
                    return false;
                }
                if (!on && eventOn) {
                    return true;
                }
            }
            return on;
        }
    }

    public AmbientVolume(
            String name,
            BrushVolume shape,
            @Nullable WorldTile ownerTile,
            int priority,
            float fadeInSeconds,
            float fadeOutSeconds,
            List<Sound> sounds,
            ZoneRender zoneRender,
            Events events,
            boolean silentUnderWater) {
        if (shape == null) {
            throw new IllegalArgumentException("an AmbientVolume needs its brush");
        }
        this.name = name == null ? "" : name;
        this.shape = shape;
        this.ownerTile = ownerTile;
        this.priority = priority;
        this.fadeInSeconds = fadeInSeconds;
        this.fadeOutSeconds = fadeOutSeconds;
        this.sounds = List.copyOf(sounds);
        this.zoneRender = zoneRender == null ? ZoneRender.EVERY_STATE : zoneRender;
        this.events = events == null ? Events.NONE : events;
        this.silentUnderWater = silentUnderWater;
    }

    public AmbientVolume(
            String name,
            BrushVolume shape,
            @Nullable WorldTile ownerTile,
            int priority,
            float fadeInSeconds,
            float fadeOutSeconds,
            List<Sound> sounds) {
        this(
                name,
                shape,
                ownerTile,
                priority,
                fadeInSeconds,
                fadeOutSeconds,
                sounds,
                ZoneRender.EVERY_STATE,
                Events.NONE,
                false);
    }

    public boolean contains(Vec3 point) {
        if (ownerTile != null && !ownerTile.equals(WorldTile.at(point.getX(), point.getY()))) {
            return false;
        }
        return shape.contains(point);
    }

    public boolean isTaken(ZoneStateChoice zoneStates, @Nullable MapEventState state) {
        return events.plays(state) && zoneRender.shows(zoneStates);
    }

    public String getName() {
        return name;
    }

    public BrushVolume getShape() {
        return shape;
    }

    public @Nullable WorldTile getOwnerTile() {
        return ownerTile;
    }

    public int getPriority() {
        return priority;
    }

    public float getFadeInSeconds() {
        return fadeInSeconds;
    }

    public float getFadeOutSeconds() {
        return fadeOutSeconds;
    }

    public List<Sound> getSounds() {
        return sounds;
    }

    public ZoneRender getZoneRender() {
        return zoneRender;
    }

    public Events getEvents() {
        return events;
    }

    public boolean isSilentUnderWater() {
        return silentUnderWater;
    }

    @Override
    public String toString() {
        return "AmbientVolume{" + name + ", priority " + priority + ", " + sounds.size() + " sounds, fade "
                + fadeInSeconds + "/" + fadeOutSeconds + (zoneRender.mask() == 0 ? "" : ", " + zoneRender)
                + (events.gated() ? ", " + events : "") + "}";
    }
}
