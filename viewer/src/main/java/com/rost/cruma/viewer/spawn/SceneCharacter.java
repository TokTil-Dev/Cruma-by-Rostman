package com.rost.cruma.viewer.spawn;

import com.rost.cruma.core.export.NpcExport.Race;
import com.rost.cruma.core.spawn.NpcGrpParts.Parts;
import com.rost.cruma.core.spawn.PlayerEquipment;
import com.rost.cruma.core.spawn.PlayerEquipment.Slot;
import com.rost.cruma.core.spawn.PlayerEquipment.Tables;
import com.rost.cruma.core.world.GameScenes.Action;
import com.rost.cruma.core.world.GameScenes.Actor;
import com.rost.cruma.core.world.GameScenes.Visual;
import com.rost.cruma.core.world.PlayerFigures;
import com.rost.cruma.core.world.SceneFields;
import com.rost.cruma.core.world.SceneOrders;
import com.rost.cruma.core.world.UnrealLoader.Outfit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public record SceneCharacter(String face, Parts parts, String figure) {
    private static final int SHIRT = 1146;
    private static final int PANTS = 1147;
    private static final int NOTHING = 0;

    public static final SceneCharacter STAND_IN = new SceneCharacter(
            "Fighter.MFighter_m000_f",
            new Parts(NOTHING, SHIRT, PANTS, NOTHING, NOTHING, NOTHING, NOTHING, NOTHING, NOTHING, NOTHING));

    public static final String NO_PLAYERS =
            "The scenes of this client hold no player characters. The Viewer plays this one, and a file of this client"
                    + " leaves it out.";

    private static final int FEMALE = 1;
    private static final int SLOTS = 29;
    private static final int BEFORE_DRAWN = 19;
    private static final int HUMAN_MAGE = 6;
    private static final int ORC_MAGE = 7;
    private static final int ORC = 3;
    private static final int SYLPH = 30;
    private static final int HIGH_ELF = 31;

    public SceneCharacter(String face, Parts parts) {
        this(face, parts, new Race("", face).figure());
    }

    public static SceneCharacter of(Actor order) {
        if (order.self() || order.action() != Action.SPAWN) {
            return STAND_IN;
        }
        var female = order.sex() == FEMALE;
        return switch (order.race()) {
            case SYLPH ->
                new SceneCharacter(
                        female ? "Ertheia.FErtheia_m000_f" : "Ertheia.MErtheia_m000_f",
                        parts(order),
                        female ? "FSylph" : "MSylph");
            case HIGH_ELF ->
                new SceneCharacter(
                        female ? "HighElf.FHighElf_m000_f" : "HighElf.MHighElf_m000_f",
                        parts(order),
                        female ? "FHighElf" : "MHighElf");
            default -> new SceneCharacter(face(order), parts(order));
        };
    }

    public static boolean canHold(Race race) {
        var figure = race.figure();
        return switch (figure.toLowerCase(Locale.ROOT)) {
            case "msylph", "fsylph", "mhighelf", "fhighelf" -> true;
            default -> !PlayerFigures.isEssenceFigure(figure) && PlayerFigures.findClassFigure(figure) == null;
        };
    }

    public static boolean hasSlots(Set<String> pc) {
        return pc.contains("ItemSlot") && !pc.contains("VisualItemList");
    }

    public static boolean hasClothes(Set<String> pc) {
        return pc.isEmpty() || pc.contains("VisualItemList") || pc.contains("ItemSlot");
    }

    public static boolean hasMages(Set<String> pc) {
        return pc.isEmpty() || pc.contains("IsMage") || hasSlots(pc);
    }

    public static Actor order(String face, Map<Slot, Integer> items, boolean slots) {
        return order(face, new Race("", face).figure(), items, slots);
    }

    public static Actor order(String face, String figure, Map<Slot, Integer> items, boolean slots) {
        var visuals = new ArrayList<Visual>(items.size());
        for (var item : items.entrySet()) {
            if (item.getValue() > 0) {
                visuals.add(new Visual(visualType(item.getKey()), item.getValue()));
            }
        }
        visuals.sort(Comparator.comparingInt(Visual::slot));
        var people = face.substring(0, face.indexOf('.'));
        var female = face.charAt(people.length() + 1) == 'F';
        var mage = people.equals("Magic") || people.equals("Shaman");
        var race = race(people, figure);
        if (!slots) {
            return SceneOrders.character(0, 0, 0, 0, 0, race, female ? FEMALE : 0, mage, visuals);
        }
        var numbers = new ArrayList<>(Collections.nCopies(SLOTS, NOTHING));
        for (var visual : visuals) {
            numbers.set(BEFORE_DRAWN + visual.slot(), visual.item());
        }
        if (mage) {
            race = race == ORC ? ORC_MAGE : HUMAN_MAGE;
        }
        var order = SceneOrders.character(0, 0, 0, 0, 0, race, female ? FEMALE : 0, false, List.of());
        return SceneFields.ACTOR.with(order, "itemSlots", numbers);
    }

    public Outfit outfit(Tables tables) {
        return PlayerEquipment.buildOutfit(parts, figure, tables);
    }

    private static String face(Actor order) {
        var slots = !order.itemSlots().isEmpty();
        var people = switch (order.race()) {
            case 1 -> "Elf";
            case 2 -> "DarkElf";
            case ORC -> order.mage() ? "Shaman" : "Orc";
            case 4 -> "Dwarf";
            case 5 -> "Kamael";
            case HUMAN_MAGE -> slots ? "Magic" : "Ertheia";
            case ORC_MAGE -> slots ? "Shaman" : order.mage() ? "Magic" : "Fighter";
            default -> order.mage() ? "Magic" : "Fighter";
        };
        return people + "." + (order.sex() == FEMALE ? "F" : "M") + people + "_m000_f";
    }

    private static int race(String people, String figure) {
        if (figure.equalsIgnoreCase("MSylph") || figure.equalsIgnoreCase("FSylph")) {
            return SYLPH;
        }
        return switch (people) {
            case "HighElf" -> HIGH_ELF;
            case "Elf" -> 1;
            case "DarkElf" -> 2;
            case "Orc", "Shaman" -> ORC;
            case "Dwarf" -> 4;
            case "Kamael" -> 5;
            case "Ertheia" -> 6;
            default -> 0;
        };
    }

    private static int visualType(Slot slot) {
        return switch (slot) {
            case GLOVES -> 1;
            case CHEST -> 2;
            case LEGS -> 3;
            case BOOTS -> 4;
            case ACCESSORY -> 6;
            case RIGHT_HAND -> 8;
            case LEFT_HAND -> 9;
        };
    }

    private static Parts parts(Actor order) {
        var items = new int[10];
        for (var visual : order.visuals()) {
            if (visual.slot() > 0 && visual.slot() < items.length) {
                items[visual.slot()] = visual.item();
            }
        }
        for (var type = 1;
                type < items.length && BEFORE_DRAWN + type < order.itemSlots().size();
                type++) {
            var item = order.itemSlots().get(BEFORE_DRAWN + type);
            if (item > NOTHING) {
                items[type] = item;
            }
        }
        return new Parts(
                NOTHING,
                items[2],
                items[3],
                items[1],
                items[4],
                items[5],
                items[6] != NOTHING ? items[6] : items[7],
                NOTHING,
                items[8],
                items[9]);
    }
}
