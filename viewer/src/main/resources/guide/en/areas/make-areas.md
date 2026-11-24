---
title: Make new areas
kind: howto
summary: Add an area of any type in the middle of the view, draw one corner by corner, or copy one.
tool: areas
next: areas/area-settings
related: areas/edit-a-shape, areas/area-settings, areas/save-areas
---
Add an area to the file: a square to shape, one you draw, or a copy of another.

## New area

1. Press **New area** under the search field.
2. Choose a type. The types of this file come first. **Other types…** shows the others in grey under **Not in this
   file**: no area of the file has them, so a server of another version may not know them. In a file without areas the
   types real files use come first, and **Rare types…** shows the others.
3. Shape the square that stands in the middle of the view. It is 800 by 800, from 200 below the ground to 400 above it.

Water is a box whose surface is the ground there. The new area is selected and ready to shape, and it gets a name of
its type and tile, such as `peace_2018_01`.

When its type needs a value to work, such as the skill of a poison area or a point of a teleport area, the panel shows
the finding and the field right under the sentence, marked, until you fill it in.

## A type of your own

A custom server can know types that Cruma does not list. **Custom type…**, the last row of the menu, opens a field:
type the word and press <kbd>Enter</kbd> or **Use**. The first row goes back, and <kbd>Esc</kbd> closes it. A type word
has letters A to Z, digits and `_`, and starts with a letter. A word that is a type of the list, also in capitals,
chooses that type.

The new area gets its name, its tile, the type and its shape, and no settings of a type. A word of the file that Cruma
does not know, such as `bless` of High Five, is among the types of the file with a grey dot, and works the same.

## Draw area

1. Press **Draw area** and choose a type.
2. Click the corners on the ground. They snap to the 16-unit cells of the geodata, and <kbd>Shift</kbd> places them
   freely.
3. Press <kbd>Backspace</kbd> to take the last corner back.
4. Click the first corner again to make the area. It has a ring around it once there are three corners.
   <kbd>Enter</kbd> and **Close area** do the same. <kbd>Esc</kbd> or **Cancel** stops.

A tag at the pointer counts the corners and gives the size. The area reaches from 200 below the lowest corner to 400
above the highest.

## Copies

The row of buttons on the card of the selected area has:

- Duplicate here: a copy in the middle of the view, on the ground there, with a free name such as `town_2`.
- Copy as text: the area as the file writes it, with its note, for another file or for **Paste area here**.
- Delete: takes the area out of the file. Undo brings it back.

**Paste area here**, in the More menu at the end of that row or under **NEAR THE CAMERA**, adds the areas of copied text
in the middle of the view. The text can hold one area or many, from Copy as text or from another file. Each gets a free name, and its
teleport and restart points stay where they were.

### If it went wrong

- "The clipboard holds no area": copy the lines from `area_begin` to `area_end`.
- The new area has a finding: read it under the sentence. Most types need a setting or two, see
  [Area settings by type](guide:areas/area-settings).
- "Type new to this file" or "Unknown type": check that your server reads the type, see
  [Checks and their fixes](guide:areas/check-areas).
- "A type starts with a letter A to Z." under the field: the word starts with a digit or `_`. Put it right and press
  <kbd>Enter</kbd> again.
