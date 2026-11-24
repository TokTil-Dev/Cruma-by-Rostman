package com.rost.cruma.core.export;

import com.rost.cruma.core.export.LocationModel.AlphaMode;
import com.rost.cruma.core.export.LocationModel.Image;
import com.rost.cruma.core.export.LocationModel.LayerFile;
import com.rost.cruma.core.export.LocationModel.Material;
import com.rost.cruma.core.export.LocationModel.TerrainLayer;
import com.rost.cruma.core.export.LocationModel.Wrap;
import com.rost.cruma.core.export.WaterDepth.Result;
import com.rost.cruma.core.texture.TexturePixels;
import com.rost.cruma.core.world.Entity.AnimatedTextureEntityMaterial;
import com.rost.cruma.core.world.Entity.BlendMode;
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
import com.rost.cruma.core.world.Entity.Texture;
import com.rost.cruma.core.world.Entity.TextureEntityMaterial;
import com.rost.cruma.core.world.Entity.TextureEntityMaterialWithOpacity;
import com.rost.cruma.core.world.Entity.UvAnimation;
import com.rost.cruma.core.world.Entity.WaterEntityMaterial;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import org.jetbrains.annotations.Nullable;

final class LocationMaterials {
    static final String FRAMES = "animated-textures";

    static final String EFFECTS = "effects";

    private static final float DEFAULT_CUTOFF = .25f;

    private static final float MATTE = 1f;

    private static final float GLOSSY = .45f;

    private static final float MIRROR = .2f;

    private static final float WATER = .05f;

    private static final float WATER_IOR = 1.33f;

    private static final float[] WATER_REFERENCE = {38 / 255f, 56 / 255f, 64 / 255f};

    private final List<Image> images = new ArrayList<>();
    private final Map<Object, Integer> imageIndices = new HashMap<>();
    private final List<Material> materials = new ArrayList<>();
    private final Map<Key, Integer> materialIndices = new HashMap<>();
    private final Map<String, Integer> materialContents = new HashMap<>();
    private final Set<String> materialNames = new HashSet<>();
    private final Set<String> imageNames = new HashSet<>();
    private final List<LayerFile> frameFiles = new ArrayList<>();
    private final Map<Object, Integer> effectMaterials = new HashMap<>();
    private final Map<Object, String> framePaths = new HashMap<>();
    private final @Nullable Texture waterNormal;

    LocationMaterials(@Nullable Texture waterNormal) {
        this.waterNormal = waterNormal;
    }

    private record Key(
            Identity material,
            BlendMode blendMode,
            int alphaRef,
            boolean water,
            boolean doubleSided,
            @Nullable String name,
            @Nullable Identity depth) {}

    private record Identity(Object value) {
        @Override
        public boolean equals(Object other) {
            return other instanceof Identity identity && identity.value == value;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(value);
        }
    }

    private record Source(Object key, String kind, int width, int height, Supplier<RgbaImage> pixels, boolean repeat) {}

    private static final class Layers {
        @Nullable
        Source base;

        @Nullable
        Source emissive;

        final float[] color = {1, 1, 1, 1};
        final float[] glow = {0, 0, 0};
        float roughness = MATTE;

        @Nullable
        Source normal;

        float ior;
        Wrap wrap = Wrap.REPEAT;
        final Map<String, String> extras = new LinkedHashMap<>();

        @Nullable
        List<Texture> frames;

        @Nullable
        Source firstFrame;

        float framesPerSecond;
    }

    static boolean isWater(Surface surface) {
        return surface.material() instanceof WaterEntityMaterial water && water.alpha() < 1f;
    }

    static Surface drawnAsItIs(Surface surface) {
        var masked = surface.blendMode() == BlendMode.MASKED || surface.blendMode() == BlendMode.MASKED_BLEND;
        if (surface.blendMode() != BlendMode.OPAQUE && !masked) {
            return surface;
        }
        var alphaRef = masked && surface.alphaRef() < 0 ? Math.round(DEFAULT_CUTOFF * 255f) : surface.alphaRef();
        return new Surface(
                surface.type(),
                surface.indexOffset(),
                surface.indexCount(),
                surface.material(),
                BlendMode.UNLIT,
                surface.uvAnimation(),
                surface.water(),
                surface.materialName(),
                alphaRef,
                surface.usesVertexColor(),
                surface.depthWrite(),
                surface.modulateStaticLighting2x(),
                surface.reflectionPlane(),
                surface.distanceFog(),
                surface.waterSheetSource(),
                surface.bspDrawSource());
    }

