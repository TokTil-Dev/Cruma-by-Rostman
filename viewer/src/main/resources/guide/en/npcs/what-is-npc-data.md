---
title: What is NPC data
kind: concept
summary: How the server's npcdata and the client's npcgrp describe the same NPC, joined by one id.
tool: npcs
next: npcs/find-an-npc
related: npcs/npc-files, terms/npcdata, terms/npcgrp, spawns/what-is-a-spawn
---
A goblin in the game is two things at once. The server knows how strong it is and what it drops. The [client](guide:terms/client) knows how
it looks. Each side keeps its own file, and one number joins them.

![The server's npcdata and the client's npcgrp keep different things about the same NPC, joined by its id.](diagram:npc-server-client)

## The server's side: npcdata

[npcdata.txt](guide:terms/npcdata) has one line for each NPC. It starts with the NPC's kind, its id and its name, such
as `goblin`. Then come its settings: its level, HP and attack, the [skills](guide:terms/skill) it has, its AI, what it
drops, and the size of its collision cylinder. The server counts every fight with these numbers. Players never see
the file.

## The client's side: npcgrp

[npcgrp.dat](guide:terms/npcgrp) lies in the client's `system` folder. For each id it names the mesh and the textures
of the model, the class of its pawn, and how large it is drawn. Another table of the client,
`NpcName-e.dat`, gives the name players read over its head, such as Goblin.

## One id joins them

The server tells the client which NPC stands where by its id, and the client draws the model that npcgrp gives that
id. So a new NPC on the server also needs a record with its id in the client. Without one, the client has nothing to
draw. Cruma then shows the NPC as a capsule and says "not in the client".

## Two names

npcdata names an NPC in small letters with underscores, and [npcpos.txt](guide:terms/npcpos) uses that name. The game shows the client's
name instead. The panels of Cruma find both, `goblin` and `Goblin`.

## What Cruma does with it

The **NPCs** panel edits npcdata.txt: drops, skills, AI and numbers. **NPC Spawns** uses its levels and sizes, and the
client's models, to draw each NPC where it stands. **Export NPC** writes the client's model with its skeleton and
animations, for Blender.

[Open the NPCs panel](action:npcs-open-panel)
