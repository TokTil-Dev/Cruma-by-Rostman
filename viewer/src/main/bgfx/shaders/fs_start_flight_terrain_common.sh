#include <bgfx_shader.sh>

SAMPLER2D(s_flightLayer0, 0);
SAMPLER2D(s_flightLayer1, 1);
SAMPLER2D(s_flightLayer2, 2);
SAMPLER2D(s_flightLayer3, 3);
SAMPLER2D(s_flightLayer4, 4);
SAMPLER2D(s_flightLayer5, 5);
SAMPLER2D(s_flightLayer6, 6);
SAMPLER2D(s_flightLayer7, 7);
SAMPLER2D(s_flightLayer8, 8);
SAMPLER2D(s_flightLayer9, 9);
SAMPLER2D(s_flightLayer10, 10);
SAMPLER2D(s_flightLayer11, 11);
SAMPLER2D(s_flightAlpha0, 12);
SAMPLER2D(s_flightAlpha1, 13);
SAMPLER2D(s_flightAlpha2, 14);

uniform vec4 u_flightSunColor;
uniform vec4 u_flightAmbient;
uniform vec4 u_flightFog;
uniform vec4 u_flightEye;
uniform vec4 u_flightTerrain;

#if defined(FLIGHT_OVERLAY)
uniform vec4 u_flightLayerUv[25];
#define FLIGHT_UV(_slot, _attribute) \
    vec2(dot(u_flightLayerUv[2 * _slot], vec4(overlayPosition, 1.0)), \
        dot(u_flightLayerUv[2 * _slot + 1], vec4(overlayPosition, 1.0)))
#define FLIGHT_MIX(_rgb, _weight) \
    { float overlayWeight = (_weight); color = mix(color, (_rgb), overlayWeight); uncovered *= 1.0 - overlayWeight; }
#else
#define FLIGHT_UV(_slot, _attribute) (_attribute)
#define FLIGHT_MIX(_rgb, _weight) color = mix(color, (_rgb), (_weight))
#endif

float coverage(float authored, float diffuseAlpha, float flags, float bit)
{
    float usesDiffuseAlpha = mod(floor(flags / bit), 2.0);
    return clamp(authored, 0.0, 1.0) * mix(1.0, diffuseAlpha, usesDiffuseAlpha);
}

#define FLIGHT_LAYER(_index, _sampler, _uv, _authored, _flags, _bit) \
    if (layers > _index) \
    { \
        vec4 texel = texture2D(_sampler, _uv); \
        FLIGHT_MIX(texel.rgb, coverage(_authored, texel.a, _flags, _bit)); \
    }

void main()
{
    float layers = u_flightTerrain.x;
    vec4 alpha0 = texture2D(s_flightAlpha0, v_texcoord0);
    vec4 alpha1 = texture2D(s_flightAlpha1, v_texcoord0);
    vec4 alpha2 = texture2D(s_flightAlpha2, v_texcoord0);
#if defined(FLIGHT_OVERLAY)
    vec3 overlayPosition = v_texcoord7.xyz - u_flightLayerUv[24].xyz;
    vec3 color = vec3(0.0, 0.0, 0.0);
    float uncovered = 1.0;
    FLIGHT_LAYER(0.0, s_flightLayer0, FLIGHT_UV(0, v_texcoord1.xy), alpha0.x, u_flightTerrain.y, 1.0)
#else
    vec3 color = texture2D(s_flightLayer0, v_texcoord1.xy).rgb;
#endif
    FLIGHT_LAYER(1.0, s_flightLayer1, FLIGHT_UV(1, v_texcoord1.zw), alpha0.y, u_flightTerrain.y, 2.0)
    FLIGHT_LAYER(2.0, s_flightLayer2, FLIGHT_UV(2, v_texcoord2.xy), alpha0.z, u_flightTerrain.y, 4.0)
    FLIGHT_LAYER(3.0, s_flightLayer3, FLIGHT_UV(3, v_texcoord2.zw), alpha0.w, u_flightTerrain.y, 8.0)
    FLIGHT_LAYER(4.0, s_flightLayer4, FLIGHT_UV(4, v_texcoord3.xy), alpha1.x, u_flightTerrain.z, 1.0)
    FLIGHT_LAYER(5.0, s_flightLayer5, FLIGHT_UV(5, v_texcoord3.zw), alpha1.y, u_flightTerrain.z, 2.0)
    FLIGHT_LAYER(6.0, s_flightLayer6, FLIGHT_UV(6, v_texcoord4.xy), alpha1.z, u_flightTerrain.z, 4.0)
    FLIGHT_LAYER(7.0, s_flightLayer7, FLIGHT_UV(7, v_texcoord4.zw), alpha1.w, u_flightTerrain.z, 8.0)
    FLIGHT_LAYER(8.0, s_flightLayer8, FLIGHT_UV(8, v_texcoord5.xy), alpha2.x, u_flightTerrain.w, 1.0)
    FLIGHT_LAYER(9.0, s_flightLayer9, FLIGHT_UV(9, v_texcoord5.zw), alpha2.y, u_flightTerrain.w, 2.0)
    FLIGHT_LAYER(10.0, s_flightLayer10, FLIGHT_UV(10, v_texcoord6.xy), alpha2.z, u_flightTerrain.w, 4.0)
    FLIGHT_LAYER(11.0, s_flightLayer11, FLIGHT_UV(11, v_texcoord6.zw), alpha2.w, u_flightTerrain.w, 8.0)
#if defined(FLIGHT_OVERLAY)
    float overlayCover = 1.0 - uncovered;
    color = color / max(overlayCover, 0.000001);
#endif

    vec3 light = min(v_color0.rgb * 2.0 + u_flightAmbient.rgb + u_flightSunColor.rgb * v_color0.a, vec3(2.0, 2.0, 2.0));
    color = color * light;
    float distance = length(u_flightEye.xyz - v_texcoord7.xyz);
    float fog = clamp((distance - u_flightEye.w) / max(u_flightFog.w - u_flightEye.w, 1.0), 0.0, 1.0);
#if defined(FLIGHT_OVERLAY)
    gl_FragColor = vec4(mix(color, u_flightFog.rgb, fog) * overlayCover, overlayCover);
#else
    gl_FragColor = vec4(mix(color, u_flightFog.rgb, fog), 1.0);
#endif
}
