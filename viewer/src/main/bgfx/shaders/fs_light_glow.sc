$input v_texcoord0

#include <bgfx_shader.sh>

SAMPLER2D(s_glowDepth, 0);
SAMPLER2D(s_glowModelDepth, 1);

uniform mat4 u_glowInverse;
uniform vec4 u_glowLight;
uniform vec4 u_glowAxis;
uniform vec4 u_glowColor;
uniform vec4 u_glowFrame;

float contour(float value, float level, float width)
{
    float perPixel = max(length(vec2(dFdx(value), dFdy(value))), 0.000001);
    return 1.0 - smoothstep(width * 0.5, width * 0.5 + 1.0, abs(value - level) / perPixel);
}

void main()
{
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    float depth = texelFetch(s_glowDepth, pixel, 0).x;
    if (u_glowFrame.z > 0.5)
    {
        depth = min(depth, texelFetch(s_glowModelDepth, pixel, 0).x);
    }
    if (depth >= 0.999999)
    {
        discard;
    }
    vec2 uv = gl_FragCoord.xy / u_viewRect.zw;
    float ndcY = u_glowFrame.x > 0.5 ? uv.y * 2.0 - 1.0 : 1.0 - uv.y * 2.0;
    float ndcZ = u_glowFrame.y > 0.5 ? depth * 2.0 - 1.0 : depth;
    vec4 clip = mul(u_glowInverse, vec4(uv.x * 2.0 - 1.0, ndcY, ndcZ, 1.0));
    vec3 surface = clip.xyz / clip.w;

    vec3 toLight = u_glowLight.xyz - surface;
    float away = length(toLight);
    float reach = max(u_glowLight.w, 0.001);
    float a = away / reach;
    float falloff = a < 1.0 ? (2.0 * a - 3.0) * a * a + 1.0 : 0.0;
    float inside = 1.0 - a;
    float strength = falloff;
    if (u_glowAxis.w < 1.5)
    {
        float threshold = u_glowAxis.w;
        float cosine = away > 0.001 ? dot(-toLight / away, u_glowAxis.xyz) : 1.0;
        float cone = (cosine - threshold) / max(1.0 - threshold, 0.000001);
        float factor = clamp(cone, 0.0, 1.0);
        strength *= factor * factor;
        inside = min(inside, cone);
    }
    float scale = max(u_glowFrame.w, 1.0);
    float lit = step(0.02, strength);
    float edge = contour(inside, 0.0, 2.0 * scale);
    float middle = contour(strength, 0.5, 1.5 * scale) * lit * 0.75;
    float stroke = max(edge, middle);
    float shade = max(contour(inside, 0.0, 4.0 * scale), contour(strength, 0.5, 3.5 * scale) * lit) * 0.35;
    float cover = max(stroke, shade);
    if (cover < 0.004 && strength * u_glowColor.a < 0.002)
    {
        discard;
    }
    vec3 colour = u_glowColor.rgb;
    vec3 tint = colour * strength * u_glowColor.a * 0.55;
    gl_FragColor = vec4(tint * (1.0 - cover) + colour * stroke, cover);
}
