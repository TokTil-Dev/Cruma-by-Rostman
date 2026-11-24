package com.rost.cruma.viewer.render.bgfx;

import com.rost.cruma.core.texture.TexturePixels;
import com.rost.cruma.core.world.Entity;
import com.rost.cruma.core.world.Entity.BlendMode;
import com.rost.cruma.core.world.Entity.ColorEntityMaterial;
import com.rost.cruma.core.world.Entity.ColorModifierEntityMaterial;
import com.rost.cruma.core.world.Entity.ConstantOpacityEntityMaterial;
import com.rost.cruma.core.world.Entity.EntityMaterial;
import com.rost.cruma.core.world.Entity.EntityMesh;
import com.rost.cruma.core.world.Entity.MultiTexture;
import com.rost.cruma.core.world.Entity.MultiTextureEntityMaterial;
import com.rost.cruma.core.world.Entity.SpecularEntityMaterial;
import com.rost.cruma.core.world.Entity.Surface;
import com.rost.cruma.core.world.Entity.TerrainMesh;
import com.rost.cruma.core.world.Entity.TerrainVertexBuffer;
import com.rost.cruma.core.world.Entity.Texture;
import com.rost.cruma.core.world.Entity.TextureEntityMaterial;
import com.rost.cruma.core.world.Entity.TextureEntityMaterialWithOpacity;
import com.rost.cruma.core.world.NativeMemory;
import com.rost.cruma.core.world.ZoneStateChoice;
import com.rost.cruma.viewer.render.bgfx.BgfxSceneGeometry.Prepared;
import com.rost.cruma.viewer.render.bgfx.BgfxSceneGeometry.PreparedPrimitive;
import java.nio.ByteBuffer;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

final class BgfxGiMaterial {
    private final Map<Texture, byte[]> pixels;
    private final boolean shared;

    private BgfxGiMaterial(Map<Texture, byte[]> pixels, boolean shared) {
        this.pixels = pixels;
        this.shared = shared;
    }

    static BgfxGiMaterial sharing(Map<Texture, byte[]> pixels) {
        return new BgfxGiMaterial(pixels, true);
    }

    static ByteBuffer prepare(Entity source, Prepared geometry) {
        return new BgfxGiMaterial(new IdentityHashMap<>(), false).buildColors(source, geometry);
    }

    static int toTexel(float uv, int size, boolean clamp) {
        return clamp
                ? Math.clamp((int) Math.floor(uv * size), 0, size - 1)
                : Math.floorMod((int) Math.floor(uv * size), size);
    }

    static float srgbToLinear(float value) {
        value = Math.clamp(value, 0f, 1f);
        return value <= .04045f ? value / 12.92f : (float) Math.pow((value + .055f) / 1.055f, 2.4);
    }

    ByteBuffer buildColors(Entity source, Prepared geometry) {
        var accepted = geometry.primitives().stream()
                .filter(p -> p.rayCoverage().eligible())
                .toList();
        if (accepted.isEmpty()) {
            return ByteBuffer.allocateDirect(0);
        }
        var colors = buildTriangles(
                source,
                geometry,
                accepted,
                geometry.vertexData().remaining() / geometry.vertexCount(),
                BgfxMetalRayQueries.uvOffset(geometry.kind()));
        var packed = NativeMemory.directBuffer(colors.length * Float.BYTES);
        packed.asFloatBuffer().put(colors);
        return packed.asReadOnlyBuffer().order(packed.order());
    }

    float[] buildTriangles(Entity source, Prepared geometry, List<PreparedPrimitive> ranges, int stride, int uvOffset) {
        int count = ranges.stream().mapToInt(p -> p.indexCount() / 3).sum();
        float[] result = new float[count * 8];
        var vertices = geometry.vertexData();
        var indices = geometry.indexData();
        int output = 0;
        for (var range : ranges) {
            Surface surface = switch (source.getMesh()) {
                case EntityMesh mesh -> mesh.surfaces().get(range.sourceSurfaceOrdinal());
                case TerrainMesh mesh -> mesh.surface();
            };
            for (int i = 0; i < range.indexCount(); i += 3) {
                int a = indices.getInt((range.indexOffset() + i) * 4) * stride;
                int b = indices.getInt((range.indexOffset() + i + 1) * 4) * stride;
                int c = indices.getInt((range.indexOffset() + i + 2) * 4) * stride;
                float u = mean(vertices, a, b, c, uvOffset);
                float v = mean(vertices, a, b, c, uvOffset + 4);
                float[] rgb;
                if (surface.material() instanceof MultiTextureEntityMaterial terrain) {
                    var mesh = source.getMesh() instanceof TerrainMesh terrainMesh ? terrainMesh.vertexBuffer() : null;
                    rgb = sampleTerrain(terrain.multiTexture(), mesh, stride, a, b, c, u, v);
                } else {
                    rgb = surface.uvAnimation().mode() == 0 && surface.blendMode() != BlendMode.UNLIT
                            ? sample(surface.material(), u, v)
                            : new float[4];
                }
                for (int channel = 0; channel < 3; channel++) {
                    result[output++] = srgbToLinear(rgb[channel]);
                }
                result[output++] = rgb[3];
                var emission =
                        surface.uvAnimation().mode() == 0 ? sampleEmission(surface.material(), u, v) : new float[3];
                for (int channel = 0; channel < 3; channel++) {
                    result[output++] = emission[channel];
                }
                result[output++] = 0;
            }
        }
        if (!shared) {
            pixels.clear();
        }
        return result;
    }

