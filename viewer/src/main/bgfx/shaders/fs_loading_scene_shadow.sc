$input v_texcoord0

#include <bgfx_shader.sh>

SAMPLER2D(s_lsTexture, 0);

void main()
{
    if (texture2D(s_lsTexture, v_texcoord0).a < 0.375)
    {
        discard;
    }
    gl_FragColor = vec4(1.0, 1.0, 1.0, 1.0);
}
