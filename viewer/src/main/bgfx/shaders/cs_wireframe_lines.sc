#include <bgfx_compute.sh>
#include "compute_rows.sh"

BUFFER_RO(wireTriangles, uint, 0);
BUFFER_RW(wireLines, uint, 1);

uniform vec4 u_wireRange;

NUM_THREADS(64, 1, 1)
void main()
{
    uint face = gl_GlobalInvocationID.y * COMPUTE_ROW_INVOCATIONS + gl_GlobalInvocationID.x;
    if (face < uint(u_wireRange.z))
    {
        uint first = uint(u_wireRange.x) * 4096u + uint(u_wireRange.y) + face * 3u;
        uint a = wireTriangles[first];
        uint b = wireTriangles[first + 1u];
        uint c = wireTriangles[first + 2u];
        uint edge = first * 2u;
        wireLines[edge] = a;
        wireLines[edge + 1u] = b;
        wireLines[edge + 2u] = b;
        wireLines[edge + 3u] = c;
        wireLines[edge + 4u] = c;
        wireLines[edge + 5u] = a;
    }
}
