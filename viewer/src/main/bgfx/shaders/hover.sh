#include <bgfx_shader.sh>
#include "height_cut.sh"

uniform vec4 u_sceneFrame[18];
uniform vec4 u_hoverColor;
uniform vec4 u_hoverProjection;
SAMPLER2D(s_hoverDepth, 13);

float hoverDistance(float depth)
{
    float denominator = depth * u_hoverProjection.y - u_hoverProjection.x;
    denominator = abs(denominator) > 0.000001 ? denominator : 0.000001;
    return abs((u_hoverProjection.z - depth * u_hoverProjection.w) / denominator);
}

vec4 hoverColor(vec3 worldPosition, vec4 fragCoord)
{
    if (heightCutRejects(u_sceneFrame[16], u_sceneFrame[17], worldPosition))
    {
        discard;
    }
    float own = hoverDistance(fragCoord.z);
    float scene = hoverDistance(texelFetch(s_hoverDepth, ivec2(fragCoord.xy), 0).x);
    if (abs(own - scene) > 2.0 + own * 0.002)
    {
        discard;
    }
    return u_hoverColor;
}
