#include <bgfx_shader.sh>
#include "tex_rotator.sh"
#include "authored_reflection.sh"

uniform vec4 u_texRotatorProbe[3];

void main()
{
    vec2 uv = u_texRotatorProbe[0].xy;
    vec2 pivot = u_texRotatorProbe[0].zw;
    vec3 angles = u_texRotatorProbe[1].xyz;
    float kind = u_texRotatorProbe[1].w;
    vec2 turned = uv;
    if (kind < 0.5)
    {
        turned = texRotatorUv(uv, pivot, angles);
    }
    else if (kind < 1.5)
    {
        turned = texRotatorYawUv(uv, pivot, angles.y);
    }
    else if (kind < 2.5)
    {
        turned = texRotatorYawUv(texRotatorYawUv(uv, pivot, angles.y), pivot, -angles.y);
    }
    else if (kind < 3.5)
    {
        turned = animateEnvironmentUv(u_texRotatorProbe[0].xyz, u_texRotatorProbe[1].x, u_texRotatorProbe[1].y,
            u_texRotatorProbe[2], u_texRotatorProbe[0].w);
    }
    else if (kind < 4.5)
    {
        turned = animateEnvironmentUv(authoredSpecularPoint(vec3_splat(u_texRotatorProbe[0].x)),
            u_texRotatorProbe[1].x, u_texRotatorProbe[1].y, u_texRotatorProbe[2], u_texRotatorProbe[0].w);
    }
    else
    {
        vec3 reflection = fixedFunctionReflection(u_texRotatorProbe[0].xyz, u_texRotatorProbe[2].xyz);
        vec3 world = fixedFunctionWorldReflection(reflection, u_view);
        turned = kind < 5.5 ? reflection.xy
            : kind < 6.5 ? fixedFunctionSphereMap(reflection)
            : kind < 7.5 ? world.xy
            : vec2(world.z, 0.0);
    }
    vec2 scaled = clamp((turned + vec2(8.0, 8.0)) / 16.0, 0.0, 0.99999) * 255.0;
    vec2 high = floor(scaled);
    gl_FragColor = vec4(high.x / 255.0, scaled.x - high.x, high.y / 255.0, scaled.y - high.y);
}
