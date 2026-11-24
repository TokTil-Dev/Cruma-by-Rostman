$input v_texcoord0

#include <bgfx_shader.sh>

SAMPLER2D(s_customLightingSource, 0);
SAMPLER2D(s_customLightingDepth, 1);
SAMPLER2D(s_customShadow0, 2);
SAMPLER2D(s_customShadow1, 3);
SAMPLER2D(s_customShadow2, 4);
SAMPLER2D(s_customShadow3, 5);
SAMPLER2D(s_customLocalShadow, 6);
SAMPLER2D(s_customRayVisibility, 7);
SAMPLER2D(s_customWaterMask, 8);
SAMPLER2D(s_customLightingNormal, 9);
SAMPLER2D(s_customLightingMaterialAux, 10);
SAMPLER2D(s_customDirectLocalTiles0, 11);
SAMPLER2D(s_customDirectLocalTiles1, 12);
SAMPLER2D(s_customGi, 13);
SAMPLER2D(s_customGiPrevious, 14);
SAMPLER2D(s_customNpcShadow, 15);
uniform vec4 u_customGi[16];
uniform vec4 u_customLighting[4];
uniform vec4 u_customInverseViewProjection[4];
uniform vec4 u_customInverseView[4];
uniform vec4 u_customShadowMatrices[16];
uniform vec4 u_customShadowParams[6];
uniform vec4 u_customNpcShadow[192];
uniform vec4 u_customLocalMatrices[48];
uniform vec4 u_customLocalParams[6];
uniform vec4 u_customLocalAtlas;
uniform vec4 u_customDirectLocalParams[49];
uniform vec4 u_customRayReprojection[13];
uniform vec4 u_customRaySampling;
uniform vec4 u_customRealisticLighting[8];

vec3 srgbToLinear(vec3 value)
{
    vec3 low = value / 12.92;
    vec3 high = pow((value + 0.055) / 1.055, vec3_splat(2.4));
    return mix(high, low, step(value, vec3_splat(0.04045)));
}

vec3 linearToSrgb(vec3 value)
{
    value = max(value, vec3_splat(0.0));
    vec3 low = value * 12.92;
    vec3 high = 1.055 * pow(value, vec3_splat(1.0 / 2.4)) - 0.055;
    return mix(high, low, step(value, vec3_splat(0.0031308)));
}

float maxComponent(vec3 value)
{
    return max(value.x, max(value.y, value.z));
}

vec3 hfLightToLinearEnergy(vec3 value)
{
    vec3 nonnegative = max(value, vec3_splat(0.0));
    float intensity = maxComponent(nonnegative);
    if (intensity <= 0.000001)
    {
        return vec3_splat(0.0);
    }
    vec3 linearChroma = srgbToLinear(clamp(nonnegative / intensity, 0.0, 1.0));
    return linearChroma * (intensity / max(maxComponent(linearChroma), 0.000001));
}

float authoredFogTransmittance(vec3 viewPosition)
{
    if (u_customRealisticLighting[0].y <= 0.5)
    {
        return 1.0;
    }
    float fogStart = u_customRealisticLighting[0].z;
    float fogEnd = u_customRealisticLighting[0].w;
    return clamp(
        (fogEnd - abs(viewPosition.z)) / max(fogEnd - fogStart, 0.0001),
        0.0, 1.0
    );
}

vec2 screenNdc(vec2 uv)
{
    float y = u_customLighting[2].z > 0.5 ? uv.y * 2.0 - 1.0 : 1.0 - uv.y * 2.0;
    return vec2(uv.x * 2.0 - 1.0, y);
}

vec3 capturedRayDirection(vec2 ndc)
{
    vec3 viewDirection = vec3(
        ndc.x / u_customRayReprojection[12].x,
        ndc.y / u_customRayReprojection[12].y,
        1.0
    );
    return normalize(
        u_customRayReprojection[9].xyz * viewDirection.x
            + u_customRayReprojection[10].xyz * viewDirection.y
            + u_customRayReprojection[11].xyz
    );
}

vec3 reconstructViewPosition(vec2 uv, float depth)
{
    float p00 = u_customLighting[1].x;
    float p11 = u_customLighting[1].y;
    float p22 = u_customLighting[1].z;
    float p23 = u_customLighting[1].w;
    float p32 = u_customLighting[2].x;
    float p33 = u_customLighting[2].y;
    float z = (p32 - depth * p33) / max(abs(depth * p23 - p22), 0.000001)
        * sign(depth * p23 - p22);
    float w = p23 * z + p33;
    vec2 ndc = screenNdc(uv);
    return vec3(ndc.x * w / p00, ndc.y * w / p11, z);
}

vec3 reconstructWorldPosition(vec2 uv, float depth)
{
    vec2 ndc = screenNdc(uv);
    vec4 clip = vec4(ndc, depth, 1.0);
    vec4 world = u_customInverseViewProjection[0] * clip.x
        + u_customInverseViewProjection[1] * clip.y
        + u_customInverseViewProjection[2] * clip.z
        + u_customInverseViewProjection[3];
    return world.xyz / max(abs(world.w), 0.000001) * sign(world.w);
}

