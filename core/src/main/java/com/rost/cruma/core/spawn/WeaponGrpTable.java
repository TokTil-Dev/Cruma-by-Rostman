package com.rost.cruma.core.spawn;

import com.rost.cruma.core.schema.TableData;
import com.rost.cruma.core.schema.TableFiles;
import com.rost.cruma.core.schema.TableSchemas;
import com.rost.cruma.core.schema.Tables;
import com.rost.cruma.core.unreal.L2TextTable;
import com.rost.cruma.core.world.WorldVariant;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class WeaponGrpTable {
    private static final int SHIELD = 0;
    private static final int ONE_HANDED = 1;
    private static final int TWO_HANDED = 2;
    private static final int DUAL = 3;
    private static final int POLE = 4;
    private static final int BOW = 5;

    private final Map<Integer, Weapon> byId;
    private final Map<Integer, String> icons;

    private WeaponGrpTable(Map<Integer, Weapon> byId) {
        this(byId, Map.of());
    }

    private WeaponGrpTable(Map<Integer, Weapon> byId, Map<Integer, String> icons) {
        this.byId = Map.copyOf(byId);
        this.icons = Map.copyOf(icons);
    }

    public record Weapon(int id, int handness, List<String> meshes, List<String> textures) {}

    static WeaponGrpTable of(Map<Integer, Weapon> weapons) {
        return new WeaponGrpTable(weapons);
    }

    public static WeaponGrpTable empty() {
        return new WeaponGrpTable(Map.of());
    }

    public static WeaponGrpTable read(Path system) throws IOException {
        return read(system, WorldVariant.MAIN);
    }

    public static WeaponGrpTable read(Path system, WorldVariant variant) throws IOException {
        var file = TableFiles.find(system, variant, "Weapongrp.dat");
        if (file == null) {
            var text = variant.find(system, "weapongrp.txt");
            if (text != null) {
                return parseText(L2TextTable.read(text));
            }
            throw new IOException("No Weapongrp.dat in " + system);
        }
        var table = TableSchemas.read(Tables.WEAPONS, system, variant);
        if (table.schema() == null) {
            throw TableRows.failure(table.report());
        }
        var resources = namesResources(table.rows())
                ? TableSchemas.read(Tables.WEAPON_RESOURCES, system, variant).rows()
                : List.<Map<String, Object>>of();
        return fromRows(table.rows(), resources);
    }

    static WeaponGrpTable parseText(List<Map<String, String>> records) throws IOException {
        var weapons = new HashMap<Integer, Weapon>(records.size() * 2);
        var icons = new HashMap<Integer, String>(records.size() * 2);
        for (var record : records) {
            var id = L2TextTable.integer(record, "object_id", -1);
            if (id < 0) {
                continue;
            }
            weapons.putIfAbsent(
                    id,
                    new Weapon(
                            id,
                            handness(record),
                            L2TextTable.references(record.get("mesh")),
                            L2TextTable.references(record.get("texture"))));
            var icon = L2TextTable.references(record.get("icon"));
            if (!icon.isEmpty()) {
                icons.putIfAbsent(id, icon.getFirst());
            }
        }
        if (weapons.isEmpty()) {
            throw new IOException("weapongrp.txt has no item records");
        }
        return new WeaponGrpTable(weapons, icons);
    }

    static WeaponGrpTable parse(byte[] bytes, List<String> names) throws IOException {
        return from(TableSchemas.read(Tables.WEAPONS, Path.of("Weapongrp.dat"), bytes, names, null));
    }

    static WeaponGrpTable fromRows(List<Map<String, Object>> rows) {
        return fromRows(rows, List.of());
    }

    static WeaponGrpTable fromRows(List<Map<String, Object>> rows, List<Map<String, Object>> resources) {
        var byResource = HashMap.<Integer, Map<String, Object>>newHashMap(resources.size());
        for (var resource : resources) {
            if (resource.get("id") instanceof Integer id) {
                byResource.putIfAbsent(id, resource);
            }
        }
        var weapons = new HashMap<Integer, Weapon>(rows.size() * 2);
        var icons = new HashMap<Integer, String>(rows.size() * 2);
        for (var row : rows) {
            if (!(row.get("id") instanceof Integer id)) {
                continue;
            }
            var icon = TableRows.reference(row.get("icon"));
            if (!icon.isEmpty() && !icon.equalsIgnoreCase("None")) {
                icons.putIfAbsent(id, icon);
            }
            var meshes = TableRows.references(row.get("meshes"));
            var textures = TableRows.references(row.get("textures"));
            if (!row.containsKey("meshes") && row.get("res") instanceof Integer res) {
                var resource = byResource.get(res);
                if (resource != null) {
                    meshes = meshesOf(resource.get("parts"));
                    textures = TableRows.references(resource.get("textures"));
                }
            }
            weapons.putIfAbsent(
                    id,
                    new Weapon(
                            id,
                            row.get("handness") instanceof Integer handness ? handness : ONE_HANDED,
                            meshes,
                            textures));
        }
        return new WeaponGrpTable(weapons, icons);
    }

    public String getIcon(int itemId) {
        return icons.get(itemId);
    }

    public Map<Integer, String> getIcons() {
        return icons;
    }

    public List<Weapon> getAll() {
        return byId.values().stream()
                .sorted(Comparator.comparingInt(Weapon::id))
                .toList();
    }

    public Weapon getWeapon(int itemId) {
        return byId.get(itemId);
    }

    public int getHeldKind(int rightHand, int leftHand) {
        var weapon = rightHand > 0 ? getWeapon(rightHand) : null;
        if (weapon != null) {
            return weapon.handness();
        }
        var shield = leftHand > 0 ? getWeapon(leftHand) : null;
        return shield != null && shield.handness() == 0 ? 1 : 0;
    }

    public int size() {
        return byId.size();
    }

    private static WeaponGrpTable from(TableData table) throws IOException {
        if (table.schema() == null) {
            throw TableRows.failure(table.report());
        }
        return fromRows(table.rows());
    }

    private static boolean namesResources(List<Map<String, Object>> rows) {
        return !rows.isEmpty()
                && !rows.getFirst().containsKey("meshes")
                && rows.getFirst().containsKey("res");
    }

    private static List<String> meshesOf(Object parts) {
        if (!(parts instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        var meshes = new ArrayList<String>(list.size());
        for (var part : list) {
            meshes.add(TableRows.reference(part instanceof Map<?, ?> fields ? fields.get("mesh") : null));
        }
        return List.copyOf(meshes);
    }

    private static int handness(Map<String, String> record) {
        var part = record.getOrDefault("body_part", "").toLowerCase(Locale.ROOT);
        var type = record.getOrDefault("weapon_type", "").strip().toLowerCase(Locale.ROOT);
        if (part.contains("lrhand")) {
            return switch (type) {
                case "bow" -> BOW;
                case "pole" -> POLE;
                case "dual", "dualfist" -> DUAL;
                default -> TWO_HANDED;
            };
        }
        return part.contains("lhand") ? SHIELD : ONE_HANDED;
    }
}