    static float bakedLight(BlendMode blend, float light) {
        return switch (blend) {
            case TRANSLUCENT, ADDITIVE, MODULATE, BRIGHTEN, SCREEN, DARKEN -> light;
            case EMISSIVE, UNLIT -> LocationEnvironment.toLinear(light);
            default -> 1f;
        };
    }

    static RgbaImage decode(Texture texture, int width, int height) {
        var expected = Math.multiplyExact(Math.multiplyExact(width, height), 4);
        byte[] rgba;
        try {
            rgba = TexturePixels.decodeBaseRgba(texture);
        } catch (RuntimeException unreadable) {
            rgba = new byte[0];
        }
        if (rgba.length != expected) {
            rgba = new byte[expected];
            Arrays.fill(rgba, (byte) 0x80);
            for (var i = 3; i < rgba.length; i += 4) {
                rgba[i] = (byte) 0xFF;
            }
        }
        return new RgbaImage(width, height, rgba);
    }

    List<LayerFile> getFiles() {
        return frameFiles;
    }

    int getEffect(Texture texture, String textureName, int blend) {
        var key = List.of(
                "effect",
                new Identity(texture.data().isEmpty() ? texture : texture.data().getFirst()),
                blend);
        var known = effectMaterials.get(key);
        if (known != null) {
            return known;
        }
        var mode = switch (blend) {
            case 1 -> BlendMode.ADDITIVE;
            case 2 -> BlendMode.MODULATE;
            case 3 -> BlendMode.BRIGHTEN;
            case 4 -> BlendMode.DARKEN;
            default -> BlendMode.TRANSLUCENT;
        };
        var raw = sourceOf(texture);
        var base = switch (mode) {
            case ADDITIVE, BRIGHTEN -> deriveGlow(raw, mode == BlendMode.BRIGHTEN);
            case MODULATE, DARKEN -> darken(raw, mode == BlendMode.MODULATE);
            default -> raw;
        };
        var name = textureName.isBlank() ? "effect" : textureName;
        var file = effectFile(texture, name);
        var extras = new LinkedHashMap<String, String>();
        extras.put("client_material", name);
        extras.put("client_blend", mode.name());
        extras.put("cruma_fx_blend", Integer.toString(blend));
        extras.put("cruma_fx_texture", file);
        materials.add(new Material(
                uniqueMaterialName("FX " + name),
                addImage(base, "FX " + name),
                new Wrap(texture.clampU(), texture.clampV()),
                new float[] {1, 1, 1, 1},
                AlphaMode.BLEND,
                .5f,
                true,
                true,
                -1,
                new float[] {0, 0, 0},
                MATTE,
                -1,
                0,
                0,
                Map.copyOf(extras)));
        effectMaterials.put(key, materials.size() - 1);
        return materials.size() - 1;
    }

    List<Image> getImages() {
        return images;
    }

    List<Material> getMaterials() {
        return materials;
    }

    int getMaterial(Surface surface, boolean doubleSided) {
        return getMaterial(surface, doubleSided, null);
    }

    int getMaterial(Surface surface, boolean doubleSided, Result depth) {
        var waterDepth = isWater(surface) ? depth : null;
        var key = new Key(
                new Identity(surface.material()),
                surface.blendMode(),
                surface.alphaRef(),
                surface.water(),
                doubleSided,
                surface.materialName(),
                waterDepth == null ? null : new Identity(waterDepth));
        var known = materialIndices.get(key);
        if (known != null) {
            return known;
        }
        var layers = new Layers();
        resolve(surface.material(), layers, 0);
        var index = add(surface, layers, doubleSided, waterDepth);
        materialIndices.put(key, index);
        return index;
    }

    int getTerrain(String name, int size, Supplier<RgbaImage> baked) {
        return getTerrain(name, size, baked, List.of());
    }

