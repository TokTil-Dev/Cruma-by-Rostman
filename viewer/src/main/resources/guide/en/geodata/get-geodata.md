---
title: Get geodata
kind: howto
summary: Open a folder of server geodata, or build one from your client in the L2OFF or L2J format.
tool: geodata
next: geodata/read-the-overlay
related: geodata/formats, terms/l2off, terms/l2j
---
Give the **Geodata** panel a folder of [region](guide:terms/region) files, so it can show the [cells](guide:terms/cell) of your world.

### You need

- A world open in the Viewer.
- A folder of geodata from a server, or a client whose maps Cruma can read.

## Open a folder you have

1. Open **Geodata** on the tool rail at the right.
2. Press **Choose folder…**.
3. Go to the folder with files such as `22_22_conv.dat` or `22_22.l2j`, and press **Select folder**.
4. Press **Remember this folder for this client** to open it again at the next start.

The folder's name stands at the top of the panel, with the number of regions loaded. The cells show over the ground
around the camera. **Geodata folder** on the page of the client in [Settings](guide:start/settings) is the same
folder, and it offers the same build.

## Build it from your client

1. Open **Geodata** with no folder chosen.
2. Choose **L2OFF** or **L2J** under **Server format**.
3. Press **Build** on the card **No server geodata?**.
4. Wait while the regions are built. A card shows the region, the step and the time left. **Stop** ends the build after
   the current step, and a later **Build** goes on.

[L2OFF](guide:terms/l2off) writes files such as `22_22_conv.dat` into the folder `geodata` inside the client.
[L2J](guide:terms/l2j) writes files such as `22_22.l2j` into `geodata_l2j`. The panel opens that folder as soon as the
build starts. A region takes a few seconds. **How accurate the build is** says how close the files come to the
official ones.

[Open the Geodata panel](action:geodata-open-panel)

### If it went wrong

- The panel says Folder unavailable: the folder moved or its path is wrong. Click the folder's name and choose it again.
- No cells show: the folder has no file for the region under the camera. Fly to a place the folder covers, or build
  that region.
- **Build** stays grey: **With pathnode** is on, and the build does not know your server yet. Choose **320 zones** or
  **340 zones**.
- A region was not built: its map is missing from the client. The build goes on with the others, and the log names it.
