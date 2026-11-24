#if defined(SPAWN_MODEL_PASSES)
#include "spawn_model_reflection.sh"
#endif

uniform vec4 u_spawnModelLights[32];
uniform vec4 u_spawnModelAmbient;

void main()
{
    gl_Position = mul(u_modelViewProj, vec4(a_position, 1.0));
    v_texcoord0 = a_texcoord0;
    vec3 normal = normalize(mul(u_model[0], vec4(a_normal.xyz, 0.0)).xyz);
    vec3 world = mul(u_model[0], vec4(a_position, 1.0)).xyz;
    v_texcoord1 = vec4(normal, 0.0);
    v_texcoord2 = vec4(world, 1.0);
    vec3 lit = u_spawnModelAmbient.rgb;
    vec3 sun = vec3_splat(0.0);
    for (int i = 0; i < 8; ++i)
    {
        vec4 place = u_spawnModelLights[i * 4];
        vec4 color = u_spawnModelLights[i * 4 + 1];
        vec4 fit = u_spawnModelLights[i * 4 + 2];
        vec4 cone = u_spawnModelLights[i * 4 + 3];
        float factor = 0.0;
        if (place.w > 2.5)
        {
            factor = max(-dot(normal, place.xyz) / max(length(place.xyz), 0.000001), 0.0);
        }
        else if (place.w > 0.5)
        {
            vec3 toLight = place.xyz - world;
            float distanceToLight = length(toLight);
            float attenuation = fit.x + fit.y * distanceToLight;
            if (distanceToLight <= color.w && distanceToLight > 0.0 && attenuation > 0.0)
            {
                factor = max(dot(normal, toLight) / distanceToLight, 0.0) / attenuation;
            }
            float rho = -dot(toLight, cone.xyz) / max(distanceToLight * length(cone.xyz), 0.000001);
            factor *= place.w > 1.5 ? step(0.7071068, rho) : 1.0;
        }
        vec3 term = color.rgb * factor;
        sun += term * fit.z;
        lit += term * (1.0 - fit.z);
    }
    v_color0 = vec4(lit, 0.0);
    v_texcoord3 = vec4(sun, 0.0);
#if defined(SPAWN_MODEL_PASSES)
    v_texcoord4 = spawnModelReflection(a_position, a_normal.xyz);
#endif
}
