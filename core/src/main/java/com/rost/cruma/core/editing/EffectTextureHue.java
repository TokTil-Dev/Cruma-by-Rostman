package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.TexturePackageWriter.PackagedTexture;
import com.rost.cruma.core.texture.TextureHue;
import com.rost.cruma.core.texture.TexturePixels;
import com.rost.cruma.core.unreal.EffectClasses;
import com.rost.cruma.core.unreal.UArchive;
import com.rost.cruma.core.unreal.UArchiveLoader;
import com.rost.cruma.core.unreal.UArchiveLoader.USearchConfig;
import com.rost.cruma.core.unreal.UMaterial.TextureClampMode;
import com.rost.cruma.core.unreal.UMaterial.UTexture;
import com.rost.cruma.core.unreal.UMaterial.UTextureFormat;
import com.rost.cruma.core.world.Entity.Texture;
import com.rost.cruma.core.world.Entity.TextureFormat;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

final class EffectTextureHue implements AutoCloseable {
    static final float COLOR_THRESHOLD = 0.08f;

    static final List<USearchConfig> SEARCH = List.of(
            new USearchConfig("Textures", "utx"),
            new USearchConfig("SysTextures", "utx"),
            new USearchConfig("StaticMeshes", "usx"),
            new USearchConfig("Animations", "ukx"),
            new USearchConfig("Sounds", "uax"),
            new USearchConfig("System", "u"));
    private static final long CACHE_BYTES = 64L << 20;
    private static final int MEASURED_SIZE = 128;

    private final UArchiveLoader archives;
    private final Map<String, Source> sources = new HashMap<>();
    private final Map<String, Float> imageSaturation = new HashMap<>();
    private final LinkedHashMap<String, List<byte[]>> shifted = new LinkedHashMap<>(16, .75f, true);
    private long shiftedBytes;

    EffectTextureHue(Path clientRoot) {
        archives = new UArchiveLoader(clientRoot, SEARCH);
    }

    static final class Plan {
        private final Map<String, String> paths = new HashMap<>();
        private final Map<String, PackagedTexture> copies = new LinkedHashMap<>();
        private final Set<String> names = new HashSet<>();
        private final Set<String> held = new HashSet<>();

        Plan(Collection<String> imported) {
            for (var name : imported) {
                names.add(name.toLowerCase(Locale.ROOT));
            }
        }

        boolean isEmpty() {
            return copies.isEmpty();
        }

        List<PackagedTexture> getTextures() {
            return List.copyOf(copies.values());
        }

        void add(PackagedTexture texture) {
            copies.putIfAbsent(texture.name(), texture);
            held.add(EffectEdits.texturePath(texture.name()).toLowerCase(Locale.ROOT));
        }

        boolean holds(String path) {
            return held.contains(path.toLowerCase(Locale.ROOT));
        }

        private String newName(String wanted) {
            var name = wanted;
            for (var suffix = 'b'; !names.add(name.toLowerCase(Locale.ROOT)); suffix++) {
                name = wanted + "_" + suffix;
            }
            return name;
        }

        private boolean isFree(List<PackagedTexture> chain) {
            return chain.stream()
                    .noneMatch(texture -> names.contains(texture.name().toLowerCase(Locale.ROOT)));
        }

        private void hold(List<PackagedTexture> chain) {
            for (var texture : chain) {
                names.add(texture.name().toLowerCase(Locale.ROOT));
                copies.put(texture.name(), texture);
            }
        }
    }

    private record Source(UTexture texture, float saturation) {}

    @Nullable
    UArchive getArchive(String packageName) {
        return archives.loadArchiveIfPresent(packageName);
    }

    float getSaturation(String texturePath) throws IOException {
        var source = source(texturePath);
        return source == null ? -1f : source.saturation();
    }

