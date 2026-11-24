package com.rost.cruma.core.spawn;

import com.rost.cruma.core.schema.GameDataNames;
import com.rost.cruma.core.schema.TableData;
import com.rost.cruma.core.schema.TableFiles;
import com.rost.cruma.core.schema.TableReport;
import com.rost.cruma.core.schema.TableSchemas;
import com.rost.cruma.core.schema.Tables;
import com.rost.cruma.core.unreal.L2TextTable;
import com.rost.cruma.core.world.WorldVariant;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

public final class NpcGrpTable {
    private final Map<Integer, Appearance> byId;
    private final String layout;
    private final boolean scales;

    private NpcGrpTable(Map<Integer, Appearance> byId, String layout) {
        this(byId, layout, false);
    }

    private NpcGrpTable(Map<Integer, Appearance> byId, String layout, boolean scales) {
        this.byId = Map.copyOf(byId);
        this.layout = layout;
        this.scales = scales;
    }

    public record Appearance(
            int id,
            String mesh,
            List<String> textures,
            float drawScale,
            String npcClass,
            int rightHand,
            int leftHand,
            int walkSpeed,
            int runSpeed) {
        public Appearance {
            mesh = cleanReference(mesh);
            textures = textures.stream().map(NpcGrpTable::cleanReference).toList();
            npcClass = cleanReference(npcClass);
        }

        public Appearance(int id, String mesh, List<String> textures, float drawScale) {
            this(id, mesh, textures, drawScale, "");
        }

        public Appearance(int id, String mesh, List<String> textures, float drawScale, String npcClass) {
            this(id, mesh, textures, drawScale, npcClass, 0, 0);
        }

        public Appearance(
                int id,
                String mesh,
                List<String> textures,
                float drawScale,
                String npcClass,
                int rightHand,
                int leftHand) {
            this(id, mesh, textures, drawScale, npcClass, rightHand, leftHand, 0, 0);
        }

        public boolean clear() {
            if (mesh.isBlank() || mesh.equalsIgnoreCase("None")) {
                return true;
            }
            if (textures.isEmpty()) {
                return false;
            }
            for (var texture : textures) {
                if (!texture.toLowerCase(Locale.ROOT).contains("clear_npc")) {
                    return false;
                }
            }
            return true;
        }

        public Appearance withDrawScale(float scale) {
            return new Appearance(id, mesh, textures, scale, npcClass, rightHand, leftHand, walkSpeed, runSpeed);
        }

        public String meshPackage() {
            var dot = mesh.indexOf('.');
            return dot < 0 ? "" : mesh.substring(0, dot);
        }

        public String meshObject() {
            return mesh.substring(mesh.lastIndexOf('.') + 1);
        }
    }

    public static NpcGrpTable empty() {
        return new NpcGrpTable(Map.of(), "none");
    }

    public static NpcGrpTable read(Path system) throws IOException {
        return read(system, WorldVariant.MAIN);
    }

    public static NpcGrpTable read(Path system, WorldVariant variant) throws IOException {
        var file = TableFiles.find(system, variant, "npcgrp.dat");
        if (file == null) {
            var text = variant.find(system, "npcgrp.txt");
            if (text != null) {
                return parseText(L2TextTable.read(text));
            }
            throw new IOException("No npcgrp.dat in " + system);
        }
        return of(TableSchemas.read(Tables.NPC_GRP, system, variant));
    }

    static String cleanReference(String value) {
        var trimmed = value.strip();
        if (trimmed.length() >= 2 && trimmed.startsWith("[") && trimmed.endsWith("]")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1).strip();
        }
        return trimmed;
    }

    static NpcGrpTable parse(byte[] data, List<String> names) throws IOException {
        return of(TableSchemas.read(Tables.NPC_GRP, Path.of("npcgrp.dat"), data, names, null));
    }

    static NpcGrpTable parseText(List<Map<String, String>> records) throws IOException {
        var appearances = new HashMap<Integer, Appearance>(records.size() * 2);
        for (var record : records) {
            var id = L2TextTable.integer(record, "npc_id", -1);
            var mesh = record.get("mesh_name");
            if (id < 0 || mesh == null) {
                continue;
            }
            var textures = L2TextTable.names(record.getOrDefault("texture_name", ""));
            appearances.putIfAbsent(id, new Appearance(id, L2TextTable.name(mesh), textures, 1));
        }
        if (appearances.isEmpty()) {
            throw new IOException("npcgrp.txt has no NPC records");
        }
        return new NpcGrpTable(appearances, "text");
    }

    static List<String> gameDataNames(byte[] data) {
        return GameDataNames.read(data);
    }

    static Map<Integer, Appearance> toAppearances(List<Map<String, Object>> rows) {
        var appearances = new HashMap<Integer, Appearance>(rows.size() * 2);
        for (var row : rows) {
            if (!(row.get("id") instanceof Integer id)) {
                continue;
            }
            var textures = new ArrayList<String>();
            if (row.get("textures") instanceof List<?> list) {
                for (var texture : list) {
                    textures.add(String.valueOf(texture));
                }
            }
            appearances.putIfAbsent(
                    id,
                    new Appearance(
                            id,
                            text(row.get("mesh")),
                            textures,
                            row.get("drawScale") instanceof Number scale && scale.floatValue() > 0
                                    ? scale.floatValue()
                                    : 1,
                            text(row.get("class")),
                            number(row.get("rightHand")),
                            number(row.get("leftHand")),
                            number(row.get("walkSpeed")),
                            number(row.get("runSpeed"))));
        }
        return appearances;
    }

    public Appearance getAppearance(int id) {
        return byId.get(id);
    }

    public int size() {
        return byId.size();
    }

    public Collection<Appearance> getAppearances() {
        return Collections.unmodifiableCollection(byId.values());
    }

    public boolean hasScales() {
        return scales;
    }

    public float getScale(Appearance appearance, float classScale) {
        return hasScales() ? appearance.drawScale() : classScale;
    }

    public String getLayout() {
        return layout;
    }

    private static NpcGrpTable of(TableData table) throws IOException {
        var report = table.report();
        var schema = table.schema();
        if (schema == null) {
            throw TableRows.failure(report);
        }
        var layout = report.state() == TableReport.State.PARTIAL
                ? schema.getTitle() + ", " + report.problem()
                : schema.getTitle();
        return new NpcGrpTable(
                toAppearances(table.rows()), layout, schema.getNames().contains("drawScale"));
    }

    private static String text(@Nullable Object value) {
        return value instanceof String text ? text : "";
    }

    private static int number(@Nullable Object value) {
        return value instanceof Integer number ? number : 0;
    }
}
