---
title: Play a cut scene
kind: howto
summary: Find a cut scene of the client in the Scenes panel, play it in the world, and stop it.
tool: scenes
next: scenes/video-director
related: scenes/what-is-a-cut-scene, scenes/export-a-scene, terms/cut-scene
---
Watch a cut scene of the client in the world, as the game plays it.

### You need

- A world of a client that has cut scenes, from Gracia Final on.

## Steps

1. Open **Scenes** on the tool rail. Its icon is a clapperboard.
2. Type part of a name in **Search scenes by name**, such as `freya` or `tiat`.
3. Read the line under a name: it says where the scene plays and how long it takes, such as `Tile 23_14 · 53.5 s`.
4. Click the row to play the scene. The camera follows it, and the keys and the mouse wait until it ends.
5. Press <kbd>Esc</kbd>, or the stop button at the top of the panel, to end it early.

When it worked, the row says `Playing` with how far the scene is, and a thin line under the row grows. At the end the
camera stays where the scene left it, level, and you can fly again.

[Play an example cut scene](action:scenes-play-example)

## What the rows say

| The row says | What a click does |
|---|---|
| `Tile 23_14 · 53.5 s` | Plays the scene here |
| `Tile 23_14 not loaded · 53.5 s` | Opens that tile in place of the tiles that are loaded, then plays the scene |
| `On the screen · 40.0 s` | Plays a slide story, which has no place in the world |

The loop button beside the stop button plays a scene again and again. The Pause and the speed of the Viewer hold and
slow a scene too, with its sound.

With the script folder of an L2OFF server in the client's settings, **FROM THE SERVER** stands above **CUT SCENES**.
It lists boss scenes that no client holds. They play like the others.

### If it went wrong

- The row says `Reading the scene`: the scene first reads its figures, skills and sounds. It waits up to 15 seconds.
- The panel lists no cut scenes: the client has none, as Interlude. **MY SCENES** and the video director still work.
- A click on a row closed the tiles you had open: the scene plays on other tiles, and its row said `not loaded`.
