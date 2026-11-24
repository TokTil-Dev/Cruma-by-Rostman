---
title: Cast a skill
kind: howto
summary: Play a skill's cast between a caster and a target, at its range, level and casting speed.
tool: effects
tab: skills
next: effects/edit-an-effect
related: effects/skill-casts, terms/hit-time, terms/skill
---
See a [skill](guide:terms/skill)'s effects play in the order and at the times the game plays them.

### You need

- A client that has a skill package: `LineageSkillEffect.u` from Gracia Final on, or `skill.usk` from Chronicle 3 to
  Interlude. C1 and C2 have neither, as they keep the effects of their skills in the code of `Engine.dll`, where Cruma
  cannot read them.

## Steps

1. Open **Effects** and choose **Skills** at the top of the panel.
2. Type in **Search skills by name or id**, such as `hurricane` or `1239`, and click the skill.
3. Watch the caster cast and the target take the hit. They stand as far apart as the skill's cast range.
4. Change **Level** when the skill has more than one level.
5. Change the speed, **Casting Spd** or **Atk. Spd**. At 333 the cast takes its [hit time](guide:terms/hit-time).
6. Change **Distance** to bring the target nearer, or to stand it up to twice the range away.
7. Choose **NPC** or **Player** under **Caster** and **Target** to cast with other characters. **None** plays the
   effects with nobody there. On Essence, **Race** of **Player** also offers **Sylph**, **High Elf** and **Crow** in
   the worlds whose tables have them, each with the animations and the voices of its own, and **Class** the classes
   with a figure of their own, such as **Death Knight**, **Assassin** or **Vanguard Rider** on his wolf, with the
   animations and the hair of that class and the voices of its race.
8. Choose **Self** under **Target** to cast the skill on the caster itself, as when you target yourself in the game.
   The effects for the target, such as the glow of Might, then play on the caster. **Copy**, the choice at first,
   stands a copy of the caster as the target.

Drag left or right on a field to change it, or double-click it to type.

When it worked, the card says how long the cast takes and which animations play, such as "Casts 4.0 s" and "CastMid,
CastEnd, MagicThrow". The timeline marks the shot and the hit, or each shot and each hit of a skill that shoots more
than once, such as Double Shot.

### If it went wrong

- "The client has no skill package.": C1 and C2 keep the effects of their skills in the code of `Engine.dll`. **Effects** at the top of the panel still plays each effect class.
- The target is out of view: a skill of a long range stands it far to the right. Turn the camera, or lower **Distance**.
- A buff plays on a second character and not on the caster: the target is **Copy** at first. Choose **Self** under
  **Target**.
- The caster only waits: most monsters have no animation for a skill, and wait through the cast as in the game.
- One effect of the skill does not show: the package of its class is not in `System`, or a part of the effect could
  not be read. The skill plays its other effects, and the log names the effect.
