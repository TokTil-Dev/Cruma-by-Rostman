---
title: Export and install effects
kind: howto
summary: Write your changed effects and skills to a folder, then install them into the client or take them back out.
tool: effects
next: effects/effects-mode
related: effects/edit-an-effect, effects/edit-a-skill
---
Put your changes of effects and skills into files the game reads.

### You need

- Changes made in the **Effects** panel. Cruma keeps them by itself, also after a restart.

## Steps

1. Press **Export** in the footer of the **Effects** panel.
2. Read the files under **FILES**. Only [packages](guide:terms/package) you changed are written.
3. If you made a copy of an effect, choose under **PUT COPIES IN** between `LineageEffect_Custom.u`, a small file of
   its own, and `LineageEffect.u`, which rewrites a large file.
4. Check the folder under **FOLDER**. It is a new folder outside the client.
5. Press **Export**.
6. Press **Install into client** to copy the files into the client. Close the game first.

When it worked, a green line says what was written, and **Show folder** opens the folder. After an install, the client
plays your changes, and **Restore client** puts back the files the install replaced.

> **Warning:** Players need every file of the folder, in the same folders of their client. Do not rename
> `LineageEffect_Custom_T.utx`: the game reads it only by that name.

### If it went wrong

- **Install into client** fails: the game is running and holds the files. Close it and try again.
- **Restore client** refuses: an installed file changed later. The dialog says which.
- **Export** fails with a line about an edit: an edit no longer fits the client. Press **Drop this edit** at the top of
  the panel.
