package com.rost.cruma.viewer.spawn;

import com.rost.cruma.core.unreal.primitives.USphere;
import com.rost.cruma.core.world.Entity.EnvironmentLayer;
import com.rost.cruma.core.world.Entity.UvAnimation;
import com.rost.cruma.core.world.NpcModel;
import com.rost.cruma.core.world.NpcPartPasses;
import glm_.vec3.Vec3;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

public record SpawnModelData(
        String key,
        float[] vertices,
        int[] indices,
        List<Part> parts,
        float height,
        float lowest,
        Map<String, Vec3> bones,
        String effectBone,
        @Nullable USphere litSphere) {
    public static final int FLOATS = 8;

    public SpawnModelData {
        parts = List.copyOf(parts);
        bones = Map.copyOf(bones);
    }

    public SpawnModelData(
            String key,
            float[] vertices,
            int[] indices,
            List<Part> parts,
            float height,
            float lowest,
            Map<String, Vec3> bones,
            String effectBone) {
        this(key, vertices, indices, parts, height, lowest, bones, effectBone, null);
    }

    public SpawnModelData(
            String key,
            float[] vertices,
            int[] indices,
            List<Part> parts,
            float height,
            float lowest,
            Map<String, Vec3> bones) {
        this(key, vertices, indices, parts, height, lowest, bones, "");
    }

    public SpawnModelData(String key, float[] vertices, int[] indices, List<Part> parts, float height, float lowest) {
        this(key, vertices, indices, parts, height, lowest, Map.of());
    }

    public record Part(
            int indexOffset,
            int indexCount,
            int textureWidth,
            int textureHeight,
            byte[] rgba,
            Kind kind,
            float alphaRef,
            boolean depthWrite,
            @Nullable NpcPartPasses passes,
            @Nullable Specular specular) {
        static final float CUT = .35f;

        public Part(
                int indexOffset,
                int indexCount,
                int textureWidth,
                int textureHeight,
                byte[] rgba,
                Kind kind,
                float alphaRef,
                boolean depthWrite,
                @Nullable NpcPartPasses passes) {
            this(indexOffset, indexCount, textureWidth, textureHeight, rgba, kind, alphaRef, depthWrite, passes, null);
        }

        public Part(
                int indexOffset,
                int indexCount,
                int textureWidth,
                int textureHeight,
                byte[] rgba,
                Kind kind,
                float alphaRef,
                boolean depthWrite) {
            this(indexOffset, indexCount, textureWidth, textureHeight, rgba, kind, alphaRef, depthWrite, null);
        }

        public Part(int indexOffset, int indexCount, int textureWidth, int textureHeight, byte[] rgba, Kind kind) {
            this(
                    indexOffset,
                    indexCount,
                    textureWidth,
                    textureHeight,
                    rgba,
                    kind,
                    kind == Kind.OPAQUE ? 0f : CUT,
                    kind != Kind.BLENDED);
        }

        public Part withSpecular(@Nullable Specular layer) {
            return new Part(
                    indexOffset,
                    indexCount,
                    textureWidth,
                    textureHeight,
                    rgba,
                    kind,
                    alphaRef,
                    depthWrite,
                    passes,
                    layer);
        }
    }

    public record Bitmap(int width, int height, byte[] rgba) {}

    public record Specular(
            Bitmap texture,
            @Nullable Bitmap mask,
            @Nullable EnvironmentLayer environment,
            UvAnimation animation,
            boolean modulate2x,
            boolean lit,
            boolean clampU,
            boolean clampV,
            boolean separatePass) {
        public Specular(
                Bitmap texture,
                @Nullable Bitmap mask,
                @Nullable EnvironmentLayer environment,
                UvAnimation animation,
                boolean modulate2x,
                boolean lit,
                boolean clampU,
                boolean clampV) {
            this(texture, mask, environment, animation, modulate2x, lit, clampU, clampV, false);
        }
    }

    public enum Kind {
        OPAQUE,
        MASKED,
        BLENDED
    }
}
