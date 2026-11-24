package com.rost.cruma.viewer.render;

import com.rost.cruma.core.world.Entity;
import com.rost.cruma.core.world.Entity.AnimatedTextureEntityMaterial;
import com.rost.cruma.core.world.Entity.ColorEntityMaterial;
import com.rost.cruma.core.world.Entity.ColorModifierEntityMaterial;
import com.rost.cruma.core.world.Entity.CombinerEntityMaterial;
import com.rost.cruma.core.world.Entity.ConstantOpacityEntityMaterial;
import com.rost.cruma.core.world.Entity.EntityMaterial;
import com.rost.cruma.core.world.Entity.EntityMesh;
import com.rost.cruma.core.world.Entity.EnvMapEntityMaterial;
import com.rost.cruma.core.world.Entity.MultiTextureEntityMaterial;
import com.rost.cruma.core.world.Entity.ReflectiveEntityMaterial;
import com.rost.cruma.core.world.Entity.SpecularEntityMaterial;
import com.rost.cruma.core.world.Entity.Surface;
import com.rost.cruma.core.world.Entity.TerrainMesh;
import com.rost.cruma.core.world.Entity.Texture;
import com.rost.cruma.core.world.Entity.TextureEntityMaterial;
import com.rost.cruma.core.world.Entity.TextureEntityMaterialWithOpacity;
import com.rost.cruma.core.world.Entity.WaterEntityMaterial;
import com.rost.cruma.core.world.UMap;
import com.rost.cruma.viewer.render.api.SceneCensus;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

public final class SceneContentCensus {
    private static final String MATERIAL_DIAGNOSTICS = System.getProperty("cruma.viewer.debug_material_census");
    private final TreeMap<String, MapCensus> maps = new TreeMap<>();
    private SceneCensus cached = SceneCensus.empty();
    private boolean dirty;

    public static Prepared prepare(UMap map) {
        if (map == null || map.name() == null || map.name().isBlank()) {
            throw new IllegalArgumentException("scene census requires a named map");
        }
        if (MATERIAL_DIAGNOSTICS != null) {
            writeMaterialDiagnostics(map, Path.of(MATERIAL_DIAGNOSTICS));
        }
        return new Prepared(map.name(), inspect(map));
    }

    static String materialFeature(EntityMaterial material) {
        return switch (material) {
            case ConstantOpacityEntityMaterial value ->
                "CONSTANT_OPACITY[alpha=" + value.alpha() + "](" + materialFeature(value.base()) + ')';
            case ColorModifierEntityMaterial value -> "COLOR_MODIFIER(" + materialFeature(value.base()) + ')';
            case SpecularEntityMaterial value -> {
                var overlay = value.overlay();
                yield "SPECULAR[texture="
                        + (overlay.texture() != null)
                        + ",mask="
                        + (overlay.mask() != null)
                        + ",uv="
                        + overlay.uvAnimation().mode()
                        + ",mod2x="
                        + overlay.modulate2x()
                        + ",lit="
                        + overlay.lit()
                        + ",fade="
                        + (overlay.fade() != null)
                        + ",emissive="
                        + overlay.emissive()
                        + ",selfIllumination="
                        + overlay.selfIllumination()
                        + ",separateAlpha="
                        + overlay.separateAlphaPass()
                        + ",maskUv="
                        + overlay.maskUvChannel()
                        + "]("
                        + materialFeature(value.base())
                        + ')';
            }
            case AnimatedTextureEntityMaterial value -> "ANIMATED";
            case CombinerEntityMaterial value ->
                "COMBINER[m2="
                        + (value.material2() != null)
                        + ",mask="
                        + (value.mask() != null)
                        + ",op="
                        + value.combineOp()
                        + ",fade="
                        + (value.material2Fade() != null)
                        + ",invert="
                        + value.invertMask()
                        + ",alphaOp="
                        + value.alphaOp()
                        + ",vertexMask="
                        + value.vertexColorMask()
                        + ']';
            case ReflectiveEntityMaterial value -> "REFLECTIVE[base=" + (value.base() != null) + ']';
            case EnvMapEntityMaterial value -> "ENVMAP[type=" + value.envMapType() + ']';
            case ColorEntityMaterial value -> "COLOR";
            case TextureEntityMaterial value -> "TEXTURE";
            case TextureEntityMaterialWithOpacity value ->
                "TEXTURE_OPACITY[opacity=" + (value.opacityTexture() != null) + ']';
            case WaterEntityMaterial value -> "WATER[layered=" + (value.material2() != null) + ']';
            case MultiTextureEntityMaterial value ->
                "MULTI_TEXTURE[layers=" + value.multiTexture().entries().size() + ']';
        };
    }

