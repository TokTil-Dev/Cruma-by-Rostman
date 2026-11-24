---
title: Put a scene into a client or a lobby
kind: howto
summary: Write a scene over a cut scene of the client, or a changed scene of a lobby back into its map, and undo it.
tool: scenes
tab: director
related: scenes/export-a-scene, scenes/video-director, terms/cut-scene, terms/lobby
---
Make the game play your scene: Cruma writes it over one of the client's own cut scenes.

> **Warning:** Nobody has run such a file in a game yet, so nobody knows if the game takes it. Work on a copy of the
> client, never on the client you play with. **Restore client** puts the old file back.

### You need

- A scene in the tray: a copy of a cut scene, or a scene of your own.
- A client with cut scenes, from Gracia Final on. A scene can only go over a scene the client has, as it keeps that
  scene's name and number.

## Steps

1. Open the **Into the client** card under the export of the **Scenes** panel.
2. For a scene of your own, choose under **Replaces** the cut scene it goes over. A copy goes over the scene it comes
   from.
3. Press **Check**. It writes nothing: it makes the file in memory, reads it back, and lists what the file will not
   hold and what the client lacks.
4. Check the folder under **FOLDER**, a new folder outside the client, and press **Write the file**.
5. To try it in a game, press **Install into the client** under **CLIENT**, with a copy of the client in **Client
   folder**, and answer the question.
6. Press **Restore client** to put the client's own file back.

When it worked, **Write the file** ends with a line of what it wrote and **Show folder**, and the client has not
changed. After an install the panel reads the client's scenes again.

## What Check says

| Part | What it means |
|---|---|
| **WHAT THE FILE WILL NOT HOLD** | Fields and orders this client has no place for, such as the speed of a walk. The video keeps them, the file leaves them out |
| **WHAT THE CLIENT LACKS** | NPCs, skills or sequences the scene asks for and this client does not have |
| **MARKS THAT STILL RUN** | Animations that take the camera in the client, even from a path |

A path of your own has no form in the client. It goes in as shots around an invisible NPC, about two a second. Turn on
**As the client plays** to see those shots in place of the smooth path. A video keeps the smooth path.

## A scene of a lobby

The login screen of a client plays camera scenes that stand in its [lobby](guide:terms/lobby) map. On such a map, the
copy button of a scene's row opens it in the tray, and the card is **Into the map**. Its **Check** lists the other scenes
a change reaches, as scenes share their points, and what a map cannot hold, such as a new key.

### If it went wrong

- The card says the client has no cut scenes: a scene has no place to go there, as on Interlude.
- **Write the file** is off: press **Check** first, and again after each change.
- **Install into the client** fails: close the game first, as a file in use cannot be replaced.
