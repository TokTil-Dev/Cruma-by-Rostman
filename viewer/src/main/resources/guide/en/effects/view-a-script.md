---
title: View the script of an effect
kind: howto
summary: See an effect or a skill as the text of its .uc file, copy it, or save it into a folder of scripts.
tool: effects
next: effects/edit-an-effect
related: effects/what-are-effects, effects/edit-a-skill, effects/export-effects
---
Read how an effect or a skill is built, as the text of a script file that you can copy or save.

### You need

- An effect or a skill chosen in the **Effects** panel. See [Play an effect](guide:effects/play-an-effect).

## Steps

1. Press **Script** on the card of the effect or the skill, the file icon left of the pencil. The effect or the skill
   pauses where it is. Press <kbd>P</kbd> to play it again when you close the text.
2. Read the text. Select a part with the mouse and press <kbd>Ctrl</kbd>+<kbd>C</kbd>, or <kbd>Cmd</kbd>+<kbd>C</kbd>
   on a Mac, to copy that part.
3. Press **Copy** to copy the whole text.
4. Check the folder under **Save to**. Click it to choose another folder. To start in that folder next time too, tick
   **Use this folder next time** under it.
5. Press **Save**.

When it worked, the footer says "Saved" and the name of the file, and **Show folder** opens the folder. The file goes
into a folder of its package, as UnrealEd lays out scripts: `LineageEffect/Classes/at_shield_slam_ca.uc`. The client
does not change.

**Save to** starts in the **Effect scripts folder** of [Settings](guide:start/settings), on the page **Folders and
export**: `Scripts` in the export folder, unless you keep another. **Use this folder next time** shows under any other
folder, ticked while it is the one kept. Clear it to go back to `Scripts` in the export folder. AI agents save their
scripts into the same folder.

## What the text holds

The game keeps each class compiled. Its script text keeps only the class line and any code, as the compiler takes the
`defaultproperties` out of it. So Cruma writes them again from the compiled values:

- The class line, such as `class at_shield_slam_ca extends Emitter`, and the code of the few classes that have some,
  such as `NArrow`. The client of 162 keeps no text at all, so the class line is made from the parent class, and the
  variables and the code are rebuilt from the compiled class. That code has no comments, and Cruma lays it out.
- Each emitter of the effect as a `Begin Object` block, then the list `Emitters` that names them.
- The names of values from the client's `Engine.u`, such as `DrawStyle=PTDS_Brighten`. A value that `Engine.u` declares
  as a plain number stays a number.
- The description `Desc` in Korean, as the client keeps it, such as the Korean name of Mortal Blow.

The line under the title says whether the text holds your edits or is as the client has it. A skill of Chronicle 3 to
Interlude is not a class but an object in `skill.usk`, so its text is a `.t3d` file: its actions first, then the skill.

> **Note:** Cruma rebuilds the text for reading. It was not tried whether UnrealEd compiles it back into a package.

### If it went wrong

- **Script** is grey: the panel still reads the client's packages. Wait a moment.
- A yellow line says that parts could not be written out: a value in a layout that Cruma does not read yet. A
  comment in the text names each.
- Korean letters show as signs in the window: the computer has no Korean font. Cruma takes Apple SD Gothic Neo on
  macOS, Malgun Gothic on Windows, and Noto Sans CJK or Nanum Gothic on Linux. **Copy** and **Save** keep the letters
  as they are.
- Korean letters look wrong in another program: the file is UTF-16 when it holds Korean letters. Open it as UTF-16, as
  most text editors do by themselves.
