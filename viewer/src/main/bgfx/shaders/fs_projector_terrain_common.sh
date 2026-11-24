#include <bgfx_shader.sh>

#include "height_cut.sh"

SAMPLER2D(s_projector, 0);
uniform vec4 u_projector[7];

vec4 projectorTerrainColor(vec3 worldPosition)
{
    vec3 delta = worldPosition - u_projector[0].xyz;
    float forward = dot(delta, u_projector[1].xyz);
    float maxTrace = u_projector[1].w;
    bool rejected = forward < 0.0 || forward > maxTrace;

    float u = 0.0;
    float v = 0.0;
    if (u_projector[0].w < 0.5)
    {
        u = 0.5 - dot(delta, u_projector[2].xyz) / u_projector[2].w;
        v = 0.5 - dot(delta, u_projector[3].xyz) / u_projector[3].w;
    }
    else
    {
        float nearDistance = 0.5 * u_projector[2].w / u_projector[4].x;
        float projectiveDepth = forward + nearDistance;
        rejected = rejected || projectiveDepth <= 0.000001;
        float scale = 0.5 / u_projector[4].x / max(projectiveDepth, 0.000001);
        u = 0.5 - dot(delta, u_projector[2].xyz) * scale;
        v = 0.5 - dot(delta, u_projector[3].xyz) * scale;
    }
    rejected = rejected || u < 0.0 || u > 1.0 || v < 0.0 || v > 1.0
        || heightCutRejects(u_projector[5], u_projector[6], worldPosition);
    if (rejected)
    {
        discard;
    }

    vec4 projected = texture2D(s_projector, vec2(u, v));
    float pair = u_projector[4].y;
    vec4 result = vec4(2.0 * projected.rgb, projected.a);
    if (pair < 0.5)
    {
        result = projected;
    }
    else if (pair < 1.5)
    {
        result = vec4(2.0 * projected.rgb * projected.a, projected.a);
    }
    return result;
}

void main()
{
    vec4 projectorColor = projectorTerrainColor(v_texcoord7.xyz);
#if defined(PROJECTOR_VARIANT_DEPTH_MRT)
    gl_FragData[0] = projectorColor;
    gl_FragData[1] = vec4_splat(0.0);
    gl_FragData[2] = vec4_splat(0.0);
#else
    gl_FragColor = projectorColor;
#endif
}
