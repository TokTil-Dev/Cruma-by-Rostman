package com.rost.cruma.viewer.ui;

import com.rost.cruma.viewer.render.EditorEffectStage;
import com.rost.cruma.viewer.render.ParticleEmitter;
import com.rost.cruma.viewer.render.ParticleSystem;
import com.rost.cruma.viewer.render.api.DebugSceneObject;
import com.rost.cruma.viewer.render.api.DebugSelection;
import com.rost.cruma.viewer.render.api.ViewerRenderBackend;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;
import org.jetbrains.annotations.Nullable;

public final class ObjectCatalog {
    static final int MOST_REPLACED_ACTORS = 256;
    static final int MOST_ROWS_IN_FRAME = 4096;
    private static final int MOST_ACTORS_FOUND = 1024;
    private static final long READ_NANOS_PER_FRAME = 4_000_000L;

    private static final Comparator<ReadRow> READ_ORDER = Comparator.comparing(ReadRow::owner)
            .thenComparingInt(ReadRow::rank)
            .thenComparing(ReadRow::actor)
            .thenComparing(ReadRow::fullName);
    private static final Comparator<Entry> ORDER = Comparator.comparing(
                    (Entry entry) -> entry.selection().owner())
            .thenComparingInt(entry -> rank(entry.selection()))
            .thenComparing(entry -> entry.selection().actor())
            .thenComparing(entry -> entry.selection().fullName());
    private long sceneRevision = Long.MIN_VALUE;
    private boolean editingRows;
    private long particleRevision = Long.MIN_VALUE;
    private long extraRevision = Long.MIN_VALUE;
    private Set<String> extraActors = Set.of();
    private ArrayList<Entry> entries = new ArrayList<>();
    private Map<String, String> owners = new HashMap<>();
    private Map<String, String> effectOwners = Map.of();
    private Set<String> effectActors = new HashSet<>();
    private @Nullable FullRead reading;
    private List<Entry> matches = List.of();
    private String query;
    private long revision;
    private final LongSupplier clock;

    public record Entry(DebugSelection selection, String searchText) {}

    private record ReadRow(Entry entry, String owner, int rank, String actor, String fullName) {
        static ReadRow of(DebugSelection selection) {
            var fullName = selection.fullName();
            return new ReadRow(
                    new Entry(selection, searchText(fullName, selection)),
                    selection.owner(),
                    ObjectCatalog.rank(selection),
                    selection.actor(),
                    fullName);
        }
    }

    private record ReadResult(
            ArrayList<Entry> entries,
            Map<String, String> owners,
            Map<String, String> effectOwners,
            Set<String> effectActors) {}

    private static final class FullRead {
        final long sceneRevision;
        final boolean editing;
        final long particleRevision;
        final long extraRevision;
        final List<DebugSceneObject> extra;
        final Set<String> hidden;
        final List<ParticleEmitter> emitters;
        final Set<String> placed = new HashSet<>();
        final ArrayList<DebugSceneObject> objects = new ArrayList<>();
        final ArrayList<ReadRow> effects = new ArrayList<>();
        int group;
        int emitter;

        @Nullable
        CompletableFuture<ReadResult> result;

        FullRead(
                long sceneRevision,
                boolean editing,
                long particleRevision,
                long extraRevision,
                List<DebugSceneObject> extra,
                Set<String> hidden,
                List<ParticleEmitter> emitters) {
            this.sceneRevision = sceneRevision;
            this.editing = editing;
            this.particleRevision = particleRevision;
            this.extraRevision = extraRevision;
            this.extra = List.copyOf(extra);
            this.hidden = hidden;
            this.emitters = emitters;
        }

        boolean isFor(long sceneRevision, boolean editing, long particleRevision, long extraRevision) {
            return this.sceneRevision == sceneRevision
                    && this.editing == editing
                    && this.particleRevision == particleRevision
                    && this.extraRevision == extraRevision;
        }

        int getRowCount() {
            return objects.size() + extra.size() + effects.size();
        }

        ReadResult sort() {
            var rows = new ReadRow[getRowCount()];
            var sceneRows = objects.size();
            var hiddenRows = extra.size();
            Arrays.parallelSetAll(rows, index -> {
                if (index < sceneRows) {
                    return ReadRow.of(DebugSelection.scene(objects.get(index), Float.NaN));
                }
                return index < sceneRows + hiddenRows
                        ? ReadRow.of(DebugSelection.scene(extra.get(index - sceneRows), Float.NaN))
                        : effects.get(index - sceneRows - hiddenRows);
            });
            Arrays.parallelSort(rows, READ_ORDER);
            var entries = new ArrayList<Entry>(rows.length);
            var owners = new HashMap<String, String>();
            var effectOwners = new HashMap<String, String>();
            var effectActors = new HashSet<String>();
            for (var row : rows) {
                entries.add(row.entry());
                (row.entry().selection().emitter() == null ? owners : effectOwners).put(row.actor(), row.owner());
                if (row.rank() > 0) {
                    effectActors.add(row.actor());
                }
            }
            return new ReadResult(entries, owners, effectOwners, effectActors);
        }
    }

