#include <bgfx_shader.sh>
#include "hf_depth_pack.sh"
#include "bitmap_sampling.sh"
#include "height_cut.sh"

SAMPLER2DARRAY(s_terrain0, 0);
SAMPLER2DARRAY(s_terrain1, 1);
SAMPLER2DARRAY(s_terrain2, 2);
SAMPLER2DARRAY(s_terrain3, 3);
SAMPLER2DARRAY(s_terrain4, 4);
SAMPLER2DARRAY(s_terrain5, 5);
SAMPLER2DARRAY(s_terrain6, 6);
SAMPLER2DARRAY(s_terrain7, 7);
SAMPLER2DARRAY(s_terrain8, 8);
SAMPLER2DARRAY(s_terrain9, 9);
SAMPLER2DARRAY(s_terrain10, 10);
SAMPLER2DARRAY(s_terrain11, 11);
SAMPLER2DARRAY(s_terrainAlpha0, 12);
SAMPLER2DARRAY(s_terrainAlpha1, 13);
SAMPLER2DARRAY(s_terrainAlpha2, 14);

uniform vec4 u_terrainMaterial[8];
uniform vec4 u_sceneFrame[18];

#define terrainBitmapArray(_sampler, _uv) bitmapArrayBias(_sampler, _uv, u_sceneFrame[7].w)

#if defined(TERRAIN_OVERLAY)
uniform vec4 u_terrainLayerUv[25];
#define TERRAIN_LAYER_UV(_slot, _attribute) \
    vec2(dot(u_terrainLayerUv[2 * _slot], vec4(overlayPosition, 1.0)), \
        dot(u_terrainLayerUv[2 * _slot + 1], vec4(overlayPosition, 1.0)))
#define TERRAIN_BLEND(_rgb, _weight) \
    { float overlayWeight = (_weight); color = mix(color, (_rgb), overlayWeight); uncovered *= 1.0 - overlayWeight; }
#else
#define TERRAIN_LAYER_UV(_slot, _attribute) (_attribute)
#define TERRAIN_BLEND(_rgb, _weight) color = mix(color, (_rgb), (_weight))
#endif

#if BGFX_SHADER_LANGUAGE_HLSL > 300 || BGFX_SHADER_LANGUAGE_PSSL || BGFX_SHADER_LANGUAGE_SPIRV || BGFX_SHADER_LANGUAGE_METAL
#define terrainTextureGrad(_sampler, _uv, _layer, _uvDx, _uvDy) \
    (_sampler).m_texture.SampleGrad((_sampler).m_sampler, vec3((_uv), (_layer)), \
        (_uvDx) * exp2(u_sceneFrame[7].w), (_uvDy) * exp2(u_sceneFrame[7].w))
#else
#define terrainTextureGrad(_sampler, _uv, _layer, _uvDx, _uvDy) \
    textureGrad((_sampler), vec3((_uv), (_layer)), \
        (_uvDx) * exp2(u_sceneFrame[7].w), (_uvDy) * exp2(u_sceneFrame[7].w))
#endif

float terrainLayerCoverage(float authoredCoverage, float diffuseAlpha, float packedFlags, float bit)
{
    float usesDiffuseAlpha = mod(floor(packedFlags / bit), 2.0);
    return clamp(authoredCoverage, 0.0, 1.0) * mix(1.0, diffuseAlpha, usesDiffuseAlpha);
}

bool terrainBaseLayer(float layer)
{
    return abs(u_terrainMaterial[0].w - layer) < 0.5;
}

bool terrainLayerDraws(float layer, float hiddenBit, float authoredCoverage)
{
    bool hidden = mod(floor(u_terrainMaterial[3].w / hiddenBit), 2.0) > 0.5;
    return u_terrainMaterial[0].x > layer
        && !hidden
        && (u_terrainMaterial[0].z < 0.5 || authoredCoverage > 0.0 || terrainBaseLayer(layer));
}

float terrainLayerWeight(float layer, float authoredCoverage, float diffuseAlpha, float packedFlags, float bit)
{
    return terrainBaseLayer(layer) ? 1.0 : terrainLayerCoverage(authoredCoverage, diffuseAlpha, packedFlags, bit);
}

