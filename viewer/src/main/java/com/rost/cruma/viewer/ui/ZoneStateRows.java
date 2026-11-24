package com.rost.cruma.viewer.ui;

import com.rost.cruma.core.world.AmbientSound;
import com.rost.cruma.core.world.AmbientVolume;
import com.rost.cruma.core.world.MapEventState;
import com.rost.cruma.core.world.ZoneRender;
import com.rost.cruma.core.world.ZoneStateChoice;
import com.rost.cruma.core.world.ZoneStateFog;
import com.rost.cruma.core.world.ZoneStateItem;
import com.rost.cruma.core.world.ZoneStateMesh;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

final class ZoneStateRows {
    private final String map;
    private final List<ZoneStateMesh> meshes;
    private final List<ZoneStateFog> fogs;
    private final List<Sound> sounds;
    private final List<ZoneStateItem> items;
    private final List<Integer> states;
    private final String statesText;
    private final int defaultState;
    private final List<Integer> starts;

    ZoneStateRows(
            String map,
            List<ZoneStateMesh> meshes,
            List<ZoneStateFog> fogs,
            List<Sound> sounds,
            List<ZoneStateItem> items) {
        this.map = map;
        this.meshes = List.copyOf(meshes);
        this.fogs = List.copyOf(fogs);
        this.sounds = List.copyOf(sounds);
        this.items = List.copyOf(items);
        var found = new TreeSet<Integer>();
        var started = new TreeSet<Integer>();
        var byDefault = MapEventState.AS_THE_MAP;
        for (var mesh : this.meshes) {
            byDefault = Math.max(byDefault, mesh.render().defaultState());
            if (mesh.zoneStart() != ZoneRender.NO_ZONE_STATES) {
                found.addAll(MapEventState.zoneStates(mesh.mask()));
                found.addAll(MapEventState.zoneStates(mesh.looks()));
                addZoneState(started, mesh.zoneStart());
            }
        }
        for (var fog : this.fogs) {
            addZoneState(found, fog.state());
            addZoneState(started, fog.zoneDefault());
        }
        for (var sound : this.sounds) {
            byDefault = Math.max(byDefault, sound.zoneRender().defaultState());
            if (sound.zoneRender().zoneStart() != ZoneRender.NO_ZONE_STATES) {
                found.addAll(MapEventState.zoneStates(sound.zoneRender().mask()));
                addZoneState(started, sound.zoneRender().zoneStart());
            }
        }
        for (var item : this.items) {
            byDefault = Math.max(byDefault, item.render().defaultState());
            found.addAll(MapEventState.zoneStates(item.render().mask()));
            addZoneState(started, item.render().zoneStart());
        }
        found.addAll(started);
        starts = List.copyOf(started);
        defaultState = byDefault;
        addZoneState(found, defaultState);
        states = List.copyOf(found);
        statesText = states.size() == 1 ? "State " + states.getFirst() : "States " + MapEventState.formatIds(states);
    }

    record Sound(String actor, ZoneRender zoneRender) {}

    record Row(String detail, String status, String tip, boolean showing) {}

    static Map<String, ZoneStateRows> byMap(
            List<ZoneStateMesh> meshes, List<ZoneStateFog> fogs, List<Sound> sounds, List<ZoneStateItem> items) {
        var names = new HashMap<String, String>();
        var meshesOf = group(meshes, mesh -> mapOf(mesh.actor()), ZoneStateMesh::render, names);
        var fogsOf = group(fogs, ZoneStateFog::map, fog -> ZoneRender.EVERY_STATE, names);
        var soundsOf = group(sounds, sound -> mapOf(sound.actor()), Sound::zoneRender, names);
        var itemsOf = group(items, ZoneStateItem::map, ZoneStateItem::render, names);
        var sorted = new TreeMap<String, ZoneStateRows>(String.CASE_INSENSITIVE_ORDER);
        names.forEach((key, name) -> {
            var rows = new ZoneStateRows(
                    name,
                    meshesOf.getOrDefault(key, List.of()),
                    fogsOf.getOrDefault(key, List.of()),
                    soundsOf.getOrDefault(key, List.of()),
                    itemsOf.getOrDefault(key, List.of()));
            if (!rows.isEmpty()) {
                sorted.put(name, rows);
            }
        });
        return Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
    }

