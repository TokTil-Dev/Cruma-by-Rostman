#if defined(ENTITY_VARIANT_GPU_TABLE)
#include <bgfx_compute.sh>
#else
#include <bgfx_shader.sh>
#endif
#include "hf_depth_pack.sh"
#include "soft_particle.sh"
#include "bitmap_sampling.sh"
#include "static_mesh_ambient.sh"
#include "native_static_mesh_sun.sh"
#include "height_cut.sh"
#if defined(ENTITY_VARIANT_WATER)
#include "native_water_lights.sh"
#endif

float packedVisibilityNibble(float packed, float nibble)
{
    return mod(floor(packed / exp2(8.0 + nibble * 4.0)), 16.0);
}

bool hasPackedInstanceVisibility(vec3 packed)
{
    return abs(packedVisibilityNibble(packed.z, 2.0) - 10.0) < 0.5;
}

bool hasPackedInstanceLighting(vec3 packed)
{
    float signature = packedVisibilityNibble(packed.z, 2.0);
    return signature > 9.5 && signature < 12.5;
}

vec3 unpackInstanceLight(vec3 packed)
{
    return mod(floor(packed), vec3(256.0, 256.0, 256.0)) / 255.0;
}

SAMPLER2DARRAY(s_sceneDiffuse, 0);
#if defined(ENTITY_VARIANT_COMPLEX) || defined(ENTITY_VARIANT_WATER)
#define BEAST_LIGHTMAP_LAYER -100000.0
#endif
#if defined(ENTITY_VARIANT_GPU_PAGED_DIFFUSE)
SAMPLER2DARRAY(s_sceneDiffuseAlt, 1);
SAMPLER2DARRAY(s_sceneDiffuseAlt2, 2);
SAMPLER2DARRAY(s_sceneDiffuseAlt3, 3);
#endif
#if defined(ENTITY_VARIANT_COMPLEX)
SAMPLER2DARRAY(s_sceneOpacity, 1);
SAMPLER2DARRAY(s_sceneSecond, 2);
SAMPLER2DARRAY(s_sceneMask, 3);
SAMPLER2DARRAY(s_sceneSpecular, 4);
SAMPLER2DARRAY(s_sceneSpecMask, 5);
SAMPLER2DARRAY(s_sceneLightmap, 6);
SAMPLERCUBE(s_sceneCube, 7);
SAMPLER2DARRAY(s_sceneSelfIllumination, 12);
SAMPLER2DARRAY(s_sceneSelfIlluminationMask, 13);
#elif defined(ENTITY_VARIANT_CSG)
SAMPLER2DARRAY(s_sceneLightmap, 6);
#elif defined(ENTITY_VARIANT_WATER)
#include "authored_material.sh"
#include "authored_passes.sh"
SAMPLER2D(s_sceneMask, 3);
SAMPLER2DARRAY(s_sceneLightmap, 6);
SAMPLER2D(s_sceneReflection, 9);
SAMPLER2D(s_sceneBack, 10);
SAMPLER2DARRAY(s_nativeNormalMap, 14);
SAMPLER2DARRAY(s_nativeSpecularMap, 15);
uniform vec4 u_nativeShaderMaps;
#endif
SAMPLER2D(s_sceneDepth, 8);

#if defined(ENTITY_VARIANT_GPU_TABLE)
BUFFER_RO(u_sceneMaterialTable, vec4, 10);
#else
uniform vec4 u_sceneMaterial[18];
#endif
uniform vec4 u_sceneFrame[18];

bool sceneUnlit()
{
    return u_sceneFrame[10].w > 0.5;
}

#define sceneBitmapArray(_sampler, _uv) bitmapArrayBias(_sampler, _uv, u_sceneFrame[7].w)
#define sceneBitmapCube(_sampler, _uv) textureCubeBias(_sampler, _uv, u_sceneFrame[7].w)
#define sceneBitmap2D(_sampler, _uv) texture2DBias(_sampler, _uv, u_sceneFrame[7].w)

#if defined(ENTITY_VARIANT_GPU_TABLE)
vec4 readSceneMaterial(int index, float materialRecord)
{
    return u_sceneMaterialTable[int(materialRecord) * 18 + index];
}
#define sceneMaterial(_index) readSceneMaterial(_index, materialRecord)
#else
#define sceneMaterial(_index) u_sceneMaterial[_index]
#endif

float sceneAnimationTime(float materialTime)
{
#if defined(ENTITY_VARIANT_GPU_TABLE)
    return max(-u_sceneFrame[6].w, 0.0);
#else
    return materialTime;
#endif
}

#if defined(ENTITY_VARIANT_COMPLEX)
#include "tex_rotator.sh"
#include "authored_reflection.sh"
vec2 meshUvUnderSurfaceTransform(vec2 uv, vec4 first, vec2 second, float time)
{
    float mode = first.x;
    if (mode == 1.0)
    {
        return uv - first.zw * time;
    }
    else if (mode == 2.0)
    {
        return texRotatorYawUv(uv, first.zw, -second.x * time);
    }
    else if (mode == 3.0)
    {
        uv.x -= first.w * sin(6.2831853 * first.z * time);
        uv.y -= second.y * sin(6.2831853 * second.x * time);
        return uv;
    }
    else if (mode == 4.0)
    {
        return (uv - second) * first.zw;
    }
    else if (mode == 5.0)
    {
        return texRotatorYawUv(uv, vec2(first.w, second.y), -second.x * sin(6.2831853 * first.z * time));
    }
    return uv;
}

vec2 animateOverlayUv(vec2 uv, float mode, float time, vec4 parameters, float roll)
{
    if (mode == 1.0)
    {
        uv += parameters.xy * time;
    }
    else if (mode == 2.0)
    {
        uv = texRotatorUv(uv, parameters.xy, vec3(parameters.w, parameters.z, roll) * time);
    }
    else if (mode == 3.0)
    {
        uv += parameters.yw * sin(6.2831853 * parameters.xz * time);
    }
    else if (mode == 4.0)
    {
        uv = uv / parameters.xy + parameters.zw;
    }
    else if (mode == 5.0)
    {
        uv = texRotatorYawUv(
            uv, vec2(parameters.y, parameters.w), parameters.z * sin(6.2831853 * parameters.x * time));
    }
    return uv;
}

vec3 environmentLayerVector(float source, vec3 worldPosition, vec3 worldNormal)
{
    if (source < 1.5) return authoredSpecularPoint(vec3_splat(0.0));
    vec3 viewNormal = mul(u_view, vec4(worldNormal, 0.0)).xyz;
    if (source > 3.5)
    {
        vec3 reflection = fixedFunctionReflection(mul(u_view, vec4(worldPosition, 1.0)).xyz, viewNormal);
        if (source > 5.5) return vec3(fixedFunctionSphereMap(reflection), 0.0);
        if (source > 4.5) return fixedFunctionWorldReflection(reflection, u_view);
        return reflection;
    }
    if (source > 2.5) return vec3(authoredSphereMap(viewNormal), 1.0);
    return authoredReflection(mul(u_view, vec4(worldPosition, 1.0)).xyz, viewNormal);
}

vec4 premultipliedTarget(vec4 value, bool premultiply)
{
    return premultiply ? vec4(value.rgb * value.a, value.a) : value;
}

vec3 cubeSpecularLookup(float source, vec3 worldPosition, vec3 worldNormal, vec4 rotation)
{
    vec3 cubeNormal = normalize(worldNormal);
    vec3 viewNormal = normalize(mul(u_view, vec4(cubeNormal, 0.0)).xyz);
    vec3 viewPosition = normalize(mul(u_view, vec4(worldPosition, 1.0)).xyz);
    vec3 cameraPosition = mul(u_invView, vec4(0.0, 0.0, 0.0, 1.0)).xyz;
    vec3 lookup = source > 7.5
        ? reflect(normalize(worldPosition - cameraPosition), cubeNormal)
        : reflect(viewPosition, viewNormal);
    vec3 doubledCross = 2.0 * cross(rotation.xyz, lookup);
    return lookup + rotation.w * doubledCross + cross(rotation.xyz, doubledCross);
}
#endif

