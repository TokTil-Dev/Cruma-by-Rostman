package com.rost.cruma.viewer.editing;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.core.editing.PackageEditDocument;
import com.rost.cruma.core.texture.TexturePixels;
import com.rost.cruma.core.unreal.ClientFiles;
import com.rost.cruma.core.unreal.UArchive;
import com.rost.cruma.core.unreal.UArchiveLoader;
import com.rost.cruma.core.unreal.UArchiveLoader.USearchConfig;
import com.rost.cruma.core.unreal.UDecryptor;
import com.rost.cruma.core.unreal.UMaterial;
import com.rost.cruma.core.unreal.UMaterial.UFinalBlend;
import com.rost.cruma.core.unreal.UMaterial.UModifier;
import com.rost.cruma.core.unreal.UMaterial.UShader;
import com.rost.cruma.core.unreal.UMaterial.UTexture;
import com.rost.cruma.core.unreal.UStaticMesh;
import com.rost.cruma.core.world.Entity.Texture;
import com.rost.cruma.core.world.Entity.TextureFormat;
import com.rost.cruma.viewer.render.api.ViewerUiRenderer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.BufferUtils;

public final class MeshLibrary implements AutoCloseable {
    static final int THUMBNAIL = 128;

    static final int SWATCH = 64;

    static final int RECENT = 12;

    private static final int VERSION = 1;
    private static final String MESH_PICTURE_VERSION = "cutout|";
    private static final int UPLOADS_PER_FRAME = 6;
    private static final int KEPT = 400;
    private final Path directory;
    private final Path clientRoot;
    private final ExecutorService indexer =
            Executors.newSingleThreadExecutor(runnable -> daemon(runnable, "mesh-library"));
    private final ExecutorService materialIndexer =
            Executors.newSingleThreadExecutor(runnable -> daemon(runnable, "material-library"));
    private final ExecutorService painter =
            Executors.newSingleThreadExecutor(runnable -> daemon(runnable, "mesh-thumbnails"));
    private volatile List<MeshEntry> meshes = List.of();
    private volatile Map<String, MeshEntry> meshesByPath = Map.of();
    private volatile List<MaterialEntry> materials = List.of();
    private volatile Map<String, MaterialEntry> materialsByPath = Map.of();
    private final AtomicInteger meshesDone = new AtomicInteger();
    private final AtomicInteger meshesTotal = new AtomicInteger();
    private final AtomicInteger materialsDone = new AtomicInteger();
    private final AtomicInteger materialsTotal = new AtomicInteger();
    private volatile boolean meshesReady;
    private volatile boolean materialsReady;
    private volatile String meshFailure = "";
    private volatile String materialFailure = "";
    private boolean meshesStarted;
    private boolean materialsStarted;
    private final ConcurrentLinkedQueue<Painted> painted = new ConcurrentLinkedQueue<>();
    private final Set<String> pending = new HashSet<>();
    private final Set<String> missing = new HashSet<>();
    private final LinkedHashMap<String, Long> textures = new LinkedHashMap<>(256, .75f, true);
    private final ArrayList<String> recent = new ArrayList<>();
    private @Nullable MeshFavorites favorites;
    private @Nullable ViewerUiRenderer renderer;
    private @Nullable Path openFile;
    private @Nullable UArchive openArchive;

    public MeshLibrary(Path configDirectory, Path clientRoot) {
        this.clientRoot = clientRoot.toAbsolutePath().normalize();
        directory =
                configDirectory.toAbsolutePath().normalize().resolve("library").resolve(clientKey(this.clientRoot));
    }

    public record MeshEntry(String path, Path packageFile, float[] bounds, List<String> slots) {
        public MeshEntry {
            slots = List.copyOf(slots);
        }

        public String name() {
            return EditingSession.shortName(path);
        }

        public String packageName() {
            var dot = path.indexOf('.');
            return dot < 0 ? path : path.substring(0, dot);
        }

        public float width() {
            return Math.max(bounds[3] - bounds[0], bounds[4] - bounds[1]);
        }
    }

    public record MaterialEntry(String path, String className, Path packageFile) {
        public String name() {
            return EditingSession.shortName(path);
        }

