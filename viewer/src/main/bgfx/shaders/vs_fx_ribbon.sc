$input a_position, a_texcoord0, a_color0
$output v_texcoord0, v_texcoord1, v_texcoord2, v_texcoord3, v_texcoord4, v_color0

#include <bgfx_shader.sh>
#include "soft_particle.sh"

void main()
{
    gl_Position = mul(u_viewProj, vec4(a_position, 1.0));
    v_texcoord0 = a_texcoord0;
    v_texcoord1 = vec4(0.5, 0.5, 0.0, 0.0);
    v_texcoord4 = vec4(0.0, 0.0, 0.0, 0.0);
    float viewDepth = mul(u_view, vec4(a_position, 1.0)).z;
    v_texcoord2 = vec4(softParticleDepthUv(gl_Position), viewDepth, abs(viewDepth));
    v_texcoord3 = vec4(a_position, 1.0);
    v_color0 = a_color0;
}
