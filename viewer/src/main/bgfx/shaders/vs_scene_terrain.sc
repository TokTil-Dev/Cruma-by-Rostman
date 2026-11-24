$input a_position, a_normal, a_texcoord0, a_texcoord7, a_color0, a_color1
$output v_texcoord0, v_texcoord1, v_texcoord2, v_texcoord3, v_texcoord4, v_texcoord5, v_texcoord6, v_texcoord7, v_color0, v_tangent, v_waterEye, v_waterLight, v_softDepth

#include <bgfx_shader.sh>

uniform vec4 u_sceneVertexFrame[7];
uniform vec4 u_terrainModel[4];
uniform vec4 u_terrainMaterial[8];
uniform vec4 u_terrainBaseUv[25];

#define TERRAIN_BASE_UV(_slot, _point) vec2( \
    dot(u_terrainBaseUv[2 * _slot], _point), \
    dot(u_terrainBaseUv[2 * _slot + 1], _point))

float bakedTerrainSlot(float slot, vec4 first, vec4 second)
{
    if (slot < 0.5) return first.x;
    if (slot < 1.5) return first.y;
    if (slot < 2.5) return first.z;
    if (slot < 3.5) return first.w;
    if (slot < 4.5) return second.x;
    if (slot < 5.5) return second.y;
    if (slot < 6.5) return second.z;
    return second.w;
}

float interpolatedTerrainLight(float slot, vec4 first, vec4 second)
{
    float currentSlot = floor(slot);
    float nextSlot = currentSlot >= 7.0 ? 0.0 : currentSlot + 1.0;
    float fraction = slot - currentSlot;
    float interpolated = mix(
        bakedTerrainSlot(currentSlot, first, second),
        bakedTerrainSlot(nextSlot, first, second),
        fraction
    );
    return floor(clamp(interpolated, 0.0, 1.0) * 255.0 + 0.5) / 255.0;
}

void main()
{
    mat4 model = mtxFromCols(
        u_terrainModel[0], u_terrainModel[1], u_terrainModel[2], u_terrainModel[3]
    );
    vec4 world = mul(model, vec4(a_position, 1.0));
    gl_Position = mul(u_viewProj, world);
    v_texcoord0 = a_texcoord0;
    vec4 layerPoint = vec4(a_position - u_terrainBaseUv[24].xyz, 1.0);
    vec2 firstLayer = u_terrainBaseUv[24].w > 0.5 ? a_texcoord0 : TERRAIN_BASE_UV(0, layerPoint);
    v_texcoord1 = vec4(firstLayer, TERRAIN_BASE_UV(1, layerPoint));
    v_texcoord2 = vec4(TERRAIN_BASE_UV(2, layerPoint), TERRAIN_BASE_UV(3, layerPoint));
    v_texcoord3 = vec4(TERRAIN_BASE_UV(4, layerPoint), TERRAIN_BASE_UV(5, layerPoint));
    v_texcoord4 = vec4(TERRAIN_BASE_UV(6, layerPoint), TERRAIN_BASE_UV(7, layerPoint));
    v_texcoord5 = vec4(TERRAIN_BASE_UV(8, layerPoint), TERRAIN_BASE_UV(9, layerPoint));
    v_texcoord6 = vec4(TERRAIN_BASE_UV(10, layerPoint), TERRAIN_BASE_UV(11, layerPoint));
    v_texcoord7 = vec4(
        world.xyz,
        interpolatedTerrainLight(u_sceneVertexFrame[0].z, a_color0, a_color1)
    );
    v_waterEye = vec4(0.0, 0.0, 0.0, 0.0);
    v_waterLight = vec3(0.0, 0.0, 0.0);
    v_softDepth = vec3(0.0, 0.0, 0.0);
    v_tangent = vec4(normalize(mul(u_view, vec4(a_normal.xyz, 0.0)).xyz), 1.0);

    vec3 steadyByte = floor(clamp(a_texcoord7.rgb, 0.0, 1.0) * 255.0 + 0.5);
    vec3 ambientByte = floor(clamp(u_terrainMaterial[1].rgb, 0.0, 1.0) * 255.0 + 0.5);
    vec3 ambientHalfByte = floor(ambientByte * 0.5);
    vec3 sunByte = clamp(floor(
        max(u_terrainMaterial[2].rgb, vec3(0.0, 0.0, 0.0))
            * v_texcoord7.w * 0.5 * 255.0
    ), 0.0, 255.0);
    vec3 finalByte = min(steadyByte + ambientHalfByte + sunByte, vec3(255.0, 255.0, 255.0));
    v_color0 = vec4(finalByte * (2.0 / 255.0), 1.0);
}
