package com.rost.cruma.viewer.render.bgfx;

import com.rost.cruma.core.env.UnrealHsv;
import com.rost.cruma.core.unreal.primitives.USphere;
import com.rost.cruma.core.world.BspLightPublication;
import com.rost.cruma.core.world.BspLightPublication.Result;
import com.rost.cruma.core.world.BspLightPublication.Visibility;
import com.rost.cruma.core.world.BspVisibilityVolumes.View;
import com.rost.cruma.core.world.Entity.HardwareSunSource;
import com.rost.cruma.core.world.Light;
import com.rost.cruma.core.world.NativeActorLightCache;
import com.rost.cruma.core.world.NativeActorLightCache.Data;
import com.rost.cruma.core.world.NativeActorLightCache.SunVisibilityQuery;
import com.rost.cruma.core.world.NativeLightCollector;
import com.rost.cruma.core.world.NativeLightCollector.Receiver;
import com.rost.cruma.core.world.NativeLightReceiver;
import com.rost.cruma.core.world.NativeLightRefresh;
import com.rost.cruma.core.world.NativeLightScene;
import com.rost.cruma.core.world.NativeLightTemporal;
import com.rost.cruma.core.world.NativeLightTemporal.ActorState;
import com.rost.cruma.core.world.NativeRandom;
import com.rost.cruma.core.world.NativeStaticMeshLightSelection;
import com.rost.cruma.core.world.NativeStaticMeshLightingInputs;
import com.rost.cruma.core.world.NativeVertexLightmap;
import com.rost.cruma.core.world.NativeVertexLightmapState;
import com.rost.cruma.core.world.NativeVertexLightmapState.Cached;
import com.rost.cruma.viewer.render.bgfx.BgfxNativeLightState.Input;
import com.rost.cruma.viewer.render.bgfx.BgfxSceneZones.Reach;
import glm_.vec3.Vec3;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.DoubleSupplier;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.IntUnaryOperator;
import java.util.function.Supplier;
import org.jetbrains.annotations.Nullable;

final class BgfxNativeLightSources {
    private static final float TILE_SIZE = 32768f;
    private static final int TILE_ZERO_X = 20;
    private static final int TILE_ZERO_Y = 18;

    private final Map<String, Level> levels = new LinkedHashMap<>();
    private final Map<String, Source> sources = new LinkedHashMap<>();
    private final ArrayList<Source> indexedSources = new ArrayList<>();
    private final NativeLightTemporal temporal;
    private final Function<String, NativeLightRefresh.@Nullable Actor> receivers;
    private boolean slotsStale;
    private NativeLightRefresh refresh = new NativeLightRefresh(-1f, 0f, 0);
    private int previousPreset = -1;
    private Frame frame;
    private float levelTime;
    private Float engineDelta;

    BgfxNativeLightSources() {
        this(new NativeRandom());
    }

    BgfxNativeLightSources(DoubleSupplier random) {
        this(random, _ -> null);
    }

    BgfxNativeLightSources(DoubleSupplier random, Function<String, NativeLightRefresh.@Nullable Actor> receivers) {
        temporal = new NativeLightTemporal(random, 0f, 0);
        this.receivers = receivers;
    }

    record Frame(
            double hour,
            int preset,
            Vec3 actorSun,
            Vec3 beastActorSun,
            Vec3 movableDirection,
            Vec3 modelSun,
            Vec3 beastModelSun) {
        Frame(double hour, int preset, Vec3 actorSun, Vec3 beastActorSun, Vec3 movableDirection) {
            this(hour, preset, actorSun, beastActorSun, movableDirection, actorSun, beastActorSun);
        }
    }

    record Snapshot(int sources, int suns, int cached, long updates, float sampleHour, int frameLights) {}

    record Binding(BgfxNativeLightSources owner, String actorId, float intensity, String lookupKey) {
        Binding(BgfxNativeLightSources owner, String actorId, float intensity) {
            this(owner, actorId, intensity, normalizeKey(actorId));
        }