    @Nullable
    String createShifted(Plan plan, String texturePath, int degrees) throws IOException {
        if (plan.holds(texturePath)) {
            return null;
        }
        var key = key(texturePath, degrees);
        var known = plan.paths.get(key);
        if (known != null) {
            return known;
        }
        var source = source(texturePath);
        if (source == null || source.saturation() < COLOR_THRESHOLD) {
            return null;
        }
        var frames = new ArrayList<UTexture>();
        for (var frame = source.texture();
                frame != null && !frames.contains(frame);
                frame = frame.getAnimationNextFrame()) {
            frames.add(frame);
        }
        var levels = new ArrayList<List<byte[]>>(frames.size());
        for (var i = 0; i < frames.size(); i++) {
            levels.add(levels(i == 0 ? texturePath : frames.get(i).getFullName(), frames.get(i), degrees));
        }
        var chain = chain(frames, levels, degrees, 0);
        for (var attempt = 1; !plan.isFree(chain) || !fitsClient(chain); attempt++) {
            chain = chain(frames, levels, degrees, attempt);
        }
        plan.hold(chain);
        var result = EffectClasses.CUSTOM_TEXTURES + "." + chain.getFirst().name();
        plan.paths.put(key, result);
        plan.held.add(result.toLowerCase(Locale.ROOT));
        return result;
    }

    @Nullable
    String createShifted(Plan plan, PackagedTexture image, String file, int degrees, Set<String> taken) {
        var key = key(EffectClasses.CUSTOM_TEXTURES + "." + image.name(), degrees);
        var known = plan.paths.get(key);
        if (known != null) {
            return known;
        }
        var saturation =
                imageSaturation.computeIfAbsent(file, _ -> TextureHue.saturation(measuredPixels(image.entity())));
        if (saturation < COLOR_THRESHOLD) {
            return null;
        }
        var name = plan.newName(image.name() + "_h" + degrees);
        while (taken.contains(name.toLowerCase(Locale.ROOT))) {
            name = plan.newName(image.name() + "_h" + degrees);
        }
        plan.copies.put(name, image.renamed(name, imageLevels(file, image, degrees)));
        var result = EffectClasses.CUSTOM_TEXTURES + "." + name;
        plan.paths.put(key, result);
        plan.held.add(result.toLowerCase(Locale.ROOT));
        return result;
    }

    @Override
    public void close() {
        sources.clear();
        imageSaturation.clear();
        shifted.clear();
        archives.clearArchives();
    }

    private boolean fitsClient(List<PackagedTexture> chain) {
        var archive = archives.loadArchiveIfPresent(EffectClasses.CUSTOM_TEXTURES);
        if (archive == null) {
            return true;
        }
        var exports = archive.getExportMap();
        for (var copy : chain) {
            for (var index = 1; index <= exports.size(); index++) {
                if (exports.get(index - 1).getObjectName().equalsIgnoreCase(copy.name())
                        && !(archive.getObjectLoader().loadObject(index) instanceof UTexture held
                                && isSameTexture(held, copy))) {
                    return false;
                }
            }
        }
        return true;
    }

    private @Nullable Source source(String path) throws IOException {
        var key = path.toLowerCase(Locale.ROOT);
        var known = sources.get(key);
        if (known != null || sources.containsKey(key)) {
            return known;
        }
        var source = read(path);
        sources.put(key, source);
        return source;
    }

    private @Nullable Source read(String path) {
        var parts = path.split("\\.");
        var archive = archives.loadArchiveIfPresent(parts[0]);
        if (archive == null) {
            return null;
        }
        var name = parts[parts.length - 1];
        var exports = archive.getExportMap();
        for (var index = 1; index <= exports.size(); index++) {
            var export = exports.get(index - 1);
            if (!export.getObjectName().equalsIgnoreCase(name) || !"Texture".equals(export.getClassName())) {
                continue;
            }
            if (parts.length > 2
                    && export.getPackageIndex() > 0
                    && !exports.get(export.getPackageIndex() - 1)
                            .getObjectName()
                            .equalsIgnoreCase(parts[parts.length - 2])) {
                continue;
            }
            if (archive.getObjectLoader().loadObject(index) instanceof UTexture texture
                    && texture.getMips() != null
                    && texture.getMips().length > 0
                    && isDecodable(texture.getFormat())) {
                return new Source(texture, measure(texture));
            }
        }
        return null;
    }

