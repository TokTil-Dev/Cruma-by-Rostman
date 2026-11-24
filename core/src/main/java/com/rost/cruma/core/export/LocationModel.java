package com.rost.cruma.core.export;

import com.rost.cruma.core.audio.SoundClip;
import com.rost.cruma.core.world.AmbientSoundHours;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.jetbrains.annotations.Nullable;

record LocationModel(
        String name,
        float[] origin,
        float metersPerUnit,
        List<Image> images,
        List<Material> materials,
        List<Mesh> meshes,
        List<Tile> tiles,
        List<LayerFile> layerFiles,
        Map<String, Integer> zoneDefaults) {
    LocationModel(
            String name,
            float[] origin,
            float metersPerUnit,
            List<Image> images,
            List<Material> materials,
            List<Mesh> meshes,
            List<Tile> tiles) {
        this(name, origin, metersPerUnit, images, materials, meshes, tiles, List.of(), Map.of());
    }

    enum AlphaMode {
        OPAQUE,
        MASK,
        BLEND
    }

    record Image(String name, int width, int height, Supplier<byte[]> rgba) {}

    record LayerFile(String path, int width, int height, boolean gray, Supplier<byte[]> pixels) {}

    record TerrainLayer(
            int index, String name, String texture, String alphaMap, boolean diffuseAlpha, float[] u, float[] v) {}

    record Wrap(boolean clampU, boolean clampV) {
        static final Wrap REPEAT = new Wrap(false, false);
    }

    record Material(
            String name,
            int baseImage,
            Wrap wrap,
            float[] baseColor,
            AlphaMode alphaMode,
            float alphaCutoff,
            boolean doubleSided,
            boolean unlit,
            int emissiveImage,
            float[] emissive,
            float roughness,
            int normalImage,
            float ior,
            int baseTexCoord,
            Map<String, String> extras,
            List<TerrainLayer> terrainLayers) {
        Material(
                String name,
                int baseImage,
                Wrap wrap,
                float[] baseColor,
                AlphaMode alphaMode,
                float alphaCutoff,
                boolean doubleSided,
                boolean unlit,
                int emissiveImage,
                float[] emissive,
                float roughness,
                int normalImage,
                float ior,
                int baseTexCoord,
                Map<String, String> extras) {
            this(
                    name,
                    baseImage,
                    wrap,
                    baseColor,
                    alphaMode,
                    alphaCutoff,
                    doubleSided,
                    unlit,
                    emissiveImage,
                    emissive,
                    roughness,
                    normalImage,
                    ior,
                    baseTexCoord,
                    extras,
                    List.of());
        }

        boolean matte() {
            return !unlit && roughness >= 1f;
        }
    }

    record Primitive(
            float[] positions,
            float[] normals,
            float[] uvs,
            float @Nullable [] tileUvs,
            int[] indices,
            int material,
            float @Nullable [] light,
            float @Nullable [] lightmap,
            @Nullable Facing facing) {
        Primitive(
                float[] positions,
                float[] normals,
                float[] uvs,
                float @Nullable [] tileUvs,
                int[] indices,
                int material,
                float @Nullable [] light,
                float @Nullable [] lightmap) {
            this(positions, normals, uvs, tileUvs, indices, material, light, lightmap, null);
        }

        Primitive(
                float[] positions,
                float[] normals,
                float[] uvs,
                float @Nullable [] tileUvs,
                int[] indices,
                int material) {
            this(positions, normals, uvs, tileUvs, indices, material, null, null);
        }

        Primitive(
                float[] positions,
                float[] normals,
                float[] uvs,
                float @Nullable [] tileUvs,
                int[] indices,
                int material,
                float @Nullable [] light) {
            this(positions, normals, uvs, tileUvs, indices, material, light, null);
        }

        int vertexCount() {
            return positions.length / 3;
        }
    }

    record Facing(float[] colors, float[] centers, float[] shapes, float[] axes) {}

    record Mesh(String name, List<Primitive> primitives) {}

    record Node(
            String name,
            int mesh,
            float @Nullable [] matrix,
            Map<String, String> extras,
            @Nullable Instances instances,
            @Nullable MeshLight light,
            int @Nullable [] projectors) {
        Node(String name, int mesh, float @Nullable [] matrix) {
            this(name, mesh, matrix, Map.of(), null, null);
        }

        Node(String name, int mesh, float @Nullable [] matrix, Map<String, String> extras) {
            this(name, mesh, matrix, extras, null, null);
        }

        Node(
                String name,
                int mesh,
                float @Nullable [] matrix,
                Map<String, String> extras,
                @Nullable Instances instances) {
            this(name, mesh, matrix, extras, instances, null);
        }

        Node(
                String name,
                int mesh,
                float @Nullable [] matrix,
                Map<String, String> extras,
                @Nullable Instances instances,
                @Nullable MeshLight light) {
            this(name, mesh, matrix, extras, instances, light, null);
        }

        int copies() {
            return instances == null ? 1 : instances.count();
        }
    }

    record MeshLight(
            int ambient,
            int actorRgb,
            float intensity,
            int sun,
            float @Nullable [] hardwareSun,
            byte[] vertices,
            int @Nullable [] beastPages,
            float @Nullable [] beastCells) {
        MeshLight(
                int ambient, int actorRgb, float intensity, int sun, float @Nullable [] hardwareSun, byte[] vertices) {
            this(ambient, actorRgb, intensity, sun, hardwareSun, vertices, null, null);
        }
    }

    record Instances(float[] translations, float[] rotations, float[] scales, float @Nullable [] light) {
        Instances {
            var count = translations.length / 3;
            if (translations.length % 3 != 0
                    || rotations.length != count * 4
                    || scales.length != count * 3
                    || light != null && light.length != count * 4) {
                throw new IllegalArgumentException("instance arrays of different lengths");
            }
        }

        Instances(float[] translations, float[] rotations, float[] scales) {
            this(translations, rotations, scales, null);
        }

        int count() {
            return translations.length / 3;
        }
    }

    record Group(String name, List<Node> nodes) {}

    record Lamp(
            String name,
            float[] position,
            float[] color,
            float radius,
            float @Nullable [] direction,
            float cone,
            int effect,
            int type) {}

    record Projector(
            String name,
            String texture,
            int blend,
            float[] location,
            float[] direction,
            float[] left,
            float[] up,
            float uSize,
            float vSize,
            int fov,
            float maxTrace,
            boolean terrain,
            boolean level,
            boolean meshes,
            boolean backfaces) {
        Projector(
                String name,
                String texture,
                int blend,
                float[] location,
                float[] direction,
                float[] left,
                float[] up,
                float uSize,
                float vSize,
                int fov,
                float maxTrace) {
            this(
                    name, texture, blend, location, direction, left, up, uSize, vSize, fov, maxTrace, true, false,
                    false, false);
        }
    }

    record Sound(
            String name,
            String file,
            boolean emitter,
            String actor,
            String tile,
            float[] position,
            boolean spatial,
            float radius,
            float volume,
            float pitchMin,
            float pitchMax,
            boolean loop,
            int chance,
            float startTime,
            AmbientSoundHours hours,
            String when,
            float delaySeconds,
            float fadeInSeconds,
            float fadeOutStartSeconds,
            float fadeOutSeconds,
            float seconds,
            SoundClip clip) {
        float maxDistance() {
            return radius * LocationModelBuilder.AUDIBLE_RADII;
        }
    }

    record EffectActor(
            String actor,
            String tile,
            float[] position,
            float[] rotation,
            float[] scale,
            List<String> nodes,
            List<EffectEmitter> emitters) {}

    record EffectEmitter(String name, int order, String type, String texture, int blend) {}

    record MusicTrack(String name, String file, boolean decoded, TrackBytes bytes) {}

    interface TrackBytes {
        byte[] read() throws IOException;
    }

    record MusicZone(
            String actor,
            String tile,
            int musicId,
            List<MusicTrack> tracks,
            boolean night,
            int nightMusicId,
            List<MusicTrack> nightTracks,
            boolean loop,
            boolean force,
            float[] boundsMin,
            float[] boundsMax,
            float[] planes,
            int[] front,
            int[] back,
            boolean[] decides,
            boolean rootOutside,
            List<String> missing) {}

    record Tile(
            String name,
            List<Group> groups,
            List<Lamp> lamps,
            List<Projector> projectors,
            List<Sound> sounds,
            List<EffectActor> effects,
            List<MusicZone> music) {
        Tile(String name, List<Group> groups) {
            this(name, groups, List.of(), List.of(), List.of(), List.of(), List.of());
        }
    }
}
