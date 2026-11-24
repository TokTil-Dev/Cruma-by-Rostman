package com.rost.cruma.core.world;

import com.rost.cruma.core.unreal.Bsp.UModel;
import com.rost.cruma.core.unreal.UActor.MusicVolumeActor;
import com.rost.cruma.core.unreal.UActor.ZoneMusicState;
import com.rost.cruma.core.unreal.UPackage;
import glm_.mat4x4.Mat4;
import glm_.vec3.Vec3;
import java.util.ArrayList;
import java.util.List;

public final class MusicVolume {
    private final String name;
    private final BrushVolume shape;
    private final int musicId;
    private final boolean forcePlay;
    private final boolean loop;
    private final boolean hasNightMusic;
    private final int nightMusicId;
    private final List<ZoneMusicState> zoneMusicStates;
    private final int zoneStart;
    private final String map;

    public MusicVolume(
            UModel brush,
            Mat4 brushToWorld,
            int musicId,
            boolean forcePlay,
            boolean loop,
            boolean hasNightMusic,
            int nightMusicId) {
        this("", new BrushVolume(brush, brushToWorld), musicId, forcePlay, loop, hasNightMusic, nightMusicId);
    }

    public MusicVolume(
            BrushVolume shape, int musicId, boolean forcePlay, boolean loop, boolean hasNightMusic, int nightMusicId) {
        this("", shape, musicId, forcePlay, loop, hasNightMusic, nightMusicId);
    }

    public MusicVolume(
            String name,
            BrushVolume shape,
            int musicId,
            boolean forcePlay,
            boolean loop,
            boolean hasNightMusic,
            int nightMusicId) {
        this(name, shape, musicId, forcePlay, loop, hasNightMusic, nightMusicId, List.of(), ZoneRender.NO_ZONE_STATES);
    }

    public MusicVolume(
            String name,
            BrushVolume shape,
            int musicId,
            boolean forcePlay,
            boolean loop,
            boolean hasNightMusic,
            int nightMusicId,
            List<ZoneMusicState> zoneMusicStates,
            int zoneStart) {
        if (shape == null) {
            throw new IllegalArgumentException("a MusicVolume needs its brush");
        }
        this.name = name;
        this.shape = shape;
        this.musicId = musicId;
        this.forcePlay = forcePlay;
        this.loop = loop;
        this.hasNightMusic = hasNightMusic;
        this.nightMusicId = nightMusicId;
        this.zoneMusicStates = List.copyOf(zoneMusicStates);
        this.zoneStart = zoneStart;
        map = ZoneStateChoice.key(ZoneStateChoice.mapOf(name));
    }

    public static List<MusicVolume> readAll(UPackage uPackage) {
        var actors = new ArrayList<MusicVolumeActor>();
        uPackage.loadObjects("MusicVolume", actors);
        var result = new ArrayList<MusicVolume>(actors.size());
        for (var actor : actors) {
            if (actor.isDeleteMe()) {
                continue;
            }
            var brush = actor.getBrush().get();
            if (brush == null || brush.getPoints().length == 0) {
                continue;
            }
            var zoneMusicStates = actor.getZoneMusicStates();
            var zoneStart = zoneMusicStates.isEmpty() ? ZoneRender.NO_ZONE_STATES : UnrealLoader.zoneStart(actor);
            result.add(new MusicVolume(
                    actor.getFullName(),
                    new BrushVolume(
                            brush,
                            Entity.modelMatrix(
                                    actor.getPosition(), actor.getRotation().vector(), actor.getScale())),
                    actor.getMusicId(),
                    actor.isForcePlayMusic(),
                    actor.isLoopMusic(),
                    actor.isHasNightMusic(),
                    actor.getNightMusicId(),
                    zoneMusicStates,
                    zoneStart));
        }
        return result;
    }

    public boolean contains(Vec3 point) {
        return shape.contains(point);
    }

    public String getName() {
        return name;
    }

    public BrushVolume getShape() {
        return shape;
    }

    public int getMusicId() {
        return musicId;
    }

    public boolean isForcePlay() {
        return forcePlay;
    }

    public boolean isLoop() {
        return loop;
    }

    public boolean hasNightMusic() {
        return hasNightMusic;
    }

    public int getNightMusicId() {
        return nightMusicId;
    }

    public List<ZoneMusicState> getZoneMusicStates() {
        return zoneMusicStates;
    }

    public int getZoneStart() {
        return zoneStart;
    }

    public int getMusicIdAt(boolean night) {
        return night && hasNightMusic ? nightMusicId : musicId;
    }

    public int getMusicIdAt(boolean night, ZoneStateChoice choice) {
        return zoneMusicStates.isEmpty()
                ? getMusicIdAt(night)
                : getMusicIdAt(night, choice.getState(map, ZoneStateChoice.defaultOf(zoneStart, true)));
    }

    public int getMusicIdAt(boolean night, int zoneState) {
        var music = getMusicIdAt(night);
        if (zoneMusicStates.isEmpty() || zoneStart == ZoneRender.NO_ZONE_STATES) {
            return music;
        }
        var state = zoneState >= 1 && zoneState <= MapEventState.MAX_ZONE_STATE ? zoneState : zoneStart;
        if (state <= 0) {
            return music;
        }
        for (var entry : zoneMusicStates) {
            if (entry.state() == state) {
                return entry.musicId();
            }
        }
        return music;
    }

    @Override
    public String toString() {
        return "MusicVolume{music="
                + musicId
                + (hasNightMusic ? ", night=" + nightMusicId : "")
                + (loop ? ", loop" : "")
                + (forcePlay ? ", force" : "")
                + (zoneMusicStates.isEmpty() ? "" : ", zone states " + zoneMusicStates + " from " + zoneStart)
                + "}";
    }
}
