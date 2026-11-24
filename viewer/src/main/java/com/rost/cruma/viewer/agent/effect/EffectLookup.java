package com.rost.cruma.viewer.agent.effect;

import com.rost.cruma.core.export.NpcExport;
import com.rost.cruma.core.export.NpcExport.Race;
import com.rost.cruma.core.npc.ClientCatalog;
import com.rost.cruma.core.npc.ClientCatalog.Npc;
import com.rost.cruma.core.unreal.EffectClasses.Effect;
import com.rost.cruma.core.unreal.SkillEffects.Skill;
import com.rost.cruma.viewer.agent.ToolProblem;
import com.rost.cruma.viewer.ui.EffectsPanel.Spot;
import com.rost.cruma.viewer.ui.EffectsPanel.Who;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import org.jetbrains.annotations.Nullable;

final class EffectLookup {
    private static final int OFFERED = 8;

    private EffectLookup() {}

    record Found(Skill skill, List<String> others) {}

    static Effect effect(List<Effect> effects, String words) throws ToolProblem {
        var wanted = words.strip().toLowerCase(Locale.ROOT);
        var named = new ArrayList<Effect>();
        for (var effect : effects) {
            if (effect.path().equalsIgnoreCase(wanted)) {
                return effect;
            }
            if (effect.name().equalsIgnoreCase(wanted)) {
                named.add(effect);
            }
        }
        if (!named.isEmpty()) {
            return named.getFirst();
        }
        var partly = effects.stream()
                .filter(effect -> effect.name().toLowerCase(Locale.ROOT).contains(wanted))
                .toList();
        if (partly.size() == 1) {
            return partly.getFirst();
        }
        if (partly.isEmpty()) {
            throw new ToolProblem(
                    "The client has no effect \"" + words + "\". Call assets_find with kind effect to search.");
        }
        throw new ToolProblem(partly.size()
                + " effects hold \""
                + words
                + "\", such as "
                + names(partly, Effect::path)
                + ". Give the full name.");
    }

    static Found skill(List<Skill> skills, Function<Skill, String> names, String words) throws ToolProblem {
        var wanted = words.strip().toLowerCase(Locale.ROOT);
        var named = new ArrayList<Skill>();
        var partly = new ArrayList<Skill>();
        for (var skill : skills) {
            if (Integer.toString(skill.skillId()).equals(wanted)
                    || skill.className().equalsIgnoreCase(wanted)) {
                return new Found(skill, List.of());
            }
            var name = names.apply(skill).toLowerCase(Locale.ROOT);
            if (name.equals(wanted)) {
                named.add(skill);
            } else if (name.contains(wanted)) {
                partly.add(skill);
            }
        }
        var found = named.isEmpty() ? partly : named;
        found.sort(Comparator.comparingInt(Skill::skillId));
        if (found.isEmpty()) {
            throw new ToolProblem("The client plays no skill \"" + words
                    + "\" in its skill package. Call assets_find with kind skill to search.");
        }
        if (named.isEmpty() && found.size() > 1) {
            throw new ToolProblem(found.size()
                    + " skills hold \""
                    + words
                    + "\", such as "
                    + names(found, skill -> skill.skillId() + " " + names.apply(skill))
                    + ". Give the id or the whole name.");
        }
        var others = new ArrayList<String>();
        for (var other : found.subList(1, Math.min(found.size(), OFFERED + 1))) {
            others.add(other.skillId() + " " + other.className());
        }
        return new Found(found.getFirst(), others);
    }

    static Who who(ClientCatalog catalog, String words, String nobody) throws ToolProblem {
        var wanted = words.strip().toLowerCase(Locale.ROOT);
        if (wanted.equals(nobody) || wanted.equals("none")) {
            return Who.NOBODY;
        }
        var race = findRace(words);
        return race != null ? new Who(true, 0, race.figure()) : new Who(true, npc(catalog, words), null);
    }

    static @Nullable Race findRace(String words) {
        var wanted = words.strip().toLowerCase(Locale.ROOT);
        for (var race : NpcExport.RACES) {
            if (simple(race.label()).equals(simple(wanted))) {
                return race;
            }
        }
        return null;
    }

    static Who target(ClientCatalog catalog, String words) throws ToolProblem {
        var wanted = words.strip().toLowerCase(Locale.ROOT);
        if (wanted.equals("self")) {
            return Who.SELF;
        }
        return who(catalog, words, wanted.equals("copy") ? "copy" : "as_caster");
    }

    static int npc(ClientCatalog catalog, String words) throws ToolProblem {
        var wanted = words.strip().toLowerCase(Locale.ROOT);
        if (wanted.matches("\\d+")) {
            var id = Integer.parseInt(wanted);
            if (catalog.getNpc(id) == null) {
                throw new ToolProblem("The client has no NPC " + id + ". Call npc_find to search.");
            }
            return id;
        }
        var named = new ArrayList<Npc>();
        var partly = new ArrayList<Npc>();
        for (var npc : catalog.getNpcs()) {
            var name = npc.name().toLowerCase(Locale.ROOT);
            if (name.equals(wanted)) {
                named.add(npc);
            } else if (!name.isEmpty() && name.contains(wanted)) {
                partly.add(npc);
            }
        }
        var found = named.isEmpty() ? partly : named;
        found.sort(Comparator.comparingInt(Npc::id));
        if (found.isEmpty()) {
            throw new ToolProblem("The client has no NPC \"" + words
                    + "\", and it is no race such as Elf, female. Call npc_find to search.");
        }
        if (named.isEmpty() && found.size() > 1) {
            throw new ToolProblem(found.size()
                    + " NPCs hold \""
                    + words
                    + "\", such as "
                    + names(found, npc -> npc.id() + " " + npc.name())
                    + ". Give the id.");
        }
        return found.getFirst().id();
    }

    static Spot spot(String words) throws ToolProblem {
        var wanted = simple(words);
        for (var spot : Spot.values()) {
            if (simple(spot.name()).equals(wanted)
                    || simple(spot.name().replace("RIGHT", "R").replace("LEFT", "L"))
                            .equals(wanted)) {
                return spot;
            }
        }
        throw new ToolProblem("spot must be feet, chest, head, r_hand or l_hand.");
    }

    private static String simple(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[\\s,_]", "");
    }

    private static <T> String names(List<T> items, Function<T, String> name) {
        var shown = new ArrayList<String>();
        for (var item : items.subList(0, Math.min(items.size(), OFFERED))) {
            shown.add(name.apply(item));
        }
        return String.join(", ", shown);
    }
}
