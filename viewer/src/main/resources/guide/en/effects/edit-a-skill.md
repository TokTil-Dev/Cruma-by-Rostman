---
title: Edit a skill
kind: howto
summary: Change which effects a skill's cast plays, when and where, on its tracks of actions.
tool: effects
tab: skills
next: effects/export-effects
related: effects/skill-casts, effects/edit-an-effect, terms/hit-time
---
Change the plan of a [skill](guide:terms/skill)'s cast: which effect plays in each phase, on whom, and how late.

### You need

- A skill chosen under **Skills** in the **Effects** panel, on a client that has the package `LineageSkillEffect.u`, or
  `skill.usk` from Chronicle 3 to Interlude. See [Cast a skill](guide:effects/play-a-skill).

## Steps

1. Press the pencil on the skill's card. **ACTIONS** shows its phases as tracks under one time axis.
2. Click a bar to choose its action. Drag it left or right to change its delay.
3. Change the fields of the chosen action: **Effect** to play another effect, **On** for **Target** or **Caster**,
   **Place** for where on the body, **Scale** for its size, and **Phase** to move it to another phase.
4. Double-click a bar, or press the pencil beside **Effect**, to edit that effect itself.
5. Press **Add effect** to add an action, in a phase you choose.
6. Change **Flight time**, when the skill has a shot, to make the shot leave earlier or later.

When it worked, the cast plays the change at once, and the skill's row says "edited". A label turns yellow while its
value differs from the client's.

> **Note:** The copy button beside **Effect** makes a copy of the effect for this skill alone, so other skills keep the
> original.

> **Note:** A client of Chronicle 3 to Interlude has fewer choices. **Scale** is off, as its actions have no size of
> their own. Chronicle 3 also has no **Delay** and no **Channel** phase, and only Interlude has **Right foot** and
> **Left foot** under **Place**. A yellow note on the card says when `Skillgrp.dat` names another object for the skill,
> or none: then the game never plays this one, and an edit of it changes nothing in the game.

### If it went wrong

- "This skill plays no effect.": the skill's class lists no actions. Press **Add effect**.
- A bar is an outline with "(missing)": the client has no effect of that name.
- A change to an effect shows in other skills: they play the same effect. Make a copy first.
