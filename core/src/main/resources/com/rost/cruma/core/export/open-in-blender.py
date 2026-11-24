# Opens a Cruma export in Blender as a scene ready to animate.
#
# Start it with Open in Blender in Cruma, or run it yourself:
#   blender --python open-in-blender.py
# or in Blender: Scripting, Open this file, Run Script.
#
# It makes a new scene named after the export, so scenes already open stay as
# they are, and then:
# - imports the export's .glb file and puts each tile and each of its parts,
#   such as Terrain or Static meshes, in a collection of its own, so a
#   collection's checkbox hides the whole part,
# - gives each terrain a material that mixes its layers, from the files in the
#   terrain-layers folder, as the client mixes them. The baked texture stays in
#   the material: the Mix node "Layers or baked texture" shows it at factor 1,
# - lights each terrain with the client's own light, as the viewer does: the
#   light of its lamps with the shadows of leaves and walls, and the sun's
#   light with the shadows the client baked for the export's hour, under the
#   textures its projectors lay over the terrain, such as the light through
#   a tree's leaves. The Mix Shader "Blender light or client light" shows
#   Blender's light at 0,
# - lights level geometry with the client's lightmaps of the export's hour,
#   and each placed mesh with the light the client baked for it, as the
#   client does, under the projectors that reach them,
# - places each kind of grass at its tufts with a Geometry Nodes modifier, so
#   tens of thousands of tufts are one object that edits as one tuft,
# - puts each NPC's rig from the npcs folder where its still figure stands,
#   playing its idle animation, each one from another frame so they do not
#   move together, and hides the figures,
# - puts the rig of each pawn that a map places, such as a flag, a prisoner or
#   a seal of the lobby, from the figures folder where its still figure stands,
#   turned, scaled and mirrored as the figure is, and hides the figure. From
#   the first frame it plays what the pawn plays, one sequence after another,
#   as the client picks them, so the flags of a camp move together as in the
#   viewer. A pawn that plays nothing stands in the rest pose of its skeleton,
# - lights these NPCs and pawns, and the NPCs and characters of a cut scene, as
#   the viewer lit them before it took the lights of the place (it keeps that
#   light for a model in no level): their texture in a white light from the
#   scene's sun and an ambient light that is brighter from above, the same at
#   every hour.
#   The node group "Cruma viewer light of NPCs" holds the sums, and the Value
#   node "Blender light or client light" of a material shows Blender's light
#   at 0,
# - plays a character's idle animation, and makes each of its animations
#   repeat, so the one picked in the Action Editor plays on. Its bones are
#   hidden in the views (Overlays, Bones shows them) and draw as thin lines,
# - in a scene of many NPCs, plays each one's own idle: its actions are named
#   after it, such as Roxxy_30006|wait,
# - lights a location with the client's sun, ambient light and sky at the
#   export's hour, and adds the client's fog in the collection Client fog,
#   which starts switched off: tick it in the Outliner to see the fog,
# - gives each of the client's lamps, in a tile's collection Lamps, the
#   client's light: its colour, and a light that fades to nothing at its
#   radius as the client's does. The scene's custom property Lamp strength
#   makes all of them brighter or darker. The collection starts switched off,
#   as the client light of the terrain and the grass has the lamps' light
#   already: tick it in the Outliner to light the meshes with them too,
# - gives each of the client's sounds, an empty in a tile's collection Sounds
#   with the sound's settings in its custom property cruma_sound, a speaker
#   that plays it as the client does, heard from the camera. The collection
#   starts switched off: tick it and play the timeline to hear the sounds,
# - sizes the camera, the views' clip distances and the fog box by the export's
#   scale (cruma_metres_per_unit in the .glb), so a location exported with
#   1 unit = 1 m or 1 unit = 1 cm opens as well as one at real size,
# - adds a sun, a sky colour and a camera when the scene has none. In a map
#   with a name of its own, such as a lobby, the camera stands where the
#   viewer's camera starts in the map (cruma_view in the .glb),
# - shows the textures in the 3D views, in the light of the scene's sun and sky,
#   and turns the views to the camera's side,
# - in the export of a cut scene, which has a scene.json, plays the scene: the
#   camera follows its path and the 3D views look through it, each actor is an
#   empty that moves with its rig under it, and the rig's sequences lie on its
#   track in the NLA Editor at their times. The music, voices and sounds lie in
#   the Sequencer, and an effect is an empty with its name and a marker on the
#   timeline, as glTF has no form for particles. The doors of the map that it
#   moves have keys. The fades, the bars, the slides and the lines are planes on
#   the camera with keys, so a render has them.
# Works with Blender 3.6 and later.

import importlib
import json
import math
import os
import random
import re
import statistics
import struct
import sys
import tempfile
import wave

import bpy
from mathutils import Matrix, Vector


def export_folder():
    """The folder of the export: given after --, or the folder of this file."""
    if "--" in sys.argv:
        given = sys.argv[sys.argv.index("--") + 1:]
        if given and os.path.isdir(given[0]):
            return os.path.abspath(given[0])
    candidates = []
    if "__file__" in globals():
        candidates.append(globals()["__file__"])
    space = getattr(bpy.context, "space_data", None)
    text = getattr(space, "text", None) if space else None
    if text is not None and text.filepath:
        candidates.append(bpy.path.abspath(text.filepath))
    for candidate in candidates:
        if candidate and os.path.isfile(candidate):
            return os.path.dirname(os.path.abspath(candidate))
    raise RuntimeError("Cannot tell which export to open. Run Blender with: blender --python open-in-blender.py -- <export folder>")


def main_file(folder):
    """The export's .glb, named like its folder, else any .glb or .gltf."""
    name = os.path.basename(folder.rstrip(os.sep))
    for candidate in (name + ".glb",):
        if os.path.isfile(os.path.join(folder, candidate)):
            return os.path.join(folder, candidate)
    files = sorted(os.listdir(folder))
    for extension in (".glb", ".gltf"):
        for file in files:
            if file.lower().endswith(extension):
                return os.path.join(folder, file)
    raise RuntimeError("The export folder has no .glb file: " + folder)


# One client unit in metres at real size, 4 cm. The distances below were chosen for it.
REAL_METRES_PER_UNIT = 0.04


def gltf_json(path):
    """The JSON part of a .glb, or an empty dict."""
    if not path.lower().endswith(".glb"):
        return {}
    try:
        with open(path, "rb") as file:
            header = file.read(20)
            if len(header) < 20 or header[:4] != b"glTF":
                return {}
            length, kind = struct.unpack("<II", header[12:20])
            if kind != 0x4E4F534A:
                return {}
            data = json.loads(file.read(length))
    except (OSError, ValueError):
        return {}
    return data if isinstance(data, dict) else {}


def file_extras(data):
    """The extras of the .glb, where Cruma writes its scale and the client's light, or an empty dict."""
    extras = data.get("extras", {})
    return extras if isinstance(extras, dict) else {}


def accessor_bytes(path, data, index):
    """The bytes of an accessor of normalized unsigned bytes of a .glb, from its binary chunk."""
    accessor = data["accessors"][index]
    view = data["bufferViews"][accessor["bufferView"]]
    width = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4}[accessor["type"]]
    if accessor.get("componentType") != 5121:
        raise RuntimeError("Cruma writes the light of meshes as bytes")
    with open(path, "rb") as file:
        json_length = struct.unpack("<I", file.read(16)[12:16])[0]
        file.seek(20 + json_length + 8 + view.get("byteOffset", 0) + accessor.get("byteOffset", 0))
        return file.read(accessor["count"] * width)


def accessor_floats(path, data, index):
    """The floats of an accessor of a .glb, read from its binary chunk, as rows of its type."""
    accessor = data["accessors"][index]
    view = data["bufferViews"][accessor["bufferView"]]
    width = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4}[accessor["type"]]
    count = accessor["count"]
    if accessor.get("componentType") != 5126:
        raise RuntimeError("Cruma writes the places of copies as floats")
    with open(path, "rb") as file:
        json_length = struct.unpack("<I", file.read(16)[12:16])[0]
        start = 20 + json_length + 8 + view.get("byteOffset", 0) + accessor.get("byteOffset", 0)
        file.seek(start)
        raw = file.read(count * width * 4)
    values = struct.unpack("<%df" % (count * width), raw)
    return [values[i:i + width] for i in range(0, len(values), width)]


def environment(extras):
    """The client's light at the export's hour, from the extras of the .glb, or None."""
    found = extras.get("cruma_environment")
    return found if isinstance(found, dict) else None


# A Blender sphere lamp whose size is half its cutoff distance fades as the client's lamps do: 1 - 3f^2 + 2f^3 of
# its light at the share f of the cutoff, within a few percent from f = 0.25 on. Measured in EEVEE of Blender 5.2: a
# lamp of 100 W, size 1 and cutoff 2 at a distance of 1 above a white matte plane made it 1.043 bright. That is
# 0.04172 for 1 W at a cutoff of 1, and it falls with the square of the cutoff.
LAMP_HALFWAY = 0.04172

# The scene's custom property whose value scales the light of every lamp of the client.
LAMP_STRENGTH = "Lamp strength"


def lamp_scale(env):
    """How much the display light of the client's lamps counts in Blender's linear light.

    The client lights a surface with twice a lamp's colour, times the cosine of the light's angle and the falloff,
    and adds that to the sun's and the ambient light in display colour. In linear colour, a little more display
    light A adds 2.2 * A^1.2 times as much, so a lamp adds more where the other light is brighter. The script takes
    A from the export's hour, the client's ambient and sun light together, at most 1: 4.4 at noon, less at night.
    On HF Lobby01 at 13:00 the warm light under the tree then comes within 0.02 of the viewer's.
    """
    light = 1.0
    client = env.get("client") if env else None
    if isinstance(client, dict) and len(client.get("ambient", [])) == 3 and len(client.get("sun", [])) == 3:
        light = min(1.0, max(a + b for a, b in zip(client["ambient"], client["sun"])))
    return 2 * 2.2 * max(light, 0.05) ** 1.2


def client_lamps(objects, env):
    """Gives each of the client's lamps the client's light, from the cruma_lamp extras of its node. Returns how many.

    The client's light of a lamp is all of its colour at the lamp, half of it halfway to its radius and nothing at
    the radius. A spot shines in a cone that fades from its middle to its edge. The lamps cast no shadows. A driver
    multiplies each lamp's power by the scene's Lamp strength, 1 at first.
    """
    scale = lamp_scale(env)
    scene = bpy.context.scene
    count = 0
    for obj in objects:
        lamp = obj.get("cruma_lamp") if obj.type == "LIGHT" else None
        if lamp is None:
            continue
        radius = float(lamp.get("radius", 0))
        colour = [max(0.0, float(value)) for value in lamp.get("color", [])]
        if radius <= 0 or len(colour) != 3 or max(colour) <= 0:
            continue
        brightest = max(colour)
        light = obj.data
        light.color = [value / brightest for value in colour]
        # Halfway to the radius the client gives half of the lamp's light.
        power = scale * brightest * 0.5 * radius * radius / LAMP_HALFWAY
        light.energy = power
        if LAMP_STRENGTH not in scene.keys():
            scene[LAMP_STRENGTH] = 1.0
        # A simple expression, which runs without Python scripts allowed.
        driver = light.driver_add("energy").driver
        driver.type = "SCRIPTED"
        variable = driver.variables.new()
        variable.name = "strength"
        variable.targets[0].id_type = "SCENE"
        variable.targets[0].id = scene
        variable.targets[0].data_path = '["' + LAMP_STRENGTH + '"]'
        driver.expression = "strength * %.6g" % power
        light.shadow_soft_size = radius / 2
        # The client lights terrain and meshes from its lamps without shadows. With shadows, the 593 lamps of GF
        # 20_21 kept EEVEE of Blender 5.2 rendering for more than 10 minutes; without, 0.6 s.
        light.use_shadow = False
        if hasattr(light, "use_custom_distance"):
            light.use_custom_distance = True
            light.cutoff_distance = radius
        if light.type == "SPOT":
            light.spot_size = 2 * math.acos(min(1.0, max(-1.0, float(lamp.get("cone", 0)))))
            light.spot_blend = 1.0
        count += 1
    return count


def switch_off_lamps(objects):
    """Switches off the collections of the client's lamps, as the collection Client fog starts.

    The terrain and the grass show the client's light, which has the lamps' light with the shadows of leaves and
    walls. The client gives each mesh only the lamps listed for it, with their shadows, while Blender lights every
    surface with every lamp, and lamp shadows are too slow: the green lamps among the ruins of GF 20_21 made the
    whole place green, and the tree of HF Lobby01 came out 40 percent brighter than in the viewer.
    """
    switch_off({collection for obj in objects if obj.type == "LIGHT" and obj.get("cruma_lamp") is not None
                for collection in obj.users_collection})


def is_sound_day(hour):
    """The client's day of a sound, UL2NEnvManager::IsSoundDay: from 6:00 to midnight."""
    return hour >= 5.999 or hour <= 0.001


def is_sound_night(hour):
    """The client's night of a sound, UL2NEnvManager::IsSoundNight: from midnight to 6:00."""
    return hour < 6.001 or hour > 23.999


def sound_clock(hour):
    """The game time as the client compares it with the hours of a sound, HHMM.SS: 16:19:30 is 1619.3."""
    hours = int(hour)
    fraction = hour - hours
    minutes = int(fraction * 60.0)
    seconds = int((fraction - minutes / 60.0) * 3600.0)
    return hours * 100 + minutes + seconds / 100


def sound_plays_at(settings, hour):
    """Whether the client lets a sound play at this hour of the game day, with the listener out of water.

    A sound of the day plays from 6:00 to midnight, one of the night from midnight to 6:00, one of range_time while
    the game time lies from the start to the end of one of its hours, both included, and one of water never.
    """
    when = settings.get("when", "always")
    if when == "day":
        return is_sound_day(hour)
    if when == "night":
        return is_sound_night(hour)
    if when == "water":
        return False
    if when == "range_time":
        clock = sound_clock(hour)
        hours = settings.get("hours")
        for window in hours if isinstance(hours, list) else []:
            if isinstance(window, dict) and float(window.get("start", 0)) <= clock <= float(window.get("end", 0)):
                return True
        return False
    return True


def sound_chance(settings):
    """The chance in percent of an ambient sound that plays now and then, or 100 when it loops.

    An export of version 1 gave random_seconds, which was the client's AmbientRandom: from 1 to 99 it is the chance,
    and any other number loops.
    """
    if settings.get("loop"):
        return 100
    try:
        chance = int(float(settings.get("chance", settings.get("random_seconds", 100))))
    except (TypeError, ValueError):
        return 100
    return chance if 0 < chance < 100 else 100


def chance_plays(chance, start_time, length, pitch, timeline, rolls):
    """The times on the timeline at which the client starts a sound of this chance, in seconds.

    The client rolls the chance while the sound may play and does not play: at once the first time when the start
    time is 0, else when (int) ((now - start) / length) grows, so at start + n x length for every whole n but 0, as
    the cut toward zero makes the two lengths around the start one. A win plays the sound once, for its length over
    its pitch. The timeline starts as a frame after the one before, so a stored start time does not roll at 0. A play
    that ends right on the next length frees the sound for that roll, where the client's frames decide either way.
    """
    if length <= 0:
        return []
    times = [0.0] if start_time == 0 else []
    start = 0.0 if start_time == 0 else float(start_time)
    n = math.floor(-start / length) + 1
    while start + n * length <= timeline:
        if n != 0:
            times.append(start + n * length)
        n += 1
    plays = []
    free = 0.0
    for time in times:
        if time >= free and rolls.randrange(100) < chance:
            plays.append(time)
            free = time + length / pitch
    return plays


def client_sounds(objects, folder, env):
    """Gives each of the client's sounds a speaker that plays it while the timeline plays, heard from the scene's
    camera, and switches off their collections. Returns how many sounds have a speaker and how many of those play at
    the export's hour.

    Each sound is an empty with its settings in its custom property cruma_sound, and its speaker is its child. The
    speaker plays the sound at full volume within its radius and at volume x radius / distance beyond, as the client
    does, and a driver of its Volume silences it while the camera is farther than max_distance, 50 radii, where the
    client hears nothing. Blender's sound only grows quieter there: without the driver, the sounds out of the client's
    reach came to a third of the power of those in it on most of HF 22_22. A 2D sound plays at its full volume within
    its reach. A sound that the client does not play at the export's hour is muted: one of the day at night, one of
    the night by day, one of range_time outside its hours, and one of water, as the camera is not under water. The
    speaker's Mute takes it back.

    A speaker plays its clip once, from its strip on the timeline. So a looping sound plays a clip that repeats it to
    the end of the timeline, written once into sounds/repeated in the export, each looping sound from another point
    of it. A sound the client plays now and then by its chance plays at each roll it wins on the timeline, see
    chance_plays, with a speaker of its own for each play after the first, and a sound that wins no roll there gets
    no speaker. The rolls of a sound draw from random numbers of its own, so it plays the same each time. A sound of
    an Emitter actor plays once after its delay.
    """
    sounds = [obj for obj in objects if obj.type == "EMPTY" and obj.get("cruma_sound") is not None]
    if not sounds:
        return 0, 0
    # The collections of each sound, found at once: users_collection looks through all of them for each object.
    wanted = {obj.as_pointer() for obj in sounds}
    homes = {}
    for collection in [bpy.context.scene.collection, *bpy.data.collections]:
        for obj in collection.objects:
            if obj.as_pointer() in wanted:
                homes.setdefault(obj.as_pointer(), []).append(collection)
    switch_off({collection for found in homes.values() for collection in found})
    scene = bpy.context.scene
    # The client's attenuation: volume_max keeps the full volume within the radius. The camera moves past still
    # sounds, which the client plays without a Doppler shift.
    scene.audio_distance_model = "INVERSE"
    scene.audio_doppler_factor = 0.0
    rate = scene.render.fps / scene.render.fps_base
    timeline = (scene.frame_end - scene.frame_start + 1) / rate
    hour = float(env.get("hour", 13)) if env else 13.0
    # The operator is the only way to give a speaker its strip on the timeline, so it makes one, and the others are
    # its copies, which keep the strip.
    try:
        bpy.ops.object.speaker_add(location=(0.0, 0.0, 0.0))
    except RuntimeError:
        return 0, 0
    template = bpy.context.view_layer.objects.active
    if template is None or template.type != "SPEAKER" or template.animation_data is None:
        return 0, 0
    template_data = template.data
    camera = scene.camera
    clips = {}
    repeated = {}
    count = 0
    playing = 0
    for empty in sounds:
        settings = sound_settings(empty)
        path = os.path.join(folder, *str(settings.get("file", "")).split("/"))
        length = clip_length(path, clips)
        if length <= 0:
            continue
        emitter = settings.get("kind") == "emitter"
        if "pitch" in settings:
            pitch = float(settings["pitch"])
        else:
            pitch = (float(settings.get("pitch_min", 1)) + float(settings.get("pitch_max", 1))) / 2
        pitch = max(pitch, 0.01)
        chance = 100 if emitter else sound_chance(settings)
        loop = bool(settings.get("loop")) or (not emitter and chance == 100)
        delay = float(settings.get("delay_seconds", 0))
        if loop:
            sound = repeated_clip(path, timeline * max(pitch, 1.0) + length, folder, repeated)
            starts = [delay - random.uniform(0.0, length / pitch)]
        elif emitter:
            sound = clips[path][1]
            starts = [delay]
        else:
            sound = clips[path][1]
            start_time = float(settings.get("start_time", 0) or 0)
            starts = chance_plays(chance, start_time, length, pitch, timeline, random.Random(empty.name))
        if sound is None or not starts:
            continue
        speaker = template.copy()
        speaker.name = empty.name + " speaker"
        data = bpy.data.speakers.new(str(settings.get("name", empty.name)))
        data.sound = sound
        data.volume = float(settings.get("volume", 1))
        data.pitch = pitch
        data.volume_min = 0.0
        data.volume_max = 1.0
        radius = max(float(settings.get("radius", 1)), 1e-3)
        data.distance_reference = radius
        data.distance_max = max(float(settings.get("max_distance", radius)), radius)
        data.attenuation = 0.0 if settings.get("spatial") == "2d" else 1.0
        data.muted = not emitter and not sound_plays_at(settings, hour)
        speaker.data = data
        if camera is not None:
            # A simple expression, which Blender runs without Python, so it works without Auto Run Python Scripts.
            driver = data.driver_add("volume").driver
            driver.type = "SCRIPTED"
            variable = driver.variables.new()
            variable.name = "distance"
            variable.type = "LOC_DIFF"
            variable.targets[0].id = speaker
            variable.targets[1].id = camera
            driver.expression = "%.6g if distance <= %.6g else 0" % (data.volume, data.distance_max)
        for number, start in enumerate(starts):
            # Each play after the first is a copy of the speaker with the same data, so one Mute mutes them all.
            played = speaker if number == 0 else template.copy()
            if number > 0:
                played.name = speaker.name + " " + str(number + 1)
                played.data = data
            for collection in homes.get(empty.as_pointer(), []):
                collection.objects.link(played)
            played.parent = empty
            played.matrix_parent_inverse = Matrix.Identity(4)
            played.location = (0.0, 0.0, 0.0)
            played.rotation_euler = (0.0, 0.0, 0.0)
            for track in played.animation_data.nla_tracks:
                for strip in track.strips:
                    strip.frame_start_ui = scene.frame_start + start * rate
        count += 1
        playing += 0 if data.muted else 1
    bpy.data.objects.remove(template)
    if template_data.users == 0:
        bpy.data.speakers.remove(template_data)
    return count, playing


def sound_settings(obj):
    """The settings of a client's sound from its empty's custom property cruma_sound, as a dict."""
    value = obj.get("cruma_sound")
    if hasattr(value, "to_dict"):
        value = value.to_dict()
    return value if isinstance(value, dict) else {}


def clip_length(path, clips):
    """How long the WAVE file at path plays, in seconds, 0 when it cannot be read. Loads it into Blender once and
    keeps it in clips with its length."""
    if path not in clips:
        clips[path] = (0.0, None)
        try:
            with wave.open(path, "rb") as source:
                length = source.getnframes() / source.getframerate()
            sound = bpy.data.sounds.load(path, check_existing=True)
            sound.use_memory_cache = True
            clips[path] = (length, sound)
        except (OSError, EOFError, wave.Error, RuntimeError, ZeroDivisionError):
            pass
    return clips[path][0]


def repeated_clip(path, seconds, folder, made):
    """A sound of the WAVE file at path played again and again for at least this many seconds, or None.

    Written once into sounds/repeated in the export, named after the clip and how many times it repeats, and again
    when the clip is newer. Where the export cannot be written to, the file goes into the system's temporary folder.
    """
    try:
        with wave.open(path, "rb") as source:
            params = source.getparams()
    except (OSError, EOFError, wave.Error):
        return None
    if params.nframes == 0:
        return None
    times = max(1, math.ceil(seconds * params.framerate / params.nframes))
    key = (path, times)
    if key not in made:
        made[key] = None
        name = os.path.splitext(os.path.basename(path))[0] + " x" + str(times) + ".wav"
        places = (os.path.join(folder, "sounds", "repeated"), os.path.join(tempfile.gettempdir(), "cruma-repeated"))
        for place in places:
            target = os.path.join(place, name)
            try:
                os.makedirs(place, exist_ok=True)
                if not os.path.isfile(target) or os.path.getmtime(target) < os.path.getmtime(path):
                    with wave.open(path, "rb") as source:
                        frames = source.readframes(params.nframes)
                    with wave.open(target, "wb") as out:
                        out.setparams(params)
                        out.writeframes(frames * times)
            except (OSError, EOFError, wave.Error):
                continue
            sound = bpy.data.sounds.load(target, check_existing=True)
            sound.use_memory_cache = True
            made[key] = sound
            break
    return made[key]


def switch_off(collections):
    """Switches off these collections in the view layer, as the collection Client fog starts."""

    def walk(layer):
        if layer.collection in collections:
            layer.exclude = True
        for child in layer.children:
            walk(child)

    walk(bpy.context.view_layer.layer_collection)


