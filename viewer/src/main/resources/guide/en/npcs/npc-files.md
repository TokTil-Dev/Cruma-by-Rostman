---
title: NPC files
kind: reference
summary: The files of the server and of the client that the NPCs panel reads, and the parts of npcdata it edits.
tool: npcs
related: npcs/what-is-npc-data, npcs/edit-an-npc, terms/npcdata, terms/npcgrp
---
The **NPCs** panel edits one file of the server, `npcdata.txt`, and reads the others for names, icons and checks. A
save changes only the values you edited, and keeps the encoding, the line endings and every other byte.

## Files of the server

| File | What the panel takes from it |
|---|---|
| `npcdata.txt` | Every NPC: one line each, with its kind, id, name and settings |
| `itemdata.txt` | The names of items, or `item_pch.txt` when it is missing |
| `skilldata.txt` | The names of [skills](guide:terms/skill), or `skill_pch.txt` when it is missing |
| `npc_pch.txt` | The number the compiled AI knows each NPC by. A new NPC gets a line, a deleted one loses its line |
| `npcpos.txt` | Where each NPC spawns, through **NPC Spawns** |

## Files of the client

| File | What it gives |
|---|---|
| `npcgrp.dat` | The mesh, textures, pawn class and size of each NPC, by id |
| `NpcName-e.dat` | The names and titles players see |
| `ItemName-e.dat`, `SkillName-e.dat` | The names and descriptions of items and skills |
| `Skillgrp.dat` | The icons of skills |

## Parts of an NPC's line

### On the Drop tab

- `additional_make_multi_list`: the [drop groups](guide:terms/drop-group), each a chance and its items with their shares.
- `corpse_make_list`: what Spoil and Sweep give, each item with its chance.
- `ex_item_drop_list`: herbs, in groups like the drop.
- `additional_make_list`: an older drop, where each item is rolled on its own.

### On the other tabs

- `skill_list`, on **Skills**: the skills it has, mostly passive, such as its race.
- `npc_ai`, on **AI**: the AI class the server runs for it, and the settings that class reads.
- `level`, `acquire_exp_rate` and `acquire_sp`, on **Stats**: its level, and what a kill gives.
- `collision_radius` and `collision_height`, on **Stats**: the size of its collision cylinder, in world units.

Every setting, known or not, is also under **All**. A kill gives experience = level² × `acquire_exp_rate`, before the
server shares it and applies its own rates.