    private List<byte[]> levels(String path, UTexture texture, int degrees) {
        var key = key(path, degrees);
        var cached = shifted.get(key);
        if (cached != null) {
            return cached;
        }
        var mips = texture.getMips();
        var result = new ArrayList<byte[]>(mips.length);
        for (var level = 0; level < mips.length; level++) {
            var width = mips[level].getUSize();
            var height = mips[level].getVSize();
            var data = mips[level].getData();
            var turned = switch (texture.getFormat()) {
                case TEXF_DXT1 -> TextureHue.rotateDxt(TextureFormat.TEXTURE_DXT1, width, height, data, degrees);
                case TEXF_DXT3 -> TextureHue.rotateDxt(TextureFormat.TEXTURE_DXT3, width, height, data, degrees);
                case TEXF_DXT5 -> TextureHue.rotateDxt(TextureFormat.TEXTURE_DXT5, width, height, data, degrees);
                case TEXF_RGBA8 -> TextureHue.rotateBgra(data, degrees);
                default -> TextureHue.rotateBgra(palettedBgra(texture, level), degrees);
            };
            result.add(turned);
        }
        keep(key, result);
        return result;
    }

    private List<byte[]> imageLevels(String file, PackagedTexture image, int degrees) {
        var key = key("image:" + file, degrees);
        var cached = shifted.get(key);
        if (cached != null) {
            return cached;
        }
        var texture = image.entity();
        var result = new ArrayList<byte[]>(image.levels().size());
        for (var level = 0; level < image.levels().size(); level++) {
            var data = image.levels().get(level);
            result.add(
                    texture.format() == TextureFormat.TEXTURE_RGBA
                            ? TextureHue.rotateBgra(data, degrees)
                            : TextureHue.rotateDxt(
                                    texture.format(),
                                    Math.max(1, image.width() >> level),
                                    Math.max(1, image.height() >> level),
                                    data,
                                    degrees));
        }
        keep(key, result);
        return result;
    }

    private void keep(String key, List<byte[]> levels) {
        shifted.put(key, levels);
        for (var level : levels) {
            shiftedBytes += level.length;
        }
        while (shiftedBytes > CACHE_BYTES && shifted.size() > 1) {
            var eldest = shifted.entrySet().iterator();
            var dropped = eldest.next().getValue();
            eldest.remove();
            for (var level : dropped) {
                shiftedBytes -= level.length;
            }
        }
    }

    private static List<PackagedTexture> chain(
            List<UTexture> frames, List<List<byte[]>> levels, int degrees, int attempt) {
        var mark = attempt == 0
                ? ""
                : "_" + (attempt < 26 ? String.valueOf((char) ('a' + attempt)) : Integer.toString(attempt));
        var names = new ArrayList<String>(frames.size());
        var seen = new HashSet<String>();
        for (var i = 0; i < frames.size(); i++) {
            var name = frames.get(i).getName() + "_h" + degrees + mark;
            names.add(seen.add(name.toLowerCase(Locale.ROOT)) ? name : name + "_" + i);
        }
        var last = frames.getLast().getAnimationNextFrame();
        var chain = new ArrayList<PackagedTexture>(frames.size());
        for (var i = 0; i < frames.size(); i++) {
            var next = i + 1 < frames.size() ? names.get(i + 1) : last == null ? null : names.get(frames.indexOf(last));
            chain.add(packaged(frames.get(i), names.get(i), next, levels.get(i)));
        }
        return chain;
    }

    private static boolean isSameTexture(UTexture held, PackagedTexture copy) {
        var mips = held.getMips();
        if (mips == null
                || held.getFormat().getId() != copy.format()
                || held.getUSize() != copy.width()
                || held.getVSize() != copy.height()
                || mips.length != copy.levels().size()) {
            return false;
        }
        for (var level = 0; level < mips.length; level++) {
            if (!Arrays.equals(mips[level].getData(), copy.levels().get(level))) {
                return false;
            }
        }
        var next = held.getAnimationNextFrame();
        return next == null ? copy.animNext() == null : next.getName().equalsIgnoreCase(copy.animNext());
    }