    public synchronized void include(UMap map) {
        include(prepare(map));
    }

    public synchronized void include(Prepared prepared) {
        maps.put(prepared.mapName, prepared.census);
        dirty = true;
    }

    public static final class Prepared {
        private final String mapName;
        private final MapCensus census;

        private Prepared(String mapName, MapCensus census) {
            this.mapName = mapName;
            this.census = census;
        }

        public String getMapName() {
            return mapName;
        }
    }

    public synchronized void remove(String mapName) {
        if (mapName != null && maps.remove(mapName) != null) {
            dirty = true;
        }
    }

    public synchronized SceneCensus snapshot() {
        if (!dirty) {
            return cached;
        }
        var featureCounts = new TreeMap<String, Integer>();
        var blendCounts = new TreeMap<String, Integer>();
        var formatCounts = new TreeMap<String, Integer>();
        var entities = 0;
        var primitives = 0;
        var fxEmitters = 0;
        var waterSurfaces = 0;
        var reflectionChildren = 0;
        var terrainLayers = 0;
        var textureInputs = 0;
        var aggregate = digest();
        for (var entry : maps.entrySet()) {
            var map = entry.getValue();
            entities = Math.addExact(entities, map.entityCount);
            primitives = Math.addExact(primitives, map.primitiveCount);
            fxEmitters = Math.addExact(fxEmitters, map.fxEmitterCount);
            waterSurfaces = Math.addExact(waterSurfaces, map.waterSurfaceCount);
            reflectionChildren = Math.addExact(reflectionChildren, map.reflectionChildCount);
            terrainLayers = Math.max(terrainLayers, map.maximumTerrainLayers);
            textureInputs = Math.max(textureInputs, map.maximumAuthoredTextureInputs);
            merge(featureCounts, map.materialFeatureCombinations);
            merge(blendCounts, map.blendModes);
            merge(formatCounts, map.textureFormats);
            update(aggregate, entry.getKey());
            update(aggregate, map.fingerprint);
        }
        cached = new SceneCensus(
                maps.size(),
                entities,
                primitives,
                fxEmitters,
                waterSurfaces,
                reflectionChildren,
                terrainLayers,
                textureInputs,
                featureCounts,
                blendCounts,
                formatCounts,
                new ArrayList<>(maps.keySet()),
                HexFormat.of().formatHex(aggregate.digest()));
        dirty = false;
        return cached;
    }

    private static MapCensus inspect(UMap map) {
        var mutable = new MutableMapCensus();
        var fingerprint = digest();
        update(fingerprint, map.name());
        var entityOrdinal = 0;
        for (var entity : map.entities()) {
            mutable.entityCount++;
            update(fingerprint, "entity=" + entityOrdinal++);
            update(fingerprint, "mesh-name=" + String.valueOf(entity.getMeshName()));
            switch (entity.getMesh()) {
                case EntityMesh mesh -> {
                    update(
                            fingerprint,
                            "entity-mesh="
                                    + mesh.vertices().size()
                                    + ':'
                                    + mesh.indices().length
                                    + ':'
                                    + mesh.instanceMatrices().size());
                    for (var surface : mesh.surfaces()) {
                        inspectSurface("ENTITY", surface, mutable, fingerprint);
                    }
                }
                case TerrainMesh mesh -> {
                    update(fingerprint, "terrain-mesh=" + mesh.vertices().size() + ':' + mesh.indices().length);
                    inspectSurface("TERRAIN", mesh.surface(), mutable, fingerprint);
                }
            }
        }
        for (var descriptor : map.spriteEmitters()) {
            mutable.fxEmitterCount++;
            increment(mutable.features, "FX_SPRITE|blend=" + descriptor.blend());
            increment(mutable.blends, "FX_" + descriptor.blend());
            addTexture(descriptor.sprite(), mutable);
        }
        for (var descriptor : map.meshEmitters()) {
            mutable.fxEmitterCount++;
            for (var surface : descriptor.mesh().surfaces()) {
                inspectSurface("PARTICLE_MESH", surface, mutable, fingerprint);
            }
        }
        for (var descriptor : map.beamEmitters()) {
            mutable.fxEmitterCount++;
            increment(mutable.features, "FX_BEAM|blend=" + descriptor.blend());
            increment(mutable.blends, "FX_" + descriptor.blend());
            addTexture(descriptor.texture(), mutable);
        }
        update(fingerprint, "sprite-fx=" + map.spriteEmitters().size());
        update(fingerprint, "mesh-fx=" + map.meshEmitters().size());
        update(fingerprint, "beam-fx=" + map.beamEmitters().size());
        return mutable.freeze(HexFormat.of().formatHex(fingerprint.digest()));
    }

