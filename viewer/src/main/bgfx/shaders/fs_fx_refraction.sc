$input v_texcoord0
#include <bgfx_shader.sh>
SAMPLER2D(s_refractionField, 0);
SAMPLER2D(s_refractionScene, 1);
SAMPLER2D(s_refractionRegion, 2);
uniform vec4 u_refraction;

void main()
{
    vec2 uv = v_texcoord0 * u_refraction.zw;
    vec3 original = texture2D(s_refractionScene, uv).rgb;
    float region = texture2D(s_refractionRegion, uv).r;
    vec3 result = original;
    if (region == 0.0)
    {
        vec4 field = texture2D(s_refractionField, uv);
        vec2 displaced = v_texcoord0 + (field.rg - vec2_splat(0.5)) * field.a * u_refraction.xy;
        vec2 sampleUv = displaced * u_refraction.zw;
        bool inside = all(greaterThanEqual(displaced, vec2_splat(0.0))) && all(lessThanEqual(displaced, vec2_splat(1.0)));
        vec3 refracted = inside ? texture2D(s_refractionScene, sampleUv).rgb : vec3_splat(0.0);
        float outsideRegion = inside ? texture2D(s_refractionRegion, sampleUv).r : 0.0;
        result = mix(refracted, original, outsideRegion);
    }
    gl_FragColor = vec4(result, 1.0);
}
