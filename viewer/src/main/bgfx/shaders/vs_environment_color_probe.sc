$input a_position
$output v_color0
#include <bgfx_shader.sh>
#include "native_environment_color.sh"
uniform vec4 u_environmentColorProbe[3];
void main()
{
    v_color0 = vec4(nativeEnvironmentColorMerge(u_environmentColorProbe[0].rgb,
        u_environmentColorProbe[1].rgb, u_environmentColorProbe[2].rgb,
        u_environmentColorProbe[1].w > 0.5), u_environmentColorProbe[2].a);
    gl_Position = vec4(a_position, 1.0);
}
