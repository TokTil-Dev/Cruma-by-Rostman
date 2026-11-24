---
title: Export an NPC
kind: howto
summary: Save an NPC's model with its skeleton and every animation as a glTF file for Blender.
tool: export-npc
related: npcs/what-is-npc-data, terms/npcgrp
---
Write an NPC of the client as a character that Blender and other 3D programs open, with its bones and its moves.

### You need

- A game client. No server files are needed: the list comes from the client's own tables, such as
  [npcgrp.dat](guide:terms/npcgrp).

## Steps

1. Open **Export NPC**: choose **Export NPC…** in the Viewer menu, or press **Export** on an NPC's card in the **NPCs**
   panel. In a narrow panel that button shows only its icon, and its tooltip begins with "Export NPC".
2. Type in **Find an NPC by name, title, id or mesh**, and click the NPC's row. The picture shows its model. Drag
   across it to turn it.
3. Choose a size under **SCALE**. **Real size** makes one client unit 4 cm.
4. Press the export button at the bottom. It names the NPC, such as **Export Roxxy**.
5. Press **Open in Blender** to see it play its idle, or **Show folder** to see the files.

When it worked, the folder holds a file such as `Roxxy_30006/Roxxy_30006.glb`, with `export-info.txt`, which lists the
animations, and `open-in-blender.py`.

[Open Export NPC](action:npcs-open-export)

## Several at once

- Tick the box at the left end of a row to add the NPC to the batch. **Add all** with the number shown adds every NPC
  the list shows, such as all the guards after a search for `guard`.
- Choose **Player character** to make a character of any race the client has, then press **Add this character**. On
  Essence, **Race** also offers **Sylph**, **High Elf** and **Crow** in the worlds whose tables have them,
  and **Class** offers the classes with a figure of their own, such as **Death Knight** under **Human**, **Elf** and
  **Dark Elf**, in the worlds where a player can make them, and **Other classes** for the body that every other class
  of those people wears. Such a class wears the clothes of its race, the hair and face of its class, and moves as that
  class does in the game. The **Vanguard Rider** under **Orc** sits on his wolf. A saved character whose class this
  world does not have keeps the race the form shows, and a note under the form names the worlds that have it. Under
  an item of **Head or back** that sits on the head, a character wears the hair the game gives it: only the back of
  its hair under a circlet, a short hair under most hats, so a long style does not stick out, and no hair under a wig.
- Under **SAVE TO**, **One scene** writes the whole batch into one file, and **Separate files** writes a folder for
  each model.
- Press the button that counts the models, such as **Export 3 models**.

### If it went wrong

- The export button is off: the client's mesh of this NPC cannot be read.
- An earlier export is in the way: turn on **Replace it**.
- In Blender only one animation plays: open the Action Editor and choose another. Each animation is an action of the
  armature.
