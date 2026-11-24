SAMPLER2D(s_fxTexture, 0);
SAMPLER2D(s_fxDepth, 1);
uniform vec4 u_fxParams[10];

#include "hf_depth_pack.sh"
#include "soft_particle.sh"
#include "height_cut.sh"

vec4 fxResult(
    vec2 texcoord0,
    vec4 texcoord1,
    vec4 nextCell,
    vec4 screenDepth,
    vec4 worldPosition,
    vec4 color0
)
{
    if ((u_fxParams[0].w > 0.5 && dot(worldPosition, u_fxParams[5]) < 0.0)
        || heightCutRejects(u_fxParams[8], u_fxParams[9], worldPosition.xyz))
    {
        discard;
    }
    vec4 texel = texture2DBias(s_fxTexture, texcoord0, u_fxParams[7].z);
    if (nextCell.z > 0.0)
    {
        texel = mix(texel, texture2DBias(s_fxTexture, nextCell.xy, u_fxParams[7].z), nextCell.z);
    }
    float hasTexture = u_fxParams[1].w;
    if (hasTexture < 0.5)
    {
        float distanceFromCenter = length(texcoord1.xy - vec2(0.5, 0.5)) * 2.0;
        float alpha = max(1.0 - distanceFromCenter, 0.0);
        texel = vec4(1.0, 1.0, 1.0, alpha * alpha);
    }

    bool softParticle = u_fxParams[4].x > 0.5;
    if (!softParticle && u_fxParams[4].w >= 0.0 && texel.a * color0.a <= u_fxParams[4].w)
    {
        discard;
    }
    vec3 color = texel.rgb * color0.rgb;
    float rgbCoverage = hasTexture < 0.5 ? texel.a : 1.0;
    float blend = u_fxParams[2].w;
    vec4 result;
    if (blend < 0.5)
    {
        result = vec4(color, texel.a * color0.a);
    }
    else if (blend > 1.5 && blend < 2.5)
    {
        result = vec4(color, 1.0);
    }
    else
    {
        result = vec4(color * rgbCoverage, texel.a * color0.a);
    }

    if (softParticle)
    {
        vec2 depthUv = softParticleDepthSampleUv(
            screenDepth.xy, u_fxParams[3].zw, u_fxParams[3].xy, u_fxParams[4].z);
        float environmentDist = unpackHfViewDepth(texture2D(s_fxDepth, depthUv).rgb);
        result.a = softParticleAlpha(result.a, environmentDist, screenDepth.z, u_fxParams[4].y);
        if (u_fxParams[4].w >= 0.0 && result.a <= u_fxParams[4].w) discard;
    }
    if (u_fxParams[6].w > 0.5 && (u_fxParams[4].x < 0.5 || u_fxParams[6].w > 1.5))
    {
        float fogFactor = clamp(
            (u_fxParams[7].y - screenDepth.w)
                / max(u_fxParams[7].y - u_fxParams[7].x, 0.0001),
            0.0,
            1.0
        );
        result.rgb = mix(u_fxParams[6].rgb, result.rgb, fogFactor);
    }
    return result;
}