        public String packageName() {
            var dot = path.indexOf('.');
            return dot < 0 ? path : path.substring(0, dot);
        }
    }

    private record Painted(String key, byte @Nullable [] rgba, int size) {}

    private record Cached<T>(long size, long modified, List<T> entries) {}

    private record Level(int width, int height, byte[] rgba) {}

    public static List<MeshEntry> readMeshes(Path file) throws IOException {
        var result = new ArrayList<MeshEntry>();
        try (var archive = open(file)) {
            for (var export : archive.getExportMap()) {
                if (!"StaticMesh".equals(export.getClassName())) {
                    continue;
                }
                UStaticMesh mesh;
                try {
                    mesh = (UStaticMesh) archive.getObjectLoader().exportObject(export);
                } catch (RuntimeException unreadable) {
                    continue;
                }
                if (mesh == null) {
                    continue;
                }
                var slots = new ArrayList<String>();
                var materials = mesh.getMaterials();
                for (var i = 0; materials != null && i < materials.length; i++) {
                    String name;
                    try {
                        name = materials[i].getMaterial().peekFullName();
                    } catch (RuntimeException unresolved) {
                        name = null;
                    }
                    slots.add(name == null ? "" : name);
                }
                result.add(new MeshEntry(
                        mesh.getSourcePath(), file.toAbsolutePath().normalize(), bounds(mesh), slots));
            }
        }
        return result;
    }

    public static List<MaterialEntry> readMaterials(Path file) throws IOException {
        var result = new ArrayList<MaterialEntry>();
        var normalized = file.toAbsolutePath().normalize();
        for (var reference : PackageEditDocument.inspectMaterials(file)) {
            result.add(new MaterialEntry(reference.sourcePath(), reference.className(), normalized));
        }
        return result;
    }

    static byte @Nullable [] drawMesh(UStaticMesh mesh) {
        var vertices =
                mesh.getVertexStream() == null ? null : mesh.getVertexStream().getVertices();
        var indexStream = mesh.getIndexStream();
        if (vertices == null || vertices.length == 0 || indexStream == null || mesh.getSurfaces() == null) {
            return null;
        }
        var positions = new float[vertices.length * 3];
        for (var i = 0; i < vertices.length; i++) {
            var p = vertices[i].getLocation();
            positions[i * 3] = p.getX();
            positions[i * 3 + 1] = p.getY();
            positions[i * 3 + 2] = p.getZ();
        }
        var uvs = firstUvs(mesh, vertices.length);
        var materials = mesh.getMaterials();
        var source = indexStream.getIndices();
        var indices = new ArrayList<Integer>();
        var cutouts = new ArrayList<MeshThumbnail.Cutout>();
        var surfaces = mesh.getSurfaces();
        for (var section = 0; section < surfaces.length; section++) {
            var surface = surfaces[section];
            var cutout = uvs == null || materials == null || section >= materials.length
                    ? null
                    : cutoutOf(materials[section]);
            for (var triangle = 0; triangle < surface.getTriangleMax(); triangle++) {
                var start = surface.getFirstIndex() + triangle * 3;
                if (start + 2 >= source.length) {
                    break;
                }
                indices.add(source[start]);
                indices.add(source[start + 1]);
                indices.add(source[start + 2]);
                cutouts.add(cutout);
            }
        }
        if (indices.isEmpty()) {
            return null;
        }
        var array = new int[indices.size()];
        for (var i = 0; i < array.length; i++) {
            array[i] = indices.get(i);
        }
        return MeshThumbnail.render(positions, array, uvs, cutouts.toArray(MeshThumbnail.Cutout[]::new), THUMBNAIL);
    }

    static MeshThumbnail.@Nullable Cutout cutoutOf(@Nullable UMaterial material, boolean alphaTest, int depth) {
        if (material == null || depth > 8) {
            return null;
        }
        if (material instanceof UTexture texture) {
            return alphaTest || texture.isMasked() || texture.isAlphaTexture() ? alphaOf(texture) : null;
        }
        if (material instanceof UFinalBlend blend) {
            return cutoutOf(blend.getMaterial().get(), alphaTest || blend.isAlphaTest(), depth + 1);
        }
        if (material instanceof UShader shader) {
            var opacity = cutoutOf(shader.getOpacity().get(), true, depth + 1);
            return opacity != null
                    ? opacity
                    : cutoutOf(shader.getDiffuse().get(), alphaTest || shader.isAlphaTest(), depth + 1);
        }
        if (material instanceof UModifier modifier) {
            return cutoutOf(modifier.getMaterial().get(), alphaTest, depth + 1);
        }
        return null;
    }

