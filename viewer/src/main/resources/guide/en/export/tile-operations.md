---
title: Copy, move or swap tiles
kind: howto
summary: Copy a map to another tile, move it, or swap two tiles, with a check before and Undo after.
tool: tile-operations
related: start/clients-and-tiles, start/how-a-map-is-built, terms/tile
---
Put a map of the client on another [tile](guide:terms/tile). A tile is one file in `Maps`, such as `20_18.unr`. This tool writes it under
the name of the new tile, and moves everything in it by whole tiles, so it stands right in its new place.

![Copy leaves the source as it was, Move empties it, and Swap trades the two tiles.](diagram:export-tile-operations)

### You need

- A client chosen on the start screen.

## Steps

1. Press **Tools** under **MORE** on the start screen, and choose **Copy, move or swap tiles…**.
2. Under **FROM**, keep **This client**, or choose **Another client** to copy a tile from it.
3. Under **OPERATION**, choose **Copy**, **Move** or **Swap**.
4. Click the source tile on the map, then the tile it goes to. **CHANGES** lists each file it writes, replaces or
   removes.
5. Under **SERVER FILES**, keep **Geodata** and **NPC spawns** on, so the server's files go along.
6. Leave **Keep a backup** on, and press the button with the tiles, such as **Copy 20_18 to 22_19**.
7. Press **Open at 22_19** to look at it, or **Undo** to put every file back.

The dialog says what it wrote and where the backup is. **BACKUPS** lists the last operations, each with its own
**Undo**.

## What does not change

- The tiles around it, so terrain and water may not meet at the edges.
- The textures and meshes the map uses. They stay in their [packages](guide:terms/package), so a tile from another client needs this client to
  have them.
- The server's doors, zones and teleports, the names of zones, and the [radar map](guide:terms/radar-map). **Update map image** draws the start
  screen's map image again.

> **Warning:** Cruma does not check whether the game accepts a moved map, and a launcher or patcher may see it as
> damaged. Try it in the game before you give it to others, and keep the backup.

### If it went wrong

- It says the tile is already in this client: choose **Replace it**, or **Swap instead**.
- From another client only **Copy** works, so that client does not change.
- It says the map uses packages this client does not have: copy those packages into the client first.
- **Undo** refuses a file that changed after the operation, so it never overwrites later work.
