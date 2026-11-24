---
title: Surfaces, materials and textures
kind: howto
summary: Retexture, hide, unlight or delete walls and floors, choose whole brushes, give objects materials.
tool: edit
tab: surfaces
next: edit/save-and-check
related: terms/texture-mapping, terms/material-slot, terms/material, terms/texture, terms/bsp
---
Line up the bricks of a wall, put a stone floor on a path, hide a wall, take a swamp or a floor disc away, or give one
barrel a texture of your own.

## How a texture lies on a surface

A wall or a floor of the [BSP](guide:terms/bsp) wears a [texture](guide:terms/texture) that repeats over it like
tiles. Its [texture mapping](guide:terms/texture-mapping) says where the tiles start, which way they run and how large
they are. Its two directions are U, across the picture, and V, down it.

![A wall's texture as saved, with its U and V, then moved along U by Pan, then drawn twice as large by Scale.](diagram:edit-texture-mapping)

A mesh is different: its texture is part of the mesh. Instead, each [material slot](guide:terms/material-slot) of an
object can wear another [material](guide:terms/material).

## Change the texture of a wall or floor

1. Press **Surfaces** in the Edit panel, or <kbd>U</kbd>.
2. Click a wall or a floor in the view. <kbd>Ctrl</kbd> and a click, <kbd>Cmd</kbd> on a Mac, adds more. An object
   that you selected before with **Select** is selected no more, and an object that you select now, in the
   **Objects** list, takes the place of the chosen surfaces. The view outlines the chosen surfaces: a line in view
   is solid, and a line behind a wall or a floor is faint and dashed.
3. Click the row under **MATERIAL**. The list of materials opens beside the panel: point at a row to see it on the
   surfaces, and click it to give it to them.
4. Choose a step of `1`, `4`, `16` or `64` texels under **PAN**, and press **U -**, **U +**, **V -** or **V +**.
5. Press **-90°**, **-45°**, **+45°** or **+90°** under **TURN**.
6. Type a size for U and V under **SCALE**, or press **Half size** or **Double size**.
7. Press **Flip U** or **Flip V** under **FLIP AND ALIGN** to mirror it. **Floor** lays it flat as seen from above,
   and **Wall** stands it upright.

[Open the Surfaces tool](action:edit-tool-surfaces)

When it worked, the view shows the new texture in its place at once. **Revert** gives the chosen surfaces their
texture, its mapping, their flags and their visibility in the client back.

A new texture keeps the size of its texels on the surface. So a larger picture, such as 512 texels in place of 256,
covers more of the wall and repeats less often. **SCALE** sizes it again.

**Client material**, the first row of the list, gives each surface its texture in the client back. The eyedropper at
the end of the **MATERIAL** row, or <kbd>Alt</kbd> and a click in the view while the list is open, takes the material of
another surface or of an object. **Import an image…** at the end of the list makes a texture from your own picture,
see below.

## Choose a whole brush or many surfaces

A wall or a floor that looks like one piece is often many surfaces, and they all came from one brush: a block that the
maker of the map drew in UnrealEd. Choose them all at once, and a new texture or a hide changes all of them in one step.

1. Click a wall or a floor with **Surfaces**, as above. The **Brush** row names its brush, what it does and how many
   sides it has, such as `Brush282 · adds solid · 424 sides`. A brush that adds solid makes walls and floors, and one
   that cuts solid out makes a room.
2. Press **Choose brush**, or <kbd>Shift+B</kbd>, to choose every side of the brush.
3. Or press **Select** for more ways to choose. **Matching texture** takes every surface with the same texture. **All
   adjacent** takes the surfaces that touch the chosen ones, then the ones that touch those, until none is left.
   **Adjacent coplanar**, **Adjacent walls**, **Adjacent floors and ceilings** and **Adjacent slants** do the same with
   one kind only. **Matching zone** takes every surface of the zones of the chosen ones, such as a whole room that
   the maker of the map closed with zone portals.
4. **Matching items** takes the sides of the same brush that have the same name. The tool that made a brush in
   UnrealEd named its sides, such as Wall and Cap of a column or Step and Rise of stairs. So one wall of a round
   column chooses all its walls without its two caps. Most brushes have no names, and then it chooses all their
   sides.
5. **Reverse** chooses every other surface of the map, **All of the map** every one, and **None** none.
6. Change them: a new texture, **Hide** or **Revert** changes every chosen surface as one Undo step.

Each row of **Select** shows its key, the key of UnrealEd, such as <kbd>Shift+T</kbd> for **Matching texture**.
**Matching zone** has none, as UnrealEd gives it none.
**Memorize** keeps the choice aside. **Recall** chooses it again, and **Or with memorized**, **And with memorized** and
**Xor with memorized** join it with the new choice. Choosing is no Undo step, so Undo leaves the choice as it is. A note
over the view says what was chosen, such as "Chose 424 surfaces of Brush282.".

Tick **Brush wireframe** under the **Brush** row to see the whole brush. Thin lines draw the brushes of the chosen
surfaces as the maker of the map drew them. A map keeps only the parts of a brush that other brushes did not cover, so
the lines also show the parts inside a wall or under a floor, which no surface has. A line in view is solid. A line
behind a wall, a floor or the ground is faint and dashed.

### Why a brush cannot move or change its shape

The game does not read brushes. It reads the BSP that UnrealEd built from all brushes of the map together, with
lightmaps for it. A changed brush needs that whole build again, and Cruma has no such builder. So Cruma changes only
what the saved BSP allows: the textures, what the game draws, and a whole brush that stands apart, which **Delete**
can take. A brush cannot move, turn, change its size or get new corners.

## Hide a wall or floor

1. Choose the surfaces with **Surfaces**, as above.
2. Press **Hide**. The view stops drawing them at once.
3. Find every surface that you hid in the loaded maps under **HIDDEN**, which the screen writes with their number,
   such as HIDDEN · 2. Point at a row to see the surface outlined in the view, and press **Show** to draw it again.
   With two or more, **Show all** shows every one.

> **Note:** A hidden wall still blocks the player. The game only stops drawing it: the nodes of the BSP decide what
> blocks, not the flags of a surface. The server's geodata is a file of its own.

A hidden surface does not answer a click, so **HIDDEN** is the way back to it. While every chosen surface is hidden,
**Hide** turns into **Show**. The save writes the hide into the map, unlike **Hide** of an object, which only lasts while
you edit. A later rebuild of the BSP lightmaps near a hidden wall lets light through it. The light of meshes still sees
the wall.

A hide that you saved and installed into the client is the client's own from then on. When the map loads again, Cruma
does not list it under **HIDDEN**. **Restore client** in the save review brings the surface back after the last save
of this session. After an older save, copy the files of `client-backup` back. A surface that the client itself hides
never shows under **HIDDEN**.

## Draw a surface from both sides, or without light

A sheet such as a fence, a cloth or a net may show only from one side. A floor may look too dark. Two flags of a
surface change how the game draws it.

1. Choose the surfaces with **Surfaces**, as above.
2. Under **FLAGS**, click **Two-sided**. The game then draws them from both sides, also when the camera is behind
   them.
3. Or click **Unlit**. The game then draws them at full brightness: no lightmap, no shadow and no dynamic light.
4. Click a box again to take its flag away. Each click is one Undo step for all chosen surfaces.

When only some of the chosen surfaces have a flag, its box is empty with `some` beside it, and a click gives the flag
to every one. A box that no chosen surface can take is off, and its tip says why.

> **Note:** Nothing blocks differently: the collision stays as it was. The lightmap of an unlit surface stays in the
> file, so the light comes back when **Unlit** goes. A surface that the client saves as unlit has no lightmap, so
> **Unlit** cannot be taken away from it.

A sheet that loses **Two-sided** shows only its front, in the view and in the game. From behind you look through it.

## Delete a sheet or a brush that stands apart

A sheet is a thin surface that never blocked, such as a swamp, a cloth or a net. The game walks through it. So
deleting a sheet takes it out of the game. Its baked shadow can stay, and so can a water zone or a volume under it.
Most water carries the game's water flag, and **Delete** keeps it: the panel says why.

A wall or a floor is the side of solid space, so one side alone cannot go. A whole brush can, when it stands apart: a
floor disc, a deck between two posts or a floating plate that touches no other solid. Its solid turns into air, so no
hole opens under it. By the client's code the game then neither draws it nor blocks there. This was not tried in a
game yet.

1. Choose sheets, or a side of the brush, with **Surfaces**, as above.
2. Press **Delete**, or <kbd>Del</kbd> or <kbd>Backspace</kbd>. A sheet goes at once. For a brush, the panel chooses
   and outlines every side of it, names the brush and asks. When objects stand on it or against it, it says how
   many: they may float in the air after the delete.
3. Press **Delete** again, or **Delete brush**, to delete it. <kbd>Esc</kbd>, **Cancel** or another click keeps it.
4. Look under **HIDDEN** for a deleted sheet or brush. **Show** brings a sheet back, and **Bring back** a brush.
   **Revert** on a side of a deleted brush does the same. With two rows or more, **Show all** brings every one back.

Most brushes cannot be deleted. Then **Delete** is off, and a line under the buttons says why as soon as you choose
the surface. A floor that looks like one surface is often a side of a large brush, so the line names the brush and its
number of sides. A brush that touches other solid would leave a hole in what it
stands on or leans against. A brush cut out of the solid would fill its room with solid. A brush with another brush,
another surface or an object inside it stays too, and so does a brush with a sheet on it, such as a decal or a net. So
does a brush whose side you hid, retextured or gave a flag: give that back first.

With sheets, brushes and walls chosen together, **Delete** deletes the sheets and the brushes and says how many
surfaces stay. A zone portal stays: an invisible surface that cuts the level into zones, so the game knows what to
draw. The sky and water stay too, as the game draws them by rules of its own. So does an antiportal, a surface that
hides what lies behind it in the game also when it is invisible, and **Hide** keeps it as well.

> **Note:** The server's geodata still blocks the space of a deleted brush. Install the saved map first, then build
> the geodata of that tile again with the [Geodata tool](guide:geodata/get-geodata) and install it. The baked shadow
> of the brush stays in the lightmaps: a light that you move bakes its light again without it. Objects that stood on
> the brush may float, and the Check lists them. A light cannot move into the space of a deleted brush.

## Give an object another material

1. Select the object with **Select**, <kbd>Q</kbd>.
2. Under **MATERIALS**, click the row of a slot. A list of materials opens beside the panel.
3. Choose who gets it: **This object**, the selected ones, or every object with this mesh in the loaded maps.
4. Point at a row to see it on the object, and click it to give it.

**Mesh default** gives the slot back the material of the mesh. The eyedropper at the end of a slot's row, or
<kbd>Alt</kbd> and a click in the view while the list is open, takes the material of another object.

## Import a picture

1. Drop a PNG, JPEG, BMP or DDS file on a slot's row, on the **MATERIAL** row of the Surfaces tool, or on an object in
   the view. Or choose **Import an image…** in the list of materials.
2. Check **Package**, `CrumaTextures` at first, and **Texture**, its name.
3. If the sides of the picture are not powers of two, such as 256 or 512, press **Resize to**, which names the nearest
   size. The game takes sides from 1 to 4096.
4. Press **Import**.

Cruma makes a new texture package for it. The save puts that package into the saved folder.

> **Note:** Give the package a name the client does not have yet, and never rename its file later: the package is
> made for the name of its file.

### If it went wrong

- A surface does not change, and the panel says why: water, sky and a few other surfaces keep their texture, and water
  and sky cannot be hidden.
- A hidden surface does not answer a click: show it again under **HIDDEN**.
- **Unlit** is off for a surface that the client saves as unlit: it has no lightmap, so the game could not light it.
- **Delete** is off: the chosen surface is the side of a solid wall or floor whose brush cannot go. The line under the
  buttons says why. Use **Hide**.
- A side of a deleted brush takes no new texture and no hide: bring the brush back first.
- **Choose brush** or **Brush wireframe** is off: the chosen surface names no brush, and the **Brush** row says
  **None**.
- A row of **Select** is off: choose a surface first. **All of the map** and **Reverse** with nothing chosen need the
  camera over a loaded map.
- After a rebuild of the geometry in UnrealEd the old texture and the old flags come back, and a hidden or deleted
  surface very likely shows again: Edit changes what the game reads, not the brushes the editor builds from. The Check
  lists such surfaces.
- **Import** is off: a side is not a power of two, or a name is missing.
