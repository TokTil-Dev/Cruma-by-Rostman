---
title: What Cruma is
kind: concept
summary: A program that opens the world of a Lineage II client, to look at it, check it, change it and export it.
next: start/clients-and-tiles
related: start/where-cruma-writes, start/how-a-map-is-built, terms/client
---
Lineage II keeps its world in the files of the game [client](guide:terms/client): the maps, the textures, the meshes
and the sounds. Cruma opens these files and draws the world as the game does, but without the game. You can fly
anywhere, look at any object, and see the data a game server uses beside it.

![Cruma reads the client and the server's files. It writes only when you save or export, into a folder you choose.](diagram:start-reads-writes)

## What you can do

The start screen lists your clients at the left and shows a map of the chosen client's [tiles](guide:terms/tile).
Choose tiles on the map, then choose what to do with them in the bar under it. The other kinds of work stand under
**MORE** at the left. In a wide window, panels right of the map show the place you chose, a flight over it, the
client and its music: see [the right column](guide:start/right-column).

| Choice | Where | What it is for |
|---|---|---|
| **View** | The bar under the map, then **Open** | Explore the world and inspect its objects. |
| **Edit** | The bar under the map, then **Open** | Move, place and paint in the client's maps, and save them back. |
| **Capture map images…** | The menu of **More**, the last button of the bar | Export tile images and a radar map of the chosen tiles. |
| **Other maps** | **MORE** at the left | Open a map that is not a world tile, such as a lobby. |
| **Effects** | **MORE** at the left | Play the client's effects and skills, without the world. |
| **Tools** | **MORE** at the left | Copy, move or swap tiles, and export a location, an NPC or another map. |
| [Settings](guide:start/settings) | The gear at the top of the side panel | Set the graphics, the sound, your folders and the folders of each client. |

In the Viewer, the tools stand on the rail at the right: Scene settings, the Object inspector, Performance, [Geodata](guide:terms/geodata),
NPC Spawns, NPCs, [Map events](guide:terms/map-event), Effects, Scenes and AI agent. A tool with an article here has a "?" in its header that opens it.

## What Cruma never changes

Looking writes nothing. Cruma reads the client and leaves its files as they are. A change you make stays inside Cruma,
where Undo can take it back, until you save it or export it. Edit's saves and every export go into a folder of their
own, outside the client. The saves of Geodata, NPC Spawns and NPCs write the server's own files, in the folders you
gave Cruma.

Only five things write into the client folder, and each only when you choose it:

- **Copy, move or swap tiles…** writes map files into the client's `Maps` folder, and keeps a backup unless you turn
  it off.
- **Build** in the **Geodata** panel writes geodata into a folder inside the client, and **Save to geodata folder**
  writes there again once you edit it.
- **Also install into the client after Save** in Edit copies the saved maps into the client, and keeps what it
  replaced.
- **Install into client** in the export of Effects copies the changed effect packages into the client. It keeps what
  it replaced, and **Restore client** puts that back.
- **Install into the client** of the video director puts a scene into the client's scene file or into a lobby map,
  with the same backup.

[Where Cruma writes](guide:start/where-cruma-writes) lists every folder.

> **Note:** A game launcher or patcher may see a changed map as damaged. Keep the backup until you have tried the
> change in the game.
