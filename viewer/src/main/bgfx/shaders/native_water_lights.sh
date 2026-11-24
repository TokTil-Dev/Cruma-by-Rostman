uniform vec4 u_nativeWaterLights[21];

vec3 nativeWaterVertexPoints(vec3 position, vec3 normal)
{
    vec3 result = vec3_splat(0.0);
    for (int i = 0; i < 4; ++i)
    {
        if (float(i) >= u_nativeWaterLights[1].w) break;
        vec3 light = u_nativeWaterLights[5 + i].xyz - position;
        float intensity = clamp(dot(normalize(light), normal), 0.0, 1.0);
        float distance = length(light) + 0.000001;
        vec3 attenuation = u_nativeWaterLights[13 + i].xyz;
        intensity *= step(distance, attenuation.z);
        result += u_nativeWaterLights[9 + i].rgb * intensity / (attenuation.x + attenuation.y * distance);
    }
    return result;
}
