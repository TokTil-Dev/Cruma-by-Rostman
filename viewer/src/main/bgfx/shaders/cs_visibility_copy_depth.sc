#include <bgfx_compute.sh>

SAMPLER2D(s_visibilityDepth, 0);
IMAGE2D_WO(visibilityDepthOut, r32f, 1);

uniform vec4 u_visibilityPyramid;

NUM_THREADS(8, 8, 1)
void main()
{
    ivec2 coordinate = ivec2(gl_GlobalInvocationID.xy);
    ivec2 extent = ivec2(u_visibilityPyramid.xy);
    if (all(lessThan(coordinate, extent)))
    {
        float depth = texelFetch(s_visibilityDepth, coordinate, 0).x;
        imageStore(visibilityDepthOut, coordinate, vec4(depth, 0.0, 0.0, 1.0));
    }
}
