---
title: Turn map events on and off
kind: howto
summary: Choose a start, switch events, castles and zone states, and read the marks in the world.
tool: events
next: events/effects-by-time
related: events/what-are-map-events, terms/map-event, terms/zone-state
---
Show or hide the effects that a server turns on and off, in the view and in your exports.

### You need

- A world with tiles that have [map events](guide:terms/map-event). Towns, castle towns and some dungeons have them.
- For **Server start**, the server's [script folder](guide:terms/script-folder): **Server scripts folder** on the page
  of the client in [Settings](guide:start/settings).

## Steps

1. Open **Map events** on the tool rail.
2. Choose a start at the top. **All shown** shows every effect. **Game start** hides them as the game does until the
   server turns them on. **Server start** shows what the server turns on when it starts.
3. Open **EVENTS** and the tile under your camera. Each event has a check box: turn it off or on.
4. Point at a mark in the world and press <kbd>T</kbd> to switch the event of that effect.
5. Under **CASTLES**, choose **Both**, **Light**, **Dark** or **None** for every castle at once.
6. Under **ZONE STATES**, open the group of a map, such as `25_18_Classic`. The map under your camera is open
   already. Choose **As the map** or a state of that map, such as **State 1**. The choice is for that map only: the
   other maps keep theirs. Each row says what its state changes in the map, and **Showing**, **Hidden** or how much
   of it shows now, such as "3 of 151". In Essence a zone in state 0 shows none of the meshes and effects of a state,
   so a map whose zones start there is in **State 1** until you choose another.
7. Press **Reset** to go back to the start. It also clears the choice of every map.

When it worked, the effects come and go in the view, and the line under the start says what differs from it, such as
"2 events off".

[Show the nearest map event](action:events-show-nearest)

## The marks in the world

While the panel is open, a dot marks each effect of an event, a castle or a set of hours.

| Mark | Means |
|---|---|
| Filled dot | An effect that shows now |
| Hollow dot | An effect that does not show now |
| Fainter dot | A wall or the ground stands between you and the effect |
| Bright rings | The effects of the event under the pointer, or of the selected one |

Click a mark to select its event, and double-click it to frame it. The marks reach 20,000 units from the camera, and
they go away with the panel.

### If it went wrong

- "No map events in the loaded tiles.": fly to a town or a castle town, or load its tile.
- **Server start** is missing: set **Server scripts folder** on the page of the client in Settings. In the Viewer,
  **Save and load again** there opens the world with it.
- **CASTLES** is missing: the client is older than P152 and has no castle effects.
