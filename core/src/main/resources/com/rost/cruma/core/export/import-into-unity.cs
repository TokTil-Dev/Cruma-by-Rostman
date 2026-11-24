// Places the sounds, the music zones and the Emitter actors of a Cruma location export in the open Unity scene.
//
// How to use it:
// 1. Put the whole export folder somewhere inside Assets. Unity imports the WAVE files in the sounds folder and
//    the Ogg Vorbis files in the music folder, and compiles this script, CrumaEffect.cs, CrumaMusic.cs and
//    CrumaSounds.cs. A glTF package, glTFast or UnityGLTF, imports the .glb.
// 2. Put the .glb in the scene at (0, 0, 0) without a turn or a scale, as the package imports it.
// 3. Select the export folder in the Project window and choose Cruma > Import sounds, music and effects, or
//    right-click the folder and choose Cruma > Import sounds, music and effects. Without a selected export folder
//    the script takes the folder it is in.
//
// The script makes a GameObject named after the export folder, with the children Sounds, Music and Effects:
// - each sound of sounds.json is a GameObject with an AudioSource: its clip from the sounds folder, 3D or 2D,
//   Logarithmic rolloff from minDistance = radius to maxDistance = max_distance, its volume and pitch (the middle
//   of an emitter's pitch_min and pitch_max). A sound of an Emitter actor loops and plays on awake when the client
//   loops it. The ambient sounds are in the CrumaSounds component of Sounds, which plays them while the scene plays
//   as the client does: in reach, by day, by night or in their hours by its hour, in a loop or by their chance,
//   once in each length of the clip. The script reads the random_seconds of an older sounds.json, of version 1,
//   as the chance it was,
// - Music has an AudioSource and a CrumaMusic component with the music zones of music.json and their tracks from
//   the music folder. While the scene plays, it plays the music of the zone the listener is in. The script sets
//   the tracks to load as Streaming, so a long track does not fill the memory,
// - each Emitter actor of effects.json is an empty GameObject at the actor's place, turn and scale, with a
//   CrumaEffect component that lists its emitters, their textures and blends. Add a ParticleSystem to it by hand.
//
// The script also sets the Intensity of the sun and the lamps of the imported .glb from lights.json. The .glb gives
// its lights in glTF's units, lux and candela, and a glTF importer takes those numbers for Unity's own Intensity, a
// few thousand for the sun, which makes the whole scene white. A light is found by its name, Sun or Lamp 12, and a
// lamp also by its place. This works in the Universal Render Pipeline and in the Built-in one. The High Definition
// pipeline counts light in physical units itself, so its lights stay as they are.
//
// The script then switches the Lamps objects of the .glb off, as open-in-blender.py does in Blender. The client
// gives each mesh only the lamps listed for it, with baked shadows, and Unity lights every surface with every lamp
// in reach: a town of some hundred lamps comes out far too bright. Tick a Lamps object to light the scene with
// its lamps.
//
// Unity compiles every .cs file under Assets. Two exports with their own copies of the scripts define the same
// classes twice, and Unity stops with an error. Keep one copy of import-into-unity.cs, CrumaEffect.cs,
// CrumaMusic.cs and CrumaSounds.cs, delete the copies in the other export folders, and select an export folder to
// import it.
//
// Axes: sounds.json, music.json and effects.json use the axes of the .glb, as glTF has them: right-handed, +Y up,
// metres. Unity is left-handed, +Y up, metres. glTFast and UnityGLTF both mirror X when they import a .glb: a
// point (x, y, z) becomes (-x, y, z) and a turn (x, y, z, w) becomes (x, -y, -z, w). This script does the same
// at first, so a sound lands on the same spot as the imported meshes. For a way of import that mirrors Z instead,
// tick Cruma > Mirror Z instead of X: a point then becomes (x, y, -z) and a turn (-x, -y, z, w).
//
// It needs Unity 2021.3 or later and no packages.

#if UNITY_EDITOR
using System;
using System.Collections.Generic;
using System.IO;
using UnityEditor;
using UnityEditor.SceneManagement;
using UnityEngine;
using UnityEngine.Rendering;

