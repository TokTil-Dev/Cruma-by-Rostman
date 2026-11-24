---
title: Change the shape of an area
kind: howto
summary: Drag an area's corners, its bottom and top, its excluded parts and its points in the world.
tool: areas
next: areas/make-areas
related: areas/what-is-an-area, areas/check-areas, areas/save-areas
---
Change where an area lies, how high it reaches and what it leaves out, by dragging it in the world.

## Steps

1. Double-click the area in the world, or select it and press the pencil on its card. White handles show on its
   corners, and small dots in the middle of its edges.
2. Drag a corner to move it. A click or a drag on the dot in the middle of an edge adds a corner there.
3. Point at a corner and press <kbd>Delete</kbd> to take it away. An area keeps three corners at least.
4. Drag inside the area to move it whole, with its excluded parts. Teleport and restart points stay, as they are where
   the area sends you.
5. Drag a blue dot of the height box up or down to move the bottom or the top. A tag beside the pointer gives the
   height.
6. Press <kbd>Esc</kbd> to stop editing. <kbd>Esc</kbd> while you still hold the mouse button takes the drag back.

Each drag is one step, and <kbd>Ctrl+Z</kbd>, <kbd>Cmd+Z</kbd> on a Mac, takes it back. Nothing is written until you
save. The sentence of what the area does follows each change.

## In numbers

- **Height range** has **Bottom** and **Top**. **Fit to the ground** sets them from 200 below the lowest ground on the
  outline to 400 above the highest, as for spawn zones.
- **Corners** gives the X and Y of each corner.
- **Tile** is the tile of the area's middle. Cruma keeps `map_no` right by itself.

## Water

Water stays a box. A corner drags the box as a rectangle while the corner across from it stays, and its edges have no
dots. Its top is called **Surface**. The file writes the heights of water 7 higher than the server uses them, and Cruma
does that for you.

## Excluded parts

**Excluded parts** lists what the area leaves out, 5 at most. While you edit, the parts show in red with handles of
their own, and a drag inside one moves only that part.

- **Add part** puts a small square in the middle of the area.
- **Draw part** takes the corners you click on the ground. A click on the first corner or <kbd>Enter</kbd> closes
  it.

A part that reaches outside the area makes the server drop the whole area. Cruma says so at the part, in the world and
in the panel.

## Teleport and restart points

A teleport area lists its **Teleport points**, and a no-restart zone its **Restart points**, with X, Y and Z each.
**Add point here** places one where you click the ground next. While you edit, drag a point's mark over the ground to
move it.

### If it went wrong

- No handles: the panel must be open, and **Inspect** and **Analyze** must be off, as their clicks look at models.
- <kbd>Delete</kbd> does nothing: point right at a corner, not at an edge, and keep three corners.
- A click inside the area does not select the area under it: stop editing first with <kbd>Esc</kbd>.
