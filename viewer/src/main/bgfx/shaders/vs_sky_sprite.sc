$input a_position, a_texcoord0
$output v_texcoord0, v_color0

#include <bgfx_shader.sh>

uniform vec4 u_skyParams[6];

void main()
{
    vec3 world = u_skyParams[2].xyz
        + a_position.x * u_skyParams[2].w * u_skyParams[0].xyz
        + a_position.y * u_skyParams[2].w * u_skyParams[1].xyz;
    gl_Position = mul(u_viewProj, vec4(world, 1.0));
    v_texcoord0 = a_texcoord0;
    v_color0 = u_skyParams[3];
}
