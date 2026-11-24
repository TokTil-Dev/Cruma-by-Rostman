package com.rost.cruma.viewer.config;

import com.rost.cruma.core.world.MapEventState;
import com.rost.cruma.core.world.MapEventState.Start;
import com.typesafe.config.ConfigValue;
import com.typesafe.config.ConfigValueFactory;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

public record CaptureView(
        String tile,
        String name,
        @Nullable Integer top,
        @Nullable Integer bottom,
        boolean hidePassable,
        boolean main,
        @Nullable Integer resolution,
        KeptLayers kept,
        @Nullable CutCircle circle,
        ViewContent content) {
    public static final Set<String> KEYS = Set.of(
            "tile",
            "name",
            "top",
            "bottom",
            "hide_passable",
            "main",
            "resolution",
            "keep_terrain",
            "keep_static_meshes",
            "keep_csg",
            "circle_x",
            "circle_y",
            "circle_radius",
            "effects",
            "map_events",
            "events",
            "castle_sides",
            "zone_state");

    public static final int HEIGHT_LIMIT = 16_300;

    private static final List<String> CIRCLE_KEYS = List.of("circle_x", "circle_y", "circle_radius");

    private static final Pattern TILE = Pattern.compile("\\d{1,3}_\\d{1,3}");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    public CaptureView {
        Objects.requireNonNull(tile, "tile");
        name = name == null ? "" : name.strip();
        kept = kept == null ? KeptLayers.NONE : kept;
        content = content == null ? ViewContent.AS_THE_CLIENT : content;
        if (!TILE.matcher(tile).matches()) {
            throw new IllegalArgumentException("Capture view tile must look like 23_18, got '" + tile + "'");
        }
        if (!main && !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Capture view of tile " + tile
                    + " needs a name of letters, digits, dots, dashes or underscores. It becomes the file suffix.");
        }
        for (var height : new Integer[] {top, bottom}) {
            if (height != null && Math.abs(height) > HEIGHT_LIMIT) {
                throw new IllegalArgumentException("Capture view heights must be within -" + HEIGHT_LIMIT + ".."
                        + HEIGHT_LIMIT + ", got " + height);
            }
        }
        if (top != null && bottom != null && bottom >= top) {
            throw new IllegalArgumentException("Capture view " + label(tile, name, main) + " needs bottom below top");
        }
        if (resolution != null && (resolution < 16 || resolution > 16_384)) {
            throw new IllegalArgumentException("Capture view " + label(tile, name, main)
                    + " resolution must be within 16..16384, got " + resolution);
        }
    }

    public CaptureView(
            String tile,
            String name,
            @Nullable Integer top,
            @Nullable Integer bottom,
            boolean hidePassable,
            boolean main) {
        this(tile, name, top, bottom, hidePassable, main, null);
    }

    public CaptureView(
            String tile,
            String name,
            @Nullable Integer top,
            @Nullable Integer bottom,
            boolean hidePassable,
            boolean main,
            @Nullable Integer resolution) {
        this(tile, name, top, bottom, hidePassable, main, resolution, KeptLayers.NONE);
    }

    public CaptureView(
            String tile,
            String name,
            @Nullable Integer top,
            @Nullable Integer bottom,
            boolean hidePassable,
            boolean main,
            @Nullable Integer resolution,
            KeptLayers kept) {
        this(tile, name, top, bottom, hidePassable, main, resolution, kept, null);
    }

    public CaptureView(
            String tile,
            String name,
            @Nullable Integer top,
            @Nullable Integer bottom,
            boolean hidePassable,
            boolean main,
            @Nullable Integer resolution,
            KeptLayers kept,
            @Nullable CutCircle circle) {
        this(tile, name, top, bottom, hidePassable, main, resolution, kept, circle, ViewContent.AS_THE_CLIENT);
    }

    public static void requireDistinct(List<CaptureView> views) {
        var seen = new HashSet<String>();
        for (var view : views) {
            if (!seen.add(view.tile() + (view.main() ? "" : "/" + view.name().toLowerCase(Locale.ROOT)))) {
                throw new IllegalArgumentException(
                        view.main()
                                ? "Tile " + view.tile() + " has more than one main capture view"
                                : "Tile " + view.tile() + " has more than one capture view named " + view.name());
            }
        }
    }

    public static CaptureView parse(com.typesafe.config.Config entry) {
        for (Entry<String, ConfigValue> value : entry.root().entrySet()) {
            if (!KEYS.contains(value.getKey())) {
                throw new IllegalArgumentException("Unknown key '" + value.getKey() + "' in capture.output.views. Use "
                        + String.join(", ", KEYS.stream().sorted().toList()));
            }
        }
        return new CaptureView(
                entry.getString("tile"),
                entry.hasPath("name") ? entry.getString("name") : "",
                entry.hasPath("top") ? entry.getInt("top") : null,
                entry.hasPath("bottom") ? entry.getInt("bottom") : null,
                entry.hasPath("hide_passable") && entry.getBoolean("hide_passable"),
                entry.hasPath("main") && entry.getBoolean("main"),
                entry.hasPath("resolution") ? entry.getInt("resolution") : null,
                KeptLayers.of(
                        entry.hasPath("keep_terrain") && entry.getBoolean("keep_terrain"),
                        entry.hasPath("keep_static_meshes") && entry.getBoolean("keep_static_meshes"),
                        entry.hasPath("keep_csg") && entry.getBoolean("keep_csg")),
                parseCircle(entry),
                parseContent(entry));
    }

    public int mapX() {
        return Integer.parseInt(tile.substring(0, tile.indexOf('_')));
    }

    public int mapY() {
        return Integer.parseInt(tile.substring(tile.indexOf('_') + 1));
    }

    public String label() {
        return label(tile, name, main);
    }

    public ConfigValue toConfigValue() {
        var values = new TreeMap<String, Object>();
        values.put("tile", tile);
        if (!name.isEmpty()) {
            values.put("name", name);
        }
        if (top != null) {
            values.put("top", top);
        }
        if (bottom != null) {
            values.put("bottom", bottom);
        }
        if (hidePassable) {
            values.put("hide_passable", true);
        }
        if (main) {
            values.put("main", true);
        }
        if (resolution != null) {
            values.put("resolution", resolution);
        }
        if (kept.terrain()) {
            values.put("keep_terrain", true);
        }
        if (kept.staticMeshes()) {
            values.put("keep_static_meshes", true);
        }
        if (kept.csg()) {
            values.put("keep_csg", true);
        }
        if (circle != null) {
            values.put("circle_x", circle.x());
            values.put("circle_y", circle.y());
            values.put("circle_radius", circle.radius());
        }
        if (!content.effects()) {
            values.put("effects", false);
        }
        if (content.mapEvents() != null) {
            values.put("map_events", content.mapEvents().key);
        }
        if (!content.events().isEmpty()) {
            values.put("events", content.events());
        }
        if (content.castleSides() != null) {
            values.put("castle_sides", sideKey(content.castleSides()));
        }
        if (content.zoneState() != null) {
            values.put("zone_state", content.zoneState());
        }
        return ConfigValueFactory.fromMap(values);
    }

    private static @Nullable CutCircle parseCircle(com.typesafe.config.Config entry) {
        var present = CIRCLE_KEYS.stream().filter(entry::hasPath).count();
        if (present == 0) {
            return null;
        }
        if (present < CIRCLE_KEYS.size()) {
            throw new IllegalArgumentException("Capture view of tile " + entry.getString("tile")
                    + " needs circle_x, circle_y and circle_radius together");
        }
        return new CutCircle(entry.getInt("circle_x"), entry.getInt("circle_y"), entry.getInt("circle_radius"));
    }

    private static ViewContent parseContent(com.typesafe.config.Config entry) {
        Start start = null;
        if (entry.hasPath("map_events")) {
            start = Start.parse(entry.getString("map_events"));
            if (start == null || start == Start.SERVER_START) {
                throw new IllegalArgumentException("Capture view of tile " + entry.getString("tile")
                        + " has map_events '" + entry.getString("map_events") + "'. Use " + Start.ALL_SHOWN.key
                        + " or " + Start.GAME_START.key + ", or leave it out to follow the client.");
            }
        }
        return new ViewContent(
                !entry.hasPath("effects") || entry.getBoolean("effects"),
                start,
                entry.hasPath("events") ? entry.getIntList("events") : List.of(),
                entry.hasPath("castle_sides") ? parseSide(entry) : null,
                entry.hasPath("zone_state") ? entry.getInt("zone_state") : null);
    }

    private static String sideKey(int side) {
        return switch (side) {
            case MapEventState.LIGHT -> "light";
            case MapEventState.DARK -> "dark";
            case MapEventState.BOTH_SIDES -> "both";
            default -> "none";
        };
    }

    private static int parseSide(com.typesafe.config.Config entry) {
        var word = entry.getString("castle_sides");
        return switch (word) {
            case "light" -> MapEventState.LIGHT;
            case "dark" -> MapEventState.DARK;
            case "both" -> MapEventState.BOTH_SIDES;
            case "none" -> MapEventState.NO_SIDE;
            default ->
                throw new IllegalArgumentException("Capture view of tile " + entry.getString("tile")
                        + " has castle_sides '" + word + "'. Use both, light, dark or none, or leave it out.");
        };
    }

    private static String label(String tile, String name, boolean main) {
        return main ? tile + " main image" : tile + " " + name;
    }
}