vec4 shadowClip(int cascade, vec3 world)
{
    int base = cascade * 4;
    return u_customShadowMatrices[base] * world.x
        + u_customShadowMatrices[base + 1] * world.y
        + u_customShadowMatrices[base + 2] * world.z
        + u_customShadowMatrices[base + 3];
}

float signNotZero(float value)
{
    return value >= 0.0 ? 1.0 : -1.0;
}

vec3 decodeRayWorldNormal(float encoded)
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

float raySunVisibilityAt(
    vec3 world,
    vec3 receiverNormal,
    out float sunValid
)
{
    vec4 clip = u_customRayReprojection[0] * world.x
        + u_customRayReprojection[1] * world.y
        + u_customRayReprojection[2] * world.z
        + u_customRayReprojection[3];
    vec3 projected = clip.xyz / max(abs(clip.w), 0.000001) * sign(clip.w);
    vec2 uv = vec2(projected.x * 0.5 + 0.5, 0.5 - projected.y * 0.5);
    bool inside = clip.w > 0.0 && projected.z > 0.0 && projected.z < 1.0
        && uv.x > 0.0 && uv.y > 0.0 && uv.x < 1.0 && uv.y < 1.0;
    vec4 capturedCamera = u_customRayReprojection[8];
    float primaryDistance = max(capturedCamera.w, 1.0);
    float sunTolerance = 32.0 / max(capturedCamera.w, 1.0);
    vec2 publishedExtent = max(u_customRaySampling.zw, vec2_splat(1.0));
    vec2 queryPixel = clamp(uv, vec2_splat(0.0), vec2_splat(1.0)) * publishedExtent - 0.5;
    vec2 basePixel = floor(queryPixel);
    vec2 fraction = fract(queryPixel);
    float sunVisibility = 0.0;
    float sunWeight = 0.0;

    for (int sampleY = 0; sampleY < 2; ++sampleY)
    {
        for (int sampleX = 0; sampleX < 2; ++sampleX)
        {
            vec2 offset = vec2(float(sampleX), float(sampleY));
            vec2 pixel = clamp(basePixel + offset, vec2_splat(0.0), publishedExtent - 1.0);
            vec2 sampleUv = (pixel + 0.5) * u_customRaySampling.xy;
            vec4 visibility = texture2DLod(s_customRayVisibility, sampleUv, 0.0);
            vec2 axisWeight = mix(vec2_splat(1.0) - fraction, fraction, offset);
            float weight = axisWeight.x * axisWeight.y;
            vec2 rayUv = (pixel + 0.5) / publishedExtent;
            vec2 rayNdc = vec2(rayUv.x * 2.0 - 1.0, 1.0 - rayUv.y * 2.0);
            vec3 rayDirection = capturedRayDirection(rayNdc);
            float planeDenominator = dot(receiverNormal, rayDirection);
            float expectedRayDistance = abs(planeDenominator) > 0.00001
                ? dot(receiverNormal, world - capturedCamera.xyz) / planeDenominator
                : -1.0;
            float normalizedExpectedDistance = expectedRayDistance / primaryDistance;
            float distanceError = abs(visibility.b - normalizedExpectedDistance);
            float normalAgreement = visibility.a > 0.5
                ? dot(decodeRayWorldNormal(visibility.a), receiverNormal) : 0.0;
            bool forwardPlaneHit = expectedRayDistance > 0.0;
            bool sameSurface = normalAgreement >= 0.75;
            if (inside && forwardPlaneHit && sameSurface && distanceError <= sunTolerance)
            {
                sunVisibility += visibility.r * weight;
                sunWeight += weight;
            }
        }
    }
    sunValid = sunWeight > 0.0001 ? 1.0 : 0.0;
    return sunValid > 0.5 ? sunVisibility / max(sunWeight, 0.0001) : 1.0;
}

float shadowDepth(int cascade, vec2 uv)
{
    float depth = 1.0;
    if (cascade == 0)      { depth = texture2DLod(s_customShadow0, uv, 0.0).x; }
    else if (cascade == 1) { depth = texture2DLod(s_customShadow1, uv, 0.0).x; }
    else if (cascade == 2) { depth = texture2DLod(s_customShadow2, uv, 0.0).x; }
    else                   { depth = texture2DLod(s_customShadow3, uv, 0.0).x; }
    float npcCascades = u_customNpcShadow[0].x;
    if (float(cascade) < npcCascades)
    {
        vec4 area = u_customNpcShadow[1 + cascade];
        if (uv.x >= area.x && uv.y >= area.y && uv.x <= area.z && uv.y <= area.w)
        {
            bool bottomUp = u_customNpcShadow[0].w > 0.5;
            float top = (bottomUp ? 1.0 - uv.y : uv.y) * u_customNpcShadow[0].z;
            vec2 npcUv = vec2((clamp(uv.x, 0.0, 1.0) + float(cascade)) / npcCascades, bottomUp ? 1.0 - top : top);
            depth = min(depth, texture2DLod(s_customNpcShadow, npcUv, 0.0).x);
        }
    }
    return depth;
}

