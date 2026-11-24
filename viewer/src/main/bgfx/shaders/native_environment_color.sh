vec3 nativeEnvironmentColorMerge(vec3 base, vec3 environment, vec3 vertexColor, bool useVertexColor)
{
    uvec3 baseBytes = uvec3(floor(clamp(base, 0.0, 1.0) * 255.0 + 0.5));
    uvec3 environmentBytes = uvec3(floor(clamp(environment, 0.0, 1.0) * 255.0 + 0.5));
    uint packedBase = baseBytes.b | (baseBytes.g << 8u) | (baseBytes.r << 16u);
    uint packedEnvironment = environmentBytes.b | (environmentBytes.g << 8u) | (environmentBytes.r << 16u);
    uint combined = packedBase + packedEnvironment;
    uvec3 rgb = uvec3((combined >> 16u) & 255u, (combined >> 8u) & 255u, combined & 255u);
    if (useVertexColor)
    {
        uvec3 multiplier = uvec3(floor(clamp(vertexColor, 0.0, 1.0) * 255.0 + 0.5));
        rgb = (rgb * multiplier) / 255u;
    }
    return vec3(rgb) / 255.0;
}