    int getTerrain(String name, int size, Supplier<RgbaImage> baked, List<TerrainLayer> layers) {
        var source = new Source(new Identity(baked), "", size, size, baked, false);
        var extras = new LinkedHashMap<String, String>();
        extras.put("cruma_kind", "terrain");
        materials.add(new Material(
                uniqueMaterialName(name),
                addImage(source, name),
                new Wrap(true, true),
                new float[] {1, 1, 1, 1},
                AlphaMode.OPAQUE,
                .5f,
                false,
                false,
                -1,
                new float[] {0, 0, 0},
                MATTE,
                -1,
                0,
                0,
                Map.copyOf(extras),
                layers));
        return materials.size() - 1;
    }

    private String effectFile(Texture texture, String name) {
        var key =
                new Identity(texture.data().isEmpty() ? texture : texture.data().getFirst());
        var known = framePaths.get(key);
        if (known != null) {
            return known;
        }
        var width = Math.max(1, texture.width());
        var height = Math.max(1, texture.height());
        var base = EFFECTS + "/" + LocationExport.toSafeName(name);
        var path = base + ".png";
        for (var number = 2; framePaths.containsValue(path); number++) {
            path = base + "_" + number + ".png";
        }
        frameFiles.add(new LayerFile(
                path, width, height, false, () -> decode(texture, width, height).rgba()));
        framePaths.put(key, path);
        return path;
    }

    private int add(Surface surface, Layers layers, boolean doubleSided, Result depth) {
        var blend = surface.blendMode();
        var extras = new LinkedHashMap<>(layers.extras);
        if (surface.materialName() != null) {
            extras.put("client_material", surface.materialName());
        }
        extras.put("client_blend", blend.name());
        var uv = surface.uvAnimation();
        if (uv.mode() != 0 && uv.mode() != 4) {
            extras.put("uv_animation", describeAnimation(uv));
            extras.put(
                    "cruma_uv_animation",
                    String.format(
                            Locale.ROOT,
                            "%d %s %s %s %s",
                            uv.mode(),
                            format(uv.paramX()),
                            format(uv.paramY()),
                            format(uv.paramZ()),
                            format(uv.paramW())));
        }
        var base = layers.base;
        UnaryOperator<Source> look = UnaryOperator.identity();
        var alphaMode = AlphaMode.OPAQUE;
        var cutoff = .5f;
        var unlit = false;
        switch (blend) {
            case MASKED, MASKED_BLEND -> {
                alphaMode = AlphaMode.MASK;
                cutoff = alphaCutoff(surface);
            }
            case TRANSLUCENT -> alphaMode = AlphaMode.BLEND;
            case EMISSIVE -> {
                alphaMode = AlphaMode.BLEND;
                unlit = true;
            }
            case UNLIT -> {
                unlit = true;
                if (surface.alphaRef() >= 0) {
                    alphaMode = AlphaMode.MASK;
                    cutoff = alphaCutoff(surface);
                }
            }
            case ADDITIVE, BRIGHTEN, SCREEN -> {
                alphaMode = AlphaMode.BLEND;
                unlit = true;
                look = source -> deriveGlow(source, blend == BlendMode.BRIGHTEN);
                layers.color[3] = 1;
            }
            case MODULATE, DARKEN -> {
                alphaMode = AlphaMode.BLEND;
                unlit = true;
                look = source -> darken(source, blend == BlendMode.MODULATE);
                layers.color[0] = 1;
                layers.color[1] = 1;
                layers.color[2] = 1;
                layers.color[3] = 1;
            }
            case OPAQUE, INVISIBLE -> {}
        }
        base = base == null ? null : look.apply(base);
        if (surface.water()) {
            alphaMode = AlphaMode.BLEND;
            extras.put("cruma_kind", "water");
        }
        var name = surface.materialName() != null ? surface.materialName() : "material";
        var wrap = layers.wrap;
        var baseTexCoord = 0;
        if (depth != null) {
            name = name + " " + depth.tile();
            var image = depth.image();
            base = new Source(new Identity(image), "depth", image.width(), image.height(), depth::image, false);
            baseTexCoord = 1;
            wrap = new Wrap(true, true);
            layers.color[0] = 1;
            layers.color[1] = 1;
            layers.color[2] = 1;
            layers.color[3] = 1;
        }
        var baseImage = base == null ? -1 : addImage(base, name);
        if (layers.frames != null && layers.frames.size() > 1 && layers.base == layers.firstFrame && depth == null) {
            var file = frameAtlas(layers.frames, look, name);
            if (file != null) {
                extras.put("cruma_frames", file + " " + layers.frames.size() + " " + format(layers.framesPerSecond));
            }
        }
        var emissiveImage = layers.emissive == null ? -1 : addImage(layers.emissive, name + " glow");
        var emissive = layers.emissive != null ? new float[] {1, 1, 1} : layers.glow.clone();
        var normalImage = layers.normal == null ? -1 : addImage(layers.normal, name + " normal");
        var content = name
                + '|'
                + baseImage
                + '|'
                + layers.wrap
                + '|'
                + Arrays.toString(layers.color)
                + '|'
                + alphaMode
                + '|'
                + cutoff
                + '|'
                + doubleSided
                + '|'
                + unlit
                + '|'
                + emissiveImage
                + '|'
                + Arrays.toString(emissive)
                + '|'
                + layers.roughness
                + '|'
                + normalImage
                + '|'
                + layers.ior
                + '|'
                + baseTexCoord
                + '|'
                + extras;
        var same = materialContents.get(content);
        if (same != null) {
            return same;
        }
        materials.add(new Material(
                uniqueMaterialName(doubleSided ? name + " two-sided" : name),
                baseImage,
                wrap,
                layers.color.clone(),
                alphaMode,
                cutoff,
                doubleSided,
                unlit,
                emissiveImage,
                emissive,
                layers.roughness,
                normalImage,
                layers.ior,
                baseTexCoord,
                Map.copyOf(extras)));
        materialContents.put(content, materials.size() - 1);
        return materials.size() - 1;
    }

