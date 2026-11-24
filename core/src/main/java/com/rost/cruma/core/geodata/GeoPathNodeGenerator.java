package com.rost.cruma.core.geodata;

import com.rost.cruma.core.geodata.PathNodeGraph.ZoneTable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;

public final class GeoPathNodeGenerator {
    public static final String ALGORITHM_ID = "cruma-pathnode-v1";

    private GeoPathNodeGenerator() {}

    @FunctionalInterface
    public interface Control {
        void checkpoint(long completed, long total, String detail) throws IOException;
    }

    public static void generate(Path directory, Control control) throws IOException {
        generate(directory, ZoneTable.Y10_TO_26, control);
    }

    public static void generate(Path directory, ZoneTable table, Control control) throws IOException {
        generate(directory, directory, table, control);
    }

    public static void generate(Path input, Path output, ZoneTable table, Control control) throws IOException {
        generate(input, null, output, table, control);
    }

    static void generate(Path input, @Nullable List<String> regions, Path output, ZoneTable table, Control control)
            throws IOException {
        Objects.requireNonNull(input);
        Objects.requireNonNull(output);
        Objects.requireNonNull(table);
        Objects.requireNonNull(control);
        for (var name : List.of("pathnode.idx", "pathnode.bin", "pathnode.txt")) {
            if (Files.exists(output.resolve(name), LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Path-node output already exists: " + name);
            }
        }
        var build = GeoPathNodeBuild.load(input, regions, control);
        GeoPathNodeLinks.requireZoneTable(build, table);
        GeoPathNodeCandidates.generate(build);
        GeoPathNodeLinks.generate(build);
        GeoPathNodeLinks.write(build, output, table);
        control.checkpoint(build.nodes.size(), build.nodes.size(), "Path-node generation complete");
    }
}
