---
title: Cells, layers and NSWE
kind: concept
summary: How geodata stores the ground: regions of blocks, blocks of cells, and in each cell floors with four sides.
tool: geodata
next: geodata/get-geodata
related: geodata/formats, terms/nswe, terms/layer, terms/block
---
Geodata describes the ground in squares of three sizes, one inside the other. Then it says, for each small square, where
its floors are and which way you can walk.

![A region holds 256 by 256 blocks, a block holds 8 by 8 cells, and a cell is 16 units wide.](diagram:geodata-sizes)

## Regions, blocks and cells

- A [region](guide:terms/region) is one file. It covers one [tile](guide:terms/tile) of the map, 32768 units on each
  side, and has its name, such as `22_22`.
- A [block](guide:terms/block) is 8 by 8 cells, 128 units wide. A file stores the world block by block.
- A [cell](guide:terms/cell) is 16 by 16 units, the smallest place the server knows. A region has 2048 by 2048 cells.

## Layers: floors over each other

Most cells have one floor. A cell under a bridge, in a house with an upper floor, or over a cave has more than one. Each
floor is a [layer](guide:terms/layer) with its own height. When a character stands in the cell, the server takes the
layer at its feet.

![A bridge over the ground, seen from the side: one cell with two layers.](diagram:geodata-layers)

[Show a place with two floors](action:geodata-show-layers)

## NSWE: the four sides

Each layer says which of its four sides a character can cross: north, south, west and east. This is
[NSWE](guide:terms/nswe). When a character walks from one cell to the next, the server looks at the side it leaves. An
open side lets it go on. A closed side stops it, as a wall does.

![One cell from above: three sides open, the east side closed.](diagram:geodata-cell)

[Show a cell with a closed side](action:geodata-show-closed-side)

A side can be closed in one cell and open in the cell next to it. Then a character crosses that edge one way only.
Built geodata does this on steep slopes, which you can walk down but not up.

![A one-way edge: B to A goes on, A to B stops.](diagram:geodata-one-way)

## Heights

A height is in world units, with Z pointing up. Geodata keeps heights in steps of 8 units, from `-16384` to `16376`.
