package com.rost.cruma.core.npc;

import com.rost.cruma.core.schema.TableSchemas;
import com.rost.cruma.core.schema.Tables;
import com.rost.cruma.core.world.WorldVariant;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

public final class EnterEvents {
    public static final int FROM_BELOW = 1;

    public static final int FROM_ABOVE = 2;

    public static final float RISE_SECONDS = 1.8f;

    private static final EnterEvents NONE = new EnterEvents(Map.of());

    private final Map<Integer, Entrance> entrances;

    private EnterEvents(Map<Integer, Entrance> entrances) {
        this.entrances = Map.copyOf(entrances);
    }

    public record Entrance(String sound, float volume, float radius, int rise, String effect, String animation) {}

    public static EnterEvents read(Path client, WorldVariant variant) {
        return of(TableSchemas.read(Tables.ENTER_EVENTS, client.resolve("system"), variant)
                .rows());
    }

    static EnterEvents of(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return NONE;
        }
        var entrances = new HashMap<Integer, Entrance>();
        for (var record : rows) {
            if (record.get("npc") instanceof Integer npc) {
                entrances.putIfAbsent(
                        npc,
                        new Entrance(
                                text(record.get("sound")),
                                record.get("volume") instanceof Number volume ? volume.floatValue() : 0f,
                                record.get("radius") instanceof Number radius ? radius.floatValue() : 0f,
                                record.get("rise") instanceof Integer rise ? rise : 0,
                                text(record.get("effect")),
                                text(record.get("anim"))));
            }
        }
        return new EnterEvents(entrances);
    }

    public @Nullable Entrance of(int npc) {
        return entrances.get(npc);
    }

    public int size() {
        return entrances.size();
    }

    private static String text(@Nullable Object value) {
        var text = value == null ? "" : String.valueOf(value).trim();
        return text.equalsIgnoreCase("None") ? "" : text;
    }
}
