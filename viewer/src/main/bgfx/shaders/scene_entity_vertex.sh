#include <bgfx_compute.sh>
#include "static_mesh_lod.sh"
#include "static_mesh_billboard.sh"
#include "tex_rotator.sh"
#include "water_vertex.sh"
#include "native_static_mesh_tangent.sh"
#include "soft_particle.sh"
#include "native_environment_color.sh"
#include "compact_static_mesh.sh"

uniform vec4 u_sceneVertexMaterial[3];
uniform vec4 u_sceneVertexFrame[7];
uniform vec4 u_nativeMeshDraw;
BUFFER_RO(u_actorLighting, uint, 11);
#include "actor_lighting_alpha.sh"
#include "scene_vertex_diffuse.sh"
#if defined(NATIVE_WATER_LIGHTING)
#include "native_water_lights.sh"
#include "native_vertex_lightmap.sh"
#include "authored_reflection.sh"
uniform vec4 u_nativeShaderMaps;
#endif

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
    float time = first.y;
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
#if defined(MESH_FRAME_BLEND)
    float frameBlend = i_data0.w;
    vec3 vertexPosition = mix(a_position, a_texcoord6.xyz, frameBlend);
    vec4 vertexNormal = vec4(mix(a_normal.xyz, a_texcoord7.xyz, frameBlend), a_normal.w);
#else
    vec3 vertexPosition = a_position;
    vec4 vertexNormal = a_normal;
#endif
    float lightingMarker = i_data4.w;
    bool terrainDecorationInstance = lightingMarker > 2.5 && lightingMarker < 4.5;
    bool meshDecorationInstance = lightingMarker > 5.5 && lightingMarker < 6.5;
    bool decorationInstance = terrainDecorationInstance || meshDecorationInstance;
    vec3 terrainDecorationSteady = decorationInstance
        ? vec3(i_data0.w, i_data1.w, i_data2.w) : vec3(0.0, 0.0, 0.0);
    vec4 staticMeshLod = decorationInstance
        ? vec4(0.0, 0.0, -1.0, 0.0)
        : vec4(i_data0.w, i_data1.w, i_data2.w, i_data3.w);
#if defined(MESH_FRAME_BLEND)
    staticMeshLod.x = 0.0;
