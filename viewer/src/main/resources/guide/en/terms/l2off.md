---
title: L2OFF
kind: term
summary: The server program NCsoft wrote for Lineage II, and the formats of its files, such as 22_22_conv.dat.
related: start/client-and-server, terms/l2j, terms/script-folder, geodata/formats
---
L2OFF is short for "Lineage II official". The name stands for the server program of NCsoft itself, for a server that
runs it, and for the formats of its files. The other kind of server is [L2J](guide:terms/l2j).

An L2OFF server keeps its game data as text in its [script folder](guide:terms/script-folder): spawns in
[npcpos.txt](guide:terms/npcpos), NPCs in [npcdata.txt](guide:terms/npcdata), and the names of
[map events](guide:terms/map-event) in `areadata.txt`. Its AI also films the [cut scenes](guide:terms/cut-scene) of
some bosses. Its [geodata](guide:terms/geodata) lies in files named like `22_22_conv.dat`, with the pathnode graph
`pathnode.idx` and `pathnode.bin` beside them, on which monsters find long paths.

**NPC Spawns**, **NPCs**, **Map events** and the scenes **FROM THE SERVER** read the files of an L2OFF server.
**Geodata** reads, builds, edits and exports its region files: choose **L2OFF** under **Server format**. See
[What the client knows and what the server knows](guide:start/client-and-server) and
[Geodata formats](guide:geodata/formats).
