---
title: Select and move objects
kind: howto
summary: Select objects, then move, turn and size them with the gizmo, in steps, on the ground, or by numbers.
tool: edit
tab: select
next: edit/place-from-the-library
related: terms/gizmo, terms/pivot, terms/snapping, edit/what-edit-changes
---
Pick up a tree, a rock or a house, and put it where you want it.

## The gizmo

When an object is selected, a [gizmo](guide:terms/gizmo) stands on it: arrows to move it, rings to turn it, or handles
to size it. It stands on the object's [pivot](guide:terms/pivot), the point it turns and grows around. With
[snapping](guide:terms/snapping) on, a drag goes in steps, so objects line up.

![The three gizmos on their pivot, and a drag that jumps from one Grid step to the next.](diagram:edit-gizmo)

## Select

1. Press **Select** in the Edit panel, or <kbd>Q</kbd>.
2. Click an object in the view, or its row in the Objects list. <kbd>Ctrl</kbd> and a click, <kbd>Cmd</kbd> on a Mac,
   adds it or takes it away. A drag on empty space selects what lies in a rectangle.
3. Click again at the same place to take the object behind it.

The flags, prisoners and seals that some maps of P152 and 162 place are selected and moved the same way.

Effects, such as a flame, smoke or sparks, have a small round mark in the view where the map saves them. Click the
mark to select the effect. A click beside it goes to the object behind, as most effects cannot be seen.

[Open the Select tool](action:edit-tool-select)

## Move, turn and size

1. Choose **Move**, **Rotate** or **Scale** under **GIZMO**, or press <kbd>W</kbd>, <kbd>E</kbd> or <kbd>R</kbd>.
2. Drag an arrow, a ring or a handle. To go an exact way, type a number while you drag one arrow, ring or handle, and
   press <kbd>Enter</kbd>.
3. Set the steps under **GIZMO**: **Grid** for a move, 16 units at first, **Angle** for a turn, 15 degrees, and
   **Scale**, 0.1. Each has a check box to turn it off.
4. Type exact values under **TRANSFORM** instead, if you know them. **Location** takes `+32` or `*2` too.
5. Press <kbd>End</kbd> to drop the selection onto the ground under it. <kbd>Shift+End</kbd> also tilts it
   to the slope.

The arrow keys move the selection one Grid step, and <kbd>Page Up</kbd> and <kbd>Page Down</kbd> lift and lower it.
**Stick to ground** keeps objects on the terrain while you move them sideways, at the height over the ground they have
in the client.
Hold <kbd>V</kbd> and drag to slide the selection over the surfaces under the pointer.

[Frame the selection](action:edit-frame-selection)

## Several objects

With two or more selected, **Pivot** under **TRANSFORM** says what they turn around: **Center**, the middle of all,
**Active**, the one selected last, or **Each**, every one around its own pivot. The More button, three dots at the end
of the row of icons, has **Align…** to line them up and **Group…** to keep them together. With three or more it also
has **Distribute X** and **Distribute Y**, which leave equal gaps between them.

## Objects that move by themselves

A swinging bowl, a balloon, the stand of the big head of Fantasy Isle, its turning rings or a windmill move, turn,
grow, are copied and are deleted as other objects are. The gizmo and **TRANSFORM** show the place that the map saves.
Under **TRANSFORM** a line says how the object moves, such as "Swings by itself in Roll. Carries 2 actors that move
with it. The values above are its saved place." or "Turns by itself: Pitch 4000 a second. The values above are its
saved place."

- While you move it or give it a new **Scale**, it goes on moving. While you turn it, it stands still, and it starts
  again from the new **Rotation** when you let go, as it does in the game after a save.
- An object that slides, turns or grows by itself, as on the maps of Gracia Final and later, says "Moves by itself. A
  change starts its movement again." It stands while you drag it and moves again when you let go.
