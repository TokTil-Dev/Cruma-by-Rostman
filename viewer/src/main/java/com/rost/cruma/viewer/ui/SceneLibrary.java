package com.rost.cruma.viewer.ui;

import com.rost.cruma.core.audio.SoundClip;
import com.rost.cruma.core.npc.ClientCatalog;
import com.rost.cruma.core.npc.ClientCatalog.SkillSound.Moment;
import com.rost.cruma.core.npc.EnterEvents;
import com.rost.cruma.core.npc.EnterEvents.Entrance;
import com.rost.cruma.core.npc.NpcSocials;
import com.rost.cruma.core.spawn.NpcGrpTable;
import com.rost.cruma.core.texture.DxtMipCodec.Options;
import com.rost.cruma.core.unreal.EffectClasses.Effect;
import com.rost.cruma.core.unreal.EffectClasses.Quake;
import com.rost.cruma.core.unreal.SkillEffects;
import com.rost.cruma.core.unreal.SkillEffects.Phase;
import com.rost.cruma.core.unreal.SkillEffects.Skill;
import com.rost.cruma.core.world.EffectParts;
import com.rost.cruma.core.world.GameScenes.Action;
import com.rost.cruma.core.world.GameScenes.Scene;
import com.rost.cruma.core.world.TextureQuality;
import com.rost.cruma.core.world.UnrealLoader;
import com.rost.cruma.core.world.WorldVariant;
import com.rost.cruma.viewer.ui.SceneCasts.Reads;
import com.rost.cruma.viewer.ui.SceneCasts.SceneSkill;
import com.rost.cruma.viewer.ui.SkillShow.Sound;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

final class SceneLibrary implements Reads, AutoCloseable {
    private static final int LEVEL = 1;
    private static final Logger log = LogManager.getLogger(SceneLibrary.class);

    private final Path client;
    private final WorldVariant variant;
    private final TextureQuality quality;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        var thread = new Thread(runnable, "cruma-scene-casts");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<Integer, CompletableFuture<@Nullable SceneSkill>> skills = new HashMap<>();
    private final Map<String, CompletableFuture<@Nullable EffectParts>> effects = new HashMap<>();
    private final Map<String, CompletableFuture<@Nullable Quake>> quakes = new HashMap<>();
    private final Map<String, CompletableFuture<@Nullable SoundClip>> sounds = new HashMap<>();
    private final Map<Integer, CompletableFuture<@Nullable Map<String, String>>> classKinds = new HashMap<>();
    private volatile ClientCatalog catalog = ClientCatalog.empty();
    private @Nullable EnterEvents entrances;
    private @Nullable UnrealLoader loader;
    private final Map<String, Skill> skillClasses = new HashMap<>();
    private final Map<String, Effect> effectClasses = new HashMap<>();
    private final Map<String, @Nullable EffectParts> parts = new HashMap<>();
    private @Nullable NpcGrpTable npcTable;
    private @Nullable NpcSocials npcClasses;

    SceneLibrary(Path client, WorldVariant variant, TextureQuality quality) {
        this.client = client;
        this.variant = variant;
        this.quality = quality;
    }

    static Map<String, Skill> byClass(List<Skill> skills) {
        var classes = new HashMap<String, Skill>();
        for (var skill : skills) {
            classes.putIfAbsent(SkillEffects.key(skill.className()), skill);
            classes.putIfAbsent(className(Integer.toString(skill.skillId())), skill);
        }
        return classes;
    }

    static @Nullable Skill effectsOf(ClientCatalog.Cast cast, Map<String, Skill> classes) {
        var effects = classes.get(className(cast.effect()));
        return effects != null && cast.style() == SceneSkill.INSTANT ? onlyCasting(effects) : effects;
    }

    List<CompletableFuture<?>> read(Scene scene) {
        var reads = new ArrayList<CompletableFuture<?>>();
        for (var item : scene.items()) {
            for (var order : item.actors()) {
                if (!order.player() && order.npc() > 0) {
                    reads.add(classKinds.computeIfAbsent(
                            order.npc(), id -> later("class of NPC " + id, () -> readKinds(id))));
                }
                if (order.action() == Action.SKILL) {
                    reads.add(skills.computeIfAbsent(order.skill(), id -> later("skill " + id, () -> readSkill(id))));
                } else if (order.action() == Action.EFFECT && !order.animation().isEmpty()) {
                    reads.add(effects.computeIfAbsent(
                            SkillEffects.key(order.animation()), key -> later("effect " + key, () -> readEffect(key))));
                    reads.add(quakes.computeIfAbsent(
                            order.animation(), name -> later("quake " + name, () -> opened().loadQuake(name))));
                } else if (order.action() == Action.SPAWN && !order.player() && !order.present()) {
                    var entrance = getEntrance(order.npc());
                    if (entrance != null && !entrance.effect().isEmpty()) {
                        reads.add(effects.computeIfAbsent(
                                SkillEffects.key(entrance.effect()),
                                key -> later("effect " + key, () -> readEffect(key))));
                    }
                    if (entrance != null && !entrance.sound().isEmpty()) {
                        reads.add(sounds.computeIfAbsent(
                                entrance.sound(), name -> later("sound " + name, () -> readSound(name))));
                    }
                }
            }
            var music = item.music();
            if (music != null && music.sound() && !music.name().isEmpty()) {
                reads.add(sounds.computeIfAbsent(music.name(), name -> later("sound " + name, () -> readSound(name))));
            }
        }
        return reads;
    }