        @Nullable
        Source source() {
            var source = owner.sources.get(lookupKey);
            return source != null && source.isHardwareSun() ? source : null;
        }
    }

    static final class Source implements NativeLightRefresh.Actor, NativeActorLightCache.Actor {
        private final BgfxNativeLightSources owner;
        private final NativeLightScene.Source authored;
        private final float levelBrightness;
        private final boolean beastEnvironment;
        private final NativeLightRefresh.Light light;
        private final int objectIndex;
        private final Object levelIdentity;
        private final ActorState temporalState;
        private final NativeLightTemporal.Inputs temporalInputs;
        private int revision;
        private int cachedRevision;
        private long generation;
        private Vec3 color;
        private final Vec3 nativeBase;
        private Vec3 modelColor;
        private Vec3 evaluatedModelColor;
        private Vec3 direction;
        private Data data;
        private boolean cachedDynamic;
        private boolean cachedChanged;

        Source(
                BgfxNativeLightSources owner,
                NativeLightScene.Source authored,
                float levelBrightness,
                boolean beastEnvironment,
                int objectIndex,
                Object levelIdentity) {
            this.owner = owner;
            this.authored = authored;
            this.levelBrightness = levelBrightness;
            this.beastEnvironment = beastEnvironment;
            this.objectIndex = objectIndex;
            this.levelIdentity = levelIdentity;
            light = new NativeLightRefresh.Light(authored.light().lightType(), authored.sunlightColor());
            nativeBase = UnrealHsv.toRgb(authored.hue(), authored.saturation(), 255)
                    .times(authored.authoredBrightness() / 255f);
            var temporal = authored.temporal();
            temporalState = new ActorState(temporal.lightOnTime());
            temporalInputs = new NativeLightTemporal.Inputs(
                    authored.light().lightType(),
                    authored.light().period(),
                    authored.light().phase(),
                    temporal.lifeSpan(),
                    0f,
                    temporal.lightOffTime(),
                    false,
                    0f,
                    temporal.palette());
        }

        public boolean isDeleted() {
            return authored.deleteMe();
        }

        public void markStaticLightingDirty() {
            var receiver = owner.receivers.apply(authored.actorId());
            if (receiver != null) {
                receiver.markStaticLightingDirty();
            }
        }

        public NativeLightRefresh.Light getLight() {
            return light;
        }

        public void clearRenderData() {
            revision++;
        }

        public int getObjectIndex() {
            return objectIndex;
        }

        public Object getClassIdentity() {
            return authored.actorClass().intern();
        }

        public boolean isMovableSun() {
            return authored.movableSun();
        }

        public Object getLevelIdentity() {
            return levelIdentity;
        }

        public Data getLightData() {
            sample();
            return data;
        }

        boolean isHardwareSun() {
            return !isDeleted()
                    && authored.light().lightEffect() == 19
                    && authored.light().lightType() == 1;
        }

        boolean refreshFromFrame(boolean explicitRefresh) {
            if (authored.staticActor() || authored.light().lightType() == 0) {
                return false;
            }
            sample();
            if (explicitRefresh
                    && (authored.dynamicLight()
                            || authored.lightChanged()
                            || authored.deleteMe()
                            || cachedDynamic
                            || cachedChanged)) {
                refresh();
            }
            return cachedDynamic || cachedChanged;
        }

        void sample() {
            if (owner.frame == null) {
                throw new IllegalStateException("source frame is not available");
            }
            if (color == null || cachedRevision != revision) {
                refresh();
            }
        }

        Vec3 getColor() {
            return color;
        }

        Vec3 getModelColor() {
            return modelColor;
        }

        Vec3 getDirection() {
            return direction;
        }

        @Nullable
        Data getCachedData() {
            return data;
        }

        long getGeneration() {
            return generation;
        }

        float getFadePhase() {
            return temporalState.getFadePhase();
        }

        Input construct() {
            return evaluate();
        }

