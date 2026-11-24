package com.rost.cruma.viewer.editing;

import com.rost.cruma.core.texture.TexturePixels;
import com.rost.cruma.core.unreal.UEmitter.UParticleEmitter;
import com.rost.cruma.core.world.EffectParts;
import com.rost.cruma.core.world.Entity.Texture;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import org.jetbrains.annotations.Nullable;

public final class EffectColors {
    private static final int PIXELS_READ = 4096;
    private static final float[] WHITE = {1f, 1f, 1f};

    private final Map<Texture, float[]> textures = new WeakHashMap<>();
    private @Nullable EffectParts countedFor;
    private List<Swatch> counted = List.of();

    public record Swatch(String name, float red, float green, float blue) {}

    private record Ordered(int order, Swatch swatch) {}

    static float[] overLife(UParticleEmitter emitter) {
        var keys = emitter.getColorScale();
        if (!emitter.isUseColorScale() || keys.isEmpty()) {
            return WHITE;
        }
        var sum = new float[3];
        var weight = 0f;
        for (var key : keys) {
            weight += key[4];
        }
        var plain = weight <= 0f;
        var total = plain ? keys.size() : weight;
        for (var key : keys) {
            var share = plain ? 1f : key[4];
            sum[0] += key[1] * share;
            sum[1] += key[2] * share;
            sum[2] += key[3] * share;
        }
        return new float[] {sum[0] / total, sum[1] / total, sum[2] / total};
    }

    static float[] mean(Texture texture) {
        byte[] pixels;
        try {
            pixels = TexturePixels.decodeMipRgba(texture, texture.data().size() - 1);
        } catch (RuntimeException unreadable) {
            return WHITE;
        }
        var count = pixels.length / 4;
        var step = Math.max(1, count / PIXELS_READ);
        var sum = new double[3];
        var weight = 0d;
        for (var pixel = 0; pixel < count; pixel += step) {
            var red = (pixels[pixel * 4] & 0xff) / 255d;
            var green = (pixels[pixel * 4 + 1] & 0xff) / 255d;
            var blue = (pixels[pixel * 4 + 2] & 0xff) / 255d;
            var share = (pixels[pixel * 4 + 3] & 0xff) / 255d * Math.max(red, Math.max(green, blue));
            sum[0] += red * share;
            sum[1] += green * share;
            sum[2] += blue * share;
            weight += share;
        }
        if (weight <= 0) {
            return WHITE;
        }
        return new float[] {(float) (sum[0] / weight), (float) (sum[1] / weight), (float) (sum[2] / weight)};
    }

    public List<Swatch> of(@Nullable EffectParts parts) {
        if (parts == countedFor) {
            return counted;
        }
        countedFor = parts;
        if (parts == null) {
            counted = List.of();
            return counted;
        }
        var ordered = new ArrayList<Ordered>();
        for (var sprite : parts.sprites()) {
            ordered.add(new Ordered(
                    sprite.childOrder(), swatch(sprite.name(), sprite.emitter(), texture(sprite.sprite()))));
        }
        for (var mesh : parts.meshes()) {
            ordered.add(new Ordered(mesh.childOrder(), swatch(mesh.name(), mesh.emitter(), WHITE)));
        }
        for (var beam : parts.beams()) {
            ordered.add(new Ordered(beam.childOrder(), swatch(beam.name(), beam.emitter(), texture(beam.texture()))));
        }
        ordered.sort(Comparator.comparingInt(Ordered::order));
        counted = ordered.stream().map(Ordered::swatch).toList();
        return counted;
    }

    private float[] texture(@Nullable Texture texture) {
        if (texture == null) {
            return WHITE;
        }
        return textures.computeIfAbsent(texture, EffectColors::mean);
    }

    private static Swatch swatch(String name, UParticleEmitter emitter, float[] texture) {
        var life = overLife(emitter);
        var low = emitter.getColorMultiplierMin();
        var high = emitter.getColorMultiplierMax();
        return new Swatch(
                name,
                part(texture[0] * life[0] * (low.getX() + high.getX()) / 2),
                part(texture[1] * life[1] * (low.getY() + high.getY()) / 2),
                part(texture[2] * life[2] * (low.getZ() + high.getZ()) / 2));
    }

    private static float part(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
