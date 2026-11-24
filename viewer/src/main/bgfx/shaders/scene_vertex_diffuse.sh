#include "static_mesh_ambient.sh"
#include "material_vertex_lighting.sh"

vec4 sceneVertexDiffuse(vec3 ambientData, vec4 color0, vec4 color1, bool nativeColor, bool unlitActor,
                        vec3 environment, vec3 beastEnvironment, vec3 sun, float beastScale, float seconds)
{
    vec4 ambient = unlitActor ? vec4_splat(1.0)
        : selectStaticMeshAmbient(ambientData, environment, beastEnvironment, sun, beastScale, seconds);
    vec3 fallback = nativeColor ? vec3_splat(0.0) : color0.rgb * 0.5;
    return materialVertexDiffuse(color0, color1, ambient, fallback, nativeColor, false, !nativeColor);
}