        private Input evaluate() {
            var frame = Objects.requireNonNull(owner.frame, "source frame is not available");
            var sample = owner.temporal.update(temporalInputs, temporalState, owner.levelTime, owner.engineDelta);
            var useEnvironment = authored.sunlightColor()
                    && authored.light().lightType() != 8
                    && authored.light().lightType() != 9;
            var base = sample.paletteColor() != null
                    ? sample.paletteColor().times(authored.authoredBrightness() / 255f)
                    : useEnvironment
                            ? beastEnvironment ? frame.beastActorSun() : frame.actorSun()
                            : authored.light().color();
            var color = base.times(sample.power()).times(levelBrightness);
            var modelBase = sample.paletteColor() != null
                    ? base
                    : useEnvironment ? beastEnvironment ? frame.beastModelSun() : frame.modelSun() : nativeBase;
            evaluatedModelColor = modelBase.times(sample.power()).times(levelBrightness);
            var effect = authored.light().lightEffect();
            var nativeDirection = effect == 19 && authored.movableSun()
                    ? frame.movableDirection().times(-1f)
                    : new Vec3(authored.light().direction());
            var position = effect == 19 ? new Vec3() : new Vec3(authored.light().position());
            var radius = effect == 19 && authored.light().lightType() != 12
                    ? 0f
                    : NativeLightTemporal.worldRadius(authored.light().lightType(), authored.authoredRadius());
            return new Input(effect, color, position, nativeDirection, radius);
        }

        private void refresh() {
            var input = evaluate();
            color = input.color();
            modelColor = evaluatedModelColor;
            direction = input.direction().times(-1f);
            var source = new NativeLightCollector.Source(
                    this, input.position(), input.radius(), input.effect(), authored.specialLit(), -1);
            if (data == null) {
                data = new Data(
                        this,
                        source,
                        input.direction(),
                        authored.authoredBrightness(),
                        authored.light().cone());
            } else {
                data.update(
                        source,
                        input.direction(),
                        authored.authoredBrightness(),
                        authored.light().cone());
            }
            cachedDynamic = authored.dynamicLight();
            cachedChanged = !cachedDynamic && (authored.lightChanged() || authored.deleteMe());
            cachedRevision = revision;
            generation++;
        }
    }

    private static final class Level {
        final NativeLightScene scene;
        final NativeLightRefresh.Actor[] actors;
        final String[] sourceKeys;
        final Source[] sourceBindings;
        final Source[] frameLights = new Source[NativeLightCollector.LIMIT];
        int frameLightCount;
        LeafLights emptyRuntime;

        Level(NativeLightScene scene) {
            this.scene = scene;
            actors = new NativeLightRefresh.Actor[scene.getActorSlotCount()];
            sourceKeys = new String[scene.getSourceCount()];
            sourceBindings = new Source[sourceKeys.length];
            for (var index = 0; index < sourceKeys.length; index++) {
                sourceKeys[index] = normalizeKey(scene.getSource(index).actorId());
            }
        }
    }

    void updateVertexLightmap(
            String owner,
            NativeVertexLightmapState state,
            NativeVertexLightmapState.Binding binding,
            IntSupplier appRand) {
        var level = levels.get(normalizeKey(owner));
        if (level == null || frame == null) {
            return;
        }
        state.prepare(
                binding,
                (float) frame.hour(),
                frame.actorSun(),
                level.scene.isBeastEnvironment(),
                actorId -> {
                    var light = getSource(actorId);
                    if (light == null) {
                        return null;
                    }
                    light.sample();
                    var data = light.getCachedData();
                    var value = data.getSource();
                    return new Cached(
                            new NativeVertexLightmap.Source(
                                    value.position(),
                                    light.getDirection().times(-1f),
                                    light.getColor(),
                                    value.radius(),
                                    value.effect(),
                                    light.authored.light().cone()),
                            light.cachedDynamic,
                            light.cachedChanged);
                },
                appRand);
    }

    void configure(float interval) {
        refresh = new NativeLightRefresh(interval, 0f, 0);
        previousPreset = -1;
        sources.values().forEach(Source::clearRenderData);
    }

