$input a_position, a_color0, a_texcoord1
$output v_color0, v_texcoord1, v_texcoord2

#include <bgfx_shader.sh>

void main()
{
    gl_Position = mul(u_viewProj, vec4(a_position, 1.0));
    v_color0 = a_color0;
    v_texcoord1 = a_texcoord1;
    v_texcoord2 = vec4(a_position, 1.0);
}
