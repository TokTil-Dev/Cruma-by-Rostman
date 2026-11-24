package com.rost.cruma.core.export;

import com.rost.cruma.core.unreal.primitives.URotator;
import com.rost.cruma.core.world.Entity;
import com.rost.cruma.core.world.Entity.EntityMesh;
import com.rost.cruma.core.world.Entity.Texture;
import com.rost.cruma.core.world.UMap;
import glm_.vec3.Vec3;
import java.util.List;
import org.jetbrains.annotations.Nullable;

@FunctionalInterface
public interface EffectStill {
    EffectStill NONE = map -> List.of();

    List<Effect> take(UMap map);

    default List<List<Effect>> takeLoop(UMap map, int count, float interval) {
        return List.of(take(map));
    }

    sealed interface Effect permits Cards, MeshCopies {
        String name();

        String actor();

        int order();

        Placement placement();
    }

    record Placement(Vec3 location, URotator rotation, Vec3 scale) {
        public float[] matrix() {
            return Entity.modelMatrix(location, rotation.vector(), scale).to(new float[16]);
        }
    }

    record Cards(
            String name,
            String actor,
            int order,
            Placement placement,
            Texture texture,
            String textureName,
            int blend,
            float[] centers,
            float[] shapes,
            float[] axes,
            float[] uvs,
            float[] colors,
            int[] indices)
            implements Effect {
        public Cards {
            var vertices = centers.length / 3;
            if (centers.length != vertices * 3
                    || shapes.length != vertices * 3
                    || axes.length != vertices * 3
                    || uvs.length != vertices * 2
                    || colors.length != vertices * 4
                    || indices.length % 3 != 0) {
                throw new IllegalArgumentException("the vertices of an effect need all their values");
            }
        }

        public int vertexCount() {
            return centers.length / 3;
        }
    }

    record MeshCopies(
            String name,
            String actor,
            int order,
            Placement placement,
            EntityMesh mesh,
            List<float[]> matrices,
            float[] colors,
            int blend,
            float @Nullable [] lights)
            implements Effect {
        public MeshCopies {
            if (colors.length != matrices.size() * 4) {
                throw new IllegalArgumentException("each copy of an effect's mesh needs its colour");
            }
            if (lights != null && lights.length != mesh.surfaces().size() * 3) {
                throw new IllegalArgumentException("each surface of an effect's mesh needs its light");
            }
        }

        public MeshCopies(
                String name,
                String actor,
                int order,
                Placement placement,
                EntityMesh mesh,
                List<float[]> matrices,
                float[] colors,
                int blend) {
            this(name, actor, order, placement, mesh, matrices, colors, blend, null);
        }
    }
}
