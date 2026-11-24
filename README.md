# Cruma

An independent desktop explorer for Unreal Engine 2-era **Lineage II** worlds.
Read packages from your own local client, fly through the scene, and inspect geometry, materials,
lighting and effects with a modern bgfx renderer.

**Preview software.** Game content is not part of this repository, and the application does not download it.
Cruma is not affiliated with, endorsed by, or a product of NC/NCSOFT or Epic Games. Cruma's original code is under
the PolyForm Noncommercial License 1.0.0 in `LICENSE`: you may use, change and share it for any noncommercial
purpose. The licenses of the libraries, fonts and ported code are in `third_party/`.

This repository holds the source that is needed to build and run the application. It has no tests and no
development documents.

## What it does

- Renders terrain, BSP, static meshes, authored material animation, water, skies, fog and particle effects.
- Plays supported local music and ambient audio.
- **Explore** keeps the scene clear; **Inspect** shows a selected object's materials, animation and source;
  **Analyze** adds the object catalogue and animation/comparison controls.
- **Edit** transforms static-mesh actors, supports group selection and Undo/Redo, sculpts and paints Terrain,
  and exports edited `.unr` maps to a separate folder.
- Shows and edits server geodata and NPC spawns, NPC data, map events, effects and skills.
- Captures configured map tiles as images.
- Exports map tiles as glTF for Blender, 3ds Max and other 3D programs, NPCs as rigged glTF characters with their
  animations, and cut scenes for Blender or as MP4 files with ffmpeg.
- **Explains itself.** The Guide inside the application says what geodata, spawns, map layers and the other
  things are, and how to do a job with each tool. Open it with Shift+F1 or the book button in the top bar.
- **Works with an AI agent.** The `mcp` command lets an agent such as Claude use these tools through the Model Context
  Protocol. **Settings > AI agent** shows the command that connects it, and the Guide explains the access rules.

`core/` reads packages and assembles a graphics-API-independent scene. `viewer/` contains the bgfx renderer, the
interface, configuration, capture tools and the application entry point. `build-logic/` builds the shader
compiler, the shaders and the patched bgfx library.

## Compatibility

The package reader supports the UE2 package family and `Lineage2Ver111/121` containers, the
`Lineage2Ver211/212` text tables of C1 and the `Lineage2Ver411/412/413` data files of C2 and later. Development
and local checks have used C1 to C5, Interlude, Gracia Final, High Five and P152/P162 clients. This is not a
promise that every package, region, effect or later client is supported.

| Platform | Renderer |
| --- | --- |
| macOS, Apple Silicon | Metal |
| Windows x64 | D3D11 / D3D12 / Vulkan |
| Linux x64 | Vulkan |

Experimental hardware ray queries and GI need the Metal bridge and a compatible Apple GPU. The portable raster
renderer does not need them.

## Build and run

Install a JDK **25 or newer** to run Gradle, CMake **3.20+**, and a C++ toolchain: Xcode command-line tools
on macOS, Visual Studio C++ tools on Windows, or GCC/Clang on Linux. Gradle provisions the project JDK
(currently **26**, pinned in `gradle/libs.versions.toml`). The first build needs Internet access for the
libraries and the pinned bgfx sources. The shader compiler and the bgfx library are built on your computer.

On Ubuntu and Debian, also install the X11 and OpenGL development files that the bgfx CMake project needs, and
`patch`, which applies Cruma's changes to the bgfx library:

```sh
sudo apt-get update
sudo apt-get install --yes --no-install-recommends libx11-dev libgl1-mesa-dev patch
```

```sh
./gradlew build
./gradlew :viewer:run
```

Use `gradlew.bat` on Windows. The first native build can take several minutes, and later launches reuse it.
Avoid `clean` for ordinary launches, because it removes that build.

`run` starts the JVM with a 12 GiB heap limit and ZGC. The start menu lets you add a client folder, choose tiles
on a map of the client, open them to view or edit, capture map images of them, and open Settings. In a wide window
a column right of the map shows one panel, chosen at its top right: the chosen place, a flight over it, the client
or its music. A new client starts with **one tile, 22_22**, or with its first tile when it has no 22_22.

