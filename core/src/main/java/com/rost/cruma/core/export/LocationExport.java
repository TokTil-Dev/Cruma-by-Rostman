package com.rost.cruma.core.export;

import com.rost.cruma.core.audio.WavWriter;
import com.rost.cruma.core.env.LobbyTimeEnv;
import com.rost.cruma.core.export.LocationEnvironment.Reader;
import com.rost.cruma.core.export.LocationModel.MusicTrack;
import com.rost.cruma.core.export.LocationModel.Sound;
import com.rost.cruma.core.export.NpcExport.Npc;
import com.rost.cruma.core.texture.DxtMipCodec.Options;
import com.rost.cruma.core.world.Entity.Texture;
import com.rost.cruma.core.world.MapEventState;
import com.rost.cruma.core.world.MapEventState.Clock;
import com.rost.cruma.core.world.NamedMapStart;
import com.rost.cruma.core.world.NpcModel;
import com.rost.cruma.core.world.UMap;
import com.rost.cruma.core.world.UnrealLoader;
import com.rost.cruma.core.world.UnrealLoader.Outfit;
import com.rost.cruma.core.world.WorldDimensions;
import com.rost.cruma.core.world.WorldTile;
import com.rost.cruma.core.world.WorldVariant;
import com.rost.cruma.core.world.ZoneStateChoice;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import org.jetbrains.annotations.Nullable;

public final class LocationExport {
    public static final String INFO = "export-info.txt";

    public static final String BLENDER_SCRIPT = "open-in-blender.py";

    public static final String UNITY_SCRIPT = "import-into-unity.cs";

    public static final String UNITY_MUSIC = "CrumaMusic.cs";

    public static final String UNITY_SOUNDS = "CrumaSounds.cs";

    public static final String UNITY_MENU = "Cruma > Import sounds, music and effects";

    public static final String UNITY_COMPONENT = "CrumaEffect.cs";

    public static final String SOUNDS = LocationModelBuilder.SOUND_FOLDER;

    public static final String SOUNDS_FILE = LocationJson.SOUNDS;

    public static final String MUSIC = LocationMusic.FOLDER;

    public static final String MUSIC_FILE = LocationJson.MUSIC;

    public static final String LIGHTS_FILE = LocationJson.LIGHTS;

    public static final String EFFECTS_FILE = LocationJson.EFFECTS;

    public static final int MAX_TILES = 25;

    public static final int COMFORTABLE_TILES = 9;

    public static final int[] TERRAIN_SIZES = {2048, 4096, 8192};

    public static final String NPCS = "npcs";

    public static final String FIGURES = MapFigures.FOLDER;

    public static final float METERS_PER_UNIT = ExportScale.REAL_SIZE.metersPerUnit();

    public static final String SKY = "sky.png";

    public static final int EFFECT_STILLS = 24;

    public static final float EFFECT_INTERVAL = 1f / 12f;

    private static final String RESOURCES = "/com/rost/cruma/core/export/";

    private static final String BLENDER_SCRIPT_RESOURCE = RESOURCES + BLENDER_SCRIPT;

    private static final double READING = .35;

    private static final String TEXTURES = "textures";

    private LocationExport() {}

    public static Path tileFolder(Path parent, String tile) {
        return parent.resolve(tile);
    }

    public static List<String> fileNames(String tile, Settings settings) {
        var names = new ArrayList<String>();
        names.add(tile + ".glb");
        names.add(TerrainLayers.FOLDER);
        names.add(LocationModelBuilder.PROJECTORS);
        names.add(LocationModelBuilder.LIGHTMAPS);
        names.add(LocationMaterials.FRAMES);
        names.add(LocationMaterials.EFFECTS);
        names.add(SKY);
        if (settings.npcs()) {
            names.add(NPCS);
        }
        names.add(FIGURES);
        names.add(SOUNDS);
        names.add(SOUNDS_FILE);
        names.add(MUSIC);
        names.add(MUSIC_FILE);
        names.add(EFFECTS_FILE);
        names.add(LIGHTS_FILE);
        names.add(BLENDER_SCRIPT);
        names.add(UNITY_SCRIPT);
        names.add(UNITY_COMPONENT);
        names.add(UNITY_MUSIC);
        names.add(UNITY_SOUNDS);
        names.add(INFO);
        return List.copyOf(names);
    }

    public static List<Path> findExistingFiles(Path parent, String tile, Settings settings) {
        var result = new ArrayList<Path>();
        var folder = tileFolder(parent, tile);
        for (var name : fileNames(tile, settings)) {
            var path = folder.resolve(name);
            if (Files.exists(path)) {
                result.add(path);
            }
        }
        return List.copyOf(result);
    }

    public static String name(String tile, WorldVariant variant) {
        return name(List.of(tile), variant);
    }

    public static String name(List<String> tiles, WorldVariant variant, @Nullable Area area) {
        if (area == null) {
            return name(tiles, variant);
        }
        var label = area.label().isBlank() ? name(tiles, variant) : toSafeName(area.label()) + variant.getTableSuffix();
        return label + "-" + Math.round((area.maxX() - area.minX()) * METERS_PER_UNIT) + "m";
    }

    public static String name(List<String> tiles, WorldVariant variant) {
        if (tiles.size() == 1) {
            return tiles.getFirst() + variant.getTableSuffix();
        }
        var minX = Integer.MAX_VALUE;
        var minY = Integer.MAX_VALUE;
        var maxX = Integer.MIN_VALUE;
        var maxY = Integer.MIN_VALUE;
        for (var tile : tiles) {
            var coordinates = LocationModelBuilder.parseTile(tile);
            if (coordinates == null) {
                throw new IllegalArgumentException("not a map tile: " + tile);
            }
            minX = Math.min(minX, coordinates[0]);
            minY = Math.min(minY, coordinates[1]);
            maxX = Math.max(maxX, coordinates[0]);
            maxY = Math.max(maxY, coordinates[1]);
        }
        return minX + "_" + minY + "-" + maxX + "_" + maxY + variant.getTableSuffix();
    }

    public static Result exportTile(
            Path client,
            WorldVariant variant,
            String tile,
            Path parent,
            Settings settings,
            boolean replace,
            Progress progress,
            BooleanSupplier cancelled)
            throws IOException {
        return exportTiles(client, variant, List.of(tile), parent, settings, replace, null, progress, cancelled);
    }

    public static Result exportTiles(
            Path client,
            WorldVariant variant,
            List<String> tiles,
            Path parent,
            Settings settings,
            boolean replace,
            Progress progress,
            BooleanSupplier cancelled)
            throws IOException {
        return exportTiles(client, variant, tiles, parent, settings, replace, null, progress, cancelled);
    }

    public static Result exportTiles(
            Path client,
            WorldVariant variant,
            List<String> tiles,
            Path parent,
            Settings settings,
            boolean replace,
            @Nullable Population population,
            Progress progress,
            BooleanSupplier cancelled)
            throws IOException {
        return exportTiles(
                client, variant, tiles, parent, settings, replace, population, EffectStill.NONE, progress, cancelled);
    }

    public static Result exportTiles(
            Path client,
            WorldVariant variant,
            List<String> tiles,
            Path parent,
            Settings settings,
            boolean replace,
            @Nullable Population population,
            EffectStill effects,
            Progress progress,
            BooleanSupplier cancelled)
            throws IOException {
        checkCount(tiles.size());
        return exportTiles(
                client,
                variant,
                tiles,
                name(tiles, variant, settings.area()),
                parent,
                settings,
                replace,
                population,
                effects,
                progress,
                cancelled);
    }

