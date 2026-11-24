---
title: Fields Cruma needs
kind: reference
summary: The fields Cruma reads from each of the 24 client tables, their kinds, and what is lost without them.
next: tables/schemas-and-keys
related: tables/schema-language, tables/write-a-schema, tables/client-tables
---
Cruma finds the fields it uses by their names, and keeps only those. A schema may name the other fields as it likes:
those names serve `like` and **Show row**. A schema that lacks a name Cruma needs is not tried, and **Client tables**
says which name is missing.

## Kinds of values

Each field Cruma uses must give the kind of value it reads:

| Kind | Types that give it |
|---|---|
| whole | `byte`, `short`, `ushort`, `int`, `uint`, `compact` |
| number | a whole one, or `long`, `float`, `double` |
| text | `string`, `fstring`, `ansi`, `name` |
| texts | a list of texts |
| numbers | a list of numbers |
| structure | a field of a structure type |
| groups | a list of groups with a name |

A field Cruma needs is read in every row: when one side of an `if` reads it, the other side reads it too. A schema
that breaks this, or gives another kind, is passed over with a reason such as "its field level is a float, and Cruma
needs a whole number".

## The tables

The file names are those of the main world. A table of a world, such as `Npcgrp_Classic.dat` or `Npcgrp_ClassicAden.dat`, uses the same fields.
A table in a language, such as `ItemName-e.dat`, is read in English when the client has it, else in its first
language. "Also" names the fields Cruma takes when a schema has them.

### Names

- `L2GameDataName.dat` needs `text` text. It holds the texts that the `name` fields of the other tables point at, so
  without it no table of P152 and later can be read. Its schema has no `name` field, so it is read first.

### NPCs

- `npcgrp.dat` needs `id` whole, `mesh` text and `textures` texts. Also `class` text, `drawScale` number, and
  `rightHand`, `leftHand`, `walkSpeed`, `runSpeed` whole. Without it, NPCs are drawn without their models.
- `NpcName-e.dat` needs `id` whole, `name` and `title` text. Without it, NPCs have no names and no titles.
- `AdditionalNpcGrpParts.dat` needs `npcId`, `chest`, `legs`, `gloves`, `feet`, `back`, `hairAccessory`,
  `hairStyle`, `rightHand` and `leftHand`, all whole. Without it, NPCs that look like players wear none of their
  items.
- `EnterEventgrp.dat` needs `npc` and `rise` whole, `sound`, `effect` and `anim` text, `volume` and `radius` number.
  Without it, NPCs come into the world with no sound, no effect, no animation and no rise.
- `CharCreategrp.dat` needs `slot` whole: the place of each class a player can make in the world. Without it, no
  class figure of Essence, such as the Death Knight, can be made as a character. Only Essence has a schema for it.
- `CharHeadExgrp.dat` needs `class` text, `rows`, groups of `styles`, groups with the structure `back` of `mesh` and
  `texture` text, and `faces` and `faceTextures` texts. Without it, the class figures of Essence wear the hair and the
  face of their race. Only Essence has a schema for it.
- `Chargrp.dat` needs `class` text, `rows`, groups of `styles`, groups with the structure `back` of `mesh` and
  `texture` text, `faces` texts, and the structures `gloves`, `chest`, `legs` and `feet` with `meshes` and `textures`
  texts. It also takes the structure `front` of `mesh` and `texture` text when the schema has it. The rows of hair
  say which hair a figure wears under a hat or another hair accessory: the two digits after `_m0` in the name of the
  accessory's mesh pick the row. In Essence, a row that names a chest and no other part of the body is a figure of one
  mesh, as the High Elves and the Crow are, and a row whose first face is that of another row wears that row's meshes,
  as the Sylph wear the Ertheia's. Without it, a hair accessory hides the hair by Cruma's own rule: a hat hides the
  hair over the forehead and keeps the rest. In Essence the High Elves and the Crow are then drawn without a body, the
  female High Elf without hair, and the Sylph and the Ertheia play all the sequences of their animation. It has
  schemas from Gracia Final on. Interlude has the table in another layout, without rows of hair, and the table of the
  Classic world of P152 and 162 names other texts than its client holds. Where no schema reads it and the client has no
  `CharHeadExgrp.dat`, the page shows it as not used: no fault.
- `PawnAnimData.dat` needs `class` text and `anims`, groups of `key` and `index` whole and `anim` text. Without it,
  NPCs play no social of the server, their skills and deaths play no sequences of their own, and a character waits as
  Cruma guesses for the weapon it holds, not as its race names it. Each client numbers the `key` in its own way, and
  Cruma tells which way from the rows of the human fighter, `MFighter`.
- `MobSkillAnimgrp.dat` needs `npc` and `skill` whole, `anim` text. Without it, NPCs play no sequence of their own
  for a skill, so most skills of monsters play no animation.

### Items

