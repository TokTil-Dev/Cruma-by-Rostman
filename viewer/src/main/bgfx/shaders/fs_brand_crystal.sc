$input v_texcoord0, v_texcoord1, v_texcoord2

#include <bgfx_shader.sh>

SAMPLER2D(s_bcBody, 0);
SAMPLER2D(s_bcSheen, 1);

uniform vec4 u_bcSheenPivot;
uniform vec4 u_bcLight;
uniform vec4 u_bcViewer;

void main()
{
    vec3 normal = normalize(v_texcoord1.xyz);
    if (dot(normal, u_bcViewer.xyz) <= 0.0)
    {
        discard;
    }
    vec4 body = texture2D(s_bcBody, v_texcoord0);
    float lit = u_bcLight.w + (1.0 - u_bcLight.w) * max(dot(normal, u_bcLight.xyz), 0.0);
    vec3 sheen = texture2D(s_bcSheen, v_texcoord2.xy).rgb * step(0.5, u_bcSheenPivot.z);
    gl_FragColor = vec4(body.rgb * lit * body.a + sheen, body.a);
}
