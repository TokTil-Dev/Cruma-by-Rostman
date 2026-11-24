package com.rost.cruma.core.spawn;

import com.rost.cruma.core.spawn.SpawnPosition.Anywhere;
import java.util.List;
import java.util.Objects;

public record SpawnNpc(
        int index,
        int makerIndex,
        String npcName,
        SpawnPosition position,
        int total,
        String respawn,
        String respawnRandom,
        String nickname,
        String dbName,
        String privates,
        String ai,
        int line,
        Spans spans,
        List<String> extra) {
    public SpawnNpc {
        Objects.requireNonNull(npcName, "npcName");
        Objects.requireNonNull(position, "position");
        respawn = respawn == null ? "" : respawn;
        respawnRandom = respawnRandom == null ? "" : respawnRandom;
        nickname = nickname == null ? "" : nickname;
        dbName = dbName == null ? "" : dbName;
        privates = privates == null ? "" : privates;
        ai = ai == null ? "" : ai;
        Objects.requireNonNull(spans, "spans");
        extra = extra == null ? List.of() : List.copyOf(extra);
    }

    public record Spans(
            SourceSpan entry,
            SourceSpan name,
            SourceSpan position,
            SourceSpan total,
            SourceSpan respawn,
            SourceSpan respawnRandom,
            SourceSpan end) {
        public static final Spans NONE = new Spans(
                SourceSpan.NONE,
                SourceSpan.NONE,
                SourceSpan.NONE,
                SourceSpan.NONE,
                SourceSpan.NONE,
                SourceSpan.NONE,
                SourceSpan.NONE);
    }

    public SpawnNpc withNpcName(String value) {
        return new SpawnNpc(
                index,
                makerIndex,
                value,
                position,
                total,
                respawn,
                respawnRandom,
                nickname,
                dbName,
                privates,
                ai,
                line,
                spans,
                extra);
    }

    public SpawnNpc withPosition(SpawnPosition value) {
        return new SpawnNpc(
                index,
                makerIndex,
                npcName,
                value,
                total,
                respawn,
                respawnRandom,
                nickname,
                dbName,
                privates,
                ai,
                line,
                spans,
                extra);
    }

    public SpawnNpc withTotal(int value) {
        return new SpawnNpc(
                index,
                makerIndex,
                npcName,
                position,
                value,
                respawn,
                respawnRandom,
                nickname,
                dbName,
                privates,
                ai,
                line,
                spans,
                extra);
    }

    public SpawnNpc withRespawn(String value, String randomValue) {
        return new SpawnNpc(
                index,
                makerIndex,
                npcName,
                position,
                total,
                value,
                randomValue,
                nickname,
                dbName,
                privates,
                ai,
                line,
                spans,
                extra);
    }

    public SpawnNpc detached(int newIndex, int newMakerIndex) {
        return new SpawnNpc(
                newIndex,
                newMakerIndex,
                npcName,
                position,
                total,
                respawn,
                respawnRandom,
                nickname,
                dbName,
                privates,
                ai,
                -1,
                Spans.NONE,
                extra);
    }

    public SpawnNpc copied(int newMakerIndex, SpawnPosition newPosition) {
        var kept = extra.stream()
                .filter(setting -> !setting.startsWith("dbname") && !setting.startsWith("dbsaving"))
                .toList();
        return new SpawnNpc(
                -1,
                newMakerIndex,
                npcName,
                newPosition,
                total,
                respawn,
                respawnRandom,
                nickname,
                "",
                privates,
                ai,
                -1,
                Spans.NONE,
                kept);
    }

    public boolean isAnywhere() {
        return position instanceof Anywhere;
    }
}
