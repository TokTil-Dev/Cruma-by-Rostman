---
title: Settings
kind: reference
summary: Where Settings opens, what each of its pages holds, and when a saved change reaches the world.
related: start/where-cruma-writes, tables/client-tables, start/open-a-world, export/capture-map-images
---
Settings holds what Cruma keeps from one start to the next: the folders and choices of each client, the graphics, the
sound, the folders of your files and how the tools start. Nothing is kept before you press **Save**.

## Where it opens

| Way in | Where |
|---|---|
| The gear of the start screen | At the right of the title row of the side panel, beside the Guide's book. Its tip says "Settings" |
| The gear of a client | At the right of a row under **CLIENTS**, on the chosen client or under the pointer. It opens the page of that client |
| **Settings…** | The Viewer menu, at the right end of the top bar. A window that an AI agent opened, and a recording, have no such row |

## The pages

The list at the left has two parts. **THIS CLIENT** holds the page of the chosen client, named after its folder. It
keeps what Cruma knows of that client:

- **WORLD**: **World**, with **Main**, **Classic** and **Essence** for a client that has those worlds, or "Main,
  this client has no other world".
- **MAP EVENTS**: **Effects by time of day** and **Castle sides**.
- **SERVER DATA**: the **Geodata folder**, with the build of geodata from the client under it, and the
  **Server scripts folder**.
- **CLIENT FOLDER**: the **Game client** folder with **Show folder**, and its **Map image**.

Under it, **Client tables** shows how Cruma reads the tables of the client, its `system/*.dat` files, and which
schema read each. It changes no setting, so **Save** and **Reset page** do not touch it. See
[Client tables](guide:tables/client-tables).

**CRUMA** holds the pages that every client shares.

| Page | What it holds |
|---|---|
| **Graphics** | The graphics backend, **Frame limit (FPS)**, upscaling, distances, textures, light, water and sky |
| **Interface** | **Interface scale**, **Animations**, **Interface in screenshots** and **Screenshot folder** |
| **Audio** | Music and ambient sound |
| **Places** | **STARTING CAMERA**, where the camera starts, and **NAMED LOCATIONS**, the places of the world map |
| **Tools** | How the tools of the Viewer start: **GEODATA DISPLAY** and **NPC SPAWNS** |
| **Folders and export** | **Export folder** and **Export scale (cm per unit)**, the export of cut scenes, **Install after writing** of Edit, the **Effect scripts folder** of the Script window of Effects and the **Scenes folder** |
| **Capture rendering** | How Capture map images draws its images |
| **AI agent** | **Let an AI agent use this window**, **ACCESS** and **CONNECT** |
| **Advanced** | **DEFAULT AREA** and **DEFAULT AREA OF CAPTURE**, the tiles that load when Cruma starts without the start screen. Then the recovery of Edit, Edit itself and the technical controls |

The files of Capture, its radar map and its tile views are not here. They are in the dialog
[Capture map images](guide:export/capture-map-images).

## Search and Reset page

**Search every setting** at the top finds the settings of every page that hold all your words, such as `fog` or
`folder`. A click on a page in the list ends the search. **Reset page** puts the settings of the page you see back to
how Cruma first starts, but keeps its folders and the named locations. The page of the client has no **Reset page**.

## Save

The line at the bottom says how many changes wait, such as "2 unsaved changes". **Save** writes them into `cruma.conf`
and closes Settings. **Cancel** closes it and keeps nothing. <kbd>Esc</kbd> and the close button ask first when
changes are not saved.

In the Viewer, **Save** changes only the map events of the client in the world that is open: **Effects by time of
day** and **Castle sides**. The bottom line says "Changes apply when the world loads again. Map events apply at
once." **Save and load again** saves and opens the same tiles again with the new settings, in the same
workspace, with the camera where it stood. When Edit or another tool has unsaved work, it asks first, as **Main menu**
does. The map image of a client is made on the start screen only.
