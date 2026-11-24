package com.rost.cruma.core.spawn;

import com.rost.cruma.core.spawn.ArmorGrpTable.Armor;
import com.rost.cruma.core.spawn.ArmorGrpTable.Look;
import com.rost.cruma.core.spawn.NpcDataTable.Npc;
import com.rost.cruma.core.spawn.NpcGrpParts.Parts;
import com.rost.cruma.core.spawn.WeaponGrpTable.Weapon;
import com.rost.cruma.core.world.FigureBodies;
import com.rost.cruma.core.world.HairRows;
import com.rost.cruma.core.world.PlayerFigures;
import com.rost.cruma.core.world.UnrealLoader.HairCover;
import com.rost.cruma.core.world.UnrealLoader.HeldItem;
import com.rost.cruma.core.world.UnrealLoader.Outfit;
import com.rost.cruma.core.world.UnrealLoader.WornItem;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;
import org.jetbrains.annotations.Nullable;

public final class PlayerEquipment {
    public static final String RIGHT_HAND = "Weapon_R_Bone";
    public static final String LEFT_HAND = "Weapon_L_Bone";
    public static final String SHIELD = "Shield_L_Bone";
    private static final Grip PLAYER = new Grip(RIGHT_HAND, LEFT_HAND, SHIELD, 1);

    private PlayerEquipment() {}

    public record Equipment(List<HeldItem> held, String stance) {}

    public record Tables(
            ItemNames items,
            WeaponGrpTable weapons,
            ArmorGrpTable armors,
            NpcGrpParts parts,
            Waits waits,
            FigureBodies bodies) {
        public static final Tables EMPTY =
                new Tables(ItemNames.empty(), WeaponGrpTable.empty(), ArmorGrpTable.empty(), NpcGrpParts.empty());

        public Tables(ItemNames items, WeaponGrpTable weapons, ArmorGrpTable armors, NpcGrpParts parts) {
            this(items, weapons, armors, parts, Waits.NONE);
        }

        public Tables(ItemNames items, WeaponGrpTable weapons, ArmorGrpTable armors, NpcGrpParts parts, Waits waits) {
            this(items, weapons, armors, parts, waits, FigureBodies.empty());
        }
    }

    public interface Waits {
        Waits NONE = (_, _, _) -> null;

        @Nullable
        String getWait(String figure, int handness, boolean shield);
    }

    public record Grip(String right, String left, String shield, float scale) {}

    public static Outfit buildOutfit(Npc npc, String figure, Tables tables) {
        return buildOutfit(tables.parts().getParts(npc.id()), npc, figure, tables, 0, 0);
    }

    public static Outfit buildOutfit(Npc npc, String figure, Tables tables, int rightHand, int leftHand) {
        return buildOutfit(tables.parts().getParts(npc.id()), npc, figure, tables, rightHand, leftHand);
    }

    public static Outfit buildOutfit(Parts parts, String figure, Tables tables) {
        return buildOutfit(parts, null, figure, tables, 0, 0);
    }

    public static @Nullable Outfit buildHands(Npc npc, int rightHand, int leftHand, Grip grip, Tables tables) {
        var right = hand(npc.rightHand(), rightHand, tables);
        var left = hand(npc.leftHand(), leftHand, tables);
        var held = of(drawn(right), drawn(left), grip).held();
        return held.isEmpty() ? null : new Outfit(List.of(), Set.of(), 0, HairCover.NONE, held, null, null);
    }

