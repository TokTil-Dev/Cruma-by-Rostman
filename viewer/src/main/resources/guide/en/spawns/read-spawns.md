---
title: Open and read spawns
kind: howto
summary: Open a script folder, then read the zones in the world, their colours and the makers near you.
tool: spawns
next: spawns/edit-a-spawn
related: spawns/what-is-a-spawn, terms/script-folder, geodata/get-geodata
---
See where the server puts its NPCs around the camera, and what each zone spawns.

### You need

- The server's script folder with `npcpos.txt` and `npcdata.txt`. See [Script folder](guide:terms/script-folder).
- Geodata, if you can, for the ground as the server knows it. Without it zones and NPCs stand on the floors of the
  loaded map, and their random spawn points are only an estimate. See [Get geodata](guide:geodata/get-geodata).

## Open the files

1. Open **NPC Spawns** on the tool rail.
2. Press **Choose folder…** and choose the `script` folder, or a folder that holds it.
3. Wait while the panel says "Reading npcpos.txt and npcdata.txt…".
4. If the panel says "On the client's floors: no geodata.", press **Set up** beside it to open the Geodata panel.
   You can also go on without geodata.

When it worked, the card at the top counts the zones, [makers](guide:terms/maker) and NPCs on the loaded tiles. Cruma keeps the folder for
this client, so it opens again next time.

## Read the world

1. Turn on **Show**, or press <kbd>N</kbd>, to draw zones and NPCs.
2. Point at a zone. A card lists its NPCs with their level, count and respawn time.
3. Click a zone to select its maker. Click it again for the next maker of the same zone.
4. Press <kbd>F</kbd> to bring the camera to the selected maker.

[Show the nearest zone](action:spawns-show-nearest)

A zone takes the colour of the NPCs it spawns. A zone that spawns only in an event or a siege is purple. Else a zone
with a boss is a boss zone, and else the kind with the most NPCs wins.

| Colour | Kind |
|---|---|
| Orange | Monsters |
| Crimson | Bosses |
| Teal | Townsfolk |
| Blue | Guards |
| Violet | Summons and pets |
| Olive | Objects and traps |
| Grey | Unknown NPCs |
| Purple | Event and siege spawns |

**View** lists the same colours. It also sets **Draw distance**, 10,000 units at first, and what is drawn, such as
**Spawn zones**, **NPC models** and **NPC names**.

An NPC model takes the light of the place it stands in, as the game gives it: up to four lights near it, the sun where
the zone lets it in, and the ambient of its zone or of the time of day. So an NPC beside a torch turns orange, and one
in a green-lit hall turns green. Metal armour and other shiny parts also show the sheen the game gives them, a
coloured shine that moves over the plates as you go around the model, in the gaps of lace and mail too.

## Read the panel

- **IN THE LOADED WORLD** is a bar of the NPCs by kind. Click a kind to hide it in the world, and again to show it.
- **NEAR THE CAMERA** lists the six makers nearest to you. Point at a row to see its NPCs in the world, and click it
  to fly there.
- **Find an NPC, maker or zone** finds names as npcdata.txt writes them and as the game shows them.
- A selected maker shows its card, a map of its zones from above, **Max NPC**, and its entries under the map.

### If it went wrong

- "Cannot read the files": the folder has no `npcpos.txt` or `npcdata.txt`. Choose the `script` folder of an [L2OFF](guide:terms/l2off)
  server.
- The lists are full but the world is empty: the map tile under the camera is not loaded. Zones and NPCs show only on
  loaded tiles.
- Random NPCs stand in odd places: with no geodata for the region they stand on the floors of the client's map, which
  is an estimate. The panel says "No geodata for" the region. Build its geodata in the Geodata panel.
- A zone is missing: its maker spawns only in an event. Turn on **Events**.
- An NPC stands as a capsule: the client has no model for its id. Its hover card says "not in the client".
