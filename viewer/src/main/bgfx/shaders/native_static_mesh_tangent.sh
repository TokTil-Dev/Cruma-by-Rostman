bool nativeStaticMeshTangentStream(vec4 metadata)
{
    return mod(floor(metadata.w / 4.0), 2.0) > 0.5;
}

bool nativeStaticMeshUseTangent(vec4 metadata)
{
    return nativeStaticMeshTangentStream(metadata) && mod(floor(metadata.w / 8.0), 2.0) > 0.5;
}

void nativeStaticMeshTangentDirections(vec3 localNormal, vec4 localTangent, mat4 model, vec3 worldPosition,
    vec3 surfaceToSun, out vec3 eye, out vec3 normal, out vec3 direction)
{
    vec3 N = normalize(localNormal);
    vec3 T = normalize(localTangent.xyz);
    vec3 B = localTangent.w * normalize(cross(N, T));
    vec3 viewPosition = mul(u_view, vec4(worldPosition, 1.0)).xyz;
    eye = normalize(vec3(dot(T, viewPosition), dot(B, viewPosition), dot(N, viewPosition)));
    normal = mul(u_view, mul(model, vec4(localNormal, 0.0))).xyz;
    vec3 localSun = vec3(dot(model[0].xyz, surfaceToSun), dot(model[1].xyz, surfaceToSun), dot(model[2].xyz, surfaceToSun));
    direction = vec3(dot(T, localSun), dot(B, localSun), dot(N, localSun));
}
