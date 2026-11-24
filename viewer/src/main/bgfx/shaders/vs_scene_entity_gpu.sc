$input a_position, a_normal, a_tangent, a_texcoord0, a_texcoord1, a_texcoord2, a_color1, a_color0, i_data0, i_data1, i_data2, i_data3, i_data4
$output v_texcoord0, v_texcoord1, v_texcoord2, v_texcoord3, v_texcoord4, v_texcoord5, v_texcoord6, v_texcoord7, v_color0, v_tangent, v_waterEye, v_waterLight, v_softDepth, v_actorAmbientData, v_nativeDiffuse, v_nativeSunColor, v_nativeSunNormal, v_nativeSunDirection

#include <bgfx_compute.sh>
#include "static_mesh_lod.sh"
#include "static_mesh_billboard.sh"
#include "tex_rotator.sh"
#include "water_vertex.sh"
#include "native_static_mesh_tangent.sh"
#include "soft_particle.sh"
#include "compact_static_mesh.sh"

BUFFER_RO(u_sceneMaterialTable, vec4, 10);
BUFFER_RO(u_actorLighting, uint, 11);
#include "actor_lighting_alpha.sh"
#include "scene_vertex_diffuse.sh"
uniform vec4 u_sceneVertexFrame[7];

bool hasActorVertexLighting(vec3 metadata)
{
    return metadata.y < -0.5;
}

uint actorLightingBase(vec3 metadata)
{
    return uint(metadata.x + 0.5) | (uint(-metadata.y - 0.5) << 16u);
}

vec3 unpackActorRgb(uint packed)
{
    return vec3(
        float(packed & 255u),
        float((packed >> 8u) & 255u),
        float((packed >> 16u) & 255u)
    ) / 255.0;
}

vec4 gpuMaterial(float record, int index)
{
    return u_sceneMaterialTable[int(record) * 18 + index];
}

bool instanceBeyondCullDistance(
    vec4 modelColumn0,
    vec4 modelColumn1,
    vec4 modelColumn2,
    vec4 modelColumn3
)
{
    vec3 localCenter = u_sceneVertexFrame[1].xyz;
    vec3 localExtent = u_sceneVertexFrame[2].xyz;
    vec3 center = modelColumn3.xyz
        + modelColumn0.xyz * localCenter.x
        + modelColumn1.xyz * localCenter.y
        + modelColumn2.xyz * localCenter.z;
    vec3 extent = abs(modelColumn0.xyz) * localExtent.x
        + abs(modelColumn1.xyz) * localExtent.y
        + abs(modelColumn2.xyz) * localExtent.z;
    vec3 camera = u_sceneVertexFrame[0].xyz;
    vec3 distanceToBox = max(
        max(camera - (center + extent), (center - extent) - camera),
        vec3(0.0, 0.0, 0.0)
    );
    return dot(distanceToBox, distanceToBox) > u_sceneVertexFrame[0].w;
}

vec3 transformedNormal(
    vec3 source,
    vec4 modelColumn0,
    vec4 modelColumn1,
    vec4 modelColumn2
)
{
    vec3 column0 = modelColumn0.xyz;
    vec3 column1 = modelColumn1.xyz;
    vec3 column2 = modelColumn2.xyz;
    vec3 cofactor0 = cross(column1, column2);
    vec3 cofactor1 = cross(column2, column0);
    vec3 cofactor2 = cross(column0, column1);
    float determinantSign = dot(column0, cofactor0) < 0.0 ? -1.0 : 1.0;
    return normalize(
        (cofactor0 * source.x + cofactor1 * source.y + cofactor2 * source.z)
            * determinantSign
    );
}

float threeHourSlot()
{
    return mod(u_sceneVertexFrame[1].w, 16.0);
}

float packedDecorationByte(vec3 packed, float slot)
{
    float value;
    float byteIndex;
    if (slot < 3.0)
    {
        value = packed.x;
        byteIndex = slot;
    }
    else if (slot < 6.0)
    {
        value = packed.y;
        byteIndex = slot - 3.0;
    }
    else
    {
        value = packed.z;
        byteIndex = slot - 6.0;
    }
    float divisor = byteIndex < 0.5 ? 1.0 : (byteIndex < 1.5 ? 256.0 : 65536.0);
    return mod(floor(value / divisor), 256.0) / 255.0;
}

