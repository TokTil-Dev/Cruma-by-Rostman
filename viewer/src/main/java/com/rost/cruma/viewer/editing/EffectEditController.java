package com.rost.cruma.viewer.editing;

import com.rost.cruma.core.editing.EffectEditSession;
import com.rost.cruma.core.editing.EffectEditSession.ActionInfo;
import com.rost.cruma.core.editing.EffectEditSession.EditProblem;
import com.rost.cruma.core.editing.EffectEditSession.EmitterInfo;
import com.rost.cruma.core.editing.EffectEditSession.Placement;
import com.rost.cruma.core.editing.EffectEditSession.Preview;
import com.rost.cruma.core.editing.EffectEditSession.PropertyInfo;
import com.rost.cruma.core.editing.EffectEdits;
import com.rost.cruma.core.editing.EffectEdits.ClassEdit;
import com.rost.cruma.core.editing.EffectEdits.Copy;
import com.rost.cruma.core.editing.EffectEdits.EmitterEdit;
import com.rost.cruma.core.editing.EffectEdits.EmitterRef;
import com.rost.cruma.core.editing.EffectEdits.Kind;
import com.rost.cruma.core.editing.EffectEdits.SkillEdit;
import com.rost.cruma.core.npc.ClientIcons.Icon;
import com.rost.cruma.core.texture.DxtMipCodec.Options;
import com.rost.cruma.core.unreal.SkillEffects;
import com.rost.cruma.core.unreal.SkillEffects.Layout;
import com.rost.cruma.core.unreal.SkillEffects.Phase;
import com.rost.cruma.core.unreal.SkillEffects.Skill;
import com.rost.cruma.core.world.EffectParts;
import com.rost.cruma.core.world.TextureQuality;
import com.rost.cruma.core.world.UnrealLoader;
import com.rost.cruma.core.world.WorldVariant;
import com.rost.cruma.viewer.editing.PackageExportService.AssetInput;
import com.rost.cruma.viewer.editing.PackageExportService.DerivedAssetInput;
import com.rost.cruma.viewer.editing.PackageExportService.Plan;
import com.rost.cruma.viewer.editing.PackageExportService.ReplacedAssetInput;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import org.apache.logging.log4j.LogManager;
import org.jetbrains.annotations.Nullable;

public final class EffectEditController implements AutoCloseable {
    public static final float FACTOR_MIN = .1f;

    public static final float FACTOR_MAX = 10f;

    public static final int HUE_LIMIT = 180;

    private static final long REBUILD_NANOS = 100_000_000L;
    private static final long SAVE_NANOS = 500_000_000L;
    private static final int UNDO_STEPS = 200;
    private static final int PARTS_KEPT = 64;

    private final Path clientRoot;
    private final @Nullable EffectEditStore store;
    private final PartsReader reader;
    private final LongSupplier clock;
    private final ExecutorService worker;
    private final Queue<Runnable> finished = new ConcurrentLinkedQueue<>();
    private final List<EffectEdits> undoSteps = new ArrayList<>();
    private final List<EffectEdits> redoSteps = new ArrayList<>();
    private final Map<String, Answer<?>> answers = new HashMap<>();
    private @Nullable EffectEditSession session;
    private final Map<Path, String> pinned = new ConcurrentHashMap<>();
    private final AtomicReference<@Nullable EffectEdits> unsaved = new AtomicReference<>();
    private @Nullable Path imageFolder;
    private final Map<String, BuiltParts> builtParts = new HashMap<>();
    private EffectEdits edits = EffectEdits.NONE;
    private EffectEdits exported = EffectEdits.NONE;
    private @Nullable EffectEdits dragBase;
    private boolean restored;
    private @Nullable StaleEdits stale;
    private String error = "";
    private @Nullable String watched;
    private @Nullable EffectEdits builtFor;
    private boolean building;
    private boolean settling;
    private long lastBuild;
    private @Nullable Rebuilt rebuilt;
    private @Nullable Skill watchedSkill;
    private @Nullable EffectEdits skillBuiltFor;
    private boolean skillBuilding;
    private boolean skillSettling;
    private long lastSkillBuild;
    private @Nullable BuiltSkill rebuiltSkill;
    private @Nullable EffectEdits saveWanted;
    private long saveAt;
    private @Nullable EffectEdits usedFor;
    private Map<String, String> used = Map.of();

