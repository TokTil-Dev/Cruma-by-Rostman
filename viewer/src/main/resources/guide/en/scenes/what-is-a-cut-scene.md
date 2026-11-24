---
title: What is a cut scene
kind: concept
summary: The short films the game plays in the world: shots of a camera, actors, sound and pictures on a time line.
tool: scenes
next: scenes/play-a-cut-scene
related: scenes/play-a-cut-scene, scenes/video-director, terms/cut-scene, terms/shot
---
Before some boss fights, or when an airship lands, the game stops your control for a moment and plays a short film
around you. Such a film is a [cut scene](guide:terms/cut-scene). The server starts it with a number, and the client
plays it in the world you see.

![A cut scene is a list of items on a time line. Each item can hold a shot, orders to actors, a fade or a line, and music.](diagram:scene-timeline)

## Items on a time line

A cut scene is a list of items, and each item has its moment, such as 2.5 seconds after the start. When that moment
comes, the client does what the item holds:

- A [shot](guide:terms/shot) moves the camera. It looks at an actor or a point from a distance and an angle, and it
  can turn there slowly.
- Orders to actors: an NPC or a player character comes, walks, plays an animation, casts a skill or leaves.
- The screen: a fade to a colour, black bars, a line of text, a slide, or a post effect such as motion blur.
- Sound: music, a voice line or a sound.

The scene ends with its last item.

## Where the game keeps them

The client keeps its cut scenes as classes in `System/LineageSceneInfo.u`. Gracia Final has 11, High Five 35, and
P152 and 162 have 130 each. Interlude and older clients have none. Some boss scenes are not in the client at all: the
AI of an L2OFF server films them, and Cruma plays those too when you give it the server's
[script folder](guide:terms/script-folder).

## What Cruma does with them

The **Scenes** panel lists the cut scenes of the client and plays one in the world, with its camera, actors, fades,
slides, lines and sound. You can export a scene as a video or for Blender.

The video director of the same panel makes films of your own, also on a client with no cut scenes. It can also copy a
cut scene of the client and change it. See [The video director](guide:scenes/video-director).

[Play an example cut scene](action:scenes-play-example)
