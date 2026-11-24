$input v_texcoord0, v_texcoord1, v_texcoord2, v_texcoord3, v_color0

#include <bgfx_shader.sh>

SAMPLER2D(s_flightTexture, 0);
SAMPLER2D(s_flightLightmap, 1);

uniform vec4 u_flightSun;
uniform vec4 u_flightSunColor;
uniform vec4 u_flightAmbient;
uniform vec4 u_flightFog;
uniform vec4 u_flightEye;
uniform vec4 u_flightMaterial;
uniform vec4 u_flightWater;

void main()
{
    vec3 toEye = u_flightEye.xyz - v_texcoord1.xyz;
    float fog = clamp((length(toEye) - u_flightEye.w) / max(u_flightFog.w - u_flightEye.w, 1.0), 0.0, 1.0);
    if (u_flightMaterial.y > 0.5)
    {
        float facing = clamp(abs(normalize(toEye).z), 0.0, 1.0);
        vec3 water = mix(u_flightFog.rgb, u_flightWater.rgb, 0.55 + 0.45 * facing);
        gl_FragColor = vec4(mix(water, u_flightFog.rgb, fog), 1.0);
        return;
    }
    vec4 texel = texture2D(s_flightTexture, v_texcoord0);
    float coverage = 1.0;
    if (u_flightMaterial.x > 0.0)
    {
        coverage = clamp((texel.a - u_flightMaterial.x) / max(fwidth(texel.a), 0.0001) + 0.5, 0.0, 1.0);
        if (coverage <= 0.0)
        {
            discard;
        }
    }
    vec3 light;
    if (u_flightMaterial.z > 0.5)
    {
        light = v_texcoord3.x >= 0.0 ? texture2D(s_flightLightmap, v_texcoord3.xy).rgb * 2.0 : vec3(1.0, 1.0, 1.0);
    }
    else
    {
        vec3 normal = normalize(v_texcoord2.xyz);
        if (dot(normal, toEye) < 0.0)
        {
            normal = -normal;
        }
        float sun = max(0.0, dot(normal, u_flightSun.xyz)) * v_color0.a;
        light = v_color0.rgb * 2.0 + u_flightSunColor.rgb * sun + u_flightAmbient.rgb;
    }
    vec3 color = texel.rgb * light;
    gl_FragColor = vec4(mix(color, u_flightFog.rgb, fog), coverage);
}
