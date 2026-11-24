package com.rost.cruma.core.world;

import com.rost.cruma.core.spawn.SpawnText;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

public record ServerEvents(Map<Integer, Event> events) {
    public static final String AREAS = "areadata.txt";
    public static final String DOORS = "doordata.txt";
    public static final ServerEvents NONE = new ServerEvents(Map.of());

    private static final Pattern AREA = Pattern.compile("area_begin(.*?)area_end", Pattern.DOTALL);
    private static final Pattern DOOR = Pattern.compile("door_begin\\s*\\[([^\\]]*)\\](.*?)door_end", Pattern.DOTALL);
    private static final Pattern NAME = Pattern.compile("(?:^|\\s)name=\\[([^\\]]*)\\]");
    private static final Pattern EVENT_ID = Pattern.compile("(?:^|\\s)event_id=(\\d+)");
    private static final Pattern EMITTER_ID = Pattern.compile("(?:^|\\s)emitter_id=(\\d+)");
    private static final Pattern DEFAULT_STATUS = Pattern.compile("(?:^|\\s)default_status=(on|off)");

    private static final Map<Path, Cached> CACHE = new ConcurrentHashMap<>();

    public record Event(int id, List<String> names, boolean onAtStart) {
        public Event {
            names = List.copyOf(names);
        }

        public String name() {
            return String.join(", ", names);
        }
    }

    private record Cached(long stamp, ServerEvents events) {}

    public ServerEvents {
        events = Map.copyOf(events);
    }

    public static ServerEvents of(@Nullable Path folder) {
        if (folder == null) {
            return NONE;
        }
        for (var inside : List.of("script", "scripts")) {
            if (find(folder, AREAS) == null
                    && find(folder, DOORS) == null
                    && Files.isDirectory(folder.resolve(inside))) {
                folder = folder.resolve(inside);
            }
        }
        var areas = find(folder, AREAS);
        var doors = find(folder, DOORS);
        if (areas == null && doors == null) {
            return NONE;
        }
        var key = folder.toAbsolutePath().normalize();
        var stamp = stamp(areas) * 31 + stamp(doors);
        var cached = CACHE.get(key);
        if (cached != null && cached.stamp() == stamp) {
            return cached.events();
        }
        ServerEvents events;
        try {
            events = parse(
                    areas == null ? "" : SpawnText.read(areas).text(),
                    doors == null ? "" : SpawnText.read(doors).text());
        } catch (IOException | RuntimeException unreadable) {
            events = NONE;
        }
        CACHE.put(key, new Cached(stamp, events));
        return events;
    }

    public static ServerEvents parse(String areas, String doors) {
        var names = new TreeMap<Integer, List<String>>();
        var on = new HashSet<Integer>();
        var matcher = AREA.matcher(areas);
        while (matcher.find()) {
            var body = matcher.group(1);
            var id = EVENT_ID.matcher(body);
            if (!id.find()) {
                continue;
            }
            var event = Integer.parseInt(id.group(1));
            if (event == 0) {
                continue;
            }
            var name = NAME.matcher(body);
            add(names, event, name.find() ? name.group(1) : "");
            var status = DEFAULT_STATUS.matcher(body);
            if (!status.find() || status.group(1).equals("on")) {
                on.add(event);
            }
        }
        matcher = DOOR.matcher(doors);
        while (matcher.find()) {
            var id = EMITTER_ID.matcher(matcher.group(2));
            if (id.find() && Integer.parseInt(id.group(1)) != 0) {
                add(names, Integer.parseInt(id.group(1)), matcher.group(1));
            }
        }
        var events = new LinkedHashMap<Integer, Event>();
        names.forEach((id, list) -> events.put(id, new Event(id, list, on.contains(id))));
        return new ServerEvents(events);
    }

    public boolean isEmpty() {
        return events.isEmpty();
    }

    public @Nullable Event get(int id) {
        return events.get(id);
    }

    public String name(int id) {
        var event = events.get(id);
        return event == null ? "" : event.name();
    }

    public Set<Integer> onAtStart() {
        var on = new HashSet<Integer>();
        events.forEach((id, event) -> {
            if (event.onAtStart()) {
                on.add(id);
            }
        });
        return Set.copyOf(on);
    }

    public boolean nameContains(int id, String lowerCaseText) {
        return name(id).toLowerCase(Locale.ROOT).contains(lowerCaseText);
    }

    private static void add(Map<Integer, List<String>> names, int id, String name) {
        var list = names.computeIfAbsent(id, ignored -> new ArrayList<>());
        if (!name.isBlank() && !list.contains(name)) {
            list.add(name);
        }
    }

    private static long stamp(@Nullable Path file) {
        if (file == null) {
            return 0;
        }
        try {
            return Files.getLastModifiedTime(file).toMillis() * 31 + Files.size(file);
        } catch (IOException unreadable) {
            return -1;
        }
    }

    private static @Nullable Path find(Path folder, String name) {
        try (var entries = Files.list(folder)) {
            return entries.filter(path -> path.getFileName().toString().equalsIgnoreCase(name))
                    .filter(Files::isRegularFile)
                    .findFirst()
                    .orElse(null);
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }
}