namespace Cruma
{
    public static class ImportIntoUnity
    {
        const string ImportMenu = "Cruma/Import sounds, music and effects";
        const string FolderMenu = "Assets/Cruma/Import sounds, music and effects";
        const string MirrorZMenu = "Cruma/Mirror Z instead of X";
        const string MirrorZKey = "Cruma.MirrorZ";
        const string ScriptName = "import-into-unity.cs";

        // What the Built-in pipeline leaves of a lamp's Intensity at half its range: 1 / (1 + 25 * 0.5 * 0.5).
        const float BuiltInHalfway = .138f;

        // What the Universal pipeline leaves of a lamp's light at half its range, where it starts to fade the light
        // out towards the range: the square of 1 - 0.5 * 0.5 * 0.5 * 0.5.
        const float UniversalHalfway = .879f;

        /// <summary>
        /// Whether the script mirrors Z instead of X, kept in the editor's preferences. Off at first, as glTFast
        /// and UnityGLTF mirror X.
        /// </summary>
        public static bool MirrorZ
        {
            get { return EditorPrefs.GetBool(MirrorZKey, false); }
            set { EditorPrefs.SetBool(MirrorZKey, value); }
        }

        [MenuItem(ImportMenu)]
        public static void ImportFromMenu()
        {
            Import();
        }

        [MenuItem(FolderMenu)]
        public static void ImportFromFolder()
        {
            Import();
        }

        [MenuItem(MirrorZMenu)]
        public static void ToggleMirrorZ()
        {
            MirrorZ = !MirrorZ;
        }

        [MenuItem(MirrorZMenu, true)]
        public static bool ShowMirrorZ()
        {
            Menu.SetChecked(MirrorZMenu, MirrorZ);
            return true;
        }

        /// <summary>Places the sounds, music and effects of the selected export folder in the open scene.</summary>
        public static void Import()
        {
            var folder = ExportFolder();
            if (folder == null)
            {
                EditorUtility.DisplayDialog("Cruma",
                    "Select the folder of a Cruma export in the Project window. It has sounds.json, music.json or "
                        + "effects.json.",
                    "OK");
                return;
            }
            var root = new GameObject(Path.GetFileName(folder));
            Undo.RegisterCreatedObjectUndo(root, "Import Cruma sounds, music and effects");
            var sounds = ImportSounds(folder, root.transform);
            var zones = ImportMusic(folder, root.transform);
            var effects = ImportEffects(folder, root.transform);
            var lights = ImportLights(folder);
            Selection.activeGameObject = root;
            EditorSceneManager.MarkSceneDirty(root.scene);
            Debug.Log("Cruma: placed " + sounds + " sounds, " + zones + " music zones and " + effects
                + " Emitter actors of " + folder + ", and set the Intensity of " + lights + " lights");
        }

        /// <summary>
        /// The export folder selected in the Project window, or the one this script is in, or null when neither
        /// has sounds.json, music.json or effects.json.
        /// </summary>
        static string ExportFolder()
        {
            foreach (var guid in Selection.assetGUIDs)
            {
                var path = AssetDatabase.GUIDToAssetPath(guid);
                if (string.IsNullOrEmpty(path))
                {
                    continue;
                }
                var folder = AssetDatabase.IsValidFolder(path) ? path : ParentFolder(path);
                if (IsExport(folder))
                {
                    return folder;
                }
            }
            foreach (var guid in AssetDatabase.FindAssets("t:MonoScript"))
            {
                var path = AssetDatabase.GUIDToAssetPath(guid);
                if (path.EndsWith("/" + ScriptName, StringComparison.Ordinal) && IsExport(ParentFolder(path)))
                {
                    return ParentFolder(path);
                }
            }
            return null;
        }

        static string ParentFolder(string path)
        {
            var parent = Path.GetDirectoryName(path);
            return parent == null ? "" : parent.Replace('\\', '/');
        }

        static bool IsExport(string folder)
        {
            return File.Exists(folder + "/sounds.json") || File.Exists(folder + "/music.json")
                || File.Exists(folder + "/effects.json") || File.Exists(folder + "/lights.json");
        }

