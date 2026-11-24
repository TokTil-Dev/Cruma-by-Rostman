---
title: Save areadata.txt
kind: howto
summary: Read every changed line, keep a backup, and write the file in its own encoding and line endings.
tool: areas
related: areas/check-areas, terms/areadata, spawns/edit-a-spawn
---
Write your changes to `areadata.txt` after you have seen each line that changes.

## Steps

1. Look at the bar at the bottom of the panel. It says "Unsaved", or how many problems stop a save.
2. Press **Save…**, or <kbd>Ctrl+S</kbd>, <kbd>Cmd+S</kbd> on a Mac.
3. Read **Save areadata.txt**: each change with its line, the area and what changed in words, over the old and the new
   line, the changed parts marked.
4. Fix what stands under "Fix these before saving". **Save** waits for it. What stands under "Worth a look" does not stop
   it.
5. Keep **Back up the current file first** on, to keep the file as it was next to it, named with the date and time.
6. Press **Save**, or <kbd>Enter</kbd>.

When it worked, the bar says "Saved", and its folder button shows the file and its backup. Point at the status to read
the name of the backup.

## What a save writes

- Only the lines of the areas you changed, and your new areas, each after the last area of its type. Every other byte
  of the file stays as it was.
- The file keeps its encoding, UTF-16 with its mark, UTF-8 or the bytes of an ANSI file, and its line endings.
- The file is written whole or not at all: the new file takes the place of the old one only once it is complete.
- **Map events** reads the file again, as an area with an **Event** names that event and turns it on with the server.

## Undo, Discard and leaving

- Undo and Redo stand at the left of the bar. <kbd>Ctrl+Z</kbd> undoes and <kbd>Shift+Ctrl+Z</kbd> redoes.
- **Discard** goes back to the file as last saved, after a question. Undo brings the changes back.
- Closing the window, going back to the main menu, opening another script folder from **Areas** or **NPC Spawns**, and
  reading the file again first ask "Unsaved area changes": **Save**, **Discard** or **Cancel**.

### If it went wrong

- The review says the file changed on disk: another program changed it since Cruma read it. Saving replaces those
  changes. Keep the backup, or read the file again with the button beside its name and make your edits again.
- **Save** is off: an error of your edits is left. The review lists it, and the area shows it under its sentence.
- "Could not save": the folder may be read-only, or another program holds the file.
