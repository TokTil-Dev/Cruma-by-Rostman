---
title: Numbers and limits of Edit
kind: reference
summary: The steps, sizes, defaults and limits of Edit's tools, in world units, degrees and points of the grid.
tool: edit
related: edit/select-and-move, edit/sculpt-the-ground, edit/lights, terms/world-unit
---
All lengths are in [world units](guide:terms/world-unit): one unit is 4 cm, and a tile is 32768 units wide.

## Select and move

| What | Value |
|---|---|
| **Grid** step of a move and of the arrow keys | 16 units at first, from 0.1 to 8192. <kbd>Shift</kbd> moves ten steps |
| **Angle** step of a turn | 15 degrees at first, from 0.1 to 180 |
| **Scale** step | 0.1 at first, from 0.001 to 10 |
| How far Duplicate puts a copy | 64, 0, 0 units at first, set in **Duplicate offset…** |
| A click again on the same place takes the object behind | Within 4 pixels and 1.5 seconds |
| Snapping to a neighbour, while <kbd>V</kbd> is held | Within 8 units of its side, for objects up to 512 units away |
| An object stands on the terrain for **Stick to ground** | Its bottom is at most 8 units over it |
| **Randomize…** of several objects | A turn from 0 to 360 degrees, a size from 0.9 to 1.1 |
| Marks of effects in the view | Within 12,000 units of the camera, the nearest 400 |
| What a swinging object carries | The objects that one of its first 12 tag names names |
| Names that tie a copy of a carrier to the copies of what it carries | `CrumaCarry`, the map's name and a number, such as `CrumaCarry2018n1` in `20_18` |

## Place

| What | Value |
|---|---|
| **Random scale** | 0.9 to 1.1 at first |
| **Spacing** of the brush | Twice the mesh's width at first, from 1 to 100,000 units |
| **Jitter** of the brush | 0.3 at first, from 0 to 1 times the spacing |
| Pictures under **RECENT** without a mesh | Up to 6 |

## Terrain

| What | Value |
|---|---|
| Points of the [heightmap](guide:terms/heightmap) | 256 by 256 a tile, 128 units apart |
| **Size** of the brush | 1 to 32 points. **S** 4, **M** 8, **L** 16. 4 at first |
| **Speed (units per second)** of Raise | 400 at first, from 20 to 4000 |
| **Strength** | 0.50 at first, from 0.05 to 1. At 1 a brush works in about a quarter of a second |
| **Ramp width** | 512 units at first |
| Noise | **Amplitude** 64 units, **Scale** 512 units at first |
| Select | **Inner** 0, **Outer** 512 and **Move by** 64 units at first |
| **Slope above** of Fill by slope | 35 degrees at first |
| **From** and **To** of Fill by height | 0 and 500 units at first |
| Terrains open at once | Up to 9 |
| The Terrain of the next map opens by itself | When the brush comes within its **Size** and 8 points more of the edge |

## Lights

| What | Value |
|---|---|
| Reach of a light | 25 × (**Radius** + 1) units. **Radius** runs from 0 to 255 |
| A new light | **Radius** 16, which reaches 425 units, 64 units from the surface |
| A new spotlight | **Radius** 28, which reaches 725 units, 256 units from the surface |
| **Distance from the surface** | 0 to 512 units |
| **Brightness** | 0 to 1024. Above 255 the colour washes out to white |
| **Hue** and **Saturation** | 0 to 255, and 0 to 100 percent |
| Rows of the list of lights before it scrolls | 5 |

## Surfaces and images

| What | Value |
|---|---|
| Steps of **PAN** | 1, 4, 16 or 64 texels |
| Steps of **TURN** | 45 or 90 degrees, either way |
| Sides of an imported picture | A power of two from 1 to 4096, such as 256 or 1024 |

## Save and Check

| What | Value |
|---|---|
| Folder of a save | `Documents/Cruma/Exports`, a new folder with the date and time, until you choose another |
| A mesh floats or sinks for the Check | More than 8 units off the terrain, more than in the client, within 2048 units of a change |
| Meshes whose light a save builds again | The changed ones, and the others within 2048 units of a change |
| Steps listed by a right click on Undo | Up to 30, and it scrolls after 12 |