    static byte @Nullable [] swatchOf(UTexture texture) {
        var level = decodeLevel(texture, SWATCH);
        if (level == null) {
            return null;
        }
        var width = level.width();
        var height = level.height();
        var rgba = level.rgba();
        var result = new byte[SWATCH * SWATCH * 4];
        for (var y = 0; y < SWATCH; y++) {
            for (var x = 0; x < SWATCH; x++) {
                var from = ((y * height / SWATCH) * width + x * width / SWATCH) * 4;
                var to = (y * SWATCH + x) * 4;
                result[to] = rgba[from];
                result[to + 1] = rgba[from + 1];
                result[to + 2] = rgba[from + 2];
                result[to + 3] = (byte) 255;
            }
        }
        return result;
    }

    public void start() {
        if (meshesStarted) {
            return;
        }
        meshesStarted = true;
        indexer.submit(() -> {
            try {
                var found = index(
                        ".usx",
                        directory.resolve("meshes-v" + VERSION + ".json"),
                        meshesDone,
                        meshesTotal,
                        MeshLibrary::readMeshes,
                        MeshLibrary::meshJson,
                        MeshLibrary::meshFromJson);
                found.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
                var byPath = new TreeMap<String, MeshEntry>(String.CASE_INSENSITIVE_ORDER);
                found.forEach(entry -> byPath.putIfAbsent(entry.path(), entry));
                meshesByPath = Collections.unmodifiableMap(byPath);
                meshes = List.copyOf(found);
            } catch (IOException | RuntimeException failed) {
                meshFailure = "The mesh index could not be read: " + failed.getMessage();
            } finally {
                meshesReady = true;
            }
        });
    }

    public void startMaterials() {
        if (materialsStarted) {
            return;
        }
        materialsStarted = true;
        materialIndexer.submit(() -> {
            try {
                var found = index(
                        ".utx",
                        directory.resolve("materials-v" + VERSION + ".json"),
                        materialsDone,
                        materialsTotal,
                        MeshLibrary::readMaterials,
                        MeshLibrary::materialJson,
                        MeshLibrary::materialFromJson);
                found.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
                var byPath = new TreeMap<String, MaterialEntry>(String.CASE_INSENSITIVE_ORDER);
                found.forEach(entry -> byPath.putIfAbsent(entry.path(), entry));
                materialsByPath = Collections.unmodifiableMap(byPath);
                materials = List.copyOf(found);
            } catch (IOException | RuntimeException failed) {
                materialFailure = "The material index could not be read: " + failed.getMessage();
            } finally {
                materialsReady = true;
            }
        });
    }

    public boolean areMeshesReady() {
        return meshesReady;
    }

    public boolean areMaterialsReady() {
        return materialsReady;
    }

    public int[] getMeshProgress() {
        return new int[] {meshesDone.get(), meshesTotal.get()};
    }

    public int[] getMaterialProgress() {
        return new int[] {materialsDone.get(), materialsTotal.get()};
    }

    public String getFailure() {
        return meshFailure.isEmpty() ? materialFailure : meshFailure;
    }

    public String getMeshFailure() {
        return meshFailure;
    }

    public String getMaterialFailure() {
        return materialFailure;
    }

    public List<MeshEntry> getMeshes() {
        return meshes;
    }

    public @Nullable MeshEntry getMesh(String path) {
        return meshesByPath.get(path);
    }

    public List<MaterialEntry> getMaterials() {
        return materials;
    }

    public @Nullable MaterialEntry getMaterial(String path) {
        return materialsByPath.get(path);
    }

    public Path getDirectory() {
        return directory;
    }

    public List<String> getRecent() {
        return List.copyOf(recent);
    }

