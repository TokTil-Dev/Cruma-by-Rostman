---
title: Capture map images
kind: howto
summary: Draw each tile from above into an image, join them into one radar map, or save a screenshot of the view.
next: export/tile-operations
related: terms/radar-map, start/where-cruma-writes, start/clients-and-tiles, start/settings
---
Make a picture of each [tile](guide:terms/tile) seen from above, and a [radar map](guide:terms/radar-map) of the whole area.

### You need

- A client chosen on the start screen.

## Steps

1. Choose the tiles on the map of the start screen, as for the Viewer. **World** in the bar under the map takes every
   tile. See [Open a world](guide:start/open-a-world).
2. Press **More**, the last button of the bar, and choose **Capture map images…**. The dialog names the tiles and
   counts them under **AREA**.
3. Choose the tile images under **TILE IMAGES**: **Write individual tiles**, **Image format** and
   **Tile resolution (px)**.
4. Choose the radar map under **RADAR MAP**: **Create radar map**, its format and its own **Tile resolution (px)**.
   The dialog says how large the radar map grows.
5. Check the **Output folder** under **OUTPUT**.
6. Press **Start capture**. It keeps the values you changed for the next capture, and a window counts the tiles as it
   draws them. **Cancel** closes the dialog and keeps none of them.
7. When it is done, press **Show folder** on the message over the map.

Capture uses the tiles chosen on the map. It has no area of its own, so choose the tiles first. With the settings
Cruma starts with, you get:

| File | What it is |
|---|---|
| `22_22.png` | One image of each tile, 4096 by 4096 pixels, 8 units a pixel |
| `world.png` | The radar map: every tile at 512 by 512 pixels, joined into one image |

They go into `Pictures/Cruma`, in a folder for each client. **Output folder** changes the folder, and
**Folder per client** puts the images of every client into their own folder in it. **Delete old images first**
removes the images of the last capture of the client before the new one starts.

The images show the figures that some maps of P152 and 162 place, such as the flags of a camp, each at the start of
what it plays. Seen from above, a flag is only a few pixels. They show no NPCs of
[NPC Spawns](guide:spawns/what-is-a-spawn).

## Tile views

**Tile views** under **OUTPUT** opens extra images of single tiles, such as the floors of a tower or a dungeon under
the ground. Each view names a **Tile** and cuts away what lies above **Top Z**, and below **Bottom Z** when you give
one. A view with **Main image** on takes the place of the tile's normal image, also in the radar map. The other views
are saved beside it, such as `23_18_floor-01`. **Hide passable surfaces** leaves out bushes, small plants and other
surfaces you can walk through.

Cruma starts with views for the 13 floors of the Tower of Insolence in `23_18`, the three floors of Cruma Tower in
`20_21` and some dungeons. Their heights were measured in a High Five client, so another client may need other
numbers. Capture skips a view of a tile that the client does not have.

## A tile with its own effects and events

A view can also change what one tile shows. Its form has **EFFECTS AND EVENTS** beside the cuts:

| Row | What it does |
|---|---|
| **Effects** | Off draws none of the particle effects of the tile, such as fires, smoke and waterfalls |
| **Map events** | **As the client** follows the choice of the client. **All shown** shows the effects of every [map event](guide:terms/map-event). **Game start** hides them, as the game does before the server sends an event |
| **Hide**, **Show** | The ids of single events. With **As the client** and with **All shown**, the events to hide. With **Game start**, the events to show. An id is a number, such as `22226292` |
| **Castle sides** | **Both**, **Light**, **Dark** or **None** for the effects of the castle in this image. With **As the client** in **Map events**, it can also follow the client |
| **Zone state** | **As the client**, **As the map**, or **State 1** to **State 10** for the [zone state](guide:terms/zone-state) of the tile's map |

To change the tile's own image, add a view of the tile and turn **Main image** on. With **Main image** off the view
is one more file, and the tile's own image stays as it is. Under **As the client**, a line says what the client has
now, such as "now: all shown".

Under **As the client**, **Hide** takes an event out of this image and leaves the rest as the client has it. An
event that the client hides already stays hidden. Another choice of **Map events** empties the field.

The **Map events** panel of the Viewer shows the id of each event and the zone states of each map. See
[Turn map events on and off](guide:events/switch-events).

How Capture draws its images, such as its light, its time of day and its textures, is on the page
**Capture rendering** of [Settings](guide:start/settings).

## Effects in the images

Capture draws the particle effects of a tile, such as fires, smoke and waterfalls. It stops each of them at the same
moment of its play, so two captures of a tile give the same image. To leave them out of a tile, give the tile a view
with **Effects** off, as the part above says.

The effects of [map events](guide:terms/map-event) follow the choice of the client in every tile without such a view.
The line **Map events** under **AREA** says what that choice is, such as "all shown". To change it for every tile,
open the tiles in the Viewer, open **Map events** on the tool rail and choose **Game start**, or turn single events
off there. See [Turn map events on and off](guide:events/switch-events). The effects of some hours follow
**Effects by time of day** on the page of the client in [Settings](guide:start/settings): **Off** hides them, and
**By clock** shows those of **Time of day (hour)** on the page **Capture rendering**. **Castle sides** on the page of
the client hides the effects of the castle towns with **None**.

## A screenshot of the view

Press <kbd>F12</kbd> in the Viewer. Cruma saves the view as a JPEG file named by the date and time, such as
`cruma-2026-11-10_14-25-00.jpg`, and a notice with **Open folder** shows where. <kbd>Shift+F12</kbd> takes the other
kind, with or without the interface. The notice is never in the next screenshot: press <kbd>F12</kbd> again while it
shows, and it goes before the window is saved.

### If it went wrong

- The start screen says "Cruma cannot save images in" a folder: choose another **Output folder** in **Capture map
  images**.
- **Cancel capture** stopped it: the images already written stay in the folder.
- A radar map of many tiles needs much memory: the line "Radar RAM" under **RADAR MAP** in the dialog says about how
  much.
