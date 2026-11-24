---
title: Lights and why light is built again
kind: howto
summary: Add a light, change its colour, place and reach, and learn why light is built again after a change.
tool: edit
tab: lights
next: edit/surfaces-and-materials
related: terms/baked-light, terms/lightmap, terms/spotlight, edit/save-and-check
---
Light a dark corner, warm up a tavern, or move a lamp to where its post now stands.

## What a light does

A light is an [actor](guide:terms/actor) with a place, a colour, a brightness and a radius. Everything inside its
reach takes its light: the meshes, the walls and floors of the [BSP](guide:terms/bsp), and the ground. The reach is 25
units for each step of **Radius**, and one step more.

![A light with its reach. A mesh, a wall and the ground inside it take its light, a mesh outside does not.](diagram:edit-light-reach)

The game does not work this light out while you play. It was worked out once, when the map was built, and saved with
the map as [baked light](guide:terms/baked-light): in [lightmaps](guide:terms/lightmap) on the walls and floors, and
as a colour at each point of the meshes and the ground.

## Why light is built again

So the saved light only fits the map as it was. Move a building, and its old shadow stays on the ground while the
building stands dark in its new place. Move a light, and the old place stays lit. Cruma builds the light of that place
again:

- While you edit, as a preview in the background. "Relighting" stands in the status row of the Edit panel, and the
  view shows the new light a moment later.
- When you save, for real. The save rebuilds the lightmaps, the meshes and the ground near each change, and
  nothing far away.

## Add a light

1. Press **Lights** in the Edit panel, or <kbd>I</kbd>.
2. Press **New light**, or <kbd>N</kbd>. **New spotlight**, <kbd>Shift+N</kbd>, adds a
   [spotlight](guide:terms/spotlight).
3. Choose a colour in the row of swatches. Each names itself on hover: Candle, Lamp, Daylight, Moonlight, Fire, Magic
   or Poison.
4. Set **Radius**, and **Distance from the surface**: 64 units at first, 256 for a spotlight.
5. Click a wall, a floor or the ground. The light stands out from it, and is chosen.

[Open the Lights tool](action:edit-tool-lights)

A new light is a copy of an ordinary light of the same map, with your colour and radius. The line under the sliders
names it, such as "Copies Light9".

## Change a light

Each light has a mark in the view, a dot in its colour. A far mark is smaller and fainter, and a mark behind a wall or
a floor is faint: a light inside a house shows faint from the street. A yellow ring marks a light you changed and did
not save yet.

1. Click the mark of a light in the view, or its row in the list. Where marks overlap, the click takes the one nearest
   to you. An object that you selected before with **Select** is selected no more, and an object that you select now,
   in the **Objects** list, takes the place of the chosen light.
2. Change **Temperature**, **Hue**, **Saturation** or **Brightness**, or press a preset.
3. Drag the arrows in the view to move it, or type its **Location**. Drag the square on its ring, or **Radius**, to set
   its reach.
4. For a spotlight, drag its round handle, or set **Yaw**, **Pitch** and **Cone**.

Boxes in the view show the meshes it reaches. The icons of its card copy it, delete it, or revert it to the client.

A lamp with a glow over it, as the game draws one, takes the glow along when it moves, and the glow takes its new
colour. Delete takes the glow away and Undo brings it back. A copy of such a lamp has the lamp's glow too. A new light
is made from a light of the map, and has a glow when that light has one.

A glow that hangs on a swinging object, such as the haze over a bowl of a lobby, belongs to a light that lights
nothing. This tool does not change it: it moves, and is copied, with its bowl in the **Select** tool, see
[Select and move objects](guide:edit/select-and-move).

### If it went wrong

- **New spotlight** is off: the map has no spotlight to copy. Point at it for the reason.
- A light does not move, and its card says why: a light cannot go into a wall, or into or out of the sky, and some
  lights have no saved place.
- You are not sure the game shows it so: Cruma builds the light by the rules of the game's own editor, but it cannot
  run the game. Look at the place in the game, see [Save, install and check in the game](guide:edit/save-and-check).
