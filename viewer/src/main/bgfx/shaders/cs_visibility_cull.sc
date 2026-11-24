#include <bgfx_compute.sh>
#include "compute_rows.sh"
#include "static_mesh_lod.sh"

SAMPLER2D(s_visibilityDepth, 0);
BUFFER_RO(visibilityBounds, vec4, 1);
BUFFER_RO(visibilityInstancesIn, vec4, 2);
BUFFER_RW(visibilityCommandCounts, uint, 3);
BUFFER_WO(visibilityInstancesOut, vec4, 4);
BUFFER_RO(visibilityCommandData, uint, 5);
IMAGE2D_WO(visibilityClassifications, rgba8, 6);

uniform vec4 u_visibilityConfig;
uniform vec4 u_visibilityExtent;
uniform vec4 u_visibilityCamera;
uniform vec4 u_visibilityOptions;
uniform vec4 u_visibilityLod;
uniform mat4 u_visibilityPreviousViewProj;

void writeClassification(uint candidate, float code)
{
    uint width = max(1u, uint(u_visibilityOptions.w));
    imageStore(
        visibilityClassifications,
        ivec2(int(candidate % width), int(candidate / width)),
        vec4(code / 255.0, 0.0, 0.0, 1.0)
    );
}

bool projectsConservatively(mat4 viewProjection, vec3 minimum, vec3 maximum,
                            out vec3 ndcMinimum, out vec3 ndcMaximum,
                            out bool hasFrontCorner)
{
    ndcMinimum = vec3(1.0e30, 1.0e30, 1.0e30);
    ndcMaximum = vec3(-1.0e30, -1.0e30, -1.0e30);
    bool crossesEye = false;
    hasFrontCorner = false;
    for (int corner = 0; corner < 8; ++corner)
    {
        vec3 cornerPosition = minimum;
        if ((corner & 1) != 0) cornerPosition.x = maximum.x;
        if ((corner & 2) != 0) cornerPosition.y = maximum.y;
        if ((corner & 4) != 0) cornerPosition.z = maximum.z;
        vec4 clip = mul(viewProjection, vec4(cornerPosition, 1.0));
        if (clip.w <= 0.00001)
        {
            crossesEye = true;
        }
        else
        {
            hasFrontCorner = true;
#if BGFX_SHADER_LANGUAGE_GLSL
            clip.z = 0.5 * (clip.z + clip.w);
#endif
            vec3 ndc = clip.xyz / clip.w;
            ndcMinimum = min(ndcMinimum, ndc);
            ndcMaximum = max(ndcMaximum, ndc);
        }
    }
    return crossesEye;
}

bool frustumVisible(vec3 minimum, vec3 maximum,
                    out vec3 ndcMinimum, out vec3 ndcMaximum,
                    out bool crossesEye, out bool hasFrontCorner)
{
    crossesEye = projectsConservatively(
        u_viewProj, minimum, maximum, ndcMinimum, ndcMaximum, hasFrontCorner
    );
    if (crossesEye)
    {
        return hasFrontCorner;
    }
    return hasFrontCorner
        && ndcMaximum.x >= -1.0 && ndcMinimum.x <= 1.0
        && ndcMaximum.y >= -1.0 && ndcMinimum.y <= 1.0
        && ndcMaximum.z >= 0.0 && ndcMinimum.z <= 1.0;
}

