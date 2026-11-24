#ifndef CRUMA_WATER_VERTEX_SH
#define CRUMA_WATER_VERTEX_SH

void waterVertexDirections(
    vec3 worldPosition, vec3 worldNormal, vec3 worldTangent, vec3 lightDirection,
    out vec3 eyeTangent, out vec3 lightTangent
)
{
    vec3 normalView = mul(u_view, vec4(worldNormal, 0.0)).xyz;
    vec3 tangentView = normalize(mul(u_view, vec4(worldTangent, 0.0)).xyz);
    vec3 binormalView = cross(normalView, tangentView);
    vec3 eyeView = mul(u_view, vec4(worldPosition, 1.0)).xyz;
    vec3 lightView = mul(u_view, vec4(lightDirection, 0.0)).xyz;
    eyeTangent = vec3(dot(tangentView, eyeView), dot(binormalView, eyeView), dot(normalView, eyeView));
    lightTangent = vec3(dot(tangentView, lightView), dot(binormalView, lightView), dot(normalView, lightView));
}

#endif