float decorationTerrainShadow(vec3 packed)
{
    float slotTime = threeHourSlot();
    float currentSlot = floor(slotTime);
    float nextSlot = mod(currentSlot + 1.0, 8.0);
    return mix(
        packedDecorationByte(packed, currentSlot),
        packedDecorationByte(packed, nextSlot),
        fract(slotTime)
    );
}

float sunVisibility(float packedMask)
{
    if (packedMask < 0.0)
    {
        return -1.0;
    }
    float slotTime = threeHourSlot();
    float currentSlot = floor(slotTime);
    float nextSlot = mod(currentSlot + 1.0, 8.0);
    float currentVisible = mod(floor(packedMask / exp2(currentSlot)), 2.0);
    float nextVisible = mod(floor(packedMask / exp2(nextSlot)), 2.0);
    return mix(currentVisible, nextVisible, fract(slotTime));
}

vec3 nativeEnvironmentColorStream(
    float currentVisibility,
    float nextVisibility,
    vec3 worldNormal
)
{
    float fraction = fract(threeHourSlot());
    float directional = max(dot(worldNormal, u_sceneVertexFrame[2].xyz), 0.0);
    vec3 sun = max(u_sceneVertexFrame[1].xyz, vec3(0.0, 0.0, 0.0));
    vec3 currentByte = floor(clamp(
        sun * directional * currentVisibility * (1.0 - fraction),
        vec3(0.0, 0.0, 0.0), vec3(1.0, 1.0, 1.0)
    ) * 255.0);
    vec3 nextByte = floor(clamp(
        sun * directional * nextVisibility * fraction,
        vec3(0.0, 0.0, 0.0), vec3(1.0, 1.0, 1.0)
    ) * 255.0);
    return min(currentByte + nextByte, vec3(255.0, 255.0, 255.0)) / 255.0;
}

vec3 nativeEnvironmentColorStream(float packedMask, vec3 worldNormal)
{
    float slotTime = threeHourSlot();
    float currentSlot = floor(slotTime);
    float nextSlot = mod(currentSlot + 1.0, 8.0);
    return nativeEnvironmentColorStream(
        mod(floor(packedMask / exp2(currentSlot)), 2.0),
        mod(floor(packedMask / exp2(nextSlot)), 2.0),
        worldNormal
    );
}

float packedVisibilityNibble(float packed, float nibble)
{
    return mod(floor(packed / exp2(8.0 + nibble * 4.0)), 16.0);
}

bool hasPackedInstanceVisibility(vec3 packed)
{
    return abs(packedVisibilityNibble(packed.z, 2.0) - 10.0) < 0.5;
}

float packedInstanceVisibilitySlot(vec3 packed, float slot)
{
    if (slot < 3.0)
    {
        return packedVisibilityNibble(packed.x, slot) / 15.0;
    }
    if (slot < 6.0)
    {
        return packedVisibilityNibble(packed.y, slot - 3.0) / 15.0;
    }
    return packedVisibilityNibble(packed.z, slot - 6.0) / 15.0;
}

float packedInstanceVisibility(vec3 packed)
{
    float slotTime = threeHourSlot();
    float currentSlot = floor(slotTime);
    float nextSlot = mod(currentSlot + 1.0, 8.0);
    return mix(
        packedInstanceVisibilitySlot(packed, currentSlot),
        packedInstanceVisibilitySlot(packed, nextSlot),
        fract(slotTime)
    );
}

vec3 packedInstanceEnvironmentColorStream(vec3 packed, vec3 worldNormal)
{
    float slotTime = threeHourSlot();
    float currentSlot = floor(slotTime);
    float nextSlot = mod(currentSlot + 1.0, 8.0);
    return nativeEnvironmentColorStream(
        packedInstanceVisibilitySlot(packed, currentSlot),
        packedInstanceVisibilitySlot(packed, nextSlot),
        worldNormal
    );
}

bool hasPackedHardwareSun(vec3 packed)
{
    return abs(packedVisibilityNibble(packed.z, 2.0) - 11.0) < 0.5;
}

bool hasPackedFlatSun(vec3 packed)
{
    return abs(packedVisibilityNibble(packed.z, 2.0) - 12.0) < 0.5;
}

vec3 packedHardwareSunColor(vec3 packed)
{
    return vec3(
        packedVisibilityNibble(packed.x, 0.0) + packedVisibilityNibble(packed.x, 1.0) * 16.0,
        packedVisibilityNibble(packed.y, 0.0) + packedVisibilityNibble(packed.y, 1.0) * 16.0,
        packedVisibilityNibble(packed.z, 0.0) + packedVisibilityNibble(packed.z, 1.0) * 16.0
    ) / 255.0;
}

