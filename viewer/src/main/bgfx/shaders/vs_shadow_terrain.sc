$input a_position
$output v_texcoord0

#include <bgfx_shader.sh>

uniform vec4 u_terrainModel[4];

void main()
{
    mat4 model = mtxFromCols(
        u_terrainModel[0], u_terrainModel[1], u_terrainModel[2], u_terrainModel[3]
    );
    gl_Position = mul(u_viewProj, mul(model, vec4(a_position, 1.0)));
    v_texcoord0 = vec2(0.0, 0.0);
}