    public MeshFavorites getFavorites() {
        if (favorites == null) {
            favorites = new MeshFavorites(directory.resolve(MeshFavorites.FILE));
        }
        return favorites;
    }

    public void used(String meshPath) {
        recent.removeIf(path -> path.equalsIgnoreCase(meshPath));
        recent.addFirst(meshPath);
        while (recent.size() > RECENT) {
            recent.removeLast();
        }
    }

    public long getThumbnail(MeshEntry mesh) {
        var key = "mesh:" + mesh.path();
        return texture(key, () -> paintMesh(mesh, key));
    }

    public long getSwatch(MaterialEntry material) {
        var key = "material:" + material.path();
        return texture(key, () -> paintMaterial(material, key));
    }

    public void poll(@Nullable ViewerUiRenderer uiRenderer) {
        if (uiRenderer == null) {
            return;
        }
        renderer = uiRenderer;
        for (var uploaded = 0; uploaded < UPLOADS_PER_FRAME; uploaded++) {
            var next = painted.poll();
            if (next == null) {
                break;
            }
            pending.remove(next.key());
            if (next.rgba() == null) {
                missing.add(next.key());
                continue;
            }
            var pixels = BufferUtils.createByteBuffer(next.rgba().length);
            pixels.put(next.rgba()).flip();
            textures.put(
                    next.key(), uiRenderer.createTexture("ui/library/" + next.key(), next.size(), next.size(), pixels));
        }
        var iterator = textures.entrySet().iterator();
        while (textures.size() > KEPT && iterator.hasNext()) {
            uiRenderer.destroyTexture(iterator.next().getValue());
            iterator.remove();
        }
    }

    @Override
    public void close() {
        indexer.shutdownNow();
        materialIndexer.shutdownNow();
        painter.shutdownNow();
        try {
            if (painter.awaitTermination(2, TimeUnit.SECONDS) && openArchive != null) {
                openArchive.close();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        if (renderer != null) {
            textures.values().forEach(renderer::destroyTexture);
        }
        textures.clear();
    }

    private <T> ArrayList<T> index(
            String extension,
            Path cacheFile,
            AtomicInteger done,
            AtomicInteger total,
            Reader<T> reader,
            Encoder<T> encoder,
            Decoder<T> decoder)
            throws IOException {
        var cached = readCache(cacheFile, decoder);
        var files = packages(extension);
        total.set(files.size());
        var result = new ArrayList<T>();
        var packages = new JSONArray();
        var changed = cached.size() != files.size();
        for (var file : files) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IOException("Stopped");
            }
            var relative = relative(file);
            var size = Files.size(file);
            var modified = Files.getLastModifiedTime(file).toMillis();
            var known = cached.get(relative);
            List<T> entries;
            if (known != null && known.size() == size && known.modified() == modified) {
                entries = known.entries();
            } else {
                changed = true;
                try {
                    entries = reader.read(file);
                } catch (IOException | RuntimeException unreadable) {
                    entries = List.of();
                }
            }
            result.addAll(entries);
            var json = new JSONObject();
            json.put("file", relative);
            json.put("size", size);
            json.put("modified", modified);
            var list = new JSONArray();
            for (var entry : entries) {
                list.add(encoder.encode(entry, clientRoot));
            }
            json.put("entries", list);
            packages.add(json);
            done.incrementAndGet();
        }
        if (changed) {
            var root = new JSONObject();
            root.put("version", VERSION);
            root.put("client", clientRoot.toString());
            root.put("packages", packages);
            write(cacheFile, JSON.toJSONString(root).getBytes(StandardCharsets.UTF_8));
        }
        return result;
    }