    static List<Sound> soundsOf(List<AmbientSound> sounds, List<AmbientVolume> volumes) {
        var kept = new ArrayList<Sound>();
        for (var sound : sounds) {
            if (sound.zoneStateMask() != 0) {
                kept.add(new Sound(sound.actor(), sound.zoneRender()));
            }
        }
        for (var volume : volumes) {
            if (volume.getZoneRender().mask() != 0) {
                kept.add(new Sound(volume.getName(), volume.getZoneRender()));
            }
        }
        return kept;
    }

    static String mapOf(String actor) {
        return ZoneStateChoice.mapOf(actor);
    }

    String getMap() {
        return map;
    }

    List<Integer> getStates() {
        return states;
    }

    String getStatesText() {
        return statesText;
    }

    boolean isEmpty() {
        return states.isEmpty();
    }

    int getDefaultState() {
        return defaultState;
    }

    int getChoice(ZoneStateChoice zoneStates) {
        return zoneStates.getState(map, defaultState);
    }

    Row describe(int state, ZoneStateChoice zoneStates) {
        var bit = 1 << (state - 1);
        var choice = getChoice(zoneStates);
        var counted = 0;
        var now = 0;
        var meshCount = 0;
        var skins = 0;
        for (var mesh : meshes) {
            if (mesh.zoneStart() == ZoneRender.NO_ZONE_STATES) {
                continue;
            }
            boolean shows;
            if ((mesh.mask() & bit) != 0) {
                meshCount++;
                shows = mesh.render().shows(choice);
            } else if ((mesh.looks() & bit) != 0) {
                skins++;
                shows = mesh.render().shows(choice) && isInState(mesh.zoneStart(), state, choice);
            } else {
                continue;
            }
            counted++;
            now += shows ? 1 : 0;
        }
        var kinds = new EnumMap<ZoneStateItem.Kind, Integer>(ZoneStateItem.Kind.class);
        for (var item : items) {
            if ((item.render().mask() & bit) != 0) {
                kinds.merge(item.kind(), 1, Integer::sum);
                counted++;
                now += item.shows(zoneStates) ? 1 : 0;
            }
        }
        var zoneFogs = new StringBuilder();
        var fogCount = kinds.getOrDefault(ZoneStateItem.Kind.FOG, 0);
        for (var fog : fogs) {
            if (fog.state() != state) {
                continue;
            }
            fogCount++;
            counted++;
            now += isInState(fog.zoneDefault(), state, choice) ? 1 : 0;
            zoneFogs.append(String.format(
                    Locale.ROOT,
                    " The fog of %s is %s from %,d to %,d units.",
                    fog.zone(),
                    fog.colorText(),
                    Math.round(fog.fog().start()),
                    Math.round(fog.fog().end())));
        }
        var soundCount = 0;
        for (var sound : sounds) {
            if (sound.zoneRender().zoneStart() == ZoneRender.NO_ZONE_STATES
                    || (sound.zoneRender().mask() & bit) == 0) {
                continue;
            }
            soundCount++;
            counted++;
            now += sound.zoneRender().shows(choice) ? 1 : 0;
        }
        var parts = new ArrayList<String>();
        addCount(parts, meshCount, "mesh", "meshes");
        addCount(parts, skins, "skin", "skins");
        addCount(parts, kinds.getOrDefault(ZoneStateItem.Kind.EFFECT, 0), "effect", "effects");
        addWord(parts, kinds.getOrDefault(ZoneStateItem.Kind.TERRAIN_LAYER, 0), "terrain");
        addWord(parts, kinds.getOrDefault(ZoneStateItem.Kind.DECORATION, 0), "decorations");
        addWord(parts, kinds.getOrDefault(ZoneStateItem.Kind.WATER, 0), "water");
        addWord(parts, fogCount, "fog");
        addWord(parts, kinds.getOrDefault(ZoneStateItem.Kind.SKY, 0), "sky");
        addCount(parts, kinds.getOrDefault(ZoneStateItem.Kind.SKY_ZONE, 0), "sky zone", "sky zones");
        addCount(parts, soundCount, "sound", "sounds");
        if (parts.isEmpty()) {
            parts.add(starts.contains(state) ? "a zone starts in it" : "nothing of the map lists it");
        }
        var tip = new StringBuilder(
                state == choice
                        ? "Every zone with zone states in " + map + " is in this state."
                        : "Show every zone with zone states in " + map + " in this state. The other maps keep their"
                                + " states.");
        if (state == defaultState) {
            tip.append(" The map starts in this state here: the map gives its zones state 0, where the game shows"
                    + " nothing of a state until the server sends one.");
        }
        addSentence(tip, skins, "mesh wears skins", "meshes wear skins", "of its own", "of their own");
        addSentence(
                tip,
                kinds.getOrDefault(ZoneStateItem.Kind.TERRAIN_LAYER, 0),
                "terrain layer shows",
                "terrain layers show");
        addSentence(
                tip,
                kinds.getOrDefault(ZoneStateItem.Kind.DECORATION, 0),
                "decoration layer grows",
                "decoration layers grow");
        addSentence(
                tip,
                kinds.getOrDefault(ZoneStateItem.Kind.WATER, 0),
                "water volume is water",
                "water volumes are water");
        tip.append(zoneFogs);
        addSentence(
                tip,
                kinds.getOrDefault(ZoneStateItem.Kind.FOG, 0),
                "L2FogInfo actor adds its fog",
                "L2FogInfo actors add their fog");
        addSentence(
                tip,
                kinds.getOrDefault(ZoneStateItem.Kind.SKY, 0),
                "layer of the sky changes",
                "layers of the sky change");
        addSentence(
                tip,
                kinds.getOrDefault(ZoneStateItem.Kind.SKY_ZONE, 0),
                "sky zone gives its place",
                "sky zones give their places",
                "a time of day or a sky of its own",
                "a time of day or a sky of their own");
        addSentence(tip, soundCount, "sound plays", "sounds play");
        var status = counted == 0 || now == counted
                ? (counted == 0 ? "" : "Showing")
                : now == 0 ? "Hidden" : String.format(Locale.ROOT, "%d of %d", now, counted);
        return new Row(String.join(" · ", parts), status, tip.toString(), now > 0);
    }