    private void resolve(EntityMaterial material, Layers layers, int depth) {
        if (depth > 16) {
            return;
        }
        switch (material) {
            case TextureEntityMaterial plain -> setBaseTexture(layers, plain.texture());
            case TextureEntityMaterialWithOpacity withOpacity -> {
                setBaseTexture(layers, withOpacity.texture());
                if (withOpacity.opacityTexture() != null && layers.base != null) {
                    layers.base = withOpacity(layers.base, sourceOf(withOpacity.opacityTexture()));
                }
            }
            case ColorEntityMaterial color -> {
                layers.color[0] *= color.color().getX();
                layers.color[1] *= color.color().getY();
                layers.color[2] *= color.color().getZ();
                layers.color[3] *= color.color().getW();
            }
            case ColorModifierEntityMaterial modifier -> {
                resolve(modifier.base(), layers, depth + 1);
                layers.color[0] *= modifier.tint().getX();
                layers.color[1] *= modifier.tint().getY();
                layers.color[2] *= modifier.tint().getZ();
                layers.color[3] *= modifier.tint().getW();
            }
            case ConstantOpacityEntityMaterial constant -> {
                resolve(constant.base(), layers, depth + 1);
                if (layers.base != null) {
                    layers.base = toOpaque(layers.base);
                }
                layers.color[3] = constant.alpha();
            }
            case SpecularEntityMaterial specular -> {
                resolve(specular.base(), layers, depth + 1);
                var overlay = specular.overlay();
                if (overlay.selfIllumination() || overlay.emissive()) {
                    if (overlay.texture() != null) {
                        var glow = sourceOf(overlay.texture());
                        layers.emissive = overlay.mask() == null ? glow : maskGlow(glow, sourceOf(overlay.mask()));
                    } else if (overlay.fade() != null) {
                        var color = overlay.fade().color1();
                        layers.glow[0] = color.getX();
                        layers.glow[1] = color.getY();
                        layers.glow[2] = color.getZ();
                    }
                    layers.extras.put("self_illumination", "true");
                } else {
                    layers.extras.put(
                            "specular",
                            overlay.cube() != null ? "cube" : overlay.texture() == null ? "colour" : "texture");
                }
                if (overlay.uvAnimation().mode() != 0) {
                    layers.extras.put("specular_uv_animation", describeAnimation(overlay.uvAnimation()));
                }
            }
            case AnimatedTextureEntityMaterial animated -> {
                setBaseTexture(layers, animated.frames().getFirst());
                layers.extras.put(
                        "animation_frames", Integer.toString(animated.frames().size()));
                layers.extras.put("animation_fps", format(animated.framesPerSecond()));
                layers.frames = animated.frames();
                layers.firstFrame = layers.base;
                layers.framesPerSecond = animated.framesPerSecond();
            }
            case CombinerEntityMaterial combiner -> {
                setBaseTexture(layers, combiner.material1());
                if (combiner.material2Cube() == null && !combiner.vertexColorMask()) {
                    layers.base = bakeCombiner(combiner);
                } else {
                    layers.extras.put(
                            "combiner",
                            combiner.material2Cube() != null
                                    ? "reflection layer left out"
                                    : "vertex colour mask left out");
                }
                if (combiner.material2Fade() != null) {
                    layers.extras.put("combiner_fade", "first colour kept");
                }
            }
            case ReflectiveEntityMaterial reflective -> {
                if (reflective.base() != null) {
                    setBaseTexture(layers, reflective.base());
                }
                layers.roughness = GLOSSY;
                layers.extras.put("reflectivity", format(reflective.reflectivity()));
            }
            case EnvMapEntityMaterial envMap -> {
                setBaseTexture(layers, envMap.map());
                layers.extras.put("environment_map", envMap.envMapType() == 0 ? "world" : "camera");
            }
            case WaterEntityMaterial water -> {
                if (water.specularDecal()) {
                    setBaseTexture(layers, water.texture());
                    layers.extras.put("specular", "texture");
                    return;
                }
                if (water.alpha() >= 1f) {
                    setBaseTexture(layers, water.texture());
                    layers.roughness = MIRROR;
                    layers.extras.put("reflective", "mirror");
                    return;
                }
                var tint = water.tint();
                var color = tint.getX() + tint.getY() + tint.getZ() > 0
                        ? new float[] {tint.getX(), tint.getY(), tint.getZ()}
                        : WATER_REFERENCE;
                layers.base = null;
                layers.color[0] *= toLinear(color[0]);
                layers.color[1] *= toLinear(color[1]);
                layers.color[2] *= toLinear(color[2]);
                layers.color[3] *= water.alpha();
                layers.roughness = WATER;
                layers.ior = WATER_IOR;
                if (waterNormal != null) {
                    layers.normal = waterRipples(sourceOf(waterNormal));
                }
                layers.extras.put("cruma_kind", "water");
                layers.extras.put(
                        "water_normal_animation",
                        "two samples of cubemaps.water_normal at (2u + 0.015 t, 2v) and (2v + 0.025 t, 2u)");
            }
            case MultiTextureEntityMaterial multi -> {
                var first = multi.getTexture();
                if (first != null) {
                    setBaseTexture(layers, first);
                }
            }
        }
    }

