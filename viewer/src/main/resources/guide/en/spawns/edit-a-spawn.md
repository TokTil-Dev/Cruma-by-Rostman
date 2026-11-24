---
title: Edit a spawn and save
kind: howto
summary: Change what a maker spawns, move its zone and NPCs, then save npcpos.txt with a backup.
tool: spawns
next: spawns/check-spawns
related: spawns/read-spawns, spawns/spawn-formats, terms/maker, terms/respawn
---
Change which NPCs a zone spawns, how many, how often, and where they stand.

### You need

- A [script folder](guide:terms/script-folder) open in **NPC Spawns**. See [Open and read spawns](guide:spawns/read-spawns).

## Change an entry

1. Select a [maker](guide:terms/maker): click its zone in the world, or a row of **NEAR THE CAMERA**.
2. Click an entry in the list under the maker's map to open it.
3. Choose another NPC in the list at the top of the entry. Type its name, as npcdata.txt or the game writes it, or its
   id.
4. Change **Count**, **Respawn** and **Respawn ±**. A time is a number with `sec`, `min`, `hour` or `day`, such as
   `30sec`, or `no`.
5. Choose **Random in zone** or **Fixed point**. A fixed point has **X**, **Y**, **Z** and **Heading**, and **Put on
   the ground** sets **Z** from the [geodata](guide:terms/geodata). Where there is no geodata, it takes the floor of
   the client's map, which often lies about 32 units under the server's.
6. Press **Add NPC** for a new entry, or **Add NPC here** and click the ground for an NPC at a fixed point.

**Heading** goes from 0 to 65535 for a whole turn: 0 faces east and 16384 south.

## Move a zone and its NPCs

1. Press the pencil on the maker's card, or double-click the zone.
2. Drag a white corner to move it. Click the middle of an edge to add a corner. <kbd>Delete</kbd> removes the corner
   under the pointer.
3. Drag inside the zone to move it. Its fixed NPCs move with it and stay on the ground.
4. Drag a fixed NPC to move it, and drag its blue handle to turn it.
5. Press <kbd>Esc</kbd>, or the pencil again, to stop.

**New spawn zone here** makes a square zone with a maker in the middle of the view. **Draw zone** draws one corner by
corner: a click on the first corner, <kbd>Enter</kbd> or **Close zone** closes it.

## Save

1. Press **Save…** at the bottom of the panel, or <kbd>Ctrl+S</kbd>, <kbd>Cmd+S</kbd> on a Mac.
2. Read the changes. Each shows its line in the file and what it changed, such as `maximum_npc 4 → 3`.
3. Keep **Back up the current file first** on, and press **Save**.

When it worked, the footer says "Saved npcpos.txt" and names the backup, with a folder icon that shows it. Save
rewrites only the lines you changed, and the backup lies next to the file, such as
`npcpos.txt.2026-10-13_13-40-05.bak`.

> **Note:** Every change is a step of Undo. **Discard** goes back to the file as last saved, and Undo brings your
> changes back.

### If it went wrong

- **Save** is off: the review lists what to fix under "Fix these before saving". A maker has no NPCs, an entry names an
  NPC that npcdata.txt lacks, or a zone has fewer than three corners.
- "A respawn time is a number with sec, min, hour or day": write the time again, such as `5min`.
- **Put on the ground** is off: the ground under the NPC is not known. Its geodata region is still loading, or its
  map tile is not loaded.
- A zone does not move when you drag it: press the pencil first. Without it, a click only selects.