    public static Map<Slot, List<Choice>> findChoices(
            Tables tables, String figure, IntFunction<@Nullable String> names) {
        var result = new EnumMap<Slot, List<Choice>>(Slot.class);
        for (var slot : Slot.values()) {
            result.put(slot, new ArrayList<>());
        }
        for (var armor : tables.armors().getAll()) {
            var look = armor.findDrawnLook(figure);
            if (look == null) {
                continue;
            }
            var slots = bodySlots(armor, look, figure, tables.bodies());
            var slot = slots.contains("u")
                            || armor.bodyPart() == ArmorGrpTable.ONE_PIECE
                            || armor.bodyPart() == ArmorGrpTable.ALL_DRESS
                    ? Slot.CHEST
                    : slots.contains("l")
                            ? Slot.LEGS
                            : slots.contains("g") ? Slot.GLOVES : slots.contains("b") ? Slot.BOOTS : Slot.ACCESSORY;
            result.get(slot).add(new Choice(armor.id(), name(armor.id(), names)));
        }
        for (var weapon : tables.weapons().getAll()) {
            if (weapon.meshes().isEmpty()
                    || weapon.meshes().getFirst().isBlank()
                    || weapon.meshes().getFirst().equalsIgnoreCase("None")) {
                continue;
            }
            var choice = new Choice(weapon.id(), name(weapon.id(), names));
            result.get(Slot.RIGHT_HAND).add(choice);
            result.get(Slot.LEFT_HAND).add(choice);
        }
        for (var list : result.values()) {
            list.sort(Comparator.comparing(Choice::name, String.CASE_INSENSITIVE_ORDER)
                    .thenComparingInt(Choice::id));
        }
        var copy = new EnumMap<Slot, List<Choice>>(Slot.class);
        result.forEach((slot, list) -> copy.put(slot, List.copyOf(list)));
        return copy;
    }

    public static Parts toParts(Map<Slot, Integer> chosen, int hairStyle) {
        return new Parts(
                0,
                chosen.getOrDefault(Slot.CHEST, 0),
                chosen.getOrDefault(Slot.LEGS, 0),
                chosen.getOrDefault(Slot.GLOVES, 0),
                chosen.getOrDefault(Slot.BOOTS, 0),
                0,
                chosen.getOrDefault(Slot.ACCESSORY, 0),
                hairStyle,
                chosen.getOrDefault(Slot.RIGHT_HAND, 0),
                chosen.getOrDefault(Slot.LEFT_HAND, 0));
    }

    public static Equipment of(Npc npc, ItemNames items, WeaponGrpTable weapons) {
        var tables = new Tables(items, weapons, ArmorGrpTable.empty(), NpcGrpParts.empty());
        return of(findWeapon(npc.rightHand(), tables), findWeapon(npc.leftHand(), tables));
    }

    static String stance(int handness) {
        return switch (handness) {
            case 2 -> "wait_2HS";
            case 3, 7 -> "wait_Dual";
            case 4 -> "wait_Pole";
            case 5 -> "wait_Bow";
            default -> "wait_1HS";
        };
    }

    public static String slotOf(String mesh) {
        var under = mesh.lastIndexOf('_');
        return under < 0 ? "" : mesh.substring(under + 1).toLowerCase(Locale.ROOT);
    }

    static List<String> textureFor(Weapon weapon, int mesh) {
        return textureFor(weapon.textures(), mesh, weapon.meshes().size());
    }

    static List<String> textureFor(List<String> textures, int mesh, int meshes) {
        if (meshes > 1 && textures.size() % meshes == 0) {
            var share = textures.size() / meshes;
            return List.copyOf(textures.subList(mesh * share, (mesh + 1) * share));
        }
        return textures;
    }

    private static int accessoryStyleOf(Look look) {
        for (var names : List.of(look.meshes(), look.addMeshes())) {
            for (var at = names.size() - 1; at >= 0; at--) {
                var name = names.get(at);
                if (!name.isBlank() && !name.equalsIgnoreCase("None")) {
                    return HairRows.parseStyle(name);
                }
            }
        }
        return 0;
    }

