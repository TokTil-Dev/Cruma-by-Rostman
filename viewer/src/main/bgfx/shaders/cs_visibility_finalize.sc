#include <bgfx_compute.sh>
#include "compute_rows.sh"

BUFFER_RO(visibilityCommandData, uint, 0);
BUFFER_RO(visibilityCommandCounts, uint, 1);
BUFFER_RW(visibilityIndirectData, uvec4, 2);

uniform vec4 u_visibilityConfig;

NUM_THREADS(64, 1, 1)
void main()
{
    uint command = gl_GlobalInvocationID.y * COMPUTE_ROW_INVOCATIONS + gl_GlobalInvocationID.x;
    if (command < uint(u_visibilityConfig.y))
    {
        uint offset = command * 4u;
        drawIndexedIndirect(
            visibilityIndirectData,
            command,
            visibilityCommandData[offset],
            visibilityCommandCounts[command],
            visibilityCommandData[offset + 1u],
            visibilityCommandData[offset + 2u],
            visibilityCommandData[offset + 3u]
        );
    }
}
