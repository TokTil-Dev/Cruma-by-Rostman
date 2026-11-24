$input v_texcoord0, v_texcoord1

#include <bgfx_shader.sh>

uniform vec4 u_skyParams[6];

void main()
{
    vec2 screen = v_texcoord1.xy;
    vec3 ray = normalize(
        u_skyParams[0].xyz
        + screen.x * u_skyParams[1].xyz * u_skyParams[1].w * u_skyParams[2].w
        + screen.y * u_skyParams[2].xyz * u_skyParams[1].w
    );
    if (u_skyParams[0].w > 0.5)
    {
        vec3 normal = normalize(u_skyParams[5].xyz);
        ray = ray - 2.0 * dot(ray, normal) * normal;
    }
    float height = clamp(ray.z * 0.5 + 0.5, 0.0, 1.0);
    float blend = smoothstep(0.35, 0.95, height);
    gl_FragColor = vec4(mix(u_skyParams[4].rgb, u_skyParams[3].rgb, blend), 1.0);
}