    private static boolean isDecodable(UTextureFormat format) {
        return switch (format) {
            case TEXF_DXT1, TEXF_DXT3, TEXF_DXT5, TEXF_RGBA8, TEXF_P8 -> true;
            default -> false;
        };
    }

    private static float measure(UTexture texture) {
        var mips = texture.getMips();
        var format = switch (texture.getFormat()) {
            case TEXF_DXT1 -> TextureFormat.TEXTURE_DXT1;
            case TEXF_DXT3 -> TextureFormat.TEXTURE_DXT3;
            case TEXF_DXT5 -> TextureFormat.TEXTURE_DXT5;
            case TEXF_RGBA8 -> TextureFormat.TEXTURE_RGBA;
            default -> TextureFormat.TEXTURE_P8;
        };
        var levels = new ArrayList<byte[]>(mips.length);
        for (var i = 0; i < mips.length; i++) {
            levels.add(format == TextureFormat.TEXTURE_P8 ? palettedRgba(texture, i) : mips[i].getData());
        }
        var pixels = measuredPixels(
                new Texture(format, texture.getUSize(), texture.getVSize(), levels, texture.isAlphaTexture()));
        if (!texture.isAlphaTexture() && format == TextureFormat.TEXTURE_P8) {
            for (var alpha = 3; alpha < pixels.length; alpha += 4) {
                pixels[alpha] = (byte) 255;
            }
        }
        return TextureHue.saturation(pixels);
    }

    private static byte[] measuredPixels(Texture texture) {
        var level = 0;
        while (level + 1 < texture.data().size()
                && Math.max(texture.width() >> level, texture.height() >> level) > MEASURED_SIZE) {
            level++;
        }
        return TexturePixels.decodeMipRgba(texture, level);
    }

    private static PackagedTexture packaged(UTexture texture, String name, @Nullable String next, List<byte[]> levels) {
        var format = switch (texture.getFormat()) {
            case TEXF_DXT1, TEXF_DXT3, TEXF_DXT5, TEXF_RGBA8 ->
                texture.getFormat().getId();
            default -> UTextureFormat.TEXF_RGBA8.getId();
        };
        return new PackagedTexture(
                name,
                format,
                texture.getUSize(),
                texture.getVSize(),
                texture.getUClamp(),
                texture.getVClamp(),
                levels,
                texture.isAlphaTexture(),
                texture.isMasked(),
                texture.isTwoSided(),
                clampMode(texture.getUClampMode()),
                clampMode(texture.getVClampMode()),
                next,
                texture.getMinFrameRate(),
                texture.getMaxFrameRate(),
                texture.getTotalFrameNum(),
                texture.getPrimeCount(),
                texture.isOneTimeAnimLoop());
    }

    private static int clampMode(@Nullable TextureClampMode mode) {
        return mode == null ? 0 : mode.getId();
    }

    private static byte[] palettedRgba(UTexture texture, int level) {
        var bgra = palettedBgra(texture, level);
        for (var pixel = 0; pixel + 3 < bgra.length; pixel += 4) {
            var blue = bgra[pixel];
            bgra[pixel] = bgra[pixel + 2];
            bgra[pixel + 2] = blue;
        }
        return bgra;
    }

    private static byte[] palettedBgra(UTexture texture, int level) {
        var palette = texture.getPalette().get();
        var colors = palette == null ? null : palette.getColors();
        var data = texture.getMips()[level].getData();
        var result = new byte[data.length * 4];
        var usesAlpha = texture.isAlphaTexture() || texture.isMasked();
        for (var pixel = 0; pixel < data.length; pixel++) {
            var entry = data[pixel] & 255;
            if (texture.isMasked() && entry == 0) {
                continue;
            }
            var color = colors == null ? null : colors[entry];
            result[pixel * 4] = color == null ? (byte) entry : color.b();
            result[pixel * 4 + 1] = color == null ? (byte) entry : color.g();
            result[pixel * 4 + 2] = color == null ? (byte) entry : color.r();
            result[pixel * 4 + 3] = usesAlpha && color != null ? color.a() : (byte) 255;
        }
        return result;
    }

    private static String key(String path, int degrees) {
        return path.toLowerCase(Locale.ROOT) + "@" + degrees;
    }
}
