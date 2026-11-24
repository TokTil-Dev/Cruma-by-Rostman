---
title: What are effects
kind: concept
summary: How the client draws fire, light and magic: effect classes made of emitters that throw particles.
tool: effects
next: effects/skill-casts
related: effects/play-an-effect, terms/emitter, terms/particle, terms/skill
---
The fire of a lamp, the glow over a gate, the spark of a hit and the storm of a spell: the [client](guide:terms/client) draws all of them in
the same way, as effects.

![An effect class holds emitters, and each emitter throws particles that are born, fly out, fade and die.](diagram:effect-emitters)

## Emitters and particles

An effect is made of [emitters](guide:terms/emitter). An emitter throws [particles](guide:terms/particle): small
pictures or shapes that start at its place, move, change their colour and size, and die after a short time. One
emitter throws sparks, another a ring of light, a third a flash. Together they make the effect.

- An emitter of sprites draws flat pictures, most of them turned to the camera.
- An emitter of meshes draws small 3D shapes.
- A beam draws a line from one place to another.

## Where they live

The effects are classes in the LineageEffect [packages](guide:terms/package) of the client's `System` folder: `LineageEffect.u`, and in newer
clients also `LineageEffect2.u` and others. A class has a name such as `at_shock_blast_ta`, and its emitters are inside
it. A map places some effects in the world, such as the fire of a lamp. The game starts others for a skill, a hit or an
event.

A package under a name of your own, such as `CustomSkillEffects.u`, can hold effects too. Put it into `System` and
give a skill an effect of it: the game loads the package by the name the skill gives. Effects then lists its effects
with the others, and you can find them by the name of the package. A package from a compiler of an older engine
build plays too, as it does in the game.

## Light of an effect

An effect can also give off a light of its own, which colours the bodies of characters near it for a short time. Few do:
in High Five only the decoration of Tiat, `e_u608_tiat_deco`, a red light for 0.3 seconds. The **Effects** panel shows
it on a character of **NPC** or **Player** beside the effect, such as on **R hand**: the body turns red while the light
lives. The client gives this light to bodies only, not to the ground or the buildings, and neither does Cruma.

## Skills use them

A [skill](guide:terms/skill) plays effects while it is cast: a glow in the hands, a shot that flies, a flash on the
target. A client keeps this plan in the package `LineageSkillEffect.u` from Gracia Final on, and in `skill.usk` from
Chronicle 3 to Interlude. See
[How a skill casts](guide:effects/skill-casts).

## What Cruma does with it

The **Effects** panel lists every effect class of the client and plays the one you choose in the world, alone or on a
character. You can change its colours, sizes and emitters, and write the changed packages to a folder. Nothing in the
client changes until you install them.

[Play an example effect](action:effects-play-example)
