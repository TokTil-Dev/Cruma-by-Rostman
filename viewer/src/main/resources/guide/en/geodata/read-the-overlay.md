---
title: Read the overlay
kind: howto
summary: What the colours over the ground mean, and how to read one cell with Inspect.
tool: geodata
tab: inspect
next: geodata/check-a-route
related: geodata/cells-layers-nswe, terms/nswe, terms/layer
---
Read what the server knows about a place from the [cells](guide:terms/cell) drawn over the world.

### You need

- A geodata folder open in the **Geodata** panel. See [Get geodata](guide:geodata/get-geodata).

## Show the cells

1. Turn on **Show** at the top of the panel, or press <kbd>G</kbd>.
2. Turn on **X-ray**, or press <kbd>X</kbd>, to see floors behind walls and under the ground.
3. Open **View** to choose a style: **Cells** draws every cell with its grid, **Surface** a calmer fill, and **Walls**
   only what blocks.

[Show the cells here](action:geodata-show-cells)

## What the colours mean

| Looks like | Means |
|---|---|
| Green floor | A floor a character can walk on |
| Red band on one side of a cell | That side is closed: nobody crosses it |
| Short red wall that fades upward | A closed side, seen from beside the cell |
| Red cell with stripes | An obstacle: every side is closed, and nobody stands there |
| Darker step between two cells | A step of up to 48 units, as on stairs and slopes |

> **Note:** Geodata keeps one height for each cell, so it often lies a little above or below the ground the client
> draws. That is normal.

## Read one cell

1. Choose **Inspect**, or press <kbd>Q</kbd>.
2. Point at the ground. A small card beside the pointer gives the cell's height, its floors and its open sides.
3. Click the cell to select it. The compass in the panel shows its four sides: green is open, red is closed.
4. Click the same spot again for the floor below, or press <kbd>[</kbd> and <kbd>]</kbd> to step between floors.
5. Press **Slice height**, or <kbd>Z</kbd>, to dim the other floors when they hide the one you read.

Under the compass the panel says what a step out of the cell meets in each direction: the same height, a step up or
down, a closed side, or no ground. A cell with several floors lists them under **FLOORS IN THIS CELL**.

### If it went wrong

- Nothing is drawn: **Show** is off, or the camera is far from the [regions](guide:terms/region) of the folder. The line under the folder's
  name says how many regions are loaded.
- The cells end in a square around you: the overlay covers 2048 units around its focus. Change **Radius** in **View**.
- Floors hide each other: turn on **Heights**, or press <kbd>Z</kbd> with a cell selected.
