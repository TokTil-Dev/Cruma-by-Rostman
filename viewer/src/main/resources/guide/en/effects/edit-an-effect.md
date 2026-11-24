---
title: Edit an effect
kind: howto
summary: Change an effect's size, speed, colour and emitters, and make a copy so other skills keep the original.
tool: effects
next: effects/edit-a-skill
related: effects/what-are-effects, effects/export-effects, terms/emitter
---
Change how an effect looks. It plays changed at once, and the client stays as it is until you install an export.

### You need

- An effect chosen in the **Effects** panel. See [Play an effect](guide:effects/play-an-effect).

## Steps

1. Press the pencil on the effect's card.
2. Under **WHOLE EFFECT**, drag a field left or right. **Size**, **Spread**, **Speed**, **Lifetime**, **Count** and
   **Opacity** go from 0.1x to 10x, and **Hue** turns the colours from -180° to 180°. Double-click a field to type.
3. Choose **Colour** for a tint. It multiplies the colour, so it can only make it darker.
4. Under **EMITTERS**, click an [emitter](guide:terms/emitter) to change it alone. Its eye turns it off or on.
5. Press **Add emitter** to copy an emitter of another effect into this one.
6. Press **Make a copy** when other skills use the effect and should keep the original.
7. To rename a copy, type another name in **Name of the copy** at the top of its editor and press Enter.
8. Press **Reset** to take the fields back to no change.

When it worked, the effect plays changed at once, and the footer counts what you changed, with **Discard** and
**Export**.

> **Note:** A change to an effect of the client reaches every skill that plays it. The line "Used by" under the card
> names those skills.

A factor of 1.00x changes nothing. A drag moves about 1% a pixel, and a whole drag is one step of Undo.

A new name of a copy is one step of Undo too. The copy keeps its edits, and the skills that play it follow the new
name. A name has letters, digits and underscores and cannot end in a digit. After an install the copy is a class of the
client and keeps its name.

### If it went wrong

- The effect looks the same after a change: the emitter you changed may be off. Check its eye under **EMITTERS**.
- **Colour** does not make it brighter: a tint only darkens. Use **Opacity** or **Hue**.
- A new name of a copy is refused: a red line under **Name of the copy** says why, such as a name that ends in a digit
  or that another class has.
- Your edits did not come back after a restart: the client's [packages](guide:terms/package) changed since, and the panel names them with
  **Apply** and **Discard these edits**.
