$input v_texcoord0, v_texcoord1, v_texcoord2, v_texcoord3

#include <bgfx_shader.sh>

SAMPLER2D(s_lsTexture, 0);
SAMPLER2D(s_lsShadow, 1);

uniform vec4 u_lsSun;
uniform vec4 u_lsSunColor;
uniform vec4 u_lsSky;
uniform vec4 u_lsGround;
uniform vec4 u_lsMaterial;
uniform vec4 u_lsFill;
uniform vec4 u_lsTint;
uniform vec4 u_lsFilled;
uniform vec4 u_lsEdge;
uniform vec4 u_lsHaze;
uniform vec4 u_lsDepth;
uniform vec4 u_lsGroundLook;
uniform vec4 u_lsGroundBelow;

float sunDepth(vec2 uv)
{
    return texture2DLod(s_lsShadow, uv, 0.0).x;
}

float sunReach(vec4 place, float bias)
{
    vec2 uv = place.xy * 0.5 + vec2(0.5, 0.5);
    if (u_lsDepth.x > 0.0)
    {
        uv.y = 1.0 - uv.y;
    }
    float depth = u_lsDepth.y > 0.0 ? place.z * 0.5 + 0.5 : place.z;
    if (uv.x < 0.0 || uv.y < 0.0 || uv.x > 1.0 || uv.y > 1.0)
    {
        return 1.0;
    }
    float compared = depth - bias;
    float texel = u_lsSun.w;
    float lit = step(compared, sunDepth(uv));
    lit += step(compared, sunDepth(uv + vec2(texel, texel)));
    lit += step(compared, sunDepth(uv + vec2(-texel, texel)));
    lit += step(compared, sunDepth(uv + vec2(texel, -texel)));
    lit += step(compared, sunDepth(uv + vec2(-texel, -texel)));
    return lit * 0.2;
}

void main()
{
    vec4 texel = texture2D(s_lsTexture, v_texcoord0);
    if (u_lsMaterial.w > 0.0)
    {
        vec3 light = texel.rgb * u_lsMaterial.x;
        gl_FragColor = vec4(light, max(light.r, max(light.g, light.b)));
        return;
    }
    if (texel.a < 0.375 || (u_lsGroundLook.x <= 0.0 && v_texcoord1.y < u_lsGroundLook.w))
    {
        discard;
    }
    vec3 normal = normalize(v_texcoord2.xyz);
    if (normal.z < 0.0 && u_lsGroundLook.x <= 0.0)
    {
        normal = -normal;
    }
    float facing = max(0.0, dot(normal, u_lsSun.xyz));
    float bias = 0.0006 + 0.0012 * (1.0 - facing);
    float sun = facing * sunReach(v_texcoord3, bias);
    vec3 sky = mix(u_lsGround.rgb, u_lsSky.rgb, normal.y * 0.5 + 0.5);
    vec3 color = texel.rgb * (sky + u_lsSunColor.rgb * sun) * u_lsMaterial.x;
    float haze = min(1.0, u_lsMaterial.y + u_lsMaterial.z * clamp(-v_texcoord1.z, 0.0, 1.0));
    color = mix(color, u_lsHaze.rgb, haze);
    vec3 tint = u_lsTint.rgb;
    if (u_lsFill.w > 0.0)
    {
        float height = v_texcoord1.y;
        if (height < u_lsFill.x)
        {
            tint = u_lsFilled.rgb;
        }
        if (abs(height - u_lsFill.x) < u_lsFill.y)
        {
            tint = mix(tint, u_lsEdge.rgb, u_lsFill.z);
        }
    }
    color *= tint;
    float alpha = 1.0;
    if (u_lsGroundLook.x > 0.0)
    {
        color = mix(u_lsGroundBelow.rgb, color, u_lsGroundBelow.w * smoothstep(0.0, u_lsGroundLook.w, v_texcoord1.y));
        alpha = 1.0 - smoothstep(u_lsGroundLook.y, u_lsGroundLook.z, abs(v_texcoord1.x));
    }
    gl_FragColor = vec4(color, alpha);
}
