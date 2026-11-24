$input a_position, a_normal, a_tangent, a_texcoord0, a_texcoord1, a_texcoord2, a_texcoord6, a_texcoord7, a_color1, a_color0, i_data0, i_data1, i_data2, i_data3, i_data4
$output v_texcoord0, v_texcoord1, v_texcoord2, v_texcoord3, v_texcoord4, v_texcoord5, v_texcoord6, v_texcoord7, v_color0, v_tangent, v_waterEye, v_waterLight, v_softDepth, v_actorAmbientData, v_nativeDiffuse, v_nativeSunColor, v_nativeSunNormal, v_nativeSunDirection, v_nativeLightmap0, v_nativeLightmap1, v_nativeLightmap2

#define NATIVE_WATER_LIGHTING 1
#define MESH_FRAME_BLEND 1
#include "scene_entity_vertex.sh"
