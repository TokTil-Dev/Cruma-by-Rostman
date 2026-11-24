package com.rost.cruma.viewer.spawn;

import com.rost.cruma.core.npc.NpcSocials;
import com.rost.cruma.core.texture.DxtMipCodec.Options;
import com.rost.cruma.core.unreal.EffectClasses.Effect;
import com.rost.cruma.core.unreal.SkillEffects;
import com.rost.cruma.core.world.EffectParts;
import com.rost.cruma.core.world.NpcAnimation;
import com.rost.cruma.core.world.UnrealLoader;
import com.rost.cruma.core.world.WorldVariant;
import com.rost.cruma.viewer.spawn.SpawnModels.Animated;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import org.apache.logging.log4j.LogManager;
import org.jetbrains.annotations.Nullable;

public final class MapPawnModels implements MapPawnStage.Figures {
    private static final String EFFECT = "effect:";

    private final Path client;
    private final WorldVariant variant;
    private final Map<String, Figure> figures = new ConcurrentHashMap<>();
    private final Map<String, EffectParts> effects = new ConcurrentHashMap<>();
    private final Set<String> missing = ConcurrentHashMap.newKeySet();
    private final Set<String> pending = ConcurrentHashMap.newKeySet();
    private final LinkedBlockingDeque<Request> queue = new LinkedBlockingDeque<>();
    private final Thread worker;
    private @Nullable NpcSocials names;
    private volatile boolean closed;
    private @Nullable UnrealLoader loader;
    private @Nullable Map<String, Effect> effectClasses;

    public MapPawnModels(Path client, WorldVariant variant) {
        this.client = client;
        this.variant = variant;
        worker = new Thread(this::run, "map-pawn-models");
        worker.setDaemon(true);
        worker.start();
    }

    public record Figure(SpawnModelData model, @Nullable Animated animated, List<String> bones) {
        public Figure {
            bones = List.copyOf(bones);
        }

        public Figure(SpawnModelData model, @Nullable Animated animated) {
            this(model, animated, List.of());
        }
    }

    public static String keyOf(String mesh, List<String> skins) {
        return "pawn:" + mesh + (skins.isEmpty() ? "" : "#" + String.join(",", skins));
    }

    @Override
    public @Nullable Figure getFigure(String mesh, List<String> skins) {
        var key = keyOf(mesh, skins);
        var figure = figures.get(key);
        if (figure == null && !missing.contains(key) && pending.add(key)) {
            queue.addLast(new Request(key, mesh, skins));
        }
        return figure;
    }

    @Override
    public boolean isMissing(String mesh, List<String> skins) {
        return missing.contains(keyOf(mesh, skins));
    }

    @Override
    public @Nullable EffectParts getEffect(String effectClass) {
        var key = EFFECT + SkillEffects.key(effectClass);
        var parts = effects.get(key);
        if (parts == null && !missing.contains(key) && pending.add(key)) {
            queue.addLast(new Request(key, effectClass, List.of()));
        }
        return parts;
    }

    @Override
    public boolean hasReadEffect(String effectClass) {
        var key = EFFECT + SkillEffects.key(effectClass);
        return effects.containsKey(key) || missing.contains(key);
    }

    @Override
    public synchronized String getWait(String pawnClass) {
        if (names == null) {
            names = NpcSocials.read(client, variant);
        }
        var wait = names.getKind(pawnClass, "wait");
        return wait == null || wait.equalsIgnoreCase("None") ? "" : wait;
    }

    @Override
    public void close() {
        closed = true;
        worker.interrupt();
    }

    private void run() {
        while (!closed) {
            Request request;
            try {
                request = queue.takeFirst();
            } catch (InterruptedException interrupted) {
                return;
            }
            try {
                if (request.key().startsWith(EFFECT)) {
                    var parts = readEffect(request.mesh());
                    if (parts == null) {
                        missing.add(request.key());
                    } else {
                        effects.put(request.key(), parts);
                    }
                    continue;
                }
                var figure = read(request);
                if (figure == null) {
                    missing.add(request.key());
                } else {
                    figures.put(request.key(), figure);
                }
            } catch (RuntimeException | LinkageError failure) {
                missing.add(request.key());
                LogManager.getLogger(MapPawnModels.class)
                        .warn("The pawn model {} failed: {}", request.mesh(), failure.toString());
            } finally {
                pending.remove(request.key());
            }
        }
    }

    private UnrealLoader opened() {
        if (loader == null) {
            loader = new UnrealLoader(client, new Options(false, 0), true);
            loader.worldVariant(variant);
        }
        return loader;
    }

    private @Nullable Figure read(Request request) {
        var rig = opened().loadNpcRig(request.mesh(), request.skins());
        if (rig == null) {
            LogManager.getLogger(MapPawnModels.class).info("The client has no mesh {} for a pawn", request.mesh());
            return null;
        }
        var data = SpawnModels.toModelData(0, rig.model(), 1f);
        if (data == null) {
            LogManager.getLogger(MapPawnModels.class).info("The mesh {} of a pawn has nothing to draw", request.mesh());
            return null;
        }
        var model = new SpawnModelData(
                request.key(),
                data.vertices(),
                data.indices(),
                data.parts(),
                data.height(),
                data.lowest(),
                data.bones(),
                data.effectBone(),
                data.litSphere());
        var animation = NpcAnimation.of(rig);
        var bones = new ArrayList<String>();
        for (var bone : rig.mesh().getBones()) {
            bones.add(bone.name());
        }
        return new Figure(model, animation == null ? null : new Animated(model, animation, 1f), bones);
    }

    private @Nullable EffectParts readEffect(String effectClass) {
        var opened = opened();
        if (effectClasses == null) {
            var known = new HashMap<String, Effect>();
            for (var effect : opened.listEffects()) {
                known.putIfAbsent(SkillEffects.key(effect.path()), effect);
                known.putIfAbsent(SkillEffects.key(effect.name()), effect);
            }
            effectClasses = known;
        }
        var effect = effectClasses.get(SkillEffects.key(effectClass));
        var parts = effect == null ? null : opened.loadEffect(effect);
        if (parts == null || parts.isEmpty()) {
            LogManager.getLogger(MapPawnModels.class)
                    .info("The client has no effect {} for the script of a pawn", effectClass);
            return null;
        }
        return parts;
    }

    private record Request(String key, String mesh, List<String> skins) {}
}
