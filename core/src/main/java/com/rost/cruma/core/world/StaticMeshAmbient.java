package com.rost.cruma.core.world;

import glm_.vec3.Vec3;

public record StaticMeshAmbient(Source source, int actorRgb, boolean animatedGlow) {
    public StaticMeshAmbient {
        if (source == null || (actorRgb & 0xff00_0000) != 0) {
            throw new IllegalArgumentException("ambient requires a source and RGB8 color");
        }
    }

    public StaticMeshAmbient(Source source, Vec3 actorColor, boolean animatedGlow) {
        this(
                source,
                toChannel(actorColor.getX()) | toChannel(actorColor.getY()) << 8 | toChannel(actorColor.getZ()) << 16,
                animatedGlow);
    }

    public enum Source {
        ACTOR(1),
        ENVIRONMENT(2),
        ENVIRONMENT_FLAT_SUN(3),
        BEAST(4),
        BEAST_FALLBACK(5);

        private final int id;

        Source(int id) {
            this.id = id;
        }

        public static Source select(
                boolean sunAffected,
                boolean hasEnvironment,
                boolean beastWorld,
                boolean originalInstance,
                boolean acceptedBeast,
                boolean billboard) {
            if (!sunAffected || !hasEnvironment) {
                return ACTOR;
            }
            if (beastWorld) {
                return acceptedBeast && !billboard ? BEAST : BEAST_FALLBACK;
            }
            return !originalInstance || billboard ? ENVIRONMENT_FLAT_SUN : ENVIRONMENT;
        }

        public int getId() {
            return id;
        }
    }

    public int control() {
        return source.getId() | (animatedGlow ? 8 : 0);
    }

    private static int toChannel(float value) {
        return Math.clamp(Math.round(value * 255f), 0, 255);
    }
}
