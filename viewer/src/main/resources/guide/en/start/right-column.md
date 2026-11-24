---
title: The right column of the start screen
kind: reference
summary: Four panels beside the map in a wide window: the place you chose, a flight over it, the client and its music.
next: start/moving-around
related: start/open-a-world, start/settings, tables/client-tables, terms/script-folder, terms/maker
---
In a wide window the map of the start screen leaves room at its right. A column stands there, and you choose which
of its four panels it shows.

## When it shows

The column needs at least 360 units beside the map and the bar under it, which is a window about 1586 units wide or
more. It is at most 520 wide. In a narrower window the column goes first, and the map, the bar and the side panel
keep their size. The Guide beside the map takes the room first too, so the column goes while the Guide is open
there.

A switcher at the top right chooses the panel: **Place**, **Flight**, **Client** or **Radio**. It stays in the same
place for every choice. Cruma keeps your choice for the next start, and the first start shows **Place**.

## Place

What the tiles you chose hold.

- A picture of them from the client's map image, with the chosen tiles outlined and the named places on it. Without a
  map image, **Create map image** at the top right of the map makes one, and until then the picture shows the
  tiles' names.
- **NPC spawns**: the NPCs of the [makers](guide:terms/maker) whose zones reach into the chosen tiles, or whose fixed
  NPCs stand in them, counted as the NPC Spawns panel counts them, from `npcpos.txt`.
- **named places**: the places of Settings, **Places**, that lie in the chosen tiles.
- **geodata regions**: how many of the chosen tiles have a region file in the client's geodata folder.
- **THEME OF THIS PLACE**: the music the game plays there, from the music zones of the map and the client's
  `music` folder. Where no named place lies in a music zone, it is the music of the tile's largest zone. The play
  button plays it. While it plays and you choose tiles without a theme, or no tiles, **NOW PLAYING** keeps a link that
  pauses it.
- **WHO SPAWNS HERE**: the kinds of NPC with the most spawns, with the game's name and the level from `npcdata.txt`.
  NPCs of server events are left out of this list.
- **NEXT TO IT**: the nearest named places outside the choice. A click chooses that place on the map.

The NPC numbers need the **Server scripts folder**, and the regions the **Geodata folder**, both on the page of the
client in [Settings](guide:start/settings).

## Flight

A slow flight over one tile, drawn from the client as you watch. It starts once the choice has rested for half a
second, over the tile of the place that names the choice, else over its first tile. Where a tall building stands
there, such as Cruma Tower, the flight moves back and up until the top of the building is in the picture. "LIVE ·
FLYING OVER" and the tile stand at its top. The light is the client's own at **Time of day (hour)** on the page
**Graphics** of Settings. The button at the bottom pauses the flight, and the menu then rests.

The picture is simpler than the Viewer's. It shows one tile, and no translucent surfaces, glows, particles, sky dome
or tiles around it. Open the tile in the Viewer to see all of it.

## Client

A passport of the chosen client: its name and folder, where it stands among your clients under **Saved clients by
tiles**, and its numbers **tiles**, **named places**, **Classic tiles** for a client with a Classic world, and **other
maps**. With a server scripts folder it adds the **zones**, **makers** and **NPCs** of `npcpos.txt`. **AGAINST**
compares its tiles with another client you choose. **FOLDERS** says what its folders hold, as its page of Settings
does, and **Settings of this client** opens that page. When a table of the client is not read, or only partly read,
**FOLDERS** also has a **Client tables** line, such as "1 not read". A click on it opens
[Client tables](guide:tables/client-tables) in Settings. Cruma reads the client's tables for it once a run, on a thread
of its own. The panel shows no chronicle and no date, as the files do not
say them.

## Radio

The client's own music, the files of its `music` folder. A track's name is the name of its file. The line under it
says whose theme the track is, when it came from a place. A click on the time bar jumps in a track that plays, and
chooses where a waiting or paused track starts. **Follow the map** plays the music of the tile under the pointer once
the pointer rests on it, or of the chosen tiles. It shows when the client's music table can be read, and does nothing
while the music is paused. A track you pick from **TRACKS**, or with the buttons beside play, turns it off. The
loudness is the music's **Volume (0–1)** on the page **Audio** of Settings.

> **Note:** Radio and Place make no sound before you press play. The music stops when you choose another panel or
> another client, open the tiles or leave the start screen.
