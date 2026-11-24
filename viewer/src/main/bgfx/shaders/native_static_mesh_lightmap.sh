vec3 nativeStaticMeshLightmapBasis(vec3 value)
{
    return vec3(dot(vec3(0.0, 0.8164966106414795, 0.5773502588272095), value),
        dot(vec3(-0.7071067690849304, -0.40824830532073975, 0.5773502588272095), value),
        dot(vec3(0.7071067690849304, -0.40824830532073975, 0.5773502588272095), value));
}

void nativeStaticMeshNormalLighting(vec3 sampleColor, vec3 eye, vec3 lightmap0, vec3 lightmap1,
    vec3 lightmap2, float power, inout vec3 diffuse, out vec3 specular, out vec3 normal, out vec3 reflection)
{
    normal = 2.0 * sampleColor - vec3_splat(1.0);
    reflection = nativeStaticMeshLightmapBasis(normalize(reflect(eye, normal)));
    normal = clamp(nativeStaticMeshLightmapBasis(normal), vec3_splat(0.0), vec3_splat(1.0));
    diffuse += clamp(normal.x * lightmap0, vec3_splat(0.0), vec3_splat(1.0));
    diffuse += clamp(normal.y * lightmap1, vec3_splat(0.0), vec3_splat(1.0));
    diffuse += clamp(normal.z * lightmap2, vec3_splat(0.0), vec3_splat(1.0));
    vec3 transfer = vec3(nativeShaderPower(reflection.x, power), nativeShaderPower(reflection.y, power),
        nativeShaderPower(reflection.z, power));
    specular = clamp(transfer.x * lightmap0, vec3_splat(0.0), vec3_splat(1.0))
        + clamp(transfer.y * lightmap1, vec3_splat(0.0), vec3_splat(1.0))
        + clamp(transfer.z * lightmap2, vec3_splat(0.0), vec3_splat(1.0));
}
