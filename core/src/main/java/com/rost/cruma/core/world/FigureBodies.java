package com.rost.cruma.core.world;

import com.rost.cruma.core.schema.TableData;
import com.rost.cruma.core.schema.TableFiles;
import com.rost.cruma.core.schema.TableSchemas;
import com.rost.cruma.core.schema.Tables;
import com.rost.cruma.core.world.PlayerFigures.WholeBody;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

public final class FigureBodies {
    private final Map<String, Figure> figures;
    private final boolean read;

    private FigureBodies(Map<String, Figure> figures, boolean read) {
        this.figures = Map.copyOf(figures);
        this.read = read;
    }

    public record Figure(String pawnClass, @Nullable WholeBody wholeBody, ClassHeads.Head head) {}

    public static FigureBodies empty() {
        return new FigureBodies(Map.of(), false);
    }

    public static FigureBodies read(Path client, WorldVariant variant) {
        var system = client.resolve("system");
        if (TableFiles.find(system, variant, "CharHeadExgrp.dat") == null) {
            return empty();
        }
        try {
            return from(TableSchemas.read(Tables.CHARACTERS, system, variant));
        } catch (RuntimeException unreadable) {
            return empty();
        }
    }

    static FigureBodies parse(byte[] bytes, List<String> names) {
        return from(TableSchemas.read(Tables.CHARACTERS, Path.of("Chargrp.dat"), bytes, names, null));
    }

    public static FigureBodies fromRows(List<Map<String, Object>> rows) {
        var figures = new HashMap<String, Figure>();
        for (var row : rows) {
            var name = text(row.get("class"));
            if (name.isEmpty()) {
                continue;
            }
            figures.put(name.toLowerCase(Locale.ROOT), new Figure(name, findWholeBody(row), readHead(row)));
        }
        return new FigureBodies(figures, true);
    }

    public @Nullable Figure find(String pawnClass) {
        return figures.get(pawnClass.toLowerCase(Locale.ROOT));
    }

    public boolean hasRow(String figure) {
        return !read || find(PlayerFigures.getPawnClassName(figure)) != null;
    }

    public @Nullable WholeBody findWholeBody(String figure) {
        var found = find(PlayerFigures.getPawnClassName(figure));
        return found == null ? null : found.wholeBody();
    }

    public Map<String, String> getSharedRaces() {
        var shared = new HashMap<String, String>();
        for (var entry : figures.entrySet()) {
            var face = PlayerFigures.parseFace(entry.getValue().head().face());
            var race = face == null ? null : figures.get(face.race().toLowerCase(Locale.ROOT));
            if (race != null
                    && !race.pawnClass().equalsIgnoreCase(entry.getValue().pawnClass())) {
                shared.put(entry.getKey(), race.pawnClass());
            }
        }
        return Map.copyOf(shared);
    }

    private static FigureBodies from(TableData table) {
        return table.schema() == null ? empty() : fromRows(table.rows());
    }

    private static @Nullable WholeBody findWholeBody(Map<String, Object> row) {
        var chest = meshes(row.get("chest"), "meshes");
        if (chest.isEmpty()) {
            return null;
        }
        for (var part : List.of("gloves", "legs", "feet")) {
            if (!meshes(row.get(part), "meshes").isEmpty()) {
                return null;
            }
        }
        return new WholeBody(chest.getFirst(), meshes(row.get("chest"), "textures"));
    }

    private static ClassHeads.Head readHead(Map<String, Object> row) {
        var hair = new ArrayList<String>();
        var hairTextures = new ArrayList<String>();
        if (row.get("rows") instanceof List<?> hairRows
                && !hairRows.isEmpty()
                && hairRows.getFirst() instanceof Map<?, ?> first
                && first.get("styles") instanceof List<?> styles) {
            for (var style : styles) {
                var back = style instanceof Map<?, ?> place && place.get("back") instanceof Map<?, ?> found
                        ? found
                        : Map.of();
                hair.add(text(back.get("mesh")));
                hairTextures.add(text(back.get("texture")));
            }
        }
        var face = row.get("faces") instanceof List<?> faces && !faces.isEmpty() ? text(faces.getFirst()) : "";
        return new ClassHeads.Head(hair, hairTextures, face, "");
    }

    private static List<String> meshes(@Nullable Object part, String field) {
        var names = new ArrayList<String>();
        if (part instanceof Map<?, ?> body && body.get(field) instanceof List<?> list) {
            for (var value : list) {
                var name = text(value);
                if (!name.isEmpty()) {
                    names.add(name);
                }
            }
        }
        return List.copyOf(names);
    }

    private static String text(@Nullable Object value) {
        return value instanceof String name && !name.isBlank() && !name.equalsIgnoreCase("None") ? name.strip() : "";
    }
}
