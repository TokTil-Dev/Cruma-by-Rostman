---
title: Inspect an object and its materials
kind: howto
summary: Click an object to see what it is, where it comes from, and the materials and textures it wears.
tool: object
next: view/read-performance
related: terms/actor, terms/material, terms/texture, start/how-a-map-is-built
---
Find out what an object in the world is made of.

![An actor stands in the map and shows a mesh. Each slot of the mesh wears a material, which names its textures.](diagram:view-object-parts)

## Steps

1. Press **Inspect** in the top bar.
2. Click an object in the world. The **Object** panel at the right shows its name, and under it the tile and the [actor](guide:terms/actor)
   it belongs to.
3. Press **Focus** to fly to it, or **Isolate** to hide everything else for a while. **Show all** brings the rest back.
4. Open the **Material** tab. It has a card for each slot of the object, with its [material](guide:terms/material) and its [textures](guide:terms/texture).
5. Open the **Source** tab to see its **Tile / owner**, its **Actor** and its **Asset**, the mesh with its [package](guide:terms/package).
6. Press <kbd>Esc</kbd> when you are done, to clear the selection.

[Switch to Inspect](action:start-mode-inspect)

## Figures that a map places

Some maps of P152 and 162 place figures of their own: flags of a camp, prisoners in a jail, and in P152 the six seals
in the hall of the lobby `Lobby01`. They play their sequences by the rules of the client's code. A click on one shows
it in the **Object** panel: its actor, such as `Lobby01.LineagePawn3`, under **MODEL** its mesh, and the textures the
map gives it when it has its own, under **ANIMATION** what it plays, and under **PLACE** where it stands. Edit moves, turns, sizes, copies and deletes them, and an export of their map takes them with
their rigs, see [Export a location](guide:export/export-a-location).

A map can also give a figure a class of its own, as the lobby of a custom Interlude client gives Lilith and Anakim.
The script of that class says what the figure plays and hangs its effects on its bones, such as the eight blue orbs
around Anakim, and the camera scenes of the lobby start it again. The card names that class under **Class**, its
**Tag**, and under **Played by** that the script of its class plays it. Edit moves, copies and deletes such a figure
too.

## Many objects at once

**Analyze** adds **Objects** at the left: a list of the objects of the loaded tiles, with a search. A click on a row
selects that object. The tray at the bottom has **Animation**, to play the scene slower or faster, and **Compare A/B**,
to compare two sets of **Scene settings**.

The search of the top bar, **Search objects…**, also finds objects, and switches to Analyze.

### If it went wrong

- A click selects nothing: the view is in Wire, which picks nothing. Choose Lit in the view bar.
- In Explore a click only selects while the **Object** panel is open. Press <kbd>Tab</kbd> to open it.
- **Animation** is grey: the object and its materials do not move.
- The panel shows no place or size: it shows what an object is made of. Edit shows where it stands.