    public EffectEditController(
            Path clientRoot, @Nullable Path dataRoot, TextureQuality quality, WorldVariant variant) {
        this(clientRoot, dataRoot, loaderReader(clientRoot, quality, variant), System::nanoTime);
    }

    EffectEditController(Path clientRoot, @Nullable Path dataRoot, PartsReader reader, LongSupplier clock) {
        this.clientRoot = clientRoot.toAbsolutePath().normalize();
        store = dataRoot == null
                ? null
                : new EffectEditStore(EditingRecovery.clientDirectory(dataRoot.resolve("effects"), this.clientRoot));
        this.reader = reader;
        this.clock = clock;
        worker = Executors.newSingleThreadExecutor(task -> {
            var thread = new Thread(task, "effect-edits");
            thread.setDaemon(true);
            return thread;
        });
        worker.execute(this::restore);
    }

    @FunctionalInterface
    public interface PartsReader {
        @Nullable
        EffectParts read(Preview preview) throws IOException;

        default void reset() {}
    }

    @FunctionalInterface
    public interface SessionCall<T> {
        T run(EffectEditSession session) throws IOException;
    }

    public record Rebuilt(String classPath, @Nullable EffectParts parts, boolean settled) {}

    public record BuiltSkill(
            String className, @Nullable Skill skill, Map<String, EffectParts> parts, boolean settled) {}

    public record StaleEdits(
            Path file, List<String> changed, @Nullable EffectEdits edits) {}

    public record PreparedExport(EffectEdits edits, Placement placement, PackageExportService service, Plan plan) {}

    private static final class Answer<T> {
        private @Nullable Object version;
        private @Nullable T value;
        private boolean asking;
    }

    private record BuiltParts(
            ClassEdit edit, @Nullable Copy copy, @Nullable EffectParts parts) {}

    private record EmitterVersion(
            EmitterEdit edit, @Nullable EmitterRef origin) {}

    private record WatchedBuild(@Nullable EffectParts parts) {}

    public static @Nullable String valueProblem(Kind kind, String text) {
        try {
            switch (kind) {
                case BYTE -> {
                    var value = Integer.parseInt(text);
                    if (value < 0 || value > 255) {
                        return "A byte is a whole number from 0 to 255.";
                    }
                }
                case INT -> Integer.parseInt(text);
                case FLOAT -> Float.parseFloat(text);
                case VECTOR -> {
                    var parts = text.split(",");
                    if (parts.length != 3) {
                        return "A vector is three numbers, such as 1,2,3.";
                    }
                    for (var part : parts) {
                        Float.parseFloat(part.trim());
                    }
                }
                case ROTATOR -> {
                    var parts = text.split(",");
                    if (parts.length != 3) {
                        return "A rotator is three whole numbers, such as 0,16384,0.";
                    }
                    for (var part : parts) {
                        Integer.parseInt(part.trim());
                    }
                }
                default -> {
                    return null;
                }
            }
            return null;
        } catch (NumberFormatException wrong) {
            return "This is not a number: " + text;
        }
    }

    public static boolean touches(EffectEdits value, Skill skill) {
        if (value.skills().containsKey(skill.className())) {
            return true;
        }
        if (value.classes().isEmpty()) {
            return false;
        }
        var edited = new HashSet<String>();
        for (var path : value.classes().keySet()) {
            edited.add(SkillEffects.key(path));
        }
        for (var phase : Phase.values()) {
            for (var action : skill.actions(phase)) {
                if (edited.contains(SkillEffects.key(action.effect()))) {
                    return true;
                }
            }
        }
        return false;
    }

    public Path getClientRoot() {
        return clientRoot;
    }

    public boolean isReady() {
        return restored;
    }

    public EffectEdits getEdits() {
        return edits;
    }

    public @Nullable StaleEdits getStale() {
        return stale;
    }

    public void applyStale() {
        var waiting = stale;
        if (waiting == null || waiting.edits() == null) {
            return;
        }
        stale = null;
        change(waiting.edits());
        flush();
        forget(waiting.file());
    }

    public void discardStale() {
        var waiting = stale;
        if (waiting != null) {
            stale = null;
            forget(waiting.file());
        }
    }

    public String getError() {
        return error;
    }

    public void clearError() {
        error = "";
    }

