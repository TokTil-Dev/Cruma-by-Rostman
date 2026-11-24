---
title: How a skill casts
kind: concept
summary: A skill's cast on a time axis: what plays at once, what flies, and what plays on the target at the hit.
tool: effects
tab: skills
next: effects/play-an-effect
related: effects/play-a-skill, effects/edit-a-skill, terms/hit-time, terms/skill
---
When a character casts a spell, many things happen in a few seconds: the hands glow, something flies, and the target
lights up. The [client](guide:terms/client) plays each of them at its own moment of the cast.

![A cast of 4 seconds: the cast effects start at once, the shot leaves before the hit time, and the hit plays on the target.](diagram:effect-cast)

## The time of a cast

Each [skill](guide:terms/skill) has a [hit time](guide:terms/hit-time) in the client's `Skillgrp.dat`: how long its cast takes, such as 4
seconds for Wind Strike. It also names an animation for the caster: the hands rise, hold, and throw.

## The phases

From Gracia Final on, the client keeps the effects of its skills in the package `LineageSkillEffect.u`, a class for
each skill. From Chronicle 3 to Interlude they are in `Animations/skill.usk`, an object for each skill, such as
`skill.mo.8`. For each phase of the cast a skill lists actions, and an action names an effect and where it plays.
Cruma needs one of the two packages to play a skill. C1 and C2 have neither: they keep the effects of their skills in
the code of `Engine.dll`, so there a cast shows only the animation of the caster.

In `skill.usk` a skill plays only when `Skillgrp.dat` names its object. Interlude's Wind Strike names none, so the game
draws it in the code of `Engine.dll`, and the object `skill.el.1177` in the package never plays.

| Phase | When it plays |
|---|---|
| **Cast** | At once, as the cast starts |
| **Channel** | When the caster's animation reaches its mark for it |
| **Before the shot**, **Shot** | At the shot: its flight time before the hit time, and at least 0.15 seconds before |
| **Hit** | When a flying shot reaches the target, or at the hit time for a skill whose shot does not fly |
| **Linked targets** | At the hit time |

An action can also wait a moment of its own, its delay, after its phase starts.

An effect of the **Cast** phase lasts as long as the cast. As it starts, the game stretches or shortens it, so that
it ends a fifth of a second after the shot. The glow of Surrender to Earth lives 4.5 seconds by itself, but its cast
takes 1.5, so it ends at 1.7 seconds. The glow of Heal lives 4 seconds by itself, and the game makes it last through
the whole cast of 5 seconds. An effect that waits a delay ends at the shot. A few actions say that their effect keeps
its own length. A few others, of the Halloween event skills, say that their effect goes away whole a fixed time after
it starts, all its particles at once.

An effect of the **Channel** phase follows a rule of its own. For the long area skills such as Volcano and Cyclone,
the game gives each of its particles a life that ends about 2.5 seconds before the shot, so the effect goes before the
release. For most other skills the effect keeps its own length.

The **Channel** phase waits for the caster's animation, as in the game: it starts where the animation has a mark for
it, and a cast whose animation has no mark plays no **Channel** phase at all. The figures of the player races have the
mark only in their long cast, at about two thirds of it. So the ray of Chain Heal starts near the end of its cast and
runs on into its shot, while Chain Lightning shows its bolt only at the shot.

Some skills draw a beam from the caster to the target, such as the bolt of Chain Lightning and the ray of Chain Heal.
The game points the beam at the middle of the target, whatever the effect says by itself, and makes it flash for as
long as the skill asks: about 2 seconds for these two, and up to the shot for some others.
The picture of a beam lies along it, from the caster to the target, so these two show a bundle of lightning that
starts as one line at the caster and spreads towards the target.

An effect that does not fly faces the way from the caster to the target, also when it plays on the target. So a blow
on the target goes on the way of the caster, however the target stands: the slash of Mortal Blow in Essence, which
plays on the target, cuts away from the caster as it does in the clients where it plays on the caster.

A flying shot does not take a fixed time. It starts at the speed of its effect and flies faster and faster, and it
follows the target. It aims at a bone of the target, mostly the spine, and follows it while the target moves. It faces the way it
flies. An effect that turns by itself as it plays keeps turning in flight when the game says so: the blade of Sonic
Blaster rolls about its way. Over a short way it arrives before the hit time, over a long way after it: Wind Strike takes 0.1
seconds for 100 units and half a second for 900. Only some effects of a shot fly, the ones the game makes
projectiles. The rest stay where they start, such as a glow in the caster's hands. When a skill has two flying
shots, its hit plays as each one arrives. Some shots fly on a curve instead: up and down onto the target, or around
it through points beside the way, as the missiles of a skill of Lindvior do. A curve takes the skill's flight time, so it arrives at
the hit time.

A bow skill, such as Double Shot or Stun Shot, puts an arrow into the caster's right hand **Before the shot**. The
arrow comes into the hand at the moment of the animation where the game puts it there, early in the draw, and moves
with the hand until the shot. The caster draws a bow for such a skill, whatever it holds. At the shot the arrow flies to the target, and the first effect of the **Shot** rides on it, such as the glow of Double Shot,
even when the skill names the target for that effect. The hit plays when the arrow arrives, so nothing shows on the
target before then. The arrow is a mesh of its own, such as `shining_arrow_m00_et`, and not an effect with emitters,
so the list of effects does not show it.

Some skills shoot more than once in one cast, such as Double Shot. The game takes the moments of the shots from the
caster's animation, and the skill table says which skills shoot at each of them. Each arrow comes into the hand at its
own moment and flies at its own shot, and the hit plays as each arrow arrives. The timeline then marks each shot and
each hit. With **None** as the caster, Cruma takes the moments from the animation of a human fighter that it does
not show, so the skill shoots as many times as with a character. No hand shows the arrows then, and each arrow
shows only as it flies.

## Where an effect plays

An action plays on the target unless it says the caster. When a character casts a skill on itself, the game names it
as the target too, so all the effects play on it. It can hang on a hand, an arm, the chest, a bone or the place
of the body. An effect on a bone moves with that bone, so a glow stays in the hands while they move. Most effects
grow with the body they play on, so the same hit is larger on a wide monster. As in the game, the part of an effect
in front of a body is seen over it, and the part behind the body is hidden by it.

A drain, such as Drain Health or Steal Essence, goes the other way: its shot starts on the target and flies to the
caster, as life comes back to the caster. The game knows a drain by its cast style in the skill table, not by its
effects. The shot does not fly straight. It swings out behind the target, to a side and up, and then turns to the
caster, faster and faster. When a drain has two shots, they swing to the two sides. The flight takes longer than for
other skills: about 1.4 seconds when the two stand 300 units apart. Soul Rage and Devour Soul do both: their shot
flies to the target, and when it hits, a second one flies back to the caster.

## Speed

The server makes a cast shorter for a fast caster: the cast takes the hit time × 333 / speed. Magic uses Casting Spd,
and physical skills, dances and songs use Atk. Spd. At Casting Spd 666, Hurricane casts in 2 seconds, not 4, and the
effects of its cast end sooner with it.

## What Cruma does with it

**Skills** in the **Effects** panel casts a skill between two characters, with its phases at their times. You can
change its level, the caster's speed and the distance, and edit its actions. See [Cast a skill](guide:effects/play-a-skill).
