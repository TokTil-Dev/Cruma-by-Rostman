$input v_texcoord0, v_texcoord1, v_texcoord2, v_color0, v_texcoord3, v_texcoord4

#include <bgfx_shader.sh>
#include "bitmap_sampling.sh"

#include "spawn_model_fragment.sh"
SAMPLER2DARRAY(s_sceneDiffuse, 0);
SAMPLER2DARRAY(s_authored1, 7);
SAMPLER2DARRAY(s_authored2, 8);
SAMPLER2DARRAY(s_authored3, 9);
SAMPLER2DARRAY(s_authored4, 10);
SAMPLER2DARRAY(s_authored5, 11);
SAMPLER2DARRAY(s_authored6, 12);
SAMPLER2DARRAY(s_authored7, 13);
#include "authored_passes.sh"

void main()
{
    if (spawnModelBehindScene(gl_FragCoord))
    {
        discard;
    }
    vec3 normal = spawnModelFacingNormal(v_texcoord1.xyz, v_texcoord2.xyz);
    vec4 light = spawnModelLight(normal, v_texcoord2.xyz, v_color0.rgb, v_texcoord3.rgb);
    vec3 viewPosition = mul(u_view, vec4(v_texcoord2.xyz, 1.0)).xyz;
    vec3 viewNormal = mul(u_view, vec4(v_texcoord1.xyz, 0.0)).xyz;
    vec4 color = authoredPassMaterial(v_texcoord0, v_texcoord4.xy, viewPosition, viewNormal, 0.0,
        vec4(light.rgb, 1.0), light.w);
    if (u_authoredPasses[0].y < 0.5 && u_authoredPasses[4].w > 0.5)
    {
        color *= vec4(u_authoredPasses[2].rgb * light.w, u_authoredPasses[2].a);
    }
    if (color.a < u_spawnModelParams.x)
    {
        discard;
    }
    gl_FragColor = vec4(color.rgb, color.a * u_spawnModelParams.w);
}
