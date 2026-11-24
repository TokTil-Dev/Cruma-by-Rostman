package com.rost.cruma.viewer.render;

import com.rost.cruma.core.editing.ActorTransform;
import com.rost.cruma.core.geometry.Bounds3f;
import com.rost.cruma.core.unreal.UEmitter.UParticleEmitter;
import com.rost.cruma.core.unreal.UObject;
import com.rost.cruma.core.unreal.primitives.URotator;
import com.rost.cruma.viewer.render.api.DebugSceneObject;
import com.rost.cruma.viewer.render.api.DebugSceneObject.Category;
import glm_.quat.Quat;
import glm_.vec3.Vec3;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jetbrains.annotations.Nullable;

public final class EditorEffectStage {
    public static final String ROW_KIND = "Effect";

    public static final String ROW_MESH = "Emitter";

    private static final int CELL = 2048;
    private static final float FIRST_TICK = 1f / 60f;
    private static final float REACH = 8;
    private static final int JOURNAL = 64;
    private static final Set<String> WHOLE = Set.of("");

    private final ParticleSystem particles;
    private final SwingSystem swings;
    private final Map<String, Map<String, List<ParticleEmitter>>> byMap = new HashMap<>();
    private final Map<String, ActorTransform> placed = new HashMap<>();
    private final Set<String> removed = new HashSet<>();
    private final Set<String> hidden = new HashSet<>();
    private final Map<String, String> copies = new LinkedHashMap<>();
    private final Map<String, String> copyCarriers = new HashMap<>();
    private Shares shares = Shares.NONE;
    private Sources sources = Sources.NONE;
    private final Map<String, List<ParticleEmitter>> out = new HashMap<>();
    private final Map<ParticleEmitter, Original> originals = new IdentityHashMap<>();
    private final Map<ParticleEmitter, ParticleEmitter> twinSources = new IdentityHashMap<>();
    private Function<String, @Nullable ActorTransform> mapPlaces = path -> null;
    private final Map<String, DebugSceneObject> rowCache = new LinkedHashMap<>();
    private final Set<String> staleRows = new HashSet<>();
    private boolean allRowsStale = true;
    private long rowsLayout = -1;
    private List<DebugSceneObject> rowList = List.of();
    private Map<Long, List<DebugSceneObject>> cells = Map.of();
    private long rowsBuilt;
    private long topology;
    private final LinkedHashMap<Long, Set<String>> journal = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Set<String>> eldest) {
            return size() > JOURNAL;
        }
    };

    private record Original(Vec3 place, Quat turn, Vec3 scale) {}

    public interface Shares {
        Shares NONE = new Shares() {
            @Override
            public boolean attach(ParticleEmitter source, ParticleEmitter twin) {
                return true;
            }

            @Override
            public void detach(ParticleEmitter twin) {}
        };

        boolean attach(ParticleEmitter source, ParticleEmitter twin);

        void detach(ParticleEmitter twin);
    }

    public interface Sources {
        Sources NONE = (actor, use) -> use.accept(List.of());

        void read(String actor, Consumer<List<UObject>> use);
    }

    public EditorEffectStage(ParticleSystem particles, SwingSystem swings) {
        this.particles = particles;
        this.swings = swings;
    }

    public static boolean isEffectRow(DebugSceneObject row) {
        return ROW_KIND.equals(row.lighting());
    }

    public void setShares(Shares next) {
        shares = next;
    }

    public void setSources(Sources next) {
        sources = next;
    }

    public void setMapPlaces(@Nullable Function<String, @Nullable ActorTransform> places) {
        mapPlaces = places == null ? path -> null : places;
        change(null);
    }

    public void mapLoaded(String map, List<ParticleEmitter> emitters) {
        var actors = new LinkedHashMap<String, List<ParticleEmitter>>();
        for (var emitter : emitters) {
            var name = emitter.getRenderActorName();
            if (name != null && name.startsWith(map + '.')) {
                actors.computeIfAbsent(name, ignored -> new ArrayList<>()).add(emitter);
            }
        }
        byMap.put(map, actors);
        for (var entry : List.copyOf(actors.entrySet())) {
            var actor = entry.getKey();
            var place = placed.get(actor);
            if (place != null) {
                apply(actor, place);
            }
            if (removed.contains(actor) || hidden.contains(actor)) {
                takeOut(actor);
            }
        }
        for (var copy : copies.keySet()) {
            if (copy.startsWith(map + '.')) {
                buildCopy(copy);
            }
        }
        change(null);
    }

    public void mapRemoved(String map) {
        var actors = byMap.remove(map);
        if (actors == null) {
            return;
        }
        for (var entry : actors.entrySet()) {
            out.remove(entry.getKey());
            entry.getValue().forEach(originals::remove);
            if (copies.containsKey(entry.getKey())) {
                dropTwins(entry.getKey(), entry.getValue());
            }
        }
        change(null);
    }

    public void addCopy(String actor, String source, ActorTransform place) {
        addCopy(actor, source, place, null);
    }

    public void addCopy(String actor, String source, ActorTransform place, @Nullable String carrier) {
        if (carrier != null) {
            copyCarriers.put(actor, carrier);
        } else {
            copyCarriers.remove(actor);
        }
        if (source.equals(copies.get(actor))) {
            return;
        }
        copies.put(actor, source);
        placed.put(actor, place);
        buildCopy(actor);
        change(Set.of(actor));
    }

    public boolean hasEffect(String actor) {
        return emittersOf(actor) != null;
    }

    public boolean isCopy(String actor) {
        return copies.containsKey(actor);
    }

    public void forgetCopy(String actor) {
        if (copies.remove(actor) == null) {
            return;
        }
        var dot = actor.indexOf('.');
        var actors = byMap.get(actor.substring(0, dot));
        var twins = actors == null ? null : actors.remove(actor);
        var away = out.remove(actor);
        if (twins != null) {
            dropTwins(actor, twins);
        } else if (away != null) {
            dropTwins(actor, away);
        } else {
            swings.forgetCopiedEffect(actor);
        }
        placed.remove(actor);
        removed.remove(actor);
        hidden.remove(actor);
        change(Set.of(actor));
    }

    public void place(Map<String, ActorTransform> placements) {
        if (placements.isEmpty()) {
            return;
        }
        for (var entry : placements.entrySet()) {
            placed.put(entry.getKey(), entry.getValue());
            apply(entry.getKey(), entry.getValue());
        }
        change(placements.keySet());
    }

    public void setRemoved(Collection<String> actors, boolean value) {
        if (actors.isEmpty()) {
            return;
        }
        for (var actor : actors) {
            if (value ? removed.add(actor) : removed.remove(actor)) {
                refreshOut(actor);
            }
        }
        change(Set.copyOf(actors));
    }

    public void setHidden(Collection<String> actors, boolean value) {
        var changed = new HashSet<String>();
        for (var actor : actors) {
            if (value ? hidden.add(actor) : hidden.remove(actor)) {
                refreshOut(actor);
                changed.add(actor);
            }
        }
        if (!changed.isEmpty()) {
            change(changed);
        }
    }

    public void clear() {
        for (var copy : copies.keySet()) {
            var actors = byMap.get(copy.substring(0, copy.indexOf('.')));
            var twins = actors == null ? null : actors.remove(copy);
            if (twins != null) {
                dropTwins(copy, twins);
            }
            out.remove(copy);
        }
        copies.clear();
        removed.clear();
        hidden.clear();
        placed.clear();
        for (var actor : List.copyOf(out.keySet())) {
            refreshOut(actor);
        }
        for (var entry : originals.entrySet()) {
            var emitter = entry.getKey();
            var original = entry.getValue();
            var before = emitter.getActorPos();
            var x = before.getX();
            var y = before.getY();
            emitter.moveActor(original.place());
            emitter.setActorTransform(original.turn(), original.scale());
            particles.relocate(emitter, x, y);
        }
        originals.clear();
        change(null);
    }

    public long getTopology() {
        return topology;
    }

    public @Nullable Set<String> getTopologyActorsSince(long since) {
        if (since > topology) {
            return null;
        }
        var result = new HashSet<String>();
        for (var revision = since + 1; revision <= topology; revision++) {
            var actors = journal.get(revision);
            if (actors == null || actors == WHOLE) {
                return null;
            }
            result.addAll(actors);
        }
        return result;
    }

    public List<DebugSceneObject> getRows() {
        refreshRows();
        return rowList;
    }

    public long getRowsBuilt() {
        return rowsBuilt;
    }

    public List<DebugSceneObject> getRowsNear(float x, float y, float reach) {
        refreshRows();
        var result = new ArrayList<DebugSceneObject>();
        var lowX = Math.floorDiv((int) Math.floor(x - reach), CELL);
        var highX = Math.floorDiv((int) Math.floor(x + reach), CELL);
        var lowY = Math.floorDiv((int) Math.floor(y - reach), CELL);
        var highY = Math.floorDiv((int) Math.floor(y + reach), CELL);
        for (var cellX = lowX; cellX <= highX; cellX++) {
            for (var cellY = lowY; cellY <= highY; cellY++) {
                var rows = cells.get(cellKey(cellX, cellY));
                if (rows == null) {
                    continue;
                }
                for (var row : rows) {
                    var bounds = row.bounds();
                    var dx = (bounds.minX() + bounds.maxX()) * .5f - x;
                    var dy = (bounds.minY() + bounds.maxY()) * .5f - y;
                    if (dx * dx + dy * dy <= reach * reach) {
                        result.add(row);
                    }
                }
            }
        }
        return result;
    }

    public List<DebugSceneObject> getRows(Collection<String> actors) {
        var rows = new ArrayList<DebugSceneObject>();
        for (var actor : actors) {
            if (emittersOf(actor) != null) {
                var row = row(actor);
                if (row != null) {
                    rows.add(row);
                }
            }
        }
        return rows;
    }

    public @Nullable ActorTransform getSavedPlace(String actor) {
        var place = placed.get(actor);
        return place != null ? place : mapPlaces.apply(actor);
    }

    private @Nullable DebugSceneObject row(String actor) {
        if (out.containsKey(actor)) {
            return null;
        }
        var place = getSavedPlace(actor);
        var emitters = emittersOf(actor);
        Vec3 at;
        var saved = place == null ? swings.getSavedLocation(actor) : null;
        if (place != null) {
            var location = place.location();
            at = new Vec3(location.x(), location.y(), location.z());
        } else if (saved != null) {
            at = saved;
        } else if (emitters != null && !emitters.isEmpty()) {
            at = emitters.getFirst().getActorPos();
        } else {
            return null;
        }
        var map = actor.substring(0, actor.indexOf('.'));
        var count = emitters == null ? 0 : emitters.size();
        return new DebugSceneObject(
                actor.substring(actor.lastIndexOf('.') + 1),
                ROW_MESH,
                count == 1 ? "1 emitter" : count + " emitters",
                map,
                0,
                -1,
                new Bounds3f(
                        at.getX() - REACH,
                        at.getY() - REACH,
                        at.getZ() - REACH,
                        at.getX() + REACH,
                        at.getY() + REACH,
                        at.getZ() + REACH),
                Category.STATIC_MESH,
                ROW_KIND,
                Integer.toUnsignedLong(actor.hashCode()) | 1L << 61,
                actor,
                List.of(),
                "Unavailable",
                -1,
                false);
    }

    private void apply(String actor, ActorTransform place) {
        var emitters = emittersOf(actor);
        if (emitters == null) {
            return;
        }
        var at = place.location();
        var location = new Vec3(at.x(), at.y(), at.z());
        var turn = place.rotation();
        var rotation = new URotator(turn.pitch(), turn.yaw(), turn.roll());
        var before = new float[emitters.size() * 2];
        for (var index = 0; index < emitters.size(); index++) {
            var emitter = emitters.get(index);
            before[index * 2] = emitter.getActorPos().getX();
            before[index * 2 + 1] = emitter.getActorPos().getY();
        }
        if (swings.placesEffect(actor)) {
            swings.placeFollower(actor, location, rotation);
        } else {
            for (var emitter : emitters) {
                originals.computeIfAbsent(
                        emitter,
                        ignored -> new Original(
                                new Vec3(emitter.getActorPos()),
                                new Quat(emitter.getActorRotation()),
                                new Vec3(emitter.getActorScale())));
                emitter.moveActor(location);
                emitter.turnActor(rotation);
            }
        }
        for (var index = 0; index < emitters.size(); index++) {
            particles.relocate(emitters.get(index), before[index * 2], before[index * 2 + 1]);
        }
    }

    private void refreshOut(String actor) {
        var wanted = removed.contains(actor) || hidden.contains(actor);
        if (wanted) {
            takeOut(actor);
            return;
        }
        var back = out.remove(actor);
        if (back != null) {
            back.forEach(particles::addFromEdit);
            var place = placed.get(actor);
            if (copies.containsKey(actor) && place != null) {
                carryCopy(actor, back, place);
                apply(actor, place);
                warm(back);
            }
        }
    }

    private void buildCopy(String copy) {
        var dot = copy.indexOf('.');
        var actors = byMap.get(copy.substring(0, dot));
        var live = emittersOf(copies.get(copy));
        if (actors == null) {
            return;
        }
        var old = actors.remove(copy);
        if (old != null) {
            dropTwins(copy, old);
        }
        var twins = new ArrayList<ParticleEmitter>();
        if (live != null && !live.isEmpty()) {
            sources.read(originOf(copy), children -> {
                for (var source : live) {
                    var order = source.getRenderChildOrder();
                    if (order < 0
                            || order >= children.size()
                            || !(children.get(order) instanceof UParticleEmitter child)) {
                        continue;
                    }
                    var twin = source.createTwin(copy, source.getActorPos(), child);
                    if (shares.attach(source, twin)) {
                        twins.add(twin);
                        twinSources.put(twin, source);
                    }
                }
            });
        }
        actors.put(copy, twins);
        var place = placed.get(copy);
        if (removed.contains(copy) || hidden.contains(copy)) {
            if (place != null) {
                carryCopy(copy, twins, place);
            }
            out.put(copy, twins);
            return;
        }
        twins.forEach(particles::addFromEdit);
        if (place != null) {
            carryCopy(copy, twins, place);
            apply(copy, place);
        }
        warm(twins);
    }

    private void warm(List<ParticleEmitter> twins) {
        for (var twin : twins) {
            if (!twin.isWarmedUp()) {
                twin.update(FIRST_TICK);
            }
        }
    }

    private String originOf(String copy) {
        var origin = copy;
        for (var steps = 0; steps <= copies.size(); steps++) {
            var source = copies.get(origin);
            if (source == null) {
                break;
            }
            origin = source;
        }
        return origin;
    }

    private void carryCopy(String copy, List<ParticleEmitter> twins, ActorTransform place) {
        var at = place.location();
        var turn = place.rotation();
        var sources = new ArrayList<ParticleEmitter>(twins.size());
        for (var twin : twins) {
            sources.add(twinSources.get(twin));
        }
        swings.addCopiedEffect(
                copy,
                twins,
                sources,
                new Vec3(at.x(), at.y(), at.z()),
                new URotator(turn.pitch(), turn.yaw(), turn.roll()),
                copyCarriers.get(copy));
    }

    private void dropTwins(String copy, List<ParticleEmitter> twins) {
        particles.removeAllFromEdit(twins);
        twins.forEach(shares::detach);
        twins.forEach(originals::remove);
        twins.forEach(twinSources::remove);
        swings.forgetCopiedEffect(copy);
    }

    private void takeOut(String actor) {
        if (out.containsKey(actor)) {
            return;
        }
        var emitters = emittersOf(actor);
        if (emitters != null) {
            particles.removeAllFromEdit(emitters);
            out.put(actor, emitters);
        }
    }

    private @Nullable List<ParticleEmitter> emittersOf(String actor) {
        var dot = actor.indexOf('.');
        var actors = dot <= 0 ? null : byMap.get(actor.substring(0, dot));
        return actors == null ? null : actors.get(actor);
    }

    private void change(@Nullable Set<String> actors) {
        topology++;
        journal.put(topology, actors == null ? WHOLE : Set.copyOf(actors));
        if (actors == null) {
            allRowsStale = true;
        } else {
            staleRows.addAll(actors);
        }
    }

    private void refreshRows() {
        if (swings.getLayout() != rowsLayout) {
            rowsLayout = swings.getLayout();
            allRowsStale = true;
        }
        if (!allRowsStale && staleRows.isEmpty()) {
            return;
        }
        if (allRowsStale) {
            rowCache.clear();
            for (var actors : byMap.values()) {
                for (var actor : actors.keySet()) {
                    cacheRow(actor);
                }
            }
        } else {
            for (var actor : staleRows) {
                rowCache.remove(actor);
                if (emittersOf(actor) != null) {
                    cacheRow(actor);
                }
            }
        }
        allRowsStale = false;
        staleRows.clear();
        rowList = List.copyOf(rowCache.values());
        cells = new HashMap<>();
        for (var row : rowList) {
            var bounds = row.bounds();
            var cellX = Math.floorDiv((int) Math.floor((bounds.minX() + bounds.maxX()) * .5f), CELL);
            var cellY = Math.floorDiv((int) Math.floor((bounds.minY() + bounds.maxY()) * .5f), CELL);
            cells.computeIfAbsent(cellKey(cellX, cellY), ignored -> new ArrayList<>())
                    .add(row);
        }
    }

    private void cacheRow(String actor) {
        var row = row(actor);
        rowsBuilt++;
        if (row != null) {
            rowCache.put(actor, row);
        }
    }

    private static long cellKey(int cellX, int cellY) {
        return (long) cellX << 32 | cellY & 0xffffffffL;
    }
}
