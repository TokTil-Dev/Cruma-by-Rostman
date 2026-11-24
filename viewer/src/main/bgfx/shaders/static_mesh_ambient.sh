float staticMeshAmbientByte(float channel, float intensity)
{
    float value = floor(channel * 255.0 + 0.5) * intensity;
    float result = 0.0;
    if (!(value != value || value >= 2147483648.0 || value < -2147483648.0))
        result = float(uint(int(value)) & 255u) / 255.0;
    return result;
}

vec4 scaleStaticMeshAmbient(vec4 ambient, float intensity)
{
    vec4 result = ambient;
    if (intensity != 1.0)
    {
        result.rgb = vec3(staticMeshAmbientByte(ambient.r, intensity),
                          staticMeshAmbientByte(ambient.g, intensity),
                          staticMeshAmbientByte(ambient.b, intensity));
    }
    return result;
}

uint staticMeshAmbientTruncatedByte(float value)
{
    uint result = 0u;
    if (!(value != value || value >= 2147483648.0 || value < -2147483648.0))
        result = uint(int(value)) & 255u;
    return result;
}

vec4 staticMeshAmbientUnpack(uint argb)
{
    return vec4(float((argb >> 16u) & 255u), float((argb >> 8u) & 255u),
                float(argb & 255u), float(argb >> 24u)) / 255.0;
}

vec4 selectStaticMeshAmbient(vec3 data, vec3 environment, vec3 beastEnvironment,
                            vec3 sun, float beastScale, float seconds)
{
    uint control = uint(data.y);
    uint source = control & 7u;
    uint actor = uint(data.z);
    vec3 rgb = vec3(float(actor & 255u), float((actor >> 8u) & 255u), float((actor >> 16u) & 255u));
    if (source == 1u)
    {
        if ((control & 8u) != 0u)
        {
            float glow = sin(seconds * 8.0) * 0.2 + 0.25;
            rgb = floor(clamp(rgb / 255.0 + glow, 0.0, 1.0) * 255.0);
        }
        rgb = floor(rgb * 0.5);
    }
    else if (source == 2u || source == 3u)
    {
        rgb = floor(floor(environment * 255.0 + 0.5) * 0.5);
        if (source == 3u)
        {
            vec3 gain = vec3(float(staticMeshAmbientTruncatedByte(sun.r * 32.0)),
                             float(staticMeshAmbientTruncatedByte(sun.g * 32.0)),
                             float(staticMeshAmbientTruncatedByte(sun.b * 32.0)));
            rgb = min(rgb + gain, vec3(255.0, 255.0, 255.0));
        }
    }
    else
    {
        rgb = floor(beastEnvironment * 255.0 + 0.5);
    }
    vec4 ambient = vec4(rgb / 255.0, 1.0);
    if (source == 5u)
    {
        uint packed = 0xff000000u | (uint(rgb.r) << 16u) | (uint(rgb.g) << 8u) | uint(rgb.b);
        float gain = beastScale * 0.6;
        uint byteGain = uint(clamp(floor(gain * 255.0), 0.0, 255.0));
        uint added = 0xff000000u | (byteGain << 16u) | (byteGain << 8u) | byteGain;
        ambient = staticMeshAmbientUnpack(packed + added);
    }
    return scaleStaticMeshAmbient(ambient, data.x);
}
