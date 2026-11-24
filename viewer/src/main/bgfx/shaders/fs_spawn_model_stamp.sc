$input v_texcoord0

#include <bgfx_shader.sh>

SAMPLER2D(s_spawnModelStampDepth, 0);

void main()
{
    float depth = texelFetch(s_spawnModelStampDepth, ivec2(gl_FragCoord.xy), 0).x;
    if (depth >= 1.0)
    {
        discard;
    }
    gl_FragDepth = depth;
    gl_FragColor = vec4_splat(1.0);
}
