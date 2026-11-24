float actorLightingAlpha(uint base, uint vertex)
{
    uint flags = u_actorLighting[base + 1u];
    if ((flags & 8u) == 0u) return 0.0;
    uint count = flags >> 8u;
    uint packed = u_actorLighting[base + 3u + count + vertex / 4u];
    return float((packed >> ((vertex & 3u) * 8u)) & 255u) / 255.0;
}
float actorAmbientIntensity(uint base)
{
    uint flags = u_actorLighting[base + 1u];
    float intensity = 1.0;
    if ((flags & 128u) != 0u)
    {
        uint count = flags >> 8u;
        uint alphaWords = (flags & 8u) != 0u ? (count + 3u) / 4u : 0u;
        intensity = uintBitsToFloat(u_actorLighting[base + 3u + count + alphaWords]);
    }
    return intensity;
}

vec3 actorAmbientData(uint base)
{
    uint flags = u_actorLighting[base + 1u];
    uint control = (u_actorLighting[base + 2u] >> 24u) & 15u;
    vec3 ambient = vec3(actorAmbientIntensity(base), 0.0, 0.0);
    if (control != 0u)
    {
        uint count = flags >> 8u;
        uint alphaWords = (flags & 8u) != 0u ? (count + 3u) / 4u : 0u;
        uint offset = base + 3u + count + alphaWords + ((flags & 128u) != 0u ? 1u : 0u);
        ambient.y = float(control);
        ambient.z = float(u_actorLighting[offset]);
    }
    return ambient;
}

bool actorHardwareSunPresent(uint base)
{
    return (u_actorLighting[base + 2u] & 0x80000000u) == 0u;
}

vec3 actorHardwareSun(uint base, vec3 actorSun)
{
    uint flags = u_actorLighting[base + 1u];
    uint count = flags >> 8u;
    uint alphaWords = (flags & 8u) != 0u ? (count + 3u) / 4u : 0u;
    uint ambientWords = ((u_actorLighting[base + 2u] >> 24u) & 15u) != 0u ? 1u : 0u;
    uint offset = base + 3u + count + alphaWords + ((flags & 128u) != 0u ? 1u : 0u) + ambientWords;
    uint control = u_actorLighting[base + 2u] >> 24u;
    vec3 hardwareSun = vec3_splat(0.0);
    if ((control & 64u) != 0u) {
        uint environmentOffset = offset + 3u + ((control & 16u) != 0u ? 3u : 0u);
        float levelBrightness = uintBitsToFloat(u_actorLighting[environmentOffset]);
        float lightIntensity = uintBitsToFloat(u_actorLighting[environmentOffset + 1u]);
        hardwareSun = (((actorSun * levelBrightness) * lightIntensity) * 0.875) * 2.0;
    }
    else {
        hardwareSun = vec3(uintBitsToFloat(u_actorLighting[offset]), uintBitsToFloat(u_actorLighting[offset + 1u]),
            uintBitsToFloat(u_actorLighting[offset + 2u]));
    }
    return hardwareSun;
}

vec3 actorHardwareSunDirection(uint base, vec3 environmentDirection, vec3 movableDirection)
{
    uint control = u_actorLighting[base + 2u] >> 24u;
    if ((control & 32u) != 0u) return movableDirection;
    if ((control & 16u) == 0u) return environmentDirection;
    uint flags = u_actorLighting[base + 1u];
    uint count = flags >> 8u;
    uint alphaWords = (flags & 8u) != 0u ? (count + 3u) / 4u : 0u;
    uint offset = base + 3u + count + alphaWords + ((flags & 128u) != 0u ? 1u : 0u)
        + ((control & 15u) != 0u ? 1u : 0u) + 3u;
    return vec3(uintBitsToFloat(u_actorLighting[offset]), uintBitsToFloat(u_actorLighting[offset + 1u]),
        uintBitsToFloat(u_actorLighting[offset + 2u]));
}