def start_view(extras):
    """Where the viewer's camera starts in a map with a name of its own, as Blender's (position, direction), or None.

    The file gives both in glTF's axes, and glTF (x, y, z) is Blender (x, -z, y).
    """
    found = extras.get("cruma_view")
    if not isinstance(found, dict):
        return None
    position = found.get("position")
    direction = found.get("direction")
    if not (isinstance(position, list) and len(position) == 3 and isinstance(direction, list) and len(direction) == 3):
        return None
    turn = Vector((direction[0], -direction[2], direction[1]))
    if turn.length < 1e-6:
        return None
    return Vector((position[0], -position[2], position[1])), turn.normalized()


def unit_ratio(extras):
    """How much longer a unit of this export is than at real size: 1 at 4 cm, 25 at 1 m, 0.25 at 1 cm.

    The camera, the clip distances and the fog box are sized for real size. This scales them to the export.
    """
    value = extras.get("cruma_metres_per_unit")
    if isinstance(value, (int, float)) and value > 0:
        return value / REAL_METRES_PER_UNIT
    return 1.0


def new_scene(name):
    """A new scene in the window, or the current scene when Blender runs without one."""
    window = bpy.context.window
    if window is None:
        return bpy.context.scene
    scene = bpy.data.scenes.new(name)
    window.scene = scene
    return scene


def importer_nodes():
    """The module of the glTF importer that reads EXT_mesh_gpu_instancing, or None.

    Blender 4.0 and later read the extension and make one object for each copy. For tens of thousands of tufts of
    grass that takes long and makes the scene slow, so the script places the copies itself.
    """
    for name in ("io_scene_gltf2.blender.imp.vnode", "io_scene_gltf2.blender.imp.gltf2_blender_vnode"):
        try:
            module = importlib.import_module(name)
        except ImportError:
            continue
        if hasattr(module, "manage_gpu_instancing"):
            return module
    return None


def import_file(path, one_copy=False):
    """The objects and actions a file brings, without the importer's bone shapes.

    With one_copy, a node with copies becomes one object of its mesh, and place_copies places the copies.
    """
    before = set(bpy.data.objects)
    actions = set(bpy.data.actions)
    options = {}
    # Newer importers draw every bone as a sphere unless told not to. A character of many bones then hides under
    # them.
    if "disable_bone_shape" in bpy.ops.import_scene.gltf.get_rna_type().properties.keys():
        options["disable_bone_shape"] = True
    nodes = importer_nodes() if one_copy else None
    original = nodes.manage_gpu_instancing if nodes else None
    if nodes:
        def one_object(gltf, vnode, index, *rest):
            vnode.mesh_node_idx = index

        nodes.manage_gpu_instancing = one_object
    try:
        bpy.ops.import_scene.gltf(filepath=path, **options)
    finally:
        if nodes:
            nodes.manage_gpu_instancing = original
    objects = [obj for obj in bpy.data.objects if obj not in before]
    return drop_bone_shapes(objects), [action for action in bpy.data.actions if action not in actions]


def places_group(tuft):
    """Geometry Nodes that put a copy of the tuft on every point, turned and scaled by the point's attributes.

    The copies stay instances, which draw fast. The node Make tufts real is muted: unmuted, it makes them one
    mesh, which Object, Convert, Mesh and exporters such as FBX then keep.
    """
    group = bpy.data.node_groups.new(tuft.name + " places", "GeometryNodeTree")
    if hasattr(group, "interface"):
        group.interface.new_socket("Geometry", in_out="INPUT", socket_type="NodeSocketGeometry")
        group.interface.new_socket("Geometry", in_out="OUTPUT", socket_type="NodeSocketGeometry")
    else:
        group.inputs.new("NodeSocketGeometry", "Geometry")
        group.outputs.new("NodeSocketGeometry", "Geometry")
    nodes = group.nodes
    links = group.links
    entry = nodes.new("NodeGroupInput")
    entry.location = (-600, 0)
    exit = nodes.new("NodeGroupOutput")
    exit.location = (400, 0)
    info = nodes.new("GeometryNodeObjectInfo")
    info.location = (-400, -150)
    info.label = "The tuft"
    info.transform_space = "ORIGINAL"
    info.inputs["Object"].default_value = tuft
    place = nodes.new("GeometryNodeInstanceOnPoints")
    place.location = (150, 0)
    links.new(entry.outputs[0], place.inputs["Points"])
    links.new(info.outputs["Geometry"], place.inputs["Instance"])
    for offset, (attribute, socket) in enumerate((("cruma_rotation", "Rotation"), ("cruma_scale", "Scale"))):
        read = nodes.new("GeometryNodeInputNamedAttribute")
        read.location = (-150, -300 - offset * 150)
        read.data_type = "FLOAT_VECTOR"
        read.inputs["Name"].default_value = attribute
        output = next(out for out in read.outputs if out.type == "VECTOR" and getattr(out, "enabled", True))
        links.new(output, place.inputs[socket])
    real = nodes.new("GeometryNodeRealizeInstances")
    real.location = (300, -150)
    real.name = real.label = "Make tufts real"
    real.mute = True
    exit.location = (550, 0)
    links.new(place.outputs["Instances"], real.inputs[0])
    links.new(real.outputs[0], exit.inputs[0])
    return group


def euler_xyz(x, y, z, w):
    """Blender's XYZ Euler angles of the unit quaternion x i + y j + z k + w, as Instance on Points takes them."""
    r00 = 1 - 2 * (y * y + z * z)
    r10 = 2 * (x * y + w * z)
    r20 = 2 * (x * z - w * y)
    r21 = 2 * (y * z + w * x)
    r22 = 1 - 2 * (x * x + y * y)
    pitch = math.asin(max(-1.0, min(1.0, -r20)))
    if abs(r20) < 0.999999:
        return math.atan2(r21, r22), pitch, math.atan2(r10, r00)
    r01 = 2 * (x * y - w * z)
    r11 = 1 - 2 * (x * x + z * z)
    return 0.0, pitch, math.atan2(-r01, r11)


def place_copies(objects, path, data):
    """Turns each node with copies, such as a kind of grass, into one object that draws its mesh at every copy.

    The .glb places the copies with EXT_mesh_gpu_instancing. The script reads their places from the file and gives
    them to a mesh of points with a Geometry Nodes modifier, which draws a copy of the tuft on each point. The tuft
    itself is hidden: an edit to it changes every copy. An importer that made one object for each copy has them
    removed first. Returns how many copies it placed, and the ids of the imported objects it removed.
    """
    placed = 0
    removed = set()
    for obj in [obj for obj in objects if "cruma_instances" in obj.keys()]:
        index = obj["cruma_instances"]
        try:
            node = data["nodes"][int(index)]
            attributes = node["extensions"]["EXT_mesh_gpu_instancing"]["attributes"]
            moves = accessor_floats(path, data, attributes["TRANSLATION"])
            turns = accessor_floats(path, data, attributes["ROTATION"])
            sizes = accessor_floats(path, data, attributes["SCALE"])
            lights = accessor_floats(path, data, attributes[CLIENT_LIGHT]) if CLIENT_LIGHT in attributes else None
        except (KeyError, IndexError, ValueError, RuntimeError, OSError):
            continue
        tuft = obj
        if obj.type == "EMPTY":
            # An importer of Blender 4.0 or later whose way of making copies the script did not know.
            copies = [child for child in objects if id(child) not in removed and child.type == "MESH" and child.parent == obj]
            if not copies:
                continue
            tuft = bpy.data.objects.new(obj.name + " tuft", copies[0].data)
            for owner in obj.users_collection:
                owner.objects.link(tuft)
            tuft.parent = obj
            removed.update(id(copy) for copy in copies)
            bpy.data.batch_remove(copies)
        elif obj.type != "MESH":
            continue
        name = re.sub(r"\.\d{3}$", "", obj.name)
        # The tuft gives its name to the object of all the tufts.
        tuft.name = name + " tuft"
        points = bpy.data.meshes.new(name + " places")
        # glTF has Y up, Blender Z up: (x, y, z) becomes (x, -z, y), and a quaternion's axis turns the same way.
        points.vertices.add(len(moves))
        points.vertices.foreach_set("co", [value for x, y, z in moves for value in (x, -z, y)])
        rotation = points.attributes.new("cruma_rotation", "FLOAT_VECTOR", "POINT")
        rotation.data.foreach_set("vector", [value for x, y, z, w in turns for value in euler_xyz(x, -z, y, w)])
        scale = points.attributes.new("cruma_scale", "FLOAT_VECTOR", "POINT")
        scale.data.foreach_set("vector", [value for x, y, z in sizes for value in (x, z, y)])
        if lights is not None and len(lights) == len(moves):
            # Each copy keeps it, so the tuft's material reads it through an Attribute node of type Instancer.
            light = points.attributes.new(TUFT_LIGHT, "FLOAT_COLOR", "POINT")
            light.data.foreach_set("color", [value for row in lights for value in row])
        points.update()
        grass = bpy.data.objects.new(name, points)
        for owner in obj.users_collection:
            owner.objects.link(grass)
        # The imported object stands where the node does. A new object gets its place only at the next update.
        grass.parent = obj.parent
        grass.matrix_world = obj.matrix_world.copy()
        modifier = grass.modifiers.new("Tufts", "NODES")
        modifier.node_group = places_group(tuft)
        tuft.hide_set(True)
        tuft.hide_render = True
        placed += len(moves)
    return placed, removed


def part_collections(objects):
    """Puts each tile, and each of its parts such as Terrain or Static meshes, in a collection of its own.

    The .glb keeps a tile and its parts as parent objects. When the eye of a parent object is closed, Blender hides
    only that object and not its children, so a part seemed to stay on. A collection's checkbox or eye hides all of
    it.
    """
    imported = set(objects)
    targets = []
    for tile in [obj for obj in objects if obj.parent is None and obj.type == "EMPTY"]:
        tile_collection = bpy.data.collections.new(tile.name)
        bpy.context.scene.collection.children.link(tile_collection)
        targets.append((tile, tile_collection))
        for part in tile.children:
            if part not in imported:
                continue
            if part.type == "EMPTY" and part.children:
                part_collection = bpy.data.collections.new(part.name)
                tile_collection.children.link(part_collection)
                targets.extend((obj, part_collection) for obj in [part, *part.children_recursive])
            else:
                targets.append((part, tile_collection))
    # The objects leave the collections the importer put them in one collection at a time. Asking each object for
    # its collections looks through all of them: 87 seconds for the 50,000 objects of 25 tiles in Blender 5.2.
    moving = {obj for obj, _ in targets}
    for owner in [*bpy.data.collections, *(scene.collection for scene in bpy.data.scenes)]:
        for obj in [obj for obj in owner.objects if obj in moving]:
            owner.objects.unlink(obj)
    for obj, collection in targets:
        collection.objects.link(obj)


def socket(sockets, identifier):
    """A node socket by its identifier, which stays the same when names repeat, as on a Mix node."""
    return next(item for item in sockets if item.identifier == identifier)


def mix_node(nodes, location, label):
    """A Mix node that mixes two colours by a factor kept from 0 to 1."""
    mix = nodes.new("ShaderNodeMix")
    mix.data_type = "RGBA"
    mix.blend_type = "MIX"
    mix.clamp_factor = True
    mix.location = location
    mix.label = label
    return mix


def srgb_to_linear():
    """A node group that turns a display (sRGB) colour into the linear colour that Blender lights with.

    The client mixes its terrain layers in display colour. The layers' images are read as Non-Color, so they mix the
    same way, and this group turns the result to linear with the exact sRGB curve.
    """
    name = "Cruma display to linear colour"
    group = bpy.data.node_groups.get(name)
    if group is not None:
        return group
    group = bpy.data.node_groups.new(name, "ShaderNodeTree")
    if hasattr(group, "interface"):
        group.interface.new_socket("Color", in_out="INPUT", socket_type="NodeSocketColor")
        group.interface.new_socket("Color", in_out="OUTPUT", socket_type="NodeSocketColor")
    else:
        group.inputs.new("NodeSocketColor", "Color")
        group.outputs.new("NodeSocketColor", "Color")
    nodes = group.nodes
    links = group.links
    entry = nodes.new("NodeGroupInput")
    entry.location = (-900, 0)
    exit = nodes.new("NodeGroupOutput")
    exit.location = (700, 0)
    split = nodes.new("ShaderNodeSeparateColor") if hasattr(bpy.types, "ShaderNodeSeparateColor") else nodes.new("ShaderNodeSeparateRGB")
    split.location = (-700, 0)
    join = nodes.new("ShaderNodeCombineColor") if hasattr(bpy.types, "ShaderNodeCombineColor") else nodes.new("ShaderNodeCombineRGB")
    join.location = (500, 0)
    links.new(entry.outputs[0], split.inputs[0])
    for channel in range(3):
        y = 300 - channel * 300

        def math_node(operation, x, value=None, clamp=False):
            node = nodes.new("ShaderNodeMath")
            node.operation = operation
            node.location = (x, y)
            node.use_clamp = clamp
            if value is not None:
                node.inputs[1].default_value = value
            return node

        # Dark values follow a straight line, the rest a power curve: c / 12.92, or ((c + 0.055) / 1.055) ^ 2.4.
        low = math_node("DIVIDE", -450, 12.92)
        shift = math_node("ADD", -450, 0.055)
        scale = math_node("DIVIDE", -250, 1.055)
        power = math_node("POWER", -50, 2.4)
        dark = math_node("LESS_THAN", -250, 0.04045)
        pick = nodes.new("ShaderNodeMix")
        pick.data_type = "FLOAT"
        pick.location = (250, y)
        links.new(split.outputs[channel], low.inputs[0])
        links.new(split.outputs[channel], shift.inputs[0])
        links.new(shift.outputs[0], scale.inputs[0])
        links.new(scale.outputs[0], power.inputs[0])
        links.new(split.outputs[channel], dark.inputs[0])
        links.new(dark.outputs[0], socket(pick.inputs, "Factor_Float"))
        links.new(power.outputs[0], socket(pick.inputs, "A_Float"))
        links.new(low.outputs[0], socket(pick.inputs, "B_Float"))
        links.new(socket(pick.outputs, "Result_Float"), join.inputs[channel])
    links.new(join.outputs[0], exit.inputs[0])
    return group


# The glTF axes in Blender's: glTF (x, y, z) is Blender (x, -z, y) after import, so a glTF position is (X, Z, -Y).
GLTF_FROM_BLENDER = ((1, 0, 0), (0, 0, 1), (0, -1, 0))


def image_rows(layer):
    """The layer's image coordinates as two rows over Blender's object position: s = row . p + offset, and t.

    The .glb gives u and v from the glTF position. Blender reads an image from its bottom row, glTF from its top, so
    t is -v, which is the same as 1 - v on a texture that repeats.
    """
    rows = []
    for key, sign in (("u", 1.0), ("v", -1.0)):
        row = [float(value) for value in layer[key]]
        rows.append(([sign * sum(row[g] * GLTF_FROM_BLENDER[g][b] for g in range(3)) for b in range(3)], sign * row[3]))
    return rows


def mapping_values(rows):
    """Location, rotation and scale of a Mapping node (Point) that gives these rows, or None.

    A Mapping node turns and scales its axes but cannot shear. A layer that lies flat on two of the axes, which the
    client's own layers do, fits it exactly.
    """
    (s, s0), (t, t0) = rows
    largest = max(abs(value) for value in s + t)
    if largest == 0:
        return None
    unused = [axis for axis in range(3) if abs(s[axis]) + abs(t[axis]) <= largest * 1e-6]
    if len(unused) != 1:
        return None
    k = unused[0]
    a, b = [axis for axis in range(3) if axis != k]
    first = Vector((s[a], t[a]))
    second = Vector((s[b], t[b]))
    if first.length == 0 or second.length == 0 or abs(first.dot(second)) > 1e-4 * first.length * second.length:
        return None
    angle = math.atan2(first.y, first.x)
    along = first.length
    across = second.dot(Vector((-math.sin(angle), math.cos(angle))))
    # The rows of a turn that brings axis a to X, axis b to Y and the unused one to Z.
    turn = Matrix(([1.0 if axis == a else 0.0 for axis in range(3)], [1.0 if axis == b else 0.0 for axis in range(3)],
                   [1.0 if axis == k else 0.0 for axis in range(3)]))
    if turn.determinant() < 0:
        turn[2] = -turn[2]
    rotation = (Matrix.Rotation(angle, 3, "Z") @ turn).to_euler("XYZ")
    scale = [1.0, 1.0, 1.0]
    scale[a] = along
    scale[b] = across
    return (s0, t0, 0.0), tuple(rotation), tuple(scale)


def layer_coordinates(nodes, links, position, rows, location):
    """The nodes that give a layer's image coordinates from the object position: a Mapping node when it fits."""
    values = mapping_values(rows)
    if values is not None:
        mapping = nodes.new("ShaderNodeMapping")
        mapping.vector_type = "POINT"
        mapping.location = location
        mapping.inputs["Location"].default_value = values[0]
        mapping.inputs["Rotation"].default_value = values[1]
        mapping.inputs["Scale"].default_value = values[2]
        links.new(position, mapping.inputs["Vector"])
        return mapping.outputs["Vector"]
    # A sheared projection: each coordinate as a dot product with the object position.
    join = nodes.new("ShaderNodeCombineXYZ")
    join.location = (location[0] + 200, location[1])
    for index, (row, offset) in enumerate(rows):
        dot = nodes.new("ShaderNodeVectorMath")
        dot.operation = "DOT_PRODUCT"
        dot.location = (location[0] - 200, location[1] - index * 150)
        dot.inputs[1].default_value = row
        links.new(position, dot.inputs[0])
        add = nodes.new("ShaderNodeMath")
        add.operation = "ADD"
        add.location = (location[0], location[1] - index * 150)
        add.inputs[1].default_value = offset
        links.new(dot.outputs["Value"], add.inputs[0])
        links.new(add.outputs[0], join.inputs[index])
    return join.outputs[0]


def layer_image(folder, relative):
    """A layer file as an image that a node reads as it is: as data, without a colour change or premultiplied alpha."""
    path = os.path.join(folder, *relative.split("/"))
    if not os.path.isfile(path):
        return None
    image = bpy.data.images.load(path, check_existing=True)
    image.colorspace_settings.name = "Non-Color"
    image.alpha_mode = "CHANNEL_PACKED"
    return image


def terrain_layers(material, by_name):
    """The layers of a terrain material: from its custom properties, which the importer copies from the glTF
    material's extras, or else from the .glb by the material's name."""
    found = material.get("cruma_terrain_layers")
    if found is not None:
        try:
            return [dict(layer.to_dict()) if hasattr(layer, "to_dict") else dict(layer) for layer in found]
        except (TypeError, ValueError):
            pass
    return by_name.get(re.sub(r"\.\d{3}$", "", material.name))


def image_limit():
    """How many images EEVEE lets a material read. Before Blender 4.2 it keeps many of the GPU's texture slots for
    itself and draws at most about 16. EEVEE of 4.2 and later keeps 3 of the 32 of OpenGL."""
    return 16 if bpy.app.version < (4, 2, 0) else 29


def pack_alpha_maps(textures, alpha_maps):
    """Whether a terrain material reads its alpha maps four to an image instead of one image each.

    One image each lets a person paint an alpha map in Blender, so it is the first choice. 12 layers (25 images) fit
    the limit of EEVEE 4.2 and later, but a Terrain can have up to 32 layers, so more layers read packed alpha maps.
    Blender 5.2's Metal backend fails on a material of exactly 14 images: it counts one of EEVEE's samplers too few.
    """
    separate = textures + alpha_maps + 1
    packed = textures + (alpha_maps + 3) // 4 + 1
    if separate > image_limit():
        return True
    return sys.platform == "darwin" and separate == 14 and packed != 14


def fits_material(textures, alpha_maps):
    """Whether the layers fit one material, with their alpha maps packed four to an image when they must be. A
    material with more images draws nothing, so such a terrain keeps its baked texture alone."""
    if not pack_alpha_maps(textures, alpha_maps):
        return True
    return textures + (alpha_maps + 3) // 4 + 1 <= image_limit()


def resampled(grey, width, height):
    """A grey image at another size, sampled bilinearly between texel centres."""
    import numpy

    rows, columns = grey.shape
    if (columns, rows) == (width, height):
        return grey
    xs = numpy.clip((numpy.arange(width) + 0.5) * columns / width - 0.5, 0, columns - 1)
    ys = numpy.clip((numpy.arange(height) + 0.5) * rows / height - 0.5, 0, rows - 1)
    x0 = numpy.floor(xs).astype(int)
    y0 = numpy.floor(ys).astype(int)
    x1 = numpy.minimum(x0 + 1, columns - 1)
    y1 = numpy.minimum(y0 + 1, rows - 1)
    fx = xs - x0
    fy = (ys - y0)[:, None]
    top = grey[y0][:, x0] * (1 - fx) + grey[y0][:, x1] * fx
    bottom = grey[y1][:, x0] * (1 - fx) + grey[y1][:, x1] * fx
    return top * (1 - fy) + bottom * fy


def packed_alpha_maps(folder, layers, name):
    """Up to four layers' alpha maps in the red, green, blue and alpha of one image, as the client packs them.

    A material that cannot read one image for each alpha map reads them from these, see pack_alpha_maps. The files
    of the separate alpha maps stay in the terrain-layers folder.
    """
    import numpy

    loaded = [layer_image(folder, layer["alpha_map"]) for layer in layers]
    sizes = [tuple(image.size) for image in loaded if image is not None]
    if not sizes:
        return None
    width = max(size[0] for size in sizes)
    height = max(size[1] for size in sizes)
    packed = numpy.zeros((height, width, 4), dtype=numpy.float32)
    for channel, image in enumerate(loaded):
        if image is None:
            continue
        columns, rows = image.size
        values = numpy.empty(columns * rows * 4, dtype=numpy.float32)
        image.pixels.foreach_get(values)
        packed[:, :, channel] = resampled(values.reshape(rows, columns, 4)[:, :, 0], width, height)
        bpy.data.images.remove(image)
    result = bpy.data.images.new(name, width, height, alpha=True)
    result.colorspace_settings.name = "Non-Color"
    result.alpha_mode = "CHANNEL_PACKED"
    result.pixels.foreach_set(packed.ravel())
    result.pack()
    return result


