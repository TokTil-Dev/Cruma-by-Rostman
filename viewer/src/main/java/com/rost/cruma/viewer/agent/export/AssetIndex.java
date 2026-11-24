package com.rost.cruma.viewer.agent.export;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.core.unreal.ClientFiles;
import com.rost.cruma.core.unreal.EffectClasses;
import com.rost.cruma.core.unreal.EffectPackages;
import com.rost.cruma.core.unreal.UArchive;
import com.rost.cruma.core.unreal.UArchiveLoader;
import com.rost.cruma.core.unreal.UDecryptor;
import com.rost.cruma.core.unreal.UObjectExport;
import com.rost.cruma.viewer.editing.MeshLibrary;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import org.jetbrains.annotations.Nullable;

final class AssetIndex {
    private static final int VERSION = 1;
    private static final int READERS = Math.clamp(Runtime.getRuntime().availableProcessors() / 2, 1, 6);

    private final Path client;
    private final Path file;
    private final Reader reader;
    private Map<String, Known> packages = new HashMap<>();
    private List<Asset> assets = List.of();

    AssetIndex(Path client, Path folder) {
        this(client, folder, AssetIndex::read);
    }

    AssetIndex(Path client, Path folder, Reader reader) {
        this.client = client.toAbsolutePath().normalize();
        file = folder.resolve("assets").resolve(key(this.client) + ".json");
        this.reader = reader;
        var cached = load();
        if (!cached.isEmpty()) {
            packages = cached;
            assets = flatten(cached);
        }
    }

    enum Kind {
        MESH("mesh"),
        TEXTURE("texture"),
        SOUND("sound"),
        EFFECT("effect");

        final String word;

        Kind(String word) {
            this.word = word;
        }

        static @Nullable Kind parse(@Nullable String word) {
            for (var kind : values()) {
                if (kind.word.equals(word)) {
                    return kind;
                }
            }
            return null;
        }
    }

    record Asset(Kind kind, String path, String type, String file, float @Nullable [] size) {
        String packageName() {
            var dot = path.indexOf('.');
            return dot < 0 ? path : path.substring(0, dot);
        }

        String name() {
            return path.substring(path.lastIndexOf('.') + 1);
        }
    }

    record Found(List<Asset> shown, int total, Map<Kind, Integer> kinds) {}

    @FunctionalInterface
    interface Reader {
        List<Asset> read(Path file, String relative) throws IOException;
    }

    private record Known(long size, long modified, List<Asset> assets) {}

    private record Packaged(Path file, String relative, long size, long modified) {}

    private record Read(Packaged file, List<Asset> assets) {}

    private record Hit(Asset asset, int rank) {}

    static List<Asset> read(Path file, String relative) throws IOException {
        var name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        var result = new ArrayList<Asset>();
        if (name.endsWith(".usx")) {
            for (var mesh : MeshLibrary.readMeshes(file)) {
                var box = mesh.bounds();
                result.add(new Asset(Kind.MESH, mesh.path(), "StaticMesh", relative, new float[] {
                    box[3] - box[0], box[4] - box[1], box[5] - box[2]
                }));
            }
        } else if (name.endsWith(".utx")) {
            for (var material : MeshLibrary.readMaterials(file)) {
                result.add(new Asset(Kind.TEXTURE, material.path(), material.className(), relative, null));
            }
        } else if (name.endsWith(".uax")) {
            try (var archive = open(file)) {
                var exports = archive.getExportMap();
                for (var export : exports) {
                    if ("Sound".equals(export.getClassName())) {
                        result.add(new Asset(
                                Kind.SOUND, path(archive.getName(), exports, export), "Sound", relative, null));
                    }
                }
            }
        } else {
            try (var archive = open(file)) {
                for (var effect : EffectClasses.list(archive)) {
                    result.add(new Asset(Kind.EFFECT, effect.path(), effect.emitters() + " emitters", relative, null));
                }
            }
        }
        return result;
    }

