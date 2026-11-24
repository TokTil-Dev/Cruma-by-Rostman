package com.rost.cruma.core.area;

import com.rost.cruma.core.spawn.SpawnText;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public final class AreaDataFile {
    public static final String FILE_NAME = "areadata.txt";

    private final SpawnText source;
    private final List<ServerArea> areas;
    private final List<ServerArea> defaults;
    private final AreaDefaults defaultValues;
    private final List<Problem> problems;

    AreaDataFile(SpawnText source, List<ServerArea> areas, List<ServerArea> defaults, List<Problem> problems) {
        this.source = Objects.requireNonNull(source, "source");
        this.areas = List.copyOf(areas);
        this.defaults = List.copyOf(defaults);
        this.defaultValues = AreaDefaults.of(this.defaults);
        this.problems = List.copyOf(problems);
    }

    public record Problem(int line, String message) {}

    public static AreaDataFile read(Path path) throws IOException {
        return parse(SpawnText.read(path));
    }

    public static AreaDataFile parse(String text) {
        return parse(SpawnText.of(text));
    }

    public static AreaDataFile parse(SpawnText source) {
        return new AreaDataParser(source).parse();
    }

    public SpawnText getSource() {
        return source;
    }

    public List<ServerArea> getAreas() {
        return areas;
    }

    public List<ServerArea> getDefaults() {
        return defaults;
    }

    public AreaDefaults getDefaultValues() {
        return defaultValues;
    }

    public List<Problem> getProblems() {
        return problems;
    }

    public boolean usesMapNumbers() {
        if (areas.isEmpty()) {
            return true;
        }
        for (var area : areas) {
            if (area.hasMap()) {
                return true;
            }
        }
        return false;
    }
}
