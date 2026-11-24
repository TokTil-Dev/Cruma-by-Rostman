$input v_color0
#include <bgfx_shader.sh>
#include "native_shader_power.sh"
#if BGFX_SHADER_LANGUAGE_HLSL
#pragma warning(disable : 3571)
#endif
uniform vec4 u_nativePowerProbe[2];
void main()
{
    vec4 value = u_nativePowerProbe[0];
    float exponent = u_nativePowerProbe[1].x;
    if (u_nativePowerProbe[1].y == 1.0)
    {
        gl_FragColor = pow(value, vec4_splat(exponent));
        return;
    }
    gl_FragColor = vec4(nativeShaderPower(value.x, exponent), nativeShaderPower(value.y, exponent),
        nativeShaderPower(value.z, exponent), nativeShaderPower(value.w, exponent));
    if (u_nativePowerProbe[1].z == 3.0) gl_FragColor.w = 0.0;
}