    private static void writeMaterialDiagnostics(UMap map, Path directory) {
        var counts = new TreeMap<String, Integer>();
        for (var entity : map.entities()) {
            switch (entity.getMesh()) {
                case EntityMesh mesh -> {
                    for (var surface : mesh.surfaces()) {
                        countDiagnosticSurface(counts, "ENTITY", entity.getMeshName(), surface);
                    }
                }
                case TerrainMesh mesh ->
                    countDiagnosticSurface(counts, "TERRAIN", entity.getMeshName(), mesh.surface());
            }
        }
        for (var descriptor : map.meshEmitters()) {
            for (var surface : descriptor.mesh().surfaces()) {
                countDiagnosticSurface(counts, "PARTICLE_MESH", null, surface);
            }
        }
        var output = new StringBuilder("geometry\tmesh\tmaterial\tsurface\tblend\tfeatures\tcount\n");
        counts.forEach(
                (key, count) -> output.append(key).append('\t').append(count).append('\n'));
        try {
            Files.createDirectories(directory);
            Files.writeString(directory.resolve(map.name() + ".tsv"), output);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static void countDiagnosticSurface(
            Map<String, Integer> counts, String geometry, String meshName, Surface surface) {
        increment(
                counts,
                geometry
                        + '\t'
                        + meshName
                        + '\t'
                        + surface.materialName()
                        + '\t'
                        + Long.toUnsignedString(surface.type() & ~Entity.CSG_DEPTH_LAYER_MASK, 16)
                        + '\t'
                        + surface.blendMode()
                        + '\t'
                        + materialFeature(surface.material()));
    }

    private static void inspectSurface(
            String geometry, Surface surface, MutableMapCensus target, MessageDigest fingerprint) {
        target.primitiveCount++;
        var material = surface.material();
        var feature = geometry
                + "|surface=0x"
                + Long.toUnsignedString(surface.type() & ~Entity.CSG_DEPTH_LAYER_MASK, 16)
                + "|blend="
                + surface.blendMode()
                + "|uv="
                + surface.uvAnimation().mode()
                + "|alpha="
                + surface.alphaRef()
                + "|vertexColor="
                + surface.usesVertexColor()
                + "|depthWrite="
                + surface.depthWrite()
                + "|light2x="
                + surface.modulateStaticLighting2x()
                + "|material="
                + materialFeature(material);
        increment(target.features, feature);
        increment(target.blends, surface.blendMode().name());
        collectTextures(material, target);
        target.maximumAuthoredTextureInputs =
                Math.max(target.maximumAuthoredTextureInputs, authoredTextureInputs(material));
        if (material instanceof MultiTextureEntityMaterial terrain) {
            target.maximumTerrainLayers = Math.max(
                    target.maximumTerrainLayers,
                    terrain.multiTexture().entries().size());
        }
        var water = surface.water() || surface.reflectionPlane() != null || material instanceof WaterEntityMaterial;
        if (water) {
            target.waterSurfaceCount++;
        }
        if (surface.reflectionPlane() != null) {
            target.reflectionChildCount++;
        }
        update(fingerprint, feature + "|indices=" + surface.indexOffset() + ':' + surface.indexCount());
    }

    private static int authoredTextureInputs(EntityMaterial material) {
        return switch (material) {
            case ColorModifierEntityMaterial value -> authoredTextureInputs(value.base());
            case ConstantOpacityEntityMaterial value -> authoredTextureInputs(value.base());
            case SpecularEntityMaterial value ->
                authoredTextureInputs(value.base())
                        + (value.overlay().texture() != null || value.overlay().fade() != null ? 1 : 0)
                        + (value.overlay().mask() != null ? 1 : 0);
            case AnimatedTextureEntityMaterial value -> value.frames().isEmpty() ? 0 : 1;
            case CombinerEntityMaterial value ->
                1
                        + (value.material2() != null || value.material2Fade() != null ? 1 : 0)
                        + (value.mask() != null && !value.vertexColorMask() ? 1 : 0);
            case ReflectiveEntityMaterial value -> (value.base() == null ? 0 : 1) + 1;
            case EnvMapEntityMaterial value -> 1;
            case ColorEntityMaterial value -> 0;
            case TextureEntityMaterial value -> 1;
            case TextureEntityMaterialWithOpacity value -> 1 + (value.opacityTexture() == null ? 0 : 1);
            case WaterEntityMaterial value -> 1 + (value.material2() == null ? 0 : 1);
            case MultiTextureEntityMaterial value -> {
                var inputs = 0;
                for (var entry : value.multiTexture().entries()) {
                    inputs += entry.texture() == null ? 0 : 1;
                    inputs += entry.alphaMap() == null ? 0 : 1;
                }
                yield inputs;
            }
        };
    }

    private static void collectTextures(EntityMaterial material, MutableMapCensus target) {
        switch (material) {
            case ColorModifierEntityMaterial value -> collectTextures(value.base(), target);
            case ConstantOpacityEntityMaterial value -> collectTextures(value.base(), target);
            case SpecularEntityMaterial value -> {
                collectTextures(value.base(), target);
                addTexture(value.overlay().texture(), target);
                addTexture(value.overlay().mask(), target);
            }
            case AnimatedTextureEntityMaterial value -> value.frames().forEach(texture -> addTexture(texture, target));
            case CombinerEntityMaterial value -> {
                addTexture(value.material1(), target);
                addTexture(value.material2(), target);
                addTexture(value.mask(), target);
            }
            case ReflectiveEntityMaterial value -> {
                addTexture(value.base(), target);
                value.cubemap().faces().forEach(texture -> addTexture(texture, target));
            }
            case EnvMapEntityMaterial value -> addTexture(value.map(), target);
            case ColorEntityMaterial value -> {}
            case TextureEntityMaterial value -> addTexture(value.texture(), target);
            case TextureEntityMaterialWithOpacity value -> {
                addTexture(value.texture(), target);
                addTexture(value.opacityTexture(), target);
            }
            case WaterEntityMaterial value -> {
                addTexture(value.texture(), target);
                addTexture(value.material2(), target);
            }
            case MultiTextureEntityMaterial value -> {
                for (var entry : value.multiTexture().entries()) {
                    addTexture(entry.texture(), target);
                    addTexture(entry.alphaMap(), target);
                }
            }
        }
    }

    private static void addTexture(Texture texture, MutableMapCensus target) {
        if (texture != null) {
            increment(target.formats, texture.format().name());
        }
    }

    private static void merge(Map<String, Integer> target, Map<String, Integer> source) {
        for (var entry : source.entrySet()) {
            target.merge(entry.getKey(), entry.getValue(), Math::addExact);
        }
    }

    private static void increment(Map<String, Integer> target, String key) {
        target.merge(key, 1, Math::addExact);
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) '\n');
    }

