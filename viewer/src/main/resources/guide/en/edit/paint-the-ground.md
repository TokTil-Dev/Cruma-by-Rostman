---
title: Paint the ground
kind: howto
summary: Paint the layers of the terrain and where its grass grows, fill them by slope or height, and cut holes.
tool: edit
tab: paint
next: edit/lights
related: terms/terrain-layer, terms/decoration, terms/texture, edit/sculpt-the-ground, terms/texture-mapping
---
Put grass where there was sand, rock on a cliff, or a path through a field.

## Layers and their masks

The ground wears several [terrain layers](guide:terms/terrain-layer). Each one is a [texture](guide:terms/texture),
such as grass, with a mask: a grey picture over the whole tile that says where and how much the layer shows. The game
draws the layers in order, and a later layer covers an earlier one where its mask is bright.

![Three layers, each shown where its mask lets it, and the ground they make together.](diagram:edit-terrain-layers)

So painting does not change a texture. It changes the mask of one layer: brighter where you paint, darker where you
erase.

## Steps

1. Press **Paint** in the Edit panel, or <kbd>B</kbd>, and open the Terrain of a map as for
   [sculpting](guide:edit/sculpt-the-ground).
2. Choose a layer in the grid of swatches under **LAYER**. The line under the grid names it, such as "3 · Grass".
   <kbd>Alt</kbd> and a click on the ground chooses the layer that shows most there.
3. Choose **Paint** or **Erase** under **BRUSH**, and set **Size** and **Strength**.
4. Drag on the ground. <kbd>Shift</kbd> while you drag swaps Paint and Erase.

[Open the Paint tool](action:edit-tool-paint)

When it worked, the layer shows under the brush. Each drag is one step of Undo.

- **Cover the layers above** also fades the layers drawn over the chosen one, so it shows.
- The eye at the right of **LAYER** tints the ground where the chosen layer shows. It only marks the view.
- **Fill** paints the layer over the whole Terrain: **Fill by slope** where the ground is steeper than **Slope above**,
  35 degrees at first, such as rock on cliffs, and **Fill by height** between **From** and **To**.
- **Mapping** sets how large the layer's texture is drawn and how it turns: **Scale U**, **Scale V**, **Pan U**,
  **Pan V** and **Rotation**. See [texture mapping](guide:terms/texture-mapping).

## Decorations

Grass, flowers and small bushes grow on the ground by themselves. Each Terrain has a few
[decoration](guide:terms/decoration) layers: a mesh, such as a tuft of grass, and a density map, a grey picture over
the tile that says how much of the mesh grows where. The game scatters the meshes when it loads the map, so painting
the density map is all it takes to grow grass or take it away.

1. In **Paint**, choose **Decorations** at the top, in place of **Layers**.
2. Choose a decoration in the grid under **DECORATION**. Each picture shows its mesh, and the line under the grid
   names it, such as "0 · Grass01". <kbd>Alt</kbd> and a click on the ground chooses the decoration that grows most
   there.
3. Choose **Paint** or **Erase** under **BRUSH**, and set **Size** and **Strength**.
4. Drag on the ground. The meshes grow and go under the brush as you drag. <kbd>Shift</kbd> while you drag swaps Paint
   and Erase.

Each drag is one step of Undo. **Fill** paints the decoration at full density, by slope or by height, as for layers.
**Cover the layers above** and **Mapping** belong to the layers of the ground, so they are not there.

A density map often lies in a package of textures that other maps read too, such as `L2DecoLayer.utx`. A save writes a
changed copy of that package under its own name, so every map that reads the density map grows the new grass. When the
only other reader is the copy of the same tile for the Classic servers, or another decoration of the same Terrain, the
panel says what else changes under the line, such as "Also changes the decorations of 16_24_Classic.". A decoration
whose density map any other map reads is locked.

> **Note:** The viewer places the meshes by the game's own rules and random numbers, so each tuft should stand where the
> game puts it. A stroke can move the tufts of the whole square of 16 by 16 cells it touches, as in the game. Painted
> decorations were not yet tried in the game.

## Over the border of a map

Each map has its own layers, so a paint stroke stays on the map it started on. To paint a road over the border of two
maps:

1. Open the Terrain of both maps, as "Several Terrains at once" in [Sculpt the ground](guide:edit/sculpt-the-ground)
   says.
2. Paint up to the border on the first map.
3. Point at the second map and paint on. The chosen layer follows: the layer with the same texture is chosen there. When
   the second map has no layer with that texture, choose one under **LAYER**.

**Fill**, **Mapping**, **Holes** and **Edges** also work on one map at a time. The height brushes of **Sculpt** are
different: one stroke of them crosses the border.

## Holes and edges

Two brushes of **Sculpt** change the cells of the ground, the squares between four points of the grid, without moving
a height.

1. Press **Sculpt**, and choose **Holes** or **Edges** at the end of the row of brushes.
2. Drag over the ground. **Holes** hides cells, so caves and cellars can go under the ground. **Edges** turns the line
   across each cell, which changes its shape and its light.
3. Hold <kbd>Shift</kbd> and drag to put cells back as the client has them.

**Hidden** and **Turned** count the cells that differ from the client.

> **Warning:** Players, monsters and the camera pass through a hidden cell, and the server's geodata does not know
> it. After you save, build the [geodata](guide:terms/geodata) of this tile again, as the panel asks.

### If it went wrong

- A swatch is dimmed with a padlock: that layer cannot be painted. Point at it for the reason.
- A decoration is dimmed with a padlock: another map grows from the same density map, or its density map cannot be
  written. Point at it for the reason.
- The swatches show only an icon: the Library is still reading the client's textures. The first time this takes a few
  minutes.
- A painted layer looks a little different after the save: many masks are stored in blocks of 4 by 4 pixels, which
  round the paint.
