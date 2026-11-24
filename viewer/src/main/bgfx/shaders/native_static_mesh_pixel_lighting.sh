#include "native_shader_power.sh"
#include "native_static_mesh_lightmap.sh"

vec3 nativeStaticMeshDirectionalDiffuse(vec3 normal, vec3 lightDirection, vec3 lightColor)
{
    return lightColor * clamp(dot(normal, lightDirection), 0.0, 1.0);
}

float nativeStaticMeshHighlight(vec3 lightDirection, vec3 reflection, float power)
{
    return nativeShaderPower(clamp(dot(lightDirection, reflection), 0.0, 1.0), power);
}

vec3 nativeStaticMeshPointLighting(vec4 light, vec3 normal, vec3 reflection, vec3 lightColor,
    float range, float power, inout vec3 specular)
{
    if (!(light.w < range)) return vec3_splat(0.0);
    vec3 direction = normalize(light.xyz);
    float ratio = light.w / range;
    float brightness = dot(vec3(ratio * ratio * ratio, ratio * ratio, 1.0), vec3(4.0, -6.0, 2.0));
    specular += vec3_splat(brightness * nativeStaticMeshHighlight(direction, reflection, power));
    return brightness * nativeStaticMeshDirectionalDiffuse(normal, direction, lightColor);
}

void nativeStaticMeshDynamicLighting(inout vec3 diffuse, inout vec3 specular,
    vec3 normal, vec3 reflection, float power, vec3 flags, vec3 sunColor,
    vec4 light1, vec4 light2, vec3 pointColor0, vec3 pointColor1, vec2 ranges)
{
    if (flags.x == 1.0)
    {
        vec3 direction = normalize(light1.xyz);
        diffuse += nativeStaticMeshDirectionalDiffuse(normal, direction, sunColor);
        specular = vec3_splat(nativeStaticMeshHighlight(direction, reflection, power));
    }
    else if (flags.z == 1.0)
    {
        diffuse += nativeStaticMeshPointLighting(light1, normal, reflection, pointColor1, ranges.y, power, specular);
    }
    if (flags.y == 1.0)
    {
        diffuse += nativeStaticMeshPointLighting(light2, normal, reflection, pointColor0, ranges.x, power, specular);
    }
}