        static int ImportSounds(string folder, Transform root)
        {
            var path = folder + "/sounds.json";
            if (!File.Exists(path))
            {
                return 0;
            }
            var list = JsonUtility.FromJson<SoundList>(File.ReadAllText(path));
            if (list == null || list.sounds == null)
            {
                return 0;
            }
            var parent = new GameObject("Sounds").transform;
            parent.SetParent(root, false);
            var ambient = new List<CrumaSounds.Sound>();
            foreach (var sound in list.sounds)
            {
                var item = new GameObject(string.IsNullOrEmpty(sound.actor) ? sound.name : sound.actor);
                item.transform.SetParent(parent, false);
                item.transform.localPosition = Point(sound.position);
                var source = item.AddComponent<AudioSource>();
                source.clip = AssetDatabase.LoadAssetAtPath<AudioClip>(folder + "/" + sound.file);
                if (source.clip == null)
                {
                    Debug.LogWarning("Cruma: " + folder + "/" + sound.file + " is not an audio clip in this project.");
                }
                source.spatialBlend = sound.spatial == "2d" ? 0f : 1f;
                source.rolloffMode = AudioRolloffMode.Logarithmic;
                source.minDistance = sound.radius;
                source.maxDistance = Mathf.Max(sound.max_distance, sound.radius);
                source.volume = sound.volume;
                var pitch = sound.kind == "emitter" ? (sound.pitch_min + sound.pitch_max) / 2f : sound.pitch;
                source.pitch = pitch > 0f ? pitch : 1f;
                source.loop = sound.loop;
                source.playOnAwake = sound.loop;
                source.dopplerLevel = 0f;
                if (sound.kind != "emitter")
                {
                    // CrumaSounds starts and stops an ambient sound by its hours, its reach and its chance.
                    var entry = AmbientSound(sound, list.version);
                    entry.source = source;
                    entry.maxDistance = source.maxDistance;
                    source.loop = entry.loop;
                    source.playOnAwake = false;
                    ambient.Add(entry);
                }
            }
            if (ambient.Count > 0)
            {
                var player = parent.gameObject.AddComponent<CrumaSounds>();
                player.sounds = ambient.ToArray();
                player.hour = list.hour;
            }
            return list.sounds.Length;
        }

        /// <summary>
        /// The settings that CrumaSounds plays an ambient sound by. sounds.json of version 1 gave random_seconds,
        /// which was the client's AmbientRandom: from 1 to 99 it is the chance, and any other number loops.
        /// </summary>
        static CrumaSounds.Sound AmbientSound(SoundEntry sound, int version)
        {
            var entry = new CrumaSounds.Sound();
            var chance = version >= 2 ? sound.chance : (int) sound.random_seconds;
            entry.loop = sound.loop || chance <= 0 || chance >= 100;
            entry.chance = entry.loop ? 100 : chance;
            entry.startTime = sound.start_time;
            entry.seconds = sound.seconds;
            entry.when = string.IsNullOrEmpty(sound.when) ? "always" : sound.when;
            var hours = sound.hours ?? new HourEntry[0];
            entry.hours = new Vector2[hours.Length];
            for (var index = 0; index < hours.Length; index++)
            {
                entry.hours[index] = new Vector2(hours[index].start, hours[index].end);
            }
            return entry;
        }

