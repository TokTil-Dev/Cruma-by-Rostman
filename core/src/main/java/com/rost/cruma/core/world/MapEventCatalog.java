package com.rost.cruma.core.world;

import com.rost.cruma.core.geometry.Bounds3f;
import com.rost.cruma.core.world.EmitterTimeWindows.Window;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;

public final class MapEventCatalog {
    public static final MapEventCatalog EMPTY = new MapEventCatalog(List.of(), List.of(), List.of());
    private static final String[] CASTLES = {
        "Gludio", "Dion", "Giran", "Oren", "Aden", "Innadril", "Goddard", "Rune", "Schuttgart"
    };
    private static final float FRAME_MARGIN = 384f;

    private final List<Group> events;
    private final List<Group> castles;
    private final List<Timed> timed;

    private MapEventCatalog(List<Group> events, List<Group> castles, List<Timed> timed) {
        this.events = events;
        this.castles = castles;
        this.timed = timed;
    }

    public record Placed(String name, String map, float x, float y, float z, List<int[]> entries) {}

    public record TimedPlaced(
            String name, String map, float x, float y, float z, EmitterTimeWindows windows, String effect) {}

    public record TimedSound(
            String name, String map, float x, float y, float z, AmbientSoundHours hours, String sound) {}

    public record Timed(
            EmitterTimeWindows windows,
            List<String> effects,
            Group group,
            AmbientSoundHours soundHours,
            List<String> soundNames,
            List<Actor> sounds) {
        public Timed {
            effects = List.copyOf(effects);
            soundNames = List.copyOf(soundNames);
            sounds = List.copyOf(sounds);
            soundHours = soundHours == null ? AmbientSoundHours.NONE : soundHours;
        }

        public Timed(EmitterTimeWindows windows, List<String> effects, Group group) {
            this(windows, effects, group, AmbientSoundHours.NONE, List.of(), List.of());
        }

        public int effectCount() {
            return group.actors().size();
        }

        public int soundCount() {
            return sounds.size();
        }

        public Group places() {
            return group.actors().isEmpty() ? new Group(group.id(), group.name(), sounds) : group;
        }

        public List<String> maps() {
            var maps = new ArrayList<>(group.maps());
            for (var sound : sounds) {
                if (!maps.contains(sound.map())) {
                    maps.add(sound.map());
                }
            }
            return maps;
        }

        public boolean isOn(MapEventState state, double hour) {
            return effectCount() > 0 && state.timeRatio(windows, hour) > 0f
                    || soundCount() > 0 && state.playsInHours(soundHours, hour);
        }

        public Window nextWindow(float hour) {
            for (var window : windows.windows()) {
                if (window.middle() > hour + 1e-3f) {
                    return window;
                }
            }
            return windows.windows().getFirst();
        }
    }

    public record Actor(String name, String map, float x, float y, float z, boolean dark) {}

    public record Group(int id, String name, List<Actor> actors) {
        public Group {
            actors = List.copyOf(actors);
        }

        public List<String> maps() {
            var maps = new ArrayList<String>();
            for (var actor : actors) {
                if (!maps.contains(actor.map())) {
                    maps.add(actor.map());
                }
            }
            return maps;
        }

        public int darkCount() {
            var count = 0;
            for (var actor : actors) {
                if (actor.dark()) {
                    count++;
                }
            }
            return count;
        }

        public Actor nearest(float x, float y, float z) {
            var best = actors.getFirst();
            var bestDistance = Float.MAX_VALUE;
            for (var actor : actors) {
                var dx = actor.x() - x;
                var dy = actor.y() - y;
                var dz = actor.z() - z;
                var distance = dx * dx + dy * dy + dz * dz;
                if (distance < bestDistance) {
                    best = actor;
                    bestDistance = distance;
                }
            }
            return best;
        }

        public Bounds3f boundsNear(float x, float y, float z) {
            var map = nearest(x, y, z).map();
            var minX = Float.MAX_VALUE;
            var minY = Float.MAX_VALUE;
            var minZ = Float.MAX_VALUE;
            var maxX = -Float.MAX_VALUE;
            var maxY = -Float.MAX_VALUE;
            var maxZ = -Float.MAX_VALUE;
            for (var actor : actors) {
                if (actor.map().equals(map)) {
                    minX = Math.min(minX, actor.x());
                    minY = Math.min(minY, actor.y());
                    minZ = Math.min(minZ, actor.z());
                    maxX = Math.max(maxX, actor.x());
                    maxY = Math.max(maxY, actor.y());
                    maxZ = Math.max(maxZ, actor.z());
                }
            }
            return new Bounds3f(
                    minX - FRAME_MARGIN,
                    minY - FRAME_MARGIN,
                    minZ - FRAME_MARGIN,
                    maxX + FRAME_MARGIN,
                    maxY + FRAME_MARGIN,
                    maxZ + FRAME_MARGIN);
        }
    }

    public static MapEventCatalog of(List<Placed> placed) {
        return of(placed, List.of());
    }