    void publish(String owner, NativeLightScene scene) {
        var key = normalizeKey(owner);
        var existing = levels.get(key);
        if (existing != null && existing.scene == scene) {
            return;
        }
        if (existing != null) {
            remove(owner);
        }
        var level = new Level(scene);
        levels.put(key, level);
        for (var i = 0; i < scene.getSourceCount(); i++) {
            var authored = scene.getSource(i);
            if (normalizeKey(authored.actorId()).startsWith(key + ".")) {
                var source = new Source(
                        this,
                        authored,
                        scene.getLevelBrightness(),
                        scene.isBeastEnvironment(),
                        indexedSources.size(),
                        level);
                sources.put(normalizeKey(authored.actorId()), source);
                indexedSources.add(source);
            }
        }
        slotsStale = true;
    }

    @Nullable
    Source getHardwareSun(String actorId) {
        var source = sources.get(normalizeKey(actorId));
        return source != null && source.isHardwareSun() ? source : null;
    }

    @Nullable
    Source getSource(String actorId) {
        return sources.get(normalizeKey(actorId));
    }

    @Nullable
    NativeActorLightCache.Actor getIndexedSource(int index) {
        return index < 0 || index >= indexedSources.size() ? null : indexedSources.get(index);
    }

    int frameLightCount(String owner) {
        return levels.get(normalizeKey(owner)).frameLightCount;
    }

    Source frameLight(String owner, int index) {
        var level = levels.get(normalizeKey(owner));
        return level.frameLights[Objects.checkIndex(index, level.frameLightCount)];
    }

    record LeafLights(Source[] sources, Result publication) {
        int count(int leaf) {
            return publication.getLeaf(leaf).length;
        }

        Source source(int leaf, int index) {
            return sources[publication.getLeaf(leaf)[index]];
        }
    }

    final class ReceiverView {
        private final String owner;
        private final Level level;
        private final LeafLights runtime;
        private final NativeLightCollector collector = new NativeLightCollector();
        private final NativeStaticMeshLightSelection selected = new NativeStaticMeshLightSelection();

        private ReceiverView(String owner, Level level, LeafLights runtime) {
            this.owner = owner;
            this.level = level;
            this.runtime = runtime;
        }

        NativeStaticMeshLightSelection select(
                NativeLightReceiver receiver,
                NativeActorLightCache cache,
                NativeStaticMeshLightingInputs inputs,
                int lightLimit) {
            if (levels.get(owner) != level) {
                throw new IllegalStateException("native light view outlived its level");
            }
            selected.select(
                    inputs.acceptedInstance(),
                    inputs.lod(),
                    inputs.rootEnvironmentEntry(),
                    lightLimit,
                    cache.size(),
                    index -> {
                        var source = (Source) cache.getLight(index).getActor();
                        return source.cachedDynamic || source.cachedChanged;
                    },
                    receiver.getFlags().lightIntensity());
            return selected;
        }

        Vec3 getSelectedColor(NativeActorLightCache cache, int slot) {
            if (levels.get(owner) != level) {
                throw new IllegalStateException("native light view outlived its level");
            }
            var index = selected.getSource(slot);
            if (index == NativeStaticMeshLightSelection.ROOT_ENVIRONMENT) {
                throw new IllegalArgumentException("root environment light requires its separate construction");
            }
            return new Vec3(((Source) cache.getLight(index).getActor()).getColor());
        }

        void submitSelectedLights(
                NativeActorLightCache cache,
                BgfxNativeLightState state,
                USphere bounds,
                boolean programmable,
                boolean modulate2x,
                float ordinaryScale,
                NativeStaticMeshLightingInputs inputs) {
            submitSelectedLights(cache, state, bounds, programmable, modulate2x, ordinaryScale, () -> {
                var name = inputs.rootEnvironmentSource();
                var root = name == null ? null : BgfxNativeLightSources.this.getSource(name);
                if (root == null) {
                    throw new IllegalStateException("root environment source is unavailable: " + name);
                }
                return root.construct();
            });
        }

