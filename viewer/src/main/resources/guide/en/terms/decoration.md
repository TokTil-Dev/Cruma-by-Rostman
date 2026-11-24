---
title: Decoration
kind: term
summary: A mesh such as grass that a Terrain scatters over itself, or that a map keeps placed on a static mesh.
related: edit/paint-the-ground, terms/terrain
---
A Terrain keeps a few decoration layers. Each has a mesh, such as a tuft of grass or a small bush, and a density map: a
grey picture over the tile, brighter where more of the mesh grows. The game scatters the meshes when it loads the map,
so nobody places them one by one. **Decorations** of the Paint tool paints the density map. See [Paint the
ground](guide:edit/paint-the-ground).

A [static mesh](guide:terms/static-mesh) can have decorations too, such as the ferns on a forest floor that is a
mesh. The map keeps each of them where it was placed, so they stand the same every time. They show and hide with
their mesh, and **Grass distance** in Scene settings says how far they are drawn. When you move, turn or scale the
mesh in Edit, they go with it, in the view and in the saved map. The Paint tool does not change them, and a copy of
the mesh has none.