        static int ImportMusic(string folder, Transform root)
        {
            var path = folder + "/music.json";
            if (!File.Exists(path))
            {
                return 0;
            }
            var list = JsonUtility.FromJson<MusicList>(File.ReadAllText(path));
            if (list == null || list.zones == null || list.zones.Length == 0)
            {
                return 0;
            }
            var item = new GameObject("Music");
            item.transform.SetParent(root, false);
            var source = item.AddComponent<AudioSource>();
            source.playOnAwake = false;
            source.loop = false;
            source.spatialBlend = 0f;
            var music = item.AddComponent<CrumaMusic>();
            music.hour = list.hour;
            if (list.night != null && (list.night.from_hour != 0f || list.night.to_hour != 0f))
            {
                music.nightFromHour = list.night.from_hour;
                music.nightToHour = list.night.to_hour;
            }
            music.zones = new CrumaMusic.Zone[list.zones.Length];
            for (var index = 0; index < list.zones.Length; index++)
            {
                var entry = list.zones[index];
                var zone = new CrumaMusic.Zone();
                zone.actor = entry.actor;
                zone.tile = entry.tile;
                zone.musicId = entry.music_id;
                zone.tracks = Tracks(folder, entry.tracks);
                zone.hasNightMusic = entry.has_night_music;
                zone.nightMusicId = entry.night_music_id;
                zone.nightTracks = Tracks(folder, entry.night_tracks);
                zone.loop = entry.loop;
                zone.force = entry.force;
                // Mirroring an axis swaps the two ends of the box on it.
                var low = Point(entry.bounds_min);
                var high = Point(entry.bounds_max);
                zone.boundsMin = Vector3.Min(low, high);
                zone.boundsMax = Vector3.Max(low, high);
                zone.rootOutside = entry.root_outside;
                var nodes = entry.front != null ? entry.front.Length : 0;
                zone.planes = new Vector4[nodes];
                for (var node = 0; node < nodes; node++)
                {
                    // A plane's normal is mirrored as a point is, and its distance stays.
                    var normal = Point(new[]
                    {
                        entry.planes[node * 4], entry.planes[node * 4 + 1], entry.planes[node * 4 + 2]
                    });
                    zone.planes[node] = new Vector4(normal.x, normal.y, normal.z, entry.planes[node * 4 + 3]);
                }
                zone.front = entry.front != null ? entry.front : new int[0];
                zone.back = entry.back != null ? entry.back : new int[0];
                zone.decides = entry.decides != null ? entry.decides : new bool[0];
                music.zones[index] = zone;
            }
            return list.zones.Length;
        }

        /// <summary>The audio clips of a zone's track files, each set to load as Streaming.</summary>
        static AudioClip[] Tracks(string folder, string[] files)
        {
            if (files == null)
            {
                return new AudioClip[0];
            }
            var clips = new AudioClip[files.Length];
            for (var index = 0; index < files.Length; index++)
            {
                var path = folder + "/" + files[index];
                var importer = AssetImporter.GetAtPath(path) as AudioImporter;
                if (importer != null)
                {
                    var settings = importer.defaultSampleSettings;
                    if (settings.loadType != AudioClipLoadType.Streaming)
                    {
                        settings.loadType = AudioClipLoadType.Streaming;
                        importer.defaultSampleSettings = settings;
                        importer.SaveAndReimport();
                    }
                }
                clips[index] = AssetDatabase.LoadAssetAtPath<AudioClip>(path);
                if (clips[index] == null)
                {
                    Debug.LogWarning("Cruma: " + path + " is not an audio clip in this project.");
                }
            }
            return clips;
        }

        static int ImportEffects(string folder, Transform root)
        {
            var path = folder + "/effects.json";
            if (!File.Exists(path))
            {
                return 0;
            }
            var list = JsonUtility.FromJson<EffectList>(File.ReadAllText(path));
            if (list == null || list.effects == null)
            {
                return 0;
            }
            var parent = new GameObject("Effects").transform;
            parent.SetParent(root, false);
            foreach (var effect in list.effects)
            {
                var item = new GameObject(effect.actor);
                item.transform.SetParent(parent, false);
                item.transform.localPosition = Point(effect.position);
                item.transform.localRotation = Turn(effect.rotation);
                item.transform.localScale = Scale(effect.scale);
                var component = item.AddComponent<CrumaEffect>();
                component.actor = effect.actor;
                component.tile = effect.tile;
                component.nodes = effect.nodes != null ? effect.nodes : new string[0];
                component.emitters = effect.emitters != null ? effect.emitters : new CrumaEffect.Emitter[0];
            }
            return list.effects.Length;
        }

