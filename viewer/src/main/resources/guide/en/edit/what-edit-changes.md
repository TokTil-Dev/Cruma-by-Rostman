---
title: What Edit can change
kind: concept
summary: Edit moves, places and deletes objects, shapes and paints the ground, and changes lights and textures.
tool: edit
next: edit/first-steps
related: start/how-a-map-is-built, edit/save-and-check, terms/actor, terms/mover
---
Edit is the map editor of Cruma. You change a place of the world as you look at it, and then save the changed maps into
a folder of their own. The client stays as it was until you choose to install the folder into it.

![What one tile's map holds. A filled dot marks a part Edit changes, a crossed ring a part it only shows.](diagram:edit-tile-parts)

## What you can change

- Objects. A [static mesh](guide:terms/static-mesh), such as a tree, a rock or a house, can move, turn, grow,
  get another mesh or other materials, be copied or be deleted. New ones come from the Library.
- Figures that a map places. Some maps of P152 and 162 place figures of their own: flags of a camp, prisoners in a
  jail, and the seals of the lobby. They can move, turn, grow, be copied or be deleted as objects can. Each keeps its
  mesh, its textures and what it plays, and it stays in its map. A figure of a class that its map gives it, as the
  lobby of a custom Interlude client gives Lilith and Anakim, can move, turn, grow, be copied or be deleted too, and
  keeps its class and the values of its script.
- Objects that move by themselves, such as a swinging bowl of a lobby, the stand of the big head of Fantasy Isle and
  its turning rings, or a windmill. They move, turn, grow, are copied or are deleted as other objects are, and go on
  moving from their new place. What hangs on a swinging object, its flame, its glow or its rings, comes along and is
  saved at its new place. The editor of the game would leave it where it was: this is Cruma's way.
- Effects, such as fire, smoke or sparks. They can move, turn, be hidden, be copied or be deleted, and are selected by
  their marks in the **Select** tool. Their size and their particles stay, as the game does not size an effect by its
  object.
- The ground. The [terrain](guide:terms/terrain) can be raised, lowered and smoothed, painted with its layers, and
  given holes. See [Sculpt the ground](guide:edit/sculpt-the-ground) and [Paint the
  ground](guide:edit/paint-the-ground).
- Lights. A light can be added, moved, coloured, made larger or deleted. A
  [spotlight](guide:terms/spotlight) can also be turned.
- Walls and floors. On the [BSP](guide:terms/bsp), Edit gives them another material and changes how its texture lies:
  its place, its turn and its size. It hides a wall, which still blocks. It deletes a sheet, a thin surface such as a
  swamp that never blocked, and a whole brush that stands apart, such as a floor disc, whose solid turns into air. Most
  water stays.

## Which map a change goes into

Edit changes the maps of the world you look at. In the **Classic** and **Essence** worlds a tile such as `25_17` shows
`25_17_Classic.unr` where the client has it, so its objects, its lights, its walls and its ground are changed and saved
there, and the ground keeps its heights in `T_25_17_Classic.utx`. The panels name that map, such as "Terrain of
25_17_Classic". A tile with no Classic map shows `25_17.unr` in these worlds too, and Edit changes that one. See
[Clients and tiles](guide:start/clients-and-tiles).

## What stays as it is

- Doors, gates and lifts, called [movers](guide:terms/mover), and sounds. Their rows in the Objects list are faint,
  and a click on one says why.
- The shape of the BSP, but for a deleted brush, and the water and the sky.
- The maps next to the ones you change. Each map keeps its own light, so an edit never reaches over the edge of its
  tile.
- The server's files. [Geodata](guide:terms/geodata) and NPC spawns are not in the map. After you change the ground,
  build the geodata of that tile again with the Geodata tool.

## Why the light is built again

The light of a map is worked out once and saved with it, as [baked light](guide:terms/baked-light). When an object or
a light moves, Cruma builds the light of that place again, first as a preview and then for real when you save. See
[Lights and why light is built again](guide:edit/lights).

## Nothing is written until you save

Every change is a step of Undo. Cruma keeps a [recovery draft](guide:terms/recovery-draft) while you work, and writes
files only when you press **Save** in the save review. See [Save, install and check in the game](guide:edit/save-and-check).