#endif
    mat4 model = mtxFromCols(
        vec4(i_data0.xyz, 0.0), vec4(i_data1.xyz, 0.0),
        vec4(i_data2.xyz, 0.0), vec4(i_data3.xyz, 1.0)
    );
    vec4 world = mul(model, vec4(vertexPosition, 1.0));
    if (staticMeshUsesBillboard(staticMeshLod))
    {
#if defined(MESH_FRAME_BLEND)
        vec2 billboardTilt = vec2(0.0, 0.0);
#else
        vec2 billboardTilt = staticMeshBillboardTilt(staticMeshLod);
#endif
        world = staticMeshBillboardWorldPosition(
            vertexPosition, i_data0, i_data1, i_data2, i_data3,
            u_sceneVertexFrame[0].xy - i_data3.xy, billboardTilt
        );
    }
    vec4 projected = mul(u_viewProj, world);
    float lodCoverage = u_nativeMeshDraw.x == 1.0 ? 1.0 : staticMeshLodCoverage(
        u_sceneVertexFrame[0].xy, i_data3.xy,
        staticMeshLod, u_sceneVertexFrame[0].w
    );
    if (lodCoverage < 0.5
        || u_sceneVertexFrame[2].w > 0.5
        && instanceBeyondCullDistance(i_data0, i_data1, i_data2, i_data3))
    {
        projected = vec4(0.0, 0.0, -2.0, 1.0);
    }
    projected.z += u_sceneVertexMaterial[2].x * projected.w;
    gl_Position = projected;

    v_texcoord0 = animateUv(
        a_texcoord0,
        u_sceneVertexMaterial[0].x,
        u_sceneVertexMaterial[0],
        u_sceneVertexMaterial[1].xy
    );
    v_texcoord1 = vec4(vertexTexcoord1.xy, a_texcoord2.xy);
    vec3 bakedLighting = a_color1.xyz;
    vec3 instanceLighting = i_data4.rgb;
    float vertexVisibilityMask = vertexNormal.w;
    float vertexVisibility = sunVisibility(vertexVisibilityMask);
    vec3 hardwareSun = vec3(0.0, 0.0, 0.0);
    v_nativeSunColor = vec4_splat(0.0);
    bool hardwareSunPresent = false;
    v_nativeSunDirection = u_sceneVertexFrame[2].xyz;
    v_nativeSunNormal = i_data0.xyz * vertexNormal.x + i_data1.xyz * vertexNormal.y + i_data2.xyz * vertexNormal.z;
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
        uint localVertex = uint(gl_VertexID);
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
                u_sceneVertexFrame[1].rgb, u_sceneVertexFrame[3].w, u_sceneVertexMaterial[0].y).rgb * 255.0 + 0.5)
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
    vec3 worldNormal = transformedNormal(vertexNormal.xyz, i_data0, i_data1, i_data2);
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
        vec3 nativeWorldNormal = i_data0.xyz * vertexNormal.x
            + i_data1.xyz * vertexNormal.y + i_data2.xyz * vertexNormal.z;
        vec3 eyeTangent;
        vec3 lightTangent;
        waterVertexDirections(world.xyz, nativeWorldNormal, worldTangent, v_nativeSunDirection,
            eyeTangent, lightTangent);
        v_waterEye = vec4(eyeTangent, 1.0);
        v_waterLight = lightTangent;
    }
    bool nativeUseTangent = u_nativeMeshDraw.x == 1.0 ? u_nativeMeshDraw.y == 1.0
        : nativeStaticMeshUseTangent(staticMeshLod);
    if (nativeUseTangent)
    {
        vec3 eye;
        nativeStaticMeshTangentDirections(vertexNormal.xyz, vertexTangent, model, world.xyz,
            v_nativeSunDirection, eye, v_nativeSunNormal, v_nativeSunDirection);
        v_waterEye = vec4(eye, 2.0);
    }
    if (lightingMarker > 0.5 && lightingMarker < 2.5)
    {
        if (vertexVisibilityMask >= 0.0)
        {
            vec3 environment = nativeEnvironmentColorStream(vertexVisibilityMask, worldNormal);
            bakedLighting = u_nativeMeshDraw.x == 1.0
                ? nativeEnvironmentColorMerge(bakedLighting, environment, a_color0.rgb, u_nativeMeshDraw.z == 1.0)
                : min(bakedLighting + environment * a_color0.rgb, vec3(1.0, 1.0, 1.0));
            nativeSceneColor = true;
        }
        else if (packedVisibility)
        {
            vec3 environment = packedInstanceEnvironmentColorStream(i_data4.rgb, worldNormal);
            bakedLighting = u_nativeMeshDraw.x == 1.0
                ? nativeEnvironmentColorMerge(bakedLighting, environment, a_color0.rgb, u_nativeMeshDraw.z == 1.0)
                : min(bakedLighting + environment * a_color0.rgb, vec3(1.0, 1.0, 1.0));
        }
        else if (hardwareSunPresent)
        {
            if (u_sceneVertexMaterial[2].w > 0.5)
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
    bool nativeBspPlaneSide = vertexNormal.w < -1.5;
    v_texcoord2 = vec4(bakedLighting, nativeBspPlaneSide ? vertexTexcoord1.w : lightingSample);
    v_texcoord3 = world;
    v_texcoord4 = vec4(worldNormal, vertexTexcoord1.z);
    v_texcoord5 = nativeBspPlaneSide
        ? vec4(a_texcoord2.zw, a_color1.w, -2.0)
        : vec4(instanceLighting, lightingMarker);
    v_texcoord6 = projected;
    v_softDepth = vec3(softParticleDepthUv(projected), mul(u_view, world).z);
    v_texcoord7 = vec4(
        0.0, animatedAmbientGlow ? 1.0 : 0.0,
        nativeSceneColor ? 1.0 : 0.0,
        staticMeshUsesBillboard(staticMeshLod) ? 0.0 : 1.0
    );
    v_nativeDiffuse = a_color0;
    if (v_actorAmbientData.y > 0.5)
        v_nativeDiffuse = sceneVertexDiffuse(v_actorAmbientData, vec4(bakedLighting, color0Alpha),
            a_color0, nativeSceneColor, lightingMarker > 4.5 && lightingMarker < 5.5, u_sceneVertexFrame[3].rgb, u_sceneVertexFrame[4].rgb,
            u_sceneVertexFrame[1].rgb, u_sceneVertexFrame[3].w, u_sceneVertexMaterial[0].y);
    v_color0 = a_color0;
#if defined(NATIVE_WATER_LIGHTING)
    v_nativeLightmap0 = vec4(nativeVertexLightmap(uint(gl_VertexID), 0u), a_texcoord0.x);
    v_nativeLightmap1 = vec4(nativeVertexLightmap(uint(gl_VertexID), 1u), a_texcoord0.y);
    v_nativeLightmap2 = nativeVertexLightmap(uint(gl_VertexID), 2u);
    vec3 reflectionView = authoredReflection(mul(u_view, world).xyz,
        mul(u_view, vec4(mul(model, vec4(vertexNormal.xyz, 0.0)).xyz, 0.0)).xyz);
    vec3 reflectionWorld = mul(u_view, vec4(mul(model, vec4(reflectionView, 0.0)).xyz, 0.0)).xyz;
    v_texcoord3.w = reflectionWorld.x;
    v_texcoord6.z = reflectionWorld.y;
    if (u_nativeWaterLights[0].x == 1.0 && u_sceneVertexMaterial[2].w > 0.5)
    {
        bool normalMap = u_nativeShaderMaps.z == 1.0;
        vec3 nativeNormal = mul(model, vec4(vertexNormal.xyz, 0.0)).xyz;
        vec4 ambient = lightingMarker > 4.5 && lightingMarker < 5.5 ? vec4_splat(1.0)
            : selectStaticMeshAmbient(v_actorAmbientData, u_sceneVertexFrame[3].rgb,
                u_sceneVertexFrame[4].rgb, u_sceneVertexFrame[1].rgb,
                u_sceneVertexFrame[3].w, u_sceneVertexMaterial[0].y);
        v_nativeDiffuse = materialVertexDiffuse(vec4(bakedLighting, color0Alpha), a_color0, ambient,
            nativeWaterVertexPoints(world.xyz, normalize(nativeNormal)),
            u_nativeWaterLights[0].y == 1.0, normalMap, u_nativeWaterLights[0].z == 1.0);
        vec3 light0 = u_nativeWaterLights[5].xyz - world.xyz;
        vec3 light1 = u_nativeWaterLights[6].xyz - world.xyz;
        bool sun = u_nativeWaterLights[1].x == 1.0;
        bool point1 = u_nativeWaterLights[1].z == 1.0;
        bool point0 = u_nativeWaterLights[1].y == 1.0;
        v_nativeSunDirection = sun || point1
            ? mul(u_view, vec4(sun ? u_nativeWaterLights[4].xyz : light1, 0.0)).xyz : vec3_splat(0.0);
        v_texcoord7.x = sun ? 1.0 : (point1 ? length(light1) : 0.0);
        v_nativeSunColor = vec4_splat(0.0);
        v_waterLight = point0 ? mul(u_view, vec4(light0, 0.0)).xyz : vec3_splat(0.0);
        v_waterEye = vec4(u_nativeWaterLights[0].z == 1.0 || normalMap ? mul(u_view, world).xyz : vec3_splat(0.0),
            point0 ? length(light0) : 0.0);
        v_nativeSunNormal = mul(u_view, vec4(nativeNormal, 0.0)).xyz;
        if (nativeUseTangent && (u_nativeWaterLights[0].z == 1.0 || normalMap))
        {
            vec3 N = normalize(vertexNormal.xyz);
            vec3 T = normalize(vertexTangent.xyz);
            vec3 B = vertexTangent.w * normalize(cross(N, T));
            vec3 eye = v_waterEye.xyz;
            v_waterEye.xyz = normalize(vec3(dot(T, eye), dot(B, eye), dot(N, eye)));
            vec3 first = v_nativeSunDirection;
            if (sun)
            {
                vec3 direction = u_nativeWaterLights[4].xyz;
                first = vec3(dot(i_data0.xyz, direction), dot(i_data1.xyz, direction), dot(i_data2.xyz, direction));
            }
            v_nativeSunDirection = vec3(dot(T, first), dot(B, first), dot(N, first));
            vec3 second = v_waterLight;
            v_waterLight = vec3(dot(T, second), dot(B, second), dot(N, second));
        }
    }
#endif
}
