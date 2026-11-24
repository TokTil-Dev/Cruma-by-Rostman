uniform vec4 u_nativeVertexLightmap;

vec3 nativeVertexLightmap(uint vertex, uint coefficient)
{
    if (vertex >= uint(u_nativeVertexLightmap.z)) return vec3_splat(0.0);
    uint base = uint(u_nativeVertexLightmap.x) | (uint(u_nativeVertexLightmap.y) << 16u);
    uint at = base + vertex * 9u + coefficient * 3u;
    return uintBitsToFloat(uvec3(u_actorLighting[at], u_actorLighting[at + 1u], u_actorLighting[at + 2u]));
}
