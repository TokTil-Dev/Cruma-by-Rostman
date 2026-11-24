$input v_texcoord0

#include <bgfx_shader.sh>

SAMPLER2D(s_postSource, 0);
uniform vec4 u_postParams[4];

vec3 sampleSource(vec2 uv)
{
    vec2 halfTexel = u_postParams[0].xy * 0.5;
    return texture2D(
        s_postSource,
        clamp(uv, halfTexel, u_postParams[0].zw - halfTexel)
    ).rgb;
}

void main()
{
    vec2 step = u_postParams[0].xy * u_postParams[1].yz;
    vec2 uv = v_texcoord0 * u_postParams[0].zw;
    vec3 color = sampleSource(uv) * 0.2270270270;
    color += sampleSource(uv + step * 1.3846153846) * 0.3162162162;
    color += sampleSource(uv - step * 1.3846153846) * 0.3162162162;
    color += sampleSource(uv + step * 3.2307692308) * 0.0702702703;
    color += sampleSource(uv - step * 3.2307692308) * 0.0702702703;
    gl_FragColor = vec4(color, 1.0);
}
