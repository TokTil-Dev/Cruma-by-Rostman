---
title: Geodata formats
kind: reference
summary: The L2OFF and L2J region files, the three kinds of block, the sizes, and how close a build comes.
tool: geodata
related: geodata/get-geodata, geodata/cells-layers-nswe, terms/l2off, terms/l2j
---
Both formats hold the same [cells](guide:terms/cell). They differ only in how a file writes a [block](guide:terms/block). Cruma reads both, edits both, and
saves a file in the format it had. **Export to folder** can convert a whole folder from one format to the other.

## Files

| | L2OFF | L2J |
|---|---|---|
| Servers | NCsoft's servers | [L2J](guide:terms/l2j) Server and servers built on it |
| File of a [region](guide:terms/region) | `22_22_conv.dat` | `22_22.l2j` |
| Header | 18 bytes | none |
| Pathnode graph | `pathnode.idx` and `pathnode.bin` | not used |
| Built into | `geodata` in the client | `geodata_l2j` in the client |

When a folder has a region in both formats, Cruma reads the [L2OFF](guide:terms/l2off) file.

## Sizes

| Thing | Size |
|---|---|
| Cell | 16 by 16 world units |
| Block | 8 by 8 cells, 128 units |
| Region | 256 by 256 blocks, 2048 by 2048 cells, 32768 units |
| Height | `-16384` to `16376`, in steps of 8 |
| Server world | regions `10_10` to `29_26`, 20 by 17 |

The west edge of region X lies at (X - 20) × 32768, and the north edge of region Y at (Y - 18) × 32768. So `20_18`
starts at X 0 and Y 0. X points east and Y south.

## Blocks

![The three kinds of block: one height, one floor in each cell, or several floors.](diagram:geodata-blocks)

| Kind | L2OFF writes | L2J writes |
|---|---|---|
| Flat | `0`, then the height twice | type `0`, then the height |
| One floor each | `64`, then 64 layers | type `1`, then 64 layers |
| Several floors | the total, then for each cell a count and its layers | type `2`, then for each cell a count and its layers |

A flat block is open on every side. L2J reads at most 125 layers in a cell, and writes a cell without layers as one
closed layer at `-16384`. Only the first height of an L2OFF flat block is used.

## A layer in 16 bits

Each layer is one number of 16 bits. The 4 low bits are the open sides: east `1`, west `2`, south `4`, north `8`. The
12 high bits are the height times two, which is why heights move in steps of 8.

## How close a build comes

Cruma's builder repeats NCsoft's own. From a 162 client, a typical region has 99.6% of its cells the same as the 162
server's file, and 154 of 224 regions match in 99% of their cells or more. C4 and Gracia servers have files from an
older builder, so builds from those clients differ in about 1% more cells, up to 3%.