bool historyVisible(vec3 minimum, vec3 maximum,
                    vec3 currentMinimum, vec3 currentMaximum,
                    bool currentCrossesEye, bool hasCurrentFrontCorner)
{
    if (u_visibilityConfig.z < 0.5)
    {
        return true;
    }
    vec3 ndcMinimum;
    vec3 ndcMaximum;
    bool hasPreviousFrontCorner;
    if (projectsConservatively(
        u_visibilityPreviousViewProj, minimum, maximum, ndcMinimum, ndcMaximum,
        hasPreviousFrontCorner
    ) || !hasPreviousFrontCorner)
    {
        return true;
    }
    if (currentCrossesEye || !hasCurrentFrontCorner)
    {
        return true;
    }
    ndcMinimum = min(ndcMinimum, currentMinimum);
    ndcMaximum = max(ndcMaximum, currentMaximum);

    float yScale = u_visibilityOptions.z > 0.5 ? 0.5 : -0.5;
    vec2 uvMinimum = ndcMinimum.xy * vec2(0.5, yScale) + vec2(0.5, 0.5);
    vec2 uvMaximum = ndcMaximum.xy * vec2(0.5, yScale) + vec2(0.5, 0.5);
    vec2 low = min(uvMinimum, uvMaximum);
    vec2 high = max(uvMinimum, uvMaximum);
    vec2 guard = 2.0 * u_visibilityExtent.zw;
    low = clamp(low - guard, vec2(0.0, 0.0), vec2(1.0, 1.0));
    high = clamp(high + guard, vec2(0.0, 0.0), vec2(1.0, 1.0));

    vec2 pixels = max((high - low) * u_visibilityExtent.xy, vec2(1.0, 1.0));
    float mip = clamp(ceil(log2(max(pixels.x, pixels.y))) - 2.0, 0.0, u_visibilityConfig.w);
    int mipLevel = int(mip);
    ivec2 mipExtent = max(
        ivec2(1, 1), ivec2(u_visibilityExtent.xy) >> mipLevel
    );
    ivec2 maximumTexel = mipExtent - ivec2(1, 1);
    vec2 mipCoordinates = u_visibilityExtent.xy / exp2(mip);
    ivec2 lowTexel = clamp(ivec2(floor(low * mipCoordinates)), ivec2(0, 0), maximumTexel);
    ivec2 highTexel = clamp(
        ivec2(ceil(high * mipCoordinates)) - ivec2(1, 1), lowTexel, maximumTexel
    );
    float previousDepth = 0.0;
    for (int row = 0; row < 5; ++row)
    {
        for (int column = 0; column < 5; ++column)
        {
            ivec2 coordinate = lowTexel + ivec2(column, row);
            if (all(lessThanEqual(coordinate, highTexel)))
            {
                previousDepth = max(
                    previousDepth,
                    texelFetch(s_visibilityDepth, coordinate, mipLevel).x
                );
            }
        }
    }
    const float depthMargin = 32.0 / 16777216.0;
    return clamp(ndcMinimum.z, 0.0, 1.0) <= previousDepth + depthMargin;
}

