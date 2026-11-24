---
title: Find an NPC
kind: howto
summary: Search npcdata by name, id or level, narrow the list with filters, and open an NPC.
tool: npcs
next: npcs/edit-an-npc
related: npcs/what-is-npc-data, terms/npcdata
---
Find an NPC of the server's file and open it.

### You need

- A [script folder](guide:terms/script-folder) open in **NPC Spawns**. The **NPCs** panel reads the same folder. See
  [Open and read spawns](guide:spawns/read-spawns).

## Steps

1. Open **NPCs** on the tool rail. Without a folder it shows **No script folder**: press **Open** to choose it in NPC
   Spawns.
2. Type in **Find an NPC by name, id or level**. It finds the game's name (Goblin), the file's name (goblin) and the
   id (20003).
3. Type `lv 40` for the NPCs of level 40, or `lv 20-30` for levels 20 to 30.
4. Choose **All**, **Monsters**, **Bosses** or **Townsfolk** to list one kind.
5. Press **Filters** to list only NPCs that drop an item, know a [skill](guide:terms/skill), have a race or an AI, are edited, or are
   spawned.
6. Click a row to open the NPC, or press <kbd>Enter</kbd> for the first one in the list.

When it worked, a card shows the NPC's names, level and kind, and the line of npcdata.txt it is on. Under it stand the
tabs **Drop**, **Skills**, **AI**, **Stats** and **All**.

## From the world

- Double-click an NPC in the world, with NPC Spawns open, to open it here.
- In NPC Spawns, the card button of an entry opens its NPC here.
- On the NPC's card, **Fly to** flies to its nearest spawn, and **Spawns** lists every [maker](guide:terms/maker) that spawns it. In a
  narrow panel the buttons of the card show only their icons, and their tooltips name them.

[Open the NPC that spawns nearest to you](action:npcs-open-nearest)

While the search is empty, the last eight NPCs you opened stand under **RECENT**. **File order** changes to **By
level** and then **By name** on each click.

### If it went wrong

- "No NPC matches": shorten the search, choose **All**, or turn off the filters under **Filters**. The chip counts
  the filters that are on.
- **Fly to** is off: npcpos.txt does not spawn this NPC, or its spawns lie on tiles that are not loaded. Its tooltip
  says which.
