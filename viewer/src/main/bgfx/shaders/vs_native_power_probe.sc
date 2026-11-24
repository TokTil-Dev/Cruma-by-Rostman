$input a_position
$output v_color0
#include <bgfx_shader.sh>
void main()
{
    v_color0 = vec4_splat(0.0);
    gl_Position = vec4(a_position, 1.0);
}
