$input v_texcoord0

#include <bgfx_shader.sh>

SAMPLER2D(s_rayTemporalCurrent, 0);
SAMPLER2D(s_rayTemporalHistory, 1);

uniform vec4 u_rayTemporal[17];

float signNotZero(float value)
{
    return value >= 0.0 ? 1.0 : -1.0;
}

vec3 decodeWorldNormal(float encoded)
{
    float code = max(floor(encoded + 0.5) - 1.0, 0.0);
    vec2 quantized = vec2(mod(code, 32.0), floor(code / 32.0));
    vec2 octahedral = quantized / 31.0 * 2.0 - 1.0;
    vec3 normal = vec3(
        octahedral,
        1.0 - abs(octahedral.x) - abs(octahedral.y)
    );
    if (normal.z < 0.0)
    {
        normal.xy = (vec2_splat(1.0) - abs(normal.yx)) * vec2(
            signNotZero(normal.x), signNotZero(normal.y)
        );
    }
    return normalize(normal);
}

vec4 multiplyMatrix(int base, vec3 world)
{
    return u_rayTemporal[base] * world.x
        + u_rayTemporal[base + 1] * world.y
        + u_rayTemporal[base + 2] * world.z
        + u_rayTemporal[base + 3];
}

vec3 reconstructCurrentWorld(vec2 screenUv, float normalizedDistance)
{
    vec2 ndc = vec2(screenUv.x * 2.0 - 1.0, 1.0 - screenUv.y * 2.0);
    vec3 viewDirection = vec3(
        ndc.x / u_rayTemporal[16].x,
        ndc.y / u_rayTemporal[16].y,
        1.0
    );
    vec3 rayDirection = normalize(
        u_rayTemporal[13].xyz * viewDirection.x
            + u_rayTemporal[14].xyz * viewDirection.y
            + u_rayTemporal[15].xyz
    );
    vec3 camera = u_rayTemporal[10].xyz;
    float distanceToHit = normalizedDistance * max(u_rayTemporal[10].w, 1.0);
    return camera + rayDirection * distanceToHit;
}

bool sameSurface(vec4 sampleValue, vec3 normal, float expectedDistance, float primaryDistance)
{
    if (sampleValue.a < 0.5)
    {
        return false;
    }
    float distanceTolerance = u_rayTemporal[12].z / max(primaryDistance, 1.0);
    return abs(sampleValue.b - expectedDistance) <= distanceTolerance
        && dot(decodeWorldNormal(sampleValue.a), normal) >= u_rayTemporal[12].w;
}

void main()
{
    vec2 currentScale = u_rayTemporal[0].zw * u_rayTemporal[0].xy;
    vec4 current = texture2D(s_rayTemporalCurrent, v_texcoord0 * currentScale);
    if (u_rayTemporal[12].x < 0.5 || current.a < 0.5)
    {
        gl_FragColor = current;
        return;
    }

    vec3 currentNormal = decodeWorldNormal(current.a);
    vec3 world = reconstructCurrentWorld(v_texcoord0, current.b);
    vec4 previousClip = multiplyMatrix(6, world);
    vec3 previousNdc = previousClip.xyz / max(abs(previousClip.w), 0.000001)
        * sign(previousClip.w);
    vec2 previousScreenUv = vec2(
        previousNdc.x * 0.5 + 0.5,
        0.5 - previousNdc.y * 0.5
    );
    bool inside = previousClip.w > 0.0
        && previousNdc.z > 0.0 && previousNdc.z < 1.0
        && previousScreenUv.x > 0.0 && previousScreenUv.y > 0.0
        && previousScreenUv.x < 1.0 && previousScreenUv.y < 1.0;
    if (!inside)
    {
        gl_FragColor = current;
        return;
    }

    float previousPrimaryDistance = max(u_rayTemporal[11].w, 1.0);
    float expectedPreviousDistance = length(world - u_rayTemporal[11].xyz)
        / previousPrimaryDistance;
    vec2 previousExtent = max(u_rayTemporal[1].zw, vec2_splat(1.0));
    vec2 previousPixel = previousScreenUv * previousExtent - 0.5;
    vec2 previousBase = floor(previousPixel);
    vec2 previousFraction = fract(previousPixel);
    float historyVisibility = 0.0;
    float historyWeight = 0.0;
    for (int sampleY = 0; sampleY < 2; ++sampleY)
    {
        for (int sampleX = 0; sampleX < 2; ++sampleX)
        {
            vec2 offset = vec2(float(sampleX), float(sampleY));
            vec2 pixel = clamp(previousBase + offset, vec2_splat(0.0), previousExtent - 1.0);
            vec4 history = texture2DLod(
                s_rayTemporalHistory,
                (pixel + 0.5) * u_rayTemporal[1].xy,
                0.0
            );
            vec2 axisWeight = mix(vec2_splat(1.0) - previousFraction, previousFraction, offset);
            float weight = axisWeight.x * axisWeight.y;
            if (sameSurface(
                history, currentNormal, expectedPreviousDistance, previousPrimaryDistance
            ))
            {
                historyVisibility += history.r * weight;
                historyWeight += weight;
            }
        }
    }
    if (historyWeight < 0.0001)
    {
        gl_FragColor = current;
        return;
    }

    vec2 currentExtent = max(u_rayTemporal[0].zw, vec2_splat(1.0));
    vec2 currentPixel = v_texcoord0 * currentExtent - 0.5;
    vec2 currentBase = floor(currentPixel + 0.5);
    float currentMinimum = current.r;
    float currentMaximum = current.r;
    for (int neighbourY = -1; neighbourY <= 1; ++neighbourY)
    {
        for (int neighbourX = -1; neighbourX <= 1; ++neighbourX)
        {
            vec2 pixel = clamp(
                currentBase + vec2(float(neighbourX), float(neighbourY)),
                vec2_splat(0.0), currentExtent - 1.0
            );
            vec4 neighbour = texture2DLod(
                s_rayTemporalCurrent,
                (pixel + 0.5) * u_rayTemporal[0].xy,
                0.0
            );
            if (sameSurface(
                neighbour, currentNormal, current.b, max(u_rayTemporal[10].w, 1.0)
            ))
            {
                currentMinimum = min(currentMinimum, neighbour.r);
                currentMaximum = max(currentMaximum, neighbour.r);
            }
        }
    }
    float previousVisibility = historyVisibility / historyWeight;
    previousVisibility = clamp(
        previousVisibility,
        max(0.0, currentMinimum - 0.125),
        min(1.0, currentMaximum + 0.125)
    );
    current.r = mix(current.r, previousVisibility, u_rayTemporal[12].y);
    gl_FragColor = current;
}
