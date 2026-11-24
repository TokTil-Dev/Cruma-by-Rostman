package com.rost.cruma.core.world;

import com.rost.cruma.core.texture.DxtMipCodec;
import com.rost.cruma.core.texture.DxtMipCodec.Options;
import com.rost.cruma.core.unreal.UActor.StaticMeshActor;
import com.rost.cruma.core.unreal.ULevel;
import com.rost.cruma.core.unreal.UMaterial;
import com.rost.cruma.core.unreal.UMaterial.UCombiner;
import com.rost.cruma.core.unreal.UMaterial.UCubemap;
import com.rost.cruma.core.unreal.UMaterial.UMipmap;
import com.rost.cruma.core.unreal.UMaterial.UModifier;
import com.rost.cruma.core.unreal.UMaterial.UShader;
import com.rost.cruma.core.unreal.UMaterial.UTexture;
import com.rost.cruma.core.unreal.UObjectRef;
import com.rost.cruma.core.unreal.UPackage;
import com.rost.cruma.core.unreal.UTerrain.UTerrainInfoActor;
import com.rost.cruma.core.world.Entity.TextureFormat;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.CRC32C;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public final class DxtChainCache {
    private static final int MAX_ANIM_FRAMES = 64;

    private static final Logger log = LogManager.getLogger(DxtChainCache.class);

    private final Options options;
    private final Map<String, List<byte[]>> chains = new ConcurrentHashMap<>();
    private final @Nullable DxtChainStore store;
    private final AtomicBoolean storeWarned = new AtomicBoolean();
    private final ThreadLocal<Map<UTexture, String>> chainKeys = ThreadLocal.withInitial(IdentityHashMap::new);
    private final Map<String, Sample> census = new ConcurrentHashMap<>();
    private final ThreadLocal<Set<UTexture>> censusIdentity =
            ThreadLocal.withInitial(() -> Collections.newSetFromMap(new IdentityHashMap<>()));

    public DxtChainCache(Options options, @Nullable Path cacheRoot, Path clientRoot) {
        if (options == null) {
            throw new IllegalArgumentException("DXT mip codec options cannot be null");
        }
        this.options = options;
        this.store = cacheRoot == null ? null : openStore(cacheRoot, clientRoot);
    }

    public DxtChainCache(Options options) {
        this(options, null, null);
    }

    private record Sample(int authoredLevels, int expectedLevels, long authoredBytes, long expectedBytes) {}

    public record Census(
            int textures,
            int complete,
            int partial,
            int baseOnly,
            int malformed,
            long authoredLevels,
            long expectedLevels,
            long authoredBytes,
            long expectedBytes) {}

    private record Job(
            String key,
            UTexture texture,
            TextureFormat format,
            int width,
            int height,
            List<byte[]> authored,
            int validLevels) {}

    static @Nullable TextureFormat dxtFormat(UTexture texture) {
        return switch (texture.getFormat()) {
            case TEXF_DXT1 -> TextureFormat.TEXTURE_DXT1;
            case TEXF_DXT3 -> TextureFormat.TEXTURE_DXT3;
            case TEXF_DXT5 -> TextureFormat.TEXTURE_DXT5;
            default -> null;
        };
    }

    public Options getOptions() {
        return options;
    }

    public boolean isEnabled() {
        return options.enabled();
    }

    public int size() {
        return chains.size();
    }

    public long getBytes() {
        var total = 0L;
        for (var chain : chains.values()) {
            for (var mip : chain) {
                total += mip.length;
            }
        }
        return total;
    }

    public void clear() {
        chains.clear();
        chainKeys.get().clear();
    }

    void trimThreadState() {
        chainKeys.get().clear();
        censusIdentity.get().clear();
    }

    public Census getCensus() {
        var complete = 0;
        var partial = 0;
        var baseOnly = 0;
        var malformed = 0;
        var authoredLevels = 0L;
        var expectedLevels = 0L;
        var authoredBytes = 0L;
        var expectedBytes = 0L;
        for (var sample : census.values()) {
            authoredLevels += sample.authoredLevels();
            expectedLevels += sample.expectedLevels();
            authoredBytes += sample.authoredBytes();
            expectedBytes += sample.expectedBytes();
            if (sample.authoredLevels() == 0) {
                malformed++;
            } else if (sample.authoredLevels() == sample.expectedLevels()) {
                complete++;
            } else if (sample.authoredLevels() == 1) {
                baseOnly++;
            } else {
                partial++;
            }
        }
        return new Census(
                census.size(),
                complete,
                partial,
                baseOnly,
                malformed,
                authoredLevels,
                expectedLevels,
                authoredBytes,
                expectedBytes);
    }

    void prebuild(UPackage uPackage) {
        if (!options.enabled()) {
            return;
        }
        var textures = new LinkedHashSet<UTexture>();
        var levels = new ArrayList<ULevel>();
        uPackage.loadObjects("Level", levels);
        for (var level : levels) {
            try {
                var model = level.getModel().get();
                if (model == null || model.getSurfaces() == null) {
                    continue;
                }
                for (var surface : model.getSurfaces()) {
                    try {
                        collectMaterialTextures(surface.getMaterial().get(), textures);
                    } catch (RuntimeException ignored) {
                    }
                }
            } catch (RuntimeException ignored) {
            }
        }
        var meshActors = new ArrayList<StaticMeshActor>();
        uPackage.loadObjects("StaticMeshActor", meshActors);
        uPackage.loadObjects("MovableStaticMeshActor", meshActors);
        uPackage.loadObjects("L2MovableStaticMeshActor", meshActors);
        for (var actor : meshActors) {
            try {
                var mesh = actor.getStaticMesh().get();
                if (mesh == null || mesh.getMaterials() == null) {
                    continue;
                }
                for (var material : mesh.getMaterials()) {
                    try {
                        collectMaterialTextures(material.getMaterial().get(), textures);
                    } catch (RuntimeException ignored) {
                    }
                }
            } catch (RuntimeException ignored) {
            }
        }
        var terrains = new ArrayList<UTerrainInfoActor>();
        uPackage.loadObjects("TerrainInfo", terrains);
        for (var terrainInfo : terrains) {
            for (var layer : terrainInfo.getLayers()) {
                try {
                    collectMaterialTextures(layer.getTexture().get(), textures);
                } catch (RuntimeException ignored) {
                }
            }
        }
        var frames = new LinkedHashSet<UTexture>();
        for (var texture : textures) {
            try {
                var frame = texture.getAnimationNextFrame();
                var walked = 0;
                while (frame != null && !textures.contains(frame) && frames.add(frame) && ++walked < MAX_ANIM_FRAMES) {
                    frame = frame.getAnimationNextFrame();
                }
            } catch (RuntimeException ignored) {
            }
        }
        textures.addAll(frames);
        var jobs = new ArrayList<Job>();
        for (var texture : textures) {
            try {
                var format = dxtFormat(texture);
                if (format == null) {
                    continue;
                }
                var mips = texture.getMips();
                if (mips == null || mips.length == 0) {
                    continue;
                }
                var width = texture.getUSize();
                var height = texture.getVSize();
                var authored = Arrays.stream(mips).map(UMipmap::getData).toList();
                var inspection = DxtMipCodec.inspectAuthoredChain(format, width, height, authored);
                if (inspection.isComplete() || inspection.validLevels() == 0) {
                    continue;
                }
                var key = chainKey(texture, format, width, height, authored, inspection.validLevels());
                if (chains.containsKey(key)) {
                    continue;
                }
                jobs.add(new Job(key, texture, format, width, height, authored, inspection.validLevels()));
            } catch (RuntimeException ignored) {
            }
        }
        jobs.parallelStream().forEach(job -> {
            try {
                loadOrEncodeTail(job.key(), job.format(), job.width(), job.height(), job.authored(), job.validLevels());
            } catch (RuntimeException e) {
                log.warn("Mip prebuild failed for {}: {}", job.texture().getFullName(), e.toString());
            }
        });
    }

    List<byte[]> complete(UTexture texture, TextureFormat format, int width, int height, List<byte[]> authored) {
        if (!options.enabled()) {
            return authored;
        }
        var inspection = DxtMipCodec.inspectAuthoredChain(format, width, height, authored);
        if (inspection.isComplete() || inspection.validLevels() == 0) {
            return authored;
        }
        var key = chainKey(texture, format, width, height, authored, inspection.validLevels());
        var cachedTail = chains.get(key);
        if (cachedTail == null) {
            try {
                cachedTail = loadOrEncodeTail(key, format, width, height, authored, inspection.validLevels());
            } catch (RuntimeException e) {
                log.warn("Mip codec failed for {}, using authored levels: {}", texture.getFullName(), e.toString());
                return authored;
            }
        }
        var chain = new ArrayList<byte[]>(inspection.validLevels() + cachedTail.size());
        chain.addAll(authored.subList(0, inspection.validLevels()));
        chain.addAll(cachedTail);
        return chain;
    }

    void recordCensus(UTexture texture, TextureFormat format, int width, int height, List<byte[]> authored) {
        if (!censusIdentity.get().add(texture)) {
            return;
        }
        try {
            var inspection = DxtMipCodec.inspectAuthoredChain(format, width, height, authored);
            var blockBytes = format == TextureFormat.TEXTURE_DXT1 ? 8 : 16;
            var expectedBytes = 0L;
            var authoredBytes = 0L;
            for (var level = 0; level < inspection.expectedLevels(); level++) {
                var levelWidth = Math.max(1, width >> level);
                var levelHeight = Math.max(1, height >> level);
                var bytes = (long) ((levelWidth + 3) / 4) * ((levelHeight + 3) / 4) * blockBytes;
                expectedBytes = Math.addExact(expectedBytes, bytes);
                if (level < inspection.validLevels()) {
                    authoredBytes = Math.addExact(authoredBytes, authored.get(level).length);
                }
            }
            var key = texture.getFullName() + '|' + format + '|' + width + 'x' + height;
            census.putIfAbsent(
                    key,
                    new Sample(inspection.validLevels(), inspection.expectedLevels(), authoredBytes, expectedBytes));
        } catch (RuntimeException ignored) {
        }
    }

    private List<byte[]> loadOrEncodeTail(
            String key, TextureFormat format, int width, int height, List<byte[]> authored, int validLevels) {
        var cached = chains.get(key);
        if (cached != null) {
            return cached;
        }
        var tail = store == null ? null : store.load(key);
        if (tail == null) {
            var completed = DxtMipCodec.completeAuthoredChain(format, width, height, authored);
            tail = List.copyOf(
                    completed.levels().subList(validLevels, completed.levels().size()));
            if (store != null) {
                try {
                    store.save(key, tail);
                } catch (IOException e) {
                    if (storeWarned.compareAndSet(false, true)) {
                        log.warn("Mip cache write failed, continuing without it: {}", e.toString());
                    }
                }
            }
        }
        var previous = chains.putIfAbsent(key, tail);
        return previous == null ? tail : previous;
    }

    private String chainKey(
            UTexture texture, TextureFormat format, int width, int height, List<byte[]> authored, int validLevels) {
        var cache = chainKeys.get();
        var cached = cache.get(texture);
        if (cached != null) {
            return cached;
        }
        var crc = new CRC32C();
        for (var level = 0; level < validLevels; level++) {
            crc.update(authored.get(level));
        }
        var key = texture.getFullName()
                + '|'
                + format
                + '|'
                + width
                + 'x'
                + height
                + '|'
                + validLevels
                + '|'
                + Long.toHexString(crc.getValue());
        cache.put(texture, key);
        return key;
    }

    private static @Nullable DxtChainStore openStore(Path cacheRoot, Path clientRoot) {
        var directory = DxtChainStore.clientDirectory(cacheRoot, clientRoot);
        try {
            return new DxtChainStore(directory);
        } catch (IOException e) {
            log.warn("Mip cache at {} is unusable: {}", directory, e.toString());
            return null;
        }
    }

    private static void collectMaterialTextures(@Nullable UMaterial material, Set<UTexture> out) {
        switch (material) {
            case null -> {}
            case UTexture texture -> out.add(texture);
            case UShader shader -> {
                collectRefTextures(shader.getDiffuse(), out);
                collectRefTextures(shader.getOpacity(), out);
                collectRefTextures(shader.getSelfIllumination(), out);
                collectRefTextures(shader.getSpecular(), out);
            }
            case UCombiner combiner -> {
                collectRefTextures(combiner.getMaterial1(), out);
                collectRefTextures(combiner.getMaterial2(), out);
                collectRefTextures(combiner.getMask(), out);
            }
            case UModifier modifier -> collectRefTextures(modifier.getMaterial(), out);
            case UCubemap cubemap -> {
                for (var face : cubemap.getFaces()) {
                    collectRefTextures(face, out);
                }
            }
            default -> {}
        }
    }

    private static void collectRefTextures(UObjectRef<? extends UMaterial, ?> ref, Set<UTexture> out) {
        try {
            collectMaterialTextures(ref.get(), out);
        } catch (RuntimeException ignored) {
        }
    }
}