    private void setBaseTexture(Layers layers, Texture texture) {
        layers.base = sourceOf(texture);
        layers.wrap = new Wrap(texture.clampU(), texture.clampV());
    }

    private @Nullable String frameAtlas(List<Texture> frames, UnaryOperator<Source> look, String name) {
        var first = frames.getFirst();
        for (var frame : frames) {
            if (frame.width() != first.width()
                    || frame.height() != first.height()
                    || frame.width() < 1
                    || frame.height() < 1) {
                return null;
            }
        }
        var sources = frames.stream().map(frame -> look.apply(sourceOf(frame))).toList();
        var key = List.copyOf(sources.stream().map(Source::key).toList());
        var known = framePaths.get(key);
        if (known != null) {
            return known;
        }
        var columns = (int) Math.ceil(Math.sqrt(frames.size()));
        var rows = (frames.size() + columns - 1) / columns;
        var cellWidth = first.width();
        var cellHeight = first.height();
        var width = cellWidth * columns;
        var height = cellHeight * rows;
        var base = FRAMES + "/" + LocationExport.toSafeName(name);
        var path = base + ".png";
        for (var number = 2; framePaths.containsValue(path); number++) {
            path = base + "_" + number + ".png";
        }
        frameFiles.add(new LayerFile(path, width, height, false, () -> {
            var pixels = new byte[Math.multiplyExact(Math.multiplyExact(width, height), 4)];
            for (var index = 0; index < sources.size(); index++) {
                var cell = sources.get(index)
                        .pixels()
                        .get()
                        .resized(cellWidth, cellHeight, true)
                        .rgba();
                var left = index % columns * cellWidth;
                var top = index / columns * cellHeight;
                for (var row = 0; row < cellHeight; row++) {
                    System.arraycopy(
                            cell, row * cellWidth * 4, pixels, ((top + row) * width + left) * 4, cellWidth * 4);
                }
            }
            return pixels;
        }));
        framePaths.put(key, path);
        return path;
    }

