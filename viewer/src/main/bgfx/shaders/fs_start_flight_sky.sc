$input v_texcoord0

#include <bgfx_shader.sh>

uniform vec4 u_flightSky[5];

void main()
{
    vec3 ray = u_flightSky[0].xyz
        + v_texcoord0.x * u_flightSky[0].w * u_flightSky[2].xyz
        + v_texcoord0.y * u_flightSky[1].w * u_flightSky[1].xyz;
    float up = normalize(ray).z;
    float share = smoothstep(-0.02, 0.45, up);
    gl_FragColor = vec4(mix(u_flightSky[4].rgb, u_flightSky[3].rgb, sqrt(share)), 1.0);
}
