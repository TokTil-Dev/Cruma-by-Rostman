---
title: Export a scene as a video or to Blender
kind: howto
summary: Write a cut scene or a scene of the tray as an MP4 video, or as a folder that Blender opens.
tool: scenes
next: scenes/into-a-client
related: scenes/play-a-cut-scene, scenes/video-director, terms/ffmpeg, terms/gltf
---
Take a scene out of Cruma: as a film, or as a 3D scene to work on in Blender.

![A scene can leave Cruma in three ways: as a video, as a folder for Blender, or into a client.](diagram:scene-outputs)

### You need

- A scene: a cut scene that plays or played last, or a scene in the tray with at least one key.
- For a video, the program ffmpeg. Without it the button is off, and its tip says what to install.

## Steps

1. In the **Scenes** panel, play the cut scene. Its export stands under the stop button. With a scene in the tray, open
   the **Export** card instead.
2. Choose **Blender** or **MP4**.
3. For **MP4**, choose the **Size**, from `720p` to `4K`, and **Frames a second**, `30` or `60`.
4. Press **Export MP4** or **Export to Blender**.
5. Wait for the bar. **Stop** ends the export and leaves nothing behind.
6. Press **Open video** or **Open in Blender**, or **Show folder**.

When it worked, a green line under the button names the file, its length and its size. The files go into the
**Export folder** of Settings, on the page **Folders and export**, and a new export goes beside an older one, such as
`sc_echmus_opening_2.mp4`.

## What each holds

| Export | What you get |
|---|---|
| **MP4** | A video with its sound: the world, the fades, the black bars, the slides and the lines, and no panel. A second Cruma draws it with no window, so this one stays free |
| **Blender** | The tiles of the scene as Export location writes them, the camera with a key on each frame, and for a cut scene its NPCs with their animations, its sounds and marks of its effects |

A video plays the scene at exactly 30 or 60 steps a second, however long a frame takes to draw, so a heavy shot does
not run faster in the file. 60 is smoother and takes twice as long to make. The choices stay for the next time.

### If it went wrong

- The button is off with "Play a scene first": the export takes the cut scene that plays, or played last. Play one.
- **Export MP4** is off: ffmpeg is not installed, or the Viewer started before it was. Its tip says what to do.
- **Open in Blender** is missing: Cruma did not find Blender. **Show folder** opens the folder, and Blender opens the
  files from there.