    private int addImage(Source source, String owner) {
        var known = imageIndices.get(source.key());
        if (known != null) {
            return known;
        }
        var pixels = source.pixels();
        var name = source.kind().isEmpty() ? owner : owner + " " + source.kind();
        images.add(new Image(
                uniqueImageName(name),
                source.width(),
                source.height(),
                () -> pixels.get().rgba()));
        imageIndices.put(source.key(), images.size() - 1);
        return images.size() - 1;
    }

    private Source bakeCombiner(CombinerEntityMaterial combiner) {
        var first = sourceOf(combiner.material1());
        var second = combiner.material2() == null ? null : sourceOf(combiner.material2());
        var mask = combiner.mask() == null ? null : sourceOf(combiner.mask());
        var width = first.width();
        var height = first.height();
        if (second != null) {
            width = Math.max(width, second.width());
            height = Math.max(height, second.height());
        }
        if (mask != null) {
            width = Math.max(width, mask.width());
            height = Math.max(height, mask.height());
        }
        var fade = combiner.material2Fade() == null
                ? null
                : combiner.material2Fade().color1();
        var key = List.of("combiner", new Identity(combiner));
        var outWidth = width;
        var outHeight = height;
        Supplier<RgbaImage> pixels = () -> {
            var a = first.pixels().get().resized(outWidth, outHeight, true);
            var b = second == null ? null : second.pixels().get().resized(outWidth, outHeight, true);
            var m = mask == null ? null : mask.pixels().get().resized(outWidth, outHeight, true);
            var result = new byte[a.rgba().length];
            for (var i = 0; i < result.length; i += 4) {
                var r1 = Byte.toUnsignedInt(a.rgba()[i]) / 255f;
                var g1 = Byte.toUnsignedInt(a.rgba()[i + 1]) / 255f;
                var b1 = Byte.toUnsignedInt(a.rgba()[i + 2]) / 255f;
                var a1 = Byte.toUnsignedInt(a.rgba()[i + 3]) / 255f;
                float r2 = 1;
                float g2 = 1;
                float b2 = 1;
                float a2 = 1;
                if (b != null) {
                    r2 = Byte.toUnsignedInt(b.rgba()[i]) / 255f;
                    g2 = Byte.toUnsignedInt(b.rgba()[i + 1]) / 255f;
                    b2 = Byte.toUnsignedInt(b.rgba()[i + 2]) / 255f;
                    a2 = Byte.toUnsignedInt(b.rgba()[i + 3]) / 255f;
                }
                if (fade != null) {
                    r2 *= fade.getX();
                    g2 *= fade.getY();
                    b2 *= fade.getZ();
                    a2 *= fade.getW();
                }
                float rm = 1;
                float gm = 1;
                float bm = 1;
                float am = 1;
                if (m != null) {
                    rm = Byte.toUnsignedInt(m.rgba()[i]) / 255f;
                    gm = Byte.toUnsignedInt(m.rgba()[i + 1]) / 255f;
                    bm = Byte.toUnsignedInt(m.rgba()[i + 2]) / 255f;
                    am = Byte.toUnsignedInt(m.rgba()[i + 3]) / 255f;
                }
                var blend = combiner.invertMask() ? 1 - am : am;
                float r;
                float g;
                float bl;
                switch (combiner.combineOp()) {
                    case 1 -> {
                        r = r2;
                        g = g2;
                        bl = b2;
                    }
                    case 2 -> {
                        r = r1 * r2 * combiner.modulate();
                        g = g1 * g2 * combiner.modulate();
                        bl = b1 * b2 * combiner.modulate();
                    }
                    case 3 -> {
                        r = r1 + r2;
                        g = g1 + g2;
                        bl = b1 + b2;
                    }
                    case 4 -> {
                        r = r1 - r2;
                        g = g1 - g2;
                        bl = b1 - b2;
                    }
                    case 5 -> {
                        r = r1 + (r2 - r1) * blend;
                        g = g1 + (g2 - g1) * blend;
                        bl = b1 + (b2 - b1) * blend;
                    }
                    case 6 -> {
                        r = r1 + r2 * blend;
                        g = g1 + g2 * blend;
                        bl = b1 + b2 * blend;
                    }
                    case 7 -> {
                        r = rm;
                        g = gm;
                        bl = bm;
                    }
                    default -> {
                        r = r1;
                        g = g1;
                        bl = b1;
                    }
                }
                result[i] = RgbaImage.toByte(r);
                result[i + 1] = RgbaImage.toByte(g);
                result[i + 2] = RgbaImage.toByte(bl);
                result[i + 3] = RgbaImage.toByte(combiner.alphaOp() == 4 ? a2 : a1);
            }
            return new RgbaImage(outWidth, outHeight, result);
        };
        return new Source(key, "combined", width, height, pixels, true);
    }

