vec4 materialVertexDiffuse(vec4 color0, vec4 color1, vec4 ambient, vec3 dynamicLight,
                           bool vertexColor, bool normalMap, bool dynamicLighting)
{
    vec4 diffuse = vertexColor && !normalMap ? color0 : vec4_splat(0.0);
    diffuse += ambient * color1;
    if (dynamicLighting) diffuse.rgb += dynamicLight;
    return clamp(diffuse, vec4_splat(0.0), vec4_splat(1.0));
}