    private <T> Map<String, Cached<T>> readCache(Path cacheFile, Decoder<T> decoder) {
        var result = new HashMap<String, Cached<T>>();
        if (!Files.isRegularFile(cacheFile)) {
            return result;
        }
        try {
            var root = JSON.parseObject(Files.readString(cacheFile));
            if (root == null || root.getIntValue("version") != VERSION) {
                return result;
            }
            var packages = root.getJSONArray("packages");
            for (var i = 0; packages != null && i < packages.size(); i++) {
                var json = packages.getJSONObject(i);
                var relative = json.getString("file");
                var file = clientRoot.resolve(relative).normalize();
                var entries = new ArrayList<T>();
                var list = json.getJSONArray("entries");
                for (var k = 0; list != null && k < list.size(); k++) {
                    entries.add(decoder.decode(list.getJSONObject(k), file));
                }
                result.put(relative, new Cached<>(json.getLongValue("size"), json.getLongValue("modified"), entries));
            }
        } catch (IOException | RuntimeException unreadable) {
            result.clear();
        }
        return result;
    }

    private List<Path> packages(String extension) throws IOException {
        try (var files = Files.walk(clientRoot, 2)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName()
                            .toString()
                            .toLowerCase(Locale.ROOT)
                            .endsWith(extension))
                    .filter(path -> !ClientFiles.isAppleDouble(path))
                    .sorted()
                    .toList();
        }
    }

    private String relative(Path file) {
        return clientRoot
                .relativize(file.toAbsolutePath().normalize())
                .toString()
                .replace('\\', '/');
    }

    private long texture(String key, Runnable paint) {
        var texture = textures.get(key);
        if (texture != null) {
            return texture;
        }
        if (!missing.contains(key) && pending.add(key)) {
            painter.submit(paint);
        }
        return 0;
    }

    private void paintMesh(MeshEntry mesh, String key) {
        try {
            var cache = thumbnailFile(MESH_PICTURE_VERSION + key, mesh.packageFile());
            var rgba = readPng(cache, THUMBNAIL);
            if (rgba == null) {
                rgba = drawMesh(mesh);
                if (rgba != null) {
                    writePng(cache, rgba, THUMBNAIL);
                }
            }
            painted.add(new Painted(key, rgba, THUMBNAIL));
        } catch (IOException | RuntimeException unreadable) {
            painted.add(new Painted(key, null, THUMBNAIL));
        }
    }

    private void paintMaterial(MaterialEntry material, String key) {
        try {
            var cache = thumbnailFile(key, material.packageFile());
            var rgba = readPng(cache, SWATCH);
            if (rgba == null) {
                rgba = drawMaterial(material);
                if (rgba != null) {
                    writePng(cache, rgba, SWATCH);
                }
            }
            painted.add(new Painted(key, rgba, SWATCH));
        } catch (IOException | RuntimeException unreadable) {
            painted.add(new Painted(key, null, SWATCH));
        }
    }

    private byte @Nullable [] drawMesh(MeshEntry entry) {
        var archive = archive(entry.packageFile());
        for (var export : archive.getExportMap()) {
            if (!"StaticMesh".equals(export.getClassName())
                    || !EditingSession.shortName(entry.path()).equalsIgnoreCase(export.getObjectName())) {
                continue;
            }
            var mesh = (UStaticMesh) archive.getObjectLoader().exportObject(export);
            if (!entry.path().equalsIgnoreCase(mesh.getSourcePath())) {
                continue;
            }
            return drawMesh(mesh);
        }
        return null;
    }

    private byte @Nullable [] drawMaterial(MaterialEntry entry) {
        var archive = archive(entry.packageFile());
        for (var export : archive.getExportMap()) {
            if (!"Texture".equals(export.getClassName())
                    || !EditingSession.shortName(entry.path()).equalsIgnoreCase(export.getObjectName())) {
                continue;
            }
            if (archive.getObjectLoader().exportObject(export) instanceof UTexture texture) {
                return swatchOf(texture);
            }
        }
        return null;
    }

    private UArchive archive(Path file) {
        if (!file.equals(openFile) || openArchive == null) {
            if (openArchive != null) {
                openArchive.close();
            }
            openArchive = openWithClient(file);
            openFile = file;
        }
        return openArchive;
    }

    private Path thumbnailFile(String key, Path packageFile) throws IOException {
        var stamp = key
                + "|"
                + Files.size(packageFile)
                + "|"
                + Files.getLastModifiedTime(packageFile).toMillis();
        return directory.resolve("thumbnails").resolve(sha256(stamp).substring(0, 32) + ".png");
    }

    private UArchive openWithClient(Path file) {
        var name = file.getFileName().toString();
        var dot = name.lastIndexOf('.');
        return new UArchive(
                dot < 0 ? name : name.substring(0, dot),
                UDecryptor.decrypt(file),
                new UArchiveLoader(
                        clientRoot,
                        List.of(
                                new USearchConfig("StaticMeshes", "usx"),
                                new USearchConfig("Textures", "utx"),
                                new USearchConfig("SysTextures", "utx"))));
    }

    private static float[] bounds(UStaticMesh mesh) {
        var box = mesh.getBoundingBox();
        if (box != null && box.isValid()) {
            return new float[] {
                box.min().getX(),
                box.min().getY(),
                box.min().getZ(),
                box.max().getX(),
                box.max().getY(),
                box.max().getZ()
            };
        }
        var result = new float[] {
            Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE
        };
        var stream = mesh.getVertexStream();
        if (stream == null || stream.getVertices().length == 0) {
            return new float[6];
        }
        for (var vertex : stream.getVertices()) {
            var p = vertex.getLocation();
            result[0] = Math.min(result[0], p.getX());
            result[1] = Math.min(result[1], p.getY());
            result[2] = Math.min(result[2], p.getZ());
            result[3] = Math.max(result[3], p.getX());
            result[4] = Math.max(result[4], p.getY());
            result[5] = Math.max(result[5], p.getZ());
        }
        return result;
    }

    private static UArchive open(Path file) {
        var name = file.getFileName().toString();
        var dot = name.lastIndexOf('.');
        return new UArchive(
                dot < 0 ? name : name.substring(0, dot),
                UDecryptor.decrypt(file),
                new UArchiveLoader(file.getParent(), List.of()));
    }

    private static float @Nullable [] firstUvs(UStaticMesh mesh, int count) {
        var streams = mesh.getUvStream();
        var uvs = streams == null || streams.length == 0 ? null : streams[0].getUvs();
        if (uvs == null || uvs.length < count) {
            return null;
        }
        var result = new float[count * 2];
        for (var i = 0; i < count; i++) {
            result[i * 2] = uvs[i].getX();
            result[i * 2 + 1] = uvs[i].getY();
        }
        return result;
    }

    private static MeshThumbnail.@Nullable Cutout cutoutOf(UStaticMesh.Material slot) {
        try {
            return cutoutOf(slot.getMaterial().get(), false, 0);
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    private static MeshThumbnail.@Nullable Cutout alphaOf(UTexture texture) {
        var level = decodeLevel(texture, THUMBNAIL);
        if (level == null) {
            return null;
        }
        var alpha = new byte[level.width() * level.height()];
        for (var i = 0; i < alpha.length; i++) {
            alpha[i] = level.rgba()[i * 4 + 3];
        }
        return new MeshThumbnail.Cutout(level.width(), level.height(), alpha);
    }

    private static @Nullable Level decodeLevel(UTexture texture, int size) {
        var mips = texture.getMips();
        if (mips == null || mips.length == 0 || texture.getUSize() <= 0 || texture.getVSize() <= 0) {
            return null;
        }
        var level = 0;
        while (level + 1 < mips.length
                && Math.min(texture.getUSize() >> level + 1, texture.getVSize() >> level + 1) >= size) {
            level++;
        }
        var width = Math.max(1, texture.getUSize() >> level);
        var height = Math.max(1, texture.getVSize() >> level);
        var data = mips[level].getData();
        byte[] rgba;
        switch (texture.getFormat()) {
            case TEXF_DXT1 ->
                rgba = TexturePixels.decodeBaseRgba(
                        new Texture(TextureFormat.TEXTURE_DXT1, width, height, List.of(data), false));
            case TEXF_DXT3 ->
                rgba = TexturePixels.decodeBaseRgba(
                        new Texture(TextureFormat.TEXTURE_DXT3, width, height, List.of(data), false));
            case TEXF_DXT5 ->
                rgba = TexturePixels.decodeBaseRgba(
                        new Texture(TextureFormat.TEXTURE_DXT5, width, height, List.of(data), false));
            case TEXF_RGBA8 ->
                rgba = TexturePixels.decodeBaseRgba(
                        new Texture(TextureFormat.TEXTURE_RGBA, width, height, List.of(data), false));
            case TEXF_P8 -> {
                var palette = texture.getPalette().get();
                if (palette == null || data.length < width * height) {
                    return null;
                }
                var colors = palette.getColors();
                rgba = new byte[width * height * 4];
                for (var i = 0; i < width * height; i++) {
                    var index = data[i] & 0xFF;
                    var color = colors[index];
                    rgba[i * 4] = color.r();
                    rgba[i * 4 + 1] = color.g();
                    rgba[i * 4 + 2] = color.b();
                    rgba[i * 4 + 3] = texture.isMasked() && index == 0 ? 0 : (byte) 255;
                }
            }
            default -> {
                return null;
            }
        }
        if (rgba.length != width * height * 4) {
            return null;
        }
        return new Level(width, height, rgba);
    }

    @FunctionalInterface
    private interface Reader<T> {
        List<T> read(Path file) throws IOException;
    }

    @FunctionalInterface
    private interface Encoder<T> {
        JSONObject encode(T entry, Path root);
    }

    @FunctionalInterface
    private interface Decoder<T> {
        T decode(JSONObject json, Path file);
    }

    private static JSONObject meshJson(MeshEntry entry, Path root) {
        var json = new JSONObject();
        json.put("path", entry.path());
        var bounds = new JSONArray();
        for (var value : entry.bounds()) {
            bounds.add(value);
        }
        json.put("bounds", bounds);
        json.put("slots", new JSONArray(entry.slots()));
        return json;
    }

    private static MeshEntry meshFromJson(JSONObject json, Path file) {
        var array = json.getJSONArray("bounds");
        var bounds = new float[6];
        for (var i = 0; i < 6 && array != null && i < array.size(); i++) {
            bounds[i] = array.getFloatValue(i);
        }
        var slots = new ArrayList<String>();
        var list = json.getJSONArray("slots");
        for (var i = 0; list != null && i < list.size(); i++) {
            slots.add(list.getString(i));
        }
        return new MeshEntry(json.getString("path"), file, bounds, slots);
    }

    private static JSONObject materialJson(MaterialEntry entry, Path root) {
        var json = new JSONObject();
        json.put("path", entry.path());
        json.put("class", entry.className());
        return json;
    }

    private static MaterialEntry materialFromJson(JSONObject json, Path file) {
        return new MaterialEntry(json.getString("path"), json.getString("class"), file);
    }

    private static byte @Nullable [] readPng(Path file, int size) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            var image = ImageIO.read(file.toFile());
            if (image == null || image.getWidth() != size || image.getHeight() != size) {
                return null;
            }
            var result = new byte[size * size * 4];
            for (var y = 0; y < size; y++) {
                for (var x = 0; x < size; x++) {
                    var argb = image.getRGB(x, y);
                    var at = (y * size + x) * 4;
                    result[at] = (byte) (argb >> 16);
                    result[at + 1] = (byte) (argb >> 8);
                    result[at + 2] = (byte) argb;
                    result[at + 3] = (byte) (argb >>> 24);
                }
            }
            return result;
        } catch (IOException unreadable) {
            return null;
        }
    }

    private static void writePng(Path file, byte[] rgba, int size) throws IOException {
        var image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        for (var y = 0; y < size; y++) {
            for (var x = 0; x < size; x++) {
                var at = (y * size + x) * 4;
                image.setRGB(
                        x,
                        y,
                        (rgba[at + 3] & 0xFF) << 24
                                | (rgba[at] & 0xFF) << 16
                                | (rgba[at + 1] & 0xFF) << 8
                                | rgba[at + 2] & 0xFF);
            }
        }
        var bytes = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", bytes)) {
            throw new IOException("PNG encoding is unavailable.");
        }
        write(file, bytes.toByteArray());
    }

    private static void write(Path file, byte[] bytes) throws IOException {
        Files.createDirectories(file.getParent());
        var temporary = Files.createTempFile(file.getParent(), ".library-", ".tmp");
        try {
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String clientKey(Path client) {
        return sha256(client.toString()).substring(0, 16);
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static Thread daemon(Runnable runnable, String name) {
        var thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }
}
