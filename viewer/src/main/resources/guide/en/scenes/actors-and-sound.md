---
title: Actors, sound and the screen
kind: howto
summary: Put NPCs into a scene and give them orders, then add music, voice lines, fades and lines of text.
tool: scenes
tab: director
next: scenes/export-a-scene
related: scenes/video-director, scenes/keys-and-shots, terms/shot
---
Fill a scene with NPCs that walk and fight, and give it sound and what shows over the world.

### You need

- A scene open in the tray, and the playhead at the moment where the new thing starts.

## Steps

1. Press **Add** at the left of the tray, and choose **NPC…**.
2. Type a name or a number in the list at the top of the panel, and choose the NPC.
3. Press in the world where it stands. A drag from there sets the way it faces. A ghost of lines shows where it goes.
4. With its key chosen, the panel shows **ACTOR**. Press **Move**, then press in the world where the NPC walks to.
5. Choose a sequence in **Animation**, or a skill in **Skill**, to give it more orders at the playhead.
6. Press **Add** again and choose **Music…**, then a track from its list.
7. Press **Add** and choose **Fade** or **Line** for what shows over the world.

When it worked, the actor has a track of its own in the tray, with a key for each order, and the world shows the NPC at
the playhead. Each new thing is one Undo step, and its key moves on its track as any key does.

[Open the video director](action:scenes-open-director)

## What the Add menu holds

| Kind | Rows |
|---|---|
| Camera | **Camera key**, **New shot**, **Orbit…**, **Fly to…**, **Reveal…** |
| Actors | **NPC…**, **Character…**, **Airship**, **Skill on a place…**, **Effect on a place…** |
| Sound | **Music…**, **Voice…**, **Sound…** |
| Screen | **Fade**, **Line**, **Slide…**, **Post effect…**, **End slides** |

A row with three dots shows its list or its form at the top of the panel, and **Cancel** or <kbd>Esc</kbd> goes back
with nothing added. A row that this client cannot do is grey, and its tip says why, such as a client with no field for
voice lines.

A **Line** shows a row of the client's table of system messages. A Classic world of the newer clients, such as 196 and
Essence, has a table of its own, so the same number can read a little differently there, as the game shows it in that
world.

## Actors in the world

- A press on the body of an actor chooses it, and a drag moves it over the floor.
- **Despawn** makes the chosen actor leave the scene at the playhead.
- The world shows bodies after the scene has run once in the background. Until then a new actor stands as a ghost of
  lines.

> **Note:** A scene that you made for one client can hold what another client has no field for, such as slides on
> Gracia Final. A note under the name of the scene says so. The Viewer still plays it.

> **Note:** **Character…** works on every client, as the Viewer draws the character itself. The scenes of Gracia
> Final hold no player characters, so a character there plays in the Viewer and in a video, and a file of that client
> leaves it out. The form says so. A scene keeps the race of a character as a number. On Essence that number can be
> the Sylph or the High Elf, but not the Crow or a class such as the Death Knight, so the form of a scene does not
> offer those.

### If it went wrong

- A key is in the warning colour: the order does nothing as it stands, such as a walk of an actor that is not in the
  scene at that moment. The panel says why and offers a button that fixes it.
- A press in the world does nothing: there is no floor under the pointer, or the floors are still being read. The panel
  says so under **PLACE**.
- **Character…** is grey: the Viewer is still reading what the client has. It comes on in a moment.