    private static boolean isInState(int zoneStart, int state, int choice) {
        return new ZoneRender(1 << (state - 1), zoneStart, true).shows(choice);
    }

    private static <T> Map<String, List<T>> group(
            List<T> things, Function<T, String> nameOf, Function<T, ZoneRender> renderOf, Map<String, String> names) {
        var grouped = new HashMap<String, List<T>>();
        for (var thing : things) {
            var name = nameOf.apply(thing);
            var followed = renderOf.apply(thing).map();
            var key = followed.isEmpty() ? ZoneStateChoice.key(name) : followed;
            if (ZoneStateChoice.key(name).equals(key)) {
                names.merge(key, name, (kept, given) -> kept.equals(key) ? given : kept);
            } else {
                names.putIfAbsent(key, key);
            }
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(thing);
        }
        return grouped;
    }

    private static void addZoneState(TreeSet<Integer> states, int state) {
        if (state >= 1 && state <= MapEventState.MAX_ZONE_STATE) {
            states.add(state);
        }
    }

    private static void addCount(List<String> parts, int count, String one, String many) {
        if (count > 0) {
            parts.add(String.format(Locale.ROOT, "%,d %s", count, count == 1 ? one : many));
        }
    }

    private static void addWord(List<String> parts, int count, String word) {
        if (count > 0) {
            parts.add(word);
        }
    }

    private static void addSentence(StringBuilder tip, int count, String one, String many) {
        addSentence(tip, count, one, many, "", "");
    }

    private static void addSentence(
            StringBuilder tip, int count, String one, String many, String oneEnd, String manyEnd) {
        if (count > 0) {
            var end = count == 1 ? oneEnd : manyEnd;
            tip.append(String.format(
                    Locale.ROOT,
                    " %,d %s%s in this state.",
                    count,
                    count == 1 ? one : many,
                    end.isEmpty() ? "" : " " + end));
        }
    }
}
