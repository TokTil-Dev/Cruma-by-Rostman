package com.rost.cruma.core.schema;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.Nullable;

public final class GameDataNames {
    private GameDataNames() {}

    public static List<String> read(byte[] bytes) {
        return read(Path.of("L2GameDataName.dat"), bytes, null);
    }

    public static List<String> read(Path file, byte[] bytes, @Nullable Path clientSchemas) {
        var table = TableSchemas.read(Tables.GAME_DATA_NAMES, file, bytes, List.of(), clientSchemas);
        var names = new ArrayList<String>(table.rows().size());
        for (var row : table.rows()) {
            names.add(row.get("text") instanceof String text ? text : "");
        }
        return names;
    }
}
