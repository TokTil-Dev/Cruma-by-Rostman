#include <bgfx_shader.sh>
#include <bgfx_compute.sh>
BUFFER_RO(u_actorLighting, uint, 11);
uniform vec4 u_actorBufferProbe;

void main()
{
    uint index = uint(gl_FragCoord.x) + uint(u_actorBufferProbe.x);
    uint value = u_actorLighting[index];
    gl_FragColor = vec4(float(value & 255u), float((value >> 8u) & 255u),
        float((value >> 16u) & 255u), float(value >> 24u)) / 255.0;
}
