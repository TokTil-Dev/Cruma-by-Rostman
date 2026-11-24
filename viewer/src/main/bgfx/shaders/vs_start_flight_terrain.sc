$input a_position, a_normal, a_color0, a_texcoord0, a_texcoord1, a_texcoord2, a_texcoord3, a_texcoord4, a_texcoord5, a_texcoord6
$output v_texcoord0, v_texcoord1, v_texcoord2, v_texcoord3, v_texcoord4, v_texcoord5, v_texcoord6, v_texcoord7, v_color0, v_tangent

#include <bgfx_shader.sh>

void main()
{
    gl_Position = mul(u_viewProj, vec4(a_position, 1.0));
    v_texcoord0 = a_texcoord0;
    v_texcoord1 = a_texcoord1;
    v_texcoord2 = a_texcoord2;
    v_texcoord3 = a_texcoord3;
    v_texcoord4 = a_texcoord4;
    v_texcoord5 = a_texcoord5;
    v_texcoord6 = a_texcoord6;
    v_texcoord7 = vec4(a_position, 1.0);
    v_tangent = vec4(a_normal.xyz, 0.0);
    v_color0 = a_color0;
}
