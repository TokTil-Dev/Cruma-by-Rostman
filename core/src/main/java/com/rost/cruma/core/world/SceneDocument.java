package com.rost.cruma.core.world;

import com.rost.cruma.core.world.GameScenes.Item;
import com.rost.cruma.core.world.GameScenes.Scene;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.Nullable;

public record SceneDocument(
        String name,
        String basedOn,
        String basedOnSha256,
        String client,
        String variant,
        String map,
        long seed,
        List<Item> items,
        List<CameraPath> paths,
        String replaces,
        double hour) {
    private static final String COPY = "_copy";

    public SceneDocument {
        if (items.isEmpty()) {
            throw new IllegalArgumentException("A scene needs an item");
        }
        items = List.copyOf(items);
        paths = List.copyOf(paths);
    }

    public SceneDocument(
            String name,
            String basedOn,
            String basedOnSha256,
            String client,
            String variant,
            String map,
            long seed,
            List<Item> items,
            List<CameraPath> paths) {
        this(name, basedOn, basedOnSha256, client, variant, map, seed, items, paths, "", Double.NaN);
    }

    public SceneDocument(
            String name,
            String basedOn,
            String client,
            String variant,
            String map,
            long seed,
            List<Item> items,
            List<CameraPath> paths) {
        this(name, basedOn, "", client, variant, map, seed, items, paths);
    }

    public static SceneDocument blank(String name, double seconds, String client, String variant, long seed) {
        return new SceneDocument(
                name, "", client, variant, "", seed, List.of(blankItem(0), blankItem(seconds)), List.of());
    }

    public static SceneDocument of(Scene scene) {
        var seed = scene.seed();
        return new SceneDocument(
                        scene.name(),
                        scene.source().equals(scene.name()) ? "" : scene.source(),
                        "",
                        "",
                        scene.map(),
                        seed == null ? 0 : seed,
                        scene.items(),
                        scene.paths())
                .withHour(scene.hour());
    }

    public static SceneDocument copy(Scene scene, String client, String variant, long seed, String sha256) {
        var times = scene.times();
        var items = new ArrayList<Item>(times.length);
        for (var index = 0; index < times.length; index++) {
            items.add(scene.items().get(index).at(times[index]));
        }
        return new SceneDocument(
                scene.name() + COPY, scene.source(), sha256, client, variant, "", seed, items, scene.paths());
    }

    public static Item blankItem(double seconds) {
        return new Item(seconds, "", null, List.of(), null, null);
    }

    public Scene scene() {
        var source = basedOn.isEmpty() ? name : basedOn;
        var last = new Scene(name, items).seconds();
        var end = end();
        if (end <= last) {
            return new Scene(name, items, paths, source, seed, map, hour);
        }
        var longer = new ArrayList<>(items);
        longer.add(blankItem(end));
        return new Scene(name, longer, paths, source, seed, map, hour);
    }

    public double seconds() {
        return Math.max(new Scene(name, items).seconds(), end());
    }

    public @Nullable CameraPath path() {
        return paths.isEmpty() ? null : paths.getFirst();
    }

    public SceneDocument withPath(@Nullable CameraPath path) {
        var next = new ArrayList<>(paths);
        if (!next.isEmpty()) {
            next.removeFirst();
        }
        if (path != null) {
            next.addFirst(path);
        }
        return new SceneDocument(name, basedOn, basedOnSha256, client, variant, map, seed, items, next, replaces, hour);
    }

    public SceneDocument withName(String value) {
        return new SceneDocument(
                value, basedOn, basedOnSha256, client, variant, map, seed, items, paths, replaces, hour);
    }

    public SceneDocument withItems(List<Item> value) {
        return new SceneDocument(
                name, basedOn, basedOnSha256, client, variant, map, seed, value, paths, replaces, hour);
    }

    public SceneDocument withMap(String value) {
        return new SceneDocument(
                name, basedOn, basedOnSha256, client, variant, value, seed, items, paths, replaces, hour);
    }

    public SceneDocument withReplaces(String value) {
        return new SceneDocument(name, basedOn, basedOnSha256, client, variant, map, seed, items, paths, value, hour);
    }

    public SceneDocument withSeed(long value) {
        return new SceneDocument(
                name, basedOn, basedOnSha256, client, variant, map, value, items, paths, replaces, hour);
    }

    public SceneDocument withHour(double value) {
        return new SceneDocument(
                name, basedOn, basedOnSha256, client, variant, map, seed, items, paths, replaces, value);
    }

    public String target() {
        return replaces.isEmpty() ? basedOn : replaces;
    }

    public boolean hasContent() {
        for (var item : items) {
            if (!item.quiet()) {
                return true;
            }
        }
        return false;
    }

    public SceneDocument withSeconds(double value) {
        var next = new ArrayList<>(items);
        var last = next.removeLast();
        var least = next.isEmpty() ? 0 : new Scene(name, next).seconds();
        var end = Math.max(value, Math.max(least, end()));
        next.add(last.at(end));
        return withItems(next);
    }

    private double end() {
        var end = 0.0;
        for (var path : paths) {
            end = Math.max(end, path.getEnd());
        }
        return end;
    }
}
