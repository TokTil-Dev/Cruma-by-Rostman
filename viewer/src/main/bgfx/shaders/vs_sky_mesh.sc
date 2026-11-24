$input a_position, a_texcoord0
$output v_texcoord0, v_color0

#include <bgfx_shader.sh>
#include "tex_rotator.sh"

uniform vec4 u_skyParams[6];

vec2 animateUv(vec2 uv)
{
    float mode = u_skyParams[3].x;
    float time = u_skyParams[3].y;
    if (mode == 1.0)
    {
        uv += u_skyParams[3].zw * time;
    }
    else if (mode == 2.0)
    {
        uv = texRotatorYawUv(uv, u_skyParams[3].zw, u_skyParams[4].x * time);
    }
    else if (mode == 3.0)
    {
        uv.x += u_skyParams[3].w * sin(6.2831853 * u_skyParams[3].z * time);
        uv.y += u_skyParams[4].y * sin(6.2831853 * u_skyParams[4].x * time);
    }
    else if (mode == 4.0)
    {
        uv = uv / u_skyParams[3].zw + u_skyParams[4].xy;
    }
    return uv;
}

void main()
{
    v_texcoord0 = animateUv(a_texcoord0);
    vec3 world = a_position + u_skyParams[0].xyz;
    if (u_skyParams[0].w > 0.5)
    {
        vec4 plane = u_skyParams[1];
        world -= 2.0 * (dot(plane.xyz, world) + plane.w) * plane.xyz;
    }
    gl_Position = mul(u_viewProj, vec4(world, 1.0));
    v_color0 = u_skyParams[2];
}
