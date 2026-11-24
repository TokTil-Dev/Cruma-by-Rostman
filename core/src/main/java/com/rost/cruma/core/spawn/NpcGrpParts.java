package com.rost.cruma.core.spawn;

import com.rost.cruma.core.schema.TableData;
import com.rost.cruma.core.schema.TableFiles;
import com.rost.cruma.core.schema.TableSchemas;
import com.rost.cruma.core.schema.Tables;
import com.rost.cruma.core.world.WorldVariant;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

public final class NpcGrpParts {
    private final Map<Integer, Parts> byId;

    private NpcGrpParts(Map<Integer, Parts> byId) {
        this.byId = Map.copyOf(byId);
    }

    public record Parts(
            int npcId,
            int chest,
            int legs,
            int gloves,
            int feet,
            int back,
            int hairAccessory,
            int hairStyle,
            int rightHand,
            int leftHand) {}

    static NpcGrpParts of(Map<Integer, Parts> parts) {
        return new NpcGrpParts(parts);
    }

    public static NpcGrpParts empty() {
        return new NpcGrpParts(Map.of());
    }

    public static NpcGrpParts read(Path system) throws IOException {
        return read(system, WorldVariant.MAIN);
    }

    public static NpcGrpParts read(Path system, WorldVariant variant) throws IOException {
        var file = TableFiles.find(system, variant, "AdditionalNpcGrpParts.dat");
        return file == null ? empty() : from(TableSchemas.read(Tables.NPC_GRP_PARTS, system, variant));
    }

    static NpcGrpParts parse(byte[] bytes) throws IOException {
        return from(
                TableSchemas.read(Tables.NPC_GRP_PARTS, Path.of("AdditionalNpcGrpParts.dat"), bytes, List.of(), null));
    }

    static NpcGrpParts fromRows(List<Map<String, Object>> rows) {
        var parts = new HashMap<Integer, Parts>(rows.size() * 2);
        for (var row : rows) {
            if (!(row.get("npcId") instanceof Integer npcId)) {
                continue;
            }
            parts.putIfAbsent(
                    npcId,
                    new Parts(
                            npcId,
                            number(row.get("chest")),
                            number(row.get("legs")),
                            number(row.get("gloves")),
                            number(row.get("feet")),
                            number(row.get("back")),
                            number(row.get("hairAccessory")),
                            number(row.get("hairStyle")),
                            number(row.get("rightHand")),
                            number(row.get("leftHand"))));
        }
        return new NpcGrpParts(parts);
    }

    public Parts getParts(int npcId) {
        return byId.get(npcId);
    }

    private static NpcGrpParts from(TableData table) throws IOException {
        if (table.schema() == null) {
            throw TableRows.failure(table.report());
        }
        return fromRows(table.rows());
    }

    private static int number(@Nullable Object value) {
        return value instanceof Integer number ? number : 0;
    }
}
