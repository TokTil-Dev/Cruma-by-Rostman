$input a_position, a_normal, a_texcoord0, a_color0, a_texcoord1
$output v_texcoord0, v_texcoord1, v_texcoord2, v_texcoord3, v_color0

#include <bgfx_shader.sh>

void main()
{
    gl_Position = mul(u_viewProj, vec4(a_position, 1.0));
    v_texcoord0 = a_texcoord0;
    v_texcoord1 = vec4(a_position, 1.0);
    v_texcoord2 = vec4(a_normal.xyz, 0.0);
    v_texcoord3 = vec4(a_texcoord1.xy, 0.0, 0.0);
    v_color0 = a_color0;
}
