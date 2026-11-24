#include <bgfx_compute.sh>
#include "compute_rows.sh"
#include "visibility_order.sh"

BUFFER_RO(visibilityCommandData, uint, 0);
BUFFER_RO(visibilityCommandCounts, uint, 1);
BUFFER_RO(visibilityInstancesOut, vec4, 2);
BUFFER_RO(visibilityOrderedCommands, uint, 3);
BUFFER_RO(visibilityOrderBlocks, uint, 4);
BUFFER_WO(visibilityOrderMasks, uint, 5);
BUFFER_WO(visibilityOrderBlockCounts, uint, 6);

uniform vec4 u_visibilityConfig;

SHARED uint orderHalves[64];
SHARED uint orderSums[64];

NUM_THREADS(64, 1, 1)
void main()
{
    uint block = gl_WorkGroupID.y * COMPUTE_ROW_GROUPS + gl_WorkGroupID.x;
    uint thread = gl_LocalInvocationID.x;
    bool live = block < uint(u_visibilityConfig.z);
    uint begin = 0u;
    uint marked = 0u;
    uint words = 0u;
    uint maskBase = 0u;
    if (live)
    {
        uint entry = visibilityOrderBlocks[block] * 4u;
        uint command = visibilityOrderedCommands[entry];
        uint index = block - visibilityOrderedCommands[entry + 1u];
        uint local = index * ORDER_BLOCK_CANDIDATES;
        uint size = min(visibilityOrderedCommands[entry + 2u] - local, ORDER_BLOCK_CANDIDATES);
        begin = visibilityCommandData[command * 4u + 3u] + local;
        words = (size + 31u) / 32u;
        maskBase = visibilityOrderedCommands[entry + 3u] + index * ORDER_BLOCK_WORDS;
        uint end = visibilityCommandCounts[command];
        marked = end > local ? min(end - local, size) : 0u;
    }
    uint bits = 0u;
    uint visible = 0u;
    for (uint offset = 0u; offset < ORDER_THREAD_CANDIDATES; ++offset)
    {
        uint candidate = thread * ORDER_THREAD_CANDIDATES + offset;
        if (candidate < marked)
        {
            if (visibilityInstancesOut[(begin + candidate) * 5u + 4u].w > 0.5)
            {
                bits |= 1u << offset;
                visible++;
            }
        }
    }
    orderHalves[thread] = bits;
    orderSums[thread] = visible;
    barrier();
    if (thread < words)
    {
        visibilityOrderMasks[maskBase + thread] = orderHalves[thread * 2u] | (orderHalves[thread * 2u + 1u] << 16u);
    }
    for (uint stride = 32u; stride > 0u; stride >>= 1u)
    {
        if (thread < stride)
        {
            orderSums[thread] += orderSums[thread + stride];
        }
        barrier();
    }
    if (live && thread == 0u)
    {
        visibilityOrderBlockCounts[block] = orderSums[0];
    }
}