float shadowCompareBilinear(int cascade, vec2 uv, float receiver, float texel)
{
    vec2 samplePosition = uv / texel - vec2_splat(0.5);
    vec2 base = floor(samplePosition);
    vec2 fraction = fract(samplePosition);
    vec2 baseUv = (base + vec2_splat(0.5)) * texel;
    float visible00 = step(receiver, shadowDepth(cascade, baseUv));
    float visible10 = step(receiver, shadowDepth(cascade, baseUv + vec2(texel, 0.0)));
    float visible01 = step(receiver, shadowDepth(cascade, baseUv + vec2(0.0, texel)));
    float visible11 = step(receiver, shadowDepth(cascade, baseUv + vec2_splat(texel)));
    return mix(
        mix(visible00, visible10, fraction.x),
        mix(visible01, visible11, fraction.x),
        fraction.y
    );
}

float cascadeVisibility(int cascade, vec3 world)
{
    if (u_customShadowParams[3][cascade] < 0.5)
    {
        return 1.0;
    }
    vec4 clip = shadowClip(cascade, world);
    vec3 projected = clip.xyz / max(abs(clip.w), 0.000001) * sign(clip.w);
    vec2 uv = vec2(
        projected.x * 0.5 + 0.5,
        u_customShadowParams[2].z > 0.5
            ? projected.y * 0.5 + 0.5 : 0.5 - projected.y * 0.5
    );
    if (uv.x <= 0.0 || uv.y <= 0.0 || uv.x >= 1.0 || uv.y >= 1.0
        || projected.z <= 0.0 || projected.z >= 1.0)
    {
        return 1.0;
    }
    float texel = u_customShadowParams[2].x;
    float kernelGuard = texel * 4.0;
    if (uv.x <= kernelGuard || uv.y <= kernelGuard
        || uv.x >= 1.0 - kernelGuard || uv.y >= 1.0 - kernelGuard)
    {
        return 1.0;
    }
    float bias = u_customShadowParams[4][cascade];
    float receiver = projected.z - bias;
    float blockerSum = 0.0;
    float blockerCount = 0.0;
    for (int y = -1; y <= 1; ++y)
    {
        for (int x = -1; x <= 1; ++x)
        {
            float sampleValue = shadowDepth(cascade, uv + vec2(float(x), float(y)) * texel * 2.0);
            if (sampleValue < receiver)
            {
                blockerSum += sampleValue;
                blockerCount += 1.0;
            }
        }
    }
    if (blockerCount < 0.5)
    {
        return 1.0;
    }
    float blocker = blockerSum / blockerCount;
    float penumbra = clamp((receiver - blocker) * u_customShadowParams[5][cascade], 0.0, texel * 3.0);
    float visible = 0.0;
    for (int py = -1; py <= 1; ++py)
    {
        for (int px = -1; px <= 1; ++px)
        {
            visible += shadowCompareBilinear(cascade, uv + vec2(float(px), float(py)) * 0.5 * penumbra, receiver, texel);
        }
    }
    return visible / 9.0;
}

float sunShadowVisibility(vec3 world)
{
    float cascadeDistance = length(world - u_customShadowParams[1].xyz);
    if (u_customShadowParams[2].w < 0.5
        || cascadeDistance > u_customShadowParams[0].w)
    {
        return 1.0;
    }
    int cascade = cascadeDistance < u_customShadowParams[0].x ? 0
        : cascadeDistance < u_customShadowParams[0].y ? 1
        : cascadeDistance < u_customShadowParams[0].z ? 2 : 3;
    float visibility = cascadeVisibility(cascade, world);
    if (cascade >= 3)
    {
        float fadeStart = mix(
            u_customShadowParams[0].z, u_customShadowParams[0].w, 0.78
        );
        return mix(
            visibility, 1.0,
            smoothstep(fadeStart, u_customShadowParams[0].w, cascadeDistance)
        );
    }
    float nearSplit = cascade == 0 ? 0.0 : u_customShadowParams[0][cascade - 1];
    float farSplit = u_customShadowParams[0][cascade];
    float blend = smoothstep(
        farSplit - (farSplit - nearSplit) * 0.08, farSplit, cascadeDistance
    );
    return mix(visibility, cascadeVisibility(cascade + 1, world), blend);
}

vec2 npcTilesUv(vec2 tiles)
{
    float top = u_customNpcShadow[8].y + (0.5 - 0.5 * tiles.y) * u_customNpcShadow[8].z;
    return vec2((0.5 + 0.5 * tiles.x) * u_customNpcShadow[8].x, u_customNpcShadow[6].z > 0.5 ? 1.0 - top : top);
}