    public void poll() {
        for (var done = finished.poll(); done != null; done = finished.poll()) {
            done.run();
        }
        startBuild();
        startSkillBuild();
        startSave();
    }

    public void flush() {
        var value = saveWanted;
        if (value != null && dragBase == null) {
            saveWanted = null;
            save(value);
        }
    }

    public void change(EffectEdits next) {
        settle();
        if (next.equals(edits)) {
            return;
        }
        step(edits);
        edits = next;
        touched();
    }

    public <T> CompletableFuture<T> change(SessionCall<T> call) {
        var result = new CompletableFuture<T>();
        changeOn(edits, call, result);
        return result;
    }

    public void drag(EffectEdits next) {
        if (dragBase == null) {
            dragBase = edits;
        }
        if (next.equals(edits)) {
            return;
        }
        edits = next;
        touched();
    }

    public void settle() {
        var base = dragBase;
        if (base == null) {
            return;
        }
        dragBase = null;
        settling = true;
        skillSettling = true;
        if (!base.equals(edits)) {
            step(base);
        }
    }

    public boolean isDragging() {
        return dragBase != null;
    }

    boolean isBuilding() {
        return building;
    }

    boolean isBuildingSkill() {
        return skillBuilding;
    }

    public boolean canUndo() {
        return !undoSteps.isEmpty();
    }

    public boolean canRedo() {
        return !redoSteps.isEmpty();
    }

    public void undo() {
        settle();
        if (undoSteps.isEmpty()) {
            return;
        }
        redoSteps.add(edits);
        edits = undoSteps.removeLast();
        touched();
    }

    public void redo() {
        settle();
        if (redoSteps.isEmpty()) {
            return;
        }
        undoSteps.add(edits);
        edits = redoSteps.removeLast();
        touched();
    }

    public boolean isEdited(String classPath) {
        return edits.classes().containsKey(classPath) || edits.copies().containsKey(classPath);
    }

    public boolean isUnexported() {
        return !edits.equals(exported);
    }

    public boolean hasEdits() {
        return !edits.classes().isEmpty()
                || !edits.copies().isEmpty()
                || !edits.skills().isEmpty();
    }

    public String getSummary() {
        var counts = getCounts();
        return counts.isEmpty() ? "" : counts + " changed";
    }

    public String getCounts() {
        var parts = new ArrayList<String>();
        var effects = 0;
        for (var path : edits.classes().keySet()) {
            if (!edits.copies().containsKey(path)) {
                effects++;
            }
        }
        if (effects > 0) {
            parts.add(effects == 1 ? "1 effect" : effects + " effects");
        }
        var copies = edits.copies().size();
        if (copies > 0) {
            parts.add(copies == 1 ? "1 copy" : copies + " copies");
        }
        var skills = edits.skills().size();
        if (skills > 0) {
            parts.add(skills == 1 ? "1 skill" : skills + " skills");
        }
        return String.join(", ", parts);
    }

    public @Nullable List<EmitterInfo> getEmitters(String classPath) {
        return cached("emitters|" + classPath, edits.classEdit(classPath), session -> session.getEmitters(classPath));
    }

    public @Nullable List<PropertyInfo> getProperties(String classPath, String emitterKey) {
        return cached(
                "properties|" + classPath + "|" + emitterKey,
                new EmitterVersion(edits.classEdit(classPath).emitter(emitterKey), edits.origin(classPath, emitterKey)),
                session -> session.getProperties(classPath, emitterKey));
    }

    public @Nullable List<ActionInfo> getActions(String className) {
        var key = "actions|" + className;
        var known = answers.get(key);
        if (dragBase != null && known != null) {
            @SuppressWarnings("unchecked")
            var value = (List<ActionInfo>) known.value;
            return value;
        }
        return cached(key, edits.skillEdit(className), session -> session.getActions(className));
    }

    public @Nullable List<ActionInfo> getClientActions(String className) {
        return cached("client-actions|" + className, className, session -> {
            session.setEdits(EffectEdits.NONE);
            return session.getActions(className);
        });
    }

    public @Nullable String suggestCopyName(String classPath) {
        return cached("copy-name|" + classPath, edits.copies().keySet(), session -> session.suggestCopyName(classPath));
    }

    public @Nullable String copyRefusal(String classPath) {
        return cached("copy-refusal|" + classPath, classPath, session -> {
            var reason = session.copyRefusal(classPath);
            return reason == null ? "" : reason;
        });
    }

