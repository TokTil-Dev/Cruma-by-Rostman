// The ambient sounds of a Cruma location export. import-into-unity.cs puts this component on the GameObject Sounds,
// with an entry for each ambient sound of sounds.json. Unity keeps a component only in a file of its own name, so it
// has a file of its own.
//
// While the scene plays, the component plays each ambient sound as the client does:
// - a sound plays only while the listener is within its max_distance and its when lets it play at hour: always,
//   day from 6:00 to midnight, night from midnight to 6:00, range_time while the game time lies in one of its hours,
//   both ends included, and water never, as the listener of a scene is not under water. A sound that may no longer
//   play stops,
// - a sound with loop plays in a loop,
// - any other sound rolls its chance while it may play and does not play, once in each length of its clip, counted
//   from its start time, and plays once when it wins. A sound whose start time is 0 rolls at once the first time and
//   takes that time as its start time.
//
// The listener is the Transform in listener, or the scene's AudioListener when it is empty. The sounds of the
// Emitter actors are not in the list: they play as the import set them.

using System;
using UnityEngine;

namespace Cruma
{
    public class CrumaSounds : MonoBehaviour
    {
        /// <summary>One ambient sound of sounds.json and its AudioSource.</summary>
        [Serializable]
        public class Sound
        {
            public AudioSource source;
            public bool loop;
            [Tooltip("The chance in percent, from 1 to 99, that the sound plays once in each length of its clip.")]
            public int chance = 100;
            [Tooltip("The client's AmbientSoundStartTime, in seconds. 0: the time of the first roll.")]
            public float startTime;
            [Tooltip("The length of the clip, in seconds.")]
            public float seconds;
            [Tooltip("always, day, night, water or range_time.")]
            public string when = "always";
            [Tooltip("The hours of a sound of range_time: x is the start and y the end, written as HHMM.")]
            public Vector2[] hours = new Vector2[0];
            public float maxDistance;
            [NonSerialized] public bool rolled;
            [NonSerialized] public double firstRoll;
        }

        public Sound[] sounds = new Sound[0];

        [Tooltip("Where the sounds are heard from. Empty: the scene's AudioListener.")]
        public Transform listener;

        [Tooltip("The hour of the game day, which lets the sounds of the day, of the night and of their hours play.")]
        [Range(0f, 24f)]
        public float hour = 13f;

        [Tooltip("How many of the sounds play now, while the scene plays.")]
        public int nowPlaying;

        Transform found;
        double last = double.NaN;

        /// <summary>The client's day of a sound: from 5.999 on, or up to 0.001.</summary>
        public static bool IsSoundDay(double hour)
        {
            return hour >= 5.999 || hour <= 0.001;
        }

        /// <summary>The client's night of a sound: below 6.001, or above 23.999.</summary>
        public static bool IsSoundNight(double hour)
        {
            return hour < 6.001 || hour > 23.999;
        }

        /// <summary>The game time as the client compares it with the hours: 16:19:30 is 1619.3.</summary>
        public static float ClockOf(double hour)
        {
            var hours = (int) hour;
            var fraction = hour - hours;
            var minutes = (int) (fraction * 60.0);
            var seconds = (int) ((fraction - minutes / 60.0) * 3600.0);
            return hours * 100f + minutes + seconds / 100f;
        }

        /// <summary>Whether a sound may play at an hour of the game day, while the listener is not under water.</summary>
        public static bool PlaysAt(Sound sound, double hour)
        {
            switch (sound.when)
            {
                case "day":
                    return IsSoundDay(hour);
                case "night":
                    return IsSoundNight(hour);
                case "water":
                    return false;
                case "range_time":
                    var clock = ClockOf(hour);
                    foreach (var window in sound.hours)
                    {
                        if (window.x <= clock && clock <= window.y)
                        {
                            return true;
                        }
                    }
                    return false;
                default:
                    return true;
            }
        }

        /// <summary>
        /// Whether a new length of a sound begins between the two times, as the client counts the whole lengths
        /// since the start time, each cut off toward zero.
        /// </summary>
        public static bool IsNewLength(double start, double length, double now, double before)
        {
            return Truncate((now - start) / length) > Truncate((before - start) / length);
        }

        void Update()
        {
            var now = Time.timeAsDouble;
            if (double.IsNaN(last))
            {
                last = now;
            }
            var from = Listener();
            var count = 0;
            if (from != null)
            {
                foreach (var sound in sounds)
                {
                    var source = sound.source;
                    if (source == null || source.clip == null)
                    {
                        continue;
                    }
                    var reach = sound.maxDistance;
                    var inReach = (source.transform.position - from.position).sqrMagnitude <= reach * reach;
                    if (!inReach || !PlaysAt(sound, hour))
                    {
                        if (source.isPlaying)
                        {
                            source.Stop();
                        }
                        continue;
                    }
                    if (!source.isPlaying && (sound.loop || Rolls(sound, now)))
                    {
                        source.loop = sound.loop;
                        source.Play();
                    }
                    count += source.isPlaying ? 1 : 0;
                }
            }
            last = now;
            nowPlaying = count;
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

        /// <summary>The client's roll of a sound that may play and does not: true when it plays once now.</summary>
        bool Rolls(Sound sound, double now)
        {
            if (sound.startTime == 0f && !sound.rolled)
            {
                sound.rolled = true;
                sound.firstRoll = (float) now;
            }
            else
            {
                var start = sound.startTime != 0f ? sound.startTime : sound.firstRoll;
                if (!IsNewLength(start, sound.seconds, now, last))
                {
                    return false;
                }
            }
            return UnityEngine.Random.Range(0, 100) < sound.chance;
        }

        // A number out of the range of an int, or NaN, gives the smallest int, as the client's conversion does.
        static int Truncate(double value)
        {
            return value > int.MinValue - 1.0 && value < int.MaxValue + 1.0 ? (int) value : int.MinValue;
        }
    }
}