    public ObjectCatalog() {
        this(System::nanoTime);
    }

    ObjectCatalog(LongSupplier clock) {
        this.clock = clock;
    }

    public boolean refresh(ViewerRenderBackend backend, ParticleSystem particles) {
        return refresh(backend, particles, false);
    }

    public boolean refresh(ViewerRenderBackend backend, ParticleSystem particles, boolean editing) {
        return refresh(backend, particles, editing, List.of(), Long.MIN_VALUE);
    }

    public boolean refresh(
            ViewerRenderBackend backend,
            ParticleSystem particles,
            boolean editing,
            List<DebugSceneObject> extra,
            long extraRevision) {
        var currentRevision = editing ? backend.getDebugSceneTopologyRevision() : backend.getDebugSceneRevision();
        var currentParticles = editing ? particles.getSceneRegistryRevision() : particles.getRegistryRevision();
        if (reading != null && !reading.isFor(currentRevision, editing, currentParticles, extraRevision)) {
            reading = null;
        }
        if (reading != null) {
            return continueReading(backend);
        }
        if (sceneRevision == currentRevision
                && particleRevision == currentParticles
                && this.extraRevision == extraRevision) {
            return false;
        }
        var changed = editing && editingRows && particleRevision == currentParticles
                ? backend.getEditorTopologyActorsSince(sceneRevision)
                : null;
        var hidden = actorsOf(extra);
        Set<String> replaced = null;
        if (changed != null) {
            replaced = new HashSet<>(changed);
            for (var actor : extraActors) {
                if (!hidden.contains(actor)) {
                    replaced.add(actor);
                }
            }
            for (var actor : hidden) {
                if (!extraActors.contains(actor)) {
                    replaced.add(actor);
                }
            }
        }
        if (replaced == null || replaced.size() > MOST_REPLACED_ACTORS) {
            reading = new FullRead(
                    currentRevision, editing, currentParticles, extraRevision, extra, hidden, particles.getEmitters());
            return continueReading(backend);
        }
        replaceRows(backend, replaced, extra);
        showRevisions(hidden, currentRevision, editing, currentParticles, extraRevision);
        return true;
    }

    public boolean isReading() {
        return reading != null;
    }

    public List<Entry> search(String text) {
        var normalized = text.strip().toLowerCase(Locale.ROOT);
        if (!normalized.equals(query)) {
            var words = words(normalized);
            matches = normalized.isEmpty()
                    ? Collections.unmodifiableList(entries)
                    : entries.parallelStream()
                            .filter(entry -> matches(entry, words))
                            .toList();
            query = normalized;
        }
        return matches;
    }

    public List<Entry> getRowsOf(String owner, String actor, String text) {
        var result = new ArrayList<Entry>();
        addRows(owner, actor, firstRow(owner, actor), words(text), result);
        return result;
    }

    public List<Entry> getRowsOf(Collection<String> actors, String text) {
        var words = words(text);
        if (actors.size() > MOST_ACTORS_FOUND) {
            var wanted = actors instanceof Set<String> set ? set : new HashSet<>(actors);
            return entries.parallelStream()
                    .filter(entry -> wanted.contains(entry.selection().actor()) && matches(entry, words))
                    .toList();
        }
        var firstRows = new TreeSet<Integer>();
        for (var actor : actors) {
            var owner = owners.get(actor);
            if (owner != null) {
                firstRows.add(firstRow(owner, actor));
            }
            var effectOwner = effectOwners.get(actor);
            if (effectOwner != null) {
                firstRows.add(firstRow(effectOwner, actor));
            }
        }
        var result = new ArrayList<Entry>();
        for (var row : firstRows) {
            var selection = entries.get(row).selection();
            addRows(selection.owner(), selection.actor(), row, words, result);
        }
        return result;
    }