    private static Outfit buildOutfit(
            @Nullable Parts parts, @Nullable Npc npc, String figure, Tables tables, int rightHand, int leftHand) {
        var worn = new ArrayList<WornItem>();
        var covered = new HashSet<String>();
        var fallback = HairCover.Fallback.NONE;
        var accessoryStyle = 0;
        var hiddenByArmor = false;
        var hairStyle = 0;
        Weapon right;
        Weapon left;
        if (parts != null) {
            for (var item : new int[] {
                parts.chest(), parts.legs(), parts.gloves(), parts.feet(), parts.back(), parts.hairAccessory()
            }) {
                var hairAccessory = item == parts.hairAccessory();
                if (hairAccessory && hiddenByArmor) {
                    continue;
                }
                var armor = item > 0 ? tables.armors().getArmor(item) : null;
                var look = armor == null
                        ? null
                        : tables.bodies().findWholeBody(figure) != null
                                ? armor.findDrawnLook(figure)
                                : armor.look(figure);
                if (look == null) {
                    continue;
                }
                for (var mesh = 0; mesh < look.meshes().size(); mesh++) {
                    var name = look.meshes().get(mesh);
                    if (isWorn(worn, name)) {
                        covered.add(slotOf(name));
                        continue;
                    }
                    worn.add(new WornItem(
                            name,
                            textureFor(look.textures(), mesh, look.meshes().size()),
                            hairAccessory));
                    covered.add(slotOf(name));
                }
                for (var mesh = 0; mesh < look.addMeshes().size(); mesh++) {
                    var name = look.addMeshes().get(mesh);
                    if (!name.isBlank() && !name.equalsIgnoreCase("None") && !isWorn(worn, name)) {
                        worn.add(new WornItem(
                                name,
                                textureFor(
                                        look.addTextures(),
                                        mesh,
                                        look.addMeshes().size()),
                                hairAccessory));
                    }
                }
                covered.addAll(coveredBy(armor));
                if (tables.bodies().findWholeBody(figure) != null
                        && bodySlots(armor, look, figure, tables.bodies()).contains("u")) {
                    covered.addAll(List.of("u", "l", "g", "b"));
                }
                var hideMask = armor.getHideMask(figure);
                if (hairAccessory) {
                    accessoryStyle = armor.bodyPart() == ArmorGrpTable.BACK ? 0 : accessoryStyleOf(look);
                } else if ((hideMask & (ArmorGrpTable.HIDE_HAIR | ArmorGrpTable.HIDE_HAIR_ACCESSORY)) != 0) {
                    hiddenByArmor = true;
                }
                if (hiddenByArmor || (hideMask & ArmorGrpTable.HIDE_HAIR) != 0) {
                    fallback = HairCover.Fallback.ALL;
                } else if (armor.bodyPart() == ArmorGrpTable.HAIR_ALL && fallback == HairCover.Fallback.NONE) {
                    fallback = HairCover.Fallback.FRONT;
                }
            }
            addMount(worn, figure, tables);
            hairStyle = Math.max(parts.hairStyle(), 0);
            right = parts.rightHand() > 0 ? tables.weapons().getWeapon(parts.rightHand()) : null;
            left = parts.leftHand() > 0 ? tables.weapons().getWeapon(parts.leftHand()) : null;
        } else if (npc != null) {
            right = hand(npc.rightHand(), rightHand, tables);
            left = hand(npc.leftHand(), leftHand, tables);
        } else {
            right = null;
            left = null;
        }
        var equipment = of(right, left);
        var handness = right == null || right.meshes().isEmpty() ? 0 : right.handness();
        var shield = npc == null && left != null && !left.meshes().isEmpty() && left.handness() == 0;
        return new Outfit(
                List.copyOf(worn),
                Set.copyOf(covered),
                hairStyle,
                new HairCover(accessoryStyle, hiddenByArmor, fallback),
                equipment.held(),
                equipment.stance(),
                tables.waits().getWait(PlayerFigures.getPawnClassName(figure), handness, shield),
                figure);
    }

    public enum Slot {
        CHEST("Chest"),
        LEGS("Legs"),
        GLOVES("Gloves"),
        BOOTS("Boots"),
        ACCESSORY("Head or back"),
        RIGHT_HAND("Right hand"),
        LEFT_HAND("Left hand");

        private final String label;

