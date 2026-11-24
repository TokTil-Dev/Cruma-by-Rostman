#ifndef AUTHORED_REFLECTION_SH
#define AUTHORED_REFLECTION_SH

vec3 authoredReflection(vec3 viewPosition, vec3 viewNormal)
{
    vec3 eye = viewPosition * inversesqrt(dot(viewPosition, viewPosition) + 1.0);
    return reflect(eye, normalize(viewNormal));
}

vec2 authoredSphereMap(vec3 viewNormal)
{
    return normalize(viewNormal).xy * 0.5 + vec2_splat(0.5);
}

vec3 authoredSpecularPoint(vec3 specularIntensity)
{
    return specularIntensity * 0.25 + vec3_splat(0.2);
}

vec2 authoredReflectionCoordinates(vec3 source, vec3 column0, vec3 column1)
{
    return vec2(dot(source, column0), dot(source, column1));
}

vec3 fixedFunctionReflection(vec3 viewPosition, vec3 viewNormal)
{
    return reflect(normalize(viewPosition), normalize(viewNormal));
}

vec2 fixedFunctionSphereMap(vec3 reflection)
{
    float m = 2.0 * length(reflection - vec3(0.0, 0.0, 1.0));
    return reflection.xy / max(m, 0.000001) + vec2_splat(0.5);
}

vec3 fixedFunctionWorldReflection(vec3 reflection, mat4 view)
{
    return mul(vec4(reflection, 0.0), view).xyz;
}

#endif