float npcTileVisibility(int tile, vec3 light)
{
    bool isNear = float(tile) < u_customNpcShadow[6].x;
    vec4 area = u_customNpcShadow[22 + tile];
    vec2 local = (light.xy - area.xy) * area.z;
    vec4 params = u_customNpcShadow[102 + tile];
    float depth = (light.z - area.w) * params.w;
    float guard = isNear ? u_customNpcShadow[9].z : u_customNpcShadow[9].w;
    if (abs(local.x) >= 1.0 - guard || abs(local.y) >= 1.0 - guard || depth <= 0.0 || depth >= 1.0)
    {
        return 1.0;
    }
    vec2 scale = isNear ? u_customNpcShadow[10].xy : u_customNpcShadow[10].zw;
    vec2 uv = npcTilesUv(local * scale + params.xy);
    float receiver = depth - params.w;
    float lit = 0.0;
    for (int y = 0; y < 4; ++y)
    {
        for (int x = 0; x < 4; ++x)
        {
            vec2 offset = vec2(float(x) - 1.5, float(y) - 1.5) * 1.5 * u_customNpcShadow[9].xy;
            lit += step(receiver, texture2DLod(s_customNpcShadow, uv + offset, 0.0).x);
        }
    }
    return lit / 16.0;
}

float npcOwnVisibility(vec3 world)
{
    if (u_customNpcShadow[0].y < 0.5)
    {
        return 1.0;
    }
    vec3 light = vec3(
        dot(world, u_customNpcShadow[3].xyz),
        dot(world, u_customNpcShadow[4].xyz),
        dot(world, u_customNpcShadow[5].xyz)
    );
    vec4 all = u_customNpcShadow[11];
    if (light.x <= all.x || light.y <= all.y || light.x >= all.z || light.y >= all.w)
    {
        return 1.0;
    }
    int groups = int(u_customNpcShadow[7].w + 0.5);
    float visible = 1.0;
    for (int group = 0; group < 10; ++group)
    {
        if (group >= groups)
        {
            break;
        }
        vec4 square = u_customNpcShadow[12 + group];
        if (light.x <= square.x || light.y <= square.y || light.x >= square.z || light.y >= square.w)
        {
            continue;
        }
        int first = int(u_customNpcShadow[182 + group].x + 0.5);
        int end = int(u_customNpcShadow[182 + group].y + 0.5);
        for (int index = 0; index < 8; ++index)
        {
            int tile = first + index;
            if (tile >= end)
            {
                break;
            }
            visible = min(visible, npcTileVisibility(tile, light));
        }
    }
    return visible;
}

int localShadowFace(vec3 direction)
{
    vec3 magnitude = abs(direction);
    if (magnitude.x >= magnitude.y && magnitude.x >= magnitude.z)
    {
        return direction.x >= 0.0 ? 0 : 1;
    }
    if (magnitude.y >= magnitude.z)
    {
        return direction.y >= 0.0 ? 2 : 3;
    }
    return direction.z >= 0.0 ? 4 : 5;
}

vec4 localShadowClip(int lightIndex, int face, vec3 world)
{
    int base = (lightIndex * 6 + face) * 4;
    return u_customLocalMatrices[base] * world.x
        + u_customLocalMatrices[base + 1] * world.y
        + u_customLocalMatrices[base + 2] * world.z
        + u_customLocalMatrices[base + 3];
}

float localLightVisibility(int lightIndex, vec3 world)
{
    vec4 light = u_customLocalParams[lightIndex];
    vec3 direction = world - light.xyz;
    float distanceToLight = length(direction);
    if (distanceToLight <= 18.0 || distanceToLight >= light.w)
    {
        return 1.0;
    }
    int face = localShadowFace(direction);
    vec4 clip = localShadowClip(lightIndex, face, world);
    vec3 projected = clip.xyz / max(abs(clip.w), 0.000001) * sign(clip.w);
    if (projected.x <= -1.0 || projected.y <= -1.0
        || projected.x >= 1.0 || projected.y >= 1.0
        || projected.z <= 0.0 || projected.z >= 1.0)
    {
        return 1.0;
    }
    float originBottomLeft = fract(u_customLocalAtlas.w) > 0.1 ? 1.0 : 0.0;
    vec2 faceUv = vec2(
        projected.x * 0.5 + 0.5,
        originBottomLeft > 0.5 ? projected.y * 0.5 + 0.5 : 0.5 - projected.y * 0.5
    );
    float tileSize = u_customLocalAtlas.z;
    uint faceIndex = uint(face);
    vec2 tile = vec2(float(faceIndex % 3u), float(uint(lightIndex) * 2u + faceIndex / 3u));
    vec2 tileMin = (tile * tileSize + vec2_splat(1.5)) * u_customLocalAtlas.xy;
    vec2 tileMax = ((tile + vec2_splat(1.0)) * tileSize - vec2_splat(1.5)) * u_customLocalAtlas.xy;
    vec2 atlasUv = (tile * tileSize + faceUv * tileSize) * u_customLocalAtlas.xy;
    float receiver = projected.z - 0.0012;
    float visible = 0.0;
    for (int y = -1; y <= 1; ++y)
    {
        for (int x = -1; x <= 1; ++x)
        {
            vec2 sampleUv = clamp(
                atlasUv + vec2(float(x), float(y)) * u_customLocalAtlas.xy,
                tileMin, tileMax
            );
            visible += step(receiver, texture2DLod(s_customLocalShadow, sampleUv, 0.0).x);
        }
    }
    return visible / 9.0;
}

