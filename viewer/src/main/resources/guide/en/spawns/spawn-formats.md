---
title: Spawn files
kind: reference
summary: The blocks and settings of npcpos.txt that NPC Spawns reads and writes, with their units.
tool: spawns
related: spawns/what-is-a-spawn, spawns/edit-a-spawn, terms/npcpos, terms/npcdata
---
`npcpos.txt` is a text file of an [L2OFF](guide:terms/l2off) server, in UTF-16. Cruma reads it as the server's grammar does and keeps the
settings it does not know. A save writes back only the settings you changed and adds new zones and [makers](guide:terms/maker) at the end,
so comments, columns, the encoding and the line endings stay.

## Files of the script folder

| File | What NPC Spawns takes from it |
|---|---|
| `npcpos.txt` | Zones, makers and their NPC entries |
| `npcdata.txt` | The name, id, kind, level and collision size of each NPC |
| `item_pch.txt` | The names of the items an NPC holds |

## Blocks

| Block | What it is |
|---|---|
| `territory_begin` to `territory_end` | A zone: its name in brackets, then its corners |
| `npcmaker_begin` to `npcmaker_end` | A maker of one zone or more |
| `npcmaker_ex_begin` to `npcmaker_ex_end` | A maker with a name and an AI of its own |
| `npc_begin` to `npc_end`, `npc_ex_begin` to `npc_ex_end` | One NPC entry of a maker |

A corner of a zone is four numbers in braces, apart by semicolons: X, Y, the bottom Z and the top Z. The zone's height
range runs from the lowest bottom of its corners to the highest top.

## A maker

| Setting | What it holds |
|---|---|
| `maximum_npc` | How many of its NPCs the server keeps alive at once, such as `maximum_npc=4` |
| `name` | The maker's own name, on an `npcmaker_ex` maker |
| `ai`, `ai_parameters` | The AI of an `npcmaker_ex` maker and its settings, such as `ai=[default_maker]` |
| `banned_territory` | Zones left out of the maker's zones |
| `spawn_time` | The siege or event in which the maker spawns, such as `siege_warfare_start` |

## An NPC entry

| Setting | What it holds |
|---|---|
| The name in brackets | The NPC as npcdata.txt names it, such as `[goblin]` |
| `pos` | `pos=anywhere` for random in the zone, or X, Y, Z and the heading in braces, apart by semicolons |
| `total` | How many NPCs, such as `total=5` |
| `respawn` | The time until one comes back: a number with `sec`, `min`, `hour` or `day`, or `no` |
| `respawn_rand` | A random part added or taken, such as `respawn_rand=5sec` |

A `pos` can also list several points, each with a percent: the server picks one of them by those weights. The panel
shows such an entry as "Chosen from" the number of its points.

## Units

| Value | Unit |
|---|---|
| X, Y, Z | [World units](guide:terms/world-unit), 32768 a tile. X points east, Y south, Z up |
| Heading | 0 to 65535 for a whole turn. 0 faces east, 16384 south |

A backup takes the name of the file with the date and time of the save, such as
`npcpos.txt.2026-10-13_13-40-05.bak`, next to the file.
