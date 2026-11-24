---
title: Play an effect
kind: howto
summary: Choose an effect class, play it step by step, and hang it on a character.
tool: effects
next: effects/play-a-skill
related: effects/what-are-effects, effects/effects-mode, terms/emitter
---
Look at any effect of the client in the world, slowly and from close by.

### You need

- A game client. A world is not needed, see [Effects without a world](guide:effects/effects-mode).

## Steps

1. Open **Effects** on the tool rail, and choose **Effects** at the top of the panel.
2. Type part of a name in **Search effects by name**, such as `shock_blast`, or the name of a [package](guide:terms/package), such as
   `LineageEffect2`.
3. Click a row, or press <kbd>Up</kbd> and <kbd>Down</kbd>, to play an effect. It plays where the middle of the view
   meets the ground.
4. Use the playback row of its card: <kbd>P</kbd> pauses, <kbd>,</kbd> and <kbd>.</kbd> step a 60th of a second,
   <kbd>R</kbd> plays it again and <kbd>F</kbd> brings it into view.
5. Drag on the timeline to see one moment. The same moment always shows the same [particles](guide:terms/particle).
6. Choose **NPC** or **Player** in place of **Ground** to hang the effect on a character, on its **Feet**, **Chest**,
   **Head**, **R hand** or **L hand**. The effect faces the way the character faces. An effect that turns as it plays
   turns on from there.
7. Press <kbd>Esc</kbd> to take the effect out of the world.

When it worked, the card names the effect's package and its parent class, and says "Playing" with the number of
particles alive.

[Play an example effect](action:effects-play-example)

Each row of the list counts its [emitters](guide:terms/emitter), such as `5 sprites · 1 mesh`. The speed menu at the end of the timeline plays
an effect from 0.1x to 3x.

### If it went wrong

- "The client has no LineageEffect package.": the client folder holds no effects to list.
- The effect is out of view: press <kbd>F</kbd>.
- Nothing moves: the scene is paused. Press <kbd>P</kbd>.
- The effect plays again and again by itself, though the timeline shows one short play: its class starts itself again
  when its particles are gone. In Chronicle 3, Interlude and Gracia Final some effects do that, such as the smoke of
  a golem, and in the game they last as long as their owner. Press <kbd>Esc</kbd> to take it out.
- The effect stays on the ground with **NPC** chosen: the client draws no model for that NPC, and the card says so.
