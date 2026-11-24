// The music of a Cruma location export. import-into-unity.cs puts this component on the GameObject Music, with
// the music zones of music.json and an AudioSource. Unity keeps a component only in a file of its own name, so it
// has a file of its own.
//
// While the scene plays, the component plays the music of the zone the listener is in, as the client does:
// - of two zones that hold the listener it takes one with force, otherwise the later one of the list,
// - in another zone the track fades out over fadeSeconds, and a track of the new zone starts, picked at random,
// - a track that ends plays again at once in a zone with loop, otherwise after waitSeconds,
// - outside every zone the track plays to its end,
// - at night, from nightFromHour to nightToHour by hour, a zone with night music plays its night tracks.
//
// The listener is the Transform in listener, or the scene's AudioListener when it is empty. The zones are in the
// axes of this GameObject, so keep it where the import put it, or move it together with the imported .glb.
//
// A zone is a brush, and its bounds are only a box around it. Zone.Contains walks the brush's nodes, as
// music.json says under "inside". A selected Music object draws the boxes in the Scene view.

using System;
using UnityEngine;

namespace Cruma
{
    public class CrumaMusic : MonoBehaviour
    {
        /// <summary>One music zone, as music.json lists it, in this GameObject's axes.</summary>
        [Serializable]
        public class Zone
        {
            public string actor;
            public string tile;
            public int musicId;
            public AudioClip[] tracks = new AudioClip[0];
            public bool hasNightMusic;
            public int nightMusicId;
            public AudioClip[] nightTracks = new AudioClip[0];
            public bool loop;
            public bool force;
            public Vector3 boundsMin;
            public Vector3 boundsMax;
            public bool rootOutside;
            // One plane for each node: its normal in x, y and z and its distance in w.
            public Vector4[] planes = new Vector4[0];
            public int[] front = new int[0];
            public int[] back = new int[0];
            public bool[] decides = new bool[0];

            /// <summary>Whether a point in the axes of the Music object is inside the zone's brush.</summary>
            public bool Contains(Vector3 point)
            {
                if (point.x < boundsMin.x || point.x > boundsMax.x
                    || point.y < boundsMin.y || point.y > boundsMax.y
                    || point.z < boundsMin.z || point.z > boundsMax.z)
                {
                    return false;
                }
                if (front.Length == 0)
                {
                    return !rootOutside;
                }
                var index = 0;
                var outside = rootOutside;
                // A well-formed brush ends at a leaf. The counter only bounds a damaged one.
                for (var guard = 0; index >= 0 && index < front.Length && guard <= front.Length; guard++)
                {
                    var plane = planes[index];
                    var side = plane.x * point.x + plane.y * point.y + plane.z * point.z - plane.w;
                    var isFront = side > 0f;
                    if (decides[index])
                    {
                        outside = isFront;
                    }
                    var next = isFront ? front[index] : back[index];
                    if (next < 0)
                    {
                        return !outside;
                    }
                    index = next;
                }
                return false;
            }

            /// <summary>The tracks the zone plays by day or by night.</summary>
            public AudioClip[] TracksAt(bool night)
            {
                return night && hasNightMusic ? nightTracks : tracks;
            }
        }

        public Zone[] zones = new Zone[0];

        [Tooltip("Where the music is heard from. Empty: the scene's AudioListener.")]
        public Transform listener;

        [Tooltip("The hour of the game day, which chooses the music of the day or of the night.")]
        [Range(0f, 24f)]
        public float hour = 13f;

        public float nightFromHour = 20f;
        public float nightToHour = 6f;

        [Range(0f, 1f)]
        public float volume = 1f;

        [Tooltip("How long a track fades out when the listener comes into another zone.")]
        public float fadeSeconds = 2.5f;

        [Tooltip("How long a zone without loop waits before it plays a track again.")]
        public float waitSeconds = 60f;

        [Tooltip("The track that plays and its zone, while the scene plays.")]
        public string nowPlaying = "";

        AudioSource source;
        Transform found;
        Zone active;
        AudioClip next;
        bool fadingOut;
        float level = 1f;
        bool canReplay;
        float replayWait;