- `Weapongrp.dat` needs `id` and `handness` whole and `icon` text, and it names the meshes in one of two ways:
  `meshes` and `textures` texts in the row, or `res` whole, the number of a row of `WeaponGrp_Res.dat`, as Essence 563
  does. A schema with neither is passed over. Without it, NPCs hold no weapons, and weapons have no icons.
- `WeaponGrp_Res.dat` needs `id` whole, `parts`, groups with `mesh` text, and `textures` texts. Only Essence 563 and
  later has it, one for all its worlds. Without it, in such a client NPCs hold no weapons, and the weapons keep
  their icons.
- `Armorgrp.dat` needs `id` and `bodyPart` whole, `icon` text. Also `hideMask` whole and a look for each figure, see
  below, or `res` whole, the number of its rows of `ArmorGrp_Res.dat`. Without it, characters and NPCs wear no armor,
  and armor has no icons.
- `ArmorGrp_Res.dat` needs `id` and `figure` whole, `meshes` and `textures` texts. Also the other fields of a look,
  see below, and `hideMask` whole. Only Essence 563 and later has it, one for all its worlds. Without it, in such a
  client characters and NPCs wear no armor, and the pieces keep their icons.
- `EtcItemgrp.dat` needs `id` whole and `icon` text. Without it, items that are neither weapons nor armor, such as
  potions and scrolls, have no icons.
- `ItemName-e.dat` needs `id` whole and `name` text. Also `add` and `desc` text. Without it, items have no names and
  no descriptions.

### Skills

- `Skillgrp.dat` needs `id` and `level` whole. Also `icon` and `effect` text, `hit` number, `kind`, `range` and
  `style` whole, `anim` as a text or texts, and `anims` texts. Without it, skills have no icons, and a skill casts with
  no animation and no effect.
- `SkillName-e.dat` needs `id` and `level` whole, `name` text. Also `desc` and `values` text. Without it, skills have
  no names and no descriptions.
- `AnimationCombo.dat` needs `name`, `first`, `second` and `third` text, `loop` whole. Without it, skills play their
  animations with the parts that High Five builds in.
- `SkillSoundgrp.dat` needs `id` and `level` whole, and for each slot 1 to 3 the sounds `cast1`, `shot1`, `hit1` text
  with their `castVolume1`, `castRadius1`, `shotVolume1`, `shotRadius1`, `hitVolume1` and `hitRadius1` number. Also
  the delays `castDelay1` to `hitDelay3` number, `castVoices` and `shotVoices` texts, `voiceVolume` and `voiceRadius`
  number. Without it, skills cast with no sounds and no voices.
- `SkillSoundSource.dat` needs `id`, `castSource1` to `castSource3`, `shotSource1` to `shotSource3` and
  `hitSource1` to `hitSource3`, all whole. Without it, every cast and shot sound of a skill plays on the caster, and
  every hit sound on the target.

### Cut scenes and music

- `PostEffectData.dat` needs `id`, `type`, `mode` and `keepsChroma` whole, `seconds` number, `first` and `second`
  numbers. Also `name` text. Without it, cut scenes play with no motion blur and no changes of colour.
- `SystemMsg-e.dat` needs `id` whole and `message` text. Without it, cut scenes show none of their lines of text.
- `MusicInfo.dat` needs `id` whole and `tracks` texts. Without it, the world and the menu play no music.

## The looks of armor

A look of `Armorgrp.dat` is a field of a structure type for each figure, with these names: `mfighter`, `ffighter`,
`mdarkelf`, `fdarkelf`, `mdwarf`, `fdwarf`, `melf`, `felf`, `mmagic`, `fmagic`, `morc`, `forc`, `mshaman`,
`fshaman`, `mkamael`, `fkamael`, `mertheia`, `fertheia`, the five of Essence `msylph`, `fsylph`, `mhighelf`,
`fhighelf` and `crow`, and `npc`. A table has the figures of its client, in this order. A look holds:

| Field | Kind |
|---|---|
| `meshes`, `textures` | texts |
| `addMeshes` | texts, or groups whose `mesh` is the text |
| `addTextures` | texts |
| `addValues` | groups of `first` and `second`, whole. Only in a table of `name` fields |
| `addTexture` | text. Only in a table of `name` fields |

`set bodyParts 1` says that the body parts are numbered as Interlude numbers them. `set backPart 24` names the
body part of a cloak, which never changes the hair under a hair accessory.

Essence 563 keeps the looks outside `Armorgrp.dat`. A row of `ArmorGrp_Res.dat` is the look of one figure for the
pieces whose `res` is its `id`: it holds the fields of a look in the row itself, the `figure`, which is the place of
the figure in the list above, from 0 for `mfighter`, and its own `hideMask`. A figure without a row does not wear
the piece.

## Checks of the first rows

`npcgrp.dat`, `Weapongrp.dat`, `Armorgrp.dat`, `WeaponGrp_Res.dat` and `ArmorGrp_Res.dat` are also checked in their
first 50 rows: each mesh is empty, `None` or `Package.Name`, also in square brackets. A schema that reads such a table to its end with other values is passed
over, as its fields stand in the wrong places.
