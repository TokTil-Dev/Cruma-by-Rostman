package com.rost.cruma.core.editing;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.jetbrains.annotations.Nullable;

public final class ActorLightingChanges {
    private final Map<String, Actor> actors;
    private final Map<Integer, BeastTexture> textures;

    public ActorLightingChanges(Map<String, Actor> actors) {
        this(actors, Map.of());
    }

    public ActorLightingChanges(Map<String, Actor> actors, Map<Integer, BeastTexture> textures) {
        var sorted = new TreeMap<String, Actor>(String.CASE_INSENSITIVE_ORDER);
        sorted.putAll(actors);
        this.actors = Collections.unmodifiableMap(sorted);
        this.textures = Collections.unmodifiableMap(new TreeMap<>(textures));
    }

    public record Light(int reference, byte[] bits, int applied) {
        public Light {
            if (reference == 0) {
                throw new IllegalArgumentException("A light entry needs a light reference");
            }
            bits = bits.clone();
        }

        @Override
        public byte[] bits() {
            return bits.clone();
        }

        byte[] rawBits() {
            return bits;
        }
    }

    public record BeastTexture(int width, int height, List<byte[]> mips) {
        public BeastTexture {
            if (!BeastTextureCodec.isValidSize(width, height) || mips.size() != BeastTextureCodec.MIP_LEVELS) {
                throw new IllegalArgumentException("Invalid Beast lightmap texture");
            }
            mips = List.of(mips.get(0).clone(), mips.get(1).clone());
            if (mips.get(0).length != BeastTextureCodec.dxt1Size(width, height)
                    || mips.get(1).length
                            != BeastTextureCodec.dxt1Size(Math.max(1, width / 2), Math.max(1, height / 2))) {
                throw new IllegalArgumentException("Beast lightmap mips do not match the size");
            }
        }
    }

    public record BeastBake(BeastTexture slot0, BeastTexture slot1) {
        public BeastBake {
            Objects.requireNonNull(slot0);
            Objects.requireNonNull(slot1);
            if (slot0.width() != slot1.width() || slot0.height() != slot1.height()) {
                throw new IllegalArgumentException("Both Beast slots need the same size");
            }
        }

        public int width() {
            return slot0.width();
        }

        public int height() {
            return slot0.height();
        }
    }

    public record Instance(
            int vertexCount,
            byte[] colors,
            int revision,
            List<Light> sceneLights,
            List<Light> environmentLights,
            @Nullable BeastBake beast) {
        public Instance(
                int vertexCount, byte[] colors, int revision, List<Light> sceneLights, List<Light> environmentLights) {
            this(vertexCount, colors, revision, sceneLights, environmentLights, null);
        }

        public Instance {
            if (vertexCount < 0 || colors.length != vertexCount * 4) {
                throw new IllegalArgumentException("Color stream size does not match the vertex count");
            }
            colors = colors.clone();
            sceneLights = List.copyOf(sceneLights);
            environmentLights = List.copyOf(environmentLights);
            var bytes = (vertexCount + 7) >>> 3;
            for (var light : sceneLights) {
                if (light.rawBits().length != bytes) {
                    throw new IllegalArgumentException("Scene light bitmap size does not match the vertex count");
                }
            }
            for (var light : environmentLights) {
                if (light.rawBits().length != bytes) {
                    throw new IllegalArgumentException("Environment light bitmap size does not match the vertex count");
                }
            }
        }

        public Instance withBeast(@Nullable BeastBake bake) {
            return new Instance(vertexCount, colors, revision, sceneLights, environmentLights, bake);
        }

        @Override
        public byte[] colors() {
            return colors.clone();
        }

        byte[] rawColors() {
            return colors;
        }
    }

    public record Actor(
            Instance lod0,
            @Nullable Instance lod1,
            @Nullable Instance lod2,
            boolean lod2SharesLod1) {
        public Actor {
            Objects.requireNonNull(lod0);
            if (lod2SharesLod1 && (lod1 == null || lod2 != null)) {
                throw new IllegalArgumentException(
                        "A shared LOD2 instance needs a LOD1 instance and no own LOD2 instance");
            }
        }
    }

    public static ActorLightingChanges empty() {
        return new ActorLightingChanges(Map.of());
    }

    public Map<String, Actor> getActors() {
        return actors;
    }

    public Map<Integer, BeastTexture> getTextures() {
        return textures;
    }

    public boolean isEmpty() {
        return actors.isEmpty() && textures.isEmpty();
    }
}
