$input v_texcoord0

#include <bgfx_shader.sh>

SAMPLER2D(s_postSource, 0);
uniform vec4 u_postParams[4];

void main()
{
    vec3 color = texture2D(s_postSource, v_texcoord0 * u_postParams[0].zw).rgb;
    float share = u_postParams[1].y;
    float keepsChroma = u_postParams[1].z;
    float mode = u_postParams[1].w;
    vec3 first = u_postParams[2].xyz;
    vec3 second = u_postParams[3].xyz;
    if (u_postParams[2].w > 0.5)
    {
        vec3 ycc = vec3(
            dot(color, vec3(0.29900, 0.58700, 0.11400)),
            dot(color, vec3(-0.168736, -0.331264, 0.5000)),
            dot(color, vec3(0.5000, -0.418688, -0.081312))
        );
        vec3 wanted1 = ycc;
        vec3 wanted2 = ycc;
        wanted1.x *= first.x;
        wanted2.x *= second.x;
        if (keepsChroma < 0.5)
        {
            wanted1.yz = first.yz;
            wanted2.yz = second.yz;
        }
        if (abs(mode - 1.0) < 0.5)
        {
            wanted1 = ycc;
        }
        else if (abs(mode - 2.0) < 0.5)
        {
            wanted2 = ycc;
        }
        ycc = mix(wanted1, wanted2, share);
        color = vec3_splat(ycc.x) + vec3(0.0, -0.34414, 1.77200) * ycc.y + vec3(1.40200, -0.71414, 0.0) * ycc.z;
    }
    gl_FragColor = vec4(clamp(color, 0.0, 1.0), u_postParams[1].x);
}