float localShadowVisibility(vec3 world)
{
    int lightCount = int(floor(u_customLocalAtlas.w + 0.01));
    float visibility = 1.0;
    for (int lightIndex = 0; lightIndex < 2; ++lightIndex)
    {
        if (lightIndex >= lightCount)
        {
            continue;
        }
        vec4 light = u_customLocalParams[lightIndex];
        vec4 color = u_customLocalParams[2 + lightIndex];
        vec4 spot = u_customLocalParams[4 + lightIndex];
        float radial = saturate(1.0 - length(world - light.xyz) / max(light.w, 1.0));
        float cone = 1.0;
        if (spot.w >= 0.0)
        {
            float lightToSurface = dot(normalize(world - light.xyz), normalize(spot.xyz));
            cone = saturate((lightToSurface - spot.w) / max(1.0 - spot.w, 0.0001));
            cone *= cone;
        }
        float luminance = dot(color.rgb, vec3(0.2126, 0.7152, 0.0722));
        float directWeight = saturate(luminance * radial * radial * cone * color.w);
        visibility *= mix(1.0, localLightVisibility(lightIndex, world), directWeight);
    }
    return visibility;
}

vec3 localDirectLight(int lightIndex, int lightCount, vec3 world, vec3 normal)
{
    if (lightIndex < 0 || lightIndex >= lightCount)
    {
        return vec3_splat(0.0);
    }
    vec4 light = u_customDirectLocalParams[lightIndex];
    vec4 color = u_customDirectLocalParams[16 + lightIndex];
    vec4 spot = u_customDirectLocalParams[32 + lightIndex];
    vec3 toLight = light.xyz - world;
    float distanceToLight = length(toLight);
    vec3 lightDirection = distanceToLight > 0.0001
        ? toLight / distanceToLight : normal;
    float radial = saturate(1.0 - distanceToLight / max(light.w, 1.0));
    float cone = 1.0;
    if (spot.w >= 0.0)
    {
        float lightToSurface = dot(-lightDirection, normalize(spot.xyz));
        cone = saturate((lightToSurface - spot.w) / max(1.0 - spot.w, 0.0001));
        cone *= cone;
    }
    float lambert = max(dot(normal, lightDirection), 0.0);
    float visibility = 1.0;
    int shadowLightCount = int(floor(u_customLocalAtlas.w + 0.01));
    for (int shadowIndex = 0; shadowIndex < 2; ++shadowIndex)
    {
        if (shadowIndex >= shadowLightCount)
        {
            continue;
        }
        vec4 shadowLight = u_customLocalParams[shadowIndex];
        vec4 shadowColor = u_customLocalParams[2 + shadowIndex];
        vec4 shadowSpot = u_customLocalParams[4 + shadowIndex];
        vec4 identityDelta = abs(light - shadowLight);
        vec4 colorDelta = abs(color - shadowColor);
        vec4 spotDelta = abs(spot - shadowSpot);
        if (max(max(identityDelta.x, identityDelta.y), identityDelta.z) < 0.25
            && identityDelta.w < 0.25
            && max(max(colorDelta.x, colorDelta.y), colorDelta.z) < 0.001
            && max(max(spotDelta.x, spotDelta.y), spotDelta.z) < 0.001
            && spotDelta.w < 0.001)
        {
            visibility = localLightVisibility(shadowIndex, world);
            break;
        }
    }
    return hfLightToLinearEnergy(color.rgb)
        * color.w * radial * radial * cone * lambert * visibility;
}

float localTileComponent(vec4 values, int componentIndex)
{
    if (componentIndex == 0) return values.x;
    if (componentIndex == 1) return values.y;
    if (componentIndex == 2) return values.z;
    return values.w;
}