- A turn of an object that its map saved in the middle of its swing, as the stand of the big head, makes the turned
  place the middle of its new swing.
- What hangs on a swinging object, such as the flame and the glow of a bowl or the rings of the big head, comes along
  when you move, turn or size the object, and stays at the same point of it. A bigger object takes them farther out.
  The same holds for the head that a flying creature of Gracia carries. This is Cruma's way: the editor of the game
  leaves them where they were. One undo puts all of it back.
- A light that lights the map stays where it is, as only the **Lights** tool moves it. The line under **TRANSFORM**
  names it and says why, such as "Light92 keeps its saved place: Edit moves a light that lights the map only in the
  Lights tool."
- **Duplicate** makes a copy that moves too: a copy of a swinging object swings and carries copies of what its source
  carries, such as a bowl with its flame and its glow, and a copy of a ring that a stand carries is carried too. What
  cannot be copied stays out, and a note and the copy's line under **TRANSFORM** name it with the reason. **Copy** and
  paste place only the mesh, which stands still.
- A drag into the next tile leaves the object in its map, and **Replace mesh…** and **Move to another map…** are off
  for it.
- An object that the map marks as not static but that does not move here says "Not static: the game may move it. It
  keeps its mesh and its map."
- When you leave Edit without saving, these objects move again as the map has them.

## Effects

An effect moves and turns as other objects do, and **Hide**, **Duplicate** and Delete work too. The card says
"Effect" with its map and how many emitters it has.

- **Scale** is off for an effect: the game does not scale an effect by its object. So are **Replace mesh…**,
  **Mirror X**, **Mirror Y**, **Mirror Z**, **Randomize…**, **Move to another map…** and **Save as prefab…**, each
  with the reason in its tip.
- The Objects list names an effect once, with the sparkles icon, under the other objects of its map.
- A flame that a bowl carries goes on swinging with the bowl from the place you give it.
- A copy of an effect shows its particles at once, and a copy of a flame that a bowl carries swings with the bowl.
- A beam that ends on another object keeps that end when you move its effect: only its start moves, in the view and
  in the game. A copy of the effect ends on the same object. When you move or delete the object that a beam ends on,
  the view shows the old end until the map loads again. Other beams move and turn whole with their effect.

## Hide, lock and undo

The icons on the selection's card do what their keys do: Focus <kbd>F</kbd>, Lock <kbd>L</kbd>, Hide <kbd>H</kbd>,
Duplicate <kbd>Ctrl+D</kbd> and Delete <kbd>Del</kbd>. Point at an icon for its name. Hiding and locking last
for this session and are never saved. <kbd>Alt+H</kbd> shows all again.

Every change is one step of Undo, <kbd>Ctrl+Z</kbd>, and Redo is <kbd>Ctrl+Y</kbd>. On a Mac use
<kbd>Cmd</kbd>. A right click on the Undo icon lists the steps, and a click on a row goes back to it.

### If it went wrong

- A click selects nothing and says "Doors and other movers cannot be edited.": Edit does not change doors or
  lifts.
- A click on an effect takes the object behind it: click its mark in the **Select** tool.
- The object does not move: it is locked. Press <kbd>L</kbd>.
- **Replace mesh…** and **Move to another map…** are off for a flag or a prisoner: a figure that its map places keeps
  its mesh and its map. **Duplicate** copies it.
- **Replace mesh…** or **Move to another map…** says "A mesh that can move keeps its mesh and its map.": an object
  that moves by itself keeps both. **Duplicate** copies it.
- A scale says "A mesh with decorations on it cannot take a scale of zero.": the mesh has grass or other
  [decorations](guide:terms/decoration) placed on it. Give it a scale above zero.
- <kbd>End</kbd> says no ground is in view: turn the camera so the ground around the object shows, and press it again.
- The steps are too big: lower **Grid**, or hold <kbd>Ctrl</kbd>, <kbd>Cmd</kbd> on a Mac, during the drag.