    private record MapCensus(
            int entityCount,
            int primitiveCount,
            int fxEmitterCount,
            int waterSurfaceCount,
            int reflectionChildCount,
            int maximumTerrainLayers,
            int maximumAuthoredTextureInputs,
            Map<String, Integer> materialFeatureCombinations,
            Map<String, Integer> blendModes,
            Map<String, Integer> textureFormats,
            String fingerprint) {}

    private static final class MutableMapCensus {
        private int entityCount;
        private int primitiveCount;
        private int fxEmitterCount;
        private int waterSurfaceCount;
        private int reflectionChildCount;
        private int maximumTerrainLayers;
        private int maximumAuthoredTextureInputs;
        private final Map<String, Integer> features = new LinkedHashMap<>();
        private final Map<String, Integer> blends = new LinkedHashMap<>();
        private final Map<String, Integer> formats = new LinkedHashMap<>();

        private MapCensus freeze(String fingerprint) {
            return new MapCensus(
                    entityCount,
                    primitiveCount,
                    fxEmitterCount,
                    waterSurfaceCount,
                    reflectionChildCount,
                    maximumTerrainLayers,
                    maximumAuthoredTextureInputs,
                    Map.copyOf(features),
                    Map.copyOf(blends),
                    Map.copyOf(formats),
                    fingerprint);
        }
    }
}
