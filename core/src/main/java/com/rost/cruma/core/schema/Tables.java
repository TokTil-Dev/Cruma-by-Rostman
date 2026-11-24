package com.rost.cruma.core.schema;

import static com.rost.cruma.core.schema.ValueKind.GROUPS;
import static com.rost.cruma.core.schema.ValueKind.NUMBER;
import static com.rost.cruma.core.schema.ValueKind.NUMBERS;
import static com.rost.cruma.core.schema.ValueKind.STRUCT;
import static com.rost.cruma.core.schema.ValueKind.TEXT;
import static com.rost.cruma.core.schema.ValueKind.TEXTS;
import static com.rost.cruma.core.schema.ValueKind.WHOLE;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

public final class Tables {
    private static final String FIGURES_OF_ESSENCE =
            "Cruma takes this table only for the figures of Essence, which this client does not have.";

    private static final String HAIR_ROWS_ONLY =
            "No schema reads this table, and Cruma needs it here only for the hair under a hat, which then follows"
                    + " Cruma's own rule.";

    public static final TableSpec GAME_DATA_NAMES = new TableSpec(
            "L2GameDataName",
            "L2GameDataName.dat",
            false,
            FieldKinds.of(TEXT, "text"),
            FieldKinds.none(),
            "No table whose texts are numbers of this one can be read, which is every table of P152 and later.",
            null);

    public static final TableSpec ENTER_EVENTS = new TableSpec(
            "EnterEventgrp",
            "EnterEventgrp.dat",
            false,
            FieldKinds.of(WHOLE, "npc")
                    .and(TEXT, "sound")
                    .and(NUMBER, "volume", "radius")
                    .and(WHOLE, "rise")
                    .and(TEXT, "effect", "anim"),
            FieldKinds.none(),
            "NPCs come into the world with no sound, no effect, no animation and no rise.",
            null);

    public static final TableSpec SKILLS = new TableSpec(
            "Skillgrp",
            "Skillgrp.dat",
            false,
            FieldKinds.of(WHOLE, "id", "level"),
            FieldKinds.of(TEXT, "icon")
                    .and(NUMBER, "hit")
                    .and(WHOLE, "kind", "range", "style")
                    .and(TEXT, "anim")
                    .and(TEXTS, "anim", "anims")
                    .and(TEXT, "effect"),
            "Skills have no icons, and a skill casts with no animation and no effect.",
            null);

    public static final TableSpec SKILL_NAMES = new TableSpec(
            "SkillName",
            "SkillName.dat",
            true,
            FieldKinds.of(WHOLE, "id", "level").and(TEXT, "name"),
            FieldKinds.of(TEXT, "desc", "values"),
            "Skills have no names and no descriptions.",
            null);

    public static final TableSpec NPC_NAMES = new TableSpec(
            "NpcName",
            "NpcName.dat",
            true,
            FieldKinds.of(WHOLE, "id").and(TEXT, "name", "title"),
            FieldKinds.none(),
            "NPCs have no names and no titles.",
            null);

    public static final TableSpec NPC_SKILL_ANIMATIONS = new TableSpec(
            "MobSkillAnimgrp",
            "MobSkillAnimgrp.dat",
            false,
            FieldKinds.of(WHOLE, "npc", "skill").and(TEXT, "anim"),
            FieldKinds.none(),
            "NPCs play no sequence of their own for a skill, so most skills of monsters play no animation.",
            null);

    public static final TableSpec COMBOS = new TableSpec(
            "AnimationCombo",
            "AnimationCombo.dat",
            false,
            FieldKinds.of(TEXT, "name", "first", "second", "third").and(WHOLE, "loop"),
            FieldKinds.none(),
            "Skills play their animations with the parts that High Five builds in.",
            null);

