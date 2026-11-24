package com.rost.cruma.core.spawn;

import java.util.List;
import java.util.Objects;

public record SpawnMaker(
        int index,
        boolean extended,
        List<String> territoryNames,
        List<String> bannedTerritoryNames,
        String name,
        String ai,
        String aiParameters,
        int maximumNpc,
        String spawnTime,
        String initialSpawn,
        boolean flying,
        List<SpawnNpc> npcs,
        int line,
        SourceSpan span,
        SourceSpan header,
        SourceSpan maximumNpcSpan,
        SourceSpan end,
        SourceSpan aiSpan,
        SourceSpan aiParametersSpan,
        SourceSpan territoriesSpan,
        SourceSpan bannedSpan,
        SourceSpan nameSpan,
        List<String> extra) {
    public SpawnMaker {
        territoryNames = List.copyOf(territoryNames);
        bannedTerritoryNames = List.copyOf(bannedTerritoryNames);
        name = name == null ? "" : name;
        ai = ai == null ? "" : ai;
        aiParameters = aiParameters == null ? "" : aiParameters;
        spawnTime = spawnTime == null ? "" : spawnTime;
        initialSpawn = initialSpawn == null ? "" : initialSpawn;
        npcs = List.copyOf(npcs);
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(header, "header");
        Objects.requireNonNull(maximumNpcSpan, "maximumNpcSpan");
        Objects.requireNonNull(end, "end");
        Objects.requireNonNull(aiSpan, "aiSpan");
        Objects.requireNonNull(aiParametersSpan, "aiParametersSpan");
        Objects.requireNonNull(territoriesSpan, "territoriesSpan");
        Objects.requireNonNull(bannedSpan, "bannedSpan");
        Objects.requireNonNull(nameSpan, "nameSpan");
        extra = extra == null ? List.of() : List.copyOf(extra);
    }

    public static SpawnMaker newMaker(int index, List<String> territoryNames, String name, List<SpawnNpc> npcs) {
        var maximum = npcs.stream().mapToInt(SpawnNpc::total).sum();
        return new SpawnMaker(
                index,
                true,
                territoryNames,
                List.of(),
                name,
                "default_maker",
                "",
                maximum,
                "",
                "",
                false,
                npcs,
                -1,
                SourceSpan.NONE,
                SourceSpan.NONE,
                SourceSpan.NONE,
                SourceSpan.NONE,
                SourceSpan.NONE,
                SourceSpan.NONE,
                SourceSpan.NONE,
                SourceSpan.NONE,
                SourceSpan.NONE,
                List.of());
    }

    public SpawnMaker withNpcs(List<SpawnNpc> newNpcs) {
        return new SpawnMaker(
                index,
                extended,
                territoryNames,
                bannedTerritoryNames,
                name,
                ai,
                aiParameters,
                maximumNpc,
                spawnTime,
                initialSpawn,
                flying,
                newNpcs,
                line,
                span,
                header,
                maximumNpcSpan,
                end,
                aiSpan,
                aiParametersSpan,
                territoriesSpan,
                bannedSpan,
                nameSpan,
                extra);
    }

    public SpawnMaker withMaximumNpc(int value) {
        return new SpawnMaker(
                index,
                extended,
                territoryNames,
                bannedTerritoryNames,
                name,
                ai,
                aiParameters,
                value,
                spawnTime,
                initialSpawn,
                flying,
                npcs,
                line,
                span,
                header,
                maximumNpcSpan,
                end,
                aiSpan,
                aiParametersSpan,
                territoriesSpan,
                bannedSpan,
                nameSpan,
                extra);
    }

    public SpawnMaker withAi(String newAi, String newParameters) {
        return new SpawnMaker(
                index,
                extended,
                territoryNames,
                bannedTerritoryNames,
                name,
                newAi,
                newParameters,
                maximumNpc,
                spawnTime,
                initialSpawn,
                flying,
                npcs,
                line,
                span,
                header,
                maximumNpcSpan,
                end,
                aiSpan,
                aiParametersSpan,
                territoriesSpan,
                bannedSpan,
                nameSpan,
                extra);
    }

    public SpawnMaker withTerritories(List<String> names, List<String> bannedNames) {
        return new SpawnMaker(
                index,
                extended,
                names,
                bannedNames,
                name,
                ai,
                aiParameters,
                maximumNpc,
                spawnTime,
                initialSpawn,
                flying,
                npcs,
                line,
                span,
                header,
                maximumNpcSpan,
                end,
                aiSpan,
                aiParametersSpan,
                territoriesSpan,
                bannedSpan,
                nameSpan,
                extra);
    }

    public SpawnMaker withName(String newName) {
        return new SpawnMaker(
                index,
                extended,
                territoryNames,
                bannedTerritoryNames,
                newName,
                ai,
                aiParameters,
                maximumNpc,
                spawnTime,
                initialSpawn,
                flying,
                npcs,
                line,
                span,
                header,
                maximumNpcSpan,
                end,
                aiSpan,
                aiParametersSpan,
                territoriesSpan,
                bannedSpan,
                nameSpan,
                extra);
    }

    public SpawnMaker copied(
            int newIndex, List<String> names, List<String> bannedNames, String newName, List<SpawnNpc> newNpcs) {
        return new SpawnMaker(
                newIndex,
                extended,
                names,
                bannedNames,
                newName,
                ai,
                aiParameters,
                maximumNpc,
                spawnTime,
                initialSpawn,
                flying,
                newNpcs,
                -1,
                SourceSpan.NONE,
                SourceSpan.NONE,
                SourceSpan.NONE,
                SourceSpan.NONE,
                SourceSpan.NONE,
                SourceSpan.NONE,
                SourceSpan.NONE,
                SourceSpan.NONE,
                SourceSpan.NONE,
                extra);
    }

    public int totalNpcs() {
        return npcs.stream().mapToInt(SpawnNpc::total).sum();
    }

    public String displayName() {
        if (!name.isEmpty()) {
            return name;
        }
        return territoryNames.isEmpty() ? "maker " + index : territoryNames.getFirst();
    }

    public boolean isConditional() {
        if (!spawnTime.isEmpty() || aiParameters.contains("EventName")) {
            return true;
        }
        for (var setting : extra) {
            if (setting.startsWith("event_name")) {
                return true;
            }
        }
        return false;
    }

    public String eventName() {
        if (aiParameters.contains("EventName")) {
            var value = AiParameters.parse(aiParameters).get("EventName");
            if (value != null) {
                return stripBrackets(value);
            }
        }
        for (var setting : extra) {
            if (setting.startsWith("event_name")) {
                var equals = setting.indexOf('=');
                return equals < 0 ? "" : stripBrackets(setting.substring(equals + 1));
            }
        }
        return "";
    }

    public String spawnTimeKind() {
        var open = spawnTime.indexOf('(');
        return (open < 0 ? spawnTime : spawnTime.substring(0, open)).strip();
    }

    private static String stripBrackets(String value) {
        var text = value.strip();
        return text.startsWith("[") && text.endsWith("]") ? text.substring(1, text.length() - 1) : text;
    }
}
