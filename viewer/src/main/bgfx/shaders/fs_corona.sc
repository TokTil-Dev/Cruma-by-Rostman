$input v_texcoord0, v_color0

#include <bgfx_shader.sh>

SAMPLER2D(s_coronaTexture, 0);

uniform vec4 u_coronaParams;

void main()
{
    vec4 texel = texture2D(s_coronaTexture, v_texcoord0);
    if (u_coronaParams.x > 0.5 && texel.a <= 0.0)
    {
        discard;
    }
    gl_FragColor = vec4(texel.rgb * v_color0.rgb, 1.0);
}
