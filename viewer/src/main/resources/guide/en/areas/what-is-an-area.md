---
title: What is a server area
kind: concept
summary: How a server marks the places where its rules change, such as towns, water, poison and gates.
tool: areas
next: areas/see-areas
related: areas/area-settings, terms/areadata, terms/script-folder, terms/tile
---
Some places in the game have rules of their own. Nobody can fight in a town, characters swim in a lake, a swamp slows
you down and a gate sends you somewhere else. The [client](guide:terms/client) does not decide this. The server does,
and it reads these places from its [areadata.txt](guide:terms/areadata).

![An area from above, with a part it leaves out and a teleport point outside it.](diagram:area-shape)

## An area

An area is a shape on the map with three or more corners, a bottom and a top. A character inside the shape, between
the bottom and the top, is in the area. The server keeps one bottom and one top for the whole area.

An area can leave out excluded parts, 5 at most, where its rule does not hold. Water is a box: four corners and a
surface.

## Types

The type of an area says what it does. These types are the ones real files use most:

| Type | What it does |
|---|---|
| Peace zone | Nobody can fight here |
| Battle zone | A battle zone, without settings of its own |
| Water | Characters swim here |
| Swamp | Changes how fast characters move, such as half as fast |
| Damage area | Takes HP or MP every few ticks |
| Poison area | Casts a skill every few ticks, such as a poison or a buff |
| Instant skill area | Casts a skill every few ticks |
| Teleport area | Moves whoever steps in to one of its points |
| No-restart zone | A character that logs out here comes back in a town |
| Mother Tree | Elves, and the races it names, regain HP and MP faster |
| Seven Signs zone | Has its own XP penalty and item drop on death |

The rare types, such as siege zones and the entrances of instance zones, are there too.

## Servers differ

Servers of different versions know different types. A C1 file uses 6 types, a High Five file 13, among them `bless`,
which Cruma does not know. A type that the file already has is safe. So Cruma offers the types of the file first, and
warns about a type that no other area of the file has. A custom server can have types of its own: **Custom type…** takes
them.

## On and off

An area can start off. It then does nothing until an event or an AI of the server turns it on. Cruma draws it fainter.

## One tile

The server files each area under the [tile](guide:terms/tile) of its middle, and looks only at the areas of the tile
where a character stands. So a part of an area on another tile does nothing. See
[Checks and their fixes](guide:areas/check-areas).

## What Cruma does with it

The **Areas** panel draws the areas of the file in the world and on the world map, says in plain words what each one
does, lets you change them, and checks them against the server's rules. Nothing is written until you save.

[Open the Areas panel](action:areas-open-panel)
