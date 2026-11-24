---
title: Skill
kind: term
summary: Something a character or an NPC can do or has, such as a spell, a strong hit, or a passive bonus.
related: effects/skill-casts, npcs/edit-an-npc
---
The server knows each skill by name in its `skilldata.txt`, and the client by id, with its icon, name and cast time.
An NPC's `skill_list` in [npcdata.txt](guide:terms/npcdata) holds mostly passive skills, such as its race. A skill that
is cast plays effects in phases. See [How a skill casts](guide:effects/skill-casts).