To build the application as a folder that runs without Gradle:

```sh
./gradlew :viewer:installDist
viewer/build/install/cruma/bin/cruma
```

`./gradlew :viewer:distZip` packs the same folder into `viewer/build/distributions/cruma-<version>.zip`, to copy
to another computer. The folder has no Java inside: the computer needs **Java 26**, found through `JAVA_HOME` or
as `java` on the `PATH`. Build it on the kind of system it will run on, because the shaders and the bgfx library
are built for that system. On Windows the launcher is `bin\cruma.bat`.

Pass arguments to `run` with `--args`, or give them to the launcher of the folder:

```sh
./gradlew :viewer:run --args='help'
./gradlew :viewer:run --args='viewer --client "/Games/Lineage II"'
viewer/build/install/cruma/bin/cruma viewer --client "/Games/Lineage II"
```

You need your own compatible **Lineage II client folder** with Maps, Textures, StaticMeshes and system. Cruma
reads those files directly. It does not install, repair or download a game client.

For a first look, plan for a modern 4-core CPU, 16 GiB RAM and a GPU with 4 GiB VRAM, or Apple Silicon with
16 GiB unified memory. Memory use depends on the client, the number of tiles, the resolution and the effects.
Start with one tile before you load a larger area.

## Configuration

**Settings** opens from the gear of the start menu and from **Settings…** in the Viewer's menu. It has a page for the
chosen client, with its world, its map events and its geodata and script folders, and the pages Graphics, Interface,
Audio, Places, Tools, Folders and export, Capture rendering, AI agent and Advanced, with a search across all of them.
Changes are checked before saving and apply to the next launch. In the Viewer, **Save and load again** opens the
world again with them. The files of Capture, its radar map and its tile views are set in the **Capture map images**
dialog.

Settings writes changed settings to `cruma.conf`. At start, Cruma merges this file over the defaults of the
running version. `cruma.defaults.conf` beside it is a reference copy of those defaults with a comment for each
key. It is written again on every start, so edits to it have no effect.

Default locations: `~/Library/Application Support/Cruma` on macOS, `%APPDATA%\Cruma` on Windows, and
`$XDG_CONFIG_HOME/cruma` (or `~/.config/cruma`) on Linux. `--config <directory>` chooses another folder.

```hocon
viewer.world.client_path = "/Games/Lineage II"
viewer.world.min_x_tile = 22
viewer.world.max_x_tile = 22
viewer.world.min_y_tile = 22
viewer.world.max_y_tile = 22
```

The commands and their options:

```sh
viewer --client "/Games/Lineage II"                  # Skip the picker for this launch
viewer --no-client-picker                            # Use viewer.world.client_path
viewer --client "/Games/Lineage II" --map Lobby01    # Open a map that is not a world tile
viewer --client "/Games/Lineage II" --effects        # Open the Effects tool with no map
viewer --client "/Games/Lineage II" --editing        # Open the Viewer in Edit
viewer --print-config
capture --config /path/to/config
help                                                 # List every command and option
```

## Controls

| Input | Action |
| --- | --- |
| Right-drag | Look around |
| W A S D, E / Space | Move; up / down |
| Shift / Alt | Fast / fine movement |
| 1–5, + / - | Speed presets and adjustment |
| O | Orthographic view |
| M | Open / close the world map |
| Left / Right | Previous / next tile |
| F1 | Help |
| Shift+F1 | The Guide |
| F2 | Cycle Explore / Inspect / Analyze |
| Tab | Open / close the Object panel |
| F3 | Scene settings |
| F4 | Performance |
| F12 | Capture a screenshot |
| H | Hide the HUD |

## Troubleshooting

- **Missing meshes or effects:** not every client and package is supported yet.
- **Out of memory:** load fewer tiles. Heap, native and GPU memory are separate budgets.
- **macOS direct Java launch:** add `-XstartOnFirstThread`. `run` and the launcher of the folder already do so.

Do not upload captured game imagery or extracted packages in bug reports.
