$input v_texcoord0

#include <bgfx_shader.sh>

SAMPLER2D(s_source, 0);
uniform vec4 u_rcasCon;
uniform vec4 u_fsrOutputSize;

vec4 rcasSample(vec2 pixel)
{
    vec2 clampedPixel = clamp(pixel, vec2_splat(0.0), u_fsrOutputSize.xy - vec2_splat(1.0));
    return texture2DLod(s_source, (clampedPixel + vec2_splat(0.5)) * u_fsrOutputSize.zw, 0.0);
}

void main()
{
    vec2 pixel = floor(v_texcoord0 * u_fsrOutputSize.xy);
    vec3 b = rcasSample(pixel + vec2( 0.0, -1.0)).rgb;
    vec3 d = rcasSample(pixel + vec2(-1.0,  0.0)).rgb;
    vec4 center = rcasSample(pixel);
    vec3 e = center.rgb;
    vec3 f = rcasSample(pixel + vec2( 1.0,  0.0)).rgb;
    vec3 h = rcasSample(pixel + vec2( 0.0,  1.0)).rgb;

    vec3 minimum4 = min(min(b, d), min(f, h));
    vec3 maximum4 = max(max(b, d), max(f, h));
    vec3 hitMinimum = min(minimum4, e) / max(vec3_splat(1.0e-8), 4.0 * maximum4);
    vec3 hitMaximum = (vec3_splat(1.0) - max(maximum4, e)) / min(vec3_splat(-1.0e-8), 4.0 * minimum4 - vec3_splat(4.0));
    vec3 lobeRgb = max(-hitMinimum, hitMaximum);
    float lobe = clamp(max(lobeRgb.r, max(lobeRgb.g, lobeRgb.b)), -(0.25 - 1.0 / 16.0), 0.0);
    lobe *= u_rcasCon.x;
    float reciprocal = 1.0 / (4.0 * lobe + 1.0);
    vec3 color = (lobe * (b + d + f + h) + e) * reciprocal;
    gl_FragColor = vec4(color, center.a);
}
