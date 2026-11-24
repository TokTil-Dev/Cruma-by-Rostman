---
title: Edit an NPC and save
kind: howto
summary: Change an NPC's drops, skills, AI and numbers, then save npcdata.txt with a backup.
tool: npcs
next: npcs/export-an-npc
related: npcs/find-an-npc, npcs/npc-files, terms/drop-group, terms/skill
---
Change what an NPC drops, which [skills](guide:terms/skill) it has, and how strong it is.

### You need

- An NPC open in the **NPCs** panel. See [Find an NPC](guide:npcs/find-an-npc).

## Steps

1. Choose a tab: **Drop**, **Skills**, **AI**, **Stats** or **All**.
2. In **Drop**, click an item to change how many drop and its chance. **Add item** and **New drop group** add more.
3. In **Skills**, press **Add skill** to add a skill to the **SKILL LIST**.
4. In **AI**, click the name of the AI to choose another, and press **Add setting** for a setting it reads.
5. In **Stats**, change the level, HP, attack and the rest. A yellow label means the value is far from what NPCs of
   the same kind and level have.
6. In **All**, find any setting of the NPC's line by its name, as [npcdata.txt](guide:terms/npcdata) writes it.
7. Press **Save…** at the bottom, read the changes, and press **Save**.

When it worked, the footer says "Saved npcdata.txt" and names the backup, with a folder icon that shows it. The old
file stays next to the new one, such as `npcdata.txt.2026-10-17_11-28-54.bak`.

## How a drop works

![A drop group of 70% with two items: the group's chance times an item's share is its chance per kill.](diagram:npc-drop-group)

When the NPC dies, the server rolls each [drop group](guide:terms/drop-group) with its chance. A group that hits drops
one of its items, and the shares of its items add up to 100%. Drag the line between two items on the group's bar to
move share from one to the other. When the shares do not add up to 100%, **Make 100%** scales them.

## Many NPCs at once

1. Press **Select**, then click rows to check them. **All shown** checks every NPC of the list.
2. Choose **Drop ×**, **Spoil ×**, **Exp rate ×**, **SP ×**, **Set…** or **Skill**.

Each batch is one step of Undo, and the review of **Save…** lists it once.

### If it went wrong

- **Save** is off: an item or a skill that itemdata.txt or skilldata.txt lacks, a chance below 0 or above 100, an
  empty group, or a list that cannot be read. The review lists them.
- `exp` cannot be typed: the server sets it from the level. Change the level, and `exp` follows.
- A new NPC has no model in the game: the client knows NPCs by id, and its npcgrp has no record for the new id yet.
