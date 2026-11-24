---
title: Where Cruma writes
kind: reference
summary: Where Cruma saves files, from a change you can undo to the folders of exports, pictures and settings.
next: start/settings
related: start/what-is-cruma, start/settings, tables/client-tables, export/export-a-location, export/tile-operations
---
Looking at the world writes nothing. A change first waits inside Cruma, and a file is written only when you save,
export, capture or build. Each of these ends with a button that opens the folder in your file manager, most often **Show folder**.

## Changes and Undo

| What | Where it goes |
|---|---|
| A change in Edit, [Geodata](guide:terms/geodata), NPC Spawns or the video director | Into the Undo of that tool. <kbd>Cmd+Z</kbd> on a Mac, <kbd>Ctrl+Z</kbd> elsewhere, takes it back. In Edit, a right click on the Undo button lists the steps. |
| **Save…** in Edit | A new folder outside the client, with `Maps`, `Textures` and `StaticMeshes` in it. It never changes the client. |
| **Also install into the client after Save** | The saved files go into the client, and the files they replace go into `client-backup` in the saved folder. |
| A geodata **Build** | `geodata` or `geodata_l2j` inside the client |
| **Copy, move or swap tiles…** | The client's `Maps`, with the replaced maps in a backup folder beside it, such as `Maps-backup-20261110-142500` |
| **Save** in the video director | A [scene file](guide:terms/scene-file), such as `flight_2.scene.json`, in the scenes folder below. A new name makes a new file, and the old file stays. |
| **Install into the client** of a scene | `System/LineageSceneInfo.u` in the client folder you name. The old file is kept, and **Restore client** puts it back. |
| **Create and open** of **Open schemas folder** in [Client tables](guide:tables/client-tables) | An empty `cruma/schemas` folder in the client folder, beside `system`, for the schemas of this client. Cruma asks first, and writes nothing else there. |

## The server's files

These saves write the server's own files, in the folders you gave Cruma for the client: in its panels, or on its page
of [Settings](guide:start/settings).

| What | Where it goes |
|---|---|
| **Save to geodata folder** in Geodata | The changed regions, back into the geodata folder. After a Build that folder is inside the client. |
| A save in NPC Spawns | [npcpos.txt](guide:terms/npcpos) in the [script folder](guide:terms/script-folder). It first copies the old file to a dated `.bak`, unless you turn that off. |
| A save in NPCs | [npcdata.txt](guide:terms/npcdata) in the script folder, with dated backups. |

## Folders of files

| What | Default folder | Where to change it |
|---|---|---|
| Export location, Export NPC, the video and Blender exports of a scene | `Documents/Cruma exports` | **Export folder** in Settings, on the page **Folders and export** |
| Scene files of the video director | `Documents/Cruma/Scenes` | **Scenes folder** in Settings, on the page **Folders and export** |
| **Save** of the Script window of Effects, and the scripts of AI agents | `Scripts` in the export folder | **Effect scripts folder** in Settings, on the page **Folders and export**, or **Use this folder next time** in the Script window |
| Capture | `Pictures/Cruma`, with a folder for each client | **Output folder** in the dialog **Capture map images** |
| Screenshots of <kbd>F12</kbd> | `Screenshots` in the Capture folder of the client | **Screenshot folder** in Settings, on the page **Interface** |
| Edit's **Save…** | `Documents/Cruma/Exports`, with the date and time | Chosen at each save |

A folder of each client is named after the client's folder with a short code, such as `Lineage2-1a2b3c4d`, so two
clients never mix their pictures.

## Settings

[Settings](guide:start/settings) and the clients you opened are kept in `cruma.conf` and `clients.conf`, the last
starts that **RECENT** lists on the start screen in `sessions.conf`, and the panel of the
[right column](guide:start/right-column) in `start-aside.txt`. Two folders of
[Client tables](guide:tables/client-tables) stand beside them: `schemas`, your schemas and `keys.txt` for every client,
made when you press **Open my schemas folder**, and `built-in schemas`, written by **Save built-in schemas**. Cruma
reads no schema from `built-in schemas`. All of these are in a folder of your user:

| System | Folder |
|---|---|
| macOS | `Library/Application Support/Cruma` in your home folder |
| Windows | `AppData/Roaming/Cruma` in your home folder |
| Linux | `.config/cruma` in your home folder |

> **Note:** The logs are in a folder inside it. **Logs** on the start screen opens that folder.