        /// <summary>
        /// Gives the sun and the lamps of the imported .glb the strength of lights.json, and returns how many
        /// lights it set. Unity lights a white surface that faces a light of Intensity 1 fully, so the strength
        /// is the Intensity.
        /// </summary>
        static int ImportLights(string folder)
        {
            var path = folder + "/lights.json";
            if (!File.Exists(path))
            {
                return 0;
            }
            var list = JsonUtility.FromJson<LightList>(File.ReadAllText(path));
            if (list == null || list.lights == null || list.lights.Length == 0)
            {
                return 0;
            }
            var pipeline = GraphicsSettings.currentRenderPipeline;
            if (pipeline != null && !pipeline.GetType().FullName.Contains("Universal"))
            {
                Debug.Log("Cruma: the lights stay as they are. This render pipeline counts light in units of its own.");
                return 0;
            }
            var entries = new Dictionary<string, LightEntry>();
            foreach (var entry in list.lights)
            {
                entries[entry.name] = entry;
            }
            var count = 0;
            var groups = new HashSet<GameObject>();
            foreach (var light in Resources.FindObjectsOfTypeAll<Light>())
            {
                var item = light.gameObject;
                // A light of an asset, not of the scene.
                if (!item.scene.IsValid() || EditorUtility.IsPersistent(item))
                {
                    continue;
                }
                // An importer may put the Light on a child of the node's GameObject.
                var node = item.transform;
                LightEntry found;
                if (!entries.TryGetValue(node.name, out found))
                {
                    node = node.parent;
                    if (node == null || !entries.TryGetValue(node.name, out found))
                    {
                        continue;
                    }
                }
                // A light of another .glb, such as a second export in the same scene.
                var asset = PrefabUtility.GetPrefabAssetPathOfNearestInstanceRoot(item);
                if (!string.IsNullOrEmpty(asset) && !asset.StartsWith(folder + "/", StringComparison.Ordinal))
                {
                    continue;
                }
                var sun = found.type == "sun";
                if (sun != (light.type == LightType.Directional))
                {
                    continue;
                }
                if (!sun)
                {
                    var place = Point(found.position);
                    if ((node.localPosition - place).magnitude > .01f + place.magnitude * .0001f)
                    {
                        continue;
                    }
                }
                Undo.RecordObject(light, "Import Cruma sounds, music and effects");
                light.intensity = Intensity(found);
                if (!sun && found.radius > 0f)
                {
                    light.range = found.radius;
                }
                PrefabUtility.RecordPrefabInstancePropertyModifications(light);
                count++;
                if (!sun && node.parent != null && IsLamps(node.parent.name))
                {
                    groups.Add(node.parent.gameObject);
                }
            }
            foreach (var group in groups)
            {
                if (group.activeSelf)
                {
                    Undo.RecordObject(group, "Import Cruma sounds, music and effects");
                    group.SetActive(false);
                    PrefabUtility.RecordPrefabInstancePropertyModifications(group);
                }
            }
            if (groups.Count > 0)
            {
                Debug.Log("Cruma: the lamps are switched off, as Unity lights every surface with every lamp in reach. "
                    + "Tick a Lamps object to light the scene with its lamps.");
            }
            return count;
        }

        /// <summary>Whether a GameObject is the group of a tile's lamps: Lamps, or Lamps 23_22.</summary>
        static bool IsLamps(string name)
        {
            return name == "Lamps" || name.StartsWith("Lamps ", StringComparison.Ordinal);
        }

        /// <summary>
        /// The Intensity of a light of lights.json in this project's render pipeline, the Universal or the
        /// Built-in one. The sun's is its strength. A lamp gets the Intensity that gives the client's light at
        /// half its radius: the Universal pipeline fades a lamp with the square of the distance, as the strength
        /// counts, and the Built-in one over its range.
        /// </summary>
        public static float Intensity(LightEntry light)
        {
            var value = light.strength;
            if (light.type != "sun" && light.radius > 0f)
            {
                var half = light.radius / 2f;
                value = GraphicsSettings.currentRenderPipeline == null
                    ? light.strength / (half * half) / BuiltInHalfway
                    : light.strength / UniversalHalfway;
            }
            // Such a project multiplies a light's colour and Intensity in display colour. The result is near in a
            // project of Gamma colour space, which also lights its surfaces in display colour.
            if (QualitySettings.activeColorSpace != ColorSpace.Linear || !GraphicsSettings.lightsUseLinearIntensity)
            {
                value = Mathf.LinearToGammaSpace(value);
            }
            return value;
        }