        /// <summary>Whether the hour is in the night.</summary>
        public bool IsNight
        {
            get
            {
                return nightFromHour <= nightToHour
                    ? hour >= nightFromHour && hour < nightToHour
                    : hour >= nightFromHour || hour < nightToHour;
            }
        }

        /// <summary>The zone that holds a point in world axes, or null.</summary>
        public Zone Locate(Vector3 worldPoint)
        {
            var point = transform.InverseTransformPoint(worldPoint);
            Zone result = null;
            foreach (var zone in zones)
            {
                if (zone.Contains(point))
                {
                    if (zone.force)
                    {
                        return zone;
                    }
                    result = zone;
                }
            }
            return result;
        }

        void Awake()
        {
            source = GetComponent<AudioSource>();
            if (source == null)
            {
                source = gameObject.AddComponent<AudioSource>();
            }
            source.playOnAwake = false;
            source.loop = false;
            source.spatialBlend = 0f;
        }

        void Update()
        {
            var seconds = Time.unscaledDeltaTime;
            Fade(seconds);
            var from = Listener();
            if (from == null)
            {
                return;
            }
            var playing = source.isPlaying || next != null;
            var containing = Locate(from.position);
            if (!playing)
            {
                if (containing != null && containing.loop)
                {
                    canReplay = true;
                    replayWait = 0f;
                }
                else if (!canReplay)
                {
                    replayWait += seconds;
                    if (replayWait >= waitSeconds)
                    {
                        canReplay = true;
                        replayWait = 0f;
                    }
                }
            }
            if (containing != null)
            {
                if (containing != active)
                {
                    if (playing)
                    {
                        FadeOut();
                    }
                    Play(containing);
                }
                else if (canReplay && !playing)
                {
                    Play(containing);
                }
            }
            active = containing;
            if (!source.isPlaying && next == null)
            {
                nowPlaying = "";
            }
        }

        void OnDrawGizmosSelected()
        {
            Gizmos.matrix = transform.localToWorldMatrix;
            foreach (var zone in zones)
            {
                Gizmos.color = zone == active ? Color.green : new Color(1f, .75f, .2f);
                Gizmos.DrawWireCube((zone.boundsMin + zone.boundsMax) / 2f, zone.boundsMax - zone.boundsMin);
            }
        }

        Transform Listener()
        {
            if (listener != null)
            {
                return listener;
            }
            if (found == null)
            {
#if UNITY_2023_1_OR_NEWER
                var heard = FindAnyObjectByType<AudioListener>();
#else
                var heard = FindObjectOfType<AudioListener>();
#endif
                found = heard != null ? heard.transform : null;
            }
            return found;
        }

        /// <summary>Starts a track of the zone, after the one that plays has faded out.</summary>
        void Play(Zone zone)
        {
            var clips = zone.TracksAt(IsNight);
            var count = 0;
            foreach (var clip in clips)
            {
                count += clip != null ? 1 : 0;
            }
            if (count == 0)
            {
                return;
            }
            var pick = UnityEngine.Random.Range(0, count);
            AudioClip chosen = null;
            foreach (var clip in clips)
            {
                if (clip != null && pick-- == 0)
                {
                    chosen = clip;
                    break;
                }
            }
            canReplay = false;
            replayWait = 0f;
            var where = string.IsNullOrEmpty(zone.actor) ? "music " + zone.musicId : zone.actor;
            nowPlaying = chosen.name + " (" + where + ")";
            if (source.isPlaying)
            {
                next = chosen;
                fadingOut = true;
            }
            else
            {
                Begin(chosen);
            }
        }

        void FadeOut()
        {
            next = null;
            fadingOut = source.isPlaying;
        }

        void Begin(AudioClip clip)
        {
            next = null;
            fadingOut = false;
            level = 1f;
            source.clip = clip;
            source.volume = volume;
            source.Play();
        }

        void Fade(float seconds)
        {
            if (fadingOut)
            {
                level = fadeSeconds > 0f ? level - seconds / fadeSeconds : 0f;
                if (level <= 0f || !source.isPlaying)
                {
                    source.Stop();
                    fadingOut = false;
                    level = 1f;
                    if (next != null)
                    {
                        Begin(next);
                    }
                }
            }
            source.volume = volume * Mathf.Clamp01(level);
        }
    }
}
