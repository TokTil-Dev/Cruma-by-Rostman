package com.rost.cruma.viewer.ui;

import com.rost.cruma.core.export.LocationExport.Progress;
import com.rost.cruma.core.texture.DxtMipCodec.Options;
import com.rost.cruma.core.world.GameScenes.Scene;
import com.rost.cruma.core.world.TextureQuality;
import com.rost.cruma.core.world.UnrealLoader;
import com.rost.cruma.core.world.WorldVariant;
import com.rost.cruma.viewer.application.SceneRequest;
import com.rost.cruma.viewer.geodata.ClientFloorGrid;
import com.rost.cruma.viewer.geodata.ClientFloors;
import com.rost.cruma.viewer.ui.SceneSampler.Shared;
import com.rost.cruma.viewer.ui.SceneSampler.Surroundings;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

final class SceneBake implements AutoCloseable, SceneBaker {
    private static final double SCENE_HOUR = 12;
    private static final Logger log = LogManager.getLogger(SceneBake.class);

    private final Path client;
    private final WorldVariant variant;
    private final TextureQuality quality;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        var thread = new Thread(runnable, "cruma-scene-bake");
        thread.setDaemon(true);
        return thread;
    });
    private final Function<Scene, Surroundings> surroundings;
    private final ClientFloors floors = new ClientFloors();
    private final Set<String> readMaps = new HashSet<>();
    private final Set<String> askedFloors = ConcurrentHashMap.newKeySet();
    private final AtomicInteger floorsOnTheirWay = new AtomicInteger();
    private @Nullable Shared shared;
    private volatile @Nullable Job running;
    private volatile @Nullable BakedScene latest;
    private volatile String problem = "";

    SceneBake(Path client, WorldVariant variant, TextureQuality quality) {
        this.client = client;
        this.variant = variant;
        this.quality = quality;
        surroundings = this::floorsOf;
    }

    SceneBake(Path client, WorldVariant variant, TextureQuality quality, Function<Scene, Surroundings> surroundings) {
        this.client = client;
        this.variant = variant;
        this.quality = quality;
        this.surroundings = surroundings;
    }

    @Override
    public void start(Scene scene, int rate) {
        cancel();
        var job = new Job(scene, rate);
        running = job;
        worker.execute(() -> run(job));
    }

    @Override
    public void cancel() {
        var job = running;
        if (job != null) {
            job.cancelled = true;
            running = null;
        }
    }

    @Override
    public @Nullable BakedScene getLatest() {
        return latest;
    }

    @Override
    public boolean isBusy() {
        return running != null;
    }

    @Override
    public double getProgress() {
        var job = running;
        return job == null ? 0 : job.done;
    }

    @Override
    public String getProblem() {
        return problem;
    }

    @Override
    public ClientFloors getFloors() {
        return floors;
    }

    @Override
    public void readFloors(String tile) {
        if (askedFloors.add(tile)) {
            floorsOnTheirWay.incrementAndGet();
            worker.execute(() -> {
                try {
                    if (!readMaps.contains(tile)) {
                        read(List.of(tile));
                    }
                } finally {
                    floorsOnTheirWay.decrementAndGet();
                }
            });
        }
    }

    @Override
    public boolean isReadingFloors() {
        return floorsOnTheirWay.get() > 0;
    }

    @Override
    public void release() {
        cancel();
        worker.execute(this::letGo);
    }

    @Override
    public void close() {
        release();
        worker.shutdown();
    }

    private void letGo() {
        if (shared != null) {
            shared.close();
            shared = null;
        }
        floors.clear();
        readMaps.clear();
        askedFloors.clear();
        latest = null;
    }

    private void run(Job job) {
        if (job.cancelled) {
            return;
        }
        try {
            if (shared == null) {
                shared = new Shared(client, variant, quality);
            }
            var around = surroundings.apply(job.scene);
            Progress progress = (_, done) -> job.done = done;
            var baked = SceneSampler.bake(job.scene, around, shared, job.rate, progress, () -> job.cancelled);
            if (!job.cancelled) {
                latest = baked;
                problem = "";
            }
        } catch (CancellationException stopped) {
            log.debug("A bake of {} stopped for a newer one", job.scene.name());
        } catch (RuntimeException failure) {
            log.warn("{} could not be played", job.scene.name(), failure);
            problem = "The scene could not be played: " + failure.getMessage();
        } finally {
            if (running == job) {
                running = null;
            }
        }
    }

    private Surroundings floorsOf(Scene scene) {
        var maps = scene.map().isEmpty() || hasActors(scene)
                ? SceneRequest.close(scene, client, variant).tiles()
                : List.<String>of();
        var missing = maps.stream().filter(map -> !readMaps.contains(map)).toList();
        if (!missing.isEmpty()) {
            read(missing);
        }
        return Surroundings.of(GameSceneStage.ground(floors), (float) scene.hourOr(SCENE_HOUR));
    }

    private void read(List<String> maps) {
        var loader = new UnrealLoader(client, new Options(false, 0), true);
        loader.worldVariant(variant);
        try {
            for (var name : maps) {
                readMaps.add(name);
                try {
                    var map = loader.loadMap(name);
                    var grid = map == null ? null : ClientFloorGrid.build(map, () -> false);
                    if (grid != null) {
                        floors.put(grid);
                    }
                } catch (RuntimeException failure) {
                    log.warn("The floors of {} could not be read: {}", name, failure.toString());
                }
            }
        } finally {
            loader.trimCaches();
        }
    }

    private static final class Job {
        final Scene scene;
        final int rate;
        volatile boolean cancelled;
        volatile double done;

        Job(Scene scene, int rate) {
            this.scene = scene;
            this.rate = rate;
        }
    }

    private static boolean hasActors(Scene scene) {
        for (var item : scene.items()) {
            if (!item.actors().isEmpty()) {
                return true;
            }
        }
        return false;
    }
}