NUM_THREADS(64, 1, 1)
void main()
{
    uint candidate = gl_GlobalInvocationID.y * COMPUTE_ROW_INVOCATIONS + gl_GlobalInvocationID.x;
    if (candidate >= uint(u_visibilityConfig.x))
    {
        return;
    }

    vec4 packedMinimum = visibilityBounds[candidate * 3u];
    vec4 packedMaximum = visibilityBounds[candidate * 3u + 1u];
    vec4 lod = visibilityBounds[candidate * 3u + 2u];
    vec3 minimum = packedMinimum.xyz;
    vec3 maximum = packedMaximum.xyz;
    uint command = uint(packedMinimum.w);
    uint flags = uint(packedMaximum.w);
    uint materialRecord = flags >> 8u;
    uint enabledMask = uint(u_visibilityOptions.x);
    const uint CATEGORY_MASK = 31u;
    const uint FLAG_ORDERED = 128u;
    bool ordered = (flags & FLAG_ORDERED) != 0u;
    if (ordered)
    {
        visibilityInstancesOut[candidate * 5u + 4u] = vec4(0.0, 0.0, 0.0, 0.0);
    }
    vec3 ndcMinimum;
    vec3 ndcMaximum;
    bool crossesEye;
    bool hasFrontCorner;
    if (((flags & CATEGORY_MASK) & (~enabledMask)) != 0u
        || !frustumVisible(
            minimum, maximum, ndcMinimum, ndcMaximum, crossesEye, hasFrontCorner
        ))
    {
        writeClassification(candidate, 1.0);
        return;
    }

    vec2 projectedPixels = max(ndcMaximum.xy - ndcMinimum.xy, vec2(0.0, 0.0))
        * 0.5 * u_visibilityExtent.xy;
    float projectedSpan = max(projectedPixels.x, projectedPixels.y);
    uint instanceInput = candidate * 5u;
    float lodCoverage = staticMeshLodCoverage(
        u_visibilityCamera.xy, visibilityInstancesIn[instanceInput + 3u].xy, lod,
        u_visibilityLod.z
    );
    if (lodCoverage < 0.5)
    {
        writeClassification(candidate, 2.0);
        return;
    }

    if (lod.z < -1.5 && lod.x > 0.0 && u_visibilityLod.z <= 0.5)
    {
        vec3 toCamera = visibilityInstancesIn[instanceInput + 3u].xyz - u_visibilityCamera.xyz;
        if (dot(toCamera, toCamera) > lod.x * lod.x)
        {
            writeClassification(candidate, 2.0);
            return;
        }
    }

    const uint FLAG_TERRAIN_PROXY = 32u;
    const uint FLAG_TERRAIN_DETAIL = 64u;
    {
        if ((flags & FLAG_TERRAIN_PROXY) != 0u
            && (u_visibilityLod.y <= 0.0 || crossesEye || projectedSpan >= u_visibilityLod.y))
        {
            writeClassification(candidate, 2.0);
            return;
        }
        if ((flags & FLAG_TERRAIN_DETAIL) != 0u
            && u_visibilityLod.y > 0.0 && !crossesEye && projectedSpan < u_visibilityLod.y)
        {
            writeClassification(candidate, 2.0);
            return;
        }
    }

    const uint CATEGORY_STATIC_MESH = 4u;
    if ((flags & CATEGORY_STATIC_MESH) != 0u
        && u_visibilityLod.x > 0.0 && !crossesEye)
    {
        float lightingMarker = visibilityInstancesIn[instanceInput + 4u].w;
        bool grass = lightingMarker > 2.5 && lightingMarker < 6.5;
        float minimumPixels = u_visibilityLod.x / (grass ? max(1.0, u_visibilityLod.w) : 1.0);
        if (projectedSpan < minimumPixels)
        {
            writeClassification(candidate, 2.0);
            return;
        }
    }

    if (u_visibilityConfig.w < 0.0)
    {
        vec3 distanceToBox = max(
            max(u_visibilityCamera.xyz - maximum, minimum - u_visibilityCamera.xyz),
            vec3(0.0, 0.0, 0.0)
        );
        if (dot(distanceToBox, distanceToBox) > -u_visibilityConfig.w)
        {
            writeClassification(candidate, 2.0);
            return;
        }
    }

    if ((flags & CATEGORY_STATIC_MESH) != 0u && u_visibilityCamera.w >= 0.0)
    {
        vec3 distanceToBox = max(
            max(u_visibilityCamera.xyz - maximum, minimum - u_visibilityCamera.xyz),
            vec3(0.0, 0.0, 0.0)
        );
        if (dot(distanceToBox, distanceToBox) > u_visibilityCamera.w)
        {
            writeClassification(candidate, 2.0);
            return;
        }
    }
    if (!historyVisible(
        minimum, maximum, ndcMinimum, ndcMaximum, crossesEye, hasFrontCorner
    ))
    {
        writeClassification(candidate, 3.0);
        return;
    }

    if (ordered)
    {
        uint previousEnd;
        atomicFetchAndMax(
            visibilityCommandCounts[command],
            candidate - visibilityCommandData[command * 4u + 3u] + 1u,
            previousEnd
        );
        visibilityInstancesOut[candidate * 5u + 4u] = vec4(0.0, 0.0, 0.0, 1.0);
        writeClassification(candidate, 4.0);
        return;
    }

    uint localIndex;
    atomicFetchAndAdd(visibilityCommandCounts[command], 1u, localIndex);
    uint output = visibilityCommandData[command * 4u + 3u] + localIndex;
    uint destination = output * 5u;
    visibilityInstancesOut[destination] = visibilityInstancesIn[instanceInput];
    visibilityInstancesOut[destination + 1u] = visibilityInstancesIn[instanceInput + 1u];
    visibilityInstancesOut[destination + 2u] = visibilityInstancesIn[instanceInput + 2u];
    visibilityInstancesOut[destination + 3u] = visibilityInstancesIn[instanceInput + 3u];
    vec4 lighting = visibilityInstancesIn[instanceInput + 4u];
    lighting.w += 8.0 + float(materialRecord) * 16.0;
    visibilityInstancesOut[destination + 4u] = lighting;
    writeClassification(candidate, 4.0);
}
