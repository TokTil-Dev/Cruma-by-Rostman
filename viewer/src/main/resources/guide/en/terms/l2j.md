---
title: L2J
kind: term
summary: The open server for Lineage II written in Java, the servers built on it, and the formats of its files.
related: start/client-and-server, terms/l2off, geodata/formats
---
L2J Server is a server for Lineage II that its community wrote in Java, with open code. Many other servers are built
on it. The name stands for these servers and for the formats of their files. The other kind of server is
[L2OFF](guide:terms/l2off), the program of NCsoft itself.

An L2J server keeps its game data in files of its own, mostly XML and SQL, and has no
[script folder](guide:terms/script-folder). Its [geodata](guide:terms/geodata) lies in files named like `22_22.l2j`.
They hold the same cells as the L2OFF files and write each block a little differently, with no header. L2J servers
find paths from the cells, so they need no pathnode files.

**Geodata** reads, builds, edits and exports the region files of L2J: choose **L2J** under **Server format**.
**NPC Spawns**, **NPCs** and the names of **Map events** need the script folder of an L2OFF server. See
[Geodata formats](guide:geodata/formats).
