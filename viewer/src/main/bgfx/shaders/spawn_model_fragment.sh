SAMPLER2D(s_spawnModelDepth, 1);
SAMPLER2D(s_spawnModelShadow0, 2);
SAMPLER2D(s_spawnModelShadow1, 3);
SAMPLER2D(s_spawnModelShadow2, 4);
SAMPLER2D(s_spawnModelShadow3, 5);
SAMPLER2D(s_spawnModelSelfShadow, 6);

uniform vec4 u_spawnModelProjection;
uniform vec4 u_spawnModelLight;
uniform vec4 u_spawnModelEye;
uniform vec4 u_spawnModelParams;
uniform vec4 u_spawnModelColor;
uniform vec4 u_spawnModelBodyLights[8];
uniform vec4 u_spawnModelBodyLightCount;
uniform vec4 u_spawnModelAmbient;
uniform vec4 u_spawnModelShadowMatrices[16];
uniform vec4 u_spawnModelShadow[4];
uniform vec4 u_spawnModelSelf[6];
uniform vec4 u_spawnModelSelfAtlas[5];

float spawnModelDistance(float depth)
{
    float denominator = depth * u_spawnModelProjection.y - u_spawnModelProjection.x;
    denominator = abs(denominator) > 0.000001 ? denominator : 0.000001;
    return abs((u_spawnModelProjection.z - depth * u_spawnModelProjection.w) / denominator);
}

float spawnShadowDepth(int cascade, vec2 uv)
{
    float depth = 1.0;
    if (cascade == 0)      { depth = texture2DLod(s_spawnModelShadow0, uv, 0.0).x; }
    else if (cascade == 1) { depth = texture2DLod(s_spawnModelShadow1, uv, 0.0).x; }
    else if (cascade == 2) { depth = texture2DLod(s_spawnModelShadow2, uv, 0.0).x; }
    else                   { depth = texture2DLod(s_spawnModelShadow3, uv, 0.0).x; }
    return depth;
}

float spawnSunVisibility(vec3 world)
{
    float distanceToCentre = length(world - u_spawnModelShadow[1].xyz);
    if (u_spawnModelShadow[1].w < 0.5 || distanceToCentre > u_spawnModelShadow[0].w)
    {
        return 1.0;
    }
    int cascade = distanceToCentre < u_spawnModelShadow[0].x ? 0
        : distanceToCentre < u_spawnModelShadow[0].y ? 1
        : distanceToCentre < u_spawnModelShadow[0].z ? 2 : 3;
    int base = cascade * 4;
    vec4 clip = u_spawnModelShadowMatrices[base] * world.x
        + u_spawnModelShadowMatrices[base + 1] * world.y
        + u_spawnModelShadowMatrices[base + 2] * world.z
        + u_spawnModelShadowMatrices[base + 3];
    vec3 projected = clip.xyz / max(abs(clip.w), 0.000001) * sign(clip.w);
    vec2 uv = vec2(
        projected.x * 0.5 + 0.5,
        u_spawnModelShadow[2].z > 0.5 ? projected.y * 0.5 + 0.5 : 0.5 - projected.y * 0.5
    );
    float texel = u_spawnModelShadow[2].x;
    if (uv.x <= texel * 3.0 || uv.y <= texel * 3.0 || uv.x >= 1.0 - texel * 3.0 || uv.y >= 1.0 - texel * 3.0
        || projected.z <= 0.0 || projected.z >= 1.0)
    {
        return 1.0;
    }
    float receiver = projected.z - u_spawnModelShadow[3][cascade];
    float visible = 0.0;
    for (int y = 0; y < 4; ++y)
    {
        for (int x = 0; x < 4; ++x)
        {
            vec2 offset = vec2(float(x) - 1.5, float(y) - 1.5) * texel;
            visible += step(receiver, spawnShadowDepth(cascade, uv + offset));
        }
    }
    return visible / 16.0;
}

