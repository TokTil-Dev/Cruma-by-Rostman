---
title: Find problems
kind: howto
summary: Scan the cells around you for traps, walls you can walk up and other mistakes, and fix them.
tool: geodata
tab: check
next: geodata/formats
related: geodata/edit-cells, geodata/check-a-route, geodata/cells-layers-nswe
---
List the places where geodata would trap characters or let them through walls, then fix them.

### You need

- A geodata folder open in the **Geodata** panel.

## Steps

1. Choose **Check**, or press <kbd>C</kbd>.
2. Set **Radius** under **Scan current area**: half the side of the square to scan, 512 units at first.
3. Press **Scan area**. The square stands around the view's focus, or around route end A when there is one.
4. Read the list. A check box beside each kind of finding shows or hides it in the list and in the view.
5. Press **Fly to** on a card to see the place.
6. Press a fix on the card, such as **Open both ways** or **Block both ways**. A fix is one change that you can undo.
7. Press the last button of a card, such as **Fix all 12**, to give every finding of that kind its first fix.

When it worked, the area is scanned again after each fix, and the list shows only what is left. Save the changes as
[Edit cells](guide:geodata/edit-cells) says.

## The kinds that matter most

| Finding | What it means |
|---|---|
| Traps | A one-way edge leads into a closed area that characters can never leave |
| One-way passages | A level side can be crossed one way only, with no way back near it |
| Edges into obstacles | An open side leads into a [cell](guide:terms/cell) where nobody can stand |
| Walls you can walk up | An open side climbs 64 units or more |
| Long drops | An open side falls 128 units or more, usually from a normal ledge |

Kinds that are usually normal, such as most long drops, are information and start hidden.

![A one-way edge: the kind of side that makes traps and one-way passages.](diagram:geodata-one-way)

### If it went wrong

- The list is empty, but a place looks wrong: scan a larger **Radius**, or turn on **Compare client ground** to find
  cells far from the ground the client draws.
- The scan stops at its limit: lower **Radius**, or raise **Cells at most** under **Scan limits and thresholds**.
- A fix made it worse: undo it with <kbd>Ctrl+Z</kbd>, or <kbd>Cmd+Z</kbd> on a Mac.