    private interface TexelOperation {
        void apply(float[] first, float[] second, float[] out);
    }

    private String uniqueMaterialName(String name) {
        return makeUnique(name, materialNames);
    }

    private String uniqueImageName(String name) {
        return makeUnique(name, imageNames);
    }

    private static float alphaCutoff(Surface surface) {
        return surface.alphaRef() >= 0 ? Math.max(1, surface.alphaRef()) / 255f : DEFAULT_CUTOFF;
    }

    private static Source sourceOf(Texture texture) {
        var data = texture.data().isEmpty() ? null : texture.data().getFirst();
        var width = Math.max(1, texture.width());
        var height = Math.max(1, texture.height());
        return new Source(
                new Identity(data == null ? texture : data),
                "",
                width,
                height,
                () -> decode(texture, width, height),
                true);
    }

    private static Source toOpaque(Source base) {
        return derive("opaque", base, base.width(), base.height(), image -> {
            var rgba = image.rgba().clone();
            for (var i = 3; i < rgba.length; i += 4) {
                rgba[i] = (byte) 0xFF;
            }
            return new RgbaImage(image.width(), image.height(), rgba);
        });
    }

    private static Source withOpacity(Source base, Source opacity) {
        return combine("opacity", base, opacity, (color, alpha, out) -> {
            out[0] = color[0];
            out[1] = color[1];
            out[2] = color[2];
            out[3] = color[3] * alpha[3];
        });
    }

    private static Source maskGlow(Source glow, Source mask) {
        return combine("glow-mask", glow, mask, (color, alpha, out) -> {
            out[0] = color[0] * alpha[3];
            out[1] = color[1] * alpha[3];
            out[2] = color[2] * alpha[3];
            out[3] = 1;
        });
    }

    private static Source deriveGlow(Source base, boolean brighten) {
        return derive("glow", base, base.width(), base.height(), image -> {
            var rgba = image.rgba().clone();
            for (var i = 0; i < rgba.length; i += 4) {
                var brightest = Math.max(
                        Byte.toUnsignedInt(rgba[i]),
                        Math.max(Byte.toUnsignedInt(rgba[i + 1]), Byte.toUnsignedInt(rgba[i + 2])));
                rgba[i + 3] = (byte) (brighten ? brightest * Byte.toUnsignedInt(rgba[i + 3]) / 255 : brightest);
            }
            return new RgbaImage(image.width(), image.height(), rgba);
        });
    }

    private static Source darken(Source base, boolean modulate) {
        return derive(modulate ? "modulate" : "darken", base, base.width(), base.height(), image -> {
            var rgba = image.rgba().clone();
            for (var i = 0; i < rgba.length; i += 4) {
                var luminance = (.299f * Byte.toUnsignedInt(rgba[i])
                                + .587f * Byte.toUnsignedInt(rgba[i + 1])
                                + .114f * Byte.toUnsignedInt(rgba[i + 2]))
                        / 255f;
                var alpha = modulate ? 1f - Math.min(1f, 2f * luminance) : luminance;
                rgba[i] = 0;
                rgba[i + 1] = 0;
                rgba[i + 2] = 0;
                rgba[i + 3] = RgbaImage.toByte(alpha);
            }
            return new RgbaImage(image.width(), image.height(), rgba);
        });
    }

    private static Source combine(String kind, Source first, Source second, TexelOperation operation) {
        var width = Math.max(first.width(), second.width());
        var height = Math.max(first.height(), second.height());
        return new Source(
                List.of(kind, first.key(), second.key()),
                kind,
                width,
                height,
                () -> {
                    var a = first.pixels().get().resized(width, height, first.repeat());
                    var b = second.pixels().get().resized(width, height, second.repeat());
                    var result = new byte[a.rgba().length];
                    var x = new float[4];
                    var y = new float[4];
                    var out = new float[4];
                    for (var i = 0; i < result.length; i += 4) {
                        for (var channel = 0; channel < 4; channel++) {
                            x[channel] = Byte.toUnsignedInt(a.rgba()[i + channel]) / 255f;
                            y[channel] = Byte.toUnsignedInt(b.rgba()[i + channel]) / 255f;
                        }
                        operation.apply(x, y, out);
                        for (var channel = 0; channel < 4; channel++) {
                            result[i + channel] = RgbaImage.toByte(out[channel]);
                        }
                    }
                    return new RgbaImage(width, height, result);
                },
                first.repeat());
    }