    public @Nullable Boolean hasSkillPackage() {
        return cached("skill-package", clientRoot, EffectEditSession::hasSkillPackage);
    }

    public @Nullable Layout getSkillLayout() {
        return cached("skill-layout", clientRoot, EffectEditSession::getSkillLayout);
    }

    public List<String> getImageProblems() {
        if (usedFor != edits) {
            usedFor = edits;
            used = edits.usedTextures();
        }
        if (used.isEmpty()) {
            return List.of();
        }
        var problems = cached("image-problems", used, EffectEditSession::getImageProblems);
        return problems == null ? List.of() : problems;
    }

    public List<EditProblem> getEditProblems() {
        if (!hasEdits()) {
            return List.of();
        }
        var key = "edit-problems";
        var known = answers.get(key);
        if (dragBase != null && known != null && known.value != null) {
            @SuppressWarnings("unchecked")
            var value = (List<EditProblem>) known.value;
            return value;
        }
        var problems = cached(key, edits, EffectEditSession::getEditProblems);
        return problems == null ? List.of() : problems;
    }

    public boolean isBroken(String path) {
        for (var problem : getEditProblems()) {
            if (problem.path().equals(path)) {
                return true;
            }
        }
        return false;
    }

    public void drop(EditProblem problem) {
        change(problem.skill() ? edits.withSkill(problem.path(), SkillEdit.NONE) : edits.withoutEdit(problem.path()));
        error = "";
    }

    public @Nullable Icon getPicture(String file) {
        return cached("picture|" + file, file, session -> session.getPicture(file));
    }

