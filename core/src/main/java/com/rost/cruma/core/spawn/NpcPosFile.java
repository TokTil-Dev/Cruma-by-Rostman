package com.rost.cruma.core.spawn;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class NpcPosFile {
    private final SpawnText source;
    private final List<SpawnTerritory> territories;
    private final List<SpawnMaker> makers;
    private final List<SpawnNpc> npcs;
    private final List<Problem> problems;
    private final Map<String, SpawnTerritory> territoryByName;

    NpcPosFile(SpawnText source, List<SpawnTerritory> territories, List<SpawnMaker> makers, List<Problem> problems) {
        this.source = Objects.requireNonNull(source, "source");
        this.territories = List.copyOf(territories);
        this.makers = List.copyOf(makers);
        this.problems = List.copyOf(problems);
        var allNpcs = new ArrayList<SpawnNpc>();
        for (var maker : makers) {
            allNpcs.addAll(maker.npcs());
        }
        this.npcs = List.copyOf(allNpcs);
        var byName = new HashMap<String, SpawnTerritory>();
        for (var territory : territories) {
            if (!territory.domain()) {
                byName.putIfAbsent(territory.name(), territory);
            }
        }
        this.territoryByName = Map.copyOf(byName);
    }

    public record Problem(int line, String message) {}

    public static NpcPosFile read(Path path) throws IOException {
        return parse(SpawnText.read(path));
    }

    public static NpcPosFile parse(String text) {
        return parse(SpawnText.of(text));
    }

    public static NpcPosFile parse(SpawnText source) {
        return new NpcPosParser(source).parse();
    }

    public SpawnText getSource() {
        return source;
    }

    public List<SpawnTerritory> getTerritories() {
        return territories;
    }

    public List<SpawnMaker> getMakers() {
        return makers;
    }

    public List<SpawnNpc> getNpcs() {
        return npcs;
    }

    public List<Problem> getProblems() {
        return problems;
    }

    public SpawnTerritory getTerritory(String name) {
        return territoryByName.get(name);
    }
}
