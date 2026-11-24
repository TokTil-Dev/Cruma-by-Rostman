$input v_texcoord0

#include <bgfx_shader.sh>

SAMPLER2D(s_source, 0);
uniform vec4 u_easuCon0;
uniform vec4 u_easuCon1;
uniform vec4 u_fsrOutputSize;

vec3 fsrEasuSample(vec2 pixel)
{
    pixel = clamp(pixel, vec2_splat(0.0), u_fsrOutputSize.zw - vec2_splat(1.0));
    vec2 uv = (pixel + vec2_splat(0.5)) * u_easuCon1.xy;
    return texture2DLod(s_source, uv, 0.0).rgb;
}

#include "fsr1_easu.sh"

void main()
{
    vec2 outputPixel = floor(v_texcoord0 * u_fsrOutputSize.xy);
    vec3 color = fsrEasu(outputPixel, u_easuCon0);
    vec2 sourcePosition = outputPixel * u_easuCon0.xy + u_easuCon0.zw;
    sourcePosition = clamp(
        sourcePosition, vec2_splat(0.0), u_fsrOutputSize.zw - vec2_splat(1.0)
    );
    float alpha = texture2DLod(s_source, (sourcePosition + vec2_splat(0.5)) * u_easuCon1.xy, 0.0).a;
    gl_FragColor = vec4(color, alpha);
}
