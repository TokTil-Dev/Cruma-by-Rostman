---
title: See areas in the world
kind: howto
summary: Show the server's areas, read what each one does, and find the one you need.
tool: areas
next: areas/edit-a-shape
related: areas/what-is-an-area, terms/areadata, spawns/read-spawns
---
See which areas lie around you, and what each one does.

### You need

- The server's [script folder](guide:terms/script-folder) open in **Areas** or in **NPC Spawns**. The two tools share
  it. Without one, **Areas** offers **Choose folder…**.

## Steps

1. Open **Areas** on the tool rail. The first time, the areas show at once. Later **Show**, or <kbd>L</kbd>, shows and
   hides them.
2. Look at the world. Each area lies on the ground in the colour of its type, with a short curtain and a tag with its
   type and name. An area that starts off is fainter.
3. Point at an area. A card gives its type, its tile, what it does and the other areas at the same place.
4. Click to select it. Where areas stack, such as a town's peace zone and its no-restart zone, a click selects the
   smallest. A second click on the same place, a moment later, selects the next one there.
5. Read the panel: the card, a sentence of what the area does, and the area from above. <kbd>F</kbd> brings the camera
   to it, and <kbd>Esc</kbd> goes back to the overview.

[Select the area nearest to you](action:areas-show-nearest)

## Find an area

- **IN THE LOADED WORLD** counts the areas by type. A click on a type hides or shows it, in the world and on the map.
  Water starts hidden, as its boxes can be a whole tile wide.
- **AT THE CAMERA** lists the areas that hold the camera, by the server's rules, each with what it does.
- **NEAR THE CAMERA** lists the nearest areas. A click selects one and flies there.
- The search field finds an area by its name, its type, its note or the skill it casts. <kbd>Enter</kbd> opens the
  first match.
- **View** sets the draw distance, the names, **Height box of the selection**, and whether the areas of instance zones
  and the areas that start off show.

On the world map, <kbd>M</kbd>, the layer **Areas** shows them too, with the same types hidden. Point at one to read what
it does, and click it to select it and fly there.

## Areas and spawn zones

The zones of **NPC Spawns** and the areas can show together. While **Areas** is open, the areas take the pointer, and the
spawn zones are thin and take no clicks. **Spawn zones and NPCs** in **View** turns them on and off. While only
**NPC Spawns** is open, the areas are the thin ones, and **Server areas** in its **View** turns them on and off.

### If it went wrong

- Nothing shows: press <kbd>L</kbd>, or the folder has no `areadata.txt`.
- An area is missing: its type may be hidden in **IN THE LOADED WORLD**, or its tile is not loaded in this session. Load
  the tile from the main menu.
