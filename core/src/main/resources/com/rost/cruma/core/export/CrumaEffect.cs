// An Emitter actor of a Cruma location export. import-into-unity.cs puts this component on an empty GameObject at
// the actor's place, turn and scale. It lists the actor's emitters, so a ParticleSystem added by hand can take
// their textures and blends. Unity keeps a component only in a file of its own name, so it has a file of its own.
//
// blend is the client's particle blend: 0 by alpha, 1 adds light, 2 twice the product with what is behind,
// 3 screen, 4 darkens, and -1 for a mesh emitter that keeps its materials' own blend.

using System;
using UnityEngine;

namespace Cruma
{
    public class CrumaEffect : MonoBehaviour
    {
        /// <summary>One emitter of the actor, as effects.json lists it.</summary>
        [Serializable]
        public class Emitter
        {
            public string name;
            public int order;
            public string type;
            public string texture;
            public int blend;
        }

        public string actor;
        public string tile;
        public string[] nodes = new string[0];
        public Emitter[] emitters = new Emitter[0];
    }
}