vec3 localDirectLighting(vec3 world, vec3 normal, vec2 screenUv)
{
    int lightCount = int(floor(u_customDirectLocalParams[48].x + 0.01));
    vec4 tileMetadata = u_customDirectLocalParams[48];
    vec3 direct = vec3_splat(0.0);
    bool tiled = false;
    if (tileMetadata.w > 0.5)
    {
        vec2 topDownUv = vec2(
            screenUv.x,
            u_customLighting[2].z > 0.5 ? 1.0 - screenUv.y : screenUv.y
        );
        vec2 activePixels = u_customLighting[0].zw
            / max(u_customLighting[0].xy, vec2_splat(0.000001));
        vec2 tileCoordinate = floor(
            clamp(topDownUv, vec2_splat(0.0), vec2_splat(0.999999)) * activePixels / 32.0
        );
        vec2 tileUv = (tileCoordinate + vec2_splat(0.5)) * tileMetadata.yz;
        vec4 firstCodes = floor(texture2D(s_customDirectLocalTiles0, tileUv) * 255.0 + 0.5);
        if (firstCodes.x < 254.5)
        {
            vec4 secondCodes = floor(texture2D(s_customDirectLocalTiles1, tileUv) * 255.0 + 0.5);
            for (int slot = 0; slot < 8; ++slot)
            {
                float code = slot < 4
                    ? localTileComponent(firstCodes, slot)
                    : localTileComponent(secondCodes, slot - 4);
                direct += localDirectLight(int(code) - 1, lightCount, world, normal);
            }
            tiled = true;
        }
    }

    if (!tiled)
    {
        for (int lightIndex = 0; lightIndex < 16; ++lightIndex)
        {
            direct += localDirectLight(lightIndex, lightCount, world, normal);
        }
    }
    return direct;
}

vec3 realisticWorldNormal(vec2 sourceUv)
{
    vec3 viewNormal = texture2D(s_customLightingNormal, sourceUv).rgb * 2.0 - 1.0;
    vec3 worldNormal = u_customInverseView[0].xyz * viewNormal.x
        + u_customInverseView[1].xyz * viewNormal.y
        + u_customInverseView[2].xyz * viewNormal.z;
    float normalLength = length(worldNormal);
    return normalLength > 0.0001 ? worldNormal / normalLength : vec3(0.0, 0.0, 1.0);
}


vec4 giProbeFetch(vec3 cell, float face, int level, bool previous)
{
    float index = cell.x + cell.y * 12.0 + cell.z * 144.0;
    vec2 uv = vec2((mod(index,144.0)+0.5)/144.0, (float(level)*72.0+floor(index/144.0)*12.0+face+0.5)/288.0);
    if (previous) return texture2DLod(s_customGiPrevious, uv, 0.0);
    return texture2DLod(s_customGi, uv, 0.0);
}
vec4 giDirectional(vec3 cell, vec3 direction, float offset, int level, bool previous)
{
    vec3 w = direction * direction;
    return giProbeFetch(cell, (direction.x >= 0.0 ? 0.0 : 1.0) + offset, level, previous) * w.x
        + giProbeFetch(cell, (direction.y >= 0.0 ? 2.0 : 3.0) + offset, level, previous) * w.y
        + giProbeFetch(cell, (direction.z >= 0.0 ? 4.0 : 5.0) + offset, level, previous) * w.z;
}
vec4 giField(vec3 world, vec3 normal, vec4 origin, int level, bool previous)
{
    vec3 position = world + normal * 12.0;
    vec3 grid = (position - origin.xyz) / origin.w;
    vec3 limit = vec3(11.0,11.0,5.0);
    if (any(lessThan(grid,vec3_splat(0.0))) || any(greaterThanEqual(grid,limit))) return vec4_splat(0.0);
    vec3 base = floor(grid), fraction = fract(grid), sum = vec3_splat(0.0);
    float total = 0.0;
    for (int i=0; i<8; ++i) {
        vec3 corner = vec3(float(i & 1), float((i >> 1) & 1), float(i >> 2));
        vec3 cell = base+corner;
        vec3 delta = origin.xyz + cell*origin.w-position;
        float distanceToProbe = length(delta);
        vec3 direction = delta/max(distanceToProbe,0.001);
        vec3 trilinear = mix(vec3_splat(1.0)-fraction,fraction,corner);
        float weight = trilinear.x*trilinear.y*trilinear.z;
        weight *= pow(max(0.05,dot(normal,direction)),2.0);
        vec4 moments = giDirectional(cell,-direction,6.0,level,previous);
        float distanceInCells = distanceToProbe/origin.w;
        float variance = max(0.001,moments.y-moments.x*moments.x);
        float difference = max(0.0,distanceInCells-moments.x);
        float visibility = variance/(variance+difference*difference);
        weight *= visibility*visibility*visibility*moments.a;
        vec4 irradiance = giDirectional(cell,normal,0.0,level,previous);
        sum += irradiance.rgb*weight;
        total += weight;
    }
    float confidence = smoothstep(0.000001,0.0001,total);
    vec3 edge = min(grid,limit-grid);
    float fade = smoothstep(0.0,1.0,min(edge.x,min(edge.y,edge.z)));
    return vec4(sum/max(total,0.000000000001),fade*confidence);
}
vec4 giLevel(vec3 world, vec3 normal, int level)
{
    int base = level * 4;
    if (u_customGi[base+1].y < 0.5) return vec4_splat(0.0);
    vec4 current = giField(world, normal, u_customGi[base], level, false);
    float blend = u_customGi[base+1].z;
    if (blend >= 1.0) return current;
    vec4 previous = vec4_splat(0.0);
    if (u_customGi[base+1].w > 0.5)
        previous = giField(world, normal, u_customGi[base+2], level, true);
    float oldWeight = previous.a*(1.0-blend), newWeight = current.a*blend;
    float coverage = oldWeight + newWeight;
    return vec4((previous.rgb*oldWeight + current.rgb*newWeight) / max(coverage,0.00001),coverage);
}
vec4 giIrradiance(vec3 world, vec3 normal)
{
    if (u_customGi[15].x < 0.5) return vec4_splat(0.0);
    vec3 sum = vec3_splat(0.0);
    float remaining = 1.0;
    for (int level=0; level<4; ++level) {
        vec4 value = giLevel(world,normal,level);
        float weight = value.a*remaining;
        sum += value.rgb*weight;
        remaining *= 1.0-value.a;
    }
    float coverage = 1.0-remaining;
    return vec4(sum/max(coverage,0.00001)*u_customGi[15].y,coverage);
}

