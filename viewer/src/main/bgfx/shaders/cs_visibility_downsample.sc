#include <bgfx_compute.sh>

IMAGE2D_RO(s_visibilityDepthIn, r32f, 0);
IMAGE2D_WO(s_visibilityDepthOut, r32f, 1);

uniform vec4 u_visibilityPyramid;

NUM_THREADS(8, 8, 1)
void main()
{
    ivec2 destination = ivec2(gl_GlobalInvocationID.xy);
    ivec2 sourceExtent = ivec2(u_visibilityPyramid.xy);
    ivec2 destinationExtent = ivec2(u_visibilityPyramid.zw);
    if (all(lessThan(destination, destinationExtent)))
    {
        ivec2 sourceCoordinate = destination * 2;
        ivec2 sourceMaximum = sourceExtent - ivec2(1, 1);
        ivec2 p0 = min(sourceCoordinate, sourceMaximum);
        ivec2 p1 = min(sourceCoordinate + ivec2(1, 0), sourceMaximum);
        ivec2 p2 = min(sourceCoordinate + ivec2(0, 1), sourceMaximum);
        ivec2 p3 = min(sourceCoordinate + ivec2(1, 1), sourceMaximum);
        vec4 depths = vec4(
            imageLoad(s_visibilityDepthIn, p0).x,
            imageLoad(s_visibilityDepthIn, p1).x,
            imageLoad(s_visibilityDepthIn, p2).x,
            imageLoad(s_visibilityDepthIn, p3).x
        );
        float maximumDepth = max(max(depths.x, depths.y), max(depths.z, depths.w));
        if (destination.x == destinationExtent.x - 1)
        {
            maximumDepth = max(
                maximumDepth,
                imageLoad(s_visibilityDepthIn, ivec2(sourceExtent.x - 1, p0.y)).x
            );
            maximumDepth = max(
                maximumDepth,
                imageLoad(s_visibilityDepthIn, ivec2(sourceExtent.x - 1, p2.y)).x
            );
        }
        if (destination.y == destinationExtent.y - 1)
        {
            maximumDepth = max(
                maximumDepth,
                imageLoad(s_visibilityDepthIn, ivec2(p0.x, sourceExtent.y - 1)).x
            );
            maximumDepth = max(
                maximumDepth,
                imageLoad(s_visibilityDepthIn, ivec2(p1.x, sourceExtent.y - 1)).x
            );
        }
        if (all(equal(destination, destinationExtent - ivec2(1, 1))))
        {
            maximumDepth = max(
                maximumDepth,
                imageLoad(s_visibilityDepthIn, sourceExtent - ivec2(1, 1)).x
            );
        }
        imageStore(s_visibilityDepthOut, destination, vec4(maximumDepth, 0.0, 0.0, 1.0));
    }
}