vec3 hardwareSunColorStream(vec3 color, vec3 worldNormal)
{
    return color * max(dot(worldNormal, u_sceneVertexFrame[2].xyz), 0.0);
}

vec3 flatSunAmbientStream()
{
    return floor(max(u_sceneVertexFrame[1].xyz, vec3(0.0, 0.0, 0.0)) * 32.0) / 255.0;
}

vec2 animateUv(vec2 uv, float mode, vec4 first, vec2 second)
{
    float time = max(-u_sceneVertexFrame[2].w, 0.0);
    if (mode == 1.0)
    {
        uv += first.zw * time;
    }
    else if (mode == 2.0)
    {
        uv = texRotatorYawUv(uv, first.zw, second.x * time);
    }
    else if (mode == 3.0)
    {
        uv.x += first.w * sin(6.2831853 * first.z * time);
        uv.y += second.y * sin(6.2831853 * second.x * time);
    }
    else if (mode == 4.0)
    {
        uv = uv / first.zw + second;
    }
    else if (mode == 5.0)
    {
        uv = texRotatorYawUv(uv, vec2(first.w, second.y), second.x * sin(6.2831853 * first.z * time));
    }
    return uv;
}

void main()
{
    bool compactStaticMesh = compactStaticMeshLayout(a_color1);
    vec4 vertexTexcoord1 = compactStaticMesh ? vec4_splat(0.0) : a_texcoord1;
    vec4 vertexTangent = compactStaticMesh ? vec4_splat(0.0) : a_tangent;
    float materialRecord = floor(i_data4.w / 16.0);
    float lightingMarker = i_data4.w - materialRecord * 16.0 - 8.0;
    vec4 vertexMaterial4 = gpuMaterial(materialRecord, 4);
    vec4 vertexMaterial5 = gpuMaterial(materialRecord, 5);
    vec4 vertexMaterial11 = gpuMaterial(materialRecord, 11);

    bool terrainDecorationInstance = lightingMarker > 2.5 && lightingMarker < 4.5;
    bool meshDecorationInstance = lightingMarker > 5.5 && lightingMarker < 6.5;
    bool decorationInstance = terrainDecorationInstance || meshDecorationInstance;
    vec3 terrainDecorationSteady = decorationInstance
        ? vec3(i_data0.w, i_data1.w, i_data2.w) : vec3(0.0, 0.0, 0.0);
    vec4 staticMeshLod = decorationInstance
        ? vec4(0.0, 0.0, -1.0, 0.0)
        : vec4(i_data0.w, i_data1.w, i_data2.w, i_data3.w);
    mat4 model = mtxFromCols(
        vec4(i_data0.xyz, 0.0), vec4(i_data1.xyz, 0.0),
        vec4(i_data2.xyz, 0.0), vec4(i_data3.xyz, 1.0)
    );
    vec4 world = mul(model, vec4(a_position, 1.0));
    if (staticMeshUsesBillboard(staticMeshLod))
    {
        world = staticMeshBillboardWorldPosition(
            a_position, i_data0, i_data1, i_data2, i_data3,
            u_sceneVertexFrame[0].xy - i_data3.xy, staticMeshBillboardTilt(staticMeshLod)
        );
    }
    vec4 projected = mul(u_viewProj, world);
    float lodCoverage = staticMeshLodCoverage(
        u_sceneVertexFrame[0].xy, i_data3.xy,
        staticMeshLod, u_sceneVertexFrame[0].w
    );
    if (lodCoverage < 0.5
        || u_sceneVertexFrame[2].w > 0.5
        && instanceBeyondCullDistance(i_data0, i_data1, i_data2, i_data3))
    {
        projected = vec4(0.0, 0.0, -2.0, 1.0);
    }
    projected.z += vertexMaterial11.x * projected.w;
    gl_Position = projected;

    v_texcoord0 = animateUv(
        a_texcoord0,
        vertexMaterial4.x,
        vertexMaterial4,
        vertexMaterial5.xy
    );
    v_texcoord1 = vec4(vertexTexcoord1.xy, a_texcoord2.xy);
    vec3 bakedLighting = a_color1.xyz;
    vec3 instanceLighting = i_data4.rgb;
    float vertexVisibilityMask = a_normal.w;
    float vertexVisibility = sunVisibility(vertexVisibilityMask);
    vec3 hardwareSun = vec3(0.0, 0.0, 0.0);
    v_nativeSunColor = vec4_splat(0.0);
    bool hardwareSunPresent = false;
    v_nativeSunDirection = u_sceneVertexFrame[2].xyz;
    v_nativeSunNormal = i_data0.xyz * a_normal.x + i_data1.xyz * a_normal.y + i_data2.xyz * a_normal.z;
    bool flatSun = false;
    bool actorLighting = hasActorVertexLighting(i_data4.rgb);
    v_actorAmbientData = vec3(1.0, 0.0, 0.0);
    float color0Alpha = 0.0;
    bool nativeSceneColor = false;
    bool animatedAmbientGlow = false;
    if (actorLighting)
    {
        uint actorBase = actorLightingBase(i_data4.rgb);
        v_actorAmbientData = actorAmbientData(actorBase);
        uint actorFlags = u_actorLighting[actorBase + 1u];
#if BGFX_SHADER_LANGUAGE_HLSL
        uint localVertex = uint(gl_VertexID);
#else
        uint localVertex = uint(gl_VertexID) - uint(i_data4.z + 0.5);
#endif
        uint actorVertex = 0u;
        if ((actorFlags & 3u) != 0u)
            actorVertex = u_actorLighting[actorBase + 3u + localVertex];
        instanceLighting = unpackActorRgb(u_actorLighting[actorBase]);
        if ((actorFlags & 1u) != 0u)
        {
            bakedLighting = unpackActorRgb(actorVertex);
        }
        if ((actorFlags & 2u) != 0u)
        {
            vertexVisibilityMask = float((actorVertex >> 24u) & 255u);
            vertexVisibility = sunVisibility(vertexVisibilityMask);
        }
        color0Alpha = actorLightingAlpha(actorBase, localVertex);
        nativeSceneColor = (actorFlags & 4u) != 0u;
        animatedAmbientGlow = (actorFlags & 16u) != 0u;
        if ((actorFlags & 32u) != 0u)
        {
            hardwareSunPresent = actorHardwareSunPresent(actorBase);
            hardwareSun = actorHardwareSun(actorBase, u_sceneVertexFrame[6].rgb);
            v_nativeSunDirection = actorHardwareSunDirection(actorBase, u_sceneVertexFrame[2].xyz, u_sceneVertexFrame[5].xyz);
        }
        flatSun = (actorFlags & 64u) != 0u;
    }
    else
    {
        if (hasPackedHardwareSun(i_data4.rgb))
        {
            hardwareSunPresent = true;
            hardwareSun = packedHardwareSunColor(i_data4.rgb);
        }
        flatSun = hasPackedFlatSun(i_data4.rgb);
    }
    if (terrainDecorationInstance)
    {
        bakedLighting = terrainDecorationSteady;
    }
    if (meshDecorationInstance)
    {
        vec3 meshAmbientByte = v_actorAmbientData.y > 0.5
            ? floor(selectStaticMeshAmbient(v_actorAmbientData, u_sceneVertexFrame[3].rgb, u_sceneVertexFrame[4].rgb,
                u_sceneVertexFrame[1].rgb, u_sceneVertexFrame[3].w, max(-u_sceneVertexFrame[2].w, 0.0)).rgb
                * 255.0 + 0.5)
            : vec3_splat(0.0);
        bakedLighting = min(floor(terrainDecorationSteady * 255.0 + 0.5) + meshAmbientByte, vec3_splat(255.0)) / 255.0;
    }
    bool packedVisibility = !actorLighting && hasPackedInstanceVisibility(i_data4.rgb);
    float lightingSample = lightingMarker > 2.5 && lightingMarker < 4.5
        ? decorationTerrainShadow(i_data4.rgb)
        : (vertexVisibility >= 0.0
            ? vertexVisibility
            : (packedVisibility
                ? packedInstanceVisibility(i_data4.rgb) : -1.0));
    vec3 worldNormal = transformedNormal(a_normal.xyz, i_data0, i_data1, i_data2);
    vec3 worldTangent = i_data0.xyz * vertexTangent.x
        + i_data1.xyz * vertexTangent.y + i_data2.xyz * vertexTangent.z;
    if (nativeStaticMeshTangentStream(staticMeshLod)) worldTangent = vec3_splat(0.0);
    float tangentLengthSquared = dot(worldTangent, worldTangent);
    v_tangent = tangentLengthSquared > 0.000000000001
        ? vec4(worldTangent * inversesqrt(tangentLengthSquared), 1.0)
        : vec4(0.0, 0.0, 0.0, 0.0);
    v_waterEye = vec4(0.0, 0.0, 0.0, 0.0);
    v_waterLight = vec3(0.0, 0.0, 0.0);
    if (!nativeStaticMeshTangentStream(staticMeshLod) && vertexTangent.w > 1.5)
    {
        vec3 nativeWorldNormal = i_data0.xyz * a_normal.x
            + i_data1.xyz * a_normal.y + i_data2.xyz * a_normal.z;
        vec3 eyeTangent;
        vec3 lightTangent;
        waterVertexDirections(world.xyz, nativeWorldNormal, worldTangent, v_nativeSunDirection,
            eyeTangent, lightTangent);
        v_waterEye = vec4(eyeTangent, 1.0);
        v_waterLight = lightTangent;
    }
    if (nativeStaticMeshUseTangent(staticMeshLod))
    {
        vec3 eye;
        nativeStaticMeshTangentDirections(a_normal.xyz, vertexTangent, model, world.xyz,
            v_nativeSunDirection, eye, v_nativeSunNormal, v_nativeSunDirection);
        v_waterEye = vec4(eye, 2.0);
    }
    if (lightingMarker > 0.5 && lightingMarker < 2.5)
    {
        if (vertexVisibilityMask >= 0.0)
        {
            bakedLighting = min(
                bakedLighting
                    + nativeEnvironmentColorStream(vertexVisibilityMask, worldNormal) * a_color0.rgb,
                vec3(1.0, 1.0, 1.0)
            );
            nativeSceneColor = true;
        }
        else if (packedVisibility)
        {
            bakedLighting = min(
                bakedLighting
                    + packedInstanceEnvironmentColorStream(i_data4.rgb, worldNormal) * a_color0.rgb,
                vec3(1.0, 1.0, 1.0)
            );
        }
        else if (hardwareSunPresent)
        {
            bool nativePass = mod(gpuMaterial(materialRecord, 2).x, 16.0) == 2.0
                && gpuMaterial(materialRecord, 12).x < 0.5 && gpuMaterial(materialRecord, 15).x > 2.5;
            if (nativePass)
                v_nativeSunColor = vec4(hardwareSun * 0.5, 1.0);
            else
            {
                bakedLighting = min(
                    bakedLighting
                        + hardwareSunColorStream(hardwareSun, worldNormal) * (nativeSceneColor ? 0.5 : 1.0),
                    vec3(1.0, 1.0, 1.0)
                );
            }
        }
        if (flatSun)
        {
            bakedLighting = min(
                bakedLighting * (nativeSceneColor ? 1.0 : 0.5)
                    + flatSunAmbientStream() * a_color0.rgb,
                vec3(1.0, 1.0, 1.0)
            );
            nativeSceneColor = true;
        }
    }
    bool nativeBspPlaneSide = a_normal.w < -1.5;
    v_texcoord2 = vec4(bakedLighting, nativeBspPlaneSide ? vertexTexcoord1.w : lightingSample);
    v_texcoord3 = world;
    v_texcoord4 = vec4(worldNormal, vertexTexcoord1.z);
    v_texcoord5 = nativeBspPlaneSide
        ? vec4(a_texcoord2.zw, a_color1.w, -2.0)
        : vec4(instanceLighting, lightingMarker);
    v_texcoord6 = projected;
    v_softDepth = vec3(softParticleDepthUv(projected), mul(u_view, world).z);
    v_texcoord7 = vec4(
        materialRecord, animatedAmbientGlow ? 1.0 : 0.0,
        nativeSceneColor ? 1.0 : 0.0,
        staticMeshUsesBillboard(staticMeshLod) ? 0.0 : 1.0
    );
    v_nativeDiffuse = a_color0;
    if (v_actorAmbientData.y > 0.5)
        v_nativeDiffuse = sceneVertexDiffuse(v_actorAmbientData, vec4(bakedLighting, color0Alpha),
            a_color0, nativeSceneColor, lightingMarker > 4.5 && lightingMarker < 5.5, u_sceneVertexFrame[3].rgb, u_sceneVertexFrame[4].rgb,
            u_sceneVertexFrame[1].rgb, u_sceneVertexFrame[3].w, max(-u_sceneVertexFrame[2].w, 0.0));
    v_color0 = a_color0;
}
