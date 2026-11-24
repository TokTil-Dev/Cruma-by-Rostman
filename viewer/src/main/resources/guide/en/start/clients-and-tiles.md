---
title: Clients, chronicles and tiles
kind: concept
summary: The client folder Cruma opens, the versions of the game it comes in, and the tiles its world is cut into.
next: start/open-a-world
related: start/open-a-world, start/right-column, terms/tile, terms/chronicle, terms/client
---
Before Cruma can show anything, it needs a client, and you choose which part of its world to load. This article
explains the three words the start screen uses for that.

## The client folder

A [client](guide:terms/client) is the folder of the game on your computer. Cruma needs the folders `Maps`, `Textures`,
`StaticMeshes` and `system` inside it, and at least one world map such as `Maps/22_22.unr`. Cruma remembers each client
you open and lists it under **CLIENTS** on the start screen, so you choose a folder only once.

## Chronicles

Lineage II came out in many versions, called [chronicles](guide:terms/chronicle). Their files differ a little, but
Cruma reads what the files hold and never asks which one you have. It was tested with C1, Interlude, Gracia Final,
High Five, P152 and P162.

Some newer clients hold more than one world, and the game picks one by the kind of server. Such a client shows its
worlds at the left of the row over its map on the start screen. **World** on the page of the client in
[Settings](guide:start/settings) is the same choice.

- **Main**, the main world, reads `22_22.unr`.
- **Classic**, for Classic servers, reads `22_22_Classic.unr` where the client has it, and `22_22.unr` for the other
  tiles. A client offers it when it has Classic maps, as P152 and 162 do.
- **Essence** shows the maps of Classic, with NPCs, items and skills of its own. Its tables end in `_ClassicAden`, the
  client's name for these servers, such as `Npcgrp_ClassicAden.dat`. A table that has no such file comes from the
  Classic file, and then from the main one. A client offers it when it has such tables, as Essence clients do.

## Tiles

The world is cut into square [tiles](guide:terms/tile), each 32768 [world units](guide:terms/world-unit) wide. A tile is
named by its column and its row, such as `22_22`. The first number grows to the east, the second to the south, and tile
`20_18` starts at X 0 and Y 0. Each tile is one file in `Maps`. On the map of the start screen the column numbers
stand along its top and the row numbers along its left, and the corner where they meet says which is X and which is Y.

![A part of the grid of tiles. Each square is one map file, named by its column and row.](diagram:start-tile-grid)

The names run from about `11_10` to `26_25`, and the Classic worlds of P152 and 162 reach column 27. No client has a
map in every square: the sea has none.

## Why to load few tiles first

Each tile takes memory and time to load. On a fast computer with a P152 client, nine tiles around Giran hold about
7 GB of memory once they are loaded. The whole world, 227 tiles, needs up to about 29 GB while it loads and about 20 GB after, and takes about 18
seconds. Start
with one tile, and add more once you know your computer has room for them.
