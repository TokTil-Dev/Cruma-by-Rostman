---
title: Move around the world
kind: howto
summary: Fly with the mouse and W A S D, switch Explore, Inspect and Analyze, and jump to a place with the world map.
next: start/how-a-map-is-built
related: terms/workspace, start/open-a-world, view/inspect-an-object
---
Fly the camera through the world, choose how much of the interface you see, and jump to any place.

## Fly the camera

1. Hold the right mouse button and move the mouse to look around.
2. Press <kbd>W</kbd> <kbd>A</kbd> <kbd>S</kbd> <kbd>D</kbd> to move, <kbd>E</kbd> to rise and <kbd>Space</kbd> to sink.
3. Hold <kbd>Shift</kbd> to fly ten times faster, or <kbd>Alt</kbd> for fine moves.
4. Press <kbd>1</kbd> to <kbd>5</kbd> for a speed of 1000 to 5000 [units](guide:terms/world-unit) a second, or <kbd>+</kbd> and <kbd>-</kbd> to
   change it. **Speed** in the status bar at the bottom shows it, from 100 to 50,000.
5. Press <kbd>Left</kbd> or <kbd>Right</kbd> to jump to the previous or the next loaded [tile](guide:terms/tile).

The arrow at the right end of the status bar tucks it into the foot of the tool rail, where it waits as a compass, and
the view gets its room. Point at the compass to read the place and the speed, and click it to bring the bar back.

The wheel, while you hold the right mouse button, changes the speed too. <kbd>F1</kbd> opens **Controls & shortcuts**, the
list of every key: the keys of the open tool come first, and the field at its top searches them.

The view tilts up to 89 degrees up or down and stops there, so it never turns over. When the mouse turns the view too
fast or too slowly, open **Settings**, then **Interface**, and change **Mouse sensitivity**: 1 is the usual speed, 0.5
half of it and 2 twice as fast, from 0.1 to 5.

## Explore, Inspect and Analyze

The top bar has a button for each [workspace](guide:terms/workspace). <kbd>F2</kbd> goes through the first three.

| Workspace | What you get |
|---|---|
| **Explore** | The world, with nothing in the way. |
| **Inspect** | A click on an object selects it, and the **Object** panel shows what it is made of. |
| **Analyze** | Inspect, with **Objects** at the left, a list of every object, and a tray of **Animation** and **Compare A/B**. |
| **Edit** | Change the map. The tiles load again with objects you can move, so it takes a moment. |

[Switch to Inspect](action:start-mode-inspect)

## Jump with the world map

1. Press <kbd>M</kbd>, or the button with the tile's name in the top bar.
2. Click a place on the map. The camera goes there and keeps its height.
3. To go to exact coordinates, type three numbers such as `83336 147972 -3204`, or a tile such as `22_22`, into the field
   **Go to X Y Z or tile X_Y**, and press **Go**.
4. Press <kbd>M</kbd> or <kbd>Esc</kbd> to close the map.

The wheel zooms the map, and dragging with the right mouse button moves it. **Find a place on the map…** finds the
named places of the client, such as towns. Shaded tiles are not loaded.

[Open the world map](action:start-open-world-map)

### If it went wrong

- The keys do nothing: a text field has them. Click into the world, then fly again.
- The world map says "No map image is available for this client.": go back to the start screen and press
  **Create map image** at the top right of its map.
- The camera went to a shaded tile and shows nothing: that tile is not loaded. Load it from the start screen.
- The world map does not open: a map with a name of its own, such as a lobby, and **Effects** without a world have no
  world map.