    public int nextOwner(int row) {
        var owner = entries.get(row).selection().owner();
        var low = row + 1;
        var high = entries.size();
        while (low < high) {
            var middle = (low + high) >>> 1;
            if (entries.get(middle).selection().owner().equals(owner)) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low;
    }

    public int nextActor(int row) {
        var selection = entries.get(row).selection();
        var low = row + 1;
        var high = entries.size();
        while (low < high) {
            var middle = (low + high) >>> 1;
            if (isRowOf(entries.get(middle), selection.owner(), selection.actor())) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low;
    }

    public int size() {
        return entries.size();
    }

    public boolean isEffect(String actor) {
        return effectActors.contains(actor);
    }

    public long getRevision() {
        return revision;
    }

    private boolean continueReading(ViewerRenderBackend backend) {
        var read = reading;
        if (read.result == null) {
            var groups = backend.getDebugSceneObjectGroups();
            var deadline = clock.getAsLong() + READ_NANOS_PER_FRAME;
            while (read.group < groups || read.emitter < read.emitters.size()) {
                if (read.group < groups) {
                    for (var object : backend.getDebugSceneObjects(read.group++)) {
                        if (isListed(object)) {
                            read.objects.add(object);
                            if (EditorEffectStage.isEffectRow(object)) {
                                read.placed.add(object.actorName());
                            }
                        }
                    }
                } else {
                    var emitter = read.emitters.get(read.emitter++);
                    if (!read.editing || !read.placed.contains(emitter.getRenderActorName())) {
                        read.effects.add(ReadRow.of(DebugSelection.fx(emitter, Float.NaN)));
                    }
                }
                var more = read.group < groups || read.emitter < read.emitters.size();
                if (more && clock.getAsLong() >= deadline) {
                    return false;
                }
            }
            read.result = read.getRowCount() <= MOST_ROWS_IN_FRAME
                    ? CompletableFuture.completedFuture(read.sort())
                    : CompletableFuture.supplyAsync(read::sort);
        }
        if (!read.result.isDone()) {
            return false;
        }
        var result = read.result.join();
        entries = result.entries();
        owners = result.owners();
        effectOwners = result.effectOwners();
        effectActors = result.effectActors();
        reading = null;
        showRevisions(read.hidden, read.sceneRevision, read.editing, read.particleRevision, read.extraRevision);
        return true;
    }

    private void showRevisions(
            Set<String> hidden, long sceneRevision, boolean editing, long particleRevision, long extraRevision) {
        extraActors = hidden;
        this.sceneRevision = sceneRevision;
        editingRows = editing;
        this.particleRevision = particleRevision;
        this.extraRevision = extraRevision;
        query = null;
        revision++;
    }

    private void replaceRows(ViewerRenderBackend backend, Set<String> changed, List<DebugSceneObject> extra) {
        for (var actor : changed) {
            var owner = owners.remove(actor);
            if (owner != null) {
                var from = firstRow(owner, actor);
                var to = from;
                while (to < entries.size() && isRowOf(entries.get(to), owner, actor)) {
                    to++;
                }
                entries.subList(from, to).removeIf(entry -> entry.selection().emitter() == null);
            }
            if (!effectOwners.containsKey(actor)) {
                effectActors.remove(actor);
            }
        }
        var added = new ArrayList<Entry>();
        for (var object : backend.getEditorActorObjects(changed)) {
            if (isListed(object)) {
                add(added, DebugSelection.scene(object, Float.NaN));
            }
        }
        for (var object : extra) {
            if (changed.contains(object.actorName())) {
                add(added, DebugSelection.scene(object, Float.NaN));
            }
        }
        added.sort(ORDER);
        var start = 0;
        while (start < added.size()) {
            var first = added.get(start).selection();
            var end = start + 1;
            while (end < added.size() && isRowOf(added.get(end), first.owner(), first.actor())) {
                end++;
            }
            if (rank(first) > 0) {
                effectActors.add(first.actor());
            }
            entries.addAll(firstRow(first.owner(), first.actor()), added.subList(start, end));
            owners.put(first.actor(), first.owner());
            start = end;
        }
    }

    private int firstRow(String owner, String actor) {
        var rank = effectActors.contains(actor) ? 1 : 0;
        var low = 0;
        var high = entries.size();
        while (low < high) {
            var middle = (low + high) >>> 1;
            var selection = entries.get(middle).selection();
            var order = selection.owner().compareTo(owner);
            if (order == 0) {
                order = Integer.compare(rank(selection), rank);
            }
            if (order == 0) {
                order = selection.actor().compareTo(actor);
            }
            if (order < 0) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low;
    }

    private void addRows(String owner, String actor, int first, String[] words, List<Entry> result) {
        for (var row = first; row < entries.size() && isRowOf(entries.get(row), owner, actor); row++) {
            if (matches(entries.get(row), words)) {
                result.add(entries.get(row));
            }
        }
    }

    private static int rank(DebugSelection selection) {
        return selection.emitter() != null || EditorEffectStage.isEffectRow(selection.sceneObject()) ? 1 : 0;
    }

    private static Set<String> actorsOf(List<DebugSceneObject> objects) {
        var result = new HashSet<String>();
        for (var object : objects) {
            result.add(object.actorName());
        }
        return result;
    }

    private static boolean isRowOf(Entry entry, String owner, String actor) {
        return entry.selection().owner().equals(owner)
                && entry.selection().actor().equals(actor);
    }

    private static boolean isListed(DebugSceneObject object) {
        var actor = object.actorName();
        return actor == null || !actor.endsWith(ViewerRenderBackend.EDITOR_SURFACES);
    }

    private static void add(List<Entry> list, DebugSelection selection) {
        list.add(new Entry(selection, searchText(selection.fullName(), selection)));
    }

    private static String searchText(String fullName, DebugSelection selection) {
        return (fullName + " " + selection.typeName() + " " + selection.materialName()).toLowerCase(Locale.ROOT);
    }

    private static String[] words(String text) {
        return text.strip().toLowerCase(Locale.ROOT).split("\\s+");
    }

    private static boolean matches(Entry entry, String[] words) {
        for (var word : words) {
            if (!entry.searchText().contains(word)) {
                return false;
            }
        }
        return true;
    }
}
