---
title: What is a spawn
kind: concept
summary: How a server decides where monsters and townsfolk stand, how many there are, and when they come back.
tool: spawns
next: spawns/read-spawns
related: spawns/spawn-formats, terms/npcpos, terms/territory, terms/maker, terms/respawn
---
When you walk into a hunting ground, the monsters are already there. When you kill one, another one comes back after a
while. The [client](guide:terms/client) does not decide this. The server does, and it reads it from its spawn file.

![A zone with its corners, random NPCs on its ground, one NPC at a fixed point and a part where nothing spawns.](diagram:spawn-territory)

## Zones

An [L2OFF](guide:terms/l2off) server keeps its spawns in [npcpos.txt](guide:terms/npcpos), in its [script folder](guide:terms/script-folder).
The file draws zones on the map, called [territories](guide:terms/territory). A zone is a shape of three or more corners
with a bottom and a top height. A zone can leave out a part that another zone covers, and nothing spawns there.

## Makers

A [maker](guide:terms/maker) fills one zone or more with NPCs. It lists entries: which NPC, how many, and where.

- **Random in zone**: the server picks free ground inside the zone, between its bottom and its top.
- **Fixed point**: the NPC stands at one point and faces one way. Townsfolk, guards and bosses often stand like this.

The maker also has a cap, `maximum_npc`. The server keeps no more of its NPCs alive at once.

[Show a fixed NPC near you](action:spawns-show-fixed)

## Respawn

When an NPC dies, its entry waits its [respawn](guide:terms/respawn) time, such as `30sec` or `1hour`, and then puts a
new one in. A random part, such as 5 seconds more or less, keeps them from all coming back at the same moment.

![An NPC lives, is killed, and comes back after its respawn time, a little earlier or later.](diagram:spawn-respawn)

## Names come from npcdata

An entry names its NPC as [npcdata.txt](guide:terms/npcdata) does, such as `goblin`. That file gives the NPC its level,
its size and the rest. See [What is NPC data](guide:npcs/what-is-npc-data).

## Events

Some makers spawn only in a siege, a clan hall battle or an event of the server. Cruma hides them until you turn on
**Events** in the panel.

## What Cruma does with it

The **NPC Spawns** panel draws the zones and NPCs of the file in the world, on the server's [geodata](guide:terms/geodata), or on the floors of the client's map where there is none. You can read each
maker, move zones and NPCs, change counts and respawn times, and check them against the geodata. Nothing is written
until you save.

[Open the NPC Spawns panel](action:spawns-open-panel)
