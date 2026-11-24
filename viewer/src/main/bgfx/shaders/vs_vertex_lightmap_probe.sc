$input a_position
$output v_color0

#include <bgfx_compute.sh>
BUFFER_RO(u_actorLighting, uint, 11);
#include "native_vertex_lightmap.sh"
uniform vec4 u_vertexLightmapProbe;

void main()
{
    uint vertex = uint(gl_VertexID);
    uint channel = uint(u_vertexLightmapProbe.y);
    vec3 coefficient = nativeVertexLightmap(vertex, channel / 3u);
    float value = coefficient[int(channel % 3u)];
    uint bits = floatBitsToUint(value);
    v_color0 = vec4(float(bits & 255u), float((bits >> 8u) & 255u),
        float((bits >> 16u) & 255u), float(bits >> 24u)) / 255.0;
    float pixel = u_vertexLightmapProbe.x + float(vertex * 9u + channel);
    float x = mod(pixel, u_vertexLightmapProbe.z);
    float y = floor(pixel / u_vertexLightmapProbe.z);
    gl_Position = vec4((x + 0.5) / u_vertexLightmapProbe.z * 2.0 - 1.0,
        1.0 - (y + 0.5) / u_vertexLightmapProbe.w * 2.0, a_position.z, 1.0);
}