def layered_terrain(material, layers, folder, alpha_uv):
    """Mixes the terrain's layers in the material, as the client mixes them.

    The first layer is the ground. Each next layer goes over the colour so far by its alpha map, times its texture's
    alpha when diffuse_alpha says so. Each layer's texture lies on the terrain by a Mapping node from the object's
    own coordinates, its alpha map by the second UV map. The mix is in display colour, as in the client and the
    baked texture, and turned to linear at the end. The Mix node "Layers or baked texture" shows the layers at 0
    and the baked texture at 1.
    """
    nodes = material.node_tree.nodes
    links = material.node_tree.links
    shader = next((node for node in nodes if node.type == "BSDF_PRINCIPLED"), None)
    if shader is None or not shader.inputs["Base Color"].links:
        return False
    baked = shader.inputs["Base Color"].links[0].from_node
    baked.label = "Baked texture"
    usable = []
    for layer in layers:
        image = layer_image(folder, layer["texture"])
        if image is None:
            continue
        if usable and not (layer.get("alpha_map") and os.path.isfile(os.path.join(folder, *layer["alpha_map"].split("/")))):
            continue
        usable.append((layer, image))
    if not usable:
        return False
    x = baked.location.x - 600
    top = baked.location.y + 300
    position = nodes.new("ShaderNodeTexCoord")
    position.location = (x - 1400, top)
    alpha_coordinates = nodes.new("ShaderNodeUVMap")
    alpha_coordinates.location = (x - 1400, top - 250)
    alpha_coordinates.uv_map = alpha_uv
    alpha_coordinates.label = "Alpha map coordinates"
    covers = {}
    painted = [layer for layer, _ in usable[1:]]
    textures = len({image.name for _, image in usable})
    alpha_maps = len({layer["alpha_map"] for layer in painted})
    if not fits_material(textures, alpha_maps):
        return False
    if pack_alpha_maps(textures, alpha_maps):
        for first in range(0, len(painted), 4):
            group = painted[first:first + 4]
            image = packed_alpha_maps(folder, group, material.name + " alpha maps " + str(first // 4 + 1))
            if image is None:
                continue
            y = top - (first + 1) * 420 - 200
            reader = nodes.new("ShaderNodeTexImage")
            reader.image = image
            reader.extension = "EXTEND"
            reader.location = (x - 1100, y)
            reader.label = "Alpha maps of layers " + ", ".join(str(layer.get("layer")) for layer in group)
            links.new(alpha_coordinates.outputs["UV"], reader.inputs["Vector"])
            split = nodes.new("ShaderNodeSeparateColor") if hasattr(bpy.types, "ShaderNodeSeparateColor") else nodes.new("ShaderNodeSeparateRGB")
            split.location = (x - 800, y)
            links.new(reader.outputs["Color"], split.inputs[0])
            for channel, layer in enumerate(group):
                covers[id(layer)] = split.outputs[channel] if channel < 3 else reader.outputs["Alpha"]
    colour = None
    for row, (layer, image) in enumerate(usable):
        y = top - row * 420
        frame = nodes.new("NodeFrame")
        frame.label = "Layer " + str(layer.get("layer", row)) + ": " + str(layer.get("name", ""))
        texture = nodes.new("ShaderNodeTexImage")
        texture.image = image
        texture.extension = "REPEAT"
        texture.location = (x - 800, y)
        texture.parent = frame
        coordinates = layer_coordinates(nodes, links, position.outputs["Object"], image_rows(layer), (x - 1100, y))
        coordinates.node.parent = frame
        links.new(coordinates, texture.inputs["Vector"])
        if colour is None:
            colour = texture.outputs["Color"]
            continue
        cover = covers.get(id(layer))
        if cover is None:
            alpha = nodes.new("ShaderNodeTexImage")
            alpha.image = layer_image(folder, layer["alpha_map"])
            alpha.extension = "EXTEND"
            alpha.location = (x - 800, y - 260)
            alpha.label = "Alpha map"
            alpha.parent = frame
            links.new(alpha_coordinates.outputs["UV"], alpha.inputs["Vector"])
            cover = alpha.outputs["Color"]
        if layer.get("diffuse_alpha"):
            times = nodes.new("ShaderNodeMath")
            times.operation = "MULTIPLY"
            times.use_clamp = True
            times.location = (x - 500, y - 150)
            times.label = "Times the texture's alpha"
            times.parent = frame
            links.new(cover, times.inputs[0])
            links.new(texture.outputs["Alpha"], times.inputs[1])
            cover = times.outputs[0]
        mix = mix_node(nodes, (x - 300, y), "Over the layers before")
        mix.parent = frame
        links.new(cover, socket(mix.inputs, "Factor_Float"))
        links.new(colour, socket(mix.inputs, "A_Color"))
        links.new(texture.outputs["Color"], socket(mix.inputs, "B_Color"))
        colour = socket(mix.outputs, "Result_Color")
    linear = nodes.new("ShaderNodeGroup")
    linear.node_tree = srgb_to_linear()
    linear.location = (x, top)
    linear.label = "Display to linear colour"
    links.new(colour, linear.inputs[0])
    switch = mix_node(nodes, (baked.location.x + 300, top), "Layers or baked texture")
    switch.name = "Layers or baked texture"
    socket(switch.inputs, "Factor_Float").default_value = 0.0
    links.new(linear.outputs[0], socket(switch.inputs, "A_Color"))
    links.new(baked.outputs["Color"], socket(switch.inputs, "B_Color"))
    links.new(socket(switch.outputs, "Result_Color"), shader.inputs["Base Color"])
    return True


CLIENT_LIGHT = "_CLIENT_LIGHT"
# The point attribute that carries a tuft's client light to its copy.
TUFT_LIGHT = "cruma_client_light"


def constant(nodes, location, values, label):
    """A node whose vector output is the three values."""
    node = nodes.new("ShaderNodeCombineXYZ")
    node.location = location
    node.label = label
    for index, value in enumerate(values):
        node.inputs[index].default_value = value
    return node.outputs[0]


def vector_math(nodes, links, operation, location, first, second=None):
    """A Vector Math node of the operation on one or two vectors."""
    node = nodes.new("ShaderNodeVectorMath")
    node.operation = operation
    node.location = location
    links.new(first, node.inputs[0])
    if second is not None:
        links.new(second, node.inputs[1])
    return node.outputs[0]


def math_node(nodes, links, operation, location, first, second=None, third=None):
    """A Math node of the operation on one, two or three values, each a socket or a number."""
    node = nodes.new("ShaderNodeMath")
    node.operation = operation
    node.location = location
    for index, value in enumerate((first, second, third)):
        if value is None:
            continue
        if isinstance(value, (int, float)):
            node.inputs[index].default_value = value
        else:
            links.new(value, node.inputs[index])
    return node.outputs[0]


def to_linear(nodes, links, colour, location, label):
    """The display colour, a vector socket, to the power of 2.2 in each channel."""
    split = nodes.new("ShaderNodeSeparateXYZ")
    split.location = location
    links.new(colour, split.inputs[0])
    join = nodes.new("ShaderNodeCombineXYZ")
    join.location = (location[0] + 400, location[1])
    join.label = label
    for index in range(3):
        power = math_node(nodes, links, "POWER", (location[0] + 200, location[1] - index * 160), split.outputs[index], 2.2)
        links.new(power, join.inputs[index])
    return join.outputs[0]


def blender_point(values):
    """A point or direction of the file's glTF axes in Blender's: (x, y, z) is (x, -z, y)."""
    return (values[0], -values[2], values[1])


def projector_factor(nodes, links, projector, folder, location, gate=None):
    """The display factor a projector multiplies a surface's colour by, as a vector socket, or None.

    The client projects the texture along the projector's direction, parallel or from a point, and reads it raw,
    in display colour. Inside the projector's volume the colour is multiplied by twice the texture (blend 0), by
    twice the texture times its alpha plus one minus the alpha (1), or by one plus twice the texture (2); outside
    by 1. A socket gate, when given, lets the projector through where it is 1 and keeps the colour where it is 0.
    """
    path = os.path.join(folder, *str(projector.get("texture", "")).split("/"))
    if not os.path.isfile(path):
        return None
    x, y = location
    frame = nodes.new("NodeFrame")
    frame.label = "Projector " + str(projector.get("name", ""))
    position = nodes.new("ShaderNodeNewGeometry")
    position.location = (x - 1600, y)
    position.parent = frame
    delta = vector_math(nodes, links, "SUBTRACT", (x - 1400, y), position.outputs["Position"],
                        constant(nodes, (x - 1600, y - 200), blender_point(projector["location"]), "Projector place"))
    delta.node.parent = frame
    projections = []
    for row, key in enumerate(("direction", "left", "up")):
        dot = vector_math(nodes, links, "DOT_PRODUCT", (x - 1200, y - row * 160), delta,
                          constant(nodes, (x - 1400, y - 250 - row * 160), blender_point(projector[key]), key.capitalize()))
        dot.node.parent = frame
        projections.append(dot.node.outputs["Value"])
    forward, across, upward = projections
    fov = float(projector.get("fov", 0))
    u_size = float(projector["u_size"])
    v_size = float(projector["v_size"])
    if fov > 0:
        tangent = math.tan(math.radians(fov) / 2)
        depth = math_node(nodes, links, "ADD", (x - 1000, y), forward, 0.5 * u_size / tangent)
        depth = math_node(nodes, links, "MAXIMUM", (x - 850, y), depth, 1e-6)
        scale = math_node(nodes, links, "DIVIDE", (x - 700, y), 0.5 / tangent, depth)
        u = math_node(nodes, links, "MULTIPLY", (x - 1000, y - 200), across, scale)
        v = math_node(nodes, links, "MULTIPLY", (x - 1000, y - 360), upward, scale)
    else:
        u = math_node(nodes, links, "DIVIDE", (x - 1000, y - 200), across, u_size)
        v = math_node(nodes, links, "DIVIDE", (x - 1000, y - 360), upward, v_size)
    u = math_node(nodes, links, "SUBTRACT", (x - 850, y - 200), 0.5, u)
    v = math_node(nodes, links, "SUBTRACT", (x - 850, y - 360), 0.5, v)
    # Image rows run up in Blender and down in the client.
    flipped = math_node(nodes, links, "SUBTRACT", (x - 700, y - 360), 1.0, v)
    coordinates = nodes.new("ShaderNodeCombineXYZ")
    coordinates.location = (x - 550, y - 250)
    links.new(u, coordinates.inputs[0])
    links.new(flipped, coordinates.inputs[1])
    texture = nodes.new("ShaderNodeTexImage")
    texture.image = bpy.data.images.load(path, check_existing=True)
    texture.image.colorspace_settings.name = "Non-Color"
    texture.extension = "EXTEND"
    texture.location = (x - 350, y - 250)
    texture.label = "Projected texture"
    links.new(coordinates.outputs[0], texture.inputs["Vector"])
    inside = None
    tests = [(forward, "GREATER_THAN", -1e-6), (forward, "LESS_THAN", float(projector["max_trace"]) + 1e-6),
             (u, "GREATER_THAN", -1e-6), (u, "LESS_THAN", 1 + 1e-6), (v, "GREATER_THAN", -1e-6), (v, "LESS_THAN", 1 + 1e-6)]
    for row, (value, operation, limit) in enumerate(tests):
        test = math_node(nodes, links, operation, (x - 550, y - 500 - row * 120), value, limit)
        inside = test if inside is None else math_node(nodes, links, "MULTIPLY", (x - 400, y - 500 - row * 120), inside, test)
    if gate is not None:
        inside = math_node(nodes, links, "MULTIPLY", (x - 250, y - 500), inside, gate)
    twice = vector_math(nodes, links, "SCALE", (x - 150, y - 250), texture.outputs["Color"])
    twice.node.inputs["Scale"].default_value = 2.0
    blend = int(projector.get("blend", 0))
    if blend == 1:
        weighted = nodes.new("ShaderNodeVectorMath")
        weighted.operation = "SCALE"
        weighted.location = (x, y - 250)
        links.new(twice, weighted.inputs[0])
        links.new(texture.outputs["Alpha"], weighted.inputs["Scale"])
        rest = math_node(nodes, links, "SUBTRACT", (x, y - 400), 1.0, texture.outputs["Alpha"])
        spread = nodes.new("ShaderNodeCombineXYZ")
        spread.location = (x + 150, y - 400)
        for index in range(3):
            links.new(rest, spread.inputs[index])
        factor = vector_math(nodes, links, "ADD", (x + 150, y - 250), weighted.outputs[0], spread.outputs[0])
    elif blend == 2:
        factor = vector_math(nodes, links, "ADD", (x, y - 250), twice, constant(nodes, (x - 150, y - 400), (1.0, 1.0, 1.0), "One"))
    else:
        factor = twice
    # Outside the volume the colour stays.
    mix = mix_node(nodes, (x + 350, y - 250), "Inside the projector")
    links.new(inside, socket(mix.inputs, "Factor_Float"))
    socket(mix.inputs, "A_Color").default_value = (1.0, 1.0, 1.0, 1.0)
    links.new(factor, socket(mix.inputs, "B_Color"))
    for node in (texture, coordinates, mix):
        node.parent = frame
    return socket(mix.outputs, "Result_Color")


def projector_reaches(projector, obj):
    """Whether the projector's volume may meet the object: its place lies within its reach of the object's box."""
    box = [obj.matrix_world @ Vector(corner) for corner in obj.bound_box]
    low = Vector((min(p.x for p in box), min(p.y for p in box), min(p.z for p in box)))
    high = Vector((max(p.x for p in box), max(p.y for p in box), max(p.z for p in box)))
    place = Vector(blender_point(projector["location"]))
    fov = float(projector.get("fov", 0))
    trace = float(projector["max_trace"])
    reach = Vector((float(projector["u_size"]), float(projector["v_size"]), 0)).length / 2 + trace
    if fov > 0:
        reach += math.tan(math.radians(fov) / 2) * trace
    nearest = Vector([min(max(place[axis], low[axis]), high[axis]) for axis in range(3)])
    return (nearest - place).length <= reach


def client_lit(nodes, links, attribute, base, ambient, sun, location):
    """The colour, in linear colour, of a surface of the linear colour base in the client's light from the attribute.

    The colour of the attribute is the steady light of the client's lamps, its alpha the share of the sun's light.
    The light is twice the steady light, the ambient light and the sun's light times the share, at most 2, or 1 for
    a share below 0, a tuft that the terrain's light does not touch. The client multiplies the texture by it in
    display colour, at most 1: in linear colour that is the texture's colour times the light to the power of 2.2.
    """
    x, y = location
    steady = vector_math(nodes, links, "MULTIPLY_ADD", (x - 1150, y), attribute.outputs["Vector"],
                         constant(nodes, (x - 1400, y - 250), (2.0, 2.0, 2.0), "Twice the lamps' light"))
    links.new(constant(nodes, (x - 1400, y - 400), ambient, "Client ambient light"), steady.node.inputs[2])
    sunlight = nodes.new("ShaderNodeVectorMath")
    sunlight.operation = "SCALE"
    sunlight.location = (x - 1150, y - 250)
    links.new(constant(nodes, (x - 1400, y - 550), sun, "Client sun light"), sunlight.inputs[0])
    links.new(attribute.outputs["Alpha"], sunlight.inputs["Scale"])
    light = vector_math(nodes, links, "ADD", (x - 950, y), steady, sunlight.outputs[0])
    light = vector_math(nodes, links, "MINIMUM", (x - 750, y), light, constant(nodes, (x - 950, y - 200), (2.0, 2.0, 2.0), "At most 2"))
    untouched = math_node(nodes, links, "LESS_THAN", (x - 750, y - 350), attribute.outputs["Alpha"], 0.0)
    full = mix_node(nodes, (x - 550, y), "Full light where the terrain's light does not reach")
    links.new(untouched, socket(full.inputs, "Factor_Float"))
    links.new(light, socket(full.inputs, "A_Color"))
    socket(full.inputs, "B_Color").default_value = (1.0, 1.0, 1.0, 1.0)
    linear = to_linear(nodes, links, socket(full.outputs, "Result_Color"), (x - 350, y), "Light in linear colour")
    lit = vector_math(nodes, links, "MULTIPLY", (x + 250, y), base, linear)
    return vector_math(nodes, links, "MINIMUM", (x + 450, y), lit, constant(nodes, (x + 250, y - 200), (1.0, 1.0, 1.0), "At most 1"))


def client_grass(objects, env):
    """Lights each kind of grass as the client does: its texture in the terrain's light at the tuft, from the
    client light of each copy. Returns how many kinds it lit.

    The tuft's mesh gets a material of its own, whose Principled BSDF shows black and gives the client's colour as its
    emission, so the leaves keep their cut-out alpha and Blender's sun and lamps do not light them a second time.
    The Value node "Blender light or client light" shows Blender's light at 0.
    """
    client = env.get("client") if env else None
    if not isinstance(client, dict) or len(client.get("ambient", [])) != 3 or len(client.get("sun", [])) != 3:
        return 0
    lit = 0
    for grass in objects:
        modifier = grass.modifiers.get("Tufts") if grass.type == "MESH" else None
        if modifier is None or TUFT_LIGHT not in grass.data.attributes or modifier.node_group is None:
            continue
        tuft = next((node.inputs["Object"].default_value for node in modifier.node_group.nodes if node.type == "OBJECT_INFO"), None)
        if tuft is None or tuft.type != "MESH":
            continue
        for slot in tuft.material_slots:
            found = client_copy(slot)
            if found is None:
                continue
            material, shader, base = found
            nodes = material.node_tree.nodes
            x, y = shader.location.x - 400, shader.location.y - 700
            attribute = nodes.new("ShaderNodeAttribute")
            attribute.attribute_type = "INSTANCER"
            attribute.attribute_name = TUFT_LIGHT
            attribute.location = (x - 1400, y)
            attribute.label = "Client light of the tuft"
            colour = client_lit(nodes, material.node_tree.links, attribute, base, client["ambient"], client["sun"], (x, y))
            emit_client_colour(material, shader, base, colour, (x, y))
            see_through(material, shader)
            lit += 1
    return lit


def client_copy(slot):
    """A copy of the slot's material for the client's light, put in the slot, its Principled BSDF and the socket of its
    base colour, or None for a material without a Principled BSDF, such as an unlit one.

    A copy, as other meshes may share the material. The slot belongs to the mesh, whose copies draw its own materials.
    """
    source = slot.material
    if source is None or not source.use_nodes or source.name.endswith(" in client light"):
        return None
    shader = next((node for node in source.node_tree.nodes if node.type == "BSDF_PRINCIPLED"), None)
    if shader is None or (shader.inputs.get("Emission Color") or shader.inputs.get("Emission")) is None:
        return None
    material = source.copy()
    material.name = source.name + " in client light"
    slot.material = material
    shader = next(node for node in material.node_tree.nodes if node.type == "BSDF_PRINCIPLED")
    colour_input = shader.inputs["Base Color"]
    if colour_input.links:
        return material, shader, colour_input.links[0].from_socket
    value = material.node_tree.nodes.new("ShaderNodeRGB")
    value.outputs[0].default_value = tuple(colour_input.default_value)
    value.location = (shader.location.x - 1600, shader.location.y)
    return material, shader, value.outputs[0]


def emit_client_colour(material, shader, base, colour, location, lit=None):
    """Shows the client's colour as the Principled BSDF's emission and its base colour as black, so the surface keeps
    its alpha and Blender's sun and lamps do not light it a second time. The Value node "Blender light or client light"
    shows Blender's light at 0. A socket lit, when given, says per object whether it has the client's light.
    """
    nodes = material.node_tree.nodes
    links = material.node_tree.links
    x, y = location
    choice = nodes.new("ShaderNodeValue")
    choice.name = choice.label = "Blender light or client light"
    choice.outputs[0].default_value = 1.0
    choice.location = (x + 450, y - 400)
    factor = choice.outputs[0]
    if lit is not None:
        factor = math_node(nodes, links, "MULTIPLY", (x + 450, y - 550), factor, lit)
    surface = mix_node(nodes, (x + 650, y + 200), "Base colour in Blender's light")
    links.new(factor, socket(surface.inputs, "Factor_Float"))
    links.new(base, socket(surface.inputs, "A_Color"))
    socket(surface.inputs, "B_Color").default_value = (0.0, 0.0, 0.0, 1.0)
    links.new(socket(surface.outputs, "Result_Color"), shader.inputs["Base Color"])
    glow = mix_node(nodes, (x + 650, y), "Emission in the client's light")
    links.new(factor, socket(glow.inputs, "Factor_Float"))
    socket(glow.inputs, "A_Color").default_value = (0.0, 0.0, 0.0, 1.0)
    links.new(colour, socket(glow.inputs, "B_Color"))
    links.new(socket(glow.outputs, "Result_Color"), shader.inputs.get("Emission Color") or shader.inputs.get("Emission"))
    strength = shader.inputs.get("Emission Strength")
    if strength is not None:
        strength.default_value = 1.0


# The point attribute of a placed mesh's client light, and the object properties of its placement.
MESH_LIGHT = "cruma_mesh_light"
# The name the second texture coordinates of a P152 Beast placement get, which its lightmaps are read by.
BEAST_UV = "cruma_beast_uv"


def mesh_ambient_of(light, mesh_ambient, mesh_sun):
    """The client's ambient light of a placement, as its static-mesh shader adds it before doubling: half the
    actor's own ambient (source 1), half the meshes' TimeEnv ambient (2), and that with a 32nd of the sun (3), each
    a byte, times the intensity."""
    source = int(light.get("ambient", 2))
    if source == 1:
        values = [math.floor(round(value * 255) * 0.5) for value in light.get("actor", (0, 0, 0))]
    else:
        values = [math.floor(math.floor(value * 255 + 0.5) * 0.5) for value in mesh_ambient]
        if source == 3:
            values = [min(255, value + int(sun * 32)) for value, sun in zip(values, mesh_sun)]
    intensity = float(light.get("intensity", 1))
    return [min(255, int(value * intensity)) / 255 for value in values]


def client_meshes(objects, path, data, env, folder):
    """Lights each placed static mesh that has the client's own light with it, as the viewer does. Returns how many.

    The client lights a placement at twice its light, at most 2: the steady light of its lamps, the sun times the
    cosine of its angle and the vertex's baked share of it at the hour, and its ambient light. Each such object gets a
    mesh of its own with the light of its vertices, and properties of its placement, which the shared material reads.
    A mesh whose vertices do not match keeps Blender's light.
    """
    client = env.get("client") if env else None
    if not isinstance(client, dict) or len(client.get("mesh_ambient", [])) != 3 or len(client.get("mesh_sun", [])) != 3:
        return 0
    placed = []
    atlas = ""
    for obj in objects:
        light = obj.get("cruma_mesh_light") if obj.type == "MESH" else None
        if light is None:
            continue
        try:
            raw = accessor_bytes(path, data, int(light["vertices"]))
        except (KeyError, IndexError, ValueError, RuntimeError, OSError):
            continue
        if len(raw) != len(obj.data.vertices) * 4:
            continue
        obj.data = obj.data.copy()
        attribute = obj.data.attributes.new(MESH_LIGHT, "FLOAT_COLOR", "POINT")
        attribute.data.foreach_set("color", [value / 255 for value in raw])
        obj["cruma_ambient"] = mesh_ambient_of(light, client["mesh_ambient"], client["mesh_sun"])
        obj["cruma_sun_mode"] = float(light.get("sun", 0))
        obj["cruma_hardware_sun"] = [float(value) for value in light.get("hardware_sun", (0, 0, 0))]
        obj["cruma_mesh_lit"] = 1.0
        beast = light.get("beast")
        if beast is not None and len(obj.data.uv_layers) >= 2:
            obj.data.uv_layers[1].name = BEAST_UV
            for bake in ("sun", "static"):
                cell = [float(value) for value in beast.get(bake, (0, 0, 0, 0))]
                obj["cruma_beast_" + bake + "_offset"] = (cell[0], cell[1], 0.0)
                obj["cruma_beast_" + bake + "_scale"] = (cell[2], cell[3], 0.0)
            obj["cruma_beast"] = 1.0
            atlas = os.path.join(folder, *str(beast.get("atlas", "")).split("/"))
        placed.append(obj)
    to_sun = Vector(blender_point(client.get("to_sun", (0, 1, 0)))).normalized()
    scales = client.get("beast_scales", (0, 0))
    image = None
    if any(obj.get("cruma_beast") for obj in placed) and os.path.isfile(atlas):
        image = bpy.data.images.load(atlas, check_existing=True)
        image.colorspace_settings.name = "Non-Color"
    for material in {slot.material for obj in placed for slot in obj.material_slots if slot.material}:
        client_mesh_material(material, client["mesh_sun"], to_sun, image, scales)
    return len(placed)


def client_mesh_material(material, mesh_sun, to_sun, atlas=None, scales=(0, 0)):
    """Adds the client's light of placed meshes to a material that they share. An object without it keeps
    Blender's light, as its property cruma_mesh_lit is missing and reads 0.

    A P152 Beast placement adds its two lightmaps, the sun's bake and the lamps' bake, weighted by the hour's
    BeastLightMap scales, read from the atlas by its second texture coordinates moved into each bake's cell.
    """
    if not material.use_nodes or material.name.endswith(" in client light"):
        return
    nodes = material.node_tree.nodes
    links = material.node_tree.links
    shader = next((node for node in nodes if node.type == "BSDF_PRINCIPLED"), None)
    if shader is None or (shader.inputs.get("Emission Color") or shader.inputs.get("Emission")) is None:
        return
    colour_input = shader.inputs["Base Color"]
    if colour_input.links:
        base = colour_input.links[0].from_socket
    else:
        value = nodes.new("ShaderNodeRGB")
        value.outputs[0].default_value = tuple(colour_input.default_value)
        value.location = (shader.location.x - 1600, shader.location.y)
        base = value.outputs[0]
    x, y = shader.location.x - 400, shader.location.y - 700

    def read(name, kind, row, label):
        node = nodes.new("ShaderNodeAttribute")
        node.attribute_type = kind
        node.attribute_name = name
        node.location = (x - 1800, y - row * 180)
        node.label = label
        return node

    vertex = read(MESH_LIGHT, "GEOMETRY", 0, "Client light of the vertex")
    ambient = read("cruma_ambient", "OBJECT", 1, "Client ambient light of the placement")
    mode = read("cruma_sun_mode", "OBJECT", 2, "How the sun lights the placement")
    hardware = read("cruma_hardware_sun", "OBJECT", 3, "Sun without visibility")
    lit = read("cruma_mesh_lit", "OBJECT", 4, "Placement in the client's light")
    geometry = nodes.new("ShaderNodeNewGeometry")
    geometry.location = (x - 1800, y - 950)
    facing = vector_math(nodes, links, "DOT_PRODUCT", (x - 1550, y - 950), geometry.outputs["Normal"],
                         constant(nodes, (x - 1800, y - 1150), tuple(to_sun), "Towards the sun"))
    facing = math_node(nodes, links, "MAXIMUM", (x - 1350, y - 950), facing.node.outputs["Value"], 0.0)
    by_visibility = math_node(nodes, links, "COMPARE", (x - 1550, y - 400), mode.outputs["Fac"], 1.0)
    by_visibility.node.inputs[2].default_value = 0.5
    without = math_node(nodes, links, "COMPARE", (x - 1550, y - 560), mode.outputs["Fac"], 2.0)
    without.node.inputs[2].default_value = 0.5
    share = math_node(nodes, links, "MULTIPLY", (x - 1350, y - 400), vertex.outputs["Alpha"], by_visibility)
    share = math_node(nodes, links, "MULTIPLY", (x - 1150, y - 400), share, facing)
    sunlight = nodes.new("ShaderNodeVectorMath")
    sunlight.operation = "SCALE"
    sunlight.location = (x - 950, y - 400)
    links.new(constant(nodes, (x - 1150, y - 250), tuple(mesh_sun), "Client sun light of meshes"), sunlight.inputs[0])
    links.new(share, sunlight.inputs["Scale"])
    plain = math_node(nodes, links, "MULTIPLY", (x - 1150, y - 700), without, facing)
    plain_light = nodes.new("ShaderNodeVectorMath")
    plain_light.operation = "SCALE"
    plain_light.location = (x - 950, y - 700)
    links.new(hardware.outputs["Vector"], plain_light.inputs[0])
    links.new(plain, plain_light.inputs["Scale"])
    total = vector_math(nodes, links, "ADD", (x - 750, y), vertex.outputs["Vector"], ambient.outputs["Vector"])
    total = vector_math(nodes, links, "ADD", (x - 600, y), total, sunlight.outputs[0])
    total = vector_math(nodes, links, "ADD", (x - 450, y), total, plain_light.outputs[0])
    total = vector_math(nodes, links, "MINIMUM", (x - 300, y), total, constant(nodes, (x - 450, y - 200), (1.0, 1.0, 1.0), "At most 1"))
    if atlas is not None and max(scales) > 0:
        place = nodes.new("ShaderNodeUVMap")
        place.uv_map = BEAST_UV
        place.location = (x - 1800, y - 1400)
        place.label = "Beast lightmap coordinates"
        flag = read("cruma_beast", "OBJECT", 9, "Placement with Beast lightmaps")
        for row, (bake, weight) in enumerate((("sun", scales[0]), ("static", scales[1]))):
            offset = read("cruma_beast_" + bake + "_offset", "OBJECT", 10 + row * 2, "Beast " + bake + " cell")
            size = read("cruma_beast_" + bake + "_scale", "OBJECT", 11 + row * 2, "Beast " + bake + " size")
            spot = vector_math(nodes, links, "MULTIPLY_ADD", (x - 1550, y - 1400 - row * 300), place.outputs["UV"], size.outputs["Vector"])
            links.new(offset.outputs["Vector"], spot.node.inputs[2])
            reader = nodes.new("ShaderNodeTexImage")
            reader.image = atlas
            reader.extension = "EXTEND"
            reader.location = (x - 1350, y - 1400 - row * 300)
            reader.label = "Beast " + bake + " bake"
            links.new(spot, reader.inputs["Vector"])
            weighted = nodes.new("ShaderNodeVectorMath")
            weighted.operation = "SCALE"
            weighted.location = (x - 1050, y - 1400 - row * 300)
            links.new(reader.outputs["Color"], weighted.inputs[0])
            links.new(math_node(nodes, links, "MULTIPLY", (x - 1050, y - 1550 - row * 300), flag.outputs["Fac"], float(weight)),
                      weighted.inputs["Scale"])
            total = vector_math(nodes, links, "ADD", (x - 300, y - 300 - row * 150), total, weighted.outputs[0])
    twice = vector_math(nodes, links, "SCALE", (x - 150, y), total)
    twice.node.inputs["Scale"].default_value = 2.0
    linear = to_linear(nodes, links, twice, (x, y - 300), "Light in linear colour")
    colour = vector_math(nodes, links, "MULTIPLY", (x + 450, y), base, linear)
    colour = vector_math(nodes, links, "MINIMUM", (x + 600, y), colour, constant(nodes, (x + 450, y - 200), (1.0, 1.0, 1.0), "At most 1"))
    emit_client_colour(material, shader, base, colour, (x + 400, y), lit.outputs["Fac"])


def animate_textures(data, folder):
    """Plays the client's texture animations as the viewer does. Returns how many materials it animated.

    A material's extras, cruma_uv_animation, give the mode and four values of the viewer's moving texture
    coordinates: a pan (mode 1), a turn about a pivot (2), a wave (3) or a swing about a pivot (5). cruma_frames gives the
    atlas of a looped frame animation, such as a torch flame, its number of frames and their rate, 0 for one a
    rendered frame. The client's texture coordinates run down the texture, Blender's up. A Value node, "Time in
    seconds", follows the scene's frame through a driver, so the animation plays with the timeline.
    """
    scene = bpy.context.scene
    rate = scene.render.fps / scene.render.fps_base
    images = data.get("images", [])
    textures = data.get("textures", [])
    animated = 0
    for source in data.get("materials", []):
        extras = source.get("extras")
        if not isinstance(extras, dict):
            continue
        moving = str(extras.get("cruma_uv_animation", "")).split()
        frames = str(extras.get("cruma_frames", "")).split()
        material = bpy.data.materials.get(str(source.get("name", "")))
        if (not moving and len(frames) != 3) or material is None or material.node_tree is None:
            continue
        nodes = material.node_tree.nodes
        links = material.node_tree.links
        readers = [node for node in nodes if node.type == "TEX_IMAGE" and node.image is not None
                   and (not node.inputs["Vector"].links or node.inputs["Vector"].links[0].from_node.type == "TEXCOORD")]
        if not readers:
            continue
        base_name = None
        try:
            base_name = images[textures[source["pbrMetallicRoughness"]["baseColorTexture"]["index"]]["source"]]["name"]
        except (KeyError, IndexError, TypeError):
            pass
        x, y = min(node.location.x for node in readers) - 1800, max(node.location.y for node in readers)
        time = nodes.new("ShaderNodeValue")
        time.name = time.label = "Time in seconds"
        time.location = (x - 400, y)
        driver = time.outputs[0].driver_add("default_value").driver
        driver.type = "SCRIPTED"
        driver.expression = "frame / %g" % rate
        coordinates = nodes.new("ShaderNodeTexCoord")
        coordinates.location = (x - 400, y - 200)
        split = nodes.new("ShaderNodeSeparateXYZ")
        split.location = (x - 200, y - 200)
        links.new(coordinates.outputs["UV"], split.inputs[0])
        u = split.outputs[0]
        v = math_node(nodes, links, "SUBTRACT", (x, y - 300), 1.0, split.outputs[1])
        seconds = time.outputs[0]
        if len(moving) == 5:
            mode = int(float(moving[0]))
            first, second, third, fourth = (float(value) for value in moving[1:])
            column = x + 200
            if mode == 1:
                u = math_node(nodes, links, "MULTIPLY_ADD", (column, y - 200), seconds, first, u)
                v = math_node(nodes, links, "MULTIPLY_ADD", (column, y - 350), seconds, second, v)
            elif mode == 3:
                for index, (rate_hz, size) in enumerate(((first, second), (third, fourth))):
                    wave = math_node(nodes, links, "MULTIPLY", (column, y - 200 - index * 300), seconds, 2 * math.pi * rate_hz)
                    wave = math_node(nodes, links, "SINE", (column + 150, y - 200 - index * 300), wave)
                    if index == 0:
                        u = math_node(nodes, links, "MULTIPLY_ADD", (column + 300, y - 200), wave, size, u)
                    else:
                        v = math_node(nodes, links, "MULTIPLY_ADD", (column + 300, y - 500), wave, size, v)
            elif mode in (2, 5):
                # The client turns a texture about the rotator's own pivot, not always about the middle. A turn
                # (2) keeps the pivot in its first two values and the rate in the third. A swing (5) keeps the
                # pivot in the second and fourth, the swings a second in the first and the size in the third.
                if mode == 2:
                    pivot_u, pivot_v = first, second
                    angle = math_node(nodes, links, "MULTIPLY", (column, y - 600), seconds, third)
                else:
                    pivot_u, pivot_v = second, fourth
                    angle = math_node(nodes, links, "MULTIPLY", (column, y - 600), seconds, 2 * math.pi * first)
                    angle = math_node(nodes, links, "SINE", (column + 150, y - 600), angle)
                    angle = math_node(nodes, links, "MULTIPLY", (column + 300, y - 600), angle, third)
                cosine = math_node(nodes, links, "COSINE", (column + 450, y - 550), angle)
                sine = math_node(nodes, links, "SINE", (column + 450, y - 700), angle)
                across = math_node(nodes, links, "SUBTRACT", (column + 150, y - 200), u, pivot_u)
                down = math_node(nodes, links, "SUBTRACT", (column + 150, y - 350), v, pivot_v)
                u = math_node(nodes, links, "MULTIPLY_ADD", (column + 600, y - 200), across, cosine,
                              math_node(nodes, links, "MULTIPLY_ADD", (column + 450, y - 250), down,
                                        math_node(nodes, links, "MULTIPLY", (column + 300, y - 800), sine, -1.0), pivot_u))
                v = math_node(nodes, links, "MULTIPLY_ADD", (column + 600, y - 350), across, sine,
                              math_node(nodes, links, "MULTIPLY_ADD", (column + 450, y - 400), down, cosine, pivot_v))
        atlas = None
        if len(frames) == 3:
            path = os.path.join(folder, *frames[0].split("/"))
            count = max(1, int(float(frames[1])))
            per_second = float(frames[2])
            if os.path.isfile(path):
                atlas = bpy.data.images.load(path, check_existing=True)
                columns = math.ceil(math.sqrt(count))
                rows = (count + columns - 1) // columns
                column = x + 1000
                # The client shows one frame a rendered frame when the animation has no rate of its own.
                steps = math_node(nodes, links, "MULTIPLY", (column, y - 800), seconds, per_second if per_second > 0 else rate)
                frame = math_node(nodes, links, "FLOOR", (column + 150, y - 800), steps)
                frame = math_node(nodes, links, "FLOORED_MODULO", (column + 300, y - 800), frame, float(count))
                cell_column = math_node(nodes, links, "FLOORED_MODULO", (column + 450, y - 800), frame, float(columns))
                cell_row = math_node(nodes, links, "DIVIDE", (column + 450, y - 950), frame, float(columns))
                cell_row = math_node(nodes, links, "FLOOR", (column + 600, y - 950), cell_row)
                u = math_node(nodes, links, "FRACT", (column, y - 200), u)
                v = math_node(nodes, links, "FRACT", (column, y - 350), v)
                u = math_node(nodes, links, "DIVIDE", (column + 750, y - 200), math_node(nodes, links, "ADD", (column + 600, y - 200), u, cell_column), float(columns))
                v = math_node(nodes, links, "DIVIDE", (column + 750, y - 350), math_node(nodes, links, "ADD", (column + 600, y - 350), v, cell_row), float(rows))
        joined = nodes.new("ShaderNodeCombineXYZ")
        joined.location = (x + 1800, y - 250)
        joined.label = "Moving texture coordinates"
        links.new(u, joined.inputs[0])
        links.new(math_node(nodes, links, "SUBTRACT", (x + 1650, y - 350), 1.0, v), joined.inputs[1])
        for reader in readers:
            if atlas is not None and base_name is not None and re.sub(r"(\.png)?(\.\d{3})?$", "", reader.image.name) == base_name:
                reader.image = atlas
            links.new(joined.outputs[0], reader.inputs["Vector"])
        animated += 1
    return animated


# The attributes of an effect's vertices: where its centre is, how it lies from it, its axis and its colour.
EFFECT_CENTRE = "cruma_fx_centre"
EFFECT_SHAPE = "cruma_fx_shape"
EFFECT_AXIS = "cruma_fx_axis"
EFFECT_COLOUR = "cruma_fx_colour"
# Which still of a loop of moving effects a vertex belongs to.
EFFECT_STILL = "cruma_fx_still"


def client_effects(objects, path, data, folder):
    """Turns the sprites and beams of the client's particle effects to the camera and blends them as the client does.
    Returns how many effects it set up.

    The file keeps a still of each emitter's particles, cards that face a level camera, and for each vertex its
    centre, shape and axis (_FX_CENTER, _FX_SHAPE, _FX_AXIS) and its colour (COLOR_0). The node's extras,
    cruma_effect, give the node's index. A Geometry Nodes modifier, "Face the camera", moves each vertex every frame
    as the viewer does for the scene's camera: a sprite along the camera's right and up, a sprite upright along its
    way, a beam across its axis. A mesh whose vertices do not match keeps the file's cards. The particles of mesh
    emitters stay where they are, blended by the emitter's blend in their node's extras, cruma_effect_blend, or by
    their materials' own. No effect casts a shadow. In a loop of moving effects each node's extras, cruma_effect_still,
    cruma_effect_stills and cruma_effect_interval, say which still it is, and the modifier shows the still of the
    scene's time.
    """
    nodes = data.get("nodes", [])
    meshes = data.get("meshes", [])
    sources = data.get("materials", [])
    by_name = {str(source.get("name", "")): source for source in sources if isinstance(source, dict)}
    group = None
    made = {}
    layers = []
    count = 0
    for obj in objects:
        if obj.type != "MESH":
            continue
        index = obj.get("cruma_effect")
        if index is None:
            continue
        try:
            # The importer makes one mesh of the primitives, one after the other.
            primitives = meshes[nodes[int(index)]["mesh"]]["primitives"]
            centres, shapes, axes, colours = [], [], [], []
            for primitive in primitives:
                attributes = primitive["attributes"]
                centres += accessor_floats(path, data, attributes["_FX_CENTER"])
                shapes += accessor_floats(path, data, attributes["_FX_SHAPE"])
                axes += accessor_floats(path, data, attributes["_FX_AXIS"])
                colours += accessor_floats(path, data, attributes["COLOR_0"])
            extras = sources[primitives[0]["material"]].get("extras", {})
        except (KeyError, IndexError, ValueError, TypeError, RuntimeError, OSError):
            continue
        mesh = obj.data
        if len(centres) != len(mesh.vertices):
            continue
        # glTF has Y up, Blender Z up: (x, y, z) becomes (x, -z, y).
        for name, rows, turn in ((EFFECT_CENTRE, centres, True), (EFFECT_SHAPE, shapes, False), (EFFECT_AXIS, axes, True)):
            attribute = mesh.attributes.get(name) or mesh.attributes.new(name, "FLOAT_VECTOR", "POINT")
            attribute.data.foreach_set("vector", [value for row in rows for value in ((row[0], -row[2], row[1]) if turn else row)])
        colour = mesh.attributes.get(EFFECT_COLOUR) or mesh.attributes.new(EFFECT_COLOUR, "FLOAT_COLOR", "POINT")
        colour.data.foreach_set("color", [value for row in colours for value in row])
        still = mesh.attributes.get(EFFECT_STILL) or mesh.attributes.new(EFFECT_STILL, "FLOAT", "POINT")
        still.data.foreach_set("value", [float(obj.get("cruma_effect_still", 0))] * len(mesh.vertices))
        mesh.update()
        if hasattr(obj, "visible_shadow"):
            obj.visible_shadow = False
        # Every effect has the modifier, so an actor's effects keep it when they are joined.
        if group is None:
            group = facing_group(int(float(obj.get("cruma_effect_stills", 1))), float(obj.get("cruma_effect_interval", 0)))
        modifier = obj.modifiers.new("Face the camera", "NODES")
        modifier.node_group = group
        layers.append((str(obj.get("cruma_effect_actor", obj.name)), int(float(obj.get("cruma_effect_order", 0))), obj))
        first_still = 1 if int(float(obj.get("cruma_effect_still", 0))) == 0 else 0
        own = obj.get("cruma_effect_blend")
        if own is not None:
            # A mesh emitter's particles: each slot keeps its texture, blended as the emitter draws it.
            for slot in obj.material_slots:
                if slot.material is None:
                    continue
                blend = int(float(own))
                if blend < 0:
                    blend = MESH_BLENDS.get(str(by_name.get(slot.material.name, {}).get("extras", {}).get("client_blend", "")))
                image = next((node.image for node in slot.material.node_tree.nodes if node.type == "TEX_IMAGE" and node.image is not None),
                             None) if slot.material.node_tree else None
                if blend is None or image is None:
                    continue
                key = (image.name, blend)
                if key not in made:
                    raw = image.copy()
                    raw.colorspace_settings.name = "Non-Color"
                    made[key] = effect_material(raw, blend, "FX " + image.name + " " + str(blend))
                slot.material = made[key]
            # A loop has each effect once in each still; the first still counts them.
            count += first_still
            continue
        texture = str(extras.get("cruma_fx_texture", ""))
        blend = int(float(extras.get("cruma_fx_blend", 0)))
        key = (texture, blend)
        if key not in made:
            path_of = os.path.join(folder, *texture.split("/"))
            made[key] = None
            if os.path.isfile(path_of):
                image = bpy.data.images.load(path_of, check_existing=True)
                # The client blends in display colour, so the script reads the texture as it is.
                image.colorspace_settings.name = "Non-Color"
                made[key] = effect_material(image, blend, "FX " + os.path.splitext(os.path.basename(path_of))[0] + " " + str(blend))
        if made[key] is not None:
            for slot in obj.material_slots:
                slot.material = made[key]
        count += first_still
    join_layers(layers)
    return count


def join_layers(layers):
    """Joins the effects of each Emitter actor into one object, its children in the client's order.

    The client draws an actor's children one after the other, so one that darkens a place comes before one that
    lights it up. EEVEE draws the materials of one blended object in the order of its slots, and objects in an order
    of its own: the portal of P152 22_22 drawn the other way round came out grey instead of bright cyan. layers holds
    each effect's actor, its place among the actor's children and its object.

    Blender updates the scene after each join, a tenth of a second in a town of HF such as 22_22. So the effects
    are joined in a small scene of their own, and each joined effect then goes back to the collections it came from.
    """
    by_actor = {}
    for actor, order, obj in layers:
        by_actor.setdefault(actor, []).append((order, obj.name, obj))
    groups = []
    for actor, parts in by_actor.items():
        if len(parts) < 2:
            continue
        parts.sort(key=lambda part: (part[0], part[1]))
        joined = [part[2] for part in parts]
        groups.append((actor, joined, [list(obj.users_collection) for obj in joined]))
    if not groups:
        return
    scene = bpy.data.scenes.new("Cruma joining effects")
    try:
        for _, joined, homes in groups:
            for obj, home in zip(joined, homes):
                for collection in home:
                    collection.objects.unlink(obj)
                scene.collection.objects.link(obj)
        view_layer = scene.view_layers[0]
        for actor, joined, _ in groups:
            with bpy.context.temp_override(scene=scene, view_layer=view_layer, active_object=joined[0],
                                           selected_objects=joined, selected_editable_objects=joined):
                bpy.ops.object.join()
            joined[0].name = actor
    finally:
        # Also after a failed join, so no effect is left out of the scene.
        for _, joined, homes in groups:
            for obj, home in zip(joined, homes):
                try:
                    here = scene.collection.objects.get(obj.name) is not None
                except ReferenceError:
                    # Joined into the actor's first effect.
                    continue
                for collection in home:
                    if collection.objects.get(obj.name) is None:
                        collection.objects.link(obj)
                if here:
                    scene.collection.objects.unlink(obj)
        bpy.data.scenes.remove(scene)


def facing_group(stills=1, interval=0.0):
    """Geometry Nodes that move each vertex of an effect to face the scene's camera, as the viewer's shaders do.

    Kind 0 lies the shape's first value along the camera's right and the second along its up, kind 1 the second
    along the axis and the first across it, kind 3 the first across the axis, a beam. Kind 2 stays where it is.
    The client's world is mirrored in Blender's, so a cross product there turns the other way. With a loop of
    stills, interval seconds apart, the nodes keep only the still of the scene's time, one after the other, over
    and over.
    """
    group = bpy.data.node_groups.new("Cruma effect facing", "GeometryNodeTree")
    if hasattr(group, "interface"):
        group.interface.new_socket("Geometry", in_out="INPUT", socket_type="NodeSocketGeometry")
        group.interface.new_socket("Geometry", in_out="OUTPUT", socket_type="NodeSocketGeometry")
    else:
        group.inputs.new("NodeSocketGeometry", "Geometry")
        group.outputs.new("NodeSocketGeometry", "Geometry")
    nodes = group.nodes
    links = group.links
    entry = nodes.new("NodeGroupInput")
    entry.location = (-1400, 0)
    exit = nodes.new("NodeGroupOutput")
    exit.location = (1400, 0)
    info = nodes.new("GeometryNodeObjectInfo")
    info.location = (-1200, -300)
    info.label = "The camera"
    info.transform_space = "ORIGINAL"
    if hasattr(bpy.types, "GeometryNodeInputActiveCamera"):
        camera = nodes.new("GeometryNodeInputActiveCamera")
        camera.location = (-1400, -300)
        links.new(camera.outputs[0], info.inputs["Object"])
    else:
        info.inputs["Object"].default_value = bpy.context.scene.camera

    def read(name, row):
        node = nodes.new("GeometryNodeInputNamedAttribute")
        node.location = (-1200, -600 - row * 150)
        node.data_type = "FLOAT_VECTOR"
        node.inputs["Name"].default_value = name
        return next(out for out in node.outputs if out.type == "VECTOR" and getattr(out, "enabled", True))

    def vector(operation, location, first, second=None):
        node = nodes.new("ShaderNodeVectorMath")
        node.operation = operation
        node.location = location
        links.new(first, node.inputs[0])
        if second is not None:
            links.new(second, node.inputs[1])
        return node

    def scaled(location, first, factor):
        node = vector("SCALE", location, first)
        links.new(factor, node.inputs["Scale"])
        return node.outputs[0]

    def turned(axis, location):
        """The camera's own axis in the world."""
        if hasattr(bpy.types, "FunctionNodeRotateVector"):
            node = nodes.new("FunctionNodeRotateVector")
            node.location = location
            node.inputs["Vector"].default_value = axis
            links.new(info.outputs["Rotation"], node.inputs["Rotation"])
            return node.outputs[0]
        node = nodes.new("ShaderNodeVectorRotate")
        node.rotation_type = "EULER_XYZ"
        node.location = location
        node.inputs["Vector"].default_value = axis
        links.new(info.outputs["Rotation"], node.inputs["Rotation"])
        return node.outputs[0]

    centre = read(EFFECT_CENTRE, 0)
    shape = read(EFFECT_SHAPE, 1)
    axis = read(EFFECT_AXIS, 2)
    split = nodes.new("ShaderNodeSeparateXYZ")
    split.location = (-1000, -750)
    links.new(shape, split.inputs[0])
    across, along, kind = split.outputs[0], split.outputs[1], split.outputs[2]
    right = turned((1.0, 0.0, 0.0), (-1000, -150))
    up = turned((0.0, 1.0, 0.0), (-1000, -300))
    facing = vector("ADD", (-600, 200), scaled((-800, 250), right, across), scaled((-800, 100), up, along)).outputs[0]
    facing = vector("ADD", (-400, 200), centre, facing).outputs[0]
    away = vector("SUBTRACT", (-800, -450), centre, info.outputs["Location"]).outputs[0]
    side = vector("CROSS_PRODUCT", (-600, -450), axis, away).outputs[0]
    side = vector("NORMALIZE", (-400, -450), side).outputs[0]
    # Mirrored: the viewer's right is the opposite of this cross product.
    upright = vector("SUBTRACT", (0, -350), centre, vector("SUBTRACT", (-100, -500), scaled((-300, -500), side, across),
                                                          scaled((-300, -650), axis, along)).outputs[0]).outputs[0]
    beam = vector("ADD", (0, -750), centre, scaled((-200, -750), side, across)).outputs[0]
    position = nodes.new("GeometryNodeInputPosition")
    position.location = (-200, 400)
    chosen = position.outputs[0]
    for row, (value, placed) in enumerate(((2.0, chosen), (0.0, facing), (1.0, upright), (3.0, beam))):
        if row == 0:
            continue
        test = nodes.new("FunctionNodeCompare")
        test.data_type = "FLOAT"
        test.operation = "EQUAL"
        test.location = (200 + row * 200, -200)
        links.new(kind, test.inputs[0])
        test.inputs[1].default_value = value
        test.inputs["Epsilon"].default_value = 0.1
        switch = nodes.new("GeometryNodeSwitch")
        switch.input_type = "VECTOR"
        switch.location = (200 + row * 200, 100)
        links.new(test.outputs[0], switch.inputs[0])
        links.new(chosen, switch.inputs[1])
        links.new(placed, switch.inputs[2])
        chosen = switch.outputs[0]
    move = nodes.new("GeometryNodeSetPosition")
    move.location = (1100, 0)
    geometry = entry.outputs[0]
    if stills > 1 and interval > 0:
        time = nodes.new("GeometryNodeInputSceneTime")
        time.location = (-1400, 600)
        steps = nodes.new("ShaderNodeMath")
        steps.operation = "DIVIDE"
        steps.location = (-1200, 600)
        links.new(time.outputs["Seconds"], steps.inputs[0])
        steps.inputs[1].default_value = interval
        current = nodes.new("ShaderNodeMath")
        current.operation = "FLOOR"
        current.location = (-1000, 600)
        links.new(steps.outputs[0], current.inputs[0])
        looped = nodes.new("ShaderNodeMath")
        looped.operation = "FLOORED_MODULO"
        looped.location = (-800, 600)
        links.new(current.outputs[0], looped.inputs[0])
        looped.inputs[1].default_value = float(stills)
        mine = nodes.new("GeometryNodeInputNamedAttribute")
        mine.location = (-800, 800)
        mine.data_type = "FLOAT"
        mine.inputs["Name"].default_value = EFFECT_STILL
        other = nodes.new("FunctionNodeCompare")
        other.data_type = "FLOAT"
        other.operation = "NOT_EQUAL"
        other.location = (-600, 700)
        links.new(next(out for out in mine.outputs if out.type == "VALUE" and getattr(out, "enabled", True)), other.inputs[0])
        links.new(looped.outputs[0], other.inputs[1])
        other.inputs["Epsilon"].default_value = 0.1
        drop = nodes.new("GeometryNodeDeleteGeometry")
        drop.name = drop.label = "Other stills of the loop"
        drop.domain = "POINT"
        drop.location = (-400, 700)
        links.new(entry.outputs[0], drop.inputs["Geometry"])
        links.new(other.outputs[0], drop.inputs["Selection"])
        geometry = drop.outputs[0]
    links.new(geometry, move.inputs["Geometry"])
    links.new(chosen, move.inputs["Position"])
    links.new(move.outputs[0], exit.inputs[0])
    return group


# The particle blend of a mesh particle's material by its client blend; others keep their material.
MESH_BLENDS = {"TRANSLUCENT": 0, "ADDITIVE": 1, "MODULATE": 2, "BRIGHTEN": 3, "SCREEN": 3, "DARKEN": 4}


def effect_material(image, blend, name):
    """A material that blends an effect's texture, an image of display colour, over what is behind it as the client
    does.

    The client multiplies the texture by the particle's colour in display colour and blends the result by the
    particle blend: alpha (0), adding (1), twice the product with what is behind (2), screen (3) or darkening (4).
    Blender blends in linear colour. Light that adds is added with its display colour taken as linear: close to the
    viewer where many particles meet or something bright lies behind, brighter for one faint particle in the dark.
    """
    material = bpy.data.materials.new(name)
    material.use_nodes = True
    if hasattr(material, "surface_render_method"):
        material.surface_render_method = "BLENDED"
    if hasattr(material, "blend_method"):
        material.blend_method = "BLEND"
    if hasattr(material, "shadow_method"):
        material.shadow_method = "NONE"
    material.use_backface_culling = False
    nodes = material.node_tree.nodes
    links = material.node_tree.links
    nodes.clear()
    output = nodes.new("ShaderNodeOutputMaterial")
    output.location = (1200, 0)
    reader = nodes.new("ShaderNodeTexImage")
    reader.image = image
    reader.location = (-800, 100)
    colour = nodes.new("ShaderNodeAttribute")
    colour.attribute_type = "GEOMETRY"
    colour.attribute_name = EFFECT_COLOUR
    colour.location = (-800, -250)
    colour.label = "Particle colour"
    source = vector_math(nodes, links, "MULTIPLY", (-500, 50), reader.outputs["Color"], colour.outputs["Color"])
    alpha = math_node(nodes, links, "MULTIPLY", (-500, -200), reader.outputs["Alpha"], colour.outputs["Alpha"])
    lit = to_linear(nodes, links, source, (-300, 300), "Particle in linear colour")
    emission = nodes.new("ShaderNodeEmission")
    emission.location = (400, 200)
    # Light that adds goes in as it is: the client adds it in display colour, where many faint particles add up to
    # a bright glow. In its linear colour the glow of a portal of P152 22_22 was barely visible.
    links.new(source if blend in (1, 3) else lit, emission.inputs["Color"])
    clear = nodes.new("ShaderNodeBsdfTransparent")
    clear.location = (400, -100)
    if blend in (2, 3, 4):
        if blend == 2:
            through = vector_math(nodes, links, "SCALE", (-300, -100), source)
            through.node.inputs["Scale"].default_value = 2.0
        else:
            through = vector_math(nodes, links, "SUBTRACT", (-300, -100), constant(nodes, (-500, -350), (1.0, 1.0, 1.0), "One"), source)
        through = vector_math(nodes, links, "MINIMUM", (-100, -100), through, constant(nodes, (-300, -350), (1.0, 1.0, 1.0), "At most 1"))
        through = to_linear(nodes, links, through, (100, -350), "What lets through in linear colour")
        links.new(through, clear.inputs["Color"])
    if blend == 0:
        mix = nodes.new("ShaderNodeMixShader")
        mix.location = (800, 0)
        links.new(alpha, mix.inputs["Fac"])
        links.new(clear.outputs[0], mix.inputs[1])
        links.new(emission.outputs[0], mix.inputs[2])
        surface = mix.outputs[0]
    elif blend in (1, 3):
        add = nodes.new("ShaderNodeAddShader")
        add.location = (800, 0)
        links.new(clear.outputs[0], add.inputs[0])
        links.new(emission.outputs[0], add.inputs[1])
        surface = add.outputs[0]
    else:
        surface = clear.outputs[0]
    links.new(surface, output.inputs["Surface"])
    return material


# The materials whose light the client adds over what is behind them: 1 adds it, 3 adds it as a screen does.
ADDING = {"ADDITIVE": 1, "SCREEN": 1, "BRIGHTEN": 3}


def client_blending(data):
    """Lets the materials whose light the client adds over what is behind them add it in Blender too. Returns how
    many materials it changed.

    glTF cannot add light, so the file draws such a material over what is behind it, as opaque as its texture is
    bright: a torch flame came out a fifth darker than in the viewer. The script adds the texture's colour, times
    the material's colour, to what is behind instead, and for a screen lets through what the texture leaves.
    """
    changed = 0
    for source in data.get("materials", []):
        extras = source.get("extras")
        if not isinstance(extras, dict) or "cruma_fx_blend" in extras:
            continue
        blend = ADDING.get(str(extras.get("client_blend", "")))
        material = bpy.data.materials.get(str(source.get("name", "")))
        if blend is None or material is None or material.node_tree is None:
            continue
        nodes = material.node_tree.nodes
        links = material.node_tree.links
        output = next((node for node in nodes if node.type == "OUTPUT_MATERIAL"), None)
        reader = next((node for node in nodes if node.type == "TEX_IMAGE" and node.image is not None), None)
        if output is None:
            continue
        factor = source.get("pbrMetallicRoughness", {}).get("baseColorFactor", [1.0, 1.0, 1.0, 1.0])
        x, y = output.location.x, output.location.y - 600
        tint = constant(nodes, (x - 800, y - 200), tuple(float(value) for value in factor[:3]), "Material colour")
        colour = vector_math(nodes, links, "MULTIPLY", (x - 600, y), reader.outputs["Color"], tint) if reader is not None else tint
        emission = nodes.new("ShaderNodeEmission")
        emission.location = (x - 400, y)
        links.new(colour, emission.inputs["Color"])
        clear = nodes.new("ShaderNodeBsdfTransparent")
        clear.location = (x - 400, y - 200)
        if blend == 3:
            left = vector_math(nodes, links, "SUBTRACT", (x - 600, y - 300), constant(nodes, (x - 800, y - 400), (1.0, 1.0, 1.0), "One"), colour)
            links.new(vector_math(nodes, links, "MAXIMUM", (x - 500, y - 400), left, constant(nodes, (x - 700, y - 500), (0.0, 0.0, 0.0), "None")),
                      clear.inputs["Color"])
        add = nodes.new("ShaderNodeAddShader")
        add.name = add.label = "Light added as the client does"
        add.location = (x - 200, y)
        links.new(clear.outputs[0], add.inputs[0])
        links.new(emission.outputs[0], add.inputs[1])
        links.new(add.outputs[0], output.inputs["Surface"])
        if hasattr(material, "surface_render_method"):
            material.surface_render_method = "BLENDED"
        if hasattr(material, "blend_method"):
            material.blend_method = "BLEND"
        changed += 1
    return changed


def face_camera(objects):
    """Turns each card that the client turns to face the camera, such as a torch flame, towards the scene's camera.

    The client keeps such a card upright and turns it about the world's up axis so that it faces the camera. The
    file has the card flat in its x and z, standing at its place, and the node's extras, cruma_billboard, mark it.
    A Locked Track constraint does the same: it points the card's -Y at the camera and keeps its Z up. Another
    camera made the scene's camera later needs the constraint's target changed. Returns how many cards it turned.
    """
    camera = bpy.context.scene.camera
    if camera is None:
        return 0
    turned = 0
    for obj in objects:
        if obj.get("cruma_billboard") is None:
            continue
        constraint = obj.constraints.new("LOCKED_TRACK")
        constraint.name = "Face the camera"
        constraint.target = camera
        constraint.track_axis = "TRACK_NEGATIVE_Y"
        constraint.lock_axis = "LOCK_Z"
        turned += 1
    return turned


def client_projected(objects, projectors, folder):
    """Lays the client's projectors over the level geometry and the static meshes they reach. Returns how many
    materials it changed.

    The node's extras, cruma_projected_by, list the projectors that reach a placement, as the viewer picks them. A
    material that many placements share gets each projector once, and lets it through only on the placements it
    reaches, which get the property cruma_projector_<index> of 1: an object without it reads 0.
    """
    by_material = {}
    for obj in objects:
        reached = obj.get("cruma_projected_by") if obj.type == "MESH" else None
        if reached is None:
            continue
        indices = [int(index) for index in reached if 0 <= int(index) < len(projectors) and isinstance(projectors[int(index)], dict)]
        for index in indices:
            obj["cruma_projector_" + str(index)] = 1.0
        for slot in obj.material_slots:
            if slot.material is not None and slot.material.use_nodes:
                by_material.setdefault(slot.material, set()).update(indices)
    changed = 0
    for material, indices in by_material.items():
        if project_onto(material, [(index, projectors[index]) for index in sorted(indices)], folder):
            changed += 1
    return changed


def project_onto(material, entries, folder):
    """Multiplies the material's colour by the projectors of entries, pairs of a projector's index and the projector,
    on the objects that have the projector's property. Returns whether it changed the material.

    The client multiplies the colour on the screen by the projector's factor, after the surface's light, so the
    script multiplies the base colour, for Blender's light, and the emission, for the client's light, by the factor
    in linear colour, at most 1. A projector without backfaces leaves the sides that face away from it. A material
    without a Principled BSDF, an unlit one, keeps its colour, as the client's projectors leave unlit surfaces.
    """
    nodes = material.node_tree.nodes
    links = material.node_tree.links
    shader = next((node for node in nodes if node.type == "BSDF_PRINCIPLED"), None)
    if shader is None:
        return False
    x, y = shader.location.x - 400, shader.location.y - 2800
    total = None
    for row, (index, projector) in enumerate(entries):
        top = y - row * 1100
        gate = nodes.new("ShaderNodeAttribute")
        gate.attribute_type = "OBJECT"
        gate.attribute_name = "cruma_projector_" + str(index)
        gate.location = (x - 2000, top - 700)
        gate.label = "Placement under the projector"
        let_through = gate.outputs["Fac"]
        if not projector.get("backfaces", True):
            geometry = nodes.new("ShaderNodeNewGeometry")
            geometry.location = (x - 2000, top - 900)
            facing = vector_math(nodes, links, "DOT_PRODUCT", (x - 1800, top - 900), geometry.outputs["Normal"],
                                 constant(nodes, (x - 2000, top - 1100), blender_point(projector["direction"]), "Projector direction"))
            front = math_node(nodes, links, "LESS_THAN", (x - 1600, top - 900), facing.node.outputs["Value"], 0.0)
            let_through = math_node(nodes, links, "MULTIPLY", (x - 1600, top - 700), let_through, front)
        factor = projector_factor(nodes, links, projector, folder, (x, top), let_through)
        if factor is None:
            continue
        linear = to_linear(nodes, links, factor, (x + 450, top - 250), "Projector in linear colour")
        total = linear if total is None else vector_math(nodes, links, "MULTIPLY", (x + 950, top), total, linear)
    if total is None:
        return False
    for row, target in enumerate((shader.inputs["Base Color"], shader.inputs.get("Emission Color") or shader.inputs.get("Emission"))):
        if target is None or not target.links:
            continue
        product = vector_math(nodes, links, "MULTIPLY", (shader.location.x - 250, shader.location.y - 300 - row * 200),
                              target.links[0].from_socket, total)
        product = vector_math(nodes, links, "MINIMUM", (shader.location.x - 120, shader.location.y - 300 - row * 200), product,
                              constant(nodes, (shader.location.x - 250, shader.location.y - 450 - row * 200), (1.0, 1.0, 1.0), "At most 1"))
        product.node.label = "Under the client's projectors"
        links.new(product, target)
    return True


def client_level_geometry(objects, folder):
    """Lights level geometry with the client's lightmaps. Returns how many materials it lit.

    The client shows level geometry at its texture times twice its lightmap, in display colour, at most 1, and with
    no other light: a surface without a lightmap at its texture's own colour, which the atlas's grey cell gives. The
    lightmap is the bake of the export's hour, in the atlas that the node's extras, cruma_lightmap, name, at the second
    texture coordinates. In linear colour the light is twice the lightmap to the power of 2.2.
    """
    lit = 0
    for obj in objects:
        path = obj.get("cruma_lightmap") if obj.type == "MESH" else None
        if not path or len(obj.data.uv_layers) < 2:
            continue
        file = os.path.join(folder, *str(path).split("/"))
        if not os.path.isfile(file):
            continue
        image = bpy.data.images.load(file, check_existing=True)
        # The client reads lightmaps as they are, in display colour.
        image.colorspace_settings.name = "Non-Color"
        for slot in obj.material_slots:
            found = client_copy(slot)
            if found is None:
                continue
            material, shader, base = found
            nodes = material.node_tree.nodes
            links = material.node_tree.links
            x, y = shader.location.x - 400, shader.location.y - 700
            place = nodes.new("ShaderNodeUVMap")
            place.uv_map = obj.data.uv_layers[1].name
            place.location = (x - 1200, y)
            place.label = "Lightmap coordinates"
            reader = nodes.new("ShaderNodeTexImage")
            reader.image = image
            reader.extension = "EXTEND"
            reader.location = (x - 1000, y)
            reader.label = "Lightmap"
            links.new(place.outputs["UV"], reader.inputs["Vector"])
            twice = vector_math(nodes, links, "SCALE", (x - 700, y), reader.outputs["Color"])
            twice.node.inputs["Scale"].default_value = 2.0
            light = to_linear(nodes, links, twice, (x - 500, y), "Light in linear colour")
            colour = vector_math(nodes, links, "MULTIPLY", (x + 50, y), base, light)
            colour = vector_math(nodes, links, "MINIMUM", (x + 250, y), colour, constant(nodes, (x + 50, y - 200), (1.0, 1.0, 1.0), "At most 1"))
            emit_client_colour(material, shader, base, colour, (x, y))
            lit += 1
    return lit


def client_soft_alpha(data):
    """Lets through the soft alpha of the leaves and other cards that the client tests and then blends (MASKED_BLEND).
    Returns how many materials it changed.

    The client keeps a texel whose alpha passes the material's AlphaRef and blends it by that alpha, so the border of
    a leaf stays soft and a pale leaf stays see-through. glTF can cut or blend, not both, and the file cuts at the
    AlphaRef: every leaf that passed was solid, such as the trees of Superion in P152 22_22.
    """
    changed = 0
    for source in data.get("materials", []):
        extras = source.get("extras")
        if not isinstance(extras, dict) or extras.get("client_blend") != "MASKED_BLEND":
            continue
        material = bpy.data.materials.get(str(source.get("name", "")))
        if material is None or material.node_tree is None:
            continue
        shader = next((node for node in material.node_tree.nodes if node.type == "BSDF_PRINCIPLED"), None)
        if shader is None:
            continue
        see_through(material, shader, float(source.get("alphaCutoff", 0.5)))
        changed += 1
    return changed


def see_through(material, shader, cutoff=None):
    """Lets the texture's alpha through to the shader as it is, instead of cut at the file's alpha cutoff.

    The client blends grass by its texture's alpha (MASKED_BLEND), so a tuft is partly see-through. glTF has no such
    mode, and the file cuts the alpha at a small cutoff, which made every tuft solid. With a cutoff, an alpha at or
    below it stays clear, as the client's test leaves it.
    """
    alpha = shader.inputs.get("Alpha")
    if alpha is None or not shader.inputs["Base Color"].links:
        return
    queue = [shader.inputs["Base Color"].links[0].from_node]
    seen = set()
    while queue:
        node = queue.pop(0)
        if node.name in seen:
            continue
        seen.add(node.name)
        if node.type == "TEX_IMAGE":
            through = node.outputs["Alpha"]
            if cutoff is not None:
                nodes = material.node_tree.nodes
                links = material.node_tree.links
                passes = math_node(nodes, links, "GREATER_THAN", (node.location.x + 300, node.location.y - 300), through, cutoff)
                through = math_node(nodes, links, "MULTIPLY", (node.location.x + 450, node.location.y - 300), through, passes)
                through.node.label = "Alpha above the client's AlphaRef"
            material.node_tree.links.new(through, alpha)
            break
        for socket_in in node.inputs:
            queue.extend(link.from_node for link in socket_in.links)
    if hasattr(material, "surface_render_method"):
        material.surface_render_method = "DITHERED"
    elif hasattr(material, "blend_method"):
        material.blend_method = "HASHED"


def client_terrain_light(material, ambient, sun, projectors=(), folder=""):
    """Lights the terrain's material with the client's light from the _CLIENT_LIGHT colour of its vertices.

    The client lights a terrain vertex with twice the steady light of its lamps, red, green and blue of the
    colour, the terrain's ambient light and the sun's light times the vertex's share of it at the hour, the alpha,
    at most 2. That light times the texture in display colour, at most 1, is the colour the viewer shows. In
    linear colour it is the texture's linear colour times the light to the power of 2.2. The material shows it as
    an Emission, so Blender's sun and lamps do not light the terrain a second time. The Mix Shader "Blender light or
    client light" shows Blender's light at 0.
    """
    nodes = material.node_tree.nodes
    links = material.node_tree.links
    shader = next((node for node in nodes if node.type == "BSDF_PRINCIPLED"), None)
    output = next((node for node in nodes if node.type == "OUTPUT_MATERIAL"), None)
    if shader is None or output is None or not shader.inputs["Base Color"].links or not output.inputs["Surface"].links:
        return False
    base = shader.inputs["Base Color"].links[0].from_socket
    x, y = shader.location.x, shader.location.y - 700
    attribute = nodes.new("ShaderNodeAttribute")
    attribute.attribute_type = "GEOMETRY"
    attribute.attribute_name = CLIENT_LIGHT
    attribute.location = (x - 1400, y)
    attribute.label = "Client light of the vertex"
    lit = client_lit(nodes, links, attribute, base, ambient, sun, (x, y))
    for row, projector in enumerate(projectors):
        factor = projector_factor(nodes, links, projector, folder, (x + 450, y - 900 - row * 900))
        if factor is not None:
            linear = to_linear(nodes, links, factor, (x + 700, y - 900 - row * 900), "Projector in linear colour")
            lit = vector_math(nodes, links, "MULTIPLY", (x + 1200, y - row * 120), lit, linear)
    emission = nodes.new("ShaderNodeEmission")
    emission.location = (x + 1400, y)
    emission.label = "Client light"
    links.new(lit, emission.inputs["Color"])
    surface = output.inputs["Surface"].links[0].from_socket
    mix = nodes.new("ShaderNodeMixShader")
    mix.name = mix.label = "Blender light or client light"
    mix.location = (output.location.x - 200, output.location.y - 200)
    mix.inputs["Fac"].default_value = 1.0
    links.new(surface, mix.inputs[1])
    links.new(emission.outputs["Emission"], mix.inputs[2])
    links.new(mix.outputs[0], output.inputs["Surface"])
    return True


def mesh_light(env):
    """The light of the client's static meshes, once the terrain shows the client's own light.

    Blender's sun and ambient light then light only meshes and level geometry, and the client gives meshes a light of
    their own, weaker in some clients: at 13:00 in HF its sun gives the ground 0.73 and meshes 0.61, and the tree of
    HF Lobby01 was brighter than in the viewer. Sets the sun to it and returns the environment with its ambient light.
    """
    if "mesh_sun_strength" not in env or "mesh_ambient" not in env:
        return env
    for obj in bpy.context.scene.objects:
        if obj.type == "LIGHT" and obj.data.type == "SUN" and obj.get("cruma_lamp") is None:
            obj.data.energy = float(env["mesh_sun_strength"])
            obj.data.color = tuple(env.get("mesh_sun_color", (1.0, 1.0, 1.0))[:3])
    return dict(env, ambient=env["mesh_ambient"])


def client_terrains(objects, env, projectors=(), folder=""):
    """Lights every terrain that has the client's light with it, under the projectors that reach it.

    Returns how many materials it changed.
    """
    client = env.get("client") if env else None
    if not isinstance(client, dict) or len(client.get("ambient", [])) != 3 or len(client.get("sun", [])) != 3:
        return 0
    seen = set()
    lit = 0
    for obj in objects:
        if obj.type != "MESH" or CLIENT_LIGHT not in obj.data.attributes:
            continue
        for slot in obj.material_slots:
            material = slot.material
            if material is None or material.name in seen or material.node_tree is None:
                continue
            seen.add(material.name)
            reaching = [projector for projector in projectors
                        if isinstance(projector, dict) and projector.get("terrain", True) and projector_reaches(projector, obj)]
            if client_terrain_light(material, client["ambient"], client["sun"], reaching, folder):
                lit += 1
    return lit


def layered_terrains(objects, folder, data):
    """Gives every terrain of the export the material that mixes its layers. Returns how many it built."""
    by_name = {}
    for source in data.get("materials", []):
        extras = source.get("extras")
        if isinstance(extras, dict) and extras.get("cruma_terrain_layers"):
            by_name[source.get("name")] = extras["cruma_terrain_layers"]
    seen = set()
    built = 0
    for obj in objects:
        if obj.type != "MESH" or len(obj.data.uv_layers) < 2:
            continue
        for slot in obj.material_slots:
            material = slot.material
            if material is None or material.name in seen or material.node_tree is None:
                continue
            seen.add(material.name)
            layers = terrain_layers(material, by_name)
            if layers and layered_terrain(material, layers, folder, obj.data.uv_layers[1].name):
                built += 1
    return built


def quiet_armatures(objects):
    """Draws bones as thin lines, so the character shows through them."""
    for obj in objects:
        if obj.type == "ARMATURE":
            obj.data.display_type = "WIRE"


def drop_bone_shapes(objects):
    """Removes the meshes an importer adds to draw bones with. The bones then draw as Blender's own."""
    shapes = set()
    for obj in objects:
        if obj.type == "ARMATURE":
            for bone in obj.pose.bones:
                if bone.custom_shape is not None:
                    shapes.add(bone.custom_shape)
                    bone.custom_shape = None
    for shape in shapes:
        bpy.data.objects.remove(shape)
    return [obj for obj in objects if obj not in shapes]


def find_action(actions, pose, prefix=""):
    """The imported action of a sequence: named after it, maybe with a suffix, after the prefix of a scene's model."""
    pose = (pose or "").lower()
    named = [(action, action.name[len(prefix):].lower()) for action in actions if action.name.startswith(prefix)]
    for action, name in named:
        if name == pose:
            return action
    for action, name in named:
        if pose and name.startswith(pose):
            return action
    for action, name in named:
        if name.startswith("wait"):
            return action
    return named[0][0] if named else None


def model_name(obj):
    """The name a model of a scene carries in its root's extras, on the armature or above it."""
    while obj is not None:
        if "cruma_model" in obj.keys():
            return str(obj["cruma_model"])
        obj = obj.parent
    return None


def play(armature, action):
    """Plays the action on the armature over and over, from a random frame."""
    data = armature.animation_data or armature.animation_data_create()
    data.action = None
    start, end = action.frame_range
    length = max(1.0, end - start)
    track = data.nla_tracks.new()
    track.name = action.name
    strip = track.strips.new(action.name, int(start), action)
    if hasattr(strip, "action_slot") and hasattr(action, "slots") and len(action.slots):
        strip.action_slot = action.slots[0]
    strip.repeat = max(1.0, 3000.0 / length)
    # Each NPC starts somewhere else in its idle, so a crowd does not breathe as one.
    shift = int(random.uniform(0, length))
    strip.frame_start = strip.frame_start - shift
    strip.frame_end = strip.frame_end - shift


def fcurves(action):
    """The animation curves of an action, in Blender 4.4's layered actions or the older ones."""
    layers = getattr(action, "layers", None)
    if layers:
        for layer in layers:
            for strip in layer.strips:
                for bag in getattr(strip, "channelbags", []):
                    yield from bag.fcurves
    elif hasattr(action, "fcurves"):
        yield from action.fcurves


def loop_actions(actions):
    """Makes every animation repeat, so the one picked in the Action Editor plays on and does not stop at its end.

    Each curve gets a Cycles modifier: about 200,000 for a player character's 362 animations, in under a second.
    """
    for action in actions:
        for curve in fcurves(action):
            if not any(modifier.type == "CYCLES" for modifier in curve.modifiers):
                curve.modifiers.new("CYCLES")


def copy_hierarchy(objects, collection):
    """A copy of imported objects that shares their meshes and armatures."""
    copies = {}
    for obj in objects:
        copy = obj.copy()
        collection.objects.link(copy)
        copies[obj] = copy
    for obj, copy in copies.items():
        if obj.parent in copies:
            copy.parent = copies[obj.parent]
            copy.matrix_parent_inverse = obj.matrix_parent_inverse.copy()
        for modifier in copy.modifiers:
            if modifier.type == "ARMATURE" and modifier.object in copies:
                modifier.object = copies[modifier.object]
    return [copies[obj] for obj in objects]


def place_rigs(folder):
    """Puts each rig where its still figure stands and hides the figure. Returns how many rigs of NPCs and how many of
    the pawns that the maps place it put.

    The rig's root takes the figure's place, turn and scale, also a mirror: a figure with a negative scale on one axis
    has its rig mirrored the same way, and Blender then turns the faces of both around, so neither is inside out. An
    NPC plays its idle from a random frame. A pawn of a map plays what it plays from the first frame, see play_pawn.
    """
    figures = [obj for obj in bpy.data.objects if "cruma_rig" in obj.keys()]
    if not figures:
        return 0, 0
    collections = {}
    templates = {}
    placed = [0, 0]
    for figure in figures:
        rig_path = os.path.join(folder, str(figure["cruma_rig"]))
        if not os.path.isfile(rig_path):
            continue
        pawn = PAWN_LIST in figure.keys()
        name = "Map figure rigs" if pawn else "NPC rigs"
        if name not in collections:
            collections[name] = bpy.data.collections.new(name)
            bpy.context.scene.collection.children.link(collections[name])
        collection = collections[name]
        if rig_path not in templates:
            objects, actions = import_file(rig_path)
            quiet_armatures(objects)
            mark_pawns(objects)
            for obj in objects:
                for owner in list(obj.users_collection):
                    owner.objects.unlink(obj)
                collection.objects.link(obj)
            rest = {obj: obj.matrix_world.copy() for obj in objects}
            templates[rig_path] = (objects, actions, rest, True)
        objects, actions, rest, unused = templates[rig_path]
        if unused:
            rig = objects
            templates[rig_path] = (objects, actions, rest, False)
        else:
            rig = copy_hierarchy(objects, collection)
        roots = [obj for obj in rig if obj.parent is None]
        originals = [obj for obj in objects if obj.parent is None]
        for root, original in zip(roots, originals):
            root.matrix_world = figure.matrix_world @ rest[original]
            # Which figure the rig stands for, such as Lobby01.LineagePawn3.
            root["cruma_figure"] = figure.name
        armature = next((obj for obj in rig if obj.type == "ARMATURE"), None)
        if pawn:
            if armature is not None:
                play_pawn(armature, actions, figure)
        else:
            action = find_action(actions, figure.get("cruma_pose", ""))
            if armature is not None and action is not None:
                play(armature, action)
        figure.hide_set(True)
        figure.hide_render = True
        placed[1 if pawn else 0] += 1
    return placed[0], placed[1]


# The extras of the figure of a pawn that a map places: its list of sequences, the one it plays between two of the
# list, the one it plays first in the lobby, whether it picks at random, whether it starts as in the lobby, and how
# many seconds the client plays each sequence, as name=seconds joined by commas.
PAWN_LIST = "cruma_pawn_list"
PAWN_INTERVAL = "cruma_pawn_interval"
PAWN_FIRST = "cruma_pawn_first"
PAWN_RANDOM = "cruma_pawn_random"
PAWN_LOBBY = "cruma_pawn_lobby"
PAWN_SECONDS = "cruma_pawn_seconds"
# How many frames of what a pawn plays lie on its track, as many as an NPC's idle repeats over.
PAWN_FRAMES = 3000.0
# How many picks in a row may find nothing to play before a pawn stops, as in the viewer.
PAWN_PICKS = 10000


def pawn_plays(figure, has):
    """The sequences a pawn of a map plays, one after another, as the client's APawn::PlayUnrPawnAnimSequences picks
    them when the last one has ended. See docs/CLIENT_PAWNS.md.

    After an entry of the list it plays the interval, when the pawn has one. Else it takes the next entry of the list,
    or a random one. A pawn that starts as in the lobby plays its first sequence before all of them. A sequence the
    rig does not have (has(name) is False) does not play, and the next one is taken. The first sequence is the
    figure's cruma_pose, which the viewer picked: a random order goes on with Blender's own random numbers. Yields
    names without end, or none when the pawn plays nothing.
    """
    names = [name for name in str(figure.get(PAWN_LIST, "")).split(",") if name]
    interval = str(figure.get(PAWN_INTERVAL, ""))
    first = str(figure.get(PAWN_FIRST, ""))
    randomly = str(figure.get(PAWN_RANDOM, "")).lower() == "true"
    start = str(figure.get("cruma_pose", ""))
    if not names or not start:
        return
    picks = random.Random(figure.name)
    state = {"index": -1 if str(figure.get(PAWN_LOBBY, "")).lower() == "true" else 0, "interval": False}

    def pick():
        if state["interval"]:
            state["interval"] = False
            return interval or None
        if state["index"] == -1 and first:
            state["index"] = 0
            state["interval"] = True
            return first
        if randomly:
            state["index"] = picks.randrange(len(names))
        else:
            state["index"] = 0 if state["index"] + 1 >= len(names) else state["index"] + 1
        state["interval"] = True
        return names[state["index"]]

    misses = 0
    started = False
    while misses < PAWN_PICKS:
        name = pick()
        if not name or not has(name):
            misses += 1
            continue
        misses = 0
        if not started:
            started = True
            name = start if has(start) else name
        yield name


def play_pawn(armature, actions, figure):
    """Lays what a pawn of a map plays on one NLA track of its rig, from the scene's first frame on: each sequence
    once, starting when the viewer starts it, so the first frame shows the pose of the still figure. A sequence plays
    at its own speed and holds its last key until the next one starts, as the client does: a rig's animation ends at
    its last key, which can come before the client's end of the sequence (cruma_pawn_seconds). The last strip holds
    its last frame. A pawn that plays nothing stands in the rest pose of its skeleton, as the viewer draws it. Returns
    how many strips it laid.

    The importer leaves every action on a track of its own: they stay in the file through a fake user.
    """
    data = armature.animation_data or armature.animation_data_create()
    data.action = None
    for track in list(data.nla_tracks):
        data.nla_tracks.remove(track)
    named = {}
    for action in actions:
        action.use_fake_user = True
        named.setdefault(re.sub(r"\.\d{3}$", "", action.name).lower(), action)
    for bone in armature.pose.bones:
        bone.matrix_basis = Matrix()
    seconds = {}
    for pair in str(figure.get(PAWN_SECONDS, "")).split(","):
        name, _, value = pair.partition("=")
        try:
            seconds[name.lower()] = float(value)
        except ValueError:
            continue
    scene = bpy.context.scene
    rate = scene.render.fps / scene.render.fps_base
    plays = []
    frame = 0.0
    for name in pawn_plays(figure, lambda sequence: sequence.lower() in named):
        if frame >= PAWN_FRAMES:
            break
        action = named[name.lower()]
        start, end = action.frame_range
        length = max(end - start, SCENE_STILL_FRAMES)
        # Without the client's length the rig's own is taken.
        plays.append((action, frame, length))
        frame += max(seconds.get(name.lower(), 0.0) * rate, length)
    if not plays:
        return 0
    track = data.nla_tracks.new()
    track.name = "Plays"
    first = float(scene.frame_start)
    for index, (action, at, length) in enumerate(plays):
        start, end = action.frame_range
        at += first
        # A new strip takes a whole frame, so it goes at the next whole frame and is moved to its place after that,
        # keeping its length. Two strips never lie on one another on a track.
        strip = track.strips.new(action.name, int(math.ceil(at)), action)
        if hasattr(strip, "action_slot") and hasattr(action, "slots") and len(action.slots):
            strip.action_slot = action.slots[0]
        strip.use_sync_length = False
        strip.extrapolation = "HOLD_FORWARD"
        strip.blend_type = "REPLACE"
        if hasattr(strip, "frame_start_ui"):
            strip.frame_start_ui = at
        else:
            strip.frame_start = at
            strip.frame_end = at + length
        if index + 1 < len(plays):
            room = plays[index + 1][1] + first - at
            if strip.frame_end > at + room - SCENE_STILL_FRAMES:
                strip.frame_end = at + room - SCENE_STILL_FRAMES
    return len(plays)


def mark_pawns(objects):
    """Marks the meshes of an NPC's or a character's rig, so pawn_light lights them as the viewer does. A copy of
    the rig keeps the mark."""
    for obj in objects:
        if obj.type == "MESH":
            obj[PAWN] = 1.0


# The custom property of a mesh of an NPC or a character that the script placed, and of a material in the viewer's
# light of them.
PAWN = "cruma_pawn"
PAWN_LIT = "cruma_pawn_light"
PAWN_LIGHT = "Cruma viewer light of NPCs"
# The viewer's old light of NPCs and characters, from its shader fs_spawn_model.sc and BgfxSpawnModelRenderer, which
# the viewer now keeps only for a model that stands in no level (docs/MODEL_LIGHTING.md). It is not the client's
# light: it is white and the same at every hour, and only its way to the sun follows the hour.
# The sun is raised to at least this height, so NPCs keep a lit side at dawn, at dusk and at night.
PAWN_LOWEST_SUN = 0.35
# The ambient light, from a normal that faces down to one that faces up.
PAWN_AMBIENT_DOWN = 0.28
PAWN_AMBIENT_UP = 0.55
# The share of the sun on a side that faces it, and what the sum is multiplied by at the end.
PAWN_SUN = 0.75
PAWN_GAIN = 1.25


def linear_to_srgb():
    """A node group that turns a linear colour into the display (sRGB) colour the viewer computes in, with the exact
    sRGB curve, the inverse of srgb_to_linear."""
    name = "Cruma linear to display colour"
    group = bpy.data.node_groups.get(name)
    if group is not None:
        return group
    group = bpy.data.node_groups.new(name, "ShaderNodeTree")
    if hasattr(group, "interface"):
        group.interface.new_socket("Color", in_out="INPUT", socket_type="NodeSocketColor")
        group.interface.new_socket("Color", in_out="OUTPUT", socket_type="NodeSocketColor")
    else:
        group.inputs.new("NodeSocketColor", "Color")
        group.outputs.new("NodeSocketColor", "Color")
    nodes = group.nodes
    links = group.links
    entry = nodes.new("NodeGroupInput")
    entry.location = (-900, 0)
    exit = nodes.new("NodeGroupOutput")
    exit.location = (700, 0)
    split = nodes.new("ShaderNodeSeparateColor") if hasattr(bpy.types, "ShaderNodeSeparateColor") else nodes.new("ShaderNodeSeparateRGB")
    split.location = (-700, 0)
    join = nodes.new("ShaderNodeCombineColor") if hasattr(bpy.types, "ShaderNodeCombineColor") else nodes.new("ShaderNodeCombineRGB")
    join.location = (500, 0)
    links.new(entry.outputs[0], split.inputs[0])
    for channel in range(3):
        y = 300 - channel * 300
        value = split.outputs[channel]
        # Dark values follow a straight line, the rest a power curve: 12.92 c, or 1.055 c ^ (1 / 2.4) - 0.055.
        low = math_node(nodes, links, "MULTIPLY", (-450, y), value, 12.92)
        power = math_node(nodes, links, "POWER", (-450, y - 120), value, 1 / 2.4)
        curve = math_node(nodes, links, "MULTIPLY_ADD", (-250, y - 120), power, 1.055, -0.055)
        dark = math_node(nodes, links, "LESS_THAN", (-250, y), value, 0.0031308)
        pick = nodes.new("ShaderNodeMix")
        pick.data_type = "FLOAT"
        pick.location = (250, y)
        links.new(dark, socket(pick.inputs, "Factor_Float"))
        links.new(curve, socket(pick.inputs, "A_Float"))
        links.new(low, socket(pick.inputs, "B_Float"))
        links.new(socket(pick.outputs, "Result_Float"), join.inputs[channel])
    links.new(join.outputs[0], exit.inputs[0])
    return group


def towards_sun(nodes, sun, location):
    """A Combine XYZ node whose vector points from the ground to the sun: the sun's +Z, as a sun shines along its -Z.

    Drivers read the sun's turn in the world as a quaternion, so the light follows the sun when it is turned. Their
    expressions are simple ones, which run without Python scripts allowed.
    """
    node = nodes.new("ShaderNodeCombineXYZ")
    node.location = location
    node.label = "Towards the sun, from " + sun.name
    for index, expression in enumerate(("2 * (x * z + w * y)", "2 * (y * z - w * x)", "1 - 2 * (x * x + y * y)")):
        driver = node.inputs[index].driver_add("default_value").driver
        driver.type = "SCRIPTED"
        for name in "wxyz":
            variable = driver.variables.new()
            variable.name = name
            variable.type = "TRANSFORMS"
            target = variable.targets[0]
            target.id = sun
            target.transform_type = "ROT_" + name.upper()
            target.rotation_mode = "QUATERNION"
            target.transform_space = "WORLD_SPACE"
        driver.expression = expression
    return node.outputs[0]


def pawn_light_group(sun):
    """A node group of the viewer's light of NPCs and characters: a colour in, the same colour in that light out.

    The viewer multiplies the texture in display colour by the light 1.25 (0.28 + 0.27 u + 0.75 s), at most 1: u is
    the normal's height from 0 facing down to 1 facing up, and s the cosine of the angle to the sun, 0 on the far
    side. The way to the sun comes from the scene's sun, raised to a height of 0.35 at least. A normal that points
    away from the camera is turned towards it, as the viewer does, so a cape or a wing seen from behind is lit as the
    side that faces the camera. The numbers are on the nodes, so the light of all NPCs changes in one place. Each sun
    has a group of its own, so an export opened next to another one keeps its own sun.
    """
    for group in bpy.data.node_groups:
        if group.name.startswith(PAWN_LIGHT) and group.get("cruma_sun") == sun:
            return group
    group = bpy.data.node_groups.new(PAWN_LIGHT, "ShaderNodeTree")
    group["cruma_sun"] = sun
    if hasattr(group, "interface"):
        group.interface.new_socket("Color", in_out="INPUT", socket_type="NodeSocketColor")
        group.interface.new_socket("Color", in_out="OUTPUT", socket_type="NodeSocketColor")
    else:
        group.inputs.new("NodeSocketColor", "Color")
        group.outputs.new("NodeSocketColor", "Color")
    nodes = group.nodes
    links = group.links
    entry = nodes.new("NodeGroupInput")
    entry.location = (-1800, 300)
    exit = nodes.new("NodeGroupOutput")
    exit.location = (900, 300)
    toward = towards_sun(nodes, sun, (-1800, -300))
    raw = nodes.new("ShaderNodeSeparateXYZ")
    raw.location = (-1600, -300)
    links.new(toward, raw.inputs[0])
    height = math_node(nodes, links, "MAXIMUM", (-1400, -450), raw.outputs[2], PAWN_LOWEST_SUN)
    height.node.label = "The sun at least this high"
    raised = nodes.new("ShaderNodeCombineXYZ")
    raised.location = (-1200, -300)
    links.new(raw.outputs[0], raised.inputs[0])
    links.new(raw.outputs[1], raised.inputs[1])
    links.new(height, raised.inputs[2])
    to_sun = vector_math(nodes, links, "NORMALIZE", (-1000, -300), raised.outputs[0])
    geometry = nodes.new("ShaderNodeNewGeometry")
    geometry.location = (-1600, 100)
    # The viewer turns a normal that points away from the eye towards it, also on a face seen from the front: the
    # rims of a dragon's wing bones, seen from below, were lit by the sun in Blender and are dark in the viewer.
    seen = vector_math(nodes, links, "DOT_PRODUCT", (-1400, 200), geometry.outputs["Normal"], geometry.outputs["Incoming"])
    away = math_node(nodes, links, "LESS_THAN", (-1200, 200), seen.node.outputs["Value"], 0.0)
    turn = math_node(nodes, links, "MULTIPLY_ADD", (-1000, 200), away, -2.0, 1.0)
    turn.node.label = "-1 for a normal away from the eye"
    seen_normal = nodes.new("ShaderNodeVectorMath")
    seen_normal.operation = "SCALE"
    seen_normal.location = (-1000, 50)
    seen_normal.label = "Normal towards the eye"
    links.new(geometry.outputs["Normal"], seen_normal.inputs[0])
    links.new(turn, seen_normal.inputs["Scale"])
    facing = vector_math(nodes, links, "DOT_PRODUCT", (-800, -200), seen_normal.outputs[0], to_sun)
    sunny = math_node(nodes, links, "MAXIMUM", (-600, -200), facing.node.outputs["Value"], 0.0)
    sunny.node.label = "Facing the sun"
    normal = nodes.new("ShaderNodeSeparateXYZ")
    normal.location = (-800, 100)
    links.new(seen_normal.outputs[0], normal.inputs[0])
    up = math_node(nodes, links, "MULTIPLY_ADD", (-800, 100), normal.outputs[2], 0.5, 0.5)
    up.node.label = "Facing up"
    ambient = math_node(nodes, links, "MULTIPLY_ADD", (-600, 100), up, PAWN_AMBIENT_UP - PAWN_AMBIENT_DOWN, PAWN_AMBIENT_DOWN)
    ambient.node.label = "Ambient light"
    light = math_node(nodes, links, "MULTIPLY_ADD", (-400, 0), sunny, PAWN_SUN, ambient)
    light.node.label = "Ambient light and the sun"
    light = math_node(nodes, links, "MULTIPLY", (-200, 0), light, PAWN_GAIN)
    light.node.label = "The viewer's light"
    display = nodes.new("ShaderNodeGroup")
    display.node_tree = linear_to_srgb()
    display.location = (-400, 300)
    links.new(entry.outputs[0], display.inputs[0])
    lit = nodes.new("ShaderNodeVectorMath")
    lit.operation = "SCALE"
    lit.location = (0, 300)
    links.new(display.outputs[0], lit.inputs[0])
    links.new(light, lit.inputs["Scale"])
    lit = vector_math(nodes, links, "MINIMUM", (200, 300), lit.outputs[0], constant(nodes, (0, 100), (1.0, 1.0, 1.0), "At most 1"))
    linear = nodes.new("ShaderNodeGroup")
    linear.node_tree = srgb_to_linear()
    linear.location = (500, 300)
    links.new(lit, linear.inputs[0])
    links.new(linear.outputs[0], exit.inputs[0])
    return group


def scene_sun():
    """The scene's sun: the client's, or the one the script made. None when it has none."""
    return next((obj for obj in bpy.context.scene.objects
                 if obj.type == "LIGHT" and obj.data.type == "SUN" and obj.get("cruma_lamp") is None), None)


def pawn_light(objects):
    """Lights the NPCs and characters that the script placed as the viewer lights them. Returns how many materials.

    The glTF of a rig has plain materials, which Blender's sun and the world's ambient light with the client's light
    of the hour. That light is warm, and weak in the evening and at night: at 18:00 in P152 Lindvior came out at about
    half the brightness of the MP4, almost black where the viewer shows her blue. The material of each marked mesh
    now shows its texture in the viewer's light, as its emission. Its alpha, two sides and skinning stay as they are,
    and the Value node "Blender light or client light" shows Blender's light at 0. Copies of a rig share its
    materials.
    """
    sun = scene_sun()
    if sun is None:
        return 0
    group = None
    lit = 0
    for obj in objects:
        if obj.type != "MESH" or obj.get(PAWN) is None:
            continue
        for slot in obj.material_slots:
            material = slot.material
            if material is None or not material.use_nodes or material.get(PAWN_LIT) is not None:
                continue
            nodes = material.node_tree.nodes
            shader = next((node for node in nodes if node.type == "BSDF_PRINCIPLED"), None)
            if shader is None or (shader.inputs.get("Emission Color") or shader.inputs.get("Emission")) is None:
                continue
            colour_input = shader.inputs["Base Color"]
            if colour_input.links:
                base = colour_input.links[0].from_socket
            else:
                value = nodes.new("ShaderNodeRGB")
                value.outputs[0].default_value = tuple(colour_input.default_value)
                value.location = (shader.location.x - 1600, shader.location.y)
                base = value.outputs[0]
            group = group or pawn_light_group(sun)
            x, y = shader.location.x - 400, shader.location.y - 700
            node = nodes.new("ShaderNodeGroup")
            node.node_tree = group
            node.location = (x, y)
            node.label = "The viewer's light of NPCs"
            material.node_tree.links.new(base, node.inputs[0])
            emit_client_colour(material, shader, base, node.outputs[0], (x, y))
            material[PAWN_LIT] = 1.0
            lit += 1
    return lit


def mesh_boxes():
    """The box around each mesh that renders, as (low, high) corners in the scene."""
    boxes = []
    for obj in bpy.context.scene.objects:
        if obj.type != "MESH" or obj.hide_render:
            continue
        points = [obj.matrix_world @ Vector(corner) for corner in obj.bound_box]
        boxes.append((Vector((min(p.x for p in points), min(p.y for p in points), min(p.z for p in points))),
                      Vector((max(p.x for p in points), max(p.y for p in points), max(p.z for p in points)))))
    return boxes


def box_around(boxes):
    """The box around the boxes, or a small box at the origin without any."""
    if not boxes:
        return Vector((0, 0, 0)), Vector((10, 10, 10))
    low = Vector((min(box[0].x for box in boxes), min(box[0].y for box in boxes), min(box[0].z for box in boxes)))
    high = Vector((max(box[1].x for box in boxes), max(box[1].y for box in boxes), max(box[1].z for box in boxes)))
    return low, high


def scene_bounds():
    return box_around(mesh_boxes())


def without_far(boxes, ratio):
    """The boxes without the meshes that stand alone far away from the others.

    Some maps keep a few meshes far from the place they show. The P152 Lobby01 has a room 360 km above the lobby and
    two trees 5 km away, the HF Lobby01 three small meshes 21 km away. A view that keeps them in sight shows the lobby
    as a dot. A mesh is far when even its nearest point is more than 8 times as far from the middle of the meshes as
    a usual mesh is: half of the meshes are nearer than a usual one.
    """
    if len(boxes) < 3:
        return boxes
    middle = Vector([statistics.median((low[axis] + high[axis]) / 2 for low, high in boxes) for axis in range(3)])
    usual = statistics.median(((low + high) / 2 - middle).length for low, high in boxes)
    reach = max(usual * 8, 50.0 * ratio)
    near = []
    for low, high in boxes:
        nearest = Vector([min(max(middle[axis], low[axis]), high[axis]) for axis in range(3)])
        if (nearest - middle).length <= reach:
            near.append((low, high))
    return near or boxes


def client_sky(world, folder, env):
    """The world of the client's light: the sky panorama for the camera, the ambient colour for the light.

    The camera sees the sky. Every surface gets the client's ambient light from all sides, as in the client, and
    not the light of the sky picture, which is much brighter.
    """
    world.use_nodes = True
    nodes = world.node_tree.nodes
    links = world.node_tree.links
    nodes.clear()
    output = nodes.new("ShaderNodeOutputWorld")
    output.location = (600, 0)
    ambient = nodes.new("ShaderNodeBackground")
    ambient.name = ambient.label = "Ambient light"
    ambient.location = (100, -150)
    ambient.inputs[0].default_value = tuple(env["ambient"][:3]) + (1,)
    ambient.inputs[1].default_value = 1
    sky_file = os.path.join(folder, env["sky"]) if env.get("sky") else None
    if sky_file is None or not os.path.isfile(sky_file):
        links.new(ambient.outputs[0], output.inputs[0])
        return
    texture = nodes.new("ShaderNodeTexEnvironment")
    texture.location = (-300, 150)
    texture.image = bpy.data.images.load(sky_file, check_existing=True)
    sky = nodes.new("ShaderNodeBackground")
    sky.name = sky.label = "Client sky"
    sky.location = (100, 150)
    links.new(texture.outputs[0], sky.inputs[0])
    path = nodes.new("ShaderNodeLightPath")
    path.location = (-300, 450)
    mix = nodes.new("ShaderNodeMixShader")
    mix.location = (350, 0)
    links.new(path.outputs["Is Camera Ray"], mix.inputs[0])
    links.new(ambient.outputs[0], mix.inputs[1])
    links.new(sky.outputs[0], mix.inputs[2])
    links.new(mix.outputs[0], output.inputs[0])


def client_fog(env, low, high, ratio):
    """The client's fog as a box of thin mist over the scene, in the collection Client fog, switched off.

    The client fades to the fog colour from the fog start to its end. Blender's mist thickens evenly instead, so
    it is half thick halfway. Seen from far above, as the views open, the fog would grey out the whole location.
    """
    fog = env.get("fog")
    if not fog or fog.get("end", 0) <= 0:
        return
    scene = bpy.context.scene
    collection = bpy.data.collections.new("Client fog")
    scene.collection.children.link(collection)
    mesh = bpy.data.meshes.new("Fog")
    margin = 20.0 * ratio
    x0, y0, z0 = low.x - margin, low.y - margin, low.z - margin
    x1, y1, z1 = high.x + margin, high.y + margin, high.z + 200.0 * ratio
    corners = [(x0, y0, z0), (x1, y0, z0), (x1, y1, z0), (x0, y1, z0), (x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1)]
    faces = [(0, 3, 2, 1), (4, 5, 6, 7), (0, 1, 5, 4), (1, 2, 6, 5), (2, 3, 7, 6), (3, 0, 4, 7)]
    mesh.from_pydata(corners, [], faces)
    box = bpy.data.objects.new("Fog", mesh)
    # The box has no surface, only the mist inside it.
    box.hide_select = True
    collection.objects.link(box)
    material = bpy.data.materials.new("Client fog")
    material.use_nodes = True
    nodes = material.node_tree.nodes
    links = material.node_tree.links
    nodes.clear()
    output = nodes.new("ShaderNodeOutputMaterial")
    output.location = (400, 0)
    density = math.log(2) / max(ratio, (fog["start"] + fog["end"]) / 2)
    absorb = nodes.new("ShaderNodeVolumeAbsorption")
    absorb.location = (0, 100)
    absorb.inputs["Color"].default_value = (0, 0, 0, 1)
    absorb.inputs["Density"].default_value = density
    glow = nodes.new("ShaderNodeEmission")
    glow.location = (0, -100)
    glow.inputs["Color"].default_value = tuple(fog["color"][:3]) + (1,)
    glow.inputs["Strength"].default_value = density
    add = nodes.new("ShaderNodeAddShader")
    add.location = (200, 0)
    links.new(absorb.outputs[0], add.inputs[0])
    links.new(glow.outputs[0], add.inputs[1])
    links.new(add.outputs[0], output.inputs["Volume"])
    mesh.materials.append(material)
    # EEVEE draws volumes only up to a set distance from the camera.
    if hasattr(scene.eevee, "volumetric_end"):
        scene.eevee.volumetric_end = max(scene.eevee.volumetric_end, (high - low).length * 3)
    layer = bpy.context.view_layer.layer_collection.children.get(collection.name)
    if layer is not None:
        layer.exclude = True


def light_and_camera(character, folder, env, ratio, crowd=False, view=None):
    """A sun, a sky and a camera. For one character the camera stands before its face, for a row of them before
    the middle of the row. In a map with a name of its own it stands at the map's start view, else above a corner
    of the location.

    Lineage II models face +X, as Unreal's do, and the exports keep that, so a character looks along +X.
    """
    scene = bpy.context.scene
    boxes = mesh_boxes()
    # The views and the fog are sized for the place the export shows, without meshes far away from it. The clip
    # distance still reaches them.
    low, high = box_around(boxes if character or crowd else without_far(boxes, ratio))
    whole_low, whole_high = box_around(boxes)
    # A warm afternoon sun and a soft sky, close to the client's daylight: lit sides about as bright as their
    # textures, shadows a little under half of that. A stronger or bluer sky makes the shadows bright and grey.
    if not any(obj.type == "LIGHT" and obj.data.type == "SUN" for obj in scene.objects):
        sun_data = bpy.data.lights.new("Sun", "SUN")
        sun_data.energy = 2.6
        sun_data.color = (1.0, 0.95, 0.88)
        sun_data.angle = math.radians(3)
        sun = bpy.data.objects.new("Sun", sun_data)
        if character:
            # From the front, a little to the camera's side and high, so the face and the side the camera sees are
            # lit and the shadows fall behind. A sun shines along its -Z.
            sun.rotation_euler = Vector((-1.0, 0.2, -0.8)).to_track_quat("-Z", "Y").to_euler()
        else:
            sun.rotation_euler = (math.radians(50), math.radians(10), math.radians(140))
        scene.collection.objects.link(sun)
    if scene.world is None:
        scene.world = bpy.data.worlds.new("Sky")
    if env is not None:
        client_sky(scene.world, folder, env)
        client_fog(env, low, high, ratio)
        for obj in scene.objects:
            if obj.type == "LIGHT" and obj.data.type == "SUN":
                # The client's shadows have sharp edges.
                obj.data.angle = math.radians(1)
    else:
        scene.world.use_nodes = True
        background = scene.world.node_tree.nodes.get("Background")
        if background is not None:
            background.inputs[0].default_value = (0.62, 0.7, 0.8, 1)
            background.inputs[1].default_value = 0.55
    centre = (low + high) / 2
    # A location is wide, a character tall: the camera keeps all of either in view.
    size = max(high.x - low.x, high.y - low.y, high.z - low.z, 1)
    clip_end = max(1000.0 * ratio, size * 4, (whole_high - whole_low).length * 1.2)
    focus = None
    if scene.camera is None:
        camera_data = bpy.data.cameras.new("Camera")
        camera_data.lens = 50 if character else 28
        camera_data.clip_start = 0.1 * ratio
        camera_data.clip_end = clip_end
        # Drawn small, so it does not stand over a character in the views. At a start view the person stands
        # where the camera is.
        camera_data.display_size = 0.5 * ratio if view is not None else max(0.2 * ratio, size * 0.08)
        camera = bpy.data.objects.new("Camera", camera_data)
        if view is not None:
            # Where the viewer's camera starts in the map, looking the same way.
            camera.location = view[0]
            camera.rotation_euler = view[1].to_track_quat("-Z", "Y").to_euler()
            focus = view_focus(view, size)
        elif crowd:
            # In front of the row and a little above it: the row runs across the picture.
            camera.location = centre + Vector((1.0, -0.12, 0.3)).normalized() * size * 1.5
        elif character:
            # In front, a little to its right and above: the face, the chest and both hands show.
            camera.location = centre + Vector((1.0, -0.45, 0.25)).normalized() * size * 1.7
        else:
            camera.location = centre + Vector((-size * 0.55, -size * 0.55, size * 0.5))
        if view is None:
            camera.rotation_euler = (centre - camera.location).to_track_quat("-Z", "Y").to_euler()
        scene.collection.objects.link(camera)
        scene.camera = camera
    for engine in ("BLENDER_EEVEE_NEXT", "BLENDER_EEVEE"):
        try:
            scene.render.engine = engine
            break
        except TypeError:
            continue
    scene.view_settings.view_transform = "Standard"
    scene.frame_end = 250
    show_textures(focus if focus is not None else centre, clip_end, ratio)


def view_focus(view, size):
    """The point a start view looks at: the first surface ahead, or a point a quarter of the location ahead.

    The 3D views turn around it, so they turn around what the camera shows.
    """
    bpy.context.view_layer.update()
    hit, location, _, _, _, _ = bpy.context.scene.ray_cast(bpy.context.evaluated_depsgraph_get(), view[0], view[1],
                                                           distance=size * 2)
    return location if hit else view[0] + view[1] * (size / 4)


def show_textures(centre, clip_end, ratio):
    """Sets every 3D view to show the textures and to look at the scene as the camera does.

    Material Preview draws them as they render, with their see-through parts such as wings, in the scene's own sun
    and sky, with shadows: Blender's studio light comes from all sides and makes a scene look flat and pale. Blender
    4.2 and later build the shaders of a large location in about a minute. Before 4.2 a location of many materials
    stays in Solid with its textures on, which needs no shaders.
    """
    materials = {slot.material for obj in bpy.context.scene.objects if obj.type == "MESH" for slot in obj.material_slots if slot.material}
    preview = len(materials) <= 150 or bpy.app.version >= (4, 2, 0)
    # The camera made just now has its place only after an update.
    bpy.context.view_layer.update()
    for window in bpy.context.window_manager.windows:
        for area in window.screen.areas:
            if area.type != "VIEW_3D":
                continue
            for space in area.spaces:
                if space.type != "VIEW_3D":
                    continue
                # A location is large: the view must see far.
                space.clip_start = 0.1 * ratio
                space.clip_end = clip_end
                # Many bones hide a character. They stay in the rig, and Overlays, Bones shows them. The dashed
                # lines from each NPC's mesh to its rig cross a whole location.
                space.overlay.show_bones = False
                space.overlay.show_relationship_lines = False
                # Blender's floor grid lies at height 0. It crosses a location, whose ground lies lower, and the 3D
                # view hides it even behind the see-through part of a mesh, such as the empty corners of a wing's
                # plane, which looks like a hole in the floor. Overlays, Floor shows it.
                space.overlay.show_floor = False
                space.overlay.show_axis_x = False
                space.overlay.show_axis_y = False
                if preview:
                    space.shading.type = "MATERIAL"
                    space.shading.use_scene_lights = True
                    space.shading.use_scene_world = True
                else:
                    space.shading.type = "SOLID"
                    space.shading.color_type = "TEXTURE"
                camera = bpy.context.scene.camera
                view = space.region_3d
                if camera is not None and view is not None:
                    # The camera stands in front of a character. The view starts just ahead of it, with its lens,
                    # so the character faces the person and the camera is out of the way.
                    view.view_perspective = "PERSP"
                    view.view_rotation = camera.matrix_world.to_quaternion()
                    view.view_location = centre
                    view.view_distance = (camera.matrix_world.translation - centre).length * 0.85
                    if camera.data.type == "PERSP":
                        space.lens = camera.data.lens


# The timeline of a cut scene beside the export's .glb.
SCENE_TIMELINE = "scene.json"
# The view of a scene is fixed in height from 4:3 up: the width of a 4:3 picture is what the file gives.
SCENE_HEIGHT_SHARE = 0.75
SCENE_SENSOR_HEIGHT = 24.0
SCENE_RESOLUTION = (1920, 1080)
SCENE_BOX_SHAPE = 4 / 3
# The first channel of each kind in the Sequencer. What overlaps takes the next free channel.
SCENE_CHANNELS = {"ambient": 1, "music": 2, "voice": 4, "sound": 6}
SCENE_LAST_CHANNEL = 128
# An NLA strip needs a length. A body that stands still in a moment plays this little of its action, slowly.
SCENE_STILL_FRAMES = 0.001
SCENE_STILL_SCALE = 1000.0
# Two times of the file this many frames apart are one frame, and two speeds this far apart are one speed.
SCENE_SAME_FRAME = 0.01
SCENE_SAME_SCALE = 0.001
# How long after the script the window has been drawn, so the views can be fitted.
SCENE_FIT_SECONDS = 1.0


def scene_timeline(folder):
    """The timeline of a cut scene's export, or None for any other export."""
    path = os.path.join(folder, SCENE_TIMELINE)
    if not os.path.isfile(path):
        return None
    with open(path, encoding="utf-8") as file:
        return json.load(file)


def scene_frame(seconds, rate):
    """The frame of a time of the scene. The first frame comes one step after the start, so it is frame 1."""
    return int(round(seconds * rate))


def strip_frame(seconds, rate):
    """Where a time of a strip lies on the timeline. The file's numbers are short, so a time that is a frame comes out
    a little after it, and the strip would miss that frame. A strip that ends between two frames keeps its place.
    """
    frame = seconds * rate
    whole = round(frame)
    return float(whole) if abs(frame - whole) < SCENE_SAME_FRAME else frame


def scene_point(values):
    """A place of the file in Blender's axes: glTF (x, y, z) is Blender (x, -z, y)."""
    return Vector((values[0], -values[2], values[1]))


def scene_collection(name):
    collection = bpy.data.collections.new(name)
    bpy.context.scene.collection.children.link(collection)
    return collection


def jump_keys(keys, discrete=False):
    """The keys of a curve from (frame, values) pairs in the order of the scene, on whole frames. Returns
    [frame, values, held] lists. A held key keeps its value up to the next key.

    Two keys at one frame are a jump: the curve comes to the first, and the last is its value at that frame. A Blender
    curve has only one key on a frame and joins two into one, so a jump would become a slope from the key before. So
    the curve holds on the frame before the jump and steps on the frame of the jump. The key before holds when it is on
    that frame, or when it has the first value, as in a still time. Else a new key on that frame takes the value on the
    straight line to the first value. So each whole frame has the scene's value, and each key is on a whole frame,
    where a person can move it.

    A curve of yes or no, such as hide_render, has no slope: Blender holds each key up to the next one.
    """
    curve = []
    index = 0
    while index < len(keys):
        frame, first = keys[index]
        last = first
        index += 1
        while index < len(keys) and keys[index][0] == frame:
            last = keys[index][1]
            index += 1
        if last != first:
            before = frame - 1
            if curve and (discrete or curve[-1][0] >= before or curve[-1][1] == first):
                curve[-1][2] = True
            elif curve:
                start, value = curve[-1][0], curve[-1][1]
                share = (before - start) / (frame - start)
                curve.append([before, [a + (b - a) * share for a, b in zip(value, first)], True])
            else:
                curve.append([before, first, True])
        curve.append([frame, last, False])
    return curve


def set_keys(owner, path, keys):
    """Keys a property at (frame, value) pairs, each value a list for a property of many numbers.

    A curve takes its keys at once: one keyframe_insert for each of a scene's tens of thousands of keys takes long.
    A key is linear, so the frames between two keys of a still time do not move. Two keys at one frame are a jump,
    see jump_keys. The owner can also be a part of a data-block, such as a node's socket or a strip of the Sequencer.
    """
    items = bpy.types.Keyframe.bl_rna.properties["interpolation"].enum_items
    linear, constant = items["LINEAR"].value, items["CONSTANT"].value
    first = keys[0][1]
    size = len(first) if isinstance(first, (list, tuple)) else 1
    curve_keys = jump_keys([(frame, list(value) if size > 1 else [value]) for frame, value in keys],
                           isinstance(first, bool))
    setattr(owner, path, first)
    owner.keyframe_insert(path, frame=curve_keys[0][0])
    full_path = owner.path_from_id(path)
    action = owner.id_data.animation_data.action
    curves = {curve.array_index: curve for curve in fcurves(action) if curve.data_path == full_path}
    for index in range(size):
        curve = curves[index]
        points = curve.keyframe_points
        points.add(len(curve_keys) - 1)
        numbers = []
        for key_frame, key_value, _ in curve_keys:
            numbers.append(key_frame)
            numbers.append(float(key_value[index]))
        points.foreach_set("co", numbers)
        points.foreach_set("interpolation", [constant if held else linear for _, _, held in curve_keys])
        curve.update()


def show_between(obj, first, last, start, end):
    """Keeps an object out of the views and the picture outside its frames from first to last, ends of the scene apart."""
    keys = []
    if first > start:
        keys.append((start, True))
    keys.append((first, False))
    if last < end:
        keys.append((last + 1, True))
    if len(keys) > 1:
        for path in ("hide_viewport", "hide_render"):
            set_keys(obj, path, keys)


def lay_out_strips(armature, strips, actions, rate):
    """Lays a body's sequences on one NLA track at their times, and returns how many it found.

    A strip holds its last pose until the next one, and the body does the same in the scene. The importer leaves every
    action on a muted track of its own: they stay in the file through a fake user, and only the track of the scene
    shows in the NLA Editor.
    """
    data = armature.animation_data or armature.animation_data_create()
    data.action = None
    for track in list(data.nla_tracks):
        data.nla_tracks.remove(track)
    track = data.nla_tracks.new()
    track.name = "Scene"
    named = {}
    for action in actions:
        action.use_fake_user = True
        named[re.sub(r"\.\d{3}$", "", action.name).lower()] = action
    laid = 0
    for index, item in enumerate(strips):
        action = named.get(item["sequence"].lower())
        if action is None:
            continue
        start = strip_frame(item["start"], rate)
        end = strip_frame(item["end"], rate)
        # A strip takes the room up to the next one at the most. Two strips never lie on one another on a track.
        room = strip_frame(strips[index + 1]["start"], rate) - start if index + 1 < len(strips) else math.inf
        if room <= 0:
            continue
        # A new strip takes a whole frame and the action's whole length, so it goes at the next whole frame and is
        # cut to its place after that: the strip before it can end anywhere in this frame.
        strip = track.strips.new(item["sequence"], int(math.ceil(start)), action)
        if hasattr(strip, "action_slot") and hasattr(action, "slots") and len(action.slots):
            strip.action_slot = action.slots[0]
        strip.use_sync_length = False
        first = strip_frame(item["from"], rate)
        last = strip_frame(item["to"], rate)
        strip.extrapolation = "HOLD_FORWARD"
        strip.blend_type = "REPLACE"
        if last - first < SCENE_STILL_FRAMES:
            strip.action_frame_start = first
            strip.action_frame_end = first + SCENE_STILL_FRAMES
            strip.scale = SCENE_STILL_SCALE
            strip.repeat = 1.0
            strip.frame_start = start
            strip.frame_end = start + min(SCENE_STILL_FRAMES * SCENE_STILL_SCALE, room)
        else:
            strip.action_frame_start = first
            strip.action_frame_end = last
            strip.repeat = max(item["repeat"], 0.001)
            # A repeat comes back to the action's first frame only at a scale of exactly 1.
            scale = (end - start) / ((last - first) * strip.repeat)
            strip.scale = 1.0 if abs(scale - 1.0) < SCENE_SAME_SCALE else scale
            strip.frame_start = start
            # Where the next strip goes on in the same frame, that frame is its own: a track takes the first strip.
            strip.frame_end = min(end, start + room - SCENE_STILL_FRAMES)
        laid += 1
    return laid


def scene_actors(timeline, folder, ratio):
    """Makes an empty for each actor, keyed to the pawn's place, with its rig under it. Returns the actors and strips.

    The rig stands on the lowest point of its idle pose, and in the scene it hangs from the pawn's place, lift metres
    above that point. Actors of one NPC share its meshes and actions, as copies of one import.
    """
    rate = timeline["frames_per_second"]
    start = bpy.context.scene.frame_start
    end = scene_frame(timeline["seconds"], rate)
    collection = scene_collection("Actors")
    templates = {}
    laid = 0
    for actor in timeline["actors"]:
        pawn = bpy.data.objects.new(actor["name"], None)
        pawn.empty_display_size = 0.5 * ratio
        collection.objects.link(pawn)
        keys = actor["keys"]
        set_keys(pawn, "location", [(scene_frame(key[0], rate), list(scene_point(key[1:4]))) for key in keys])
        pawn.rotation_mode = "XYZ"
        set_keys(pawn, "rotation_euler", [(scene_frame(key[0], rate), [0.0, 0.0, key[4]]) for key in keys])
        shown = [pawn]
        rig_path = os.path.join(folder, actor["rig"]) if "rig" in actor else None
        if rig_path is not None and os.path.isfile(rig_path):
            if rig_path not in templates:
                objects, actions = import_file(rig_path)
                quiet_armatures(objects)
                mark_pawns(objects)
                for obj in objects:
                    for owner in list(obj.users_collection):
                        owner.objects.unlink(obj)
                    collection.objects.link(obj)
                # The roots and their places, as they are before the first actor takes them.
                roots = [(index, obj.matrix_world.copy()) for index, obj in enumerate(objects) if obj.parent is None]
                templates[rig_path] = (objects, actions, roots, True)
            objects, actions, roots, unused = templates[rig_path]
            rig = objects if unused else copy_hierarchy(objects, collection)
            templates[rig_path] = (objects, actions, roots, False)
            if not unused:
                # A copy shares the keys of the first actor, which came and went at other times.
                for obj in rig:
                    if obj.type == "MESH":
                        obj.animation_data_clear()
            hanging = Matrix.Translation((0.0, 0.0, -actor["lift"]))
            for index, rest in roots:
                root = rig[index]
                root.parent = pawn
                root.matrix_parent_inverse = Matrix.Identity(4)
                root.matrix_basis = hanging @ rest
            armature = next((obj for obj in rig if obj.type == "ARMATURE"), None)
            if armature is not None:
                laid += lay_out_strips(armature, actor["strips"], actions, rate)
            shown += [obj for obj in rig if obj.type == "MESH"]
        for obj in shown:
            show_between(obj, scene_frame(actor["enters"], rate), scene_frame(actor["leaves"], rate), start, end)
    return len(timeline["actors"]), laid


def scene_camera(timeline, clip_end, ratio):
    """Keys the scene's camera along its path.

    The picture's height follows the viewer's: from 4:3 up it stays the same in any wider picture, so the sensor fits
    the height. On a map loaded alone, such as a lobby, the field of view is the width of the picture itself, and the
    lens is fitted to the picture of the file.
    """
    scene = bpy.context.scene
    rate = timeline["frames_per_second"]
    share = SCENE_HEIGHT_SHARE
    if timeline["camera"].get("lens") == "lobby":
        share = SCENE_RESOLUTION[1] / SCENE_RESOLUTION[0]
    camera = scene.camera
    if camera is None:
        camera = bpy.data.objects.new("Camera", bpy.data.cameras.new("Camera"))
        scene.collection.objects.link(camera)
        scene.camera = camera
    data = camera.data
    data.sensor_fit = "VERTICAL"
    data.sensor_height = SCENE_SENSOR_HEIGHT
    data.clip_start = 0.1 * ratio
    data.clip_end = max(data.clip_end, clip_end)
    camera.rotation_mode = "QUATERNION"
    places = []
    turns = []
    lenses = []
    last = None
    for key in timeline["camera"]["keys"]:
        frame = scene_frame(key[0], rate)
        look = scene_point(key[4:7])
        up = scene_point(key[7:10])
        # A camera looks along its -Z, with +Y up.
        turn = Matrix((look.cross(up), up, -look)).transposed().to_quaternion()
        if last is not None and last.dot(turn) < 0:
            turn.negate()
        last = turn
        places.append((frame, list(scene_point(key[1:4]))))
        turns.append((frame, list(turn)))
        lenses.append((frame, SCENE_SENSOR_HEIGHT / (2 * share * math.tan(math.radians(key[10]) / 2))))
    if places:
        set_keys(camera, "location", places)
        set_keys(camera, "rotation_quaternion", turns)
        set_keys(data, "lens", lenses)
    return camera


def fade_volume(strip, frame, volume):
    strip.volume = volume
    strip.keyframe_insert("volume", frame=frame)


def follow_levels(strip, levels, rate):
    """Keys a sound's volume and pan as the camera moves around it, at the frames of the scene's steps."""
    set_keys(strip, "volume", [(scene_frame(seconds, rate), volume) for seconds, volume, _ in levels])
    set_keys(strip, "pan", [(scene_frame(seconds, rate), pan) for seconds, _, pan in levels])


def scene_sounds(timeline, folder):
    """Lays the scene's music, voices and sounds in the Sequencer at their times, and returns how many.

    A sound ends where the scene cut it off, and a track fades out there, as the viewer fades it.
    A sound with levels has a place in the world, and its strip follows the camera. All the map's
    sounds around the camera come as one strip of kind "ambient".
    """
    scene = bpy.context.scene
    rate = timeline["frames_per_second"]
    editor = scene.sequence_editor or scene.sequence_editor_create()
    strips = editor.strips if hasattr(editor, "strips") else editor.sequences
    # From Blender 5 the Sequencer shows the scene its workspace names, and without one it stays empty.
    for workspace in bpy.data.workspaces:
        if hasattr(workspace, "sequencer_scene"):
            workspace.sequencer_scene = scene
    free = {}
    placed = 0
    for item in timeline["sounds"]:
        path = os.path.join(folder, item["file"])
        if not os.path.isfile(path):
            continue
        start = scene_frame(item["start"], rate)
        channel = SCENE_CHANNELS[item["kind"]]
        while channel < SCENE_LAST_CHANNEL and free.get(channel, 0) > start:
            channel += 1
        strip = strips.new_sound(item["name"], path, channel, start)
        # Blender 6.0 takes away the older name of the strip's end.
        end_name = "right_handle" if hasattr(strip, "right_handle") else "frame_final_end"
        end = scene_frame(item["end"], rate)
        if end < getattr(strip, end_name):
            setattr(strip, end_name, end)
        free[channel] = getattr(strip, end_name)
        levels = item.get("levels")
        volume = levels[-1][1] if levels else item["volume"]
        strip.volume = volume
        if levels:
            follow_levels(strip, levels, rate)
        fade_in = item.get("fade_in", 0) * rate
        fade_out = item.get("fade_out", 0) * rate
        if fade_in > 0:
            fade_volume(strip, start, 0.0)
            fade_volume(strip, int(start + fade_in), volume)
        if fade_out > 0 and getattr(strip, end_name) == end and end < scene_frame(timeline["seconds"], rate):
            fade_volume(strip, end, volume)
            fade_volume(strip, int(end + fade_out), 0.0)
            setattr(strip, end_name, int(end + fade_out))
            free[channel] = getattr(strip, end_name)
        placed += 1
    return placed


def scene_effects(timeline, ratio):
    """Marks each effect the scene starts: an empty with its name, its times in custom properties and its path, and a
    marker on the timeline. glTF has no form for particles.
    """
    scene = bpy.context.scene
    rate = timeline["frames_per_second"]
    collection = scene_collection("Effects of the scene")
    last = scene_frame(timeline["seconds"], rate)
    for effect in timeline["effects"]:
        start = scene_frame(effect["start"], rate)
        mark = bpy.data.objects.new(effect["name"], None)
        mark.empty_display_type = "SPHERE"
        mark.empty_display_size = 0.5 * ratio
        collection.objects.link(mark)
        mark["cruma_effect"] = effect["name"]
        mark["cruma_start"] = effect["start"]
        if "end" in effect:
            mark["cruma_end"] = effect["end"]
        keys = [(scene_frame(key[0], rate), list(scene_point(key[1:4]))) for key in effect["keys"]]
        set_keys(mark, "location", keys)
        show_between(mark, start, scene_frame(effect["end"], rate) if "end" in effect else last, scene.frame_start, last)
        scene.timeline_markers.new(effect["name"], frame=start)
    return len(timeline["effects"])


def fit_views():
    """Fits the 3D views to the camera's frame and the timelines to the scene. Blender can do it only in a window that
    has been drawn, so a timer calls this.
    """
    for window in bpy.context.window_manager.windows:
        for area in window.screen.areas:
            region = next((item for item in area.regions if item.type == "WINDOW"), None)
            if region is None:
                continue
            fit = {"VIEW_3D": bpy.ops.view3d.view_center_camera, "DOPESHEET_EDITOR": bpy.ops.action.view_all}.get(area.type)
            if fit is None:
                continue
            with bpy.context.temp_override(window=window, area=area, region=region):
                if fit.poll():
                    fit()
    return None


def linear_part(value):
    """A part of a colour of 0 to 255 on a screen as Blender's linear value of it."""
    share = value / 255
    return share / 12.92 if share <= 0.04045 else ((share + 0.055) / 1.055) ** 2.4


def screen_material(name, path):
    """A material that shows the colour of its object, or a picture, as it is, and as clear as the object's alpha."""
    material = bpy.data.materials.new(name)
    material.use_nodes = True
    nodes = material.node_tree.nodes
    links = material.node_tree.links
    nodes.clear()
    info = nodes.new("ShaderNodeObjectInfo")
    light = nodes.new("ShaderNodeEmission")
    clear = nodes.new("ShaderNodeBsdfTransparent")
    mix = nodes.new("ShaderNodeMixShader")
    output = nodes.new("ShaderNodeOutputMaterial")
    links.new(clear.outputs[0], mix.inputs[1])
    links.new(light.outputs[0], mix.inputs[2])
    links.new(mix.outputs[0], output.inputs["Surface"])
    if path is None:
        links.new(info.outputs["Color"], light.inputs["Color"])
        links.new(info.outputs["Alpha"], mix.inputs[0])
    else:
        picture = nodes.new("ShaderNodeTexImage")
        picture.image = bpy.data.images.load(path)
        picture.extension = "CLIP"
        share = nodes.new("ShaderNodeMath")
        share.operation = "MULTIPLY"
        links.new(picture.outputs["Color"], light.inputs["Color"])
        links.new(picture.outputs["Alpha"], share.inputs[0])
        links.new(info.outputs["Alpha"], share.inputs[1])
        links.new(share.outputs[0], mix.inputs[0])
    if hasattr(material, "surface_render_method"):
        material.surface_render_method = "BLENDED"
    else:
        material.blend_method = "BLEND"
    return material


def screen_place(space, key, aspect):
    """The place of a key on a screen of height 2 around its middle, from shares of the screen or the slide window."""
    x, y, width, height = key[1:5]
    if space == "box":
        across = min(1.0, SCENE_BOX_SHAPE / aspect)
        down = across * aspect / SCENE_BOX_SHAPE
        x = (1 - across) / 2 + x * across
        y = (1 - down) / 2 + y * down
        width *= across
        height *= down
    return (x - 0.5) * 2 * aspect, (0.5 - y) * 2, width * 2 * aspect, height * 2


def scene_screen(timeline, folder, camera):
    """Puts what the scene shows over its world on planes just in front of the camera, and returns how many.

    The planes hang from an empty that follows the camera's lens, so they fill the picture through every change of
    the view, and a render has them. Each is keyed to its place and to its clearness, the alpha of the object's colour.
    They count on the picture's shape when the file opens.
    """
    layers = timeline.get("screen", {}).get("layers", [])
    if not layers:
        return 0
    scene = bpy.context.scene
    rate = timeline["frames_per_second"]
    aspect = scene.render.resolution_x / scene.render.resolution_y
    collection = scene_collection("Screen of the scene")
    depth = camera.data.clip_start * 2
    screen = bpy.data.objects.new("Screen", None)
    screen.empty_display_size = 0.01
    collection.objects.link(screen)
    screen.parent = camera
    screen.location = (0.0, 0.0, -depth)
    for axis in range(2):
        driver = screen.driver_add("scale", axis).driver
        driver.type = "SCRIPTED"
        lens = driver.variables.new()
        lens.name = "lens"
        lens.type = "SINGLE_PROP"
        lens.targets[0].id_type = "CAMERA"
        lens.targets[0].id = camera.data
        lens.targets[0].data_path = "lens"
        driver.expression = "%r / lens" % (depth * SCENE_SENSOR_HEIGHT / 2)
    flat = screen_material("Screen colour", None)
    for index, layer in enumerate(layers):
        mesh = bpy.data.meshes.new(layer["name"])
        mesh.from_pydata([(0, 0, 0), (1, 0, 0), (1, -1, 0), (0, -1, 0)], [], [(0, 3, 2, 1)])
        uvs = mesh.uv_layers.new(name="UVMap")
        for corner, uv in enumerate([(0, 1), (0, 0), (1, 0), (1, 1)]):
            uvs.data[corner].uv = uv
        path = os.path.join(folder, layer["picture"]) if "picture" in layer else None
        mesh.materials.append(screen_material(layer["name"], path) if path and os.path.isfile(path) else flat)
        plane = bpy.data.objects.new(layer["name"], mesh)
        collection.objects.link(plane)
        plane.parent = screen
        plane.hide_select = True
        for ray in ("shadow", "diffuse", "glossy", "transmission", "volume_scatter"):
            setattr(plane, "visible_" + ray, False)
        color = [linear_part(part) for part in layer["color"]]
        lift = index * depth * 1e-4
        places = []
        sizes = []
        colors = []
        for key in layer["keys"]:
            frame = scene_frame(key[0], rate)
            x, y, width, height = screen_place(layer["space"], key, aspect)
            places.append((frame, [x, y, lift]))
            sizes.append((frame, [width, height, 1.0]))
            colors.append((frame, color + [key[5]]))
        set_keys(plane, "location", places)
        set_keys(plane, "scale", sizes)
        set_keys(plane, "color", colors)
    return len(layers)


def scene_grade(timeline):
    """Changes the colours of the picture as a post effect of the scene does, in the compositor. Returns how many keys.

    The client's sums work on what the screen shows, so the numbers go to the screen's gamma and back. Each colour
    becomes chroma times itself, plus luma - chroma times its luma, plus what the keys add. A render takes this, and
    the 3D view does not. Over the world this also changes what stands on the camera, as a compositor gets one picture.
    """
    keys = timeline.get("grades", {}).get("keys", [])
    scene = bpy.context.scene
    if not keys or not hasattr(scene, "compositing_node_group"):
        return 0
    rate = timeline["frames_per_second"]
    tree = bpy.data.node_groups.new("Cruma grade", "CompositorNodeTree")
    tree.interface.new_socket("Image", in_out="OUTPUT", socket_type="NodeSocketColor")
    nodes = tree.nodes
    links = tree.links

    def math(operation, *inputs):
        node = nodes.new("ShaderNodeMath")
        node.operation = operation
        for index, value in enumerate(inputs):
            if isinstance(value, (int, float)):
                node.inputs[index].default_value = value
            else:
                links.new(value, node.inputs[index])
        return node.outputs[0]

    sums = {
        "Grey": lambda key: key[1] - key[2],
        "Chroma": lambda key: key[2],
        "Red": lambda key: 1.402 * key[4],
        "Green": lambda key: -0.34414 * key[3] - 0.71414 * key[4],
        "Blue": lambda key: 1.772 * key[3],
    }
    values = {}
    for name, sum_of in sums.items():
        node = nodes.new("ShaderNodeValue")
        node.name = name
        values[name] = node
        set_keys(node.outputs[0], "default_value", [(scene_frame(key[0], rate), sum_of(key)) for key in keys])
    render = nodes.new("CompositorNodeRLayers")
    apart = nodes.new("CompositorNodeSeparateColor")
    together = nodes.new("CompositorNodeCombineColor")
    output = nodes.new("NodeGroupOutput")
    links.new(render.outputs["Image"], apart.inputs[0])
    shown = [math("POWER", apart.outputs[index], 1 / 2.2) for index in range(3)]
    luma = math("ADD", math("MULTIPLY", shown[0], 0.299), math("ADD", math("MULTIPLY", shown[1], 0.587), math("MULTIPLY", shown[2], 0.114)))
    grey = math("MULTIPLY", luma, values["Grey"].outputs[0])
    for index, name in enumerate(("Red", "Green", "Blue")):
        changed = math("ADD", math("ADD", math("MULTIPLY", shown[index], values["Chroma"].outputs[0]), grey), values[name].outputs[0])
        links.new(math("POWER", math("MAXIMUM", changed, 0.0), 2.2), together.inputs[index])
    links.new(apart.outputs["Alpha"], together.inputs["Alpha"])
    links.new(together.outputs[0], output.inputs[0])
    scene.compositing_node_group = tree
    scene.render.use_compositing = True
    return len(keys)


def scene_doors(timeline):
    """Keys the doors of the map that the scene moves, and returns how many it found.

    A door stands closed in the location, and its node names its actor. It moves by keys on Delta Transform, so its
    own place stays as the location has it. A door on a tile that is not in the export is not there to move.
    """
    doors = timeline.get("doors", [])
    if not doors:
        return 0
    rate = timeline["frames_per_second"]
    found = {}
    for obj in bpy.context.scene.objects:
        actor = obj.get("cruma_door")
        if actor is not None:
            found.setdefault(actor, []).append(obj)
    moved = 0
    for door in doors:
        for obj in found.get(door["actor"], []):
            turn = obj.parent.matrix_world.to_3x3().inverted() if obj.parent else Matrix.Identity(3)
            set_keys(obj, "delta_location", [(scene_frame(key[0], rate), list(turn @ scene_point(key[1:4]))) for key in door["keys"]])
        moved += 1 if door["actor"] in found else 0
    return moved


def watch_scene(camera):
    """Turns the 3D views to the scene's camera, so Play shows the scene as the viewer does, and darkens what lies
    outside its picture.
    """
    camera.data.show_passepartout = True
    camera.data.passepartout_alpha = 1.0
    for window in bpy.context.window_manager.windows:
        for area in window.screen.areas:
            if area.type == "VIEW_3D":
                area.spaces.active.region_3d.view_perspective = "CAMERA"
    if not bpy.app.background:
        bpy.app.timers.register(fit_views, first_interval=SCENE_FIT_SECONDS)


def play_scene(timeline, folder, ratio):
    """Makes the cut scene of the timeline: its frames, its camera, its actors, its sounds and the marks of its effects.

    The picture and the timeline run at the scene's rate, so a strip's frames are the scene's seconds. Returns the
    numbers for the report.
    """
    scene = bpy.context.scene
    rate = timeline["frames_per_second"]
    scene.frame_start = 1
    scene.frame_end = max(1, scene_frame(timeline["seconds"], rate))
    scene.frame_current = 1
    scene.render.resolution_x, scene.render.resolution_y = SCENE_RESOLUTION
    # The picture keeps up with the sound while it plays, as a large location draws slower than the scene runs.
    scene.sync_mode = "AUDIO_SYNC"
    low, high = box_around(mesh_boxes())
    camera = scene_camera(timeline, (high - low).length * 1.2, ratio)
    watch_scene(camera)
    actors, strips = scene_actors(timeline, folder, ratio)
    sounds = scene_sounds(timeline, folder)
    effects = scene_effects(timeline, ratio)
    scene_doors(timeline)
    scene_screen(timeline, folder, camera)
    scene_grade(timeline)
    # The objects show as their keys say at the first frame.
    scene.frame_set(scene.frame_start)
    return actors, strips, sounds, effects


def open_story(timeline, folder):
    """Plays a cut scene that shows on the screen alone: it has slides and lines, and no place in the world."""
    new_scene(timeline["scene"])
    scene = bpy.context.scene
    scene.render.fps = timeline["frames_per_second"]
    scene.render.fps_base = 1.0
    for engine in ("BLENDER_EEVEE_NEXT", "BLENDER_EEVEE"):
        try:
            scene.render.engine = engine
            break
        except TypeError:
            continue
    scene.view_settings.view_transform = "Standard"
    # Before the scene turns the views to its camera, as this turns them to a place.
    show_textures(Vector((0, 0, 0)), 1000.0, 1.0)
    played = play_scene(timeline, folder, 1.0)
    print("Cruma: opened " + timeline["scene"] + " with " + str(played[2]) + " sounds, on the screen alone")


def run():
    random.seed(1)
    folder = export_folder()
    timeline = scene_timeline(folder)
    if timeline is not None and not any(file.lower().endswith((".glb", ".gltf")) for file in os.listdir(folder)):
        open_story(timeline, folder)
        return
    path = main_file(folder)
    new_scene(os.path.splitext(os.path.basename(path))[0])
    if timeline is not None:
        # The importer turns the seconds of an animation into frames at the scene's rate.
        bpy.context.scene.render.fps = timeline["frames_per_second"]
        bpy.context.scene.render.fps_base = 1.0
    data = gltf_json(path)
    objects, actions = import_file(path, one_copy=True)
    # Before the client's light copies materials, so the copies move too.
    moving = animate_textures(data, folder)
    client_blending(data)
    client_soft_alpha(data)
    quiet_armatures(objects)
    armatures = [obj for obj in objects if obj.type == "ARMATURE"]
    # A scene of many NPCs: each armature has the actions named after its model.
    crowd = len(armatures) > 1 and all(model_name(obj) for obj in armatures)
    if not armatures:
        part_collections(objects)
    tufts, removed = place_copies(objects, path, data)
    objects = [obj for obj in objects if id(obj) not in removed]
    # The objects of the grass are made by place_copies.
    client_grass([obj for obj in bpy.context.scene.objects if obj.modifiers.get("Tufts")], environment(file_extras(data)))
    lit_level = client_level_geometry(objects, folder)
    client_meshes(objects, path, data, environment(file_extras(data)), folder)
    client_projected(objects, file_extras(data).get("cruma_projectors", []), folder)
    terrains = layered_terrains(objects, folder, data)
    lit_terrains = client_terrains(objects, environment(file_extras(data)), file_extras(data).get("cruma_projectors", []), folder)
    lamps = client_lamps(objects, environment(file_extras(data)))
    if lamps:
        switch_off_lamps(objects)
    for obj in armatures:
        # An NPC export plays its idle, which the importer may not have picked.
        idle = find_action(actions, "wait", model_name(obj) + "|" if crowd else "")
        if idle is not None:
            # Its own name: data is the file's JSON, whose extras give the scale below.
            animation = obj.animation_data or obj.animation_data_create()
            animation.action = idle
            if hasattr(animation, "action_slot") and hasattr(idle, "slots") and len(idle.slots):
                animation.action_slot = idle.slots[0]
            bpy.context.view_layer.objects.active = obj
    placed, figures = place_rigs(folder)
    # One NPC or player character, not a location with its NPCs.
    character = placed == 0 and figures == 0 and any(obj.type == "ARMATURE" for obj in objects)
    if character:
        loop_actions(actions)
    extras = file_extras(data)
    env = environment(extras)
    if (lit_terrains or lit_level) and env is not None:
        env = mesh_light(env)
    light_and_camera(character, folder, env, unit_ratio(extras), crowd, start_view(extras))
    # After light_and_camera, which makes the camera, and before the speakers, which reach to the end of the timeline.
    played = play_scene(timeline, folder, unit_ratio(extras)) if timeline is not None else None
    # After the scene's actors, and after light_and_camera, which makes a sun when the export has none.
    pawns = pawn_light(bpy.context.scene.objects)
    # The speakers start on the timeline that light_and_camera sets.
    sounds, heard = client_sounds(objects, folder, env)
    # The cards need the camera, which light_and_camera makes.
    cards = face_camera(objects)
    effects = client_effects(objects, path, data, folder)
    for obj in bpy.context.scene.objects:
        obj.select_set(False)
    details = []
    if played is not None:
        details.append(str(played[0]) + " actors, " + str(played[1]) + " strips, " + str(played[2]) + " sounds and "
                       + str(played[3]) + " marks of effects of " + timeline["scene"])
    if crowd:
        details.append(str(len(armatures)) + " NPCs, each playing its idle")
    if placed:
        details.append(str(placed) + " NPC rigs placed")
    if figures:
        details.append(str(figures) + (" rig" if figures == 1 else " rigs") + " of the figures that the maps place")
    if pawns:
        details.append(str(pawns) + (" material" if pawns == 1 else " materials") + " of NPCs in the viewer's light")
    if terrains:
        details.append(str(terrains) + (" terrain" if terrains == 1 else " terrains") + " with their layers")
    if tufts:
        details.append(str(tufts) + " tufts of grass")
    if lamps:
        details.append(str(lamps) + (" lamp" if lamps == 1 else " lamps") + " of the client, switched off")
    if sounds:
        details.append(str(sounds) + " of the client's sounds with speakers, " + str(heard)
                       + " heard at the hour, switched off")
    if cards:
        details.append(str(cards) + (" card" if cards == 1 else " cards") + " facing the camera")
    if moving:
        details.append(str(moving) + (" moving material" if moving == 1 else " moving materials"))
    if effects:
        details.append(str(effects) + (" effect" if effects == 1 else " effects") + " facing the camera")
    print("Cruma: opened " + path + "".join(", " + detail for detail in details))


run()
