$input v_texcoord0
#include <bgfx_shader.sh>
#include "bitmap_sampling.sh"
SAMPLER2DARRAY(s_sceneDiffuse, 0);
#include "authored_material.sh"
#include "authored_passes.sh"
#include "native_shader_power.sh"
uniform vec4 u_authoredPassProbe[8];
void main()
{
    vec3 cameraReflection = authoredReflection(u_authoredPassProbe[2].xyz, u_authoredPassProbe[3].xyz);
    vec2 worldReflection = vec2(dot(u_authoredPassProbe[4].xyz, cameraReflection),
        dot(u_authoredPassProbe[5].xyz, cameraReflection));
    vec4 cameraPoint = vec4_splat(0.0);
    if (u_authoredPassProbe[7].x > 0.5)
    {
        vec3 highlightReflection =
            normalize(reflect(u_authoredPassProbe[2].xyz, normalize(u_authoredPassProbe[3].xyz)));
        float highlight = nativeShaderPower(
            clamp(dot(normalize(u_authoredPassProbe[6].xyz), highlightReflection), 0.0, 1.0),
            u_authoredPassProbe[6].w);
        cameraPoint = vec4(authoredSpecularPoint(vec3_splat(highlight)), 1.0);
    }
    float specularMask;
    vec4 result = authoredPassMaterial(v_texcoord0.xy, worldReflection, u_authoredPassProbe[2].xyz,
        u_authoredPassProbe[3].xyz, 0.0, u_authoredPassProbe[0], u_authoredPassProbe[1].x, cameraPoint,
        specularMask);
    if (v_texcoord0.x >= 0.5) result.a = specularMask;
    gl_FragColor = result;
}