    @Override
    public @Nullable SceneSkill getSkill(int id) {
        return done(skills.get(id));
    }

    @Override
    public List<SkillShow.Cast> getCasts(SceneSkill skill, int npc) {
        var read = catalog;
        var kinds = done(classKinds.get(npc));
        return SkillShow.casts(
                skill.cast(),
                read::getCombo,
                read.getNpcSkillAnimation(npc, skill.id()),
                kind -> kinds == null ? null : kinds.get(kind.toLowerCase(Locale.ROOT)));
    }

    @Override
    public @Nullable EffectParts getEffect(String name) {
        return done(effects.get(SkillEffects.key(name)));
    }

    CompletableFuture<?> prefetchEffect(String name) {
        return effects.computeIfAbsent(SkillEffects.key(name), key -> later("effect " + key, () -> readEffect(key)));
    }

    @Nullable
    Quake getQuake(String name) {
        return done(quakes.get(name));
    }

    @Override
    public @Nullable SoundClip getSound(String name) {
        return done(sounds.get(name));
    }

    @Nullable
    Entrance getEntrance(int npc) {
        var read = entrances;
        if (read == null) {
            read = EnterEvents.read(client, variant);
            entrances = read;
        }
        return read.of(npc);
    }

    @Override
    public void close() {
        if (worker.isShutdown()) {
            return;
        }
        worker.execute(() -> {
            if (loader != null) {
                loader.trimCaches();
            }
        });
        worker.shutdown();
    }

    private <T> CompletableFuture<@Nullable T> later(String what, Supplier<@Nullable T> read) {
        return CompletableFuture.<@Nullable T>supplyAsync(read, worker).exceptionally(failure -> {
            log.warn("The {} of a cut scene could not be read", what, failure);
            return null;
        });
    }

    private UnrealLoader opened() {
        var opened = loader;
        if (opened != null) {
            return opened;
        }
        opened = new UnrealLoader(client, new Options(false, 0), true, quality);
        opened.worldVariant(variant);
        catalog = ClientCatalog.read(client, variant);
        skillClasses.putAll(byClass(opened.listSkills()));
        for (var effect : opened.listEffects()) {
            effectClasses.putIfAbsent(SkillEffects.key(effect.path()), effect);
            effectClasses.putIfAbsent(SkillEffects.key(effect.name()), effect);
        }
        loader = opened;
        return opened;
    }

    private Map<String, String> readKinds(int npc) {
        var table = npcTable;
        if (table == null) {
            try {
                table = NpcGrpTable.read(client.resolve("system"), variant);
            } catch (IOException | RuntimeException unreadable) {
                table = NpcGrpTable.empty();
            }
            npcTable = table;
            npcClasses = NpcSocials.read(client, variant);
        }
        var appearance = table.getAppearance(npc);
        var classes = npcClasses;
        return appearance == null || classes == null || appearance.npcClass().isEmpty()
                ? Map.of()
                : classes.getKinds(appearance.npcClass());
    }

    private @Nullable SceneSkill readSkill(int id) {
        var opened = opened();
        var cast = catalog.hasLevel(id, LEVEL) ? catalog.getCast(id, LEVEL) : null;
        if (cast == null) {
            return null;
        }
        var effects = effectsOf(cast, skillClasses);
        if (cast.style() == SceneSkill.INSTANT) {
            return effects == null ? null : read(opened, id, cast, effects, true);
        }
        return read(opened, id, cast, effects == null ? new Skill(id, "", 0f, Map.of()) : effects, false);
    }

    private SceneSkill read(UnrealLoader opened, int id, ClientCatalog.Cast cast, Skill skill, boolean instant) {
        var read = new HashMap<String, EffectParts>();
        for (var phase : Phase.values()) {
            for (var action : skill.actions(phase)) {
                var key = SkillEffects.key(action.effect());
                var loaded = partsOf(opened, key);
                if (loaded != null) {
                    read.put(key, loaded);
                }
            }
        }
        var heard = new ArrayList<Sound>();
        for (var sound : catalog.getSkillSounds(id, LEVEL, null)) {
            var clip = instant && sound.moment() != Moment.CAST ? null : opened.loadSound(sound.sound());
            if (clip != null) {
                heard.add(Sound.of(clip, sound));
            }
        }
        return new SceneSkill(id, cast, skill, read, heard);
    }

    private @Nullable EffectParts readEffect(String key) {
        return partsOf(opened(), key);
    }

    private @Nullable EffectParts partsOf(UnrealLoader opened, String key) {
        if (parts.containsKey(key)) {
            return parts.get(key);
        }
        var effect = effectClasses.get(key);
        var loaded = effect == null ? null : opened.loadEffect(effect);
        var drawn = loaded == null || loaded.isEmpty() ? null : loaded;
        parts.put(key, drawn);
        return drawn;
    }

    private @Nullable SoundClip readSound(String name) {
        return opened().loadSound(name);
    }

    private static <T> @Nullable T done(@Nullable CompletableFuture<@Nullable T> read) {
        return read == null ? null : read.getNow(null);
    }

    private static String className(String effect) {
        return SkillEffects.key(effect.indexOf('.') >= 0 ? effect : "l2_" + effect + "_skill");
    }

    private static Skill onlyCasting(Skill skill) {
        return new Skill(skill.skillId(), skill.className(), 0f, Map.of(Phase.CASTING, skill.actions(Phase.CASTING)));
    }
}