    static Result exportTiles(
            Path client,
            WorldVariant variant,
            List<String> tiles,
            String name,
            Path parent,
            Settings settings,
            boolean replace,
            @Nullable Population population,
            EffectStill effects,
            Progress progress,
            BooleanSupplier cancelled)
            throws IOException {
        if (settings.npcs() && population == null) {
            throw new IllegalArgumentException("NPCs need a population");
        }
        checkCount(tiles.size());
        var area = settings.area();
        if (area != null && (tiles.isEmpty() || !Set.copyOf(area.tiles()).containsAll(tiles))) {
            throw new IllegalArgumentException("the tiles must be ones the area touches");
        }
        checkTarget(parent, name, settings, replace);
        var loader = new UnrealLoader(client, new Options(false, 0), true);
        loader.worldVariant(variant);
        try {
            var origin = area == null
                    ? LocationModelBuilder.computeOrigin(tiles)
                    : new float[] {area.centerX(), area.centerY(), 0};
            var builder = new LocationModelBuilder(
                            name,
                            origin,
                            area,
                            settings.terrainSize(),
                            settings.decorations(),
                            waterNormal(loader, client),
                            settings.scale(),
                            cancelled)
                    .atHour(settings.hour())
                    .withClientLight(settings.clientLight())
                    .withSounds(settings.sounds())
                    .withMusic(settings.music() ? LocationMusic.of(client, variant) : null)
                    .inZoneState(settings.zoneStates())
                    .byTime(settings.clocks());
            var environment = new Reader(client, loader, origin[0], origin[1], settings.hour(), settings.zoneStates());
            var count = tiles.size();
            var rigs = new LinkedHashMap<Integer, Figure>();
            var mapFigures = new MapFigures(loader);
            for (var index = 0; index < count; index++) {
                var tile = tiles.get(index);
                var of = count == 1 ? "" : ", " + (index + 1) + " of " + count;
                progress.update("Reading map " + tile + of, READING * index / count);
                var map = loader.loadMap(tile);
                if (map == null) {
                    throw new IOException("Map " + tile + " cannot be read from this client.");
                }
                if (cancelled.getAsBoolean()) {
                    throw new CancellationException("export cancelled");
                }
                progress.update("Baking the terrain of " + tile + of, READING * (index + .5) / count);
                builder.add(map);
                environment.add(map);
                if (settings.effects()) {
                    progress.update("Taking a still of the effects of " + tile + of, READING * (index + .7) / count);
                    addEffects(builder, effects, map, settings);
                }
                if (!map.pawns().isEmpty()) {
                    progress.update("Posing the figures of " + tile + of, READING * (index + .75) / count);
                    builder.addMapFigures(mapFigures.collect(map, area));
                }
                if (settings.npcs()) {
                    progress.update("Placing the NPCs of " + tile + of, READING * (index + .8) / count);
                    var figures = population.collectFigures(map, cancelled);
                    if (area != null) {
                        figures = figures.stream()
                                .filter(figure -> area.contains(figure.x(), figure.y()))
                                .toList();
                    }
                    builder.addFigures(figures);
                    for (var figure : figures) {
                        rigs.putIfAbsent(figure.npc().id(), figure);
                    }
                }
            }
            progress.update("Baking the sky at " + settings.hour() + ":00", READING);
            return write(
                    builder.build(),
                    readLight(environment, cancelled),
                    name,
                    clientName(client) + ", " + variant.getLabel() + " world",
                    parent,
                    settings,
                    replace,
                    List.copyOf(rigs.values()),
                    loader,
                    mapFigures,
                    settings.npcs() ? population.withoutPlace(area) : 0,
                    null,
                    progress,
                    cancelled);
        } finally {
            loader.trimCaches();
        }
    }

    public static String mapName(String map) {
        return toSafeName(map);
    }

    public static Result exportMap(
            Path client,
            String map,
            Path parent,
            Settings settings,
            boolean replace,
            Progress progress,
            BooleanSupplier cancelled)
            throws IOException {
        return exportMap(client, map, parent, settings, replace, EffectStill.NONE, progress, cancelled);
    }

    public static Result exportMap(
            Path client,
            String map,
            Path parent,
            Settings settings,
            boolean replace,
            EffectStill effects,
            Progress progress,
            BooleanSupplier cancelled)
            throws IOException {
        return exportMap(client, map, mapName(map), parent, settings, replace, effects, progress, cancelled);
    }

    public static float[] mapOrigin(Path client, String map) {
        var start = NamedMapStart.find(client, map).orElse(null);
        return start == null ? new float[] {0, 0, 0} : new float[] {start.x(), start.y(), 0};
    }

    public static Result exportMap(
            Path client,
            String map,
            String name,
            Path parent,
            Settings settings,
            boolean replace,
            EffectStill effects,
            Progress progress,
            BooleanSupplier cancelled)
            throws IOException {
        checkMap(map, settings);
        checkTarget(parent, name, settings, replace);
        progress.update("Reading map " + map, 0);
        var start = NamedMapStart.find(client, map).orElse(null);
        var origin = mapOrigin(client, map);
        var loader = new UnrealLoader(client, new Options(false, 0), true);
        try {
            var loaded = loader.loadMap(map);
            if (loaded == null) {
                throw new IOException("Map " + map + " cannot be read from this client.");
            }
            if (cancelled.getAsBoolean()) {
                throw new CancellationException("export cancelled");
            }
            progress.update("Baking the terrain of " + map, READING / 2);
            var builder = new LocationModelBuilder(
                            name,
                            origin,
                            null,
                            settings.terrainSize(),
                            settings.decorations(),
                            waterNormal(loader, client),
                            settings.scale(),
                            cancelled)
                    .atHour(settings.hour())
                    .withClientLight(settings.clientLight())
                    .withSounds(settings.sounds())
                    .withMusic(settings.music() ? LocationMusic.of(client, WorldVariant.MAIN) : null)
                    .inZoneState(settings.zoneStates())
                    .byTime(settings.clocks());
            builder.add(loaded);
            if (settings.effects()) {
                progress.update("Taking a still of the effects of " + map, READING * .7);
                addEffects(builder, effects, loaded, settings);
            }
            var mapFigures = new MapFigures(loader);
            if (!loaded.pawns().isEmpty()) {
                progress.update("Posing the figures of " + map, READING * .8);
                builder.addMapFigures(mapFigures.collect(loaded, null));
            }
            var environment = new Reader(client, loader, origin[0], origin[1], settings.hour(), settings.zoneStates());
            environment.add(loaded);
            progress.update("Baking the sky at " + settings.hour() + ":00", READING);
            return write(
                    builder.build(),
                    readLight(environment, cancelled),
                    name,
                    clientName(client),
                    parent,
                    settings,
                    replace,
                    List.of(),
                    loader,
                    mapFigures,
                    0,
                    start,
                    progress,
                    cancelled);
        } finally {
            loader.trimCaches();
        }
    }

    static Result exportMap(
            UMap map,
            NamedMapStart start,
            String source,
            Path parent,
            Settings settings,
            boolean replace,
            Progress progress,
            BooleanSupplier cancelled)
            throws IOException {
        checkMap(map.name(), settings);
        var name = mapName(map.name());
        checkTarget(parent, name, settings, replace);
        progress.update("Baking the terrain", READING / 2);
        var builder = new LocationModelBuilder(
                        name,
                        new float[] {start.x(), start.y(), 0},
                        null,
                        settings.terrainSize(),
                        settings.decorations(),
                        null,
                        settings.scale(),
                        cancelled)
                .atHour(settings.hour())
                .withClientLight(settings.clientLight())
                .withSounds(settings.sounds())
                .byTime(settings.clocks());
        builder.add(map);
        return write(
                builder.build(),
                null,
                name,
                source,
                parent,
                settings,
                replace,
                List.of(),
                null,
                null,
                0,
                start,
                progress,
                cancelled);
    }

