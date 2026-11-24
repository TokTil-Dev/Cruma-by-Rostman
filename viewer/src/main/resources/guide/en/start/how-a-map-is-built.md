---
title: How a map is built
kind: concept
summary: A tile is made of terrain, BSP, static meshes and actors, with light, effects and sound, read from packages.
next: start/client-and-server
related: terms/package, terms/terrain, terms/bsp, terms/static-mesh, terms/actor
---
A town in Cruma looks like one picture, but the client builds it from layers, and each layer comes from its own kind of
file. Knowing the layers helps you read the Object panel, choose what to draw, and see why an export holds what it holds.

![The layers of a tile from the ground up, and the packages that hold each one.](diagram:start-map-layers)

## The layers

- [Terrain](guide:terms/terrain) is the open ground: a grid of heights, painted with several textures such as grass,
  sand and rock.
- [BSP](guide:terms/bsp) is the built level: walls, floors, bridges and the insides of buildings. Its light is baked
  into [lightmaps](guide:terms/lightmap), so it costs nothing while you play.
- [Static meshes](guide:terms/static-mesh) are the props on top: trees, rocks, fences, lamps and furniture. One mesh
  can stand in many places. From Chronicle 3 on, some swing by themselves, such as the hanging bowls of a lobby, the
  gondolas of Heine, the lanterns with their flames or the balloons of Fantasy Isle, and the flames and glows hung on
  them swing with them. Some flying creatures of Gracia carry their heads as they fly. Others
  turn round and round, such as the windmills of Dion, the rings of the big head of Fantasy Isle, which turn on their
  swinging stand, and some effects with them. They all stop while the scene is paused, follow the speed of
  **Animation**, and stand as the map places them in Capture. Edit moves them as other meshes, and they go on moving
  from their new place.
- [Actors](guide:terms/actor) are everything placed in the map. Each mesh, light, sound and effect is an actor with a
  place, a turn and a size.
- Lights, effects and sounds are actors too. Effects are [particles](guide:terms/particle) such as smoke and fire.
  Some effects of P152 and 162 are placed from the game's own effect classes, such as the bursts of cannon bombs,
  and some play again after a pause, as the game plays them. In High Five, P152 and 162 an effect of the map stands
  still while you look away from it, as the game holds it, and goes on when you look back. An ambient sound plays near
  its place, the air of a field or a town plays while you are inside its volume, and the music changes with the zone
  you are in.

Every surface wears a [material](guide:terms/material), which names its [textures](guide:terms/texture), the pictures
painted on it.

## The packages

A [package](guide:terms/package) is one file that holds many things of one kind. A map file names what it uses, and the
client finds each thing in the package of that name.

| Folder | Files | What they hold |
|---|---|---|
| `Maps` | `.unr` | One [tile](guide:terms/tile): its terrain, BSP and actors |
| `Textures` | `.utx` | Textures and materials |
| `StaticMeshes` | `.usx` | Static meshes |
| `Animations` | `.ukx` | Characters and monsters, with their moves |
| `Sounds` | `.uax` | Sounds |
| `system` | `.u` | Scripts, and the classes of effects |
| `music` | `.ogg` | Music |

So a map file alone is not the whole map. A tile copied from another client looks right only when this client has the
packages it names.

## What Cruma shows of it

The **World** tab of **Scene settings** can hide a layer: **Terrain**, **Static Meshes** and **CSG Geometry**, which is
the BSP. In the **Object** panel, **Asset** on the **Source** tab names the mesh or the effect an object
shows, with its package.