void main()
{
    vec2 screenUv = v_texcoord0;
    vec2 sourceUv = screenUv * u_customLighting[0].zw;
    vec4 source = texture2D(s_customLightingSource, sourceUv);
    vec4 material = texture2D(s_customWaterMask, sourceUv);
    if (material.a > 0.99)
    {
        gl_FragColor = source;
        return;
    }
    float depth = texture2D(s_customLightingDepth, sourceUv).x;
    if (depth >= 0.999999)
    {
        gl_FragColor = source;
        return;
    }
    vec3 viewPosition = reconstructViewPosition(screenUv, depth);
    vec3 worldPosition = reconstructWorldPosition(screenUv, depth);
    vec3 receiverNormalRaw = cross(dFdx(worldPosition), dFdy(worldPosition));
    float receiverNormalLength = length(receiverNormalRaw);
    vec3 receiverNormal = receiverNormalLength > 0.000001
        ? receiverNormalRaw / receiverNormalLength : vec3_splat(0.0);
    vec3 rayCameraToReceiver = worldPosition - u_customRayReprojection[8].xyz;
    if (dot(receiverNormal, rayCameraToReceiver) > 0.0)
    {
        receiverNormal = -receiverNormal;
    }
    float raySunValid = 0.0;
    float raySunVisibility = 1.0;
    if (u_customLighting[2].w > 0.5)
    {
        raySunVisibility = raySunVisibilityAt(
            worldPosition, receiverNormal, raySunValid
        );
    }
    float raySunRange = 1.0 - smoothstep(
        26000.0, 32000.0,
        length(worldPosition - u_customRayReprojection[8].xyz)
    );
    float hardwareSunActive = raySunValid;
    hardwareSunActive *= clamp(u_customLighting[2].w, 0.0, 1.0) * raySunRange;
    float rasterSunShadow = min(sunShadowVisibility(worldPosition), npcOwnVisibility(worldPosition));
    float raySunObservedOccluder = 1.0 - step(0.9999, raySunVisibility);
    float rayRefinedSunShadow = max(rasterSunShadow, raySunVisibility);
    float sunShadow = mix(
        rasterSunShadow,
        rayRefinedSunShadow,
        hardwareSunActive * raySunObservedOccluder
    );
    float localShadow = localShadowVisibility(worldPosition);
    float sunWeight = clamp(u_customShadowParams[1].w, 0.0, 1.0);
    float directionalVisibility = mix(1.0, sunShadow, sunWeight);
    float visibility = mix(1.0, min(sunShadow, localShadow), sunWeight);
    bool preservedBspReceiver = material.a > 0.05 && material.a < 0.125;
    if (u_customRealisticLighting[0].x > 0.5
        && (material.a > 0.125 || (preservedBspReceiver && u_customGi[15].x > 0.5)))
    {
        bool terrainReceiver = material.a >= (159.5 / 255.0)
            && material.a < (223.5 / 255.0);
        bool preservedStaticReceiver = material.a >= (223.5 / 255.0)
            && material.a < 0.99;
        vec3 worldNormal = realisticWorldNormal(sourceUv);
        vec4 indirectGi = giIrradiance(worldPosition, worldNormal);
        if (preservedBspReceiver && indirectGi.a <= 0.0) {
            vec3 fog = u_customRealisticLighting[1].rgb;
            vec3 shaded = source.rgb * visibility + fog * (1.0 - visibility)
                * (1.0 - authoredFogTransmittance(viewPosition));
            gl_FragColor = vec4(shaded, source.a);
            return;
        }
        if ((terrainReceiver || preservedStaticReceiver) && indirectGi.a <= 0.0)
        {
            float clientSunResponse = terrainReceiver
                ? clamp((material.a * 255.0 - 160.0) / 63.0, 0.0, 1.0)
                : clamp((material.a * 255.0 - 224.0) / 27.0, 0.0, 1.0) * 2.0;
            vec3 clientSun = terrainReceiver
                ? u_customRealisticLighting[7].rgb : u_customRealisticLighting[3].rgb;
            vec3 knownOccludedDiffuse = material.rgb * clientSun * clientSunResponse
                * (1.0 - directionalVisibility);
            float authoredFogFactor = authoredFogTransmittance(viewPosition);
            vec3 preserved = source.rgb - knownOccludedDiffuse * authoredFogFactor;
            gl_FragColor = vec4(clamp(preserved, 0.0, 1.0), source.a);
            return;
        }
        vec4 materialAux = texture2D(s_customLightingMaterialAux, sourceUv);
        vec3 ambient = preservedBspReceiver ? u_customRealisticLighting[4].rgb
            : (material.a < 0.375 || preservedStaticReceiver) ? u_customRealisticLighting[2].rgb
            : material.a < 0.625 ? u_customRealisticLighting[4].rgb
            : u_customRealisticLighting[6].rgb;
        vec3 sunColor = preservedBspReceiver ? u_customRealisticLighting[5].rgb
            : (material.a < 0.375 || preservedStaticReceiver) ? u_customRealisticLighting[3].rgb
            : material.a < 0.625 ? u_customRealisticLighting[5].rgb
            : u_customRealisticLighting[7].rgb;
        float sunLambert = max(dot(worldNormal, normalize(u_customLighting[3].xyz)), 0.0);
        vec3 directLocal = localDirectLighting(worldPosition, worldNormal, screenUv);
        float realisticDirectionalVisibility = mix(
            1.0, sunShadow, clamp(u_customLighting[3].w, 0.0, 1.0)
        );
        vec3 ambientLinear = hfLightToLinearEnergy(ambient);
        vec3 sunLinear = hfLightToLinearEnergy(sunColor);
        float environmentExposure = 1.0
            / max(maxComponent(ambientLinear + sunLinear), 1.0);
        vec3 realisticIllumination =
            (ambientLinear
                + sunLinear * sunLambert * realisticDirectionalVisibility)
                    * environmentExposure
                + directLocal;
        float giAmbientWeight = (terrainReceiver || preservedStaticReceiver || preservedBspReceiver)
            ? 1.0 : indirectGi.a;
        realisticIllumination += (indirectGi.rgb - ambientLinear * environmentExposure) * giAmbientWeight;
        vec3 relitLinear = srgbToLinear(clamp(material.rgb, 0.0, 1.0))
            * realisticIllumination;
        if (materialAux.a > 0.125)
        {
            bool materialAux2x = materialAux.a > 0.625;
            bool materialAuxLit = (materialAux.a > 0.375 && materialAux.a < 0.625)
                || materialAux.a > 0.875;
            vec3 auxLinear = srgbToLinear(clamp(materialAux.rgb, 0.0, 1.0));
            auxLinear *= materialAux2x ? 2.0 : 1.0;
            relitLinear += auxLinear * (materialAuxLit ? realisticIllumination : vec3_splat(1.0));
        }
        vec3 relit = linearToSrgb(relitLinear);
        if (u_customRealisticLighting[0].y > 0.5)
        {
            float fogFactor = authoredFogTransmittance(viewPosition);
            relit = mix(u_customRealisticLighting[1].rgb, relit, fogFactor);
        }
        if (terrainReceiver || preservedStaticReceiver || preservedBspReceiver) {
            vec3 authored = source.rgb;
            if (preservedBspReceiver) {
                authored = source.rgb * visibility + u_customRealisticLighting[1].rgb * (1.0 - visibility)
                    * (1.0 - authoredFogTransmittance(viewPosition));
            } else {
                float response = terrainReceiver ? clamp((material.a*255.0-160.0)/63.0,0.0,1.0)
                    : clamp((material.a*255.0-224.0)/27.0,0.0,1.0)*2.0;
                vec3 clientSun = terrainReceiver ? u_customRealisticLighting[7].rgb : u_customRealisticLighting[3].rgb;
                authored -= material.rgb * clientSun * response * (1.0-directionalVisibility)
                    * authoredFogTransmittance(viewPosition);
            }
            relit = mix(authored,relit,indirectGi.a);
        }
        gl_FragColor = vec4(clamp(relit, 0.0, 1.0), source.a);
        return;
    }
    float authoredFogFactor = authoredFogTransmittance(viewPosition);
    vec3 shadowedSource = source.rgb * visibility;
    vec3 fogProtectedSource = shadowedSource
        + u_customRealisticLighting[1].rgb
            * (1.0 - authoredFogFactor) * (1.0 - visibility);
    gl_FragColor = vec4(clamp(fogProtectedSource, 0.0, 1.0), source.a);
}
