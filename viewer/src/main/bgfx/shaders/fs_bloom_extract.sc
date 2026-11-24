$input v_texcoord0

#include <bgfx_shader.sh>

SAMPLER2D(s_postSource, 0);
uniform vec4 u_postParams[4];

void main()
{
    vec3 color = texture2D(s_postSource, v_texcoord0 * u_postParams[0].zw).rgb;
    float luminance = dot(color, vec3(0.299, 0.587, 0.114));
    float threshold = u_postParams[1].x;
    float contribution = max(luminance - threshold, 0.0) / max(1.0 - threshold, 0.0001);
    gl_FragColor = vec4(color * contribution, 1.0);
}
