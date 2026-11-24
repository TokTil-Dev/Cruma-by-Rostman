---
title: What an export holds
kind: reference
summary: The files of an export folder, the scales of its units, and how Blender, Unity, Unreal and 3ds Max open it.
tool: export-location
related: export/export-a-location, terms/gltf
---
An export of a location is a folder named after its tiles or its place. Its main file is one [glTF](guide:terms/gltf)
file, and the other files help a 3D program set it up as the Viewer shows it.

## Files

| File | What it holds |
|---|---|
| `22_22.glb` | The whole location: [terrain](guide:terms/terrain), level geometry, meshes, doors and lamps, with their [textures](guide:terms/texture) inside |
| `open-in-blender.py` | Sets up the scene in Blender: the sky, the fog, a sun and a camera |
| `sky.png` | The sky at the hour of the export, as a panorama |
| `terrain-layers` | Each layer of the terrain with its alpha map, for a program that mixes them itself |
| `lightmaps` | The light baked on the level geometry |
| `projectors`, `animated-textures` | Pictures laid over surfaces, and the frames of looped textures such as a torch flame |
| `effects`, `effects.json` | The effects and where they stand |
| `sounds`, `sounds.json` | The sounds placed at a point and where they play. Sounds of a list and of a volume are left out |
| `music`, `music.json` | The tracks of the music zones as Ogg Vorbis or WAVE files, and where each zone plays |
| `lights.json` | The sun and the lamps with their strength, for a game engine |
| `import-into-unity.cs`, `CrumaEffect.cs`, `CrumaMusic.cs`, `CrumaSounds.cs` | Bring the sounds, music and effects into Unity, and set its lights |
| `npcs` | The NPCs, when **Place NPCs from npcpos** was on |
| `figures` | A rig for each kind of figure that the map places, such as flags and prisoners, which Blender plays as the Viewer does |
| `export-info.txt` | What was exported, and how |

## Scale

| **SCALE** | One unit is | Use it for |
|---|---|---|
| **Real size** | 4 cm | Blender and Unity, where a person is about 1.84 m tall |
| **Unreal** | 1 cm | Unreal Engine, which reads the client's numbers as centimetres |
| **1 m** | 100 cm | Programs that want one unit a metre |
| **Custom** | 0.1 to 1000 cm | Anything else |

A tile is 32768 units, so it is 1310.72 m wide at real size.

## Open it

| Program | How |
|---|---|
| Blender 3.6 or later | **Open in Blender** in the dialog, or `open-in-blender.py` in Blender's Scripting. File, Import, glTF 2.0 brings the `.glb` alone. |
| Unity 2021.3 or later | Import the `.glb` with glTFast or UnityGLTF, and add the three `.cs` files for the sounds, music and effects. |
| Unreal Engine | Import the `.glb`. Export it with the scale **Unreal**. |
| 3ds Max | Open the `.glb` in Blender and save it as FBX. |

The Viewer menu's **Export NPC…** saves an NPC with its skeleton and animations in the same way.
