package com.rost.cruma.viewer.render.bgfx;

import com.rost.cruma.core.texture.TexturePixels;
import com.rost.cruma.core.world.Entity.AnimatedTextureEntityMaterial;
import com.rost.cruma.core.world.Entity.ColorEntityMaterial;
import com.rost.cruma.core.world.Entity.ColorModifierEntityMaterial;
import com.rost.cruma.core.world.Entity.CombinerEntityMaterial;
import com.rost.cruma.core.world.Entity.ConstantOpacityEntityMaterial;
import com.rost.cruma.core.world.Entity.EntityMaterial;
import com.rost.cruma.core.world.Entity.EnvMapEntityMaterial;
import com.rost.cruma.core.world.Entity.MultiTextureEntityMaterial;
import com.rost.cruma.core.world.Entity.ReflectiveEntityMaterial;
import com.rost.cruma.core.world.Entity.SpecularEntityMaterial;
import com.rost.cruma.core.world.Entity.Surface;
import com.rost.cruma.core.world.Entity.SurfaceType;
import com.rost.cruma.core.world.Entity.Texture;
import com.rost.cruma.core.world.Entity.TextureEntityMaterial;
import com.rost.cruma.core.world.Entity.TextureEntityMaterialWithOpacity;
import com.rost.cruma.core.world.Entity.TextureFormat;
import com.rost.cruma.core.world.Entity.WaterEntityMaterial;
import java.util.IdentityHashMap;
import java.util.List;
import org.jetbrains.annotations.Nullable;

final class BgfxRayOpacity {
    static final int FLAG_CLAMP_U = 1;
    static final int FLAG_CLAMP_V = 1 << 1;

    private BgfxRayOpacity() {}

    static final class AlphaCache {
        static final AlphaCache DISABLED = new AlphaCache();

        private final IdentityHashMap<Texture, byte[]> planes = new IdentityHashMap<>();

        private byte[] alphaOf(Texture texture) {
            var cached = planes.get(texture);
            if (cached == null) {
                cached = decodeAlpha(texture);
                planes.put(texture, cached);
            }
            return cached;
        }
    }

    static Coverage prepare(Surface surface) {
        return prepare(surface, null);
    }

    static Coverage prepare(Surface surface, @Nullable AlphaCache cache) {
        long nonPhysical = SurfaceType.BOUNDING_BOX.getId()
                | SurfaceType.IMPORTED_GEODATA.getId()
                | SurfaceType.GENERATED_GEODATA.getId()
                | SurfaceType.FAKE_BACKDROP.getId();
        if (surface.water() || (surface.type() & nonPhysical) != 0) {
            return Coverage.excluded();
        }
        return switch (surface.blendMode()) {
            case OPAQUE, UNLIT -> Coverage.opaque();
            case MASKED, MASKED_BLEND -> maskedCoverage(surface, cache);
            default -> Coverage.excluded();
        };
    }

    private static Coverage maskedCoverage(Surface surface, @Nullable AlphaCache cache) {
        if (cache == AlphaCache.DISABLED) {
            return Coverage.excluded();
        }
        if (surface.uvAnimation().mode() != 0) {
            return Coverage.excluded();
        }
        var layers = alphaLayers(surface.material(), 1f);
        if (layers == null || layers.primary() == null) {
            return Coverage.excluded();
        }
        var primary = layers.primary();
        var secondary = layers.secondary();
        if (secondary != null
                && (secondary.width() != primary.width()
                        || secondary.height() != primary.height()
                        || secondary.clampU() != primary.clampU()
                        || secondary.clampV() != primary.clampV())) {
            return Coverage.excluded();
        }
        var first = cache == null ? decodeAlpha(primary) : cache.alphaOf(primary);
        var second = secondary == null ? null : cache == null ? decodeAlpha(secondary) : cache.alphaOf(secondary);
        if (first.length != Math.multiplyExact(primary.width(), primary.height())
                || second != null && second.length != first.length) {
            return Coverage.excluded();
        }
        var multiplier = clamp01(layers.multiplier());
        var combined = new byte[first.length];
        for (var pixel = 0; pixel < combined.length; pixel++) {
            var value = (first[pixel] & 0xff) / 255f;
            if (second != null) {
                value *= (second[pixel] & 0xff) / 255f;
            }
            combined[pixel] = (byte) Math.round(255f * clamp01(value * multiplier));
        }
        var flags = (primary.clampU() ? FLAG_CLAMP_U : 0) | (primary.clampV() ? FLAG_CLAMP_V : 0);
        var cutoff = surface.alphaRef() >= 0 ? surface.alphaRef() / 255f : 0.25f;
        return Coverage.masked(primary.width(), primary.height(), combined, flags, cutoff);
    }

