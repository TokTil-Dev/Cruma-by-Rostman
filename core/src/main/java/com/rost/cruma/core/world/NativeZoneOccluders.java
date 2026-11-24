package com.rost.cruma.core.world;

import com.rost.cruma.core.unreal.UConvexVolume;
import com.rost.cruma.core.unreal.primitives.UBox;
import com.rost.cruma.core.world.BspVisibilityVolumes.View;
import com.rost.cruma.core.world.NativeOccluderVolume.Geometry;
import glm_.mat4x4.Mat4;
import glm_.vec3.Vec3;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public final class NativeZoneOccluders {
    private final List<Actor> actors;
    private final int[][] zones;
    private final int[] stamps;

    public NativeZoneOccluders(List<Actor> actors, int[][] zones) {
        if (zones.length > 64) {
            throw new IllegalArgumentException("native BSP has at most 64 zones");
        }
        this.actors = List.copyOf(actors);
        this.zones = new int[zones.length][];
        for (var i = 0; i < zones.length; i++) {
            this.zones[i] = zones[i].clone();
            for (var actor : zones[i]) {
                Objects.checkIndex(actor, actors.size());
            }
        }
        stamps = new int[actors.size()];
    }

    public record Actor(Geometry geometry, Mat4 actorToWorld, UBox bounds, float cullDistance) {
        public Actor {
            actorToWorld = new Mat4(actorToWorld);
            bounds = new UBox(new Vec3(bounds.min()), new Vec3(bounds.max()), bounds.isValid());
        }

        public static Actor captureConvex(UConvexVolume volume, Mat4 actorToWorld, float cullDistance) {
            return new Actor(
                    Geometry.capture(volume),
                    actorToWorld,
                    NativeActorBounds.transformBox(volume.getConvexBounds(), actorToWorld),
                    cullDistance);
        }
    }

    public record Options(int stamp, float fovDegrees, int precaching, boolean debug) {}

    public record Result(int examined, int published) {}

    public static int advanceStamp(int current, List<NativeZoneOccluders> levels) {
        var next = current + 1;
        if (next != 0) {
            return next;
        }
        if (!levels.isEmpty()) {
            levels.getFirst().clearStamps();
        }
        return 1;
    }

    public Result publish(int zone, View view, Vec3 origin, Options options, List<Integer> debugActors) {
        if (options.precaching() != 0 || zone >= zones.length) {
            return new Result(0, 0);
        }
        var examined = 0;
        var published = 0;
        for (var index : zones[zone]) {
            var actor = actors.get(index);
            examined++;
            if (stamps[index] == options.stamp()) {
                continue;
            }
            if (!NativeActorDrawDistance.isVisible(
                    actor.bounds(), origin, actor.cullDistance(), options.fovDegrees(), options.precaching())) {
                continue;
            }
            if (!view.intersectsBox(actor.bounds(), zone)) {
                continue;
            }
            var volume = NativeOccluderVolume.build(actor.geometry(), actor.actorToWorld(), origin);
            view.prependExcluding(zone, volume);
            if (options.debug()) {
                debugActors.addFirst(index);
            }
            stamps[index] = options.stamp();
            published++;
        }
        return new Result(examined, published);
    }

    public int getActorStamp(int index) {
        return stamps[index];
    }

    public void clearStamps() {
        Arrays.fill(stamps, 0);
    }
}