    synchronized int update(BooleanSupplier cancelled, IntConsumer reading, IntConsumer progress)
            throws IOException, InterruptedException {
        var files = packageFiles();
        var kept = new HashMap<String, Known>();
        var stale = new ArrayList<Packaged>();
        for (var candidate : files) {
            var known = packages.get(candidate.relative());
            if (known != null && known.size() == candidate.size() && known.modified() == candidate.modified()) {
                kept.put(candidate.relative(), known);
            } else {
                stale.add(candidate);
            }
        }
        var changed = kept.size() != packages.size() || !stale.isEmpty();
        if (!stale.isEmpty()) {
            reading.accept(stale.size());
            for (var read : readAll(stale, cancelled, progress)) {
                kept.put(
                        read.file().relative(),
                        new Known(read.file().size(), read.file().modified(), read.assets()));
            }
        }
        if (changed) {
            packages = kept;
            assets = flatten(kept);
            save();
        }
        return stale.size();
    }

    synchronized List<Asset> getAll() {
        return assets;
    }

    Found find(@Nullable String query, @Nullable Kind kind, @Nullable String packageName, int limit) {
        var words = query == null
                ? new String[0]
                : query.toLowerCase(Locale.ROOT).strip().split("\\s+");
        var whole = query == null ? "" : query.toLowerCase(Locale.ROOT).strip();
        var hits = new ArrayList<Hit>();
        var kinds = new EnumMap<Kind, Integer>(Kind.class);
        for (var asset : getAll()) {
            if (kind != null && asset.kind() != kind) {
                continue;
            }
            if (packageName != null && !asset.packageName().equalsIgnoreCase(packageName)) {
                continue;
            }
            var rank = rank(asset, whole, words);
            if (rank >= 0) {
                hits.add(new Hit(asset, rank));
                kinds.merge(asset.kind(), 1, Integer::sum);
            }
        }
        hits.sort(Comparator.comparingInt(Hit::rank)
                .thenComparingInt(hit -> hit.asset().name().length())
                .thenComparing(hit -> hit.asset().path(), String.CASE_INSENSITIVE_ORDER));
        var shown = new ArrayList<Asset>(Math.min(limit, hits.size()));
        for (var hit : hits.subList(0, Math.min(limit, hits.size()))) {
            shown.add(hit.asset());
        }
        return new Found(List.copyOf(shown), hits.size(), kinds);
    }

    private List<Read> readAll(List<Packaged> files, BooleanSupplier cancelled, IntConsumer progress)
            throws InterruptedException {
        var result = new ArrayList<Read>(files.size());
        try (var pool = Executors.newFixedThreadPool(READERS, runnable -> {
            var thread = new Thread(runnable, "cruma-asset-index");
            thread.setDaemon(true);
            return thread;
        })) {
            var done = new ExecutorCompletionService<Read>(pool);
            for (var candidate : files) {
                Callable<Read> work =
                        () -> new Read(candidate, cancelled.getAsBoolean() ? List.of() : readOne(candidate));
                done.submit(work);
            }
            for (var index = 0; index < files.size(); index++) {
                result.add(done.take().get());
                progress.accept(index + 1);
            }
        } catch (ExecutionException impossible) {
            throw new IllegalStateException(impossible.getCause());
        }
        if (cancelled.getAsBoolean()) {
            throw new InterruptedException("Stopped");
        }
        return result;
    }

    private List<Asset> readOne(Packaged candidate) {
        try {
            return reader.read(candidate.file(), candidate.relative());
        } catch (IOException | RuntimeException unreadable) {
            return List.of();
        }
    }

    private List<Packaged> packageFiles() throws IOException {
        var effects = new HashSet<>(EffectPackages.find(client));
        try (var files = Files.walk(client, 2)) {
            var found = new ArrayList<Packaged>();
            for (var path : files.toList()) {
                if ((holdsAssets(path) || effects.contains(path))
                        && !ClientFiles.isAppleDouble(path)
                        && Files.isRegularFile(path)) {
                    found.add(new Packaged(
                            path,
                            client.relativize(path).toString().replace('\\', '/'),
                            Files.size(path),
                            Files.getLastModifiedTime(path).toMillis()));
                }
            }
            return found;
        }
    }

