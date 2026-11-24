$input v_texcoord0, v_texcoord1, v_texcoord2

#include <bgfx_shader.sh>

SAMPLER2D(s_spawnShadowTexture, 0);
uniform vec4 u_spawnShadowParams;

void main()
{
    if (u_spawnShadowParams.x > 0.0 && texture2D(s_spawnShadowTexture, v_texcoord0).a < u_spawnShadowParams.x)
    {
        discard;
    }
    gl_FragColor = vec4(1.0, 1.0, 1.0, 1.0);
}
