$input a_position, a_normal, a_texcoord0
$output v_texcoord0, v_texcoord1, v_texcoord2

#include <bgfx_shader.sh>

void main()
{
    gl_Position = mul(u_modelViewProj, vec4(a_position, 1.0));
    v_texcoord0 = a_texcoord0;
    v_texcoord1 = vec4(normalize(mul(u_model[0], vec4(a_normal.xyz, 0.0)).xyz), 0.0);
    v_texcoord2 = vec4(mul(u_model[0], vec4(a_position, 1.0)).xyz, 1.0);
}
