---
title: Edit cells
kind: howto
summary: Open or close the sides of cells, paint ground or obstacles, add a floor, change heights, then save or export.
tool: geodata
tab: edit
next: geodata/find-problems
related: geodata/formats, terms/nswe, terms/layer
---
Change what the server knows about a place, then write the changed regions for your server.

> **Warning:** Geodata editing is experimental. Keep a copy of the folder, or leave **Back up the files it replaces** on
> when you save into it.

### You need

- A geodata folder open in the **Geodata** panel.
- The camera over the place you want to change.

## Steps

1. Choose **Edit**, or press <kbd>B</kbd>. Your first change starts an editing session.
2. Choose a tool: **Edges** opens or closes one side of a [cell](guide:terms/cell), **Block** paints obstacles, **Open** paints ground to
   walk on, **Floor** adds a floor where there is none, **Height** moves floors up or down, and **Rebuild** builds
   cells again from the client.
3. Change the cells in the view. With **Edges**, point near a side until it lights up, then click. With the others,
   drag over the ground.
4. Undo a change with <kbd>Ctrl+Z</kbd>, or <kbd>Cmd+Z</kbd> on a Mac.
5. Press **Save…** in the bar at the bottom of the panel when you are done.
6. Under **Save and export**, press **Save to geodata folder** to change the folder itself, or **Export to folder** to
   write a copy.

When it worked, a green line under the button says what was written, and **Show folder** opens the folder.

A brush changes only the floors near the height of your first click, so floors above and below stay as they are.
**Height tolerance** sets how near.

[Show a cell with a closed side](action:geodata-show-closed-side)

## Go on with a floor

The brushes above change floors that exist. **Floor** makes a floor where there is none: in front of a doorway,
along a bridge, or past the edge of a platform.

![A floor from the side: the cells under the drag get a layer at its height, and their layers on the ground stay.](diagram:geodata-floor)

1. Choose **Floor**.
2. Press on the floor you want to go on with. Its height shows next to the brush.
3. Drag out over the cells that have no floor at that height, and release.

Each of these cells gets a new [layer](guide:terms/layer) at the height of the first click. You can walk from the old
floor onto the new one and back at once. The floors above and below stay as they are. A cell that has a layer within
**Height tolerance** of that height keeps it and gets no new one.

**Add layer above** under **SELECTED CELL** adds one layer over the selected cell only.

## Keep the server's paths right

Monsters find long paths on the pathnode graph, not on the cells. After you change cells for an
[L2OFF](guide:terms/l2off) server, turn on **Rebuild pathnode** when you save, or **With pathnode** when you export.
[L2J](guide:terms/l2j) servers find paths from the cells and need no graph.

### If it went wrong

- A click on a side changes nothing: point closer to the side, away from the corners and the middle of the cell.
- The wrong floor changed: undo, lower **Height tolerance**, and start the stroke on the floor you want.
- **Floor** added nothing: every cell under the stroke has a layer near that height already. Start on the floor to go
  on with, and drag past its edge.
- **Save to geodata folder** stays grey: there is nothing to save yet, or **Rebuild pathnode** waits for the server's
  zones.
- You want to go on another day: **Save patch** writes only your changes into a small file, and **Load patch** applies
  it later to the same folder.