    public static Result export(
            List<UMap> maps,
            String name,
            String source,
            Path parent,
            Settings settings,
            boolean replace,
            Progress progress,
            BooleanSupplier cancelled)
            throws IOException {
        return export(maps, name, source, parent, settings, replace, EffectStill.NONE, progress, cancelled);
    }

    public static Result export(
            List<UMap> maps,
            String name,
            String source,
            Path parent,
            Settings settings,
            boolean replace,
            EffectStill effects,
            Progress progress,
            BooleanSupplier cancelled)
            throws IOException {
        checkCount(maps.size());
        checkTarget(parent, name, settings, replace);
        progress.update("Baking the terrain", READING / 2);
        var model = LocationModelBuilder.build(
                maps,
                settings.terrainSize(),
                settings.decorations(),
                settings.scale(),
                settings.hour(),
                settings.clientLight(),
                settings.effects() ? effects : EffectStill.NONE,
                settings.movingEffects() ? EFFECT_STILLS : 1,
                EFFECT_INTERVAL,
                settings.sounds(),
                settings.clocks(),
                cancelled);
        return write(
                model, null, name, source, parent, settings, replace, List.of(), null, null, 0, null, progress,
                cancelled);
    }

    public static void writeBlenderScript(Path folder) throws IOException {
        try (var input = LocationExport.class.getResourceAsStream(BLENDER_SCRIPT_RESOURCE)) {
            if (input == null) {
                throw new IOException(BLENDER_SCRIPT + " is missing from Cruma");
            }
            Files.write(folder.resolve(BLENDER_SCRIPT), input.readAllBytes());
        }
    }

    public static void writeUnityScripts(Path folder) throws IOException {
        for (var name : List.of(UNITY_SCRIPT, UNITY_COMPONENT, UNITY_MUSIC, UNITY_SOUNDS)) {
            try (var input = LocationExport.class.getResourceAsStream(RESOURCES + name)) {
                if (input == null) {
                    throw new IOException(name + " is missing from Cruma");
                }
                Files.write(folder.resolve(name), input.readAllBytes());
            }
        }
    }

    static int writeMusic(LocationModel model, Path folder, int hour, BooleanSupplier cancelled) throws IOException {
        var files = new HashSet<String>();
        for (var tile : model.tiles()) {
            for (var zone : tile.music()) {
                for (var track : Stream.concat(zone.tracks().stream(), zone.nightTracks().stream())
                        .toList()) {
                    if (!files.add(track.file())) {
                        continue;
                    }
                    if (cancelled.getAsBoolean()) {
                        throw new CancellationException("export cancelled");
                    }
                    var target = folder.resolve(track.file());
                    Files.createDirectories(target.getParent());
                    Files.write(target, track.bytes().read());
                }
            }
        }
        Files.writeString(folder.resolve(MUSIC_FILE), LocationJson.music(model, hour), StandardCharsets.UTF_8);
        return files.size();
    }

    static String leftOutInfo(int withoutPlace) {
        return withoutPlace == 0
                ? ""
                : "Left out: "
                        + withoutPlace
                        + (withoutPlace == 1 ? " NPC" : " NPCs")
                        + " of random spawns (pos=anywhere), which stand on the server's geodata. None was given.\n";
    }

    static String mapFigureInfo(int figures, List<String> rigs, List<String> skipped, List<String> unreadable) {
        if (figures == 0 && unreadable.isEmpty()) {
            return "";
        }
        var text = new StringBuilder("\nMap figures: ")
                .append(figures)
                .append(" pawns that the maps place, such as flags and prisoners, in the Map figures part of their"
                        + " tile or map.\n")
                .append("Each stands in the first moment of what it plays, as the viewer draws it, or in the reference"
                        + " pose of its\nskeleton when it plays nothing, and is named after its actor, such as"
                        + " Lobby01.LineagePawn3. Its node's\nextras name its rig (cruma_rig), the sequence it plays"
                        + " first (cruma_pose), its rule (cruma_pawn_list,\ncruma_pawn_interval, cruma_pawn_first,"
                        + " cruma_pawn_random, cruma_pawn_lobby) and how long the client plays\neach sequence"
                        + " (cruma_pawn_seconds).\n")
                .append("Rigs: ")
                .append(rigs.size())
                .append(rigs.size() == 1 ? " file" : " files")
                .append(" in the ")
                .append(FIGURES)
                .append(" folder, one for each mesh and its skins, with its skeleton and every animation:\n");
        for (var rig : rigs) {
            text.append("  ").append(rig).append('\n');
        }
        text.append("A rig's origin goes where its figure's origin is: give it the figure's place, turn, scale and"
                + " mirror.\n");
        if (!skipped.isEmpty()) {
            text.append("Not rigged: ").append(String.join(", ", skipped)).append('\n');
        }
        if (!unreadable.isEmpty()) {
            text.append("Left out, as the client cannot read their mesh: ")
                    .append(String.join(", ", unreadable))
                    .append('\n');
        }
        return text.toString();
    }

    public static String toSafeName(String name) {
        var result = new StringBuilder(name.length());
        for (var i = 0; i < name.length(); i++) {
            var c = name.charAt(i);
            result.append(c < 128 && (Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == '_') ? c : '_');
        }
        return result.isEmpty() ? "unnamed" : result.toString();
    }

    static String[] textureNames(LocationModel model) {
        var names = new String[model.images().size()];
        var taken = new HashSet<String>();
        for (var image = 0; image < names.length; image++) {
            var base = toSafeName(model.images().get(image).name());
            var candidate = base + ".png";
            for (var suffix = 2; !taken.add(candidate.toLowerCase(Locale.ROOT)); suffix++) {
                candidate = base + "_" + suffix + ".png";
            }
            names[image] = candidate;
        }
        return names;
    }

