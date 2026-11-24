$input a_position, a_normal, a_texcoord0
$output v_texcoord0, v_texcoord1, v_texcoord2, v_color0, v_texcoord3, v_texcoord4

#include <bgfx_shader.sh>
#define SPAWN_MODEL_PASSES
#include "spawn_model_lit_vertex.sh"