    private float[] sampleTerrain(
            MultiTexture material,
            @Nullable TerrainVertexBuffer mesh,
            int stride,
            int a,
            int b,
            int c,
            float alphaU,
            float alphaV) {
        float[] color = new float[4];
        var layers = material.entries();
        var zones = material.getZoneLayers();
        var hidden = zones.findHiddenLayers(ZoneStateChoice.EMPTY);
        var base = zones.findBaseLayer(hidden);
        var drawn = mesh == null ? 0 : Math.min(BgfxTerrainMaterial.MAX_LAYERS, layers.size());
        for (int layer = 0; layer < drawn; layer++) {
            var entry = layers.get(layer);
            if (entry.texture() == null || layer < base || (hidden >>> layer & 1) != 0 && layer != base) {
                continue;
            }
            var layerU = mean(mesh, a / stride, b / stride, c / stride, layer * 2);
            var layerV = mean(mesh, a / stride, b / stride, c / stride, layer * 2 + 1);
            var sampled = sampleTexture(entry.texture(), layerU, layerV);
            float weight;
            if (layer == base) {
                weight = 1;
            } else if (layer / 4 < material.packedAlphaGroups().size()) {
                var atlas = material.packedAlphaGroups().get(layer / 4);
                int x = Math.clamp((int) (alphaU * atlas.width()), 0, atlas.width() - 1);
                int y = Math.clamp((int) (alphaV * atlas.height()), 0, atlas.height() - 1);
                weight = (atlas.rgba()[(y * atlas.width() + x) * 4 + layer % 4] & 255) / 255f;
            } else {
                weight = entry.alphaMap() == null ? 0 : sampleTexture(entry.alphaMap(), alphaU, alphaV)[3];
            }
            if (layer > base && entry.texture().alphaTexture()) {
                weight *= sampled[3];
            }
            for (int channel = 0; channel < 3; channel++) {
                color[channel] += (sampled[channel] - color[channel]) * weight;
            }
            color[3] = 1;
        }
        return color;
    }

    private float[] sample(EntityMaterial material, float u, float v) {
        return switch (material) {
            case TextureEntityMaterial m -> sampleDiffuse(m.texture(), u, v);
            case TextureEntityMaterialWithOpacity m -> sampleDiffuse(m.texture(), u, v);
            case ColorEntityMaterial m ->
                new float[] {m.color().getX(), m.color().getY(), m.color().getZ(), 1};
            case ColorModifierEntityMaterial m -> {
                var rgb = sample(m.base(), u, v);
                rgb[0] *= m.tint().getX();
                rgb[1] *= m.tint().getY();
                rgb[2] *= m.tint().getZ();
                yield rgb;
            }
            case ConstantOpacityEntityMaterial m -> sample(m.base(), u, v);
            case SpecularEntityMaterial m -> sample(m.base(), u, v);
            default -> new float[4];
        };
    }

    private float[] sampleEmission(EntityMaterial material, float u, float v) {
        return switch (material) {
            case ColorModifierEntityMaterial m -> {
                var e = sampleEmission(m.base(), u, v);
                e[0] *= srgbToLinear(m.tint().getX());
                e[1] *= srgbToLinear(m.tint().getY());
                e[2] *= srgbToLinear(m.tint().getZ());
                yield e;
            }
            case ConstantOpacityEntityMaterial m -> sampleEmission(m.base(), u, v);
            case SpecularEntityMaterial m -> {
                var e = sampleEmission(m.base(), u, v);
                var overlay = m.overlay();
                if ((overlay.emissive() || overlay.selfIllumination())
                        && !overlay.lit()
                        && overlay.texture() != null
                        && overlay.fade() == null
                        && overlay.uvAnimation().mode() == 0
                        && overlay.maskUvChannel() == 0) {
                    var sampled = sampleTexture(overlay.texture(), u, v);
                    float mask = overlay.mask() == null ? 1 : sampleTexture(overlay.mask(), u, v)[3];
                    float scale = mask * (overlay.modulate2x() ? 2 : 1);
                    for (int c = 0; c < 3; c++) {
                        e[c] += srgbToLinear(sampled[c]) * scale;
                    }
                }
                yield e;
            }
            default -> new float[3];
        };
    }

    private float[] sampleDiffuse(Texture source, float u, float v) {
        var value = sampleTexture(source, u, v);
        value[3] = 1;
        return value;
    }

    private float[] sampleTexture(Texture texture, float u, float v) {
        var rgba = pixels.computeIfAbsent(texture, TexturePixels::decodeBaseRgba);
        if (rgba.length != texture.width() * texture.height() * 4) {
            return new float[4];
        }
        int x = toTexel(u, texture.width(), texture.clampU());
        int y = toTexel(v, texture.height(), texture.clampV());
        int base = (y * texture.width() + x) * 4;
        return new float[] {
            (rgba[base] & 255) / 255f,
            (rgba[base + 1] & 255) / 255f,
            (rgba[base + 2] & 255) / 255f,
            (rgba[base + 3] & 255) / 255f
        };
    }

    private static float mean(ByteBuffer data, int a, int b, int c, int offset) {
        return (data.getFloat(a + offset) + data.getFloat(b + offset) + data.getFloat(c + offset)) / 3;
    }

    private static float mean(TerrainVertexBuffer mesh, int a, int b, int c, int coordinate) {
        if (coordinate >= mesh.getTextureCoordinateCount()) {
            return Float.NaN;
        }
        return (mesh.getTerrainTextureCoordinate(a, coordinate)
                        + mesh.getTerrainTextureCoordinate(b, coordinate)
                        + mesh.getTerrainTextureCoordinate(c, coordinate))
                / 3;
    }
}
