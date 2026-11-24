$input v_color0

#include <bgfx_shader.sh>

SAMPLER2D(s_spawnDepth, 0);
SAMPLER2D(s_spawnModelDepth, 1);

uniform vec4 u_spawnProjection;
uniform vec4 u_spawnDepth;

float spawnViewDistance(float depth)
{
    float denominator = depth * u_spawnProjection.y - u_spawnProjection.x;
    denominator = abs(denominator) > 0.000001 ? denominator : 0.000001;
    return abs((u_spawnProjection.z - depth * u_spawnProjection.w) / denominator);
}

void main()
{
    float hidden = 0.0;
    if (u_spawnDepth.w > 0.0)
    {
        float own = spawnViewDistance(gl_FragCoord.z);
        float sceneDepth = texelFetch(s_spawnDepth, ivec2(gl_FragCoord.xy), 0).x;
        if (sceneDepth < 0.999999)
        {
            float scene = spawnViewDistance(sceneDepth);
            float tolerance = u_spawnDepth.w * (1.0 + own / 4096.0);
            hidden = smoothstep(tolerance * 0.5, tolerance, own - scene);
        }
        if (u_spawnDepth.x > 0.5)
        {
            float modelDepth = texelFetch(s_spawnModelDepth, ivec2(gl_FragCoord.xy), 0).x;
            if (modelDepth < 0.999999)
            {
                float model = spawnViewDistance(modelDepth);
                float tolerance = u_spawnDepth.y * (1.0 + own / 4096.0);
                hidden = max(hidden, smoothstep(tolerance * 0.5, tolerance, own - model));
            }
        }
    }
    float alpha = v_color0.a * mix(1.0, u_spawnDepth.z, hidden);
    if (alpha < 0.004)
    {
        discard;
    }
    gl_FragColor = vec4(v_color0.rgb, alpha);
}
