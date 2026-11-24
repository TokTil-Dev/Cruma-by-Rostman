package com.rost.cruma.viewer.editing;

import com.rost.cruma.core.editing.ActorLightingChanges;
import com.rost.cruma.core.editing.ActorLightingRebuild;
import com.rost.cruma.core.editing.ActorLightingRebuild.Options;
import com.rost.cruma.core.editing.ActorLightingScene.Settings;
import com.rost.cruma.core.editing.ActorTransform;
import com.rost.cruma.core.editing.PackageEditDocument;
import com.rost.cruma.core.editing.PackageEditDocument.RecoveryBundle;
import com.rost.cruma.core.world.Entity;
import com.rost.cruma.viewer.editing.EditingSession.Rebuild;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

final class EditingLightingPreview implements AutoCloseable {
    private static final Logger log = LogManager.getLogger(EditingLightingPreview.class);

    private final Thread ownerThread = Thread.currentThread();
    private final Set<String> queued = new LinkedHashSet<>();
    private final Set<String> queuedMaps = new LinkedHashSet<>();
    private final EditingLightSurfaces surfaces = new EditingLightSurfaces();
    private ExecutorService worker;
    private CompletableFuture<Result> pending;
    private Set<String> pendingActors = Set.of();
    private Set<String> pendingMaps = Set.of();
    private AtomicBoolean cancelled = new AtomicBoolean();
    private boolean closed;

    record Job(
            long revision,
            Map<String, Path> sources,
            Map<String, RecoveryBundle> documents,
            Map<String, Set<String>> actors,
            Set<String> lightMaps,
            Map<String, Path> packages,
            Settings settings,
            Path clientRoot,
            Map<String, ActorTransform> baselines,
            Function<Rebuild, @Nullable Map<String, List<Entity>>> loader) {}

    record Result(
            long revision,
            Map<String, ActorLightingChanges> lighting,
            Map<String, EditingLightSurfaces.Result> surfaces,
            @Nullable Map<String, List<Entity>> actors) {
        Result(long revision, Map<String, ActorLightingChanges> lighting) {
            this(revision, lighting, Map.of(), null);
        }
    }

    void schedule(Set<String> actors) {
        requireOwner();
        if (!closed && !actors.isEmpty()) {
            queued.addAll(actors);
            stopPending();
        }
    }

    void scheduleMaps(Set<String> maps) {
        requireOwner();
        if (!closed && !maps.isEmpty()) {
            queuedMaps.addAll(maps);
            stopPending();
        }
    }

    void follow(Set<String> actors, Set<String> maps) {
        requireOwner();
        if (!closed) {
            queued.addAll(actors);
            queuedMaps.addAll(maps);
        }
    }

    boolean isWaiting() {
        return !queued.isEmpty() || !queuedMaps.isEmpty();
    }

    boolean isBusy() {
        return pending != null;
    }

    Set<String> getQueued() {
        return Set.copyOf(queued);
    }

    Set<String> getQueuedMaps() {
        return Set.copyOf(queuedMaps);
    }

    Result poll(BiFunction<Set<String>, Set<String>, Job> capture) {
        requireOwner();
        Result finished = null;
        if (pending != null && pending.isDone()) {
            if (cancelled.get()) {
                queued.addAll(pendingActors);
                queuedMaps.addAll(pendingMaps);
            } else {
                try {
                    finished = pending.join();
                } catch (CompletionException | CancellationException failure) {
                    var cause = failure.getCause() == null ? failure : failure.getCause();
                    log.warn("Lighting preview failed: {}", cause.toString());
                }
            }
            pending = null;
            pendingActors = Set.of();
            pendingMaps = Set.of();
        }
        if (pending == null && (!queued.isEmpty() || !queuedMaps.isEmpty()) && !closed) {
            var actors = Set.copyOf(queued);
            var maps = Set.copyOf(queuedMaps);
            queued.clear();
            queuedMaps.clear();
            Job job;
            try {
                job = capture.apply(actors, maps);
            } catch (RuntimeException failure) {
                log.warn("Lighting preview could not capture the edit: {}", failure.toString());
                job = null;
            }
            if (job != null && (!job.actors().isEmpty() || !job.lightMaps().isEmpty())) {
                if (worker == null) {
                    worker = Executors.newSingleThreadExecutor(Thread.ofPlatform()
                            .daemon()
                            .name("editor-lighting-preview")
                            .factory());
                }
                cancelled = new AtomicBoolean();
                var flag = cancelled;
                var input = job;
                pendingActors = actors;
                pendingMaps = maps;
                pending = CompletableFuture.supplyAsync(() -> run(input, flag), worker);
            }
        }
        return finished;
    }

