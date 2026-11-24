package com.rost.cruma.core.world;

import com.rost.cruma.core.schema.TableSchemas;
import com.rost.cruma.core.schema.Tables;
import com.rost.cruma.core.unreal.L2DataFile;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

public final class SystemMessages {
    private static final SystemMessages NONE = new SystemMessages(Map.of());
    private static final String ENGLISH = "systemmsg-e.dat";

    private final Map<Integer, String> lines;

    private SystemMessages(Map<Integer, String> lines) {
        this.lines = lines;
    }

    public static SystemMessages read(Path clientRoot, WorldVariant variant) {
        var system = clientRoot.resolve("system");
        var file = TableSchemas.findTable(Tables.SYSTEM_MESSAGES, system, variant);
        if (file == null) {
            return NONE;
        }
        try {
            return fromRows(TableSchemas.read(
                            Tables.SYSTEM_MESSAGES,
                            file,
                            L2DataFile.read(file),
                            TableSchemas.readGameDataNames(system, variant),
                            TableSchemas.getClientFolder(system))
                    .rows());
        } catch (IOException | RuntimeException failure) {
            return NONE;
        }
    }

    static SystemMessages parse(byte[] table) {
        return fromRows(TableSchemas.read(Tables.SYSTEM_MESSAGES, Path.of(ENGLISH), table, List.of(), null)
                .rows());
    }

    static SystemMessages fromRows(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return NONE;
        }
        var lines = new HashMap<Integer, String>(rows.size() * 2);
        for (var row : rows) {
            if (row.get("id") instanceof Integer id && row.get("message") instanceof String message) {
                lines.put(id, message);
            }
        }
        return new SystemMessages(lines);
    }

    public @Nullable String getLine(int id) {
        return lines.get(id);
    }

    public int size() {
        return lines.size();
    }

    public List<Line> getRows() {
        var rows = new ArrayList<Line>(lines.size());
        for (var line : lines.entrySet()) {
            rows.add(new Line(line.getKey(), line.getValue()));
        }
        rows.sort(Comparator.comparingInt(Line::id));
        return rows;
    }

    public record Line(int id, String text) {}
}
