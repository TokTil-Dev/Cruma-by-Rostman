---
title: Change the light, sky, water and sound
kind: howto
summary: Scene settings change what the view draws, with its light, sky, fog, effects, water and sound.
tool: scene
next: view/inspect-an-object
related: view/read-performance, start/how-a-map-is-built, terms/lightmap
---
Change how Cruma draws the world. Nothing in the client changes, and **Reset to defaults** brings back how it started.

## Steps

1. Press <kbd>F3</kbd>, or the first button of the tool rail at the right. **Scene settings** opens.
2. Choose a tab with the row of icons at its top. The name of the tab stands under the row.
3. Move a slider or press a switch. The view changes at once.
4. Press **Reset to defaults** at the bottom to go back to the start.

[Open Scene settings](action:view-open-scene)

## What each tab holds

| Tab | What it changes |
|---|---|
| **World** | What is drawn: **Textures**, **Terrain**, **Static Meshes** and **CSG Geometry**, how far details and grass reach, and **View Distance** |
| **Lighting** | **Custom Lighting** adds Cruma's own light and shadows to the light of the map |
| **Atmosphere** | **Time of day** from 0 to 24 hours, the **Sky Preset**, the **Moon**, the size of the sun and **Fog Strength** |
| **Effects** | **Gamma**, **Bloom**, **Particle Distance** and **Emitter Distance**: **Authored (Client)** or **Custom** |
| **Water** | **Use Realistic Water Effects** for reflections and waves, or the water as the client draws it |
| **Audio** | **Music** and its **Volume**, the gaps between tracks, and the **Ambient** sounds with **Max Voices** |

**Time of day** starts at 13 hours. Some effects of the client show only at some hours, and **By clock** in
[Map events](guide:events/effects-by-time) follows the hour you set.

In Essence some places have their own time of day: a hall that is always at night, or a region whose hour changes
with the [zone state](guide:terms/zone-state) chosen for its map. With the camera in such a place the view shows that hour, and a
line under **Time of day** says so, such as "This place has its own time of day, 17:00, in the light TimeEnv9.
Outside it the hour above shows again.". Your own hour is kept and comes back when the camera leaves.

## Lit, Unlit and Wire

The button of the view in the top bar, such as **Free · Lit**, chooses how surfaces look:

- Lit shows the scene as the game lights it, with [lightmaps](guide:terms/lightmap), sun, shadows and fog.
- Unlit shows every surface in its own colours, with no light, shadows or fog.
- Wire shows every triangle as lines, in one colour for each kind: meshes, [BSP](guide:terms/bsp), [terrain](guide:terms/terrain) and water.

The same button chooses the camera: Free, or the flat views Top, Front and Side. <kbd>O</kbd> switches the top view,
except in [Effects without a world](guide:effects/effects-mode), which has only the free camera.

**Static Meshes** also shows and hides the figures that some maps place, such as flags and the seals of the lobby,
with the effects that their scripts hang on them.

## How far effects show

**Particle Distance** says how far from the camera effects show, and 0 turns them off. With **Emitter Distance** at
**Custom**, as it is at first, every effect shows up to that distance. With **Authored (Client)** each effect keeps
the distances that its map gives it, as in the game, so an effect that the game hides at a distance is hidden here
too.

In High Five, P152 and 162 the game does not go on with an effect of the map that nobody sees, and the viewer does
the same: such an effect stands still while you look away and goes on when you look back. With **Authored (Client)**
this also holds for an effect that is in view but outside its own distances. The effects of skills and of the
**Effects** tool always go on.

## Glows of lights

Many lamps, fires and braziers have a glow over them, as in the game: a soft light that faces you, such as the haze
over the braziers of a lobby. It fades in when you can see the light and out when a wall comes between. The glows go
with the effects: they show in Lit and Unlit, not in Wire or the flat views, and **Particle Distance** at 0 turns
them off with the effects.

## Far models

The client keeps a simpler model of many buildings, bridges and trees, and draws it from far away in place of the
detailed one. Some trees end as a flat card that faces you. A far model is not only coarser. It can bring parts
that the map draws itself near you: the far model of a bridge of the Elven Village has its own deck, lighter by day
than the deck of the map, so the bridge changes colour where the models change.

**Impostor distance** on the **World** tab moves every such change away from the camera. At 1 the models change at
the distances of the client, and at 8, as it starts, eight times farther. Higher values draw more detail far away.

### If it went wrong

- The fog is gone: **Fog Strength** at 0% turns it off, and 100% keeps the fog of the map.
- The effects and the glows of the lamps are gone: **Particle Distance** at 0 turns them off.
- No music plays: the camera may stand in no music zone. **NOW PLAYING** on the **Audio** tab says what plays.
- The view is slow: lower **Grass distance** and **Impostor distance**, or turn **Custom Lighting** off. See
  [Read the Performance panel](guide:view/read-performance).
- A bridge or a building changes its colour or its shape when the camera moves away: the client's far model stands
  there. Raise **Impostor distance**.
