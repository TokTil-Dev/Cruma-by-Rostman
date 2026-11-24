$input v_texcoord0

#include <bgfx_shader.sh>

SAMPLER2D(s_pickDepth, 0);
uniform vec4 u_pickPixel;

void main()
{
    float depth = texelFetch(s_pickDepth, ivec2(u_pickPixel.xy), 0).x;
    float value = floor(clamp(depth, 0.0, 1.0) * 16777215.0 + 0.5);
    float high = floor(value / 65536.0);
    float middle = floor((value - high * 65536.0) / 256.0);
    float low = value - high * 65536.0 - middle * 256.0;
    gl_FragColor = vec4(high / 255.0, middle / 255.0, low / 255.0, 1.0);
}
