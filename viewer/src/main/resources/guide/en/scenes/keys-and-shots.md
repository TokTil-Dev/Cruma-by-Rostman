---
title: Keys, shots and easing
kind: howto
summary: Set the keys of a flight by hand, cut it into shots, move keys on the timeline and choose how a key bends.
tool: scenes
tab: director
next: scenes/actors-and-sound
related: scenes/first-film, terms/key, terms/shot, terms/easing
---
Build a flight key by key, and shape it on the timeline of the tray.

### You need

- A scene open in the tray: **New** under **MY SCENES** opens an empty one.

## Steps

1. Fly the free camera to the first view, and press <kbd>K</kbd> or **Set key**. A key stands at the playhead.
2. Press on the ruler at a later moment, fly to the next view, and press <kbd>K</kbd> again.
3. To cut to another place, put the playhead where the new shot starts, fly to its first view, and press **New shot**
   or <kbd>C</kbd>.
4. Click a key on the **Camera** track to choose it. Its title in the panel says where it stands, such as
   `KEY 2 · 4.37 s · Shot 1`.
5. Under **Ease**, choose **Smooth** or **Corner** for that key.
6. Drag keys on the track to move them in time. Shift adds a key to the ones chosen.
7. Press <kbd>Space</kbd> to play from the playhead, and **Save** to keep the scene.

When it worked, the path in the world passes through each key, and a shot of more than one key has a span with its name
on the **Camera** track, such as `Shot 2`.

![Smooth bends the path through a key and keeps its speed. Corner makes straight lines meet there.](diagram:director-ease)

## Shots and cuts

A cut is two keys at one moment: the camera jumps from the first to the second. **New shot** ends the shot at the
playhead and starts the next one with what the camera shows. A press on the span of a shot chooses all its keys, so a
drag moves the whole shot and <kbd>Delete</kbd> takes it away.

![The Camera track of a path in two shots: a cut where two keys stand at one moment, and the span of each shot.](diagram:director-shots)

## Change a key

- **Take the view** gives the chosen key what the camera shows now. Fly the free camera first.
- **Look through** puts the camera in the key, at its moment. A double-click on the key does the same.
- **Field of view** and **Time** change it with a drag, and a double-click opens them for typing. **Numbers** holds X,
  Y, Z, Yaw, Pitch and Roll.

A move of the free camera leaves the path. The label at the top right of the world then says
`Free camera · K keeps this view`, and the ruler or Play puts the camera back on the path.

## Keys of the tray

| Key | What it does |
|---|---|
| <kbd>Space</kbd> | Play, or hold |
| <kbd>K</kbd> | A key at the playhead. Over a key, it replaces that key |
| <kbd>C</kbd> | A new shot at the playhead |
| <kbd>Delete</kbd> | Takes the chosen keys away |
| <kbd>,</kbd> and <kbd>.</kbd> | A frame back, a frame on |
| <kbd>[</kbd> and <kbd>]</kbd> | The key before, the key after |
| <kbd>Cmd+Z</kbd>, <kbd>Shift+Cmd+Z</kbd> | Undo and Redo, with Ctrl in place of Cmd outside macOS. 200 steps |

A drag snaps to the frames, and to other keys, the playhead and the ends of the scene within 6 pixels. Hold Ctrl, or
Cmd on a Mac, to let it go free. Ctrl or Cmd with the wheel zooms the timeline.

### If it went wrong

- **New shot** is off: it needs a key before the playhead, and **Even speed** in **Settings** must be off.
- **Take the view** is off: the view already is the key or the path. Fly the free camera first.
- K changed a key instead of adding one: the playhead stood on that key. Move it first.
