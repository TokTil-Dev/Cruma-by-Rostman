$input a_position

#include <bgfx_shader.sh>
uniform vec4 u_bufferSmokeOffset;

void main()
{
    gl_Position = vec4(a_position + u_bufferSmokeOffset.xyz, 1.0);
}