    private interface ImageOperation {
        RgbaImage apply(RgbaImage image);
    }

    private static Source derive(String kind, Source base, int width, int height, ImageOperation operation) {
        return new Source(
                List.of(kind, base.key()),
                kind,
                width,
                height,
                () -> operation.apply(base.pixels().get()),
                base.repeat());
    }

    private static Source waterRipples(Source normal) {
        var width = normal.width() * 2;
        var height = normal.height() * 2;
        return new Source(
                List.of("water-ripples", normal.key()),
                "ripples",
                width,
                height,
                () -> {
                    var map = normal.pixels().get();
                    var rgba = new byte[Math.multiplyExact(Math.multiplyExact(width, height), 4)];
                    var first = new float[4];
                    var second = new float[4];
                    for (var y = 0; y < height; y++) {
                        for (var x = 0; x < width; x++) {
                            var u = (x + .5f) / width;
                            var v = (y + .5f) / height;
                            map.sample(2 * u, 2 * v, true, first);
                            map.sample(2 * v, 2 * u, true, second);
                            var nx = first[0] * 2 - 1 + second[0] * 2 - 1;
                            var ny = first[1] * 2 - 1 + second[1] * 2 - 1;
                            var nz = first[2] * 2 - 1 + second[2] * 2 - 1;
                            var length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                            if (length < 1e-6f) {
                                nx = 0;
                                ny = 0;
                                nz = 1;
                                length = 1;
                            }
                            var offset = (y * width + x) * 4;
                            rgba[offset] = RgbaImage.toByte(nx / length * .5f + .5f);
                            rgba[offset + 1] = RgbaImage.toByte(ny / length * .5f + .5f);
                            rgba[offset + 2] = RgbaImage.toByte(nz / length * .5f + .5f);
                            rgba[offset + 3] = (byte) 0xFF;
                        }
                    }
                    return new RgbaImage(width, height, rgba);
                },
                true);
    }

    private static float toLinear(float srgb) {
        return srgb <= .04045f ? srgb / 12.92f : (float) Math.pow((srgb + .055f) / 1.055f, 2.4f);
    }

    private static String makeUnique(String name, Set<String> taken) {
        if (taken.add(name)) {
            return name;
        }
        for (var suffix = 2; ; suffix++) {
            var candidate = name + " " + suffix;
            if (taken.add(candidate)) {
                return candidate;
            }
        }
    }

    private static String describeAnimation(UvAnimation animation) {
        return switch (animation.mode()) {
            case 1 -> "pan " + format(animation.paramX()) + " " + format(animation.paramY()) + " per second";
            case 2 ->
                "rotate "
                        + format(animation.paramZ())
                        + " radians per second about "
                        + format(animation.paramX())
                        + " "
                        + format(animation.paramY())
                        + (animation.paramW() != 0f || animation.extra() != 0f
                                ? ", pitch " + format(animation.paramW()) + ", roll " + format(animation.extra())
                                : "");
            case 3 ->
                "oscillate "
                        + format(animation.paramX())
                        + " "
                        + format(animation.paramY())
                        + " "
                        + format(animation.paramZ())
                        + " "
                        + format(animation.paramW());
            case 4 ->
                "scale "
                        + format(animation.paramX())
                        + " "
                        + format(animation.paramY())
                        + " offset "
                        + format(animation.paramZ())
                        + " "
                        + format(animation.paramW());
            case 5 ->
                "oscillating rotation "
                        + format(animation.paramX())
                        + " "
                        + format(animation.paramZ())
                        + " about "
                        + format(animation.paramY())
                        + " "
                        + format(animation.paramW());
            default -> "mode " + animation.mode();
        };
    }

    private static String format(float value) {
        return String.format(Locale.ROOT, "%.4g", value);
    }
}