    private static AlphaLayers alphaLayers(EntityMaterial material, float multiplier) {
        return switch (material) {
            case TextureEntityMaterial value -> new AlphaLayers(value.texture(), null, multiplier);
            case TextureEntityMaterialWithOpacity value ->
                new AlphaLayers(value.texture(), value.opacityTexture(), multiplier);
            case ColorModifierEntityMaterial value ->
                alphaLayers(value.base(), multiplier * value.tint().getW());
            case SpecularEntityMaterial value -> alphaLayers(value.base(), multiplier);
            case ConstantOpacityEntityMaterial value -> new AlphaLayers(constantAlpha(value.alpha()), null, multiplier);
            case CombinerEntityMaterial value -> {
                if (value.alphaOp() == 4) {
                    yield value.material2() == null || value.material2Fade() != null
                            ? null
                            : new AlphaLayers(value.material2(), null, multiplier);
                }
                yield new AlphaLayers(value.material1(), null, multiplier);
            }
            case ColorEntityMaterial value ->
                new AlphaLayers(constantAlpha(value.color().getW()), null, multiplier);
            case AnimatedTextureEntityMaterial _,
                    EnvMapEntityMaterial _,
                    ReflectiveEntityMaterial _,
                    MultiTextureEntityMaterial _,
                    WaterEntityMaterial _ -> null;
        };
    }

    private static Texture constantAlpha(float alpha) {
        var value = (byte) Math.round(255f * clamp01(alpha));
        return new Texture(
                TextureFormat.TEXTURE_P8,
                1,
                1,
                List.of(new byte[] {(byte) 255, (byte) 255, (byte) 255, value}),
                true,
                true,
                true);
    }

    private static byte[] decodeAlpha(Texture texture) {
        var rgba = TexturePixels.decodeBaseRgba(texture);
        var pixels = Math.multiplyExact(texture.width(), texture.height());
        if (rgba.length != Math.multiplyExact(pixels, 4)) {
            return new byte[0];
        }
        var alpha = new byte[pixels];
        for (var pixel = 0; pixel < pixels; pixel++) {
            alpha[pixel] = rgba[pixel * 4 + 3];
        }
        return alpha;
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private record AlphaLayers(Texture primary, Texture secondary, float multiplier) {}

    record Coverage(boolean eligible, byte[] alpha, int width, int height, int flags, float cutoff) {
        Coverage {
            alpha = alpha == null ? new byte[0] : alpha;
            if (!eligible && alpha.length != 0
                    || eligible
                            && alpha.length != 0
                            && (width < 1 || height < 1 || alpha.length != Math.multiplyExact(width, height))
                    || !Float.isFinite(cutoff)
                    || cutoff < 0f
                    || cutoff > 1f) {
                throw new IllegalArgumentException("invalid ray opacity coverage");
            }
        }

        static Coverage excluded() {
            return new Coverage(false, new byte[0], 0, 0, 0, 0f);
        }

        static Coverage opaque() {
            return new Coverage(true, new byte[0], 0, 0, 0, 0f);
        }

        static Coverage masked(int width, int height, byte[] alpha, int flags, float cutoff) {
            return new Coverage(true, alpha, width, height, flags, cutoff);
        }

        boolean masked() {
            return alpha.length != 0;
        }

        long retainedBytes() {
            return alpha.length;
        }
    }
}
