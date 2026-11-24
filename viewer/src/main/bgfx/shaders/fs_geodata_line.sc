$input v_color0, v_texcoord1, v_texcoord2

#include <bgfx_shader.sh>

SAMPLER2D(s_geodataDepth, 0);
SAMPLER2D(s_geodataModelDepth, 1);

uniform vec4 u_geodataLineView;
uniform vec4 u_geodataProjection;
uniform vec4 u_geodataHeight;

float geodataLineDistance(float depth)
{
    float denominator = depth * u_geodataProjection.y - u_geodataProjection.x;
    denominator = abs(denominator) > 0.000001 ? denominator : 0.000001;
    return abs((u_geodataProjection.z - depth * u_geodataProjection.w) / denominator);
}

void main()
{
    float isDot = v_texcoord1.w;
    float edge;
    if (isDot > 0.5)
    {
        edge = length(v_texcoord2.xy);
        if (edge > 1.0)
        {
            discard;
        }
    }
    else
    {
        edge = abs(v_texcoord1.y);
    }
    float coverage = 1.0 - smoothstep(1.0 - 2.0 / max(v_texcoord1.z, 1.0), 1.0, edge);

    float hidden = 0.0;
    if (u_geodataHeight.w > 0.0)
    {
        float own = geodataLineDistance(gl_FragCoord.z);
        float sceneDepth = texelFetch(s_geodataDepth, ivec2(gl_FragCoord.xy), 0).x;
        if (sceneDepth < 0.999999)
        {
            float scene = geodataLineDistance(sceneDepth);
            float tolerance = u_geodataHeight.w * (1.0 + own * u_geodataLineView.z);
            hidden = smoothstep(tolerance * 0.5, tolerance, own - scene);
        }
        if (u_geodataHeight.x > 0.5)
        {
            float modelDepth = texelFetch(s_geodataModelDepth, ivec2(gl_FragCoord.xy), 0).x;
            if (modelDepth < 0.999999)
            {
                float model = geodataLineDistance(modelDepth);
                float tolerance = u_geodataHeight.y * (1.0 + own * u_geodataLineView.z);
                hidden = max(hidden, smoothstep(tolerance * 0.5, tolerance, own - model));
            }
        }
    }
    float dash = isDot > 0.5 ? 1.0 : step(0.45, fract(v_texcoord1.x / 28.0));
    float behind = isDot > 1.5 ? 0.55 : 0.4 * dash * u_geodataHeight.z;
    float alpha = v_color0.a * coverage * mix(1.0, behind, hidden);
    if (alpha < 0.004)
    {
        discard;
    }
    gl_FragColor = vec4(v_color0.rgb, alpha);
}
