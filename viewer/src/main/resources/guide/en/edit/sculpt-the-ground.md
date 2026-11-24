---
title: Sculpt the ground
kind: howto
summary: Raise, lower, flatten and smooth the terrain of a map with a round brush, and cut holes into it.
tool: edit
tab: sculpt
next: edit/paint-the-ground
related: terms/heightmap, terms/terrain, edit/paint-the-ground, geodata/what-is-geodata
---
Make a hill, flatten a place for a house, or lay a ramp up to a gate.

## What a heightmap is

The ground of a tile is a [heightmap](guide:terms/heightmap): a grid of points 128 units apart, each with one height.
The game joins the points with triangles. Sculpting changes these heights and nothing else, so the textures of the
ground stay where they are.

The brush is a circle on the ground. Inside its inner ring it works fully, and from there it fades to nothing at its
edge. **Falloff** sets how it fades: **Smooth**, **Linear** or **Sharp**.

![The heightmap from above with a brush over it, and the same points from the side after Raise.](diagram:edit-heightmap)

Every point of a map can change, up to its very edge. The height brushes and **Ramp** also go on over the edge onto
the next map, as "Over the border of a map" below says.

## Steps

1. Press **Sculpt** in the Edit panel, or <kbd>T</kbd>.
2. Press **Edit the Terrain of 22_18**, named after the map under the camera. Or click the ground, or choose a map in
   the list and press **Edit Terrain**. In a map from **Other maps**, such as a [lobby](guide:terms/lobby), the
   button is named after that map.
3. Choose a brush under **BRUSH**: Raise, Flatten, Smooth, Ramp, Noise or Set height. Point at a tile for its name.
4. Set **Size** with **S**, **M** or **L**, 4, 8 or 16 points of the grid, or with <kbd>[</kbd> and <kbd>]</kbd>.
5. Drag on the ground. **Raise** lifts it, and <kbd>Shift</kbd> with a drag lowers it.

[Open the Sculpt tool](action:edit-tool-sculpt)

When it worked, the ground changes under the brush, and the panel says "Changes not saved". Each drag is one step of
Undo, such as "Raise ground".

## Several Terrains at once

A road or a pass often crosses the border of two maps. Open the Terrain of each, and work on both without closing one:

1. With a Terrain open, point at the ground of the next map. Beside the pointer stands "Terrain of 22_19" and "Click to
   edit it too". Click, and its Terrain opens beside the first. Or choose **Open another Terrain…** in the More menu.
2. The open Terrains show as chips under the title, one for each map. A dot marks one with changes not saved.
3. The brush works on the Terrain under the pointer, and the panel follows it. Click a chip to choose one too.

Each Terrain keeps its own changes, and one Undo goes back through the steps of all of them in the order you made
them. **Save…** writes every changed map together. Up to 9 Terrains can be open at once.

To close one, choose **Close this Terrain** in the More menu: its changes go, and its map shows as it was. **Close all
Terrains** closes every one.

## Over the border of a map

A pass through a mountain often crosses the border of two maps. One stroke can change both:

1. Choose **Raise**, **Flatten**, **Smooth**, **Noise**, **Set height** or **Ramp**, and point near the edge of the map.
   When the brush comes within its size and 8 points more of the edge, the Terrain of the next map opens by itself,
   beside the one you work on. It does not take the panel, and a chip under the title shows it. Near a corner, the
   map beyond the corner opens too.
2. Drag over the border. The stroke goes on without a break, and one Undo takes it back on both maps. **Flatten** and
   **Set height** give both sides the same height, also where the two maps count their heights from another level.
   **Save…** then writes both maps.
3. With **Ramp**, the first click may lie on one map and the second on the next.

**Select**, **Holes** and **Edges**, and the Paint tool with its **Mapping** and **Fill**, work on one map at a time.

When the next map is not loaded, or its Terrain cannot open, the brush changes this map up to its very edge and stops
there. A quiet line under the chips then says why, such as "The Terrain of 22_19 does not open by itself, so the brush
stops at the border." followed by the reason.

> **Warning:** Nobody has looked at such a border in the game yet. After you save, build the
> [geodata](guide:terms/geodata) of both maps again.

## The brushes

| Brush | What it does |
|---|---|
| Raise | Lifts the ground, **Speed (units per second)** fast, 400 at first |
| Flatten | Pulls the ground to its **Target**, such as **Where the drag starts**. <kbd>Alt</kbd> and a click picks a height |
| Smooth | Softens bumps |
| Ramp | A straight slope from one click to the next, **Ramp width** wide |
| Noise | Random bumps up to **Amplitude** units high |
| Set height | The whole circle at the target height at once |
| Select | Marks ground with a soft edge, to lift it by **Move by** units |
| Holes | Hides the cells of the ground under the brush: the game does not draw them, and everyone passes through |
| Edges | Turns the diagonal of each cell, which changes its shape, its light and its collision |

## After sculpting

Meshes that stood on the ground may now float or sink. The panel then says how many, such as "7 meshes may float or
sink", with **Show** and **Drop them to the ground**.

> **Warning:** The server's geodata does not know the new ground. After you save, build the
> [geodata](guide:terms/geodata) of this tile again in the Geodata tool.

### If it went wrong

- Nothing changes under the brush: no Terrain is open. Open one with **Edit Terrain**, or with the button that names the
  map under the camera.
- A stroke stops at the border of the map: the Terrain of the next map is not open. The line under the chips says why
  it did not open by itself.
- A step shows at the border after a stroke on the first row or column of a map: the map west or north of it is not
  open, so it still shows its loaded edge. Open its Terrain, and its edge follows.
- A Terrain says "read-only": Cruma cannot write it, and the line under it says why.
- A second Terrain of the same map, or one whose heights are in the same height package as an open one, does not open.
  Close the other one first.