        Slot(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    public record Choice(int id, String name) {}

    private static Set<String> bodySlots(Armor armor, Look look, String figure, FigureBodies bodies) {
        var slots = new HashSet<String>();
        for (var mesh : look.meshes()) {
            slots.add(slotOf(mesh));
        }
        if (bodies.findWholeBody(figure) == null || containsBodySlot(slots)) {
            return slots;
        }
        for (var name : ArmorGrpTable.FIGURES) {
            var other = armor.look(name);
            if (other == null) {
                continue;
            }
            var otherSlots = new HashSet<String>();
            for (var mesh : other.meshes()) {
                otherSlots.add(slotOf(mesh));
            }
            if (containsBodySlot(otherSlots)) {
                return otherSlots;
            }
        }
        return slots;
    }

    private static boolean containsBodySlot(Set<String> slots) {
        return slots.contains("u") || slots.contains("l") || slots.contains("g") || slots.contains("b");
    }

    private static String name(int id, IntFunction<@Nullable String> names) {
        var name = names.apply(id);
        return name == null || name.isBlank() ? "Item " + id : name;
    }

    private static Equipment of(Weapon right, Weapon left) {
        return of(right, left, PLAYER);
    }

    private static Equipment of(@Nullable Weapon right, @Nullable Weapon left, Grip grip) {
        var held = new ArrayList<HeldItem>();
        var stance = "wait_Hand";
        if (right != null && !right.meshes().isEmpty()) {
            stance = stance(right.handness());
            var bone = right.handness() == 5 ? grip.left() : grip.right();
            held.add(new HeldItem(right.meshes().getFirst(), textureFor(right, 0), bone, grip.scale()));
            if (right.meshes().size() > 1) {
                held.add(new HeldItem(right.meshes().get(1), textureFor(right, 1), grip.left(), grip.scale()));
            }
        }
        if (left != null && !left.meshes().isEmpty()) {
            var shield = left.handness() == 0;
            held.add(new HeldItem(
                    left.meshes().getFirst(), textureFor(left, 0), shield ? grip.shield() : grip.left(), grip.scale()));
            if (shield && "wait_1HS".equals(stance)) {
                stance = "wait_shield";
            }
        }
        return new Equipment(List.copyOf(held), stance);
    }

    private static void addMount(List<WornItem> worn, String figure, Tables tables) {
        var made = PlayerFigures.findClassFigure(figure);
        if (made == null || made.mount().isEmpty()) {
            return;
        }
        for (var item : worn) {
            if (item.mesh().toLowerCase(Locale.ROOT).contains("_ride_")) {
                return;
            }
        }
        for (var armor : tables.armors().getAll()) {
            var look = armor.look(figure);
            if (look == null) {
                continue;
            }
            for (var index = 0; index < look.addMeshes().size(); index++) {
                var mesh = look.addMeshes().get(index);
                if (mesh.toLowerCase(Locale.ROOT).endsWith("." + made.mount().toLowerCase(Locale.ROOT))) {
                    worn.add(new WornItem(
                            mesh,
                            textureFor(
                                    look.addTextures(), index, look.addMeshes().size()),
                            false));
                    return;
                }
            }
        }
    }

    private static List<String> coveredBy(Armor armor) {
        return switch (armor.bodyPart()) {
            case ArmorGrpTable.ONE_PIECE -> List.of("u", "l");
            case ArmorGrpTable.ALL_DRESS -> List.of("u", "l", "g", "b");
            default -> List.of();
        };
    }

    private static Weapon findWeapon(String name, Tables tables) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        var id = tables.items().getId(name);
        return id < 0 ? null : tables.weapons().getWeapon(id);
    }

    private static @Nullable Weapon hand(String name, int item, Tables tables) {
        var named = findWeapon(name, tables);
        return named != null ? named : tables.weapons().getWeapon(item);
    }

    private static @Nullable Weapon drawn(@Nullable Weapon weapon) {
        return weapon == null
                        || weapon.meshes().isEmpty()
                        || weapon.meshes().getFirst().isBlank()
                        || weapon.meshes().getFirst().equalsIgnoreCase("None")
                ? null
                : weapon;
    }

    private static boolean isWorn(List<WornItem> worn, String mesh) {
        return worn.stream().anyMatch(item -> item.mesh().equalsIgnoreCase(mesh));
    }
}
