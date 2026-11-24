package com.rost.cruma.core.geodata.generation;

import com.rost.cruma.core.geodata.GeoTile;
import com.rost.cruma.core.geodata.generation.GeoWorldBuilder.StartRounds;
import com.rost.cruma.core.spawn.NpcPosFile;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public interface ExtraStartPoints {
    ExtraStartPoints NONE = new ExtraStartPoints() {
        @Override
        public List<StartRounds> forRegion(GeoTile tile) {
            return List.of();
        }

        @Override
        public String describe() {
            return "";
        }
    };

    static ExtraStartPoints spawns(Path npcpos) throws IOException {
        return new SpawnStartPoints(NpcPosFile.read(npcpos), npcpos.toString());
    }

    static ExtraStartPoints spawns(NpcPosFile file, String name) {
        return new SpawnStartPoints(file, name);
    }

    List<StartRounds> forRegion(GeoTile tile);

    String describe();
}