vec3 spawnBodyLights(vec3 world, vec3 normal)
{
    vec3 sum = vec3(0.0, 0.0, 0.0);
    for (int i = 0; i < 4; ++i)
    {
        vec4 place = u_spawnModelBodyLights[i * 2];
        vec3 toLight = place.xyz - world;
        float distanceToLight = length(toLight);
        float t = distanceToLight / max(place.w, 1.0);
        float used = step(float(i) + 0.5, u_spawnModelBodyLightCount.x) * step(t, 1.0);
        float falloff = 2.0 * ((2.0 * t - 3.0) * t * t + 1.0);
        float facing = max(dot(normal, toLight / max(distanceToLight, 0.0001)), 0.0);
        sum += u_spawnModelBodyLights[i * 2 + 1].rgb * (used * falloff * facing);
    }
    return sum;
}

vec2 spawnTilesUv(vec2 tiles)
{
    float top = u_spawnModelSelfAtlas[2].y + (0.5 - 0.5 * tiles.y) * u_spawnModelSelfAtlas[2].z;
    return vec2((0.5 + 0.5 * tiles.x) * u_spawnModelSelfAtlas[2].x, u_spawnModelSelfAtlas[0].z > 0.5 ? 1.0 - top : top);
}

float spawnSelfVisibility(vec3 world, vec3 normal)
{
    if (u_spawnModelSelfAtlas[0].w < 0.5 || u_spawnModelSelf[5].x < 0.5)
    {
        return 1.0;
    }
    float facing = clamp(dot(normal, u_spawnModelSelfAtlas[1].xyz), 0.05, 1.0);
    float slope = min(sqrt(1.0 - facing * facing) / facing, 4.0);
    float texelWorld = u_spawnModelSelf[4].z;
    vec3 lifted = world + normal * texelWorld * (1.5 + slope);
    vec4 clip = u_spawnModelSelf[0] * lifted.x
        + u_spawnModelSelf[1] * lifted.y
        + u_spawnModelSelf[2] * lifted.z
        + u_spawnModelSelf[3];
    float guard = u_spawnModelSelf[5].w;
    if (abs(clip.x) >= 1.0 - guard || abs(clip.y) >= 1.0 - guard || clip.z <= 0.0 || clip.z >= 1.0)
    {
        return 1.0;
    }
    vec2 tiles = clip.xy * u_spawnModelSelf[5].yz + u_spawnModelSelf[4].xy;
    vec2 uv = spawnTilesUv(tiles);
    float receiver = clip.z - u_spawnModelSelf[4].w * (1.0 + texelWorld * slope);
    float visible = 0.0;
    for (int y = 0; y < 4; ++y)
    {
        for (int x = 0; x < 4; ++x)
        {
            vec2 offset = vec2(float(x) - 1.5, float(y) - 1.5) * u_spawnModelSelfAtlas[3].xy;
            visible += step(receiver, texture2DLod(s_spawnModelSelfShadow, uv + offset, 0.0).x);
        }
    }
    return visible / 16.0;
}

bool spawnModelBehindScene(vec4 fragCoord)
{
    bool behind = false;
    if (u_spawnModelParams.z > 0.0)
    {
        float sceneDepth = texelFetch(s_spawnModelDepth, ivec2(fragCoord.xy), 0).x;
        if (sceneDepth < 0.999999)
        {
            float own = spawnModelDistance(fragCoord.z);
            float scene = spawnModelDistance(sceneDepth);
            float steps = abs(spawnModelDistance(min(fragCoord.z + 4.0 / 16777216.0, 0.9999999)) - own);
            behind = own - scene > u_spawnModelParams.z + steps;
        }
    }
    return behind;
}

vec3 spawnModelFacingNormal(vec3 vertexNormal, vec3 world)
{
    vec3 normal = normalize(vertexNormal);
    if (dot(normal, u_spawnModelEye.xyz - world) < 0.0)
    {
        normal = -normal;
    }
    return normal;
}

vec4 spawnModelLight(vec3 normal, vec3 world, vec3 vertexLight, vec3 vertexSun)
{
    float sun = min(spawnSunVisibility(world), spawnSelfVisibility(world, normal));
    if (u_spawnModelAmbient.w >= 0.0)
    {
        return vec4(clamp(vertexLight + vertexSun * sun, 0.0, 1.0), 2.0);
    }
    float direct = max(dot(normal, u_spawnModelLight.xyz), 0.0) * sun;
    float ambient = mix(0.28, 0.55, normal.z * 0.5 + 0.5) * mix(0.85, 1.0, sun);
    float light = (ambient + 0.75 * direct) * 1.25;
    return vec4(vec3(light, light, light) + spawnBodyLights(world, normal), 1.0);
}
