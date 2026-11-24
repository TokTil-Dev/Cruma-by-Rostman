---
title: Export a location to Blender
kind: howto
summary: Save whole tiles or a square of the world as one glTF file, which Blender, Unity and Unreal open.
tool: export-location
next: export/what-an-export-holds
related: export/what-an-export-holds, terms/gltf, start/where-cruma-writes
---
Take a part of the world into a 3D program, with its [terrain](guide:terms/terrain), buildings, meshes and textures.

### You need

- A client chosen on the start screen, or a world open in the Viewer.

## Steps

1. Press **Tools** under **MORE** on the start screen and choose **Export location…**, or **Export here…** in the
   Viewer menu for a square around the camera.
2. Under **AREA**, choose **Whole tiles** and click [tiles](guide:terms/tile) on the map, or **A part** and find a **Place**, with a
   **Side** from **200 m** to **1300 m**.
3. For a client with more than one world, choose the world above **TERRAIN TEXTURE**, such as **Main**, **Classic**
   or **Essence**. See [Clients, chronicles and tiles](guide:start/clients-and-tiles).
4. Choose the **TERRAIN TEXTURE**. **4096 px** suits most work, and **8192 px** is sharper up close.
5. Under **INCLUDE**, choose what comes along: **Grass and flowers**, **Client light**, **Effects**, **Moving effects**,
   **Sounds** and **Music**.
6. Choose the **SCALE**. **Real size** makes a unit 4 cm, so a person stands 1.84 m tall.
7. Check the **Folder** under **SAVE TO**, and press the export button, such as **Export 22_22**.
8. When it is done, press **Open in Blender**, or **Show folder**.

**RESULT** then counts the objects, triangles and textures, and names the folder. The folder is named after the tiles
or the place, such as `22_22` or `Giran_Castle_Town-400m`, with the end of the world's tables for Classic and
Essence, such as `22_22_Classic` and `22_22_ClassicAden`. **Map figures** counts the figures that some maps of P152
and 162 place, such as the flags of a camp, prisoners and the seals of the lobby. They come with every export of
their map, each with a rig that moves in Blender as in the Viewer.

With **Effects** on, **MAP EVENTS** chooses which [map events](guide:terms/map-event) the effects follow. With
**Effects** off it shows one line when your choice in the Viewer still changes the export: the
[zone state](guide:terms/zone-state) of each map picks the meshes, the ground and the sounds, and with **Sounds** on the choice
under **BY TIME OF DAY** picks the sounds by the hour.

[Export a square around the camera](action:export-here)

> **Note:** An export takes at most 25 tiles. Up to 9 work well: more make a large file that 3D programs open slowly.

### If it went wrong

- The export button is grey: the line at the bottom of the dialog says what is missing, such as "Choose the
  folder to save to.".
- It says "An earlier export of 22_22 is in this folder.": choose **Replace it**, or another folder.
- **Open in Blender** does not show: Cruma did not find Blender. Open the `.glb` in Blender with File, Import, glTF 2.0,
  or install Blender in the usual folder of your system.
- A 3D program runs out of video memory: **8192 px** needs about 0.35 GB of it for each tile. Choose **4096 px**.
- It says "Choose the server's script folder for the NPCs.": **Place NPCs from npcpos** is on. Choose the
  **Script folder** under **NPCS**, or turn it off.
