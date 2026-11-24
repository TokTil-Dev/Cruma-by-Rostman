---
title: Place objects from the Library
kind: howto
summary: Find a mesh of your client in the Library, place it with clicks or a brush, and copy, paste or save groups.
tool: edit
tab: place
next: edit/sculpt-the-ground
related: edit/select-and-move, terms/static-mesh, terms/prefab, terms/package
---
Put new trees, rocks or fences into a map, one by one or many in one stroke.

## The Library

The **Library** tab of the Scene panel on the left lists every [static mesh](guide:terms/static-mesh) of your client
with a picture. Cruma reads the client's mesh packages once, in the background, and keeps the list, so the next time it
is ready at once. Nothing is written into the client.

The meshes come in groups: **FAVOURITES**, the ones you marked with the star at the right of a row, **IN THIS MAP**,
**RECENT**, **PREFABS** and **ALL**. The search field over the list finds a mesh by its name or its package.

[Open the Library](action:edit-open-library)

## Place with clicks

1. Press **Place** in the Edit panel, or <kbd>P</kbd>.
2. Click a mesh in the Library. Or <kbd>Ctrl</kbd> and a click on an object in the view, <kbd>Cmd</kbd> on a Mac, takes
   its mesh. **Take selected** takes the mesh of the selection.
3. Point at the ground. A thin box shows where the mesh will stand.
4. Click to place it. Each click places one more, standing on the surface you click.
5. Press <kbd>Esc</kbd> to go back to **Select**.

You can also drag a mesh from the Library into the view and let go where it should stand.

Under **OPTIONS**, **Align to ground** tilts each new object to the slope, **Random yaw** turns it any way, **Random
scale** gives it a size between two factors, 0.9 and 1.1 at first, and **Snap to grid (16)** puts it on the Grid step.

[Open the Place tool](action:edit-tool-place)

## Place with the brush

1. Under **BRUSH**, set **Place with** to **Brush**.
2. Drag on the ground. Objects stand along the path, one each **Spacing** units, each moved from the path at random by
   up to **Jitter** times the spacing.
3. Hold <kbd>Shift</kbd> and drag to erase objects of this mesh near the path.

One drag is one step of Undo, such as "Scatter 12 Pillar".

## Replace, copy and keep a group

- Replace a mesh. Select objects, press the box icon on their card, Replace mesh, and click another mesh in the
  Library. Their place, turn, size and materials stay.
- Copy and paste. <kbd>Ctrl+C</kbd> copies the selection, and <kbd>Ctrl+V</kbd> pastes it on the surface under the
  pointer, also in another map or another session of Cruma.
- Prefabs. **Save as prefab…** in the More menu of the card, or in the right-click menu, saves the selection as a
  [prefab](guide:terms/prefab) file, in `Documents/Cruma/Prefabs` at first. The Library lists it under **PREFABS**, and
  each click of Place puts the whole group down.

### If it went wrong

- The Library says "Reading mesh packages": wait. The first time on a client this takes a while.
- A note says "Placed in 20_18, because the map 21_18 is not loaded": the object went into another map. Load that tile,
  or move it later with **Move to another map…** in the More menu.
- **Brush** is off: a prefab is chosen. The brush paints one mesh.
