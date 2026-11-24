#include <bgfx_compute.sh>
#include "compute_rows.sh"

BUFFER_RO(copySource, uint, 0);
BUFFER_RW(copyTarget, uint, 1);

uniform vec4 u_copyRange;

NUM_THREADS(64, 1, 1)
void main()
{
    uint index = gl_GlobalInvocationID.y * COMPUTE_ROW_INVOCATIONS + gl_GlobalInvocationID.x;
    if (index < uint(u_copyRange.z))
    {
        uint word = uint(u_copyRange.x) * 4096u + uint(u_copyRange.y) + index;
        copyTarget[word] = copySource[word];
    }
}
