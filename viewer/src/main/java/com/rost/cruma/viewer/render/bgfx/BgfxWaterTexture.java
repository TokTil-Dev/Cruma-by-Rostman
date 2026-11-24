package com.rost.cruma.viewer.render.bgfx;

import com.rost.cruma.core.world.TextureFilters;
import com.rost.cruma.core.world.TextureSequence;
import com.rost.cruma.core.world.TextureSequence.Clock;
import com.rost.cruma.core.world.TextureSequence.Clocks;
import com.rost.cruma.viewer.render.bgfx.BgfxTextureCache.TextureRef;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class BgfxWaterTexture implements AutoCloseable {
    private final BgfxTextureCache cache;
    private final List<TextureRef> frames = new ArrayList<>();
    private final Clock clock;
    private final boolean linearFiltering;
    private final int oceanSamplerFlags;
    private TextureRef lightmap;
    private Source source;
    private boolean closed;

    BgfxWaterTexture(
            BgfxTextureCache cache, TextureSequence sequence, double loadedAtSeconds, boolean linearFiltering) {
        this(cache, sequence, loadedAtSeconds, linearFiltering, new Clocks());
    }

    BgfxWaterTexture(
            BgfxTextureCache cache,
            TextureSequence sequence,
            double loadedAtSeconds,
            boolean linearFiltering,
            Clocks clocks) {
        this.cache = cache;
        this.linearFiltering = linearFiltering;
        oceanSamplerFlags = sequence == null
                ? BgfxSceneRenderer.waterFilterFlags(linearFiltering)
                : BgfxBitmapSampler.water(sequence.frames().getFirst(), linearFiltering);
        clock = sequence == null ? null : clocks.getClockFor(sequence, loadedAtSeconds);
        try {
            if (sequence != null) {
                for (var index = 0; index < sequence.frames().size(); index++) {
                    frames.add(cache.loadPaged(sequence.frames().get(index), "water/bitmap/frame-" + index));
                }
            }
        } catch (RuntimeException | Error failure) {
            try {
                close();
            } catch (RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    Binding select(
            TextureRef currentLightmap, int variants, boolean frontZoneSunAffected, int shadowVariant, double seconds) {
        if (closed) {
            throw new IllegalStateException("water texture is closed");
        }
        var useLightmap = currentLightmap != null && !frontZoneSunAffected;
        if (source == null) {
            source = useLightmap ? Source.LIGHTMAP : Source.OCEAN;
        }
        if (useLightmap && source == Source.LIGHTMAP) {
            if (!currentLightmap.equals(lightmap)) {
                var retained = currentLightmap.retain();
                if (lightmap != null) {
                    lightmap.close();
                }
                lightmap = retained;
            }
        }
        if (source == Source.LIGHTMAP) {
            var flags = BgfxBitmapSampler.water(lightmap.getSamplerFlags(), TextureFilters.INHERITED, linearFiltering);
            return new Binding(lightmap, lightmap.getBaseLayer(), useLightmap, flags);
        }
        var texture = frames.isEmpty() ? cache.getPagedBlack() : frames.get(clock.frameAt(seconds));
        return new Binding(texture, texture.getBaseLayer(), useLightmap, oceanSamplerFlags);
    }

    void collectTextures(Set<TextureRef> textures) {
        textures.addAll(frames);
        if (lightmap != null) {
            textures.add(lightmap);
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        var failure = (RuntimeException) null;
        var owned = new ArrayList<>(frames);
        if (lightmap != null) {
            owned.add(lightmap);
            lightmap = null;
        }
        frames.clear();
        for (var texture : owned) {
            try {
                texture.close();
            } catch (RuntimeException cleanup) {
                if (failure == null) {
                    failure = cleanup;
                } else {
                    failure.addSuppressed(cleanup);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    record Binding(TextureRef texture, float layer, boolean useLightmap, int samplerFlags) {}

    private enum Source {
        OCEAN,
        LIGHTMAP
    }
}