void main()
{
    if ((u_sceneFrame[1].x > 0.5
            && dot(vec4(v_texcoord7.xyz, 1.0), u_sceneFrame[0]) < 0.0)
        || heightCutRejects(u_sceneFrame[16], u_sceneFrame[17], v_texcoord7.xyz))
    {
        discard;
    }
    float layers = u_terrainMaterial[0].x;
    vec4 alpha0 = terrainBitmapArray(s_terrainAlpha0, vec3(v_texcoord0, u_terrainMaterial[7].x));
    vec4 alpha1 = layers > 4.0
        ? terrainBitmapArray(s_terrainAlpha1, vec3(v_texcoord0, u_terrainMaterial[7].y))
        : vec4(0.0, 0.0, 0.0, 0.0);
    vec4 alpha2 = layers > 8.0
        ? terrainBitmapArray(s_terrainAlpha2, vec3(v_texcoord0, u_terrainMaterial[7].z))
        : vec4(0.0, 0.0, 0.0, 0.0);
#if defined(TERRAIN_OVERLAY)
    vec3 overlayPosition = v_texcoord7.xyz - u_terrainLayerUv[24].xyz;
    vec2 uv0 = TERRAIN_LAYER_UV(0, v_texcoord1.xy);
    vec3 color = vec3(0.0, 0.0, 0.0);
    float uncovered = 1.0;
    vec2 uv0dx = dFdx(uv0);
    vec2 uv0dy = dFdy(uv0);
    if (terrainLayerDraws(0.0, 1.0, alpha0.x))
    {
        vec4 layer0 = terrainTextureGrad(s_terrain0, uv0, u_terrainMaterial[4].x, uv0dx, uv0dy);
        TERRAIN_BLEND(layer0.rgb, terrainLayerWeight(0.0, alpha0.x, layer0.a, u_terrainMaterial[3].x, 1.0));
    }
#else
    vec2 uv0 = TERRAIN_LAYER_UV(0, v_texcoord1.xy);
    vec3 color = terrainTextureGrad(
        s_terrain0, uv0, u_terrainMaterial[4].x, dFdx(uv0), dFdy(uv0)
    ).rgb;
#endif

    vec2 uv1 = TERRAIN_LAYER_UV(1, v_texcoord1.zw);
    vec2 uv1dx = dFdx(uv1);
    vec2 uv1dy = dFdy(uv1);
    if (terrainLayerDraws(1.0, 2.0, alpha0.y))
    {
        vec4 layer1 = terrainTextureGrad(s_terrain1, uv1, u_terrainMaterial[4].y, uv1dx, uv1dy);
        TERRAIN_BLEND(layer1.rgb, terrainLayerWeight(1.0, alpha0.y, layer1.a, u_terrainMaterial[3].x, 2.0));
    }
    vec2 uv2 = TERRAIN_LAYER_UV(2, v_texcoord2.xy);
    vec2 uv2dx = dFdx(uv2);
    vec2 uv2dy = dFdy(uv2);
    if (terrainLayerDraws(2.0, 4.0, alpha0.z))
    {
        vec4 layer2 = terrainTextureGrad(s_terrain2, uv2, u_terrainMaterial[4].z, uv2dx, uv2dy);
        TERRAIN_BLEND(layer2.rgb, terrainLayerWeight(2.0, alpha0.z, layer2.a, u_terrainMaterial[3].x, 4.0));
    }
    vec2 uv3 = TERRAIN_LAYER_UV(3, v_texcoord2.zw);
    vec2 uv3dx = dFdx(uv3);
    vec2 uv3dy = dFdy(uv3);
    if (terrainLayerDraws(3.0, 8.0, alpha0.w))
    {
        vec4 layer3 = terrainTextureGrad(s_terrain3, uv3, u_terrainMaterial[4].w, uv3dx, uv3dy);
        TERRAIN_BLEND(layer3.rgb, terrainLayerWeight(3.0, alpha0.w, layer3.a, u_terrainMaterial[3].x, 8.0));
    }
    vec2 uv4 = TERRAIN_LAYER_UV(4, v_texcoord3.xy);
    vec2 uv4dx = dFdx(uv4);
    vec2 uv4dy = dFdy(uv4);
    if (terrainLayerDraws(4.0, 16.0, alpha1.x))
    {
        vec4 layer4 = terrainTextureGrad(s_terrain4, uv4, u_terrainMaterial[5].x, uv4dx, uv4dy);
        TERRAIN_BLEND(layer4.rgb, terrainLayerWeight(4.0, alpha1.x, layer4.a, u_terrainMaterial[3].y, 1.0));
    }
    vec2 uv5 = TERRAIN_LAYER_UV(5, v_texcoord3.zw);
    vec2 uv5dx = dFdx(uv5);
    vec2 uv5dy = dFdy(uv5);
    if (terrainLayerDraws(5.0, 32.0, alpha1.y))
    {
        vec4 layer5 = terrainTextureGrad(s_terrain5, uv5, u_terrainMaterial[5].y, uv5dx, uv5dy);
        TERRAIN_BLEND(layer5.rgb, terrainLayerWeight(5.0, alpha1.y, layer5.a, u_terrainMaterial[3].y, 2.0));
    }
    vec2 uv6 = TERRAIN_LAYER_UV(6, v_texcoord4.xy);
    vec2 uv6dx = dFdx(uv6);
    vec2 uv6dy = dFdy(uv6);
    if (terrainLayerDraws(6.0, 64.0, alpha1.z))
    {
        vec4 layer6 = terrainTextureGrad(s_terrain6, uv6, u_terrainMaterial[5].z, uv6dx, uv6dy);
        TERRAIN_BLEND(layer6.rgb, terrainLayerWeight(6.0, alpha1.z, layer6.a, u_terrainMaterial[3].y, 4.0));
    }
    vec2 uv7 = TERRAIN_LAYER_UV(7, v_texcoord4.zw);
    vec2 uv7dx = dFdx(uv7);
    vec2 uv7dy = dFdy(uv7);
    if (terrainLayerDraws(7.0, 128.0, alpha1.w))
    {
        vec4 layer7 = terrainTextureGrad(s_terrain7, uv7, u_terrainMaterial[5].w, uv7dx, uv7dy);
        TERRAIN_BLEND(layer7.rgb, terrainLayerWeight(7.0, alpha1.w, layer7.a, u_terrainMaterial[3].y, 8.0));
    }
    vec2 uv8 = TERRAIN_LAYER_UV(8, v_texcoord5.xy);
    vec2 uv8dx = dFdx(uv8);
    vec2 uv8dy = dFdy(uv8);
    if (terrainLayerDraws(8.0, 256.0, alpha2.x))
    {
        vec4 layer8 = terrainTextureGrad(s_terrain8, uv8, u_terrainMaterial[6].x, uv8dx, uv8dy);
        TERRAIN_BLEND(layer8.rgb, terrainLayerWeight(8.0, alpha2.x, layer8.a, u_terrainMaterial[3].z, 1.0));
    }
    vec2 uv9 = TERRAIN_LAYER_UV(9, v_texcoord5.zw);
    vec2 uv9dx = dFdx(uv9);
    vec2 uv9dy = dFdy(uv9);
    if (terrainLayerDraws(9.0, 512.0, alpha2.y))
    {
        vec4 layer9 = terrainTextureGrad(s_terrain9, uv9, u_terrainMaterial[6].y, uv9dx, uv9dy);
        TERRAIN_BLEND(layer9.rgb, terrainLayerWeight(9.0, alpha2.y, layer9.a, u_terrainMaterial[3].z, 2.0));
    }
    vec2 uv10 = TERRAIN_LAYER_UV(10, v_texcoord6.xy);
    vec2 uv10dx = dFdx(uv10);
    vec2 uv10dy = dFdy(uv10);
    if (terrainLayerDraws(10.0, 1024.0, alpha2.z))
    {
        vec4 layer10 = terrainTextureGrad(s_terrain10, uv10, u_terrainMaterial[6].z, uv10dx, uv10dy);
        TERRAIN_BLEND(layer10.rgb, terrainLayerWeight(10.0, alpha2.z, layer10.a, u_terrainMaterial[3].z, 4.0));
    }
    vec2 uv11 = TERRAIN_LAYER_UV(11, v_texcoord6.zw);
    vec2 uv11dx = dFdx(uv11);
    vec2 uv11dy = dFdy(uv11);
    if (terrainLayerDraws(11.0, 2048.0, alpha2.w))
    {
        vec4 layer11 = terrainTextureGrad(s_terrain11, uv11, u_terrainMaterial[6].w, uv11dx, uv11dy);
        TERRAIN_BLEND(layer11.rgb, terrainLayerWeight(11.0, alpha2.w, layer11.a, u_terrainMaterial[3].z, 8.0));
    }

#if defined(TERRAIN_OVERLAY)
    float overlayCover = 1.0 - uncovered;
    color = color / max(overlayCover, 0.000001);
    overlayCover *= u_terrainMaterial[7].w;
#endif
    vec3 realisticMaterialColor = clamp(color, 0.0, 1.0);
    color = clamp(u_sceneFrame[10].w > 0.5 ? color : color * v_color0.rgb, 0.0, 1.0);
    if (u_sceneFrame[3].y > 0.5)
    {
        float eyeDepth = abs(mul(u_view, vec4(v_texcoord7.xyz, 1.0)).z);
        float fogFactor = clamp(
            (u_sceneFrame[11].y - eyeDepth)
                / max(u_sceneFrame[11].y - u_sceneFrame[11].x, 0.0001),
            0.0,
            1.0
        );
        color = mix(u_sceneFrame[10].rgb, color, fogFactor);
    }
    if (u_sceneFrame[11].z > 0.5)
    {
        if (u_sceneFrame[11].w < 0.5) discard;
        color = u_sceneFrame[3].w > 0.5
            ? vec3(0.04, 0.012, 0.004)
            : vec3(1.0, 0.15, 0.02);
    }
    if (u_sceneFrame[12].w > 0.5)
    {
        color = u_sceneFrame[12].rgb;
    }
#if defined(TERRAIN_OVERLAY) && defined(ENTITY_VARIANT_DEPTH_MRT)
    gl_FragData[0] = vec4(color * overlayCover, overlayCover);
    gl_FragData[1] = vec4(0.0, 0.0, 0.0, 0.0);
    gl_FragData[2] = vec4(0.0, 0.0, 0.0, 0.0);
    gl_FragData[3] = vec4(realisticMaterialColor * overlayCover, overlayCover);
    gl_FragData[4] = vec4(0.0, 0.0, 0.0, 0.0);
#elif defined(TERRAIN_OVERLAY)
    gl_FragColor = vec4(color * overlayCover, overlayCover);
#elif defined(ENTITY_VARIANT_DEPTH_MRT)
    gl_FragData[0] = vec4(color, 1.0);
    float depthCoverage = 1.0;
    float inWaterPlaneZ = u_sceneFrame[6].x;
    if (inWaterPlaneZ != 0.0)
    {
        float waterDepth = max(inWaterPlaneZ - v_texcoord7.z, 0.0);
        depthCoverage = 1.0 - clamp(waterDepth / 10.0, 0.0, 1.0);
    }
    gl_FragData[1] = vec4(
        packHfViewDepth(abs(mul(u_view, vec4(v_texcoord7.xyz, 1.0)).z)),
        depthCoverage
    );
    gl_FragData[2] = vec4(v_tangent.xyz * 0.5 + 0.5, 1.0);
    float preservedTerrainClass =
        (160.0 + clamp(v_texcoord7.w, 0.0, 1.0) * 63.0) / 255.0;
    gl_FragData[3] = vec4(realisticMaterialColor, preservedTerrainClass);
    gl_FragData[4] = vec4(0.0, 0.0, 0.0, 0.0);
#else
    gl_FragColor = vec4(color, 1.0);
#endif
}
