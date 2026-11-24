$input v_texcoord0

#include <bgfx_shader.sh>

SAMPLER2DARRAY(s_shadowDiffuse, 0);
SAMPLER2DARRAY(s_shadowOpacity, 1);
uniform vec4 u_shadowMaterial[4];

void main()
{
    vec2 dx = dFdx(v_texcoord0);
    vec2 dy = dFdy(v_texcoord0);
    float minification = clamp(log2(max(max(length(dx), length(dy)) * 512.0, 1.0)), 0.0, 1.0);
    float alpha = texture2DArray(
        s_shadowDiffuse, vec3(v_texcoord0, u_shadowMaterial[0].z)
    ).a * u_shadowMaterial[3].x;
    if (u_shadowMaterial[0].w < -0.5)
    {
        alpha = (-u_shadowMaterial[0].w - 1.0) * u_shadowMaterial[3].x;
    }
    else if (mod(u_shadowMaterial[0].w, 2.0) > 0.5)
    {
        alpha *= texture2DArray(
            s_shadowOpacity,
            vec3(v_texcoord0, floor(u_shadowMaterial[0].w * 0.5))
        ).a;
    }
    float cutoff = u_shadowMaterial[0].x;
    if (u_shadowMaterial[3].y > 0.5)
    {
        cutoff = mix(cutoff, max(cutoff, 0.5), minification);
    }
    if (alpha < cutoff)
    {
        discard;
    }
    gl_FragColor = vec4(1.0, 1.0, 1.0, 1.0);
}
