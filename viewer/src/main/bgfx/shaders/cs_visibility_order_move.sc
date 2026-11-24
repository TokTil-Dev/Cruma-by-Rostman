#include <bgfx_compute.sh>
#include "compute_rows.sh"
#include "visibility_order.sh"

BUFFER_RO(visibilityBounds, vec4, 0);
BUFFER_RO(visibilityInstancesIn, vec4, 1);
BUFFER_RO(visibilityCommandData, uint, 2);
BUFFER_WO(visibilityCommandCounts, uint, 3);
BUFFER_WO(visibilityInstancesOut, vec4, 4);
BUFFER_RO(visibilityOrderedCommands, uint, 5);
BUFFER_RO(visibilityOrderBlocks, uint, 6);
BUFFER_RO(visibilityOrderMasks, uint, 7);
BUFFER_RO(visibilityOrderBlockCounts, uint, 8);

uniform vec4 u_visibilityConfig;

SHARED uint orderSums[64];
SHARED uint orderWords[32];
SHARED uint orderBefore[32];

void copyCandidate(uint candidate, uint slot)
{
    uint source = candidate * 5u;
    uint target = slot * 5u;
    visibilityInstancesOut[target] = visibilityInstancesIn[source];
    visibilityInstancesOut[target + 1u] = visibilityInstancesIn[source + 1u];
    visibilityInstancesOut[target + 2u] = visibilityInstancesIn[source + 2u];
    visibilityInstancesOut[target + 3u] = visibilityInstancesIn[source + 3u];
    vec4 lighting = visibilityInstancesIn[source + 4u];
    uint materialRecord = uint(visibilityBounds[candidate * 3u + 1u].w) >> 8u;
    lighting.w += 8.0 + float(materialRecord) * 16.0;
    visibilityInstancesOut[target + 4u] = lighting;
}

NUM_THREADS(64, 1, 1)
void main()
{
    uint block = gl_WorkGroupID.y * COMPUTE_ROW_GROUPS + gl_WorkGroupID.x;
    uint thread = gl_LocalInvocationID.x;
    bool live = block < uint(u_visibilityConfig.z);
    uint command = 0u;
    uint index = 0u;
    uint begin = 0u;
    uint first = 0u;
    uint words = 0u;
    uint maskBase = 0u;
    uint sumFirst = 0u;
    uint sumEnd = 0u;
    if (live)
    {
        uint entry = visibilityOrderBlocks[block] * 4u;
        command = visibilityOrderedCommands[entry];
        uint firstBlock = visibilityOrderedCommands[entry + 1u];
        uint candidates = visibilityOrderedCommands[entry + 2u];
        index = block - firstBlock;
        uint local = index * ORDER_BLOCK_CANDIDATES;
        first = visibilityCommandData[command * 4u + 3u];
        begin = first + local;
        words = (min(candidates - local, ORDER_BLOCK_CANDIDATES) + 31u) / 32u;
        maskBase = visibilityOrderedCommands[entry + 3u] + index * ORDER_BLOCK_WORDS;
        sumFirst = firstBlock;
        sumEnd = index == 0u
            ? firstBlock + (candidates + ORDER_BLOCK_CANDIDATES - 1u) / ORDER_BLOCK_CANDIDATES
            : block;
    }
    uint partial = 0u;
    for (uint counted = sumFirst + thread; counted < sumEnd; counted += 64u)
    {
        partial += visibilityOrderBlockCounts[counted];
    }
    orderSums[thread] = partial;
    if (thread < ORDER_BLOCK_WORDS)
    {
        uint word = 0u;
        if (thread < words)
        {
            word = visibilityOrderMasks[maskBase + thread];
        }
        orderWords[thread] = word;
        orderBefore[thread] = countOrderBits(word);
    }
    barrier();
    for (uint stride = 32u; stride > 0u; stride >>= 1u)
    {
        if (thread < stride)
        {
            orderSums[thread] += orderSums[thread + stride];
        }
        barrier();
    }
    for (uint step = 1u; step < ORDER_BLOCK_WORDS; step <<= 1u)
    {
        uint before = 0u;
        if (thread < ORDER_BLOCK_WORDS && thread >= step)
        {
            before = orderBefore[thread - step];
        }
        barrier();
        if (thread < ORDER_BLOCK_WORDS)
        {
            orderBefore[thread] += before;
        }
        barrier();
    }
    uint sum = orderSums[0];
    if (live && index == 0u && thread == 0u)
    {
        visibilityCommandCounts[command] = sum;
    }
    uint slotBase = first + (index == 0u ? 0u : sum);
    for (uint offset = 0u; offset < ORDER_THREAD_CANDIDATES; ++offset)
    {
        uint candidate = offset * 64u + thread;
        uint word = orderWords[candidate >> 5u];
        uint bit = candidate & 31u;
        if (((word >> bit) & 1u) != 0u)
        {
            uint rank = orderBefore[candidate >> 5u] - countOrderBits(word)
                + countOrderBits(word & ((1u << bit) - 1u));
            copyCandidate(begin + candidate, slotBase + rank);
        }
    }
}
