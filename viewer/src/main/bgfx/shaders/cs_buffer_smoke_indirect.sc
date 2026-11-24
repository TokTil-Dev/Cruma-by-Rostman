#include <bgfx_compute.sh>

BUFFER_RW(smokeIndirect, uvec4, 0);
uniform vec4 u_bufferSmokeIndirect;

NUM_THREADS(64, 1, 1)
void main()
{
    uint command = gl_GlobalInvocationID.x;
    if (command < 16u)
    {
        uint phase = uint(u_bufferSmokeIndirect.x);
        uint instances = (command + phase) % 5u == 0u ? 0u : 1u;
        drawIndexedIndirect(smokeIndirect, command, 6u, instances, 3u, 1u + command * 4u, 0u);
    }
}
