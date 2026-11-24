$input v_texcoord0, v_texcoord1, v_texcoord2, v_color0, v_texcoord3

#include <bgfx_shader.sh>

SAMPLER2D(s_spawnModelTexture, 0);
SAMPLER2D(s_spawnModelSpecular, 7);
SAMPLER2D(s_spawnModelSpecularMask, 8);
#include "spawn_model_fragment.sh"
#include "tex_rotator.sh"
#include "authored_reflection.sh"

uniform vec4 u_spawnModelSpecular[3];

vec3 spawnModelSpecularVector(float source, vec2 uv, vec3 world, vec3 normal)
{
    vec3 viewPosition = mul(u_view, vec4(world, 1.0)).xyz;
    vec3 viewNormal = mul(u_view, vec4(normal, 0.0)).xyz;
    vec3 fixedReflection = fixedFunctionReflection(viewPosition, viewNormal);
    vec3 result = vec3(uv, 1.0);
    if (source > 5.5)
    {
        result = vec3(fixedFunctionSphereMap(fixedReflection), 0.0);
    }
    else if (source > 4.5)
    {
        result = fixedFunctionWorldReflection(fixedReflection, u_view);
    }
    else if (source > 3.5)
    {
        result = fixedReflection;
    }
    else if (source > 2.5)
    {
        result = vec3(authoredSphereMap(viewNormal), 1.0);
    }
    else if (source > 1.5)
    {
        result = authoredReflection(viewPosition, viewNormal);
    }
    return result;
}

void main()
{
    vec4 albedo = u_spawnModelParams.y > 0.5 ? texture2D(s_spawnModelTexture, v_texcoord0) : u_spawnModelColor;
    if (albedo.a < u_spawnModelParams.x || spawnModelBehindScene(gl_FragCoord))
    {
        discard;
    }
    vec3 normal = spawnModelFacingNormal(v_texcoord1.xyz, v_texcoord2.xyz);
    vec4 light = spawnModelLight(normal, v_texcoord2.xyz, v_color0.rgb, v_texcoord3.rgb);
    vec3 color = albedo.rgb * light.rgb * light.w;
    if (u_spawnModelAmbient.w >= 0.0)
    {
        color = clamp(color, 0.0, 1.0);
    }
    vec3 specularVector = spawnModelSpecularVector(u_spawnModelSpecular[0].y, v_texcoord0, v_texcoord2.xyz,
        normalize(v_texcoord1.xyz));
    vec2 specularUv = animateEnvironmentUv(specularVector, u_spawnModelSpecular[1].x, u_spawnModelSpecular[1].w,
        u_spawnModelSpecular[2], u_spawnModelSpecular[1].z);
    vec3 specular = texture2D(s_spawnModelSpecular, specularUv).rgb;
    float specularMask = mix(1.0, texture2D(s_spawnModelSpecularMask, v_texcoord0).a, u_spawnModelSpecular[1].y);
    specular *= 1.0 + u_spawnModelSpecular[0].z;
    specular *= mix(vec3_splat(1.0), light.rgb, u_spawnModelSpecular[0].w);
    float specularOnly = step(1.5, u_spawnModelSpecular[0].x);
    color += specular * (specularMask * min(u_spawnModelSpecular[0].x, 1.0));
    if (u_spawnModelAmbient.w >= 0.0)
    {
        color = clamp(color, 0.0, 1.0);
    }
    color = mix(color, specular * specularMask, specularOnly);
    gl_FragColor = vec4(color, mix(albedo.a * u_spawnModelParams.w, 1.0, specularOnly));
}
