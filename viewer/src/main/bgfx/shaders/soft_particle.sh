#include "render_target_sampling.sh"

vec2 softParticleDepthUv(vec4 projected)
{
    return projected.xy / projected.w * vec2(0.5, -0.5) + vec2(0.5, 0.5);
}

vec2 softParticleDepthSampleUv(vec2 screenUv, vec2 activeExtent, vec2 allocationScale, float bottomLeft)
{
    return renderTargetPointSampleUv(fract(screenUv), activeExtent, allocationScale, bottomLeft);
}

float softParticleAlpha(float alpha, float environmentDepth, float currentDepth, float fadeDistance)
{
    float difference = environmentDepth - currentDepth;
    if (environmentDepth != 0.0 && difference > 0.0 && difference <= fadeDistance)
    {
        alpha *= clamp(difference / fadeDistance, 0.0, 1.0);
    }
    return clamp(alpha, 0.0, 1.0);
}
