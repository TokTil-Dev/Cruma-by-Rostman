---
title: Checks and their fixes
kind: reference
summary: Why the server drops an area or ignores part of it, and the fixes the Areas panel offers.
tool: areas
next: areas/save-areas
related: areas/what-is-an-area, areas/edit-a-shape, areas/save-areas, terms/tile
---
The panel checks every area against the rules of the server. **CHECK**, at the end of the overview, lists what it finds
in the whole file, grouped by kind, each with **Fly to** and its fix when it has one. **Fix all** fixes every finding of
a kind with a fix as one step. A selected area shows its own findings right under its sentence.

An error makes the server drop the area or fail on it, and it stops a save. A warning is allowed but worth a look. Only
a problem that your edits made stops a save, never one the file had before.

![An area across the border of two tiles: its middle is on the left one, so its part on the right does nothing.](diagram:area-tiles)

## The tile of the middle

The server files each area under the tile of its middle, the plain mean of its corners, and looks only at the areas of
the tile where a character stands. A tile is 32,768 units across. `map_no` in the file must name that tile.

## Errors

| Finding | Why the server drops it | Fix |
|---|---|---|
| map_no is not the center's tile | `map_no` names another tile than the tile of the middle | **Set map_no** |
| Excluded part reaches outside | A corner of an excluded part lies outside the area | Move the part inside |
| Too many excluded parts | The server takes 5 at most | Remove one |
| unit_tick below 1 | The server divides by it, so it must be 1 or more | Set **Every N ticks** |
| Center outside the server's tiles | Its table holds the tiles 10_10 to 29_26 only | Move the area |
| Fewer than 3 corners | An outline needs 3 corners at least | Add a corner |
| Bottom above top | A corner's bottom stands above its top | Set **Height range** |
| No type | Without `type=` the server does not know what it does | Choose a **Type** |
| Name cannot be read | The name is empty or holds a bracket | Set **Name** |
| skill_prob outside 0 to 100 | It is a chance in percent | Set **Chance, %** |

Cruma keeps `map_no` right by itself as you edit. The fix is for areas that were wrong in the file.

## Warnings

| Finding | What happens | Fix |
|---|---|---|
| Reaches another tile | The part on another tile does nothing. A corner up to 16 units past the border does not count | **Split at tile borders** |
| unit_tick before type | `type=` comes after `unit_tick=` and sets a tick of its own, so the server drops yours | **Move unit_tick after type** |
| Name used twice | A search by name finds only the last of the two | Rename one |
| Sides cross | The inside is not what the outline suggests | Move a corner |
| No skill to cast | A poison or instant skill area without a skill casts nothing | Choose a **Skill** |
| Nowhere to teleport to | A teleport area without points sends nobody | **Add point here** |
| Top equals bottom | Only that exact height is inside | Set **Height range** |
| Type new to this file | No other area of the file has the type. A server of another version may not know it, and then it cannot read the area | None. Check your server |
| Unknown type | The type is not in this file and Cruma does not know it. It keeps it as it is | None. Check your server |

The types of the file are those of its areas when you last saved or opened it. A type you saved once is one of the
file's from then on, so it gives no warning any more. A word that the file has and Cruma does not know, such as `bless`
of High Five, gives none either.

**Split at tile borders** cuts the area at the borders of its tiles. The part on the tile of its middle keeps the name,
and each other part becomes an area of its own tile with a free name and the same settings. The same command stands in
the More menu of the area's card.

### If it went wrong

- A fix did nothing: the area changed since the check. Read the finding again, it follows the file.
- Save stays off after a fix: another error of your edits is left. The review lists each one.