    public <T> CompletableFuture<T> read(SessionCall<T> call) {
        var value = edits;
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        var opened = session();
                        opened.setEdits(value);
                        return call.run(opened);
                    } catch (IOException failure) {
                        throw new CompletionException(failure);
                    }
                },
                worker);
    }

    public CompletableFuture<@Nullable EffectParts> build(String classPath) {
        return read(session -> reader.read(session.preview(classPath)));
    }

    public CompletableFuture<BuiltSkill> buildSkill(String className) {
        var value = edits;
        var settled = dragBase == null;
        return read(session -> skillBuild(session, value, className, settled));
    }

    public void watchSkill(@Nullable Skill skill) {
        if (skill == watchedSkill) {
            return;
        }
        watchedSkill = skill;
        skillBuiltFor = null;
        skillSettling = false;
        rebuiltSkill = null;
    }

    public @Nullable BuiltSkill takeRebuiltSkill() {
        var result = rebuiltSkill;
        rebuiltSkill = null;
        return result;
    }

    public void watch(@Nullable String classPath) {
        if (Objects.equals(classPath, watched)) {
            return;
        }
        watched = classPath;
        builtFor = edits;
        settling = false;
        rebuilt = null;
    }

    public @Nullable Rebuilt takeRebuilt() {
        var result = rebuilt;
        rebuilt = null;
        return result;
    }

    public CompletableFuture<PreparedExport> prepareExport(Placement placement) {
        var value = edits;
        return read(session -> {
            var outputs = session.export(placement);
            if (outputs.isEmpty()) {
                throw new IOException("There is nothing to export: the edits change no file.");
            }
            var origins = new ArrayList<AssetInput>();
            for (var file : sourceFiles(value)) {
                origins.add(new AssetInput(file, pin(file)));
            }
            var replaced = new ArrayList<ReplacedAssetInput>();
            var derived = new ArrayList<DerivedAssetInput>();
            for (var output : outputs) {
                if (output.source() != null) {
                    replaced.add(new ReplacedAssetInput(output.source(), pin(output.source()), output.bytes()));
                } else {
                    if (origins.isEmpty()) {
                        throw new IOException("A new package has no source package: " + output.name());
                    }
                    var first = origins.getFirst();
                    derived.add(new DerivedAssetInput(
                            first.source(), first.sourceHash(), output.name(), output.bytes(), origins));
                }
            }
            var service = new PackageExportService(clientRoot);
            var plan = service.prepare(List.of(), List.of(), derived, replaced);
            return new PreparedExport(value, placement, service, plan);
        });
    }

    public CompletableFuture<List<Path>> publish(PreparedExport prepared, Path folder, BooleanSupplier cancelled) {
        var result = read(session -> prepared.service().publish(prepared.plan(), folder, cancelled));
        result.thenRun(() -> finished.add(() -> exported = prepared.edits()));
        return result;
    }

    public void installed() {
        undoSteps.clear();
        redoSteps.clear();
        edits = EffectEdits.NONE;
        exported = EffectEdits.NONE;
        dragBase = null;
        builtFor = edits;
        settling = false;
        rebuilt = null;
        skillBuiltFor = edits;
        skillSettling = false;
        rebuiltSkill = null;
        saveWanted = null;
        unsaved.set(null);
        answers.clear();
        worker.execute(() -> {
            closeSession();
            reader.reset();
            pinned.clear();
            builtParts.clear();
            try {
                if (store != null) {
                    store.delete();
                }
            } catch (IOException failure) {
                LogManager.getLogger(EffectEditController.class).warn("The saved edits could not be removed", failure);
            }
        });
    }

    public void restored() {
        installed();
    }

    @Override
    public void close() {
        var value = saveWanted;
        saveWanted = null;
        if (value != null) {
            unsaved.set(value);
        }
        worker.execute(this::closeSession);
        worker.shutdown();
        writeUnsaved();
        try {
            if (!worker.awaitTermination(2, TimeUnit.SECONDS)) {
                worker.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void step(EffectEdits before) {
        undoSteps.add(before);
        if (undoSteps.size() > UNDO_STEPS) {
            undoSteps.removeFirst();
        }
        redoSteps.clear();
    }

    private void touched() {
        saveWanted = edits;
        saveAt = clock.getAsLong() + SAVE_NANOS;
    }

    private <T> void changeOn(EffectEdits base, SessionCall<T> call, CompletableFuture<T> result) {
        CompletableFuture.runAsync(
                () -> {
                    try {
                        var opened = session();
                        opened.setEdits(base);
                        var value = call.run(opened);
                        var next = opened.getEdits();
                        finished.add(() -> {
                            if (edits != base) {
                                changeOn(edits, call, result);
                                return;
                            }
                            change(next);
                            result.complete(value);
                        });
                    } catch (IOException | RuntimeException failure) {
                        finished.add(() -> result.completeExceptionally(failure));
                    }
                },
                worker);
    }

    @SuppressWarnings("unchecked")
    private <T> @Nullable T cached(String key, Object version, SessionCall<T> call) {
        var answer = (Answer<T>) answers.computeIfAbsent(key, _ -> new Answer<T>());
        if (!version.equals(answer.version) && !answer.asking) {
            answer.asking = true;
            read(call)
                    .whenComplete((value, failure) -> finished.add(() -> {
                        answer.asking = false;
                        answer.version = version;
                        if (failure == null) {
                            answer.value = value;
                        } else {
                            error = message(failure);
                        }
                    }));
        }
        return answer.value;
    }

    private void startBuild() {
        var path = watched;
        if (path == null || building || edits == builtFor && !settling) {
            return;
        }
        if (!settling
                && builtFor != null
                && edits.classes().equals(builtFor.classes())
                && edits.copies().equals(builtFor.copies())) {
            builtFor = edits;
            return;
        }
        var now = clock.getAsLong();
        if (dragBase != null && now - lastBuild < REBUILD_NANOS) {
            return;
        }
        building = true;
        lastBuild = now;
        builtFor = edits;
        var settled = dragBase == null;
        settling = false;
        read(session -> session.hasClass(path) ? new WatchedBuild(reader.read(session.preview(path))) : null)
                .whenComplete((built, failure) -> finished.add(() -> {
                    building = false;
                    if (failure != null) {
                        error = message(failure);
                    } else if (built != null && path.equals(watched)) {
                        error = "";
                        rebuilt = new Rebuilt(path, built.parts(), settled);
                    }
                }));
    }

    private void startSkillBuild() {
        var skill = watchedSkill;
        if (skill == null || skillBuilding || edits == skillBuiltFor && !skillSettling) {
            return;
        }
        var now = clock.getAsLong();
        if (dragBase != null && now - lastSkillBuild < REBUILD_NANOS) {
            return;
        }
        var name = skill.className();
        skillBuiltFor = edits;
        skillSettling = false;
        if (!touches(edits, skill)) {
            rebuiltSkill = new BuiltSkill(name, null, Map.of(), dragBase == null);
            return;
        }
        skillBuilding = true;
        lastSkillBuild = now;
        buildSkill(name)
                .whenComplete((built, failure) -> finished.add(() -> {
                    skillBuilding = false;
                    if (failure != null) {
                        error = message(failure);
                    } else if (watchedSkill != null && name.equals(watchedSkill.className())) {
                        error = "";
                        rebuiltSkill = built;
                    }
                }));
    }

    private BuiltSkill skillBuild(EffectEditSession session, EffectEdits value, String className, boolean settled)
            throws IOException {
        var skill = session.previewSkill(className);
        var named = new HashMap<String, String>();
        for (var path : value.classes().keySet()) {
            named.put(SkillEffects.key(path), path);
        }
        for (var path : value.copies().keySet()) {
            named.put(SkillEffects.key(path), path);
        }
        if (builtParts.size() > PARTS_KEPT) {
            builtParts.clear();
        }
        var parts = new HashMap<String, EffectParts>();
        for (var phase : Phase.values()) {
            for (var action : skill.actions(phase)) {
                var key = SkillEffects.key(action.effect());
                if (!parts.containsKey(key)) {
                    var built = partsOf(session, value, named.getOrDefault(key, action.effect()));
                    if (built != null && !built.isEmpty()) {
                        parts.put(key, built);
                    }
                }
            }
        }
        return new BuiltSkill(className, skill, parts, settled);
    }

    private @Nullable EffectParts partsOf(EffectEditSession session, EffectEdits value, String path) {
        var key = SkillEffects.key(path);
        var edit = value.classEdit(path);
        var copy = value.copies().get(path);
        var known = builtParts.get(key);
        if (known != null && known.edit().equals(edit) && Objects.equals(known.copy(), copy)) {
            return known.parts();
        }
        EffectParts parts = null;
        try {
            parts = reader.read(session.preview(path));
        } catch (IOException | RuntimeException failure) {
            LogManager.getLogger(EffectEditController.class)
                    .debug("Effect {} of a skill could not be built", path, failure);
        }
        builtParts.put(key, new BuiltParts(edit, copy, parts));
        return parts;
    }

    private void startSave() {
        var value = saveWanted;
        if (value == null || dragBase != null || clock.getAsLong() < saveAt) {
            return;
        }
        saveWanted = null;
        save(value);
    }

    private void save(EffectEdits value) {
        unsaved.set(value);
        worker.execute(this::writeUnsaved);
    }

    private void writeUnsaved() {
        synchronized (unsaved) {
            var value = unsaved.get();
            if (value != null) {
                write(value);
                unsaved.compareAndSet(value, null);
            }
        }
    }

    private void write(EffectEdits value) {
        if (store == null) {
            return;
        }
        try {
            if (value.equals(EffectEdits.NONE)) {
                store.delete();
                return;
            }
            var sources = new TreeMap<String, String>();
            for (var file : sourceFiles(value)) {
                sources.put(file.getFileName().toString(), pin(file));
            }
            store.write(clientRoot, value, sources);
        } catch (IOException | RuntimeException failure) {
            LogManager.getLogger(EffectEditController.class).warn("The effect edits could not be saved", failure);
        }
    }

    private void restore() {
        EffectEdits saved = null;
        if (store != null) {
            try {
                var file = store.read();
                if (file != null) {
                    if (changedSources(file.sources()).isEmpty()) {
                        saved = file.edits();
                    } else {
                        store.keep();
                    }
                }
            } catch (IOException failure) {
                LogManager.getLogger(EffectEditController.class)
                        .warn("The saved effect edits could not be read", failure);
                try {
                    store.keep();
                } catch (IOException keptFailure) {
                    LogManager.getLogger(EffectEditController.class)
                            .warn("The saved effect edits stay as they are", keptFailure);
                }
            }
        }
        var value = saved;
        var left = nextStale();
        finished.add(() -> {
            if (value != null) {
                edits = value;
            }
            stale = left;
            restored = true;
        });
    }

    private @Nullable StaleEdits nextStale() {
        if (store == null) {
            return null;
        }
        try {
            var files = store.getKept();
            if (files.isEmpty()) {
                return null;
            }
            var file = files.getFirst();
            try {
                var saved = store.read(file);
                return saved == null ? null : new StaleEdits(file, changedSources(saved.sources()), saved.edits());
            } catch (IOException unreadable) {
                LogManager.getLogger(EffectEditController.class)
                        .warn("The kept effect edits could not be read", unreadable);
                return new StaleEdits(file, List.of(), null);
            }
        } catch (IOException failure) {
            LogManager.getLogger(EffectEditController.class).warn("The kept effect edits could not be listed", failure);
            return null;
        }
    }

    private void forget(Path file) {
        worker.execute(() -> {
            try {
                Files.deleteIfExists(file);
            } catch (IOException failure) {
                LogManager.getLogger(EffectEditController.class)
                        .warn("The kept effect edits could not be removed", failure);
            }
            var next = nextStale();
            finished.add(() -> stale = next);
        });
    }

    private List<String> changedSources(Map<String, String> sources) throws IOException {
        var changed = new ArrayList<String>();
        for (var source : sources.entrySet()) {
            var file = clientFile(source.getKey());
            if (file == null || !source.getValue().equals(PackageExportService.fileFingerprint(file))) {
                changed.add(source.getKey());
            } else {
                pinned.put(file, source.getValue());
            }
        }
        return changed;
    }

    private String pin(Path file) throws IOException {
        var known = pinned.get(file);
        if (known == null) {
            known = PackageExportService.fileFingerprint(file);
            pinned.put(file, known);
        }
        return known;
    }

    private EffectEditSession session() throws IOException {
        if (session == null) {
            session = EffectEditSession.open(clientRoot);
            session.imageFolder(imageFolder());
        }
        return session;
    }

    private Path imageFolder() throws IOException {
        if (imageFolder == null) {
            imageFolder = store != null ? store.getImageDirectory() : Files.createTempDirectory("cruma-effect-images");
        }
        return imageFolder;
    }

    private void closeSession() {
        if (session != null) {
            session.close();
            session = null;
        }
    }

    private List<Path> sourceFiles(EffectEdits value) throws IOException {
        var names = new LinkedHashSet<String>();
        for (var copy : value.copies().values()) {
            names.add(packageOf(copy.source()));
        }
        for (var entry : value.classes().entrySet()) {
            names.add(packageOf(sourcePath(value, entry.getKey())));
            for (var added : entry.getValue().added()) {
                names.add(packageOf(sourcePath(value, added.fromClass())));
            }
        }
        if (!value.copies().isEmpty()) {
            names.add(EffectEdits.CUSTOM_PACKAGE.toLowerCase(Locale.ROOT));
        }
        var files = new ArrayList<Path>();
        var skills = EffectEditSession.findSkillPackageFile(clientRoot);
        if (!value.skills().isEmpty() && skills != null) {
            files.add(skills);
        }
        for (var name : names) {
            var file = clientFile(name + ".u");
            if (file != null) {
                files.add(file);
            }
        }
        return files;
    }

    private @Nullable Path clientFile(String name) throws IOException {
        for (var folder : list(clientRoot)) {
            var folderName = folder.getFileName().toString();
            if (folderName.equalsIgnoreCase("System") || folderName.equalsIgnoreCase("Animations")) {
                for (var file : list(folder)) {
                    if (file.getFileName().toString().equalsIgnoreCase(name)) {
                        return file;
                    }
                }
            }
        }
        return null;
    }

    private static PartsReader loaderReader(Path clientRoot, TextureQuality quality, WorldVariant variant) {
        return new PartsReader() {
            private @Nullable UnrealLoader loader;

            @Override
            public @Nullable EffectParts read(Preview preview) {
                if (loader == null) {
                    loader = new UnrealLoader(clientRoot, new Options(false, 0), true, quality);
                    loader.worldVariant(variant);
                }
                return loader.loadEffect(preview.effect(), preview.effectPackage(), preview.texturePackage());
            }

            @Override
            public void reset() {
                loader = null;
            }
        };
    }

    private static String sourcePath(EffectEdits value, String classPath) {
        var copy = value.copies().get(classPath);
        return copy == null ? classPath : copy.source();
    }

    private static String packageOf(String classPath) {
        var dot = classPath.indexOf('.');
        return (dot < 0 ? classPath : classPath.substring(0, dot)).toLowerCase(Locale.ROOT);
    }

    private static List<Path> list(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (var entries = Files.list(folder)) {
            return entries.toList();
        }
    }

    private static String message(Throwable failure) {
        var cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
        return cause.getMessage() == null ? cause.toString() : cause.getMessage();
    }
}
