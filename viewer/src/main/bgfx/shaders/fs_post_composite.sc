$input v_texcoord0

#include <bgfx_shader.sh>

SAMPLER2D(s_postSource, 0);
SAMPLER2D(s_postBloom, 1);
uniform vec4 u_postParams[4];

void main()
{
    vec4 source = texture2D(s_postSource, v_texcoord0 * u_postParams[0].zw);
    vec2 bloomHalfTexel = u_postParams[3].zw * 0.5;
    vec2 bloomUv = clamp(
        v_texcoord0 * u_postParams[3].xy,
        bloomHalfTexel, u_postParams[3].xy - bloomHalfTexel
    );
    vec3 bloom = texture2D(s_postBloom, bloomUv).rgb;
    bool bloomEnabled = u_postParams[1].x > 0.5;
    bool underwater = u_postParams[1].z > 0.5;
    float inverseGamma = u_postParams[1].w;
    vec3 color = source.rgb;
    if (bloomEnabled)
    {
        color += bloom * u_postParams[1].y;
    }
    if (underwater)
    {
        color = mix(color, u_postParams[2].rgb, clamp(u_postParams[2].a, 0.0, 1.0));
    }
    if (inverseGamma > 0.0)
    {
        color = pow(max(color, vec3_splat(0.0)), vec3_splat(inverseGamma));
    }
    gl_FragColor = vec4(clamp(color, 0.0, 1.0), source.a);
}
