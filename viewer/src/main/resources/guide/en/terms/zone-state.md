---
title: Zone state
kind: term
summary: A look of a zone of a map, from 1 to 10: some meshes, skins, effects, fogs and sounds show in one state only.
related: events/what-are-map-events, events/switch-events
---
A zone starts in the state its map gives it, and the server can change it. The server sends a state for each map,
and every zone of that map takes it. A place with two looks keeps both in the map, each in its own state, so a viewer
that draws every mesh would show both at once. Under **ZONE STATES**, the **Map events** panel chooses the state of
each map on its own. A state number means something else in each map: `25_18_Classic` of Essence has five looks of
its sealed temple in states 1 to 5, and another map can have no state 3 at all. See [Turn map events on and
off](guide:events/switch-events).

Essence gives states to much more: effects, the skins of a mesh, the layers and the grass of the ground, local fog,
water, the sky, the time of day of a place, sounds and music. There a zone in state 0 shows none of the meshes and
effects of a state. Many of its zones start in state 0, such as the square of Giran, and the game shows them only
after the server has sent a state. So Cruma starts such a map in **State 1**, the plain square, and its group reads
"22_22_Classic · State 1". Choose another state for its looks, such as snow in **State 3** or a thin film of water
in **State 5**. **As the map** there is state 0, where the square has holes in its floor: its floor draws only in a
state. The choice is saved for the client.

**ZONE STATES** has a group for each loaded map with zone states. Its header names the map and its choice, such as
"25_18_Classic · State 3" or "25_18_Classic · As the map". A row of a group says what a state changes in that map,
such as "9 skins · 38 effects · 27 sounds" under `24_21_Classic`:

| In a row | Means |
|---|---|
| meshes | Meshes that draw only in this state |
| skins | Meshes that draw in every state and wear other skins in this one |
| effects | Effects that show only in this state |
| terrain | Layers of the ground that show in this state |
| decorations | Grass and other decorations that grow in this state |
| water | Water that is water in this state |
| fog | A fog of this state |
| sky | Sheets of the sky that change in this state |
| sky zones | Places that get a time of day or a sky of their own in this state |
| sounds | Sounds that play in this state |

A sky zone is a box in an Essence map that gives the place inside it an hour, a light and a sky of its own. With
the camera inside, the view shows that hour in place of yours: one hall of `17_14_Classic` is at noon in **State 2**,
at 3:00 in **State 5** and at 17:00 in **State 8**. See **Time of day** in
[Scene settings](guide:view/scene-settings).

Some Essence zones fade from one state to the next: the new look comes in, then the old one goes out, in half a second
to two seconds.
