---
title: Make a first film
kind: howto
summary: Circle a place with the Orbit quick start, play the flight, and save it, in a few minutes.
tool: scenes
tab: director
next: scenes/keys-and-shots
related: scenes/video-director, scenes/keys-and-shots, scenes/export-a-scene, terms/camera-path
---
Make a flight that circles a place, with no key set by hand.

### You need

- A world open in Explore or Inspect. The tray of the director does not show in Analyze or Edit.

## Steps

1. Open **Scenes** on the tool rail, and press **New** under **MY SCENES**. The tray opens under the world.
2. Fly the free camera to the start of the circle: beside the place, at the distance and the height you want.
3. Press **Orbit** under **QUICK START**.
4. Under **Around**, choose **A point in the world**, then press on the floor in the middle of the place. An actor of
   the scene can be the middle too.
5. Set **Turns**, **Direction** and **Length**, in seconds.
6. Press **Make orbit**.
7. Press <kbd>Space</kbd> to play the flight, and **Save** at the bottom of the panel to keep it.

When it worked, the **Camera** track has eight keys for one turn, and the world shows the circle of the path with a
small camera on each key. After **Save** the bar at the bottom of the panel says `Saved`.

[Open the video director](action:scenes-open-director)

## The other quick starts

**QUICK START** shows only while a scene has no key. Later the same moves are in the **Add** menu of the tray, as
**Orbit…**, **Fly to…** and **Reveal…**. Each adds its keys as a new shot after the last key.

| Quick start | What it makes | Its fields |
|---|---|---|
| **Orbit** | A circle around a point or an actor, looking at it | **Turns** from 0.25 to 4, 1 at first. **Direction** **Left** or **Right**. **Length** |
| **Fly to** | A flight from one view to another | **Length**. Press **Start here**, fly to the end, then press **End here** |
| **Reveal** | The camera pulls back and rises, so the shot ends wide | **Distance** 1500 units at first, from 100 to 20000. **Rise** 300 at first, up to 5000. **Length** |

The keys a quick start makes are ordinary keys. You can move, change or delete each of them, and one Undo takes the
whole move back.

### If it went wrong

- **Make orbit** is off: choose what the camera turns around first, or fly aside if the camera stands right over the
  middle.
- The quick starts are grey in the **Add** menu: **Even speed** is on in **Settings**, and a path with even speed has
  one shot. Turn it off.
- **Save** is grey: this run of Cruma keeps no scene files.
