---
title: Save, install and check in the game
kind: howto
summary: Check your changes, save the changed maps to a folder, put them into the client with a backup, and test them.
tool: edit
tab: save
next: edit/limits
related: edit/what-edit-changes, start/where-cruma-writes, terms/recovery-draft, terms/package
---
Turn your changes into map files, try them in the game, and go back if something is wrong.

## Where a save goes

A save never writes into your client. It writes the changed maps, and the packages they need that the client lacks,
into a new folder. Only if you ask, Cruma then copies that folder into the client, and keeps the files it replaces.

![The way of a save: from Edit into a new folder, and from there into the client, with the old files kept.](diagram:edit-save-path)

## Save

1. Press **Save…** at the bottom of the Edit panel, or <kbd>Ctrl+S</kbd>, <kbd>Cmd+S</kbd> on a Mac.
2. Look at **SAVE TO**. A new folder with the date and time, in `Documents/Cruma/Exports` at first. A click on the
   field chooses another place.
3. Read **CHANGES**. Each card says what changed, with **Fly to** and **Revert**.
4. Wait for the check under **CHECK**. It says "1 map ready to save", or which problems block the save.
5. Read **FINDINGS**, and fix what you want to fix.
6. Turn on **Also install into the client after Save** if you want to try the maps in the game now.
7. Press **Save**.

[Open the save review](action:edit-open-save)

When it worked, the review says what it saved, such as "Saved 1 map to 2026-11-10_14-02", with **Show folder**. The
save also rebuilds the [baked light](guide:terms/baked-light) near your changes, but a change of walls and floors in
the Surfaces tool rebuilds no light.

## Install into the client

With **Also install into the client after Save** on, or with **Install the last save** after it, the dialog **Install
into the client** says how many client files it replaces, and asks first.

- The replaced files go to `client-backup` in the saved folder.
- **Restore client** puts them back and removes the packages the save added.
- Close the game first. A file in use cannot be replaced.
- After an install into the client you edit, press **Reload**, so Edit reads the new files. Until then the view
  shows the maps as Edit opened them: when you leave Edit without **Reload**, a surface that you hid or deleted shows
  again and a moved object stands where it stood, although the client has the change.

## What the Check finds

| Finding | What it means |
|---|---|
| Meshes that float or sink | A mesh near an edit stands more than 8 units off the ground, more than in the client. Fix: **Drop to ground** |
| Actors outside their map | A changed object stands in another tile than its map. Fix: **Move to** that map |
| Copies in one place | Two objects of one mesh at one place, after a copy that did not move |
| Problems that block the save | A package the changes need is missing, or another reason. Nothing saves until it is solved |
| NPC Spawns in meshes or under the ground | An NPC of the open `npcpos.txt` now stands inside a mesh or under raised ground |
| Geodata older than the edit | The geodata of an edited tile is older than your changes. Build it again |
| Objects on a deleted brush | An object that stood on or against a deleted brush, and may float now. Fly to selects it |
| Changed BSP surfaces | For your information: hidden, deleted or retextured walls and floors, a new mapping, or a deleted brush. A hidden wall still blocks the player. A rebuild in UnrealEd would very likely undo them |

**Check again** looks at the maps as they are now.

## Check in the game

Cruma checks the files, not the game. Load the changed place in the game client: walk over the new ground, bump into
the moved objects, and look at it by day and by night.

### If it went wrong

- **Save** is off: the check has not passed for the current changes, or there is no folder. Read **FINDINGS**.
- The install says a client file changed: the client is newer than the save. Save again, then install.
- The game shows something wrong: press **Restore client** in the review, or copy the files of `client-backup` back.
- Cruma closed before you saved: open Edit again, and press **Recover** in **Recover the Edit session**.
