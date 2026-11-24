---
title: Open a world
kind: howto
summary: Choose your client on the start screen, pick a few tiles on its map, and open them in the Viewer.
next: start/moving-around
related: start/clients-and-tiles, start/right-column, start/moving-around
---
Open a part of your client's world in the Viewer.

### You need

- A Lineage II client on this computer.

## Steps

1. Press **Add client…** under **CLIENTS** at the left of the start screen, and choose the folder of your client. Or
   drop the folder onto the window. A client you added before stays in the list: click its name.
2. Click a [tile](guide:terms/tile) on the map of the client. Drag over the map to choose several. Hold
   <kbd>Shift</kbd>, <kbd>Ctrl</kbd> or <kbd>Cmd</kbd> as you click to add a tile or take it away.
3. Type a place or a tile, such as `Giran` or `22_22`, into **Find a place or tile** to choose it by name instead.
   <kbd>Enter</kbd> takes the first result.
4. Choose how much to load around the tile in the bar under the map: **1 tile**, **3×3**, **5×5** or **World**. Start
   with one to nine tiles: they load fast. In a narrow window the sizes are in the menu of **More**, the last button of
   the bar.
5. Keep **View** chosen, and press **Open** or <kbd>Enter</kbd>.

A double-click on a tile opens it at once. Each start goes into **RECENT** at the left, and a click on its row opens it
again at once. In a wide window a column of panels stands right of the map, and shows what the chosen tiles hold:
see [the right column](guide:start/right-column).

The Viewer opens over the tiles you chose, and shows them as they load. The camera starts at the place you took from
the search. Without one, it starts at **Location or coordinates** on the page **Places** of
[Settings](guide:start/settings) when that lies in the chosen tiles, else
at the place that names the tiles in the bar, else high over the first tile. A button in the top bar names the tile
under the camera, and opens the world map.

[Open the world map](action:start-open-world-map)

### If it went wrong

- The start screen says "Choose the game folder containing Maps, Textures, StaticMeshes and system.": neither the
  folder nor the folder around it holds all four. Choose the folder that holds them.
- It says "No world maps found.": the client has no tile such as `Maps/22_22.unr`, so Cruma cannot open it.
- The map shows no picture, and "No map image" stands over it: **Create map image** beside it draws one, tile by tile.
- **Open** stays grey: no tile is chosen.
- A row of **RECENT** is grey: its client or its tiles are gone. Its tooltip says what is missing.
- The Viewer is slow, or runs out of memory: go back with **Main menu** in the Viewer menu, and load fewer tiles.
