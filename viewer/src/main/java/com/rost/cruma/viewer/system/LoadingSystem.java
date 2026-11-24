package com.rost.cruma.viewer.system;

import com.rost.cruma.core.audio.SoundClipCache;
import com.rost.cruma.core.texture.DxtMipCodec.Options;
import com.rost.cruma.core.unreal.DecryptedPackages;
import com.rost.cruma.core.world.DxtChainCache;
import com.rost.cruma.core.world.UMap;
import com.rost.cruma.core.world.UnrealLoader;
import com.rost.cruma.core.world.UnrealLoader.LoadTelemetry;
import com.rost.cruma.core.world.UnrealLoader.StaticMeshStreamTelemetry;
import com.rost.cruma.core.world.WorldMapCatalog;
import com.rost.cruma.core.world.WorldTile;
import com.rost.cruma.viewer.application.UiContext;
import com.rost.cruma.viewer.application.WindowContext;
import com.rost.cruma.viewer.application.WindowContext.Tile;
import com.rost.cruma.viewer.config.Config.Viewer.World;
import com.rost.cruma.viewer.render.api.SceneUploadSink;
import com.rost.cruma.viewer.render.api.SceneUploadSink.PreparedMap;
import com.rost.cruma.viewer.render.api.WorldLoadProgress.WorldLoadStage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BooleanSupplier;
import java.util.function.IntFunction;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class LoadingSystem implements System {
    private static final int MEMORY_LOG_INTERVAL = 10;
    private static final int TELEMETRY_LOG_INTERVAL = 20;
    private static final int CACHE_TRIM_INTERVAL = 20;
    private static final int DRAW_LIST_CELL_SIZE = 32768;
    private static final int QUEUE_CAPACITY = 3;
    private static final long WAITING_MAP_BYTES = 256L << 20;
    private static final long IDLE_PACKAGE_BYTES = 1L << 30;
    private static final long PUBLISH_NANOS = 10_000_000L;

    private static final Logger log = LogManager.getLogger(LoadingSystem.class);
    private final int parserWorkers;
    private final int parserLookahead;

    private final World worldConfig;
    private final SceneUploadSink renderer;
    private final WindowContext windowContext;
    private final UiContext uiContext;
    private final DxtChainCache dxtChains;
    private final DecryptedPackages packages;
    private final SoundClipCache soundClips;
    private final List<MapName> maps;
    private final ThreadLocal<ParserContext> parserContext = new ThreadLocal<>();
    private final Set<String> published = new HashSet<>();
    private final Set<String> mergedMaps = new HashSet<>();
    private final Set<String> viewedMaps = new HashSet<>();
    private final Set<String> mergingMaps = new HashSet<>();
    private boolean worldShown;
    private boolean stopped;
    private Pass pass;

    public LoadingSystem(
            World worldConfig,
            List<String> mapNames,
            SceneUploadSink renderer,
            WindowContext windowContext,
            float initialCameraX,
            float initialCameraY,
            UiContext uiContext) {
        this(worldConfig, mapNames, renderer, windowContext, initialCameraX, initialCameraY, uiContext, false);
    }

    public LoadingSystem(
            World worldConfig,
            List<String> mapNames,
            SceneUploadSink renderer,
            WindowContext windowContext,
            float initialCameraX,
            float initialCameraY,
            UiContext uiContext,
            boolean editingPrepared) {
        this.worldConfig = worldConfig;
        this.parserWorkers = worldConfig.parserWorkers();
        this.parserLookahead = parserWorkers * 2;
        this.renderer = renderer;
        this.windowContext = windowContext;
        this.uiContext = uiContext;
        var dxtOptions = new Options(
                worldConfig.rendering().dxtMipCodecEnabled(),
                worldConfig.rendering().dxtMipMinSize());
        this.dxtChains =
                new DxtChainCache(dxtOptions, worldConfig.rendering().dxtMipCachePath(), worldConfig.clientPath());
        this.packages = new DecryptedPackages(IDLE_PACKAGE_BYTES);
        this.soundClips = new SoundClipCache();
        this.maps = sortMaps(mapNames, worldConfig);
        this.pass = new Pass(editingPrepared, prioritizeMaps(maps, initialCameraX, initialCameraY));
        uiContext.rendering.loading.tilesTotal = maps.size();
        uiContext.rendering.loading.tilesLoaded = 0;
        uiContext.rendering.loading.tile = "";
        uiContext.rendering.loading.stage = WorldLoadStage.INDEXING;
        uiContext.rendering.loading.startedNanos = 0L;
        uiContext.rendering.loading.etaSeconds = -1L;
        uiContext.rendering.loading.active = true;
    }

    public record MapName(int x, int y, @Nullable String named) {
        public MapName(int x, int y) {
            this(x, y, null);
        }

        public @NotNull String toString() {
            return named != null ? named : x + "_" + y;
        }
    }

    private record LoadedMap(
            MapName name, @Nullable UMap map, @Nullable PreparedMap prepared) {}

    static <T> void streamOrdered(
            int itemCount,
            int parallelism,
            int lookahead,
            ExecutorService executor,
            IntFunction<T> loader,
            InterruptibleConsumer<T> consumer,
            BooleanSupplier stopRequested)
            throws InterruptedException {
        if (parallelism < 1) {
            throw new IllegalArgumentException("parallelism must be positive");
        }
        if (lookahead < parallelism) {
            throw new IllegalArgumentException("lookahead must cover every worker");
        }
        var pending = new ArrayList<Future<T>>(itemCount);
        for (var index = 0; index < itemCount; index++) {
            if (index < lookahead) {
                var submittedIndex = index;
                pending.add(executor.submit(() -> loader.apply(submittedIndex)));
            } else {
                pending.add(null);
            }
        }
        for (var index = 0; index < itemCount; index++) {
            if (stopRequested.getAsBoolean()) {
                return;
            }
            var result = awaitResult(pending.get(index));
            pending.set(index, null);
            consumer.accept(result);
            var nextIndex = index + lookahead;
            if (nextIndex < itemCount) {
                var submittedIndex = nextIndex;
                pending.set(nextIndex, executor.submit(() -> loader.apply(submittedIndex)));
            }
        }
    }

    static long estimateRemainingSeconds(int loaded, int total, long elapsedNanos) {
        if (loaded < 0 || total < 0 || elapsedNanos < 0L) {
            throw new IllegalArgumentException("load ETA inputs cannot be negative");
        }
        if (loaded >= total) {
            return 0L;
        }
        if (elapsedNanos == 0L || loaded < 3) {
            return -1L;
        }
        var elapsedSeconds = elapsedNanos / 1_000_000_000.0;
        var remainingTiles = Math.max(0, total - loaded);
        var estimate = elapsedSeconds / loaded * remainingTiles;
        if (!Double.isFinite(estimate) || estimate <= 0.0) {
            return -1L;
        }
        return Math.min(86_400L, Math.max(1L, (long) Math.ceil(estimate)));
    }

    static String dominantMeasuredWork(long decodeNanos, long prepareNanos, long uploadNanos, long topologyNanos) {
        var name = "decode";
        var maximum = decodeNanos;
        if (prepareNanos > maximum) {
            name = "prepare";
            maximum = prepareNanos;
        }
        if (uploadNanos > maximum) {
            name = "upload";
            maximum = uploadNanos;
        }
        if (topologyNanos > maximum) {
            name = "topology";
        }
        return name;
    }

    static LoadTelemetry aggregateLoaderTelemetry(List<UnrealLoader> loaders) {
        var attempts = 0;
        var loaded = 0;
        var total = 0L;
        var packages = 0L;
        var dxt = 0L;
        var terrain = 0L;
        var lighting = 0L;
        var staticMesh = 0L;
        var occluder = 0L;
        var index = 0L;
        var merge = 0L;
        var bsp = 0L;
        var effects = 0L;
        var slowestMap = "";
        var slowest = 0L;
        for (var loader : loaders) {
            var telemetry = loader.getLoadTelemetry();
            attempts += telemetry.attempts();
            loaded += telemetry.loaded();
            total += telemetry.totalNanos();
            packages += telemetry.packageNanos();
            dxt += telemetry.dxtNanos();
            terrain += telemetry.terrainNanos();
            lighting += telemetry.lightingNanos();
            staticMesh += telemetry.staticMeshNanos();
            occluder += telemetry.staticMeshOccluderNanos();
            index += telemetry.staticMeshIndexNanos();
            merge += telemetry.staticMeshMergeNanos();
            bsp += telemetry.bspNanos();
            effects += telemetry.effectsNanos();
            if (telemetry.slowestMapNanos() > slowest) {
                slowest = telemetry.slowestMapNanos();
                slowestMap = telemetry.slowestMap();
            }
        }
        return new LoadTelemetry(
                attempts,
                loaded,
                total,
                packages,
                dxt,
                terrain,
                lighting,
                staticMesh,
                occluder,
                index,
                merge,
                bsp,
                effects,
                slowestMap,
                slowest);
    }

    public static List<MapName> sortMaps(List<String> mapNames, World worldConfig) {
        var named = WorldMapCatalog.findStandaloneMap(mapNames);
        if (named != null) {
            return List.of(new MapName(0, 0, named));
        }
        Set<String> ignoredMaps = new LinkedHashSet<>();
        Set<String> duplicateMaps = new LinkedHashSet<>();
        Set<MapName> seenMaps = new HashSet<>();
        List<MapName> sortedMaps = new ArrayList<>(mapNames.size());
        for (var mapName : mapNames) {
            var coords = mapName.split("_");
            if (coords.length != 2) {
                throw new IllegalArgumentException("Invalid map name: " + mapName);
            }
            int mapX, mapY;
            try {
                mapX = Integer.parseInt(coords[0]);
                mapY = Integer.parseInt(coords[1]);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid map name: " + mapName, e);
            }
            if (mapX < worldConfig.minXTile()
                    || mapX > worldConfig.maxXTile()
                    || mapY < worldConfig.minYTile()
                    || mapY > worldConfig.maxYTile()) {
                ignoredMaps.add(mapName);
                continue;
            }
            var map = new MapName(mapX, mapY);
            if (!seenMaps.add(map)) {
                duplicateMaps.add(mapName);
                continue;
            }
            sortedMaps.add(map);
        }
        if (!ignoredMaps.isEmpty()) {
            var sample = ignoredMaps.stream().limit(8).toList();
            log.debug(
                    "Ignored {} map(s) outside configured bounds{}: {}",
                    ignoredMaps.size(),
                    ignoredMaps.size() > sample.size() ? " (first 8)" : "",
                    sample);
        }
        if (!duplicateMaps.isEmpty()) {
            log.warn("Ignored maps as they are listed more than once: {}", duplicateMaps);
        }
        sortedMaps.sort(Comparator.comparingInt(MapName::x).thenComparingInt(MapName::y));
        return sortedMaps;
    }

    static List<MapName> prioritizeMaps(List<MapName> maps, float initialCameraX, float initialCameraY) {
        var cameraTileX = (int) Math.floor(initialCameraX / DRAW_LIST_CELL_SIZE) + 20;
        var cameraTileY = (int) Math.floor(initialCameraY / DRAW_LIST_CELL_SIZE) + 18;
        var prioritized = new ArrayList<>(maps);
        prioritized.sort(Comparator.comparingLong((MapName map) -> tileDistanceSquared(map, cameraTileX, cameraTileY))
                .thenComparingInt(MapName::x)
                .thenComparingInt(MapName::y));
        return prioritized;
    }

    @Override
    public void start() {
        pass.start();
    }

    @Override
    public void frameBegin(long frameTime) {
        if (!stopped) {
            pass.frameBegin();
        }
    }

    @Override
    public void stop(WindowContext windowContext) {
        stopped = true;
        pass.stop();
        dxtChains.clear();
        renderer.clearNamedTextureCache();
    }

    public int reload(boolean editing, float cameraX, float cameraY, boolean everyMap) {
        if (stopped) {
            return 0;
        }
        pass.finishPublishing();
        pass.stop();
        var again = new ArrayList<MapName>();
        for (var map : maps) {
            var name = map.toString();
            var differs = editing
                    ? mergedMaps.contains(name)
                    : !viewedMaps.contains(name) || mergingMaps.contains(name) && !mergedMaps.contains(name);
            if (everyMap || !worldShown || !published.contains(name) || differs) {
                again.add(map);
            }
        }
        log.info("Loading {} of {} tiles again {}", again.size(), maps.size(), editing ? "for Editing" : "for viewing");
        if (again.isEmpty() && worldShown) {
            uiContext.rendering.loading.reloading = false;
            return 0;
        }
        pass = new Pass(editing, prioritizeMaps(again, cameraX, cameraY));
        if (worldShown) {
            uiContext.rendering.loading.reloading = !again.isEmpty();
            uiContext.rendering.loading.reloadingForEditing = editing;
            uiContext.rendering.loading.tilesReloaded = 0;
            uiContext.rendering.loading.tilesToReload = again.size();
        } else {
            uiContext.rendering.loading.tilesLoaded = 0;
            uiContext.rendering.loading.stage = WorldLoadStage.INDEXING;
            uiContext.rendering.loading.etaSeconds = -1L;
        }
        pass.start();
        return again.size();
    }

    public Set<String> getLoadedMaps() {
        return Set.copyOf(published);
    }

    private static boolean hasMergedProps(UMap map) {
        for (var entity : map.entities()) {
            if (!entity.getSourceParts().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private final class Pass {
        private final boolean editing;
        private final List<UnrealLoader> unrealLoaders;
        private final UnrealLoader unrealLoader;
        private final List<MapName> loadOrder;
        private final ArrayBlockingQueue<LoadedMap> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
        private final LoadBudget budget = new LoadBudget(WAITING_MAP_BYTES);
        private boolean behindWorld;

        private volatile boolean stopRequested;
        private volatile boolean producerDone;
        private volatile @Nullable Throwable producerFailure;
        private volatile boolean preScanComplete;
        private @Nullable Thread producer;
        private volatile @Nullable ExecutorService parserExecutor;

        private int consumed;
        private boolean loadComplete;
        private boolean finalizationAnnounced;
        private boolean finalizationPresentationPassed;
        private long postLoadFinalizationStarted;
        private long loadStartedNanos;
        private volatile long preScanNanos;
        private volatile long preScanCompletedAtNanos;
        private final LongAdder producerDecodeNanos = new LongAdder();
        private final LongAdder producerPrepareNanos = new LongAdder();
        private final LongAdder producerQueueWaitNanos = new LongAdder();
        private long enqueueNanos;
        private @Nullable LoadedMap publishing;

        private Pass(boolean editing, List<MapName> loadOrder) {
            this.editing = editing;
            this.loadOrder = loadOrder;
            var loaders = new ArrayList<UnrealLoader>(parserWorkers);
            for (var worker = 0; worker < parserWorkers; worker++) {
                var loader = new UnrealLoader(
                        worldConfig.clientPath(),
                        dxtChains,
                        soundClips,
                        true,
                        worldConfig.rendering().textureQuality(),
                        packages);
                loader.setImpostorDistanceMultiplier(worldConfig.rendering().impostorDistanceMultiplier());
                loader.worldVariant(worldConfig.variant());
                loader.setDecorationsNearCamera(true);
                loaders.add(loader);
            }
            this.unrealLoaders = List.copyOf(loaders);
            this.unrealLoader = unrealLoaders.getFirst();
        }

        private void start() {
            behindWorld = worldShown;
            loadStartedNanos = java.lang.System.nanoTime();
            if (!behindWorld) {
                uiContext.rendering.loading.startedNanos = loadStartedNanos;
            }
            var thread = new Thread(this::produce, "world-loader-coordinator");
            thread.setDaemon(true);
            thread.start();
            this.producer = thread;
        }

        private void frameBegin() {
            if (loadComplete || stopRequested) {
                return;
            }
            if (producerDone && producerFailure != null) {
                throw new IllegalStateException("World loading failed", producerFailure);
            }
            if (!behindWorld && preScanComplete && uiContext.rendering.loading.stage == WorldLoadStage.INDEXING) {
                uiContext.rendering.loading.stage = WorldLoadStage.ASSEMBLING;
            }
            var result = queue.peek();
            if (result != null && publish(result, PUBLISH_NANOS)) {
                queue.remove();
                budget.published(
                        result.prepared() == null ? 0L : result.prepared().getRetainedBytes());
                consumed++;
                if (behindWorld) {
                    uiContext.rendering.loading.tilesReloaded = consumed;
                } else {
                    uiContext.rendering.loading.tilesLoaded = consumed;
                    uiContext.rendering.loading.tile = result.name().toString();
                    uiContext.rendering.loading.stage = WorldLoadStage.ASSEMBLING;
                }
                if (consumed % CACHE_TRIM_INTERVAL == 0) {
                    renderer.clearCache();
                }
                if (consumed % TELEMETRY_LOG_INTERVAL == 0) {
                    logLoadTelemetry("progress");
                }
            }
            if (!behindWorld) {
                refreshEta(java.lang.System.nanoTime());
            }
            if (producerDone && queue.isEmpty()) {
                if (behindWorld) {
                    finalizeLoad();
                    return;
                }
                if (!finalizationAnnounced) {
                    finalizationAnnounced = true;
                    uiContext.rendering.loading.stage = WorldLoadStage.FINALIZING;
                    uiContext.rendering.loading.etaSeconds = 0L;
                    return;
                }
                if (!finalizationPresentationPassed) {
                    finalizationPresentationPassed = true;
                    return;
                }
                finalizeLoad();
            }
        }

        private boolean publish(LoadedMap result, long nanos) {
            var map = result.map();
            if (map == null) {
                return true;
            }
            var mapName = result.name();
            var name = mapName.toString();
            var enqueueStarted = java.lang.System.nanoTime();
            var added = !published.contains(name);
            if (added) {
                var whole = renderer.enqueueMapPart(map, result.prepared(), behindWorld ? Long.MAX_VALUE : nanos);
                enqueueNanos = Math.addExact(enqueueNanos, Math.max(0L, java.lang.System.nanoTime() - enqueueStarted));
                publishing = whole ? null : result;
                if (!whole) {
                    return false;
                }
                published.add(name);
                var position = map.position();
                var centerX = position.getX() + 32768f / 2;
                var centerY = position.getY() + 32768f / 2;
                var tile = WorldTile.ofMap(mapName.toString(), position.getX(), position.getY());
                windowContext.tiles.add(new Tile(tile.x(), tile.y(), centerX, centerY));
            } else {
                renderer.replaceMap(map, result.prepared());
                enqueueNanos = Math.addExact(enqueueNanos, Math.max(0L, java.lang.System.nanoTime() - enqueueStarted));
            }
            var merged = hasMergedProps(map);
            if (merged) {
                mergedMaps.add(name);
            } else {
                mergedMaps.remove(name);
            }
            if (!editing) {
                viewedMaps.add(name);
                if (merged) {
                    mergingMaps.add(name);
                } else {
                    mergingMaps.remove(name);
                }
            }
            return true;
        }

        private void finishPublishing() {
            var result = publishing;
            if (result != null) {
                publish(result, Long.MAX_VALUE);
            }
        }

        private void stop() {
            stopRequested = true;
            budget.stop();
            var thread = producer;
            if (thread != null) {
                thread.interrupt();
                shutdownParserExecutor(true);
                try {
                    thread.join(10_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            for (var waiting = queue.poll(); waiting != null; waiting = queue.poll()) {
                if (waiting.prepared() != null) {
                    waiting.prepared().release();
                }
            }
        }

        private void produce() {
            try {
                var names = new ArrayList<String>(maps.size());
                for (var mapName : maps) {
                    names.add(mapName.toString());
                }
                var rendering = worldConfig.rendering();
                var preScanStarted = java.lang.System.nanoTime();
                if (!editing) {
                    unrealLoader.prepareOneOffMerge(names, rendering.oneOffMergeVertexBudget(), true);
                    for (var worker = 1; worker < unrealLoaders.size(); worker++) {
                        unrealLoaders.get(worker).reuseOneOffMergePlan(unrealLoader);
                    }
                }
                preScanNanos = Math.max(0L, java.lang.System.nanoTime() - preScanStarted);
                if (stopRequested) {
                    return;
                }
                preScanCompletedAtNanos = java.lang.System.nanoTime();
                preScanComplete = true;

                var executor = createParserExecutor();
                parserExecutor = executor;
                var produced = new AtomicInteger();
                streamOrdered(
                        loadOrder.size(),
                        parserWorkers,
                        parserLookahead,
                        executor,
                        index -> decodeMap(index, loadOrder.get(index)),
                        result -> {
                            var queueStarted = java.lang.System.nanoTime();
                            queue.put(result);
                            producerQueueWaitNanos.add(Math.max(0L, java.lang.System.nanoTime() - queueStarted));
                            var count = produced.incrementAndGet();
                            if (count % MEMORY_LOG_INTERVAL == 0) {
                                logMemory(
                                        count,
                                        loadOrder.size(),
                                        result.name().toString(),
                                        unrealLoaders,
                                        renderer,
                                        dxtChains);
                            }
                        },
                        () -> stopRequested);
            } catch (InterruptedException e) {
                if (!stopRequested) {
                    producerFailure = e;
                }
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                if (stopRequested) {
                    log.debug("World load aborted by shutdown");
                } else {
                    producerFailure = t;
                }
            } finally {
                shutdownParserExecutor(stopRequested || producerFailure != null);
                producerDone = true;
            }
        }

        private ExecutorService createParserExecutor() {
            var nextWorker = new AtomicInteger();
            return Executors.newFixedThreadPool(parserWorkers, task -> {
                var worker = nextWorker.getAndIncrement();
                var context = new ParserContext(unrealLoaders.get(worker));
                var thread = new Thread(
                        () -> {
                            parserContext.set(context);
                            try {
                                task.run();
                            } finally {
                                context.loader.trimCaches();
                                parserContext.remove();
                            }
                        },
                        "world-parser-" + worker);
                thread.setDaemon(true);
                return thread;
            });
        }

        private LoadedMap decodeMap(int index, MapName mapName) {
            var context = parserContext.get();
            if (context == null) {
                throw new IllegalStateException("World decode ran outside a parser worker");
            }
            try {
                budget.awaitRoom(index);
            } catch (InterruptedException stopping) {
                Thread.currentThread().interrupt();
                return new LoadedMap(mapName, null, null);
            }
            var loader = context.loader;
            UMap map = null;
            var decodeStarted = java.lang.System.nanoTime();
            try {
                map = loader.loadMap(mapName.toString());
            } catch (Exception e) {
                if (!stopRequested && !Thread.currentThread().isInterrupted()) {
                    log.error("Failed to load map {}", mapName, e);
                }
            } finally {
                producerDecodeNanos.add(Math.max(0L, java.lang.System.nanoTime() - decodeStarted));
                loader.trimCaches();
            }
            var prepared = map == null ? null : prepareMap(mapName, map);
            if (prepared != null) {
                budget.finished(prepared.getRetainedBytes());
            }
            return new LoadedMap(mapName, map, prepared);
        }

        private @Nullable PreparedMap prepareMap(MapName mapName, UMap map) {
            var prepareStarted = java.lang.System.nanoTime();
            try {
                return renderer.prepareMap(map);
            } catch (RuntimeException e) {
                if (!stopRequested && !Thread.currentThread().isInterrupted()) {
                    log.warn("Preparing map {} on the loader failed; the render thread prepares it", mapName, e);
                }
                return null;
            } finally {
                producerPrepareNanos.add(Math.max(0L, java.lang.System.nanoTime() - prepareStarted));
            }
        }

        private void shutdownParserExecutor(boolean immediately) {
            var executor = parserExecutor;
            if (executor == null) {
                return;
            }
            if (immediately) {
                executor.shutdownNow();
            } else {
                executor.shutdown();
            }
            try {
                executor.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private void refreshEta(long now) {
            if (preScanCompletedAtNanos == 0L) {
                uiContext.rendering.loading.etaSeconds = -1L;
                return;
            }
            var elapsed = Math.max(0L, now - preScanCompletedAtNanos);
            var estimate = estimateRemainingSeconds(consumed, loadOrder.size(), elapsed);
            if (estimate == 0L && !producerDone && consumed >= loadOrder.size()) {
                estimate = -1L;
            }
            uiContext.rendering.loading.etaSeconds = estimate;
        }

        private void finalizeLoad() {
            postLoadFinalizationStarted = java.lang.System.nanoTime();
            log.debug("Post-load finalization started for {} tiles", consumed);
            var phase = java.lang.System.nanoTime();
            renderer.clearCache();
            dxtChains.clear();
            packages.clearIdle();
            SoftHeapLimit.collectSoon();
            logFinalizationPhase("cache cleanup", phase);
            phase = java.lang.System.nanoTime();
            renderer.computeBakePlan();
            logFinalizationPhase("compute bake plan", phase);
            phase = java.lang.System.nanoTime();
            renderer.applyBake();
            logFinalizationPhase("apply instance bake", phase);
            phase = java.lang.System.nanoTime();
            renderer.buildDrawList(DRAW_LIST_CELL_SIZE);
            logFinalizationPhase("build draw list", phase);
            logDxtMipCensus();
            logMemory(consumed, loadOrder.size(), "final", unrealLoaders, renderer, dxtChains);
            logLoadTelemetry("complete");
            var completedAt = java.lang.System.nanoTime();
            var finalizationMillis = (completedAt - postLoadFinalizationStarted) / 1_000_000;
            var loadMillis = loadStartedNanos == 0L ? 0L : Math.max(0L, completedAt - loadStartedNanos) / 1_000_000;
            if (behindWorld) {
                log.info(
                        "Loaded {}/{} tiles again {} in {} ms (finalization {} ms)",
                        consumed,
                        loadOrder.size(),
                        editing ? "for Editing" : "for viewing",
                        loadMillis,
                        finalizationMillis);
            } else {
                log.info(
                        "Ready {}/{} tiles in {} ms (finalization {} ms)",
                        consumed,
                        loadOrder.size(),
                        loadMillis,
                        finalizationMillis);
            }
            logLoadBreakdown(finalizationMillis);
            log.info("{} ambient clips decoded, {} MiB PCM", soundClips.size(), soundClips.getBytes() >> 20);
            if (behindWorld) {
                uiContext.rendering.loading.reloading = false;
            } else {
                uiContext.rendering.loading.stage = WorldLoadStage.READY;
                uiContext.rendering.loading.etaSeconds = 0L;
                uiContext.rendering.loading.active = false;
                worldShown = true;
            }
            loadComplete = true;
        }

        private void logLoadBreakdown(long finalizationMillis) {
            var loader = aggregateLoaderTelemetry(unrealLoaders);
            var backend = renderer.getLoadTelemetry();
            log.info(
                    "Load breakdown: preScan={}ms | parser {}w cpu={}ms "
                            + "(pkg={} dxt={} terrain={} light={} mesh={} bsp={} fx={} other={}) "
                            + "prepare={}ms wait={}ms | api enqueue={}ms upload={}ms topology={}ms "
                            + "finalization={}ms | dominant={} slowest={}({} ms)",
                    toMillis(preScanNanos),
                    parserWorkers,
                    toMillis(loader.totalNanos()),
                    toMillis(loader.packageNanos()),
                    toMillis(loader.dxtNanos()),
                    toMillis(loader.terrainNanos()),
                    toMillis(loader.lightingNanos()),
                    toMillis(loader.staticMeshNanos()),
                    toMillis(loader.bspNanos()),
                    toMillis(loader.effectsNanos()),
                    toMillis(loader.otherNanos()),
                    toMillis(producerPrepareNanos.sum()),
                    toMillis(producerQueueWaitNanos.sum()),
                    toMillis(enqueueNanos),
                    toMillis(backend.uploadNanos()),
                    toMillis(backend.topologyNanos()),
                    finalizationMillis,
                    dominantMeasuredWork(
                            loader.totalNanos(),
                            producerPrepareNanos.sum(),
                            backend.uploadNanos(),
                            backend.topologyNanos()),
                    loader.slowestMap().isBlank() ? "-" : loader.slowestMap(),
                    toMillis(loader.slowestMapNanos()));
        }

        private void logDxtMipCensus() {
            var census = dxtChains.getCensus();
            if (census.textures() == 0) {
                return;
            }
            log.debug(
                    "Client authored mips: textures={} complete={} partial={} baseOnly={} malformed={} "
                            + "levels={}/{} ({}%) bytes={}/{}MiB ({}%); only missing tails are synthesized",
                    census.textures(),
                    census.complete(),
                    census.partial(),
                    census.baseOnly(),
                    census.malformed(),
                    census.authoredLevels(),
                    census.expectedLevels(),
                    percentOf(census.authoredLevels(), census.expectedLevels()),
                    formatMiB(census.authoredBytes()),
                    formatMiB(census.expectedBytes()),
                    percentOf(census.authoredBytes(), census.expectedBytes()));
        }

        private void logLoadTelemetry(String state) {
            if (!log.isDebugEnabled()) {
                return;
            }
            var loader = aggregateLoaderTelemetry(unrealLoaders);
            var backend = renderer.getLoadTelemetry();
            var wallNanos = loadStartedNanos == 0L ? 0L : Math.max(0L, java.lang.System.nanoTime() - loadStartedNanos);
            log.debug(
                    "Load {} {}/{}: wall={}ms | parserCpu={}ms/{}w "
                            + "[pkg={} dxt={} terrain={} light={} mesh={} "
                            + "(occluder={} index={} merge={} actor={}) bsp={} fx={} other={}] "
                            + "slowest={}({} ms) | pipeline preScan={}ms producerDecode={}ms producerPrepare={}ms "
                            + "producerWait={}ms enqueue={}ms | bgfx upload={}maps/{}ms "
                            + "staging={}copies/{}ms/{}MiB index={}submits/{}->{}MiB "
                            + "instance={}submits/{}MiB "
                            + "pools=geometry:{}c {}/{}MiB instance:{}c {}/{}MiB "
                            + "topology={}builds/{}ms/{}MiB",
                    state,
                    consumed,
                    loadOrder.size(),
                    toMillis(wallNanos),
                    toMillis(loader.totalNanos()),
                    parserWorkers,
                    toMillis(loader.packageNanos()),
                    toMillis(loader.dxtNanos()),
                    toMillis(loader.terrainNanos()),
                    toMillis(loader.lightingNanos()),
                    toMillis(loader.staticMeshNanos()),
                    toMillis(loader.staticMeshOccluderNanos()),
                    toMillis(loader.staticMeshIndexNanos()),
                    toMillis(loader.staticMeshMergeNanos()),
                    toMillis(loader.staticMeshOtherNanos()),
                    toMillis(loader.bspNanos()),
                    toMillis(loader.effectsNanos()),
                    toMillis(loader.otherNanos()),
                    loader.slowestMap().isBlank() ? "-" : loader.slowestMap(),
                    toMillis(loader.slowestMapNanos()),
                    toMillis(preScanNanos),
                    toMillis(producerDecodeNanos.sum()),
                    toMillis(producerPrepareNanos.sum()),
                    toMillis(producerQueueWaitNanos.sum()),
                    toMillis(enqueueNanos),
                    backend.uploadedMaps(),
                    toMillis(backend.uploadNanos()),
                    backend.stagingCopyCalls(),
                    toMillis(backend.stagingCopyNanos()),
                    toMebibytes(backend.stagingCopyBytes()),
                    backend.indexSubmissions(),
                    toMebibytes(backend.stagedIndexBytes()),
                    toMebibytes(backend.submittedIndexBytes()),
                    backend.instanceSubmissions(),
                    toMebibytes(backend.instanceUploadedBytes()),
                    backend.geometryPoolChunks(),
                    toMebibytes(backend.geometryPoolLiveBytes()),
                    toMebibytes(backend.geometryPoolCapacityBytes()),
                    backend.instancePoolChunks(),
                    toMebibytes(backend.instancePoolLiveBytes()),
                    toMebibytes(backend.instancePoolCapacityBytes()),
                    backend.topologyBuilds(),
                    toMillis(backend.topologyNanos()),
                    toMebibytes(backend.topologyUploadBytes()));
            if ("complete".equals(state)) {
                log.debug(
                        "Dominant measured load work: {} (phase work overlaps; wall={}ms, "
                                + "decode={}ms, prepare={}ms, upload={}ms, topology={}ms)",
                        dominantMeasuredWork(
                                loader.totalNanos(),
                                producerPrepareNanos.sum(),
                                backend.uploadNanos(),
                                backend.topologyNanos()),
                        toMillis(wallNanos),
                        toMillis(loader.totalNanos()),
                        toMillis(producerPrepareNanos.sum()),
                        toMillis(backend.uploadNanos()),
                        toMillis(backend.topologyNanos()));
                var streams = aggregateStaticMeshStreamTelemetry(unrealLoaders);
                log.debug(
                        "Static-mesh streams: actors={} / {}M referenced vertices, "
                                + "actor-specific lighting={} / {}M vertices (spatial={}, exact={})",
                        streams.actors(),
                        streams.actorVertices() / 1_000_000.0,
                        streams.actorSpecificLightStreams(),
                        streams.actorSpecificLightVertices() / 1_000_000.0,
                        streams.spatialLightActors(),
                        streams.exactInstanceLightActors());
                log.debug(
                        "HF static-mesh LOD: actors={} (LOD2={}), referenced vertices "
                                + "LOD0={}M -> LOD1={}M -> LOD2={}M",
                        streams.lodActors(),
                        streams.lod2Actors(),
                        streams.lod0Vertices() / 1_000_000.0,
                        streams.lod1Vertices() / 1_000_000.0,
                        streams.lod2Vertices() / 1_000_000.0);
            }
        }
    }

    private static <T> T awaitResult(@Nullable Future<T> future) throws InterruptedException {
        if (future == null) {
            throw new IllegalStateException("Missing parser result");
        }
        try {
            return future.get();
        } catch (ExecutionException e) {
            var cause = e.getCause();
            if (cause instanceof Error error) {
                throw error;
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("World parser worker failed", cause);
        }
    }

    @FunctionalInterface
    interface InterruptibleConsumer<T> {
        void accept(T value) throws InterruptedException;
    }

    private static final class ParserContext {
        private final UnrealLoader loader;

        private ParserContext(UnrealLoader loader) {
            this.loader = loader;
        }
    }

    private static void logFinalizationPhase(String phase, long started) {
        log.debug("Post-load phase '{}' finished in {} ms", phase, (java.lang.System.nanoTime() - started) / 1_000_000);
    }

    private static int percentOf(long value, long total) {
        return total <= 0L ? 0 : (int) Math.min(100L, (value * 100L + total / 2L) / total);
    }

    private static String formatMiB(long bytes) {
        return String.format(Locale.ROOT, "%.1f", bytes / (1024.0 * 1024.0));
    }

    private static StaticMeshStreamTelemetry aggregateStaticMeshStreamTelemetry(List<UnrealLoader> loaders) {
        long actors = 0L;
        long actorVertices = 0L;
        long streams = 0L;
        long streamVertices = 0L;
        long spatial = 0L;
        long exact = 0L;
        long lodActors = 0L;
        long lod2Actors = 0L;
        long lod0Vertices = 0L;
        long lod1Vertices = 0L;
        long lod2Vertices = 0L;
        for (var loader : loaders) {
            var telemetry = loader.getStaticMeshStreamTelemetry();
            actors = Math.addExact(actors, telemetry.actors());
            actorVertices = Math.addExact(actorVertices, telemetry.actorVertices());
            streams = Math.addExact(streams, telemetry.actorSpecificLightStreams());
            streamVertices = Math.addExact(streamVertices, telemetry.actorSpecificLightVertices());
            spatial = Math.addExact(spatial, telemetry.spatialLightActors());
            exact = Math.addExact(exact, telemetry.exactInstanceLightActors());
            lodActors = Math.addExact(lodActors, telemetry.lodActors());
            lod2Actors = Math.addExact(lod2Actors, telemetry.lod2Actors());
            lod0Vertices = Math.addExact(lod0Vertices, telemetry.lod0Vertices());
            lod1Vertices = Math.addExact(lod1Vertices, telemetry.lod1Vertices());
            lod2Vertices = Math.addExact(lod2Vertices, telemetry.lod2Vertices());
        }
        return new StaticMeshStreamTelemetry(
                actors,
                actorVertices,
                streams,
                streamVertices,
                spatial,
                exact,
                lodActors,
                lod2Actors,
                lod0Vertices,
                lod1Vertices,
                lod2Vertices);
    }

    private static long toMillis(long nanos) {
        return Math.max(0L, nanos) / 1_000_000L;
    }

    private static long toMebibytes(long bytes) {
        return Math.max(0L, bytes) >> 20;
    }

    private static void logMemory(
            int loaded,
            int total,
            String label,
            List<UnrealLoader> loaders,
            SceneUploadSink renderer,
            DxtChainCache dxtChains) {
        if (!log.isDebugEnabled()) {
            return;
        }
        try {
            var runtime = Runtime.getRuntime();
            var usedMb = (runtime.totalMemory() - runtime.freeMemory()) >> 20;
            var maxMb = runtime.maxMemory() >> 20;
            var stats = renderer.getUploadStats();
            var meshCacheSize =
                    loaders.stream().mapToInt(UnrealLoader::getMeshCacheSize).sum();
            var bbCacheSize =
                    loaders.stream().mapToInt(UnrealLoader::getBbCacheSize).sum();
            var packageCacheSize =
                    loaders.stream().mapToInt(UnrealLoader::getPackageCacheSize).sum();
            log.debug(
                    "Memory at {}/{} {}: used={}MB max={}MB | meshCache={} bbCache={} pkgCache={} entMeshCache={}"
                        + " texCache={} namedTexCache={} dxtChain={} ({}MB) | texArrays={} layers={} ({}MB, dual={}) |"
                        + " lmArrays={} layers={} ({}MB)",
                    loaded,
                    total,
                    label,
                    usedMb,
                    maxMb,
                    meshCacheSize,
                    bbCacheSize,
                    packageCacheSize,
                    stats.entityMeshCacheSize(),
                    stats.textureCacheSize(),
                    stats.namedTextureCacheSize(),
                    dxtChains.size(),
                    dxtChains.getBytes() >> 20,
                    stats.textureArrayFamilyCount(),
                    stats.textureArrayLayerCount(),
                    stats.textureArrayVramBytes() >> 20,
                    stats.textureArrayDualPathCount(),
                    stats.csgLightmapArrayCount(),
                    stats.csgLightmapLayerCount(),
                    stats.csgLightmapVramBytes() >> 20);
            if (stats.textureArrayMultiUnitEnabled()) {
                var histogram = stats.textureArrayMultiUnitBucketFamilies();
                log.debug(
                        "Multi-unit: buckets={} families={} -> groups={} (draws collapsed={}) | "
                                + "families/bucket 1={} 2={} 3={} 4={} 5={} 6={} 7+={}",
                        stats.textureArrayMultiUnitBuckets(),
                        stats.textureArrayMultiUnitFamilies(),
                        stats.textureArrayMultiUnitGroups(),
                        stats.textureArrayMultiUnitFamilies() - stats.textureArrayMultiUnitGroups(),
                        histogram[1],
                        histogram[2],
                        histogram[3],
                        histogram[4],
                        histogram[5],
                        histogram[6],
                        histogram[7]);
            }
        } catch (RuntimeException e) {
            log.debug("Skipped a memory log line", e);
        }
    }

    private static long tileDistanceSquared(MapName map, int cameraTileX, int cameraTileY) {
        var dx = (long) map.x() - cameraTileX;
        var dy = (long) map.y() - cameraTileY;
        return dx * dx + dy * dy;
    }
}
