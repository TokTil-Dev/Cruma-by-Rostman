package com.rost.cruma.core.world;

import com.rost.cruma.core.schema.TableData;
import com.rost.cruma.core.schema.TableSchemas;
import com.rost.cruma.core.schema.Tables;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

public final class ClassHeads {
    private final Map<String, Head> heads;

    private ClassHeads(Map<String, Head> heads) {
        this.heads = Map.copyOf(heads);
    }

    public record Head(List<String> hair, List<String> hairTextures, String face, String faceTexture) {
        public Head {
            hair = List.copyOf(hair);
            hairTextures = List.copyOf(hairTextures);
        }

        public @Nullable String findHair(int style) {
            for (var number : style > 0 ? List.of(style, 0) : List.of(0)) {
                if (number < hair.size() && !hair.get(number).isEmpty()) {
                    return hair.get(number);
                }
            }
            return null;
        }

        public String findHairTexture(int style) {
            for (var number : style > 0 ? List.of(style, 0) : List.of(0)) {
                if (number < hair.size() && !hair.get(number).isEmpty()) {
                    return hairTextures.get(number);
                }
            }
            return "";
        }
    }

    public static ClassHeads empty() {
        return new ClassHeads(Map.of());
    }

    public static ClassHeads read(Path client, WorldVariant variant) {
        try {
            return from(TableSchemas.read(Tables.CLASS_HEADS, client.resolve("system"), variant));
        } catch (RuntimeException unreadable) {
            return empty();
        }
    }

    static ClassHeads parse(byte[] bytes, List<String> names) {
        return from(TableSchemas.read(Tables.CLASS_HEADS, Path.of("CharHeadExgrp.dat"), bytes, names, null));
    }

    static ClassHeads fromRows(List<Map<String, Object>> rows) {
        var heads = new HashMap<String, Head>();
        for (var row : rows) {
            var name = text(row.get("class"));
            if (name.isEmpty() || !(row.get("rows") instanceof List<?> hairRows) || hairRows.isEmpty()) {
                continue;
            }
            var hair = new ArrayList<String>();
            var hairTextures = new ArrayList<String>();
            if (hairRows.getFirst() instanceof Map<?, ?> first && first.get("styles") instanceof List<?> styles) {
                for (var style : styles) {
                    var back = style instanceof Map<?, ?> place && place.get("back") instanceof Map<?, ?> found
                            ? found
                            : Map.of();
                    hair.add(text(back.get("mesh")));
                    hairTextures.add(text(back.get("texture")));
                }
            }
            heads.put(
                    name.toLowerCase(Locale.ROOT),
                    new Head(hair, hairTextures, first(row.get("faces")), first(row.get("faceTextures"))));
        }
        return new ClassHeads(heads);
    }

    public @Nullable Head find(String pawnClass) {
        return heads.get(pawnClass.toLowerCase(Locale.ROOT));
    }

    private static ClassHeads from(TableData table) {
        return table.schema() == null ? empty() : fromRows(table.rows());
    }

    private static String first(@Nullable Object values) {
        return values instanceof List<?> list && !list.isEmpty() ? text(list.getFirst()) : "";
    }

    private static String text(@Nullable Object value) {
        return value instanceof String name && !name.isBlank() && !name.equalsIgnoreCase("None") ? name.strip() : "";
    }
}