void main()
{
    float materialRecord = 0.0;
#if defined(ENTITY_VARIANT_GPU_TABLE)
    materialRecord = floor(v_texcoord7.x + 0.5);
#endif
    if ((u_sceneFrame[1].x > 0.5
            && dot(vec4(v_texcoord3.xyz, 1.0), u_sceneFrame[0]) < 0.0)
        || heightCutRejects(u_sceneFrame[16], u_sceneFrame[17], v_texcoord3.xyz))
    {
        discard;
    }
#if defined(ENTITY_VARIANT_CSG)
    if (v_texcoord5.w < -1.5
        && dot(v_texcoord5.xyz, u_sceneFrame[4].xyz) - v_texcoord2.w <= 0.0)
    {
        discard;
    }
#endif
    float encodedKind = sceneMaterial(2).x;
#if defined(ENTITY_VARIANT_GPU_TABLE)
    bool gpuFoliage = encodedKind > 15.5;
    float kind = mod(encodedKind, 16.0);
#else
    bool gpuFoliage = false;
    float kind = encodedKind;
#endif
    vec2 diffuseUv = v_texcoord0;
    if (kind == 4.0)
    {
        vec3 mapNormal = normalize(v_texcoord4.xyz);
        diffuseUv = mapNormal.xy * 0.5 + vec2(0.5, 0.5);
    }

    float blendCode = sceneMaterial(3).y;
    bool meshParticle = u_sceneFrame[12].w < -0.5;
    bool softParticle = meshParticle && u_sceneFrame[12].y > 0.5;
    float particleBlendCode = meshParticle ? u_sceneFrame[12].x : blendCode;
    bool emitterOverridesMeshMaterial = meshParticle && particleBlendCode >= 0.0;
    if (emitterOverridesMeshMaterial)
    {
        blendCode = particleBlendCode;
    }
    float maskedMinification = 0.0;
    float maskedLodBias = 0.0;
    float maskedMip = 0.0;
#if defined(ENTITY_VARIANT_ORDINARY)
    bool maskedFoliage = sceneMaterial(2).y > 0.0;
    vec2 maskedTextureExtent = max(abs(sceneMaterial(2).yz), vec2(1.0, 1.0));
#else
    bool maskedFoliage = sceneMaterial(15).z > 0.5;
    vec2 maskedTextureExtent = max(sceneMaterial(15).xy, vec2(1.0, 1.0));
#endif
    if (blendCode == 7.0 || blendCode == 9.0)
    {
        vec2 maskedDx = dFdx(diffuseUv) * maskedTextureExtent;
        vec2 maskedDy = dFdy(diffuseUv) * maskedTextureExtent;
        float maskedFootprint = max(length(maskedDx), length(maskedDy));
        maskedMip = log2(max(maskedFootprint, 1.0));

        if (maskedFoliage)
        {
            maskedLodBias = -clamp(maskedMip - 1.0, 0.0, 1.5);
        }

        maskedMinification = clamp(maskedMip, 0.0, 1.0);
    }

    float encodedDiffuseLayer = sceneMaterial(3).w;
#if defined(ENTITY_VARIANT_GPU_PAGED_DIFFUSE)
    bool alternateDiffusePage = encodedDiffuseLayer < 0.0;
    float encodedAlternateLayer = alternateDiffusePage
        ? -encodedDiffuseLayer - 1.0 : 0.0;
    float diffusePage = alternateDiffusePage
        ? floor(encodedAlternateLayer / 2048.0) : 0.0;
    float diffuseLayer = alternateDiffusePage
        ? encodedAlternateLayer - diffusePage * 2048.0 : encodedDiffuseLayer;
    vec4 texel;
    if (diffusePage > 2.5)
    {
        texel = maskedLodBias < -0.0001
            ? texture2DArrayLod(
                s_sceneDiffuseAlt3,
                vec3(diffuseUv, diffuseLayer),
                max(maskedMip + maskedLodBias, 0.0)
            )
            : sceneBitmapArray(s_sceneDiffuseAlt3, vec3(diffuseUv, diffuseLayer));
    }
    else if (diffusePage > 1.5)
    {
        texel = maskedLodBias < -0.0001
            ? texture2DArrayLod(
                s_sceneDiffuseAlt2,
                vec3(diffuseUv, diffuseLayer),
                max(maskedMip + maskedLodBias, 0.0)
            )
            : sceneBitmapArray(s_sceneDiffuseAlt2, vec3(diffuseUv, diffuseLayer));
    }
    else if (diffusePage > 0.5)
    {
        texel = maskedLodBias < -0.0001
            ? texture2DArrayLod(
                s_sceneDiffuseAlt,
                vec3(diffuseUv, diffuseLayer),
                max(maskedMip + maskedLodBias, 0.0)
            )
            : sceneBitmapArray(s_sceneDiffuseAlt, vec3(diffuseUv, diffuseLayer));
    }
    else
    {
        texel = maskedLodBias < -0.0001
            ? texture2DArrayLod(
                s_sceneDiffuse,
                vec3(diffuseUv, diffuseLayer),
                max(maskedMip + maskedLodBias, 0.0)
            )
            : sceneBitmapArray(s_sceneDiffuse, vec3(diffuseUv, diffuseLayer));
    }
#else
    float diffuseLayer = encodedDiffuseLayer;
    vec4 texel = maskedLodBias < -0.0001
        ? texture2DArrayLod(
            s_sceneDiffuse,
            vec3(diffuseUv, diffuseLayer),
            max(maskedMip + maskedLodBias, 0.0)
        )
        : sceneBitmapArray(s_sceneDiffuse, vec3(diffuseUv, diffuseLayer));
#endif
    vec3 color = texel.rgb;
    float alpha = texel.a;
    float nativeWaterRoute = 0.0;
    bool nativeMaterialPass = false;
#if defined(ENTITY_VARIANT_WATER)
    bool nativeMaterialNormalWritten = false;
    vec3 nativeMaterialNormal = vec3_splat(0.0);
#endif
    if (kind == 1.0)
    {
        color = sceneMaterial(0).rgb;
        alpha = sceneMaterial(0).a;
    }
#if defined(ENTITY_VARIANT_WATER)
    else if (kind == 2.0)
    {
        nativeWaterRoute = sceneMaterial(12).x;
        alpha = sceneMaterial(7).x;
        nativeMaterialPass = nativeWaterRoute < 0.5 && sceneMaterial(15).x > 2.5;
        if (nativeMaterialPass)
        {
            bool nativeLights = u_nativeWaterLights[0].x == 1.0;
            vec4 diffuse = nativeLights ? v_nativeDiffuse
                : nativeStaticMeshSunDiffuse(v_nativeDiffuse, v_nativeSunColor.rgb,
                    v_nativeSunNormal, v_nativeSunDirection);
            vec3 nativeSpecular = vec3_splat(0.0);
            if (nativeLights)
            {
                vec3 normal = u_nativeWaterLights[0].z == 1.0
                    ? normalize(v_nativeSunNormal) : v_nativeSunNormal;
                vec3 reflection = normalize(reflect(v_waterEye.xyz, normal));
                if (u_nativeShaderMaps.z == 1.0)
                {
                    vec2 primaryUv = vec2(v_nativeLightmap0.w, v_nativeLightmap1.w);
                    vec3 sampleColor = sceneBitmapArray(s_nativeNormalMap,
                        vec3(primaryUv, u_nativeShaderMaps.x)).rgb;
                    nativeStaticMeshNormalLighting(sampleColor, v_waterEye.xyz, v_nativeLightmap0.xyz,
                        v_nativeLightmap1.xyz, v_nativeLightmap2, u_authoredPasses[6].w,
                        diffuse.rgb, nativeSpecular, normal, reflection);
                }
                if (u_nativeWaterLights[0].z == 1.0)
                    nativeStaticMeshDynamicLighting(diffuse.rgb, nativeSpecular, normal, reflection,
                    u_authoredPasses[6].w, u_nativeWaterLights[2].xyz, u_nativeWaterLights[3].rgb,
                    vec4(v_nativeSunDirection, v_texcoord7.x), vec4(v_waterLight, v_waterEye.w),
                    u_nativeWaterLights[17].rgb, u_nativeWaterLights[18].rgb,
                    vec2(u_nativeWaterLights[19].z, u_nativeWaterLights[20].z));
                nativeMaterialNormal = normal;
                nativeMaterialNormalWritten = true;
            }
            bool firstPass = u_authoredPasses[0].y < 0.5;
            bool modulate2x = v_texcoord5.w < 4.5 || v_texcoord5.w > 5.5;
            float materialFactor = u_authoredPasses[10].w > 0.5
                ? u_authoredPasses[10].w : (modulate2x ? 2.0 : 1.0);
            float colorModifierFactor = u_authoredPasses[12].w > 0.5
                ? u_authoredPasses[12].w : (modulate2x ? 2.0 : 1.0);
            if (sceneUnlit())
            {
                diffuse.rgb = vec3_splat(1.0 / max(materialFactor, 0.0001));
                colorModifierFactor = 1.0;
            }
            if (firstPass && (!nativeLights || u_nativeWaterLights[0].w == 1.0)
                && !sceneUnlit()
                && sceneMaterial(11).y > 0.5 && v_texcoord1.x >= 0.0
                && sceneMaterial(11).z <= BEAST_LIGHTMAP_LAYER + 0.5)
            {
                float base = BEAST_LIGHTMAP_LAYER - sceneMaterial(11).z;
                diffuse.rgb += u_sceneFrame[8].w * sceneBitmapArray(s_sceneLightmap, vec3(v_texcoord1.zw, base)).rgb
                    + u_sceneFrame[9].w * sceneBitmapArray(s_sceneLightmap, vec3(v_texcoord1.zw, base + 1.0)).rgb;
            }
            bool selectedSun = !nativeLights && v_nativeSunColor.a == 1.0;
            float sunHighlight = 0.0;
            if (selectedSun)
            {
                vec3 eye = v_waterEye.w > 1.5 ? v_waterEye.xyz
                    : v_texcoord3.xyz - mul(u_invView, vec4(0.0, 0.0, 0.0, 1.0)).xyz;
                vec3 reflection = normalize(reflect(eye, normalize(v_nativeSunNormal)));
                sunHighlight = nativeStaticMeshHighlight(normalize(v_nativeSunDirection), reflection,
                    u_authoredPasses[6].w);
            }
            vec4 cameraPoint = vec4_splat(0.0);
            if (mod(floor(u_authoredPasses[14].w / 65536.0), 2.0) < 0.5)
            {
                if (nativeLights && (u_nativeWaterLights[0].z == 1.0 || u_nativeShaderMaps.z == 1.0))
                    cameraPoint = vec4(authoredSpecularPoint(nativeSpecular), 1.0);
                else if (selectedSun)
                    cameraPoint = vec4(authoredSpecularPoint(vec3_splat(sunHighlight)), 1.0);
            }
            float specularMask;
            vec4 authored = authoredPassMaterial(diffuseUv, vec2(v_texcoord3.w, v_texcoord6.z),
                mul(u_view, vec4(v_texcoord3.xyz, 1.0)).xyz, mul(u_view, vec4(v_texcoord4.xyz, 0.0)).xyz,
                u_sceneFrame[7].w, diffuse, materialFactor, cameraPoint, specularMask);
            if (firstPass && u_authoredPasses[4].w > 0.5)
                authored *= vec4(u_authoredPasses[2].rgb * colorModifierFactor, u_authoredPasses[2].a);
            if (nativeLights && !sceneUnlit())
            {
                vec3 highlightColor = vec3_splat(specularMask);
                if (u_nativeShaderMaps.w == 1.0)
                    highlightColor = sceneBitmapArray(s_nativeSpecularMap,
                        vec3(v_nativeLightmap0.w, v_nativeLightmap1.w, u_nativeShaderMaps.y)).rgb;
                authored.rgb += nativeSpecular * highlightColor * u_authoredPasses[8].w;
            }
            else if (selectedSun && !sceneUnlit())
            {
                authored.rgb += vec3_splat(sunHighlight * specularMask * u_authoredPasses[8].w);
            }
            color = authored.rgb;
            alpha = authored.a;
        }
        else if (nativeWaterRoute < 0.5 && sceneMaterial(15).x > 1.5)
        {
            vec4 authored = authoredMaterial(diffuseUv, u_sceneFrame[7].w, v_color0);
            color = authored.rgb;
            alpha = authored.a;
        }
        else if (sceneMaterial(15).x > 0.5)
        {
            vec2 uvA = diffuseUv;
            float rotation = sceneMaterial(15).y;
            if (abs(rotation) > 0.000001)
            {
                float cosine = cos(rotation);
                float sine = sin(rotation);
                uvA = vec2(
                    uvA.x * cosine - uvA.y * sine,
                    uvA.x * sine + uvA.y * cosine
                );
            }
            float mode = sceneMaterial(15).z;
            float time = sceneAnimationTime(sceneMaterial(15).w);
            if (mode == 1.0)
            {
                uvA += sceneMaterial(13).xy * time;
            }
            else if (mode == 3.0)
            {
                uvA.x += sceneMaterial(13).y
                    * sin(6.2831853 * sceneMaterial(13).x * time);
                uvA.y += sceneMaterial(13).w
                    * sin(6.2831853 * sceneMaterial(13).z * time);
            }
            vec4 oceanA = sceneBitmapArray(s_sceneDiffuse, vec3(uvA, diffuseLayer));
            vec3 combined = clamp(
                oceanA.rgb * texel.rgb + sceneMaterial(7).yzw,
                0.0, 1.0
            );
            color = clamp(
                combined + (oceanA.a * alpha) * texel.rgb,
                0.0, 1.0
            );
        }
        else
        {
            color = clamp(color + sceneMaterial(7).yzw, 0.0, 1.0);
        }
        if (nativeWaterRoute > 0.5)
        {
            vec2 screenUv = v_texcoord6.xy
                / max(abs(v_texcoord6.w), 0.00001) * vec2(0.5, -0.5) + vec2(0.5, 0.5);
            if (nativeWaterRoute > 1.5)
            {
                vec3 legacyCamera = mul(u_view, vec4(v_texcoord3.xyz, 1.0)).xyz;
                float legacyDepth = max(abs(legacyCamera.z), 0.00001);
                float viewportWidth = max(u_viewRect.z, 1.0);
                float viewportHeight = max(u_viewRect.w, 1.0);
                float legacyXAspect = clamp(
                    ((viewportHeight / viewportWidth) * 4.0) / 3.0, 0.2, 1.0
                );
                float legacyYAspect = clamp(
                    viewportWidth / viewportHeight, 0.26666668, 1.3333334
                );
                screenUv = vec2(
                    1.08000004 * legacyXAspect * legacyCamera.x / legacyDepth + 0.5,
                    -1.08000004 * legacyYAspect * legacyCamera.y / legacyDepth + 0.504999995
                );
                vec2 displacement = vec2(0.0, 0.0);
                if (sceneMaterial(12).w > 0.5)
                {
                    vec2 bump = clamp(
                        (sceneBitmap2D(s_sceneMask, diffuseUv).rg * 255.0 - vec2(128.0, 128.0))
                            / 127.0,
                        vec2(-1.0, -1.0),
                        vec2(1.0, 1.0)
                    );
                    float bumpCos = sceneMaterial(13).x;
                    float bumpSin = sceneMaterial(13).y;
                    vec2 texBem = vec2(
                        bump.x * bumpCos + bump.y * bumpSin,
                        bump.x * bumpSin + bump.y * bumpCos
                    );
                    displacement = texBem;
                }
                vec2 reflectionScale = u_sceneFrame[2].zw;
                vec2 reflectionHalfTexel = vec2(u_sceneFrame[3].x, u_sceneFrame[3].z);
                vec2 mirroredUv = vec2(1.0, 1.0)
                    - abs(fract((screenUv + displacement) * 0.5) * 2.0 - vec2(1.0, 1.0));
                vec2 reflectionUv = renderTargetSampleUv(
                    mirroredUv, reflectionScale, reflectionHalfTexel, u_sceneFrame[1].y, u_sceneFrame[13].w
                );
                vec3 reflectedScene = texture2D(s_sceneReflection, reflectionUv).rgb;
                color = mix(
                    reflectedScene,
                    sceneMaterial(14).rgb,
                    clamp(sceneMaterial(12).y, 0.0, 1.0)
                );
                alpha = sceneMaterial(14).a;
            }
            else
            {
                float waterTime = sceneAnimationTime(sceneMaterial(4).y);
                vec2 normalBias = vec2(
                    fract((waterTime * 0.015) / 1024.0) * 1024.0,
                    fract((waterTime * 0.025) / 1024.0) * 1024.0
                );
                vec2 normalUv0 = vec2(
                    (diffuseUv.x + normalBias.x) * 2.0,
                    diffuseUv.y * 2.0
                );
                vec2 normalUv1 = vec2(
                    (diffuseUv.y + normalBias.y) * 2.0,
                    diffuseUv.x * 2.0
                );
                vec3 normalSample0 = sceneMaterial(12).w > 0.5
                    ? sceneBitmap2D(s_sceneMask, normalUv0).rgb * 2.0 - 1.0
                    : vec3(0.0, 0.0, 1.0);
                vec3 normalSample1 = sceneMaterial(12).w > 0.5
                    ? sceneBitmap2D(s_sceneMask, normalUv1).rgb * 2.0 - 1.0
                    : vec3(0.0, 0.0, 1.0);
                vec3 waterNormal = normalize(normalSample0 + normalSample1);

                vec3 eyeTangent;
                vec3 lightTangent;
                vec3 viewPosition = mul(u_view, vec4(v_texcoord3.xyz, 1.0)).xyz;
                if (v_waterEye.w > 0.5 && v_waterEye.w < 1.5)
                {
                    eyeTangent = normalize(v_waterEye.xyz);
                    lightTangent = normalize(v_waterLight);
                }
                else
                {
                    vec3 worldPosition = v_texcoord3.xyz;
                    vec3 baseWorldNormal = normalize(v_texcoord4.xyz);
                    vec3 tangentWorld;
                    vec3 binormalWorld;
                    if (v_tangent.w > 0.5)
                    {
                        tangentWorld = normalize(v_tangent.xyz);
                        binormalWorld = normalize(cross(baseWorldNormal, tangentWorld));
                    }
                    else
                    {
                        vec3 positionDx = dFdx(worldPosition);
                        vec3 positionDy = dFdy(worldPosition);
                        vec2 uvDx = dFdx(diffuseUv);
                        vec2 uvDy = dFdy(diffuseUv);
                        float tangentDeterminant = uvDx.x * uvDy.y - uvDx.y * uvDy.x;
                        if (abs(tangentDeterminant) > 0.0000001)
                        {
                            tangentWorld = normalize(
                                (positionDx * uvDy.y - positionDy * uvDx.y) / tangentDeterminant
                            );
                            binormalWorld = normalize(cross(baseWorldNormal, tangentWorld));
                        }
                        else
                        {
                            vec3 fallbackAxis = abs(baseWorldNormal.z) < 0.999
                                ? vec3(0.0, 0.0, 1.0) : vec3(0.0, 1.0, 0.0);
                            tangentWorld = normalize(cross(fallbackAxis, baseWorldNormal));
                            binormalWorld = normalize(cross(baseWorldNormal, tangentWorld));
                        }
                    }
                    vec3 tangentView = normalize(mul(u_view, vec4(tangentWorld, 0.0)).xyz);
                    vec3 binormalView = normalize(mul(u_view, vec4(binormalWorld, 0.0)).xyz);
                    vec3 normalView = normalize(mul(u_view, vec4(baseWorldNormal, 0.0)).xyz);
                    eyeTangent = normalize(vec3(
                        dot(tangentView, viewPosition),
                        dot(binormalView, viewPosition),
                        dot(normalView, viewPosition)
                    ));
                    vec3 lightView = normalize(mul(
                        u_view, vec4(normalize(u_sceneFrame[7].xyz), 0.0)
                    ).xyz);
                    lightTangent = normalize(vec3(
                        dot(tangentView, lightView),
                        dot(binormalView, lightView),
                        dot(normalView, lightView)
                    ));
                }
                float diffuseIntensity = clamp(dot(lightTangent, waterNormal), 0.0, 1.0);
                float fresnel = 0.02037 + 0.97963 * pow(
                    1.0 - dot(vec3(0.0, 0.0, 1.0), -eyeTangent), 5.0
                );

                vec2 depthScale = u_sceneFrame[1].zw;
                vec2 depthHalfTexel = u_sceneFrame[2].xy;
                bool retainedBack = u_sceneFrame[14].z > 0.0;
                vec2 backScale = retainedBack ? vec2_splat(1.0) : depthScale;
                vec2 backHalfTexel = retainedBack ? u_sceneFrame[14].xy : depthHalfTexel;
                vec2 screenHalfPixel = retainedBack ? u_sceneFrame[14].zw : depthHalfTexel / depthScale;
                vec2 originalScreenUv = screenUv + screenHalfPixel;
                vec2 refractedScreenUv = originalScreenUv + waterNormal.xy * 0.02;
                vec2 originalBackUv = renderTargetSampleUv(
                    originalScreenUv, backScale, backHalfTexel, u_sceneFrame[1].y, u_sceneFrame[13].w
                );
                vec2 refractionUv = renderTargetSampleUv(
                    refractedScreenUv, backScale, backHalfTexel, u_sceneFrame[1].y, u_sceneFrame[13].w
                );
                vec2 originalDepthUv = renderTargetSampleUv(
                    fract(originalScreenUv), depthScale, depthHalfTexel, u_sceneFrame[1].y, 0.0
                );
                vec2 refractedDepthUv = renderTargetSampleUv(
                    fract(refractedScreenUv), depthScale, depthHalfTexel, u_sceneFrame[1].y, 0.0
                );
                vec2 reflectionScale = u_sceneFrame[2].zw;
                vec2 reflectionHalfTexel = vec2(u_sceneFrame[3].x, u_sceneFrame[3].z);
                vec2 reflectionUv = renderTargetSampleUv(
                    originalScreenUv + waterNormal.xy * 0.05, reflectionScale,
                    reflectionHalfTexel, u_sceneFrame[1].y, u_sceneFrame[13].w
                );

                vec3 originalBack = texture2D(s_sceneBack, originalBackUv).rgb;
                vec3 refractedBack = texture2D(s_sceneBack, refractionUv).rgb;
                float refractedDepth = unpackHfViewDepth(
                    texture2D(s_sceneDepth, refractedDepthUv).rgb
                );
                float waterDepth = abs(viewPosition.z);
                float depthFactor = clamp(waterDepth / refractedDepth * 0.5, 0.0, 1.0);
                vec3 inWaterColor = mix(refractedBack, originalBack, depthFactor);

                vec4 depthSample = texture2D(s_sceneDepth, originalDepthUv);
                float waterAlpha = 1.0 - depthSample.a;
                float sceneDepth = unpackHfViewDepth(depthSample.rgb);
                float depthDelta = max(sceneDepth - waterDepth, 0.0);
                inWaterColor *= clamp(
                    exp2(vec3(-0.01, -0.001, -0.001) * depthDelta),
                    vec3(0.0, 0.0, 0.0), vec3(1.0, 1.0, 1.0)
                );
                inWaterColor *= min(sceneDepth, 1.0);

                float specular = pow(
                    clamp(dot(lightTangent, reflect(eyeTangent, waterNormal)), 0.0, 1.0),
                    250.0 * waterAlpha
                );
                vec3 reflected = texture2D(s_sceneReflection, reflectionUv).rgb;
                if (sceneMaterial(11).y > 0.5)
                {
                    vec3 waterDiffuse = sceneBitmapArray(
                        s_sceneDiffuse, vec3(v_texcoord1.xy, diffuseLayer)
                    ).rgb * diffuseIntensity;
                    color = waterDiffuse
                        + clamp(mix(inWaterColor, reflected, clamp(fresnel, 0.0, 1.0)), 0.0, 1.0)
                        + vec3_splat(specular);
                }
                else
                {
                    vec3 waterDiffuse = vec3_splat(diffuseIntensity);
                    vec3 reflectedWater = clamp(mix(
                        inWaterColor * 0.5,
                        reflected * 0.5 + vec3_splat(specular),
                        clamp(fresnel * 4.0, 0.0, 1.0)
                    ), 0.0, 1.0);
                    vec3 waterTex = sceneBitmapArray(
                        s_sceneDiffuse, vec3(normalUv0, diffuseLayer)
                    ).rgb;
                    color = (waterTex * 0.7 + waterDiffuse * 0.3) * 0.2
                        + reflectedWater * 0.8;
                }
                alpha = waterAlpha;
            }
        }
    }
#elif defined(ENTITY_VARIANT_COMPLEX)
    else if (kind == 2.0)
    {
        color = clamp(color + sceneMaterial(7).yzw, 0.0, 1.0);
        alpha = sceneMaterial(7).x;
    }
    else if (kind == 3.0)
    {
        float secondLayer = floor(sceneMaterial(2).z * 0.5);
        vec4 layer2;
        if (sceneMaterial(6).w > 0.5)
        {
            vec3 cubeNormal = normalize(v_texcoord4.xyz);
            vec3 lookup;
            if (sceneMaterial(6).w > 1.5)
            {
                vec3 viewNormal = normalize(mul(u_view, vec4(cubeNormal, 0.0)).xyz);
                vec3 viewPosition = normalize(mul(u_view, vec4(v_texcoord3.xyz, 1.0)).xyz);
                lookup = reflect(viewPosition, viewNormal);
            }
            else
            {
                vec3 cameraPosition = mul(u_invView, vec4(0.0, 0.0, 0.0, 1.0)).xyz;
                lookup = reflect(normalize(v_texcoord3.xyz - cameraPosition), cubeNormal);
            }
            vec4 rotation = sceneMaterial(9);
            vec3 doubledCross = 2.0 * cross(rotation.xyz, lookup);
            lookup = lookup + rotation.w * doubledCross + cross(rotation.xyz, doubledCross);
            layer2 = sceneBitmapCube(s_sceneCube, lookup) * sceneMaterial(12);
        }
        else
        {
            layer2 = sceneBitmapArray(s_sceneSecond, vec3(diffuseUv, secondLayer)) * sceneMaterial(12);
        }
        vec4 maskValue = sceneMaterial(6).z > 0.5
            ? v_color0
            : sceneBitmapArray(
                s_sceneMask, vec3(diffuseUv, floor(sceneMaterial(2).w * 0.5))
            );
        float blendMask = maskValue.a;
        if (sceneMaterial(6).x > 0.5)
        {
            blendMask = 1.0 - blendMask;
        }
        float operation = sceneMaterial(5).z;
        if (operation == 1.0) color = layer2.rgb;
        else if (operation == 2.0) color = color * layer2.rgb * sceneMaterial(5).w;
        else if (operation == 3.0) color = color + layer2.rgb;
        else if (operation == 4.0) color = color - layer2.rgb;
        else if (operation == 5.0) color = mix(color, layer2.rgb, blendMask);
        else if (operation == 6.0) color = color + layer2.rgb * blendMask;
        else if (operation == 7.0) color = maskValue.rgb;
        if (sceneMaterial(6).y == 3.0) alpha = texel.a;
        else if (sceneMaterial(6).y == 4.0) alpha = layer2.a;
    }
    else if (kind == 5.0)
    {
        vec3 reflected = sceneBitmapCube(s_sceneCube, normalize(v_texcoord4.xyz)).rgb;
        color = mix(color, reflected, clamp(sceneMaterial(10).x, 0.0, 1.0));
        alpha = 1.0;
    }
#endif

#if defined(ENTITY_VARIANT_COMPLEX) || defined(ENTITY_VARIANT_CSG) || defined(ENTITY_VARIANT_WATER)
    if (!nativeMaterialPass && nativeWaterRoute < 0.5 && sceneMaterial(2).y < -0.5)
    {
        alpha = -sceneMaterial(2).y - 1.0;
    }
#endif
    bool hasMaterialColorModifier = !nativeMaterialPass && mod(floor(sceneMaterial(3).z / 4.0), 2.0) > 0.5;
    vec3 materialColorModifier = sceneMaterial(1).rgb;
#if defined(ENTITY_VARIANT_WATER)
    if (!nativeMaterialPass && sceneMaterial(15).x > 1.5 && u_authoredMaterial[0].z > 0.5)
    {
        hasMaterialColorModifier = true;
        materialColorModifier *= u_authoredMaterial[34].rgb;
    }
#endif
    if (!nativeMaterialPass && nativeWaterRoute < 0.5)
    {
        if (!hasMaterialColorModifier)
        {
            color *= sceneMaterial(1).rgb;
            alpha *= sceneMaterial(1).a;
        }
    }
#if defined(ENTITY_VARIANT_COMPLEX)
    if (sceneMaterial(2).y >= 0.0 && mod(sceneMaterial(2).y, 2.0) > 0.5)
    {
        alpha *= sceneBitmapArray(
            s_sceneOpacity, vec3(diffuseUv, floor(sceneMaterial(2).y * 0.5))
        ).a;
    }
#endif
    float vertexStreamFlags = sceneMaterial(3).z;
    bool usesVertexColorRgb = mod(vertexStreamFlags, 2.0) > 0.5;
    bool usesVertexAlpha = mod(floor(vertexStreamFlags / 2.0), 2.0) > 0.5;
    if (nativeWaterRoute < 0.5 && hasMaterialColorModifier)
        alpha *= sceneMaterial(1).a;
    if (!nativeMaterialPass && nativeWaterRoute < 0.5 && usesVertexAlpha && (blendCode == 1.0 || blendCode == 9.0))
    {
        alpha *= v_color0.a;
    }
    bool vertexColorCombinerMask = false;
#if defined(ENTITY_VARIANT_COMPLEX)
    vertexColorCombinerMask = sceneMaterial(6).z > 0.5;
#elif defined(ENTITY_VARIANT_WATER)
    vertexColorCombinerMask = sceneMaterial(15).x > 1.5 && u_authoredMaterial[0].y > 0.5;
#endif
    if (!nativeMaterialPass && nativeWaterRoute < 0.5
        && usesVertexColorRgb
        && !vertexColorCombinerMask)
    {
        color *= v_color0.rgb;
    }
    float alphaCutoff = sceneMaterial(3).x;
#if defined(ENTITY_VARIANT_WATER)
    if (!softParticle && nativeWaterRoute < 0.5 && alphaCutoff >= 0.0 && alpha <= alphaCutoff)
    {
        discard;
    }
#else
    if (blendCode == 7.0 || blendCode == 9.0)
    {
        alphaCutoff = mix(alphaCutoff, max(alphaCutoff, 0.5), maskedMinification);
    }
    bool bareAlphaTextureTest = blendCode == 1.0 && alphaCutoff <= 0.0;
    if (!softParticle && bareAlphaTextureTest && alpha <= alphaCutoff)
    {
        discard;
    }
    if (!softParticle && (blendCode == 5.0 || blendCode == 7.0 || blendCode == 9.0)
        && alpha <= alphaCutoff)
    {
        discard;
    }
    if (!softParticle && blendCode == 9.0 && maskedFoliage)
    {
        alpha = mix(alpha, 1.0, maskedMinification);
    }
#endif
    vec3 realisticMaterialColor = clamp(color, 0.0, 1.0);
    vec3 realisticMaterialAux = vec3(0.0, 0.0, 0.0);
    float realisticMaterialAuxMode = 0.0;
    vec3 normal = normalize(v_texcoord4.xyz);
    vec3 baked = max(v_texcoord2.xyz, vec3(0.0, 0.0, 0.0));
    bool packedInstanceVisibility = v_texcoord5.w > 0.5 && v_texcoord5.w < 2.5
        && hasPackedInstanceLighting(v_texcoord5.rgb);
    vec3 instanceFloor = max(
        packedInstanceVisibility ? unpackInstanceLight(v_texcoord5.rgb) : v_texcoord5.rgb,
        vec3(0.0, 0.0, 0.0)
    );
    vec3 sunDirection = normalize(u_sceneFrame[7].xyz);
    float directional = max(dot(normal, sunDirection), 0.0);
    vec3 light;
    float colorModifierLightingScale = 1.0;
#if defined(ENTITY_VARIANT_CSG)
    light = vec3(1.0, 1.0, 1.0);
    if (sceneMaterial(11).y > 0.5 && v_texcoord1.x >= 0.0)
    {
        float lightmapLayer = sceneMaterial(11).z < 0.0
            ? v_texcoord4.w + (-sceneMaterial(11).z - 1.0) : sceneMaterial(11).z;
        vec3 lightmap = sceneBitmapArray(
            s_sceneLightmap, vec3(v_texcoord1.xy, lightmapLayer)
        ).rgb;
        light = lightmap * 2.0;
    }
#else
#if defined(ENTITY_VARIANT_WATER)
    bool authoredMeshLighting = nativeWaterRoute < 0.5 && sceneMaterial(10).w > 1.5;
    light = vec3(1.0, 1.0, 1.0);
    if (authoredMeshLighting)
    {
#endif
    float lightingMarker = v_texcoord5.w;
    vec3 floorSource = instanceFloor + baked;
    float floorLuminance = dot(floorSource, vec3(0.299, 0.587, 0.114));
    bool nativeSceneColor = v_texcoord7.z > 0.5;
    bool hasNativeAmbient = v_actorAmbientData.y > 0.5;
    bool animatedAmbientGlow = v_texcoord7.y > 0.5;
    vec3 authoredFloor = nativeSceneColor || floorLuminance > 0.001
        ? floorSource : vec3(0.5, 0.5, 0.5);
    bool fullbrightDecoration = lightingMarker > 4.5 && lightingMarker < 5.5;
    bool terrainDecoration = lightingMarker > 2.5 && lightingMarker < 4.5;
    bool meshDecoration = lightingMarker > 5.5 && lightingMarker < 6.5;
    hasNativeAmbient = hasNativeAmbient && !meshDecoration;
    bool liveSunAffected = lightingMarker > 0.5;
    float nativeStaticLightingScale = nativeSceneColor || hasNativeAmbient ? 2.0 : 1.0;
    if (sceneMaterial(10).z < 0.5 && !meshParticle)
        colorModifierLightingScale = nativeStaticLightingScale;
#if defined(ENTITY_VARIANT_GPU_TABLE)
    bool missingInstanceFoliage = gpuFoliage;
#else
    bool missingInstanceFoliage = floor(u_sceneFrame[5].w / 16.0) > 0.5;
#endif
    if (fullbrightDecoration)
    {
        light = vec3(1.0, 1.0, 1.0);
    }
    else if (meshDecoration)
    {
        light = floor(clamp(baked, 0.0, 1.0) * 255.0 + 0.5) * (2.0 / 255.0);
    }
    else if (terrainDecoration)
    {
#if defined(ENTITY_VARIANT_GPU_TABLE)
        vec3 terrainSun = u_sceneFrame[4].rgb;
        vec3 terrainAmbient = u_sceneFrame[5].rgb;
#else
        vec3 terrainSun = u_sceneFrame[8].rgb;
        vec3 terrainAmbient = u_sceneFrame[9].rgb;
#endif
        vec3 steadyByte = floor(clamp(baked, 0.0, 1.0) * 255.0 + 0.5);
        vec3 ambientByte = floor(clamp(terrainAmbient, 0.0, 1.0) * 255.0 + 0.5);
        vec3 ambientHalfByte = floor(ambientByte * 0.5);
        vec3 sunByte = clamp(floor(
            max(terrainSun, vec3(0.0, 0.0, 0.0))
                * v_texcoord2.w * 0.5 * 255.0
        ), 0.0, 255.0);
        vec3 finalByte = min(
            steadyByte + ambientHalfByte + sunByte,
            vec3(255.0, 255.0, 255.0)
        );
        light = finalByte * (2.0 / 255.0);
    }
    else if (hasNativeAmbient)
    {
        light = v_nativeDiffuse.rgb * 2.0;
    }
    else if (missingInstanceFoliage)
    {
        light = u_sceneFrame[9].rgb
            + u_sceneFrame[8].rgb * abs(dot(normal, sunDirection)) * 2.0;
    }
    else if (liveSunAffected)
    {
        vec3 nativeStaticMeshAmbientByte = floor(u_sceneFrame[9].rgb * 255.0 + 0.5);
        vec3 nativeStaticMeshAmbientHalf = floor(nativeStaticMeshAmbientByte * 0.5) / 255.0;
        nativeStaticMeshAmbientHalf = scaleStaticMeshAmbient(
            vec4(nativeStaticMeshAmbientHalf, 1.0), v_actorAmbientData.x).rgb;
        light = nativeSceneColor
            ? (nativeStaticMeshAmbientHalf * v_color0.rgb + baked) * nativeStaticLightingScale
            : u_sceneFrame[9].rgb + baked;
    }
    else
    {
        if (nativeSceneColor)
        {
            vec3 nativeActorAmbientHalf = instanceFloor;
            if (animatedAmbientGlow)
            {
                float seconds = sceneAnimationTime(sceneMaterial(4).y);
                float glow = sin(seconds * 8.0) * 0.2 + 0.25;
                vec3 baseByte = floor(instanceFloor * 255.0 + 0.5);
                vec3 ambientByte = floor(clamp(baseByte / 255.0 + glow, 0.0, 1.0) * 255.0);
                nativeActorAmbientHalf = floor(ambientByte * 0.5) / 255.0;
            }
            nativeActorAmbientHalf = scaleStaticMeshAmbient(
                vec4(nativeActorAmbientHalf, 1.0), v_actorAmbientData.x).rgb;
            light = (nativeActorAmbientHalf * v_color0.rgb + baked)
                * nativeStaticLightingScale;
        }
        else
        {
            light = authoredFloor;
        }
    }
#if defined(ENTITY_VARIANT_WATER)
    light = max(light, vec3(0.0, 0.0, 0.0));
#else
    light = clamp(
        light, vec3(0.0, 0.0, 0.0),
        terrainDecoration ? vec3(2.0, 2.0, 2.0) : vec3(1.0, 1.0, 1.0)
    );
#endif
#if defined(ENTITY_VARIANT_COMPLEX) || defined(ENTITY_VARIANT_WATER)
    if (sceneMaterial(11).y > 0.5 && v_texcoord1.x >= 0.0)
    {
        if (sceneMaterial(11).z <= BEAST_LIGHTMAP_LAYER + 0.5)
        {
            float beastBase = BEAST_LIGHTMAP_LAYER - sceneMaterial(11).z;
            vec3 beastSun = sceneBitmapArray(s_sceneLightmap, vec3(v_texcoord1.zw, beastBase)).rgb;
            vec3 beastStatic = sceneBitmapArray(s_sceneLightmap, vec3(v_texcoord1.zw, beastBase + 1.0)).rgb;
            vec3 beastDiffuse = hasNativeAmbient ? v_nativeDiffuse.rgb : baked + scaleStaticMeshAmbient(
                vec4(u_sceneFrame[13].rgb, 1.0), v_actorAmbientData.x).rgb;
            light = (beastDiffuse + u_sceneFrame[8].w * beastSun + u_sceneFrame[9].w * beastStatic) * 2.0;
        }
        else
        {
            float lightmapLayer = sceneMaterial(11).z < 0.0
                ? v_texcoord4.w + (-sceneMaterial(11).z - 1.0) : sceneMaterial(11).z;
            light = sceneBitmapArray(s_sceneLightmap, vec3(v_texcoord1.xy, lightmapLayer)).rgb + baked;
        }
    }
#endif
#if defined(ENTITY_VARIANT_WATER)
    }
#endif
#endif
    if (sceneUnlit())
    {
        light = vec3_splat(1.0);
        colorModifierLightingScale = 1.0;
    }
    if (meshParticle)
    {
        light = vec3(1.0, 1.0, 1.0);
    }
#if defined(ENTITY_VARIANT_WATER)
    if (!nativeMaterialPass && authoredMeshLighting && sceneMaterial(10).z < 0.5)
    {
        color *= light;
    }
#else
    if (sceneMaterial(10).z < 0.5)
    {
        color *= light;
    }
    else if (usesVertexAlpha && !meshParticle && nativeWaterRoute < 0.5)
    {
        color *= v_color0.rgb;
    }
#endif

    if (nativeWaterRoute < 0.5 && hasMaterialColorModifier)
        color *= materialColorModifier * colorModifierLightingScale;

    bool particleColorLast = meshParticle && !emitterOverridesMeshMaterial;
    if (meshParticle)
    {
        float particleOpacity = clamp(v_texcoord5.a, 0.0, 1.0);
        color *= u_sceneFrame[15].rgb;
        if (!particleColorLast)
        {
            color *= v_texcoord5.rgb;
        }
        if (!softParticle && emitterOverridesMeshMaterial && u_sceneFrame[6].y > 0.5
            && alpha * particleOpacity <= u_sceneFrame[6].z)
        {
            discard;
        }
        alpha *= particleOpacity;
    }

#if defined(ENTITY_VARIANT_COMPLEX)
    bool separateSpecularPass = sceneMaterial(11).w > 0.5 && !emitterOverridesMeshMaterial;
    vec3 separateSpecular = vec3_splat(0.0);
    vec2 overlayMeshUv = meshUvUnderSurfaceTransform(
        v_texcoord0, sceneMaterial(4), sceneMaterial(5).xy, sceneAnimationTime(sceneMaterial(4).y));
    if (mod(sceneMaterial(16).x, 2.0) > 0.5)
    {
        float selfSource = floor(sceneMaterial(16).z / 16.0);
        if (meshParticle && selfSource == 1.0) selfSource = 2.0;
        float selfMaskChannel = mod(floor(sceneMaterial(16).z / 8.0), 2.0);
        float selfMode = mod(sceneMaterial(16).z, 8.0);
        vec2 selfMaskUv = selfMaskChannel > 0.5 ? v_texcoord1.zw : overlayMeshUv;
        float selfTime = sceneAnimationTime(sceneMaterial(13).y);
        vec2 selfUv = selfSource > 0.5
            ? animateEnvironmentUv(environmentLayerVector(selfSource, v_texcoord3.xyz, v_texcoord4.xyz), selfMode,
                selfTime, sceneMaterial(17), sceneMaterial(16).w)
            : animateOverlayUv(selfMaskUv, selfMode, selfTime, sceneMaterial(17), sceneMaterial(16).w);
        vec3 selfIllumination = sceneBitmapArray(s_sceneSelfIllumination,
            vec3(selfUv, floor(sceneMaterial(16).x * 0.5))).rgb;
        float selfMask = sceneBitmapArray(s_sceneSelfIlluminationMask,
            vec3(selfMaskUv, floor(sceneMaterial(16).y * 0.5))).a;
        if (particleColorLast)
        {
            color = clamp(color, 0.0, 1.0);
        }
        color = mix(color, selfIllumination, selfMask);
    }
    if (mod(sceneMaterial(8).x, 2.0) > 0.5)
    {
        float specSource = floor(sceneMaterial(14).z * 0.5);
        if (meshParticle && specSource == 1.0) specSource = 2.0;
        vec2 specMaskUv = mod(sceneMaterial(14).z, 2.0) > 0.5 ? v_texcoord1.zw : overlayMeshUv;
        float specTime = sceneAnimationTime(sceneMaterial(13).y);
        vec4 specParameters = vec4(sceneMaterial(13).zw, sceneMaterial(14).xy);
        vec2 specUv = specSource > 0.5
            ? animateEnvironmentUv(environmentLayerVector(specSource, v_texcoord3.xyz, v_texcoord4.xyz),
                sceneMaterial(13).x, specTime, specParameters, sceneMaterial(14).w)
            : animateOverlayUv(specMaskUv, sceneMaterial(13).x, specTime, specParameters, sceneMaterial(14).w);
        vec3 specular = sceneBitmapArray(
            s_sceneSpecular, vec3(specUv, floor(sceneMaterial(8).x * 0.5))
        ).rgb * sceneMaterial(9).rgb;
        if (specSource > 6.5)
        {
            specular = sceneBitmapCube(s_sceneCube,
                cubeSpecularLookup(specSource, v_texcoord3.xyz, v_texcoord4.xyz, sceneMaterial(9))).rgb;
        }
        float specMask = mod(sceneMaterial(8).y, 2.0) > 0.5
            ? sceneBitmapArray(
                s_sceneSpecMask, vec3(specMaskUv, floor(sceneMaterial(8).y * 0.5))
            ).a
            : 1.0;
        realisticMaterialAux = clamp(specular * specMask, 0.0, 1.0);
        bool realisticAux2x = sceneMaterial(8).z > 0.5;
        bool realisticAuxLit = sceneMaterial(8).w > 0.5;
        realisticMaterialAuxMode = realisticAuxLit
            ? (realisticAux2x ? 1.0 : 0.50)
            : (realisticAux2x ? 0.75 : 0.25);
        if (sceneMaterial(8).z > 0.5) specular *= 2.0;
        if (sceneMaterial(8).w > 0.5) specular *= light;
        if (separateSpecularPass)
        {
            separateSpecular = specular * specMask;
        }
        else if (particleColorLast)
        {
            color = clamp(clamp(color, 0.0, 1.0) + specular * specMask, 0.0, 1.0);
        }
        else
        {
            color += specular * specMask;
        }
    }
#endif
    if (particleColorLast)
    {
        color *= v_texcoord5.rgb;
    }

    if (u_sceneFrame[3].y > 0.5
        && (!(meshParticle && u_sceneFrame[12].y > 0.5) || u_sceneFrame[3].y > 1.5))
    {
        float eyeDepth = abs(mul(u_view, vec4(v_texcoord3.xyz, 1.0)).z);
        float fogFactor = clamp(
            (u_sceneFrame[11].y - eyeDepth)
                / max(u_sceneFrame[11].y - u_sceneFrame[11].x, 0.0001),
            0.0,
            1.0
        );
        vec3 fogColor = u_sceneFrame[10].rgb;
#if defined(ENTITY_VARIANT_WATER)
        if (nativeWaterRoute < 0.5 && sceneMaterial(17).x > 0.5)
        {
            fogColor = sceneMaterial(17).yzw;
        }
#endif
        color = mix(fogColor, color, fogFactor);
#if defined(ENTITY_VARIANT_COMPLEX)
        separateSpecular *= fogFactor;
#endif
    }

    if (u_sceneFrame[11].z > 0.5)
    {
        if (u_sceneFrame[11].w < 0.5) discard;
        color = u_sceneFrame[3].w > 0.5
            ? vec3(0.04, 0.012, 0.004)
            : vec3(1.0, 0.15, 0.02);
        alpha = 1.0;
    }

    if (u_sceneFrame[12].w > 0.5)
    {
        color = u_sceneFrame[12].rgb;
        alpha = 1.0;
    }

#if defined(ENTITY_VARIANT_ORDINARY)
    if (sceneMaterial(15).w > 0.5)
    {
        color = vec3(1.0, 0.0, 1.0);
        alpha = 1.0;
    }
#endif

    if (softParticle)
    {
        vec2 depthUv = softParticleDepthSampleUv(
            v_softDepth.xy, u_sceneFrame[2].xy, u_sceneFrame[1].zw, u_sceneFrame[1].y);
        float environmentDist = unpackHfViewDepth(texture2D(s_sceneDepth, depthUv).rgb);
        alpha = softParticleAlpha(alpha, environmentDist, v_softDepth.z, u_sceneFrame[12].z);
        float softAlphaCutoff = emitterOverridesMeshMaterial
            ? (u_sceneFrame[6].y > 0.5 ? u_sceneFrame[6].z : -1.0)
            : sceneMaterial(3).x;
        if (softAlphaCutoff >= 0.0 && alpha <= softAlphaCutoff) discard;
    }

    vec4 result = vec4(clamp(color, 0.0, 1.0), clamp(alpha, 0.0, 1.0));
#if defined(ENTITY_VARIANT_COMPLEX)
    if (separateSpecularPass)
    {
        result.rgb = result.rgb * result.a + max(separateSpecular, vec3_splat(0.0)) * clamp(sceneMaterial(1).w, 0.0, 1.0);
    }
#define SCENE_TARGET(_value) premultipliedTarget(_value, separateSpecularPass)
#else
#define SCENE_TARGET(_value) (_value)
#endif
#if defined(ENTITY_VARIANT_WATER_RECEIVER_MRT)
    gl_FragData[0] = result;
    gl_FragData[3] = vec4(0.0, 0.0, 0.0, 1.0);
    gl_FragData[4] = vec4(0.0, 0.0, 0.0, 0.0);
#elif defined(ENTITY_VARIANT_DEPTH_MRT)
    gl_FragData[0] = result;
    float depthCoverage = result.a;
    float inWaterPlaneZ = u_sceneFrame[6].x;
    if (inWaterPlaneZ != 0.0)
    {
        float waterDepth = max(inWaterPlaneZ - v_texcoord3.z, 0.0);
#if defined(ENTITY_VARIANT_CSG)
        depthCoverage = 1.0 - clamp(waterDepth / 10.0, 0.0, 1.0);
#else
        depthCoverage *= 1.0 - clamp(waterDepth * 10000.0, 0.0, 1.0);
#endif
    }
    gl_FragData[1] = SCENE_TARGET(!meshParticle && v_texcoord7.w > 0.5
        ? vec4(
            packHfViewDepth(abs(mul(u_view, vec4(v_texcoord3.xyz, 1.0)).z)),
            depthCoverage
        )
        : vec4(0.0, 0.0, 0.0, 0.0));
    vec3 outputNormal = normalize(mul(u_view, vec4(v_texcoord4.xyz, 0.0)).xyz);
#if defined(ENTITY_VARIANT_WATER)
    if (nativeMaterialNormalWritten)
        outputNormal = nativeMaterialNormal;
#endif
    gl_FragData[2] = SCENE_TARGET(!meshParticle && v_texcoord7.w > 0.5
        ? vec4(
            outputNormal * 0.5 + 0.5,
            result.a
        )
        : vec4(0.0, 0.0, 0.0, 0.0));
    float realisticReceiverClass = 0.0;
    bool replacementRelightingSupported;
#if defined(ENTITY_VARIANT_CSG)
    replacementRelightingSupported = !(sceneMaterial(11).y > 0.5 && v_texcoord1.x >= 0.0);
#elif defined(ENTITY_VARIANT_WATER)
    replacementRelightingSupported = false;
#else
    replacementRelightingSupported = !nativeSceneColor
        && v_texcoord2.w < 0.0
        && floorLuminance <= 0.001
        && !fullbrightDecoration
        && !terrainDecoration
        && !meshDecoration
        && !missingInstanceFoliage;
#if defined(ENTITY_VARIANT_COMPLEX)
    replacementRelightingSupported = replacementRelightingSupported
        && !(sceneMaterial(11).y > 0.5 && v_texcoord1.x >= 0.0)
        && mod(sceneMaterial(16).x, 2.0) < 0.5;
#endif
#endif
    if (!meshParticle && v_texcoord7.w > 0.5 && sceneMaterial(10).z < 0.5
        && nativeWaterRoute < 0.5
        && replacementRelightingSupported
        && (blendCode == 0.0 || blendCode == 7.0))
    {
#if defined(ENTITY_VARIANT_CSG)
        realisticReceiverClass = 0.50;
#elif defined(ENTITY_VARIANT_COMPLEX)
        realisticReceiverClass = 0.25;
#else
        realisticReceiverClass = 0.25;
#endif
    }
#if defined(ENTITY_VARIANT_CSG)
    if (!meshParticle && v_texcoord7.w > 0.5 && sceneMaterial(10).z < 0.5
        && nativeWaterRoute < 0.5 && !replacementRelightingSupported
        && (blendCode == 0.0 || blendCode == 7.0))
        realisticReceiverClass = 16.0 / 255.0;
#endif
#if !defined(ENTITY_VARIANT_CSG) && !defined(ENTITY_VARIANT_WATER)
    if (!meshParticle && v_texcoord7.w > 0.5 && sceneMaterial(10).z < 0.5
        && nativeWaterRoute < 0.5
        && !replacementRelightingSupported
        && !fullbrightDecoration && !terrainDecoration && !meshDecoration && !missingInstanceFoliage
        && (blendCode == 0.0 || blendCode == 7.0))
    {
        float clientEnvironmentResponse = v_texcoord2.w >= 0.0
            ? directional * clamp(v_texcoord2.w, 0.0, 1.0)
                * (nativeSceneColor ? 2.0 : 1.0)
            : 0.0;
        realisticReceiverClass =
            (224.0 + clamp(clientEnvironmentResponse * 0.5, 0.0, 1.0) * 27.0) / 255.0;
    }
#endif
    if (u_sceneFrame[11].z > 0.5 || u_sceneFrame[12].w > 0.5)
    {
        realisticReceiverClass = 0.0;
    }
#if defined(ENTITY_VARIANT_ORDINARY)
    if (sceneMaterial(15).w > 0.5)
    {
        realisticReceiverClass = 0.0;
    }
#endif
    if (!meshParticle && v_texcoord7.w > 0.5
        && nativeWaterRoute < 0.5 && blendCode == 9.0)
    {
        realisticMaterialColor = vec3_splat(0.0);
        realisticReceiverClass = 1.0;
    }
    gl_FragData[3] = SCENE_TARGET(vec4(realisticMaterialColor, realisticReceiverClass));
    gl_FragData[4] = SCENE_TARGET(vec4(realisticMaterialAux, realisticMaterialAuxMode));
#else
    gl_FragColor = result;
#endif
}
