---
title: Check spawns against geodata
kind: howto
summary: Find NPCs on blocked ground, zones where nothing can spawn, and entries the server cannot read.
tool: spawns
next: spawns/spawn-formats
related: spawns/edit-a-spawn, terms/geodata, geodata/what-is-geodata
---
Find the spawns around you that the server cannot place well, and fix the common ones.

### You need

- A script folder open in **NPC Spawns**.
- A geodata folder open in the **Geodata** panel. The check stands each NPC on the server's
  [geodata](guide:terms/geodata), as the server does.

## Steps

1. Fly the camera over the place you want to check.
2. Close the [maker](guide:terms/maker)'s card, if one is open, to see the overview of the panel.
3. Under **CHECK SPAWNS**, choose how far to look: `2,000`, `5,000` or `10k` units around the camera.
4. Press **Check spawns**. It runs in the background, and **Stop** ends it.
5. Read the findings. They are grouped by kind, and each one has **Fly to** and, for some kinds, a fix.

When it worked and found nothing, a green line says "No problems within" the distance you chose.

## What it finds

| Finding | What it means | Fix |
|---|---|---|
| Stands on blocked or missing geodata | A fixed NPC on a blocked [cell](guide:terms/cell), or more than 128 units over the nearest floor | **Drop to ground** |
| Stands under the geodata floor | A fixed NPC more than 64 units under the floor. Often a room the geodata does not have, such as a shop under a roof | None: check it by eye |
| No walkable cell leads to it | No walkable cell within 64 units leads to the NPC. Fine for a merchant behind a counter | None |
| Too few points to spawn on | A random zone with fewer than 4 points of free ground inside it, between its heights | **Fit height range** |
| Zone floats above the ground | The bottom of the zone lies above the ground everywhere under it | **Fit height range** |
| No geodata for its region | The geodata folder has no file for the zone's region | None |
| NPC not in npcdata.txt | An entry names an NPC that npcdata.txt does not have | **Remove entry** |

Only the last one is an error, and it stops a save. The others are warnings: the server still reads the file.

**Save…** also checks the makers you changed, and lists what it finds under "Worth a look".

### If it went wrong

- **Check spawns** is off: it needs a geodata folder.
- Nothing is found, but you see a problem: the check covers only the loaded tiles and the distance you chose. Choose
  `10k`, or fly closer.
