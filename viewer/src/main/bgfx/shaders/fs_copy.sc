$input v_texcoord0

#include <bgfx_shader.sh>

SAMPLER2D(s_source, 0);
uniform vec4 u_easuCon0;

void main()
{
    gl_FragColor = texture2D(s_source, v_texcoord0 * u_easuCon0.xy);
}
