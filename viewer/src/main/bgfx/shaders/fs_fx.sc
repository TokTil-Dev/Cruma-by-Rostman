$input v_texcoord0, v_texcoord1, v_texcoord2, v_texcoord3, v_texcoord4, v_color0

#include <bgfx_shader.sh>
#include "fs_fx_common.sh"

void main()
{
    gl_FragColor = fxResult(
        v_texcoord0, v_texcoord1, v_texcoord4, v_texcoord2, v_texcoord3, v_color0
    );
}