    public static MapEventCatalog of(List<Placed> placed, List<TimedPlaced> timedPlaced) {
        return of(placed, timedPlaced, List.of());
    }

    public static MapEventCatalog of(List<Placed> placed, List<TimedPlaced> timedPlaced, List<TimedSound> timedSounds) {
        var events = new TreeMap<Integer, List<Actor>>();
        var castles = new TreeMap<Integer, List<Actor>>();
        for (var actor : placed) {
            for (var entry : actor.entries()) {
                var command = entry[1];
                if (!MapEventState.switchesVisibility(command)) {
                    continue;
                }
                var castle = command != MapEventState.SHOW;
                var list = (castle ? castles : events).computeIfAbsent(entry[0], ignored -> new ArrayList<>());
                var item = new Actor(
                        actor.name(),
                        actor.map(),
                        actor.x(),
                        actor.y(),
                        actor.z(),
                        command == MapEventState.CASTLE_DARK);
                if (!list.contains(item)) {
                    list.add(item);
                }
            }
        }
        var eventGroups = new ArrayList<Group>(events.size());
        events.forEach((id, actors) -> eventGroups.add(new Group(id, "Event " + id, actors)));
        var castleGroups = new ArrayList<Group>(castles.size());
        castles.forEach((id, actors) -> castleGroups.add(new Group(id, castleName(id), actors)));
        return new MapEventCatalog(
                List.copyOf(eventGroups), List.copyOf(castleGroups), timed(timedPlaced, timedSounds));
    }

    public static String castleName(int id) {
        return id >= 1 && id <= CASTLES.length ? CASTLES[id - 1] : "Castle " + id;
    }

    public List<Group> getEvents() {
        return events;
    }

    public List<Group> getCastles() {
        return castles;
    }

    public List<Timed> getTimed() {
        return timed;
    }

    public boolean isEmpty() {
        return events.isEmpty() && castles.isEmpty() && timed.isEmpty();
    }

    private static List<Timed> timed(List<TimedPlaced> placed, List<TimedSound> placedSounds) {
        var actors = new LinkedHashMap<EmitterTimeWindows, List<Actor>>();
        var effects = new LinkedHashMap<EmitterTimeWindows, Map<String, Integer>>();
        for (var child : placed) {
            if (child.windows().always()) {
                continue;
            }
            var item = new Actor(child.name(), child.map(), child.x(), child.y(), child.z(), false);
            var list = actors.computeIfAbsent(child.windows(), ignored -> new ArrayList<>());
            if (!list.contains(item)) {
                list.add(item);
            }
            effects.computeIfAbsent(child.windows(), ignored -> new LinkedHashMap<>())
                    .merge(child.effect(), 1, Integer::sum);
        }
        var byLabel = new LinkedHashMap<String, EmitterTimeWindows>();
        for (var hours : actors.keySet()) {
            byLabel.putIfAbsent(hours.label(), hours);
        }
        var sounds = new LinkedHashMap<EmitterTimeWindows, List<Actor>>();
        var soundNames = new LinkedHashMap<EmitterTimeWindows, Map<String, Integer>>();
        var soundHours = new LinkedHashMap<EmitterTimeWindows, AmbientSoundHours>();
        for (var sound : placedSounds) {
            if (sound.hours().isEmpty()) {
                continue;
            }
            var hours = byLabel.computeIfAbsent(
                    sound.hours().label(), ignored -> sound.hours().timeWindows());
            var item = new Actor(sound.name(), sound.map(), sound.x(), sound.y(), sound.z(), false);
            var list = sounds.computeIfAbsent(hours, ignored -> new ArrayList<>());
            if (!list.contains(item)) {
                list.add(item);
            }
            soundNames.computeIfAbsent(hours, ignored -> new LinkedHashMap<>()).merge(sound.sound(), 1, Integer::sum);
            soundHours.putIfAbsent(hours, sound.hours());
        }
        var windows = new ArrayList<>(actors.keySet());
        for (var hours : sounds.keySet()) {
            if (!actors.containsKey(hours)) {
                windows.add(hours);
            }
        }
        windows.sort(Comparator.comparingDouble(
                        (EmitterTimeWindows hours) -> hours.windows().getFirst().min())
                .thenComparing(EmitterTimeWindows::label));
        var groups = new ArrayList<Timed>(windows.size());
        for (var hours : windows) {
            groups.add(new Timed(
                    hours,
                    mostCommonFirst(effects.get(hours)),
                    new Group(hours.label().hashCode(), hours.label(), actors.getOrDefault(hours, List.of())),
                    soundHours.get(hours),
                    mostCommonFirst(soundNames.get(hours)),
                    sounds.getOrDefault(hours, List.of())));
        }
        return List.copyOf(groups);
    }

    private static List<String> mostCommonFirst(Map<String, Integer> counts) {
        if (counts == null) {
            return List.of();
        }
        var names = new ArrayList<>(counts.entrySet());
        names.sort(Entry.<String, Integer>comparingByValue().reversed().thenComparing(Entry.comparingByKey()));
        return names.stream().map(Entry::getKey).toList();
    }
}
