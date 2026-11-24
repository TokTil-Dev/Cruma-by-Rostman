---
title: Heightmap
kind: term
summary: The shape of the terrain, kept as a grid of heights, one for each point 128 units apart on a tile.
related: edit/sculpt-the-ground, terms/terrain
---
The ground of a tile is 256 by 256 points, and the game joins each point to its neighbours with triangles. The heights
are often kept in a texture package of their own, such as `T_22_18.utx`. Sculpting changes these numbers, nothing else.
See [Sculpt the ground](guide:edit/sculpt-the-ground).