        void submitSelectedLights(
                NativeActorLightCache cache,
                BgfxNativeLightState state,
                USphere bounds,
                boolean programmable,
                boolean modulate2x,
                float ordinaryScale,
                Supplier<Input> rootEnvironment) {
            if (levels.get(owner) != level) {
                throw new IllegalStateException("native light view outlived its level");
            }
            for (var slot = 0; slot < selected.size(); slot++) {
                var index = selected.getSource(slot);
                Input input;
                if (index == NativeStaticMeshLightSelection.ROOT_ENVIRONMENT) {
                    input = Objects.requireNonNull(rootEnvironment.get(), "root environment light");
                } else {
                    var source = (Source) cache.getLight(index).getActor();
                    var data = Objects.requireNonNull(source.getCachedData()).getSource();
                    input = new Input(
                            data.effect(),
                            source.getColor(),
                            data.position(),
                            source.getDirection().times(-1f),
                            data.radius());
                }
                state.setLight(
                        slot,
                        input,
                        programmable,
                        modulate2x,
                        true,
                        selected.getActorIntensity(),
                        ordinaryScale,
                        bounds);
            }
        }

        int update(
                NativeLightReceiver receiver,
                NativeActorLightCache cache,
                double now,
                boolean programmable,
                SunVisibilityQuery sunlight) {
            return update(
                    receiver,
                    receiver.getWorldSphere(),
                    receiver.getLeafCount(),
                    receiver::getLeaf,
                    cache,
                    now,
                    programmable,
                    sunlight);
        }

        int update(
                NativeLightReceiver receiver,
                USphere sphere,
                int[] leaves,
                NativeActorLightCache cache,
                double now,
                boolean programmable,
                SunVisibilityQuery sunlight) {
            return update(receiver, sphere, leaves.length, index -> leaves[index], cache, now, programmable, sunlight);
        }

        int updateModel(
                NativeLightReceiver.Flags flags,
                USphere sphere,
                int[] leaves,
                NativeActorLightCache cache,
                double now,
                List<Data> bodies) {
            if (levels.get(owner) != level) {
                throw new IllegalStateException("native light view outlived its level");
            }
            collect(flags, sphere, leaves.length, index -> leaves[index]);
            cache.clear();
            var count = collector.size();
            var supplied = new Data[count + bodies.size()];
            for (var i = 0; i < count; i++) {
                supplied[i] =
                        Objects.requireNonNull(((Source) collector.getSource(i).identity()).getCachedData());
            }
            for (var i = 0; i < bodies.size(); i++) {
                supplied[count + i] = bodies.get(i);
            }
            return cache.update(
                    supplied,
                    now,
                    sphere,
                    flags.maxLights(),
                    false,
                    level,
                    BgfxNativeLightSources.this::getIndexedSource,
                    (_, present) -> present);
        }

        private int update(
                NativeLightReceiver receiver,
                USphere sphere,
                int leafCount,
                IntUnaryOperator leafAt,
                NativeActorLightCache cache,
                double now,
                boolean programmable,
                SunVisibilityQuery sunlight) {
            if (levels.get(owner) != level) {
                throw new IllegalStateException("native light view outlived its level");
            }
            var flags = receiver.getFlags();
            collect(flags, sphere, leafCount, leafAt);
            return cache.updateCollected(
                    collector,
                    identity -> Objects.requireNonNull(((Source) identity).getCachedData()),
                    now,
                    sphere,
                    flags.maxLights(),
                    programmable,
                    level,
                    BgfxNativeLightSources.this::getIndexedSource,
                    sunlight);
        }

