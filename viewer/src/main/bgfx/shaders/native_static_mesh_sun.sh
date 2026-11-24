#include "native_static_mesh_pixel_lighting.sh"

vec4 nativeStaticMeshSunDiffuse(vec4 diffuse, vec3 sunColor, vec3 worldNormal, vec3 sunDirection)
{
    if (dot(sunColor, sunColor) > 0.0)
        diffuse.rgb += nativeStaticMeshDirectionalDiffuse(normalize(worldNormal), normalize(sunDirection), sunColor);
    return diffuse;
}