    public static final TableSpec SKILL_SOUNDS = new TableSpec(
            "SkillSoundgrp",
            "SkillSoundgrp.dat",
            false,
            soundsOfSlots(),
            FieldKinds.of(NUMBER, "castDelay1", "shotDelay1", "hitDelay1", "castDelay2", "shotDelay2", "hitDelay2")
                    .and(NUMBER, "castDelay3", "shotDelay3", "hitDelay3")
                    .and(TEXTS, "castVoices", "shotVoices")
                    .and(NUMBER, "voiceVolume", "voiceRadius"),
            "Skills cast with no sounds and no voices.",
            null);

    public static final TableSpec SKILL_SOUND_SOURCES = new TableSpec(
            "SkillSoundSource",
            "SkillSoundSource.dat",
            false,
            FieldKinds.of(
                    WHOLE,
                    "id",
                    "castSource1",
                    "shotSource1",
                    "hitSource1",
                    "castSource2",
                    "shotSource2",
                    "hitSource2",
                    "castSource3",
                    "shotSource3",
                    "hitSource3"),
            FieldKinds.none(),
            "Every cast and shot sound of a skill plays on the caster, and every hit sound on the target.",
            null);

    public static final TableSpec POST_EFFECTS = new TableSpec(
            "PostEffectData",
            "PostEffectData.dat",
            false,
            FieldKinds.of(WHOLE, "id", "type", "mode")
                    .and(NUMBER, "seconds")
                    .and(WHOLE, "keepsChroma")
                    .and(NUMBERS, "first", "second"),
            FieldKinds.of(TEXT, "name"),
            "Cut scenes play with no motion blur and no changes of colour.",
            null);

    public static final TableSpec SYSTEM_MESSAGES = new TableSpec(
            "SystemMsg",
            "SystemMsg.dat",
            true,
            FieldKinds.of(WHOLE, "id").and(TEXT, "message"),
            FieldKinds.none(),
            "Cut scenes show none of their lines of text.",
            null);

    public static final TableSpec MUSIC = new TableSpec(
            "MusicInfo",
            "MusicInfo.dat",
            false,
            FieldKinds.of(WHOLE, "id").and(TEXTS, "tracks"),
            FieldKinds.none(),
            "The world and the menu play no music.",
            null);

    public static final TableSpec PAWN_ANIMATIONS = new TableSpec(
            "PawnAnimData",
            "PawnAnimData.dat",
            false,
            FieldKinds.of(TEXT, "class")
                    .and(GROUPS, "anims")
                    .and(WHOLE, "key", "index")
                    .and(TEXT, "anim"),
            FieldKinds.none(),
            "NPCs play no social of the server, and their skills and deaths play no sequences of their own.",
            null);

    public static final TableSpec NPC_GRP = new TableSpec(
            "Npcgrp",
            "npcgrp.dat",
            false,
            FieldKinds.of(WHOLE, "id").and(TEXT, "mesh").and(TEXTS, "textures"),
            FieldKinds.of(TEXT, "class")
                    .and(NUMBER, "drawScale")
                    .and(WHOLE, "rightHand", "leftHand", "walkSpeed", "runSpeed"),
            "NPCs are drawn without their models.",
            rows -> {
                for (var row : rows) {
                    var problem = findMeshProblem(row.get("mesh"));
                    if (problem != null) {
                        return problem;
                    }
                }
                return null;
            });

    public static final TableSpec WEAPONS = new TableSpec(
            "Weapongrp",
            "Weapongrp.dat",
            false,
            FieldKinds.of(WHOLE, "id", "handness").and(TEXT, "icon"),
            FieldKinds.of(TEXTS, "meshes", "textures").and(WHOLE, "res"),
            "NPCs hold no weapons, and weapons have no icons.",
            rows -> {
                for (var row : rows) {
                    if (!row.containsKey("meshes") && !row.containsKey("res")) {
                        return "the rows have no meshes and no res, the row of WeaponGrp_Res.dat that names them";
                    }
                    var problem = findMeshesProblem(row.get("meshes"));
                    if (problem != null) {
                        return problem;
                    }
                }
                return null;
            });

