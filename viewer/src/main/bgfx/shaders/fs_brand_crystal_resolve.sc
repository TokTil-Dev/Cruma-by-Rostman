$input v_texcoord0

#include <bgfx_shader.sh>

SAMPLER2D(s_bcScene, 0);

uniform vec4 u_bcEmpty;

void main()
{
    ivec2 corner = ivec2(gl_FragCoord.xy) * 4;
    vec4 scene = vec4_splat(0.0);
    for (int row = 0; row < 4; row++)
    {
        for (int column = 0; column < 4; column++)
        {
            scene += texelFetch(s_bcScene, corner + ivec2(column, row), 0);
        }
    }
    scene *= 0.0625;
    float alpha = max(scene.a, max(scene.r, max(scene.g, scene.b)));
    gl_FragColor = alpha > 0.0 ? vec4(scene.rgb / alpha, alpha) : vec4(u_bcEmpty.xyz, 0.0);
}
