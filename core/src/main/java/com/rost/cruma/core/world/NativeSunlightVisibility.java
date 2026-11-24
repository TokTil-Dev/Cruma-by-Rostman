package com.rost.cruma.core.world;

import glm_.vec3.Vec3;
import org.jetbrains.annotations.Nullable;

public final class NativeSunlightVisibility {
    public static final int TRACE_FLAGS = 0x380;

    private boolean culled;

    public NativeSunlightVisibility(boolean culledSunlight) {
        culled = culledSunlight;
    }

    public record Receiver(Object identity, Vec3 location, Vec3 movement, boolean unlitCheck, int[] leaves) {}

    public record TraceResult(boolean clear, Vec3 hitLocation) {}

    public interface World {
        int getLeafZone(int leaf);

        boolean isZoneSunAffected(int zone);

        Vec3 getViewpoint();

        TraceResult checkSingleLine(Object receiver, Vec3 start, Vec3 end, int flags, Vec3 extent);
    }

    public boolean isVisible(
            Receiver receiver, boolean editor, @Nullable World world, Vec3 traceDirection, boolean present) {
        if (editor || !receiver.unlitCheck()) {
            return present;
        }
        var motion = receiver.movement();
        if (motion.getX() == 0f && motion.getY() == 0f && motion.getZ() == 0f) {
            return !culled;
        }
        culled = false;
        if (world == null) {
            return true;
        }
        for (var leaf : receiver.leaves()) {
            if (!world.isZoneSunAffected(world.getLeafZone(leaf))) {
                culled = true;
                return false;
            }
        }
        var location = receiver.location();
        var view = world.getViewpoint();
        var dx = view.getX() - location.getX();
        var dy = view.getY() - location.getY();
        var distance = (float) Math.sqrt((double) (dy * dy + dx * dx));
        if (distance <= 512f) {
            var end = new Vec3(
                    location.getX() - traceDirection.getX() * 65536f,
                    location.getY() - traceDirection.getY() * 65536f,
                    location.getZ() - traceDirection.getZ() * 65536f);
            var hit = world.checkSingleLine(receiver.identity(), location, end, TRACE_FLAGS, new Vec3());
            if (!hit.clear()) {
                dx = hit.hitLocation().getX() - location.getX();
                dy = hit.hitLocation().getY() - location.getY();
                var dz = hit.hitLocation().getZ() - location.getZ();
                var hitDistance = (float) Math.sqrt((double) ((dx * dx + dy * dy) + dz * dz));
                culled = Math.abs(hitDistance) < 2000f;
            }
        }
        return !culled;
    }

    public boolean isSunlightCulled() {
        return culled;
    }
}
