$input v_texcoord0, v_color0

#include <bgfx_shader.sh>

SAMPLER2D(s_skyTexture, 0);
uniform vec4 u_skyParams[6];

void main()
{
    vec4 texel = u_skyParams[4].z > 0.5
        ? texture2D(s_skyTexture, v_texcoord0)
        : vec4(1.0, 1.0, 1.0, 1.0);
    float alpha = u_skyParams[4].w > 0.5 ? texel.a * v_color0.a : 1.0;
    alpha *= u_skyParams[5].x;
    if (u_skyParams[5].y >= 0.0 && alpha <= u_skyParams[5].y)
    {
        discard;
    }
    gl_FragColor = vec4(texel.rgb * v_color0.rgb, alpha);
}
