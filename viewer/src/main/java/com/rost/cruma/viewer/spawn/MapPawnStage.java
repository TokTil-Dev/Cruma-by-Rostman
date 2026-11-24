package com.rost.cruma.viewer.spawn;

import com.rost.cruma.core.geometry.Bounds3f;
import com.rost.cruma.core.npc.script.ScriptPawn.Host;
import com.rost.cruma.core.npc.script.ScriptValues.Coords;
import com.rost.cruma.core.npc.script.ScriptValues.Rotator;
import com.rost.cruma.core.npc.script.ScriptValues.Vector;
import com.rost.cruma.core.npc.script.ScriptedClass;
import com.rost.cruma.core.unreal.MapPawnClasses;
import com.rost.cruma.core.unreal.SkeletalPose;
import com.rost.cruma.core.unreal.primitives.URotator;
import com.rost.cruma.core.world.EffectParts;
import com.rost.cruma.core.world.MapPawn;
import com.rost.cruma.core.world.MapPawnPlayer;
import com.rost.cruma.core.world.MapPawnPlayer.Moment;
import com.rost.cruma.core.world.MapPawnScript;
import com.rost.cruma.core.world.NpcAnimation.Pose;
import com.rost.cruma.viewer.render.Camera;
import com.rost.cruma.viewer.render.Scene;
import com.rost.cruma.viewer.render.SpawnOverlayState;
import com.rost.cruma.viewer.render.SpawnOverlayState.LivePose;
import com.rost.cruma.viewer.render.SpawnOverlayState.ModelInstance;
import com.rost.cruma.viewer.render.api.DebugSceneObject;
import com.rost.cruma.viewer.render.api.DebugSceneObject.Category;
import com.rost.cruma.viewer.render.api.EffectPreview;
import com.rost.cruma.viewer.spawn.MapPawnModels.Figure;
import com.rost.cruma.viewer.spawn.ScriptEffects.Body;
import com.rost.cruma.viewer.spawn.ScriptEffects.Bone;
import glm_.vec3.Vec3;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.DoubleFunction;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public final class MapPawnStage implements AutoCloseable {
    public static final float REACH = 16_000;

    public static final long MOST_WAIT_NANOS = TimeUnit.SECONDS.toNanos(20);

    private static final Logger log = LogManager.getLogger(MapPawnStage.class);
    private static final long PAUSE_NANOS = TimeUnit.MILLISECONDS.toNanos(1);
    private static final float UNREAD_REACH = 32;
    private static final int EDITOR_JOURNAL = 64;
    private static final Set<String> WHOLE = Set.of("");
    private static final int MOST_EVENTS = 32;
    private static final float COLLISION_HEIGHT = 78;
    private static final float COLLISION_RADIUS = 34;

    private final boolean capture;
    private final LongSupplier clock;
    private final Runnable pause;
    private Figures figures = Figures.NONE;
    private final List<Entry> entries = new ArrayList<>();
    private final Map<String, Entry> paths = new HashMap<>();
    private final Map<String, Double> origins = new HashMap<>();
    private final Map<String, Track> tracks = new HashMap<>();
    private long revision = -1;
    private long frame;
    private List<Entry> drawn = List.of();
    private Set<String> waiting = Set.of();
    private long waitingSince;
    private boolean waitReported;
    private final Set<String> reportedMissing = new HashSet<>();
    private final Map<String, float[]> editorDeltas = new HashMap<>();
    private final Map<String, MapPawn> editorAdded = new LinkedHashMap<>();
    private final Set<String> editorRemoved = new HashSet<>();
    private final Set<String> editorHidden = new HashSet<>();
    private @Nullable Set<String> editorIsolated;
    private long addedRevision;
    private long syncedAddedRevision = -1;
    private long editorRevision;
    private long editorTopology;
    private final LinkedHashMap<Long, Set<String>> editorJournal = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Set<String>> eldest) {
            return size() > EDITOR_JOURNAL;
        }
    };
    private Supplier<@Nullable EffectPreview> effects = () -> null;
    private final Map<String, Scripted> scripted = new LinkedHashMap<>();
    private final Map<String, ScriptedClass> classes = new HashMap<>();
    private final ArrayDeque<Event> events = new ArrayDeque<>();
    private final List<String> triggered = new ArrayList<>();
    private final Set<String> pawnClasses = new HashSet<>();

    public MapPawnStage(boolean capture) {
        this(capture, System::nanoTime, () -> LockSupport.parkNanos(PAUSE_NANOS));
    }

    MapPawnStage(boolean capture, LongSupplier clock, Runnable pause) {
        this.capture = capture;
        this.clock = clock;
        this.pause = pause;
    }

    public interface Figures extends AutoCloseable {
        Figures NONE = new Figures() {
            @Override
            public @Nullable Figure getFigure(String mesh, List<String> skins) {
                return null;
            }

            @Override
            public boolean isMissing(String mesh, List<String> skins) {
                return true;
            }

            @Override
            public void close() {
            }
        };

        @Nullable
        Figure getFigure(String mesh, List<String> skins);

        boolean isMissing(String mesh, List<String> skins);

        default @Nullable EffectParts getEffect(String effectClass) {
            return null;
        }

        default boolean hasReadEffect(String effectClass) {
            return true;
        }

        default String getWait(String pawnClass) {
            return "";
        }

        @Override
        void close();
    }

    public record Hit(String path, String mesh, float distance) {}

    public record Place(
            MapPawn pawn,
            float @Nullable [] matrix,
            @Nullable Figure figure,
            boolean missing,
            @Nullable Moment moment,
            boolean drawn) {}

    static float intersectBox(float[] origin, float[] direction, float[] box) {
        var near = Float.NEGATIVE_INFINITY;
        var far = Float.POSITIVE_INFINITY;
        for (var axis = 0; axis < 3; axis++) {
            var low = box[axis];
            var high = box[axis + 3];
            if (Math.abs(direction[axis]) < 1e-9f) {
                if (origin[axis] < low || origin[axis] > high) {
                    return -1;
                }
                continue;
            }
            var first = (low - origin[axis]) / direction[axis];
            var second = (high - origin[axis]) / direction[axis];
            near = Math.max(near, Math.min(first, second));
            far = Math.min(far, Math.max(first, second));
        }
        if (far < near || far < 0) {
            return -1;
        }
        return Math.max(near, 0);
    }

    public void open(Figures source) {
        endScripts(path -> true);
        classes.clear();
        events.clear();
        triggered.clear();
        figures.close();
        figures = source;
        revision = -1;
        entries.clear();
        paths.clear();
        tracks.clear();
        drawn = List.of();
        waiting = Set.of();
    }

    public void setEffects(Supplier<@Nullable EffectPreview> source) {
        effects = source;
    }

    public void trigger(String event) {
        triggered.add(event);
    }

    public void update(Scene scene, Camera camera, SpawnOverlayState overlay, double seconds, boolean shown) {
        frame++;
        var time = capture ? 0 : seconds;
        sync(scene, time);
        runScripts(time);
        if (!shown || entries.isEmpty()) {
            if (!overlay.pawns.isEmpty() || !overlay.pawnShadows.isEmpty()) {
                overlay.pawns = List.of();
                overlay.pawnShadows = List.of();
            }
            drawn = List.of();
            noteWaiting(Set.of());
            showEffects(camera, time, false);
            return;
        }
        if (capture) {
            awaitModels();
            runScripts(time);
        }
        var eye = camera.getPosition();
        var frustum = camera.getFrustum();
        var instances = new ArrayList<ModelInstance>();
        var casters = new ArrayList<ModelInstance>();
        var shownNow = new ArrayList<Entry>();
        Set<String> wanted = null;
        var reach = capture || camera.isInteractiveOrtho() ? Double.POSITIVE_INFINITY : (double) REACH * REACH;
        for (var entry : entries) {
            if (!isShown(entry)) {
                continue;
            }
            var dx = entry.at[0] - eye.getX();
            var dy = entry.at[1] - eye.getY();
            var dz = entry.at[2] - eye.getZ();
            if (dx * dx + dy * dy + dz * dz > reach) {
                continue;
            }
            var figure = figures.getFigure(entry.pawn.mesh(), entry.pawn.skins());
            if (figure == null) {
                wanted = want(entry, wanted);
                continue;
            }
            entry.place(figure);
            if (!frustum.intersectsSphere(entry.center[0], entry.center[1], entry.center[2], entry.radius)) {
                continue;
            }
            var track = track(entry, figure);
            track.pose(frame, time - entry.origin);
            var instance = new ModelInstance(
                    figure.model(),
                    entry.matrix[12],
                    entry.matrix[13],
                    entry.matrix[14],
                    0,
                    entry.scale,
                    track.pose,
                    entry.matrix);
            instances.add(instance);
            if (entry.pawn.castsShadow()) {
                casters.add(instance);
            }
            entry.figure = figure;
            entry.track = track;
            entry.drawnFrame = frame;
            shownNow.add(entry);
        }
        overlay.pawns = List.copyOf(instances);
        overlay.pawnShadows = List.copyOf(casters);
        drawn = List.copyOf(shownNow);
        noteWaiting(wanted == null ? Set.of() : wanted);
        showEffects(camera, time, true);
    }

    public boolean isSettled() {
        if (waiting.isEmpty()) {
            return true;
        }
        if (clock.getAsLong() - waitingSince < MOST_WAIT_NANOS) {
            return false;
        }
        if (!waitReported) {
            waitReported = true;
            log.warn(
                    "Capture goes on without the models of pawns {}: they were not read in {} seconds",
                    new TreeSet<>(waiting),
                    TimeUnit.NANOSECONDS.toSeconds(MOST_WAIT_NANOS));
        }
        return true;
    }

    public @Nullable Hit pick(Vec3 origin, Vec3 direction, float limit) {
        Hit best = null;
        var nearest = limit;
        for (var entry : drawn) {
            var distance = entry.intersect(origin, direction, nearest);
            if (distance >= 0 && distance < nearest) {
                nearest = distance;
                best = new Hit(entry.pawn.path(), entry.pawn.mesh(), distance);
            }
        }
        return best;
    }

    public List<MapPawn> getPawns() {
        var pawns = new ArrayList<MapPawn>(entries.size());
        for (var entry : entries) {
            if (!entry.added) {
                pawns.add(entry.pawn);
            }
        }
        return pawns;
    }

    public @Nullable Place findPlace(String path) {
        var entry = paths.get(path);
        if (entry == null) {
            return null;
        }
        var pawn = entry.pawn;
        var figure = figures.getFigure(pawn.mesh(), pawn.skins());
        var track = entry.track;
        return new Place(
                pawn,
                entry.matrix.length == 16 ? entry.matrix.clone() : null,
                figure,
                figure == null && figures.isMissing(pawn.mesh(), pawn.skins()),
                track == null ? null : track.moment,
                entry.drawnFrame == frame);
    }

    public boolean hasPawn(String path) {
        return paths.containsKey(path) || editorAdded.containsKey(path);
    }

    public boolean isPawnClassName(String path) {
        var dot = path.lastIndexOf('.');
        var end = path.length();
        while (end > dot + 1 && Character.isDigit(path.charAt(end - 1))) {
            end--;
        }
        return end < path.length()
                && pawnClasses.contains(path.substring(0, end).toLowerCase(Locale.ROOT));
    }

    public @Nullable MapPawn findSavedPawn(String path) {
        var entry = paths.get(path);
        return entry != null ? entry.pawn : editorAdded.get(path);
    }

    public void setEditorDeltas(Map<String, float[]> updates, Collection<String> restores) {
        for (var path : restores) {
            if (editorDeltas.remove(path) != null) {
                moved(path);
            }
        }
        for (var update : updates.entrySet()) {
            editorDeltas.put(update.getKey(), update.getValue().clone());
            moved(update.getKey());
        }
    }

    public void replaceEditorDeltas(Map<String, float[]> deltas) {
        var restores = new HashSet<>(editorDeltas.keySet());
        restores.removeAll(deltas.keySet());
        setEditorDeltas(deltas, restores);
    }

    public void addEditorPawn(MapPawn pawn) {
        if (pawn.equals(editorAdded.put(pawn.path(), pawn))) {
            return;
        }
        addedRevision++;
        noteTopology(Set.of(pawn.path()));
    }

    public void setEditorRemoved(String path, boolean removed) {
        if (removed ? editorRemoved.add(path) : editorRemoved.remove(path)) {
            noteTopology(Set.of(path));
        }
    }

    public void setEditorHidden(Collection<String> paths, boolean hidden) {
        var changed = new HashSet<String>();
        for (var path : paths) {
            if (hidden ? editorHidden.add(path) : editorHidden.remove(path)) {
                changed.add(path);
            }
        }
        if (!changed.isEmpty()) {
            noteTopology(changed);
        }
    }

    public void isolateEditor(@Nullable Set<String> actors) {
        var next = actors == null ? null : Set.copyOf(actors);
        if (!Objects.equals(next, editorIsolated)) {
            editorIsolated = next;
            editorRevision++;
        }
    }

    public void clearEditor() {
        var changed = !editorDeltas.isEmpty()
                || !editorAdded.isEmpty()
                || !editorRemoved.isEmpty()
                || !editorHidden.isEmpty();
        for (var path : List.copyOf(editorDeltas.keySet())) {
            editorDeltas.remove(path);
            moved(path);
        }
        if (!editorAdded.isEmpty()) {
            editorAdded.clear();
            addedRevision++;
        }
        editorRemoved.clear();
        editorHidden.clear();
        if (changed) {
            noteTopology(null);
        }
        isolateEditor(null);
    }

    public long getEditorRevision() {
        return editorRevision;
    }

    public long getEditorTopology() {
        return editorTopology;
    }

    public @Nullable Set<String> getEditorTopologyActorsSince(long since) {
        if (since > editorTopology) {
            return null;
        }
        var result = new HashSet<String>();
        for (var revision = since + 1; revision <= editorTopology; revision++) {
            var actors = editorJournal.get(revision);
            if (actors == null || actors == WHOLE) {
                return null;
            }
            result.addAll(actors);
        }
        return result;
    }

    public List<DebugSceneObject> getEditorRows() {
        var rows = new ArrayList<DebugSceneObject>();
        for (var entry : entries) {
            if (isListed(entry.pawn.path())) {
                rows.add(row(entry));
            }
        }
        return rows;
    }

    public List<DebugSceneObject> getEditorRows(Collection<String> actors) {
        var rows = new ArrayList<DebugSceneObject>();
        for (var actor : actors) {
            var entry = paths.get(actor);
            if (entry != null && isListed(actor)) {
                rows.add(row(entry));
            }
        }
        return rows;
    }

    @Override
    public void close() {
        open(Figures.NONE);
    }

    private void sync(Scene scene, double seconds) {
        if (scene.getMapPawnsRevision() == revision && addedRevision == syncedAddedRevision) {
            return;
        }
        if (scene.getMapPawnsRevision() != revision) {
            noteTopology(null);
        }
        revision = scene.getMapPawnsRevision();
        syncedAddedRevision = addedRevision;
        var maps = scene.getMapPawns();
        origins.keySet().retainAll(maps.keySet());
        entries.clear();
        paths.clear();
        for (var map : maps.entrySet()) {
            var origin = origins.computeIfAbsent(map.getKey(), _ -> seconds);
            var lobby = MapPawnPlayer.isLobby(map.getKey());
            for (var pawn : map.getValue()) {
                addEntry(new Entry(pawn, origin, lobby, false));
            }
            for (var pawn : editorAdded.values()) {
                if (pawn.path().startsWith(map.getKey() + '.')) {
                    addEntry(new Entry(pawn, origin, lobby, true));
                }
            }
        }
        var keys = new HashSet<String>();
        pawnClasses.clear();
        for (var entry : entries) {
            keys.add(trackKey(entry));
            var script = entry.pawn.script();
            if (script != null && script.className().contains(".")) {
                pawnClasses.add(script.className().toLowerCase(Locale.ROOT));
            }
        }
        tracks.keySet().retainAll(keys);
        drawn = List.of();
        endScripts(path -> {
            var entry = paths.get(path);
            var running = scripted.get(path);
            return entry == null || running == null || entry.origin != running.origin;
        });
        for (var running : scripted.values()) {
            running.entry = Objects.requireNonNull(paths.get(running.path));
        }
    }

    private void runScripts(double time) {
        for (var event : triggered) {
            events.addLast(new Event(event, time));
            if (events.size() > MOST_EVENTS) {
                events.removeFirst();
            }
        }
        var begun = new HashSet<String>();
        for (var entry : entries) {
            var script = entry.pawn.script();
            if (script == null || scripted.containsKey(entry.pawn.path())) {
                continue;
            }
            var figure = figures.getFigure(entry.pawn.mesh(), entry.pawn.skins());
            if (figure == null) {
                continue;
            }
            var type = classes.computeIfAbsent(script.className(), _ -> ScriptedClass.of(script.sources()));
            var ready = true;
            if (type != null && !capture) {
                for (var effect : type.getEffects()) {
                    if (!figures.hasReadEffect(effect)) {
                        figures.getEffect(effect);
                        ready = false;
                    }
                }
            }
            if (!ready) {
                continue;
            }
            var running = new Scripted(entry, figure, type);
            scripted.put(running.path, running);
            begun.add(running.path);
            running.script.begin(entry.lobby);
            for (var event : events) {
                if (event.time() >= entry.origin) {
                    running.script.runTo(event.time() - entry.origin);
                    running.script.trigger(event.name());
                }
            }
        }
        for (var running : scripted.values()) {
            running.script.runTo(Math.max(0, time - running.origin));
            if (!begun.contains(running.path)) {
                for (var event : triggered) {
                    running.script.trigger(event);
                }
            }
        }
        triggered.clear();
    }

    private void showEffects(Camera camera, double time, boolean shown) {
        var preview = capture ? null : effects.get();
        var eye = camera.getPosition();
        var reach = camera.isInteractiveOrtho() ? Double.POSITIVE_INFINITY : (double) REACH * REACH;
        for (var running : scripted.values()) {
            var pawnEffects = running.effects;
            if (pawnEffects == null) {
                continue;
            }
            pawnEffects.settle(preview);
            var entry = running.entry;
            var dx = entry.at[0] - eye.getX();
            var dy = entry.at[1] - eye.getY();
            var dz = entry.at[2] - eye.getZ();
            if (preview == null || !shown || !isShown(entry) || dx * dx + dy * dy + dz * dz > reach) {
                pawnEffects.hide(preview);
                continue;
            }
            entry.place(running.figure);
            track(entry, running.figure).pose(frame, time - entry.origin);
            pawnEffects.show(preview, running);
            pawnEffects.follow(preview, running);
        }
    }

    private void endScripts(Predicate<String> ending) {
        var preview = capture ? null : effects.get();
        for (var iterator = scripted.values().iterator(); iterator.hasNext(); ) {
            var running = iterator.next();
            if (!ending.test(running.path)) {
                continue;
            }
            iterator.remove();
            running.script.end();
            if (running.effects != null) {
                running.effects.settle(preview);
                running.effects.remove(preview);
            }
        }
    }

    private void addEntry(Entry entry) {
        entry.move(editorDeltas.get(entry.pawn.path()));
        entries.add(entry);
        paths.put(entry.pawn.path(), entry);
    }

    private void moved(String path) {
        var entry = paths.get(path);
        if (entry != null) {
            entry.move(editorDeltas.get(path));
        }
        editorRevision++;
    }

    private void noteTopology(@Nullable Set<String> actors) {
        editorTopology++;
        editorRevision++;
        editorJournal.put(editorTopology, actors == null ? WHOLE : Set.copyOf(actors));
    }

    private boolean isListed(String path) {
        return !editorRemoved.contains(path) && !editorHidden.contains(path);
    }

    private boolean isShown(Entry entry) {
        var path = entry.pawn.path();
        return isListed(path) && (editorIsolated == null || editorIsolated.contains(path));
    }

    private DebugSceneObject row(Entry entry) {
        var pawn = entry.pawn;
        var figure = figures.getFigure(pawn.mesh(), pawn.skins());
        if (figure != null) {
            entry.place(figure);
        }
        var bounds = entry.matrix.length == 16
                ? entry.getWorldBox()
                : new Bounds3f(
                        entry.at[0] - UNREAD_REACH,
                        entry.at[1] - UNREAD_REACH,
                        entry.at[2] - UNREAD_REACH,
                        entry.at[0] + UNREAD_REACH,
                        entry.at[1] + UNREAD_REACH,
                        entry.at[2] + UNREAD_REACH);
        var mesh = pawn.mesh();
        var map = pawn.path().substring(0, Math.max(1, pawn.path().indexOf('.')));
        var plays = pawn.animations().list();
        return new DebugSceneObject(
                mesh.substring(mesh.lastIndexOf('.') + 1),
                mesh,
                "Unavailable",
                map,
                0,
                -1,
                bounds,
                Category.STATIC_MESH,
                "Dynamic",
                Integer.toUnsignedLong(pawn.path().hashCode()) | 1L << 62,
                pawn.path(),
                List.of(),
                plays.isEmpty() ? "Unavailable" : plays.getFirst(),
                -1,
                false);
    }

    private void awaitModels() {
        while (true) {
            Set<String> wanted = null;
            for (var entry : entries) {
                if (figures.getFigure(entry.pawn.mesh(), entry.pawn.skins()) == null) {
                    wanted = want(entry, wanted);
                }
            }
            noteWaiting(wanted == null ? Set.of() : wanted);
            if (isSettled()) {
                return;
            }
            pause.run();
        }
    }

    private Track track(Entry entry, Figure figure) {
        return tracks.computeIfAbsent(trackKey(entry), _ -> new Track(figure, moments(entry, figure)));
    }

    private DoubleFunction<@Nullable Moment> moments(Entry entry, Figure figure) {
        var pawn = entry.pawn;
        if (pawn.script() != null) {
            var path = pawn.path();
            return _ -> {
                var running = scripted.get(path);
                return running == null ? null : running.script.getMoment();
            };
        }
        var player =
                new MapPawnPlayer(pawn.animations(), name -> computeLength(figure, name), pawn.getSeed(), entry.lobby);
        return player::findMoment;
    }

    private @Nullable Set<String> want(Entry entry, @Nullable Set<String> wanted) {
        if (figures.isMissing(entry.pawn.mesh(), entry.pawn.skins())) {
            if (capture && reportedMissing.add(entry.key)) {
                log.warn(
                        "Capture draws pawn {} and others of mesh {} without their model: the client's mesh cannot be"
                                + " read",
                        entry.pawn.path(),
                        entry.pawn.mesh());
            }
            return wanted;
        }
        var result = wanted == null ? new HashSet<String>() : wanted;
        result.add(entry.key);
        return result;
    }

    private void noteWaiting(Set<String> wanted) {
        if (!waiting.containsAll(wanted)) {
            waitingSince = clock.getAsLong();
            waitReported = false;
        }
        waiting = wanted;
    }

    private static String trackKey(Entry entry) {
        var pawn = entry.pawn;
        if (pawn.script() != null) {
            return "script|" + pawn.path() + "|" + entry.origin;
        }
        return pawn.animations().random()
                ? pawn.path()
                : entry.key + "|" + pawn.animations() + "|" + entry.lobby + "|" + entry.origin;
    }

    private static final class Entry {
        final MapPawn pawn;
        final double origin;
        final boolean lobby;
        final boolean added;
        final String key;
        float @Nullable [] delta;
        final float[] at = new float[3];
        float[] matrix = new float[0];
        final float[] center = new float[3];
        float radius;
        float scale = 1;
        float[] box = new float[0];
        long drawnFrame = -1;

        @Nullable
        Figure figure;

        @Nullable
        Track track;

        Entry(MapPawn pawn, double origin, boolean lobby, boolean added) {
            this.pawn = pawn;
            this.origin = origin;
            this.lobby = lobby;
            this.added = added;
            key = MapPawnModels.keyOf(pawn.mesh(), pawn.skins());
            move(null);
        }

        void move(float @Nullable [] value) {
            delta = value == null ? null : value.clone();
            matrix = new float[0];
            var place = pawn.location();
            at[0] = place.getX();
            at[1] = place.getY();
            at[2] = place.getZ();
            if (delta != null) {
                var x = at[0];
                var y = at[1];
                var z = at[2];
                for (var row = 0; row < 3; row++) {
                    at[row] = delta[row] * x + delta[4 + row] * y + delta[8 + row] * z + delta[12 + row];
                }
            }
        }

        void place(Figure figure) {
            if (matrix.length == 16) {
                return;
            }
            var model = figure.model();
            var actor = pawn.computeMatrix();
            if (delta != null) {
                actor = multiply(delta, actor);
            }
            matrix = MapPawn.computeModelMatrix(actor, model.lowest());
            box = bounds(model.vertices());
            var x = (box[0] + box[3]) / 2;
            var y = (box[1] + box[4]) / 2;
            var z = (box[2] + box[5]) / 2;
            for (var row = 0; row < 3; row++) {
                center[row] = matrix[row] * x + matrix[4 + row] * y + matrix[8 + row] * z + matrix[12 + row];
            }
            scale = Math.max(length(actor, 0), Math.max(length(actor, 4), length(actor, 8)));
            radius = (float) Math.sqrt(sq(box[3] - box[0]) + sq(box[4] - box[1]) + sq(box[5] - box[2])) * scale;
        }

        Bounds3f getWorldBox() {
            var min = new float[] {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
            var max = new float[] {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
            for (var corner = 0; corner < 8; corner++) {
                var x = box[(corner & 1) == 0 ? 0 : 3];
                var y = box[(corner & 2) == 0 ? 1 : 4];
                var z = box[(corner & 4) == 0 ? 2 : 5];
                for (var row = 0; row < 3; row++) {
                    var value = matrix[row] * x + matrix[4 + row] * y + matrix[8 + row] * z + matrix[12 + row];
                    min[row] = Math.min(min[row], value);
                    max[row] = Math.max(max[row], value);
                }
            }
            return new Bounds3f(min[0], min[1], min[2], max[0], max[1], max[2]);
        }

        float intersect(Vec3 origin, Vec3 direction, float limit) {
            if (matrix.length != 16 || figure == null) {
                return -1;
            }
            var inverse = invert(matrix);
            if (inverse == null) {
                return -1;
            }
            var start = new float[] {origin.getX(), origin.getY(), origin.getZ()};
            var way = new float[] {direction.getX(), direction.getY(), direction.getZ()};
            var localStart = new float[3];
            var localWay = new float[3];
            for (var row = 0; row < 3; row++) {
                localStart[row] = inverse[row] * start[0]
                        + inverse[3 + row] * start[1]
                        + inverse[6 + row] * start[2]
                        + inverse[9 + row];
                localWay[row] = inverse[row] * way[0] + inverse[3 + row] * way[1] + inverse[6 + row] * way[2];
            }
            var grown = box.clone();
            for (var axis = 0; axis < 3; axis++) {
                var half = (box[axis + 3] - box[axis]) / 2;
                grown[axis] -= half;
                grown[axis + 3] += half;
            }
            var entry = intersectBox(localStart, localWay, grown);
            if (entry < 0 || entry > limit) {
                return -1;
            }
            var pose = track == null ? null : track.pose.getVertices();
            var vertices = pose != null && pose.length == figure.model().vertices().length
                    ? pose
                    : figure.model().vertices();
            return intersectTriangles(vertices, figure.model().indices(), localStart, localWay, limit);
        }

        private static float[] multiply(float[] a, float[] b) {
            var result = new float[16];
            for (var col = 0; col < 4; col++) {
                for (var row = 0; row < 4; row++) {
                    for (var k = 0; k < 4; k++) {
                        result[col * 4 + row] += a[k * 4 + row] * b[col * 4 + k];
                    }
                }
            }
            return result;
        }

        private static float length(float[] matrix, int column) {
            return (float) Math.sqrt(sq(matrix[column]) + sq(matrix[column + 1]) + sq(matrix[column + 2]));
        }

        private static float[] bounds(float[] vertices) {
            var box = new float[] {
                Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE
            };
            for (var i = 0; i + 2 < vertices.length; i += SpawnModelData.FLOATS) {
                for (var axis = 0; axis < 3; axis++) {
                    box[axis] = Math.min(box[axis], vertices[i + axis]);
                    box[axis + 3] = Math.max(box[axis + 3], vertices[i + axis]);
                }
            }
            return box;
        }

        private static float sq(float value) {
            return value * value;
        }

        private static float @Nullable [] invert(float[] m) {
            var a = m[0];
            var b = m[4];
            var c = m[8];
            var d = m[1];
            var e = m[5];
            var f = m[9];
            var g = m[2];
            var h = m[6];
            var i = m[10];
            var det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g);
            if (Math.abs(det) < 1e-12f) {
                return null;
            }
            var inv = new float[] {
                (e * i - f * h) / det,
                -(d * i - f * g) / det,
                (d * h - e * g) / det,
                -(b * i - c * h) / det,
                (a * i - c * g) / det,
                -(a * h - b * g) / det,
                (b * f - c * e) / det,
                -(a * f - c * d) / det,
                (a * e - b * d) / det,
                0,
                0,
                0
            };
            for (var row = 0; row < 3; row++) {
                inv[9 + row] = -(inv[row] * m[12] + inv[3 + row] * m[13] + inv[6 + row] * m[14]);
            }
            return inv;
        }

        private static float intersectTriangles(
                float[] vertices, int[] indices, float[] origin, float[] direction, float limit) {
            var best = -1f;
            var nearest = limit;
            for (var t = 0; t + 2 < indices.length; t += 3) {
                var p = indices[t] * SpawnModelData.FLOATS;
                var q = indices[t + 1] * SpawnModelData.FLOATS;
                var r = indices[t + 2] * SpawnModelData.FLOATS;
                var e1x = vertices[q] - vertices[p];
                var e1y = vertices[q + 1] - vertices[p + 1];
                var e1z = vertices[q + 2] - vertices[p + 2];
                var e2x = vertices[r] - vertices[p];
                var e2y = vertices[r + 1] - vertices[p + 1];
                var e2z = vertices[r + 2] - vertices[p + 2];
                var px = direction[1] * e2z - direction[2] * e2y;
                var py = direction[2] * e2x - direction[0] * e2z;
                var pz = direction[0] * e2y - direction[1] * e2x;
                var det = e1x * px + e1y * py + e1z * pz;
                if (Math.abs(det) < 1e-9f) {
                    continue;
                }
                var tx = origin[0] - vertices[p];
                var ty = origin[1] - vertices[p + 1];
                var tz = origin[2] - vertices[p + 2];
                var u = (tx * px + ty * py + tz * pz) / det;
                if (u < 0 || u > 1) {
                    continue;
                }
                var qx = ty * e1z - tz * e1y;
                var qy = tz * e1x - tx * e1z;
                var qz = tx * e1y - ty * e1x;
                var v = (direction[0] * qx + direction[1] * qy + direction[2] * qz) / det;
                if (v < 0 || u + v > 1) {
                    continue;
                }
                var along = (e2x * qx + e2y * qy + e2z * qz) / det;
                if (along >= 0 && along < nearest) {
                    nearest = along;
                    best = along;
                }
            }
            return best;
        }
    }

    private static double computeLength(Figure figure, String name) {
        var animated = figure.animated();
        if (animated == null) {
            return 0;
        }
        var sequence = animated.animation().findSequence(name);
        return sequence < 0 ? 0 : animated.animation().getSeconds(sequence);
    }

    private record Event(String name, double time) {}

    private static final class Track {
        final DoubleFunction<@Nullable Moment> moments;
        final @Nullable Figure figure;
        final LivePose pose;
        long posed = -1;

        @Nullable
        Moment moment;

        @Nullable
        Pose shown;

        boolean referencePosed;

        Track(Figure figure, DoubleFunction<@Nullable Moment> moments) {
            this.figure = figure;
            this.moments = moments;
            pose = new LivePose(figure.model().vertices());
        }

        void pose(long frame, double time) {
            if (posed == frame || figure == null) {
                return;
            }
            posed = frame;
            var animated = figure.animated();
            if (animated == null) {
                return;
            }
            var now = moments.apply(Math.max(0, time));
            if (now == null) {
                if (!referencePosed) {
                    shown = animated.animation().getReferencePose();
                    pose.set(animated.vertices(shown));
                    referencePosed = true;
                }
                moment = null;
                return;
            }
            if (now.equals(moment)) {
                return;
            }
            moment = now;
            referencePosed = false;
            var sequence = animated.animation().findSequence(now.sequence());
            shown = animated.animation().getPose(sequence, now.seconds());
            pose.set(animated.vertices(shown));
        }
    }

    private final class Scripted implements Host, Body {
        final String path;
        final double origin;
        final Figure figure;
        final MapPawnScript script;
        final @Nullable ScriptEffects effects;
        final SplittableRandom random;
        Entry entry;

        Scripted(Entry entry, Figure figure, @Nullable ScriptedClass type) {
            this.entry = entry;
            this.figure = figure;
            path = entry.pawn.path();
            origin = entry.origin;
            random = new SplittableRandom(entry.pawn.getSeed());
            var pawn = entry.pawn;
            var script = Objects.requireNonNull(pawn.script());
            var waitClass = script.className().contains(".") ? script.className() : MapPawnClasses.PAWN_CLASS;
            this.script = new MapPawnScript(
                    type,
                    script.saved(),
                    pawn.tag(),
                    name -> computeLength(figure, name),
                    figures.getWait(waitClass),
                    this);
            var running = this.script.getPawn();
            effects = running == null ? null : new ScriptEffects(running, script.className(), figures::getEffect);
        }

        @Override
        public Vector getLocation() {
            return new Vector(entry.at[0], entry.at[1], entry.at[2]);
        }

        @Override
        public Rotator getRotation() {
            var rotation = entry.pawn.rotation();
            return new Rotator(rotation.pitch(), rotation.yaw(), rotation.roll());
        }

        @Override
        public float getCollisionHeight() {
            return COLLISION_HEIGHT;
        }

        @Override
        public float getCollisionRadius() {
            return COLLISION_RADIUS;
        }

        @Override
        public boolean hasBone(String bone) {
            for (var name : figure.bones()) {
                if (name.equalsIgnoreCase(bone)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public @Nullable Coords getBoneCoords(String bone) {
            var found = findWorldBone(bone);
            if (found == null) {
                return null;
            }
            var axes = new Vector[3];
            for (var axis = 0; axis < 3; axis++) {
                var x = found.axes()[axis * 3];
                var y = found.axes()[axis * 3 + 1];
                var z = found.axes()[axis * 3 + 2];
                var length = (float) Math.sqrt(x * x + y * y + z * z);
                axes[axis] = length > 0 ? new Vector(x / length, y / length, z / length) : new Vector(x, y, z);
            }
            var place = found.place();
            return new Coords(new Vector(place.getX(), place.getY(), place.getZ()), axes[0], axes[1], axes[2]);
        }

        @Override
        public boolean canSpawn(String effectClass) {
            return !capture && figures.getEffect(effectClass) != null;
        }

        @Override
        public float random() {
            return random.nextFloat();
        }

        @Override
        public @Nullable String findBone(int index) {
            return index >= 0 && index < figure.bones().size() ? figure.bones().get(index) : null;
        }

        @Override
        public Vec3 getPlace() {
            return new Vec3(entry.at[0], entry.at[1], entry.at[2]);
        }

        @Override
        public URotator getTurn() {
            return entry.pawn.rotation();
        }

        @Override
        public @Nullable Bone findWorldBone(String name) {
            var track = tracks.get(trackKey(entry));
            var posed = track == null ? null : track.shown;
            var animated = figure.animated();
            if (posed == null || animated == null || entry.matrix.length != 16) {
                return null;
            }
            var key = SkeletalPose.boneKey(name);
            var at = posed.bones().get(key);
            var axes = posed.axes().get(key);
            if (at == null || axes == null) {
                return null;
            }
            var m = entry.matrix;
            var place = new Vec3(
                    m[0] * at.getX() + m[4] * at.getY() + m[8] * at.getZ() + m[12],
                    m[1] * at.getX() + m[5] * at.getY() + m[9] * at.getZ() + m[13],
                    m[2] * at.getX() + m[6] * at.getY() + m[10] * at.getZ() + m[14]);
            return new Bone(
                    place, turnAxes(m, axes), turnAxes(m, animated.animation().getMeshAxes()));
        }

        private float[] turnAxes(float[] m, float[] axes) {
            var turned = new float[9];
            for (var axis = 0; axis < 3; axis++) {
                var x = axes[axis * 3];
                var y = axes[axis * 3 + 1];
                var z = axes[axis * 3 + 2];
                for (var row = 0; row < 3; row++) {
                    turned[axis * 3 + row] = m[row] * x + m[4 + row] * y + m[8 + row] * z;
                }
            }
            return turned;
        }
    }
}