        /// <summary>A point of the files in Unity's axes.</summary>
        static Vector3 Point(float[] values)
        {
            if (values == null || values.Length < 3)
            {
                return Vector3.zero;
            }
            return MirrorZ
                ? new Vector3(values[0], values[1], -values[2])
                : new Vector3(-values[0], values[1], values[2]);
        }

        /// <summary>A turn of the files, a unit quaternion (x, y, z, w), in Unity's axes.</summary>
        static Quaternion Turn(float[] values)
        {
            if (values == null || values.Length < 4)
            {
                return Quaternion.identity;
            }
            return MirrorZ
                ? new Quaternion(-values[0], -values[1], values[2], values[3])
                : new Quaternion(values[0], -values[1], -values[2], values[3]);
        }

        /// <summary>A scale of the files. Mirroring one axis on both sides of a scale leaves it as it is.</summary>
        static Vector3 Scale(float[] values)
        {
            if (values == null || values.Length < 3)
            {
                return Vector3.one;
            }
            return new Vector3(values[0], values[1], values[2]);
        }
    }

    // The classes below mirror sounds.json, music.json and effects.json for JsonUtility. A field that an entry
    // leaves out reads as 0, false or null.

    [Serializable]
    public class SoundList
    {
        public int version;
        public float metres_per_unit;
        // The export's hour. A list of version 1 has none: then 13, the first hour of the dialog.
        public float hour = 13f;
        public SoundEntry[] sounds;
    }

    [Serializable]
    public class SoundEntry
    {
        public string name;
        public string file;
        public string kind;
        public string actor;
        public string tile;
        public float[] position;
        public string spatial;
        public float radius;
        public float max_distance;
        public float volume;
        public float pitch;
        public float pitch_min;
        public float pitch_max;
        public bool loop;
        // Version 1 only: the client's AmbientRandom of a sound that does not loop.
        public float random_seconds;
        public int chance;
        public float start_time;
        public string when;
        public HourEntry[] hours;
        public float delay_seconds;
        public float fade_in_seconds;
        public float fade_out_start_seconds;
        public float fade_out_seconds;
        public float seconds;
    }

    /// <summary>One of the hours of a sound of range_time, as HHMM: 1630 is 16:30.</summary>
    [Serializable]
    public class HourEntry
    {
        public float start;
        public float end;
    }

    [Serializable]
    public class MusicList
    {
        public int version;
        public float metres_per_unit;
        public float hour;
        public NightHours night;
        public MusicEntry[] zones;
    }

    [Serializable]
    public class NightHours
    {
        public float from_hour;
        public float to_hour;
    }

    [Serializable]
    public class MusicEntry
    {
        public string actor;
        public string tile;
        public int music_id;
        public string[] tracks;
        public bool has_night_music;
        public int night_music_id;
        public string[] night_tracks;
        public bool loop;
        public bool force;
        public float[] bounds_min;
        public float[] bounds_max;
        public bool root_outside;
        public float[] planes;
        public int[] front;
        public int[] back;
        public bool[] decides;
    }

    [Serializable]
    public class LightList
    {
        public int version;
        public float metres_per_unit;
        public float[] ambient;
        public LightEntry[] lights;
    }

    [Serializable]
    public class LightEntry
    {
        public string name;
        public string type;
        public string tile;
        public float[] position;
        public float[] color;
        public float strength;
        public float radius;
    }

    [Serializable]
    public class EffectList
    {
        public int version;
        public float metres_per_unit;
        public EffectEntry[] effects;
    }

    [Serializable]
    public class EffectEntry
    {
        public string actor;
        public string tile;
        public float[] position;
        public float[] rotation;
        public float[] scale;
        public string[] nodes;
        public CrumaEffect.Emitter[] emitters;
    }
}
#endif
