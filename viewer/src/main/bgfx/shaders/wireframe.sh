#include <bgfx_shader.sh>
#include "height_cut.sh"

uniform vec4 u_sceneFrame[18];
uniform vec4 u_wireColor;

vec4 wireframeColor(vec3 worldPosition)
{
    if (heightCutRejects(u_sceneFrame[16], u_sceneFrame[17], worldPosition))
    {
        discard;
    }
    return u_wireColor;
}
