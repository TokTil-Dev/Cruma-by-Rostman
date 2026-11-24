$input a_position
$output v_color0
#include <bgfx_compute.sh>
BUFFER_RO(u_actorLighting, uint, 11);
#include "actor_lighting_alpha.sh"
#include "material_vertex_lighting.sh"
#include "static_mesh_ambient.sh"
uniform vec4 u_materialDiffuseProbe[6];
void main()
{
    uint base = uint(u_materialDiffuseProbe[3].x);
    uint vertex = uint(u_materialDiffuseProbe[3].y) + uint(gl_VertexID);
    uint packed = 0u;
    if ((u_actorLighting[base + 1u] & 1u) != 0u)
        packed = u_actorLighting[base + 3u + vertex];
    vec4 color0 = vec4(float(packed & 255u), float((packed >> 8u) & 255u),
        float((packed >> 16u) & 255u), 0.0) / 255.0;
    color0.a = actorLightingAlpha(base, vertex);
    uint flags = uint(u_materialDiffuseProbe[2].w);
    vec4 ambient = scaleStaticMeshAmbient(u_materialDiffuseProbe[1], actorAmbientIntensity(base));
    vec3 ambientData = actorAmbientData(base);
    if (ambientData.y > 0.5)
        ambient = selectStaticMeshAmbient(ambientData, u_materialDiffuseProbe[1].rgb,
            u_materialDiffuseProbe[4].rgb, u_materialDiffuseProbe[5].rgb,
            u_materialDiffuseProbe[4].w, u_materialDiffuseProbe[5].w);
    v_color0 = u_materialDiffuseProbe[3].z > 0.5 ? ambient : materialVertexDiffuse(color0, u_materialDiffuseProbe[0], ambient,
        u_materialDiffuseProbe[2].xyz, (flags & 1u) != 0u, (flags & 2u) != 0u, (flags & 4u) != 0u);
    gl_Position = vec4(a_position, 1.0);
}
