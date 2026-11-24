---
title: What is geodata
kind: concept
summary: The server's own map of where characters can stand and walk, as the client never tells it.
tool: geodata
next: geodata/cells-layers-nswe
related: geodata/get-geodata, geodata/read-the-overlay, terms/geodata
---
When you play Lineage II, your client draws the world: the ground, houses, trees and walls. The server never sees any of
that. It still has to decide where a character may stand, whether a monster can reach you, and whether an arrow hits.
Geodata is what it uses to decide.

![The client draws the world from meshes. The server keeps only cells, each one walkable or blocked.](diagram:geodata-client-server)

## What is in it

Geodata cuts the world into small squares called [cells](guide:terms/cell), each 16
[world units](guide:terms/world-unit) wide. For each cell it keeps the height of the floor, and which of its four sides
a character can cross. A cell under a bridge keeps two floors: one on the bridge and one on the ground under it. There
are no pictures, textures or meshes in it. There are only these numbers.

## Where it comes from

Geodata is made from the maps of the client, once, before a server starts. NCsoft's servers and L2J servers read
different files, [L2OFF](guide:terms/l2off) and [L2J](guide:terms/l2j), but both hold the same cells. Each file covers
one [region](guide:terms/region), a square of the map 32768 units wide.

## What goes wrong without it

Without geodata, a server lets characters walk through walls and float over valleys, and monsters run at you in a
straight line across a river. With wrong geodata, a character gets stuck at the foot of the stairs, or falls through a
floor that the client shows.

## What Cruma does with it

The **Geodata** panel draws the cells over the world, as the server sees them. It reads a folder of geodata from a
server, or builds one from your client. You can follow a route as the server would, find places that trap characters,
and fix the cells. Nothing is written until you build, save or export.

[Open the Geodata panel](action:geodata-open-panel)