        private void collect(NativeLightReceiver.Flags flags, USphere sphere, int leafCount, IntUnaryOperator leafAt) {
            collector.collect(
                    new Receiver(
                            sphere,
                            flags.drawType(),
                            flags.unlit(),
                            flags.specialLit(),
                            flags.useDynamicLights(),
                            flags.staticActor(),
                            flags.lightChanged(),
                            true,
                            flags.hasStaticMeshInstance(),
                            false,
                            false,
                            flags.sunAffect()),
                    new NativeLightCollector.Inputs() {
                        public int getLeafCount() {
                            return leafCount;
                        }

                        public int getStaticSource(int leaf, int offset) {
                            var start =
                                    level.scene.getLeaf(leafAt.applyAsInt(leaf)).permeating();
                            return start < 0 || offset >= level.scene.getStaticReferenceCount() - start
                                    ? NativeLightScene.NO_SOURCE
                                    : level.scene.getStaticSource(start + offset);
                        }

                        public NativeLightCollector.Source getStaticRenderData(int index) {
                            var actor = level.sourceBindings[index];
                            return actor == null ? null : actor.getLightData().getSource();
                        }

                        public NativeLightCollector.Source getRuntimeRenderData(int leaf, int offset) {
                            var index = leafAt.applyAsInt(leaf);
                            return offset >= runtime.count(index)
                                    ? null
                                    : Objects.requireNonNull(runtime.source(index, offset)
                                                    .getCachedData())
                                            .getSource();
                        }
                    });
        }
    }

    ReceiverView openReceiverView(
            String owner,
            BgfxNativeView view,
            boolean precaching,
            boolean antiPortalClipping,
            boolean wire,
            boolean editor) {
        bindStaleSlots();
        var normalized = normalizeKey(owner);
        var level = Objects.requireNonNull(levels.get(normalized), "native light level");
        if (level.frameLightCount == 0) {
            if (level.emptyRuntime == null) {
                level.emptyRuntime = new LeafLights(
                        new Source[0],
                        BspLightPublication.empty(level.scene.getLightTree().leafCount()));
            }
            return new ReceiverView(normalized, level, level.emptyRuntime);
        }
        return openReceiverView(
                owner, view.getOrigin(), getVisibility(owner, view, precaching, antiPortalClipping), wire, editor);
    }

    ReceiverView openReceiverView(String owner, Vec3 origin, Visibility visibility, boolean wire, boolean editor) {
        bindStaleSlots();
        var normalized = normalizeKey(owner);
        var level = Objects.requireNonNull(levels.get(normalized), "native light level");
        return new ReceiverView(normalized, level, publishRuntime(owner, origin, visibility, wire, editor));
    }

    Reach reach(BgfxNativeView view) {
        var origin = view.getOrigin();
        var tile = (int) Math.floor(origin.getX() / TILE_SIZE)
                + TILE_ZERO_X
                + "_"
                + ((int) Math.floor(origin.getY() / TILE_SIZE) + TILE_ZERO_Y);
        var level = levels.get(tile);
        if (level == null || level.scene.pointRegion(origin).zone() == 0) {
            return null;
        }
        var visibility = view.getVisibility(level.scene, false, true);
        BspLightPublication.publish(
                level.scene.getLightTree(), List.of(), new int[0], origin, visibility, false, false);
        return new Reach(tile, level.scene, visibility);
    }

    View getVisibility(String owner, BgfxNativeView view, boolean precaching, boolean antiPortalClipping) {
        return view.getVisibility(levels.get(normalizeKey(owner)).scene, precaching, antiPortalClipping);
    }

    LeafLights publishRuntime(String owner, Vec3 viewOrigin, Visibility visibility, boolean wire, boolean editor) {
        var level = levels.get(normalizeKey(owner));
        var table = Arrays.copyOf(level.frameLights, level.frameLightCount);
        var values = new ArrayList<Light>(table.length);
        var order = new int[table.length];
        for (var i = 0; i < table.length; i++) {
            var cached = Objects.requireNonNull(table[i].getCachedData()).getSource();
            values.add(new Light(cached.position(), new Vec3(), cached.radius(), cached.effect()));
            order[i] = i;
        }
        return new LeafLights(
                table,
                BspLightPublication.publish(
                        level.scene.getLightTree(), values, order, viewOrigin, visibility, wire, editor));
    }

    Binding bind(HardwareSunSource source) {
        return new Binding(this, source.actorId(), source.lightIntensity());
    }

    void update(Frame frame) {
        update(frame, 0f, null, true, true, true);
    }

