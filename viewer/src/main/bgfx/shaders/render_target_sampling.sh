#ifndef RENDER_TARGET_SAMPLING_SH
#define RENDER_TARGET_SAMPLING_SH

vec2 renderTargetPointSampleUv(vec2 addressedUv, vec2 activeExtent, vec2 allocationScale, float bottomLeft)
{
    vec2 texel = clamp(floor(addressedUv * activeExtent), vec2(0.0, 0.0), activeExtent - vec2(1.0, 1.0));
    if (bottomLeft > 0.5) texel.y = activeExtent.y - 1.0 - texel.y;
    return (texel + vec2(0.5, 0.5)) / activeExtent * allocationScale;
}

vec2 renderTargetSampleUv(
    vec2 addressedUv, vec2 allocationScale, vec2 halfTexel, float bottomLeft, float linearFilter)
{
    vec2 texelSize = vec2(halfTexel.x > 0.0 ? halfTexel.x * 2.0 : 1.0,
                          halfTexel.y > 0.0 ? halfTexel.y * 2.0 : 1.0);
    vec2 activeExtent = max(floor(allocationScale / texelSize + vec2(0.5, 0.5)), vec2(1.0, 1.0));
    if (linearFilter > 0.5)
    {
        vec2 edge = vec2(0.5, 0.5) / activeExtent;
        vec2 uv = clamp(addressedUv, edge, vec2(1.0, 1.0) - edge);
        if (bottomLeft > 0.5) uv.y = 1.0 - uv.y;
        return uv * allocationScale;
    }
    return renderTargetPointSampleUv(addressedUv, activeExtent, allocationScale, bottomLeft);
}

#endif
