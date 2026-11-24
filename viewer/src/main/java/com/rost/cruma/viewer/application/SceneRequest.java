package com.rost.cruma.viewer.application;

import com.rost.cruma.core.world.GameScenes;
import com.rost.cruma.core.world.GameScenes.Scene;
import com.rost.cruma.core.world.ServerScenes;
import com.rost.cruma.core.world.WorldMapCatalog;
import com.rost.cruma.core.world.WorldTile;
import com.rost.cruma.core.world.WorldVariant;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.Nullable;

public record SceneRequest(
        String scene,
        List<String> tiles,
        boolean copy,
        @Nullable Path file) {
    private static final String ARENA_SCENE = "arena_";

    public SceneRequest {
        tiles = List.copyOf(tiles);
    }

    public SceneRequest(String scene, List<String> tiles, boolean copy) {
        this(scene, tiles, copy, null);
    }

    public SceneRequest(String scene, List<String> tiles) {
        this(scene, tiles, false);
    }

    public static SceneRequest of(Scene scene, Path client, WorldVariant variant) {
        return of(scene, scene.tiles(), client, variant);
    }

    public static SceneRequest close(Scene scene, Path client, WorldVariant variant) {
        return of(scene, scene.closeTiles(), client, variant);
    }

    public static @Nullable SceneRequest find(String name, Path client, WorldVariant variant, @Nullable Path scripts) {
        var scenes = new ArrayList<>(GameScenes.find(client));
        scenes.addAll(ServerScenes.find(scripts, client, variant));
        for (var scene : scenes) {
            if (scene.name().equalsIgnoreCase(name)) {
                return of(scene, client, variant);
            }
        }
        return null;
    }

    public SceneRequest asCopy() {
        return new SceneRequest(scene, tiles, true);
    }

    public SceneRequest fromFile(Path saved) {
        return new SceneRequest(scene, tiles, false, saved);
    }

    public @Nullable String lack(Scene film) {
        if (!tiles.isEmpty()) {
            return null;
        }
        if (!film.map().isEmpty()) {
            return "This client has no map " + film.map() + ".";
        }
        return film.tiles().isEmpty() ? null : "This client has none of the tiles of " + film.name() + ".";
    }

    private static SceneRequest of(Scene scene, List<String> tiles, Path client, WorldVariant variant) {
        if (!scene.map().isEmpty()) {
            return new SceneRequest(scene.name(), namedMap(scene.map(), client));
        }
        var maps = new ArrayList<String>();
        for (var name : tiles) {
            var arena = scene.source().startsWith(ARENA_SCENE) ? WorldMapCatalog.arenaMap(client, name) : null;
            if (arena != null) {
                maps.add(arena);
                continue;
            }
            var tile = WorldTile.fromMapName(name);
            maps.addAll(WorldMapCatalog.discover(client, variant, tile.x(), tile.x(), tile.y(), tile.y()));
        }
        return new SceneRequest(scene.name(), maps);
    }

    private static List<String> namedMap(String map, Path client) {
        for (var name : WorldMapCatalog.namedMaps(client)) {
            if (name.equalsIgnoreCase(map)) {
                return List.of(name);
            }
        }
        return List.of();
    }
}
