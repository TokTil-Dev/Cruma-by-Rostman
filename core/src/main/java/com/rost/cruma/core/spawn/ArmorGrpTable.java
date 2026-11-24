package com.rost.cruma.core.spawn;

import com.rost.cruma.core.schema.Schema;
import com.rost.cruma.core.schema.TableData;
import com.rost.cruma.core.schema.TableFiles;
import com.rost.cruma.core.schema.TableSchemas;
import com.rost.cruma.core.schema.Tables;
import com.rost.cruma.core.unreal.L2TextTable;
import com.rost.cruma.core.world.PlayerFigures;
import com.rost.cruma.core.world.WorldVariant;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ArmorGrpTable {
    static final List<String> FIGURES = List.of(
            "mfighter",
            "ffighter",
            "mdarkelf",
            "fdarkelf",
            "mdwarf",
            "fdwarf",
            "melf",
            "felf",
            "mmagic",
            "fmagic",
            "morc",
            "forc",
            "mshaman",
            "fshaman",
            "mkamael",
            "fkamael",
            "mertheia",
            "fertheia",
            "msylph",
            "fsylph",
            "mhighelf",
            "fhighelf",
            "crow",
            "npc");

    public static final int ONE_PIECE = 8;

    public static final int ALL_DRESS = 9;
    public static final int HAIR_ALL = 10;

    public static final int HIDE_HAIR = 0x20;

    public static final int HIDE_HAIR_ACCESSORY = 0x40;

    public static final int BACK = 2000;

    private static final int TEXT_PART = 1000;

    private final Map<Integer, Armor> byId;
    private final Map<Integer, String> icons;

    private ArmorGrpTable(Map<Integer, Armor> byId) {
        this(byId, Map.of());
    }

    private ArmorGrpTable(Map<Integer, Armor> byId, Map<Integer, String> icons) {
        this.byId = Map.copyOf(byId);
        this.icons = Map.copyOf(icons);
    }

    public record Look(
            List<String> meshes,
            List<String> textures,
            List<String> addMeshes,
            int[] addValues,
            List<String> addTextures,
            String addTexture) {
        public Look(List<String> meshes, List<String> textures) {
            this(meshes, textures, List.of(), new int[0], List.of(), "");
        }
    }

    public record Armor(int id, int bodyPart, Map<String, Look> looks, int hideMask, Map<String, Integer> hideMasks) {
        public Armor(int id, int bodyPart, Map<String, Look> looks, int hideMask) {
            this(id, bodyPart, looks, hideMask, Map.of());
        }

        public int getHideMask(String figure) {
            var mask = hideMasks.get(PlayerFigures.getLookFigure(figure).toLowerCase(Locale.ROOT));
            return mask != null ? mask : hideMask;
        }

        public Look look(String figure) {
            var look = looks.get(PlayerFigures.getLookFigure(figure).toLowerCase(Locale.ROOT));
            return look == null || look.meshes().isEmpty() ? null : look;
        }

        public Look findDrawnLook(String figure) {
            var look = look(figure);
            return look == null || !isDrawn(look) ? null : look;
        }

        private static boolean isDrawn(Look look) {
            for (var mesh : look.meshes()) {
                if (!mesh.isBlank() && !mesh.equalsIgnoreCase("None")) {
                    return true;
                }
            }
            for (var mesh : look.addMeshes()) {
                if (!mesh.isBlank() && !mesh.equalsIgnoreCase("None")) {
                    return true;
                }
            }
            return false;
        }
    }

    static ArmorGrpTable of(Map<Integer, Armor> armors) {
        return new ArmorGrpTable(armors);
    }

    public static ArmorGrpTable empty() {
        return new ArmorGrpTable(Map.of());
    }

    public static ArmorGrpTable read(Path system) throws IOException {
        return read(system, WorldVariant.MAIN);
    }

    public static ArmorGrpTable read(Path system, WorldVariant variant) throws IOException {
        var file = TableFiles.find(system, variant, "Armorgrp.dat");
        if (file == null) {
            var text = variant.find(system, "armorgrp.txt");
            if (text != null) {
                return parseText(L2TextTable.read(text));
            }
            throw new IOException("No Armorgrp.dat in " + system);
        }
        var table = TableSchemas.read(Tables.ARMORS, system, variant);
        var schema = table.schema();
        if (schema == null) {
            throw TableRows.failure(table.report());
        }
        var resources = namesResources(table.rows())
                ? TableSchemas.read(Tables.ARMOR_RESOURCES, system, variant).rows()
                : List.<Map<String, Object>>of();
        return fromRows(table.rows(), schema, resources);
    }

    static ArmorGrpTable parseText(List<Map<String, String>> records) throws IOException {
        var armors = new HashMap<Integer, Armor>(records.size() * 2);
        var icons = new HashMap<Integer, String>(records.size() * 2);
        for (var record : records) {
            var id = L2TextTable.integer(record, "object_id", -1);
            if (id < 0) {
                continue;
            }
            var looks = new HashMap<String, Look>();
            for (var figure : FIGURES) {
                var meshes = L2TextTable.references(record.get(figure + "_mesh"));
                if (!meshes.isEmpty()) {
                    looks.put(figure, new Look(meshes, L2TextTable.references(record.get(figure + "_texture"))));
                }
            }
            var onePiece = record.getOrDefault("body_part", "").contains("CHEST");
            armors.putIfAbsent(id, new Armor(id, onePiece ? ONE_PIECE : TEXT_PART, looks, 0));
            var icon = L2TextTable.references(record.get("icon"));
            if (!icon.isEmpty()) {
                icons.putIfAbsent(id, icon.getFirst());
            }
        }
        if (armors.isEmpty()) {
            throw new IOException("armorgrp.txt has no item records");
        }
        return new ArmorGrpTable(armors, icons);
    }

    static ArmorGrpTable parse(byte[] bytes, List<String> names) throws IOException {
        return from(TableSchemas.read(Tables.ARMORS, Path.of("Armorgrp.dat"), bytes, names, null));
    }

    static ArmorGrpTable fromRows(List<Map<String, Object>> rows, Schema schema) {
        return fromRows(rows, schema, List.of());
    }

    static ArmorGrpTable fromRows(List<Map<String, Object>> rows, Schema schema, List<Map<String, Object>> resources) {
        var byResource = toResources(resources);
        var interludeParts = Integer.valueOf(1).equals(schema.getConstants().get("bodyParts"));
        var backPart = schema.getConstants().get("backPart") instanceof Integer number ? number : null;
        var everyLook = schema.needsGameDataNames();
        var armors = new HashMap<Integer, Armor>(rows.size() * 2);
        var icons = new HashMap<Integer, String>(rows.size() * 2);
        for (var row : rows) {
            if (!(row.get("id") instanceof Integer id)) {
                continue;
            }
            var icon = TableRows.reference(row.get("icon"));
            if (!icon.isEmpty() && !icon.equalsIgnoreCase("None")) {
                icons.putIfAbsent(id, icon);
            }
            var looks = HashMap.<String, Look>newHashMap(FIGURES.size());
            for (var figure : FIGURES) {
                if (row.get(figure) instanceof Map<?, ?> fields) {
                    var look = toLook(fields);
                    if (everyLook
                            || !look.meshes().isEmpty()
                            || !look.addMeshes().isEmpty()) {
                        looks.put(figure, look);
                    }
                }
            }
            var part = row.get("bodyPart") instanceof Integer number ? number : 0;
            var resource = row.get("res") instanceof Integer res ? byResource.get(res) : null;
            armors.putIfAbsent(
                    id,
                    new Armor(
                            id,
                            interludeParts
                                    ? toNewerPart(part)
                                    : Integer.valueOf(part).equals(backPart) ? BACK : part,
                            resource != null ? resource.looks() : looks,
                            row.get("hideMask") instanceof Integer mask ? mask : 0,
                            resource != null ? resource.hideMasks() : Map.of()));
        }
        return new ArmorGrpTable(armors, icons);
    }

    private record Resource(Map<String, Look> looks, Map<String, Integer> hideMasks) {}

    public String getIcon(int itemId) {
        return icons.get(itemId);
    }

    public Map<Integer, String> getIcons() {
        return icons;
    }

    public List<Armor> getAll() {
        return byId.values().stream().sorted(Comparator.comparingInt(Armor::id)).toList();
    }

    public Armor getArmor(int itemId) {
        return byId.get(itemId);
    }

    public boolean hasLooks(String figure) {
        for (var armor : byId.values()) {
            var look = armor.findDrawnLook(figure);
            if (look != null
                    && look.meshes().stream().anyMatch(mesh -> !mesh.isBlank() && !mesh.equalsIgnoreCase("None"))) {
                return true;
            }
        }
        return false;
    }

    public int size() {
        return byId.size();
    }

    private static ArmorGrpTable from(TableData table) throws IOException {
        var schema = table.schema();
        if (schema == null) {
            throw TableRows.failure(table.report());
        }
        return fromRows(table.rows(), schema);
    }

    private static boolean namesResources(List<Map<String, Object>> rows) {
        return !rows.isEmpty() && rows.getFirst().containsKey("res");
    }

    private static Map<Integer, Resource> toResources(List<Map<String, Object>> rows) {
        var resources = new HashMap<Integer, Resource>();
        for (var row : rows) {
            if (!(row.get("id") instanceof Integer id)
                    || !(row.get("figure") instanceof Integer figure)
                    || figure < 0
                    || figure >= FIGURES.size()) {
                continue;
            }
            var resource = resources.computeIfAbsent(id, key -> new Resource(new HashMap<>(), new HashMap<>()));
            var name = FIGURES.get(figure);
            if (resource.looks().putIfAbsent(name, toLook(row)) == null) {
                resource.hideMasks().put(name, row.get("hideMask") instanceof Integer mask ? mask : 0);
            }
        }
        return resources;
    }

    private static Look toLook(Map<?, ?> fields) {
        List<String> addMeshes;
        if (fields.get("addMeshes") instanceof List<?> list
                && !list.isEmpty()
                && list.getFirst() instanceof Map<?, ?>) {
            var meshes = new ArrayList<String>(list.size());
            for (var item : list) {
                meshes.add(TableRows.reference(item instanceof Map<?, ?> group ? group.get("mesh") : item));
            }
            addMeshes = List.copyOf(meshes);
        } else {
            addMeshes = TableRows.references(fields.get("addMeshes"));
        }
        int[] addValues;
        if (fields.get("addValues") instanceof List<?> pairs) {
            addValues = new int[pairs.size() * 2];
            for (var i = 0; i < pairs.size(); i++) {
                if (pairs.get(i) instanceof Map<?, ?> pair) {
                    addValues[i * 2] = pair.get("first") instanceof Integer first ? first : 0;
                    addValues[i * 2 + 1] = pair.get("second") instanceof Integer second ? second : 0;
                }
            }
        } else {
            addValues = new int[addMeshes.size() * 2];
        }
        return new Look(
                TableRows.references(fields.get("meshes")),
                TableRows.references(fields.get("textures")),
                addMeshes,
                addValues,
                TableRows.references(fields.get("addTextures")),
                TableRows.reference(fields.get("addTexture")));
    }

    private static int toNewerPart(int part) {
        return switch (part) {
            case 15 -> ONE_PIECE;
            case 16 -> ALL_DRESS;
            case 19 -> HAIR_ALL;
            default -> 1000 + part;
        };
    }
}
