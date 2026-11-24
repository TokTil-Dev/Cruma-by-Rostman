#include <bgfx_shader.sh>
#include <bgfx_compute.sh>
BUFFER_RO(u_vec4Buffer, vec4, 11);
uniform vec4 u_actorBufferProbe;

void main()
{
    uint index = uint(gl_FragCoord.x) + uint(u_actorBufferProbe.x);
    vec4 element = u_vec4Buffer[index >> 2u];
    uint part = index & 3u;
    float chosen = element.x;
    if (part == 1u)
    {
        chosen = element.y;
    }
    else if (part == 2u)
    {
        chosen = element.z;
    }
    else if (part == 3u)
    {
        chosen = element.w;
    }
    uint value = floatBitsToUint(chosen);
    gl_FragColor = vec4(float(value & 255u), float((value >> 8u) & 255u),
        float((value >> 16u) & 255u), float(value >> 24u)) / 255.0;
}
