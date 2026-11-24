$input v_texcoord0, v_texcoord1, v_texcoord2, v_texcoord3, v_texcoord4, v_color0
#include <bgfx_shader.sh>
#include "height_cut.sh"
uniform vec4 u_fxParams[10];
void main()
{
    if ((u_fxParams[0].w > 0.5 && dot(v_texcoord3, u_fxParams[5]) < 0.0)
        || heightCutRejects(u_fxParams[8], u_fxParams[9], v_texcoord3.xyz)) discard;
    gl_FragColor = vec4_splat(0.0);
}