    void update(
            Frame frame,
            float levelTime,
            @Nullable Float engineDelta,
            boolean lit,
            boolean dynamicLightsEnabled,
            boolean explicitRefresh) {
        this.frame = frame;
        this.levelTime = levelTime;
        this.engineDelta = engineDelta;
        refresh.update(frame.hour(), false, true, false);
        if (previousPreset >= 0 && previousPreset != frame.preset()) {
            refresh.setPending(1);
        }
        previousPreset = frame.preset();
        if (lit || refresh.getPending() != 0) {
            bindStaleSlots();
        }
        for (var level : levels.values()) {
            refresh.invalidate(level.actors);
        }
        for (var level : levels.values()) {
            Arrays.fill(level.frameLights, 0, level.frameLightCount, null);
            level.frameLightCount = 0;
            if (!lit) {
                continue;
            }
            for (var slot = 0; slot < level.scene.getFrameSourceCount(); slot++) {
                var source = level.sourceBindings[level.scene.frameSource(slot)];
                if (source != null
                        && source.refreshFromFrame(explicitRefresh)
                        && dynamicLightsEnabled
                        && level.frameLightCount < level.frameLights.length) {
                    level.frameLights[level.frameLightCount++] = source;
                }
            }
        }
    }

    void remove(String owner) {
        var key = normalizeKey(owner);
        levels.remove(key);
        sources.entrySet().removeIf(entry -> {
            if (!entry.getKey().startsWith(key + ".")) {
                return false;
            }
            indexedSources.set(entry.getValue().getObjectIndex(), null);
            return true;
        });
        slotsStale = true;
    }

    void clear() {
        levels.clear();
        sources.clear();
        indexedSources.clear();
        previousPreset = -1;
        slotsStale = false;
    }

    Snapshot snapshot() {
        var suns = 0;
        var cached = 0;
        var updates = 0L;
        for (var source : sources.values()) {
            if (source.isHardwareSun()) {
                suns++;
            }
            if (source.color != null) {
                cached++;
            }
            updates += source.generation;
        }
        var frameLights = 0;
        for (var level : levels.values()) {
            frameLights += level.frameLightCount;
        }
        return new Snapshot(sources.size(), suns, cached, updates, refresh.getLastSampleHour(), frameLights);
    }

    boolean hasLevel(String owner) {
        return levels.containsKey(normalizeKey(owner));
    }

    boolean hasNormalizedLevel(String owner) {
        return levels.containsKey(owner);
    }

    @Nullable
    String findLevel(Vec3 point) {
        var tile = (int) Math.floor(point.getX() / TILE_SIZE)
                + TILE_ZERO_X
                + "_"
                + ((int) Math.floor(point.getY() / TILE_SIZE) + TILE_ZERO_Y);
        var level = levels.get(tile);
        if (level != null && level.scene.getNodeCount() != 0) {
            return tile;
        }
        for (var entry : levels.entrySet()) {
            var scene = entry.getValue().scene;
            if (scene.getNodeCount() != 0 && scene.pointRegion(point).zone() > 0) {
                return entry.getKey();
            }
        }
        return null;
    }

    NativeLightScene getScene(String owner) {
        return Objects.requireNonNull(levels.get(normalizeKey(owner)), "native light level").scene;
    }

    private void bindStaleSlots() {
        if (slotsStale) {
            slotsStale = false;
            bindActorSlots();
        }
    }

    private void bindActorSlots() {
        for (var level : levels.values()) {
            for (var index = 0; index < level.sourceBindings.length; index++) {
                level.sourceBindings[index] = sources.get(level.sourceKeys[index]);
            }
            for (var slot = 0; slot < level.actors.length; slot++) {
                var index = level.scene.getActorSource(slot);
                var actorId = level.scene.getActorId(slot);
                level.actors[slot] =
                        index < 0 ? actorId == null ? null : receivers.apply(actorId) : level.sourceBindings[index];
            }
        }
    }

    private static String normalizeKey(String value) {
        return value.toLowerCase(Locale.ROOT);
    }
}
