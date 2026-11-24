#ifndef SPAWN_MODEL_REFLECTION_SH
#define SPAWN_MODEL_REFLECTION_SH

#include "authored_reflection.sh"

vec4 spawnModelReflection(vec3 position, vec3 normal)
{
    vec3 viewPosition = mul(u_view, vec4(mul(u_model[0], vec4(position, 1.0)).xyz, 1.0)).xyz;
    vec3 viewNormal = mul(u_view, vec4(mul(u_model[0], vec4(normal, 0.0)).xyz, 0.0)).xyz;
    vec3 cameraReflection = authoredReflection(viewPosition, viewNormal);
    vec3 worldReflection = mul(u_view, vec4(mul(u_model[0], vec4(cameraReflection, 0.0)).xyz, 0.0)).xyz;
    return vec4(worldReflection.xy, 0.0, 0.0);
}

#endif
