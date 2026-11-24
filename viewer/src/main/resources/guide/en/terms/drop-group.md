---
title: Drop group
kind: term
summary: A group of items with one chance: when it hits, the NPC drops one of them, picked by their shares.
related: npcs/edit-an-npc
---
An NPC's drop is a list of groups. When the NPC dies, the server rolls each group with its chance, and a group that
hits drops one of its items. The shares of a group's items add up to 100%, so an item's chance per kill is the group's
chance times its share. See [Edit an NPC](guide:npcs/edit-an-npc).