    private Map<String, Known> load() {
        var result = new HashMap<String, Known>();
        if (!Files.isRegularFile(file)) {
            return result;
        }
        try {
            var root = JSON.parseObject(Files.readString(file));
            if (root == null || root.getIntValue("version") != VERSION) {
                return result;
            }
            var list = root.getJSONArray("packages");
            for (var index = 0; list != null && index < list.size(); index++) {
                var item = list.getJSONObject(index);
                var relative = item.getString("file");
                var kept = new ArrayList<Asset>();
                var entries = item.getJSONArray("assets");
                for (var at = 0; entries != null && at < entries.size(); at++) {
                    kept.add(decode(entries.getJSONArray(at), relative));
                }
                result.put(relative, new Known(item.getLongValue("size"), item.getLongValue("modified"), kept));
            }
        } catch (IOException | RuntimeException unreadable) {
            result.clear();
        }
        return result;
    }

    private void save() throws IOException {
        var list = new JSONArray();
        for (var entry : packages.entrySet()) {
            var item = new JSONObject();
            item.put("file", entry.getKey());
            item.put("size", entry.getValue().size());
            item.put("modified", entry.getValue().modified());
            var assets = new JSONArray();
            for (var asset : entry.getValue().assets()) {
                assets.add(encode(asset));
            }
            item.put("assets", assets);
            list.add(item);
        }
        var root = new JSONObject();
        root.put("version", VERSION);
        root.put("client", client.toString());
        root.put("packages", list);
        Files.createDirectories(file.getParent());
        var temporary = Files.createTempFile(file.getParent(), ".assets-", ".tmp");
        try {
            Files.write(temporary, JSON.toJSONString(root).getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static int rank(Asset asset, String whole, String[] words) {
        var path = asset.path().toLowerCase(Locale.ROOT);
        for (var word : words) {
            if (!path.contains(word)) {
                return -1;
            }
        }
        var name = asset.name().toLowerCase(Locale.ROOT);
        if (name.equals(whole) || path.equals(whole)) {
            return 0;
        }
        if (name.startsWith(whole)) {
            return 1;
        }
        for (var word : words) {
            if (!name.contains(word)) {
                return 3;
            }
        }
        return 2;
    }

    private static boolean holdsAssets(Path path) {
        var name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".usx") || name.endsWith(".utx") || name.endsWith(".uax");
    }

    private static UArchive open(Path file) {
        var name = file.getFileName().toString();
        return new UArchive(
                name.substring(0, name.lastIndexOf('.')),
                UDecryptor.decrypt(file),
                new UArchiveLoader(file.getParent(), List.of()));
    }

    private static String path(String packageName, List<UObjectExport> exports, UObjectExport export) {
        var parts = new ArrayDeque<String>();
        parts.addFirst(export.getObjectName());
        for (var outer = export.getPackageIndex();
                outer > 0 && outer <= exports.size();
                outer = exports.get(outer - 1).getPackageIndex()) {
            parts.addFirst(exports.get(outer - 1).getObjectName());
        }
        return packageName + "." + String.join(".", parts);
    }

    private static List<Asset> flatten(Map<String, Known> packages) {
        var result = new ArrayList<Asset>();
        for (var known : packages.values()) {
            result.addAll(known.assets());
        }
        return List.copyOf(result);
    }

    private static Asset decode(JSONArray entry, String relative) {
        var kind = Kind.parse(entry.getString(0));
        if (kind == null) {
            throw new IllegalArgumentException("Unknown kind of asset");
        }
        float[] size = null;
        if (entry.size() > 3) {
            var box = entry.getJSONArray(3);
            size = new float[] {box.getFloatValue(0), box.getFloatValue(1), box.getFloatValue(2)};
        }
        return new Asset(kind, entry.getString(1), entry.getString(2), relative, size);
    }

    private static JSONArray encode(Asset asset) {
        var entry = JSONArray.of(asset.kind().word, asset.path(), asset.type());
        if (asset.size() != null) {
            entry.add(JSONArray.of(asset.size()[0], asset.size()[1], asset.size()[2]));
        }
        return entry;
    }

    private static String key(Path client) {
        try {
            var hash = MessageDigest.getInstance("SHA-256")
                    .digest(client.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
