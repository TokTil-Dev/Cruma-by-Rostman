---
title: What the client knows and what the server knows
kind: concept
summary: The client draws the world, the server decides what happens in it, and Cruma reads the files of both.
next: start/where-cruma-writes
related: geodata/what-is-geodata, geodata/get-geodata, terms/client
---
A game of Lineage II needs two programs. The client on your computer draws the world and plays its sounds. The server
decides where characters can walk, which monsters stand where, and what they do. Each keeps its own files, and the
server never reads the client's.

![The client keeps what you see. The server keeps what happens. Cruma reads both and shows them together.](diagram:start-client-server)

## What only the client has

Everything you see and hear: the maps, the [textures](guide:terms/texture) and [meshes](guide:terms/static-mesh), the [effects](guide:effects/what-are-effects), the music and the screens of the game.
Cruma reads all of it from the client folder, which is all it needs to show the world.

## Two kinds of server

A server is one of two kinds. [L2OFF](guide:terms/l2off) is the server program of NCsoft itself. [L2J](guide:terms/l2j)
is an open server written in Java, and many servers are built on it. The two keep the same kinds of data in different
files, so Cruma asks which one you have where it matters.

## What only the server has

| Data | Files | The panel that shows it |
|---|---|---|
| [Geodata](guide:terms/geodata) | `22_22_conv.dat` or `22_22.l2j` | **Geodata** |
| [Where NPCs stand](guide:terms/npcpos) | `npcpos.txt` | **NPC Spawns** |
| [What NPCs are](guide:terms/npcdata) | `npcdata.txt` | **NPCs** |
| Doors and areas the server switches, the [map events](guide:terms/map-event) | `doordata.txt`, `areadata.txt` | **Map events** |

The geodata files are those of L2OFF and of L2J. The text files are those of an L2OFF server, from its
[script folder](guide:terms/script-folder).

## How Cruma gets the server's files

Give Cruma the folders once, on the page of your client in [Settings](guide:start/settings): **Geodata folder** and
**Server scripts folder** under **SERVER DATA**, the second the folder with `npcpos.txt` and `npcdata.txt`. The gear
of the client's row under **CLIENTS** opens that page. The panels **Geodata** and **NPC Spawns** can choose the same
folders. Without them the world still opens, and each panel says what it misses. Geodata can also be built from
the client: see [Get geodata](guide:geodata/get-geodata).

## When the two do not agree

A server's files were made from one client. With another client, a wall can stand where the geodata has none, or an
NPC stands inside a rock that the server does not know. Cruma draws the server's data over the client's world, so you
see where they differ.