    void retry(Result result) {
        for (var lighting : result.lighting().values()) {
            queued.addAll(lighting.getActors().keySet());
        }
        queuedMaps.addAll(result.surfaces().keySet());
    }

    @Override
    public void close() {
        requireOwner();
        closed = true;
        cancelled.set(true);
        queued.clear();
        queuedMaps.clear();
        if (worker != null) {
            worker.shutdownNow();
        }
        pending = null;
    }

    private void stopPending() {
        if (pending != null) {
            cancelled.set(true);
        }
    }

    private Result run(Job job, AtomicBoolean cancelled) {
        var lighting = new LinkedHashMap<String, ActorLightingChanges>();
        var lit = new LinkedHashMap<String, EditingLightSurfaces.Result>();
        Map<String, List<Entity>> loaded = new LinkedHashMap<>();
        for (var entry : job.documents().entrySet()) {
            if (cancelled.get()) {
                break;
            }
            var map = entry.getKey();
            var archives = new LightingArchives(job.clientRoot(), job.packages());
            try {
                var document = PackageEditDocument.restoreRecovery(
                                job.sources().get(map), entry.getValue().encode())
                        .document();
                var actors = job.actors().get(map);
                if (actors != null && !actors.isEmpty()) {
                    var result = ActorLightingRebuild.rebuild(
                            document,
                            archives,
                            job.settings(),
                            Options.preview(),
                            actors,
                            (done, total) -> {},
                            cancelled::get);
                    if (!result.changes().isEmpty()) {
                        lighting.put(map, result.changes());
                        loaded = load(job, map, document, result.changes(), loaded, cancelled);
                    }
                }
                if (job.lightMaps().contains(map) && !cancelled.get()) {
                    var result = surfaces.rebuild(document, archives, job.clientRoot(), job.settings(), cancelled::get);
                    log.info(
                            "Light preview of {}: BSP {} ms ({} lightmap textures), Terrain {} ms ({} owners)",
                            map,
                            result.bspNanos() / 1_000_000,
                            result.lightmaps().size(),
                            result.terrainNanos() / 1_000_000,
                            result.terrain().size());
                    lit.put(map, result);
                }
            } catch (Exception failure) {
                throw new CompletionException(failure);
            } finally {
                archives.clearArchives();
            }
        }
        return new Result(job.revision(), Map.copyOf(lighting), Map.copyOf(lit), loaded);
    }

    private void requireOwner() {
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException("The lighting preview belongs to the editing owner thread");
        }
    }

    private static @Nullable Map<String, List<Entity>> load(
            Job job,
            String map,
            PackageEditDocument document,
            ActorLightingChanges changes,
            @Nullable Map<String, List<Entity>> loaded,
            AtomicBoolean cancelled) {
        if (loaded == null || cancelled.get()) {
            return loaded;
        }
        var actors = changes.getActors().keySet();
        var baselines = new LinkedHashMap<String, ActorTransform>();
        for (var actor : actors) {
            var baseline = job.baselines().get(actor);
            if (baseline != null) {
                baselines.put(actor, baseline);
            }
        }
        Map<String, List<Entity>> entities;
        try {
            entities = job.loader()
                    .apply(new Rebuild(
                            Map.of(map, document),
                            Map.copyOf(baselines),
                            Set.copyOf(actors),
                            job.packages(),
                            Map.of(map, changes)));
        } catch (RuntimeException failure) {
            log.debug("Lighting preview could not load {} on its worker: {}", map, failure.toString());
            entities = null;
        }
        if (entities == null) {
            return null;
        }
        loaded.putAll(entities);
        return loaded;
    }
}
