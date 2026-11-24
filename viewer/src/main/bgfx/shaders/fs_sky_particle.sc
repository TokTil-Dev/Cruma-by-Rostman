$input v_texcoord0, v_color0

#include <bgfx_shader.sh>
SAMPLER2D(s_skyTexture, 0);
uniform vec4 u_skyParams[6];

void main()
{
    gl_FragColor = texture2D(s_skyTexture, v_texcoord0) * v_color0;
    if (gl_FragColor.a < u_skyParams[5].x) discard;
}