    public static final TableSpec WEAPON_RESOURCES = new TableSpec(
            "WeaponGrp_Res",
            "WeaponGrp_Res.dat",
            false,
            FieldKinds.of(WHOLE, "id").and(GROUPS, "parts").and(TEXT, "mesh").and(TEXTS, "textures"),
            FieldKinds.none(),
            "In a client that keeps the meshes of its weapons in this table, NPCs hold no weapons.",
            rows -> {
                for (var row : rows) {
                    if (row.get("parts") instanceof List<?> parts) {
                        for (var part : parts) {
                            var problem = part instanceof Map<?, ?> fields ? findMeshProblem(fields.get("mesh")) : null;
                            if (problem != null) {
                                return problem;
                            }
                        }
                    }
                }
                return null;
            });

    private static final List<String> FIGURES = List.of(
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

    public static final TableSpec ARMORS = new TableSpec(
            "Armorgrp",
            "Armorgrp.dat",
            false,
            FieldKinds.of(WHOLE, "id", "bodyPart").and(TEXT, "icon"),
            FieldKinds.of(STRUCT, FIGURES.toArray(String[]::new))
                    .and(TEXTS, "meshes", "textures", "addMeshes")
                    .and(GROUPS, "addMeshes")
                    .and(TEXT, "mesh")
                    .and(GROUPS, "addValues")
                    .and(WHOLE, "first", "second")
                    .and(TEXTS, "addTextures")
                    .and(TEXT, "addTexture")
                    .and(WHOLE, "hideMask", "res"),
            "Characters and NPCs wear no armor, and armor has no icons.",
            rows -> {
                for (var row : rows) {
                    for (var value : row.values()) {
                        if (value instanceof Map<?, ?> look) {
                            var problem = findMeshesProblem(look.get("meshes"));
                            if (problem != null) {
                                return problem;
                            }
                        }
                    }
                }
                return null;
            });

    public static final TableSpec ARMOR_RESOURCES = new TableSpec(
            "ArmorGrp_Res",
            "ArmorGrp_Res.dat",
            false,
            FieldKinds.of(WHOLE, "id", "figure").and(TEXTS, "meshes", "textures"),
            FieldKinds.of(TEXTS, "addMeshes", "addTextures")
                    .and(GROUPS, "addMeshes", "addValues")
                    .and(TEXT, "mesh", "addTexture")
                    .and(WHOLE, "first", "second", "hideMask"),
            "In a client that keeps the looks of its armor in this table, characters and NPCs wear no armor.",
            rows -> {
                for (var row : rows) {
                    var problem = findMeshesProblem(row.get("meshes"));
                    if (problem != null) {
                        return problem;
                    }
                }
                return null;
            });

    public static final TableSpec ETC_ITEMS = new TableSpec(
            "EtcItemgrp",
            "EtcItemgrp.dat",
            false,
            FieldKinds.of(WHOLE, "id").and(TEXT, "icon"),
            FieldKinds.none(),
            "Items that are neither weapons nor armor, such as potions and scrolls, have no icons.",
            null);

    public static final TableSpec ITEM_NAMES = new TableSpec(
            "ItemName",
            "ItemName.dat",
            true,
            FieldKinds.of(WHOLE, "id").and(TEXT, "name"),
            FieldKinds.of(TEXT, "add", "desc"),
            "Items have no names and no descriptions.",
            null);

    public static final TableSpec NPC_GRP_PARTS = new TableSpec(
            "AdditionalNpcGrpParts",
            "AdditionalNpcGrpParts.dat",
            false,
            FieldKinds.of(
                    WHOLE,
                    "npcId",
                    "chest",
                    "legs",
                    "gloves",
                    "feet",
                    "back",
                    "hairAccessory",
                    "hairStyle",
                    "rightHand",
                    "leftHand"),
            FieldKinds.none(),
            "NPCs that look like players wear none of their items.",
            null);

    public static final TableSpec CHARACTER_CLASSES = new TableSpec(
                    "CharCreategrp",
                    "CharCreategrp.dat",
                    false,
                    FieldKinds.of(WHOLE, "slot"),
                    FieldKinds.none(),
                    "No class figure of Essence, such as the Death Knight, can be made as a character.",
                    null)
            .onlyWith("CharHeadExgrp.dat", FIGURES_OF_ESSENCE);

    public static final TableSpec CLASS_HEADS = new TableSpec(
            "CharHeadExgrp",
            "CharHeadExgrp.dat",
            false,
            FieldKinds.of(TEXT, "class")
                    .and(GROUPS, "rows", "styles")
                    .and(STRUCT, "back")
                    .and(TEXT, "mesh", "texture")
                    .and(TEXTS, "faces", "faceTextures"),
            FieldKinds.none(),
            "The class figures of Essence wear the hair and the face of their race.",
            null);

    public static final TableSpec CHARACTERS = new TableSpec(
                    "Chargrp",
                    "Chargrp.dat",
                    false,
                    FieldKinds.of(TEXT, "class")
                            .and(GROUPS, "rows", "styles")
                            .and(STRUCT, "back", "gloves", "chest", "legs", "feet")
                            .and(TEXT, "mesh", "texture")
                            .and(TEXTS, "faces", "meshes", "textures"),
                    FieldKinds.of(STRUCT, "front"),
                    "A hair accessory hides the hair by Cruma's own rule, not by the rows of hair of the client. In"
                            + " Essence the High Elves and the Crow are drawn without a body, the female High Elf"
                            + " without hair, and the Sylph and the Ertheia play all the sequences of their animation.",
                    null)
            .neededOnlyWith("CharHeadExgrp.dat", HAIR_ROWS_ONLY);

    public static final List<TableSpec> ALL = List.of(
            GAME_DATA_NAMES,
            ENTER_EVENTS,
            NPC_GRP,
            NPC_GRP_PARTS,
            WEAPONS,
            WEAPON_RESOURCES,
            ARMORS,
            ARMOR_RESOURCES,
            ETC_ITEMS,
            ITEM_NAMES,
            SKILLS,
            SKILL_NAMES,
            NPC_NAMES,
            NPC_SKILL_ANIMATIONS,
            COMBOS,
            SKILL_SOUNDS,
            SKILL_SOUND_SOURCES,
            POST_EFFECTS,
            SYSTEM_MESSAGES,
            MUSIC,
            PAWN_ANIMATIONS,
            CHARACTER_CLASSES,
            CLASS_HEADS,
            CHARACTERS);

    private static final Pattern PACKAGE_NAME = Pattern.compile("[^\\s.]+(\\.[^\\s.]+)+");

    private Tables() {}

    private static @Nullable String findMeshProblem(@Nullable Object mesh) {
        if (!(mesh instanceof String text)) {
            return null;
        }
        var name = text.strip();
        if (name.length() >= 2 && name.startsWith("[") && name.endsWith("]")) {
            name = name.substring(1, name.length() - 1).strip();
        }
        if (name.isEmpty()
                || name.equalsIgnoreCase("None")
                || PACKAGE_NAME.matcher(name).matches()) {
            return null;
        }
        return "the mesh \"" + text + "\" is not Package.Name";
    }

    private static @Nullable String findMeshesProblem(@Nullable Object meshes) {
        if (meshes instanceof List<?> list) {
            for (var mesh : list) {
                var problem = findMeshProblem(mesh);
                if (problem != null) {
                    return problem;
                }
            }
        }
        return null;
    }

    private static FieldKinds soundsOfSlots() {
        var kinds = FieldKinds.of(WHOLE, "id", "level");
        for (var slot = 1; slot <= 3; slot++) {
            kinds.and(TEXT, "cast" + slot, "shot" + slot, "hit" + slot)
                    .and(
                            NUMBER,
                            "castVolume" + slot,
                            "castRadius" + slot,
                            "shotVolume" + slot,
                            "shotRadius" + slot,
                            "hitVolume" + slot,
                            "hitRadius" + slot);
        }
        return kinds;
    }
}