    static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            for (var path : walk.sorted(Comparator.reverseOrder()).toList()) {
                try {
                    Files.deleteIfExists(path);
                } catch (DirectoryNotEmptyException ignored) {
                }
            }
        }
    }

    private static void addEffects(LocationModelBuilder builder, EffectStill effects, UMap map, Settings settings) {
        if (settings.movingEffects()) {
            builder.addEffectLoop(effects.takeLoop(map, EFFECT_STILLS, EFFECT_INTERVAL), EFFECT_INTERVAL);
        } else {
            builder.addEffects(effects.take(map));
        }
    }

    public record Settings(
            int terrainSize,
            boolean decorations,
            boolean npcs,
            @Nullable Area area,
            int hour,
            ExportScale scale,
            boolean clientLight,
            boolean effects,
            boolean movingEffects,
            boolean sounds,
            String mapEvents,
            ZoneStateChoice zoneStates,
            boolean music,
            Map<String, Clock> clocks) {
        public static final int DEFAULT_HOUR = LocationEnvironment.DEFAULT_HOUR;

        public Settings(
                int terrainSize,
                boolean decorations,
                boolean npcs,
                @Nullable Area area,
                int hour,
                ExportScale scale,
                boolean clientLight,
                boolean effects,
                boolean movingEffects,
                boolean sounds,
                String mapEvents,
                ZoneStateChoice zoneStates,
                boolean music) {
            this(
                    terrainSize,
                    decorations,
                    npcs,
                    area,
                    hour,
                    scale,
                    clientLight,
                    effects,
                    movingEffects,
                    sounds,
                    mapEvents,
                    zoneStates,
                    music,
                    Map.of());
        }

        public Settings(
                int terrainSize,
                boolean decorations,
                boolean npcs,
                @Nullable Area area,
                int hour,
                ExportScale scale,
                boolean clientLight,
                boolean effects,
                boolean movingEffects,
                boolean sounds,
                String mapEvents,
                ZoneStateChoice zoneStates) {
            this(
                    terrainSize,
                    decorations,
                    npcs,
                    area,
                    hour,
                    scale,
                    clientLight,
                    effects,
                    movingEffects,
                    sounds,
                    mapEvents,
                    zoneStates,
                    false);
        }

        public Settings(
                int terrainSize,
                boolean decorations,
                boolean npcs,
                @Nullable Area area,
                int hour,
                ExportScale scale,
                boolean clientLight,
                boolean effects,
                boolean movingEffects,
                boolean sounds,
                String mapEvents) {
            this(
                    terrainSize,
                    decorations,
                    npcs,
                    area,
                    hour,
                    scale,
                    clientLight,
                    effects,
                    movingEffects,
                    sounds,
                    mapEvents,
                    ZoneStateChoice.EMPTY);
        }

        public Settings(
                int terrainSize,
                boolean decorations,
                boolean npcs,
                @Nullable Area area,
                int hour,
                ExportScale scale,
                boolean clientLight,
                boolean effects,
                boolean movingEffects,
                boolean sounds) {
            this(terrainSize, decorations, npcs, area, hour, scale, clientLight, effects, movingEffects, sounds, "");
        }

        public Settings(
                int terrainSize,
                boolean decorations,
                boolean npcs,
                @Nullable Area area,
                int hour,
                ExportScale scale,
                boolean clientLight,
                boolean effects,
                boolean movingEffects) {
            this(terrainSize, decorations, npcs, area, hour, scale, clientLight, effects, movingEffects, true);
        }

        public Settings(
                int terrainSize,
                boolean decorations,
                boolean npcs,
                @Nullable Area area,
                int hour,
                ExportScale scale,
                boolean clientLight) {
            this(terrainSize, decorations, npcs, area, hour, scale, clientLight, true);
        }

        public Settings(
                int terrainSize,
                boolean decorations,
                boolean npcs,
                @Nullable Area area,
                int hour,
                ExportScale scale,
                boolean clientLight,
                boolean effects) {
            this(terrainSize, decorations, npcs, area, hour, scale, clientLight, effects, false);
        }

        public Settings(
                int terrainSize, boolean decorations, boolean npcs, @Nullable Area area, int hour, ExportScale scale) {
            this(terrainSize, decorations, npcs, area, hour, scale, true);
        }

        public Settings(int terrainSize, boolean decorations, boolean npcs, @Nullable Area area, int hour) {
            this(terrainSize, decorations, npcs, area, hour, ExportScale.REAL_SIZE);
        }

        public Settings(int terrainSize, boolean decorations) {
            this(terrainSize, decorations, false, null);
        }

        public Settings(int terrainSize, boolean decorations, boolean npcs) {
            this(terrainSize, decorations, npcs, null);
        }

        public Settings(int terrainSize, boolean decorations, boolean npcs, @Nullable Area area) {
            this(terrainSize, decorations, npcs, area, DEFAULT_HOUR);
        }

        public Settings {
            if (terrainSize < 16 || terrainSize > 16384) {
                throw new IllegalArgumentException("terrain texture size must be 16..16384");
            }
            if (hour < 0 || hour > 23) {
                throw new IllegalArgumentException("the hour must be 0..23");
            }
            zoneStates = zoneStates == null ? ZoneStateChoice.EMPTY : zoneStates;
            Objects.requireNonNull(scale, "scale");
            clocks = clocks == null ? Map.of() : Map.copyOf(clocks);
        }

        public Settings withMusic(boolean music) {
            return new Settings(
                    terrainSize,
                    decorations,
                    npcs,
                    area,
                    hour,
                    scale,
                    clientLight,
                    effects,
                    movingEffects,
                    sounds,
                    mapEvents,
                    zoneStates,
                    music,
                    clocks);
        }

        public Settings withClocks(Map<String, Clock> chosen) {
            return new Settings(
                    terrainSize,
                    decorations,
                    npcs,
                    area,
                    hour,
                    scale,
                    clientLight,
                    effects,
                    movingEffects,
                    sounds,
                    mapEvents,
                    zoneStates,
                    music,
                    chosen);
        }
    }

    public record Area(String label, float minX, float minY, float maxX, float maxY) {
        public static final float MIN_SIDE = 200;

        public Area {
            if (!(maxX - minX >= MIN_SIDE) || !(maxY - minY >= MIN_SIDE)) {
                throw new IllegalArgumentException("an area needs sides of at least " + MIN_SIDE + " units");
            }
        }

        public static Area around(String label, float x, float y, float side) {
            return new Area(label, x - side / 2, y - side / 2, x + side / 2, y + side / 2);
        }

        public float centerX() {
            return (minX + maxX) / 2;
        }

        public float centerY() {
            return (minY + maxY) / 2;
        }

        public boolean contains(double x, double y) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY;
        }

        public boolean meets(double lowX, double lowY, double highX, double highY) {
            return highX >= minX && lowX <= maxX && highY >= minY && lowY <= maxY;
        }

        public List<String> tiles() {
            var size = WorldDimensions.TILE_SIZE;
            var firstX = (int) Math.floor(minX / size) + WorldDimensions.TILE_ZERO_COORD_X;
            var lastX = (int) Math.floor(Math.nextDown(maxX) / size) + WorldDimensions.TILE_ZERO_COORD_X;
            var firstY = (int) Math.floor(minY / size) + WorldDimensions.TILE_ZERO_COORD_Y;
            var lastY = (int) Math.floor(Math.nextDown(maxY) / size) + WorldDimensions.TILE_ZERO_COORD_Y;
            var result = new ArrayList<String>();
            for (var y = firstY; y <= lastY; y++) {
                for (var x = firstX; x <= lastX; x++) {
                    result.add(x + "_" + y);
                }
            }
            return List.copyOf(result);
        }
    }

    public interface Population {
        List<Figure> collectFigures(UMap map, BooleanSupplier cancelled);

        default int withoutPlace(@Nullable Area area) {
            return 0;
        }
    }

    public record Figure(
            Npc npc,
            NpcModel model,
            double x,
            double y,
            double z,
            double yaw,
            @Nullable Outfit outfit) {
        public Figure(Npc npc, NpcModel model, double x, double y, double z, double yaw) {
            this(npc, model, x, y, z, yaw, null);
        }
    }

    public interface Progress {
        void update(String stage, double fraction);
    }

    public record Result(
            Path folder,
            List<Path> files,
            int objects,
            int meshes,
            long triangles,
            int textures,
            int figures,
            int rigs,
            int sounds,
            int withoutPlace,
            int musicZones,
            int musicTracks,
            int mapFigures,
            int mapRigs) {
        public Result(
                Path folder,
                List<Path> files,
                int objects,
                int meshes,
                long triangles,
                int textures,
                int figures,
                int rigs,
                int sounds,
                int withoutPlace,
                int musicZones,
                int musicTracks) {
            this(
                    folder,
                    files,
                    objects,
                    meshes,
                    triangles,
                    textures,
                    figures,
                    rigs,
                    sounds,
                    withoutPlace,
                    musicZones,
                    musicTracks,
                    0,
                    0);
        }

        public Result(
                Path folder,
                List<Path> files,
                int objects,
                int meshes,
                long triangles,
                int textures,
                int figures,
                int rigs,
                int sounds,
                int withoutPlace) {
            this(folder, files, objects, meshes, triangles, textures, figures, rigs, sounds, withoutPlace, 0, 0);
        }

        public Result(Path folder, List<Path> files, int objects, int meshes, long triangles, int textures) {
            this(folder, files, objects, meshes, triangles, textures, 0, 0);
        }

        public Result(
                Path folder,
                List<Path> files,
                int objects,
                int meshes,
                long triangles,
                int textures,
                int figures,
                int rigs) {
            this(folder, files, objects, meshes, triangles, textures, figures, rigs, 0, 0);
        }
    }

    private static Result write(
            LocationModel model,
            @Nullable LocationEnvironment environment,
            String name,
            String source,
            Path parent,
            Settings settings,
            boolean replace,
            List<Figure> rigs,
            @Nullable UnrealLoader loader,
            @Nullable MapFigures mapFigures,
            int withoutPlace,
            @Nullable NamedMapStart view,
            Progress progress,
            BooleanSupplier cancelled)
            throws IOException {
        var tile = name;
        Files.createDirectories(parent);
        var staging = Files.createTempDirectory(parent, "." + tile + "-export-");
        try {
            progress.update("Encoding " + model.images().size() + " textures", READING);
            var png = encode(model, staging.resolve(TEXTURES), progress, cancelled);
            writeLayerFiles(model, staging, cancelled);
            progress.update("Writing " + tile + ".glb", .75);
            GlbWriter.write(model, png, staging.resolve(tile + ".glb"), cancelled, environment, view);
            if (environment != null && environment.sky() != null) {
                var sky = environment.sky();
                Files.write(staging.resolve(SKY), PngWriter.encode(sky.width(), sky.height(), sky.rgba(), false));
            }
            var written = new ArrayList<String>();
            var skipped = new ArrayList<String>();
            if (settings.npcs()) {
                var npcFolder = Files.createDirectories(staging.resolve(NPCS));
                for (var index = 0; index < rigs.size(); index++) {
                    if (cancelled.getAsBoolean()) {
                        throw new CancellationException("export cancelled");
                    }
                    var npc = rigs.get(index).npc();
                    progress.update(
                            "Writing the rig of " + npc.label() + ", " + (index + 1) + " of " + rigs.size(),
                            .9 + .07 * index / Math.max(1, rigs.size()));
                    if (loader == null) {
                        skipped.add(npc.label());
                        continue;
                    }
                    try {
                        NpcExport.writeRig(
                                loader,
                                npc,
                                rigs.get(index).outfit(),
                                settings.scale(),
                                npcFolder.resolve(NpcExport.name(npc) + ".glb"));
                        written.add(NpcExport.name(npc) + ".glb");
                    } catch (IOException | RuntimeException unreadable) {
                        skipped.add(npc.label());
                    }
                }
            }
            var figureRigs = new ArrayList<String>();
            var figureSkipped = new ArrayList<String>();
            var kinds = mapFigures == null || loader == null ? List.<MapFigures.Kind>of() : mapFigures.getKinds();
            if (!kinds.isEmpty()) {
                var figureFolder = Files.createDirectories(staging.resolve(FIGURES));
                for (var index = 0; index < kinds.size(); index++) {
                    if (cancelled.getAsBoolean()) {
                        throw new CancellationException("export cancelled");
                    }
                    var kind = kinds.get(index);
                    progress.update(
                            "Writing the rig of " + kind.mesh() + ", " + (index + 1) + " of " + kinds.size(),
                            .9 + .06 * index / kinds.size());
                    try {
                        NpcExport.writeRig(
                                loader, kind.toNpc(), null, settings.scale(), figureFolder.resolve(kind.file()));
                        figureRigs.add(kind.file() + ": " + describeKind(kind));
                    } catch (IOException | RuntimeException unreadable) {
                        figureSkipped.add(describeKind(kind));
                    }
                }
            }
            var sounds = 0;
            if (settings.sounds()) {
                progress.update("Writing the sounds", .96);
                sounds = writeSounds(model, staging, settings.hour(), cancelled);
            }
            var musicTracks = 0;
            if (settings.music()) {
                progress.update("Copying the music", .965);
                musicTracks = writeMusic(model, staging, settings.hour(), cancelled);
            }
            if (settings.effects()) {
                Files.writeString(staging.resolve(EFFECTS_FILE), LocationJson.effects(model), StandardCharsets.UTF_8);
            }
            Files.writeString(
                    staging.resolve(LIGHTS_FILE), LocationJson.lights(model, environment), StandardCharsets.UTF_8);
            var summary = summarize(model);
            Files.writeString(
                    staging.resolve(INFO),
                    buildInfoText(model, tile, source, settings, summary, environment)
                            + npcInfo(settings, summary, written, skipped, withoutPlace)
                            + mapFigureInfo(
                                    summary.mapFigures(),
                                    figureRigs,
                                    figureSkipped,
                                    mapFigures == null ? List.of() : mapFigures.getUnreadable()),
                    StandardCharsets.UTF_8);
            writeBlenderScript(staging);
            writeUnityScripts(staging);
            if (cancelled.getAsBoolean()) {
                throw new CancellationException("export cancelled");
            }
            progress.update("Putting files in place", .97);
            checkTarget(parent, tile, settings, replace);
            var folder = tileFolder(parent, tile);
            Files.createDirectories(folder);
            var files = new ArrayList<Path>();
            for (var file : fileNames(tile, settings)) {
                if (!Files.exists(staging.resolve(file))) {
                    continue;
                }
                var target = folder.resolve(file);
                if (Files.isDirectory(target)) {
                    deleteTree(target);
                }
                Files.move(staging.resolve(file), target, StandardCopyOption.REPLACE_EXISTING);
                files.add(target);
            }
            progress.update("Done", 1);
            return new Result(
                    folder,
                    List.copyOf(files),
                    summary.objects(),
                    model.meshes().size(),
                    summary.triangles(),
                    model.images().size(),
                    summary.figures(),
                    written.size(),
                    sounds,
                    withoutPlace,
                    musicZones(model),
                    musicTracks,
                    summary.mapFigures(),
                    figureRigs.size());
        } finally {
            deleteTree(staging);
        }
    }

    private record Summary(int objects, long triangles, int figures, int mapFigures) {}

    private static String describeGrass(LocationModel model) {
        var kinds = 0;
        var tufts = 0L;
        for (var tile : model.tiles()) {
            for (var group : tile.groups()) {
                for (var node : group.nodes()) {
                    if (node.instances() != null) {
                        kinds++;
                        tufts += node.copies();
                    }
                }
            }
        }
        if (kinds == 0) {
            return "included";
        }
        return String.format(
                Locale.ROOT,
                "%,d tufts of %d %s. Each kind is one mesh placed at its tufts with%nEXT_mesh_gpu_instancing. A"
                        + " program that does not read it shows one tuft of each kind.",
                tufts,
                kinds,
                kinds == 1 ? "kind" : "kinds");
    }

    private static int writeSounds(LocationModel model, Path folder, int hour, BooleanSupplier cancelled)
            throws IOException {
        var count = 0;
        var files = new HashSet<String>();
        for (var tile : model.tiles()) {
            for (var sound : tile.sounds()) {
                count++;
                if (!files.add(sound.file())) {
                    continue;
                }
                if (cancelled.getAsBoolean()) {
                    throw new CancellationException("export cancelled");
                }
                var target = folder.resolve(sound.file());
                Files.createDirectories(target.getParent());
                Files.write(target, WavWriter.encode(sound.clip()));
            }
        }
        Files.writeString(folder.resolve(SOUNDS_FILE), LocationJson.sounds(model, hour), StandardCharsets.UTF_8);
        return count;
    }

    private static int musicZones(LocationModel model) {
        return model.tiles().stream().mapToInt(tile -> tile.music().size()).sum();
    }

    private static String npcInfo(
            Settings settings, Summary summary, List<String> written, List<String> skipped, int withoutPlace) {
        if (!settings.npcs()) {
            return "";
        }
        var text = new StringBuilder("\nNPCs: ")
                .append(summary.figures())
                .append(" figures in their idle pose, placed from npcpos as the NPC Spawns tool places\n")
                .append("them: random spawns in one possible layout. Each figure is named after its NPC and id, such"
                        + " as Roxxy 30006.\n")
                .append("Rigs: ")
                .append(written.size())
                .append(" files in the ")
                .append(NPCS)
                .append(" folder, one per NPC type, with its skeleton and every animation.\n")
                .append("A rig's origin goes where its figure's origin is: put it there and turn it the same way.\n");
        if (!skipped.isEmpty()) {
            text.append("Not rigged: ").append(String.join(", ", skipped)).append('\n');
        }
        return text.append(leftOutInfo(withoutPlace)).toString();
    }

    private static Summary summarize(LocationModel model) {
        var objects = 0;
        var triangles = 0L;
        var figures = 0;
        var mapFigures = 0;
        for (var tile : model.tiles()) {
            for (var group : tile.groups()) {
                if (group.name().equals(LocationModelBuilder.FIGURES)) {
                    figures += group.nodes().size();
                }
                if (group.name().equals(LocationModelBuilder.MAP_FIGURES)) {
                    mapFigures += group.nodes().size();
                }
                for (var node : group.nodes()) {
                    objects++;
                    for (var primitive : model.meshes().get(node.mesh()).primitives()) {
                        triangles += (long) primitive.indices().length / 3 * node.copies();
                    }
                }
            }
        }
        return new Summary(objects, triangles, figures, mapFigures);
    }

    private static void writeLayerFiles(LocationModel model, Path folder, BooleanSupplier cancelled)
            throws IOException {
        var pool = ForkJoinPool.commonPool();
        var futures = new ArrayList<Future<?>>(model.layerFiles().size());
        for (var file : model.layerFiles()) {
            var target = folder.resolve(file.path());
            Files.createDirectories(target.getParent());
            futures.add(pool.submit(() -> {
                if (cancelled.getAsBoolean()) {
                    throw new CancellationException("export cancelled");
                }
                var pixels = file.pixels().get();
                Files.write(
                        target,
                        file.gray()
                                ? PngWriter.encodeGray(file.width(), file.height(), pixels)
                                : PngWriter.encode(file.width(), file.height(), pixels, PngWriter.hasAlpha(pixels)));
                return null;
            }));
        }
        for (var future : futures) {
            try {
                future.get();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new CancellationException("export interrupted");
            } catch (ExecutionException failure) {
                if (failure.getCause() instanceof CancellationException cancellation) {
                    throw cancellation;
                }
                throw new IOException(
                        "A terrain layer could not be encoded: "
                                + failure.getCause().getMessage(),
                        failure.getCause());
            }
        }
    }

    private static List<Path> encode(LocationModel model, Path folder, Progress progress, BooleanSupplier cancelled)
            throws IOException {
        Files.createDirectories(folder);
        var images = model.images();
        var names = textureNames(model);
        var done = new AtomicInteger();
        var pool = ForkJoinPool.commonPool();
        var futures = new ArrayList<Future<Path>>(images.size());
        for (var index = 0; index < images.size(); index++) {
            var image = images.get(index);
            var file = folder.resolve(names[index]);
            futures.add(pool.submit(() -> {
                if (cancelled.getAsBoolean()) {
                    throw new CancellationException("export cancelled");
                }
                var rgba = image.rgba().get();
                Files.write(file, PngWriter.encode(image.width(), image.height(), rgba, PngWriter.hasAlpha(rgba)));
                var count = done.incrementAndGet();
                if (count % 16 == 0) {
                    progress.update(
                            "Encoding textures, " + count + " of " + images.size(),
                            .35 + .4 * count / Math.max(1, images.size()));
                }
                return file;
            }));
        }
        var result = new ArrayList<Path>(images.size());
        for (var future : futures) {
            try {
                result.add(future.get());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new CancellationException("export interrupted");
            } catch (ExecutionException failure) {
                if (failure.getCause() instanceof CancellationException cancellation) {
                    throw cancellation;
                }
                throw new IOException(
                        "A texture could not be encoded: " + failure.getCause().getMessage(), failure.getCause());
            }
        }
        return result;
    }

    private static String buildInfoText(
            LocationModel model,
            String name,
            String source,
            Settings settings,
            Summary summary,
            @Nullable LocationEnvironment environment) {
        var origin = model.origin();
        var text = new StringBuilder();
        text.append("Cruma location export\n\n");
        var tileNames = new ArrayList<String>();
        for (var tile : model.tiles()) {
            tileNames.add(tile.name());
        }
        var named = tileNames.size() == 1 && !WorldTile.isMapName(tileNames.getFirst());
        if (named) {
            text.append("Map: ")
                    .append(tileNames.getFirst())
                    .append(", a map with a name of its own that the game shows alone\n");
        } else {
            text.append(tileNames.size() == 1 ? "Tile: " : "Tiles: ")
                    .append(String.join(", ", tileNames))
                    .append('\n');
        }
        text.append("Client: ").append(source).append('\n');
        var sky = environment != null && environment.sky() != null;
        var layers = model.layerFiles().stream().anyMatch(file -> file.path().startsWith(TerrainLayers.FOLDER + "/"));
        var frames =
                model.layerFiles().stream().anyMatch(file -> file.path().startsWith(LocationMaterials.FRAMES + "/"));
        var effects = model.tiles().stream()
                .flatMap(tile -> tile.groups().stream())
                .filter(group -> group.name().equals(LocationModelBuilder.EFFECTS))
                .flatMap(group -> group.nodes().stream())
                .filter(node ->
                        node.extras().getOrDefault("cruma_effect_still", "0").equals("0"))
                .count();
        var cards = model.tiles().stream()
                .flatMap(tile -> tile.groups().stream())
                .flatMap(group -> group.nodes().stream())
                .filter(node -> node.extras().containsKey("cruma_billboard"))
                .count();
        var lightmaps =
                model.layerFiles().stream().anyMatch(file -> file.path().equals(LocationModelBuilder.LIGHTMAP_FILE));
        var projectors = model.tiles().stream()
                .mapToInt(tile -> tile.projectors().size())
                .sum();
        var sounds =
                model.tiles().stream().mapToInt(tile -> tile.sounds().size()).sum();
        var clips = model.tiles().stream()
                .flatMap(tile -> tile.sounds().stream())
                .map(Sound::file)
                .distinct()
                .count();
        var effectActors =
                model.tiles().stream().mapToInt(tile -> tile.effects().size()).sum();
        var musicZones = musicZones(model);
        var musicTracks = model.tiles().stream()
                .flatMap(tile -> tile.music().stream())
                .flatMap(zone -> Stream.concat(zone.tracks().stream(), zone.nightTracks().stream()))
                .map(MusicTrack::file)
                .distinct()
                .count();
        var decodedTracks = model.tiles().stream()
                .flatMap(tile -> tile.music().stream())
                .flatMap(zone -> Stream.concat(zone.tracks().stream(), zone.nightTracks().stream()))
                .filter(MusicTrack::decoded)
                .map(MusicTrack::file)
                .distinct()
                .count();
        var missingTracks = model.tiles().stream()
                .flatMap(tile -> tile.music().stream())
                .flatMap(zone -> zone.missing().stream())
                .distinct()
                .sorted()
                .toList();
        text.append("Files: ")
                .append(String.join(
                        ", ",
                        fileNames(name, settings).stream()
                                .filter(file -> sky || !file.equals(SKY))
                                .filter(file -> layers || !file.equals(TerrainLayers.FOLDER))
                                .filter(file -> projectors > 0 || !file.equals(LocationModelBuilder.PROJECTORS))
                                .filter(file -> lightmaps || !file.equals(LocationModelBuilder.LIGHTMAPS))
                                .filter(file -> frames || !file.equals(LocationMaterials.FRAMES))
                                .filter(file -> effects > 0 || !file.equals(LocationMaterials.EFFECTS))
                                .filter(file -> sounds > 0 || !file.equals(SOUNDS))
                                .filter(file -> settings.sounds() || !file.equals(SOUNDS_FILE))
                                .filter(file -> musicTracks > 0 || !file.equals(MUSIC))
                                .filter(file -> settings.music() || !file.equals(MUSIC_FILE))
                                .filter(file -> settings.effects() || !file.equals(EFFECTS_FILE))
                                .toList()))
                .append("\n\n");
        var scale = settings.scale();
        text.append("Scale: ")
                .append(scale.describe())
                .append(".\n"
                        + "Every length in the files is in metres at this scale. An NPC export made with the same"
                        + " scale fits this one.\n");
        var area = settings.area();
        if (named) {
            text.append(String.format(
                    Locale.ROOT,
                    "Origin: client point (%.0f, %.0f, %.0f), where the viewer's camera starts in the map, is (0, 0,"
                            + " 0).%n",
                    origin[0],
                    origin[1],
                    origin[2]));
            text.append("Heights keep the client's zero. The file's extras, cruma_view, give that place and the way the"
                    + " camera looks,\n");
            text.append("and " + BLENDER_SCRIPT + " puts its camera there.\n");
        } else if (area == null) {
            text.append(String.format(
                    Locale.ROOT,
                    "Origin: client point (%.0f, %.0f, %.0f), the centre of the tiles, is (0, 0, 0). Heights keep the"
                            + " client's zero,%n",
                    origin[0],
                    origin[1],
                    origin[2]));
            text.append("so tiles exported one by one meet when you move each one by its tile offset (")
                    .append(scale.tileText())
                    .append(" per tile).\n");
        } else {
            text.append(String.format(
                    Locale.ROOT,
                    "Area: %.0f by %.0f m%s, client X %.0f to %.0f, Y %.0f to %.0f. Level geometry, terrain, water and"
                            + " grass%n",
                    (area.maxX() - area.minX()) * model.metersPerUnit(),
                    (area.maxY() - area.minY()) * model.metersPerUnit(),
                    area.label().isBlank() ? "" : " around " + area.label(),
                    area.minX(),
                    area.maxX(),
                    area.minY(),
                    area.maxY()));
            text.append("end at its edges. Buildings, trees and other meshes that reach into it come whole.\n");
            text.append(String.format(
                    Locale.ROOT,
                    "Origin: client point (%.0f, %.0f, %.0f), the centre of the area, is (0, 0, 0). Heights keep the"
                            + " client's zero.%n",
                    origin[0],
                    origin[1],
                    origin[2]));
        }
        text.append("Axes: Y is up in the files, as glTF defines. Blender and 3ds Max turn it to Z up on import.\n");
        text.append("Blender: " + BLENDER_SCRIPT
                + " opens the export as a scene, with each NPC's rig where its figure stands, the\n");
        text.append("client's sun, sky and fog, and a camera. Run blender --python " + BLENDER_SCRIPT
                + ", or open it in Blender's Scripting tab\n");
        text.append("and press Run Script.\n");
        text.append("Unity: put this folder inside Assets. " + UNITY_SCRIPT + " adds the menu " + UNITY_MENU + ",\n");
        text.append("which places the sounds, the music zones and the Emitter actors of " + SOUNDS_FILE + ", "
                + MUSIC_FILE + " and\n");
        text.append(EFFECTS_FILE
                + " in the open scene. It also gives the sun and the lamps of the imported .glb the strength of\n");
        text.append(LIGHTS_FILE
                + ": a glTF importer of Unity takes glTF's lux for Unity's own intensity, which makes the"
                + " scene white.\n");
        text.append('\n');
        text.append("Objects: ")
                .append(summary.objects())
                .append(", meshes: ")
                .append(model.meshes().size())
                .append(", triangles: ")
                .append(summary.triangles())
                .append(", textures: ")
                .append(model.images().size())
                .append('\n');
        text.append("Terrain texture: ")
                .append(settings.terrainSize())
                .append(" x ")
                .append(settings.terrainSize())
                .append(" px, every layer baked in.\n");
        if (layers) {
            text.append("Terrain layers: each layer's texture and alpha map are in ")
                    .append(TerrainLayers.FOLDER)
                    .append(", and the terrain material's extras,\n"
                            + "cruma_terrain_layers, say how to place and mix them. ")
                    .append(BLENDER_SCRIPT)
                    .append(" builds the layers in Blender.\n");
        }
        text.append("Grass and flowers: ")
                .append(settings.decorations() ? describeGrass(model) : "left out")
                .append("\n");
        if (environment != null) {
            text.append(String.format(
                    Locale.ROOT,
                    "Time of day: %d:00. The client's sun is a light named Sun in the glTF file. Its ambient light%n",
                    environment.hour()));
            text.append("and fog are in the file's extras, cruma_environment")
                    .append(
                            sky
                                    ? ", and its sky, with clouds, sun and moon, is " + SKY
                                            + ",\na panorama for a 3D program's world"
                                    : "")
                    .append(".\n");
            var lobbyLight = environment.timeEnv().toLowerCase(Locale.ROOT).startsWith("lobbytimeenv");
            if (named && LobbyTimeEnv.isLobby(tileNames.getFirst()) && lobbyLight) {
                text.append("Light: ")
                        .append(environment.timeEnv())
                        .append(", the light the game gives its lobby, not the world's TimeEnv.\n");
            }
            if (environment.skyZone() != null) {
                text.append(String.format(
                        Locale.ROOT,
                        "Sky zone: %s gives the middle its own time of day, %s, and the light %s. The sun, sky and fog"
                                + " take them.%n",
                        environment.skyZone(),
                        clockOf(environment.lightHour()),
                        environment.timeEnv()));
            }
        } else {
            text.append("Time of day: the client's sun, sky and fog could not be read, so the 3D program lights the"
                    + " scene.\n");
        }
        var lamps = model.tiles().stream().mapToInt(tile -> tile.lamps().size()).sum();
        if (lamps > 0) {
            text.append("Lamps: ")
                    .append(lamps)
                    .append(" lights of the client in each tile's group Lamps. The extras of each, cruma_lamp, keep"
                            + " the client's colour\n");
            text.append("and radius, and ")
                    .append(BLENDER_SCRIPT)
                    .append(" gives them the client's falloff, without shadows. They start switched off.\n");
        }
        var clientLight = model.meshes().stream()
                .anyMatch(mesh -> mesh.primitives().stream().anyMatch(primitive -> primitive.light() != null));
        if (clientLight) {
            text.append(
                    "Client light: each terrain vertex and each tuft of grass has the client's own light at the hour,"
                            + " _CLIENT_LIGHT:\n");
            text.append("the light of the lamps with the shadows of leaves and walls, and the share of the sun's"
                            + " light. ")
                    .append(BLENDER_SCRIPT)
                    .append("\nlights the terrain and the grass with it, as the viewer does.\n");
        }
        if (lightmaps) {
            text.append("Lightmaps: the light the client bakes into its level geometry, at the hour, in ")
                    .append(LocationModelBuilder.LIGHTMAP_FILE)
                    .append(", which level geometry\nreads by its second texture coordinates. ")
                    .append(BLENDER_SCRIPT)
                    .append(" lights level geometry with twice the lightmap, as the client.\n");
        }
        if (projectors > 0) {
            text.append("Projectors: ")
                    .append(projectors)
                    .append(" textures the client lays over the terrain and meshes, such as the light through a tree's"
                            + " leaves, in ")
                    .append(LocationModelBuilder.PROJECTORS)
                    .append(",\nand the file's extras, cruma_projectors, say where. ")
                    .append(BLENDER_SCRIPT)
                    .append(" lays them over the client light of the terrain and the meshes they reach.\n");
        }
        if (effects > 0) {
            text.append("Effects: ")
                    .append(effects)
                    .append(
                            settings.movingEffects()
                                    ? String.format(
                                            Locale.ROOT,
                                            " particle effects as a loop of %d stills over %.0f seconds, in each"
                                                + " tile's group Effects. Each node's\n"
                                                + "extras, cruma_effect_still, say which still it is. The vertices of"
                                                + " sprites and beams keep how they face\n",
                                            EFFECT_STILLS,
                                            EFFECT_STILLS * EFFECT_INTERVAL)
                                    : " particle effects as a still, in each tile's group Effects. The vertices of"
                                            + " sprites and beams keep how they face\n")
                    .append("the camera, _FX_CENTER, _FX_SHAPE and _FX_AXIS; mesh particles stay in place. ")
                    .append(BLENDER_SCRIPT)
                    .append(" turns them to its camera and blends\nthem as the client does, with the textures in ")
                    .append(LocationMaterials.EFFECTS)
                    .append(".\n");
        }
        if (settings.effects() && !settings.mapEvents().isBlank()) {
            text.append("Map events: ")
                    .append(settings.mapEvents())
                    .append(". The effects these events hide are left out, as in the viewer.\n");
        }
        if (!settings.zoneStates().isEmpty()) {
            var parts = new ArrayList<String>();
            settings.zoneStates()
                    .getStates()
                    .forEach((map, state) -> parts.add(ZoneStateChoice.describe(state) + " in " + map));
            text.append("Zone state: ")
                    .append(String.join(", ", parts))
                    .append(", for every zone of that map that has zone states. The other maps keep the state they"
                            + " start in. What the client shows only in the other states is left out, as in"
                            + " the viewer: meshes and their skins, effects, fogs, the sky, Terrain layers,"
                            + " decorations and sounds.\n");
        }
        if (!model.zoneDefaults().isEmpty()) {
            var parts = new ArrayList<String>();
            new TreeMap<>(model.zoneDefaults()).forEach((map, state) -> parts.add(state + " in " + map));
            text.append("Zone state by default: ")
                    .append(String.join(", ", parts))
                    .append(". The map starts its zones in state 0, where the game shows nothing of a state until"
                            + " its server sends one, so the export takes the first state, as the viewer does."
                            + " Choose As the map in the Viewer's Map events for state 0.\n");
        }
        if (effects > 0 && settings.effects()) {
            text.append(EFFECTS_FILE)
                    .append(": the ")
                    .append(effectActors)
                    .append(" Emitter actors of the effects, each with its place, turn and scale and its emitters, for"
                            + " a game engine.\n");
        }
        if (settings.sounds()) {
            text.append(String.format(
                    Locale.ROOT,
                    "Sounds: %d sounds of %d clips, each clip once as a WAVE file in %s. %s lists them with their"
                            + " place,%n",
                    sounds,
                    clips,
                    SOUNDS,
                    SOUNDS_FILE));
            text.append(
                    "radius, volume, pitch and when they play, and each tile's group Sounds has an empty node at each,"
                            + " with the same\n");
            text.append("in its extras, cruma_sound. " + BLENDER_SCRIPT
                    + " gives each a speaker in the collection Sounds, which starts\n");
            text.append("switched off: tick it and play the timeline to hear them from the camera. In Unity, "
                    + UNITY_SOUNDS + "\n");
            text.append("plays the ambient sounds by their hours and their chance, as the client does.\n");
            if (!settings.clocks().isEmpty()) {
                text.append("Sounds by time of day: ")
                        .append(MapEventState.ALL_SHOWN
                                .withClocks(settings.clocks())
                                .clockWords())
                        .append(". A sound by the hour of hours that are off is left out, and one of hours that are"
                                + " always plays at every hour, as in the viewer.\n");
            }
        }
        if (settings.music()) {
            if (musicZones == 0) {
                text.append(
                        "Music: none. These maps have no music zone, or the client's music table or music folder does"
                                + " not read.\n");
            } else {
                text.append(String.format(
                        Locale.ROOT,
                        "Music: %d music %s of %d %s, each track once in %s. %d of them %s the client's own Ogg Vorbis"
                                + " file, and %d%n",
                        musicZones,
                        musicZones == 1 ? "zone" : "zones",
                        musicTracks,
                        musicTracks == 1 ? "track" : "tracks",
                        MUSIC,
                        musicTracks - decodedTracks,
                        musicTracks - decodedTracks == 1 ? "is" : "are",
                        decodedTracks));
                text.append("of an early kind of Vorbis that Unity does not read are decoded into WAVE files. "
                        + MUSIC_FILE + " lists the zones with\n");
                text.append("their tracks by day and by night and their shape. In Unity, " + UNITY_MUSIC
                        + " plays the music of the zone the listener\n");
                text.append("is in. " + BLENDER_SCRIPT + " does not play the music.\n");
            }
            if (!missingTracks.isEmpty()) {
                text.append("Music left out: the client has no file that reads for ")
                        .append(String.join(", ", missingTracks))
                        .append(".\n");
            }
        }
        if (cards > 0) {
            text.append("Cards: ")
                    .append(cards)
                    .append(" torch flames and other cards the client turns to the camera, flat, marked by the node's"
                            + " extras, cruma_billboard.\n")
                    .append(BLENDER_SCRIPT)
                    .append(" turns them to its camera.\n");
        }
        if (frames) {
            text.append("Animated textures: the frames of each are in ")
                    .append(LocationMaterials.FRAMES)
                    .append(", and the materials' extras, cruma_frames and cruma_uv_animation, say how\nthey move. ")
                    .append(BLENDER_SCRIPT)
                    .append(" plays them with the timeline.\n");
        }
        text.append('\n');
        text.append("Not exported: ")
                .append(settings.npcs() ? "" : "NPCs, ")
                .append(
                        settings.effects() && settings.movingEffects()
                                ? "far levels of detail"
                                : "far levels of detail, the motion of the particle effects, which come as a still")
                .append(
                        settings.sounds()
                                ? ".\n"
                                : settings.effects() && settings.movingEffects()
                                        ? " and sounds.\n"
                                        : ", and sounds.\n");
        text.append("Materials keep their client names, and the glTF materials list what could not be kept in their"
                + " extras.\n");
        return text.toString();
    }

    private static @Nullable Texture waterNormal(UnrealLoader loader, Path client) {
        try {
            return loader.loadHighWaterNormalTexture(client).orElse(null);
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    private static @Nullable LocationEnvironment readLight(Reader environment, BooleanSupplier cancelled) {
        try {
            return environment.read(cancelled);
        } catch (CancellationException stopped) {
            throw stopped;
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    private static String describeKind(MapFigures.Kind kind) {
        var skins = kind.skins().stream().filter(skin -> !skin.isBlank()).toList();
        return kind.mesh() + (skins.isEmpty() ? "" : " with " + String.join(", ", skins));
    }

    private static String clientName(Path client) {
        return client.getFileName() == null ? "client" : client.getFileName().toString();
    }

    private static void checkMap(String map, Settings settings) {
        if (WorldTile.isMapName(map)) {
            throw new IllegalArgumentException(map + " is a world tile, not a map with a name of its own");
        }
        if (settings.npcs() || settings.area() != null) {
            throw new IllegalArgumentException("a map with a name of its own is exported whole and without NPCs");
        }
    }

    private static void checkCount(int tiles) {
        if (tiles < 1) {
            throw new IllegalArgumentException("choose at least one tile");
        }
        if (tiles > MAX_TILES) {
            throw new IllegalArgumentException("An export takes at most " + MAX_TILES + " tiles. Choose fewer tiles.");
        }
    }

    private static void checkTarget(Path parent, String tile, Settings settings, boolean replace) throws IOException {
        if (replace) {
            return;
        }
        var existing = findExistingFiles(parent, tile, settings);
        if (!existing.isEmpty()) {
            throw new IOException("The folder already has "
                    + existing.getFirst().getFileName() + " from an earlier export. Choose Replace to overwrite it.");
        }
    }

    private static String clockOf(float hour) {
        var minutes = Math.round(hour * 60f);
        return String.format(Locale.ROOT, "%d:%02d", Math.floorMod(minutes / 60, 24), Math.floorMod(minutes, 60));
    }
}
